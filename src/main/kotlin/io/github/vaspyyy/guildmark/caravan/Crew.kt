package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A caravan's crew (design-road.md section 1): the driver on the bench's right end with the reins, a guard beside
 * them, one on the let-down tailboard with a spear across the knees and, with three, one walking beside on the left.
 * They're townsfolk, named and in their looks, in a [Show]'s charge ([CrewDirector]) while they're out: the wagon's
 * driver never comes to harm, and its guards fight for it only in a fight a player has joined.
 */
internal object Crew {
    /** Every crew member's tag, kept if a body is ever saved by mistake, so it goes again when loaded. */
    const val TAG = "guildmark.caravan"

    /** Crew member [k] (0 the driver, then the guards) of a caravan whose looks go by [seed], standing at [at] facing [yaw]. */
    fun spawn(level: ServerLevel, at: Vec3, yaw: Float, seed: Long, k: Int): Townsperson? {
        val role = if (k == 0) TownRole.CITIZEN else TownRole.GUARD
        val own = seed * 31L + k * 7919L
        val look = Math.floorMod(own, role.looks.toLong()).toInt()
        val p = Townsfolk.create(level, BlockPos.containing(at), role, look, yaw, EntitySpawnReason.EVENT) ?: return null
        p.snapTo(at.x, at.y, at.z, yaw, 0f)
        p.yHeadRot = yaw
        p.yBodyRot = yaw
        val woman = Names.woman(role, p.look, own)
        p.customName = Component.literal(Names.given(null, woman, own) + " " + Names.family(null, own / 3 + k))
        p.isCustomNameVisible = false
        p.addTag(TAG)
        p.home = BlockPos.containing(at)
        // No running from the band, and no going after anything on their own: the show says when they fight
        p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
        p.targetSelector.removeAllGoals { true }
        return if (level.addFreshEntity(p)) p else null
    }

    /** Whether [p] is one of a caravan's crew (out on the road, or a driver back at the gate). */
    fun of(p: Townsperson): Boolean = p.entityTags().contains(TAG)
}

/** Has charge of a crew member while they're out: their show moves them; using them speaks to the show. */
internal class CrewDirector(val hand: Hand) : Director {
    override fun tick(p: Townsperson) {}

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean = hand.show.used(hand, player)
}

/**
 * One crew member as a show runs them: where they are on the wagon ([seat]), and what they're doing, step by step
 * ([then]): the clips at the seats (onto the road with jump_down, back up with climb_up), short walks round the
 * wagon played straight from the clip's numbers ([Stroll]), longer ones on foot ([Walk]).
 */
internal class Hand(val show: Show, val p: Townsperson, val index: Int) {
    /** Where on the wagon they are: Wagon.OFF on the road, else one of its seats. */
    var seat = Wagon.OFF

    /** The seat they last sat on, for the clips that go from it. */
    var from = Wagon.OFF

    val walker = Walker(p)
    private val steps = ArrayDeque<Step>()
    private var current: Step? = null

    /** Moved by hand (on the wagon, or played round it), not by their own legs. */
    var kinematic = false
        private set

    val driver: Boolean get() = index == 0
    val idle: Boolean get() = current == null && steps.isEmpty()

    /** Queues [step]. */
    fun then(step: Step): Hand {
        steps.addLast(step)
        return this
    }

    fun then(block: (Hand) -> Unit): Hand = then(Once(block))

    /** Stops what they're at and forgets what's queued. */
    fun clear() {
        current?.stop(this)
        current = null
        steps.clear()
        walker.stop()
    }

    /** One tick, after the wagon has moved: on their seat, or the step they're at. */
    fun tick() {
        if (seat != Wagon.OFF) show.wagon?.let { sit(it) }
        var guard = 0
        while (guard++ < 8) {
            val step = current ?: steps.removeFirstOrNull()?.also {
                current = it
                it.start(this)
            } ?: return
            if (!step.tick(this)) return
            step.stop(this)
            current = null
        }
    }

    /** Puts them on [seat] of the wagon at once (as a body is first made, or a climb starts). */
    fun seatOn(seat: Int) {
        if (seat != Wagon.OFF) from = seat
        this.seat = seat
        show.wagon?.seat(index, seat)
        if (seat != Wagon.OFF) {
            hold(true)
            show.wagon?.let { sit(it) }
        }
    }

