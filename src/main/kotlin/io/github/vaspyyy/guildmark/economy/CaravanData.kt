package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/** Where a caravan's record is in its day; [Caravans.State] is how its bodies see it (a planned one isn't seen yet). */
enum class Phase(val id: String) {
    /** Loaded and on the timetable, setting out within the hour. */
    PLANNED("planned"),
    WAITING("waiting"),
    ROLLING("rolling"),
    STRUCK("struck"),
    WAYLAID("waylaid"),
    STANDING("standing"),
    LEAVING("leaving");

    companion object {
        fun of(id: String): Phase = entries.firstOrNull { it.id == id } ?: ROLLING
    }
}

/** What a caravan is: one of the day's wagons, a Perilous road's noon convoy, the relief after a band is broken, one set out by hand, or a trading house's wagon (trade part G). */
enum class Run(val id: String) {
    WAGON("wagon"),
    CONVOY("convoy"),
    RELIEF("relief"),
    TEST("test"),
    HOUSE("house");

    companion object {
        fun of(id: String): Run = entries.firstOrNull { it.id == id } ?: WAGON
    }
}

/**
 * A good and a count off a wagon: a crate or a sack in a wreck or a loot pile (count 0 once taken), a line of what was
 * lost, or what a band carried off; bound for [capital] (its centre) on caravan [caravan], waylaid at [at] (clock ticks).
 */
class Cargo(val good: Good, var count: Int, val capital: Long, val caravan: Long, val at: Long) {
    companion object {
        val CODEC: Codec<Cargo> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.INT.fieldOf("count").forGetter(Cargo::count),
                Codec.LONG.optionalFieldOf("capital", 0L).forGetter(Cargo::capital),
                Codec.LONG.optionalFieldOf("caravan", -1L).forGetter(Cargo::caravan),
                Codec.LONG.optionalFieldOf("at", 0L).forGetter(Cargo::at),
            ).apply(i) { good, count, capital, caravan, at -> Cargo(Good.byId(good) ?: Good.WHEAT, count, capital, caravan, at) }
        }
    }
}

/** Goods by ordinal, kept as a map of ids (fractions and all, as the market keeps them). */
internal object GoodsCodec {
    val CODEC: Codec<DoubleArray> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).xmap(
        { map -> DoubleArray(Good.entries.size).also { a -> for ((id, v) in map) Good.byId(id)?.let { a[it.ordinal] = v } } },
        { a -> Good.entries.filter { a[it.ordinal] != 0.0 }.associate { it.id to a[it.ordinal] } },
    )
}

/**
 * One caravan, from the hour it's loaded to the hour it's gone: the capital it's bound for ([capital], its centre, the
 * market's key; [bell], the bell the roads end at), the [road] it comes by (-1: the plan's own country road, from the
 * farm edge in), the market [hour] it's due, what [run] it is, its [seed], where it is in its day ([phase]); when it
 * set out ([setOut], in ticks of the overworld's clock, pushed on while it waits at the start), how long it's been held
 * up since ([held]) and when its present phase ends ([until]); its [length] of leg, its [load] and the [exports] it
 * drives back out with, its [guards] and [wagons]; then its roll and its escort.
 */
