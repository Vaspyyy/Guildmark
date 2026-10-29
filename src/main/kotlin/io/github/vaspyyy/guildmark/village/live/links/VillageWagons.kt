package io.github.vaspyyy.guildmark.village.live.links

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageBudget
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.keptList
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster
import io.github.vaspyyy.guildmark.village.live.market.PedlarRoster.NONE
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier
import kotlin.math.max
import kotlin.math.min

/**
 * What a capital's wagon does for the village at the end of its road: carries the village's surplus in and the capital's goods
 * and news back (living-world/villages/design.md sections 9.2 and 9.3).
 *
 * **Out.** A wagon (a [Run.WAGON] or a [Run.CONVOY]) whose leg starts at a linked village ([VillageLinks]) that is live or sleeping
 * and has its market open sets out ([load], from `Caravans.depart` once the capital's load is worked out): the village's market is
 * run on to the wagon's hour first (at most [RUN_TO] hours; further behind, its stock is used as it stands), and of each good the
 * capital asked for the wagon takes what the village has in glut (`Economy.exports`, less what the mail has taken already and not yet
 * applied): `give = min(load, exports)`. The capital's load does not change: the village's real goods stand in for that much of the
 * supply from afar, and [CaravanRec.fromVillage] says which share is the village's. The give goes into the village's mail, stamped
 * with the wagon's hour, and leaves its stock tallied EXPORTED when the village's step reaches it. A wagon that may go only with an
 * escort (a Closed road's) takes nothing, since it may never go.
 *
 * **Back.** When the wagon's record ends at the start of its leg ([home], from `Caravans.end`), the village is brought what it is short
 * of, as a cart's call brings it (`Economy.bring`, one caravan's share for each eight hours since its last call, by the pedlar or a
 * wagon; [RELIEF_TIMES] on the road's first call after a band on it was broken), stamped with the hour it is back; the capital's
 * morning surplus the wagon carried away ([CaravanRec.exports]) is the first of it ([Trip.fromCapital]), the rest is the supply from
 * afar the road is the gateway to. A wagon called off or switched off before it is back (time set back, the wagons off, its market
 * gone) comes back at once with what it has. A waylaid wagon brings nothing: the village's goods went with it (they stay tallied
 * EXPORTED at the village, and the capital tallies the whole load LOST, as it always has), and the village notes WAYLAID in its
 * fortunes. A Closed road's wagon that nobody came to escort never set out, and makes no call.
 *
 * **Never a block.** Nothing waits for a village: a wagon whose village has no record, is paused or lost, or has no market, runs as it
 * always has, and every hook here is caught so that a failure cannot stop a capital's wagon. The mail is kept to [MAIL] transfers (the
 * oldest two merged at the later's hour).
 *
 * What each village's wagons did is kept in the store "village_wagons" ([Book]: the last [TRIPS] trips, when a wagon last came back,
 * and a relief owed), which `village cart wagon`, the scenes and the pedlar's rule read. With the switch off ([VillageLinks.enabled])
 * nothing here runs and nothing is written.
 */
object VillageWagons {
    /** The most hours a village's market is run on to a wagon's hour as it sets out. */
    const val RUN_TO = 24

    /** The most transfers a village's mail keeps (the oldest are merged). */
    const val MAIL = 24

    /** A broken band's relief: the road's next call brings this many caravans' share at once, two days' load. */
    const val RELIEF_TIMES = 6

    /** Trips kept for each village. */
    private const val TRIPS = 8

    /** Clock ticks in a market hour. */
    private const val HOUR = 1000L

    /** How a trip ended ([Trip.end]): still out, home, waylaid, never set out, or called off before it was back. */
    const val OUT = "out"
    const val HOME = "home"
    const val WAYLAID = "waylaid"
    const val WITHDRAWN = "withdrawn"
    const val CALLED = "called"

    fun enabled(): Boolean = VillageLinks.enabled()

    // ---- What is kept ----

