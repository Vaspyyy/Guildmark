package io.github.vaspyyy.guildmark

import io.github.vaspyyy.guildmark.companion.CompanionEntity
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.EventPriority
import io.github.vaspyyy.guildmark.death.Purses
import net.neoforged.fml.config.ModConfig
import io.github.vaspyyy.guildmark.combat.Combat
import io.github.vaspyyy.guildmark.combat.Stance
import io.github.vaspyyy.guildmark.network.MovePayload
import io.github.vaspyyy.guildmark.network.CityDistrictPayload
import io.github.vaspyyy.guildmark.atlas.Atlas
import io.github.vaspyyy.guildmark.network.AtlasPayload
import io.github.vaspyyy.guildmark.network.PartyPositionsPayload
import io.github.vaspyyy.guildmark.network.CoachStopsPayload
import io.github.vaspyyy.guildmark.network.CoachStopsRequestPayload
import io.github.vaspyyy.guildmark.network.RideCoachPayload
import io.github.vaspyyy.guildmark.network.WorldIdPayload
import io.github.vaspyyy.guildmark.network.OpenWardfirePayload
import io.github.vaspyyy.guildmark.network.WardfireActionPayload
import io.github.vaspyyy.guildmark.network.WardfireTravelPayload
import io.github.vaspyyy.guildmark.network.WardfireTravelledPayload
import io.github.vaspyyy.guildmark.network.DrinkFlaskPayload
import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.region.Regions
import io.github.vaspyyy.guildmark.wardfire.WardensFlask
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.network.PacketDistributor
import io.github.vaspyyy.guildmark.atlas.WorldId
import io.github.vaspyyy.guildmark.network.ReleaseChargePayload
import io.github.vaspyyy.guildmark.network.StaggerPayload
import io.github.vaspyyy.guildmark.network.BossCuePayload
import io.github.vaspyyy.guildmark.network.StartMovePayload
import io.github.vaspyyy.guildmark.network.StrikePayload
import io.github.vaspyyy.guildmark.network.VitalsPayload
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.neoforged.neoforge.event.PlayLevelSoundEvent
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent
import io.github.vaspyyy.guildmark.guild.Errands
import io.github.vaspyyy.guildmark.village.Festivals
import io.github.vaspyyy.guildmark.progression.Titles
import io.github.vaspyyy.guildmark.road.Coaches
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
import io.github.vaspyyy.guildmark.city.CapitalVisits
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.network.GrowPayload
import io.github.vaspyyy.guildmark.network.TreePayload
import io.github.vaspyyy.guildmark.network.TakeNotePayload
import io.github.vaspyyy.guildmark.progression.Skills
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.progression.Work
import io.github.vaspyyy.guildmark.quest.QuestPoolLoader
import io.github.vaspyyy.guildmark.guild.GuildMember
import io.github.vaspyyy.guildmark.guild.Reception
import io.github.vaspyyy.guildmark.guild.Recruiting
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.registry.ModFeatures
import io.github.vaspyyy.guildmark.registry.ModStructures
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.network.AcceptChapterPayload
import io.github.vaspyyy.guildmark.network.EndTalkPayload
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
import io.github.vaspyyy.guildmark.siege.Defenders
import io.github.vaspyyy.guildmark.siege.Sieges
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.SeaWork
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
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent
import io.github.vaspyyy.guildmark.enemy.FallenWarden
import io.github.vaspyyy.guildmark.enemy.DuskRider
import io.github.vaspyyy.guildmark.enemy.HillTroll
import io.github.vaspyyy.guildmark.dragon.Dragons
import io.github.vaspyyy.guildmark.magic.Magic
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.field.NightRiders
import io.github.vaspyyy.guildmark.item.SpectralSteed
import io.github.vaspyyy.guildmark.enemy.CinderHollow
import io.github.vaspyyy.guildmark.enemy.GlimmerHollow
import io.github.vaspyyy.guildmark.enemy.HollowSoldier
import io.github.vaspyyy.guildmark.enemy.MireHollow
import io.github.vaspyyy.guildmark.enemy.RimeHollow
import net.minecraft.world.entity.SpawnPlacementTypes
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent
import net.neoforged.neoforge.event.entity.living.LivingEvent
import net.neoforged.neoforge.event.entity.player.AnvilCraftEvent
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent
import net.neoforged.neoforge.event.AnvilUpdateEvent
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.PistonEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
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
        container.registerConfig(ModConfig.Type.SYNCED, GuildmarkConfig.SPEC)
        ModBlocks.BLOCKS.register(modBus)
        ModItems.ITEMS.register(modBus)
        ModBlockEntities.BLOCK_ENTITIES.register(modBus)
        ModDataComponents.COMPONENTS.register(modBus)
        ModCreativeTabs.TABS.register(modBus)
        // The new weapons sit with vanilla's too
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == CreativeModeTabs.COMBAT) ModItems.WEAPONS.forEach { event.accept(it.get()) }
        }
        ModVillagers.POI_TYPES.register(modBus)
        ModFeatures.FEATURE_TYPES.register(modBus)
        ModStructures.STRUCTURE_TYPES.register(modBus)
        ModStructures.PIECE_TYPES.register(modBus)
        ModStructures.PLACEMENTS.register(modBus)
        ModVillagers.PROFESSIONS.register(modBus)
        ModAttachments.ATTACHMENTS.register(modBus)
        ModEntities.ENTITIES.register(modBus)
        ModSounds.SOUNDS.register(modBus)
        ModParticles.PARTICLES.register(modBus)
        Townsfolk.init(modBus)
        // Named regions: who's where, their titles, music and air, and their borders on the map (region/)
        Regions.register(modBus)
        // Dragons on the high peaks: the boss, its fire, its roosts and their hoards (dragon/)
        Dragons.register(modBus)
        Magic.register(modBus)
        // The Starwatch, the Hearthsworn and the Quiet Hand: joining, ranks, questlines and their trials (faction/)
        io.github.vaspyyy.guildmark.faction.Factions.register(modBus)
        // Bram, Ysolde, Kestrel and Lucan: who travels with the player, loyalty, chapters and Sworn perks (companion/)
        io.github.vaspyyy.guildmark.companion.Companions.register(modBus)
        // The cog: sailing by the wind, her hold, and where each player left theirs (ship/)
        io.github.vaspyyy.guildmark.ship.Ships.register(modBus)
        // Gnashfins and the Maelgrasp: what hunts the open sea, and when it comes (sea/)
        io.github.vaspyyy.guildmark.sea.Seas.register(modBus)
        // Islands out at sea: wild isles, sea stacks, coves, atolls and reefs, grown with new ocean (island/)
        io.github.vaspyyy.guildmark.island.Islands.register(modBus)
        // Outlaw bands camped in the wild: their camps, their day, their crimes and the price on their captains (outlaw/)
        io.github.vaspyyy.guildmark.outlaw.Outlaws.register()
        // Players' homesteads: the charter, the plot, the builders and the tiers going up (homestead/)
        io.github.vaspyyy.guildmark.homestead.Homesteads.register(modBus)
        // Which part of a capital each player is in, for the city sounds (city/CapitalVisits)
        CapitalVisits.register()
        // Capitals' sites worked out ahead of the players (city/Capitals)
        Capitals.register()
        // Each capital's market: goods grown, made, carted, eaten and traded an hour at a time (economy/)
        io.github.vaspyyy.guildmark.economy.Markets.register()
        io.github.vaspyyy.guildmark.economy.MarketCommand.init()
        // The market boards the criers chalk the prices on, and boards for the capitals built without one
        io.github.vaspyyy.guildmark.economy.MarketBoards.register()
        // Trading face to face with the shopkeepers: the market screen
        io.github.vaspyyy.guildmark.economy.Stalls.register(modBus)
        // The carters' handcarts, pulled behind them with their loads heaped on (cart/)
        io.github.vaspyyy.guildmark.cart.Handcarts.register(modBus)
        // The market's goods on show: its displays, kept as full as its stock, and set out in capitals built before them
        io.github.vaspyyy.guildmark.economy.WaresRegistry.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) {
            it.put(ModEntities.GUILD_MEMBER.get(), GuildMember.createAttributes().build())
            it.put(ModEntities.STORY_NPC.get(), StoryNpc.createAttributes().build())
            it.put(ModEntities.HOLLOW_KING.get(), HollowKing.createAttributes().build())
            it.put(ModEntities.RIVAL.get(), RivalAdventurer.createAttributes().build())
            it.put(ModEntities.HOLLOW_SOLDIER.get(), HollowSoldier.createAttributes().build())
            it.put(ModEntities.MIRE_HOLLOW.get(), MireHollow.createAttributes().build())
            it.put(ModEntities.CINDER_HOLLOW.get(), CinderHollow.createAttributes().build())
            it.put(ModEntities.RIME_HOLLOW.get(), RimeHollow.createAttributes().build())
            it.put(ModEntities.GLIMMER_HOLLOW.get(), GlimmerHollow.createAttributes().build())
            it.put(ModEntities.FALLEN_WARDEN.get(), FallenWarden.createAttributes().build())
            it.put(ModEntities.HILL_TROLL.get(), HillTroll.createAttributes().build())
            it.put(ModEntities.DUSK_RIDER.get(), DuskRider.createAttributes().build())
            it.put(ModEntities.SPECTRAL_STEED.get(), SpectralSteed.createAttributes().build())
        }
        // Hollow Soldiers spawn like other monsters, on the ground in the dark (biome_modifier/hollow_soldier.json);
        // in the wild lands their land's own Hollows take their place (region/RegionFoes)
        modBus.addListener(RegisterSpawnPlacementsEvent::class.java) { event ->
            fun <T : Monster> onGround(type: EntityType<T>) = event.register(
                type, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Monster::checkMonsterSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE,
            )
            onGround(ModEntities.HOLLOW_SOLDIER.get())
            onGround(ModEntities.MIRE_HOLLOW.get())
            onGround(ModEntities.CINDER_HOLLOW.get())
            onGround(ModEntities.RIME_HOLLOW.get())
            onGround(ModEntities.GLIMMER_HOLLOW.get())
        }

        modBus.addListener(FMLCommonSetupEvent::class.java, ::onCommonSetup)
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, ::onRegisterPayloads)
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java, ::onLivingDeath)
        // Last, so a fall another mod undoes doesn't cost the player their Marks
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LivingDeathEvent::class.java, Purses::onDeath)
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java, ::onPlayerTick)
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { Traffic.onJoin(it.entity) }
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock::class.java, HollowThrone::onUseBlock)
        // Sea work (quest/SeaWork): the chest a salvage contract was for, opened or broken into
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.RightClickBlock::class.java, SeaWork::onUseBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BreakBlockEvent::class.java, SeaWork::onBreak)
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> SeaWork.forget() }
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java, Relics::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java, Parties::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java, Relics::onDamaged)
        // Who fought off a siege or a bandit ambush, so its pay goes to them (siege/Defenders)
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java, Defenders::onDamaged)
        // The action combat (combat/): damage, guard, poise, knockback and sound hooks
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java, Combat::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(LivingShieldBlockEvent::class.java, Combat::onShieldBlock)
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java, Combat::onDamaged)
        NeoForge.EVENT_BUS.addListener(LivingKnockBackEvent::class.java, Combat::onKnockBack)
        NeoForge.EVENT_BUS.addListener(CriticalHitEvent::class.java, Combat::onCriticalHit)
        NeoForge.EVENT_BUS.addListener(SweepAttackEvent::class.java, Combat::onSweep)
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition::class.java, Combat::onSound)
        // Stats, skills and the skill tree (progression/): what the build does in a fight...
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent::class.java, Talents::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post::class.java, Talents::onDamaged)
        // First, so a fall Undying turns aside isn't counted as a death by anything else
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, LivingDeathEvent::class.java, Talents::onDeath)
        NeoForge.EVENT_BUS.addListener(LivingEvent.LivingVisibilityEvent::class.java, Talents::onVisibility)
        NeoForge.EVENT_BUS.addListener(PlayerXpEvent.PickupXp::class.java, Talents::onPickupXp)
        NeoForge.EVENT_BUS.addListener(LivingEntityUseItemEvent.Finish::class.java, Talents::onFinishUsing)
        NeoForge.EVENT_BUS.addListener(LivingEntityUseItemEvent.Tick::class.java, Talents::onUsingTick)
        NeoForge.EVENT_BUS.addListener(ArrowLooseEvent::class.java, Talents::onArrowLoose)
        // ...and the skills that grow by working (after other mods have had their say on a broken block)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, BreakBlockEvent::class.java, Work::onBreak)
        NeoForge.EVENT_BUS.addListener(PlayerEvent.BreakSpeed::class.java, Work::onBreakSpeed)
        NeoForge.EVENT_BUS.addListener(BlockDropsEvent::class.java, Work::onDrops)
        // Last, and only if nothing stopped them: a block that wasn't put down mustn't be remembered as placed
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.EntityPlaceEvent::class.java, Work::onPlaced)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PistonEvent.Pre::class.java, Work::onPiston)
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemCraftedEvent::class.java, Work::onCrafted)
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemSmeltedEvent::class.java, Work::onSmelted)
        NeoForge.EVENT_BUS.addListener(PlayerBrewedPotionEvent::class.java, Work::onBrewed)
        NeoForge.EVENT_BUS.addListener(PlayerEnchantItemEvent::class.java, Work::onEnchanted)
        NeoForge.EVENT_BUS.addListener(AnvilUpdateEvent::class.java, Work::onAnvilUpdate)
        NeoForge.EVENT_BUS.addListener(AnvilCraftEvent.Post::class.java, Work::onAnvilCrafted)
        NeoForge.EVENT_BUS.addListener(ItemFishedEvent::class.java, Work::onFished)
        NeoForge.EVENT_BUS.addListener(TradeWithVillagerEvent::class.java, Work::onTrade)
        NeoForge.EVENT_BUS.addListener(BabyEntitySpawnEvent::class.java, Work::onBreed)
        NeoForge.EVENT_BUS.addListener(AnimalTameEvent::class.java, Work::onTame)
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteract::class.java, Reception::onInteract)
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post::class.java) { event ->
            val level = event.level
            if (level is ServerLevel) {
                RoadNetwork.get(level).tick(level)
                Stance.tick(level)
                if (level.gameTime % 10L == 0L) Traffic.tick(level)
                if (level.gameTime % 20L == 0L) {
                    Lairs.get(level).tick(level)
                    Sieges.tick(level)
                    if (level.dimension() == Level.OVERWORLD) HollowThrone.get(level).tick(level)
                    if (level.dimension() == Level.OVERWORLD) FieldBosses.get(level).tick(level)
                    if (level.dimension() == Level.OVERWORLD) NightRiders.tick(level)
                }
                if (level.dimension() == Level.OVERWORLD) TownControl.tick(level)
            }
        }
        // The build's attribute modifiers aren't copied to a respawned player, and the build may have changed since the last visit
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) {
            Talents.apply(it.entity)
            Milestones.onLogin(it.entity)
            (it.entity as? ServerPlayer)?.let { player ->
                Parties.onLogin(player)
                player.refreshDisplayName()
                player.refreshTabListName()
                Atlas.resend(player)
                PacketDistributor.sendToPlayer(player, WorldIdPayload(WorldId.of(player.level().server)))
            }
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) {
            (it.entity as? ServerPlayer)?.let { player -> Atlas.resend(player) }
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.NameFormat::class.java) { Titles.onNameFormat(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.TabListNameFormat::class.java) { Titles.onTabListName(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) {
            Talents.apply(it.entity)
            Combat.reset(it.entity)
            // Waking after a fall fills the flask and readies Undying, as a rest would
            if (!it.isEndConquered) {
                WardensFlask.refill(it.entity)
                (it.entity as? ServerPlayer)?.let { player -> Talents.onRest(player) }
            }
            // The party list isn't copied to the new player entity
            (it.entity as? ServerPlayer)?.let { player ->
                Parties.onLogin(player)
                Atlas.resend(player)
            }
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) {
            AdvanceCommand.register(it.dispatcher)
            PartyCommand.register(it.dispatcher)
            Coaches.register(it.dispatcher)
            Titles.register(it.dispatcher)
            Errands.register(it.dispatcher)
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
        // Kills by fire, lightning and the like still count for whoever last hit the victim
        val killer = event.source.entity as? Player ?: victim.killCredit as? Player
        if (killer == null) {
            // A companion's kill counts towards their player's contracts and party, though it teaches the player nothing
            val owner = (event.source.entity as? CompanionEntity)?.player ?: return
            if (!owner.level().isClientSide()) {
                Contracts.onKill(owner, event.entity)
                Parties.onKill(owner, event.entity)
            }
            return
        }
        if (!killer.level().isClientSide()) {
            Contracts.onKill(killer, event.entity)
            Parties.onKill(killer, event.entity)
            if (killer is ServerPlayer) Work.onKill(killer, victim, event.source)
        }
    }

    private fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity
        val level = player.level()
        if (level !is ServerLevel) return
        if (player is ServerPlayer) Combat.tick(player)
        if (player is ServerPlayer) Talents.tick(player)
        if (player is ServerPlayer) Skills.tick(player)
        if (player is ServerPlayer) Purses.tick(player)
        if (player.tickCount % 200 == 0) VillageBoards.checkNearbyVillages(level, player.blockPosition())
        if (player.tickCount % 200 == 50) TownControl.visit(level, player)
        if (player.tickCount % 200 == 150) Rivals.tickPlayer(level, player)
        if (player.tickCount % 40 == 20) Rivals.syncChallenges(level, player)
        if (player.tickCount % 100 == 75 && player is ServerPlayer) Festivals.tick(level, player)
        if (player.tickCount % 200 == 25 && player is ServerPlayer) Errands.tick(level, player)
        if (player.tickCount % 200 == 100) Characters.tick(level, player)
        if (player.tickCount % 10 == 0) Contracts.tickEscorts(level, player)
        if (player.tickCount % 40 == 30 && player is ServerPlayer) Atlas.tick(level, player)
        if (player.tickCount % 20 == 5 && player is ServerPlayer) Atlas.sendParty(level, player)
        Milestones.tick(player)
        Traffic.trySpawn(level, player)
        Sieges.maybeStart(level, player)
    }

    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToServer(TakeNotePayload.TYPE, TakeNotePayload.STREAM_CODEC, TakeNotePayload::handle)
            .playToServer(GrowPayload.TYPE, GrowPayload.STREAM_CODEC, GrowPayload::handle)
            .playToServer(TreePayload.TYPE, TreePayload.STREAM_CODEC, TreePayload::handle)
            .playToServer(ReceptionActionPayload.TYPE, ReceptionActionPayload.STREAM_CODEC, ReceptionActionPayload::handle)
            .playToServer(FoundGuildPayload.TYPE, FoundGuildPayload.STREAM_CODEC, FoundGuildPayload::handle)
            .playToServer(AcceptChapterPayload.TYPE, AcceptChapterPayload.STREAM_CODEC, AcceptChapterPayload::handle)
            .playToServer(EndTalkPayload.TYPE, EndTalkPayload.STREAM_CODEC, EndTalkPayload::handle)
            .playToClient(OpenCharacterPayload.TYPE, OpenCharacterPayload.STREAM_CODEC, OpenCharacterPayload::handle)
            .playToClient(OpenReceptionPayload.TYPE, OpenReceptionPayload.STREAM_CODEC, OpenReceptionPayload::handle)
            .playToClient(OpenAdvancesPayload.TYPE, OpenAdvancesPayload.STREAM_CODEC, OpenAdvancesPayload::handle)
            .playToServer(ClaimVillagePayload.TYPE, ClaimVillagePayload.STREAM_CODEC, ClaimVillagePayload::handle)
            .playToServer(StartMovePayload.TYPE, StartMovePayload.STREAM_CODEC, StartMovePayload::handle)
            .playToServer(ReleaseChargePayload.TYPE, ReleaseChargePayload.STREAM_CODEC, ReleaseChargePayload::handle)
            .playToServer(StrikePayload.TYPE, StrikePayload.STREAM_CODEC, StrikePayload::handle)
            .playToClient(VitalsPayload.TYPE, VitalsPayload.STREAM_CODEC, VitalsPayload::handle)
            .playToClient(MovePayload.TYPE, MovePayload.STREAM_CODEC, MovePayload::handle)
            .playToClient(StaggerPayload.TYPE, StaggerPayload.STREAM_CODEC, StaggerPayload::handle)
            .playToClient(BossCuePayload.TYPE, BossCuePayload.STREAM_CODEC, BossCuePayload::handle)
            .playToClient(AtlasPayload.TYPE, AtlasPayload.STREAM_CODEC, AtlasPayload::handle)
            .playToClient(PartyPositionsPayload.TYPE, PartyPositionsPayload.STREAM_CODEC, PartyPositionsPayload::handle)
            .playToServer(CoachStopsRequestPayload.TYPE, CoachStopsRequestPayload.STREAM_CODEC, CoachStopsRequestPayload::handle)
            .playToClient(CoachStopsPayload.TYPE, CoachStopsPayload.STREAM_CODEC, CoachStopsPayload::handle)
            .playToClient(WorldIdPayload.TYPE, WorldIdPayload.STREAM_CODEC, WorldIdPayload::handle)
            .playToServer(RideCoachPayload.TYPE, RideCoachPayload.STREAM_CODEC, RideCoachPayload::handle)
            .playToClient(OpenWardfirePayload.TYPE, OpenWardfirePayload.STREAM_CODEC, OpenWardfirePayload::handle)
            .playToServer(WardfireActionPayload.TYPE, WardfireActionPayload.STREAM_CODEC, WardfireActionPayload::handle)
            .playToServer(WardfireTravelPayload.TYPE, WardfireTravelPayload.STREAM_CODEC, WardfireTravelPayload::handle)
            .playToClient(WardfireTravelledPayload.TYPE, WardfireTravelledPayload.STREAM_CODEC, WardfireTravelledPayload::handle)
            .playToServer(DrinkFlaskPayload.TYPE, DrinkFlaskPayload.STREAM_CODEC, DrinkFlaskPayload::handle)
            .playToClient(CityDistrictPayload.TYPE, CityDistrictPayload.STREAM_CODEC, CityDistrictPayload::handle)
    }
}
