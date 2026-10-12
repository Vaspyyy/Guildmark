package io.github.vaspyyy.guildmark.death

import io.github.vaspyyy.guildmark.registry.ModParticles
import net.minecraft.core.UUIDUtil
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * The Guild Marks a player carried when they fell, hanging where they fell as a few slowly turning
 * coins. Only that player can take them back, by walking into them. Nothing harms them and they never
 * despawn, but they're gone the moment their owner falls again before reaching them.
 */
class LostMarks(type: EntityType<out LostMarks>, level: Level) : Entity(type, level) {
    var owner: UUID? = null
    /** Which of the owner's falls these are from; see [Purses]. */
    var stamp = 0L

    var amount: Int
        get() = entityData.get(AMOUNT)
        set(value) = entityData.set(AMOUNT, value)

    init {
        noPhysics = true
        isNoGravity = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(AMOUNT, 0)
    }

    override fun tick() {
        super.tick()
        val level = level()
        if (level.isClientSide) {
            if (tickCount % 4 == 0) {
                level.addParticle(
                    ModParticles.WARD_EMBER.get(),
                    x + (random.nextDouble() - 0.5) * 0.5, y + 0.2 + random.nextDouble() * 0.4, z + (random.nextDouble() - 0.5) * 0.5,
                    0.0, 0.015 + random.nextDouble() * 0.01, 0.0,
                )
            }
            return
        }
        if (tickCount % 5 == 0 && level is ServerLevel) Purses.tickLost(level, this)
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    override fun ignoreExplosion(explosion: Explosion): Boolean = true

    override fun isPickable(): Boolean = false

    override fun isPushable(): Boolean = false

    override fun getPistonPushReaction(): PushReaction = PushReaction.IGNORE_ENTITY

    override fun isIgnoringBlockTriggers(): Boolean = true

    override fun displayFireAnimation(): Boolean = false

    override fun canUsePortal(allowPassengers: Boolean): Boolean = false

    override fun teleport(transition: TeleportTransition): Entity? = null

    override fun readAdditionalSaveData(input: ValueInput) {
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null)
        stamp = input.getLongOr("stamp", 0L)
        amount = input.getIntOr("amount", 0)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        owner?.let { output.store("owner", UUIDUtil.CODEC, it) }
        output.putLong("stamp", stamp)
        output.putInt("amount", amount)
    }

    companion object {
        val AMOUNT: EntityDataAccessor<Int> = SynchedEntityData.defineId(LostMarks::class.java, EntityDataSerializers.INT)
    }
}