    /** Off the wagon, standing at [at] facing [yaw]: still moved by hand ([kinematic]) or on their own legs. */
    fun land(at: Vec3, yaw: Float, byHand: Boolean) {
        seat = Wagon.OFF
        show.wagon?.seat(index, Wagon.OFF)
        p.setPos(at.x, at.y, at.z)
        p.face(yaw)
        hold(byHand)
    }

    /** Moved by hand, or let go to move on their own. */
    fun hold(byHand: Boolean) {
        kinematic = byHand
        p.noPhysics = byHand
        p.isNoGravity = byHand
        p.deltaMovement = Vec3.ZERO
        if (byHand) p.navigation.stop()
    }

    /** Placed on their seat as the wagon now stands. */
    private fun sit(wagon: Wagon) {
        val (at, yaw) = wagon.seatAt(seat)
        p.setPos(at.x, at.y, at.z)
        p.deltaMovement = Vec3.ZERO
        p.yRot = yaw
        p.yBodyRot = yaw
        // The head keeps looking where it looks, within a turn of the shoulders
        p.yHeadRot = yaw + Mth.clamp(Mth.wrapDegrees(p.yHeadRot - yaw), -70f, 70f)
    }

    /** Where they stand on the road beside seat [seat], [right] px to its right and [ahead] px ahead (the clips' numbers), and the yaw [turn] from the seat's. */
    fun beside(seat: Int, right: Float, ahead: Float, turn: Float): Pair<Vec3, Float> {
        val wagon = show.wagon ?: return p.position() to p.yRot
        val s = Wagon.seatOf(seat) ?: return p.position() to p.yRot
        // The seat's own right and ahead, in the wagon's space (px; the right is -x, ahead -z, turned by the seat's yaw)
        val r = Math.toRadians(s.yaw.toDouble())
        val ax = sin(r).toFloat()
        val az = -cos(r).toFloat()
        val rx = -cos(r).toFloat()
        val rz = -sin(r).toFloat()
        val point = wagon.point(s.x + rx * right + ax * ahead, 0f, s.z + rz * right + az * ahead)
        val level = show.level
        val y = Ground.stand(level, point.x, point.z, wagon.y + 0.5)
        return Vec3(point.x, y, point.z) to Mth.wrapDegrees(wagon.yRot + s.yaw + turn)
    }
}

/** Something a crew member does, a tick at a time: true once it's done. */
internal abstract class Step {
    open fun start(h: Hand) {}
    abstract fun tick(h: Hand): Boolean
    open fun stop(h: Hand) {}
}

/** Done at once. */
internal class Once(private val block: (Hand) -> Unit) : Step() {
    override fun tick(h: Hand): Boolean {
        block(h)
        return true
    }
}

/** A while doing nothing new. */
internal class Wait(private val ticks: Int) : Step() {
    private var t = 0
    override fun tick(h: Hand): Boolean = ++t >= ticks
}

/** Until [until] says so (or [most] ticks at most), [each] tick by tick. */
internal class Until(private val most: Int, private val each: ((Hand) -> Unit)? = null, private val until: (Hand) -> Boolean) : Step() {
    private var t = 0
    override fun tick(h: Hand): Boolean {
        each?.invoke(h)
        return until(h) || ++t >= most
    }
}

/** [what] played through ([ticks] long, or the one-shot's own length). */
internal class Play(private val what: Activity, private val ticks: Int = what.ticks) : Step() {
    private var t = 0
    override fun start(h: Hand) = h.p.act(what)
    override fun tick(h: Hand): Boolean = ++t >= ticks
}

/** Says [line] (a key under say.guildmark.), heard by the players near. */
internal class Say(private val line: String, private val hearing: Double = 24.0) : Step() {
    override fun tick(h: Hand): Boolean {
        h.p.say(line, force = true, hearing = hearing)
        return true
    }
}

/**
 * Down off the wagon from their seat (the driver's end of the bench, or the tailboard): jump_down plays with them
 * on the seat, and as it ends they stand on the road beside it, facing out (CrewMoves' JUMP_DOWN numbers). Stays
 * moved by hand ([byHand]) for a played walk after.
 */
