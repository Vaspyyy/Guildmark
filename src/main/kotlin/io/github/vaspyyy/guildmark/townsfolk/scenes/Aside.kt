package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Audience
import io.github.vaspyyy.guildmark.townsfolk.Chase
import io.github.vaspyyy.guildmark.townsfolk.Chat
import io.github.vaspyyy.guildmark.townsfolk.Climb
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Follow
import io.github.vaspyyy.guildmark.townsfolk.Hear
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Pull
import io.github.vaspyyy.guildmark.townsfolk.Run
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** A scene's own task in someone's day (a place in a queue, a stall shut up): never caught up in another scene meanwhile. */
interface Scened

/**
 * [first] and whatever follows it ([Task.then]) as one task of a scene's (a loaf bought at the head of the line and
 * taken home, a hurry home out of the cold): busy, and [Scened], so no other scene catches them in the middle of it.
 */
internal class InScene(p: Townsperson, first: Task) : Task(p), Scened {
    private var current: Task? = first

    override fun start() {
        current?.start()
    }

    override fun run(): Boolean {
        val c = current ?: return false
        if (c.tick()) return true
        c.stop()
        current = c.then
        current?.start()
        return current != null
    }

    override fun stop() {
        current?.stop()
        current = null
    }
}

/**
 * Has charge of a townsperson for their part in a scene that catches them in passing (living-world/design-influence.md
 * section 5): someone on their way somewhere stops to watch the builders, reads the market board and grumbles, hugs
 * themselves against the cold. What they were at is paused, not ended, as a talk or the wayside's stepping aside has
 * it ([io.github.vaspyyy.guildmark.townsfolk.talk.Talking]): when their part is played they go on with it from where
 * they stand. Their part is a run of [Move]s. It breaks off as soon as their day moves on (a new stretch begins:
 * what's due then begins, rather than what they were at), when they're hurt, fight or run, when no player is near
 * enough to see it ([reach]), after [most] ticks at the longest, and when a player speaks to them (the talk takes them
 * up from their day). One part at a time each: nobody already in someone's charge, or in a scene, is caught.
 */
class Aside(
    private val moves: List<Move>,
    private val reach: Double = REACH,
    private val most: Int = MOST,
    /** Whether what they were at is dropped at the end (their day picking up afresh) rather than taken up again. */
    private val fresh: Boolean = false,
) : Director {
    private var i = -1
    private var t = 0
    private var age = 0
    private var last = -1
    private var doing: Doing? = null
    private var done = false

    /** Hears the part end, however it ends (the scene lets them go from its count). */
    var onEnd: (() -> Unit)? = null

    /** Whether their part is over. */
    val over: Boolean get() = done

    override fun tick(p: Townsperson) {
        if (done) return
        val level = p.level() as? ServerLevel ?: return
        // Back from something that had them meanwhile (a fright, a fight), or in danger: what they were at is gone already
        if (last >= 0 && p.tickCount - last > 5 || danger(p)) return end(p, drop = true)
        if (last < 0) doing = Days.life(p)?.let { Days.stretch(it, level.overworldClockTime).doing }
        last = p.tickCount
        age++
        if (age > most) return end(p, drop = fresh)
        if (p.tickCount % 20 == 0) {
            val life = Days.life(p)
            if (doing != null && life != null && Days.stretch(life, level.overworldClockTime).doing != doing) return end(p, drop = true)
        }
        if (p.tickCount % 40 == 0 && level.getNearestPlayer(p, reach) == null) return end(p, drop = fresh)
        if (i < 0) next(p)
        while (!done && i < moves.size) {
            if (moves[i].tick(p, this, t++)) return
            moves[i].stop(p)
            next(p)
        }
        if (!done) end(p, drop = fresh)
    }

    private fun next(p: Townsperson) {
        i++
        t = 0
        if (i < moves.size) moves[i].start(p, this)
    }

    /** A player's word comes first: their part ends, and the talk takes them up from their day. */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        end(p, drop = false)
        return false
    }

    /** Lets [p] go now (the scene's over, or theirs in it): back to what they were at, or (if [drop]) to their day afresh. */
    fun end(p: Townsperson, drop: Boolean) {
        if (done) return
        done = true
        if (i in moves.indices) moves[i].stop(p)
        if (p.director === this) p.director = null
        p.navigation.stop()
        if (!p.activity.overlay) p.act(Activity.NONE)
        if (drop || fresh) p.routine.interrupt()
        onEnd?.invoke()
    }

    companion object {
        /** How near a player must be for a scene to go on: scenes are for being seen. */
        const val REACH = 64.0

        /** The longest any part runs. */
        const val MOST = 2400

        /** Degrees a tick they turn, to watch something or face someone. */
        const val TURN = 18f

        /**
         * Whether [p] can be caught by a scene just now: up and about out of doors or in the street, in nobody's charge,
         * in no danger, hands empty, in no hurry (whatever they're at is idle enough to break off for a chat), not
         * up a tower or a ladder, and in no scene already. With [seated], someone sitting may be got up too.
         */
        fun free(p: Townsperson, seated: Boolean = false): Boolean {
            if (!p.isAlive || p.isRemoved || p.director != null || p.captiveOf >= 0 || p.isLeader || p.watcher) return false
            if (p.isSleeping || p.isPassenger || p.attending != null || p.target != null || p.hurtTime > 0) return false
            if (p.activity == Activity.FLEE || !p.carried.isEmpty || Faction.of(p.role) != null) return false
            if (p.seat != null && !seated) return false
            if (Lookout.up(p)) return false
            val task = p.routine.task ?: return true
            if (task is Scened || task is Chat || task is Hear || task is Audience || task is Follow || task is Chase || task is Run || task is Climb || task is Pull) return false
            if (p.seat != null) return true
            return task.leisure && !task.anchored
        }

        /** Gives [p] [aside] to play, if they're free to; true if taken. */
        fun take(p: Townsperson, aside: Aside, seated: Boolean = false): Boolean {
            if (!free(p, seated)) return false
            if (p.seat != null) {
                p.routine.interrupt()
                p.standUp()
            }
            p.navigation.stop()
            p.director = aside
            return true
        }

        /** Hurt, fighting, running, or a monster close by. */
        private fun danger(p: Townsperson): Boolean {
            if (p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE) return true
            return p.tickCount % 10 == 0 && p.threat() != null
        }
    }
}

