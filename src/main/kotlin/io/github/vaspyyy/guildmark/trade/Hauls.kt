package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Fill
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Marked
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Stalls
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.MarketHaul
import io.github.vaspyyy.guildmark.network.MarketHaulPayload
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.ContainerHelper
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * The kinds of haul ticket (living-world/trader/design.md, Part 2). The ids are what is saved, and the order is the order they were
 * added in: new kinds go at the end, and none is ever moved or taken out.
 */
enum class HaulKind(val id: String) {
    /** 16 to 48 units, for any trader. */
    PARCEL("parcel"),

    /** 64 to 128 units, for a trader with a Warrant. */
    CONSIGNMENT("consignment"),

    /** A staple the town went short of, at up to twice the usual price; for a trader with a Warrant. */
    RELIEF("relief"),

    /** Goods for a festival, posted by whatever holds one ([Hauls.postFeast]); for a trader with a Warrant. */
    FEAST("feast"),
    ;

    companion object {
        fun byId(id: String): HaulKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A ticket as the listeners hear of it: number [id] at the capital whose heart is [capital], for [units] of [good] to be brought to
 * [shop] there for [pay] whole Marks, of the given [kind], to be filled by the market hour [due]. Part A appended [done], [booked] and
 * [posted]: how many of the units are in, how many are booked and still to come, and the market hour the ticket was posted.
 */
data class Haul(
    val id: Int,
    val capital: BlockPos,
    val shop: Shop,
    val good: Good,
    val units: Int,
    val pay: Int,
    val kind: HaulKind,
    val due: Long,
    val done: Int = 0,
    val booked: Int = 0,
    val posted: Long = 0,
) {
    /** How many are still to come in, booked or not. */
    val left: Int get() = (units - done).coerceAtLeast(0)

    /** How many nobody has booked. */
    val open: Int get() = (units - done - booked).coerceAtLeast(0)
}

/** Who takes a ticket and fills it: a player, or a House's carter for its owner (Part 5). Either way the [owner] is who is paid, and whose standing it raises. */
sealed interface Taker {
    val owner: UUID

    data class Player(override val owner: UUID) : Taker

    data class House(override val owner: UUID) : Taker
}

/**
 * A ticket filled, as [Hauls.Listener.delivered] hears of it: [taker] brought [units] of [good] to [shop] at the capital whose heart is
 * [capital] and was paid [pay] whole Marks for a ticket of the given [kind]. [from] is where most of the goods came from (the capital whose
 * heart it was bought at), or null if that is not known. Part A appended [refund], the part of the booking's deposit given back with the
 * pay (for a House it is the House's to put back in its purse, as the pay is), and [left], how many units of the ticket are still to come
 * in after this delivery (0 when it is filled). The listeners hear of a booking once, when its last unit is in, and so of all of it: [units],
 * [pay] and [refund] are the booking's in all, however many handovers it took. What [Hauls.deliver] and [Hauls.fill] give back is the handover
 * itself, for the shop's keeper and the screen to speak of.
 */
data class Delivery(
    val taker: Taker,
    val capital: BlockPos,
    val shop: Shop,
    val good: Good,
    val units: Int,
    val pay: Int,
    val kind: HaulKind,
    val from: BlockPos?,
    val refund: Int = 0,
    val left: Int = 0,
)

/**
 * Haul tickets (living-world/trader/design.md, Part 2): what a capital short of something posts to have it brought, the bookings
 * and deliveries against it, and the hauled ledger ([Ledger]). The tickets and the hours they are worked out in live in [HaulData] and
 * [HaulRun]; the rules of sizes and pay in [HaulRules]; this is what players and the other parts of the trader's life call:
 *
 * - seeing: [find], [all], [open], [fills];
 * - booking and filling: [book] (a deposit held), [fill] (goods already counted in: what a House's carter does), [deliver] (what a player
 *   carries, off their pack and carriers and off their ledger), [release] (a booking given up, or let go without a loss);
 * - posting by hand, for the festivals and other parts: [postFeast], [postCustom];
 * - the dealer's lot: [capital], [room], [hauledHere].
 *
 * Every Mark a ticket pays is worked out from [HaulRules.owed], and cannot be more than the ticket's pay in all (see there). With
 * `tradeHauls` off nothing here pays, books or posts, and the open tickets' days stand still.
 */
object Hauls {
    /**
     * Hears a ticket's life: posted, booked, filled (with what was delivered), lapsed for want of goods, and withdrawn because the
     * town no longer wants it. Each is called on the server, with the ticket as it stood. Subscribe once, at setup, with [listen].
     * `delivered` is heard once for a booking brought in full, as one handover or several (a ticket filled in full, in the usual case of one
     * booker), with the whole of it in the [Delivery]; a handover that leaves some to come is not heard, so a count of deliveries cannot be run up
     * a handful at a time.
     */
    interface Listener {
        fun posted(level: ServerLevel, haul: Haul) {}
        fun booked(level: ServerLevel, haul: Haul) {}
        fun delivered(level: ServerLevel, haul: Haul, delivery: Delivery) {}
        fun lapsed(level: ServerLevel, haul: Haul) {}
        fun withdrawn(level: ServerLevel, haul: Haul) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        listeners.add(listener)
    }

    /** Tells every listener, none of which may stop the others (or the ticket) by failing. */
    private inline fun heard(call: (Listener) -> Unit) {
        for (l in listeners) {
            try {
                call(l)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("A haul listener failed", e)
            }
        }
    }

    internal fun posted(level: ServerLevel, book: HaulBook, t: Ticket) = heard { it.posted(level, haul(book, t)) }
    internal fun lapsed(level: ServerLevel, book: HaulBook, t: Ticket) = heard { it.lapsed(level, haul(book, t)) }
    internal fun withdrawn(level: ServerLevel, book: HaulBook, t: Ticket) = heard { it.withdrawn(level, haul(book, t)) }

    /**
     * How many hired carters are on the road for a trading house just now: what raises the rate tickets are posted at (half a
     * ticket a day for each, up to the ceiling). Answers 0 until the House (build part G) sets it.
     */
    var carterCount: () -> Int = { 0 }

    /** Blocks between two capitals' hearts across the ground, as the freight reckons them (see [io.github.vaspyyy.guildmark.economy.Carriage]). */
    fun distance(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return Math.sqrt(dx * dx + dz * dz)
    }

    /** Registers the market screen's payload for a ticket (see [MarketHaulPayload]) and the hourly run (see [HaulRun]). */
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1").playToServer(MarketHaulPayload.TYPE, MarketHaulPayload.STREAM_CODEC, MarketHaulPayload::handle)
        }
        HaulRun.register()
    }

