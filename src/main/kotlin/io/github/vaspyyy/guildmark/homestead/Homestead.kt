package io.github.vaspyyy.guildmark.homestead

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.homestead.plan.HomesteadLayout
import io.github.vaspyyy.guildmark.homestead.plan.PlotLand
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.Vec3
import java.util.Arrays
import java.util.BitSet
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * What each tier of a homestead costs and how it goes up: the Guild Marks paid to the builders' guild when it's
 * commissioned, the materials ([Stuff.PAID], in units: a plank, a block of stone or glass, an ingot, a block of wool)
 * the crew takes from the supply crate as they build, how many come to build it, and how many days of daylight work it
 * takes them at the normal pace (GuildmarkConfig homesteadBuildSpeed).
 */
object HomesteadTiers {
    class Tier(val n: Int, val key: String, val marks: Int, val bill: IntArray, val crew: Int, val days: Double)

    /** What a charter costs at the Guildmaster's: two payments, as a trade takes no more than a stack in each. */
    const val CHARTER_MARKS_A = 56
    const val CHARTER_MARKS_B = 64
    const val CHARTER_MARKS = CHARTER_MARKS_A + CHARTER_MARKS_B

    val TIERS: List<Tier> = listOf(
        Tier(1, "cottage", 80, intArrayOf(96, 48, 6, 2, 3), 3, 0.5),
        Tier(2, "farmstead", 300, intArrayOf(320, 160, 16, 8, 6), 4, 1.0),
        Tier(3, "manor", 800, intArrayOf(480, 640, 64, 32, 24), 5, 2.0),
    )

    fun of(n: Int): Tier? = TIERS.getOrNull(n - 1)

    /** The name key of tier [n] (0: the staked plot). */
    fun key(n: Int): String = if (n <= 0) "staked" else TIERS[min(n, TIERS.size) - 1].key

    /** Ticks of daylight in a day: the crew works from sunrise to sunset. */
    const val WORKDAY = 12_000
}

/**
 * The plot's own frame laid on the world: the Charter Post at [post], the plot reaching away from it toward [facing].
 * The plan's x runs across the plot and its z away from the road; its y is the world's.
 */
class PlotFrame(val post: BlockPos, val facing: Direction) {
    /** The turn that takes the plan (drawn reaching south) to the world. */
    val rotation: Rotation = when (facing) {
        Direction.SOUTH -> Rotation.NONE
        Direction.WEST -> Rotation.CLOCKWISE_90
        Direction.NORTH -> Rotation.CLOCKWISE_180
        else -> Rotation.COUNTERCLOCKWISE_90
    }

    /** Where the plan's (0, 0) is: the gate, two blocks along from the post. */
    private val ox: Int
    private val oz: Int

    init {
        ox = post.x - dx(PlotShape.POST_X, 0)
        oz = post.z - dz(PlotShape.POST_X, 0)
    }

    private fun dx(x: Int, z: Int): Int = when (rotation) {
        Rotation.NONE -> x
        Rotation.CLOCKWISE_90 -> -z
        Rotation.CLOCKWISE_180 -> -x
        Rotation.COUNTERCLOCKWISE_90 -> z
    }

    private fun dz(x: Int, z: Int): Int = when (rotation) {
        Rotation.NONE -> z
        Rotation.CLOCKWISE_90 -> x
        Rotation.CLOCKWISE_180 -> -z
        Rotation.COUNTERCLOCKWISE_90 -> -x
    }

    fun worldX(x: Int, z: Int): Int = ox + dx(x, z)
    fun worldZ(x: Int, z: Int): Int = oz + dz(x, z)
    fun world(x: Int, y: Int, z: Int): BlockPos = BlockPos(worldX(x, z), y, worldZ(x, z))

    /** The middle of the plan's block ([x], [z]) at height [y]. */
    fun centre(x: Double, y: Double, z: Double): Vec3 {
        val wx = ox + 0.5 + when (rotation) {
            Rotation.NONE -> x
            Rotation.CLOCKWISE_90 -> -z
            Rotation.CLOCKWISE_180 -> -x
            Rotation.COUNTERCLOCKWISE_90 -> z
        }
        val wz = oz + 0.5 + when (rotation) {
            Rotation.NONE -> z
            Rotation.CLOCKWISE_90 -> x
            Rotation.CLOCKWISE_180 -> -z
            Rotation.COUNTERCLOCKWISE_90 -> -x
        }
        return Vec3(wx, y, wz)
    }

