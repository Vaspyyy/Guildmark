package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.math.hypot

/**
 * The House's planner (living-world/trader/design.md, Part 5; build part G): where an idle carter is sent. It is the model's agent with
 * `speculate` off and the Tally's Routes page ([Routes]), the same greedy rule: for each town the House's Word shows, the basket of the House's
 * town's goods that the tickets there pay best for, within a wagon's [SLOTS]; and the towns ranked by the profit a day of the round trip
 * (loading an hour, the road there at the caravans' pace, the hour's stand, the road home).
 *
 * *What it knows.* The tickets the House's Word shows: in the House's own town (a carter never takes a ticket home), in the towns where its
 * owner keeps a stall or a Store, as they are now (the Word is live there); elsewhere within the board's reach as they were [LAG] hours ago,
 * the latest snapshot the Word holds of that age, as the model's carters have it. Only tickets the House does not hold already, still open
 * when the wagon would get in, and no more of them than the House may still hold ([Hauls]' limit of eight).
 *
 * Nothing is booked, bought or changed here ([Houses] does that, with what the board says when it does); [rank] is arithmetic on copies.
 */
object HousePlanner {
    /** The slots a wagon carries (a pack of the model's). */
    const val SLOTS = 24

    /** How old the news of a town that is not the House's own, nor a stall's or a Store's, is (hours). */
    const val LAG = 36.0

    /** The tickets a House holds booked at once (Hauls' own limit for a House). */
    const val HELD = 8

    /** The hours a ticket must still have to run when the wagon gets in. */
    private const val MARGIN = 2

    /** The least a trip must make over its goods (Marks). */
    const val LEAST = 1.0

    private const val HOUR = 1000L
    private const val DAY = 24000.0

    /** Some of a ticket's units a trip is to fill: [each] Marks a unit, as the news has it. */
    class Serve(val ticket: Int, val good: Good, val units: Int, val each: Double)

    /** A trip the planner would send a carter on: to the capital [dest] ([name]), [distance] blocks off, its goods costing [cost], paying [pay], the deposits [deposit], at [perDay] a day of the round trip. */
    class Plan(
        val dest: Long,
        val name: String,
        val distance: Double,
        val cost: Int,
        val pay: Double,
        val deposit: Int,
        val days: Double,
        val serves: List<Serve>,
        val lines: List<Routes.Line>,
    ) {
        val profit: Double get() = pay - cost
        val perDay: Double get() = if (days <= 0.0) 0.0 else profit / days
    }

    /** A town the planner may send a carter to: its Word as the House has it, and whether that is live. */
    class Known(val capital: Long, val name: String, val sight: Sight, val live: Boolean)

    /** Why the last planning sent nobody, as a key under `gui.guildmark.trade.house.idle.` ("" if it found something). */
    @Volatile
    var lastWhy = ""

    /**
     * The trips an idle carter of [h] could be sent on now (clock time [t]), best first, from the market [entry] of the House's town, none
     * costing more than [budget] Marks with its deposits. Empty, with [lastWhy] saying why, if there is none.
     */
    fun plans(level: ServerLevel, h: HouseRec, entry: Markets.Entry, t: Long, budget: Int): List<Plan> {
        lastWhy = ""
        if (budget <= 0) {
            lastWhy = "float"
            return listOf()
        }
        val held = heldBy(level, h)
        if (held >= HELD) {
            lastWhy = "held"
            return listOf()
        }
        val known = known(level, h, t)
        if (known.isEmpty()) {
            lastWhy = "word"
            return listOf()
        }
        val mine = mineOf(level, h)
        val m = entry.market
        val here = Routes.Here { g -> Shelf.of(m, entry.town, g) }
        val markets = Markets.get(level)
        val out = rank(here, BlockPos.of(h.capital), known, t, HELD - held, mine, budget) { markets[it] != null }
        if (out.isEmpty()) lastWhy = "tickets"
        return out
    }

    /**
     * The towns in [known] ranked by what a day of the round trip from [origin] pays at clock time [t], best first, each with its basket for
     * the tickets there (at most [room] of them, none in [mine]) that a wagon's slots hold and [budget] buys; only those making [LEAST] and
     * whose town [open] says has a market to get in at.
     */
    fun rank(here: Routes.Here, origin: BlockPos, known: List<Known>, t: Long, room: Int, mine: Set<Int>, budget: Int, open: (BlockPos) -> Boolean): List<Plan> {
        val out = ArrayList<Plan>()
        for (k in known) {
            val pos = BlockPos.of(k.capital)
            if (pos == origin || !open(pos)) continue
            val distance = hypot((pos.x - origin.x).toDouble(), (pos.z - origin.z).toDouble())
            if (distance < TradeConfig.haulMin()) continue
            val road = Caravans.ticksFor(distance)
            val arrive = t + HOUR + road
            // The tickets that will still be open when the wagon gets in and that the House does not hold, the best paying first, as many as it may hold
            val tickets = k.sight.tickets
                .filter { it.id !in mine && it.units > 0 && it.due * HOUR >= arrive + MARGIN * HOUR }
                .sortedByDescending { it.each }
                .take(room)
            if (tickets.isEmpty()) continue
            val dest = Routes.Dest(pos, k.name, k.sight.withTickets(tickets), 0.0)
            val days = (HOUR + 2 * road + STAND) / DAY
            var best: Plan? = null
            for (slots in intArrayOf(SLOTS, SLOTS / 2, SLOTS / 4)) {
                val leg = Routes.leg(here, origin, dest, Routes.Terms(slots, tickets = true, dealer = 0.0, haulMin = TradeConfig.haulMin(), roomDays = 0, may = { true }))
                val plan = planOf(k, distance, days, leg) ?: continue
                if (plan.cost + plan.deposit > budget) continue
                best = plan
                break
            }
            if (best != null && best.profit >= LEAST) out.add(best)
        }
        return out.sortedByDescending { it.perDay }
    }

