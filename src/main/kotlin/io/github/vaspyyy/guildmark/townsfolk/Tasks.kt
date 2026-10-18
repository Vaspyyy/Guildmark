package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/** One thing a townsperson is busy with. Their [Routine] runs one at a time and picks the next when it ends. */
abstract class Task(val p: Townsperson) {
    /** Ticks since it started. */
    var age = 0
        private set

    /** Keeps still in one place (at a station, on a seat, in bed), so they can't be shoved off it. */
    open val anchored: Boolean get() = false

    /** Idle enough to break off for a chat, a song or a wave. */
    open val leisure: Boolean get() = false

    /** Done the moment it starts (something set in hand, a block changed), so ending at once isn't balking. */
    open val instant: Boolean get() = false

    /** What follows straight after, if anything. */
    var then: Task? = null

    /** Queues [next] to run after this, after anything already queued. */
    fun andThen(next: Task?): Task {
        if (next == null) return this
        var last: Task = this
        while (true) last = last.then ?: break
        last.then = next
        return this
    }

    open fun start() {}

    /** One tick of it; false once it's done. */
    fun tick(): Boolean {
        age++
        return run()
    }

    protected abstract fun run(): Boolean

    open fun stop() {}

    protected val level: ServerLevel get() = p.level() as ServerLevel
}

/**
 * Gets a townsperson somewhere on foot. Long ways are walked in legs; someone who can't get there tries a few
 * times, and if no player is about to see it, simply turns up there (a resident must get to work somehow). Out of
 * everyone's sight at both ends there's no walking it at all: they're there, as their day would have them.
 */
class Walker(private val p: Townsperson) {
    enum class State { WALKING, ARRIVED, FAILED }

    var target: BlockPos? = null
        private set
    private var speed = 0.5
    private var near = 1.5
    private var exact = false
    private var tries = 0
    private var bestDist = Double.MAX_VALUE
    private var lastProgress = 0

    /** Sets off for [pos] at [speed]; arrived once within [near] blocks, or right on it if [exact]. */
    fun go(pos: BlockPos, speed: Double, near: Double = 1.5, exact: Boolean = false) {
        target = pos
        this.speed = speed
        this.near = if (exact) 0.45 else near
        this.exact = exact
        tries = 0
        bestDist = Double.MAX_VALUE
        lastProgress = p.tickCount
        val level = p.level()
        if (unseen(pos) && level.isLoaded(pos) && Places.standable(level, pos)) {
            p.navigation.stop()
            p.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, p.yRot, 0f)
            return
        }
        path()
    }

    private fun unseen(goal: BlockPos): Boolean {
        val level = p.level()
        return level.getNearestPlayer(p.x, p.y, p.z, SEEN, false) == null &&
            level.getNearestPlayer(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5, SEEN, false) == null
    }

    fun tick(): State {
        val goal = target ?: return State.ARRIVED
        val dx = goal.x + 0.5 - p.x
        val dz = goal.z + 0.5 - p.z
        val dy = goal.y - p.y
        val flat = dx * dx + dz * dz
        if (flat <= near * near && dy > -1.5 && dy < 1.5) {
            p.navigation.stop()
            // The last step onto the exact block, gently
            if (exact && flat > 0.02) p.setPos(goal.x + 0.5, p.y, goal.z + 0.5)
            return State.ARRIVED
        }
        val dist = flat + dy * dy
        if (dist < bestDist - 4.0) {
            bestDist = dist
            lastProgress = p.tickCount
            tries = 0
        }
        if (p.navigation.isDone || p.tickCount - lastProgress > 100) {
            if (++tries > 4) return giveUp(goal)
            lastProgress = p.tickCount
            path()
        }
        return State.WALKING
    }

    fun stop() {
        target = null
        p.navigation.stop()
    }

    private fun path() {
        val goal = target ?: return
        val dist = p.distanceToSqr(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5)
        if (dist > 44.0 * 44.0) {
            // Too far for one path: head that way
            val leg = LandRandomPos.getPosTowards(p, 32, 10, Vec3.atBottomCenterOf(goal))
            if (leg != null) {
                p.navigation.moveTo(leg.x, leg.y, leg.z, 1, speed)
                return
            }
        }
        p.navigation.moveTo(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5, if (exact) 0 else 1, speed)
    }

    private companion object {
        /** How near a player must be for a walk to be walked rather than simply done. */
        const val SEEN = 80.0
    }

    private fun giveUp(goal: BlockPos): State {
        val level = p.level()
        val seen = level.getNearestPlayer(p.x, p.y, p.z, 40.0, false) != null ||
            level.getNearestPlayer(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5, 40.0, false) != null
        if (!seen && level.isLoaded(goal) && Places.standable(level, goal)) {
            p.navigation.stop()
            p.snapTo(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5, p.yRot, 0f)
            return State.ARRIVED
        }
        return State.FAILED
    }
}

