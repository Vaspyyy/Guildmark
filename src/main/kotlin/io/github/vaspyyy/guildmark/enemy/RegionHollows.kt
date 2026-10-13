package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.combat.Blow
import io.github.vaspyyy.guildmark.region.RegionData
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.RandomStrollGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathType
import kotlin.math.min

// The regional Hollows: soldiers of the same fallen army that the wild lands have taken back. They fight as the
// Hollow Soldier does (the same attacks, shield and tells) and take its place in their own land's spawns (see
// region/RegionFoes), each with one thing of its land's about it. Models and textures: art/scripts/region_hollows.py.

/**
 * A soldier drowned in the bog. It wades the marsh water as if it were dry ground, wanders into it as readily as
 * onto the land, and every blow that lands leaves bog-rot in the wound (poison). Water runs off it as it goes.
 */
class MireHollow(type: EntityType<out MireHollow>, level: Level) : HollowSoldier(type, level) {
    init {
        setPathfindingMalus(PathType.WATER, 0f)
    }

    override fun registerGoals() {
        super.registerGoals()
        goalSelector.removeAllGoals { it is WaterAvoidingRandomStrollGoal }
        goalSelector.addGoal(6, RandomStrollGoal(this, 0.8))
    }

    override fun onBlowLanded(level: ServerLevel, victim: LivingEntity, blow: Blow) {
        victim.addEffect(MobEffectInstance(MobEffects.POISON, ROT_TICKS, 0), this)
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide && random.nextInt(5) == 0) {
            level().addParticle(ParticleTypes.FALLING_WATER, getRandomX(0.7), getY(0.35 + random.nextDouble() * 0.6), getRandomZ(0.7), 0.0, 0.0, 0.0)
        }
    }

    override fun getVoicePitch(): Float = super.getVoicePitch() * 0.85f

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(SoundEvents.MUD_STEP, 0.3f, 0.8f + random.nextFloat() * 0.2f)
    }

    companion object {
        /** Ticks of poison a landed blow leaves. */
        private const val ROT_TICKS = 100

        fun createAttributes(): AttributeSupplier.Builder = HollowSoldier.createAttributes()
            .add(Attributes.MOVEMENT_SPEED, 0.23)
            .add(Attributes.WATER_MOVEMENT_EFFICIENCY, 1.0)
    }
}

/**
 * A soldier burnt black on the ashen highlands, still smouldering: fire can't hurt it, its blows set you alight,
 * and it walks through flame without a thought. Water is the one thing it fears: it keeps out of it, and in it
 * (or in the rain) it hisses and takes harm.
 */
class CinderHollow(type: EntityType<out CinderHollow>, level: Level) : HollowSoldier(type, level) {
    init {
        setPathfindingMalus(PathType.WATER, -1f)
        setPathfindingMalus(PathType.FIRE, 0f)
        setPathfindingMalus(PathType.FIRE_IN_NEIGHBOR, 0f)
    }

    override fun onBlowLanded(level: ServerLevel, victim: LivingEntity, blow: Blow) {
        victim.igniteForSeconds(BURN_SECONDS)
    }

    override fun isSensitiveToWater(): Boolean = true

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (isInWaterOrRain && tickCount % 10 == 0) {
            level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.7f, 1.4f + random.nextFloat() * 0.3f)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, getY(0.6), z, 4, 0.3, 0.4, 0.3, 0.02)
        }
    }

    override fun aiStep() {
        super.aiStep()
        if (!level().isClientSide) return
        if (random.nextInt(4) == 0) {
            level().addParticle(ParticleTypes.SMOKE, getRandomX(0.5), getY(0.55 + random.nextDouble() * 0.45), getRandomZ(0.5), 0.0, 0.02, 0.0)
        }
        if (random.nextInt(9) == 0) {
            level().addParticle(RegionData.ASH_EMBER.get(), getRandomX(0.6), getY(0.3 + random.nextDouble() * 0.6), getRandomZ(0.6), 0.0, 0.0, 0.0)
        }
        if (random.nextInt(70) == 0) {
            level().playLocalSound(x, y, z, SoundEvents.FIRE_AMBIENT, soundSource, 0.5f, 0.7f + random.nextFloat() * 0.3f, false)
        }
    }

    override fun getVoicePitch(): Float = super.getVoicePitch() * 0.95f

    companion object {
        /** Seconds a landed blow sets its victim alight for. */
        private const val BURN_SECONDS = 3f

        fun createAttributes(): AttributeSupplier.Builder = HollowSoldier.createAttributes()
    }
}

/**
 * A soldier frozen stiff in the peaks. The cold is its own: it can't freeze and walks on powder snow (both by
 * tag), and its blows leave frost in you, a little more with each, until you freeze through and it starts to
 * hurt. Leather keeps it out, as it keeps out powder snow's cold.
 */
