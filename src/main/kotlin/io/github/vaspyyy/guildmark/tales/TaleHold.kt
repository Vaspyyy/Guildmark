package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import kotlin.math.abs

/**
 * Keeps a tale's body at its mark (living-world/stories/design.md 4.4): the director of a resident the tale holds or a figure it
 * made. It walks them to the [mark] if they are not there, stands them facing [yaw], plays the [clip] the step names on a loop
 * (LOOKOUT, GRIEVE, SHIVER, SIT...), and turns the eyes to a player within six blocks (the body too, when it has no clip to hold).
 * A player's word comes first: it steps aside for the talk ([Townsperson] `Talking`), and [TaleBodies.tend] gives it back when the
 * talk is over, within a second. A stage that takes the body leaves it here as it found it ([rest] says where it ended).
 *
 * A [seat] (a bench or a doorstep stair) keeps them sitting on it for a sitting clip; a clip with nowhere to sit plays standing. The
 * two clips drawn standing (GRIEVE, WRING_HANDS) are never played on a seat: whoever sits there sits it out ([Sets.fit]).
 */
class TaleHold(var mark: BlockPos, var yaw: Float? = null, var clip: Activity? = null, var seat: BlockPos? = null) : Director {
    private var walker: Walker? = null
    private var faced = false
    private var retry = 0

    /** Where they stand to be at the mark (or to sit on the seat), worked out for [spotFor]. */
    private var spot: BlockPos? = null
    private var spotFor: BlockPos? = null
    private var spotSeat: BlockPos? = null

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (p.tickCount % 100 == 0) Claims.held(p, 400)
        val onSeat = seat != null && p.seat == seat
        if (!onSeat) {
            val goal = goal(level)
            if (!at(p, goal)) {
                if (p.tickCount < retry) {
                    p.navigation.stop()
                    return
                }
                val w = walker ?: Walker(p).also {
                    walker = it
                    it.go(goal, Jobs.WALK, near = 0.9)
                }
                when (w.tick()) {
                    Walker.State.WALKING -> {
                        if (p.activity != Activity.NONE && !p.activity.overlay && (p.activity.loop || p.actDone)) p.act(Activity.NONE)
                        return
                    }
                    Walker.State.FAILED -> {
                        // Not to be got to just now: stand where they are, and try again in ten seconds
                        walker = null
                        retry = p.tickCount + 200
                        return
                    }
                    Walker.State.ARRIVED -> {
                        walker = null
                        faced = false
                    }
                }
            }
        }
        p.navigation.stop()
        val perch = seat
        if (perch != null && p.seat == null && Places.isSeat(level, level.getBlockState(perch), perch) && Claims.claim(level, perch, p, 400)) {
            p.sitOn(perch)
            faced = true
        }
        if (!faced) {
            yaw?.let { p.face(it) }
            faced = true
        }
        // The step's clip, on a loop (a clip drawn standing, GRIEVE or WRING_HANDS, is never played on a seat: they sit it out)
        val c = clip?.let { Sets.fit(p, it) }
        if (c != null && p.activity != c && (p.activity.loop || p.actDone)) p.act(c)
        // Eyes on a player near; the body turns to them only when it has nothing to hold
        val near = level.getNearestPlayer(p, LOOK)
        if (near != null) {
            p.lookAt(near)
            if (c == null && p.seat == null) p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(near.position()), TURN))
        }
    }

    /** A player's word comes first: it steps aside, the talk takes them, and the tidy gives it back. */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (p.director === this) p.director = null
        walker?.stop()
        walker = null
        return false
    }

    /** A scene they were in is over: here is the mark now, wherever it left them. */
    fun rest(p: Townsperson) {
        mark = p.blockPosition()
        walker = null
        faced = true
        retry = 0
        if (p.seat != null) seat = p.seat
        TaleBodies.marked(p, mark)
    }

    /** Where they stand: the way up to the seat if there is one, else the mark (or the nearest block that can be stood on). */
    private fun goal(level: ServerLevel): BlockPos {
        if (spot == null || spotFor != mark || spotSeat != seat) {
            spotFor = mark
            spotSeat = seat
            spot = seat?.let { Places.access(level, it) } ?: Sets.standable(level, mark)
        }
        return spot ?: mark
    }

    private fun at(p: Townsperson, goal: BlockPos): Boolean {
        val dx = goal.x + 0.5 - p.x
        val dz = goal.z + 0.5 - p.z
        return dx * dx + dz * dz <= NEAR * NEAR && abs(goal.y - p.y) < 2.5
    }

    private companion object {
        /** How near the spot is "at" it, how near a player must be for their eyes to be met, and the degrees a tick the body turns to them. */
        const val NEAR = 1.4
        const val LOOK = 6.0
        const val TURN = 8f
    }
}

/**
 * Walks a body out of the town and away (living-world/stories/design.md 4.4 rules 2 and 4): a resident whose day body a tale
 * takes while a player can see it, and a figure let go in sight, go by the [way] (the nearest gate and a little way down its road)
 * and are [gone] there, rather than vanishing where they stand. They are gone at once, wherever they are, when nobody is near to
 * see them any more.
 */
class TaleLeave(private val way: List<BlockPos>, private val gone: (Townsperson) -> Unit) : Director {
    private var i = 0
    private var age = 0
    private var walker: Walker? = null

    override fun tick(p: Townsperson) {
        age++
        if (age > MOST || age % 20 == 0 && i > 0 && p.level().getNearestPlayer(p, SEEN) == null || i >= way.size) return end(p)
        val w = walker ?: Walker(p).also {
            walker = it
            it.go(way[i], Jobs.WALK, near = 1.5)
        }
        when (w.tick()) {
            Walker.State.ARRIVED -> {
                i++
                walker = null
            }
            Walker.State.FAILED -> end(p)
            Walker.State.WALKING -> if (p.activity != Activity.NONE && !p.activity.overlay && (p.activity.loop || p.actDone)) p.act(Activity.NONE)
        }
    }

    /** A player's word comes first: they stop for it, and carry on with their day. */
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (p.director === this) p.director = null
        walker?.stop()
        return false
    }

    private fun end(p: Townsperson) {
        walker?.stop()
        if (p.director === this) p.director = null
        gone(p)
    }

    private companion object {
        /** The longest the way takes (a minute), and how far from a player they are gone for good. */
        const val MOST = 1200
        const val SEEN = 40.0
    }
}