class CaravanRec(
    val id: Long,
    val capital: Long,
    val bell: Long,
    val road: Int,
    val hour: Long,
    val run: Run,
    val seed: Long,
    var phase: Phase,
    var setOut: Long,
    var held: Long,
    var until: Long,
    val length: Double,
    val load: DoubleArray,
    var exports: DoubleArray,
    val guards: Int,
    val wagons: Int,
) {
    /** The band that may strike ([band], or -1), how far along the leg ([strike], -1 for nowhere), and how far its camp lies off the road. */
    var band: Int = -1
    var strike: Double = -1.0
    var reach: Double = 0.0

    /** 0 not at its strike point yet, 1 past it unstruck, 2 struck; when, how it came out and whether a player fought there. */
    var struck: Int = 0
    var struckAt: Long = 0L
    var waylaid: Boolean = false
    var horseDead: Boolean = false
    var fought: Boolean = false

    /**
     * Fixed when it sets out, so it's known if the leg isn't worked out again by the time it's needed (after a
     * restart): what the strike point is like ([place], one of [Caravans.PLACES]), the verge a wreck would lie on
     * there ([wx], [wy], [wz], turned [wyaw]), and the gate its driver would run to ([gate]).
     */
    var place: String = "road"
    var wx: Double = 0.0
    var wy: Double = 0.0
    var wz: Double = 0.0
    var wyaw: Float = 0f
    var gate: Long = 0L

    /** The escort it waits for or goes with: the job ([job], the contract's), its holder, whether they've met, and the waylayers they killed. */
    var job: UUID? = null
    var holder: UUID? = null
    var met: Boolean = false
    var kills: Int = 0

    /** It goes only with its escort (a Closed road's wagon): if they never come, it never goes. */
    var escortOnly: Boolean = false

    /** What it carries for the capital's works besides its load, and whether its strike falls in a tower's watch (see economy/works/WorksFreight, WorksWatch). */
    var freight: List<io.github.vaspyyy.guildmark.economy.works.Freight> = listOf()
    var watched: Boolean = false

    /** The slots of the timetable it carries (market hours), and the day's first among them (it brings the bakehouse's flour). */
    var slots: List<Long> = listOf()

    /** A trading house's wagon's pennant colour (RGB, -1 for the wagon's own) and the House's name on its board ("" for none); trade part G. */
    var tint: Int = -1
    var label: String = ""

    /** Not kept: the game time its bodies last said they're about, and when the record began waiting for them to act an event out. */
    var shown: Long = Long.MIN_VALUE / 2
    var waiting: Long = -1L

    /** Not kept: how a strike called up by hand comes out (`/guildmark caravan ambush`): 1 waylaid, -1 beaten off, 0 by the dice. */
    var rigged: Int = 0

    companion object {
        private class Roll(
            val band: Int, val strike: Double, val reach: Double, val struck: Int, val struckAt: Long,
            val waylaid: Boolean, val horseDead: Boolean, val fought: Boolean, val place: String,
            val wx: Double, val wy: Double, val wz: Double, val wyaw: Float, val gate: Long,
            val watched: Boolean, val freight: List<io.github.vaspyyy.guildmark.economy.works.Freight>,
        )

        private val ROLL: Codec<Roll> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("band", -1).forGetter(Roll::band),
                Codec.DOUBLE.optionalFieldOf("strike", -1.0).forGetter(Roll::strike),
                Codec.DOUBLE.optionalFieldOf("reach", 0.0).forGetter(Roll::reach),
                Codec.INT.optionalFieldOf("struck", 0).forGetter(Roll::struck),
                Codec.LONG.optionalFieldOf("struck_at", 0L).forGetter(Roll::struckAt),
                Codec.BOOL.optionalFieldOf("waylaid", false).forGetter(Roll::waylaid),
                Codec.BOOL.optionalFieldOf("horse_dead", false).forGetter(Roll::horseDead),
                Codec.BOOL.optionalFieldOf("fought", false).forGetter(Roll::fought),
                Codec.STRING.optionalFieldOf("place", "road").forGetter(Roll::place),
                Codec.DOUBLE.optionalFieldOf("wx", 0.0).forGetter(Roll::wx),
                Codec.DOUBLE.optionalFieldOf("wy", 0.0).forGetter(Roll::wy),
                Codec.DOUBLE.optionalFieldOf("wz", 0.0).forGetter(Roll::wz),
                Codec.FLOAT.optionalFieldOf("wyaw", 0f).forGetter(Roll::wyaw),
                Codec.LONG.optionalFieldOf("gate", 0L).forGetter(Roll::gate),
                Codec.BOOL.optionalFieldOf("watched", false).forGetter(Roll::watched),
                io.github.vaspyyy.guildmark.economy.works.Freight.CODEC.listOf().optionalFieldOf("freight", listOf()).forGetter(Roll::freight),
            ).apply(i, ::Roll)
        }

        private class Escort(val job: UUID?, val holder: UUID?, val met: Boolean, val kills: Int, val only: Boolean, val slots: List<Long>, val tint: Int = -1, val label: String = "")

        private val ESCORT: Codec<Escort> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.optionalFieldOf("job").forGetter { java.util.Optional.ofNullable(it.job) },
                UUIDUtil.CODEC.optionalFieldOf("holder").forGetter { java.util.Optional.ofNullable(it.holder) },
                Codec.BOOL.optionalFieldOf("met", false).forGetter(Escort::met),
                Codec.INT.optionalFieldOf("kills", 0).forGetter(Escort::kills),
                Codec.BOOL.optionalFieldOf("only", false).forGetter(Escort::only),
                Codec.LONG.listOf().optionalFieldOf("slots", listOf()).forGetter(Escort::slots),
                Codec.INT.optionalFieldOf("tint", -1).forGetter(Escort::tint),
                Codec.STRING.optionalFieldOf("label", "").forGetter(Escort::label),
            ).apply(i) { job, holder, met, kills, only, slots, tint, label -> Escort(job.orElse(null), holder.orElse(null), met, kills, only, slots, tint, label) }
        }

        private class Times(val setOut: Long, val held: Long, val until: Long)

        private val TIMES: Codec<Times> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("set_out").forGetter(Times::setOut),
                Codec.LONG.optionalFieldOf("held", 0L).forGetter(Times::held),
                Codec.LONG.optionalFieldOf("until", 0L).forGetter(Times::until),
            ).apply(i, ::Times)
        }

        val CODEC: Codec<CaravanRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("id").forGetter(CaravanRec::id),
                Codec.LONG.fieldOf("capital").forGetter(CaravanRec::capital),
                Codec.LONG.fieldOf("bell").forGetter(CaravanRec::bell),
                Codec.INT.optionalFieldOf("road", -1).forGetter(CaravanRec::road),
                Codec.LONG.fieldOf("hour").forGetter(CaravanRec::hour),
                Codec.STRING.optionalFieldOf("run", "wagon").forGetter { it.run.id },
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(CaravanRec::seed),
                Codec.STRING.optionalFieldOf("phase", "rolling").forGetter { it.phase.id },
                TIMES.fieldOf("times").forGetter { Times(it.setOut, it.held, it.until) },
                Codec.DOUBLE.fieldOf("length").forGetter(CaravanRec::length),
                GoodsCodec.CODEC.optionalFieldOf("load", DoubleArray(Good.entries.size)).forGetter(CaravanRec::load),
                GoodsCodec.CODEC.optionalFieldOf("exports", DoubleArray(Good.entries.size)).forGetter(CaravanRec::exports),
                Codec.INT.optionalFieldOf("guards", 2).forGetter(CaravanRec::guards),
                Codec.INT.optionalFieldOf("wagons", 1).forGetter(CaravanRec::wagons),
                ROLL.fieldOf("roll").forGetter {
                    Roll(it.band, it.strike, it.reach, it.struck, it.struckAt, it.waylaid, it.horseDead, it.fought, it.place, it.wx, it.wy, it.wz, it.wyaw, it.gate, it.watched, it.freight)
                },
                ESCORT.fieldOf("escort").forGetter { Escort(it.job, it.holder, it.met, it.kills, it.escortOnly, it.slots, it.tint, it.label) },
            ).apply(i) { id, capital, bell, road, hour, run, seed, phase, times, length, load, exports, guards, wagons, roll, escort ->
                CaravanRec(
                    id, capital, bell, road, hour, Run.of(run), seed, Phase.of(phase), times.setOut, times.held, times.until, length,
                    load, exports, guards, wagons,
                ).also { c ->
                    c.band = roll.band
                    c.strike = roll.strike
                    c.reach = roll.reach
                    c.struck = roll.struck
                    c.struckAt = roll.struckAt
                    c.waylaid = roll.waylaid
                    c.horseDead = roll.horseDead
                    c.fought = roll.fought
                    c.place = roll.place
                    c.wx = roll.wx
                    c.wy = roll.wy
                    c.wz = roll.wz
                    c.wyaw = roll.wyaw
                    c.gate = roll.gate
                    c.watched = roll.watched
                    c.freight = roll.freight
                    c.job = escort.job
                    c.holder = escort.holder
                    c.met = escort.met
                    c.kills = escort.kills
                    c.escortOnly = escort.only
                    c.slots = escort.slots
                    c.tint = escort.tint
                    c.label = escort.label
                }
            }
        }
    }
}