internal class JumpDown(private val byHand: Boolean = true) : Step() {
    private var t = 0
    override fun start(h: Hand) {
        h.p.act(Activity.JUMP_DOWN)
        h.show.jumped(h)
    }

    override fun tick(h: Hand): Boolean {
        if (++t < Activity.JUMP_DOWN.ticks) return false
        val seat = h.seat.takeIf { it != Wagon.OFF } ?: return true
        val (at, yaw) = h.beside(seat, JUMP_RIGHT, JUMP_AHEAD, JUMP_TURN)
        h.land(at, yaw, byHand)
        h.p.act(Activity.NONE)
        return true
    }

    companion object {
        // CrewMoves: JUMP_DOWN_RIGHT, JUMP_DOWN_AHEAD, JUMP_DOWN_TURN
        const val JUMP_RIGHT = 14f
        const val JUMP_AHEAD = 1f
        const val JUMP_TURN = 90f
    }
}

/**
 * Up onto [seat] (the driver's end of the bench, or the tailboard): they walk to where climb_up begins (CrewMoves'
 * CLIMB_UP numbers: beside and behind the seat, facing the box), then they're on the seat as it starts and the clip
 * lifts them into it; [then] plays seated after.
 */
internal class ClimbUp(private val seat: Int, private val then: Activity) : Step() {
    private var t = -1
    private var stroll: Stroll? = null

    override fun start(h: Hand) {
        val (at, _) = h.beside(seat, CLIMB_RIGHT, -CLIMB_BEHIND, CLIMB_TURN)
        stroll = Stroll(listOf(at), 0.09)
        if (!h.kinematic) h.hold(true)
    }

    override fun tick(h: Hand): Boolean {
        val s = stroll
        if (t < 0 && s != null) {
            if (s.tick(h)) return false
            val (_, yaw) = h.beside(seat, CLIMB_RIGHT, -CLIMB_BEHIND, CLIMB_TURN)
            h.p.face(yaw)
            t = 0
            h.seatOn(seat)
            h.p.act(Activity.CLIMB_UP)
            h.show.climbed(h)
            return false
        }
        if (++t < Activity.CLIMB_UP.ticks) return false
        h.p.act(then)
        return true
    }

    companion object {
        // CrewMoves: CLIMB_UP_RIGHT, CLIMB_UP_BEHIND, CLIMB_UP_TURN
        const val CLIMB_RIGHT = 14.2f
        const val CLIMB_BEHIND = 8.8f
        const val CLIMB_TURN = -90f
    }
}

/** Along the bench from one end to the other ([to]): the clients slide them across. */
internal class Slide(private val to: Int) : Step() {
    private var t = 0
    override fun start(h: Hand) = h.seatOn(to)
    override fun tick(h: Hand): Boolean = ++t >= SLIDE

    companion object {
        const val SLIDE = 8
    }
}

/**
 * A short walk played by hand along [points] (round the wagon, to the horse's head): at [speed] blocks a tick, on
 * the ground as it lies, turning as they go; facing [face] at the end, if given. Their legs walk as they move.
 */
internal class Stroll(private val points: List<Vec3>, private val speed: Double, private val face: Float? = null) {
    private var i = 0
    private var yaw = Float.NaN

    /** One step along; true while there's more to go. */
    fun tick(h: Hand): Boolean {
        val p = h.p
        if (yaw.isNaN()) {
            if (!h.kinematic) h.hold(true)
            yaw = p.yRot
        }
        var left = speed
        var x = p.x
        var z = p.z
        while (left > 1e-6 && i < points.size) {
            val target = points[i]
            val dx = target.x - x
            val dz = target.z - z
            val d = hypot(dx, dz)
            if (d <= left) {
                x = target.x
                z = target.z
                left -= d
                i++
            } else {
                x += dx / d * left
                z += dz / d * left
                left = 0.0
            }
        }
        val dx = x - p.x
        val dz = z - p.z
        if (dx * dx + dz * dz > 1e-6) {
            val want = Math.toDegrees(atan2(-dx, dz)).toFloat()
            yaw += Mth.clamp(Mth.wrapDegrees(want - yaw), -TURN, TURN)
        } else if (face != null && i >= points.size) {
            yaw += Mth.clamp(Mth.wrapDegrees(face - yaw), -TURN, TURN)
        }
        val y = Ground.stand(h.show.level, x, z, p.y + 0.5)
        val ny = if (abs(y - p.y) > 1.2) y else p.y + Mth.clamp(y - p.y, -0.3, 0.25)
        p.setPos(x, ny, z)
        p.face(yaw)
        val done = i >= points.size && (face == null || abs(Mth.wrapDegrees(face - yaw)) < 2f)
        return !done
    }

