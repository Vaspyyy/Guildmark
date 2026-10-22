package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The haul tickets' hours (living-world/trader/design.md, Part 2), worked out hour by hour of the markets' clock on the pattern of
 * `Orders`: a run every [TICK] server ticks takes each capital's book up to the hour it is (the last [Markets.CATCH_UP] hours at most, [STEPS]
 * of them for all the books together in one run), and everything a ticket keeps is an hour of that clock.
 *
 * Each hour of a capital's book, in this order: a booking that has bought a quarter of its goods far enough away (or is a House's, or has
 * delivered) stands, and one that has not lapses a day after it was made; a ticket whose day is up ends, the deposit of what was not
 * brought forfeit; a ticket the town no longer wants (its stock at the target) is withdrawn, its booked units standing; and at dawn (hour 0 of the
 * town's day) the capital posts a few new ones: a relief ticket for each staple it was short of yesterday, then up to the posting rate in
 * parcels and consignments, each for a good it is short of that another capital has to spare (see [HaulRules.plan]).
 *
 * Switched off (`tradeHauls`), nothing is posted, booked or paid and the days stand still against the clock, as an order's do: what was out
 * when the switch went off is out again, with the days it had left, when it comes back. A clock set back moves every hour with it.
 */
internal object HaulRun {
    /** Server ticks between runs. */
    private const val TICK = 20

    /** The most hours worked out in one run for all the books together, catching up (as many as the markets and the orders work out). */
    private const val STEPS = 48

    /** Market hours of fills and of lapses kept (two days, and a week), and of a player's last delivery kept to count the carriers at work. */
    private const val FILLS_KEPT = 48L
    private const val LAPSES_KEPT = 168L
    private const val ACTIVE_KEPT = 96L

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::settle) }
    }

    // ---- The hours ----

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % TICK != 0) return
        val level = server.overworld()
        val markets = Markets.get(level).all()
        if (markets.isEmpty()) return
        val on = TradeConfig.hauls()
        val data = (if (on) HaulData.get(level) else HaulData.peek(level)) ?: return
        val now = Markets.hourOf(level)
        var steps = STEPS
        for (entry in markets) {
            if (on) steps -= run(level, data, data.book(entry.capital.asLong()), entry, now, steps) else data[entry.capital.asLong()]?.let { hold(data, it, now) }
        }
    }

    /** While the tickets are switched off their days stand still against the clock: nothing lapses for want of a switch. */
    private fun hold(data: HaulData, book: HaulBook, now: Long) {
        if (book.hour == Long.MIN_VALUE || now == book.hour) return
        shift(book, now - book.hour)
        data.changed()
    }

    /** Moves every hour [book] keeps by [hours] (back, if negative), so its tickets keep the days they had left. */
    private fun shift(book: HaulBook, hours: Long) {
        for (t in book.tickets) {
            t.posted += hours
            t.due += hours
            for (b in t.bookings) b.at += hours
        }
        for (f in book.fills) f.hour += hours
        for (l in book.lapses) l.hour += hours
        for (k in book.relief.keys.toList()) book.relief[k] = book.relief.getValue(k) + hours
        book.hour += hours
    }

    /**
     * Works out the hours of the markets' clock [book] is behind ([now]), each in turn, [steps] of them at most (the rest wait for the next run):
     * up to [Markets.CATCH_UP] of them, the tickets' days standing still over the rest. Nothing is posted until its market is open, and the first
     * dawn after that is the first posting. The hours it worked out.
     */
    private fun run(level: ServerLevel, data: HaulData, book: HaulBook, entry: Markets.Entry, now: Long, steps: Int): Int {
        if (book.hour == Long.MIN_VALUE) {
            if (entry.market.hour == Long.MIN_VALUE) return 0
            book.hour = now
            data.changed()
            return 0
        }
        // Time set back: everything keeps the days it had left
        if (now < book.hour) {
            shift(book, now - book.hour)
            data.changed()
            return 0
        }
        if (now - book.hour > Markets.CATCH_UP) {
            shift(book, now - Markets.CATCH_UP - book.hour)
            data.changed()
        }
        var worked = 0
        while (book.hour < now && worked < steps) {
            book.hour++
            hour(level, data, book, entry, book.hour)
            data.changed()
            worked++
        }
        return worked
    }

    /** One market [hour] at [book]'s capital. */
    private fun hour(level: ServerLevel, data: HaulData, book: HaulBook, entry: Markets.Entry, hour: Long) {
        stand(level, data, book, hour)
        for (t in book.tickets.filter { it.due <= hour }) end(level, data, book, t, hour)
        withdraw(level, data, book, entry)
        if (Math.floorMod(hour, 24L) == 0L) dawn(level, data, book, entry, hour)
        book.fills.removeAll { hour - it.hour > FILLS_KEPT }
        book.lapses.removeAll { hour - it.hour > LAPSES_KEPT }
    }

    private fun random(book: HaulBook, hour: Long): Random = Random(book.capital * 0x2545F4914F6CDD1DL xor hour * 0x61C8864680B583EBL xor 0x4841554C53L)

    // ---- Bookings ----

    /** A booking that has bought a quarter of its goods far enough away stands until the ticket's day; one that has not lapses a day after it was made. */
    private fun stand(level: ServerLevel, data: HaulData, book: HaulBook, hour: Long) {
        val capital = BlockPos.of(book.capital)
        for (t in book.tickets) {
            for (b in t.bookings.toList()) {
                if (b.stands) continue
                if (b.house || b.done > 0 || Ledger.count(level, b.taker, t.good, capital) >= HaulRules.quarter(b.units)) {
                    b.stands = true
                    data.changed()
                } else if (hour - b.at >= HaulRules.BOOKING_HOURS) {
                    lapse(level, data, book, t, b, hour)
                }
            }
        }
    }

    /** [b] has stood a day without a quarter of its goods bought far enough away: the units go back to the board, the deposit is lost, and its booker is told. */
    internal fun lapse(level: ServerLevel, data: HaulData, book: HaulBook, t: Ticket, b: Booking, hour: Long) {
        val lost = (b.deposit - b.repaid).coerceAtLeast(0)
        val owing = b.left
        Hauls.letGo(level, data, t, b, refund = false)
        book.lapses.add(HaulLapse(b.taker, t.shop, hour))
        level.server.playerList.getPlayer(b.taker)?.sendSystemMessage(
            Component.translatable(
                "message.guildmark.trade.haul.lapsed.booking", owing, Component.translatable(t.good.key + ".many"), Orders.shopName(t.shop),
                Suppliers.place(level, BlockPos.of(book.capital)), TradeConfig.haulMin(), Orders.marks(lost),
            ).withStyle(ChatFormatting.GRAY),
        )
    }

    /** [t]'s day is up: whoever still owes units forfeits the deposit held for them and is told, and the ticket goes. */
    internal fun end(level: ServerLevel, data: HaulData, book: HaulBook, t: Ticket, hour: Long) {
        val rest = t.left
        for (b in t.bookings.toList()) {
            if (b.left <= 0 || b.house) continue
            book.lapses.add(HaulLapse(b.taker, t.shop, hour))
            level.server.playerList.getPlayer(b.taker)?.sendSystemMessage(
                Component.translatable(
                    "message.guildmark.trade.haul.lapsed.ticket", t.units, Component.translatable(t.good.key + ".many"), Orders.shopName(t.shop),
                    Suppliers.place(level, BlockPos.of(book.capital)), b.left, Orders.marks((b.deposit - b.repaid).coerceAtLeast(0)),
                ).withStyle(ChatFormatting.GRAY),
            )
        }
        book.tickets.remove(t)
        data.changed()
        if (rest > 0) Hauls.lapsed(level, book, t)
    }

    /** A ticket for a good the town now holds all it likes to keep of is withdrawn; units already booked still stand, and the ticket shrinks to them. A fixed ticket (posted by hand or for a festival) stays until its day. */
    private fun withdraw(level: ServerLevel, data: HaulData, book: HaulBook, entry: Markets.Entry) {
        for (t in book.tickets.toList()) {
            if (t.fixed || entry.market.stock(t.good) < entry.town.target(t.good)) continue
            val booked = t.booked
            if (booked <= 0) {
                book.tickets.remove(t)
                data.changed()
                Hauls.withdrawn(level, book, t)
            } else if (t.done + booked < t.units) {
                t.units = t.done + booked
                data.changed()
                Hauls.withdrawn(level, book, t)
            }
        }
    }

    // ---- Posting ----

    /** The tickets a capital posts at [hour], the first of the town's day: relief for the staples it ran short of, then the day's parcels and consignments. */
    private fun dawn(level: ServerLevel, data: HaulData, book: HaulBook, entry: Markets.Entry, hour: Long) {
        val markets = Markets.get(level).all()
        val random = random(book, hour)
        val out = HashSet<Good>()
        for (t in book.tickets) out.add(t.good)
        val day = Math.floorDiv(hour + 4, 24L)
        val today = book.relief.filterValues { Math.floorDiv(it + 4, 24L) == day }.keys.mapNotNull { Good.byId(it) }
        var room = HaulRules.OPEN_MOST - book.tickets.size
        for (p in HaulRules.relief(entry, markets, out, today)) {
            if (room <= 0) break
            post(level, data, book, entry, p, hour, random)
            book.relief[p.good.id] = hour
            out.add(p.good)
            room--
        }
        val count = min(HaulRules.count(rate(level, data), random.nextDouble()), room)
        if (count > 0) for (p in HaulRules.plan(entry, markets, out, count, random)) post(level, data, book, entry, p, hour, random)
        val now = Markets.hourOf(level)
        if (data.active.values.removeIf { now - it > ACTIVE_KEPT }) data.changed()
    }

    private fun post(level: ServerLevel, data: HaulData, book: HaulBook, entry: Markets.Entry, p: HaulRules.Plan, hour: Long, random: Random): Ticket {
        val t = Ticket(data.number(), shopFor(level, entry, p.good, random), p.good, p.units, p.each, p.kind, hour, hour + p.hours)
        book.tickets.add(t)
        data.changed()
        Hauls.posted(level, book, t)
        return t
    }

    /** The business of [entry]'s capital that would want [good] and so is named on its ticket, picked as an order's is; the market hall if no business there is known to. */
    private fun shopFor(level: ServerLevel, entry: Markets.Entry, good: Good, random: Random): Shop {
        val known = Orders.get(level)[entry.capital]?.shops?.keys ?: return Shop.HALL
        val asking = known.filter { good in Orders.asks(it, entry.town) }
        if (asking.isEmpty()) return Shop.HALL
        return Orders.pick(asking, random) { if (it.goods.contains(good)) 2.0 else 1.0 }
    }

    /** The tickets a capital posts a day just now: the setting (or what `trade hauls rate` set), rising by half a ticket for each carrier at work beyond the first, up to the most. */
    fun rate(level: ServerLevel, data: HaulData): Double {
        val base = if (data.rate >= 0.0) data.rate else TradeConfig.haulRate()
        return HaulRules.rate(base, max(TradeConfig.haulRateMax(), base), carriers(level, data))
    }

    /** How many carriers are at work: players who filled a ticket in the last two days, and the House's carters on the road. */
    fun carriers(level: ServerLevel, data: HaulData): Int {
        val now = Markets.hourOf(level)
        return data.active.values.count { now - it <= HaulRules.ACTIVE_HOURS } + max(0, Hauls.carterCount())
    }

    /**
     * Posts a ticket by hand at the capital whose heart is [capital] (for the festivals, other parts and the commands): [pay] Marks in all, at most three
     * times what the goods are usually worth, or else the pay the usual way for its [kind]; out until the market hour [due], or for [hours] if that is null.
     * It stays until then whatever the stock does. Gives it, or null if the tickets are off, the capital has no market, or its day is over.
     */
    fun postBy(level: ServerLevel, capital: BlockPos, good: Good, units: Int, kind: HaulKind, pay: Int?, due: Long?, hours: Long = HaulRules.hours(kind)): Haul? {
        if (!TradeConfig.hauls() || units < 1 || !good.traded) return null
        val markets = Markets.get(level)
        val entry = markets[capital] ?: return null
        val data = HaulData.get(level)
        val book = data.book(capital.asLong())
        if (book.hour == Long.MIN_VALUE) book.hour = Markets.hourOf(level)
        val hour = book.hour
        val end = due ?: (hour + hours)
        if (end <= hour) return null
        val m = entry.market
        val usual = good.base * m.freight(good)
        val each = if (pay != null) {
            pay.coerceIn(1, max(1, ceil(3.0 * usual * units).toInt())).toDouble() / units
        } else {
            val nearest = markets.all().filter { it !== entry }.minOfOrNull { Hauls.distance(it.capital, capital) } ?: 0.0
            HaulRules.each(kind, usual, HaulRules.shortness(m.stock(good), entry.town.target(good)), HaulRules.fee(good.base, nearest))
        }
        val t = Ticket(data.number(), shopFor(level, entry, good, random(book, hour xor 0x7A1L)), good, units, each, kind, hour, end)
        t.fixed = true
        book.tickets.add(t)
        data.changed()
        Hauls.posted(level, book, t)
        return Hauls.haul(book, t)
    }

    // ---- Away ----

    /** [player], logging in, is given the Marks a ticket paid them while they were away (if any did). */
    private fun settle(player: ServerPlayer) {
        val level = player.level()
        val data = HaulData.peek(level) ?: return
        val owed = data.owed.remove(player.uuid) ?: return
        data.changed()
        if (owed <= 0) return
        Trading.give(player, ModItems.GUILD_MARK.get(), owed)
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.haul.paid_owed", Orders.marks(owed)).withStyle(ChatFormatting.GRAY))
    }
}
