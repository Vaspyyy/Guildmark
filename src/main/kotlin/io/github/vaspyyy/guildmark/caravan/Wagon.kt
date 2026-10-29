package io.github.vaspyyy.guildmark.caravan

import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.InterpolationHandler
import net.minecraft.world.entity.SteppedInterpolationHandler
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A caravan's wagon on the road (living-world/design-road.md section 1; the mesh is client/caravan's WagonMesh): four
 * spoked wheels, bigger behind, a staked bed under a canvas tilt, the bench with its brake lever, the lantern and
 * the pennant, its load heaped on the bed from what it really carries.
 *
 * A prop, as the handcart is: never saved, never hit, pushed or ridden. Its [Show] places it each tick after everyone
 * has moved, the back axle where the record has it along its leg and the front toward its horse, standing on the
 * ground under each wheel; the clients turn its wheels by how far each rolled, steer its front toward the horse and
 * sway its tilt, lantern and pennant (client/caravan).
 */
class Wagon(type: EntityType<out Wagon>, level: Level) : Entity(type, level) {

    // ---- What every client sees ----

    /** The horse in its shafts (an entity id), or -1. */
    val horseId: Int get() = entityData.get(HORSE)

    /** The crew: the driver (0) and up to three guards (1 to 3), entity ids or -1. */
    fun crew(i: Int): Int = entityData.get(CREW[i])

    /** Where crew member [i] is placed on the wagon: [OFF], or one of [DRIVER], [BENCH], [TAIL] and [STAND]. */
    fun seat(i: Int): Int = (entityData.get(SEATS) shr (i * 3)) and 7

    /** Degrees it leans over, its right side up (the back wheels standing differently); and the front axle's own rock on its turntable. */
    val roll: Float get() = entityData.get(ROLL)
    val rock: Float get() = entityData.get(ROCK)

    /** The good heaped on load place [slot] (WagonMesh.SLOTS), or null. */
    fun piece(slot: Int): Good? = good(if (slot < 8) entityData.get(LOAD_A) else entityData.get(LOAD_B), slot % 8)

    /** The good set down on place [slot] of the stack by its back wheel while it's unloaded, or null. */
    fun stacked(slot: Int): Good? = good(if (slot < 8) entityData.get(STACK_A) else entityData.get(STACK_B), slot % 8)

    /** [FLAG_BRAKE] and its kin, with the arrows in its boards from bit [ARROWS]. */
    val flags: Int get() = entityData.get(FLAGS)

    fun has(flag: Int): Boolean = flags and flag != 0

    /** Which of the six arrows the band shot into it stand in its boards (WagonMesh's "w_arrow0" to 5). */
    val arrows: Int get() = (flags shr ARROWS) and 63

    /** Goes up when it's shoved over: the clients turn it onto its side from when they see it change. */
    val tipped: Int get() = entityData.get(TIP)

    /** A trading house's wagon's pennant colour (RGB, trade part G), or -1: it flies the caravans' own. */
    val tint: Int get() = entityData.get(TINT)

    /** What this client keeps to draw it (client/caravan); null on the server. */
    @JvmField
    var look: Any? = null

    // ---- Kept by the server ----

    /** The caravan it carries for (its record's id). */
    var caravan = -1L

    // The ground under each wheel, eased over steps
    private var groundRR = Double.NaN
    private var groundRL = Double.NaN
    private var groundFR = Double.NaN
    private var groundFL = Double.NaN
    private var placed = false