/**
 * A waylaid wagon's wreck by the road, or a broken band's loot pile at its camp ([pile]): where it lies, turned [yaw],
 * its lots (a crate or a sack each), whether the horse lies dead in its traces, and until when it's there (clock ticks).
 */
class WreckRec(
    val id: Long,
    val caravan: Long,
    val capital: Long,
    val bell: Long,
    val road: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val lots: MutableList<Cargo>,
    val horseDead: Boolean,
    val struck: Long,
    var until: Long,
    val pile: Boolean,
    val band: Int,
) {
    val emptied: Boolean get() = lots.none { it.count > 0 }

    companion object {
        private class Where(val x: Double, val y: Double, val z: Double, val yaw: Float)

        private val WHERE: Codec<Where> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.DOUBLE.fieldOf("x").forGetter(Where::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Where::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Where::z),
                Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(Where::yaw),
            ).apply(i, ::Where)
        }

        val CODEC: Codec<WreckRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("id").forGetter(WreckRec::id),
                Codec.LONG.optionalFieldOf("caravan", -1L).forGetter(WreckRec::caravan),
                Codec.LONG.fieldOf("capital").forGetter(WreckRec::capital),
                Codec.LONG.fieldOf("bell").forGetter(WreckRec::bell),
                Codec.INT.optionalFieldOf("road", -1).forGetter(WreckRec::road),
                WHERE.fieldOf("where").forGetter { Where(it.x, it.y, it.z, it.yaw) },
                Cargo.CODEC.listOf().optionalFieldOf("lots", listOf()).forGetter { it.lots },
                Codec.BOOL.optionalFieldOf("horse_dead", false).forGetter(WreckRec::horseDead),
                Codec.LONG.optionalFieldOf("struck", 0L).forGetter(WreckRec::struck),
                Codec.LONG.fieldOf("until").forGetter(WreckRec::until),
                Codec.BOOL.optionalFieldOf("pile", false).forGetter(WreckRec::pile),
                Codec.INT.optionalFieldOf("band", -1).forGetter(WreckRec::band),
            ).apply(i) { id, caravan, capital, bell, road, where, lots, horseDead, struck, until, pile, band ->
                WreckRec(id, caravan, capital, bell, road, where.x, where.y, where.z, where.yaw, lots.toMutableList(), horseDead, struck, until, pile, band)
            }
        }
    }
}

