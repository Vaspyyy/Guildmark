package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.village.live.days.VillageActs
import io.github.vaspyyy.guildmark.village.live.days.VillageDays
import io.github.vaspyyy.guildmark.village.live.days.VillageLife
import io.github.vaspyyy.guildmark.village.live.days.VillageWatch
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.Random
import kotlin.math.roundToInt

/** What someone is about for a stretch of their day (see [Days]); [phase] is the old timetable's word for it. */
enum class Doing(val phase: Phase) {
    /** In bed. */
    SLEEP(Phase.SLEEP),
    /** Up: a yawn and a stretch, a wash at the basin or the well. */
    RISE(Phase.WAKE),
    BREAKFAST(Phase.WAKE),
    /** At their trade. */
    WORK(Phase.WORK),
    /** A guard's watch. */
    WATCH(Phase.WORK),
    LUNCH(Phase.FREE),
    /** Shopping at the stalls, and a basket home. */
    MARKET(Phase.FREE),
    /** Water fetched from the well. */
    WATER(Phase.FREE),
    /** Something taken across town, or called for. */
    ERRAND(Phase.FREE),
    /** Calling on a neighbour. */
    VISIT(Phase.FREE),
    /** A prayer at the temple, or the rest day's service. */
    WORSHIP(Phase.FREE),
    /** A turn about the town. */
    STROLL(Phase.FREE),
    /** Children at their letters, at the temple. */
    LESSONS(Phase.WORK),
    /** Children at play. */
    PLAY(Phase.FREE),
    SUPPER(Phase.HOME),
    /** The tavern of an evening. */
    TAVERN(Phase.EVENING),
    /** At home of an evening, by the fire. */
    EVENING(Phase.HOME),
    /** Home and up to bed. */
    BED(Phase.HOME),
    /** Hauling the town's goods between the farms, the mill, the bakery and the market (see [Trades.haul]). */
    CART(Phase.WORK),
    /** The town crier at the market board: the prices chalked up of a morning, the news cried at noon (see [Crier]). */
    CRY(Phase.WORK),
    /** Lent to the town's works for the day: at the site from seven till sunset (see [Labour]). */
    BUILD(Phase.WORK),
}

/** A stretch of a day: [doing] from [start] ticks into it until the next stretch starts. */
class Stretch(val start: Int, val doing: Doing, val tag: String = "") {
    override fun toString(): String = "${Days.hourOf(start)} ${doing.name.lowercase()}" + if (tag.isEmpty()) "" else " ($tag)"
}

/** One person's day number [day]: its [stretches] in order, the first starting as the day does. */
class Agenda(val day: Long, val stretches: List<Stretch>) {
    /** The stretch [offset] ticks into the day is in. */
    fun at(offset: Int): Stretch = stretches.lastOrNull { it.start <= offset } ?: stretches.first()
}

/**
 * A capital's people's days. Each day, from two in the morning to two the next, is an [Agenda]: stretches of
 * sleep, a wash and breakfast, work at their trade, lunch, the market, the well, an errand or a visit, supper, the
 * tavern or an evening at home, and bed, laid out by their trade and their ways ([Trait]): the early riser is up
 * an hour before the street, the devout stop at the temple, the drinker is at the tavern every night. Every
 * seventh day is a rest day: the service, the market, visits. A day is worked out from who they are and which
 * day it is alone, so it's the same whenever it's asked for, and someone out of sight can simply be where their
 * day has them. What they do in each stretch is in [Acts].
 */
object Days {
    /** A day starts this many ticks before the clock's own (six in the morning): at two, with nearly everyone abed. */
    private const val EARLY = 4000

    fun enabled(): Boolean = try {
        GuildmarkConfig.LIVING_WORLD.get()
    } catch (e: IllegalStateException) {
        true
    }

    fun dayOf(time: Long): Long = Math.floorDiv(time + EARLY, 24000L)

    fun offsetOf(time: Long): Int = Math.floorMod(time + EARLY, 24000L).toInt()

    /** The clock [hour] (6.5 is half past six in the morning; 25.5 half past one the next night) as ticks into a day. */
    fun clock(hour: Double): Int = Math.floorMod(((hour - 2.0) * 1000.0).roundToInt(), 24000)