    /** What [player] asked to do ([MarketHaulPayload]'s TAKE, DELIVER or GIVE_UP) with ticket [haul] at the shop whose screen they have open: the shop's keeper deals with it (see [Stalls.haul]). */
    fun act(player: ServerPlayer, haul: Int, action: Int) = Stalls.haul(player, haul, action)

    // ---- Seeing the tickets ----

    /** [t] as the listeners see it. */
    internal fun haul(book: HaulBook, t: Ticket): Haul =
        Haul(t.id, BlockPos.of(book.capital), t.shop, t.good, t.units, t.pay, t.kind, t.due, t.done, t.booked, t.posted)

    /** The ticket numbered [id] as it stands, if there is one. */
    fun find(level: ServerLevel, id: Int): Haul? {
        val (book, t) = HaulData.peek(level)?.find(id) ?: return null
        return haul(book, t)
    }

    /** Every ticket out at the capital whose heart is [capital] (at every capital, if null), whatever the switch says: the commands' list. */
    fun all(level: ServerLevel, capital: BlockPos? = null): List<Haul> {
        val data = HaulData.peek(level) ?: return listOf()
        val books = if (capital == null) data.all() else listOfNotNull(data[capital.asLong()])
        return books.flatMap { b -> b.tickets.map { haul(b, it) } }.sortedBy { it.id }
    }

    /** The tickets with units nobody has booked, at [capital]'s (every capital's, if null): what a trader can take. None while the tickets are off. */
    fun open(level: ServerLevel, capital: BlockPos? = null): List<Haul> =
        if (!TradeConfig.hauls()) listOf() else all(level, capital).filter { it.open > 0 }

    /** The crier's thanks and the Word's talk: the tickets filled at [capital] these last two days, as the order book keeps its fills. */
    fun fills(capital: BlockPos): List<Fill> {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return listOf()
        val book = HaulData.peek(server.overworld())?.get(capital.asLong()) ?: return listOf()
        return book.fills.map { Fill(it.hour, it.good, it.shop, it.units, it.who) }
    }

