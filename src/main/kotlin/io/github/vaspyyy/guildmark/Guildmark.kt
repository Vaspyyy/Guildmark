package io.github.vaspyyy.guildmark

import com.mojang.logging.LogUtils
import io.github.vaspyyy.guildmark.network.TakeNotePayload
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
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

        modBus.addListener(FMLCommonSetupEvent::class.java, ::onCommonSetup)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, ::onRegisterPayloads)
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java, ::onLivingDeath)
    }

    private fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.info("Guildmark loaded, the boards are open")
    }

    private fun onLivingDeath(event: LivingDeathEvent) {
        val killer = event.source.entity as? Player ?: return
        if (!killer.level().isClientSide()) Contracts.onKill(killer, event.entity)
    }

    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("1").playToServer(TakeNotePayload.TYPE, TakeNotePayload.STREAM_CODEC, TakeNotePayload::handle)
    }
}
