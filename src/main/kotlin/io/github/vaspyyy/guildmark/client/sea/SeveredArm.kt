package io.github.vaspyyy.guildmark.client.sea

import io.github.vaspyyy.guildmark.sea.Tentacle
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The length of one of the Maelgrasp's arms hacked off it: it tumbles away from where it was cut, writhing, out from the
 * ship and down into the sea, and sinks. Worked out afresh every frame from the joints it had the moment it was cut
 * (MaelgraspArm.severed) and the ticks since, so it falls the same however fast the game runs; the renderer draws it and
 * SeaClient throws up the splash where it hits the water. All in the arm's own frame (see [Tentacle]), whose y = 0 is the
 * surface and whose z faces the ship.
 */
object SeveredArm {
    /**
     * Fills [out] with the joints of the length cut off at joint [cut] (that one to the tip), [ticks] after it was cut from
     * an arm whose joints were [joints] then. Joints before [cut] are left at the cut, unseen.
     */
    fun place(joints: FloatArray, cut: Int, ticks: Float, out: FloatArray) {
        val from = cut.coerceIn(1, Tentacle.SEGMENTS - 1)
        var cx = 0f
        var cy = 0f
        var cz = 0f
        val n = Tentacle.SEGMENTS - from + 1
        for (i in from..Tentacle.SEGMENTS) {
            cx += joints[i * 3]
            cy += joints[i * 3 + 1]
            cz += joints[i * 3 + 2]
        }
        cx /= n
        cy /= n
        cz /= n
        val hit = hitAt(cy)
        val t = ticks.coerceAtLeast(0f)
        // Falling freely till it meets the water, then slowed hard by it and sinking
        val drop = if (t <= hit) {
            0.5f * GRAVITY * t * t
        } else {
            val after = t - hit
            val impact = GRAVITY * hit
            0.5f * GRAVITY * hit * hit + SINK * after + (impact - SINK).coerceAtLeast(0f) * DRAG * (1f - exp(-after / DRAG))
        }
        // Out from the ship as it goes, its upper end going over and out
        val out1 = -DRIFT * t
        val turn = TUMBLE * min(t, hit) + (if (t > hit) TUMBLE_AFTER * ROLL_FADE * (1f - exp(-(t - hit) / ROLL_FADE)) else 0f)
        val c = cos(-turn)
        val s = sin(-turn)
        // A wave of writhing down it, dying away as the life goes out of it
        val writhe = WRITHE * exp(-t / WRITHE_FADE)
        val span = (Tentacle.SEGMENTS - from).coerceAtLeast(1).toFloat()
        for (i in from..Tentacle.SEGMENTS) {
            val lx = joints[i * 3] - cx
            val ly = joints[i * 3 + 1] - cy
            val lz = joints[i * 3 + 2] - cz
            val k = (i - from).toFloat()
            val w = writhe * sin(1.4f * k - 0.55f * t) * (0.35f + 0.65f * k / span)
            out[i * 3] = cx + lx + w
            out[i * 3 + 1] = cy - drop + (ly * c - lz * s) + w * 0.3f
            out[i * 3 + 2] = cz + out1 + (ly * s + lz * c)
        }
        for (i in 0 until from) {
            out[i * 3] = out[from * 3]
            out[i * 3 + 1] = out[from * 3 + 1]
            out[i * 3 + 2] = out[from * 3 + 2]
        }
    }

    /** The tick after it was cut that the length cut off at joint [cut] of [joints] meets the water. */
    fun hitAt(joints: FloatArray, cut: Int): Float {
        val from = cut.coerceIn(1, Tentacle.SEGMENTS - 1)
        var cy = 0f
        for (i in from..Tentacle.SEGMENTS) cy += joints[i * 3 + 1]
        return hitAt(cy / (Tentacle.SEGMENTS - from + 1))
    }

    /** Where its middle is, [ticks] after it was cut, as [place] has it: x, y and z in the arm's frame. */
    fun middle(joints: FloatArray, cut: Int, ticks: Float): FloatArray {
        val out = FloatArray((Tentacle.SEGMENTS + 1) * 3)
        place(joints, cut, ticks, out)
        val from = cut.coerceIn(1, Tentacle.SEGMENTS - 1)
        val m = FloatArray(3)
        val n = Tentacle.SEGMENTS - from + 1
        for (i in from..Tentacle.SEGMENTS) for (a in 0..2) m[a] += out[i * 3 + a]
        for (a in 0..2) m[a] /= n
        return m
    }

    private fun hitAt(middleY: Float): Float {
        val height = middleY - WATERLINE
        return if (height <= 0f) 0f else sqrt(2f * height / GRAVITY)
    }

    /** Blocks a tick, a tick, it falls at; blocks a tick it sinks at in the end, and how many ticks the sea takes to slow it. */
    private const val GRAVITY = 0.05f
    private const val SINK = 0.035f
    private const val DRAG = 4f
    /** Where its middle is, under the surface, as it goes in. */
    private const val WATERLINE = -0.3f
    /** Blocks a tick it drifts out from the ship. */
    private const val DRIFT = 0.03f
    /** Radians a tick it turns over as it falls, and once in the water (dying away over [ROLL_FADE] ticks). */
    private const val TUMBLE = 0.075f
    private const val TUMBLE_AFTER = 0.03f
    private const val ROLL_FADE = 10f
    /** Blocks it writhes, at first, and the ticks that takes to die away. */
    private const val WRITHE = 0.35f
    private const val WRITHE_FADE = 22f
}
