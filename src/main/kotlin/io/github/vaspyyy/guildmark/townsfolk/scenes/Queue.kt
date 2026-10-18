package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * A line of people waiting their turn (at the bakery's door, at the alms table): its places laid out once ([lay]):
 * beside the door and along the wall, the doorway kept clear for whoever's being served, or straight back from a
 * table out in the open, so it reads as a queue from across the street. Whoever's first is served when the place is
 * ready; the rest shuffle up a place after the one in front, not all at once. One to a door or a table.
 */
internal class Queue(val head: BlockPos, private val spots: List<BlockPos>, private val toward: Vec3) {
    val members = ArrayList<Townsperson>()

    /** When someone in it last said something, and where in the line. */
    var spoke = Long.MIN_VALUE / 2
    var murmur = -1
    var murmured = Long.MIN_VALUE / 2

    /** Whether it's breaking up: nothing more to be had (everyone still in it gives up). */
    var over = false

    val room: Int get() = spots.size
    val full: Boolean get() = members.size >= spots.size

    fun place(p: Townsperson): Int = members.indexOf(p)

    fun spot(k: Int): BlockPos? = spots.getOrNull(k)

    /** Whether [pos] is one of the line's places (to stand somewhere else: to eat, to wait for someone). */
    fun holds(pos: BlockPos): Boolean = pos in spots

    /** What the [k]th in line faces: the back of the one in front, and the door or the table for the first. */
    fun facing(k: Int): Vec3 = spots.getOrNull(k - 1)?.let { Vec3(it.x + 0.5, it.y + 1.5, it.z + 0.5) } ?: toward

    fun join(p: Townsperson): Boolean {
        if (p in members) return true
        if (full) return false
        members += p
        return true
    }

    fun leave(p: Townsperson) {
        members.remove(p)
    }

    /** Those who've gone (or are no longer in it) dropped, so the line closes up. */
    fun prune() {
        members.removeIf { !it.isAlive || it.isRemoved || it.routine.task.let { t -> t !is Queueing || t.queue !== this } && it.director == null }
    }

    companion object {
        /**
         * The places of a line waiting at [head] (the step outside a door, the front of a table), up to [room] of them.
         * At a door ([open] false) the doorway's left clear: the first stands beside it, and the rest along the wall to
         * whichever side has the more room, then on out into the street if the wall's blocked early. In the open, a
         * line straight back along [back] from [head] itself. Null if there's no ground to stand on at all.
         */
        fun lay(level: ServerLevel, head: BlockPos, back: Direction, room: Int, open: Boolean): List<BlockPos>? {
            val out = ArrayList<BlockPos>(room)
            if (open) {
                var at = ground(level, head) ?: return null
                out += at
                while (out.size < room) {
                    at = ground(level, at.relative(back)) ?: break
                    if (at in out) break
                    out += at
                }
                return out
            }
            val left = run(level, head, back.counterClockWise, room)
            val right = run(level, head, back.clockWise, room)
            out += if (right.size > left.size) right else left
            // No room along the wall at all: straight out from the door, a step clear of it
            if (out.isEmpty()) out += ground(level, head.relative(back)) ?: ground(level, head) ?: return null
            // Blocked along the wall early: on out into the street from the last of it
            var at = out.last()
            while (out.size < room) {
                at = ground(level, at.relative(back)) ?: break
                if (at in out || at == head) break
                out += at
            }
            return out
        }

        private fun run(level: ServerLevel, from: BlockPos, dir: Direction, n: Int): List<BlockPos> {
            val out = ArrayList<BlockPos>(n)
            var at = from
            while (out.size < n) {
                at = ground(level, at.relative(dir)) ?: break
                out += at
            }
            return out
        }

        private fun ground(level: ServerLevel, at: BlockPos): BlockPos? {
            for (dy in intArrayOf(0, 1, -1)) {
                val pos = at.above(dy)
                if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
            }
            return null
        }
    }
}

/**
 * A place in a [Queue], as a part of someone's day: to the back of the line, then waiting their turn (QUEUE: shifting
 * from foot to foot, peering ahead), shuffling up a place a moment after the one in front, a grumble now and then
 * (said by one at a time, with a nod or a sigh from those either side), and their turn at the head when [ready] says
 * the place can serve them ([served]: the loaf bought, the bowl taken). After [patience] ticks of it, when the line
 * breaks up, or when their day moves on, they give it up with a shrug.
 */
internal class Queueing(
    p: Townsperson, val queue: Queue, private val patience: Int,
    private val ready: (Townsperson) -> Boolean,
    private val served: (Townsperson) -> Task?,
    /** The family they say now and then while they wait, if any. */
    private val words: (Townsperson) -> String?,
    private val why: String,
) : Task(p), Scened {
    private val walker = Walker(p)
    private var k = -1
    private var moveAt = -1
    private var walking = false
    private var giving = -1
    private var turnAt = 0
    private var reacted = Long.MIN_VALUE
    private var refused = false

    override fun start() {
        if (!queue.join(p)) {
            refused = true
            return
        }
        if (!p.activity.overlay) p.act(Activity.NONE)
    }

    override fun run(): Boolean {
        if (refused) return false
        if (giving >= 0) return giveUp()
        queue.prune()
        val place = queue.place(p)
        if (place < 0) return false
        if (age > patience || p.routine.windingDown || queue.over) return giveUp()
        val spot = queue.spot(place) ?: return giveUp()
        if (place != k) {
            // The line's moved up: a step on a moment after the one in front
            if (moveAt < 0) moveAt = age + if (k < 0) 0 else 6 + 5 * place + p.random.nextInt(6)
            if (age >= moveAt) {
                // To the back of it at a walk; a step up at a shuffle
                val joining = k < 0
                k = place
                moveAt = -1
                walking = true
                if (p.activity == Activity.QUEUE) p.act(Activity.NONE)
                walker.go(spot, if (joining) 0.45 else 0.32, exact = true)
            }
        }
        if (walking) {
            when (walker.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.FAILED -> return giveUp()
                Walker.State.ARRIVED -> {
                    walking = false
                    p.act(Activity.QUEUE)
                }
            }
        }
        val ahead = queue.facing(place)
        p.lookAt(ahead)
        p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(ahead) + jitter(), Aside.TURN))
        if (p.activity != Activity.QUEUE && (p.activity.loop || p.actDone)) p.act(Activity.QUEUE)
        if (place == 0 && k == 0 && age >= turnAt && ready(p)) {
            val turn = served(p)
            if (turn == null) {
                turnAt = age + 20
                return true
            }
            queue.leave(p)
            val rest = then
            then = turn
            turn.andThen(rest)
            return false
        }
        talk(place)
        return true
    }

    /** The way each stands in line, a little turned this way or that: a line of people, not of posts. */
    private fun jitter(): Float = (Math.floorMod(p.uuid.leastSignificantBits, 31L) - 15).toFloat()

    /** A grumble now and then from one of them, and a nod or a sigh from those either side of whoever said it. */
    private fun talk(place: Int) {
        val now = p.level().gameTime
        if (queue.murmur >= 0 && now - queue.murmured in 8L..30L && kotlin.math.abs(queue.murmur - place) == 1 && reacted != queue.murmured) {
            reacted = queue.murmured
            if (p.random.nextInt(2) == 0) p.act(if (p.random.nextBoolean()) Activity.NOD else Activity.SIGH)
            return
        }
        if (age % 40 != 13 || now - queue.spoke < SPOKE_EVERY || p.random.nextInt(3) != 0) return
        val family = words(p) ?: return
        if (!Words.say(p, family, why)) return
        queue.spoke = now
        queue.murmur = place
        queue.murmured = now
        p.act(if (p.role == TownRole.CHILD) Activity.SIGH else Activity.GRUMBLE)
    }

    private fun giveUp(): Boolean {
        if (giving < 0) {
            giving = age
            queue.leave(p)
            walker.stop()
            then = null
            p.act(if (p.random.nextBoolean()) Activity.SHRUG else Activity.GRUMBLE)
            return true
        }
        return !p.actDone && age - giving < 40
    }

    override fun stop() {
        walker.stop()
        queue.leave(p)
        if (p.activity == Activity.QUEUE) p.act(Activity.NONE)
    }

    private companion object {
        /** The fewest ticks between one grumble in a line and the next. */
        const val SPOKE_EVERY = 300L
    }
}