/**
 * A waylaid wagon's driver, who runs back to the gate ([at], reached at [from]) and sits there [until] (clock ticks):
 * what was [lost], the band that took it, and the players they've told, whose maps now have the camp.
 */
class DriverRec(
    val caravan: Long,
    val capital: Long,
    val bell: Long,
    val road: Int,
    val at: BlockPos,
    val from: Long,
    val until: Long,
    val lost: List<Cargo>,
    val band: Int,
    val told: MutableSet<UUID>,
) {
    companion object {
        val CODEC: Codec<DriverRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("caravan").forGetter(DriverRec::caravan),
                Codec.LONG.fieldOf("capital").forGetter(DriverRec::capital),
                Codec.LONG.fieldOf("bell").forGetter(DriverRec::bell),
                Codec.INT.optionalFieldOf("road", -1).forGetter(DriverRec::road),
                BlockPos.CODEC.fieldOf("at").forGetter(DriverRec::at),
                Codec.LONG.fieldOf("from").forGetter(DriverRec::from),
                Codec.LONG.fieldOf("until").forGetter(DriverRec::until),
                Cargo.CODEC.listOf().optionalFieldOf("lost", listOf()).forGetter(DriverRec::lost),
                Codec.INT.optionalFieldOf("band", -1).forGetter(DriverRec::band),
                UUIDUtil.CODEC.listOf().optionalFieldOf("told", listOf()).forGetter { it.told.toList() },
            ).apply(i) { caravan, capital, bell, road, at, from, until, lost, band, told ->
                DriverRec(caravan, capital, bell, road, at, from, until, lost, band, told.toHashSet())
            }
        }
    }
}

