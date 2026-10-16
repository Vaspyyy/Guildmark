package io.github.vaspyyy.guildmark.client.island

import io.github.vaspyyy.guildmark.island.IslandMarks
import io.github.vaspyyy.guildmark.island.IslandPayload
import io.github.vaspyyy.guildmark.island.IslandRegistry
import io.github.vaspyyy.guildmark.island.IslandSoundSpot
import io.github.vaspyyy.guildmark.island.plan.IslandKind
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.SelectMusicEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * The client's half of the islands: the island whose waters the player is in, as the server last said, and what hangs
 * off it (its sounds and its music); the gulls' renderer and the lighthouse lamp's beams.
 */
object IslandClient {
    /** The island the player is at: what it is (IslandMarks), its middle, its sea, its land's reach, its own sounds. */
    class Visit(val ref: Int, val cx: Int, val cz: Int, val sea: Int, val radius: Int, val spots: List<IslandSoundSpot>) {
        val kind: IslandKind = IslandMarks.kind(ref)
    }

    var current: Visit? = null
        private set

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ ->
            IslandAmbience.tick()
            IslandMusic.tick()
        }
        // Before a region's theme has its say (a boss's music has had its own, and cancels the event)
        NeoForge.EVENT_BUS.addListener(SelectMusicEvent::class.java, IslandMusic::onSelectMusic)
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(IslandRegistry.GULL.get(), ::GullRenderer)
            event.registerBlockEntityRenderer(IslandRegistry.LIGHTHOUSE_LAMP_ENTITY.get(), ::LighthouseLampRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(GullModel.LAYER, GullModel::createBodyLayer)
        }
    }

    fun receive(payload: IslandPayload) {
        val before = current
        current = if (payload.radius <= 0) null else Visit(payload.ref, payload.cx, payload.cz, payload.sea, payload.radius, payload.spots)
        if ((before == null) != (current == null)) IslandMusic.changed(current != null)
    }

    private fun reset() {
        current = null
        IslandAmbience.reset()
        IslandMusic.reset()
    }
}
