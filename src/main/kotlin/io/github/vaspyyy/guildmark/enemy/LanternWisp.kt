package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A wisp of cold light cast from the Dusk Rider's lantern. It drifts after whoever it was cast at, turning
 * slowly enough that a step aside at the last moment slips it, and bursts on whatever it touches: a blow a
 * shield can take and armour softens, and a chill that slows. It gutters out after a few seconds.
 */
class LanternWisp : ThrowableItemProjectile {
    private var life = 0

    constructor(type: EntityType<out LanternWisp>, level: Level) : super(type, level)

    constructor(level: Level, owner: LivingEntity, at: Vec3, target: LivingEntity) :
        super(ModEntities.LANTERN_WISP.get(), at.x, at.y, at.z, level, ItemStack(ModItems.LANTERN_WISP.get())) {
        setOwner(owner)
        entityData.set(DATA_TARGET, target.id)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_TARGET, -1)
    }

    override fun getDefaultItem(): Item = ModItems.LANTERN_WISP.get()

    override fun getDefaultGravity(): Double = 0.0

    override fun tick() {
        super.tick()
        if (isRemoved) return
        val level = level()
        // It turns after its target the same way on both sides, so it flies smoothly for those watching
        val target = level.getEntity(entityData.get(DATA_TARGET)) as? LivingEntity
        if (target != null && target.isAlive) {
            val want = target.position().add(0.0, target.bbHeight * 0.6, 0.0).subtract(position()).normalize().scale(SPEED)
            deltaMovement = deltaMovement.add(want.subtract(deltaMovement).scale(TURN)).normalize().scale(SPEED)
        } else {
            deltaMovement = deltaMovement.normalize().scale(SPEED)
        }
        if (level.isClientSide) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.1, z, 0.0, 0.01, 0.0)
            if (random.nextInt(2) == 0) {
                level.addParticle(ParticleTypes.END_ROD, x + (random.nextDouble() - 0.5) * 0.3, y + 0.1 + (random.nextDouble() - 0.5) * 0.3,
                    z + (random.nextDouble() - 0.5) * 0.3, 0.0, 0.0, 0.0)
            }
            return
        }
        if (++life > LIFETIME) {
            burst(level as ServerLevel, position(), 0.6f)
            discard()
        }
    }

    // It passes through its own kind and the rider's
    override fun canHitEntity(target: Entity): Boolean = super.canHitEntity(target) && target !is Enemy && target !is LanternWisp

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level() as? ServerLevel ?: return
        val struck = (hit as? EntityHitResult)?.entity as? LivingEntity
        if (struck != null) {
            val owner = owner as? LivingEntity
            val damage = (owner?.getAttributeValue(Attributes.ATTACK_DAMAGE)?.toFloat() ?: 9f) * DAMAGE
            if (struck.hurtServer(level, damageSources().mobProjectile(this, owner), damage)) {
                struck.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 60, 1), owner ?: this)
            }
        }
        burst(level, hit.location, 1.4f)
        discard()
    }

    private fun burst(level: ServerLevel, at: Vec3, volume: Float) {
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y, at.z, 16, 0.25, 0.25, 0.25, 0.06)
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.08)
        level.playSound(null, at.x, at.y, at.z, ModSounds.RIDER_WISP_BURST.get(), SoundSource.HOSTILE, volume, 0.9f + random.nextFloat() * 0.2f)
    }

    companion object {
        /** Blocks a tick it flies. */
        const val SPEED = 0.5
        /** How much of the way toward its target it turns each tick. */
        private const val TURN = 0.08
        /** Ticks before it gutters out. */
        private const val LIFETIME = 80
        /** Its blow, as a share of the rider's. */
        private const val DAMAGE = 0.8f

        private val DATA_TARGET: EntityDataAccessor<Int> = SynchedEntityData.defineId(LanternWisp::class.java, EntityDataSerializers.INT)
    }
}
