package io.github.vaspyyy.guildmark.combat

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One blow of an enemy attack. It lands [at] ticks into the attack (and stays live [until] then, for a
 * charge), on everything within [reach] blocks of its centre and [arc] degrees either side of where the
 * attacker faces (180: all round). The centre sits [forward] blocks in front of the attacker; a blow with
 * an [inner] distance only hits beyond it, like a ring of fire.
 */
class Blow(
    val at: Int,
    val damage: Float,
    val reach: Double,
    val arc: Float = 60f,
    val poise: Float = 15f,
    val parryable: Boolean = true,
    val knockback: Float = 0.4f,
    val until: Int = at,
    val inner: Double = 0.0,
    val forward: Double = 0.0,
    /** Multiplies the stamina a shield loses blocking it. */
    val guard: Float = 1f,
    /** Seconds it sets its victims alight for. */
    val fire: Int = 0,
    /** Blows in the same group hit each victim once between them (a wave that rolls on over several ticks). */
    val group: Int = -1,
)

/**
 * One of a creature's attacks: the wind-up that gives it away, the blows, and the recovery in which it's
 * open. Clients animate it from assets/guildmark/animations/<creature>/<anim>.json, which lasts [length]
 * ticks with its blows on the same ticks as here (art/scripts/enemy_anims.py).
 */
class EnemyAttack(
    val id: Int,
    val anim: String,
    val length: Int,
    val blows: List<Blow>,
    /** Ticks in which the creature still turns to face its target; the rest of the attack is committed. */
    val tracking: List<IntRange>,
    val minRange: Double = 0.0,
    val maxRange: Double = 3.0,
    val weight: Int = 1,
    /** Ticks it waits after this attack before the next. */
    val cooldown: Int = 20,
    /** A push forward of [lunge] blocks a tick from [lungeAt] for [lungeTicks] ticks. */
    val lunge: Float = 0f,
    val lungeAt: Int = 0,
    val lungeTicks: Int = 1,
    /** Degrees a tick it can turn while tracking. */
    val turn: Float = 14f,
    /** The first phase of a boss fight in which it's used. */
    val phase: Int = 1,
)

/**
 * A creature built for the new combat. Each attack winds up where players can see it coming, commits,
 * lands its blows through vanilla damage (so shields, parries, rolls and armour all apply) and leaves an
 * opening. Clients are told which attack started and play its animation.
 */
abstract class CombatEnemy(type: EntityType<out CombatEnemy>, level: Level) : Monster(type, level), CombatMob {
    /** Every attack this creature knows, each with a distinct id from 1. */
    abstract val attacks: List<EnemyAttack>
    /** The name its animations live under, for example "hollow_soldier". */
    abstract val animations: String
    open val isBoss: Boolean get() = false
    /** Blocks between it and its target (edge to edge) it likes to keep while waiting to attack. */
    open val waitRange: Double get() = 2.5
    open val chaseSpeed: Double get() = 1.15
    /** Scales the rest it takes between attacks (a boss grows relentless). */
    protected open val cooldownScale: Float get() = 1f

    /** Whether it fights at all right now (a boss waits for its fight to begin). */
    open fun canFight(): Boolean = true

    /** The attack under way on the server, and how many ticks into it. */
    var attack: EnemyAttack? = null
        private set
    var attackTick = 0
        private set
    /** Ticks before it may start another attack. */
    var cooldown = 20
    private var serial = 0
    /** Who each blow of the attack under way has hit: (blow group << 32) | entity id. */
    private val struck = LongOpenHashSet()
    private var resolving: Blow? = null

    // What clients last heard, for the animations, and the attack it cut short (faded out underneath)
    private var clientAttack = 0
    private var clientAttackStart = 0
    private var cutShort: EnemyAttack? = null
    private var cutShortTime = 0f
    private var cutShortAt = 0

