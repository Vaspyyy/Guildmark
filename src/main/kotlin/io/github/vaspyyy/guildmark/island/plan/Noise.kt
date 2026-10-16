package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.floor

/** Hashes and smooth noise for shaping islands: the same numbers on every machine, every time, from the island's seed. */
object Noise {
    /** A well-mixed 32-bit hash of a lattice point and a seed. */
    fun hash(x: Int, y: Int, z: Int, seed: Int): Int {
        var h = (x * 73856093) xor (y * 19349663) xor (z * 83492791) xor (seed * -1640531535)
        h = h xor (h ushr 15)
        h *= -2048144777
        h = h xor (h ushr 13)
        h *= -1028477379
        h = h xor (h ushr 16)
        return h
    }

    /** [hash] as a number in [0, 1). */
    fun unit(x: Int, y: Int, z: Int, seed: Int): Double = (hash(x, y, z, seed) ushr 1) / 2147483648.0

    /** A seed for one use of the noise, from the island's seed and what it's for. */
    fun seed(island: Long, use: Int): Int = hash((island ushr 32).toInt(), island.toInt(), use, 0x51ED)

    private fun fade(t: Double): Double = t * t * (3.0 - 2.0 * t)

    /** Smooth value noise in [0, 1] over the plane: one value per whole-number point, eased between them. */
    fun value(x: Double, z: Double, seed: Int): Double {
        val x0 = floor(x)
        val z0 = floor(z)
        val ix = x0.toInt()
        val iz = z0.toInt()
        val fx = fade(x - x0)
        val fz = fade(z - z0)
        val a = unit(ix, 0, iz, seed)
        val b = unit(ix + 1, 0, iz, seed)
        val c = unit(ix, 0, iz + 1, seed)
        val d = unit(ix + 1, 0, iz + 1, seed)
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz
    }

    /** Smooth value noise in [0, 1] in space. */
    fun value3(x: Double, y: Double, z: Double, seed: Int): Double {
        val x0 = floor(x)
        val y0 = floor(y)
        val z0 = floor(z)
        val ix = x0.toInt()
        val iy = y0.toInt()
        val iz = z0.toInt()
        val fx = fade(x - x0)
        val fy = fade(y - y0)
        val fz = fade(z - z0)
        var out = 0.0
        for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
            val w = (if (dx == 1) fx else 1 - fx) * (if (dy == 1) fy else 1 - fy) * (if (dz == 1) fz else 1 - fz)
            out += w * unit(ix + dx, iy + dy, iz + dz, seed)
        }
        return out
    }

    /** Fractal noise in [0, 1]: [octaves] layers of [value], each twice as fine and half as strong as the last. */
    fun fbm(x: Double, z: Double, seed: Int, octaves: Int = 3): Double {
        var total = 0.0
        var amp = 1.0
        var norm = 0.0
        var k = 1.0
        for (o in 0 until octaves) {
            total += amp * value(x * k + 17.3 * o, z * k + 5.1 * o, seed + 31 * o)
            norm += amp
            amp *= 0.5
            k *= 2.0
        }
        return total / norm
    }

    /** Ridged fractal noise in [0, 1]: sharp crests where [fbm] would pass through its middle. */
    fun ridged(x: Double, z: Double, seed: Int, octaves: Int = 3): Double {
        var total = 0.0
        var amp = 1.0
        var norm = 0.0
        var k = 1.0
        for (o in 0 until octaves) {
            val v = 1.0 - kotlin.math.abs(value(x * k + 11.7 * o, z * k + 3.9 * o, seed + 47 * o) * 2.0 - 1.0)
            total += amp * v * v
            norm += amp
            amp *= 0.5
            k *= 2.0
        }
        return total / norm
    }
}

/** 0 below [a], 1 above [b], eased between. */
fun smooth(a: Double, b: Double, x: Double): Double {
    val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** A source of numbers that comes out the same for the same seed: for laying out what stands on an island. */
class Dice(seed: Long) {
    private var state = seed xor -0x61c8864680b583ebL

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** In [0, 1). */
    fun next(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    /** In [0, n). */
    fun int(n: Int): Int = ((nextLong() ushr 33) % n).toInt()

    /** In [a, b], both included. */
    fun int(a: Int, b: Int): Int = a + int(b - a + 1)

    fun range(a: Double, b: Double): Double = a + (b - a) * next()

    fun chance(p: Double): Boolean = next() < p

    fun <T> pick(list: List<T>): T = list[int(list.size)]

    fun <T> pick(array: Array<T>): T = array[int(array.size)]
}
