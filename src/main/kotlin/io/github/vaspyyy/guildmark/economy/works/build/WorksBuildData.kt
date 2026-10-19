package io.github.vaspyyy.guildmark.economy.works.build

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.homestead.Build
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.function.Supplier

/**
 * How one works is being built, as the building keeps it beside Part A's record (WorksRec, `town_works`): where its
 * drawing is laid ([origin], [front], [y0]: see [WorksFrame]) and which ([variant]: the oven's bay or lean-to), the
 * capital's [style], its roof's [palette], a stall's patron's [color], a tower's [path] out to its road (the land's
 * heights), and whether its lot was the generator's ([planned]); its [build] (the homestead's kind of record: the
 * step reached, the work clock, the materials taken and not yet used); what stood where it builds before it began
 * ([found], by world place, put back if it's taken down); its yard's things, post and plaque; and its ceremonies.
 */
class BuildRec(val id: Int, val kind: WorksKind, val capital: Long) {
    var origin: BlockPos = BlockPos.ZERO
    var front: Direction = Direction.NORTH
    var y0: Int = 0
    var variant: Int = 0
    var style: Int = 0
    var palette: Int = 0
    var color: Int = Dye.RED.ordinal
    var path: IntArray = IntArray(0)
    var planned: Boolean = false

    /** Whether it has been sited: its frame and heights above are worked out (before then they're only defaults). */
    var sited: Boolean = false

    var build: Build = Build(tier = 1)

    /** Whether what stood where it builds has been looked at and kept ([found]). */
    var surveyed: Boolean = false
    val found = HashMap<Long, BlockState>()

    /** Steps held up by something of the town's in the way, and when (game time, time of day) they were first. */
    val blocked = HashMap<Int, LongArray>()

    /** The post, while it stands; whether it's stamped (paid for). */
    var post: BlockPos? = null

    /** The yard's crate, heaps and tub, where they were put. */
    val yard = ArrayList<BlockPos>()

    /** The line strung between the stakes. */
    val strings = ArrayList<Long>()

    var plaque: BlockPos? = null

    /** The last stage the building reported (its ordinal), so each is reported once. */
    var reported: Int = -1

    /** Game time it was finished (laid to its last step), for its ceremonies; -1 while it isn't. */
    var finishedAt: Long = -1L

    /** It was toasted at its topping out. */
    var toasted: Boolean = false

    /** It was opened: the plaque unveiled, the post down, the master gone. */
    var opened: Boolean = false

    /** Its first use was seen (the first loaves, the first filling, the first watch, the first wares). */
    var firstUse: Boolean = false

    /** The market day its blocks were last looked over and anything gone laid again. */
    var mended: Long = Long.MIN_VALUE / 2

    /** It's gone from Part A's records and its blocks are still to be taken down (its land wasn't loaded). */
    var removed: Boolean = false

    /** `works stage`: the step it's laid to (free) as soon as its land is loaded; -1 when nothing is asked. */
    var layTo: Int = -1

    /** Its master builder has come to it (in through the town's gate, the first day); on the days after he comes from the inn. */
    var came: Boolean = false

    /** Its frame as it's laid. */
    val frame: WorksFrame get() = WorksFrame(origin, front)

    /** What its drawing depends on, to tell a build planned before from one to plan again. */
    fun signature(): String = "${origin.asLong()}/${front.ordinal}/" + shape()

    /** What its drawing's shape depends on, wherever it's laid. */
    fun shape(): String = "${kind.id}/$y0/$variant/$palette/$color/${path.joinToString(",")}"

    /** This record under another [id], all it holds with it (what was laid at a site the works has left). */
    fun copy(id: Int): BuildRec = BuildRec(id, kind, capital).also { r ->
        r.origin = origin
        r.front = front
        r.y0 = y0
        r.variant = variant
        r.style = style
        r.palette = palette
        r.color = color
        r.path = path
        r.planned = planned
        r.sited = sited
        r.build = build
        r.surveyed = surveyed
        r.found.putAll(found)
        r.blocked.putAll(blocked)
        r.post = post
        r.yard.addAll(yard)
        r.strings.addAll(strings)
        r.plaque = plaque
        r.reported = reported
        r.finishedAt = finishedAt
        r.toasted = toasted
        r.opened = opened
        r.firstUse = firstUse
        r.mended = mended
        r.removed = removed
        r.layTo = layTo
        r.came = came
    }

