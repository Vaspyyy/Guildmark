package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.renderer.entity.DonkeyRenderer
import net.minecraft.client.renderer.entity.LlamaRenderer
import net.minecraft.client.renderer.entity.state.DonkeyRenderState
import net.minecraft.client.renderer.entity.state.LlamaRenderState
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.Llama
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * The trader's carrying, seen and heard (living-world/trader/design.md, Part 3; build part D1): the laden pack on a trader's back
 * ([LadenLayer]) and the harness on a pack animal ([PackHarnessLayer]). It is the client's own entry for this part, beside
 * [io.github.vaspyyy.guildmark.client.GuildmarkClient], so that the start-up code of the client is left as it was.
 *
 * Both layers draw from art stream X2's meshes through one call each ([LadenArt.table], [PackHarnessArt.table]); a table that answers null
 * (or a mesh that cannot be baked, or colours that do not match the kit's) registers the listeners and nothing else: no layer is added to
 * anyone, no animal loses its chest boxes, no pot clinks, and the game looks and sounds as it did. What the layers draw from is synced from
 * the server (`Carry.LADEN` on a player, `Carry.PACK_KIT` on an animal), so a switch turned off on the server leaves nothing to draw.
 */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class CarryClient(modBus: IEventBus) {
    init {
        modBus.addListener(EntityRenderersEvent.AddLayers::class.java) { event ->
            // A mesh that cannot be baked leaves the game as it was rather than stopping it starting
            try {
                LadenArt.addTo(event)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.error("The laden packs could not be added to the players", e)
            }
            try {
                PackHarnessArt.addTo(event)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.error("The pack harness could not be added to the animals", e)
            }
        }
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerAvatarEntityModifier(LadenClient.AVATAR)
            // The donkey's renderer draws mules too
            event.registerEntityModifier<AbstractChestedHorse, DonkeyRenderState>(DonkeyRenderer::class.java) { animal, state ->
                PackHarnessClient.donkey(animal, state)
            }
            event.registerEntityModifier<Llama, LlamaRenderState>(LlamaRenderer::class.java) { animal, state ->
                PackHarnessClient.llama(animal, state)
            }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> LadenSounds.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            LadenClient.reset()
            PackHarnessClient.reset()
        }
    }
}
