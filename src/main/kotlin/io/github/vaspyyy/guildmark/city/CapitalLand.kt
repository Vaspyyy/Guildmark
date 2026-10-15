package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.city.plan.Land
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.structure.Structure
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The land as a world's generator makes it, before anything is built or grown on it: what capitals are sited
 * and planned on. The same answers wherever and whenever it's asked, from any thread; each costs a fraction
 * of a millisecond, so callers keep what they've asked.
 */
class GeneratedLand(private val generator: ChunkGenerator, private val randomState: RandomState, minY: Int, height: Int) {
    private val heights = LevelHeightAccessor.create(minY, height)

    val seaLevel: Int get() = generator.seaLevel

    /** Standing height at a column: on the ground, or on any water over it. */
    fun surface(x: Int, z: Int): Int = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, randomState)

    /** Standing height on the ground itself, under any water. */
    fun floor(x: Int, z: Int): Int = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, randomState)

    companion object {
        fun of(context: Structure.GenerationContext): GeneratedLand = context.heightAccessor().let {
            GeneratedLand(context.chunkGenerator(), context.randomState(), it.minY, it.height)
        }

        fun of(level: ServerLevel, generator: ChunkGenerator = level.chunkSource.generator): GeneratedLand =
            GeneratedLand(generator, level.chunkSource.randomState(), level.minY, level.height)
    }
}

/**
 * The land round one capital, for its plan: in the city's frame (its middle at [center], turned [turn]) and
 * counted from the lower town's standing height. Heights are the generator's, read on a lattice every [STEP]
 * blocks and blended between (the lie of the land, smooth and cheap to ask anywhere), each worked out the first
 * time it's needed and kept. Every chunk of the city reads the same land, however many threads draw at once:
 * two that ask for the same point at the same moment just both work it out.
 */
class CapitalLand(private val generated: GeneratedLand, private val center: BlockPos, private val turn: Rotation, reach: Int) : Land {
    /** Lattice points each way from the middle; further out reads the edge. */
    private val half = reach / STEP + 2
    private val size = half * 2 + 1
    private val surfaces = IntArray(size * size) { UNKNOWN }
    private val floors = IntArray(size * size) { UNKNOWN }

    override val sea: Int = generated.seaLevel - center.y

    override fun height(x: Int, z: Int): Int = (look(x, z, true) - center.y).roundToInt()

    override fun wet(x: Int, z: Int): Boolean = look(x, z, false) > 0.0

    /**
     * At a column in the city's frame, the standing height ([height] true) or how deep the water stands there
     * (false), blended from the four lattice points round it. A column is under water where the ground, blended,
     * lies under the water's surface at the wet points among the four.
     */
    private fun look(x: Int, z: Int, height: Boolean): Double {
        val world = CapitalPiece.toWorld(center, turn, x, 0, z)
        val u = (world.x - center.x).toDouble() / STEP
        val v = (world.z - center.z).toDouble() / STEP
        val i = floor(u).toInt()
        val j = floor(v).toInt()
        val fu = u - i
        val fv = v - j
        var ground = 0.0
        var surface = 0.0
        var water = 0.0
        var wetWeight = 0.0
        for (k in 0..3) {
            val pi = i + (k and 1)
            val pj = j + (k shr 1)
            val w = (if (k and 1 == 0) 1 - fu else fu) * (if (k shr 1 == 0) 1 - fv else fv)
            val s = surfaceAt(pi, pj)
            val f = floorAt(pi, pj, s)
            ground += w * f
            surface += w * s
            if (f < s) {
                water += w * s
                wetWeight += w
            }
        }
        if (wetWeight == 0.0) return if (height) surface else 0.0
        // The water's surface where it stands, and how far under it the ground lies here
        val level = water / wetWeight
        val depth = level - ground - 0.5
        return if (height) (if (depth > 0) level else surface) else depth
    }

    private fun index(i: Int, j: Int): Int = (i.coerceIn(-half, half) + half) * size + (j.coerceIn(-half, half) + half)

    private fun surfaceAt(i: Int, j: Int): Int {
        val k = index(i, j)
        var s = surfaces[k]
        if (s == UNKNOWN) {
            s = generated.surface(pointX(i), pointZ(j))
            surfaces[k] = s
        }
        return s
    }

    /** Only low ground is asked about water (seas, rivers and marshes), as when the site was chosen. */
    private fun floorAt(i: Int, j: Int, surface: Int): Int {
        if (surface > generated.seaLevel + 1) return surface
        val k = index(i, j)
        var f = floors[k]
        if (f == UNKNOWN) {
            f = generated.floor(pointX(i), pointZ(j))
            floors[k] = f
        }
        return f
    }

    private fun pointX(i: Int): Int = center.x + i.coerceIn(-half, half) * STEP
    private fun pointZ(j: Int): Int = center.z + j.coerceIn(-half, half) * STEP

    companion object {
        /** Blocks between lattice points. */
        const val STEP = 4
        private const val UNKNOWN = Int.MIN_VALUE
    }
}
