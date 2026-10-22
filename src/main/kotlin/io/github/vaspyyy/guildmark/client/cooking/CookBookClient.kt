package io.github.vaspyyy.guildmark.client.cooking

import net.neoforged.bus.api.IEventBus

/**
 * The Cook's Book on the client (living-world/cook/design.md 3.4): whatever Part D registers beside its screen, which the
 * item opens the way the Almanac's does (a call behind `level.isClientSide`). [CookClient.register] calls it, so D edits no
 * shared file. Part D's; until it lands, nothing is registered.
 */
object CookBookClient {
    fun register(modBus: IEventBus) {}
}