    /** The same works drawn and laid where [other] is (the great granary in its granary's frame). */
    fun sitedLike(other: BuildRec) {
        origin = other.origin
        front = other.front
        y0 = other.y0
        style = other.style
        palette = other.palette
        planned = other.planned
        sited = true
    }
}

/** The world's works builds (`town_works_build`), by works id; a works taken down while its land wasn't loaded waits here to be taken down. */
class WorksBuildData(
    val recs: LinkedHashMap<Int, BuildRec> = LinkedHashMap(),
) : SavedData() {
    fun changed() = setDirty()

    operator fun get(id: Int): BuildRec? = recs[id]

    fun of(capital: Long): List<BuildRec> = recs.values.filter { it.capital == capital }

    companion object {
        private class Where(
            val id: Int, val kind: String, val capital: Long, val origin: Long, val front: Direction, val y0: Int, val variant: Int,
            val style: Int, val palette: Int, val color: Int, val path: List<Int>, val planned: Boolean, val sited: Boolean,
        )

        private val WHERE: MapCodec<Where> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Where::id),
                Codec.STRING.fieldOf("kind").forGetter(Where::kind),
                Codec.LONG.fieldOf("capital").forGetter(Where::capital),
                Codec.LONG.optionalFieldOf("origin", 0L).forGetter(Where::origin),
                Direction.CODEC.optionalFieldOf("front", Direction.NORTH).forGetter(Where::front),
                Codec.INT.optionalFieldOf("y0", 0).forGetter(Where::y0),
                Codec.INT.optionalFieldOf("variant", 0).forGetter(Where::variant),
                Codec.INT.optionalFieldOf("style", 0).forGetter(Where::style),
                Codec.INT.optionalFieldOf("palette", 0).forGetter(Where::palette),
                Codec.INT.optionalFieldOf("color", Dye.RED.ordinal).forGetter(Where::color),
                Codec.INT.listOf().optionalFieldOf("path", listOf()).forGetter(Where::path),
                Codec.BOOL.optionalFieldOf("planned", false).forGetter(Where::planned),
                Codec.BOOL.optionalFieldOf("sited", false).forGetter(Where::sited),
            ).apply(i, ::Where)
        }

        private class Found(val pos: Long, val state: BlockState)

        private val FOUND: Codec<Found> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("pos").forGetter(Found::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Found::state),
            ).apply(i, ::Found)
        }

        private class Held(val step: Int, val time: Long, val day: Long)

        private val HELD: Codec<Held> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("step").forGetter(Held::step),
                Codec.LONG.optionalFieldOf("time", 0L).forGetter(Held::time),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Held::day),
            ).apply(i, ::Held)
        }

        private class Work(val build: Build, val surveyed: Boolean, val found: List<Found>, val blocked: List<Held>)

        private val WORK: MapCodec<Work> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Build.CODEC.optionalFieldOf("build", Build(tier = 1)).forGetter(Work::build),
                Codec.BOOL.optionalFieldOf("surveyed", false).forGetter(Work::surveyed),
                FOUND.listOf().optionalFieldOf("found", listOf()).forGetter(Work::found),
                HELD.listOf().optionalFieldOf("blocked", listOf()).forGetter(Work::blocked),
            ).apply(i, ::Work)
        }

        private class Yard(val post: Optional<Long>, val yard: List<Long>, val strings: List<Long>, val plaque: Optional<Long>)

        private val YARD: MapCodec<Yard> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.LONG.optionalFieldOf("post").forGetter(Yard::post),
                Codec.LONG.listOf().optionalFieldOf("yard", listOf()).forGetter(Yard::yard),
                Codec.LONG.listOf().optionalFieldOf("strings", listOf()).forGetter(Yard::strings),
                Codec.LONG.optionalFieldOf("plaque").forGetter(Yard::plaque),
            ).apply(i, ::Yard)
        }

        private class Life(
            val reported: Int, val finishedAt: Long, val toasted: Boolean, val opened: Boolean, val firstUse: Boolean,
            val mended: Long, val removed: Boolean, val layTo: Int, val came: Boolean,
        )

        private val LIFE: MapCodec<Life> = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.INT.optionalFieldOf("reported", -1).forGetter(Life::reported),
                Codec.LONG.optionalFieldOf("finished_at", -1L).forGetter(Life::finishedAt),
                Codec.BOOL.optionalFieldOf("toasted", false).forGetter(Life::toasted),
                Codec.BOOL.optionalFieldOf("opened", false).forGetter(Life::opened),
                Codec.BOOL.optionalFieldOf("first_use", false).forGetter(Life::firstUse),
                Codec.LONG.optionalFieldOf("mended", Long.MIN_VALUE / 2).forGetter(Life::mended),
                Codec.BOOL.optionalFieldOf("removed", false).forGetter(Life::removed),
                Codec.INT.optionalFieldOf("lay_to", -1).forGetter(Life::layTo),
                Codec.BOOL.optionalFieldOf("came", false).forGetter(Life::came),
            ).apply(i, ::Life)
        }

        private val REC: Codec<Optional<BuildRec>> = RecordCodecBuilder.create { i ->
            i.group(
                WHERE.forGetter { o: Optional<BuildRec> -> where(o.get()) },
                WORK.forGetter { o: Optional<BuildRec> ->
                    val r = o.get()
                    Work(r.build, r.surveyed, r.found.map { (p, s) -> Found(p, s) }, r.blocked.map { (s, t) -> Held(s, t[0], t[1]) })
                },
                YARD.forGetter { o: Optional<BuildRec> ->
                    val r = o.get()
                    Yard(Optional.ofNullable(r.post?.asLong()), r.yard.map { it.asLong() }, r.strings.toList(), Optional.ofNullable(r.plaque?.asLong()))
                },
                LIFE.forGetter { o: Optional<BuildRec> ->
                    val r = o.get()
                    Life(r.reported, r.finishedAt, r.toasted, r.opened, r.firstUse, r.mended, r.removed, r.layTo, r.came)
                },
            ).apply(i) { w, k, y, l ->
                val kind = WorksKind.entries.firstOrNull { it.id == w.kind } ?: return@apply Optional.empty()
                Optional.of(BuildRec(w.id, kind, w.capital).also { r ->
                    r.origin = BlockPos.of(w.origin)
                    r.front = w.front
                    r.y0 = w.y0
                    r.variant = w.variant
                    r.style = w.style
                    r.palette = w.palette
                    r.color = w.color
                    r.path = w.path.toIntArray()
                    r.planned = w.planned
                    r.sited = w.sited
                    r.build = k.build
                    r.surveyed = k.surveyed
                    for (f in k.found) r.found[f.pos] = f.state
                    for (h in k.blocked) r.blocked[h.step] = longArrayOf(h.time, h.day)
                    r.post = y.post.map(BlockPos::of).orElse(null)
                    for (p in y.yard) r.yard.add(BlockPos.of(p))
                    r.strings.addAll(y.strings)
                    r.plaque = y.plaque.map(BlockPos::of).orElse(null)
                    r.reported = l.reported
                    r.finishedAt = l.finishedAt
                    r.toasted = l.toasted
                    r.opened = l.opened
                    r.firstUse = l.firstUse
                    r.mended = l.mended
                    r.removed = l.removed
                    r.layTo = l.layTo
                    r.came = l.came
                })
            }
        }

        private fun where(r: BuildRec): Where =
            Where(r.id, r.kind.id, r.capital, r.origin.asLong(), r.front, r.y0, r.variant, r.style, r.palette, r.color, r.path.toList(), r.planned, r.sited)

        private val CODEC: Codec<WorksBuildData> = RecordCodecBuilder.create { i ->
            i.group(
                REC.listOf().optionalFieldOf("works", listOf()).forGetter { d -> d.recs.values.map { Optional.of(it) } },
            ).apply(i) { recs -> WorksBuildData(LinkedHashMap(recs.mapNotNull { it.orElse(null) }.associateBy { it.id })) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "town_works_build"), Supplier { WorksBuildData() }, CODEC)

        /** The world's, kept with the overworld whichever [level] asks. */
        fun get(level: ServerLevel): WorksBuildData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
