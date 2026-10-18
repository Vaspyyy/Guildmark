package io.github.vaspyyy.guildmark.client.economy

import io.github.vaspyyy.guildmark.economy.WaresRegistry
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent

/** The client's half of the market's displays of goods: the renderer that heaps their goods on them. */
object WaresClient {
    /** Registers the renderer. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(WaresRegistry.ENTITY.get(), ::WaresRenderer)
        }
    }
}
