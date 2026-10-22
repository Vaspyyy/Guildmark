package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.cooking.CookStats
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * The stations seen and heard (living-world/cook/design.md 1.5, 1.6, 1.8): the eight block entity renderers, the particle
 * providers, the loops and the crosshair hints. Part C's; [CookClient.register] calls it, so C edits no shared file.
 *
 * What is here: [StationFx] (the stations alive on this client, stepped each tick), [CookParticleProviders] (the seven
 * particles), [CookLoops] (the four loops, three at most) and the renderers of [CookRenderers].
 */
object CookStationsClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event -> CookParticleProviders.register(event) }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> StationFx.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> StationFx.reset() }
        CookStats.readout("stations seen and heard") { StationFx.readout() }
    }
}
