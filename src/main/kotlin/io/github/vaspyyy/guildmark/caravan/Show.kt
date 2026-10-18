package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Wayside
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One caravan's bodies on the road while a player is near (design-road.md section 1): its wagon and horse placed
 * along the leg like a train at the record's distance, its crew on the bench, the tailboard and beside it, the
 * townsfolk stepping aside ahead of it; the strike braced for and handed to [Waylay]; the stand and the standing
 * hour handed to [Unloading]; the turn in the square and the drive back out. The record ([Caravans]) is the truth:
 * the bodies follow it, and when they're held up they say so ([Caravans.waited]), so record and body never part.
 *
 * Distances: [s] is how far along [path] the horse is; the record's distance is the wagon's middle (halfway between
 * its axles), [LEAD] behind the horse, so a strike stops it with its middle at the strike point.
 */
internal class Show private constructor(val level: ServerLevel, first: Caravans.View, val leg: CaravanRoutes.Leg) {
    enum class Mode { WAIT, ROLL, BRACE, FIGHT, STAND, LEAVE, OFF, RUN }

    val id: Long = first.id
    var view: Caravans.View = first
        private set
    val way = Way(first.leg)
    var mode = Mode.ROLL
        private set

    var wagon: Wagon? = null
        private set
    var horse: DraughtHorse? = null
        private set
    val hands = ArrayList<Hand>()

    /** Whether the horse is still between the shafts (not cut loose, not lying dead), so it's placed with the wagon. */
    var harnessed = true

    // ---- Where it is ----

    /** The way in, swerved toward the verge for a strike. */
    var swerve: Swerve? = null
    private val forward = Path { s -> swerved(way, swerve, s) }
    var path: Path = forward
        private set

    /** How far along [path] the horse is, how fast it goes (blocks a tick), and how far along it the back axle is. */
    var s = 0.0
        private set
    var speed = 0.0
        private set
    private var rearS = 0.0

    /** Where it stops at its stand, and turns round to drive back out. */
    var stop: Stop? = null
        private set
    private var out: Departure? = null

    // ---- How it's going ----

    private var t = 0
    private var lastClock = Long.MIN_VALUE
    private var rate = 1.0
    private var clockStep = 0L

    /** On the way out: how far along [path] the record has the horse, by the bodies' own count (its distance stands still while the wagon turns). */
    private var expect = 0.0
    private var blocked = 0
    private var blockedBy: Entity? = null
    private var cleared = false
    private var braking = false
    private var straining = 0
    private var urgeAt = 0
    private var held = 0
    private var stopped = 0
    private var unseen = 0
    private var reinsAt = -1
    private var reinsTied = false
    private val greeted = HashSet<UUID>()
    private var quietSaid = false
    private var escortMet = false
    private var hauled = false
    private var reared = false

    /** Its strike is behind it (beaten off, or never came): the records go on showing where it fell, but it's done with. */
    private var passed = false
    private var leftAt = 0.0

    var waylay: Waylay? = null
        private set
    var unloading: Unloading? = null
        private set

    /** While the show itself is telling the records how a strike came out, so it doesn't hear its own news back. */
    var resolving = false

    /** The verge a strike swerves it to: 1 the road's right, -1 its left. */
    var verge = 1.0
        private set

    val guards: Int get() = (hands.size - 1).coerceAtLeast(0)
    val driver: Hand? get() = hands.firstOrNull { it.index == 0 }

    // ---- Each tick ----

    /** One tick, after everyone has moved: false once the show is over (its bodies gone). */
    fun step(): Boolean {
        t++
        if (lost()) return false
        val now = Caravans.view(id)
        if (now != null) {
            view = now
            Caravans.shown(id)
        } else if (mode != Mode.OFF && mode != Mode.RUN) {
            // The record's over: a wagon leaving drives on out, anything else goes
            if (mode == Mode.LEAVE) mode = Mode.OFF else return false
        }
        clock()
        when (mode) {
            Mode.WAIT -> waiting()
            Mode.ROLL, Mode.BRACE -> rolling()
            Mode.FIGHT -> place(false)
            Mode.STAND -> {
                place(false)
                val u = unloading
                if (u == null || !u.tick()) leave()
            }
            Mode.LEAVE, Mode.OFF -> if (!leaving()) return false
            Mode.RUN -> if (waylay == null) return false
        }
        // The fight, and after it whatever's left of it (the band running off, the driver for the gate)
        val w = waylay
        if (w != null && !w.tick()) {
            waylay = null
            if (mode == Mode.FIGHT || mode == Mode.RUN) return false
        }
        lantern()
        walkers()
        for (h in hands.toList()) if (!h.p.isRemoved) h.tick()
        reins()
        return true
    }

    /** Whether its bodies were taken from the world under it (a chunk unloaded, a /kill): then it's over. */
    private fun lost(): Boolean {
        val w = wagon
        if (w != null && w.isRemoved) return true
        val h = horse
        if (h != null && h.isRemoved && harnessed) return true
        val d = driver
        if (d != null && (d.p.isRemoved || !d.p.isAlive) && mode != Mode.FIGHT && mode != Mode.RUN) return true
        return false
    }

    /** How fast the record's clock goes (0 stopped, 1 as the game's): the wagon goes no faster than it. */
    private fun clock() {
        val c = level.overworldClockTime
        if (lastClock != Long.MIN_VALUE) {
            val d = c - lastClock
            val now = if (d <= 0L) 0.0 else 1.0
            rate += (now - rate) * 0.25
            clockStep = d.coerceIn(0L, 20L)
        }
        lastClock = c
    }

    // ---- Waiting at the start for its escort ----

    private fun waiting() {
        place(false)
        speedTo(0.0)
        val h = horse
        if (h != null && harnessed) {
            // Grazing the verge and looking up, by turns
            if (t % 400 == 0) h.move(if (h.move == DraughtHorse.Move.GRAZE) DraughtHorse.Move.IDLE else DraughtHorse.Move.GRAZE)
        }
        val escort = view.escort
        if (escort != null && !escortMet) {
            escortMet = true
            driver?.let { d ->
                level.getPlayerByUUID(escort)?.let { d.p.lookAt(it) }
                d.then(Say("driver_escort_meet", 32.0))
            }
            hands.firstOrNull { it.index == 1 }?.then(Wait(50))?.then(Say("guard_glad", 32.0))
        }
        greet()
        if (view.state == Caravans.State.ROLLING) {
            mode = Mode.ROLL
            setOff()
        } else if (view.state != Caravans.State.WAITING) {
            // Called off, or past it: the next look at the records sorts it out
            mode = Mode.ROLL
        }
    }

    /** Off it goes from a stand: a click and a flick of the reins, the horse leaning into the collar. */
    private fun setOff(line: Boolean = true) {
        val d = driver ?: return
        if (d.seat == Wagon.DRIVER && d.idle) {
            d.then(Play(Activity.DRIVE_URGE)).then { it.p.act(Activity.DRIVE) }
            if (line) d.then(Say("driver_walk_on", 24.0))
        }
        straining = STRAIN_START
        horse?.move(DraughtHorse.Move.WALK)
    }

