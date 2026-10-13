package io.github.vaspyyy.guildmark.client.dragon

import io.github.vaspyyy.guildmark.client.combat.CameraFx
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.dragon.Dragon
import io.github.vaspyyy.guildmark.dragon.DragonFireball
import io.github.vaspyyy.guildmark.dragon.DragonRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.NoopRenderer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * The client half of the dragons: the renderer and its model, the flames of their fire (a fire bolt is
 * nothing but its own flames), and the ground shaking under the player as a dragon lands, stomps, roars or
 * falls nearby.
 */
object DragonClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(DragonRegistry.DRAGON.get(), ::DragonRenderer)
            event.registerEntityRenderer(DragonRegistry.FIREBALL.get()) { NoopRenderer<DragonFireball>(it) }
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(DragonModel.LAYER, DragonModel::createBodyLayer)
        }
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event ->
            event.registerSpriteSet(DragonRegistry.FLAME.get()) { sprites -> DragonFlameParticle.Provider(sprites) }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tremors() }
    }

    /** Shakes the camera for each dragon near enough to be felt, on the tick its weight comes down or its roar goes up. */
    private fun tremors() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isPaused) return
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        for (dragon in level.getEntitiesOfClass(Dragon::class.java, player.boundingBox.inflate(FELT))) {
            val amount = tremor(dragon)
            // A dragon that isn't ticking stays on the same tick: once is enough
            if (amount <= 0f || dragon.shookAt == dragon.tickCount) continue
            dragon.shookAt = dragon.tickCount
            val near = 1f - (dragon.distanceTo(player) / FELT.toFloat()).coerceIn(0f, 1f)
            CameraFx.shake(amount * near)
        }
    }

    /** How hard [dragon] shakes the ground this tick, or 0. */
    private fun tremor(dragon: Dragon): Float {
        if (dragon.deathTime == Dragon.DEATH_SLAM) return 0.8f
        if (dragon.deathTime > 0) return 0f
        val (attack, time) = dragon.shownAttack(0f) ?: return 0f
        val t = time.toInt()
        return when (attack) {
            Dragon.STOMP -> if (t == blowOf(attack)) 0.6f else 0f
            Dragon.LAND -> if (t == blowOf(attack)) 0.5f else 0f
            Dragon.DIVE -> if (t == blowOf(attack)) 0.75f else 0f
            Dragon.ENRAGE -> when (t) {
                ENRAGE_ROAR -> 0.4f
                blowOf(attack) -> 0.8f
                else -> 0f
            }
            Dragon.TAKEOFF -> if (t == TAKEOFF_BEAT) 0.25f else 0f
            Dragon.ROAR -> if (t == GROUND_ROAR) 0.35f else 0f
            Dragon.PHASE_ROAR -> if (t == GROUND_ROAR) 0.45f else 0f
            Dragon.ROAR_AIR -> if (t == AIR_ROAR) 0.3f else 0f
            Dragon.PHASE_ROAR_AIR -> if (t == AIR_ROAR) 0.4f else 0f
            else -> 0f
        }
    }

    /** The tick [attack] lands its blow on. */
    private fun blowOf(attack: EnemyAttack): Int = attack.blows.first().at

    /** Blocks away a dragon's weight is still felt. */
    private const val FELT = 40.0
    /** The ticks of its roars and its leap into the air (art/scripts/dragon_anims.py, and Dragon.onAttackTick). */
    private const val GROUND_ROAR = 8
    private const val AIR_ROAR = 6
    private const val ENRAGE_ROAR = 10
    private const val TAKEOFF_BEAT = 10
}
