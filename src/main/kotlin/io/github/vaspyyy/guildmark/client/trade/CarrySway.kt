package io.github.vaspyyy.guildmark.client.trade

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A weight hung from a hinge: the stuff of a pack's sway, a bell's swing and a pot's dangle (living-world/trader/design.md, Part 3;
 * build part D1). An angle (or a height) that is pushed by how the hinge is moved, pulled home by gravity and slowed by the air.
 * It is stepped once for each game tick that has passed and read between two ticks with [at], so it moves the same at any frame rate.
 *
 * [pull] is how hard it is pulled home (a swing takes about 6.3 over the square root of it, in ticks), [drag] the part of its speed it
 * loses each tick, and [limit] how far it can go before it knocks against its stops and comes back.
 */
internal class Hinge(private val pull: Float, private val drag: Float, private val limit: Float) {
    var angle = 0f
        private set
    var speed = 0f
        private set
    private var before = 0f

    /** One tick, with the hinge pushed by [push] (a change of speed) in it. */
    fun step(push: Float) {
        before = angle
        speed += push - pull * angle - drag * speed
        angle += speed
        if (angle > limit) {
            angle = limit
            if (speed > 0f) speed *= -KNOCK
        } else if (angle < -limit) {
            angle = -limit
            if (speed < 0f) speed *= -KNOCK
        }
    }

    /** Knocks it, as a foot coming down does: a change of speed that is not part of a step. */
    fun kick(by: Float) {
        speed += by
    }

    /** Where it is [partial] of the way from the last tick to this one. */
    fun at(partial: Float): Float = Mth.lerp(partial, before, angle)

    fun rest() {
        angle = 0f
        before = 0f
        speed = 0f
    }

    private companion object {
        /** How much of its speed it keeps (turned round) when it hits a stop. */
        const val KNOCK = 0.35f
    }
}

/**
 * What a body did over the game ticks since it was last drawn, in its own directions: how much it sped up, forwards and to its left, and
 * upwards (in blocks per tick per tick), and how far it went over the ground. Nothing is worked out for a body that is not drawn: [advance]
 * is called when it is, and says how many ticks have passed since (so the caller steps what it keeps that many times).
 */
internal class Motion {
    private var primed = false
    private var tick = 0
    private var vx = 0.0
    private var vy = 0.0
    private var vz = 0.0

    /** The change of speed this tick along the way the body faces (positive when it speeds up) and to the side its left hand is on. */
    var forward = 0f
        private set
    var left = 0f
        private set

    /** The change of speed upwards this tick (a jump, a landing). */
    var up = 0f
        private set

    /** How far the body went over the ground this tick, in blocks. */
    var moved = 0f
        private set

    /**
     * How many ticks to step for [e]: 0 if it has been read this tick already, 1 up to [MOST] for the ticks since the last read, and -1 if it
     * has not been read before or so long ago that whatever is kept of it should start afresh (it was out of sight, or was taken elsewhere).
     */
    fun advance(e: LivingEntity): Int {
        val now = e.tickCount
        if (!primed) return prime(e, now)
        val behind = now - tick
        if (behind == 0) return 0
        if (behind < 0 || behind > MOST) return prime(e, now)
        tick = now
        val dx = e.x - e.xo
        val dy = e.y - e.yo
        val dz = e.z - e.zo
        val ax = (dx - vx).toFloat()
        val ay = (dy - vy).toFloat()
        val az = (dz - vz).toFloat()
        vx = dx
        vy = dy
        vz = dz
        val yaw = Math.toRadians(e.yBodyRot.toDouble())
        val s = sin(yaw).toFloat()
        val c = cos(yaw).toFloat()
        forward = -s * ax + c * az
        left = c * ax + s * az
        up = ay
        moved = hypot(dx, dz).toFloat()
        return behind
    }

    private fun prime(e: LivingEntity, now: Int): Int {
        primed = true
        tick = now
        vx = e.x - e.xo
        vy = e.y - e.yo
        vz = e.z - e.zo
        forward = 0f
        left = 0f
        up = 0f
        moved = hypot(vx, vz).toFloat()
        return -1
    }

    companion object {
        /** The most ticks stepped at once; a body not drawn for longer starts again from rest. */
        const val MOST = 6
    }
}

/** What is kept for one body that is drawn: who it is for (weakly) and the game time it was last drawn. */
internal open class Look {
    var who: WeakReference<Entity>? = null
    var seenAt = Long.MIN_VALUE
}

/**
 * The looks of the bodies drawn, by entity id. A look found under the id of another entity (the id was given again) is made afresh, and
 * those not drawn for a minute are let go, so nothing lingers for bodies that are gone.
 */
internal class Looks<T : Look>(private val make: () -> T) {
    private val map = Int2ObjectOpenHashMap<T>()
    private var swept = Long.MIN_VALUE

    fun of(e: Entity): T {
        val now = e.level().gameTime
        if (now - swept >= SWEEP || now < swept) {
            swept = now
            map.values.removeIf { now - it.seenAt > STALE || now < it.seenAt }
        }
        var look = map.get(e.id)
        if (look == null || look.who?.get() !== e) {
            look = make()
            look.who = WeakReference(e)
            map.put(e.id, look)
        }
        look.seenAt = now
        return look
    }

    fun clear() {
        map.clear()
    }

    private companion object {
        const val SWEEP = 400L
        const val STALE = 1200L
    }
}

/** Easing for things that appear and settle. */
internal object Ease {
    /** 0 to 1, going about a fifteenth over and coming back (a thing set down that settles). */
    fun back(t: Float): Float {
        val u = t.coerceIn(0f, 1f) - 1f
        return 1f + u * u * ((OVER + 1f) * u + OVER)
    }

    /** 0 to 1 slowly at both ends. */
    fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private const val OVER = 1.4f
}
