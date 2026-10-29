package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import io.github.vaspyyy.guildmark.townsfolk.scenes.Aside
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The walker of S8 The Slow Walk (living-world/stories/design.md 4.3 and 4.4): an old or weary cast member who follows one player
 * to a place at a pace of 0.45, stooped and hobbling (HOBBLE), as `Captives.homeward` has a freed captive follow whoever cut them
 * loose, with the tale's own end: within four blocks of the [target] it tells the tale ([Event.Arrived], through [Tales.tell]) and
 * the body is given back to its hold there. With no [target] (the command's test) it goes on until [until], and is let go.
 *
 * - Every thirty blocks, or at a bench within six blocks once a dozen blocks have gone by, it rests while the player is within
 *   twenty blocks: at a bench it walks to it, sits (SIT_DOWN, SIT), says a word (`tale.set.rest`) and gets up (STAND_UP); with none
 *   it stops and catches its breath (CATCH_BREATH). A rest is cut short if the player goes on past 28 blocks.
 * - If the player gets more than 24 blocks ahead in sight it waves as it walks and calls (`tale.set.wait`); out of sight it waits
 *   where it is; far behind and out of sight a while it catches up, to a few steps behind the player (as `Captives.catchUp`).
 * - A player's word comes first: it steps aside for the talk, and [TaleBodies.tend] gives the walk back, from where it had got to.
 *
 * With [leads] (Tales T2b, the lantern's walk to the square) they know the way to the [target] and walk it themselves at the same pace,
 * the player beside them, rather than following: if the player falls more than [WAIT_FOR] blocks behind they stop and turn to wait
 * for them; a player a dozen blocks ahead in sight is called after as they go (`tale.set.wait`), and one gone on out of their
 * reach is waited for where they stand, waved to and called while in sight. They rest as above but not in the last dozen blocks,
 * and are never caught up or put anywhere: the way on is always walked.
 */
class TaleEscort(
    private val player: UUID,
    private val target: BlockPos?,
    private val tale: Int,
    private val slot: String,
    private val prop: ItemStack? = null,
    private val until: Long = 0L,
    private val leads: Boolean = false,
) : Director {
    private enum class Mode { FOLLOW, TO_BENCH, SIT_DOWN, SITTING, STAND_UP, BREATH }

    private var mode = Mode.FOLLOW
    private var last: Vec3? = null
    private var walked = 0.0
    private var calledAt = 0L
    private var modeAt = 0L
    private var lostAt = -1L
    private var bench: BlockPos? = null
    private var walker: Walker? = null
    private var started = false

    /** With [leads]: the walk to the target, and the game time before which a way that could not be found is not looked for again. */
    private var way: Walker? = null
    private var wayAfter = 0L

    /** Whether the walk is over (arrived, let go or broken off). */
    var done = false
        private set

    override fun tick(p: Townsperson) {
        if (done) return
        val level = p.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (!started) {
            started = true
            calledAt = now
            if (prop != null && !prop.isEmpty) {
                p.carried = prop.copy()
                p.carriedUntil = 0
            }
        }
        if (danger(p) || until > 0 && now >= until) return end(p, false)
        if (p.tickCount % 100 == 0) Claims.held(p, 400)
        // The walk is of the place to go to: within four blocks of it, they are there
        if (target != null && flat(p, target) < ARRIVE && abs(p.y - target.y) < 6.0) return end(p, true)
        val who = level.getPlayerByUUID(player) as? ServerPlayer
        if (who == null || !who.isAlive || who.isSpectator || who.level() !== level) {
            // Their guide's gone off (or out of the world): they wait where they are
            stop(p)
            return
        }
        last?.let { from ->
            val d = sqrt((p.x - from.x) * (p.x - from.x) + (p.z - from.z) * (p.z - from.z))
            if (d < 3.0) walked += d
        }
        last = p.position()
        when (mode) {
            Mode.FOLLOW -> if (leads && target != null) lead(level, p, who, now, target) else follow(level, p, who, now)
            Mode.TO_BENCH -> toBench(level, p, who, now)
            Mode.SIT_DOWN -> if (now - modeAt >= Activity.SIT_DOWN.ticks) {
                p.act(Activity.SIT)
                Words.sayWith(p, "tale.set.rest", why = "a slow walk")
                set(Mode.SITTING, now)
            }
            Mode.SITTING -> if (now - modeAt >= SIT_FOR || p.distanceTo(who) > LEFT_BEHIND) {
                p.act(Activity.STAND_UP)
                set(Mode.STAND_UP, now)
            }
            Mode.STAND_UP -> if (now - modeAt >= Activity.STAND_UP.ticks) {
                rise(level, p)
                set(Mode.FOLLOW, now)
            }
            Mode.BREATH -> if (now - modeAt >= BREATH_FOR || p.distanceTo(who) > LEFT_BEHIND) {
                p.act(Activity.NONE)
                set(Mode.FOLLOW, now)
            } else if (now - modeAt == 12L) {
                Words.sayWith(p, "tale.set.rest", why = "a slow walk")
            }
        }
    }

    private fun follow(level: ServerLevel, p: Townsperson, who: ServerPlayer, now: Long) {
        val dist = p.distanceTo(who)
        val sees = p.hasLineOfSight(who)
        // Far behind and out of sight a while: they turn up a few steps behind the player
        if (!sees && dist > CATCH_UP) {
            if (lostAt < 0) lostAt = now
            if (now - lostAt > LOST_FOR) {
                lostAt = -1
                catchUp(level, p, who)
                return
            }
        } else lostAt = -1
        // Out of sight, they wait where they are
        if (!sees && dist > AHEAD) {
            stop(p)
            return
        }
        // A rest, while the player is near enough to wait for it
        if (dist < NEAR_REST && walked >= EVERY) return rest(level, p, null, now)
        if (dist < NEAR_REST && walked >= BENCH_AFTER) {
            val seat = benchNear(level, p)
            if (seat != null) return rest(level, p, seat, now)
        }
        when {
            dist > AHEAD -> {
                // Left behind: a wave as they come, and a call, now and then
                if (now - calledAt >= CALL_EVERY) {
                    calledAt = now
                    p.act(Activity.WAVE_WALK)
                    Words.sayWith(p, "tale.set.wait", why = "a slow walk")
                }
                go(p, who)
            }
            dist > 3.5 -> go(p, who)
            else -> {
                p.navigation.stop()
                if (p.activity == Activity.HOBBLE) p.act(Activity.NONE)
                p.lookAt(who)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(who.position()), 10f))
            }
        }
    }

    /**
     * The walk led ([leads]): on the way to [to] at their own pace with [who] beside them; stopped and turned to wait while [who] is
     * behind, called after while [who] is ahead, waited for where they stand once [who] is out of reach. Never hurried, never put
     * anywhere: no catching up, and the walk to the bench and on to [to] is always walked.
     */
    private fun lead(level: ServerLevel, p: Townsperson, who: ServerPlayer, now: Long, to: BlockPos) {
        val dist = p.distanceTo(who)
        val sees = p.hasLineOfSight(who)
        val left = flat(p, to)
        // A rest, while the player is near enough to wait for it, and not with the square in sight
        if (dist < NEAR_REST && left > LAST_STRETCH) {
            if (walked >= EVERY) return rest(level, p, null, now)
            if (walked >= BENCH_AFTER) {
                val seat = benchNear(level, p)
                if (seat != null) return rest(level, p, seat, now)
            }
        }
        val behind = flat(who, to) > left + 1.5
        when {
            dist > AHEAD -> {
                // Gone on out of reach: they wait where they are, and wave and call after them while they can see them
                halt(p)
                if (sees) {
                    p.lookAt(who)
                    p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(who.position()), 10f))
                    if (now - calledAt >= CALL_EVERY) {
                        calledAt = now
                        p.act(Activity.WAVE)
                        Words.sayWith(p, "tale.set.wait", why = "a slow walk")
                    }
                }
            }
            behind && dist > WAIT_FOR -> {
                // Fallen behind: they stop and turn to wait for them
                halt(p)
                p.lookAt(who)
                p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(who.position()), 10f))
            }
            else -> {
                if (!behind && dist > CALL_AHEAD && sees && now - calledAt >= CALL_EVERY) {
                    // A little way ahead of them: a word after them, as they keep on
                    calledAt = now
                    p.act(Activity.WAVE_WALK)
                    Words.sayWith(p, "tale.set.wait", why = "a slow walk")
                }
                onTo(p, to, now)
            }
        }
    }

    /** On along the way to [to] at the walk's pace, stooped; a way not found is looked for again in two seconds, standing. */
    private fun onTo(p: Townsperson, to: BlockPos, now: Long) {
        if (now < wayAfter) return
        val w = way ?: Walker(p).also {
            way = it
            it.go(to, PACE, near = 1.5)
        }
        when (w.tick()) {
            Walker.State.WALKING -> if (p.activity != Activity.HOBBLE && (p.activity.loop || p.actDone)) p.act(Activity.HOBBLE)
            Walker.State.ARRIVED -> way = null
            Walker.State.FAILED -> {
                way = null
                wayAfter = now + 40
                stop(p)
            }
        }
    }

    /** Stopped where they are (the way on is found afresh when they go on). */
    private fun halt(p: Townsperson) {
        way?.stop()
        way = null
        stop(p)
    }

    /** At the pace of a stoop towards the player. */
    private fun go(p: Townsperson, who: ServerPlayer) {
        if (p.navigation.isDone || p.tickCount % 20 == 0) p.navigation.moveTo(who, PACE)
        if (p.activity != Activity.HOBBLE && (p.activity.loop || p.actDone)) p.act(Activity.HOBBLE)
    }

    private fun rest(level: ServerLevel, p: Townsperson, seat: BlockPos?, now: Long) {
        walked = 0.0
        p.navigation.stop()
        if (seat != null && Claims.claim(level, seat, p, 400)) {
            val access = Places.access(level, seat)
            if (access != null) {
                bench = seat
                walker = Walker(p).also { it.go(access, Jobs.WALK, near = 1.2) }
                set(Mode.TO_BENCH, now)
                return
            }
            Claims.release(level, seat, p)
        }
        p.act(Activity.CATCH_BREATH)
        set(Mode.BREATH, now)
    }

    private fun toBench(level: ServerLevel, p: Townsperson, who: ServerPlayer, now: Long) {
        val seat = bench
        val w = walker
        if (seat == null || w == null || now - modeAt > 300 || p.distanceTo(who) > LEFT_BEHIND) return giveUpBench(level, p, now)
        when (w.tick()) {
            Walker.State.WALKING -> if (p.activity != Activity.HOBBLE && (p.activity.loop || p.actDone)) p.act(Activity.HOBBLE)
            Walker.State.FAILED -> giveUpBench(level, p, now)
            Walker.State.ARRIVED -> {
                walker = null
                if (!Places.isSeat(level, level.getBlockState(seat), seat)) return giveUpBench(level, p, now)
                // On the seat as the clip lowers them onto it from where they stood
                p.sitOn(seat)
                p.act(Activity.SIT_DOWN)
                set(Mode.SIT_DOWN, now)
            }
        }
    }

    private fun giveUpBench(level: ServerLevel, p: Townsperson, now: Long) {
        walker?.stop()
        walker = null
        bench?.let { Claims.release(level, it, p) }
        bench = null
        p.act(Activity.NONE)
        set(Mode.FOLLOW, now)
    }

    /** Off the seat, onto the floor in front of it. */
    private fun rise(level: ServerLevel, p: Townsperson) {
        if (p.seat != null) p.standUp()
        bench?.let { Claims.release(level, it, p) }
        bench = null
        p.act(Activity.NONE)
    }

    private fun set(m: Mode, now: Long) {
        mode = m
        modeAt = now
    }

    /** A seat within six blocks that no one has, and that is a bench to sit on. */
    private fun benchNear(level: ServerLevel, p: Townsperson): BlockPos? = Places.seat(level, p.blockPosition(), BENCH_NEAR, p)

    /** Fallen far behind out of sight: they turn up on the ground a few steps behind [who]. */
    private fun catchUp(level: ServerLevel, p: Townsperson, who: ServerPlayer) {
        val behind = who.blockPosition().relative(who.direction.opposite, 3)
        for (dy in 3 downTo -4) {
            val pos = behind.above(dy)
            if (level.isLoaded(pos) && Places.standable(level, pos)) {
                p.navigation.stop()
                p.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, who.yRot, 0f)
                return
            }
        }
    }

    private fun stop(p: Townsperson) {
        p.navigation.stop()
        if (p.activity == Activity.HOBBLE) p.act(Activity.NONE)
    }

    /** Hurt, fighting or running: a body that is not a tale's is let go to react as ever (a tale's cannot be hurt, and trusts the player). */
    private fun danger(p: Townsperson): Boolean {
        if (p.tale >= 0) return false
        if (p.hurtTime > 0 || p.target != null || p.activity == Activity.FLEE) return true
        return p.tickCount % 10 == 0 && p.threat() != null
    }

    private fun flat(p: net.minecraft.world.entity.Entity, at: BlockPos): Double {
        val dx = at.x + 0.5 - p.x
        val dz = at.z + 0.5 - p.z
        return sqrt(dx * dx + dz * dz)
    }

    /** The walk is over, [arrived] at the place or broken off: the body is given back (to its hold, here, if it is a tale's) and the tale is told. */
    private fun end(p: Townsperson, arrived: Boolean) {
        if (done) return
        done = true
        walker?.stop()
        way?.stop()
        val level = p.level() as? ServerLevel
        if (level != null && (mode == Mode.SITTING || mode == Mode.SIT_DOWN || mode == Mode.STAND_UP)) rise(level, p)
        val seat = bench
        if (level != null && seat != null) Claims.release(level, seat, p)
        p.navigation.stop()
        if (!p.activity.overlay || p.activity == Activity.HOBBLE) p.act(Activity.NONE)
        if (prop != null && ItemStack.isSameItemSameComponents(p.carried, prop)) {
            p.carried = ItemStack.EMPTY
            p.carriedUntil = 0
        }
        if (arrived && target != null) p.lookAt(Vec3.atCenterOf(target))
        TaleBodies.escortEnded(p, this)
        if (arrived && level != null && tale >= 0) Tales.tell(level, Event.Arrived(tale, slot))
    }

    /** The switch went off, or the walk is called off: they are let go where they stand, and the tale is not told. */
    fun cancel(p: Townsperson) {
        if (done) return
        end(p, false)
    }

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        // A player's word comes first: the talk takes them, and the tidy gives the walk back from where it had got to
        if (p.director === this) p.director = null
        walker?.stop()
        way?.stop()
        way = null
        stop(p)
        return false
    }

    companion object {
        /** The pace of the walk, and the four blocks from the place that count as arriving. */
        const val PACE = 0.45
        const val ARRIVE = 4.0

        /** Blocks ahead in sight that is "left behind", blocks away out of sight that is far, and ticks of it before they are caught up. */
        const val AHEAD = 24.0
        const val CATCH_UP = 40.0
        const val LOST_FOR = 200L

        /** Blocks walked between rests, blocks walked before a bench is stopped at, how near the player must be for a rest, and how near a bench. */
        const val EVERY = 30.0
        const val BENCH_AFTER = 12.0
        const val NEAR_REST = 20.0
        const val BENCH_NEAR = 6

        /** How far behind the player a rest is cut short, how long each kind of rest lasts (ticks), and ticks between two calls of "don't leave me behind". */
        const val LEFT_BEHIND = 28.0
        const val SIT_FOR = 100L
        const val BREATH_FOR = 80L
        const val CALL_EVERY = 240L

        /** With [leads]: blocks behind that they stop to wait at, blocks ahead in sight that they call after, and the last stretch before the place with no rest. */
        const val WAIT_FOR = 7.0
        const val CALL_AHEAD = 12.0
        const val LAST_STRETCH = 12.0

        /**
         * Starts [who] (the body in [slot] of [t], held in place first if it is not yet; with [t] null, any free adult) on the walk to
         * [target], following [player]. The tale hears [Event.Arrived] when they get there. [prop] is what they carry on the way (the
         * lantern), [until] the game time to let go at (0: not before they arrive). With [leads] and a [target] they walk the way there
         * themselves with the player beside them, rather than following (see the class). False if they cannot be taken just now.
         */
        fun start(
            level: ServerLevel, t: Tale?, slot: String, who: Townsperson, player: ServerPlayer, target: BlockPos?, prop: ItemStack? = null, until: Long = 0L,
            leads: Boolean = false,
        ): Boolean {
            if (t != null) {
                if (who.tale != t.id) TaleBodies.hold(level, t, slot, null, null)
                if (who.tale != t.id) return false
            } else if (!Aside.free(who, seated = true, station = true)) return false
            // A scene's part gives way; so does the hold the tale keeps them under; anything else has charge of them
            (who.director as? Aside)?.end(who, drop = false)
            val current = who.director
            if (current != null && current !is TaleHold) return false
            if (who.seat != null) {
                who.routine.interrupt()
                who.standUp()
            }
            val escort = TaleEscort(player.uuid, target, t?.id ?: -1, slot, prop, until, leads)
            who.director = escort
            who.navigation.stop()
            // Only a tale's walk is kept by the tidy (given back after a talk, ended with the switch); the command's is its own
            if (t != null) TaleBodies.escorting(who, escort)
            return true
        }
    }
}
