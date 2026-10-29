package io.github.vaspyyy.guildmark.village.live.build

import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.Decoder
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.homestead.Build
import io.github.vaspyyy.guildmark.village.live.keptList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.Arrays
import java.util.stream.IntStream
import java.util.stream.LongStream

/**
 * One project of a village on its way up (living-world/villages/design.md section 12.5): the village it is for (the key of its bell),
 * the project (the ordinal of its [io.github.vaspyyy.guildmark.advance.Advance]), and what the builders recorded, as the crew lays
 * it: [pos] the place of each step, in the order they are laid, and [meta] what each is (its [kind], whether it [swaps] something
 * that stood there, and its block's place in the [palette]). A step is 12 bytes on disk: the palette holds each distinct state once.
 * [stageStarts] says where each of the plan's stages begins (as `TierPlan.stageStarts` does), [deps] what holds each step up, and
 * [build] is how far the crew has got: the step they are at, the steps laid beyond it, the work the clock has allowed, and the day it
 * has been wound to.
 *
 * Nothing here is read from the world: a site is kept as saved, and a block in the palette that its game no longer knows is
 * laid as nothing ([UNKNOWN]).
 */
class VillageBuild(
    val id: Int,
    val village: Long,
    val project: Int,
    /** The game time it was funded (or started by command). */
    val born: Long,
    /** The overworld clock time from which the crew works on it: the next sunrise, or [Long.MIN_VALUE] for at once. */
    var opens: Long,
    val pos: LongArray,
    val meta: IntArray,
    val palette: List<BlockState>,
    val stageStarts: IntArray,
    val build: Build,
    var yard: Yard,
    val after: MutableList<BuildSink.After>,
    /** Being finished at once, out of sight, as the land loads (a switch turned off, the village lost, the days up, `village build rush`). */
    var rushing: Boolean = false,
    /** The ticks of working hours the clock has been wound through, to tell a site that has run three times its days. */
    var accrued: Long = 0L,
    /**
     * For each step, the step that holds it up (the block it stands on, hangs from or leans against), which must be laid before it
     * may be: -1 where the world holds it up, or where it needs nothing (air, a stake).
     */
    val deps: IntArray = IntArray(pos.size) { -1 },
) {
    val size: Int get() = pos.size

    /** The kind of step [i]: one of [Kinds]. */
    fun kind(i: Int): Int = meta[i] and KIND_MASK

    /** Whether step [i] puts its block where something stood when it was recorded (not just air or plants). */
    fun swaps(i: Int): Boolean = meta[i] and SWAP != 0

    /** The block step [i] puts (a stake and a bough have their own, built in). */
    fun state(i: Int): BlockState = palette.getOrElse(meta[i] ushr 8) { UNKNOWN }

    fun at(i: Int): BlockPos = BlockPos.of(pos[i])

    /** The kinds of step (the low three bits of a step's meta). */
    object Kinds {
        const val BLOCK = 0
        const val STAKE = 1
        const val PULL = 2
        const val TOP = 3
        const val UNTOP = 4
    }

    /**
     * Where a site's crew works from: [crate] the supply crate (a heap or two beside it, [heaps] each with the [Yard.kinds] ordinal of
     * what it heaps), [gate] where they gather as they come, [road] where the strangers come in from and go back to, and [out] the
     * horizontal direction (as a 2D data value) that leads out of the work toward the road.
     */
    class Yard(val crate: Long, val gate: Long, val road: Long, val out: Int, val heaps: List<Long> = listOf(), val kinds: List<Int> = listOf()) {
        val outDir: Direction get() = Direction.from2DDataValue(out)

        companion object {
            val CODEC: Codec<Yard> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("crate").forGetter(Yard::crate),
                    Codec.LONG.fieldOf("gate").forGetter(Yard::gate),
                    Codec.LONG.fieldOf("road").forGetter(Yard::road),
                    Codec.INT.optionalFieldOf("out", 0).forGetter(Yard::out),
                    Codec.LONG.listOf().optionalFieldOf("heaps", listOf()).forGetter(Yard::heaps),
                    Codec.INT.listOf().optionalFieldOf("kinds", listOf()).forGetter(Yard::kinds),
                ).apply(i, ::Yard)
            }
        }
    }

    companion object {
        const val KIND_MASK = 7
        const val SWAP = 8

        /** What a block of the palette that can't be read becomes: laid as nothing. */
        val UNKNOWN: BlockState = Blocks.STRUCTURE_VOID.defaultBlockState()

        fun meta(kind: Int, swap: Boolean, palette: Int): Int = kind or (if (swap) SWAP else 0) or (palette shl 8)

        /** A block state read leniently: one that can't be read is [UNKNOWN] and the rest of the site is kept. */
        private val STATE: Codec<BlockState> = Codec.of(
            BlockState.CODEC,
            object : Decoder<BlockState> {
                override fun <T> decode(ops: DynamicOps<T>, input: T): DataResult<Pair<BlockState, T>> {
                    val read = BlockState.CODEC.decode(ops, input).result()
                    return DataResult.success(if (read.isPresent) read.get() else Pair.of(UNKNOWN, input))
                }
            },
        )

        val CODEC: Codec<VillageBuild> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(VillageBuild::id),
                Codec.LONG.fieldOf("village").forGetter(VillageBuild::village),
                Codec.INT.fieldOf("project").forGetter(VillageBuild::project),
                Codec.LONG.optionalFieldOf("born", 0L).forGetter(VillageBuild::born),
                Codec.LONG.optionalFieldOf("opens", Long.MIN_VALUE).forGetter(VillageBuild::opens),
                Codec.LONG_STREAM.optionalFieldOf("pos", LongStream.empty()).forGetter { Arrays.stream(it.pos) },
                Codec.INT_STREAM.optionalFieldOf("meta", IntStream.empty()).forGetter { Arrays.stream(it.meta) },
                STATE.listOf().optionalFieldOf("palette", listOf()).forGetter(VillageBuild::palette),
                Codec.INT_STREAM.optionalFieldOf("stages", IntStream.empty()).forGetter { Arrays.stream(it.stageStarts) },
                Build.CODEC.optionalFieldOf("build", Build()).forGetter(VillageBuild::build),
                Yard.CODEC.fieldOf("yard").forGetter(VillageBuild::yard),
                BuildSink.After.CODEC.listOf().optionalFieldOf("after", listOf()).forGetter(VillageBuild::after),
                Codec.BOOL.optionalFieldOf("rushing", false).forGetter(VillageBuild::rushing),
                Codec.LONG.optionalFieldOf("accrued", 0L).forGetter(VillageBuild::accrued),
                Codec.INT_STREAM.optionalFieldOf("deps", IntStream.empty()).forGetter { Arrays.stream(it.deps) },
            ).apply(i) { id, village, project, born, opens, pos, meta, palette, stages, build, yard, after, rushing, accrued, deps ->
                val p = pos.toArray()
                val m = meta.toArray().let { if (it.size == p.size) it else IntArray(p.size) { k -> it.getOrElse(k) { 0 } } }
                val s = stages.toArray().let { if (it.size == STAGES + 1) it else IntArray(STAGES + 1) { k -> if (k == STAGES) p.size else 0 } }
                // A step held up by one laid after it (a file from elsewhere) is held up by nothing
                val d = deps.toArray().let { if (it.size == p.size) it else IntArray(p.size) { -1 } }
                for (k in d.indices) if (d[k] >= k) d[k] = -1
                VillageBuild(id, village, project, born, opens, p, m, palette, s, build, yard, after.toMutableList(), rushing, accrued, d)
            }
        }

        /** The number of stages a plan's [stageStarts] tells of (`Stage.entries.size`). */
        val STAGES: Int = io.github.vaspyyy.guildmark.homestead.plan.Stage.entries.size

        /** The sites list as saved: one that can't be read drops only itself. */
        val LIST: Codec<List<VillageBuild>> = keptList(CODEC)
    }
}
