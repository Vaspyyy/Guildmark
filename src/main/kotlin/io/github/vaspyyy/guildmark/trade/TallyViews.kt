package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.network.TallyAsk
import io.github.vaspyyy.guildmark.network.TallyGood
import io.github.vaspyyy.guildmark.network.TallyHeld
import io.github.vaspyyy.guildmark.network.TallyLeg
import io.github.vaspyyy.guildmark.network.TallyLine
import io.github.vaspyyy.guildmark.network.TallyNeed
import io.github.vaspyyy.guildmark.network.TallyNote
import io.github.vaspyyy.guildmark.network.TallyParcel
import io.github.vaspyyy.guildmark.network.TallyRoutes
import io.github.vaspyyy.guildmark.network.TallyServe
import io.github.vaspyyy.guildmark.network.TallyStanding
import io.github.vaspyyy.guildmark.network.TallyTicket
import io.github.vaspyyy.guildmark.network.TallyTown
import io.github.vaspyyy.guildmark.network.TallyView
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the Tally's five pages show a trader (living-world/trader/design.md, Part 1), worked out on the server from their book and the world as
 * they stand, and sent as one [TallyView]. Nothing here changes anything: the screen is told, and asks again every few seconds while it is open.
 *
 * Every figure is from what is really there: a town's goods as its sight says, with the age of the sight beside it; a route's basket from [Routes]
 * on those sights and on the market the trader stands in; the tickets they hold, the goods they carry and the days their hauled goods have left from
 * their own records.
 */
internal object TallyViews {
    /** The most ways the Routes page lists. */
    private const val LEGS = 6

    /** The slots of a pack that hold goods (the rest of an inventory is armour and the off hand). */
    private const val PACK = 36

    fun build(level: ServerLevel, player: ServerPlayer): TallyView {
        val now = level.overworldClockTime
        val here = Furnish.capitalAt(level, player.blockPosition())?.center
        val hereKey = here?.asLong() ?: TallyRoutes.NONE
        val word = TradeConfig.word()
        val towns = known(level, player, here)
        val rows = if (here != null && word) Word.view(level, here, null).associateBy { it.capital.asLong() } else mapOf()
        val left = Tally.reportsLeft(level, player.uuid)
        val shown = towns.map { town(level, it, hereKey, rows[it.capital], left, now) }
        val (held, needs) = held(level, player, towns)
        val place = if (here == null) "" else nameOf(level, hereKey, towns)
        return TallyView(now, hereKey, place, word, left, shown, routes(level, player, here, towns), held, needs, parcels(level, player, here, towns), standing(level, player, here))
    }

    /** The name of the capital whose heart is [key]: as the trader's book has it, else as the Word or the roads and orders have it; "" if nobody has found it yet. */
    private fun nameOf(level: ServerLevel, key: Long, towns: List<Seen>): String =
        towns.firstOrNull { it.capital == key }?.name?.ifEmpty { null } ?: Word.nameOf(level, key).ifEmpty { Word.found(level, key) }

    /** The sights on the trader's pages: the town they stand in as it is this moment (not kept), then their book's, the newest first. */
    private fun known(level: ServerLevel, player: ServerPlayer, here: BlockPos?): List<Seen> {
        val saved = Tally.seen(level, player.uuid)
        val live = here?.let { Tally.look(level, it, Seen.VISIT, false) } ?: return saved
        return listOf(live) + saved.filter { it.capital != live.capital }
    }