    /** The clock time [offset] ticks into a day, as "07:30". */
    fun hourOf(offset: Int): String {
        val minutes = (offset * 60 / 1000 + 120) % (24 * 60)
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    /** Every seventh day the whole town rests. */
    fun restDay(day: Long): Boolean = Math.floorMod(day, 7L) == 6L

    /**
     * [p]'s life, if they're one of a capital's people living days of their own (not a passer-by, a captive, a
     * faction's own, or anyone the Living World is switched off for). Looked up now and then, then kept.
     */
    fun life(p: Townsperson): Life? {
        if (p.resident < 0 || !enabled()) return null
        p.life?.let { if (it.resident.id == p.resident && it.resident.alive && !VillageLife.stale(p, it)) return it.also { life -> duties(p, life) } }
        if (p.tickCount < p.lifeLooked) return null
        p.lifeLooked = p.tickCount + 200
        if (p.crowd || p.watcher || p.captiveOf >= 0 || p.tale >= 0 || Faction.of(p.role) != null) return null
        val level = p.level() as? ServerLevel ?: return null
        val resident = Residents.get(level).of(p)?.takeIf { it.alive } ?: return null
        // Not a capital's: a village's, if the resident is one of its folk (see [VillageLife])
        val capital = Capitals.covering(level, resident.capital) ?: return VillageLife.of(p, level, resident)?.also { life ->
            duties(p, life)
            p.life = life
        }
        val places = CityPlaces.of(level, capital) ?: return null
        val home = places.lodgings[resident.home]
        val life = Life(resident, places, home)
        life.nightWatch = p.nightWatch
        duties(p, life)
        // Home is the house they live in now, not where they happened to be set down
        home?.front?.let { p.home = it.inside }
        p.life = life
        return life
    }

    /** Whether [life] carts for the town today, keeps one of its mills, cries its news or is lent to its works (see [Hands]): worked out afresh each day. */
    private fun duties(p: Townsperson, life: Life) {
        val level = p.level() as? ServerLevel ?: return
        val day = dayOf(level.overworldClockTime)
        if (life.dutiesDay == day) return
        life.dutiesDay = day
        val crew = Hands.of(level, life.places)
        life.carter = life.resident.id in crew.carters
        life.mill = crew.millers[life.resident.id]?.let { life.places.lodgings[it] }
        life.crier = life.resident.id == crew.crier
        life.labourer = life.resident.id in crew.labourers
        // A village's market day, its stall, its bell and its board (see VillageHands)
        if (life.places.kind == TownKind.VILLAGE) {
            life.market = crew.market
            life.keeper = crew.keepers[life.resident.id] ?: -1
            life.bellman = life.resident.id == crew.bellman
            life.poster = life.resident.id == crew.poster
            life.lamps = life.resident.id in crew.lamps
            if (life.resident.role == TownRole.GUARD) {
                life.nightWatch = life.resident.id in crew.night
                p.nightWatch = life.nightWatch
            }
        }
    }

    /**
     * What [p] does next in [stretch]: its next part, or once those are done, what fills the rest of it. Someone
     * who keeps balking (a seat taken, a door they can't reach) stands a while before trying again.
     */
    fun next(p: Townsperson, life: Life, stretch: Stretch): Task {
        if (life.balked >= 3) {
            life.balked = 0
            return Idle(p, 100 + p.random.nextInt(100))
        }
        // Up out of bed, for whatever they were still abed for when the world was saved
        if (p.isSleeping && stretch.doing != Doing.SLEEP && stretch.doing != Doing.BED) p.stopSleeping()
        // Off a watchtower's platform by its ladder when the watch is over, rather than walked off its edge
        val village = life.places.kind == TownKind.VILLAGE
        (if (village) VillageWatch.down(p, life, stretch) else Lookout.down(p, life, stretch))?.let { return it }
        while (life.step < life.script.size) {
            val part = life.script[life.step++]
            if (!part.laden) p.carried = ItemStack.EMPTY
            part.make()?.let { return it }
        }
        p.carried = ItemStack.EMPTY
        return if (village) VillageActs.filler(p, life, stretch) else Acts.filler(p, life, stretch.doing)
    }

    /** [life] is in [stretch] from now on: its parts are laid out afresh. */
    fun begin(p: Townsperson, life: Life, stretch: Stretch) {
        life.stretch = stretch
        life.step = 0
        life.balked = 0
        life.bound = null
        life.script = if (life.places.kind == TownKind.VILLAGE) VillageActs.script(p, life, stretch) else Acts.script(p, life, stretch.doing)
    }

    /** [life]'s day as it stands at [time], one line a stretch, the one they're in marked. */
    fun describe(life: Life, time: Long): List<String> {
        val now = stretch(life, time)
        val agenda = life.agenda ?: return listOf()
        return agenda.stretches.map { (if (it === now) "> " else "  ") + it }
    }

    /** The stretch of [life]'s day it is at [time]. */
    fun stretch(life: Life, time: Long): Stretch {
        val day = dayOf(time)
        val agenda = life.agenda?.takeIf { it.day == day } ?: plan(life, day).also { life.agenda = it }
        return agenda.at(offsetOf(time))
    }

    /** [life]'s day number [day]. */
    fun plan(life: Life, day: Long): Agenda =
        plan(life.resident, day, life.places.lodgings.values.any { it.kind in CityPlaces.TEMPLES || it.kind == "LIBRARY" }, life.nightWatch, life.carter, life.crier, life.labourer).let {
            if (life.places.kind == TownKind.VILLAGE) VillageDays.shape(life, it, day) else it
        }

    /**
     * [r]'s day number [day], where children go to [lessons] if there's a temple or a library, a guard may keep the
     * [nightWatch], and a citizen may be one of the town's carters ([carter]), its crier ([crier]) or lent to its
     * works for the day ([labourer]: a building day, whatever day it is).
     */
    fun plan(r: Resident, day: Long, lessons: Boolean, nightWatch: Boolean, carter: Boolean = false, crier: Boolean = false, labourer: Boolean = false): Agenda {
        val d = Planner(r, day)
        val rest = restDay(day)
        when (r.role) {
            TownRole.BAKER -> if (rest) d.restDay() else d.baker(Economy.ahead(day))
            TownRole.CITIZEN -> if (crier) d.crier(rest) else if (labourer) d.labourer() else if (rest) d.restDay() else if (carter) d.carter() else d.worker()
            TownRole.INNKEEPER -> d.innkeeper()
            TownRole.GUARD -> if (nightWatch) d.nightWatch() else d.dayWatch()
            TownRole.FARMER -> if (rest) d.restDay() else d.farmer()
            TownRole.FISHER -> if (rest) d.restDay() else d.fisher()
            TownRole.PRIEST -> d.priest(rest)
            TownRole.NOBLE -> d.noble()
            TownRole.CHILD -> d.child(rest, lessons)
            TownRole.BARD -> d.bard()
            TownRole.MERCHANT -> if (rest) d.restDay() else d.worker(until = 17.5)
            else -> if (rest) d.restDay() else d.worker()
        }
        return d.build()
    }

    /** Lays out one day for [r]. */
    private class Planner(val r: Resident, val day: Long) {
        val random = Random(r.id * -0x61C8864680B583EBL + day * 0x632BE59BD9B4E019L + r.capital.asLong())
        val list = ArrayList<Stretch>()

        /** A few minutes either way of everyone else. */
        val jitter = (random.nextDouble() * 2 - 1) * 0.3

        /** An hour earlier for the early riser, an hour later for the late. */
        val ways = when {
            r.has(Trait.EARLY) -> -1.0
            r.has(Trait.LATE) -> 1.0
            else -> 0.0
        }

        /** Their own clock; trades with set hours (the bakehouse, the inn) leave them only half their ways. */
        val own = jitter + ways
        val half = jitter + ways / 2

        /** [doing] from [hour] on their clock ([shift] hours off everyone's); [keep] holds it to the hour exactly. */
        fun at(hour: Double, doing: Doing, keep: Boolean = false, shift: Double = own) {
            val h = if (keep) hour else hour + shift + (random.nextDouble() * 2 - 1) * 0.08
            list.add(Stretch(Days.clock(h.coerceIn(2.0, 25.95)), doing))
        }

        fun chance(n: Int, outOf: Int): Boolean = random.nextInt(outOf) < n

        /** One of the things to do with an hour free, as their ways lean. */
        fun free(): Doing {
            val choices = ArrayList<Doing>()
            fun add(d: Doing, n: Int) = repeat(n.coerceAtLeast(0)) { choices.add(d) }
            add(Doing.MARKET, 3)
            add(Doing.WATER, 2)
            add(Doing.ERRAND, 2)
            add(Doing.VISIT, 2 + (if (r.has(Trait.CHATTY)) 2 else 0) - (if (r.has(Trait.SHY)) 2 else 0))
            add(Doing.STROLL, 1 + (if (r.role == TownRole.NOBLE) 2 else 0))
            add(Doing.WORSHIP, 1 + (if (r.has(Trait.DEVOUT)) 3 else 0))
            add(Doing.EVENING, if (r.has(Trait.IDLE)) 2 else 1)
            return choices[random.nextInt(choices.size)]
        }

        /** Whether tonight is a tavern night. */
        fun tavern(): Boolean = when {
            r.has(Trait.DRINKER) -> true
            r.has(Trait.SHY) || r.has(Trait.DEVOUT) -> chance(1, 6)
            else -> chance(2, 5)
        }

        fun evening(hour: Double) = at(hour, if (tavern()) Doing.TAVERN else Doing.EVENING)

        // --- The days of each trade ---

        fun worker(until: Double = 17.0, work: Doing = Doing.WORK, begin: Double = 7.0) {
            at(6.0, Doing.RISE)
            at(6.4, Doing.BREAKFAST)
            var start = begin
            if (r.has(Trait.DEVOUT) && chance(1, 2)) {
                at(begin - 0.1, Doing.WORSHIP)
                start = begin + 0.5
            }
            at(start, work)
            at(12.0, Doing.LUNCH)
            at(12.75, work)
            // The diligent stay on, the idle slip off early; everyone's done by supper
            val end = (until + (if (r.has(Trait.DILIGENT)) 0.75 else 0.0) - (if (r.has(Trait.IDLE)) 0.75 else 0.0)).coerceAtMost(17.75)
            at(end, free())
            if (end <= 17.0) at(end + 0.75, free())
            at(18.5, Doing.SUPPER)
            evening(19.4)
            at(21.5, Doing.BED)
            at(22.0, Doing.SLEEP)
        }

        /** [crier]: the news is cried on the rest day too, after the service (see [crier]). */
        fun restDay(crier: Boolean = false) {
            // Up in time for the service, however late they like to lie in
            val morning = minOf(own, 0.5)
            at(7.0, Doing.RISE, shift = morning)
            at(7.5, Doing.BREAKFAST, shift = morning)
            if (!r.has(Trait.IDLE) || chance(1, 2)) at(8.75, Doing.WORSHIP, keep = true)
            at(10.25, Doing.MARKET)
            if (crier) {
                at(11.0, Doing.CRY, keep = true)
                at(13.5, Doing.LUNCH, keep = true)
            } else {
                at(12.0, Doing.LUNCH)
            }
            at(if (crier) 14.25 else 13.5, if (chance(1, 2)) Doing.VISIT else Doing.STROLL)
            at(15.25, free())
            at(18.0, Doing.SUPPER)
            at(19.0, if (tavern() || chance(1, 2)) Doing.TAVERN else Doing.EVENING)
            at(22.0, Doing.BED)
            at(22.5, Doing.SLEEP)
        }

        /** Out to the farms after breakfast, and to and fro with the loads until five, as the carts go ([Economy.carting]). */
        fun carter() = worker(work = Doing.CART, begin = 7.25)

        /**
         * A building day ([Labour]): at the works at seven, however late they like to lie in, an hour off at noon, and
         * let go at sunset, as the work clock goes (daylight only); home then, with supper and the evening after.
         */
        fun labourer() {
            val morning = minOf(own, 0.25)
            at(6.0, Doing.RISE, shift = morning)
            at(6.4, Doing.BREAKFAST, shift = morning)
            at(7.0, Doing.BUILD, keep = true)
            at(12.0, Doing.LUNCH, keep = true)
            at(13.0, Doing.BUILD, keep = true)
            at(Labour.SUNSET, Doing.EVENING, keep = true)
            // Nobody's sat down to supper before they're let go
            val after = maxOf(own, 0.0)
            at(18.5, Doing.SUPPER, shift = after)
            at(19.4, if (tavern()) Doing.TAVERN else Doing.EVENING, shift = after)
            at(21.5, Doing.BED)
            at(22.0, Doing.SLEEP)
        }

        /**
         * The town crier: the market board's prices chalked up first thing, their own work until eleven, then to the
         * square to read the news over, cry it at noon on the dot and write the board up again, a late lunch, and
         * work again until five. On the rest day only the noon round.
         */
        fun crier(rest: Boolean) {
            if (rest) return restDay(crier = true)
            at(6.0, Doing.RISE)
            at(6.4, Doing.BREAKFAST)
            at(7.0, Doing.CRY)
            at(7.75, Doing.WORK)
            at(11.0, Doing.CRY, keep = true)
            at(13.5, Doing.LUNCH, keep = true)
            at(14.25, Doing.WORK)
            val end = (17.0 + (if (r.has(Trait.DILIGENT)) 0.75 else 0.0) - (if (r.has(Trait.IDLE)) 0.75 else 0.0)).coerceAtMost(17.75)
            at(end, free())
            if (end <= 17.0) at(end + 0.75, free())
            at(18.5, Doing.SUPPER)
            evening(19.4)
            at(21.5, Doing.BED)
            at(22.0, Doing.SLEEP)
        }

        /** The bread is in the oven long before the town is up; the day before the rest day it bakes on past noon for that day too. */
        fun baker(ahead: Boolean) {
            at(3.75, Doing.RISE, shift = half)
            at(4.25, Doing.WORK, shift = half)
            if (ahead) {
                at(13.75, Doing.LUNCH, shift = half)
                at(14.5, free(), shift = half)
            } else {
                at(11.5, Doing.LUNCH, shift = half)
                at(12.25, free(), shift = half)
                at(13.5, Doing.EVENING, shift = half)
                at(15.0, free(), shift = half)
            }
            at(17.0, Doing.SUPPER, shift = half)
            at(18.0, Doing.EVENING, shift = half)
            at(19.5, Doing.BED, shift = half)
            at(20.0, Doing.SLEEP, shift = half)
        }

        /** The inn never closes before one. */
        fun innkeeper() {
            at(8.5, Doing.RISE, shift = half)
            at(9.0, Doing.BREAKFAST, shift = half)
            at(9.75, Doing.WORK, shift = half)
            at(14.5, Doing.MARKET, shift = half)
            at(15.5, Doing.WORK, shift = half)
            at(25.0, Doing.BED, keep = true)
            at(25.5, Doing.SLEEP, keep = true)
        }

        fun dayWatch() {
            at(5.5, Doing.RISE)
            at(6.0, Doing.BREAKFAST)
            at(6.5, Doing.WATCH)
            at(12.0, Doing.LUNCH)
            at(12.5, Doing.WATCH)
            at(18.0, free())
            at(18.75, Doing.SUPPER)
            evening(19.5)
            at(21.75, Doing.BED)
            at(22.25, Doing.SLEEP)
        }

        /** On the walls all night, abed all morning. */
        fun nightWatch() {
            // The watch changes at the same hour for everyone on it
            at(2.0, Doing.WATCH, keep = true)
            at(6.5, Doing.BED, keep = true)
            at(7.0, Doing.SLEEP, keep = true)
            at(15.0, Doing.RISE, shift = jitter)
            at(15.5, Doing.BREAKFAST, shift = jitter)
            at(16.0, free(), shift = jitter)
            at(17.0, Doing.SUPPER, shift = jitter)
            at(17.75, Doing.WATCH, keep = true)
        }

        fun farmer() {
            at(5.0, Doing.RISE)
            at(5.4, Doing.BREAKFAST)
            at(6.0, Doing.WORK)
            at(12.0, Doing.LUNCH)
            at(12.75, Doing.WORK)
            at(18.0, Doing.SUPPER)
            evening(19.0)
            at(21.0, Doing.BED)
            at(21.5, Doing.SLEEP)
        }

        /** Out on the water before dawn. */
        fun fisher() {
            at(4.0, Doing.RISE)
            at(4.5, Doing.WORK)
            at(11.0, Doing.LUNCH)
            at(12.0, Doing.MARKET)
            at(13.0, free())
            at(15.0, Doing.EVENING)
            at(18.0, Doing.SUPPER)
            evening(19.0)
            at(21.0, Doing.BED)
            at(21.5, Doing.SLEEP)
        }

        /** Prayers from first light; on the rest day, the service. */
        fun priest(rest: Boolean) {
            at(5.5, Doing.RISE)
            at(5.9, Doing.BREAKFAST)
            at(6.25, Doing.WORK)
            at(12.0, Doing.LUNCH)
            at(13.0, Doing.WORK)
            at(16.5, if (rest) Doing.STROLL else Doing.VISIT)
            at(18.25, Doing.SUPPER)
            at(19.25, Doing.EVENING)
            at(21.0, Doing.BED)
            at(21.5, Doing.SLEEP)
        }

        fun noble() {
            at(8.0, Doing.RISE)
            at(8.5, Doing.BREAKFAST)
            at(10.0, Doing.STROLL)
            at(12.5, Doing.LUNCH)
            at(14.0, if (chance(1, 2)) Doing.VISIT else Doing.WORSHIP)
            at(15.5, Doing.STROLL)
            at(17.25, Doing.MARKET)
            at(18.5, Doing.SUPPER)
            at(19.75, if (chance(1, 5)) Doing.TAVERN else Doing.EVENING)
            at(23.0, Doing.BED)
            at(23.5, Doing.SLEEP)
        }

        fun child(rest: Boolean, lessons: Boolean) {
            val morning = if (rest) minOf(own, 0.5) else own
            at(7.0, Doing.RISE, shift = morning)
            at(7.4, Doing.BREAKFAST, shift = morning)
            if (rest) {
                at(8.75, Doing.WORSHIP, keep = true)
                at(10.25, Doing.PLAY)
            } else {
                at(8.0, if (lessons) Doing.LESSONS else Doing.PLAY)
                at(11.0, Doing.PLAY)
            }
            at(12.0, Doing.LUNCH)
            at(13.0, Doing.PLAY)
            if (chance(1, 2)) at(15.5, Doing.WATER)
            at(16.25, Doing.PLAY)
            at(18.0, Doing.SUPPER)
            at(19.25, Doing.BED)
            at(19.75, Doing.SLEEP)
        }

        /** Songs in the square by day and at the tavern by night. */
        fun bard() {
            at(9.0, Doing.RISE)
            at(9.5, Doing.BREAKFAST)
            at(10.75, Doing.WORK)
            at(13.0, Doing.LUNCH)
            at(14.0, Doing.WORK)
            at(17.0, Doing.SUPPER)
            at(18.0, Doing.TAVERN)
            at(23.0, Doing.BED)
            at(23.5, Doing.SLEEP)
        }

        fun build(): Agenda {
            list.sortBy { it.start }
            // The day opens asleep unless something else fills its first hours
            if (list.isEmpty() || list[0].start > 0) list.add(0, Stretch(0, Doing.SLEEP))
            return Agenda(day, list)
        }
    }
}

/**
 * One of a capital's people as their body knows them: their [resident] record, their city's [places] and their
 * [home]. Kept on the [Townsperson] ([Townsperson.life]) while they're about, with today's agenda and how far
 * they've got with the stretch they're in.
 */
class Life(val resident: Resident, val places: TownPlaces, val home: CityPlaces.Lodging?) {
    var agenda: Agenda? = null