    /** The plan's (x, z) of the world column [wx], [wz]. */
    fun localX(wx: Int, wz: Int): Int {
        val ax = wx - ox
        val az = wz - oz
        return when (rotation) {
            Rotation.NONE -> ax
            Rotation.CLOCKWISE_90 -> az
            Rotation.CLOCKWISE_180 -> -ax
            Rotation.COUNTERCLOCKWISE_90 -> -az
        }
    }

    fun localZ(wx: Int, wz: Int): Int {
        val ax = wx - ox
        val az = wz - oz
        return when (rotation) {
            Rotation.NONE -> az
            Rotation.CLOCKWISE_90 -> -ax
            Rotation.CLOCKWISE_180 -> -az
            Rotation.COUNTERCLOCKWISE_90 -> ax
        }
    }

    /** A plan direction in the world. */
    fun direction(d: Dir): Direction = rotation.rotate(CityStyle.direction(d))

    /** The world's columns the plan's [r] covers, as a box from [y0] to [y1]. */
    fun box(r: Rect, y0: Int, y1: Int): BoundingBox {
        val ax = worldX(r.x0, r.z0)
        val az = worldZ(r.x0, r.z0)
        val bx = worldX(r.x1, r.z1)
        val bz = worldZ(r.x1, r.z1)
        return BoundingBox(min(ax, bx), y0, min(az, bz), max(ax, bx), y1, max(az, bz))
    }

    /** Whether the world column [wx], [wz] is in the plan's [r]. */
    fun contains(r: Rect, wx: Int, wz: Int): Boolean = r.contains(localX(wx, wz), localZ(wx, wz))
}

/**
 * How a homestead's current build stands. [tier] is the tier being built (the homestead's own tier when nothing is),
 * [step] how many of its steps are done in a row from the first (see HomesteadSite), [done] which steps past those are
 * done too (the crew works on several at once), [work] the steps the crew may still lay by the time worked so far,
 * [reach] how many more steps what is in the supply crate pays for, as it was when last looked in, and [credit] what is
 * left of the last unit of each material taken from the crate (a unit of timber goes further than one block, see
 * HomesteadSite.charge).
 */
class Build(
    var tier: Int = 0,
    var step: Int = 0,
    var work: Double = 0.0,
    val credit: DoubleArray = DoubleArray(Stuff.PAID.size),
    /** Game time the work clock was last wound on. */
    var clock: Long = -1,
    /** The time of day then, so time away can be told day from night. */
    var dayTime: Long = 0,
    /** The last stage the crew finished and cheered (its ordinal), so none is cheered twice. */
    var cheered: Int = -1,
    val done: BitSet = BitSet(),
    var reach: Int = 0,
) {
    companion object {
        val CODEC: Codec<Build> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("tier").forGetter(Build::tier),
                Codec.INT.fieldOf("step").forGetter(Build::step),
                Codec.DOUBLE.optionalFieldOf("work", 0.0).forGetter(Build::work),
                Codec.DOUBLE.listOf().optionalFieldOf("credit", listOf()).forGetter { it.credit.toList() },
                Codec.LONG.optionalFieldOf("clock", -1L).forGetter(Build::clock),
                Codec.LONG.optionalFieldOf("day_time", 0L).forGetter(Build::dayTime),
                Codec.INT.optionalFieldOf("cheered", -1).forGetter(Build::cheered),
                // Always written; read with a fresh empty set when it isn't there (a stream given as the default is one stream, spent by the first build read without it)
                Codec.LONG_STREAM.optionalFieldOf("done").forGetter { java.util.Optional.of(Arrays.stream(it.done.toLongArray())) },
                Codec.INT.optionalFieldOf("reach", 0).forGetter(Build::reach),
            ).apply(i) { tier, step, work, credit, clock, dayTime, cheered, done, reach ->
                Build(
                    tier, step, work, DoubleArray(Stuff.PAID.size) { credit.getOrElse(it) { 0.0 } }, clock, dayTime, cheered,
                    done.map { BitSet.valueOf(it.toArray()) }.orElseGet { BitSet() }, reach,
                )
            }
        }
    }
}

/**
 * A player's homestead: the plot their charter staked, where its post stands and which way the plot reaches, the land
 * as it lay when it was claimed (so each tier's plan comes out the same, see HomesteadLayout), the tier it has reached
 * and how the next is coming along.
 */
