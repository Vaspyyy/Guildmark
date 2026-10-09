package io.github.vaspyyy.guildmark

import com.mojang.logging.LogUtils
import io.github.vaspyyy.guildmark.advance.AdvanceCommand
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.network.SpendPerkPayload
import io.github.vaspyyy.guildmark.network.TakeNotePayload
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.QuestPoolLoader
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModVillagers
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.event.AddServerReloadListenersEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.slf4j.Logger

@Mod(Guildmark.MOD_ID)
class Guildmark(modBus: IEventBus, container: ModContainer) {
    companion object {
        const val MOD_ID = "guildmark"
        val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        ModBlocks.BLOCKS.register(modBus)
        ModItems.ITEMS.register(modBus)
        ModBlockEntities.BLOCK_ENTITIES.register(modBus)
        ModDataComponents.COMPONENTS.register(modBus)
        ModCreativeTabs.TABS.register(modBus)
        ModVillagers.POI_TYPES.register(modBus)
        ModVillagers.PROFESSIONS.register(modBus)
        ModAttachments.ATTACHMENTS.register(modBus)

        modBus.addListener(FMLCommonSetupEvent::class.java, ::onCommonSetup)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, ::onRegisterPayloads)
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java, ::onLivingDeath)
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java, ::onPlayerTick)
        // Stat perks are transient attribute modifiers, so put them back whenever the player entity is new
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { Progression.applyAttributes(it.entity) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { Progression.applyAttributes(it.entity) }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { AdvanceCommand.register(it.dispatcher) }
        NeoForge.EVENT_BUS.addListener(AddServerReloadListenersEvent::class.java) { event ->
            event.addListener(Identifier.fromNamespaceAndPath(MOD_ID, "quest_pools"), QuestPoolLoader())
        }
    }

    private fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.info("Guildmark loaded, the boards are open")
    }

    private fun onLivingDeath(event: LivingDeathEvent) {
        val killer = event.source.entity as? Player ?: return
        if (!killer.level().isClientSide()) Contracts.onKill(killer, event.entity)
    }

    private fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity
        val level = player.level()
        if (level !is ServerLevel) return
        if (player.tickCount % 200 == 0) VillageBoards.checkNearbyVillages(level, player.blockPosition())
        if (player.tickCount % 10 == 0) Contracts.tickEscorts(level, player)
    }

    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToServer(TakeNotePayload.TYPE, TakeNotePayload.STREAM_CODEC, TakeNotePayload::handle)
            .playToServer(SpendPerkPayload.TYPE, SpendPerkPayload.STREAM_CODEC, SpendPerkPayload::handle)
    }
}
