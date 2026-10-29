package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.TradeBlocks
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.resources.Identifier
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.EntityRenderersEvent

/**
 * The Carriers' Store's and the Counting House's fittings, drawn (living-world/trader/design.md, Part 5; build part F2): the renderers of
 * [TradeBlocks]' block entities on art stream X3's meshes ([StoreArt], [HouseArt]). The client's own entry for this part, beside
 * [io.github.vaspyyy.guildmark.client.GuildmarkClient], so that the client's start-up code is left as it was. What the renderers draw
 * comes from the blocks' own synced data, so with the Store switched off on the server there is nothing new to draw.
 */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class TradeBlocksClient(modBus: IEventBus) {
    init {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(TradeBlocks.SCALE_ENTITY.get(), ::StoreScaleRenderer)
            event.registerBlockEntityRenderer(TradeBlocks.BOARD_ENTITY.get(), ::NameBoardRenderer)
            event.registerBlockEntityRenderer(TradeBlocks.DOOR_BOARD_ENTITY.get(), ::DoorBoardRenderer)
            event.registerBlockEntityRenderer(TradeBlocks.STRONGBOX_ENTITY.get(), ::StrongboxRenderer)
            event.registerBlockEntityRenderer(TradeBlocks.FRAME_ENTITY.get(), ::CountingFrameRenderer)
            event.registerBlockEntityRenderer(TradeBlocks.MAP_ENTITY.get(), ::HouseMapRenderer)
            event.registerBlockEntityRenderer(TradeBlocks.BELL_ENTITY.get(), ::HouseBellRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(SCALE, StoreArt::scaleMesh)
            event.registerLayerDefinition(STORE_BOARDS, StoreArt::boardsMesh)
            event.registerLayerDefinition(STRONGBOX, StoreArt::strongboxMesh)
            event.registerLayerDefinition(BEADS, StoreArt::beadsMesh)
            event.registerLayerDefinition(HOUSE_MAP, HouseArt::mapMesh)
            event.registerLayerDefinition(HOUSE_BOARDS, HouseArt::boardsMesh)
            event.registerLayerDefinition(HOUSE_BELL, HouseArt::bellMesh)
        }
    }

    companion object {
        val SCALE = layer("store_scale")
        val STORE_BOARDS = layer("store_boards")
        val STRONGBOX = layer("strongbox")
        val BEADS = layer("counting_beads")
        val HOUSE_MAP = layer("house_map")
        val HOUSE_BOARDS = layer("house_boards")
        val HOUSE_BELL = layer("house_bell")

        private fun layer(name: String) = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name), "main")
    }
}