    /**
     * One of a village's wagons: the [caravan] (its record's id) of [capital] by [road], the market hour it set out ([out]) and was back
     * ([back], [NONE] while out), what it took of the village's ([gave]) and brought it ([brought], of which [fromCapital] was the capital's
     * own surplus), how it ended ([end]) and where on the road it was waylaid ([place]).
     */
    class Trip(
        val caravan: Long, val capital: Long, val road: Int, val out: Long, var back: Long,
        val gave: Map<String, Double>, var brought: Map<String, Double>, var fromCapital: Map<String, Double>, var end: String, var place: String,
    ) {
        companion object {
            val CODEC: Codec<Trip> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("caravan").forGetter(Trip::caravan),
                    Codec.LONG.fieldOf("capital").forGetter(Trip::capital),
                    Codec.INT.optionalFieldOf("road", -1).forGetter(Trip::road),
                    Codec.LONG.optionalFieldOf("out", NONE).forGetter(Trip::out),
                    Codec.LONG.optionalFieldOf("back", NONE).forGetter(Trip::back),
                    GOODS.optionalFieldOf("gave", mapOf()).forGetter(Trip::gave),
                    GOODS.optionalFieldOf("brought", mapOf()).forGetter(Trip::brought),
                    GOODS.optionalFieldOf("from_capital", mapOf()).forGetter(Trip::fromCapital),
                    Codec.STRING.optionalFieldOf("end", OUT).forGetter(Trip::end),
                    Codec.STRING.optionalFieldOf("place", "").forGetter(Trip::place),
                ).apply(i, ::Trip)
            }
        }
    }

    /**
     * A village's wagons: the market hour one last came back with the capital's goods ([lastIn]), whether the road's next call brings the
     * relief ([relief]: a band on one of its roads was broken), its last trips, the news the wagons and the pedlar brought ([news], read by
     * [VillageNews]) and the day the pedlar's news was last kept ([pedlarDay]).
     */
    class Book(
        var lastIn: Long = NONE,
        var relief: Boolean = false,
        val trips: MutableList<Trip> = ArrayList(),
        val news: MutableList<VillageNews.Heard> = ArrayList(),
        var pedlarDay: Long = NONE,
    ) {
        companion object {
            val CODEC: Codec<Book> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.optionalFieldOf("last_in", NONE).forGetter(Book::lastIn),
                    Codec.BOOL.optionalFieldOf("relief", false).forGetter(Book::relief),
                    keptList(Trip.CODEC).lenientOptionalFieldOf("trips", listOf()).forGetter { it.trips },
                    keptList(VillageNews.Heard.CODEC).lenientOptionalFieldOf("news", listOf()).forGetter { it.news },
                    Codec.LONG.optionalFieldOf("pedlar_day", NONE).forGetter(Book::pedlarDay),
                ).apply(i) { lastIn, relief, trips, news, pedlarDay -> Book(lastIn, relief, ArrayList(trips), ArrayList(news), pedlarDay) }
            }
        }
    }

    /** Every village's book, by its bell. */
    class Store(val books: MutableMap<Long, Book> = HashMap()) : SavedData() {
        fun changed() = setDirty()

        companion object {
            private class Entry(val key: Long, val book: Book)

            private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("village").forGetter(Entry::key),
                    Book.CODEC.fieldOf("book").forGetter(Entry::book),
                ).apply(i, ::Entry)
            }

            private val CODEC: Codec<Store> = RecordCodecBuilder.create { i ->
                i.group(
                    keptList(ENTRY).lenientOptionalFieldOf("villages", listOf()).forGetter { s -> s.books.entries.sortedBy { it.key }.map { Entry(it.key, it.value) } },
                ).apply(i) { list -> Store(list.associateTo(HashMap()) { it.key to it.book }) }
            }

            val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_wagons"), Supplier { Store() }, CODEC)
        }
    }

    private val GOODS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)

    fun store(level: ServerLevel): Store = level.server.overworld().dataStorage.computeIfAbsent(Store.TYPE)

    /** The village [key]'s book, made if [make] and there is none. */
    fun book(level: ServerLevel, key: Long, make: Boolean = false): Book? {
        val store = store(level)
        return store.books[key] ?: if (make) Book().also { store.books[key] = it } else null
    }

    // ---- Out: the wagon sets out from the road end ----

    /** The wagon [c] of [entry]'s capital sets out (`Caravans.depart`, after the capital's load is worked out): the village's share goes aboard. */
    fun load(level: ServerLevel, entry: Markets.Entry, c: CaravanRec) {
        // A trading house's wagon carries a ticket's goods, not the capital's (trader design 11.4, need 2)
        if (c.run == Run.HOUSE) return
        if (c.run != Run.WAGON && c.run != Run.CONVOY) return
        if (c.road < 0 || !enabled()) return
        try {
            send(level, c)
        } catch (e: RuntimeException) {
            // The capital's wagon goes as it always has
            Guildmark.LOGGER.warn("Wagon #{} into the capital at {} could not take on its village's goods", c.id, BlockPos.of(entry.market.capital).toShortString(), e)
        }
    }

    private fun send(level: ServerLevel, c: CaravanRec) {
        val rec = VillageLinks.villageOf(level, c.capital, c.road) ?: return
        if (rec.state != Live.LIVE && rec.state != Live.SLEEPING) return
        val v = VillageMarkets.rec(level, rec.key) ?: return
        val hour = Math.floorDiv(c.setOut, HOUR)
        runTo(level, v, hour)
        val n = Good.entries.size
        val give = DoubleArray(n)
        if (!c.escortOnly) {
            val taken = pending(v, out = true)
            for ((g, glut) in Economy.exports(v.entry.market, v.entry.town)) {
                give[g.ordinal] = min(c.load[g.ordinal], glut - taken[g.ordinal]).coerceAtLeast(0.0)
            }
        }
        c.village = rec.key
        c.fromVillage = give
        if (give.any { it > 0.0 }) post(level, rec.key, VillageMarkets.Mail(hour, give, DoubleArray(n)))
        val store = store(level)
        val book = book(level, rec.key, make = true)!!
        book.trips.add(Trip(c.id, c.capital, c.road, hour, NONE, byId(give), mapOf(), mapOf(), OUT, ""))
        while (book.trips.size > TRIPS) book.trips.removeAt(0)
        store.changed()
    }

    /** [v]'s market run on to market [hour], [RUN_TO] hours at most; further behind, it is left as it stands. */
    private fun runTo(level: ServerLevel, v: VillageMarkets.Rec, hour: Long) {
        val m = v.entry.market
        if (m.hour >= hour || m.hour < hour - RUN_TO) return
        var n = 0
        while (m.hour < hour && n < RUN_TO) {
            VillageMarkets.step(level, v, m.hour + 1)
            n++
        }
        VillageBudget.usedSteps(n)
        VillageMarkets.get(level).changed()
    }

    /** What [v]'s mail holds not yet applied: the goods it will take away ([out]) or bring. */
    private fun pending(v: VillageMarkets.Rec, out: Boolean): DoubleArray {
        val sum = DoubleArray(Good.entries.size)
        for (mail in v.mail) {
            if (mail.lost) continue
            val a = if (out) mail.out else mail.inn
            for (g in Good.entries) sum[g.ordinal] += a[g.ordinal]
        }
        return sum
    }

    /** Posts [mail] to the village [key], its mail kept to [MAIL] transfers: the oldest two are merged at the later one's hour. */
    private fun post(level: ServerLevel, key: Long, mail: VillageMarkets.Mail) {
        VillageMarkets.post(key, mail)
        val v = VillageMarkets.rec(level, key) ?: return
        while (v.mail.size > MAIL) {
            val a = v.mail.removeAt(0)
            val b = v.mail.removeAt(0)
            val n = Good.entries.size
            val out = DoubleArray(n) { (if (a.lost) 0.0 else a.out[it]) + (if (b.lost) 0.0 else b.out[it]) }
            val inn = DoubleArray(n) { (if (a.lost) 0.0 else a.inn[it]) + (if (b.lost) 0.0 else b.inn[it]) }
            v.mail.add(0, VillageMarkets.Mail(max(a.hour, b.hour), out, inn, a.lost && b.lost))
        }
    }

    // ---- Back: the wagon is at the road end again ----

    /** The wagon [c] is back at the start of its leg (`Caravans.end`): the capital's exports and the supply from afar go to the village. */
    fun home(level: ServerLevel, c: CaravanRec) {
        // Nor does it bring the village a supply from afar for a trip that is a ticket's
        if (c.run == Run.HOUSE) return
        if (c.run != Run.WAGON && c.run != Run.CONVOY) return
        if (c.village < 0L || !enabled()) return
        try {
            back(level, c)
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("Wagon #{} could not bring its village at {} the capital's goods", c.id, BlockPos.of(c.village).toShortString(), e)
        }
    }

    private fun back(level: ServerLevel, c: CaravanRec) {
        val key = c.village
        val store = store(level)
        val book = book(level, key, make = true)!!
        val trip = book.trips.lastOrNull { it.caravan == c.id }
        val now = Markets.hourOf(level)
        val end = when {
            c.phase == Phase.WAYLAID -> WAYLAID
            c.phase == Phase.WAITING && c.escortOnly -> WITHDRAWN
            c.phase == Phase.LEAVING -> HOME
            else -> CALLED
        }
        val rec = Villages.byKey(level, key)
        val v = VillageMarkets.rec(level, key)
        trip?.end = end
        trip?.back = now
        if (end == WAYLAID) trip?.place = c.place
        store.changed()
        // Taken on the road: the village waits for it in vain at its road end, when it was due
        if (end == WAYLAID) SendOff.waylaid(level, key, c)
        // Taken on the road, or never set out: no call this trip
        if (end == WAYLAID || end == WITHDRAWN) return
        // A village lost or dropped since, or whose market is gone: nobody to bring it to
        if (rec == null || rec.state == Live.LOST || v == null) return
        val m = v.entry.market
        val stamp = if (c.phase == Phase.LEAVING) min(Math.floorDiv(c.until, HOUR), now) else now
        val last = min(max(v.cart.lastHour, book.lastIn), stamp)
        val hours = if (last <= NONE) 24L else (stamp - last).coerceAtLeast(1L)
        var times = Economy.callTimes(hours)
        if (book.relief) times = max(times, RELIEF_TIMES)
        val inn = Economy.bring(m, v.entry.town, first = true, coming = pending(v, out = false), times = times)
        val n = Good.entries.size
        val own = DoubleArray(n) { min(inn[it], c.exports.getOrElse(it) { 0.0 }).coerceAtLeast(0.0) }
        post(level, key, VillageMarkets.Mail(stamp, DoubleArray(n), inn))
        book.lastIn = max(book.lastIn, stamp)
        book.relief = false
        trip?.brought = byId(inn)
        trip?.fromCapital = byId(own)
        store.changed()
        VillageNews.carried(level, key, c.capital)
    }

    // ---- What the pedlar's rule asks ----

    /**
     * Whether the wagons are [rec]'s supply on [day] (so the pedlar comes on market day only: design 7.4): its links' wagons really run,
     * and one of its roads is open, or a wagon came back with the capital's goods in the last [PedlarRoster.WAGON_DAYS] days. A road
     * Troubled or worse with no wagon in for that long, and the pedlar takes up their rounds again.
     */
    fun serves(level: ServerLevel, rec: VillageRec, day: Long): Boolean {
        val links = VillageLinks.running(level, rec.key)
        if (links.isEmpty()) return false
        val data = CaravanData.get(level)
        if (links.any { RoadPeril.stage(data, it.capital, it.road) == RoadPeril.Stage.OPEN }) return true
        val last = book(level, rec.key)?.lastIn ?: NONE
        if (last == NONE) return false
        return day - Conditions(min(last, Markets.hourOf(level))).day <= PedlarRoster.WAGON_DAYS
    }

    /** Whether every road to [rec] is Closed (and it has one): no wagon goes but with an escort, and the pedlar does not come either. */
    fun shut(level: ServerLevel, rec: VillageRec): Boolean {
        val links = VillageLinks.linked(level, rec.key)
        if (links.isEmpty()) return false
        val data = CaravanData.get(level)
        return links.all { RoadPeril.stage(data, it.capital, it.road) == RoadPeril.Stage.CLOSED }
    }

    // ---- Who goes out to the wagon ----

    /**
     * The village's helpers at the wagon on its day (`Hands.Crew.wagon`): while it is linked, its carters ([carters]), and if it has
     * only one, the first citizen with nothing else to do ([taken] are the day's other duties), so two go out to every wagon. None for
     * a village no wagon calls on.
     */
    fun hands(level: ServerLevel, centre: BlockPos, living: List<Resident>, carters: Set<Int>, taken: Set<Int>): Set<Int> {
        if (VillageLinks.running(level, centre.asLong()).isEmpty()) return setOf()
        if (carters.size >= 2) return carters
        val more = living.filter { it.role == TownRole.CITIZEN && it.id !in taken && it.id !in carters }.minByOrNull { it.id }
        return carters + setOfNotNull(more?.id)
    }

    // ---- The wagons' listener ----

    /**
     * The second listener on the capitals' wagons (the first is the capital's fortunes): a village's wagon waylaid is WAYLAID in the
     * village's fortunes too, seen in safe by a player is ESCORTED, and a band broken on a village's road sends the capital its relief
     * and the village its own on the road's next call.
     */
    object Heard : Caravans.Listener {
        override fun setOut(level: ServerLevel, caravan: Caravans.View) {
            if (!enabled()) return
            val c = CaravanData.get(level).caravan(caravan.id) ?: return
            if (c.run != Run.RELIEF) return
            val rec = VillageLinks.villageOf(level, c.capital, c.road) ?: return
            val book = book(level, rec.key, make = true)!!
            book.relief = true
            store(level).changed()
            Fortunes.note(level, rec.key, Noted.RELIEF, listOf("any"))
        }

        override fun struck(level: ServerLevel, caravan: Caravans.View, strike: Caravans.Strike) {
            if (!enabled() || !strike.waylaid) return
            val c = CaravanData.get(level).caravan(caravan.id) ?: return
            if (c.village < 0L) return
            Fortunes.note(level, c.village, Noted.WAYLAID, listOf(c.place, "any"))
        }

        override fun arrived(level: ServerLevel, caravan: Caravans.View) {
            val escort = caravan.escort ?: return
            if (!enabled()) return
            val c = CaravanData.get(level).caravan(caravan.id) ?: return
            if (c.village < 0L) return
            val name = level.server.playerList.getPlayer(escort)?.name?.string
            Fortunes.note(level, c.village, Noted.ESCORTED, listOfNotNull(name), escort)
        }
    }

    // ---- Small things ----

    /** The goods of [a] there are any of, by id. */
    fun byId(a: DoubleArray): Map<String, Double> = Good.entries.filter { a.getOrElse(it.ordinal) { 0.0 } > 1e-9 }.associate { it.id to a[it.ordinal] }

    /** The world closed: nothing kept in memory carries over. */
    fun forget() {}

    /** Once in a while (each 200 ticks, from [VillageLinks]): a clock set back leaves no wagon in the future. */
    fun look(level: ServerLevel) {
        val store = store(level)
        if (store.books.isEmpty()) return
        val now = Markets.hourOf(level)
        var changed = false
        for (book in store.books.values) {
            if (book.lastIn != NONE && book.lastIn > now) {
                book.lastIn = now
                changed = true
            }
        }
        if (changed) store.changed()
    }
}