    /** Degrees its nose is up, as placed. */
    var pitch = 0f
        private set

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(HORSE, -1)
        for (c in CREW) builder.define(c, -1)
        builder.define(SEATS, 0)
        builder.define(ROLL, 0f)
        builder.define(ROCK, 0f)
        builder.define(LOAD_A, 0L)
        builder.define(LOAD_B, 0L)
        builder.define(STACK_A, 0L)
        builder.define(STACK_B, 0L)
        builder.define(FLAGS, FLAG_TAILBOARD_SHUT)
        builder.define(TIP, 0)
        builder.define(TINT, -1)
    }

    // Drawn a tick behind, as the townsfolk and the handcarts are
    override fun createInterpolationHandler(): InterpolationHandler = SteppedInterpolationHandler.create(this)

    override fun tick() {
        // Nothing of a mob's: the server places it (Show), the client animates it (client/caravan)
        if (level().isClientSide) clientTick?.invoke(this)
        else if (!Bodies.owns(this)) discard()
    }

    override fun onClientRemoval() {
        super.onClientRemoval()
        clientRemoved?.invoke(this)
    }

    // ---- A prop: no riding, pushing, hitting or bumping ----

    override fun isPickable(): Boolean = false
    override fun isPushable(): Boolean = false
    override fun canBeCollidedWith(entity: Entity?): Boolean = false
    override fun canBeHitByProjectile(): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun skipAttackInteraction(attacker: Entity): Boolean = true
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false
    override fun ignoreExplosion(explosion: Explosion): Boolean = true
    override fun isIgnoringBlockTriggers(): Boolean = true
    override fun isPushedByFluid(): Boolean = false
    override fun canUsePortal(allowPassengers: Boolean): Boolean = false
    override fun push(x: Double, y: Double, z: Double) {}
    override fun shouldBeSaved(): Boolean = false
    override fun readAdditionalSaveData(input: ValueInput) {}
    override fun addAdditionalSaveData(output: ValueOutput) {}

    // ---- The server's side of it ----

    internal fun horse(horse: DraughtHorse?) = entityData.set(HORSE, horse?.id ?: -1)

    internal fun crew(i: Int, id: Int) = entityData.set(CREW[i], id)

    internal fun tint(rgb: Int) = entityData.set(TINT, rgb)

    internal fun seat(i: Int, seat: Int) {
        val all = entityData.get(SEATS)
        entityData.set(SEATS, (all and (7 shl (i * 3)).inv()) or ((seat and 7) shl (i * 3)))
    }

    internal fun flag(flag: Int, on: Boolean) {
        val f = entityData.get(FLAGS)
        val next = if (on) f or flag else f and flag.inv()
        if (next != f) entityData.set(FLAGS, next)
    }

    /** One more of the band's arrows thuds into it; false once all six are in. */
    internal fun arrow(level: ServerLevel): Boolean {
        val have = arrows
        val free = (0 until 6).filter { have and (1 shl it) == 0 }
        if (free.isEmpty()) return false
        val k = free[level.random.nextInt(free.size)]
        entityData.set(FLAGS, flags or (1 shl (ARROWS + k)))
        return true
    }

    /** Heaps [slots] on the bed (a good or null for each of the 16 places). */
    internal fun load(slots: List<Good?>) {
        entityData.set(LOAD_A, pack(slots, 0))
        entityData.set(LOAD_B, pack(slots, 8))
    }

    /** Sets down [slots] on the stack by its back wheel (16 places). */
    internal fun stack(slots: List<Good?>) {
        entityData.set(STACK_A, pack(slots, 0))
        entityData.set(STACK_B, pack(slots, 8))
    }

    internal fun tip() = entityData.set(TIP, tipped + 1)

    /**
     * Stands it with its back axle at [rear] and its kingpin toward [kingpin], its front heading toward [horse]: on the
     * ground under each wheel (near the road's heights [rearRef] and [frontRef]), its nose up and its sides leaning as
     * they stand, eased over steps and edges; [snap] takes the ground as it is, as for a wagon just placed.
     */
    internal fun place(level: ServerLevel, rear: Vec3, kingpin: Vec3, horse: Vec3, rearRef: Double, frontRef: Double, snap: Boolean) {
        val dx = kingpin.x - rear.x
        val dz = kingpin.z - rear.z
        val len = hypot(dx, dz).coerceAtLeast(1e-6)
        val hx = dx / len
        val hz = dz / len
        // Right of its heading, and of the front's (toward the horse)
        val rx = -hz
        val rz = hx
        val fdx = horse.x - kingpin.x
        val fdz = horse.z - kingpin.z
        val flen = hypot(fdx, fdz).coerceAtLeast(1e-6)
        val fhx = fdx / flen
        val fhz = fdz / flen
        val frx = -fhz
        val frz = fhx
        val ht = Train.HALF_TRACK
        val rr = Ground.wheel(level, rear.x + rx * ht, rear.z + rz * ht, hx, hz, rearRef, REAR_RADIUS)
        val rl = Ground.wheel(level, rear.x - rx * ht, rear.z - rz * ht, hx, hz, rearRef, REAR_RADIUS)
        val fr = Ground.wheel(level, kingpin.x + frx * ht, kingpin.z + frz * ht, fhx, fhz, frontRef, FRONT_RADIUS)
        val fl = Ground.wheel(level, kingpin.x - frx * ht, kingpin.z - frz * ht, fhx, fhz, frontRef, FRONT_RADIUS)
        val fresh = snap || !placed
        groundRR = ease(groundRR, rr, rearRef, fresh)
        groundRL = ease(groundRL, rl, rearRef, fresh)
        groundFR = ease(groundFR, fr, frontRef, fresh)
        groundFL = ease(groundFL, fl, frontRef, fresh)
        placed = true
        val back = (groundRR + groundRL) * 0.5
        val front = (groundFR + groundFL) * 0.5
        pitch = Math.toDegrees(asin(((front - back) / Train.WHEELBASE).coerceIn(-0.6, 0.6))).toFloat()
        val roll = Math.toDegrees(asin(((groundRR - groundRL) / (2 * ht)).coerceIn(-0.5, 0.5))).toFloat()
        val frontRoll = Math.toDegrees(asin(((groundFR - groundFL) / (2 * ht)).coerceIn(-0.5, 0.5))).toFloat()
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        if (fresh) {
            snapTo(rear.x, back, rear.z, yaw, -pitch)
            setOldPosAndRot()
        } else {
            setPos(rear.x, back, rear.z)
            yRot = yaw
            xRot = -pitch
        }
        entityData.set(ROLL, roll)
        entityData.set(ROCK, Mth.clamp(frontRoll - roll, -8f, 8f))
    }

    /** Drops what it knows of the ground, so the next [place] takes it afresh. */
    internal fun unplace() {
        placed = false
    }

    private fun ease(was: Double, now: Double, ref: Double, fresh: Boolean): Double {
        val target = if (now.isNaN()) (if (was.isNaN()) ref else was) else now
        if (fresh || was.isNaN()) return target
        val d = target - was
        return was + d.coerceIn(-FALL, RISE)
    }

    // ---- Where things are on it ----

    /** Where [px], [py], [pz] of the wagon's own space (px, y down, its front -z, its right -x, as WagonMesh is) is in the world now. */
    fun point(px: Float, py: Float, pz: Float): Vec3 = frame(x, y, z, yRot, -xRot, roll).point(px, py, pz)

    /** Where seat [seat] ([DRIVER] and its kin) is in the world now: the seat's top under the sitter's hips, and the sitter's yaw. */
    fun seatAt(seat: Int): Pair<Vec3, Float> {
        val s = seatOf(seat) ?: return position() to yRot
        return point(s.x, s.y, s.z) to Mth.wrapDegrees(yRot + s.yaw)
    }

    companion object {
        private val HORSE: EntityDataAccessor<Int> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.INT)
        private val CREW: Array<EntityDataAccessor<Int>> = Array(4) { SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.INT) }
        private val SEATS: EntityDataAccessor<Int> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.INT)
        private val ROLL: EntityDataAccessor<Float> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.FLOAT)
        private val ROCK: EntityDataAccessor<Float> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.FLOAT)
        private val LOAD_A: EntityDataAccessor<Long> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.LONG)
        private val LOAD_B: EntityDataAccessor<Long> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.LONG)
        private val STACK_A: EntityDataAccessor<Long> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.LONG)
        private val STACK_B: EntityDataAccessor<Long> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.LONG)
        private val FLAGS: EntityDataAccessor<Int> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.INT)
        private val TIP: EntityDataAccessor<Int> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.INT)
        private val TINT: EntityDataAccessor<Int> = SynchedEntityData.defineId(Wagon::class.java, EntityDataSerializers.INT)

        /** Where a crew member is: on the road, or on the driver's end of the bench, its other end, the let-down tailboard, or standing on it to hand the load down. */
        const val OFF = 0
        const val DRIVER = 1
        const val BENCH = 2
        const val TAIL = 3
        const val STAND = 4

        /** The brake is on; the tailboard is shut; the lantern's lit; the traces are cut (they lie on the road); it goes over to its left; the driver has the nosebag in hand. */
        const val FLAG_BRAKE = 1
        const val FLAG_TAILBOARD_SHUT = 2
        const val FLAG_LANTERN = 4
        const val FLAG_CUT = 8
        const val FLAG_MIRROR = 16
        const val FLAG_NOSEBAG = 32

        /** The arrows in its boards are flag bits from here, six of them. */
        const val ARROWS = 8

        /** The places on the bed, and on the stack beside it. */
        const val SLOTS = 16

        /**
         * The stack set down by the right back wheel while it's unloaded: x and z (px, the wagon's space) of each of
         * the eight places on the ground; places 8 to 15 are on top of 0 to 7.
         */
        val STACK_XZ: List<FloatArray> = listOf(
            floatArrayOf(-30f, 2f), floatArrayOf(-30f, 12f), floatArrayOf(-30f, 22f), floatArrayOf(-30f, 32f),
            floatArrayOf(-41f, 2f), floatArrayOf(-41f, 12f), floatArrayOf(-41f, 22f), floatArrayOf(-41f, 32f),
        )

        /** The back wheels' radius and the front's, in blocks (WagonMesh.REAR_RADIUS, FRONT_RADIUS). */
        const val REAR_RADIUS = 12.0 / 16.0
        const val FRONT_RADIUS = 8.5 / 16.0

        /** How far a wheel's ground may rise and fall in a tick. */
        private const val RISE = 0.25
        private const val FALL = 0.4

        /** Run on the client each tick for each wagon (client/caravan: its wheels, its load as drawn, its sounds). */
        @JvmStatic
        var clientTick: ((Wagon) -> Unit)? = null

        /** Run on the client when a wagon goes. */
        @JvmStatic
        var clientRemoved: ((Wagon) -> Unit)? = null

        /** The seats as WagonMesh has them, by [DRIVER] and its kin. */
        class Seat(val x: Float, val y: Float, val z: Float, val yaw: Float)

        private val SEAT_DRIVER = Seat(-6.5f, -27.5f, -45.5f, 0f)
        private val SEAT_BENCH = Seat(6.5f, -27.5f, -45.5f, 0f)
        private val SEAT_TAIL = Seat(0f, -19f, 17f, 180f)
        private val SEAT_STAND = Seat(0f, -19f, 12.5f, 180f)

        fun seatOf(seat: Int): Seat? = when (seat) {
            DRIVER -> SEAT_DRIVER
            BENCH -> SEAT_BENCH
            TAIL -> SEAT_TAIL
            STAND -> SEAT_STAND
            else -> null
        }

        /** How a wagon stood at [x], [y], [z] heading [yaw], its nose [pitch] up and leaning [roll] (its right side up): where its own space's points are. */
        fun frame(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, roll: Float): Frame = Frame(x, y, z, yaw, pitch, roll)

        private fun pack(slots: List<Good?>, from: Int): Long {
            var out = 0L
            for (k in 0 until 8) {
                val g = slots.getOrNull(from + k) ?: continue
                out = out or ((g.ordinal + 1).toLong() shl (k * 8))
            }
            return out
        }

        private fun good(bits: Long, k: Int): Good? {
            val v = ((bits shr (k * 8)) and 0xFF).toInt()
            return if (v == 0) null else Good.entries.getOrNull(v - 1)
        }
    }

    /**
     * A wagon's frame in the world: the back axle's ground at [x], [y], [z], turned [yaw] (0 south), tipped [pitch] up
     * and leaning [roll] (its right side up) about the back axle, as client/caravan draws it.
     */
    class Frame(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val roll: Float) {
        private val q = Quaternionf()
            .rotationY(Math.toRadians(180.0 - yaw).toFloat())
            .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
            .rotateZ(Math.toRadians(roll.toDouble()).toFloat())
        private val v = Vector3f()

        /** Where [px], [py], [pz] of the wagon's own space is in the world. */
        fun point(px: Float, py: Float, pz: Float): Vec3 {
            val axle = REAR_RADIUS.toFloat()
            // Model space flipped (x and y), about the back axle
            v.set(-px / 16f, -py / 16f - axle, pz / 16f)
            q.transform(v)
            return Vec3(x + v.x, y + axle + v.y, z + v.z)
        }

        /** The world's [p] in the wagon's own space (px, y down), the other way round from [point]. */
        fun local(p: Vec3): Vector3f {
            val axle = REAR_RADIUS.toFloat()
            v.set((p.x - x).toFloat(), (p.y - y).toFloat() - axle, (p.z - z).toFloat())
            Quaternionf(q).conjugate().transform(v)
            return Vector3f(-v.x * 16f, -(v.y + axle) * 16f, v.z * 16f)
        }
    }
}

/** How far apart two places are across the ground. */
internal fun flat(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dz = a.z - b.z
    return sqrt(dx * dx + dz * dz)
}
