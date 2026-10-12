package io.github.vaspyyy.guildmark.item

import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.equine.SkeletonHorse
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * The steed a Dusk Bridle calls: the ghost of a horse, bound to whoever called it. It needs no saddle, nothing
 * harms it, nobody else can ride it, and it can't be led, fed or bred. A few seconds after its rider gets down
 * it fades away, and it never comes back from a save.
 */
class SpectralSteed(type: EntityType<out SpectralSteed>, level: Level) : SkeletonHorse(type, level) {
    /** Who called it (only known to the server). */
    var rider: UUID? = null
    private var riderless = 0

    override fun tick() {
        super.tick()
        val level = level()
        if (level.isClientSide) {
            if (tickCount % 3 == 0) {
                level.addParticle(ParticleTypes.SOUL, x + (random.nextDouble() - 0.5) * bbWidth, y + random.nextDouble() * bbHeight,
                    z + (random.nextDouble() - 0.5) * bbWidth, 0.0, 0.02, 0.0)
            }
            if (onGround() && deltaMovement.horizontalDistanceSqr() > 0.01) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x + (random.nextDouble() - 0.5) * bbWidth, y + 0.1,
                    z + (random.nextDouble() - 0.5) * bbWidth, 0.0, 0.01, 0.0)
            }
            return
        }
        if (level !is ServerLevel) return
        // Read back from a save: nobody called this one
        val caller = rider ?: run {
            discard()
            return
        }
        // Bound to whoever called it: gone with them, and a little after they get down
        val owner = level.getPlayerByUUID(caller)
        if (owner == null || !owner.isAlive) {
            fade(level)
            return
        }
        if (isVehicle) riderless = 0 else if (++riderless >= FADE_AFTER) fade(level)
    }

    /** It fades away, setting down anyone on it. */
    fun fade(level: ServerLevel) {
        if (isRemoved) return
        ejectPassengers()
        level.sendParticles(ParticleTypes.SOUL, x, y + 1.0, z, 20, 0.5, 0.6, 0.5, 0.02)
        level.playSound(null, x, y, z, ModSounds.RIDER_VANISH.get(), SoundSource.NEUTRAL, 0.5f, 1.4f)
        rider?.let { called.remove(it, uuid) }
        discard()
    }

    // Only the one who called it rides it, and there's nothing else to do with it. They can get straight back on
    // (riding would otherwise keep them off for as long as it waits for them)
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (player.uuid != rider || isVehicle || player.isShiftKeyDown) return InteractionResult.PASS
        if (!level().isClientSide) mount(player)
        return InteractionResult.SUCCESS
    }

    private fun mount(player: Player): Boolean {
        player.yRot = yRot
        player.xRot = xRot
        return player.startRiding(this, true, true)
    }

    // It needs no saddle: it was called to be ridden
    override fun isSaddled(): Boolean = true

    override fun openCustomInventoryScreen(player: Player) {}

    override fun canShearEquipment(player: Player): Boolean = false

    override fun canBeLeashed(): Boolean = false

    override fun isFood(itemStack: ItemStack): Boolean = false

    override fun canMate(partner: Animal): Boolean = false

    override fun dropEquipment(level: ServerLevel) {}

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY) && super.hurtServer(level, source, amount)

    companion object {
        /** Ticks it waits for its rider after they get down. */
        private const val FADE_AFTER = 60
        private const val SPEED = 0.32
        private const val JUMP = 0.85
        /** Headroom it needs over its back for its rider. */
        private const val RIDER_ROOM = 1.6
        /** Whose steed is whose, so calling a second sends off the first. */
        private val called = HashMap<UUID, UUID>()

        fun createAttributes() = SkeletonHorse.createAttributes()

        /**
         * Calls [player]'s steed to them, sending off any they already had, and sets them on it; null if there's
         * no room for it with them on its back.
         */
        fun summon(level: ServerLevel, player: Player): SpectralSteed? {
            val steed = ModEntities.SPECTRAL_STEED.get().create(level, EntitySpawnReason.MOB_SUMMONED) ?: return null
            steed.snapTo(player.x, player.y, player.z, player.yRot, 0f)
            if (!level.noCollision(steed, steed.boundingBox.expandTowards(0.0, RIDER_ROOM, 0.0))) return null
            called.remove(player.uuid)?.let { old -> (level.getEntity(old) as? SpectralSteed)?.fade(level) }
            steed.setTamed(true)
            steed.setOwner(player)
            steed.rider = player.uuid
            steed.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = SPEED
            steed.getAttribute(Attributes.JUMP_STRENGTH)?.baseValue = JUMP
            steed.setPersistenceRequired()
            level.addFreshEntity(steed)
            if (!steed.mount(player)) {
                steed.discard()
                return null
            }
            called[player.uuid] = steed.uuid
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, steed.x, steed.y + 0.8, steed.z, 30, 0.6, 0.6, 0.6, 0.03)
            level.playSound(null, steed.x, steed.y, steed.z, ModSounds.RIDER_NEIGH.get(), SoundSource.NEUTRAL, 0.8f, 1.25f)
            return steed
        }
    }
}
