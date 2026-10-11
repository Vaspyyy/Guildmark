package io.github.vaspyyy.guildmark

import net.minecraft.server.level.ServerPlayer
import io.github.vaspyyy.guildmark.party.PartyCommand
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.guild.Rivals
import io.github.vaspyyy.guildmark.guild.RivalAdventurer
import io.github.vaspyyy.guildmark.network.ClaimVillagePayload
import io.github.vaspyyy.guildmark.network.OpenAdvancesPayload
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.progression.Milestones
import com.mojang.logging.LogUtils
import io.github.vaspyyy.guildmark.advance.AdvanceCommand
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.network.SpendPerkPayload
import io.github.vaspyyy.guildmark.network.TakeNotePayload
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.QuestPoolLoader
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.guild.Reception
import io.github.vaspyyy.guildmark.guild.Recruiting
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.network.AcceptChapterPayload
import io.github.vaspyyy.guildmark.network.FoundGuildPayload
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.story.Characters
import io.github.vaspyyy.guildmark.story.HollowKing
import io.github.vaspyyy.guildmark.story.HollowThrone
import io.github.vaspyyy.guildmark.story.StoryNpc
import io.github.vaspyyy.guildmark.network.OpenReceptionPayload
import io.github.vaspyyy.guildmark.network.ReceptionActionPayload
import io.github.vaspyyy.guildmark.road.Bandits
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.road.Traffic
import io.github.vaspyyy.guildmark.siege.Sieges
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
import net.minecraft.world.level.Level
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
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
        ModEntities.ENTITIES.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) {
            it.put(ModEntities.GUILD_MEMBER.get(), GuildMember.createAttributes().build())
            it.put(ModEntities.STORY_NPC.get(), StoryNpc.createAttributes().build())
            it.put(ModEntities.HOLLOW_KING.get(), HollowKing.createAttributes().build())
            it.put(ModEntities.RIVAL.get(), RivalAdventurer.createAttributes().build())
        }

        modBus.addListener(FMLCommonSetupEvent::class.java, ::onCommonSetup)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, ::onRegisterPayloads)
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java, ::onLivingDeath)
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java, ::onPlayerTick)
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { Traffic.onJoin(it.entity) }
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock::class.java, HollowThrone::onUseBlock)
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java, Relics::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java, Parties::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java, Relics::onDamaged)
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteract::class.java, Reception::onInteract)
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level
            if (level is ServerLevel) {
                RoadNetwork.get(level).tick(level)
                if (level.gameTime % 10L == 0L) Traffic.tick(level)
                if (level.gameTime % 20L == 0L) {
                    Lairs.get(level).tick(level)
                    Sieges.tick(level)
                    if (level.dimension() == Level.OVERWORLD) HollowThrone.get(level).tick(level)
                }
                if (level.dimension() == Level.OVERWORLD) TownControl.tick(level)
            }
        }
        // Stat perks are transient attribute modifiers, so put them back whenever the player entity is new
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) {
            Progression.applyAttributes(it.entity)
            Milestones.onLogin(it.entity)
            (it.entity as? ServerPlayer)?.let { player -> Parties.onLogin(player) }
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { Progression.applyAttributes(it.entity) }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) {
            AdvanceCommand.register(it.dispatcher)
            PartyCommand.register(it.dispatcher)
        }
        NeoForge.EVENT_BUS.addListener(AddServerReloadListenersEvent::class.java) { event ->
            event.addListener(Identifier.fromNamespaceAndPath(MOD_ID, "quest_pools"), QuestPoolLoader())
        }
    }

    private fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.info("Guildmark loaded, the boards are open")
    }

    private fun onLivingDeath(event: LivingDeathEvent) {
        val victim = event.entity
        val level = victim.level()
        if (level is ServerLevel && victim is PathfinderMob) Bandits.onDeath(level, victim)
        if (level is ServerLevel) Sieges.onDeath(level, victim)
        if (level is ServerLevel && victim is GuildMember) Recruiting.onMemberDeath(level, victim)
        if (level is ServerLevel && victim.hasData(ModAttachments.LAIR_BOSS)) Lairs.get(level).onBossDeath(level, victim.getData(ModAttachments.LAIR_BOSS))
        val killer = event.source.entity as? Player ?: return
        if (!killer.level().isClientSide()) {
            Contracts.onKill(killer, event.entity)
            Parties.onKill(killer, event.entity)
        }
    }

    private fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity
        val level = player.level()
        if (level !is ServerLevel) return
        if (player.tickCount % 200 == 0) VillageBoards.checkNearbyVillages(level, player.blockPosition())
        if (player.tickCount % 200 == 50) TownControl.visit(level, player)
        if (player.tickCount % 200 == 150) Rivals.tickPlayer(level, player)
        if (player.tickCount % 200 == 100) Characters.tick(level, player)
        if (player.tickCount % 10 == 0) Contracts.tickEscorts(level, player)
        Milestones.tick(player)
        Traffic.trySpawn(level, player)
        Sieges.maybeStart(level, player)
    }

    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToServer(TakeNotePayload.TYPE, TakeNotePayload.STREAM_CODEC, TakeNotePayload::handle)
            .playToServer(SpendPerkPayload.TYPE, SpendPerkPayload.STREAM_CODEC, SpendPerkPayload::handle)
            .playToServer(ReceptionActionPayload.TYPE, ReceptionActionPayload.STREAM_CODEC, ReceptionActionPayload::handle)
            .playToServer(FoundGuildPayload.TYPE, FoundGuildPayload.STREAM_CODEC, FoundGuildPayload::handle)
            .playToServer(AcceptChapterPayload.TYPE, AcceptChapterPayload.STREAM_CODEC, AcceptChapterPayload::handle)
            .playToClient(OpenCharacterPayload.TYPE, OpenCharacterPayload.STREAM_CODEC, OpenCharacterPayload::handle)
            .playToClient(OpenReceptionPayload.TYPE, OpenReceptionPayload.STREAM_CODEC, OpenReceptionPayload::handle)
            .playToClient(OpenAdvancesPayload.TYPE, OpenAdvancesPayload.STREAM_CODEC, OpenAdvancesPayload::handle)
            .playToServer(ClaimVillagePayload.TYPE, ClaimVillagePayload.STREAM_CODEC, ClaimVillagePayload::handle)
    }
}