    companion object {
        /** Degrees a tick they turn as they walk. */
        const val TURN = 18f
    }
}

/** A [Stroll] as a step: along [points] (worked out as it starts) at [speed], facing [face] at the end. */
internal class Go(private val points: (Hand) -> List<Vec3>, private val speed: Double = 0.09, private val face: ((Hand) -> Float?)? = null) : Step() {
    private var s: Stroll? = null
    override fun start(h: Hand) {
        s = Stroll(points(h), speed, face?.invoke(h))
    }

    override fun tick(h: Hand): Boolean = s?.tick(h) != true
}

/** A walk on their own legs to [target], at [speed]; [near] enough is there. Gives up after [most] ticks. */
internal class Walk(private val target: () -> BlockPos?, private val speed: Double, private val near: Double = 1.5, private val most: Int = 1200) : Step() {
    private var t = 0
    override fun start(h: Hand) {
        if (h.kinematic) h.hold(false)
        val to = target() ?: return
        h.walker.go(to, speed, near)
    }

    override fun tick(h: Hand): Boolean {
        if (target() == null) return true
        return h.walker.tick() != Walker.State.WALKING || ++t >= most
    }

    override fun stop(h: Hand) = h.walker.stop()
}

// ---- Back at the gate ----

/**
 * A waylaid wagon's driver back at their capital's gate (design-road.md section 1): sitting on a step by it if
 * there's one, else standing, shaken, catching their breath. Speak to them and they tell it in person ([GateTalk]):
 * what was taken, where the band's camp is (marking it on your map), and what it means for the next wagon.
 */
internal class GateShow(val level: ServerLevel, val driver: Caravans.Driver, val p: Townsperson) : Director {
    private var seat: BlockPos? = null
    private var t = 0
    private var greeted = HashSet<java.util.UUID>()
    private var leaving = 0

    init {
        p.director = this
        p.navigation.stop()
        p.noPhysics = false
        p.isNoGravity = false
        p.act(Activity.CATCH_BREATH)
    }

    /** Each tick, from Bodies; false once they've gone. */
    fun step(): Boolean {
        if (p.isRemoved || !p.isAlive) return false
        t++
        // The records give their time at the gate in game ticks
        val now = level.gameTime
        val gone = t % 20 == 0 && Caravans.drivers(level, driver.at, 4.0).none { it.caravan == driver.caravan }
        if (gone || now > driver.until) return leave()
        val near = level.getNearestPlayer(p, 24.0) as? ServerPlayer
        if (seat == null && t > 200 && t % 100 == 0) {
            // A step to sit on by the gate, if there's one
            io.github.vaspyyy.guildmark.townsfolk.Places.seat(level, driver.at, 4, p)?.let { s ->
                if (io.github.vaspyyy.guildmark.townsfolk.Claims.claim(level, s, p, 24000)) {
                    seat = s
                    p.sitOn(s)
                    p.act(Activity.SIT)
                }
            }
        }
        if (near != null && near.distanceToSqr(p) < 36.0 && greeted.add(near.uuid) && !Talking.busy(p)) {
            p.lookAt(near)
            if (seat == null) p.act(Activity.RECOUNT)
            p.say("driver_took", force = true)
        }
        if (near != null && near.distanceToSqr(p) < 100.0) p.lookAt(near)
        if (seat == null && p.activity == Activity.RECOUNT && p.actTicks > 160) p.act(Activity.CATCH_BREATH)
        if (seat == null && t % 600 == 300 && near == null) p.act(Activity.GRUMBLE)
        if (seat == null && p.activity == Activity.GRUMBLE && p.actDone) p.act(Activity.CATCH_BREATH)
        return true
    }