/** What [player] ([name]) took from a wreck or a loot pile and hasn't brought back: [count] of [good] off [caravan], bound for [capital], taken at [at]. */
class TakeRec(val player: UUID, val name: String, val capital: Long, val caravan: Long, val good: Good, var count: Int, val at: Long) {
    companion object {
        val CODEC: Codec<TakeRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(TakeRec::player),
                Codec.STRING.optionalFieldOf("name", "").forGetter(TakeRec::name),
                Codec.LONG.fieldOf("capital").forGetter(TakeRec::capital),
                Codec.LONG.optionalFieldOf("caravan", -1L).forGetter(TakeRec::caravan),
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.INT.fieldOf("count").forGetter(TakeRec::count),
                Codec.LONG.fieldOf("at").forGetter(TakeRec::at),
            ).apply(i) { player, name, capital, caravan, good, count, at -> TakeRec(player, name, capital, caravan, Good.byId(good) ?: Good.WHEAT, count, at) }
        }
    }
}

/**
 * A road into a capital as its trouble goes: its [peril] (0 to 12), when it was last struck (clock ticks), the day it
 * last faded, the stage last told, whether a wagon has got in since it was last waylaid ([pending] if not), and the
 * band that last struck it ([band], -1 for none).
 */
class RoadRec(
    val capital: Long, val road: Int, var peril: Int, var struck: Long, var faded: Long, var told: Int, var pending: Boolean,
    var band: Int = -1,
) {
    companion object {
        val CODEC: Codec<RoadRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(RoadRec::capital),
                Codec.INT.fieldOf("road").forGetter(RoadRec::road),
                Codec.INT.optionalFieldOf("peril", 0).forGetter(RoadRec::peril),
                Codec.LONG.optionalFieldOf("struck", Long.MIN_VALUE / 2).forGetter(RoadRec::struck),
                Codec.LONG.optionalFieldOf("faded", -1L).forGetter(RoadRec::faded),
                Codec.INT.optionalFieldOf("told", 0).forGetter(RoadRec::told),
                Codec.BOOL.optionalFieldOf("pending", false).forGetter(RoadRec::pending),
                Codec.INT.optionalFieldOf("band", -1).forGetter(RoadRec::band),
            ).apply(i, ::RoadRec)
        }
    }
}

/** A piece of the road's news for the crier: at market [hour], of [kind] (a [RoadPeril.Stage]'s id, "relief" or "salvage"), on [road], naming [names]. */
class RoadNews(val hour: Long, val kind: String, val road: Int, val names: List<String>) {
    companion object {
        val CODEC: Codec<RoadNews> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("hour").forGetter(RoadNews::hour),
                Codec.STRING.fieldOf("kind").forGetter(RoadNews::kind),
                Codec.INT.optionalFieldOf("road", -1).forGetter(RoadNews::road),
                Codec.STRING.listOf().optionalFieldOf("names", listOf()).forGetter(RoadNews::names),
            ).apply(i, ::RoadNews)
        }
    }
}

/**
 * A capital as its wagons go: its [name], the market hour its wagons were first seen to ([born]), the timetable's
 * slots seen to ([handled]: market hours whose goods come by wagon, or don't come), the slots a convoy already
 * carries ([covered]), the finder's fees owed in fractions ([owed]), who has its keepers cool on them until when
 * ([cool], clock ticks), its road news, whether a band has been let settle on its road (open question 3), and the day
 * a road to it was last asked for ([surveyed]).
 */
