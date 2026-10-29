package io.github.vaspyyy.guildmark.village.live.grow

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Coming
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Due
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Leaving
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.State
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/**
 * Each village's growth as it stands (living-world/villages/design.md 5.5, 5.6), kept with the overworld by its bell's key: the
 * rules' own [State] and what the world still owes the record. All of it is optional in what is saved, so a world from before
 * V13 reads as one where nothing has grown yet.
 */
class GrowthData(val villages: MutableMap<Long, Grow> = HashMap()) : SavedData() {
    /** The village [key]'s growth, made the first time it is asked for. */
    fun of(key: Long): Grow = villages.getOrPut(key) {
        setDirty()
        Grow(key)
    }

    fun find(key: Long): Grow? = villages[key]

    fun changed() = setDirty()

    companion object {
        private val DUE: Codec<Due> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("cause").forGetter(Due::cause),
                Codec.INT.optionalFieldOf("home", -1).forGetter(Due::home),
                Codec.LONG.optionalFieldOf("from", 0L).forGetter(Due::from),
                Codec.LONG.optionalFieldOf("at", 0L).forGetter(Due::at),
            ).apply(i, ::Due)
        }

        private val COMING: Codec<Coming> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("day").forGetter(Coming::day),
                Codec.INT.fieldOf("home").forGetter(Coming::home),
                Codec.STRING.optionalFieldOf("cause", GrowthRules.EMPTY).forGetter(Coming::cause),
                Codec.INT.optionalFieldOf("adults", 1).forGetter(Coming::adults),
                Codec.INT.optionalFieldOf("children", 0).forGetter(Coming::children),
                Codec.INT.optionalFieldOf("kin", -1).forGetter(Coming::kin),
                Codec.INT.listOf().optionalFieldOf("back", listOf()).forGetter(Coming::back),
                Codec.LONG.optionalFieldOf("from", 0L).forGetter(Coming::from),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(Coming::seed),
            ).apply(i, ::Coming)
        }

        private val LEAVING: Codec<Leaving> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("day").forGetter(Leaving::day),
                Codec.INT.fieldOf("home").forGetter(Leaving::home),
            ).apply(i, ::Leaving)
        }

        private class Days(val closed: Long, val born: Long, val arrived: Long, val left: Long, val zero: Long, val base: Int, val waiting: Int, val why: String)

        private val DAYS: MapCodec<Days> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.LONG.optionalFieldOf("closed", -1L).forGetter(Days::closed),
                Codec.LONG.optionalFieldOf("born", -1L).forGetter(Days::born),
                Codec.LONG.optionalFieldOf("arrived", -1L).forGetter(Days::arrived),
                Codec.LONG.optionalFieldOf("left", -1L).forGetter(Days::left),
                Codec.LONG.optionalFieldOf("zero", -1L).forGetter(Days::zero),
                Codec.INT.optionalFieldOf("base", -1).forGetter(Days::base),
                Codec.INT.optionalFieldOf("waiting", -1).forGetter(Days::waiting),
                Codec.STRING.optionalFieldOf("why", "").forGetter(Days::why),
            ).apply(i, ::Days)
        }

        val STATE: Codec<State> = RecordCodecBuilder.create { i ->
            i.group(
                DAYS.forGetter { Days(it.closed, it.born, it.arrived, it.left, it.zero, it.base, it.waiting, it.why) },
                Codec.INT.listOf().optionalFieldOf("seen", listOf()).forGetter { it.seen.toList() },
                Codec.INT.listOf().optionalFieldOf("empty", listOf()).forGetter { it.empty.toList() },
                DUE.listOf().optionalFieldOf("due", listOf()).forGetter { it.due.toList() },
                COMING.optionalFieldOf("coming").forGetter { Optional.ofNullable(it.coming) },
                LEAVING.optionalFieldOf("leaving").forGetter { Optional.ofNullable(it.leaving) },
            ).apply(i) { days, seen, empty, due, coming, leaving ->
                State().also { s ->
                    s.closed = days.closed
                    s.born = days.born
                    s.arrived = days.arrived
                    s.left = days.left
                    s.zero = days.zero
                    s.base = days.base
                    s.waiting = days.waiting
                    s.why = days.why
                    s.seen += seen
                    s.empty += empty
                    s.due += due
                    s.coming = coming.orElse(null)
                    s.leaving = leaving.orElse(null)
                }
            }
        }

        private val CODEC: Codec<GrowthData> = RecordCodecBuilder.create { i ->
            i.group(Grow.CODEC.listOf().optionalFieldOf("villages", listOf()).forGetter { it.villages.values.toList() })
                .apply(i) { list -> GrowthData(list.associateBy { it.key }.toMutableMap()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_growth"), Supplier { GrowthData() }, CODEC)

        fun get(level: ServerLevel): GrowthData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}

/**
 * One village's growth: the rules' [state], the vanilla villagers last counted in it ([vanilla], -1 until they have been) and
 * the last siege it lost as its board had it ([siege]); and what the world owes the record: the babies (no body yet: a parent
 * carries one out of a morning, [Baby]), the bodies still to be made at home out of sight ([make]), the children due to grow
 * up when nobody is looking ([aging]), the grown-ups a neighbour remarks on of a morning ([grown]), the old bodies to drop as
 * they load ([stale]), the last of what happened, for `village grow` ([log]), and who left in hard times ([gone]).
 */
class Grow(
    val key: Long,
    var state: State = State(),
    var vanilla: Int = -1,
    var siege: Long = -1L,
    val babies: MutableList<Baby> = ArrayList(),
    val make: MutableList<Make> = ArrayList(),
    val aging: MutableList<Int> = ArrayList(),
    val grown: MutableList<Grown> = ArrayList(),
    val stale: MutableList<Stale> = ArrayList(),
    val log: MutableList<Line> = ArrayList(),
    /** Those of it who left in hard times by the growth's doing, who may come home to it ([GrowthRules.RETURN]). */
    val gone: MutableList<Int> = ArrayList(),
) {
    /** Something that happened on [day], in words; the oldest are dropped past [LOG]. */
    fun said(day: Long, text: String) {
        log += Line(day, text)
        while (log.size > LOG) log.removeAt(0)
    }

    /** The clock set back [days] days: every day kept goes back with it. */
    fun back(days: Long) {
        if (days <= 0L) return
        fun b(d: Long) = if (d < 0L) d else maxOf(0L, d - days)
        state.back(days)
        if (siege >= 0L) siege = b(siege)
        babies.replaceAll { Baby(it.id, b(it.born), it.home, it.shown) }
        make.replaceAll { Make(it.id, b(it.from)) }
        grown.replaceAll { Grown(it.id, b(it.day), it.said) }
        stale.replaceAll { Stale(it.uuid, it.id, b(it.day)) }
        log.replaceAll { Line(b(it.day), it.text) }
    }

    companion object {
        const val LOG = 12

        val CODEC: Codec<Grow> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("key").forGetter(Grow::key),
                GrowthData.STATE.optionalFieldOf("state").forGetter { Optional.of(it.state) },
                Codec.INT.optionalFieldOf("vanilla", -1).forGetter(Grow::vanilla),
                Codec.LONG.optionalFieldOf("siege", -1L).forGetter(Grow::siege),
                Baby.CODEC.listOf().optionalFieldOf("babies", listOf()).forGetter { it.babies.toList() },
                Make.CODEC.listOf().optionalFieldOf("make", listOf()).forGetter { it.make.toList() },
                Codec.INT.listOf().optionalFieldOf("aging", listOf()).forGetter { it.aging.toList() },
                Grown.CODEC.listOf().optionalFieldOf("grown", listOf()).forGetter { it.grown.toList() },
                Stale.CODEC.listOf().optionalFieldOf("stale", listOf()).forGetter { it.stale.toList() },
                Line.CODEC.listOf().optionalFieldOf("log", listOf()).forGetter { it.log.toList() },
                Codec.INT.listOf().optionalFieldOf("gone", listOf()).forGetter { it.gone.toList() },
            ).apply(i) { key, state, vanilla, siege, babies, make, aging, grown, stale, log, gone ->
                Grow(
                    key, state.orElseGet { State() }, vanilla, siege, ArrayList(babies), ArrayList(make), ArrayList(aging), ArrayList(grown),
                    ArrayList(stale), ArrayList(log.takeLast(LOG)), ArrayList(gone),
                )
            }
        }
    }
}

