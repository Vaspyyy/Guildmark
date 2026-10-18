package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.Caravans
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wagon's way as its bodies drive it: the leg's points by how far along they lie, a place for any distance along
 * it. Past either end it runs on straight the way that end heads (at that end's height), so a wagon pulling up with
 * its horse at the stand, or driving off past the start of its leg, has somewhere to be.
 */
internal class Way(points: List<Caravans.Point>) {
    private val n = points.size
    private val xs = DoubleArray(n) { points[it].pos.x }
    private val ys = DoubleArray(n) { points[it].pos.y }
    private val zs = DoubleArray(n) { points[it].pos.z }
    private val al = DoubleArray(n) { points[it].along }

    /** How long it is, start to stand. */
    val length: Double = if (n > 0) al[n - 1] else 0.0

    val empty: Boolean get() = n < 2

    /** Where [along] blocks along it lies: its height the road's, as the leg has it. */
    fun at(along: Double): Vec3 {
        if (n == 0) return Vec3.ZERO
        if (n == 1) return Vec3(xs[0], ys[0], zs[0])
        val i = when {
            along <= 0.0 -> 0
            along >= length -> n - 2
            else -> index(along).coerceAtMost(n - 2)
        }
        val span = al[i + 1] - al[i]
        val t = if (span < 1e-9) 0.0 else (along - al[i]) / span
        val ty = t.coerceIn(0.0, 1.0)
        return Vec3(xs[i] + (xs[i + 1] - xs[i]) * t, ys[i] + (ys[i + 1] - ys[i]) * ty, zs[i] + (zs[i + 1] - zs[i]) * t)
    }

    /** Which way it heads [along] blocks along, on the ground: a unit (x, z), from a block either side. */
    fun heading(along: Double): Pair<Double, Double> {
        val a = at(along - 1.0)
        val b = at(along + 1.0)
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = hypot(dx, dz)
        return if (len < 1e-6) 0.0 to 1.0 else (dx / len) to (dz / len)
    }

    /** To the right of the way in at [along]: a unit (x, z). */
    fun right(along: Double): Pair<Double, Double> {
        val (dx, dz) = heading(along)
        return -dz to dx
    }

    /** How far along it the spot nearest [x], [z] lies. */
    fun nearest(x: Double, z: Double): Double {
        var best = Double.MAX_VALUE
        var along = 0.0
        for (k in 0 until n - 1) {
            val ax = xs[k]
            val az = zs[k]
            val dx = xs[k + 1] - ax
            val dz = zs[k + 1] - az
            val len = dx * dx + dz * dz
            val t = if (len <= 1e-9) 0.0 else (((x - ax) * dx + (z - az) * dz) / len).coerceIn(0.0, 1.0)
            val px = ax + dx * t - x
            val pz = az + dz * t - z
            val d = px * px + pz * pz
            if (d < best) {
                best = d
                along = al[k] + (al[k + 1] - al[k]) * t
            }
        }
        return along
    }

    /** How far [x], [z] lies from it. */
    fun distance(x: Double, z: Double): Double {
        val p = at(nearest(x, z))
        return hypot(p.x - x, p.z - z)
    }

    private fun index(along: Double): Int {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (al[mid] <= along) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** A line a wagon drives along, by how far it has gone ([at]: where it is [s] blocks on). */
internal fun interface Path {
    fun at(s: Double): Vec3
}

/**
 * The wagon and its horse placed along a path like a train: the back axle at a distance, the kingpin the wheelbase
 * on, the horse its length on from there, each measured straight (as the shafts and the bed are rigid), so on a bend
 * the bed cuts the corner and the front steers into it.
 */
internal object Train {
    /** Blocks from the back axle to the kingpin (WagonMesh.WHEELBASE), and from the kingpin to the horse's middle (HORSE_AHEAD). */
    const val WHEELBASE = 32.0 / 16.0
    const val AHEAD = 51.0 / 16.0

    /** Blocks from the back axle to the horse's middle, the shafts straight. */
    const val LENGTH = WHEELBASE + AHEAD

    /** Each wheel's middle out from the middle, and the axles' track. */
    const val HALF_TRACK = 13.0 / 16.0

    /** How far on along [path] from [from] lies the spot [length] blocks away from it, straight across the ground. */
    fun chord(path: Path, from: Double, length: Double): Double {
        val a = path.at(from)
        var s = from + length
        repeat(8) {
            val b = path.at(s)
            val d = hypot(b.x - a.x, b.z - a.z)
            if (abs(d - length) < 1e-4) return s
            s += length - d
        }
        return s
    }

    /** How far back along [path] from [from] lies the spot [length] blocks away from it, straight across the ground (where the kingpin is behind the horse, the back axle behind the kingpin). */
    fun back(path: Path, from: Double, length: Double): Double {
        val a = path.at(from)
        var s = from - length
        repeat(8) {
            val b = path.at(s)
            val d = hypot(b.x - a.x, b.z - a.z)
            if (abs(d - length) < 1e-4) return s
            s -= length - d
        }
        return s
    }

    /** The bulb a wagon turns round in at its stand, its loops [r] blocks round: right a sixth of a turn, left five sixths, right a sixth, back where it began facing the other way. */
    fun bulbLength(r: Double): Double = r * 7.0 * PI / 3.0

    /**
     * Where [s] blocks into a bulb turn lies, the bulb beginning at [x], [z] heading ([hx], [hz]), turning first to
     * the side [side] (1 its right, -1 its left) with loops [r] blocks round: its place and heading.
     */
    fun bulb(x: Double, z: Double, hx: Double, hz: Double, side: Double, r: Double, s: Double): DoubleArray {
        // In the bulb's own frame: u across (toward the first turn's side), w straight on; h the heading's turn from
        // straight on toward +u. The loops' middles: (r, 0), then (0, r sqrt 3), then (-r, 0)
        val a1 = PI / 3.0
        val l1 = r * a1
        val l2 = r * 5.0 * PI / 3.0
        val u: Double
        val w: Double
        val h: Double
        when {
            s <= 0.0 -> {
                u = 0.0
                w = s
                h = 0.0
            }
            s <= l1 -> {
                val t = s / r
                u = r - r * cos(t)
                w = r * sin(t)
                h = t
            }
            s <= l1 + l2 -> {
                val phi = -a1 + (s - l1) / r
                u = r * cos(phi)
                w = r * sqrt(3.0) + r * sin(phi)
                h = -phi
            }
            s <= 2.0 * l1 + l2 -> {
                val tau = (s - l1 - l2) / r
                val psi = a1 - tau
                u = -r + r * cos(psi)
                w = r * sin(psi)
                h = 2.0 * a1 + tau
            }
            else -> {
                u = 0.0
                w = -(s - (2.0 * l1 + l2))
                h = PI
            }
        }
        val ux = -hz * side
        val uz = hx * side
        return doubleArrayOf(x + ux * u + hx * w, z + uz * u + hz * w, hx * cos(h) + ux * sin(h), hz * cos(h) + uz * sin(h))
    }
}

/** The ground under a wheel or a hoof, from what's really there (in loaded chunks), as the handcart's is. */
internal object Ground {
    /** How far over (and under) the road's own height the ground is looked for. */
    private const val CLIMB = 1.25
    private const val DROP = 3.0

    private val cursor = BlockPos.MutableBlockPos()

    /**
     * The height of the ground at [x], [z] near [ref] (the road's height there): the top of the highest block within
     * [CLIMB] over it, down to [DROP] under it; NaN where it isn't loaded or a wall stands higher than that.
     */
    fun at(level: ServerLevel, x: Double, z: Double, ref: Double): Double {
        val bx = Mth.floor(x)
        val bz = Mth.floor(z)
        val top = Mth.floor(ref + CLIMB)
        val bottom = Mth.floor(ref - DROP)
        cursor.set(bx, top, bz)
        if (!level.isLoaded(cursor)) return Double.NaN
        val fx = x - bx
        val fz = z - bz
        for (by in top downTo bottom) {
            cursor.setY(by)
            val state = level.getBlockState(cursor)
            if (state.isAir) continue
            val shape = state.getCollisionShape(level, cursor)
            if (shape.isEmpty) continue
            val h = shape.max(Direction.Axis.Y, fz, fx)
            if (h == Double.NEGATIVE_INFINITY) continue
            val g = by + h
            return if (g > ref + CLIMB) Double.NaN else g
        }
        return ref - DROP
    }

    /** The ground under a wheel of [radius] blocks rolling at [x], [z] heading ([hx], [hz]): it rides up over a step a little ahead or behind before it gets there. */
    fun wheel(level: ServerLevel, x: Double, z: Double, hx: Double, hz: Double, ref: Double, radius: Double): Double {
        val mid = at(level, x, z, ref)
        val patch = radius * 0.55
        val rim = radius - sqrt(max(0.0, radius * radius - patch * patch))
        var best = mid
        for (k in intArrayOf(-1, 1)) {
            val g = at(level, x + hx * patch * k, z + hz * patch * k, ref)
            if (g.isNaN()) continue
            val rides = g - rim
            if (best.isNaN() || rides > best) best = rides
        }
        return best
    }

    /** The height to stand on at [x], [z] for someone near [ref], the world's top where nothing better is found. */
    fun stand(level: ServerLevel, x: Double, z: Double, ref: Double): Double {
        val g = at(level, x, z, ref)
        if (!g.isNaN()) return g
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z)).toDouble()
    }

    /** Whether something solid stands at [x], [y], [z] (a block a body can't walk into). */
    fun solid(level: ServerLevel, x: Double, y: Double, z: Double): Boolean {
        cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))
        if (!level.isLoaded(cursor)) return false
        val state = level.getBlockState(cursor)
        return !state.isAir && !state.getCollisionShape(level, cursor).isEmpty
    }
}
