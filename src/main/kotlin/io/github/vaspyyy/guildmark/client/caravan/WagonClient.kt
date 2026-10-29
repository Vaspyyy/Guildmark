package io.github.vaspyyy.guildmark.client.caravan

import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wagon as this client draws it at one moment: its back axle's ground at [x], [y], [z], turned [yaw], its nose
 * [pitch] up and leaning [roll] (its right side up) about the back axle, as the server's Wagon.Frame has it: where
 * the points of its own space (WagonMesh's: px, y down, its front -z, its right -x) are in the world, and back.
 */
internal class WagonPose {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var yaw = 0f
    var pitch = 0f
    var roll = 0f
    private val q = Quaternionf()
    private val back = Quaternionf()
    private val v = Vector3f()

    fun set(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, roll: Float): WagonPose {
        this.x = x
        this.y = y
        this.z = z
        this.yaw = yaw
        this.pitch = pitch
        this.roll = roll
        q.rotationY(Math.toRadians(180.0 - yaw).toFloat())
            .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
            .rotateZ(Math.toRadians(roll.toDouble()).toFloat())
        back.set(q).conjugate()
        return this
    }

    /** Where [px], [py], [pz] of its own space is in the world. */
    fun world(px: Float, py: Float, pz: Float, out: Vector3d): Vector3d {
        v.set(-px / 16f, -py / 16f - AXLE, pz / 16f)
        q.transform(v)
        return out.set(x + v.x, y + AXLE + v.y, z + v.z)
    }

    /** The world's [wx], [wy], [wz] in its own space (px). */
    fun local(wx: Double, wy: Double, wz: Double, out: Vector3f): Vector3f {
        v.set((wx - x).toFloat(), (wy - y).toFloat() - AXLE, (wz - z).toFloat())
        back.transform(v)
        return out.set(-v.x * 16f, -(v.y + AXLE) * 16f, v.z * 16f)
    }

    companion object {
        private const val AXLE = Wagon.REAR_RADIUS.toFloat()
    }
}

/**
 * What this client keeps of one wagon from tick to tick to draw it and to hear it: its lean eased, each wheel's turn
 * by the ground it covered, the front steered toward the horse, the tilt's sway, the lantern's swing, the pennant's
 * ripple, the brake lever and the tailboard, the shafts dropping to the road once the horse is off them, the overturn,
 * who sits where, the load settling on, and its sounds.
 */
internal class WagonLook {
    /** Not yet ticked: the first tick takes things as they are. */
    var fresh = true
    var age = 0

    /** Degrees it leans (its right side up), eased; and last tick's. */
    var roll = 0f
    var rollO = 0f
    var rawRoll = 0f
    var rawPitch = 0f

    /** Radians each wheel has turned rolling forward (back right, back left, front right, front left); last tick's; where each hub was. */
    val spin = FloatArray(4)
    val spinO = FloatArray(4)
    val hubX = DoubleArray(4)
    val hubZ = DoubleArray(4)

    /** Degrees the front is steered (+ to its right), and last tick's; its rock on the turntable (its right side up), eased. */
    var steer = 0f
    var steerO = 0f
    var rock = 0f
    var rockO = 0f

    /** Blocks a tick it's going, eased. */
    var speed = 0f

    /** Degrees the tilt sways about its top, and how fast; last tick's. */
    var sway = 0f
    var swayO = 0f
    var swayV = 0f

    /** Degrees the lantern swings fore and aft and sideways at its hook, and how fast; last tick's. */
    var swingX = 0f
    var swingXO = 0f
    var swingXV = 0f
    var swingZ = 0f
    var swingZO = 0f
    var swingZV = 0f

    /** The pennant's ripple: its phase (radians) and how big it is; last tick's phase. */
    var flap = 0f
    var flapO = 0f
    var flapSize = 0.6f

    /** Degrees the brake lever leans, and the tailboard stands (90 shut, 0 let down); last tick's. */
    var brake = WagonMesh.BRAKE_REST
    var brakeO = WagonMesh.BRAKE_REST
    var tail = 90f
    var tailO = 90f

    /** The shafts down on the road (1) or up in the horse's tugs (0), eased; last tick's; radians they were last turned to keep through the tugs. */
    var down = 0f
    var downO = 0f
    var follow = 0f

    /** The overturns seen; ticks since this one began, or -1; which way it goes; the side the arrows are in, kept through it. */
    var tipSeen = 0
    var tipping = -1
    var tipMirror = false
    var arrowsMirror = false

    /** Who is in each of the crew's places, the seat each was on before the last move and when they moved (this look's age). */
    val crew = IntArray(4) { -1 }
    val seat = IntArray(4)
    val seatFrom = IntArray(4)
    val seatAt = IntArray(4) { -1000 }

    /** The load as shown on each place of the bed, and ticks since each was put there. */
    val load = arrayOfNulls<Good>(Wagon.SLOTS)
    val loadSince = IntArray(Wagon.SLOTS) { SETTLE }

    /** Px a piece rests up off the floor (on what's under it), eased; and last tick's. */
    val rest = FloatArray(Wagon.SLOTS)
    val restO = FloatArray(Wagon.SLOTS)

    /** The stack by the back wheel: what's on each place, since when, what it rests on, and the ground under each (world y). */
    val stack = arrayOfNulls<Good>(Wagon.SLOTS)
    val stackSince = IntArray(Wagon.SLOTS) { SETTLE }
    val stackRest = FloatArray(Wagon.SLOTS)
    val stackRestO = FloatArray(Wagon.SLOTS)
    val stackGround = DoubleArray(8) { Double.NaN }

    /** The wheels' roll going round under it (and the one fading out as the ground changes), and the ground it's on. */
    var rolling: CaravanSounds.Loop? = null
    var fading: CaravanSounds.Loop? = null
    var ground = CaravanSounds.Ground.EARTH

    /** Ticks before the next creak, flap of the tilt or squeal of the brake may come. */
    var creakIn = 0
    var tiltIn = 0
    var brakeIn = 0
    var settledSoundAt = -100

    companion object {
        /** Ticks a piece takes to settle onto the bed, and onto the stack. */
        const val SETTLE = 7
        const val STACK_SETTLE = 4
    }
}

/**
 * The client half of the wagons, ticked from Wagon.clientTick: everything each client works out for itself from what
 * the server sends (where the wagon is, its lean, its flags, its load, who sits where) to draw it moving as it moves
 * (client/caravan's WagonRenderer) and to hear it.
 */
internal object WagonClient {
    /** What this client keeps to draw [w]. */
    fun look(w: Wagon): WagonLook = w.look as? WagonLook ?: WagonLook().also { w.look = it }

    /** [w]'s horse, if this client has it. */
    fun horseOf(w: Wagon): DraughtHorse? {
        val id = w.horseId
        if (id < 0) return null
        return w.level().getEntity(id) as? DraughtHorse
    }

    /** Whether [h] is in [w]'s shafts, its traces whole: the front steers toward it, the shafts run through its tugs. */
    fun harnessed(w: Wagon, h: DraughtHorse): Boolean =
        !h.isRemoved && h.wagonId == w.id && !h.has(DraughtHorse.FLAG_CUT) && !w.has(Wagon.FLAG_CUT)

    /** [w] as drawn at [partial]: its place and turn as the renderer has them, its lean eased. */
    fun pose(w: Wagon, partial: Float, out: WagonPose): WagonPose {
        val look = look(w)
        val p = partial.toDouble()
        return out.set(
            Mth.lerp(p, w.xOld, w.x), Mth.lerp(p, w.yOld, w.y), Mth.lerp(p, w.zOld, w.z),
            w.getYRot(partial), -w.getXRot(partial), Mth.lerp(partial, look.rollO, look.roll),
        )
    }

    /** The driver (crew place 0), if this client has them. */
    fun driver(w: Wagon): Townsperson? {
        val id = w.crew(0)
        if (id < 0) return null
        return w.level().getEntity(id) as? Townsperson
    }

    /**
     * Degrees the front turns (+ to its right) to head from its kingpin toward a horse at [local] (the horse's middle, in
     * the wagon's space): null if the horse isn't somewhere ahead of it.
     */
    fun steerOf(local: Vector3f): Float? {
        val ax = local.x
        val az = local.z + WagonMesh.WHEELBASE
        if (az > -MIN_AHEAD) return null
        return Math.toDegrees(atan2(-ax.toDouble(), -az.toDouble())).toFloat().coerceIn(-MAX_STEER, MAX_STEER)
    }

    /** The brake lever's lean (degrees) [t] ticks into drive_brake, as the driver's hand hauls it (CrewMoves.BRAKE_LEAN). */
    fun brakeLean(t: Float): Float {
        val samples = CrewMoves.BRAKE_LEAN
        val at = (t * 2f).coerceIn(0f, (samples.size - 1).toFloat())
        val i = at.toInt()
        val j = min(i + 1, samples.size - 1)
        return Mth.lerp(at - i, samples[i], samples[j])
    }

    /** Radians it's over onto its side [t] ticks after the shove (WagonMesh.WRECK_ROLL at the end): faster as it falls, a little bounce as it lands. */
    fun tipAngle(t: Float): Float {
        if (t <= 0f) return 0f
        val full = Math.toRadians(WagonMesh.WRECK_ROLL.toDouble()).toFloat()
        if (t < TIP) {
            val f = t / TIP
            return full * f * f
        }
        val u = ((t - TIP) / BOUNCE).coerceIn(0f, 1f)
        return full * (1f - BOUNCE_BACK * sin(u * PI).toFloat())
    }

    private val tmp = Vector3f()
    private val pose = WagonPose()

    // ---- Each tick ----

    fun tick(w: Wagon) {
        val look = look(w)
        val level = w.level()
        val first = look.fresh
        look.fresh = false
        look.age++
        val horse = horseOf(w)
        val harnessed = horse != null && harnessed(w, horse)

        // Leaning, eased; how fast it's going
        look.rollO = look.roll
        val rollChange = if (first) 0f else w.roll - look.rawRoll
        val pitchNow = -w.xRot
        val pitchChange = if (first) 0f else pitchNow - look.rawPitch
        look.rawRoll = w.roll
        look.rawPitch = pitchNow
        look.roll = if (first) w.roll else look.roll + (w.roll - look.roll) * ROLL_EASE
        if (first) look.rollO = look.roll
        look.rockO = look.rock
        look.rock = if (first) w.rock else look.rock + (w.rock - look.rock) * ROLL_EASE
        if (first) look.rockO = look.rock
        val dx = w.x - w.xOld
        val dz = w.z - w.zOld
        val moved = sqrt(dx * dx + dz * dz).toFloat().coerceAtMost(1f)
        val was = look.speed
        look.speed = if (first) 0f else look.speed + (moved - look.speed) * 0.5f

        // The front steered toward the horse; held where it is once the horse is off it
        look.steerO = look.steer
        if (horse != null && harnessed) {
            pose.set(w.x, w.y, w.z, w.yRot, pitchNow, look.roll)
            pose.local(horse.x, horse.y, horse.z, tmp)
            steerOf(tmp)?.let { look.steer = it }
        }
        if (first) look.steerO = look.steer

        wheels(w, look, first)
        sway(w, look, first, was, rollChange, pitchChange)

        // The brake lever: hauled on with drive_brake, held while it's on, let off over a few ticks
        look.brakeO = look.brake
        val driver = driver(w)
        val hauling = driver?.takeIf { w.seat(0) == Wagon.DRIVER && it.shownActivity == Activity.DRIVE_BRAKE }
        look.brake = when {
            first -> if (w.has(Wagon.FLAG_BRAKE)) WagonMesh.BRAKE_ON else WagonMesh.BRAKE_REST
            hauling != null -> brakeLean((hauling.tickCount - hauling.shownStart).toFloat())
            else -> Mth.approach(look.brake, if (w.has(Wagon.FLAG_BRAKE)) WagonMesh.BRAKE_ON else WagonMesh.BRAKE_REST, BRAKE_SPEED)
        }
        if (first) look.brakeO = look.brake

        // The tailboard let down or put up, banging down onto its chains
        look.tailO = look.tail
        val shut = w.has(Wagon.FLAG_TAILBOARD_SHUT)
        if (first) {
            look.tail = if (shut) 90f else 0f
            look.tailO = look.tail
        } else {
            val target = if (shut) 90f else 0f
            if (look.tail != target && look.tailO == look.tail) sound(level, w, 0f, -20f, 12f, "creak", 0.3f, 1.15f)
            look.tail = Mth.approach(look.tail, target, TAIL_SPEED)
            if (look.tail == 0f && look.tailO > 0f) sound(level, w, 0f, -19f, 14f, "crate_down", 0.45f, 1.25f)
        }

        // The shafts drop to the road once the horse is off them (cut loose, or cut from it dead); a horse going down in
        // them takes them down with it as it goes. Up from when a horse is backed into them to be hitched (at its standard place)
        look.downO = look.down
        val loose = (horse == null || !harnessed) && !w.has(Wagon.FLAG_IN_SHAFTS)
        if (first) {
            look.down = if (loose) 1f else 0f
            look.downO = look.down
        } else {
            look.down = Mth.approach(look.down, if (loose) 1f else 0f, DOWN_SPEED)
            if (look.down == 1f && look.downO < 1f) sound(level, w, 0f, 0f, -90f, "crate_down", 0.5f, 0.7f)
        }

        // Shoved over: it goes over from when this client sees it
        if (first) {
            look.tipSeen = w.tipped
        } else if (w.tipped != look.tipSeen) {
            look.tipSeen = w.tipped
            look.tipping = 0
            look.tipMirror = w.has(Wagon.FLAG_MIRROR)
        } else if (look.tipping >= 0) {
            look.tipping++
        }
        if (look.tipping < 0) look.arrowsMirror = w.has(Wagon.FLAG_MIRROR)

        crew(w, look, first)
        load(w, look, first)
        stack(w, look, first, level)
        sounds(w, look, level, horse, harnessed)
    }

    /** The wheels roll as far as each went along its own heading: the outer ones further on a turn, the front ones as it steers. */
    private fun wheels(w: Wagon, look: WagonLook, first: Boolean) {
        val yaw = Math.toRadians(w.yRot.toDouble())
        val fx = -sin(yaw)
        val fz = cos(yaw)
        val rx = -cos(yaw)
        val rz = -sin(yaw)
        val s = Math.toRadians(look.steer.toDouble())
        val ffx = fx * cos(s) + rx * sin(s)
        val ffz = fz * cos(s) + rz * sin(s)
        val frx = rx * cos(s) - fx * sin(s)
        val frz = rz * cos(s) - fz * sin(s)
        val ht = WagonMesh.WHEEL_X / 16.0
        val kx = w.x + fx * WagonMesh.WHEELBASE / 16.0
        val kz = w.z + fz * WagonMesh.WHEELBASE / 16.0
        for (k in 0 until 4) {
            val (hx, hz) = when (k) {
                0 -> w.x + rx * ht to w.z + rz * ht
                1 -> w.x - rx * ht to w.z - rz * ht
                2 -> kx + frx * ht to kz + frz * ht
                else -> kx - frx * ht to kz - frz * ht
            }
            val (ax, az) = if (k < 2) fx to fz else ffx to ffz
            val radius = if (k < 2) Wagon.REAR_RADIUS else Wagon.FRONT_RADIUS
            look.spinO[k] = look.spin[k]
            if (first) {
                look.spin[k] = (w.id * 1.7f + k * 0.9f) % TAU
                look.spinO[k] = look.spin[k]
            } else {
                val d = (hx - look.hubX[k]) * ax + (hz - look.hubZ[k]) * az
                // A jump (it was put somewhere new) turns nothing
                if (abs(d) < 2.0) look.spin[k] += (d / radius).toFloat()
                if (look.spin[k] > WRAP || look.spin[k] < -WRAP) {
                    val turns = TAU * Math.floor(look.spin[k] / TAU.toDouble()).toFloat()
                    look.spin[k] -= turns
                    look.spinO[k] -= turns
                }
            }
            look.hubX[k] = hx
            look.hubZ[k] = hz
        }
    }

    /**
     * The tilt sways on rough going, the lantern swings back as it pulls away and out on the turns, the pennant
     * ripples, quicker and wider the faster it goes.
     */
    private fun sway(w: Wagon, look: WagonLook, first: Boolean, was: Float, rollChange: Float, pitchChange: Float) {
        look.swayO = look.sway
        look.swingXO = look.swingX
        look.swingZO = look.swingZ
        look.flapO = look.flap
        if (first) return
        val random = w.level().random
        val rough = (random.nextFloat() - 0.5f) * look.speed * ROUGH
        look.swayV += -look.sway * 0.14f - look.swayV * 0.2f + rollChange * 0.35f + rough
        look.sway = (look.sway + look.swayV).coerceIn(-SWAY_MOST, SWAY_MOST)
        // Fore and aft by how it speeds up or slows, and the bumps; sideways out of the turns
        val accel = look.speed - was
        val turn = Math.toRadians(Mth.wrapDegrees(w.yRot - w.yRotO).toDouble()).toFloat()
        look.swingXV += -look.swingX * 0.09f - look.swingXV * 0.05f + accel * LANTERN_PULL + pitchChange * 0.4f + rough * 0.5f
        look.swingZV += -look.swingZ * 0.09f - look.swingZV * 0.05f - turn * look.speed * LANTERN_TURN + rollChange * 0.5f
        look.swingX = (look.swingX + look.swingXV).coerceIn(-SWING_MOST, SWING_MOST)
        look.swingZ = (look.swingZ + look.swingZV).coerceIn(-SWING_MOST, SWING_MOST)
        val going = min(1f, look.speed / CRUISE)
        look.flap += 0.16f + look.speed * 1.4f
        look.flapSize += (0.55f + 0.45f * going - look.flapSize) * 0.05f
        if (look.flap > WRAP) {
            look.flap -= TAU * 300f
            look.flapO -= TAU * 300f
        }
    }

    /** Who's in the crew's places (for drawing them on their seats), and when each moved seat. */
    private fun crew(w: Wagon, look: WagonLook, first: Boolean) {
        for (i in 0 until 4) {
            val id = w.crew(i)
            if (id != look.crew[i]) {
                if (look.crew[i] >= 0) CaravanClient.unride(look.crew[i], w)
                look.crew[i] = id
                if (id >= 0) CaravanClient.ride(id, w, i)
            }
            val s = w.seat(i)
            if (s != look.seat[i]) {
                look.seatFrom[i] = if (first) s else look.seat[i]
                look.seat[i] = s
                look.seatAt[i] = if (first) -1000 else look.age
            }
        }
    }

    /** The load: what's put on settles onto the bed, one after the other; what's taken goes with whoever took it. */
    private fun load(w: Wagon, look: WagonLook, first: Boolean) {
        var landed = false
        var fresh = 0
        for (s in 0 until Wagon.SLOTS) {
            val g = w.piece(s)
            if (g != look.load[s]) {
                look.load[s] = g
                look.loadSince[s] = if (first || g == null) WagonLook.SETTLE else -STAGGER * fresh++
            } else if (look.loadSince[s] < WagonLook.SETTLE) {
                look.loadSince[s]++
                if (look.loadSince[s] == WagonLook.SETTLE && g != null) landed = true
            }
        }
        for (s in 0 until Wagon.SLOTS) {
            look.restO[s] = look.rest[s]
            val target = restOn(WagonMesh.SLOTS[s].under, look.load)
            look.rest[s] = if (first) target else Mth.approach(look.rest[s], target, REST_SPEED)
            if (first) look.restO[s] = look.rest[s]
        }
        if (landed && look.age - look.settledSoundAt > 3) {
            look.settledSoundAt = look.age
            sound(w.level(), w, 0f, WagonMesh.FLOOR_TOP, -24f, "crate_down", 0.25f, 1.1f)
        }
    }

    /** Px a piece on top of [under] rests up off the floor: the tallest of what's there. */
    fun restOn(under: IntArray, shown: Array<Good?>): Float {
        var top = 0f
        for (k in under) {
            val g = shown.getOrNull(k) ?: continue
            top = max(top, WagonDraw.piece(g).height)
        }
        return top
    }

    /** The stack by the back wheel: set down (the server's thud), resting on what's under it, on the ground as it lies there. */
    private fun stack(w: Wagon, look: WagonLook, first: Boolean, level: Level) {
        var any = false
        for (s in 0 until Wagon.SLOTS) {
            val g = w.stacked(s)
            if (g != look.stack[s]) {
                look.stack[s] = g
                look.stackSince[s] = if (first || g == null) WagonLook.STACK_SETTLE else 0
            } else if (look.stackSince[s] < WagonLook.STACK_SETTLE) {
                look.stackSince[s]++
            }
            if (g != null) any = true
        }
        for (s in 8 until Wagon.SLOTS) {
            look.stackRestO[s] = look.stackRest[s]
            val under = look.stack[s - 8]
            val target = if (under == null) 0f else WagonDraw.piece(under).height
            look.stackRest[s] = if (first) target else Mth.approach(look.stackRest[s], target, REST_SPEED)
            if (first) look.stackRestO[s] = look.stackRest[s]
        }
        // The ground under each place, looked at now and then (the wagon stands still while there's a stack)
        if (any && (look.age % 10 == 1 || look.stackGround[0].isNaN())) {
            val yaw = Math.toRadians(w.yRot.toDouble())
            val fx = -sin(yaw)
            val fz = cos(yaw)
            val rx = -cos(yaw)
            val rz = -sin(yaw)
            for (k in 0 until 8) {
                val place = Wagon.STACK_XZ[k]
                // px of the wagon's space: -x its right, -z ahead
                val right = -place[0] / 16.0
                val ahead = -place[1] / 16.0
                val x = w.x + rx * right + fx * ahead
                val z = w.z + rz * right + fz * ahead
                look.stackGround[k] = groundAt(level, x, z, w.y)
            }
        }
    }

    // ---- Its sounds ----

    /** The wheels' roll by the ground under them, a creak now and then, the tilt flapping, the brake on the way down. */
    private fun sounds(w: Wagon, look: WagonLook, level: Level, horse: DraughtHorse?, harnessed: Boolean) {
        val player = Minecraft.getInstance().player ?: return
        val near = player.distanceToSqr(w) < HEAR * HEAR
        val moving = look.speed > MOVING && look.tipping < 0
        if (look.creakIn > 0) look.creakIn--
        if (look.tiltIn > 0) look.tiltIn--
        if (look.brakeIn > 0) look.brakeIn--
        if (!near) {
            look.rolling?.target = 0f
            look.fading?.target = 0f
            return
        }
        val going = min(1f, look.speed / CRUISE)
        if (moving) {
            if (look.age % 10 == 0 || look.rolling == null) look.ground = CaravanSounds.ground(level, w.x, w.y, w.z)
            val want = look.ground.roll
            val now = look.rolling
            if (now != null && now.name != want) {
                // Onto other ground: the one fades as the other comes up
                now.target = 0f
                look.fading?.stopNow()
                look.fading = now
                look.rolling = null
            }
            look.rolling = CaravanSounds.keep(look.rolling, want, w)
            look.rolling?.let {
                it.target = 0.5f + 0.4f * min(1f, look.speed / LOUDEST)
                it.pitchTo = 0.85f + 0.2f * min(1f, look.speed / HIGHEST)
            }
        } else {
            look.rolling?.target = 0f
        }
        look.fading?.let { if (it.isStopped) look.fading = null else it.target = 0f }
        if (!moving) return
        val random = level.random
        // A creak of the boards now and then, more on the turns and the slopes
        val dSteer = abs(look.steer - look.steerO)
        val chance = 0.006f + min(0.05f, dSteer * 0.012f) + abs(look.rawPitch) * 0.0006f + abs(look.roll - look.rollO) * 0.02f
        if (look.creakIn == 0 && random.nextFloat() < chance) {
            look.creakIn = 25
            sound(level, w, 0f, -22f, -16f, "creak", 0.3f + 0.2f * going, 0.85f + random.nextFloat() * 0.25f)
        }
        // The tilt's canvas flaps, more in the rain
        val rain = if (level.isRainingAt(BlockPos.containing(w.x, w.y + 2.0, w.z))) 2.5f else 1f
        if (look.tiltIn == 0 && random.nextFloat() < (0.004f + look.speed * 0.03f) * rain) {
            look.tiltIn = 40
            sound(level, w, 0f, -46f, -16f, "tilt", 0.25f + 0.25f * going, 0.9f + random.nextFloat() * 0.2f)
        }
        // The brake shoes squeal on the way down
        if (w.has(Wagon.FLAG_BRAKE) && look.rawPitch < -BRAKE_SLOPE && look.brakeIn == 0) {
            look.brakeIn = 60 + random.nextInt(50)
            sound(level, w, 0f, -10f, -14f, "brake", 0.45f, 0.9f + random.nextFloat() * 0.15f)
        }
    }

    /** Plays `caravan.[name]` at [px], [py], [pz] of [w]'s space. */
    private fun sound(level: Level, w: Wagon, px: Float, py: Float, pz: Float, name: String, volume: Float, pitch: Float) {
        val player = Minecraft.getInstance().player ?: return
        if (player.distanceToSqr(w) > HEAR * HEAR) return
        val at = Vector3d()
        pose.set(w.x, w.y, w.z, w.yRot, -w.xRot, look(w).roll).world(px, py, pz, at)
        CaravanSounds.play(level, net.minecraft.world.phys.Vec3(at.x, at.y, at.z), name, volume, pitch)
    }

    fun removed(w: Wagon) {
        val look = w.look as? WagonLook ?: return
        for (id in look.crew) if (id >= 0) CaravanClient.unride(id, w)
        look.rolling?.stopNow()
        look.fading?.stopNow()
    }

    // ---- The ground, as this client has it ----

    private val cursor = BlockPos.MutableBlockPos()

    /** The height of the ground at [x], [z] near [ref]: the top of the highest block from a little over it to a few blocks under it. */
    fun groundAt(level: Level, x: Double, z: Double, ref: Double): Double {
        val bx = Mth.floor(x)
        val bz = Mth.floor(z)
        val top = Mth.floor(ref + 1.25)
        val bottom = Mth.floor(ref - 3.0)
        val fx = x - bx
        val fz = z - bz
        for (by in top downTo bottom) {
            cursor.set(bx, by, bz)
            val state = level.getBlockState(cursor)
            if (state.isAir) continue
            val shape = state.getCollisionShape(level, cursor)
            if (shape.isEmpty) continue
            val h = shape.max(Direction.Axis.Y, fz, fx)
            if (h == Double.NEGATIVE_INFINITY) continue
            return by + h
        }
        return ref
    }

    /** The front turns this far at most; a horse less than this far (px) ahead of the kingpin steers nothing. */
    private const val MAX_STEER = 55f
    private const val MIN_AHEAD = 20f

    private const val ROLL_EASE = 0.35f
    private const val TAU = (PI * 2).toFloat()
    private const val WRAP = 2000f

    /** Ticks the overturn takes to reach the ground, the bounce after, and how far back it bounces. */
    const val TIP = 20f
    private const val BOUNCE = 3f
    private const val BOUNCE_BACK = 0.04f

    /** Degrees a tick the brake lever goes when it's let off or put on, and the tailboard. */
    private const val BRAKE_SPEED = 6f
    private const val TAIL_SPEED = 15f
    private const val DOWN_SPEED = 0.09f

    /** How rough the going is (the tilt's sway kicked by speed), and how far the sway and the lantern's swing go at most (degrees). */
    private const val ROUGH = 3f
    private const val SWAY_MOST = 2.5f
    private const val SWING_MOST = 12f
    private const val LANTERN_PULL = 400f
    private const val LANTERN_TURN = 300f

    /** Its going speed (blocks a tick), the speeds its roll is loudest and highest at, and the least that's moving. */
    const val CRUISE = 0.12f
    private const val LOUDEST = 0.14f
    private const val HIGHEST = 0.16f
    private const val MOVING = 0.008f

    /** Blocks off it's heard; degrees nose down it has to be for the brake to squeal. */
    private const val HEAR = 32.0
    private const val BRAKE_SLOPE = 4f

    /** Ticks between the pieces of a load put on all at once settling; px a tick a piece on top settles down onto what's under it. */
    private const val STAGGER = 2
    private const val REST_SPEED = 0.9f
}