    /** How many tickets [player] has booked units on that are still to come in, at every capital. */
    fun held(level: ServerLevel, player: UUID): Int = HaulData.peek(level)?.let { held(it, player, house = false) } ?: 0

    internal fun held(data: HaulData, player: UUID, house: Boolean): Int =
        data.all().sumOf { b -> b.tickets.count { t -> t.bookings.any { it.taker == player && it.house == house && it.left > 0 } } }

    /** [taker]'s booking on [t], if they have one. */
    internal fun bookingOf(t: Ticket, taker: Taker): Booking? = t.bookings.firstOrNull { it.taker == taker.owner && it.house == (taker is Taker.House) }

    // ---- Booking ----

    /** The most tickets a House's carters hold booked at once for one owner (Part 5's limit). */
    private const val HOUSE_HELD = 8

    /** What a booking of [units] of [t] holds. */
    internal fun deposit(t: Ticket, units: Int): Int = HaulRules.deposit(TradeConfig.haulDeposit(), t.each, units)

    /** What a booking of [units] of ticket [id] holds as a deposit now (0 if there is no such ticket). */
    fun deposit(level: ServerLevel, id: Int, units: Int): Int {
        val t = HaulData.peek(level)?.find(id)?.second ?: return 0
        return deposit(t, units)
    }

    /**
     * Why [taker] cannot book [want] units of [t] at [book]'s capital just now, as a key under `gui.guildmark.trade.haul.refused.`, or null if they can:
     * off, taken (nothing left unbooked), yours (they hold a booking on it), warrant (their rung may not take that kind), first_call (a relief or a
     * feast ticket stays a day for a Factor), limit (they hold as many tickets as their rung allows) and poor (they cannot hold the deposit).
     * [player] is the taker if they are a player and on.
     */
    internal fun refusal(data: HaulData, book: HaulBook, t: Ticket, taker: Taker, player: ServerPlayer?, want: Int): String? {
        if (!TradeConfig.hauls()) return "off"
        if (t.open <= 0) return "taken"
        if (bookingOf(t, taker) != null) return "yours"
        if (taker is Taker.Player) {
            if (player == null) return "gone"
            if (!Rungs.may(player, t.kind)) return "warrant"
            val fresh = book.hour != Long.MIN_VALUE && book.hour - t.posted < HaulRules.FIRST_HOURS
            if ((t.kind == HaulKind.RELIEF || t.kind == HaulKind.FEAST) && fresh && !Rungs.reached(player, Rung.FACTOR)) return "first_call"
            if (held(data, taker.owner, house = false) >= Rungs.heldLimit(player)) return "limit"
            if (TownControl.marks(player) < deposit(t, want)) return "poor"
        } else if (held(data, taker.owner, house = true) >= HOUSE_HELD) {
            return "limit"
        }
        return null
    }

    /**
     * [taker] books [units] of ticket [id] (all that is still unbooked, if [units] is 0 or more than that), under [name]: a player's deposit is taken
     * from their Marks now and given back as the units come in; a House's is not (the House holds it in its purse; see [deposit]). A booking
     * stands until the ticket's day if its booker buys a quarter of the goods far enough away within a day, or if it is a House's; otherwise
     * it lapses. Gives null if it was made, else why not (see [refusal], and gone for no such ticket).
     */
    fun book(level: ServerLevel, id: Int, taker: Taker, units: Int = 0, name: String = ""): String? {
        if (!TradeConfig.hauls()) return "off"
        val data = HaulData.peek(level) ?: return "gone"
        val (book, t) = data.find(id) ?: return "gone"
        val player = if (taker is Taker.Player) level.server.playerList.getPlayer(taker.owner) else null
        val want = if (units <= 0) t.open else min(units, t.open)
        refusal(data, book, t, taker, player, want)?.let { return it }
        val deposit = deposit(t, want)
        if (player != null && deposit > 0) TownControl.pay(player, deposit)
        val house = taker is Taker.House
        val b = Booking(taker.owner, house, name.ifEmpty { player?.gameProfile?.name ?: "" }, want, clock(level, book))
        b.deposit = deposit
        b.stands = house
        t.bookings.add(b)
        data.changed()
        heard { it.booked(level, haul(book, t)) }
        return null
    }

