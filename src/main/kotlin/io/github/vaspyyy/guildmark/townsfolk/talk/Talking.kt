package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Chat
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * Has charge of a townsperson while players talk with them ([Talk]; living-world/design-voices.md section 2, "How
 * they behave"), on the pattern of the stalls' Serving and Wayside's Aside: what they were at is paused, not ended.
 * They stop where they stand; at their work they finish the move they're in and set the work down; then they turn to
 * whoever they're answering at 20° a tick, the head following the player's eyes (seated folk turn only the head),
 * gesture as they speak, turn toward a place to point the way, and listen with a nod now and then. When the last talk
 * is over the farewell plays through, they turn back to their work and take it up again, and their day goes on where
 * it was. They talk where they stand, so there's no walk back: someone stopped on their way walks on from there.
 */
class Talking private constructor(
    /** What they were doing when they stopped, and which way they faced. */
    private val was: Activity,
    private val yaw: Float,
    private val seated: Boolean,
    /** Keeping still at their work: an anvil, an oven, a counter, a post. */
    private val station: Boolean,
    /** The stretch of their day they were in. */
    private val doing: Doing?,
    /** Whoever they were chatting with, who's owed a nod. */
    private var partner: Townsperson?,
    private val since: Long,
) : Director {
    /** Settled and ready to talk: [Talk] waits for this before their first word. */
    var ready = false
        private set

    /** When they began setting their work down; -1 until then. */
    private var paused = -1L

    /** When they next nod while listening. */
    private var nodAt = 0L

    /** Where they're to point, once they've turned to it, and whether the pointing has begun. */
    private var pointAt: BlockPos? = null
    private var pointSince = -1L
    private var pointing = false

    /** When the last talk ended, and when they began taking their work up again; -1 while it's on. */
    private var leaving = -1L
    private var taking = -1L

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val now = level.gameTime
        // Nobody takes their seat or their place at work meanwhile
        if ((now - since) % 100 == 0L) Claims.held(p)
        if (danger(p)) {
            Talk.endAll(p)
            return release(p, false, false)
        }
        partner?.let { other ->
            if (now - since < NOD_PARTNER) return@let
            partner = null
            if (other.isAlive && other.director == null && other.routine.task !is Chat) other.act(if (other.navigation.isDone) Activity.NOD else Activity.NOD_WALK)
        }
        if (!Talk.busy(p) && !Talk.speaking(p)) return leave(level, p, now)
        // Someone else came up as they were going back to their day
        if (leaving >= 0) {
            leaving = -1
            taking = -1
        }
        // Nothing moves them while they talk, and they call out to nobody else
        p.navigation.stop()
        p.nextSpeech = now + 100
        settle(p, now)
        val who = Talk.facing(p)
        if (who != null) p.attend(who, 40)
        if (pointAt != null) return point(p, now)
        if (who != null) turn(p, who.position(), who)
        if (ready) pose(p, now)
    }

    // A crouched player may still try their purse
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (player.isShiftKeyDown) return false
        val level = p.level() as? ServerLevel ?: return false
        return Talk.open(level, player, p)
    }

    /** [Talk] has them point the way to [at]: they turn to it first. */
    fun point(at: BlockPos) {
        pointAt = at
        pointSince = -1
        pointing = false
    }

    /** Hurt, fighting, fleeing, or a monster close by: the talk's over at once, and they react as ever. */
    private fun danger(p: Townsperson): Boolean {
        if (p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE) return true
        return p.tickCount % 10 == 0 && p.threat() != null
    }

    /** At their work, the move they're in plays through (at most [SETTLE] ticks), then they set the work down as they turn. */
    private fun settle(p: Townsperson, now: Long) {
        if (ready) return
        if (!station) {
            ready = true
            return
        }
        if (paused < 0) {
            val a = p.activity
            val through = a == Activity.NONE || if (a.loop) p.actTicks % a.ticks == 0 else p.actDone
            if (!through && now - since < SETTLE) return
            p.act(Activity.PAUSE_WORK)
            paused = now
        }
        if (now - paused >= SET_DOWN) ready = true
    }

    /** The body turns to [at] at [TURN] degrees a tick, the head following [who]'s eyes; seated, only the head turns. */
    private fun turn(p: Townsperson, at: Vec3, who: Entity?) {
        if (who != null) p.lookControl.setLookAt(who, 30f, 30f) else p.lookControl.setLookAt(at.x, at.y, at.z, 30f, 30f)
        if (seated || station && paused < 0) return
        p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(at), TURN))
    }

    /** Turned to the place (or near enough, or after a moment), they point; when the pointing's done they turn back. */
    private fun point(p: Townsperson, now: Long) {
        val at = pointAt ?: return
        val there = Vec3.atCenterOf(at)
        if (pointSince < 0) pointSince = now
        turn(p, there, null)
        if (!pointing) {
            if (abs(Mth.degreesDifference(p.yBodyRot, p.yawTo(there))) > 12f && now - pointSince < 12) return
            p.act(Activity.POINT)
            pointing = true
        } else if (p.activity != Activity.POINT || p.actDone) pointAt = null
    }

    /** Between gestures: their hands going with the talk while they speak, listening (with a nod now and then) while the player chooses. */
    private fun pose(p: Townsperson, now: Long) {
        val a = p.activity
        if (a == Activity.PAUSE_WORK && !p.actDone) return
        val speaking = p.speech.isNotEmpty()
        val pose = Talk.pose(p, speaking)
        val finished = !a.loop && p.actDone
        when {
            speaking -> if (finished || a == Activity.NONE || a == Activity.LISTEN || a == Activity.SIT) p.act(pose)
            a == pose -> if (pose == Activity.LISTEN && now >= nodAt) {
                if (nodAt > 0) p.act(Activity.NOD)
                nodAt = now + NOD_EVERY + p.random.nextInt(NOD_EVERY)
            }
            finished || a.loop -> p.act(pose)
        }
    }

    /** The last talk is over: the farewell plays through, at their work they turn back to it and take it up again, and their day goes on. */
    private fun leave(level: ServerLevel, p: Townsperson, now: Long) {
        if (leaving < 0) leaving = now
        // Run off or fought meanwhile: what they were at is gone already
        if (p.routine.task == null) return release(p, false, false)
        val a = p.activity
        if (!a.loop && !p.actDone && now - leaving < FAREWELL) return
        if (station) {
            if (taking < 0) {
                if (abs(Mth.degreesDifference(p.yBodyRot, yaw)) > 6f && now - leaving < FAREWELL + 20) {
                    p.face(Mth.approachDegrees(p.yBodyRot, yaw, TURN))
                    return
                }
                p.face(yaw)
                p.act(Activity.PICK_UP)
                taking = now
                return
            }
            if (!p.actDone && now - taking < Activity.PICK_UP.ticks) return
        }
        // A new stretch of their day began meanwhile: it starts now, rather than what they were at carrying on
        val life = Days.life(p)
        val changed = doing != null && life != null && Days.stretch(life, level.overworldClockTime).doing != doing
        release(p, changed, true)
    }

    /**
     * Back to their day: what they were at carries on, its move playing again if [resume], or, if [fresh], the stretch
     * that's due begins. In danger nothing's played: they react as ever.
     */
    private fun release(p: Townsperson, fresh: Boolean, resume: Boolean) {
        if (p.director === this) p.director = null
        p.attending?.let { p.stopListening(it) }
        pointAt = null
        when {
            fresh -> {
                p.routine.interrupt()
                if (!p.activity.overlay) p.act(Activity.NONE)
            }
            resume -> p.act(was)
        }
    }

    companion object {
        /** Degrees a tick they turn, toward the player and back to their work. */
        private const val TURN = 20f

        /** The most ticks they finish the move they're in before setting their work down, and how far into that they speak. */
        private const val SETTLE = 30
        private const val SET_DOWN = 12

        /** The most ticks a farewell plays before they turn back to their day. */
        private const val FAREWELL = 40

        /** Ticks between the listening nods, at the least (twice that at most). */
        private const val NOD_EVERY = 90

        /** Ticks after they stop before the one they were chatting with nods to them, once their chat has ended. */
        private const val NOD_PARTNER = 2

        /** [p] stops for a talk: what they were at is paused where it is; a chat they were in ends, with a nod from their partner. */
        fun take(p: Townsperson) {
            val level = p.level()
            var was = p.activity
            var partner: Townsperson? = null
            (p.routine.task as? Chat)?.let { chat ->
                p.routine.interrupt()
                was = Activity.NONE
                partner = chat.partner
            }
            val doing = Days.life(p)?.let { Days.stretch(it, level.overworldClockTime).doing }
            val station = p.routine.anchored && p.seat == null && !p.isSleeping
            p.director = Talking(was, p.yBodyRot, p.seat != null, station, doing, partner, level.gameTime)
            p.navigation.stop()
        }
    }
}
