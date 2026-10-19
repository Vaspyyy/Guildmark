package io.github.vaspyyy.guildmark.economy.fortune

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/**
 * Every capital's fortunes (living-world/design-influence.md section 4), kept with the overworld as the saved data
 * `fortunes`: for each capital, by its heart, the times and when they last moved, the score and reasons of its last
 * close, what's been noted since, its last [Fortunes.DAYS] days, and its works' news for the crier. A world from
 * before has none, and each capital's record starts at its next close; every field reads back with its default.
 */
class FortuneData(private val towns: MutableMap<Long, Town> = HashMap()) : SavedData() {
    /**
     * Something noted as it happened (see [Fortunes.note]): its [kind] and [points], counted at the closes after
     * market day [from] through market day [until], its words' [args], the player it's [about], the works it's of
     * ([works], or -1), and whether it was made [hand] by `/guildmark fortune reason`.
     */
    class Note(
        val kind: String, val points: Int, val from: Long, val until: Long,
        val args: List<String>, val about: UUID?, val works: Int, val hand: Boolean,
    ) {
        /** The same note, lasting until market day [until] instead. */
        fun until(until: Long): Note = Note(kind, points, from, until, args, about, works, hand)

        companion object {
            val CODEC: Codec<Note> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("kind").forGetter(Note::kind),
                    Codec.INT.optionalFieldOf("points", 0).forGetter(Note::points),
                    Codec.LONG.optionalFieldOf("from", 0L).forGetter(Note::from),
                    Codec.LONG.optionalFieldOf("until", 0L).forGetter(Note::until),
                    Codec.STRING.listOf().optionalFieldOf("args", listOf()).forGetter(Note::args),
                    UUIDUtil.CODEC.optionalFieldOf("about").forGetter { Optional.ofNullable(it.about) },
                    Codec.INT.optionalFieldOf("works", -1).forGetter(Note::works),
                    Codec.BOOL.optionalFieldOf("hand", false).forGetter(Note::hand),
                ).apply(i) { kind, points, from, until, args, about, works, hand -> Note(kind, points, from, until, args, about.orElse(null), works, hand) }
            }
        }
    }

    /**
     * A works' news for the crier and the board (heard through [io.github.vaspyyy.guildmark.economy.works.Works.listen]):
     * works [id], of [kind] (a WorksKind's id), on [road] (-1 but for a tower), was [what] ("wanted", "funded" or
     * "raised") at [hour] of the overworld's clock; [why] is the lang key of what made it wanted, and [patron] the
     * name of who pledged most ("" for none yet).
     */
    class WorksNews(val id: Int, val kind: String, val road: Int, val what: String, val hour: Long, val why: String, val patron: String) {
        companion object {
            val CODEC: Codec<WorksNews> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("id").forGetter(WorksNews::id),
                    Codec.STRING.fieldOf("kind").forGetter(WorksNews::kind),
                    Codec.INT.optionalFieldOf("road", -1).forGetter(WorksNews::road),
                    Codec.STRING.fieldOf("what").forGetter(WorksNews::what),
                    Codec.LONG.optionalFieldOf("hour", 0L).forGetter(WorksNews::hour),
                    Codec.STRING.optionalFieldOf("why", "").forGetter(WorksNews::why),
                    Codec.STRING.optionalFieldOf("patron", "").forGetter(WorksNews::patron),
                ).apply(i, ::WorksNews)
            }
        }
    }

    /**
     * One capital's fortunes, by its heart ([capital], the key the markets go by): the [times] now, the market day
     * they last moved ([since], -1 if never), the hour of the overworld's clock they moved at ([moved], for the
     * crier's noon) and what from ([from]); the last
     * close's [score] and [reasons] (biggest first) and the market day it began ([closed]); the day `fortune set`
     * holds the times through ([held], -1 for none); what's been noted ([notes]); the last [Fortunes.DAYS] days
     * ([days], oldest first); and the works' news ([works], oldest first).
     */
    class Town(val capital: Long) {
        var times: Times = Times.STEADY
        var since: Long = -1L
        var moved: Long = NEVER
        var from: Times = Times.STEADY
        var score: Int = 0
        var closed: Long = NEVER
        var held: Long = -1L
        val reasons: MutableList<Reason> = ArrayList()
        val notes: MutableList<Note> = ArrayList()
        val days: MutableList<FortuneDay> = ArrayList()
        val works: MutableList<WorksNews> = ArrayList()

        companion object {
            private val TIMES: Codec<Times> = Codec.STRING.xmap({ id: String -> Times.entries.firstOrNull { it.id == id } ?: Times.STEADY }, { t: Times -> t.id })

            private val REASON: Codec<Reason> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.fieldOf("kind").forGetter(Reason::kind),
                    Codec.INT.optionalFieldOf("points", 0).forGetter(Reason::points),
                    Codec.LONG.optionalFieldOf("until", -1L).forGetter(Reason::until),
                    Codec.STRING.listOf().optionalFieldOf("args", listOf()).forGetter(Reason::args),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("about", listOf()).forGetter(Reason::about),
                    Codec.INT.optionalFieldOf("works", -1).forGetter(Reason::works),
                ).apply(i, ::Reason)
            }

            private val DAY: Codec<FortuneDay> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("day").forGetter(FortuneDay::day),
                    Codec.INT.optionalFieldOf("score", 0).forGetter(FortuneDay::score),
                    TIMES.optionalFieldOf("times", Times.STEADY).forGetter(FortuneDay::times),
                ).apply(i, ::FortuneDay)
            }

            /** The times and when they moved, kept apart from the lists so the record stays under the codec's sixteen fields. */
            private class State(val times: Times, val since: Long, val moved: Long, val from: Times, val score: Int, val closed: Long, val held: Long)

            private val STATE: Codec<State> = RecordCodecBuilder.create { i ->
                i.group(
                    TIMES.optionalFieldOf("times", Times.STEADY).forGetter(State::times),
                    Codec.LONG.optionalFieldOf("since", -1L).forGetter(State::since),
                    Codec.LONG.optionalFieldOf("moved", NEVER).forGetter(State::moved),
                    TIMES.optionalFieldOf("from", Times.STEADY).forGetter(State::from),
                    Codec.INT.optionalFieldOf("score", 0).forGetter(State::score),
                    Codec.LONG.optionalFieldOf("closed", NEVER).forGetter(State::closed),
                    Codec.LONG.optionalFieldOf("held", -1L).forGetter(State::held),
                ).apply(i, ::State)
            }

            val CODEC: Codec<Town> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("capital").forGetter(Town::capital),
                    STATE.optionalFieldOf("state").forGetter { Optional.of(State(it.times, it.since, it.moved, it.from, it.score, it.closed, it.held)) },
                    REASON.listOf().optionalFieldOf("reasons", listOf()).forGetter { it.reasons },
                    Note.CODEC.listOf().optionalFieldOf("notes", listOf()).forGetter { it.notes },
                    DAY.listOf().optionalFieldOf("days", listOf()).forGetter { it.days },
                    WorksNews.CODEC.listOf().optionalFieldOf("works", listOf()).forGetter { it.works },
                ).apply(i) { capital, state, reasons, notes, days, works ->
                    Town(capital).also { t ->
                        state.ifPresent { s ->
                            t.times = s.times
                            t.since = s.since
                            t.moved = s.moved
                            t.from = s.from
                            t.score = s.score
                            t.closed = s.closed
                            t.held = s.held
                        }
                        t.reasons.addAll(reasons)
                        t.notes.addAll(notes)
                        t.days.addAll(days)
                        t.works.addAll(works)
                    }
                }
            }
        }
    }

    /** [capital]'s record, if it has one yet. */
    operator fun get(capital: Long): Town? = towns[capital]

    /** [capital]'s record, started now if it has none. */
    fun town(capital: Long): Town = towns.getOrPut(capital) {
        setDirty()
        Town(capital)
    }

    /** Every capital's record. */
    fun all(): Collection<Town> = towns.values

    /**
     * Forgets [capital]'s record (`fortune clear`): what it was, if it had one. The day it last closed stays, so a day
     * lived already (on ahead, by a market run) isn't closed again and the comings that go by it aren't held back.
     */
    fun clear(capital: Long): Town? = towns.remove(capital)?.also { old ->
        towns[capital] = Town(capital).also { it.closed = old.closed }
        setDirty()
    }

    fun changed() = setDirty()

    companion object {
        /** A market hour or day long before any world began. */
        const val NEVER = Long.MIN_VALUE / 4

        private val CODEC: Codec<FortuneData> = RecordCodecBuilder.create { i ->
            i.group(
                Town.CODEC.listOf().optionalFieldOf("towns", listOf()).forGetter { it.towns.values.toList() },
            ).apply(i) { list -> FortuneData(list.associateBy { it.capital }.toMutableMap()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "fortunes"), Supplier { FortuneData() }, CODEC)

        /** Kept with the overworld, whichever level asks. */
        fun get(level: ServerLevel): FortuneData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