    private fun town(level: ServerLevel, s: Seen, hereKey: Long, row: Word.Row?, left: Int, now: Long): TallyTown {
        val sight = s.sight
        val here = s.capital == hereKey
        val live = here || s.from == Seen.LIVE && now - sight.tick <= 2 * Word.HOUR
        val report = !here && row != null && left > 0 && sight.tick - row.tick >= Tally.FRESHER
        val goods = Sight.GOODS.map { g ->
            val usual = sight.usual(g)
            TallyGood(g.id, sight.stock(g).roundToInt(), sight.target(g).roundToInt(), sight.band(g).ordinal, if (usual > 1e-9) (Math.round(sight.price(g) / usual * 100.0) / 100.0).toFloat() else 1f)
        }
        val notes = sight.events.map { TallyNote(it.kind, it.good, it.until * Word.HOUR) }
        val tickets = sight.tickets.map { TallyTicket(it.id, it.good.id, it.units, it.pay, it.shop.id, it.kind.id, it.due * Word.HOUR) }
        return TallyTown(
            s.capital, s.name.ifEmpty { nameOf(level, s.capital, listOf()) }, sight.tick, s.copied, s.from, live,
            sight.road?.stage ?: -1, sight.road?.side ?: "any", row?.tick ?: -1L, report, goods, notes, tickets,
        )
    }

    // ---- Routes ----

    /** Where the Routes page starts from, and what it buys there. */
    private class Start(val origin: Seen, val live: Boolean, val here: Routes.Here)

    /** The capital the page's ways start from: the one the trader stands in (live) or, away from every capital, the one of their book nearest them (as they last saw it); null if they have none. */
    private fun start(level: ServerLevel, player: ServerPlayer, here: BlockPos?, towns: List<Seen>): Start? {
        if (here != null) {
            val entry = Markets.find(level, here.asLong())
            val seen = towns.firstOrNull { it.capital == here.asLong() }
            if (entry != null && seen != null) return Start(seen, true) { good -> Shelf.of(entry.market, entry.town, good) }
        }
        val near = towns.minByOrNull { hypot((BlockPos.of(it.capital).x - player.x), (BlockPos.of(it.capital).z - player.z)) } ?: return null
        return Start(near, false) { good -> Shelf.of(near.sight, good) }
    }

    /** The ways from [start] to the other towns of the book, best an hour first, for the slots free in [player]'s pack and carriers. */
    private fun ranked(level: ServerLevel, player: ServerPlayer, start: Start, towns: List<Seen>): List<Routes.Leg> {
        val dests = towns.filter { it.capital != start.origin.capital }.map {
            val at = BlockPos.of(it.capital)
            Routes.Dest(at, it.name.ifEmpty { nameOf(level, it.capital, towns) }, it.sight, Suppliers.share(level, at, player.uuid))
        }
        if (dests.isEmpty()) return listOf()
        val terms = Routes.Terms(slots(player), may = { kind -> Rungs.may(player, kind) })
        return Routes.rank(start.here, BlockPos.of(start.origin.capital), dests, terms)
    }

    private fun routes(level: ServerLevel, player: ServerPlayer, here: BlockPos?, towns: List<Seen>): TallyRoutes {
        val start = start(level, player, here, towns) ?: return TallyRoutes.EMPTY
        val legs = ranked(level, player, start, towns).take(LEGS).map { leg ->
            TallyLeg(
                leg.to.capital.asLong(), leg.to.name, leg.distance.roundToInt(), leg.hours.toFloat(), leg.cost, leg.pay.roundToInt(), leg.perHour.toFloat(), leg.to.sight.tick,
                leg.lines.map { l -> TallyLine(l.good.id, l.units, l.cost, l.pay.roundToInt(), l.slots, l.serves.map { TallyServe(it.ticket.id, it.units) }) },
            )
        }
        val name = start.origin.name.ifEmpty { nameOf(level, start.origin.capital, towns) }
        return TallyRoutes(start.origin.capital, name, start.live, start.origin.sight.tick, slots(player), TallyLinks.HAULS && TradeConfig.hauls(), legs)
    }

    /**
     * The route the Routes page offers to the capital whose key is [about], as it works out from the book now: what a booking from the page books
     * (the tickets its basket fills). Null if the page offers none to it.
     */
    fun leg(level: ServerLevel, player: ServerPlayer, about: Long): Routes.Leg? {
        val here = Furnish.capitalAt(level, player.blockPosition())?.center
        val towns = known(level, player, here)
        val start = start(level, player, here, towns) ?: return null
        return ranked(level, player, start, towns).take(LEGS).firstOrNull { it.to.capital.asLong() == about }
    }