    fun attackById(id: Int): EnemyAttack? = attacks.firstOrNull { it.id == id }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_ATTACK, 0)
        builder.define(DATA_FLAGS, 0)
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (accessor == DATA_ATTACK && level().isClientSide) {
            val old = attackById(clientAttack)
            val into = (tickCount - clientAttackStart).toFloat()
            cutShort = if (old != null && into < old.length) old else null
            cutShortTime = into
            cutShortAt = tickCount
            clientAttack = entityData.get(DATA_ATTACK) and 0xFF
            clientAttackStart = tickCount
        }
    }

    protected fun flag(bit: Int): Boolean = entityData.get(DATA_FLAGS) and bit != 0

    protected fun setFlag(bit: Int, on: Boolean) {
        val flags = entityData.get(DATA_FLAGS)
        entityData.set(DATA_FLAGS, if (on) flags or bit else flags and bit.inv())
    }

    /** On a client: the attack it's seen doing and how far into it, or null. */
    fun shownAttack(partial: Float): Pair<EnemyAttack, Float>? {
        val a = attackById(clientAttack) ?: return null
        val time = tickCount - clientAttackStart + partial
        return if (time < a.length) a to time else null
    }

    /** On a client: the attack the current one (or a stagger) cut short, where it stopped, and how much of it still shows. */
    fun cutShortAttack(partial: Float): Triple<EnemyAttack, Float, Float>? {
        val a = cutShort ?: return null
        val since = tickCount - cutShortAt + partial
        return if (since < CUT_SHORT_FADE) Triple(a, cutShortTime, 1f - since / CUT_SHORT_FADE) else null
    }

    // ---- Attacking ----

    fun startAttack(next: EnemyAttack) {
        attack = next
        attackTick = 0
        struck.clear()
        serial = (serial + 1) and 0xFFFF
        entityData.set(DATA_ATTACK, (serial shl 8) or next.id)
        navigation.stop()
        onAttackStart(next)
    }

    /** The attack ended, ran its course or was cut short (a parry, a stagger). */
    fun stopAttack(interrupted: Boolean) {
        val a = attack ?: return
        attack = null
        cooldown = if (interrupted) 10 else ((a.cooldown + random.nextInt(a.cooldown / 2 + 1)) * cooldownScale).toInt()
        entityData.set(DATA_ATTACK, serial shl 8)
    }

    /** The attack it would start now against [target], [distance] blocks away (edge to edge), if any. */
    open fun chooseAttack(target: LivingEntity, distance: Double): EnemyAttack? {
        val options = attacks.filter { usable(it, target, distance) }
        val total = options.sumOf { it.weight }
        if (total <= 0) return null
        var roll = random.nextInt(total)
        for (option in options) {
            roll -= option.weight
            if (roll < 0) return option
        }
        return null
    }

    protected open fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean =
        attack.weight > 0 && distance in attack.minRange..attack.maxRange

    /** Sounds and effects for the attack's first tick. */
    protected open fun onAttackStart(attack: EnemyAttack) {}

    /** Sounds and effects on [tick] of [attack]; it may also move the creature (a leap). */
    protected open fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {}

    /** A blow landed on [victim]. */
    protected open fun onBlowLanded(level: ServerLevel, victim: LivingEntity, blow: Blow) {}

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        val a = attack
        if (a == null) {
            if (cooldown > 0) cooldown--
            return
        }
        if (Stance.isStaggered(this)) {
            stopAttack(true)
            return
        }
        val t = attackTick
        val target = target
        if (target != null && target.isAlive && a.tracking.any { t in it }) face(target, a.turn) else holdFacing()
        if (a.lunge > 0f && t >= a.lungeAt && t < a.lungeAt + a.lungeTicks) {
            val look = Vec3.directionFromRotation(0f, yRot)
            val push = a.lunge * (if (onGround()) 1.0 else 0.3)
            deltaMovement = Vec3(look.x * push, deltaMovement.y, look.z * push)
        }
        onAttackTick(level, a, t)
        for ((i, blow) in a.blows.withIndex()) {
            if (attack !== a) return
            if (t in blow.at..blow.until) resolve(level, blow, if (blow.group >= 0) 1000 + blow.group else i)
        }
        if (attack !== a) return
        attackTick++
        if (attackTick >= a.length) stopAttack(false)
    }

    /** Turns towards [target], at most [step] degrees. */
    protected fun face(target: LivingEntity, step: Float) {
        val wanted = Math.toDegrees(Math.atan2(-(target.x - x), target.z - z)).toFloat()
        yRot += Mth.clamp(Mth.wrapDegrees(wanted - yRot), -step, step)
        holdFacing()
    }

    private fun holdFacing() {
        yBodyRot = yRot
        yHeadRot = yRot
    }

    /** Lands [blow] on everything it reaches that its [group] hasn't hit yet this attack. */
    private fun resolve(level: ServerLevel, blow: Blow, group: Int) {
        val look = Vec3.directionFromRotation(0f, yRot)
        val centre = position().add(look.x * blow.forward, 0.0, look.z * blow.forward)
        val box = AABB(centre.x, y, centre.z, centre.x, y + bbHeight, centre.z).inflate(blow.reach + 1.5, 1.0, blow.reach + 1.5)
        for (victim in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
            val key = (group.toLong() shl 32) or (victim.id.toLong() and 0xFFFFFFFFL)
            if (victim === this || !victim.isAlive || struck.contains(key) || !canHit(victim)) continue
            val dx = victim.x - centre.x
            val dz = victim.z - centre.z
            val centreDistance = sqrt(dx * dx + dz * dz)
            val flat = centreDistance - victim.bbWidth / 2
            if (flat > blow.reach || centreDistance + victim.bbWidth / 2 < blow.inner) continue
            if (victim.y > y + bbHeight + 0.5 || victim.boundingBox.maxY < y - 0.5) continue
            if (blow.arc < 180f && Math.sqrt((victim.x - x) * (victim.x - x) + (victim.z - z) * (victim.z - z)) > 0.6 &&
                Combat.angleTo(yRot, victim.x - x, victim.z - z) > blow.arc) continue
            if (flat > 1.0 && !hasLineOfSight(victim)) continue
            struck.add(key)
            hit(level, victim, blow, centre)
            if (attack == null) return
        }
    }

    private fun hit(level: ServerLevel, victim: LivingEntity, blow: Blow, centre: Vec3) {
        val damage = getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat() * blow.damage
        val source = damageSources().mobAttack(this)
        resolving = blow
        val landed = try {
            victim.hurtServer(level, source, damage)
        } finally {
            resolving = null
        }
        if (!landed) return
        if (blow.knockback > 0f) {
            if (blow.arc >= 180f) {
                victim.knockback(blow.knockback.toDouble(), centre.x - victim.x, centre.z - victim.z, source, damage)
            } else {
                val r = yRot * Mth.DEG_TO_RAD
                victim.knockback(blow.knockback.toDouble(), sin(r).toDouble(), -cos(r).toDouble(), source, damage)
            }
        }
        if (blow.fire > 0) victim.igniteForSeconds(blow.fire.toFloat())
        setLastHurtMob(victim)
        onBlowLanded(level, victim, blow)
    }

    /** Players, its target, and anything that isn't a monster itself. */
    protected open fun canHit(victim: LivingEntity): Boolean = when {
        victim is Player -> !victim.isSpectator && !victim.isCreative
        victim === target -> true
        victim is Enemy || isAlliedTo(victim) -> false
        else -> victim is Mob
    }

    /** A flash of light where a blow will come from, so a blow is readable before it lands. */
    protected fun glint(level: ServerLevel, particle: ParticleOptions, height: Double, forward: Double, count: Int = 6) {
        val look = Vec3.directionFromRotation(0f, yRot)
        level.sendParticles(particle, x + look.x * forward, y + height, z + look.z * forward, count, 0.15, 0.15, 0.15, 0.02)
    }

    // ---- CombatMob ----

    override fun canBeParried(): Boolean = resolving?.parryable ?: false
    override fun poiseDamage(): Float = resolving?.poise ?: 12f
    override fun guardDamage(): Float = resolving?.guard ?: 1f

    override fun canBeBackstabbed(): Boolean =
        !isBoss && (if (level().isClientSide) clientAttack == 0 else attack == null)

    override fun onStaggered(ticks: Int, kind: Int) {
        if (ticks > 0) stopAttack(true)
    }

    override fun removeWhenFarAway(distance: Double): Boolean = !isBoss && super.removeWhenFarAway(distance)

    companion object {
        private const val CUT_SHORT_FADE = 5f

        /** (serial << 8) | attack id; the serial makes the same attack twice in a row still restart. */
        val DATA_ATTACK: EntityDataAccessor<Int> = SynchedEntityData.defineId(CombatEnemy::class.java, EntityDataSerializers.INT)
        val DATA_FLAGS: EntityDataAccessor<Int> = SynchedEntityData.defineId(CombatEnemy::class.java, EntityDataSerializers.INT)
    }
}