/**
 * A task (or a run of them) worked out only when its turn comes, from wherever they are by then: the way to a
 * seat in a house they've just knocked at, say.
 */
class Later(p: Townsperson, private val make: () -> Task?) : Task(p) {
    private var inner: Task? = null

    override val anchored: Boolean get() = inner?.anchored == true
    override val leisure: Boolean get() = inner?.leisure == true

    override fun start() {
        inner = make()
        inner?.start()
    }

    override fun run(): Boolean {
        val current = inner ?: return false
        if (current.tick()) return true
        current.stop()
        inner = current.then
        inner?.start()
        return inner != null
    }

    override fun stop() {
        inner?.stop()
        inner = null
    }
}

/** Stands about for [ticks], looking round at passers-by. */
open class Idle(p: Townsperson, private val ticks: Int, private val activity: Activity = Activity.NONE, private val chatty: Boolean = true) : Task(p) {
    private var lookAt: Vec3? = null
    private var nextLook = 0

    override val leisure: Boolean get() = chatty

    override fun start() {
        p.navigation.stop()
        p.act(activity)
    }

    override fun run(): Boolean {
        if (age >= nextLook) {
            nextLook = age + 40 + p.random.nextInt(80)
            val player = p.level().getNearestPlayer(p, 8.0)
            lookAt = when {
                player != null && p.random.nextInt(3) != 0 -> null
                else -> p.position().add(p.random.nextDouble() * 8 - 4, 1.0 + p.random.nextDouble(), p.random.nextDouble() * 8 - 4)
            }
        }
        val target = lookAt
        if (target != null) p.lookAt(target) else p.level().getNearestPlayer(p, 8.0)?.let { p.lookAt(it) }
        // A one-shot (a wave, a laugh) settles back into standing
        if (!p.activity.loop && p.actDone) p.act(Activity.NONE)
        return age < ticks
    }
}

/** Walks to [target], [overlay] playing on the way (a crate in the arms, a lantern held up). */
open class Walk(
    p: Townsperson, private val target: BlockPos, private val speed: Double,
    private val near: Double = 1.5, private val overlay: Activity = Activity.NONE, private val exact: Boolean = false,
) : Task(p) {
    private val walker = Walker(p)
    var failed = false
        private set

    override val leisure: Boolean get() = overlay == Activity.NONE || overlay == Activity.STROLL

    override fun start() {
        p.act(overlay)
        walker.go(target, speed, near, exact)
    }

    override fun run(): Boolean {
        if (p.activity != overlay && (p.activity.loop || p.actDone)) p.act(overlay)
        return when (walker.tick()) {
            Walker.State.WALKING -> true
            Walker.State.ARRIVED -> false
            Walker.State.FAILED -> {
                failed = true
                // Nothing that was to happen there can happen now
                then = null
                false
            }
        }
    }

    override fun stop() {
        walker.stop()
        if (p.activity == overlay && overlay != Activity.NONE) p.act(Activity.NONE)
    }
}

/**
 * One step of a job: an activity and how long to keep at it (a one-shot plays once whatever [ticks] says), facing
 * the work or, if [turned], away from it toward the room; [line] is said as it starts.
 */
data class Step(val activity: Activity, val ticks: Int = activity.ticks, val turned: Boolean = false, val line: String? = null)

/**
 * Working at a station: walks to [spot], faces the work and goes through the steps [plan] hands out one by one
 * (null once the work is done). [onTick] hears each tick of each step, for what the work does to the world.
 */