    /** Their time at the gate is up: off into the town, gone once nobody sees. */
    private fun leave(): Boolean {
        if (leaving == 0) {
            seat?.let {
                p.standUp()
                io.github.vaspyyy.guildmark.townsfolk.Claims.release(level, it, p)
            }
            seat = null
            p.act(Activity.NONE)
            val away = p.position().add((p.random.nextDouble() - 0.5) * 30.0, 0.0, (p.random.nextDouble() - 0.5) * 30.0)
            p.navigation.moveTo(away.x, away.y, away.z, 0.5)
        }
        leaving++
        val seen = level.getNearestPlayer(p, 32.0) != null
        if (!seen || leaving > 600) {
            p.discard()
            return false
        }
        return true
    }

    fun dismiss() {
        seat?.let { io.github.vaspyyy.guildmark.townsfolk.Claims.release(level, it, p) }
        p.discard()
    }

    override fun tick(p: Townsperson) {}

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        if (player.isShiftKeyDown) return false
        p.lookAt(player)
        if (io.github.vaspyyy.guildmark.townsfolk.talk.Talk.READY) {
            io.github.vaspyyy.guildmark.townsfolk.talk.Talk.script(level, player, p, GateTalk(driver))
        } else {
            p.say("driver_took", force = true)
            Caravans.told(player, driver.caravan)
        }
        return true
    }

    /** Helpers for the talk's state. */
    private object Talking {
        fun busy(p: Townsperson): Boolean = io.github.vaspyyy.guildmark.townsfolk.talk.Talk.busy(p)
    }
}

/** A waylaid wagon's driver telling it at the gate: what they took, where the camp is, and what comes of it. */
internal class GateTalk(private val driver: Caravans.Driver) : io.github.vaspyyy.guildmark.townsfolk.talk.Script {
    override fun opener(ask: io.github.vaspyyy.guildmark.townsfolk.talk.Ask): io.github.vaspyyy.guildmark.townsfolk.talk.Utterance =
        io.github.vaspyyy.guildmark.townsfolk.talk.Utterance.of("driver_took", lost())

    override fun choices(ask: io.github.vaspyyy.guildmark.townsfolk.talk.Ask): List<io.github.vaspyyy.guildmark.townsfolk.talk.Choice> = listOf(
        choice("caravan.happened"), choice("caravan.camp"), choice("caravan.next"), choice("bye"),
    )

    override fun answer(ask: io.github.vaspyyy.guildmark.townsfolk.talk.Ask, choice: String): Pair<io.github.vaspyyy.guildmark.townsfolk.talk.Utterance, Boolean> {
        val u = io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
        return when (choice) {
            "caravan.happened" -> u.of("driver_ran", lost()) to false
            "caravan.camp" -> {
                val player = ask.player
                val first = player != null && Caravans.told(player, driver.caravan)
                val detail = when {
                    driver.camp == null -> "talk.detail.caravan.camp.gone"
                    first -> "talk.detail.caravan.camp.marked"
                    else -> "talk.detail.caravan.camp.known"
                }
                u.of(if (driver.camp == null) "driver_ran" else "driver_mark", detail) to false
            }
            "caravan.next" -> u.of("driver_next", "talk.detail.caravan.next") to false
            else -> u.of("driver_bye") to true
        }
    }

    private fun choice(what: String) = io.github.vaspyyy.guildmark.townsfolk.talk.Choice(what, Component.translatable("talk.guildmark.choice.$what"))

    /** What they lost, in grey after the line: the two biggest of it by name. */
    private fun lost(): String {
        val goods = driver.lost.sortedByDescending { it.second }
        return when {
            goods.isEmpty() -> "talk.detail.caravan.lost.none"
            goods.size == 1 -> io.github.vaspyyy.guildmark.townsfolk.Speech.of("talk.detail.caravan.lost.one", goods[0].first.key + ".many")
            else -> io.github.vaspyyy.guildmark.townsfolk.Speech.of("talk.detail.caravan.lost.two", goods[0].first.key + ".many", goods[1].first.key + ".many")
        }
    }
}
