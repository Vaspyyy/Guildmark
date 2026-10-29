package io.github.vaspyyy.guildmark.village.live.trouble

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.village.live.keptList
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * What a village's trouble leaves behind that must outlast a restart (living-world/villages/design.md 11.4 to 11.6, 14), saved with
 * the overworld as "village_trouble", one [Kept] for each village by the key of its bell: where its memorial stone stands, the candles
 * laid on it and the dead still waiting for theirs, the blocks a lost siege broke and when the mend sets out, the night a golem was last
 * made, the watch on its way and the folk a raid took. Every field is optional with a default and a bad line drops only itself
 * ([keptList]), so an older file reads; the file is made only when something is first written, which the switch being off never does.
 *
 * What a siege or a raid is doing just now (who is sheltering where, who is on the wall) is not here: it is memory only, and a restart
 * ends it, so nobody is ever shut in by a save.
 */
class TroubleData(val villages: MutableMap<Long, Kept> = HashMap()) : SavedData() {
    /** One village's. */
    class Kept(
        /** The memorial stone's place (`BlockPos.asLong`), or [NONE] before the first death. */
        var stone: Long = NONE,
        /** The candles laid on the stone, the newest last; the last four stand on it (see [Memorial]). */
        val candles: MutableList<Candle> = ArrayList(),
        /** The dead whose candle is still to be laid, at the next dawn, the oldest first. */
        val waiting: MutableList<Mourned> = ArrayList(),
        /** The blocks a lost siege broke, as they were, in the order they were broken (see [VillageDamage]); at most [VillageDamage.MOST]. */
        val damage: MutableList<Broken> = ArrayList(),
        /** The town day the mending crew sets out on, and the day it first did; [NONE] when there is nothing to mend. */
        var mendFrom: Long = NONE,
        var mendBegun: Long = NONE,
        /** The night (the town day it began on) a golem was last made here, or [NONE]. */
        var golemNight: Long = NONE,
        /** The watch on its way: one entry for each guard, with the night they come and why (see [Watch]). */
        val due: MutableList<Due> = ArrayList(),
        /** The folk a raid took, while their record is away (see [RaidWatch]). */
        val taken: MutableList<Taken> = ArrayList(),
        /** The last town day closed here (see [Trouble.daily]), so a day is closed once. */
        var day: Long = NONE,
    ) {
        /** Whether there is nothing here worth keeping. */
        val empty: Boolean
            get() = stone == NONE && candles.isEmpty() && waiting.isEmpty() && damage.isEmpty() && mendFrom == NONE && golemNight == NONE &&
                due.isEmpty() && taken.isEmpty()
    }

    /** A candle laid for [name] on town day [day]. */
    data class Candle(val name: String, val day: Long)

    /** [name] (resident [id], who lived in home [home], -1 for none) died on town day [day], and waits for a candle. */
    data class Mourned(val name: String, val id: Int, val home: Int, val day: Long)

    /** A block a siege broke: where ([pos], packed) and what stood there. */
    class Broken(val pos: Long, val state: BlockState)

    /** A guard of the watch due on the night of town day [day], raised for [why] (a project's id, "tower", or "dead" for one who fell). */
    data class Due(val day: Long, val why: String)

    /** Resident [id] ([name]) taken by band [band] on town day [day]. */
    data class Taken(val id: Int, val name: String, val day: Long, val band: Int)

    /** [key]'s, made if there is none (only ask this when something is to be written). */
    fun of(key: Long): Kept = villages.getOrPut(key) { Kept() }

    /** [key]'s, if there is one. */
    fun peek(key: Long): Kept? = villages[key]

    companion object {
        const val NONE = Long.MIN_VALUE

        private val CANDLE: Codec<Candle> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("name").forGetter(Candle::name),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Candle::day),
            ).apply(i, ::Candle)
        }

        private val MOURNED: Codec<Mourned> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("name").forGetter(Mourned::name),
                Codec.INT.optionalFieldOf("id", -1).forGetter(Mourned::id),
                Codec.INT.optionalFieldOf("home", -1).forGetter(Mourned::home),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Mourned::day),
            ).apply(i, ::Mourned)
        }

        private val BROKEN: Codec<Broken> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("pos").forGetter(Broken::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Broken::state),
            ).apply(i, ::Broken)
        }

        private val DUE: Codec<Due> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("day").forGetter(Due::day),
                Codec.STRING.optionalFieldOf("why", "").forGetter(Due::why),
            ).apply(i, ::Due)
        }

        private val TAKEN: Codec<Taken> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Taken::id),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Taken::name),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Taken::day),
                Codec.INT.optionalFieldOf("band", -1).forGetter(Taken::band),
            ).apply(i, ::Taken)
        }

        /** A village's, with its key. */
        private class Entry(val key: Long, val kept: Kept)

        private val KEPT: Codec<Entry> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("key").forGetter { it.key },
                Codec.LONG.optionalFieldOf("stone", NONE).forGetter { it.kept.stone },
                keptList(CANDLE).lenientOptionalFieldOf("candles", listOf()).forGetter { it.kept.candles },
                keptList(MOURNED).lenientOptionalFieldOf("waiting", listOf()).forGetter { it.kept.waiting },
                keptList(BROKEN).lenientOptionalFieldOf("damage", listOf()).forGetter { it.kept.damage },
                Codec.LONG.optionalFieldOf("mend_from", NONE).forGetter { it.kept.mendFrom },
                Codec.LONG.optionalFieldOf("mend_begun", NONE).forGetter { it.kept.mendBegun },
                Codec.LONG.optionalFieldOf("golem_night", NONE).forGetter { it.kept.golemNight },
                keptList(DUE).lenientOptionalFieldOf("due", listOf()).forGetter { it.kept.due },
                keptList(TAKEN).lenientOptionalFieldOf("taken", listOf()).forGetter { it.kept.taken },
                Codec.LONG.optionalFieldOf("day", NONE).forGetter { it.kept.day },
            ).apply(i) { key, stone, candles, waiting, damage, mendFrom, mendBegun, golemNight, due, taken, day ->
                Entry(
                    key,
                    Kept(
                        stone, ArrayList(candles), ArrayList(waiting), ArrayList(damage.take(VillageDamage.MOST)), mendFrom, mendBegun, golemNight,
                        ArrayList(due), ArrayList(taken), day,
                    ),
                )
            }
        }

        private val CODEC: Codec<TroubleData> = RecordCodecBuilder.create { i ->
            i.group(
                keptList(KEPT).lenientOptionalFieldOf("villages", listOf()).forGetter { data ->
                    data.villages.entries.filter { !it.value.empty || it.value.day != NONE }.sortedBy { it.key }.map { Entry(it.key, it.value) }
                },
            ).apply(i) { list -> TroubleData(list.associateTo(HashMap()) { it.key to it.kept }) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_trouble"), Supplier { TroubleData() }, CODEC)

        /** The overworld's, made if there is none: ask it only to write. */
        fun get(level: ServerLevel): TroubleData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** The overworld's, if it has been made. */
        fun peek(level: ServerLevel): TroubleData? = level.server.overworld().dataStorage.get(TYPE)
    }
}
