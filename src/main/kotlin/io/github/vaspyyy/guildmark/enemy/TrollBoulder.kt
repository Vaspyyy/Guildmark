package io.github.vaspyyy.guildmark.enemy

import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A boulder a hill troll tore out of the ground and threw. It bursts where it lands: whoever it struck
 * takes the full blow, anyone close by some of it. It never leaves a block behind.
 */
class TrollBoulder : ThrowableItemProjectile {
    constructor(type: EntityType<out TrollBoulder>, level: Level) : super(type, level)

    constructor(level: Level, owner: LivingEntity, at: Vec3) :
        super(ModEntities.TROLL_BOULDER.get(), at.x, at.y, at.z, level, ItemStack(Items.MOSSY_COBBLESTONE)) {
        setOwner(owner)
    }

    override fun getDefaultItem(): Item = Items.MOSSY_COBBLESTONE

    override fun getDefaultGravity(): Double = GRAVITY

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level() as? ServerLevel ?: return
        burst(level, hit)
        discard()
    }

    private fun burst(level: ServerLevel, hit: HitResult) {
        val at = hit.location
        val owner = owner as? LivingEntity
        val damage = owner?.getAttributeValue(Attributes.ATTACK_DAMAGE)?.toFloat() ?: 10f
        val source = damageSources().thrown(this, owner)
        val struck = (hit as? EntityHitResult)?.entity
        for (victim in level.getEntitiesOfClass(LivingEntity::class.java, AABB.ofSize(at, 6.0, 4.0, 6.0))) {
            if (victim === owner || !victim.isAlive || victim is Enemy) continue
            if (victim is Player && (victim.isCreative || victim.isSpectator)) continue
            val amount = when {
                victim === struck -> damage
                victim.boundingBox.distanceToSqr(at) < SPLASH * SPLASH -> damage * 0.5f
                else -> continue
            }
            if (victim.hurtServer(level, source, amount)) victim.knockback(0.9, at.x - victim.x, at.z - victim.z, source, amount)
        }
        val stone = Blocks.MOSSY_COBBLESTONE.defaultBlockState()
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, stone), at.x, at.y + 0.3, at.z, 50, 0.6, 0.4, 0.6, 0.2)
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.3, at.z, 10, 0.6, 0.2, 0.6, 0.03)
        level.playSound(null, at.x, at.y, at.z, ModSounds.TROLL_BOULDER.get(), SoundSource.HOSTILE, 2.0f, 0.8f + random.nextFloat() * 0.2f)
    }

    companion object {
        const val GRAVITY = 0.05
        /** How near a burst a bystander has to be to get some of it. */
        private const val SPLASH = 2.2
    }
}
