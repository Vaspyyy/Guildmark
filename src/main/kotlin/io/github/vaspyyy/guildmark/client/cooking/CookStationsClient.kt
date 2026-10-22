package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookStats
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.ModelEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * The stations seen and heard (living-world/cook/design.md 1.5, 1.6, 1.8): the eight block entity renderers, the particle
 * providers, the loops and the crosshair hints. Part C's; [CookClient.register] calls it, so C edits no shared file.
 *
 * What is here: [StationFx] (the stations alive on this client, stepped each tick), [CookParticleProviders] (the seven
 * particles), [CookLoops] (the four loops, three at most), the renderers (each in its own file: the Stewpot, the Hob, the Bake
 * oven, the Spit, the Prep Board, the Plate, the Larder and the Menu board), [CookPieces] (the moving pieces they draw) and [CookHints].
 */
object CookStationsClient {
    private val ITEMS_ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cook_items")

    fun register(modBus: IEventBus) {
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event -> CookParticleProviders.register(event) }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event -> renderers(event) }
        modBus.addListener(ModelEvent.RegisterStandalone::class.java) { event -> CookPieces.register(event) }
        modBus.addListener(AddClientReloadListenersEvent::class.java) { event -> event.addListener(ITEMS_ID, ItemsReload) }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> StationFx.tick() }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> StationFx.reset() }
        CookStats.readout("stations seen and heard") { StationFx.readout() }
    }

    private fun renderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(CookRegistry.STEWPOT_ENTITY.get(), ::StewpotRenderer)
        event.registerBlockEntityRenderer(CookRegistry.HOB_ENTITY.get(), ::HobRenderer)
        event.registerBlockEntityRenderer(CookRegistry.OVEN_ENTITY.get(), ::OvenRenderer)
        event.registerBlockEntityRenderer(CookRegistry.SPIT_ENTITY.get(), ::SpitRenderer)
        event.registerBlockEntityRenderer(CookRegistry.PREP_BOARD_ENTITY.get(), ::PrepBoardRenderer)
        event.registerBlockEntityRenderer(CookRegistry.PLATE_ENTITY.get(), ::PlateRenderer)
        event.registerBlockEntityRenderer(CookRegistry.LARDER_ENTITY.get(), ::LarderRenderer)
    }

    /** The pictures of items that the renderers keep are looked up again when the resources reload (the atlases they point into are made again). */
    private object ItemsReload : SimplePreparableReloadListener<Unit>() {
        override fun prepare(manager: ResourceManager, profiler: ProfilerFiller) {}

        override fun apply(prepared: Unit, manager: ResourceManager, profiler: ProfilerFiller) {
            CookItems.clear()
        }
    }
}