/** A child born on [born] in [home], a baby for its first days: [shown] once its parent has carried it out for the street to see. */
class Baby(val id: Int, val born: Long, val home: Int, var shown: Boolean = false) {
    companion object {
        val CODEC: Codec<Baby> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Baby::id),
                Codec.LONG.optionalFieldOf("born", 0L).forGetter(Baby::born),
                Codec.INT.optionalFieldOf("home", -1).forGetter(Baby::home),
                Codec.BOOL.optionalFieldOf("shown", false).forGetter(Baby::shown),
            ).apply(i, ::Baby)
        }
    }
}

/** Resident [id]'s body, to be made at home out of sight from day [from] on. */
class Make(val id: Int, val from: Long) {
    companion object {
        val CODEC: Codec<Make> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Make::id),
                Codec.LONG.optionalFieldOf("from", 0L).forGetter(Make::from),
            ).apply(i, ::Make)
        }
    }
}

/** Resident [id] grew up on the night before [day]: a neighbour may remark on it that day ([said] once one has). */
class Grown(val id: Int, val day: Long, var said: Boolean = false) {
    companion object {
        val CODEC: Codec<Grown> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Grown::id),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Grown::day),
                Codec.BOOL.optionalFieldOf("said", false).forGetter(Grown::said),
            ).apply(i, ::Grown)
        }
    }
}

/** An old body of resident [id] ([uuid]) that is nobody's since [day] (a child grown up, a household gone): dropped as it loads. */
class Stale(val uuid: UUID, val id: Int, val day: Long) {
    companion object {
        val CODEC: Codec<Stale> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(Stale::uuid),
                Codec.INT.optionalFieldOf("id", -1).forGetter(Stale::id),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Stale::day),
            ).apply(i, ::Stale)
        }
    }
}

/** One line of a village's growth, on [day]. */
class Line(val day: Long, val text: String) {
    companion object {
        val CODEC: Codec<Line> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Line::day),
                Codec.STRING.optionalFieldOf("text", "").forGetter(Line::text),
            ).apply(i, ::Line)
        }
    }
}