    /** The slots free in the trader's pack and in every carrier of theirs within reach. */
    private fun slots(player: ServerPlayer): Int {
        var free = 0
        for (c in Carriers.holdings(player)) {
            val size = if (c === player.inventory) PACK else c.containerSize
            for (i in 0 until size) if (c.getItem(i).isEmpty) free++
        }
        return free
    }

    /** How many units of [good] the trader carries, in their pack and in every carrier of theirs within reach. */
    private fun carried(player: ServerPlayer, good: Good): Int {
        val item = good.item ?: return 0
        var n = 0
        for (c in Carriers.holdings(player)) {
            val size = if (c === player.inventory) PACK else c.containerSize
            for (i in 0 until size) {
                val stack = c.getItem(i)
                if (stack.item === item) n += stack.count
            }
        }
        return n
    }

    // ---- Held ----

    private fun held(level: ServerLevel, player: ServerPlayer, towns: List<Seen>): Pair<List<TallyHeld>, List<TallyNeed>> {
        val hauls = TallyLinks.held(level, player)
        if (hauls.isEmpty()) return listOf<TallyHeld>() to listOf<TallyNeed>()
        fun place(capital: BlockPos): String = nameOf(level, capital.asLong(), towns)
        val held = hauls.map { h ->
            TallyHeld(h.id, h.capital.asLong(), place(h.capital), h.shop.id, h.good.id, h.units, h.pay, h.kind.id, h.due * Word.HOUR, carried(player, h.good))
        }
        val needs = hauls.groupBy { it.good }.mapNotNull { (good, list) ->
            val need = list.sumOf { it.units } - carried(player, good)
            if (need <= 0) return@mapNotNull null
            val cheapest = towns.filter { it.sight.stock(good) >= 1.0 }.minByOrNull { it.sight.price(good) }
            if (cheapest == null) TallyNeed(good.id, need, TallyRoutes.NONE, "", 0f, 0, 0L)
            else TallyNeed(
                good.id, need, cheapest.capital, cheapest.name.ifEmpty { nameOf(level, cheapest.capital, towns) },
                (Math.round(cheapest.sight.price(good) * 100.0) / 100.0).toFloat(), cheapest.sight.stock(good).toInt(), cheapest.sight.tick,
            )
        }
        return held to needs
    }

    // ---- Ledger ----

    private fun parcels(level: ServerLevel, player: ServerPlayer, here: BlockPos?, towns: List<Seen>): List<TallyParcel> {
        val today = Days.dayOf(level.overworldClockTime)
        val ref = here ?: player.blockPosition()
        val room = HashMap<Good, Int>()
        val out = ArrayList<TallyParcel>()
        for (h in TallyLinks.ledger(level, player)) {
            val good = h.parcel.good
            val have = room.getOrPut(good) { carried(player, good) }
            if (have <= 0) continue
            val units = min(have, h.parcel.units)
            room[good] = have - units
            val origin = h.parcel.origin
            val distance = hypot((origin.x - ref.x).toDouble(), (origin.z - ref.z).toDouble())
            val left = (h.of - (today - h.parcel.boughtAt)).toInt().coerceIn(0, h.of)
            out.add(TallyParcel(good.id, units, origin.asLong(), nameOf(level, origin.asLong(), towns), distance.roundToInt(), left, h.of, h.bonded, distance >= TradeConfig.haulMin()))
        }
        return out
    }

    // ---- Standing ----

    private fun standing(level: ServerLevel, player: ServerPlayer, here: BlockPos?): TallyStanding {
        val view = Rungs.view(player)
        val asks = view.asks.map { TallyAsk(it.key, it.have, it.need) }
        val filled = peekBooks(level)?.get(player.uuid)?.deliveries ?: 0
        var rank = -1
        var points = 0
        var next = -1
        if (here != null && Suppliers.enabled()) {
            val s = Suppliers.get(level)[here, player.uuid]
            val standing = s?.rank ?: SupplierRank.STRANGER
            rank = standing.ordinal
            points = s?.points?.toInt() ?: 0
            next = standing.next?.points ?: -1
        }
        return TallyStanding(view.rung.ordinal, asks, filled, rank, points, next)
    }
}
