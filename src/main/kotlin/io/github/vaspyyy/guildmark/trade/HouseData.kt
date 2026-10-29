package io.github.vaspyyy.guildmark.trade

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * One of a House's hired carters (living-world/trader/design.md, Part 5): a record, not a resident. [name] and the [look] seed their face (and
 * the body the yard shows, [YardShow]); hired on the town's day [hired]. [state] is where they are: at the yard ([State.IDLE]), loading a wagon,
 * on the road with trip [trip], or idle for want of their wage ([State.UNPAID], [unpaid] days in a row). [guards] is how many more guards
 * than the one included ride with their wagon (at the guard's wage each); [trips] and [earned] what their trips have brought in, all told.
 */
class Carter(val id: Int, var name: String, val look: Long, val hired: Long) {
    enum class State(val id: String) {
        IDLE("idle"), LOADING("loading"), ROLLING("rolling"), UNPAID("unpaid");

        companion object {
            fun of(id: String): State = entries.firstOrNull { it.id == id } ?: IDLE
        }
    }

    var state = State.IDLE
    var trip = -1
    var unpaid = 0
    var guards = 0
    var trips = 0
    var earned = 0

    /** Let go while out with a wagon: they leave when they are back. Whether their last trip was waylaid. */
    var leaving = false
    var robbed = false

    /** Whether they are at the yard and free to be sent (not loading, on the road, or waiting for their wage). */
    val free: Boolean get() = state == State.IDLE && !leaving
}

/** A ticket a trip carries goods for: [units] of [good] booked on ticket [ticket], holding [deposit] Marks of the purse until they are in. */
class TripTicket(val ticket: Int, val good: Good, val units: Int, val deposit: Int)

/**
 * One trip of a House's wagon (Part 5): carter [carter] takes goods bought at the House's own town to the capital whose heart is [dest]
 * ([destName]), [distance] blocks away, for the [tickets] booked there. Its times are the overworld's clock: loading from [loadAt], out of the
 * gate at [departAt], into the town at [arriveAt] (the caravan record [record] has the wagon on its leg into the town, -1 for none) and back at
 * the yard at [backAt] (once it has arrived). [load] is what is on the wagon now and [bought] what was loaded; the money is the House's:
 * [cost] for the goods, [deposit] held for the bookings, then [pay] and [refund] for the tickets, [sold] for what was left over sold at the
 * stalls there, and a wright's [repair] and the units [lost] if it was waylaid ([waylaid]). [guards] rode with it.
 */
class Trip(val id: Int, val carter: Int, val dest: Long, var destName: String, val distance: Double) {
    enum class Stage(val id: String) {
        LOADING("loading"), OUT("out"), BACK("back");

        companion object {
            fun of(id: String): Stage = entries.firstOrNull { it.id == id } ?: OUT
        }
    }

    var stage = Stage.LOADING
    var loadAt = 0L
    var departAt = 0L
    var arriveAt = 0L
    var backAt = 0L
    val load = IntArray(Good.entries.size)
    val bought = IntArray(Good.entries.size)
    val tickets = ArrayList<TripTicket>()
    var cost = 0
    var deposit = 0
    var pay = 0
    var refund = 0
    var sold = 0
    var repair = 0
    var lost = 0
    var guards = 1
    var record = -1L
    var waylaid = false

    /** Whether it has got in (or been waylaid and limped in): everything is paid for and the carter is on the way home. */
    val settled: Boolean get() = stage == Stage.BACK

    /** What came of it in Marks: all it brought in less all it cost (the deposit counts as spent, what came back of it as brought in). */
    val net: Int get() = pay + refund + sold - cost - deposit - repair

    fun units(): Int = load.sum()
}

/** A line of the House book: on market hour [hour] carter [carter]'s trip to [town] with [goods] got in (or was [waylaid], [lost] units gone and the wright paid [repair]); it [cost] so much and brought in [got]. */
class BookLine(val hour: Long, val carter: String, val town: String, val goods: IntArray, val cost: Int, val got: Int, val waylaid: Boolean, val lost: Int, val repair: Int) {
    val net: Int get() = got - cost - repair
}

/**
 * The House's money all told since [since] (a market hour): Marks [put] in and [taken] out at the desk; for goods ([cost]), the deposits
 * held ([deposit]), the tickets' [pay] and [refund], the stalls' [sold], the wright's [repair], the carters' [wages] and the extra guards'
 * ([guardWages]); and the [trips] that got in, [waylaid] of them waylaid.
 */
class HouseTotals {
    var since = Long.MIN_VALUE
    var put = 0
    var taken = 0
    var cost = 0
    var deposit = 0
    var pay = 0
    var refund = 0
    var sold = 0
    var repair = 0
    var wages = 0
    var guardWages = 0
    var trips = 0
    var waylaid = 0

    /** What the trading did after every wage: what the trips brought in less what they and the House cost. */
    val net: Int get() = pay + refund + sold - cost - deposit - repair - wages - guardWages

    fun copy(): HouseTotals = HouseTotals().also { o ->
        o.since = since; o.put = put; o.taken = taken; o.cost = cost; o.deposit = deposit; o.pay = pay; o.refund = refund; o.sold = sold
        o.repair = repair; o.wages = wages; o.guardWages = guardWages; o.trips = trips; o.waylaid = waylaid
    }
}

/**
 * A trading house (Part 5; build part G): [owner]'s Counting House in the capital whose heart is [capital], known as [name] in their
 * [colour] (a dye id), its door at [origin] facing [facing], opened on the town's day [opened]. Its [purse], its [carters], the [trips]
 * under way and the [book] of the last [BOOK] trips; [hour] is the market hour its run has worked out to (its own catch-up, as the tickets'),
 * [lastOn] the market hour its owner was last seen on, and [awayPurse] what the purse held then (for the word when they are back).
 * [closed]: its works were taken down, and it winds up once its wagons are in.
 */
class HouseRec(val owner: UUID, val capital: Long) {
    var name = ""
    var colour = 0
    var origin: BlockPos = BlockPos.ZERO
    var facing: Direction = Direction.NORTH
    var opened = TradeBooks.NEVER
    var purse = 0
    val carters = ArrayList<Carter>()
    val trips = ArrayList<Trip>()
    val book = ArrayList<BookLine>()
    var hour = Long.MIN_VALUE
    var lastOn = Long.MIN_VALUE
    var awayPurse = 0
    var paidDay = TradeBooks.NEVER
    var guardCarry = 0.0
    var nextCarter = 1
    var nextTrip = 1
    var closed = false
    var totals = HouseTotals()

    /** Not kept: why the last carter that might have gone did not ("float", "tickets", "away", "off"), for the desk and the scribe. */
    var idleWhy = ""

    fun carter(id: Int): Carter? = carters.firstOrNull { it.id == id }
    fun trip(id: Int): Trip? = trips.firstOrNull { it.id == id }

    /** The carters out with a wagon (loading or on the road): what raises the posting rate. */
    fun working(): Int = carters.count { it.state == Carter.State.LOADING || it.state == Carter.State.ROLLING }

    fun note(line: BookLine) {
        book.add(line)
        while (book.size > BOOK) book.removeAt(0)
    }

    companion object {
        /** The trips the House book keeps. */
        const val BOOK = 20
    }
}

/**
 * The trading houses (Part 5; build part G), kept with the overworld as `guildmark:trade_houses`: one [HouseRec] for each Counting House
 * that has opened. Nothing is in it until one opens, so an old world loads with nothing to do; every field is optional in what is saved.
 */
class TradeHouses(val houses: ArrayList<HouseRec> = ArrayList()) : SavedData() {
    fun find(capital: Long, owner: UUID): HouseRec? = houses.firstOrNull { it.capital == capital && it.owner == owner }

    /** [owner]'s House (one a player in the world), if it is open. */
    fun of(owner: UUID): HouseRec? = houses.firstOrNull { it.owner == owner && !it.closed }

    fun changed() = setDirty()

    companion object {
        private fun goodsMap(goods: IntArray): Map<String, Int> = Good.entries.filter { goods[it.ordinal] > 0 }.associate { it.id to goods[it.ordinal] }

        private fun goodsInto(map: Map<String, Int>, goods: IntArray) {
            for ((id, n) in map) Good.byId(id)?.let { goods[it.ordinal] = maxOf(0, n) }
        }

        private val GOODS: Codec<Map<String, Int>> = Codec.unboundedMap(Codec.STRING, Codec.INT)

        private val CARTER: Codec<Carter> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Carter::id),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Carter::name),
                Codec.LONG.optionalFieldOf("look", 0L).forGetter(Carter::look),
                Codec.LONG.optionalFieldOf("hired", 0L).forGetter(Carter::hired),
                Codec.STRING.optionalFieldOf("state", "idle").forGetter { it.state.id },
                Codec.INT.optionalFieldOf("trip", -1).forGetter(Carter::trip),
                Codec.INT.optionalFieldOf("unpaid", 0).forGetter(Carter::unpaid),
                Codec.INT.optionalFieldOf("guards", 0).forGetter(Carter::guards),
                Codec.INT.optionalFieldOf("trips", 0).forGetter(Carter::trips),
                Codec.INT.optionalFieldOf("earned", 0).forGetter(Carter::earned),
                Codec.BOOL.optionalFieldOf("leaving", false).forGetter(Carter::leaving),
                Codec.BOOL.optionalFieldOf("robbed", false).forGetter(Carter::robbed),
            ).apply(i) { id, name, look, hired, state, trip, unpaid, guards, trips, earned, leaving, robbed ->
                Carter(id, name, look, hired).also { c ->
                    c.state = Carter.State.of(state)
                    c.trip = trip
                    c.unpaid = unpaid.coerceAtLeast(0)
                    c.guards = guards.coerceIn(0, Houses.EXTRA_GUARDS)
                    c.trips = trips
                    c.earned = earned
                    c.leaving = leaving
                    c.robbed = robbed
                }
            }
        }

        private val TICKET: Codec<TripTicket> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("ticket").forGetter(TripTicket::ticket),
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.INT.optionalFieldOf("units", 0).forGetter(TripTicket::units),
                Codec.INT.optionalFieldOf("deposit", 0).forGetter(TripTicket::deposit),
            ).apply(i) { ticket, good, units, deposit -> TripTicket(ticket, Good.byId(good) ?: Good.WHEAT, units, deposit) }
        }

        private class Times(val loadAt: Long, val departAt: Long, val arriveAt: Long, val backAt: Long)

        private val TIMES: Codec<Times> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("load", 0L).forGetter(Times::loadAt),
                Codec.LONG.optionalFieldOf("depart", 0L).forGetter(Times::departAt),
                Codec.LONG.optionalFieldOf("arrive", 0L).forGetter(Times::arriveAt),
                Codec.LONG.optionalFieldOf("back", 0L).forGetter(Times::backAt),
            ).apply(i, ::Times)
        }

        private class Money(val cost: Int, val deposit: Int, val pay: Int, val refund: Int, val sold: Int, val repair: Int, val lost: Int)

        private val MONEY: Codec<Money> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("cost", 0).forGetter(Money::cost),
                Codec.INT.optionalFieldOf("deposit", 0).forGetter(Money::deposit),
                Codec.INT.optionalFieldOf("pay", 0).forGetter(Money::pay),
                Codec.INT.optionalFieldOf("refund", 0).forGetter(Money::refund),
                Codec.INT.optionalFieldOf("sold", 0).forGetter(Money::sold),
                Codec.INT.optionalFieldOf("repair", 0).forGetter(Money::repair),
                Codec.INT.optionalFieldOf("lost", 0).forGetter(Money::lost),
            ).apply(i, ::Money)
        }

        private val TRIP: Codec<Trip> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Trip::id),
                Codec.INT.optionalFieldOf("carter", -1).forGetter(Trip::carter),
                Codec.LONG.fieldOf("dest").forGetter(Trip::dest),
                Codec.STRING.optionalFieldOf("dest_name", "").forGetter(Trip::destName),
                Codec.DOUBLE.optionalFieldOf("distance", 0.0).forGetter(Trip::distance),
                Codec.STRING.optionalFieldOf("stage", "out").forGetter { it.stage.id },
                TIMES.optionalFieldOf("times", Times(0L, 0L, 0L, 0L)).forGetter { Times(it.loadAt, it.departAt, it.arriveAt, it.backAt) },
                GOODS.optionalFieldOf("load", mapOf()).forGetter { goodsMap(it.load) },
                GOODS.optionalFieldOf("bought", mapOf()).forGetter { goodsMap(it.bought) },
                TICKET.listOf().optionalFieldOf("tickets", listOf()).forGetter { it.tickets },
                MONEY.optionalFieldOf("money", Money(0, 0, 0, 0, 0, 0, 0)).forGetter { Money(it.cost, it.deposit, it.pay, it.refund, it.sold, it.repair, it.lost) },
                Codec.INT.optionalFieldOf("guards", 1).forGetter(Trip::guards),
                Codec.LONG.optionalFieldOf("record", -1L).forGetter(Trip::record),
                Codec.BOOL.optionalFieldOf("waylaid", false).forGetter(Trip::waylaid),
            ).apply(i) { id, carter, dest, destName, distance, stage, times, load, bought, tickets, money, guards, record, waylaid ->
                Trip(id, carter, dest, destName, distance).also { t ->
                    t.stage = Trip.Stage.of(stage)
                    t.loadAt = times.loadAt
                    t.departAt = times.departAt
                    t.arriveAt = times.arriveAt
                    t.backAt = times.backAt
                    goodsInto(load, t.load)
                    goodsInto(bought, t.bought)
                    t.tickets.addAll(tickets)
                    t.cost = money.cost
                    t.deposit = money.deposit
                    t.pay = money.pay
                    t.refund = money.refund
                    t.sold = money.sold
                    t.repair = money.repair
                    t.lost = money.lost
                    t.guards = guards
                    t.record = record
                    t.waylaid = waylaid
                }
            }
        }

        private class Got(val cost: Int, val got: Int, val waylaid: Boolean, val lost: Int, val repair: Int)

        private val GOT: Codec<Got> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("cost", 0).forGetter(Got::cost),
                Codec.INT.optionalFieldOf("got", 0).forGetter(Got::got),
                Codec.BOOL.optionalFieldOf("waylaid", false).forGetter(Got::waylaid),
                Codec.INT.optionalFieldOf("lost", 0).forGetter(Got::lost),
                Codec.INT.optionalFieldOf("repair", 0).forGetter(Got::repair),
            ).apply(i, ::Got)
        }

        private val LINE: Codec<BookLine> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("hour", 0L).forGetter(BookLine::hour),
                Codec.STRING.optionalFieldOf("carter", "").forGetter(BookLine::carter),
                Codec.STRING.optionalFieldOf("town", "").forGetter(BookLine::town),
                GOODS.optionalFieldOf("goods", mapOf()).forGetter { goodsMap(it.goods) },
                GOT.optionalFieldOf("money", Got(0, 0, false, 0, 0)).forGetter { Got(it.cost, it.got, it.waylaid, it.lost, it.repair) },
            ).apply(i) { hour, carter, town, goods, money ->
                BookLine(hour, carter, town, IntArray(Good.entries.size).also { goodsInto(goods, it) }, money.cost, money.got, money.waylaid, money.lost, money.repair)
            }
        }

        private val TOTALS: Codec<HouseTotals> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("since", Long.MIN_VALUE).forGetter(HouseTotals::since),
                Codec.INT.optionalFieldOf("put", 0).forGetter(HouseTotals::put),
                Codec.INT.optionalFieldOf("taken", 0).forGetter(HouseTotals::taken),
                Codec.INT.optionalFieldOf("cost", 0).forGetter(HouseTotals::cost),
                Codec.INT.optionalFieldOf("deposit", 0).forGetter(HouseTotals::deposit),
                Codec.INT.optionalFieldOf("pay", 0).forGetter(HouseTotals::pay),
                Codec.INT.optionalFieldOf("refund", 0).forGetter(HouseTotals::refund),
                Codec.INT.optionalFieldOf("sold", 0).forGetter(HouseTotals::sold),
                Codec.INT.optionalFieldOf("repair", 0).forGetter(HouseTotals::repair),
                Codec.INT.optionalFieldOf("wages", 0).forGetter(HouseTotals::wages),
                Codec.INT.optionalFieldOf("guard_wages", 0).forGetter(HouseTotals::guardWages),
                Codec.INT.optionalFieldOf("trips", 0).forGetter(HouseTotals::trips),
                Codec.INT.optionalFieldOf("waylaid", 0).forGetter(HouseTotals::waylaid),
            ).apply(i) { since, put, taken, cost, deposit, pay, refund, sold, repair, wages, guardWages, trips, waylaid ->
                HouseTotals().also { t ->
                    t.since = since; t.put = put; t.taken = taken; t.cost = cost; t.deposit = deposit; t.pay = pay; t.refund = refund; t.sold = sold
                    t.repair = repair; t.wages = wages; t.guardWages = guardWages; t.trips = trips; t.waylaid = waylaid
                }
            }
        }

        private class Clock(val hour: Long, val lastOn: Long, val awayPurse: Int, val paidDay: Long, val guardCarry: Double, val nextCarter: Int, val nextTrip: Int)

        private val CLOCK: Codec<Clock> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("hour", Long.MIN_VALUE).forGetter(Clock::hour),
                Codec.LONG.optionalFieldOf("last_on", Long.MIN_VALUE).forGetter(Clock::lastOn),
                Codec.INT.optionalFieldOf("away_purse", 0).forGetter(Clock::awayPurse),
                Codec.LONG.optionalFieldOf("paid_day", TradeBooks.NEVER).forGetter(Clock::paidDay),
                Codec.DOUBLE.optionalFieldOf("guard_carry", 0.0).forGetter(Clock::guardCarry),
                Codec.INT.optionalFieldOf("next_carter", 1).forGetter(Clock::nextCarter),
                Codec.INT.optionalFieldOf("next_trip", 1).forGetter(Clock::nextTrip),
            ).apply(i, ::Clock)
        }

        private val HOUSE: Codec<HouseRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(HouseRec::owner),
                Codec.LONG.fieldOf("capital").forGetter(HouseRec::capital),
                Codec.STRING.optionalFieldOf("name", "").forGetter(HouseRec::name),
                Codec.INT.optionalFieldOf("colour", 0).forGetter(HouseRec::colour),
                Codec.LONG.optionalFieldOf("origin", 0L).forGetter { it.origin.asLong() },
                Codec.INT.optionalFieldOf("facing", Direction.NORTH.get2DDataValue()).forGetter { it.facing.get2DDataValue() },
                Codec.LONG.optionalFieldOf("opened", TradeBooks.NEVER).forGetter(HouseRec::opened),
                Codec.INT.optionalFieldOf("purse", 0).forGetter(HouseRec::purse),
                CARTER.listOf().optionalFieldOf("carters", listOf()).forGetter { it.carters },
                TRIP.listOf().optionalFieldOf("trips", listOf()).forGetter { it.trips },
                LINE.listOf().optionalFieldOf("book", listOf()).forGetter { it.book },
                CLOCK.optionalFieldOf("clock", Clock(Long.MIN_VALUE, Long.MIN_VALUE, 0, TradeBooks.NEVER, 0.0, 1, 1)).forGetter {
                    Clock(it.hour, it.lastOn, it.awayPurse, it.paidDay, it.guardCarry, it.nextCarter, it.nextTrip)
                },
                TOTALS.optionalFieldOf("totals", HouseTotals()).forGetter(HouseRec::totals),
                Codec.BOOL.optionalFieldOf("closed", false).forGetter(HouseRec::closed),
            ).apply(i) { owner, capital, name, colour, origin, facing, opened, purse, carters, trips, book, clock, totals, closed ->
                HouseRec(owner, capital).also { h ->
                    h.name = name
                    h.colour = colour.coerceIn(0, 15)
                    h.origin = BlockPos.of(origin)
                    h.facing = Direction.from2DDataValue(facing)
                    h.opened = opened
                    h.purse = purse
                    h.carters.addAll(carters)
                    h.trips.addAll(trips)
                    h.book.addAll(book.takeLast(HouseRec.BOOK))
                    h.hour = clock.hour
                    h.lastOn = clock.lastOn
                    h.awayPurse = clock.awayPurse
                    h.paidDay = clock.paidDay
                    h.guardCarry = if (clock.guardCarry.isFinite()) clock.guardCarry.coerceIn(0.0, 100.0) else 0.0
                    h.nextCarter = maxOf(clock.nextCarter, (carters.maxOfOrNull { it.id } ?: 0) + 1)
                    h.nextTrip = maxOf(clock.nextTrip, (trips.maxOfOrNull { it.id } ?: 0) + 1)
                    h.totals = totals
                    h.closed = closed
                    // A carter whose trip is not kept (a record cut short) is at the yard again
                    for (c in h.carters) if ((c.state == Carter.State.LOADING || c.state == Carter.State.ROLLING) && h.trip(c.trip) == null) {
                        c.state = Carter.State.IDLE
                        c.trip = -1
                    }
                }
            }
        }

        private val CODEC: Codec<TradeHouses> = RecordCodecBuilder.create { i ->
            i.group(
                HOUSE.listOf().optionalFieldOf("houses", listOf()).forGetter { it.houses },
            ).apply(i) { list -> TradeHouses(ArrayList(list)) }
        }

        /** The saved data's type: [get] makes the record (and the game saves what it made), so whatever only looks reads it with [peek]. */
        internal val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "trade_houses"), Supplier { TradeHouses() }, CODEC)

        /** The houses, made (and so saved, even empty) if there are none yet: for a House opening. */
        fun get(level: ServerLevel): TradeHouses = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** The houses if one ever opened, else null, making nothing: for the hourly run, a look or a status line. */
        fun peek(level: ServerLevel): TradeHouses? = level.server.overworld().dataStorage.get(TYPE)
    }
}
