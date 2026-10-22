package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.tales.TaleHold
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Audience
import io.github.vaspyyy.guildmark.townsfolk.Chase
import io.github.vaspyyy.guildmark.townsfolk.Chat
import io.github.vaspyyy.guildmark.townsfolk.Claims
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

    /** Whether their part was played through to its end (not broken off by danger, a word, a new stretch or the like). */
    var completed = false
        private set

    /** The stage this part belongs to, if it is a part of one (living-world/stories/design.md 4.2), and the name of the barrier it waits at. */
    internal var stage: Stage? = null
    internal var barrier: String? = null

    /** Set by [take] for a part played at a post or in a seat: the worker sets their work down first and takes it up after. */
    internal var post: Post? = null

    /** The hold a tale keeps a body under, which this part took them from and gives them back to, where the part leaves them (living-world/stories/design.md 4.4). */
    internal var restore: TaleHold? = null

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
        val post = post
        if (post != null) {
            // Nobody takes their place at the counter meanwhile
            if (age % 100 == 0) Claims.held(p, 400)
            if (!post.settle(p, p.level().gameTime)) return
        }
        if (i < 0) next(p)
        while (!done && i < moves.size) {
            if (moves[i].tick(p, this, t++)) return
            moves[i].stop(p)
            next(p)
        }
        if (!done) {
            // At a post they turn back to it and take the work up again before they go
            if (post != null && !post.leave(p, p.level().gameTime)) return
            completed = true
            end(p, drop = fresh)
        }
    }

    private fun next(p: Townsperson) {
        i++
        t = 0
        if (i < moves.size) moves[i].start(p, this)
    }

    /** A player's word comes first: their part ends, and the talk takes them up from their day. */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        end(p, drop = false)
        // A tale's body goes back under its hold (where the part left them), but for the talk it is in nobody's charge: the tidy gives it back after
        if (p.director is TaleHold) p.director = null
        return false
    }

    /** Lets [p] go now (the scene's over, or theirs in it): back to what they were at, or (if [drop]) to their day afresh. */
    fun end(p: Townsperson, drop: Boolean) {
        if (done) return
        done = true
        if (i in moves.indices) moves[i].stop(p)
        if (p.director === this) release(p)
        p.navigation.stop()
        val post = post
        // Played through at a post: the work they were at carries on from the move it had
        if (completed && post != null && !drop) p.act(post.was)
        else if (!p.activity.overlay) p.act(Activity.NONE)
        if (drop || fresh) p.routine.interrupt()
        onEnd?.invoke()
    }

    /** Lets go of a part that was taken but never began (the stage could not be taken whole): they are as they were. */
    internal fun cancel(p: Townsperson) {
        if (done) return
        done = true
        if (p.director === this) p.director = restore
        onEnd?.invoke()
    }

    /** Lets go of the body: back under the tale's hold if it came from one (which now holds it where it stands), else free. */
    private fun release(p: Townsperson) {
        val hold = restore
        p.director = hold
        hold?.rest(p)
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
         * up a tower or a ladder, and in no scene already. With [seated], someone sitting may be got up too. With [held], a tale's own
         * body waiting at its mark under a [TaleHold] is free too (the tale's stage may take it; no other scene does).
         */
        fun free(p: Townsperson, seated: Boolean = false, station: Boolean = false, held: Boolean = false): Boolean {
            if (!p.isAlive || p.isRemoved || p.captiveOf >= 0 || p.isLeader || p.watcher) return false
            val director = p.director
            if (director != null && !(held && p.tale >= 0 && director is TaleHold)) return false
            if (p.isSleeping || p.isPassenger || p.attending != null || p.target != null || p.hurtTime > 0) return false
            if (p.activity == Activity.FLEE || !p.carried.isEmpty || Faction.of(p.role) != null) return false
            if (p.seat != null && !seated) return false
            if (Lookout.up(p)) return false
            val task = p.routine.task ?: return true
            if (task is Scened || task is Chat || task is Hear || task is Audience || task is Follow || task is Chase || task is Run || task is Climb || task is Pull) return false
            if (p.seat != null) return true
            // At their post (a keeper at the counter, a smith at the anvil): theirs to play where they stand
            if (station && p.routine.anchored && !p.isSleeping) return true
            return task.leisure && !task.anchored
        }

        /**
         * Gives [p] [aside] to play, if they're free to; true if taken. With [station] a worker at their post can be taken
         * and plays their part where they stand (they set the work down first, and turn back to it and take it up at the end,
         * as a talk has it: [io.github.vaspyyy.guildmark.townsfolk.talk.Talking]), and someone seated stays in their seat
         * rather than being got up (living-world/stories/design.md 4.2).
         */
        fun take(p: Townsperson, aside: Aside, seated: Boolean = false, station: Boolean = false, held: Boolean = false): Boolean {
            if (!free(p, seated, station, held)) return false
            aside.restore = p.director as? TaleHold
            val post = station && (p.seat != null || p.routine.anchored)
            if (post) {
                aside.post = Post(p.activity, p.yBodyRot, p.seat != null)
            } else if (p.seat != null) {
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

/**
 * What a part played at a post or in a seat remembers: what they were at ([was]) and the way they faced ([yaw]). At their
 * work they let the move they are in play through (at most [SETTLE] ticks) and then set the work down ([Activity.PAUSE_WORK]);
 * at the end they turn back and take it up ([Activity.PICK_UP]), exactly as a talk does. Someone seated ([sitting]) simply
 * stays seated.
 */
internal class Post(val was: Activity, val yaw: Float, val sitting: Boolean) {
    private var since = -1L
    private var paused = -1L
    private var ready = false
    private var leaving = -1L
    private var taking = -1L

    /** True once the part may begin. */
    fun settle(p: Townsperson, now: Long): Boolean {
        if (ready) return true
        if (since < 0) since = now
        if (sitting) {
            ready = true
            return true
        }
        if (paused < 0) {
            val a = p.activity
            val through = a == Activity.NONE || if (a.loop) p.actTicks % a.ticks == 0 else p.actDone
            if (!through && now - since < SETTLE) return false
            p.act(Activity.PAUSE_WORK)
            paused = now
        }
        if (now - paused >= SET_DOWN) ready = true
        return ready
    }

    /** True once they are back at it and the work is in their hands again. */
    fun leave(p: Townsperson, now: Long): Boolean {
        if (sitting) return true
        if (leaving < 0) leaving = now
        if (taking < 0) {
            if (abs(Mth.degreesDifference(p.yBodyRot, yaw)) > 6f && now - leaving < TURN_BACK) {
                p.face(Mth.approachDegrees(p.yBodyRot, yaw, Aside.TURN))
                return false
            }
            p.face(yaw)
            p.act(Activity.PICK_UP)
            taking = now
            return false
        }
        return p.actDone || now - taking >= Activity.PICK_UP.ticks
    }

    companion object {
        /** The most ticks they finish the move they are in before setting their work down, and how long the setting down takes. */
        const val SETTLE = 30
        const val SET_DOWN = 12

        /** The most ticks they take to turn back to the post. */
        const val TURN_BACK = 40
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