    /** The hour in [book]'s frame: the last it worked out, or the overworld's if it has not started. */
    internal fun clock(level: ServerLevel, book: HaulBook): Long = if (book.hour != Long.MIN_VALUE) book.hour else Markets.hourOf(level)

    // ---- Filling ----

    /** How many of [good] [player] carries, in their pack and in any carrier of theirs within eight blocks, that the capital whose heart is [capital] will take (none off its own waylaid wagons). */
    fun carried(player: ServerPlayer, good: Good, capital: BlockPos): Int {
        val item = good.item ?: return 0
        var n = 0
        for (c in Carriers.holdings(player)) {
            n += ContainerHelper.clearOrCountMatchingItems(c, { it.`is`(item) && !Marked.home(it, capital) }, Int.MAX_VALUE, true)
        }
        return n
    }

    /** Takes [count] of [good] out of [player]'s pack and carriers (the pack first), none off the waylaid wagons of the capital whose heart is [capital]. */
    private fun takeCarried(player: ServerPlayer, good: Good, count: Int, capital: BlockPos) {
        val item = good.item ?: return
        var left = count
        for (c in Carriers.holdings(player)) {
            if (left <= 0) break
            left -= ContainerHelper.clearOrCountMatchingItems(c, { it.`is`(item) && !Marked.home(it, capital) }, left, false)
        }
    }

    /** How many units of [b]'s booking on [t] at [capital] [player] could hand over now: what they carry, as far as their ledger says it came a long way, and no more than is still to come. */
    fun deliverable(level: ServerLevel, player: ServerPlayer, capital: BlockPos, t: Ticket, b: Booking): Int {
        if (b.left <= 0 || t.left <= 0) return 0
        val carried = carried(player, t.good, capital)
        if (carried <= 0) return 0
        return min(min(b.left, t.left), min(carried, Ledger.count(level, player.uuid, t.good, capital)))
    }

    /**
     * Why [player] cannot deliver to ticket [id], as a key under `gui.guildmark.trade.haul.refused.`, or null if they can: off, gone,
     * not_yours (they hold no booking on it), none (they carry none of the good) or not_hauled (they carry some, but none of it came from a
     * market far enough away within ten days, or all of it came from this one).
     */
    fun cannotDeliver(level: ServerLevel, player: ServerPlayer, id: Int): String? {
        if (!TradeConfig.hauls()) return "off"
        val (book, t) = HaulData.peek(level)?.find(id) ?: return "gone"
        val b = bookingOf(t, Taker.Player(player.uuid)) ?: return "not_yours"
        val capital = BlockPos.of(book.capital)
        if (carried(player, t.good, capital) <= 0) return "none"
        if (deliverable(level, player, capital, t, b) <= 0) return "not_hauled"
        return null
    }

    /**
     * [player] hands over what they carry of ticket [id]'s good that counts as hauled at its capital, up to what their booking still owes:
     * the goods come out of their pack and carriers (the pack first), the same units off their ledger, and they are paid at once, with the
     * deposit's share back. Gives what was delivered, or null if nothing could be (see [cannotDeliver]).
     */
    fun deliver(level: ServerLevel, player: ServerPlayer, id: Int): Delivery? {
        if (!TradeConfig.hauls()) return null
        val data = HaulData.peek(level) ?: return null
        val (book, t) = data.find(id) ?: return null
        val taker = Taker.Player(player.uuid)
        val b = bookingOf(t, taker) ?: return null
        val capital = BlockPos.of(book.capital)
        val entry = Markets.get(level)[capital] ?: return null
        val n = deliverable(level, player, capital, t, b)
        if (n <= 0) return null
        val parcels = Ledger.take(level, player.uuid, t.good, n, capital)
        val taken = parcels.sumOf { it.units }
        if (taken <= 0) return null
        takeCarried(player, t.good, taken, capital)
        return finish(level, data, book, t, b, taken, taker, mostFrom(parcels), entry)
    }

    /**
     * [taker] has brought [units] of ticket [id] in (goods already counted in and taken, as a House's carter's wagon unloads): gives their
     * pay and the deposit's share back, and the standing and Trade XP a player's delivery earns. A player's Marks are given at once (kept
     * for them if they are away); a House's are in the [Delivery] for the House to put in its purse. [taker] must hold a booking on it; no
     * more is taken in than the booking still owes. Gives the delivery, or null if none could be made.
     */
    fun fill(level: ServerLevel, id: Int, units: Int, taker: Taker, from: BlockPos? = null): Delivery? {
        if (!TradeConfig.hauls() || units <= 0) return null
        val data = HaulData.peek(level) ?: return null
        val (book, t) = data.find(id) ?: return null
        val b = bookingOf(t, taker) ?: return null
        val n = min(units, min(b.left, t.left))
        if (n <= 0) return null
        val entry = Markets.get(level)[BlockPos.of(book.capital)] ?: return null
        return finish(level, data, book, t, b, n, taker, from, entry)
    }