    // ---- On the road ----

    private fun rolling() {
        val v = view
        if (v.state == Caravans.State.STANDING && stop == null) stop = findStop()
        val R = v.now
        val strike = v.strike?.takeIf { !passed }
        // The strike coming up: brace for it a little way short, swerving for the verge
        if (mode == Mode.ROLL && strike != null && middle() >= strike - BRACE_AT && swerve == null && Caravans.strikes(id)) brace(strike, fresh = true)
        // Past a strike that won't come (its band gone, or resting): the records hear so at once
        if (mode == Mode.ROLL && strike != null && middle() >= strike - 0.05 && v.state == Caravans.State.ROLLING) {
            val came = Caravans.struck(id)
            if (came != null) {
                if (swerve == null) brace(strike, fresh = false)
                fight(came)
                return
            }
            passed = true
        }
        if (strike == null && stop == null && R > way.length - STAND_LOOK) stop = findStop()
        var target = R + LEAD
        var halt = Double.MAX_VALUE
        if (mode == Mode.BRACE && strike != null) halt = strike + LEAD
        stop?.let { halt = min(halt, it.s) }
        target = min(target, halt)
        val want = drive(target, halt)
        move(want, halt)
        place(false)
        sync(target)
        afterMove()
        if (mode == Mode.BRACE && strike != null) bracing(strike)
        // Pulled up at its stand: in, and unloading
        val st = stop
        if (st != null && s >= st.s - 0.02 && speed < 0.004) {
            when (v.state) {
                Caravans.State.ROLLING -> Caravans.reached(id)
                Caravans.State.STANDING, Caravans.State.LEAVING -> stand()
                else -> {}
            }
        }
    }

    /** The middle of the wagon, along the way in. */
    private fun middle(): Double = s - LEAD

    /** How fast it would go to be at [target] (the record's place, or a stop at [halt]), held up by what's in the road. */
    private fun drive(target: Double, halt: Double): Double {
        var want = (Caravans.PACE * min(rate, 1.0) + (target - s) * GAIN).coerceIn(0.0, Caravans.PACE * FAST)
        if (halt < Double.MAX_VALUE) want = min(want, sqrt(2.0 * STOP_DECEL * max(0.0, halt - s)))
        if (want > 0.0 && obstructed()) {
            want = 0.0
            blocked++
            whenBlocked()
        } else if (want > 0.0) {
            blocked = 0
            blockedBy = null
            cleared = false
        }
        return want
    }

    /** Speeds up or slows to [want], and goes on that far (to [halt] at most). */
    private fun move(want: Double, halt: Double) {
        val was = speed
        speedTo(want)
        s += speed
        if (s > halt) s = halt
        // Off from a standstill: the horse leans into it
        if (was < 0.004 && speed >= 0.004) {
            straining = max(straining, STRAIN_START)
            if (t - urgeAt > 100) {
                urgeAt = t
                driver?.takeIf { it.seat == Wagon.DRIVER && it.idle }?.then(Play(Activity.DRIVE_URGE))?.then { it.p.act(Activity.DRIVE) }
            }
        }
    }

    private fun speedTo(want: Double) {
        speed = if (want > speed) min(want, speed + ACCEL) else max(want, speed - DECEL)
        if (speed < 1e-4) speed = 0.0
    }

    /** Keeps the record with the bodies: held up, the record waits too; far behind it (the clock jumped), they catch up at once. */
    private fun sync(target: Double) {
        val behind = target - s
        if (behind > SNAP) {
            s = target
            place(true)
            return
        }
        if (behind > SYNC && rate > 0.5) Caravans.waited(id, floor(behind / Caravans.PACE).toInt())
    }

    /** After it has moved: the horse's gait and strain, the brake, the townsfolk stepping aside, the crew's looks and words. */
    private fun afterMove() {
        val w = wagon ?: return
        val h = horse
        if (h != null && harnessed && h.move.loop) {
            if (speed > 0.01) h.move(DraughtHorse.Move.WALK)
            else if (speed == 0.0 && ++stopped > 10 && h.move == DraughtHorse.Move.WALK) h.move(DraughtHorse.Move.IDLE)
            if (speed > 0.0) stopped = 0
        }
        // Uphill the horse leans into the collar; downhill the driver works the brake
        val pitch = w.pitch
        if (straining > 0) straining--
        val climbing = pitch > CLIMB_PITCH && speed > 0.01
        h?.flag(DraughtHorse.FLAG_STRAIN, harnessed && (straining > 0 || climbing))
        if (climbing && t - urgeAt > URGE_EVERY) {
            urgeAt = t
            driver?.takeIf { it.seat == Wagon.DRIVER && it.idle }?.then(Play(Activity.DRIVE_URGE))?.then { it.p.act(Activity.DRIVE) }
        }
        val downhill = pitch < -BRAKE_PITCH && speed > 0.01
        if (downhill && !braking) {
            braking = true
            w.flag(Wagon.FLAG_BRAKE, true)
            driver?.takeIf { it.seat == Wagon.DRIVER && it.idle }?.then(Play(Activity.DRIVE_BRAKE))?.then { it.p.act(Activity.DRIVE) }
        } else if (braking && pitch > -BRAKE_OFF_PITCH && mode != Mode.BRACE) {
            braking = false
            w.flag(Wagon.FLAG_BRAKE, false)
        }
        if (speed > 0.01 && t % WAYSIDE_EVERY == 0) wayside()
        looks()
        greet()
        quiet()
    }

    /** The townsfolk on its way step aside, the grown-ups waving to the driver now and then. */
    private fun wayside() {
        val pts = ArrayList<Vec3>()
        var p = rearS - WAY_BACK
        val end = s + WAY_AHEAD
        while (p <= end && pts.size < 64) {
            pts.add(path.at(p))
            p += 1.0
        }
        val head = horse?.let { headOf(it) } ?: path.at(s + 1.2)
        Wayside.clear(level, pts, WAY_WIDTH, head)
    }

    /** Where [h]'s head is, about. */
    private fun headOf(h: DraughtHorse): Vec3 {
        val r = Math.toRadians(h.yRot.toDouble())
        return Vec3(h.x - sin(r) * 1.6, h.y + 2.0, h.z + cos(r) * 1.6)
    }

    // ---- Held up ----

