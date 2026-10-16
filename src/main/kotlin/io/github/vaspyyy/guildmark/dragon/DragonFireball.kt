package io.github.vaspyyy.guildmark.dragon

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ThrowableProjectile
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A bolt of fire a dragon spits from a hover. It flies straight and fast, bursts on whatever it meets and
 * leaves the ground there burning a while: a step aside as it comes, or a roll through the burst, and it misses.
 * Nothing in the world catches fire from it.
 */
class DragonFireball : ThrowableProjectile {
    private var life = 0

    constructor(type: EntityType<out DragonFireball>, level: Level) : super(type, level)

    constructor(level: Level, owner: Dragon, at: Vec3) : super(DragonRegistry.FIREBALL.get(), at.x, at.y - 0.5, at.z, level) {
        setOwner(owner)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}

    override fun getDefaultGravity(): Double = 0.0

    override fun getAirDrag(): Float = 1f

    override fun tick() {
        super.tick()
        if (isRemoved) return
        val level = level()
        if (level.isClientSide) {
            trail(level)
            return
        }
        if (isInWater) {
            level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 1.5f, 1f)
            (level as ServerLevel).sendParticles(ParticleTypes.CLOUD, x, y + 0.5, z, 12, 0.3, 0.3, 0.3, 0.05)
            discard()
            return
        }
        if (++life > LIFETIME) {
            burst(level as ServerLevel, position().add(0.0, 0.5, 0.0), null)
            discard()
        }
    }

    /** A ball of flame with smoke streaming off behind it. */
    private fun trail(level: Level) {
        val flame = DragonRegistry.FLAME.get()
        val v = deltaMovement
        repeat(5) {
            level.addParticle(flame, true, true,
                x + (random.nextDouble() - 0.5) * 0.6, y + 0.5 + (random.nextDouble() - 0.5) * 0.6, z + (random.nextDouble() - 0.5) * 0.6,
                v.x * 0.25 + (random.nextDouble() - 0.5) * 0.04, v.y * 0.25 + (random.nextDouble() - 0.5) * 0.04, v.z * 0.25 + (random.nextDouble() - 0.5) * 0.04)
        }
        level.addParticle(ParticleTypes.LARGE_SMOKE, true, false, xo, yo + 0.5, zo, 0.0, 0.02, 0.0)
        if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.LAVA, x, y + 0.5, z, 0.0, 0.0, 0.0)
    }

    // It passes through dragons and their fire
    override fun canHitEntity(target: Entity): Boolean = super.canHitEntity(target) && target !is Dragon && target !is DragonFireball

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level() as? ServerLevel ?: return
        if (isRemoved) return
        burst(level, hit.location, (hit as? EntityHitResult)?.entity as? LivingEntity)
        discard()
    }

    /** Bursts at [at]: [struck] takes the bolt full on, anyone near takes the splash, and the ground there burns. */
    private fun burst(level: ServerLevel, at: Vec3, struck: LivingEntity?) {
        // getOwner(): inside a projectile, plain owner is the EntityReference field rather than the dragon
        val dragon = getOwner() as? Dragon
        val damage = dragon?.getAttributeValue(Attributes.ATTACK_DAMAGE)?.toFloat() ?: FALLBACK_DAMAGE
        if (struck != null && struck.isAlive && scorches(dragon, struck)) burn(level, dragon, struck, damage * DIRECT)
        for (victim in level.getEntitiesOfClass(LivingEntity::class.java, AABB(at, at).inflate(SPLASH))) {
            if (victim === struck || !victim.isAlive || !scorches(dragon, victim)) continue
            if (victim.position().add(0.0, victim.bbHeight / 2.0, 0.0).distanceTo(at) > SPLASH + victim.bbWidth / 2) continue
            burn(level, dragon, victim, damage * SPLASH_DAMAGE)
        }
        dragon?.patchAt(level, at, PATCH_RADIUS, PATCH_TICKS)
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 40, 0.9, 0.5, 0.9, 0.08)
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.3, at.z, 10, 0.6, 0.3, 0.6, 0.1)
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.6, at.z, 12, 0.7, 0.4, 0.7, 0.03)
        level.playSound(null, at.x, at.y, at.z, DragonRegistry.FIRE_BURST.get(), SoundSource.HOSTILE, 3f, 0.9f + random.nextFloat() * 0.2f)
    }

    /** Who its fire may burn: whoever its dragon would, or (if the dragon is gone) players. */
    private fun scorches(dragon: Dragon?, victim: LivingEntity): Boolean =
        if (dragon != null) dragon.scorches(victim) else victim is Player && !victim.isSpectator && !victim.isCreative

    private fun burn(level: ServerLevel, dragon: Dragon?, victim: LivingEntity, damage: Float) {
        if (dragon != null) dragon.burn(level, victim, damage, BURN_SECONDS)
        else if (victim.hurtServer(level, level.damageSources().inFire(), damage)) victim.igniteForSeconds(BURN_SECONDS)
    }

    companion object {
        /** Blocks a tick it flies. */
        const val SPEED = 1.1
        /** Ticks before it bursts in the air. */
        private const val LIFETIME = 60
        /** Its hit and its splash, as shares of the dragon's bite. */
        private const val DIRECT = 1.0f
        private const val SPLASH_DAMAGE = 0.5f
        private const val FALLBACK_DAMAGE = 10f
        private const val SPLASH = 2.8
        private const val BURN_SECONDS = 4f
        private const val PATCH_RADIUS = 2.4
        private const val PATCH_TICKS = 160
    }
}
