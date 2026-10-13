package io.github.vaspyyy.guildmark.region

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * How a world is cut into lands: a grid of cells about a mile and a half across, each centre nudged off
 * its grid point and the borders between them bent by a slow noise, so no line runs straight. Each cell
 * is then split by the kind of land in it into regions (see RegionMap). Everything here follows from the
 * world seed alone, so it's the same on every run, in every thread, and nothing has to be saved.
 */
class RegionCells private constructor(val seed: Long) {
    private val warpX = Noise2(seed xor 0x5EED_A11CE5L)
    private val warpZ = Noise2(seed xor 0x0BADC0FFEEL)
    private val crystalNoise = Noise2(seed xor 0x7C3157A1L)

    /** The nudged centre of cell ([cx], [cz]), as world x and z. */
    fun centerX(cx: Int, cz: Int): Double = (cx + 0.5 + (unit(hash(cx, cz, 1)) - 0.5) * 2 * JITTER) * CELL
    fun centerZ(cx: Int, cz: Int): Double = (cz + 0.5 + (unit(hash(cx, cz, 2)) - 0.5) * 2 * JITTER) * CELL

    /** The cell world spot ([x], [z]) lies in, packed as [key]. */
    fun cellAt(x: Double, z: Double): Long {
        // Bend the borders: look the cell up a little way off, by a slow noise
        val wx = x + warpX.at(x * WARP_SCALE, z * WARP_SCALE) * WARP
        val wz = z + warpZ.at(x * WARP_SCALE, z * WARP_SCALE) * WARP
        val gx = floor(wx / CELL).toInt()
        val gz = floor(wz / CELL).toInt()
        var best = Double.MAX_VALUE
        var bestX = gx
        var bestZ = gz
        for (dx in -1..1) for (dz in -1..1) {
            val cx = gx + dx
            val cz = gz + dz
            val ox = centerX(cx, cz) - wx
            val oz = centerZ(cx, cz) - wz
            val d = ox * ox + oz * oz
            if (d < best) {
                best = d
                bestX = cx
                bestZ = cz
            }
        }
        return key(bestX, bestZ)
    }

    fun cellAt(x: Int, z: Int): Long = cellAt(x + 0.5, z + 0.5)

    /** Whether crystal grows in the deep caves under cell [cell] (about a third of them). */
    fun crystalCell(cell: Long): Boolean = Math.floorMod(hash(keyX(cell), keyZ(cell), 7), 100) < CRYSTAL_PERCENT

    /**
     * Whether ([x], [y], [z]) lies in the crystal deeps: deep enough, under a crystal cell, and inside the
     * winding blots the crystal keeps to there (about half of such a cell).
     */
    fun crystalAt(x: Int, y: Int, z: Int): Boolean {
        if (y > CRYSTAL_TOP) return false
        if (!crystalCell(cellAt(x, z))) return false
        return crystalNoise.at(x / CRYSTAL_SCALE, z / CRYSTAL_SCALE) > 0.0
    }

    private fun hash(cx: Int, cz: Int, salt: Int): Int {
        var h = seed * 6364136223846793005L + cx * 341873128712L + cz * 132897987541L + salt * 2654435761L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        return (h xor (h ushr 33)).toInt()
    }

    private fun unit(h: Int): Double = (h.toLong() and 0xFFFFFFL) / 16777216.0

    companion object {
        /** A cell's width in blocks, before its centre is nudged. */
        const val CELL = 1536
        private const val JITTER = 0.3
        /** How far (blocks) and how slowly the borders bend. */
        private const val WARP = 220.0
        private const val WARP_SCALE = 1.0 / 900.0
        /** The crystal deeps lie below this height. */
        const val CRYSTAL_TOP = -8
        private const val CRYSTAL_PERCENT = 35
        private const val CRYSTAL_SCALE = 260.0

        private val BY_SEED = ConcurrentHashMap<Long, RegionCells>()

        fun of(seed: Long): RegionCells = BY_SEED.computeIfAbsent(seed) { RegionCells(it) }

        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        fun keyX(key: Long): Int = (key shr 32).toInt()
        fun keyZ(key: Long): Int = key.toInt()
    }
}

/** Smooth gradient noise in two dimensions, about -1 to 1, the same for the same seed on any thread. */
internal class Noise2(seed: Long) {
    private val perm = IntArray(512)

    init {
        val order = IntArray(256) { it }
        var s = seed
        for (i in 255 downTo 1) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val j = Math.floorMod((s ushr 33).toInt(), i + 1)
            val t = order[i]
            order[i] = order[j]
            order[j] = t
        }
        for (i in 0 until 512) perm[i] = order[i and 255]
    }

    fun at(x: Double, z: Double): Double {
        val x0 = floor(x)
        val z0 = floor(z)
        val ix = x0.toInt() and 255
        val iz = z0.toInt() and 255
        val fx = x - x0
        val fz = z - z0
        val u = fade(fx)
        val v = fade(fz)
        val a = grad(perm[perm[ix] + iz], fx, fz)
        val b = grad(perm[perm[ix + 1] + iz], fx - 1, fz)
        val c = grad(perm[perm[ix] + iz + 1], fx, fz - 1)
        val d = grad(perm[perm[ix + 1] + iz + 1], fx - 1, fz - 1)
        val top = a + (b - a) * u
        val bottom = c + (d - c) * u
        return (top + (bottom - top) * v) * 1.4
    }

    private fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)

    private fun grad(h: Int, x: Double, z: Double): Double = when (h and 7) {
        0 -> x + z
        1 -> x - z
        2 -> -x + z
        3 -> -x - z
        4 -> x
        5 -> -x
        6 -> z
        else -> -z
    }
}