class Work(
    p: Townsperson, private val spot: Spot, private val lookAt: Vec3, private val plan: (Int) -> Step?,
    private val station: BlockPos? = null, private val onTick: ((Step, Int) -> Unit)? = null,
) : Task(p) {
    constructor(p: Townsperson, spot: Spot, lookAt: Vec3, steps: List<Step>, station: BlockPos? = null, onTick: ((Step, Int) -> Unit)? = null) :
        this(p, spot, lookAt, { i: Int -> steps.getOrNull(i) }, station, onTick)

    private val walker = Walker(p)
    private var arrived = false
    private var index = 0
    private var step: Step? = null

    /** Where they stand to work. */
    val target: BlockPos get() = spot.pos
    private var stepAge = 0
    private var refused = false

    override val anchored: Boolean get() = arrived

    override fun start() {
        if (!Claims.claim(level, spot.pos, p, 400) || station != null && !Claims.claim(level, station, p, 400)) {
            refused = true
            return
        }
        if (!p.activity.overlay) p.act(Activity.NONE)
        walker.go(spot.pos, 0.55, exact = true)
    }

    override fun run(): Boolean {
        if (refused) return false
        if (!arrived) {
            when (walker.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.FAILED -> return false
                Walker.State.ARRIVED -> {
                    arrived = true
                    if (!begin()) return false
                }
            }
        }
        if (age % 100 == 0) {
            Claims.claim(level, spot.pos, p, 400)
            station?.let { Claims.claim(level, it, p, 400) }
        }
        val current = step ?: return false
        if (current.turned) {
            p.face(spot.facing.opposite)
            val watcher = p.level().getNearestPlayer(p, 8.0)
            if (watcher != null) p.lookAt(watcher) else p.lookAt(p.eyePosition.add(spot.facing.opposite.stepX * 3.0, 0.0, spot.facing.opposite.stepZ * 3.0))
        } else {
            p.face(spot.yaw)
            p.lookAt(lookAt)
        }
        onTick?.invoke(current, stepAge)
        stepAge++
        val over = if (current.activity.loop) stepAge >= current.ticks else stepAge >= current.activity.ticks
        if (over) {
            index++
            if (!begin()) return false
        }
        return true
    }

    private fun begin(): Boolean {
        val next = plan(index) ?: return false
        step = next
        stepAge = 0
        p.act(next.activity)
        next.line?.let { p.say(it) }
        return true
    }

    override fun stop() {
        walker.stop()
        Claims.release(level, spot.pos, p)
        Claims.release(level, station, p)
        if (!p.activity.overlay) p.act(Activity.NONE)
    }
}

/**
 * Sits a while on the stair at [seatPos], going through [moods] (sit, sip, chat) in turn: lowered onto it from the
 * floor in front, and up again onto the floor at the end (or once a new part of their day has begun).
 */
class Sit(p: Townsperson, private val seatPos: BlockPos, private val ticks: Int, private val moods: List<Activity>) : Task(p) {
    private val walker = Walker(p)
    private var seated = false
    private var nextMood = 0

    // The tick they began to get up, or -1 while they sit on
    private var rising = -1

    override val anchored: Boolean get() = seated
    override val leisure: Boolean get() = seated

    override fun start() {
        if (!Claims.claim(level, seatPos, p, 400)) {
            nextMood = -1
            return
        }
        val access = Places.access(level, seatPos)
        if (access == null) {
            nextMood = -1
            return
        }
        walker.go(access, 0.5, near = 1.2)
    }

    override fun run(): Boolean {
        if (nextMood < 0) return false
        if (!seated) {
            when (walker.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.FAILED -> return false
                Walker.State.ARRIVED -> {
                    if (!Places.isSeat(level, level.getBlockState(seatPos), seatPos)) return false
                    seated = true
                    // On the seat as the clip lowers them onto it from where they stood
                    p.sitOn(seatPos)
                    p.act(Activity.SIT_DOWN)
                    nextMood = age + Activity.SIT_DOWN.ticks
                }
            }
        }
        if (age % 100 == 0) Claims.claim(level, seatPos, p, 400)
        if (rising < 0 && (age >= ticks || p.routine.windingDown)) {
            rising = age
            p.act(Activity.STAND_UP)
        }
        // Still on the seat while the clip raises them; stop() puts them on the floor as it ends
        if (rising >= 0) return age < rising + Activity.STAND_UP.ticks
        if (age >= nextMood && (p.activity.loop || p.actDone)) {
            val mood = moods[p.random.nextInt(moods.size)]
            p.act(mood)
            nextMood = age + if (mood.loop) mood.ticks * (2 + p.random.nextInt(3)) else mood.ticks
        }
        val other = p.attending
        if (other != null) p.lookAt(other) else p.level().getNearestPlayer(p, 6.0)?.let { if (p.random.nextInt(4) == 0) p.lookAt(it) }
        return true
    }

