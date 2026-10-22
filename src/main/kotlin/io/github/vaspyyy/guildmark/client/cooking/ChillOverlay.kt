package io.github.vaspyyy.guildmark.client.cooking

import net.neoforged.bus.api.IEventBus

/**
 * The cold vignette and the hugged arms of Winter Chill (living-world/cook/design.md 4.3, 9.4), drawn only when the
 * player's `chillOverlay` option is on. Part F's; [CookClient.register] calls it, so F edits no shared file. Until it
 * lands, nothing is registered and nothing is drawn.
 */
object ChillOverlay {
    fun register(modBus: IEventBus) {}
}
