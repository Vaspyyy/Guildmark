package io.github.vaspyyy.guildmark.client.cooking

import net.neoforged.bus.api.IEventBus

/**
 * The town's diners and their plates (living-world/cook/design.md 7.4): the dish laid flat on the table in front of a
 * townsperson eating, shown in proportion to what the inn last served, from the last `ServedPayload` of each capital. Part
 * I3's; [CookClient.register] calls it, so I3 edits no shared file. Until it lands, nothing is registered and the town eats
 * as it always did.
 */
object TownPlates {
    fun register(modBus: IEventBus) {}
}