    /** The stretch they're in, and how many of its parts they've been through. */
    var stretch: Stretch? = null
    var step = 0

    /** The parts of the stretch they're in, made when it began (see [Acts.script]). */
    var script: List<Acts.Part> = listOf()

    /** Tasks in a row that ended as soon as they began (somewhere they couldn't get to, a seat taken). */
    var balked = 0

    /** A guard of the night watch. */
    var nightWatch = false

    /** One of the town's carters today, and the mill they keep if they're its miller (see [Hands]); [dutiesDay] is the day that's for. */
    var carter = false
    var mill: CityPlaces.Lodging? = null
    var dutiesDay = Long.MIN_VALUE

    /** The town crier (see [Hands]), and the days they last chalked the morning's prices and cried the noon news (see [Crier]). */
    var crier = false
    var chalked = Long.MIN_VALUE
    var cried = Long.MIN_VALUE

    /** Lent to the town's works today ([Hands], [Labour]): a building day. */
    var labourer = false

    /**
     * In a village ([Hands], [io.github.vaspyyy.guildmark.village.live.days.VillageHands]): its market day today, the stall they keep
     * today (its index in [TownPlaces.stalls], or -1), whether they hold the bell for the week and whether they pin the board's note.
     */
    var market = false
    var keeper = -1
    var bellman = false
    var poster = false

    /** In a village: whether they keep a round of its lamps (see [io.github.vaspyyy.guildmark.village.live.VillageLamps]). */
    var lamps = false

    /** For a guard, the road whose watchtower they last kept watch at ([Lookout]), or -1. */
    var tower = -1

    /** Crops (or catches) gathered into their basket since it was last taken in, and what the last of them was (see [Trades.gathered]). */
    var basket = 0
    var gathered: Item? = null

    /** The stall, house or well they're bound for this stretch, once chosen. */
    var bound: Any? = null
}
