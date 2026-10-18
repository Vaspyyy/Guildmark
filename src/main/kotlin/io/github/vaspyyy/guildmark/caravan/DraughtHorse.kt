package io.github.vaspyyy.guildmark.caravan

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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * A caravan's heavy horse (design-road.md section 8, question 2): in its collar and hames between the wagon's
 * shafts, its traces back to the swingletree. A prop as the wagon is: its [Show] places it each tick (on the road
 * ahead of the wagon, its hooves on the ground under them), and says what it's doing ([move]); the clients play its
 * clips (client/caravan), walking and straining by the ground it covers.
 */
class DraughtHorse(type: EntityType<out DraughtHorse>, level: Level) : Entity(type, level) {

    /** What it's doing, as the clients play it. */
    enum class Move(val loop: Boolean) {
        WALK(true), BOLT(true), IDLE(true), GRAZE(true), NOSEBAG(true), REAR(false), HIT(false), COLLAPSE(false), LIE(true);

        companion object {
            fun byId(id: Int): Move = entries.getOrElse(id) { IDLE }
        }
    }

    /** Its coat: one of client/caravan's DraughtHorseMesh.COATS. */
    val coat: Int get() = entityData.get(COAT).toInt()

    val move: Move get() = Move.byId(entityData.get(MOVE).toInt())

    /** Goes up each time a move starts, so the clients start a one-shot (a rear, a hit) over even if it's the same. */
    val serial: Int get() = entityData.get(SERIAL)

    /** [FLAG_REINS_TIED] and its kin. */
    val flags: Int get() = entityData.get(FLAGS).toInt()

    fun has(flag: Int): Boolean = flags and flag != 0

    /** The wagon it's in the shafts of (an entity id), or -1 once it's loose. */
    val wagonId: Int get() = entityData.get(WAGON)

    /** What this client keeps to draw it (client/caravan); null on the server. */
    @JvmField
    var look: Any? = null

    /** Degrees its nose is up, as placed (also its xRot, negated). */
    var pitch = 0f
        private set

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(COAT, 0)
        builder.define(MOVE, Move.IDLE.ordinal.toByte())
        builder.define(SERIAL, 0)
        builder.define(FLAGS, 0)
        builder.define(WAGON, -1)
    }

    override fun createInterpolationHandler(): InterpolationHandler = SteppedInterpolationHandler.create(this)

    override fun tick() {
        if (level().isClientSide) clientTick?.invoke(this)
        else if (!Bodies.owns(this)) discard()
    }

    override fun onClientRemoval() {
        super.onClientRemoval()
        clientRemoved?.invoke(this)
    }

    // ---- A prop ----

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

    // ---- The server's side ----

    internal fun coat(coat: Int) = entityData.set(COAT, coat.toByte())

    internal fun wagon(wagon: Wagon?) = entityData.set(WAGON, wagon?.id ?: -1)

    /** Starts [what]; a loop already going goes on (unless [again]), a one-shot starts over. */
    internal fun move(what: Move, again: Boolean = false) {
        if (what == move && what.loop && !again) return
        entityData.set(MOVE, what.ordinal.toByte())
        entityData.set(SERIAL, serial + 1)
        moveStart = tickCount
    }

    /** The tick the move playing began. */
    var moveStart = 0
        private set

    val moveTicks: Int get() = tickCount - moveStart

    internal fun flag(flag: Int, on: Boolean) {
        val f = flags
        val next = if (on) f or flag else f and flag.inv()
        if (next != f) entityData.set(FLAGS, next.toByte())
    }

    // The ground under its fore and hind feet, eased over steps
    private var groundFore = Double.NaN
    private var groundHind = Double.NaN

    /**
     * Stands it with the middle of its body over [at], facing [yaw], its hooves on the ground under them (near the
     * road's height [ref]); [snap] takes the ground as it is.
     */
    internal fun place(level: ServerLevel, at: Vec3, yaw: Float, ref: Double, snap: Boolean) {
        val r = Math.toRadians(yaw.toDouble())
        val fx = -sin(r)
        val fz = cos(r)
        val fore = Ground.at(level, at.x + fx * HOOVES, at.z + fz * HOOVES, ref)
        val hind = Ground.at(level, at.x - fx * HOOVES, at.z - fz * HOOVES, ref)
        val fresh = snap || groundFore.isNaN() || groundHind.isNaN()
        groundFore = ease(groundFore, fore, ref, fresh)
        groundHind = ease(groundHind, hind, ref, fresh)
        val y = (groundFore + groundHind) * 0.5
        pitch = Math.toDegrees(asin(((groundFore - groundHind) / (2 * HOOVES)).coerceIn(-0.6, 0.6))).toFloat()
        if (fresh) {
            snapTo(at.x, y, at.z, yaw, -pitch)
            setOldPosAndRot()
        } else {
            setPos(at.x, y, at.z)
            yRot = Mth.wrapDegrees(yaw)
            xRot = -pitch
        }
    }

    /** Puts it at [at] facing [yaw] as it is (a loose horse's own run, or lying where it fell). */
    internal fun set(at: Vec3, yaw: Float, pitch: Float = 0f) {
        this.pitch = pitch
        setPos(at.x, at.y, at.z)
        yRot = Mth.wrapDegrees(yaw)
        xRot = -pitch
    }

    private fun ease(was: Double, now: Double, ref: Double, fresh: Boolean): Double {
        val target = if (now.isNaN()) (if (was.isNaN()) ref else was) else now
        if (fresh || was.isNaN()) return target
        return was + (target - was).coerceIn(-0.4, 0.25)
    }

    companion object {
        private val COAT: EntityDataAccessor<Byte> = SynchedEntityData.defineId(DraughtHorse::class.java, EntityDataSerializers.BYTE)
        private val MOVE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(DraughtHorse::class.java, EntityDataSerializers.BYTE)
        private val SERIAL: EntityDataAccessor<Int> = SynchedEntityData.defineId(DraughtHorse::class.java, EntityDataSerializers.INT)
        private val FLAGS: EntityDataAccessor<Byte> = SynchedEntityData.defineId(DraughtHorse::class.java, EntityDataSerializers.BYTE)
        private val WAGON: EntityDataAccessor<Int> = SynchedEntityData.defineId(DraughtHorse::class.java, EntityDataSerializers.INT)

        /** Nobody holds the reins: they're tied up at the saddle. */
        const val FLAG_REINS_TIED = 1

        /** Cut loose from the wagon: the traces' cut ends hang from the collar. */
        const val FLAG_CUT = 2

        /** Its nosebag on, at the stand. */
        const val FLAG_NOSEBAG = 4

        /** Lying dead: the reins cut too. */
        const val FLAG_DEAD = 8

        /** Leaning into the collar: starting the wagon, or a climb. */
        const val FLAG_STRAIN = 16

        /** Blocks from the middle of its body to its fore and hind feet. */
        const val HOOVES = 11.0 / 16.0

        /** The coat caravan [caravan]'s horse has, the same each time it's shown (and lying dead by its wreck). */
        fun coatOf(caravan: Long): Int = Math.floorMod(caravan * 7L + 3L, 3L).toInt()

        /** Run on the client each tick for each horse (client/caravan: its gait, its hooves, its harness's sounds). */
        @JvmStatic
        var clientTick: ((DraughtHorse) -> Unit)? = null

        @JvmStatic
        var clientRemoved: ((DraughtHorse) -> Unit)? = null
    }
}
