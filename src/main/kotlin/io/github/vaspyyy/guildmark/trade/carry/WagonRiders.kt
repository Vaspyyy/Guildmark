package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.caravan.ClimbUp
import io.github.vaspyyy.guildmark.caravan.Ground
import io.github.vaspyyy.guildmark.caravan.JumpDown
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.hunt.Company
import io.github.vaspyyy.guildmark.trade.hunt.GuardDirector
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The owner's hired guards on their led wagon (living-world/trader/design.md, Part 3 and Part 4, "Company"; build part D3): while the wagon is
 * led, the first guard walks up beside the bench, climbs onto its right-hand end and rides there, the second onto the let-down tailboard, the
 * others walking behind as E has them. They climb and get down by the caravans' own clips ([ClimbUp], [JumpDown]: CrewMoves' numbers), and
 * ride as the caravans' crew do (the client draws crew place 1 on the bench and 2 on the tailboard, [Wagon.crew] and [Wagon.seat]).
 *
 * They get down at once at a Hunt's call (the guard [Company.State.HOLD]), with someone to fight, or as they leave, and a couple of seconds
 * after the wagon is let go of or parked; then they walk to their places as [GuardDirector] has them. A guard that cannot reach their climb
 * (a wall beside the wagon) walks as before for a while and tries again. Nothing here is saved: a guard on a seat when the bodies go, the server
 * stops or their trader goes is put down on their feet beside the wagon.
 *
 * [GuardDirector] asks [ride] first each tick ([GuardDirector.seats], the one hook there); [sit] places each rider on their seat after the wagon
 * has moved ([LedShow]'s tick).
 */
internal object WagonRiders : GuardDirector.Seats {
    /** The crew places guards take on a led wagon (guard slot 0 and 1), and the seat each sits in: the bench's right-hand end, the tailboard. */
    private val PLACES = intArrayOf(1, 2)
    private val SEATS = intArrayOf(Wagon.DRIVER, Wagon.TAIL)

    /** How near the climb's start a guard must be to climb; how long they walk up before they give it up, and how long before they try again. */
    private const val CLIMB_AT = 1.1
    private const val WALK_MOST = 200
    private const val RETRY = 200L

    /** Ticks a guard keeps their seat once the wagon is let go of (it may be led on again at once). */
    private const val LINGER = 40

    /** Ticks the director may go without asking before a rider is put down (it has let go of them). */
    private const val ASKED = 3L

    private enum class Stage { WALK, CLIMB, SIT, JUMP }

    private class Rider(val g: Company.Guard, val sh: LedShow.Shown, val place: Int, val seat: Int) {
        var stage = Stage.WALK
        var age = 0
        var asked = 0L
        var unwanted = 0
    }

    /** Riders (and guards on their way to climb) by their contract's id, and when a guard who could not climb tries again. */
    private val riders = HashMap<Long, Rider>()
    private val rested = HashMap<Long, Long>()

    /** True while [g] is on a led wagon's seat, climbing onto it, getting down or walking up to climb: the director does nothing else with them this tick. */
    override fun ride(g: Company.Guard, owner: ServerPlayer, now: Long): Boolean {
        val r = riders[g.id]
        if (r != null) {
            r.asked = now
            if (r.g !== g || r.sh.wagon.isRemoved || LedShow.of(g.owner) !== r.sh || g.p.level() !== r.sh.level) {
                // The bodies have gone (or were made again): on their feet where they are
                off(r)
                return false
            }
            val want = wanted(g, owner, r.sh)
            r.unwanted = if (want) 0 else r.unwanted + 1
            when (r.stage) {
                Stage.WALK -> {
                    if (!want || ++r.age > WALK_MOST) {
                        riders.remove(g.id)
                        if (want) rested[g.id] = now + RETRY
                        g.stride.halt()
                        return false
                    }
                    walk(r, owner, now)
                }
                Stage.CLIMB -> {
                    if (++r.age >= Activity.CLIMB_UP.ticks || g.p.activity != Activity.CLIMB_UP) {
                        r.stage = Stage.SIT
                        r.age = 0
                        g.p.act(Activity.RIDE_TAIL)
                    }
                    if (!want && (urgent(g) || r.unwanted > LINGER)) jump(r)
                }
                Stage.SIT -> {
                    if (g.p.activity != Activity.RIDE_TAIL) g.p.act(Activity.RIDE_TAIL)
                    if (!want && (urgent(g) || r.unwanted > LINGER)) jump(r)
                }
                Stage.JUMP -> if (++r.age >= Activity.JUMP_DOWN.ticks || g.p.activity != Activity.JUMP_DOWN) {
                    land(r)
                    return false
                }
            }
            return true
        }
        // Not riding: the first two guards go to climb up once the wagon is led, a seat is free and nothing else wants them
        if (g.state != Company.State.FOLLOW) return false
        if ((rested[g.id] ?: Long.MIN_VALUE) > now) return false
        rested.remove(g.id)
        val k = g.slot
        if (k !in PLACES.indices) return false
        val sh = LedShow.of(g.owner) ?: return false
        if (!wanted(g, owner, sh)) return false
        val place = PLACES[k]
        val w = sh.wagon
        if (w.crew(place) >= 0 || w.seat(place) != Wagon.OFF) return false
        if (riders.values.any { it.sh === sh && it.place == place }) return false
        val fresh = Rider(g, sh, place, SEATS[k])
        fresh.asked = now
        riders[g.id] = fresh
        walk(fresh, owner, now)
        return true
    }

    /** Whether [g] should be on [sh]: it is led by their trader, its horse in the shafts, no scene on it, and nothing else wants them. */
    private fun wanted(g: Company.Guard, owner: ServerPlayer, sh: LedShow.Shown): Boolean =
        g.state == Company.State.FOLLOW && TradeConfig.wagon() && sh.led && sh.holder == g.owner && sh.harnessed && sh.scene == null &&
            g.p.target == null && g.sat == null && g.p.seat == null && g.p.isAlive && owner.level() === sh.level

    /** Down at once: a Hunt's call, someone to fight, their days up. */
    private fun urgent(g: Company.Guard): Boolean = g.state != Company.State.FOLLOW || g.p.target != null

    /** On the way to where the climb starts (beside and behind the seat); there, up they go. */
    private fun walk(r: Rider, owner: ServerPlayer, now: Long) {
        val g = r.g
        val p = g.p
        val (at, yaw) = beside(r, ClimbUp.CLIMB_RIGHT, -ClimbUp.CLIMB_BEHIND, ClimbUp.CLIMB_TURN)
        val d = hypot(at.x - p.x, at.z - p.z)
        if (d <= CLIMB_AT && abs(at.y - p.y) < 1.2) {
            g.stride.halt()
            g.stillSince = -1L
            hold(p, true)
            p.face(yaw)
            r.sh.wagon.crew(r.place, p.id)
            r.sh.wagon.seat(r.place, r.seat)
            seatOn(r)
            p.act(Activity.CLIMB_UP)
            r.stage = Stage.CLIMB
            r.age = 0
            return
        }
        if (!p.activity.overlay) p.act(Activity.NONE)
        val pace = Company.paceOf(g.owner)?.speed ?: 0.0
        g.stride.go(at, (pace * 1.3 + 1.0 + d * 0.6).coerceIn(2.4, 7.0), now, every = 5, moved = 0.8)
    }

    private fun jump(r: Rider) {
        r.stage = Stage.JUMP
        r.age = 0
        r.g.p.act(Activity.JUMP_DOWN)
    }

    /** Each tick after [sh] has moved: its riders placed on their seats as it now stands, and any the director has let go of put down. */
    fun sit(sh: LedShow.Shown, now: Long) {
        if (riders.isEmpty()) return
        for (r in riders.values.toList()) {
            if (r.sh !== sh) continue
            val p = r.g.p
            if (p.isRemoved || !p.isAlive) {
                free(r)
                continue
            }
            if (now - r.asked > ASKED) {
                off(r)
                continue
            }
            if (r.stage != Stage.WALK) seatOn(r)
        }
    }

    /** Every rider of [sh] put down on their feet beside it at once (its bodies are going). */
    fun off(sh: LedShow.Shown) {
        if (riders.isEmpty()) return
        for (r in riders.values.toList()) if (r.sh === sh) off(r)
    }

    /** The server stopping: nobody rides. */
    fun clear() {
        riders.clear()
        rested.clear()
    }

    /** [r] on their feet: beside their seat if they had one (no clip), else where they are; their walk forgotten. */
    private fun off(r: Rider) {
        if (r.stage == Stage.WALK) {
            riders.remove(r.g.id)
            if (!r.g.p.isRemoved) r.g.stride.halt()
            return
        }
        land(r)
    }

    /** Down off the seat where the jump lands (the other side, or by their trader, if that is in a wall), on their own legs again. */
    private fun land(r: Rider) {
        val p = r.g.p
        val (at, yaw) = landing(r)
        free(r)
        if (p.isRemoved) return
        p.setPos(at.x, at.y, at.z)
        p.face(yaw)
        hold(p, false)
        p.resetFallDistance()
        if (p.activity == Activity.JUMP_DOWN || p.activity == Activity.RIDE_TAIL || p.activity == Activity.CLIMB_UP) p.act(Activity.NONE)
        r.g.stillSince = -1L
        r.g.lastX = p.x
        r.g.lastZ = p.z
    }

    /** [r]'s seat let go of, and [r] forgotten. */
    private fun free(r: Rider) {
        riders.remove(r.g.id)
        val w = r.sh.wagon
        if (r.stage != Stage.WALK && w.crew(r.place) == r.g.p.id) {
            w.crew(r.place, -1)
            w.seat(r.place, Wagon.OFF)
        }
    }

    private fun landing(r: Rider): Pair<Vec3, Float> {
        val level = r.sh.level
        val tries = listOf(
            beside(r, JumpDown.JUMP_RIGHT, JumpDown.JUMP_AHEAD, JumpDown.JUMP_TURN),
            beside(r, -JumpDown.JUMP_RIGHT, JumpDown.JUMP_AHEAD, -JumpDown.JUMP_TURN),
        )
        for ((at, yaw) in tries) if (!Ground.solid(level, at.x, at.y + 0.3, at.z) && !Ground.solid(level, at.x, at.y + 1.3, at.z)) return at to yaw
        // Nowhere clear beside the wagon: by their trader, where nothing is in the way
        val owner = level.server.playerList.getPlayer(r.g.owner)
        if (owner != null && owner.level() === level) return owner.position() to owner.yRot
        return tries[0]
    }

    /** Placed on their seat as the wagon now stands (the caravans' crew's own way, Crew.kt's Hand.sit). */
    private fun seatOn(r: Rider) {
        val p = r.g.p
        val (at, yaw) = r.sh.wagon.seatAt(r.seat)
        p.setPos(at.x, at.y, at.z)
        p.deltaMovement = Vec3.ZERO
        p.yRot = yaw
        p.yBodyRot = yaw
        p.yHeadRot = yaw + Mth.clamp(Mth.wrapDegrees(p.yHeadRot - yaw), -70f, 70f)
    }

    /** Moved by hand, or let go to move on their own (Crew.kt's Hand.hold). */
    private fun hold(p: Townsperson, byHand: Boolean) {
        p.noPhysics = byHand
        p.isNoGravity = byHand
        p.deltaMovement = Vec3.ZERO
        if (byHand) p.navigation.stop()
    }

    /** Where one stands on the road beside [r]'s seat, [right] px to its right and [ahead] px ahead (the clips' numbers), facing [turn] from the seat (Crew.kt's Hand.beside). */
    private fun beside(r: Rider, right: Float, ahead: Float, turn: Float): Pair<Vec3, Float> {
        val wagon = r.sh.wagon
        val p = r.g.p
        val s = Wagon.seatOf(r.seat) ?: return p.position() to p.yRot
        val a = Math.toRadians(s.yaw.toDouble())
        val ax = sin(a).toFloat()
        val az = -cos(a).toFloat()
        val rx = -cos(a).toFloat()
        val rz = -sin(a).toFloat()
        val point = wagon.point(s.x + rx * right + ax * ahead, 0f, s.z + rz * right + az * ahead)
        val y = Ground.stand(r.sh.level, point.x, point.z, wagon.y + 0.5)
        return Vec3(point.x, y, point.z) to Mth.wrapDegrees(wagon.yRot + s.yaw + turn)
    }
}