class Homestead(
    val id: Int,
    val owner: UUID,
    var ownerName: String,
    val post: BlockPos,
    val facing: Direction,
    val seed: Long,
    /** The look of the land's buildings (CityStyle), from the biome it was claimed in. */
    val style: Int,
    /** The owner's colour: the stakes' banners, the pennant and the map's mark. */
    val color: Int,
    val claimed: Long,
    val land: PlotLand,
    /** The highest tier finished: 0 is a staked plot. */
    var tier: Int = 0,
    var build: Build = Build(),
) {
    val frame: PlotFrame by lazy { PlotFrame(post, facing) }
    val layout: HomesteadLayout by lazy { HomesteadLayout(seed, land, Dye.entries[color.coerceIn(0, Dye.entries.size - 1)]) }
    val cityStyle: CityStyle get() = CityStyle.entries[style.coerceIn(0, CityStyle.entries.size - 1)]

    /** Whether a tier past [tier] has been commissioned and is going up. */
    val building: Boolean get() = build.tier > tier

    /** The whole of the land the charter takes, as a box through the world's height. */
    fun reserved(minY: Int, maxY: Int): BoundingBox = frame.box(PlotShape.RESERVED, minY, maxY)

    /** The plot the homestead has the use of now (its tier's, or the one going up). */
    fun plot(): Rect = PlotShape.plot(maxOf(tier, build.tier))

    /** Whether the world column [x], [z] is on the charter's land. */
    fun covers(x: Int, z: Int): Boolean = frame.contains(PlotShape.RESERVED, x, z)

    /** Whether the world column [x], [z] is within [margin] blocks of the charter's land. */
    fun near(x: Int, z: Int, margin: Int): Boolean = frame.contains(PlotShape.RESERVED.grow(margin), x, z)

    /** Where the supply crate stands. */
    val crate: BlockPos get() = frame.world(PlotShape.CRATE_X, land.height(PlotShape.CRATE_X, 0), 0)

    companion object {
        /** The land's heights, a whole plot's worth: of another size they can't be this plot's, and its homestead is left out alone (the land would throw, and lose every homestead). */
        private val HEIGHTS: Codec<IntArray> = Codec.INT_STREAM.comapFlatMap(
            { s -> s.toArray().let { if (it.size == PlotLand.SIZE * PlotLand.SIZE) DataResult.success(it) else DataResult.error { "${it.size} heights, not a plot's" } } },
            { java.util.Arrays.stream(it) },
        )

        private val LAND_CODEC: Codec<PlotLand> = RecordCodecBuilder.create { i ->
            i.group(
                HEIGHTS.fieldOf("heights").forGetter { it.heights },
                Codec.LONG_STREAM.fieldOf("wet").forGetter { java.util.Arrays.stream(packBits(it.wets)) },
            ).apply(i) { heights, wet -> PlotLand(heights, unpackBits(wet.toArray(), PlotLand.SIZE * PlotLand.SIZE)) }
        }

        val CODEC: Codec<Homestead> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Homestead::id),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Homestead::owner),
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Homestead::ownerName),
                BlockPos.CODEC.fieldOf("post").forGetter(Homestead::post),
                Direction.CODEC.fieldOf("facing").forGetter(Homestead::facing),
                Codec.LONG.fieldOf("seed").forGetter(Homestead::seed),
                Codec.INT.optionalFieldOf("style", 0).forGetter(Homestead::style),
                Codec.INT.optionalFieldOf("color", 14).forGetter(Homestead::color),
                Codec.LONG.optionalFieldOf("claimed", 0L).forGetter(Homestead::claimed),
                LAND_CODEC.fieldOf("land").forGetter(Homestead::land),
                Codec.INT.optionalFieldOf("tier", 0).forGetter(Homestead::tier),
                Build.CODEC.optionalFieldOf("build").forGetter { java.util.Optional.of(it.build) },
            ).apply(i) { id, owner, name, post, facing, seed, style, color, claimed, land, tier, build ->
                Homestead(id, owner, name, post, facing, seed, style, color, claimed, land, tier, build.orElseGet { Build(tier) })
            }
        }

        private fun packBits(bits: BooleanArray): LongArray {
            val out = LongArray((bits.size + 63) / 64)
            for (i in bits.indices) if (bits[i]) out[i / 64] = out[i / 64] or (1L shl (i % 64))
            return out
        }

        private fun unpackBits(words: LongArray, size: Int): BooleanArray =
            BooleanArray(size) { i -> i / 64 < words.size && (words[i / 64] ushr (i % 64)) and 1L == 1L }
    }
}