    /** The capital most of [parcels] were bought at, if there are any. */
    internal fun mostFrom(parcels: List<Parcel>): BlockPos? =
        parcels.groupBy { it.origin }.maxByOrNull { (_, ps) -> ps.sumOf { it.units } }?.key

    /** [n] more units of [t] are in, brought by [taker] against [b]; every check has been made. */
    private fun finish(level: ServerLevel, data: HaulData, book: HaulBook, t: Ticket, b: Booking, n: Int, taker: Taker, from: BlockPos?, entry: Markets.Entry): Delivery {
        val capital = BlockPos.of(book.capital)
        val player = if (taker is Taker.Player) level.server.playerList.getPlayer(taker.owner) else null
        // The Trade tree's pay perk makes each unit pay a little more; the ticket's whole is worked out on the raised price, as HaulRules.owed has it
        val each = t.each * (1.0 + (player?.let { Rungs.perk(it, Perk.HAUL_PAY) } ?: 0.0))
        val before = HaulRules.owed(each, t.units, t.done)
        t.done += n
        b.done += n
        b.stands = true
        val pay = (HaulRules.owed(each, t.units, t.done) - before).coerceAtLeast(0)
        val refund = (HaulRules.repayable(b.deposit, b.units, b.done) - b.repaid).coerceAtLeast(0)
        b.repaid += refund
        b.paid += pay
        if (b.left <= 0) t.bookings.remove(b)
        // The goods go into the market as an order's do: the price stays down for them
        val m = entry.market
        m.traded(t.good, n.toDouble())
        m.tally(Tally.SOLD, t.good, n.toDouble())
        Markets.get(level).changed()
        data.active[taker.owner] = Markets.hourOf(level)
        if (pay > 0) {
            Suppliers.dealt(level, taker.owner, pay.toDouble())
            if (Suppliers.enabled()) Suppliers.gain(level, capital, taker.owner, pay.toDouble())
        }
        if (taker is Taker.Player) payOut(level, data, taker.owner, pay + refund)
        val left = t.left
        if (left <= 0) {
            book.tickets.remove(t)
            book.fills.add(HaulFill(clock(level, book), t.good, t.shop, t.units, b.name, t.kind, t.pay))
        }
        data.changed()
        // The listeners hear of a booking when its last unit is in, and of all of it (what a delivery counts for), not of each handful
        if (b.left <= 0) heard { it.delivered(level, haul(book, t), Delivery(taker, capital, t.shop, t.good, b.done, b.paid, t.kind, from, b.repaid, left)) }
        return Delivery(taker, capital, t.shop, t.good, n, pay, t.kind, from, refund, left)
    }

    /** Gives [player] [marks] Marks, or keeps them for when they next join if they are away. */
    internal fun payOut(level: ServerLevel, data: HaulData, player: UUID, marks: Int) {
        if (marks <= 0) return
        val online = level.server.playerList.getPlayer(player)
        if (online != null) {
            Trading.give(online, ModItems.GUILD_MARK.get(), marks)
        } else {
            data.owed[player] = (data.owed[player] ?: 0) + marks
            data.changed()
        }
    }

    // ---- Letting go ----

    /**
     * Ends [b]'s booking on [t] with units still owed: they go back to the board, and what is left of the deposit is lost, or given back if [refund].
     * Gives the Marks refunded (for a House's booking they are the House's to take back; a player's are given at once).
     */
    internal fun letGo(level: ServerLevel, data: HaulData, t: Ticket, b: Booking, refund: Boolean): Int {
        t.bookings.remove(b)
        data.changed()
        val held = (b.deposit - b.repaid).coerceAtLeast(0)
        b.repaid = b.deposit
        if (!refund || held <= 0) return 0
        if (!b.house) payOut(level, data, b.taker, held)
        return held
    }