class CapitalRec(
    val capital: Long,
    var name: String,
    val born: Long,
    val handled: MutableSet<Long>,
    val covered: MutableSet<Long>,
    val owed: MutableMap<UUID, Double>,
    val cool: MutableMap<UUID, Long>,
    val news: MutableList<RoadNews>,
    var founded: Boolean,
    var surveyed: Long,
) {
    companion object {
        val CODEC: Codec<CapitalRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(CapitalRec::capital),
                Codec.STRING.optionalFieldOf("name", "").forGetter(CapitalRec::name),
                Codec.LONG.optionalFieldOf("born", 0L).forGetter(CapitalRec::born),
                Codec.LONG.listOf().optionalFieldOf("handled", listOf()).forGetter { it.handled.toList() },
                Codec.LONG.listOf().optionalFieldOf("covered", listOf()).forGetter { it.covered.toList() },
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("owed", mapOf()).forGetter { c -> c.owed.mapKeys { it.key.toString() } },
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("cool", mapOf()).forGetter { c -> c.cool.mapKeys { it.key.toString() } },
                RoadNews.CODEC.listOf().optionalFieldOf("news", listOf()).forGetter { it.news },
                Codec.BOOL.optionalFieldOf("founded", false).forGetter(CapitalRec::founded),
                Codec.LONG.optionalFieldOf("surveyed", -1L).forGetter(CapitalRec::surveyed),
            ).apply(i) { capital, name, born, handled, covered, owed, cool, news, founded, surveyed ->
                CapitalRec(
                    capital, name, born, handled.toHashSet(), covered.toHashSet(),
                    owed.mapNotNull { (k, v) -> uuid(k)?.let { it to v } }.toMap(HashMap()),
                    cool.mapNotNull { (k, v) -> uuid(k)?.let { it to v } }.toMap(HashMap()),
                    news.toMutableList(), founded, surveyed,
                )
            }
        }

        private fun uuid(s: String): UUID? = try {
            UUID.fromString(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * An outlaw band as the roads know it: the wagons it has taken, the waylayers it has lost and not yet made up (healing
 * one a day, the day last healed kept), when it last struck (clock ticks), and what it carried off.
 */
class BandRec(val id: Int, var wagons: Int, var wounds: Int, var healed: Long, var struck: Long, val loot: MutableList<Cargo>) {
    companion object {
        val CODEC: Codec<BandRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(BandRec::id),
                Codec.INT.optionalFieldOf("wagons", 0).forGetter(BandRec::wagons),
                Codec.INT.optionalFieldOf("wounds", 0).forGetter(BandRec::wounds),
                Codec.LONG.optionalFieldOf("healed", -1L).forGetter(BandRec::healed),
                Codec.LONG.optionalFieldOf("struck", Long.MIN_VALUE / 2).forGetter(BandRec::struck),
                Cargo.CODEC.listOf().optionalFieldOf("loot", listOf()).forGetter { it.loot },
            ).apply(i) { id, wagons, wounds, healed, struck, loot -> BandRec(id, wagons, wounds, healed, struck, loot.toMutableList()) }
        }
    }
}

/** How an escort job stands. */
enum class EscortState(val id: String) {
    /** Taken: the next wagon on its road waits for its holder at the start of the leg. */
    OPEN("open"),
    /** The holder met the wagon and walks it in. */
    MET("met"),
    /** The wagon got in with the holder by it: the note's to be turned in. */
    DONE("done"),
    /** The wagon was waylaid, or went without them: the job's off. */
    FAILED("failed");

    companion object {
        fun of(id: String): EscortState = entries.firstOrNull { it.id == id } ?: OPEN
    }
}

/**
 * An escort job taken off a capital's board: its [job] (the contract's bound id), its [holder] ([name]), the road and
 * capital, the clock tick it lapses ([until]), the wagon it's with once one waits for it, and the waylayers killed.
 */
class EscortRec(
    val job: UUID, val holder: UUID, val name: String, val capital: Long, val road: Int, val until: Long,
    var state: EscortState, var caravan: Long, var kills: Int,
) {
    companion object {
        val CODEC: Codec<EscortRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("job").forGetter(EscortRec::job),
                UUIDUtil.CODEC.fieldOf("holder").forGetter(EscortRec::holder),
                Codec.STRING.optionalFieldOf("name", "").forGetter(EscortRec::name),
                Codec.LONG.fieldOf("capital").forGetter(EscortRec::capital),
                Codec.INT.fieldOf("road").forGetter(EscortRec::road),
                Codec.LONG.fieldOf("until").forGetter(EscortRec::until),
                Codec.STRING.optionalFieldOf("state", "open").forGetter { it.state.id },
                Codec.LONG.optionalFieldOf("caravan", -1L).forGetter(EscortRec::caravan),
                Codec.INT.optionalFieldOf("kills", 0).forGetter(EscortRec::kills),
            ).apply(i) { job, holder, name, capital, road, until, state, caravan, kills ->
                EscortRec(job, holder, name, capital, road, until, EscortState.of(state), caravan, kills)
            }
        }
    }
}