class RimeHollow(type: EntityType<out RimeHollow>, level: Level) : HollowSoldier(type, level) {
    override fun onBlowLanded(level: ServerLevel, victim: LivingEntity, blow: Blow) {
        if (!victim.canFreeze()) return
        victim.ticksFrozen = min(victim.ticksRequiredToFreeze + FROST_OVER, victim.ticksFrozen + FROST_PER_BLOW)
        level.sendParticles(ParticleTypes.SNOWFLAKE, victim.x, victim.getY(0.6), victim.z, 10, 0.3, 0.4, 0.3, 0.02)
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide && random.nextInt(4) == 0) {
            level().addParticle(ParticleTypes.SNOWFLAKE, getRandomX(0.8), getY(0.6 + random.nextDouble() * 0.5), getRandomZ(0.8), 0.0, -0.02, 0.0)
        }
    }

    override fun getVoicePitch(): Float = super.getVoicePitch() * 1.05f

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(SoundEvents.STRAY_STEP, 0.2f, 0.9f)
    }

    companion object {
        /**
         * Ticks of freezing a landed blow adds (it thaws two a tick, and 140 is frozen through), and how far
         * past frozen through it can pile up: two blows within a second freeze you through for a moment.
         */
        private const val FROST_PER_BLOW = 90
        private const val FROST_OVER = 40

        fun createAttributes(): AttributeSupplier.Builder = HollowSoldier.createAttributes()
    }
}

/**
 * A soldier lost in the crystal deeps, grown through with crystal. Arrows and bolts mostly glance off the crystal
 * (a third of the harm), and when it falls the crystal rings once and bursts, cutting anyone still beside it.
 */
class GlimmerHollow(type: EntityType<out GlimmerHollow>, level: Level) : HollowSoldier(type, level) {
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.IS_PROJECTILE)) {
            level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.HOSTILE, 1f, 0.8f + random.nextFloat() * 0.4f)
            return super.hurtServer(level, source, amount * PROJECTILE_SHARE)
        }
        return super.hurtServer(level, source, amount)
    }

    override fun tickDeath() {
        super.tickDeath()
        val level = level() as? ServerLevel ?: return
        // It rings as it falls: the warning to step back before the crystal bursts
        if (deathTime == 1) {
            level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.4f, 0.7f)
            level.sendParticles(RegionData.CRYSTAL_GLINT.get(), x, getY(0.6), z, 10, 0.4, 0.6, 0.4, 0.0)
        } else if (deathTime == BURST_AT) {
            burst(level)
        }
    }

    private fun burst(level: ServerLevel) {
        level.playSound(null, x, y, z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.HOSTILE, 1.6f, 0.8f)
        level.playSound(null, x, y, z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.8f, 1.3f)
        level.sendParticles(RegionData.CRYSTAL_GLINT.get(), x, getY(0.5), z, 40, 0.9, 0.8, 0.9, 0.0)
        level.sendParticles(ParticleTypes.END_ROD, x, getY(0.5), z, 12, 0.2, 0.3, 0.2, 0.12)
        val source = damageSources().mobAttack(this)
        for (victim in level.getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(BURST_RANGE))) {
            if (victim === this || !victim.isAlive || distanceTo(victim) > BURST_RANGE || !canHit(victim)) continue
            victim.hurtServer(level, source, BURST_DAMAGE)
        }
    }

    override fun aiStep() {
        super.aiStep()
        if (!level().isClientSide) return
        if (random.nextInt(6) == 0) {
            level().addParticle(RegionData.CRYSTAL_GLINT.get(), getRandomX(0.8), getY(0.2 + random.nextDouble() * 0.9), getRandomZ(0.8), 0.0, 0.0, 0.0)
        }
        if (random.nextInt(90) == 0) {
            level().playLocalSound(x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, soundSource, 0.8f, 0.6f + random.nextFloat() * 0.6f, false)
        }
    }

    override fun getVoicePitch(): Float = super.getVoicePitch() * 1.1f

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(SoundEvents.AMETHYST_CLUSTER_STEP, 0.25f, 0.8f + random.nextFloat() * 0.2f)
    }

    companion object {
        /** The share of an arrow's harm that gets through the crystal. */
        private const val PROJECTILE_SHARE = 0.35f
        /** The death tick the crystal bursts on (of the 20 it lies dying), how far it cuts and how deep. */
        private const val BURST_AT = 12
        private const val BURST_RANGE = 2.5
        private const val BURST_DAMAGE = 5f

        fun createAttributes(): AttributeSupplier.Builder = HollowSoldier.createAttributes()
    }
}
