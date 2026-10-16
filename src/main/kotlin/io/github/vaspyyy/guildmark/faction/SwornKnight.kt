package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.combat.FightGoal
import io.github.vaspyyy.guildmark.enemy.HollowSoldier
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.animal.golem.IronGolem
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import java.util.UUID

/**
 * A sworn knight of the Hearthsworn, steel and red tabard, who fights the way the Hollow Soldiers do (the same blade
 * and shield, the same wind-ups) because they were once the same order. They come out for two of Corra's chapters:
 * to spar a would-be knight in the training yard ([Spar], to a yield, never to the death) and to stand at a Wardfire
 * through the Long Night ([Hold]), where they turn on anything that comes for it and never on the player.
 */
class SwornKnight(type: EntityType<out SwornKnight>, level: Level) : HollowSoldier(type, level) {
    enum class Mode { SPAR, ALLY }

    var mode = Mode.ALLY
    /** Who they're to spar, in [Mode.SPAR]. */
    var sparring: UUID? = null
    /** Where they stand when not fighting, and the way they face there. */
    var post: BlockPos? = null
    var postYaw = 0f

    /** A bout is on (clients see it, so the sparring knight is the one foe in the yard). */
    var inBout: Boolean
        get() = flag(BOUT)
        set(value) = setFlag(BOUT, value)

    override fun canFight(): Boolean = if (mode == Mode.SPAR) inBout else true

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, FightGoal(this))
        goalSelector.addGoal(5, KeepPostGoal(this))
        goalSelector.addGoal(7, LookAtPlayerGoal(this, Player::class.java, 10f))
        goalSelector.addGoal(8, RandomLookAroundGoal(this))
        // At a held fire: anything hostile that comes near, but never a creeper (it would only blow the fire out)
        targetSelector.addGoal(1, NearestAttackableTargetGoal(this, Mob::class.java, 5, true, false) { target, _ ->
            mode == Mode.ALLY && target is Enemy && target !is SwornKnight && target !is Creeper && target !is WardLight
        })
    }

    override fun populateDefaultEquipmentSlots(random: RandomSource, difficulty: DifficultyInstance) {
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD))
        setDropChance(EquipmentSlot.MAINHAND, 0f)
        setDropChance(EquipmentSlot.OFFHAND, 0f)
    }

    /** Only a foe of their own: the player in the bout, or what comes for the fire. */
    override fun canHit(victim: LivingEntity): Boolean = when (mode) {
        Mode.SPAR -> inBout && victim is Player && victim.uuid == sparring
        Mode.ALLY -> victim is Enemy && victim !is SwornKnight && victim !is WardLight
    }

    override fun canAttack(target: LivingEntity): Boolean = when (mode) {
        Mode.SPAR -> inBout && target is Player && target.uuid == sparring
        Mode.ALLY -> target !is Player && target !is Townsperson && super.canAttack(target)
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount)
        val by = source.entity
        return when (mode) {
            // A bout to a yield: only their partner's blows, and none that would bring them down (Spar sees them yield)
            Mode.SPAR -> {
                if (!inBout || by !is Player || by.uuid != sparring) return false
                super.hurtServer(level, source, minOf(amount, health - 1f).coerceAtLeast(0f))
            }
            // Their own side's blows and spells pass them by
            Mode.ALLY -> if (by is Player || by is SwornKnight || by is Townsperson || by == null && source.`is`(DamageTypeTags.IS_FALL)) false
                else super.hurtServer(level, source, amount)
        }
    }

    /** They salute: a rap of the sword on the shield. */
    fun salute(level: ServerLevel) {
        level.playSound(null, x, y, z, FactionRegistry.SPAR_SALUTE.get(), soundSource, 1f, 1f)
        level.sendParticles(ParticleTypes.CRIT, x, getY(0.7), z, 6, 0.3, 0.2, 0.3, 0.05)
    }

    /** Whole again and back at their post: the bout is over. */
    fun reset() {
        inBout = false
        target = null
        if (attack != null) stopAttack(true)
        health = maxHealth
        clearFire()
    }

    // Knights keep their own counsel: no idle groans
    override fun playAmbientSound() = Unit
    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.PLAYER_HURT
    override fun getDeathSound(): SoundEvent = SoundEvents.PLAYER_DEATH
    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun shouldDropExperience(): Boolean = false

    // The city's iron golems too, who'd otherwise take a knight in the yard for a monster (they ask both ways)
    override fun considersEntityAsAlly(other: net.minecraft.world.entity.Entity): Boolean =
        other is SwornKnight || other is IronGolem || mode == Mode.ALLY && (other is Player || other is Townsperson) || super.considersEntityAsAlly(other)

    /** Walks back to their post when there's nothing to fight, and faces the way they should there. */
    private class KeepPostGoal(private val knight: SwornKnight) : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            val post = knight.post ?: return false
            return knight.target == null && knight.attack == null && knight.distanceToSqr(Vec3.atBottomCenterOf(post)) > 1.5
        }

        override fun canContinueToUse(): Boolean = knight.target == null && !knight.navigation.isDone

        override fun start() {
            val post = knight.post ?: return
            knight.navigation.moveTo(post.x + 0.5, post.y.toDouble(), post.z + 0.5, 0.9)
        }

        override fun stop() {
            knight.navigation.stop()
            if (knight.target == null && knight.post?.let { knight.distanceToSqr(Vec3.atBottomCenterOf(it)) < 2.5 } == true) {
                knight.yRot = knight.postYaw
                knight.yBodyRot = knight.postYaw
                knight.yHeadRot = knight.postYaw
            }
        }
    }

    companion object {
        private const val BOUT = 16

        /** Their names, one each: the order's own. */
        private val NAMES = listOf(
            "Ser Aldric", "Dame Brennet", "Ser Caddoc", "Dame Elowen", "Ser Garrow", "Dame Hesk", "Ser Lowen", "Dame Maelis",
            "Ser Orrin", "Dame Rhosyn", "Ser Tamsin", "Dame Wenna",
        )

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 40.0)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.ARMOR, 8.0)
            .add(Attributes.MOVEMENT_SPEED, 0.27)
            .add(Attributes.FOLLOW_RANGE, 20.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)

        /** A knight standing at [post], facing [yaw], for [mode]. */
        fun create(level: ServerLevel, post: BlockPos, yaw: Float, mode: Mode, sparring: UUID? = null): SwornKnight? {
            val knight = FactionRegistry.SWORN_KNIGHT.get().create(level, EntitySpawnReason.EVENT) ?: return null
            knight.snapTo(post.x + 0.5, post.y.toDouble(), post.z + 0.5, yaw, 0f)
            knight.yHeadRot = yaw
            knight.yBodyRot = yaw
            knight.mode = mode
            knight.sparring = sparring
            knight.post = post
            knight.postYaw = yaw
            knight.finalizeSpawn(level, level.getCurrentDifficultyAt(post), EntitySpawnReason.EVENT, null)
            knight.customName = Component.literal(NAMES[Mth.floor(level.random.nextFloat() * NAMES.size).coerceIn(0, NAMES.size - 1)])
            // In a bout they hit as a sparring partner would; at a fire, as hard as they can
            if (mode == Mode.ALLY) knight.getAttribute(Attributes.ATTACK_DAMAGE)?.baseValue = 6.0
            level.addFreshEntity(knight)
            return knight
        }
    }
}