    override fun stop() {
        walker.stop()
        if (seated) p.standUp()
        Claims.release(level, seatPos, p)
        p.act(Activity.NONE)
    }
}

/** Goes to bed at [bed] and sleeps until the night is over (or for as long as [keep] says, if given). */
class Sleep(p: Townsperson, private val bed: BlockPos, private val keep: (() -> Boolean)? = null) : Task(p) {
    private val walker = Walker(p)
    private var asleep = false

    override val anchored: Boolean get() = asleep

    override fun start() {
        // Already abed (as they were when the world was saved), or the bed is someone else's
        if (p.isSleeping || !Claims.claim(level, bed, p, 400)) {
            asleep = true
            return
        }
        p.act(Activity.NONE)
        walker.go(bed, 0.5, near = 1.6)
    }

    override fun run(): Boolean {
        if (!asleep) {
            when (walker.tick()) {
                Walker.State.WALKING -> return true
                Walker.State.FAILED -> return false
                Walker.State.ARRIVED -> {
                    val state = level.getBlockState(bed)
                    if (!state.`is`(net.minecraft.tags.BlockTags.BEDS) || state.getValue(net.minecraft.world.level.block.AbstractBedBlock.OCCUPIED)) return false
                    p.startSleeping(bed)
                    asleep = true
                }
            }
        }
        if (age % 100 == 0) Claims.claim(level, bed, p, 400)
        return p.isSleeping && (keep?.invoke() ?: (Schedule.phase(p) == Phase.SLEEP))
    }

    override fun stop() {
        walker.stop()
        if (p.isSleeping) p.stopSleeping()
        Claims.release(level, bed, p)
    }
}

/**
 * A chat between two townsfolk: they face each other and take turns, one talking (and now and then saying a
 * bit of gossip), the other listening and answering; it may end in a laugh. Both run one of these.
 */
class Chat(p: Townsperson, val partner: Townsperson, private val talking: Boolean, private val length: Int, private val seated: Boolean = false) : Task(p) {
    private var turn = 0
    private var turnEnds = 0
    private var speaking = talking
    private val walker = Walker(p)
    private var together = false

    override val leisure: Boolean get() = false

    override fun start() {
        if (talking && !seated && p.distanceToSqr(partner) > 9.0) walker.go(partner.blockPosition(), 0.45, near = 2.2) else together = true
        p.act(Activity.NONE)
    }

    override fun run(): Boolean {
        if (!partner.isAlive || partner.routine.task.let { it !is Chat || it.partner !== p }) return false
        if (!together) {
            when (walker.tick()) {
                Walker.State.WALKING -> return age < 300
                Walker.State.FAILED -> return false
                Walker.State.ARRIVED -> together = true
            }
        }
        if (!seated) p.face(p.yawTo(partner.position()))
        p.lookAt(partner.eyePosition)
        if (age >= turnEnds) {
            if (turn >= length) {
                if (p.random.nextInt(3) == 0) {
                    p.act(Activity.LAUGH)
                    p.say("laugh_0")
                }
                return age < turnEnds + 30
            }
            turn++
            speaking = if (turn == 1) talking else !speaking
            turnEnds = age + 60 + p.random.nextInt(60)
            if (seated) p.act(if (speaking) Activity.SIT_TALK else Activity.SIT)
            else p.act(if (speaking) Activity.TALK else Activity.LISTEN)
            if (speaking && p.random.nextInt(2) == 0) p.say(Lines.gossip(p))
        } else if (!speaking && age == turnEnds - 30 && p.random.nextInt(2) == 0) {
            p.say(Lines.reply(p))
        }
        return true
    }

