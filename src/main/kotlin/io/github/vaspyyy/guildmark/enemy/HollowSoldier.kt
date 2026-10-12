package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.CombatEnemy
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.combat.FightGoal
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.RandomSource
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.animal.golem.IronGolem
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * A soldier of the Hollow King's fallen army, still keeping the roads he was sent to hold. Fights with
 * a sword, and sometimes a shield it raises against blows from the front: heavy hits wear the guard down
 * until it breaks and leaves the soldier open. Every attack has a wind-up worth watching for, and the
 * overhead swing waits a beat longer than you'd think.
 */
class HollowSoldier(type: EntityType<out HollowSoldier>, level: Level) : CombatEnemy(type, level) {
    override val attacks: List<EnemyAttack> get() = ATTACKS
    override val animations: String get() = "hollow_soldier"
    override val maxStance: Float get() = 40f
    override val waitRange: Double get() = 2.2

    /** The shield is up: blows from the front mostly glance off. */
    val guarding: Boolean get() = flag(GUARDING)
    val hasShield: Boolean get() = offhandItem.has(DataComponents.BLOCKS_ATTACKS)

    private var guard = MAX_GUARD
    private var guardTicks = 0
    private var lastBlocked = 0
    private var lord: FallenWarden? = null
    private var lordCheckedAt = -100

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, FightGoal(this))
        goalSelector.addGoal(6, WaterAvoidingRandomStrollGoal(this, 0.8))
        goalSelector.addGoal(7, LookAtPlayerGoal(this, Player::class.java, 10f))
        goalSelector.addGoal(8, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this))
        targetSelector.addGoal(2, NearestAttackableTargetGoal(this, Player::class.java, true))
        targetSelector.addGoal(3, NearestAttackableTargetGoal(this, IronGolem::class.java, true))
    }

    override fun finalizeSpawn(
        level: net.minecraft.world.level.ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnReason: net.minecraft.world.entity.EntitySpawnReason,
        groupData: net.minecraft.world.entity.SpawnGroupData?,
    ): net.minecraft.world.entity.SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, spawnReason, groupData)
        populateDefaultEquipmentSlots(level.random, difficulty)
        return data
    }

    override fun populateDefaultEquipmentSlots(random: RandomSource, difficulty: DifficultyInstance) {
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack(if (random.nextFloat() < 0.7f) Items.IRON_SWORD else Items.STONE_SWORD))
        if (random.nextFloat() < 0.45f) setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD))
        setDropChance(EquipmentSlot.MAINHAND, 0.04f)
        setDropChance(EquipmentSlot.OFFHAND, 0.06f)
    }

    // ---- Standing guard ----

    /**
     * The Fallen Warden this soldier stands guard for while he kneels at his post or wakes: it holds its
     * blade until he takes up his own. Looked up twice a second.
     */
    private fun heldBy(): FallenWarden? {
        if (tickCount - lordCheckedAt >= 10) {
            lordCheckedAt = tickCount
            lord = level().getEntitiesOfClass(FallenWarden::class.java, boundingBox.inflate(HOLD_RANGE)) { it.isAlive && !it.fighting }.firstOrNull()
        }
        return lord?.takeIf { it.isAlive && !it.fighting }
    }

    override fun canAttack(target: LivingEntity): Boolean = heldBy() == null && super.canAttack(target)

    // ---- Attacks ----

    override fun usable(attack: EnemyAttack, target: LivingEntity, distance: Double): Boolean {
        if (attack === BASH) return hasShield && distance <= attack.maxRange && (target.isBlocking || random.nextFloat() < 0.3f)
        return super.usable(attack, target, distance)
    }

    override fun onAttackStart(attack: EnemyAttack) {
        setGuarding(false)
        playSound(ModSounds.SOLDIER_ATTACK.get(), 0.8f, 0.9f + random.nextFloat() * 0.2f)
    }

    override fun onAttackTick(level: ServerLevel, attack: EnemyAttack, tick: Int) {
        for (blow in attack.blows) {
            if (tick == blow.at - 2) {
                val sound = if (blow.damage >= 1.4f) ModSounds.SWING_HEAVY.get() else ModSounds.SWING_LIGHT.get()
                level.playSound(null, x, y, z, sound, SoundSource.HOSTILE, 0.8f, 0.85f + random.nextFloat() * 0.15f)
            }
        }
        // The overhead swing's blade catches the light as it's raised: the cue that the delay is coming
        if (attack === OVERHEAD && tick == 6) glint(level, ParticleTypes.ELECTRIC_SPARK, 2.6, 0.2)
    }

    // ---- The shield ----

    private fun setGuarding(on: Boolean) {
        if (guarding == on) return
        setFlag(GUARDING, on)
        val speed = getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        if (on) speed.addOrUpdateTransientModifier(AttributeModifier(GUARD_SLOW, -0.45, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        else speed.removeModifier(GUARD_SLOW)
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (tickCount - lastBlocked > 30) guard = minOf(MAX_GUARD, guard + 0.6f)
        val target = target
        if (!hasShield || attack != null || target == null || !target.isAlive || Stance.isStaggered(this) || distanceTo(target) > 6f) {
            setGuarding(false)
            return
        }
        // Raise the shield when the foe winds up a blow, and otherwise now and then while waiting
        if (!guarding && target is Player && Combat.isBusy(target) && random.nextFloat() < 0.35f) {
            setGuarding(true)
            guardTicks = 25 + random.nextInt(20)
            return
        }
        if (--guardTicks <= 0) {
            val raise = !guarding && random.nextFloat() < 0.6f
            setGuarding(raise)
            guardTicks = if (raise) 30 + random.nextInt(40) else 20 + random.nextInt(30)
        }
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        // Strike one of his guard and the knight they keep watch for wakes
        if (source.entity is Player) heldBy()?.let { if (it.dormant) it.awaken(level) }
        if (!guarding || !blocks(source)) return super.hurtServer(level, source, amount)
        lastBlocked = tickCount
        // Heavier moves wear the guard down faster: weigh the blow as it will land, not its base damage
        guard -= 6f + amount * Combat.strikeMultiplier(this, source.entity) * 3f
        if (guard > 0f) {
            level.playSound(null, x, y, z, SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1f, 0.8f + random.nextFloat() * 0.4f)
            return super.hurtServer(level, source, amount * 0.15f)
        }
        // The guard gives way: the shield is knocked aside and the soldier reels, open to a riposte
        guard = MAX_GUARD
        setGuarding(false)
        level.playSound(null, x, y, z, ModSounds.GUARD_BREAK.get(), SoundSource.HOSTILE, 1f, 0.9f)
        level.sendParticles(ParticleTypes.CRIT, x, getY(0.6), z, 12, 0.3, 0.3, 0.3, 0.2)
        val hurt = super.hurtServer(level, source, amount * 0.3f)
        if (isAlive) Stance.stagger(this, Stance.BREAK_TICKS, Stance.BROKEN)
        return hurt
    }

    /** Whether the raised shield stops [source]: anything from in front that a shield could stop. */
    private fun blocks(source: DamageSource): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_SHIELD)) return false
        val from = source.sourcePosition ?: return false
        return Combat.angleTo(yBodyRot, from.x - x, from.z - z) < 70f
    }

    // ---- Sounds ----

    override fun getAmbientSound(): SoundEvent = ModSounds.SOLDIER_AMBIENT.get()
    override fun getHurtSound(source: DamageSource): SoundEvent = ModSounds.SOLDIER_HURT.get()
    override fun getDeathSound(): SoundEvent = ModSounds.SOLDIER_DEATH.get()

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(SoundEvents.SKELETON_STEP, 0.15f, 0.8f)
    }

    companion object {
        private const val GUARDING = 1
        private const val MAX_GUARD = 40f
        private const val HOLD_RANGE = 32.0
        private val GUARD_SLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "guarding")

        // Timings match art/scripts/enemy_anims.py
        val SLASH = EnemyAttack(1, "slash", 24, listOf(Blow(11, 1.0f, 2.7, 60f, 15f)), listOf(0..8),
            maxRange = 2.8, weight = 4, cooldown = 18)
        val DOUBLE = EnemyAttack(2, "double", 34, listOf(Blow(11, 0.85f, 2.7, 60f, 12f), Blow(21, 0.85f, 2.7, 60f, 12f)), listOf(0..8, 14..18),
            maxRange = 2.8, weight = 3, cooldown = 24)
        val OVERHEAD = EnemyAttack(3, "overhead", 34, listOf(Blow(19, 1.5f, 3.0, 30f, 28f, knockback = 0.6f, guard = 1.6f)), listOf(0..15),
            maxRange = 3.0, weight = 2, cooldown = 26, turn = 10f)
        val THRUST = EnemyAttack(4, "thrust", 28, listOf(Blow(12, 1.2f, 2.4, 25f, 18f, until = 13)), listOf(0..9),
            minRange = 2.5, maxRange = 6.5, weight = 3, cooldown = 22, lunge = 0.8f, lungeAt = 10, lungeTicks = 3)
        val BASH = EnemyAttack(5, "bash", 20, listOf(Blow(8, 0.5f, 2.2, 50f, 35f, parryable = false, knockback = 1.0f, guard = 3f)), listOf(0..5),
            maxRange = 2.2, weight = 3, cooldown = 16)
        val ATTACKS = listOf(SLASH, DOUBLE, OVERHEAD, THRUST, BASH)

        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 34.0)
            .add(Attributes.ATTACK_DAMAGE, 5.0)
            .add(Attributes.ARMOR, 6.0)
            .add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.FOLLOW_RANGE, 24.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)
    }
}