    /**
     * Something in the road: a block at the horse's chest or head a stride ahead (a step it can climb doesn't count),
     * or a player or a creature standing there. Its crew don't count, nor the horse itself.
     */
    private fun obstructed(): Boolean {
        val h = horse ?: return false
        val (hx, hz) = heading()
        val px = h.x + hx * PROBE
        val pz = h.z + hz * PROBE
        val g = Ground.stand(level, px, pz, h.y + 0.5)
        if (Ground.solid(level, px, g + 1.6, pz) || Ground.solid(level, px, g + 2.4, pz)) {
            blockedBy = null
            return true
        }
        val box = AABB(px - BLOCK_REACH, g - 0.5, pz - BLOCK_REACH, px + BLOCK_REACH, g + 2.5, pz + BLOCK_REACH)
        val who = level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator && !ours(it) }.firstOrNull()
        blockedBy = who
        return who != null
    }

    /** Whether [e] is one of this caravan's own (its crew, or one of the band in its fight). */
    private fun ours(e: Entity): Boolean = e is Townsperson && hands.any { it.p === e } || waylay?.owns(e) == true

    private fun whenBlocked() {
        // The driver shouts at whoever's in the way; with nobody near for a while it goes round, and in the end, once nobody looks
        val who = blockedBy
        if (who != null && !cleared && blocked > 10) {
            cleared = true
            driver?.takeIf { it.seat == Wagon.DRIVER }?.let { d ->
                d.p.lookAt(who)
                if (d.idle) d.then(Play(Activity.DRIVE_BRAKE)).then { it.p.act(Activity.DRIVE) }
                d.p.say("driver_clear", force = true, hearing = 24.0)
            }
        }
        val near = level.players().any { !it.isSpectator && flat(it.position(), wagonAt()) < HOP_CLEAR }
        if (blocked >= HOP_AFTER && !near || blocked >= HOP_ANYWAY && !watched(64.0)) hop()
    }

    /** Round what's in the way, out of everyone's sight: a few blocks on. */
    private fun hop() {
        blocked = 0
        s += HOP
        place(true)
    }

    /** Whether a player within [range] has the wagon in view. */
    fun watched(range: Double): Boolean {
        val at = wagonAt().add(0.0, 1.2, 0.0)
        return level.players().any { player ->
            if (player.isSpectator) return@any false
            val eye = player.eyePosition
            val to = at.subtract(eye)
            val d = to.length()
            d < range && (d < 6.0 || player.lookAngle.dot(to.scale(1.0 / d)) > 0.5)
        }
    }

    /** The wagon's middle in the world (or where its show is, with no wagon). */
    fun wagonAt(): Vec3 = wagon?.point(0f, 0f, -16f) ?: hands.firstOrNull()?.p?.position() ?: way.at(middle())

    // ---- Bracing for a strike ----

    /**
     * The band's whistle from off the road: the wagon swerves for the verge its wreck would lie on and pulls up with
     * its middle at the strike point; [fresh] when it's heard just now (not a show come upon part way into it).
     */
    private fun brace(strike: Double, fresh: Boolean) {
        mode = Mode.BRACE
        verge = vergeSide(strike)
        swerve = Swerve.brace(strike, verge)
        hauled = false
        reared = false
        if (!fresh) return
        // Off the road, toward the camp: two shrill notes
        val (rx, rz) = way.right(strike)
        val side = bandSide(strike)
        val at = way.at(strike).add(rx * side * 28.0, 2.0, rz * side * 28.0)
        Bodies.sound(level, at, "whistle", 4.0f, 1.0f)
        for (h in hands) h.p.lookAt(at)
    }

    /** Which side of the road the wreck lies (the records' verge): 1 its right, -1 its left. */
    private fun vergeSide(strike: Double): Double {
        val (vp, _) = Caravans.verge(id) ?: return 1.0
        val p = way.at(strike)
        val (rx, rz) = way.right(strike)
        return if ((vp.x - p.x) * rx + (vp.z - p.z) * rz >= 0.0) 1.0 else -1.0
    }

    /** Which side of the road the band comes from: its camp's, if it has one, else across from the verge. */
    fun bandSide(along: Double): Double {
        val camp = Waylay.camp(level, id)
        if (camp != null) {
            val p = way.at(along)
            val (rx, rz) = way.right(along)
            return if ((camp.x + 0.5 - p.x) * rx + (camp.z + 0.5 - p.z) * rz >= 0.0) 1.0 else -1.0
        }
        return -verge
    }

    /** Pulling up for the strike: the driver hauls on the reins as it slows, the horse rears as it stops, and the band comes. */
    private fun bracing(strike: Double) {
        val w = wagon ?: return
        val toGo = strike + LEAD - s
        if (!hauled && toGo < STOP_OVER + 0.5) {
            hauled = true
            braking = true
            w.flag(Wagon.FLAG_BRAKE, true)
            driver?.takeIf { it.seat == Wagon.DRIVER }?.let { d ->
                d.clear()
                d.then(Play(Activity.DRIVE_BRAKE)).then { it.p.act(Activity.DRIVE) }
                d.p.say("driver_hold", force = true, hearing = 32.0)
            }
        }
        if (toGo <= 0.02 && speed < 0.004) {
            if (!reared) {
                reared = true
                horse?.move(DraughtHorse.Move.REAR, again = true)
            }
            if (view.state != Caravans.State.ROLLING) return
            val came = Caravans.struck(id)
            if (came == null) resume(false) else fight(came)
        }
    }

    /** The band's on them: [Waylay] has it from here. */
    private fun fight(strike: Caravans.Strike) {
        mode = Mode.FIGHT
        speed = 0.0
        waylay = Waylay(this, strike).also { it.start() }
    }

    /** On it goes after a strike beaten off (or one that never came): back onto the road and off, the record with it. */
    fun resume(afterFight: Boolean) {
        mode = Mode.ROLL
        passed = true
        swerve?.let { sw ->
            sw.b0 = s + 1.0
            sw.b1 = s + 1.0 + Swerve.RAMP
        }
        braking = false
        wagon?.flag(Wagon.FLAG_BRAKE, false)
        setOff(line = true)
        if (afterFight) straining = STRAIN_START * 2
    }

    // ---- At the stand ----

    /** Pulled up at the stand: the load comes off ([Unloading]). */
    private fun stand() {
        mode = Mode.STAND
        speed = 0.0
        braking = true
        wagon?.flag(Wagon.FLAG_BRAKE, true)
        unloading = Unloading(this).also { it.start(fresh = true) }
    }

    /** The record's leaving and the crew are aboard: round the square and back out the way it came. */
    private fun leave() {
        val st = stop ?: findStop().also { stop = it }
        unloading = null
        mode = Mode.LEAVE
        braking = false
        wagon?.flag(Wagon.FLAG_BRAKE, false)
        val dep = Departure(st)
        out = dep
        // The horse is at the stop now: the turn starts there. The record's distance can't follow it round the
        // square, so the record waits while it turns, and the two go on together from there
        path = dep
        s = 0.0
        speed = 0.0
        leftAt = 0.0
        expect = 0.0
        val turn = dep.lb + st.s + LEAD - view.now
        if (turn > 0.0) Caravans.waited(id, ceil(turn / Caravans.PACE).toInt())
        setOff(line = true)
    }

    /** Driving out: the record's distance the other way, then on past the start of the leg once its record's over. */
    private fun leaving(): Boolean {
        val dep = out ?: return false
        val st = dep.stop
        if (mode == Mode.LEAVE) {
            expect += Caravans.PACE * clockStep
            // The clock jumped on (the record's well past the turn): the bodies catch up
            val fromRecord = dep.lb + st.s + LEAD - view.now
            if (view.now < way.length - 0.5 && fromRecord > expect + 8.0) expect = fromRecord
        }
        val target = if (mode == Mode.LEAVE) expect else s + 10.0
        val want = drive(target, Double.MAX_VALUE)
        move(want, Double.MAX_VALUE)
        place(false)
        if (mode == Mode.LEAVE) {
            val behind = expect - s
            if (behind > SNAP) {
                s = expect
                place(true)
            } else if (behind > SYNC && rate > 0.5) {
                val k = floor(behind / Caravans.PACE).toInt()
                Caravans.waited(id, k)
                expect -= k * Caravans.PACE
            }
        }
        afterMove()
        if (mode == Mode.OFF) {
            // Out past the start of the leg, gone once nobody's near to see it
            val past = s - (dep.lb + st.s + LEAD)
            val near = level.players().any { !it.isSpectator && flat(it.position(), wagonAt()) < OFF_NEAR }
            if (past > 0.0 && !near || past > OFF_MOST) return false
        }
        return true
    }

    /** A record turned to leaving while the crew aren't yet aboard: it waits for them, a while at most. */
    fun holdLeaving(): Boolean {
        if (held >= HOLD_MOST) return false
        held++
        Caravans.waited(id, 1)
        return true
    }

    /** Where it pulls up at its stand: with room ahead to turn round (a bulb of the biggest loops that fit), in the last few blocks. */
    private fun findStop(): Stop {
        val end = way.length + LEAD
        for (r in BULB_SIZES) for (back in STOP_BACK) for (side in SIDES) {
            val s = end - back
            if (fits(s, side, r)) return Stop(s, side, r)
        }
        return Stop(end, 1.0, BULB_SIZES[2])
    }

    /** Whether a bulb of loops [r] round, from [s] turning first to [side], is clear of walls and steps. */
    private fun fits(s: Double, side: Double, r: Double): Boolean {
        val p = way.at(s)
        val (hx, hz) = way.heading(s)
        val lb = Train.bulbLength(r)
        var u = 0.0
        while (u <= lb) {
            val q = Train.bulb(p.x, p.z, hx, hz, side, r, u)
            for (o in ACROSS) {
                val x = q[0] - q[3] * o
                val z = q[1] + q[2] * o
                if (Ground.solid(level, x, p.y + 0.5, z) || Ground.solid(level, x, p.y + 1.5, z)) return false
            }
            u += 0.5
        }
        return true
    }

    // ---- Placing it ----

    /** Places the wagon and the horse along [path] as they stand now ([snap]: the ground taken as it is). */
    fun place(snap: Boolean) {
        val w = wagon ?: return
        val p = path
        val h = p.at(s)
        val ks = Train.back(p, s, Train.AHEAD)
        val k = p.at(ks)
        rearS = Train.back(p, ks, Train.WHEELBASE)
        val r = p.at(rearS)
        val horse = horse
        if (harnessed && horse != null) {
            w.place(level, r, k, h, r.y, k.y, snap)
            horse.place(level, h, yawOf(h.x - k.x, h.z - k.z), h.y, snap)
        } else {
            // No horse in the shafts: the front stands straight
            val dx = k.x - r.x
            val dz = k.z - r.z
            val len = hypot(dx, dz).coerceAtLeast(1e-6)
            w.place(level, r, k, Vec3(k.x + dx / len * Train.AHEAD, k.y, k.z + dz / len * Train.AHEAD), r.y, k.y, snap)
        }
    }

    /** Which way the horse heads on the ground: a unit (x, z). */
    private fun heading(): Pair<Double, Double> {
        val a = path.at(s - 0.5)
        val b = path.at(s + 0.5)
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = hypot(dx, dz)
        return if (len < 1e-6) 0.0 to 1.0 else (dx / len) to (dz / len)
    }

    /** Where a point of the wagon's own space ([px] right -x, [pz] ahead -z, px) is on the ground, standing height. */
    fun ground(px: Float, pz: Float): Vec3 {
        val w = wagon ?: return way.at(middle())
        val at = w.point(px, 0f, pz)
        return Vec3(at.x, Ground.stand(level, at.x, at.z, w.y + 0.6), at.z)
    }

    /** A yaw [turn] degrees from the wagon's (0 the way it faces, 90 its right). */
    fun yaw(turn: Float): Float = Mth.wrapDegrees((wagon?.yRot ?: 0f) + turn)

    /** Where [x], [z] of the horse's own space (px; its right -x, ahead -z) is in the world, and the horse's yaw. */
    fun horsePoint(x: Float, z: Float): Vec3 {
        val h = horse ?: return wagonAt()
        val th = Math.toRadians(180.0 - h.yRot)
        val lx = -x / 16.0
        val lz = z / 16.0
        val wx = lx * cos(th) + lz * sin(th)
        val wz = -lx * sin(th) + lz * cos(th)
        return Vec3(h.x + wx, Ground.stand(level, h.x + wx, h.z + wz, h.y + 0.6), h.z + wz)
    }

    /**
     * A way on foot for [h] from where they stand to [to] that doesn't go through the wagon: straight there on the
     * same side, else round its back (or, both up by the horse, round the horse's front).
     */
    fun around(h: Hand, to: Vec3): List<Vec3> {
        val w = wagon ?: return listOf(to)
        val frame = Wagon.frame(w.x, w.y, w.z, w.yRot, -w.xRot, w.roll)
        val from = frame.local(h.p.position())
        val there = frame.local(to)
        val behind = BEHIND_Z
        val front = FRONT_Z
        if (from.x * there.x >= 0f || from.z > behind && there.z > behind || from.z < front && there.z < front) return listOf(to)
        val back = abs(from.z - behind) + abs(there.z - behind)
        val ahead = abs(from.z - front) + abs(there.z - front)
        val z = if (horse != null && harnessed && back <= ahead || horse == null || !harnessed) behind else front
        val sideFrom = if (from.x < 0f) -ROUND_X else ROUND_X
        val sideTo = if (there.x < 0f) -ROUND_X else ROUND_X
        return listOf(ground(sideFrom, from.z.coerceIn(front, behind)), ground(sideFrom, z), ground(sideTo, z), to)
    }

    /**
     * Queues [h]'s way back up onto the wagon: round it to where their climb starts and up; the bench's guard by the
     * driver's end and along the bench (before the driver goes up), the walkers to their place beside it.
     */
    fun board(h: Hand) {
        val seat = boardingSeat(h.index)
        when (seat) {
            Wagon.DRIVER -> h.then(Until(400) { hands.none { g -> boardingSeat(g.index) == Wagon.BENCH && g.p.isAlive && g.seat != Wagon.BENCH } })
                .then(Go({ around(it, it.beside(Wagon.DRIVER, ClimbUp.CLIMB_RIGHT, -ClimbUp.CLIMB_BEHIND, ClimbUp.CLIMB_TURN).first) }, 0.11))
                .then(ClimbUp(Wagon.DRIVER, Activity.DRIVE))
            Wagon.BENCH -> h.then(Go({ around(it, it.beside(Wagon.DRIVER, ClimbUp.CLIMB_RIGHT, -ClimbUp.CLIMB_BEHIND, ClimbUp.CLIMB_TURN).first) }, 0.11))
                .then(Until(200) { driver?.seat != Wagon.DRIVER })
                .then(ClimbUp(Wagon.DRIVER, Activity.RIDE_TAIL)).then(Slide(Wagon.BENCH)).then { it.p.act(Activity.RIDE_TAIL) }
            Wagon.TAIL -> h.then(Go({ around(it, it.beside(Wagon.TAIL, ClimbUp.CLIMB_RIGHT, -ClimbUp.CLIMB_BEHIND, ClimbUp.CLIMB_TURN).first) }, 0.11))
                .then(ClimbUp(Wagon.TAIL, Activity.RIDE_TAIL))
            else -> {
                val side = if (h.index == 3) 1f else -1f
                h.then(Go({ around(it, ground(WALKER_OUT * side, WALKER_Z)) }, 0.1, { yaw(0f) })).then {
                    it.hold(true)
                    it.p.act(Activity.NONE)
                }
            }
        }
    }

    // ---- The crew ----

    /** The walking guards beside it (the third on its left, a fourth on its right), kept there as it goes. */
    private fun walkers() {
        val w = wagon ?: return
        for (h in hands) {
            if (h.index < 3 || h.seat != Wagon.OFF || !h.idle || !h.kinematic) continue
            if (mode == Mode.FIGHT || mode == Mode.STAND || mode == Mode.RUN) continue
            val side = if (h.index == 3) 1f else -1f
            val at = ground(WALKER_OUT * side, WALKER_Z)
            val p = h.p
            val y = if (abs(at.y - p.y) > 1.2) at.y else p.y + Mth.clamp(at.y - p.y, -0.3, 0.25)
            p.setPos(at.x, y, at.z)
            p.face(w.yRot)
            if (p.activity != Activity.NONE && p.activity != Activity.STAND_GUARD) p.act(Activity.NONE)
        }
    }

    /** Where everyone looks as it goes: the driver down the road (and at whoever they greet), the guards about them. */
    private fun looks() {
        val w = wagon ?: return
        val ahead = path.at(s + 6.0).add(0.0, 1.4, 0.0)
        for (h in hands) {
            if (!h.idle) continue
            val near = level.getNearestPlayer(h.p, 10.0)
            when {
                near != null && !near.isSpectator -> h.p.lookAt(near)
                h.driver -> h.p.lookAt(ahead)
                h.seat == Wagon.TAIL -> h.p.lookAt(path.at(rearS - 6.0).add(0.0, 1.4, 0.0))
                else -> {
                    // A guard keeps an eye on the verges, now one side, now the other
                    val side = if ((t / 120 + h.index) % 2 == 0) 1.0 else -1.0
                    val (rx, rz) = way.right(middle().coerceIn(0.0, way.length))
                    h.p.lookAt(w.position().add(rx * side * 8.0 + (ahead.x - w.x) * 0.5, 1.4, rz * side * 8.0 + (ahead.z - w.z) * 0.5))
                }
            }
        }
    }

    /** A word to each player who comes by the driver. */
    private fun greet() {
        if (t % 10 != 0) return
        val d = driver ?: return
        if (d.seat != Wagon.DRIVER && mode != Mode.WAIT) return
        for (player in level.players()) {
            if (player.isSpectator || player.distanceToSqr(d.p) > GREET * GREET || player.uuid in greeted) continue
            greeted.add(player.uuid)
            if (player.uuid == view.escort) continue
            if (d.p.say("driver_greet", force = false, hearing = 16.0)) d.p.lookAt(player)
            break
        }
    }

    /** On a troubled road, once a trip, the driver says what they make of the quiet. */
    private fun quiet() {
        if (quietSaid || t % 40 != 0 || mode != Mode.ROLL) return
        if (level.random.nextInt(30) != 0) return
        val stage = RoadPeril.stage(CaravanData.get(level), leg.capital, leg.road)
        if (!stage.troubled) return
        val d = driver ?: return
        if (d.seat == Wagon.DRIVER && d.p.say("driver_quiet", force = false, hearing = 20.0)) quietSaid = true
    }

    /** The lantern lit from dusk to dawn. */
    private fun lantern() {
        val w = wagon ?: return
        val day = Math.floorMod(level.overworldClockTime, 24000L)
        w.flag(Wagon.FLAG_LANTERN, day in DUSK..DAWN || level.isThundering)
    }

    /** The horse's reins tied up at the saddle while nobody drives, and taken up again as the driver sits. */
    private fun reins() {
        val h = horse ?: return
        if (reinsAt >= 0 && t >= reinsAt) {
            reinsAt = -1
            h.flag(DraughtHorse.FLAG_REINS_TIED, reinsTied)
        }
    }

    /** [h] jumps down off the wagon (jump_down starts): the driver lets go of the reins. */
    fun jumped(h: Hand) {
        if (!h.driver) return
        reinsTied = true
        reinsAt = t + ceil(REINS_DOWN).toInt()
    }

    /** [h] climbs up onto the wagon (climb_up starts): the driver takes up the reins. */
    fun climbed(h: Hand) {
        if (!h.driver) return
        reinsTied = false
        reinsAt = t + REINS_UP
    }

    /** A player used crew member [h]: a word, never a talk while they're at work. */
    fun used(h: Hand, player: ServerPlayer): Boolean {
        if (player.isShiftKeyDown) return true
        h.p.lookAt(player)
        val escort = player.uuid == view.escort
        val line = when {
            h.driver && mode == Mode.WAIT && escort -> "driver_escort_meet"
            h.driver && (mode == Mode.FIGHT) -> "driver_trees"
            h.driver -> if (Caravans.strikes(id) && RoadPeril.stage(CaravanData.get(level), leg.capital, leg.road).troubled) "driver_quiet" else "driver_greet"
            escort || mode == Mode.FIGHT -> "guard_glad"
            // A town guard's words, but for the walls they're not on
            else -> ROAD_GUARD[level.random.nextInt(ROAD_GUARD.size)]
        }
        h.p.say(line, force = true, hearing = 16.0)
        return true
    }

    // ---- Heard from the records ----

    /** The record settled its strike without the bodies (the clock jumped past it): beaten off, on it goes; taken, its bodies go. */
    fun settled(strike: Caravans.Strike): Boolean {
        val w = waylay
        if (w != null && (w.fighting || resolving)) return w.settled(strike, ours = resolving)
        if (resolving) return true
        if (strike.waylaid) return false
        if (mode == Mode.BRACE) resume(false)
        return true
    }

    /** The record's in at its stand (the bodies said so, or its deadline came): true if the bodies can be there too. */
    fun arrived(seenIn: Caravans.View): Boolean {
        val escort = seenIn.escort
        if (escort != null) {
            val d = driver
            if (d != null) {
                level.getPlayerByUUID(escort)?.let { d.p.lookAt(it) }
                d.then(Wait(30)).then(Say("driver_escort_done", 32.0))
            }
        }
        if (mode == Mode.STAND || mode == Mode.LEAVE) return true
        val st = stop ?: findStop().also { stop = it }
        return abs(st.s - s) < ARRIVE_NEAR || mode == Mode.FIGHT
    }

    /** The record's over: the leaving wagon drives on out; a waylaid one's driver is at the gate now. */
    fun ended() {
        when (mode) {
            Mode.LEAVE -> mode = Mode.OFF
            Mode.RUN, Mode.FIGHT -> waylay?.ended()
            else -> {}
        }
    }

    /** Turns this show into the bodies after a waylaying: no wagon now (it's the wreck), the driver running for the gate. */
    fun running() {
        mode = Mode.RUN
        wagon = null
        harnessed = false
    }

    /** The wreck took the wagon's place (Waylay): the show lets go of the wagon, and of the horse if it lies with the wreck. */
    fun dropWagon(horseToo: Boolean) {
        wagon?.discard()
        wagon = null
        if (horseToo) {
            horse?.discard()
            horse = null
        }
    }

    /** Lets go of the horse (it bolted off out of sight). */
    fun dropHorse() {
        horse?.discard()
        horse = null
    }

    /** Whether [e] is one of this show's bodies. */
    fun owns(e: Entity): Boolean = e === wagon || e === horse || waylay?.owns(e) == true

    /**
     * Its bodies go (nobody near, the wagons switched off, the server stopping): a fight still on is finished by the
     * dice, from the numbers still standing if a player had joined it.
     */
    fun dismiss(quiet: Boolean = false) {
        waylay?.abandon(quiet)
        waylay = null
        unloading?.abandon()
        unloading = null
        for (h in hands) {
            if (h.p.director is CrewDirector) h.p.director = null
            h.p.discard()
        }
        hands.clear()
        wagon?.discard()
        horse?.discard()
        wagon = null
        horse = null
    }

    /** Ticks nobody's been near: from Bodies' look round, every [Bodies.SCAN] ticks. */
    fun nobodyNear(near: Boolean, every: Int): Boolean {
        unseen = if (near) 0 else unseen + every
        return unseen >= LINGER
    }

    // ---- Making it ----

    /** Its bodies, where the record has it (set back along the leg, and the record with it, if a player is too near). */
    private fun spawn(players: List<ServerPlayer>): Boolean {
        val v = view
        when (v.state) {
            Caravans.State.STRUCK -> return false
            Caravans.State.WAYLAID -> return spawnRunner(players)
            else -> {}
        }
        val m: Double
        when (v.state) {
            Caravans.State.WAITING -> {
                m = pick(0.0, -SET_BACK, players) ?: return false
                mode = Mode.WAIT
            }
            Caravans.State.ROLLING -> {
                val r = v.now
                m = pick(r, max(0.0, r - SET_BACK), players) ?: return false
                if (m < r - 1e-3) Caravans.waited(id, ceil((r - m) / Caravans.PACE).toInt())
                mode = Mode.ROLL
            }
            Caravans.State.STANDING, Caravans.State.LEAVING -> {
                val st = findStop()
                stop = st
                m = st.s - LEAD
                if (!clearOf(way.at(m), players, STAND_NEAR) || !anyWithin(way.at(m), players)) return false
                mode = if (v.state == Caravans.State.STANDING) Mode.STAND else Mode.LEAVE
            }
            else -> return false
        }
        s = m + LEAD
        if (!level.isPositionEntityTicking(BlockPos.containing(way.at(m))) || !level.isPositionEntityTicking(BlockPos.containing(way.at(s)))) return false
        // A strike close ahead: already swerving for it
        val strike = v.strike
        if (mode == Mode.ROLL && strike != null && m >= strike - BRACE_AT && Caravans.strikes(id)) brace(strike, fresh = false)
        if (!makeTrain(v)) return false
        when (mode) {
            Mode.STAND -> {
                wagon?.flag(Wagon.FLAG_BRAKE, true)
                unloading = Unloading(this).also { it.start(fresh = false) }
            }
            Mode.LEAVE -> {
                // Part way round the square, or out along the road, as the record has it
                val st = stop!!
                val dep = Departure(st)
                out = dep
                path = dep
                s = (dep.lb + st.s - v.now + LEAD).coerceAtLeast(0.0)
                expect = s
                place(true)
                for (h in hands) h.tick()
                loadExports()
            }
            else -> {}
        }
        return true
    }

    /** Of the places along the way from [from] back to [to] for the wagon's middle, the first no player is too near (but one is near enough to see). */
    private fun pick(from: Double, to: Double, players: List<ServerPlayer>): Double? {
        var m = from
        while (m >= to - 1e-6) {
            val at = way.at(m)
            if (clearOf(at, players, SPAWN_NEAR) && anyWithin(at, players)) return m
            m -= 2.0
        }
        return null
    }

    private fun clearOf(at: Vec3, players: List<ServerPlayer>, range: Double): Boolean = players.none { flat(it.position(), at) < range }

    private fun anyWithin(at: Vec3, players: List<ServerPlayer>): Boolean = players.any { flat(it.position(), at) <= SHOW_RANGE }

    /** The wagon, the horse and the crew, on their seats. */
    private fun makeTrain(v: Caravans.View): Boolean {
        val w = Bodies.WAGON.get().create(level, EntitySpawnReason.EVENT) ?: return false
        val h = Bodies.DRAUGHT_HORSE.get().create(level, EntitySpawnReason.EVENT) ?: return false
        w.caravan = id
        h.coat(DraughtHorse.coatOf(id))
        wagon = w
        horse = h
        place(true)
        if (!level.addFreshEntity(w)) {
            wagon = null
            horse = null
            return false
        }
        if (!level.addFreshEntity(h)) {
            w.discard()
            wagon = null
            horse = null
            return false
        }
        w.horse(h)
        h.wagon(w)
        w.load(if (mode == Mode.STAND || mode == Mode.LEAVE) List(Wagon.SLOTS) { null } else slots(v.load))
        h.move(if (mode == Mode.WAIT) DraughtHorse.Move.GRAZE else if (mode == Mode.STAND) DraughtHorse.Move.NOSEBAG else DraughtHorse.Move.WALK)
        if (mode == Mode.STAND) h.flag(DraughtHorse.FLAG_NOSEBAG, true)
        val crew = 1 + v.guards.coerceIn(0, 4)
        for (k in 0 until crew) {
            val seat = boardingSeat(k)
            val (at, yaw) = if (seat != Wagon.OFF) w.seatAt(seat) else ground(WALKER_OUT * (if (k == 3) 1f else -1f), WALKER_Z) to w.yRot
            val p = Crew.spawn(level, at, yaw, id, k) ?: continue
            val hand = Hand(this, p, k)
            p.director = CrewDirector(hand)
            hands.add(hand)
            if (k < 4) w.crew(k, p.id)
            if (mode == Mode.STAND) continue
            if (seat != Wagon.OFF) {
                hand.seatOn(seat)
                p.act(seatedActivity(seat))
            } else {
                hand.hold(true)
            }
        }
        if (mode == Mode.STAND) {
            // Come upon standing: the crew about the wagon, the load long gone
            unloadedCrew()
        }
        tailboard()
        return true
    }

    /** Standing at the stand when its bodies come: the driver by the horse, the guards by the wagon. */
    private fun unloadedCrew() {
        for (h in hands) {
            val (at, yaw) = when (h.index) {
                0 -> horsePoint(-22f, -10f) to Mth.wrapDegrees((horse?.yRot ?: 0f) - 90f)
                1 -> ground(-30f, 6f) to yaw(-90f)
                2 -> ground(30f, 10f) to yaw(90f)
                else -> ground(-30f + 60f * (h.index % 2), -30f) to yaw(if (h.index % 2 == 0) -90f else 90f)
            }
            h.land(at, yaw, byHand = true)
            h.p.act(if (h.driver) Activity.NONE else Activity.STAND_GUARD)
        }
        horse?.flag(DraughtHorse.FLAG_REINS_TIED, true)
    }

    /** The tailboard is let down while someone sits or stands on it, and for unloading. */
    fun tailboard() {
        val w = wagon ?: return
        val open = hands.any { it.seat == Wagon.TAIL || it.seat == Wagon.STAND } || unloading?.open == true
        w.flag(Wagon.FLAG_TAILBOARD_SHUT, !open)
    }

    /** The exports heaped on the bed for the drive out. */
    fun loadExports() {
        wagon?.load(slots(view.exports))
    }

    /** A waylaid wagon's driver come upon running for the gate: just them, where the record has them. */
    private fun spawnRunner(players: List<ServerPlayer>): Boolean {
        val r = view.now
        val at = way.at(r)
        if (!clearOf(at, players, RUNNER_NEAR) || !anyWithin(at, players)) return false
        if (!level.isPositionEntityTicking(BlockPos.containing(at))) return false
        val y = Ground.stand(level, at.x, at.z, at.y + 0.5)
        val (hx, hz) = way.heading(r)
        val p = Crew.spawn(level, Vec3(at.x, y, at.z), yawOf(hx, hz), id, 0) ?: return false
        val hand = Hand(this, p, 0)
        p.director = CrewDirector(hand)
        hands.add(hand)
        mode = Mode.RUN
        harnessed = false
        waylay = Waylay.runnerOnly(this)
        return true
    }

    // ---- Small helpers ----

    /** Where a wagon is that's out, as a seat for each crew member when they board: the driver, the bench, the tailboard; the rest walk. */
    fun boardingSeat(k: Int): Int = when (k) {
        0 -> Wagon.DRIVER
        1 -> Wagon.BENCH
        2 -> Wagon.TAIL
        else -> Wagon.OFF
    }

    /** What someone does sitting on [seat]: the driver drives, a guard rides with the spear across the knees. */
    fun seatedActivity(seat: Int): Activity = if (seat == Wagon.DRIVER) Activity.DRIVE else Activity.RIDE_TAIL

    /** The road's stretch the wagon covers, for the commands. */
    fun describe(): String {
        val w = wagon
        val at = if (w != null) "%.0f, %.0f, %.0f".format(w.x, w.y, w.z) else hands.firstOrNull()?.p?.let { "%.0f, %.0f, %.0f".format(it.x, it.y, it.z) } ?: "?"
        return "#$id ${mode.name.lowercase()} at $at, %.0f of %.0f blocks along, %.2f a tick".format(middle().coerceAtLeast(0.0), way.length, speed)
    }

    /** Where it stops at its stand, the bulb it turns round in. */
    class Stop(val s: Double, val side: Double, val r: Double)

    /**
     * The way out: up to the stop the way it came in ([u] below 0), the bulb turning it round in the square (0 to
     * [lb]), then back along the way in, past its start and on.
     */
    private inner class Departure(val stop: Stop) : Path {
        val lb = Train.bulbLength(stop.r)
        private val p0 = way.at(stop.s)
        private val h0 = way.heading(stop.s)

        override fun at(s: Double): Vec3 {
            if (s <= 0.0) return way.at(stop.s + s)
            if (s <= lb) {
                val q = Train.bulb(p0.x, p0.z, h0.first, h0.second, stop.side, stop.r, s)
                return Vec3(q[0], p0.y, q[1])
            }
            return way.at(stop.s - (s - lb))
        }
    }

    companion object {
        /** Blocks from the back axle to the middle of the wagon, and from the middle to the horse's middle. */
        const val MID = Train.WHEELBASE / 2.0
        const val LEAD = Train.LENGTH - MID

        /** How far short of a strike (the wagon's middle) the band's whistle comes; and how far the wagon swerves for the verge. */
        const val BRACE_AT = 14.0

        /** How hard the speed follows the record, how much faster it may go to catch up, and how fast it speeds up and slows. */
        private const val GAIN = 0.08
        private const val FAST = 1.25
        private const val ACCEL = Caravans.PACE / 25.0
        private const val DECEL = Caravans.PACE / 12.0

        /** Blocks it takes to pull up at a stop, at its pace. */
        const val STOP_OVER = 3.0
        private const val STOP_DECEL = Caravans.PACE * Caravans.PACE / (2.0 * STOP_OVER)

        /** Behind the record by more than this, it says it waited; by more than [SNAP], it catches up at once. */
        private const val SYNC = 0.5
        private const val SNAP = 16.0

        /** How far ahead of the horse's middle it feels for what's in the road, and how near that counts. */
        private const val PROBE = 1.9
        private const val BLOCK_REACH = 0.9

        /** Held up this long with nobody within [HOP_CLEAR], it goes round; this long, round once nobody looks. */
        private const val HOP_AFTER = 600
        private const val HOP_ANYWAY = 12000
        private const val HOP_CLEAR = 24.0
        private const val HOP = 6.0

        /** The townsfolk are cleared from behind its tailboard to a few seconds ahead, this wide, every few ticks. */
        private const val WAY_BACK = 1.2
        private const val WAY_AHEAD = 8.0
        private const val WAY_WIDTH = 2.4
        private const val WAYSIDE_EVERY = 4

        /** Degrees of slope that set the horse straining and the driver braking (and let the brake off again). */
        private const val CLIMB_PITCH = 4f
        private const val BRAKE_PITCH = 5f
        private const val BRAKE_OFF_PITCH = 2f
        private const val STRAIN_START = 30
        private const val URGE_EVERY = 200

        /** A guard's word to a player who comes up to them on the road (the town guards' lines; "All quiet on the walls" left out). */
        private val ROAD_GUARD = listOf("guard_0", "guard_1", "guard_3", "guard_4")

        /** How near the driver greets a player; within how far of the end it looks for its stop. */
        private const val GREET = 8.0
        private const val STAND_LOOK = 40.0

        /** Bodies come within [SHOW_RANGE] of a player but never within [SPAWN_NEAR] of one, set back up to [SET_BACK] along the leg. */
        const val SHOW_RANGE = 128.0
        private const val SPAWN_NEAR = 48.0
        private const val STAND_NEAR = 40.0
        private const val RUNNER_NEAR = 32.0
        private const val SET_BACK = 64.0

        /** Ticks with nobody near before its bodies go. */
        private const val LINGER = 200

        /** The most a leaving record waits for the crew to be aboard. */
        private const val HOLD_MOST = 1200

        /** Driven out past its leg's start: gone once nobody's within [OFF_NEAR], or this far past it whatever. */
        private const val OFF_NEAR = 48.0
        private const val OFF_MOST = 64.0

        /** How near its stop the bodies must be for an arrival the record made without them to stand. */
        private const val ARRIVE_NEAR = 24.0

        /** The bulbs tried at the stand, biggest first; how far short of the end it may stop; which way it turns first; across the bulb. */
        private val BULB_SIZES = doubleArrayOf(3.0, 2.5, 2.0, 1.75)
        private val STOP_BACK = doubleArrayOf(0.0, 2.0, 4.0, 6.0)
        private val SIDES = doubleArrayOf(1.0, -1.0)
        private val ACROSS = doubleArrayOf(-0.95, 0.0, 0.95)

        /** The walking guard: px out to the side of the wagon's middle line, and along it (between the axles). */
        private const val WALKER_OUT = 27f
        private const val WALKER_Z = -16f

        /** Walking round the wagon: this far out from its middle line (px), behind its tailboard or ahead of the horse. */
        private const val ROUND_X = 26f
        private const val BEHIND_Z = 30f
        private const val FRONT_Z = -128f

        /** The clock's day from dusk to dawn, for the lantern. */
        private const val DUSK = 12500L
        private const val DAWN = 23500L

        /** Ticks into jump_down the reins leave the driver's hands, and into climb_up they're taken up (CrewMoves). */
        private const val REINS_DOWN = 1.5
        private const val REINS_UP = 17

        /** A show for [view], its bodies made where the record has them; null if they can't be (too near a player, not loaded). */
        fun make(level: ServerLevel, view: Caravans.View, players: List<ServerPlayer>): Show? {
            if (view.leg.size < 2) return null
            val leg = Caravans.leg(view.id) ?: return null
            val show = Show(level, view, leg)
            return if (show.spawn(players)) show else {
                show.dismiss()
                null
            }
        }

        /** The way in at [s], swerved by [swerve] (null: straight). */
        fun swerved(way: Way, swerve: Swerve?, s: Double): Vec3 {
            val p = way.at(s)
            val lat = swerve?.at(s) ?: 0.0
            if (lat == 0.0) return p
            val (rx, rz) = way.right(s)
            return Vec3(p.x + rx * lat, p.y, p.z + rz * lat)
        }

        /**
         * Where a wagon braced for a strike at [strike] along [way] pulls up, swerved to the verge on [side] (1 the
         * road's right): its back axle's spot and its yaw, as [Show] places it there (and its wreck lies).
         */
        fun strikePose(way: Way, strike: Double, side: Double): Pair<Vec3, Float> {
            val sw = Swerve.brace(strike, side)
            val path = Path { s -> swerved(way, sw, s) }
            val h = strike + LEAD
            val ks = Train.back(path, h, Train.AHEAD)
            val rs = Train.back(path, ks, Train.WHEELBASE)
            val r = path.at(rs)
            val k = path.at(ks)
            return r to yawOf(k.x - r.x, k.z - r.z)
        }

        /** Minecraft's yaw for a heading ([dx], [dz]): 0 south, 90 west. */
        fun yawOf(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

        /**
         * What's heaped on the bed for [load]: a piece a few units, sixteen at most, each good getting at least one
         * and the rest shared by size, the biggest goods first; the floor fills before the top.
         */
        fun slots(load: List<Pair<Good, Int>>): List<Good?> {
            val goods = load.filter { it.second > 0 }.sortedByDescending { it.second }
            val total = goods.sumOf { it.second }
            if (total <= 0) return List(Wagon.SLOTS) { null }
            val n = min(Wagon.SLOTS, max(1, ceil(total / UNITS_A_PIECE).toInt()))
            val kept = goods.take(n)
            val sum = kept.sumOf { it.second }.toDouble()
            val quota = kept.map { it.second / sum * n }
            val count = IntArray(kept.size) { max(1, floor(quota[it]).toInt()) }
            while (count.sum() > n) {
                val k = count.indices.filter { count[it] > 1 }.maxByOrNull { count[it] - quota[it] } ?: break
                count[k]--
            }
            while (count.sum() < n) {
                val k = count.indices.maxByOrNull { quota[it] - count[it] } ?: break
                count[k]++
            }
            val out = ArrayList<Good?>(Wagon.SLOTS)
            for ((i, g) in kept.withIndex()) repeat(count[i]) { out.add(g.first) }
            while (out.size < Wagon.SLOTS) out.add(null)
            return out
        }

        /** Units of a good to a piece of the load. */
        private const val UNITS_A_PIECE = 4.0
    }
}

/**
 * How far a wagon's way swerves to one side of the road at each distance along it: easing out by [amount] over [a0]
 * to [a1], and back over [b0] to [b1] once it's set off again.
 */
internal class Swerve(val amount: Double, val a0: Double, val a1: Double) {
    var b0 = Double.MAX_VALUE
    var b1 = Double.MAX_VALUE

    fun at(s: Double): Double {
        val out = ease((s - a0) / (a1 - a0))
        if (out <= 0.0) return 0.0
        val back = if (b0 == Double.MAX_VALUE) 0.0 else ease((s - b0) / (b1 - b0))
        return amount * out * (1.0 - back)
    }

    private fun ease(x: Double): Double {
        val u = x.coerceIn(0.0, 1.0)
        return u * u * (3.0 - 2.0 * u)
    }

    companion object {
        /** How far over a wagon pulls toward the verge for a strike, and over how many blocks it eases there. */
        const val LATERAL = 1.6
        const val RAMP = 8.0

        /** For a strike at [strike] to the verge on [side]: fully over by the time the back axle stops, a block short of the strike point. */
        fun brace(strike: Double, side: Double): Swerve {
            val end = strike - Show.MID
            return Swerve(side * LATERAL, end - RAMP, end)
        }
    }
}