    override fun stop() {
        walker.stop()
        if (!seated) p.act(Activity.NONE)
    }
}

/** Gathers round a bard playing nearby, and claps or cheers when the song's done. */
class Audience(p: Townsperson, private val bard: Townsperson) : Task(p) {
    private val walker = Walker(p)
    private var placed = false

    override fun start() {
        val away = p.position().subtract(bard.position()).normalize().scale(3.0 + p.random.nextDouble() * 2.5)
        val spot = BlockPos.containing(bard.position().add(away))
        walker.go(spot, 0.5, near = 1.5)
    }

    override fun run(): Boolean {
        if (!bard.isAlive || bard.activity != Activity.LUTE && bard.activity != Activity.SALUTE && bard.activity != Activity.CONJURE) {
            // The song's over: applause, then on their way
            if (p.activity != Activity.CLAP && p.activity != Activity.CHEER) {
                p.act(if (p.role == TownRole.CHILD) Activity.JUMP else if (p.random.nextInt(4) == 0) Activity.CHEER else Activity.CLAP)
                return true
            }
            return !p.actDone
        }
        if (!placed) {
            when (walker.tick()) {
                Walker.State.WALKING -> return age < 400
                Walker.State.FAILED -> placed = true
                Walker.State.ARRIVED -> placed = true
            }
            if (placed) p.act(if (p.role == TownRole.CHILD) Activity.PLAY else Activity.LISTEN)
        }
        if (placed) {
            p.face(p.yawTo(bard.position()))
            p.lookAt(bard.eyePosition)
        }
        return age < 1600
    }

    override fun stop() {
        walker.stop()
        p.act(Activity.NONE)
    }
}

/** A child following [player] about for a while, keeping a few steps off. */
class Follow(p: Townsperson, private val player: Player, private val ticks: Int) : Task(p) {
    override fun start() {
        p.act(Activity.PLAY)
        p.say("child_2")
    }

    override fun run(): Boolean {
        if (!player.isAlive || player.level() !== p.level() || p.distanceToSqr(player) > 400.0) return false
        val dist = p.distanceToSqr(player)
        if (dist > 12.0) p.navigation.moveTo(player, 0.75) else if (dist < 6.0) p.navigation.stop()
        if (p.navigation.isDone) {
            p.lookAt(player)
            if (p.activity.overlay && p.random.nextInt(120) == 0) p.act(if (p.random.nextBoolean()) Activity.WAVE else Activity.JUMP)
        }
        if (!p.activity.loop && p.actDone) p.act(Activity.PLAY)
        return age < ticks
    }

    override fun stop() {
        p.navigation.stop()
        p.act(Activity.NONE)
    }
}

/** Tag: runs after [other]; on catching them they're it, and this one runs off. */
class Chase(p: Townsperson, private val other: Townsperson) : Task(p) {
    override fun start() {
        p.act(Activity.PLAY)
        p.say("child_0")
    }

    override fun run(): Boolean {
        if (!other.isAlive || p.distanceToSqr(other) > 900.0) return false
        if (p.distanceToSqr(other) < 2.5) {
            other.say("child_1", force = true)
            val tagged = Chase(other, p)
            other.routine.replace(Idle(other, 20, Activity.SPIN).andThen(tagged))
            p.routine.replace(Run(p, other, 120))
            return true
        }
        if (age % 10 == 0) p.navigation.moveTo(other, 0.8)
        return age < 600
    }

    override fun stop() {
        p.navigation.stop()
        p.act(Activity.NONE)
    }
}

/** Runs away from [from] for [ticks], squealing. */
class Run(p: Townsperson, private val from: Townsperson, private val ticks: Int) : Task(p) {
    override fun start() {
        p.act(Activity.PLAY)
    }

    override fun run(): Boolean {
        if (age % 20 == 0 || p.navigation.isDone) {
            net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(p, 10, 4, from.position())?.let {
                p.navigation.moveTo(it.x, it.y, it.z, 0.85)
            }
        }
        if (age == 30) p.say("child_4")
        return age < ticks
    }

    override fun stop() {
        p.navigation.stop()
        p.act(Activity.NONE)
    }
}
