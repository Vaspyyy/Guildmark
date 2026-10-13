package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.client.model.RivalModel
import io.github.vaspyyy.guildmark.client.model.DuskRiderModel
import io.github.vaspyyy.guildmark.client.model.FallenWardenModel
import io.github.vaspyyy.guildmark.client.model.HillTrollModel
import io.github.vaspyyy.guildmark.client.model.HollowSoldierModel
import io.github.vaspyyy.guildmark.client.model.RegionHollowMesh
import io.github.vaspyyy.guildmark.client.model.RegionHollowModel
import io.github.vaspyyy.guildmark.enemy.HollowSoldier
import io.github.vaspyyy.guildmark.registry.ModAttachments
import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.KeyMapping
import io.github.vaspyyy.guildmark.client.model.HollowKingModel
import io.github.vaspyyy.guildmark.client.model.StoryNpcModel
import io.github.vaspyyy.guildmark.registry.ModEntities
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraft.client.renderer.entity.VillagerRenderer
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.minecraft.resources.Identifier
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import io.github.vaspyyy.guildmark.client.wardfire.FlaskFill
import io.github.vaspyyy.guildmark.client.wardfire.FlaskHud
import io.github.vaspyyy.guildmark.client.wardfire.TravelFade
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent
import io.github.vaspyyy.guildmark.client.wardfire.WardEmberParticle
import io.github.vaspyyy.guildmark.registry.ModParticles
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import io.github.vaspyyy.guildmark.client.combat.CameraFx
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.client.combat.LockOn
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import io.github.vaspyyy.guildmark.client.combat.FirstPersonCombat
import io.github.vaspyyy.guildmark.client.combat.CombatHud
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.client.ui.CharacterScreen
import io.github.vaspyyy.guildmark.client.ui.FallenScreen
import io.github.vaspyyy.guildmark.client.ui.SettingsScreen
import io.github.vaspyyy.guildmark.client.ui.title.TitleMenu
import io.github.vaspyyy.guildmark.client.atlas.AtlasClient
import io.github.vaspyyy.guildmark.client.ambience.CityAmbience
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkClient
import io.github.vaspyyy.guildmark.client.region.RegionClient
import io.github.vaspyyy.guildmark.client.dragon.DragonClient
import io.github.vaspyyy.guildmark.client.atlas.CompassHud
import io.github.vaspyyy.guildmark.client.atlas.MapData
import io.github.vaspyyy.guildmark.client.atlas.MapScreen
import io.github.vaspyyy.guildmark.client.atlas.PlaceBanner
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.event.SelectMusicEvent
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.world.entity.LivingEntity
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RenderHandEvent
import net.neoforged.neoforge.client.event.RenderLivingEvent
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent
import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.ViewportEvent

