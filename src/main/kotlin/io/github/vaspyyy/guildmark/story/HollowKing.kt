package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.lair.BossAbilities
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The Hollow King, risen at his throne when the seven shards are brought together. Three phases: he
 * calls Hollow Knights and slams the ground; below two thirds his court answers with echoes of the
 * courtiers you killed; below a third the crown burns and lightning hunts everyone in the fight.
 */
class HollowKing(type: EntityType<out HollowKing>, level: Level) : Monster(type, level) {
    var throne: BlockPos = BlockPos.ZERO
    private var phase = 1
    private var knightTimer = 120
    private var slamTimer = 200
    private var echoTimer = 60
    private var stormTimer = 80
    private var lonelyTicks = 0

    private val bossEvent = ServerBossEvent(
        Mth.createInsecureUUID(random), Component.translatable("entity.guildmark.hollow_king"),
        BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_12,
    ).also {
        it.setDarkenScreen(true)
        it.setCreateWorldFog(true)
        it.setPlayBossMusic(true)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, MeleeAttackGoal(this, 1.0, true))
        goalSelector.addGoal(7, LookAtPlayerGoal(this, Player::class.java, 16.0f))
        goalSelector.addGoal(8, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this, HollowKing::class.java))
        targetSelector.addGoal(2, NearestAttackableTargetGoal(this, Player::class.java, false))
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        bossEvent.progress = health / maxHealth
        val fighters = level.players().filter { !it.isSpectator && !it.isCreative && it.distanceToSqr(this) < FIGHT_RANGE * FIGHT_RANGE }

        // Nobody left to fight: he goes back to his throne and mends
        if (fighters.isEmpty()) {
            if (++lonelyTicks == 200) {
                teleportTo(throne.x + 0.5, throne.y.toDouble(), throne.z + 0.5)
                health = maxHealth
                phase = 1
            }
            return
        }
        lonelyTicks = 0
        // Never strays far from the throne
        if (distanceToSqr(throne.x + 0.5, throne.y.toDouble(), throne.z + 0.5) > LEASH * LEASH) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 2, z, 40, 0.6, 1.2, 0.6, 0.05)
            teleportTo(throne.x + 0.5, throne.y.toDouble(), throne.z + 0.5)
        }

        val fraction = health / maxHealth
        if (phase == 1 && fraction <= 2f / 3f) enterPhase(level, fighters, 2)
        if (phase == 2 && fraction <= 1f / 3f) enterPhase(level, fighters, 3)

        if (--knightTimer <= 0) {
            knightTimer = if (phase == 3) 260 else 200
            repeat(if (phase == 1) 2 else 1) { summonKnight(level) }
        }
        if (--slamTimer <= 0) {
            slamTimer = 140 + random.nextInt(60)
            slam(level, fighters)
        }
        if (phase >= 2 && --echoTimer <= 0) {
            echoTimer = 300
            summonEcho(level)
        }
        if (phase >= 3 && --stormTimer <= 0) {
            stormTimer = 120
            crownStorm(level, fighters)
        }
        if (tickCount % 4 == 0) level.sendParticles(ParticleTypes.WITCH, x, y + 4.6, z, 2, 0.4, 0.1, 0.4, 0.0)
    }

    private fun enterPhase(level: ServerLevel, fighters: List<Player>, next: Int) {
        phase = next
        val key = if (next == 2) "message.guildmark.king_phase2" else "message.guildmark.king_phase3"
        for (player in fighters) player.sendSystemMessage(Component.translatable(key))
        level.playSound(null, blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.2f, 0.7f)
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 2, z, 120, 2.0, 2.0, 2.0, 0.08)
        if (next == 2) repeat(2) { summonEcho(level) }
        if (next == 3) addEffect(MobEffectInstance(MobEffects.SPEED, 20 * 600, 0))
    }

    /** A Hollow Knight claws its way out of the floor. */
    private fun summonKnight(level: ServerLevel) {
        val knight = BossAbilities.summon(level, this, Identifier.withDefaultNamespace("wither_skeleton")) ?: return
        knight.customName = Component.translatable("entity.guildmark.hollow_knight")
        level.sendParticles(ParticleTypes.SOUL, knight.x, knight.y + 0.5, knight.z, 20, 0.4, 0.4, 0.4, 0.03)
    }

    /** A shade of one of the courtiers the hero already killed. */
    private fun summonEcho(level: ServerLevel) {
        val courtier = Courtier.entries[random.nextInt(Courtier.entries.size)]
        val echo = BossAbilities.summon(level, this, courtier.theme.bossId()) ?: return
        echo.customName = Component.translatable("entity.guildmark.echo", courtier.title)
        echo.addEffect(MobEffectInstance(MobEffects.GLOWING, 20 * 60, 0))
        echo.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 40.0
        echo.health = echo.maxHealth
        level.playSound(null, echo.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.2f, 0.7f)
    }

    /** He brings his sword down and the floor heaves: close fighters are thrown into the air. */
    private fun slam(level: ServerLevel, fighters: List<Player>) {
        level.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0f, 0.6f)
        level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.2, z, 12, 2.5, 0.1, 2.5, 0.0)
        for (player in fighters) {
            if (player.distanceTo(this) > 6.0f) continue
            player.hurtServer(level, damageSources().mobAttack(this), 6.0f + phase * 2)
            val away = player.position().subtract(position()).normalize()
            player.push(away.x * 1.2, 0.8, away.z * 1.2)
            player.needsSync = true
        }
    }

    /** The crown burns: lightning falls on every fighter and darkness closes in. */
    private fun crownStorm(level: ServerLevel, fighters: List<Player>) {
        for (player in fighters) {
            val bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED) ?: continue
            bolt.snapTo(player.x + random.nextGaussian(), player.y, player.z + random.nextGaussian())
            bolt.setVisualOnly(true)
            level.addFreshEntity(bolt)
            player.hurtServer(level, damageSources().indirectMagic(this, this), 5.0f)
            player.addEffect(MobEffectInstance(MobEffects.DARKNESS, 20 * 4, 0))
        }
    }

    override fun doHurtTarget(level: ServerLevel, target: Entity): Boolean {
        val hit = super.doHurtTarget(level, target)
        if (hit && target is LivingEntity) target.addEffect(MobEffectInstance(MobEffects.WITHER, 20 * 5, 1), this)
        return hit
    }

    override fun canBeAffected(effect: MobEffectInstance): Boolean =
        !effect.`is`(MobEffects.WITHER) && !effect.`is`(MobEffects.POISON) && super.canBeAffected(effect)

    override fun removeWhenFarAway(distance: Double): Boolean = false

    override fun die(source: DamageSource) {
        super.die(source)
        val level = level()
        if (level is ServerLevel) HollowThrone.onKingDeath(level, this)
    }

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        bossEvent.addPlayer(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.store("throne", BlockPos.CODEC, throne)
        output.putInt("phase", phase)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        throne = input.read("throne", BlockPos.CODEC).orElse(blockPosition())
        phase = input.getIntOr("phase", 1)
    }

    companion object {
        private const val FIGHT_RANGE = 40.0
        private const val LEASH = 22.0

        fun createAttributes(): AttributeSupplier.Builder = createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 500.0)
            .add(Attributes.ATTACK_DAMAGE, 14.0)
            .add(Attributes.ARMOR, 14.0)
            .add(Attributes.ARMOR_TOUGHNESS, 6.0)
            .add(Attributes.MOVEMENT_SPEED, 0.27)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 40.0)
            .add(Attributes.ATTACK_KNOCKBACK, 1.5)
    }
}