    private const val STAND = 1000L

    /** The plan [leg] to [k] makes: only what it takes to the tickets (a wagon is not sent for the stalls' prices), or null if it takes nothing. */
    private fun planOf(k: Known, distance: Double, days: Double, leg: Routes.Leg): Plan? {
        val serves = ArrayList<Serve>()
        var deposit = 0
        for (line in leg.lines) for (s in line.serves) {
            if (s.units <= 0) continue
            serves.add(Serve(s.ticket.id, line.good, s.units, s.ticket.each))
            deposit += HaulRules.deposit(TradeConfig.haulDeposit(), s.ticket.each, s.units)
        }
        if (serves.isEmpty()) return null
        return Plan(k.capital, k.name, distance, leg.cost, leg.pay, deposit, days, serves, leg.lines)
    }

    /** How many tickets [h]'s owner holds booked for their House now. */
    fun heldBy(level: ServerLevel, h: HouseRec): Int = HaulData.peek(level)?.let { Hauls.held(it, h.owner, house = true) } ?: 0

    /** The tickets [h]'s owner holds booked for their House. */
    private fun mineOf(level: ServerLevel, h: HouseRec): Set<Int> {
        val data = HaulData.peek(level) ?: return setOf()
        val out = HashSet<Int>()
        for (book in data.all()) for (ticket in book.tickets) if (ticket.bookings.any { it.taker == h.owner && it.house }) out.add(ticket.id)
        return out
    }

    /**
     * The towns [h]'s Word shows at clock time [t]: the House's own town and its owner's stall and Store towns as they are now, and every other
     * capital the Word has a ring for within the board's reach as it was [LAG] hours ago (the oldest snapshot there is, if none is that old yet).
     */
    fun known(level: ServerLevel, h: HouseRec, t: Long): List<Known> {
        val out = LinkedHashMap<Long, Known>()
        val markets = Markets.get(level)
        val home = BlockPos.of(h.capital)
        val live = LinkedHashSet<Long>()
        live.add(h.capital)
        peekBooks(level)?.get(h.owner)?.stores?.let { live.addAll(it) }
        if (OwnStalls.enabled()) for (hall in OwnStalls.get(level).all()) if (hall.of(h.owner) >= 0 || hall.pitches.containsKey(h.owner)) live.add(hall.capital)
        for (c in live) {
            val entry = markets[BlockPos.of(c)] ?: continue
            out[c] = Known(c, Houses.placeOf(level, c), Sight.of(level, entry, t, Word.tickets(level, entry.capital)), true)
        }
        if (!TradeConfig.word()) return out.values.toList()
        val rings = TradeWord.peek(level)?.rings ?: return out.values.toList()
        val cutoff = t - (LAG * HOUR).toLong()
        for (ring in rings.values) {
            if (ring.capital in out || ring.sights.isEmpty()) continue
            val pos = BlockPos.of(ring.capital)
            if (hypot((pos.x - home.x).toDouble(), (pos.z - home.z).toDouble()) > Word.REACH) continue
            val sight = ring.sights.lastOrNull { it.tick <= cutoff } ?: ring.sights.first()
            out[ring.capital] = Known(ring.capital, ring.name.ifEmpty { Houses.placeOf(level, ring.capital) }, sight, false)
        }
        return out.values.toList()
    }

    /** A plan told in one line for the commands: the town, the goods, what it costs and pays, and its rate. */
    fun line(p: Plan): String {
        val goods = p.lines.joinToString(", ") { "${it.units} ${it.good.id}" }
        return "%s (%d blocks): %s; cost %d, deposits %d, pays %.1f, %.2f a day over %.2f days".format(
            java.util.Locale.ROOT, p.name.ifEmpty { "?" }, p.distance.toInt(), goods, p.cost, p.deposit, p.pay, p.perDay, p.days,
        ) + " (" + p.serves.size + " ticket" + (if (p.serves.size == 1) "" else "s") + ")"
    }
}