/** Client-only setup: keys, renderers, overlays, and the client half of the combat. */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class GuildmarkClient(modBus: IEventBus, container: ModContainer) {
    private val shownTitles = HashMap<java.util.UUID, String>()

    private val category = KeyMapping.Category(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "main"))
    private val ledgerKey = KeyMapping("key.guildmark.ledger", InputConstants.KEY_G, category)
    private val trackerKey = KeyMapping("key.guildmark.tracker", InputConstants.KEY_H, category)

    init {
        container.registerConfig(ModConfig.Type.CLIENT, GuildmarkClientConfig.SPEC)
        // Mods > Guildmark > Config opens Guildmark's own settings, as the crest by the Mods button does
        container.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory { _, parent -> SettingsScreen(parent) })
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post::class.java, SettingsScreen::onScreenInit)
        modBus.addListener(RegisterKeyMappingsEvent::class.java) { event ->
            event.registerCategory(category)
            event.register(ledgerKey)
            event.register(trackerKey)
            event.register(MapScreen.KEY)
            event.register(CharacterScreen.KEY)
            event.registerCategory(CombatClient.CATEGORY)
            event.register(CombatClient.DODGE)
            event.register(CombatClient.LOCK_ON)
            event.register(CombatClient.SKILL)
            event.register(CombatClient.FLASK)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre::class.java, CombatClient::onTick)
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre::class.java) { _ -> SpectralSteedControls.onTick() }
        NeoForge.EVENT_BUS.addListener(MovementInputUpdateEvent::class.java, CombatClient::onMovementInput)
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered::class.java, CombatClient::onInteraction)
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre::class.java, LockOn::onFrame)
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles::class.java, CameraFx::onAngles)
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov::class.java, CameraFx::onFov)
        NeoForge.EVENT_BUS.addListener(RenderHandEvent::class.java, FirstPersonCombat::onRenderHand)
        NeoForge.EVENT_BUS.addListener(RenderGuiLayerEvent.Pre::class.java, CombatHud::onLayer)
        NeoForge.EVENT_BUS.addListener(CustomizeGuiOverlayEvent.BossEventProgress::class.java, CombatHud::onBossBar)
        // Last in line, so nothing cancels the render (and its Post, which pops the pose) after the lean is pushed
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RenderLivingEvent.Pre::class.java) { event: RenderLivingEvent.Pre<*, *, *> -> CombatAnimator.onRenderPre(event) }
        // Cutscenes: the camera goes first so shakes land on top of it, and hiding the hand or the player wins
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> Cutscenes.onTick() }
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, ViewportEvent.ComputeCameraAngles::class.java, Cutscenes::onAngles)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, ViewportEvent.ComputeFov::class.java, Cutscenes::onFov)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, RenderHandEvent::class.java, Cutscenes::onRenderHand)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, RenderLivingEvent.Pre::class.java) { event: RenderLivingEvent.Pre<*, *, *> -> Cutscenes.onRenderPre(event) }
        NeoForge.EVENT_BUS.addListener(RenderGuiLayerEvent.Pre::class.java, Cutscenes::onLayer)
        NeoForge.EVENT_BUS.addListener(SelectMusicEvent::class.java, Cutscenes::onSelectMusic)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening::class.java, Cutscenes::onScreenOpening)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening::class.java) { event: ScreenEvent.Opening -> FallenScreen.onScreenOpening(event) }
        TitleMenu.register()
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            Cutscenes.reset()
            AtlasClient.reset()
            PlaceBanner.reset()
            MapData.reset()
            TravelFade.reset()
            CityAmbience.reset()
        }
        // The compass pushes vanilla's boss bars down while it's up; last in line, so the pop always follows the push
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RenderGuiLayerEvent.Pre::class.java, CompassHud::onLayerPre)
        NeoForge.EVENT_BUS.addListener(RenderGuiLayerEvent.Post::class.java, CompassHud::onLayerPost)
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> PlaceBanner.tick() }
        // The map sketches the land as it loads around the player
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> MapData.tick() }
        // The sounds of towns and cities, from what's round the player (client/ambience)
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> CityAmbience.tick() }
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Post::class.java) { event: RenderLivingEvent.Post<*, *, *> -> CombatAnimator.onRenderPost(event) }
        modBus.addListener(AddClientReloadListenersEvent::class.java) { event -> event.addListener(Clips.ID, Clips) }
        // Townsfolk: their renderer, the sparks off a smith's hammer (client/townsfolk)
        TownsfolkClient.register(modBus)
        // Regions: the title card, the air, the music and the names on the map (client/region)
        RegionClient.register(modBus)
        // Dragons: their renderer, their flames, the ground shaking as they land (client/dragon)
        DragonClient.register(modBus)
        modBus.addListener(RegisterRenderStateModifiersEvent::class.java) { event ->
            event.registerAvatarEntityModifier(CombatAnimator.AVATAR)
            event.registerAvatarEntityModifier(Cutscenes.AVATAR)
            event.registerEntityModifier<LivingEntity, LivingEntityRenderState>(LivingEntityRenderer::class.java) { entity, state ->
                CombatAnimator.onLivingState(entity, state)
            }
        }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(ModEntities.GUILD_MEMBER.get(), ::VillagerRenderer)
            event.registerEntityRenderer(ModEntities.STORY_NPC.get(), ::StoryNpcRenderer)
            event.registerEntityRenderer(ModEntities.HOLLOW_KING.get(), ::HollowKingRenderer)
            event.registerEntityRenderer(ModEntities.RIVAL.get(), ::RivalRenderer)
            event.registerEntityRenderer(ModEntities.HOLLOW_SOLDIER.get(), ::HollowSoldierRenderer)
            event.registerEntityRenderer<HollowSoldier>(ModEntities.MIRE_HOLLOW.get()) { RegionHollowRenderer(it, RegionHollowModel.MIRE, "mire_hollow") }
            event.registerEntityRenderer<HollowSoldier>(ModEntities.CINDER_HOLLOW.get()) { RegionHollowRenderer(it, RegionHollowModel.CINDER, "cinder_hollow") }
            event.registerEntityRenderer<HollowSoldier>(ModEntities.RIME_HOLLOW.get()) { RegionHollowRenderer(it, RegionHollowModel.RIME, "rime_hollow") }
            event.registerEntityRenderer<HollowSoldier>(ModEntities.GLIMMER_HOLLOW.get()) { RegionHollowRenderer(it, RegionHollowModel.GLIMMER, "glimmer_hollow") }
            event.registerEntityRenderer(ModEntities.FALLEN_WARDEN.get(), ::FallenWardenRenderer)
            event.registerEntityRenderer(ModEntities.HILL_TROLL.get(), ::HillTrollRenderer)
            event.registerEntityRenderer(ModEntities.TROLL_BOULDER.get()) { ThrownItemRenderer(it, 3.0f, false) }
            event.registerEntityRenderer(ModEntities.LOST_MARKS.get(), ::LostMarksRenderer)
            event.registerEntityRenderer(ModEntities.DUSK_RIDER.get(), ::DuskRiderRenderer)
            event.registerEntityRenderer(ModEntities.LANTERN_WISP.get()) { ThrownItemRenderer(it, 1.2f, true) }
            event.registerEntityRenderer<net.minecraft.world.entity.animal.equine.AbstractHorse>(ModEntities.SPECTRAL_STEED.get(), ::SpectralSteedRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(StoryNpcModel.LAYER, StoryNpcModel::createBodyLayer)
            event.registerLayerDefinition(HollowKingModel.LAYER, HollowKingModel::createBodyLayer)
            event.registerLayerDefinition(RivalModel.LAYER, RivalModel::createBodyLayer)
            event.registerLayerDefinition(HollowSoldierModel.LAYER, HollowSoldierModel::createBodyLayer)
            event.registerLayerDefinition(RegionHollowModel.LAYER, RegionHollowMesh::createBodyLayer)
            event.registerLayerDefinition(FallenWardenModel.LAYER, FallenWardenModel::createBodyLayer)
            event.registerLayerDefinition(HillTrollModel.LAYER, HillTrollModel::createBodyLayer)
            event.registerLayerDefinition(DuskRiderModel.LAYER, DuskRiderModel::createBodyLayer)
        }
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event ->
            event.registerAbove(VanillaGuiLayers.EFFECTS, ContractTracker.ID, ContractTracker)
            event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, CombatHud.ID, CombatHud)
            event.registerBelow(VanillaGuiLayers.BOSS_OVERLAY, CompassHud.ID, CompassHud)
            event.registerBelow(VanillaGuiLayers.TITLE, PlaceBanner.ID, PlaceBanner)
            event.registerAboveAll(Cutscenes.ID, Cutscenes)
            event.registerAboveAll(TravelFade.ID, TravelFade)
            event.registerAbove(VanillaGuiLayers.HOTBAR, FlaskHud.ID, FlaskHud)
        }
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event ->
            event.registerSpriteSet(ModParticles.WARD_EMBER.get()) { sprites -> WardEmberParticle.Provider(sprites) }
        }
        modBus.addListener(RegisterRangeSelectItemModelPropertyEvent::class.java) { event ->
            event.register(FlaskFill.ID, FlaskFill.MAP_CODEC)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> TravelFade.tick() }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ ->
            val minecraft = Minecraft.getInstance()
            while (ledgerKey.consumeClick()) {
                if (minecraft.gui.screen() == null && minecraft.player != null) minecraft.gui.setScreen(LedgerScreen())
            }
            while (trackerKey.consumeClick()) ContractTracker.shown = !ContractTracker.shown
            while (MapScreen.KEY.consumeClick()) {
                if (minecraft.gui.screen() == null && minecraft.player != null) minecraft.gui.setScreen(MapScreen())
            }
            while (CharacterScreen.KEY.consumeClick()) {
                if (minecraft.gui.screen() == null && minecraft.player != null) minecraft.gui.setScreen(CharacterScreen())
            }
            // Titles arrive as synced data: names are cached, so redo them when a title changes
            val level = minecraft.level
            if (level != null && level.gameTime % 10L == 0L) {
                for (player in level.players()) {
                    val title = player.getData(ModAttachments.TITLE)
                    if (shownTitles[player.uuid] != title) {
                        shownTitles[player.uuid] = title
                        player.refreshDisplayName()
                    }
                }
            }
        }
    }
}