/** One move of someone's part in a scene: begun once, then ticked until it's done. */
abstract class Move {
    open fun start(p: Townsperson, a: Aside) {}

    /** Tick [t] of it; false once it's done. */
    abstract fun tick(p: Townsperson, a: Aside, t: Int): Boolean

    open fun stop(p: Townsperson) {}
}

/** A walk to wherever [to] says when it begins ([speed], within [near]); there, or as near as they can get, it's done. */
class GoTo(
    private val to: () -> BlockPos?, private val speed: Double = Jobs.WALK, private val near: Double = 1.0,
    private val overlay: Activity = Activity.NONE, private val most: Int = 600,
) : Move() {
    private var walker: Walker? = null

    override fun start(p: Townsperson, a: Aside) {
        val pos = to() ?: return
        if (overlay != Activity.NONE) p.act(overlay)
        walker = Walker(p).also { it.go(pos, speed, near) }
    }

    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
        val w = walker ?: return false
        if (t > most) return false
        if (overlay != Activity.NONE && p.activity != overlay && (p.activity.loop || p.actDone)) p.act(overlay)
        return w.tick() == Walker.State.WALKING
    }

    override fun stop(p: Townsperson) {
        walker?.stop()
        if (overlay != Activity.NONE && p.activity == overlay) p.act(Activity.NONE)
    }
}

/** A turn of the body (at [Aside.TURN] a tick) to face what [at] gives, the eyes there first. */
class Turn(private val at: () -> Vec3?, private val most: Int = 12) : Move() {
    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
        val there = at() ?: return false
        p.lookAt(there)
        val yaw = p.yawTo(there)
        if (abs(Mth.degreesDifference(p.yBodyRot, yaw)) <= 6f || t >= most) {
            p.face(yaw)
            return false
        }
        p.face(Mth.approachDegrees(p.yBodyRot, yaw, Aside.TURN))
        return true
    }
}

/**
 * [activity] played for [ticks] (a one-shot once through, whatever [ticks] says), facing and looking at what [at]
 * gives; [first] hears its first tick (a word said, something handed over).
 */
class Play(
    private val activity: Activity, private val ticks: Int = activity.ticks, private val at: (() -> Vec3?)? = null,
    private val first: ((Townsperson) -> Unit)? = null,
) : Move() {
    override fun start(p: Townsperson, a: Aside) {
        p.navigation.stop()
        p.act(activity)
        first?.invoke(p)
    }

    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
        at?.invoke()?.let { there ->
            p.lookAt(there)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
        }
        // Something else played meanwhile (a nod to a passer-by) settles back into it
        if (activity.loop && p.activity != activity && (p.activity.loop || p.actDone)) p.act(activity)
        return if (activity.loop) t < ticks else t < activity.ticks
    }
}

/** Waits (in [pose], looking at what [at] gives) until [until] holds, or [most] ticks. */
class Wait(
    private val until: (Townsperson, Int) -> Boolean, private val most: Int, private val pose: Activity = Activity.NONE,
    private val at: (() -> Vec3?)? = null,
) : Move() {
    override fun start(p: Townsperson, a: Aside) {
        p.navigation.stop()
        if (pose != Activity.NONE || !p.activity.overlay) p.act(pose)
    }

    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean {
        at?.invoke()?.let { there ->
            p.lookAt(there)
            p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(there), Aside.TURN))
        }
        if (p.activity != pose && (p.activity.loop || p.actDone)) p.act(pose)
        return t < most && !until(p, t)
    }
}

/** Something done at once, between two moves. */
class Now(private val what: (Townsperson) -> Unit) : Move() {
    override fun start(p: Townsperson, a: Aside) = what(p)

    override fun tick(p: Townsperson, a: Aside, t: Int): Boolean = false
}