    /**
     * [player] lets go of their booking on ticket [id] (a House's, if [house]): what they have not brought goes back to the board, and the share of the
     * deposit held for it is lost, or given back if [refund] (a Hunt that took the goods; nothing else). Gives the Marks given back, or -1 if there was
     * no such booking.
     */
    fun release(level: ServerLevel, player: UUID, id: Int, refund: Boolean = false, house: Boolean = false): Int {
        val data = HaulData.peek(level) ?: return -1
        val t = data.find(id)?.second ?: return -1
        val b = t.bookings.firstOrNull { it.taker == player && it.house == house && it.left > 0 } ?: return -1
        return letGo(level, data, t, b, refund)
    }

    /** The same for every booking of [player]'s on a ticket for [good], wherever it is (the Hunt's way of letting go the bookings for goods it took). Gives the Marks given back in all, or -1 if there was none. */
    fun release(level: ServerLevel, player: UUID, good: Good, refund: Boolean = false, house: Boolean = false): Int {
        val data = HaulData.peek(level) ?: return -1
        var any = false
        var back = 0
        for (book in data.all()) for (t in book.tickets.toList()) {
            if (t.good != good) continue
            val b = t.bookings.firstOrNull { it.taker == player && it.house == house && it.left > 0 } ?: continue
            any = true
            back += letGo(level, data, t, b, refund)
        }
        return if (any) back else -1
    }

    // ---- Posting by hand ----

    /**
     * Posts a ticket for [units] of [good] at the capital whose heart is [capital] for the festival on the town's [day] (a consignment's pay, due at the
     * day's end), for the cook's and the festivals' hooks; it stays until then whatever the stock does. Gives it, or null if the tickets are off,
     * the capital has no market, or the day is already over.
     */
    fun postFeast(level: ServerLevel, capital: BlockPos, good: Good, units: Int, day: Long): Haul? =
        HaulRun.postBy(level, capital, good, units, HaulKind.FEAST, null, (day + 1) * 24 - 4)

    /**
     * Posts a ticket for [units] of [good] at the capital whose heart is [capital] that pays [pay] Marks in all (at most three times what the goods are
     * usually worth) and stays [days] days, for other parts that want something brought; it stays until then whatever the stock does. Gives it, or null
     * if the tickets are off or the capital has no market.
     */
    fun postCustom(level: ServerLevel, capital: BlockPos, good: Good, units: Int, pay: Int, days: Int): Haul? =
        HaulRun.postBy(level, capital, good, units, HaulRules.kindFor(units), pay, null, days.coerceAtLeast(1) * 24L)

    // ---- The dealer's lot ----

    /** Whether [entry] is a capital's market, which is all the tickets, the dealer's share and the haul room are for (a village's market, if there is one, is not). */
    fun capital(level: ServerLevel, entry: Markets.Entry): Boolean = Markets.get(level)[entry.capital] != null

    /**
     * The room a dealer's lot is given on top of [entry]'s usual for [good]: `tradeHaulRoomDays` days of three of the caravans' wagon loads, while the market
     * holds less than the town likes to keep, and nothing once it holds that (see [HaulRules.room]). None with the tickets off.
     */
    fun room(entry: Markets.Entry, good: Good): Double {
        if (!TradeConfig.hauls()) return 0.0
        return HaulRules.room(TradeConfig.haulRoomDays(), entry.town.target(good), entry.market.stock(good), Economy.load(entry.market, entry.town, good))
    }

    /** How many units of [good] [player] carries in their pack that would sell at a dealer's share at [entry]'s market: what the ledger says came a long way, and no more than they hold. */
    fun hauledHere(level: ServerLevel, player: ServerPlayer, entry: Markets.Entry, good: Good): Int {
        if (!TradeConfig.hauls() || !capital(level, entry)) return 0
        val item = good.item ?: return 0
        val ledger = Ledger.count(level, player.uuid, good, entry.capital)
        if (ledger <= 0) return 0
        return min(ledger, Trading.count(player.inventory, item, entry.capital))
    }

    // ---- The shop's screen ----

    /** How many colours the booker's seal comes in (the six seals of the market screen's art). */
    private const val SEALS = 6

    /** [player]'s own booking on ticket [id], if they have one. */
    fun bookedOn(level: ServerLevel, player: UUID, id: Int): Booking? =
        HaulData.peek(level)?.find(id)?.second?.let { bookingOf(it, Taker.Player(player)) }