/**
 * How a [CombatEnemy] fights: closes in, waits its turn at a little distance (circling), then picks an
 * attack. The attack itself is driven by the creature until it ends.
 */
class FightGoal(private val mob: CombatEnemy) : Goal() {
    private var strafe = 1f
    private var strafeTicks = 0
    private var repath = 0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean = mob.target?.isAlive == true && mob.canFight()

    override fun canContinueToUse(): Boolean = canUse()

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun stop() {
        mob.navigation.stop()
        mob.moveControl.strafe(0f, 0f)
    }

    override fun tick() {
        val target = mob.target ?: return
        if (mob.attack != null || Stance.isStaggered(mob)) return
        mob.lookControl.setLookAt(target, 30f, 30f)
        val distance = mob.distanceTo(target) - target.bbWidth / 2 - mob.bbWidth / 2
        val seen = mob.sensing.hasLineOfSight(target)
        if (mob.cooldown <= 0 && seen) {
            mob.chooseAttack(target, distance.toDouble())?.let {
                mob.moveControl.strafe(0f, 0f)
                mob.startAttack(it)
                return
            }
        }
        if (mob.cooldown <= 0 || distance > mob.waitRange + 2.5 || !seen) {
            // Close in
            if (--repath <= 0) {
                repath = 5 + mob.random.nextInt(5)
                mob.navigation.moveTo(target, mob.chaseSpeed)
            }
            return
        }
        // Waiting for an opening: circle at a little distance, backing off when too close
        mob.navigation.stop()
        if (--strafeTicks <= 0) {
            strafeTicks = 20 + mob.random.nextInt(40)
            strafe = if (mob.random.nextBoolean()) 1f else -1f
        }
        val forward = when {
            distance < mob.waitRange - 1.0 -> -0.5f
            distance > mob.waitRange + 1.0 -> 0.4f
            else -> 0f
        }
        mob.moveControl.strafe(forward, strafe * 0.45f)
        mob.yRot = Mth.rotateIfNecessary(mob.yRot, mob.yHeadRot, 30f)
    }
}
