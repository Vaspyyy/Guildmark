package io.github.vaspyyy.guildmark.client.mill

import io.github.vaspyyy.guildmark.mill.MillBlockEntity
import io.github.vaspyyy.guildmark.mill.MillRegistry
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * The mills on a client: the windmills' sails and the watermills' wheels drawn turning on their hubs ([MillRenderer] and
 * its three models, whose meshes are [MillMesh]), and the sounds by a mill while it turns ([MillSounds]). How each hub
 * turns is eased along by its own block entity ([MillBlockEntity.clientTick]).
 */
object MillClient {
    /** Has it all registered, from the client's setup. */
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(MillRegistry.MILL.get(), ::MillRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(SailsModel.LAYER, MillMesh::sails)
            event.registerLayerDefinition(ClothModel.LAYER, MillMesh::cloth)
            event.registerLayerDefinition(WheelModel.LAYER, MillMesh::wheel)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> MillSounds.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            MillSounds.reset()
            MillBlockEntity.LOADED.clear()
        }
    }
}