    /** Whether [player] let a booking at [shop] in the capital whose heart is [capital] lapse lately and has not heard of it from the keeper yet: they are told now (once). */
    fun scold(level: ServerLevel, capital: BlockPos, player: UUID, shop: Shop): Boolean {
        if (!TradeConfig.hauls()) return false
        val data = HaulData.peek(level) ?: return false
        val book = data[capital.asLong()] ?: return false
        val lapse = book.lapses.firstOrNull { it.player == player && it.shop == shop } ?: return false
        book.lapses.remove(lapse)
        data.changed()
        return true
    }

    /** "in 30 hours", "in 3 days" from two days on, or "within the hour" for the last, for [hours] market hours left (the chat and the screen's tooltips). */
    fun due(hours: Int): Component = when {
        hours <= 1 -> Component.translatable("message.guildmark.trade.haul.due.soon")
        hours < 48 -> Component.translatable("message.guildmark.trade.haul.due.hours", hours)
        else -> Component.translatable("message.guildmark.trade.haul.due.days", (hours + 23) / 24)
    }

    /** The colour (0 to 5) of [taker]'s seal: the same for the same player every time. */
    internal fun seal(taker: UUID): Int = Math.floorMod(taker.hashCode(), SEALS)

    /**
     * The tickets out at [shop] in the capital whose heart is [capital], as [player]'s screen shows them: the ones they hold a booking on first, then the ones
     * they can take, then the rest. Nothing with the tickets off, and nothing made if there are none.
     */
    fun view(level: ServerLevel, player: ServerPlayer, capital: BlockPos, shop: Shop): List<MarketHaul> {
        if (!TradeConfig.hauls()) return listOf()
        val data = HaulData.peek(level) ?: return listOf()
        val book = data[capital.asLong()] ?: return listOf()
        val rows = book.tickets.filter { it.shop == shop }.map { row(level, data, book, it, player) }
        return rows.sortedWith(compareBy({ if (it.mine > 0) 0 else if (it.why.isEmpty()) 1 else 2 }, { it.id }))
    }

    /** Whether there is a ticket out at [shop] in the capital whose heart is [capital] that [player] holds a booking on or could take: what opens the inn's screen. */
    fun waiting(level: ServerLevel, player: UUID, capital: BlockPos, shop: Shop): Boolean {
        if (!TradeConfig.hauls()) return false
        val book = HaulData.peek(level)?.get(capital.asLong()) ?: return false
        return book.tickets.any { t -> t.shop == shop && (t.open > 0 || t.bookings.any { it.taker == player && !it.house && it.left > 0 }) }
    }

    /** Ticket [t] of [book] as [player] sees it (see [MarketHaul]). */
    private fun row(level: ServerLevel, data: HaulData, book: HaulBook, t: Ticket, player: ServerPlayer): MarketHaul {
        val capital = BlockPos.of(book.capital)
        val taker = Taker.Player(player.uuid)
        val b = bookingOf(t, taker)
        val mine = b?.left ?: 0
        // What the units they hold, or could take, or that are left, pay them, with the rung's perk, as the delivery will work it out
        val each = t.each * (1.0 + Rungs.perk(player, Perk.HAUL_PAY))
        val share = if (mine > 0) mine else if (t.open > 0) t.open else t.left
        val pay = (HaulRules.owed(each, t.units, t.done + share) - HaulRules.owed(each, t.units, t.done)).coerceAtLeast(0)
        val why = if (b != null) "" else refusal(data, book, t, taker, player, t.open) ?: ""
        val others = t.bookings.filter { it !== b && it.left > 0 }
        val first = b ?: others.firstOrNull()
        val carried = if (b != null) carried(player, t.good, capital) else 0
        val deliverable = if (b != null) deliverable(level, player, capital, t, b) else 0
        val deposit = if (b != null) (b.deposit - b.repaid).coerceAtLeast(0) else deposit(t, t.open)
        val hour = clock(level, book)
        return MarketHaul(
            t.id, t.good.id, t.units, t.done, mine, t.open, pay, t.kind.ordinal, (t.due - hour).coerceIn(0L, 100000L).toInt(),
            (t.due - t.posted).coerceIn(1L, 100000L).toInt(), why, carried, deliverable, deposit,
            others.map { it.name }.filter { it.isNotEmpty() }.distinct().joinToString(", "), first?.let { seal(it.taker) } ?: -1,
        )
    }
}
