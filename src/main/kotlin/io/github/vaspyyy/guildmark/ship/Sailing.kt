package io.github.vaspyyy.guildmark.ship

import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.sin

/**
 * How a square sail takes the wind. Everything is told by the wind's angle off the bow: [rel] degrees from
 * dead ahead, above 0 when it comes from starboard (the ship's right), below 0 from port; so its size is
 * 0 head to wind and 180 dead astern. A square-rigger can't sail closer than about 50° to the wind: there
 * the sail luffs, and dead ahead it's taken aback. Its best is with the wind on the quarter.
 */
object Sailing {
    /** The fastest a cog goes, in blocks a tick, with all sail set in a moderate wind on its best heading. */
    const val MAX_SPEED = 0.36

    /** Degrees either side of dead ahead the wind gives her no drive at all. */
    const val NO_GO = 45f

    /** Off-the-wind angles and how much of the wind's drive the sail gets there. */
    private val OFF = floatArrayOf(0f, NO_GO, 60f, 80f, 110f, 150f, 180f)
    private val DRIVE = floatArrayOf(0f, 0f, 0.4f, 0.8f, 1f, 1f, 0.9f)

    /** 0 to 1: how well the sail drives the ship with the wind [rel] degrees off the bow. */
    fun efficiency(rel: Float): Float {
        val off = abs(rel).coerceAtMost(180f)
        for (i in 1 until OFF.size) {
            if (off <= OFF[i]) {
                val t = (off - OFF[i - 1]) / (OFF[i] - OFF[i - 1])
                return Mth.lerp(t, DRIVE[i - 1], DRIVE[i])
            }
        }
        return DRIVE.last()
    }

    /** Blocks a tick the ship makes towards with [sail] let down in a wind of [strength] from [rel]. */
    fun speed(rel: Float, sail: Float, strength: Float): Double =
        MAX_SPEED * sail * efficiency(rel) * (0.5 + 0.75 * strength)

    /**
     * Degrees to brace the yard round from square: the windward arm goes aft, more the further forward the
     * wind comes, up to 50°. Above 0 the starboard arm swings aft (for a wind from starboard).
     */
    fun brace(rel: Float): Float {
        val turn = ((180f - abs(rel)) * 0.42f).coerceIn(0f, 50f)
        return if (rel >= 0f) turn else -turn
    }

    /**
     * How the sail fills, -1 to 1: above 0 bellied forward by the wind behind it, below 0 taken aback, 0 slack.
     * Scaled by how much of it is let down.
     */
    fun fill(rel: Float, sail: Float, strength: Float): Float {
        val off = abs(rel)
        val wind = 0.35f + 0.65f * strength
        if (off < 30f) return -sail * (0.3f + 0.5f * strength) * (1f - off / 30f)
        return sail * wind * efficiency(rel)
    }

    /** 0 to 1: how hard the sail flaps, head to wind and spilling it. */
    fun luff(rel: Float, sail: Float): Float = sail * (1f - smoothstep(38f, 60f, abs(rel)))

    /** Degrees the wind heels the ship over to port (to leeward, so below 0 with the wind from port). */
    fun heel(rel: Float, fill: Float, strength: Float): Float =
        7f * fill.coerceAtLeast(0f) * sin(rel * Mth.DEG_TO_RAD) * (0.5f + 0.5f * strength)

    fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