/** Everything the roads keep with the overworld (see [Caravans]): saved data `caravans`. */
class CaravanData(
    var next: Long = 1L,
    val caravans: MutableList<CaravanRec> = mutableListOf(),
    val wrecks: MutableList<WreckRec> = mutableListOf(),
    val drivers: MutableList<DriverRec> = mutableListOf(),
    val takes: MutableList<TakeRec> = mutableListOf(),
    val roads: MutableList<RoadRec> = mutableListOf(),
    val capitals: MutableList<CapitalRec> = mutableListOf(),
    val bands: MutableList<BandRec> = mutableListOf(),
    val escorts: MutableList<EscortRec> = mutableListOf(),
) : SavedData() {
    fun id(): Long = next++.also { setDirty() }

    fun changed() = setDirty()

    fun caravan(id: Long): CaravanRec? = caravans.firstOrNull { it.id == id }

    fun road(capital: Long, road: Int): RoadRec =
        roads.firstOrNull { it.capital == capital && it.road == road } ?: RoadRec(capital, road, 0, Long.MIN_VALUE / 2, -1L, 0, false).also {
            roads.add(it)
            setDirty()
        }

    fun roadOrNull(capital: Long, road: Int): RoadRec? = roads.firstOrNull { it.capital == capital && it.road == road }

    fun capital(capital: Long, born: Long): CapitalRec =
        capitals.firstOrNull { it.capital == capital } ?: CapitalRec(capital, "", born, HashSet(), HashSet(), HashMap(), HashMap(), mutableListOf(), false, -1L).also {
            capitals.add(it)
            setDirty()
        }

    fun capitalOrNull(capital: Long): CapitalRec? = capitals.firstOrNull { it.capital == capital }

    fun band(id: Int): BandRec = bands.firstOrNull { it.id == id } ?: BandRec(id, 0, 0, -1L, Long.MIN_VALUE / 2, mutableListOf()).also {
        bands.add(it)
        setDirty()
    }

    fun bandOrNull(id: Int): BandRec? = bands.firstOrNull { it.id == id }

    fun escort(job: UUID): EscortRec? = escorts.firstOrNull { it.job == job }

    companion object {
        private class Records(val wrecks: List<WreckRec>, val drivers: List<DriverRec>, val takes: List<TakeRec>)

        private val RECORDS: Codec<Records> = RecordCodecBuilder.create { i ->
            i.group(
                WreckRec.CODEC.listOf().optionalFieldOf("wrecks", listOf()).forGetter(Records::wrecks),
                DriverRec.CODEC.listOf().optionalFieldOf("drivers", listOf()).forGetter(Records::drivers),
                TakeRec.CODEC.listOf().optionalFieldOf("takes", listOf()).forGetter(Records::takes),
            ).apply(i, ::Records)
        }

        private val CODEC: Codec<CaravanData> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("next", 1L).forGetter(CaravanData::next),
                CaravanRec.CODEC.listOf().optionalFieldOf("caravans", listOf()).forGetter { it.caravans },
                RECORDS.fieldOf("records").forGetter { Records(it.wrecks, it.drivers, it.takes) },
                RoadRec.CODEC.listOf().optionalFieldOf("roads", listOf()).forGetter { it.roads },
                CapitalRec.CODEC.listOf().optionalFieldOf("capitals", listOf()).forGetter { it.capitals },
                BandRec.CODEC.listOf().optionalFieldOf("bands", listOf()).forGetter { it.bands },
                EscortRec.CODEC.listOf().optionalFieldOf("escorts", listOf()).forGetter { it.escorts },
            ).apply(i) { next, caravans, records, roads, capitals, bands, escorts ->
                CaravanData(
                    next, caravans.toMutableList(), records.wrecks.toMutableList(), records.drivers.toMutableList(),
                    records.takes.toMutableList(), roads.toMutableList(), capitals.toMutableList(), bands.toMutableList(), escorts.toMutableList(),
                )
            }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "caravans"), Supplier { CaravanData() }, CODEC)

        /** Kept with the overworld, whichever level asks. */
        fun get(level: ServerLevel): CaravanData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
