package io.github.vaspyyy.guildmark.sea

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shape of one of the Maelgrasp's arms, worked out alike on the server (where it can be struck, what it sweeps,
 * where it holds whoever it has caught) and on every client (where it's drawn) from the few numbers its entity
 * carries. Nothing is remembered from one moment to the next: the same numbers always make the same arm.
 *
 * The arm's own frame is in blocks: the origin where it breaks the surface, y up, z the way it faces (toward the ship
 * it's attacking) and x to its right. Its root is under the water, [ROOT_DEPTH] below.
 *
 * It rises straight up out of the sea and bends over to reach its tip to a point, arriving there travelling away from
 * a direction it comes "from" (from above for a slam onto the deck, from the side for a sweep across it): a curve
 * whose handles are stretched until it's as long as the arm. What's left of its length past the point coils round
 * (more of it the harder it [curls][solve]), and a ripple runs up it, nothing at the root and most at the tip.
 */
object Tentacle {
    const val SEGMENTS = 12

    /** Each segment's length, root to tip. */
    @JvmField
    val LENGTHS = floatArrayOf(1.4f, 1.3f, 1.2f, 1.12f, 1.04f, 0.96f, 0.9f, 0.84f, 0.78f, 0.72f, 0.66f, 0.6f)

    /** How thick it is at each joint (its radius), root to tip. */
    @JvmField
    val RADII = floatArrayOf(0.62f, 0.56f, 0.5f, 0.45f, 0.4f, 0.36f, 0.32f, 0.28f, 0.24f, 0.2f, 0.16f, 0.12f, 0.07f)

    val TOTAL: Float = LENGTHS.sum()

    /** How far under the surface its root is. */
    const val ROOT_DEPTH = 2.4f

    /** Points along the curve for measuring it and walking along it. */
    private const val SAMPLES = 40
    /** The longest its handles stretch (the tallest it rears to reach somewhere close). */
    private const val HANDLE_MAX = 9f
    /** The handle at the tip, against the one at the root. */
    private const val FROM_HANDLE = 0.8f
    /** How far the root's handle leans toward the point, against how far it rises. */
    private const val LEAN = 0.25f
    /** The part of its length a full curl takes into the coil. */
    private const val CURL_SHARE = 0.4f
    /** The farthest it reaches, of its whole length. */
    private const val REACH = 0.97f

    /** One arm's joints, root first, and the room to work them out in. */
    class Shape {
        /** (x, y, z) of each joint, in the arm's frame. */
        val joints = FloatArray((SEGMENTS + 1) * 3)
        internal val curve = FloatArray((SAMPLES + 1) * 3)
        internal val raw = FloatArray((SEGMENTS + 1) * 3)

        fun x(i: Int): Float = joints[i * 3]
        fun y(i: Int): Float = joints[i * 3 + 1]
        fun z(i: Int): Float = joints[i * 3 + 2]

        /** Where joint [i] is in the world, for an arm breaking the surface at [origin] and facing [yaw]. */
        fun world(i: Int, origin: Vec3, yaw: Float): Vec3 = toWorld(x(i), y(i), z(i), origin, yaw)

        /** The middle of the coil at the tip: where it holds whatever it has caught. */
        fun grip(): Vec3 {
            var x = 0f
            var y = 0f
            var z = 0f
            for (i in SEGMENTS - 3..SEGMENTS) {
                x += x(i)
                y += y(i)
                z += z(i)
            }
            return Vec3(x / 4.0, y / 4.0, z / 4.0)
        }
    }

    /**
     * Shapes [shape]: its tip reaching for ([tx], [ty], [tz]) and arriving travelling away from ([fx], [fy], [fz]);
     * [curl] from 0 (straight out) to 1 (coiled tight); [ripple] blocks of wave running up it; [time] in ticks,
     * and a [phase] of its own so no two arms ripple alike.
     */
    fun solve(shape: Shape, tx: Float, ty: Float, tz: Float, fx: Float, fy: Float, fz: Float, curl: Float, ripple: Float, time: Float, phase: Float) {
        val c = curl.coerceIn(0f, 1f)
        val oy = -ROOT_DEPTH
        // Out of its reach, it reaches as far as it can toward the point
        var px = tx
        var py = ty
        var pz = tz
        val dl = sqrt(px * px + (py - oy) * (py - oy) + pz * pz)
        if (dl > TOTAL * REACH) {
            val k = TOTAL * REACH / dl
            px *= k
            py = oy + (py - oy) * k
            pz *= k
        }
        var ax = fx
        var ay = fy
        var az = fz
        val al = sqrt(ax * ax + ay * ay + az * az)
        if (al < 1.0e-4f) {
            ax = 0f
            ay = 1f
            az = 0f
        } else {
            ax /= al
            ay /= al
            az /= al
        }

        // Stretch the handles until the curve is as long as its share of the arm, or as near as it can be
        val want = TOTAL * (1f - CURL_SHARE * c)
        val h = when {
            sample(shape, px, py, pz, ax, ay, az, 0f) >= want -> 0f
            sample(shape, px, py, pz, ax, ay, az, HANDLE_MAX) <= want -> HANDLE_MAX
            else -> {
                var lo = 0f
                var hi = HANDLE_MAX
                repeat(16) {
                    val mid = (lo + hi) * 0.5f
                    if (sample(shape, px, py, pz, ax, ay, az, mid) < want) lo = mid else hi = mid
                }
                (lo + hi) * 0.5f
            }
        }
        sample(shape, px, py, pz, ax, ay, az, h)
        val curve = shape.curve
        val raw = shape.raw

        // The way the tip travels as it arrives, and the way it coils from there: down, or toward the ship when it
        // arrives going straight up or down
        val e = SAMPLES * 3
        var ex = curve[e] - curve[e - 3]
        var ey = curve[e + 1] - curve[e - 2]
        var ez = curve[e + 2] - curve[e - 1]
        val el = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1.0e-5f)
        ex /= el
        ey /= el
        ez /= el
        val w = smooth(0.6f, 0.95f, abs(ey))
        var bx = 0f
        var by = -(1f - w)
        var bz = w
        val bd = bx * ex + by * ey + bz * ez
        bx -= bd * ex
        by -= bd * ey
        bz -= bd * ez
        var bl = sqrt(bx * bx + by * by + bz * bz)
        if (bl < 1.0e-3f) {
            bx = 1f
            by = 0f
            bz = 0f
            bl = 1f
        }
        bx /= bl
        by /= bl
        bz /= bl

        // Walk the joints out along the curve, each its segment's length on from the last; past its end, round the coil
        raw[0] = curve[0]
        raw[1] = curve[1]
        raw[2] = curve[2]
        var at = 0
        var coil = -1f
        var radius = 0.5f
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (i in 1..SEGMENTS) {
            val step = LENGTHS[i - 1]
            val qx = raw[i * 3 - 3]
            val qy = raw[i * 3 - 2]
            val qz = raw[i * 3 - 1]
            var placed = false
            if (coil < 0f) {
                while (at < SAMPLES) {
                    val nx = curve[at * 3 + 3]
                    val ny = curve[at * 3 + 4]
                    val nz = curve[at * 3 + 5]
                    val far = dist(qx, qy, qz, nx, ny, nz)
                    if (far >= step) {
                        // Between this sample and the next: find where along it the segment ends
                        val sx = curve[at * 3]
                        val sy = curve[at * 3 + 1]
                        val sz = curve[at * 3 + 2]
                        var lo = 0f
                        var hi = 1f
                        repeat(12) {
                            val mid = (lo + hi) * 0.5f
                            val d = dist(qx, qy, qz, sx + (nx - sx) * mid, sy + (ny - sy) * mid, sz + (nz - sz) * mid)
                            if (d < step) lo = mid else hi = mid
                        }
                        val f = (lo + hi) * 0.5f
                        raw[i * 3] = sx + (nx - sx) * f
                        raw[i * 3 + 1] = sy + (ny - sy) * f
                        raw[i * 3 + 2] = sz + (nz - sz) * f
                        placed = true
                        break
                    }
                    at++
                }
                if (!placed) {
                    // The curve's run out: the rest of it coils, a circle on from the last joint
                    var rest = 0f
                    for (k in i - 1 until SEGMENTS) rest += LENGTHS[k]
                    radius = (rest / (1.7f * PI.toFloat())).coerceIn(COIL_MIN, COIL_MAX)
                    coil = 0f
                    cx = qx
                    cy = qy
                    cz = qz
                }
            }
            if (!placed) {
                // Around the circle by the angle that makes a chord of this segment's length
                coil += 2f * asin(min(1f, step / (2f * radius)))
                raw[i * 3] = cx + ex * radius * sin(coil) + bx * radius * (1f - cos(coil))
                raw[i * 3 + 1] = cy + ey * radius * sin(coil) + by * radius * (1f - cos(coil))
                raw[i * 3 + 2] = cz + ez * radius * sin(coil) + bz * radius * (1f - cos(coil))
            }
        }

        // The ripple running up it
        val out = shape.joints
        out[0] = raw[0]
        out[1] = raw[1]
        out[2] = raw[2]
        var run = 0f
        for (i in 1..SEGMENTS) {
            run += LENGTHS[i - 1]
            val s = run / TOTAL
            val amp = ripple * s.pow(1.4f)
            var dx = 0f
            var dy = 0f
            var dz = 0f
            if (amp > 1.0e-4f) {
                var tx1 = raw[i * 3] - raw[i * 3 - 3]
                var ty1 = raw[i * 3 + 1] - raw[i * 3 - 2]
                var tz1 = raw[i * 3 + 2] - raw[i * 3 - 1]
                val tl = sqrt(tx1 * tx1 + ty1 * ty1 + tz1 * tz1).coerceAtLeast(1.0e-5f)
                tx1 /= tl
                ty1 /= tl
                tz1 /= tl
                // Across it, level (or along x when it runs straight up or down), and across both
                var nx = tz1
                var nz = -tx1
                var nl = sqrt(nx * nx + nz * nz)
                if (nl < 1.0e-3f) {
                    nx = 1f
                    nz = 0f
                    nl = 1f
                }
                nx /= nl
                nz /= nl
                val mx = ty1 * nz
                val my = tz1 * nx - tx1 * nz
                val mz = -ty1 * nx
                val w1 = sin(6.2f * s - 0.21f * time + phase)
                val w2 = sin(4.1f * s - 0.16f * time + phase * 1.7f + 1.3f)
                dx = nx * amp * w1 + mx * amp * 0.6f * w2
                dy = my * amp * 0.6f * w2
                dz = nz * amp * w1 + mz * amp * 0.6f * w2
            }
            // Each segment kept its own length, bent toward where the ripple takes it
            val wx = raw[i * 3] + dx - out[i * 3 - 3]
            val wy = raw[i * 3 + 1] + dy - out[i * 3 - 2]
            val wz = raw[i * 3 + 2] + dz - out[i * 3 - 1]
            val wl = sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(1.0e-5f)
            val k = LENGTHS[i - 1] / wl
            out[i * 3] = out[i * 3 - 3] + wx * k
            out[i * 3 + 1] = out[i * 3 - 2] + wy * k
            out[i * 3 + 2] = out[i * 3 - 1] + wz * k
        }
    }

    /** A point in an arm's frame, in the world. */
    fun toWorld(x: Float, y: Float, z: Float, origin: Vec3, yaw: Float): Vec3 {
        val r = yaw * (PI.toFloat() / 180f)
        val fx = -sin(r)
        val fz = cos(r)
        // Its right hand, facing that way
        val rx = -cos(r)
        val rz = -sin(r)
        return Vec3(origin.x + rx * x + fx * z, origin.y + y, origin.z + rz * x + fz * z)
    }

    /** A point in the world, in an arm's frame (see [toWorld]). */
    fun toLocal(at: Vec3, origin: Vec3, yaw: Float): Vec3 {
        val r = yaw * (PI.toFloat() / 180f)
        val dx = at.x - origin.x
        val dz = at.z - origin.z
        return Vec3(-cos(r) * dx - sin(r) * dz, at.y - origin.y, -sin(r) * dx + cos(r) * dz)
    }

    /** Samples the curve with handles [h] long into the shape's curve, and gives its length. */
    private fun sample(shape: Shape, px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, h: Float): Float {
        val c = shape.curve
        val oy = -ROOT_DEPTH
        // Root, its handle up and leaning a little toward the point; tip, its handle back the way it comes from
        var lx = px
        var lz = pz
        val ll = sqrt(lx * lx + lz * lz)
        if (ll < 1.0e-3f) {
            lx = 0f
            lz = 1f
        } else {
            lx /= ll
            lz /= ll
        }
        val p1x = lx * h * LEAN
        val p1y = oy + h
        val p1z = lz * h * LEAN
        val p2x = px + ax * h * FROM_HANDLE
        val p2y = py + ay * h * FROM_HANDLE
        val p2z = pz + az * h * FROM_HANDLE
        var length = 0f
        for (k in 0..SAMPLES) {
            val t = k / SAMPLES.toFloat()
            val u = 1f - t
            val b0 = u * u * u
            val b1 = 3f * u * u * t
            val b2 = 3f * u * t * t
            val b3 = t * t * t
            val x = b1 * p1x + b2 * p2x + b3 * px
            val y = b0 * oy + b1 * p1y + b2 * p2y + b3 * py
            val z = b1 * p1z + b2 * p2z + b3 * pz
            c[k * 3] = x
            c[k * 3 + 1] = y
            c[k * 3 + 2] = z
            if (k > 0) length += dist(c[k * 3 - 3], c[k * 3 - 2], c[k * 3 - 1], x, y, z)
        }
        return length
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The coil's radius: never so tight a segment can't span it. */
    private const val COIL_MIN = 0.38f
    private const val COIL_MAX = 0.8f
}
