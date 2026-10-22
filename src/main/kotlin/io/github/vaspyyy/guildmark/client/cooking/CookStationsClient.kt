package io.github.vaspyyy.guildmark.client.cooking

import net.neoforged.bus.api.IEventBus

/**
 * The stations seen and heard (living-world/cook/design.md 1.5, 1.6, 1.8): the eight block entity renderers, the particle
 * providers, the loops and the crosshair hints. Part C's; [CookClient.register] calls it, so C edits no shared file.
 * Until it lands, nothing is registered and the stations are drawn from their block models.
 */
object CookStationsClient {
    fun register(modBus: IEventBus) {}
}
