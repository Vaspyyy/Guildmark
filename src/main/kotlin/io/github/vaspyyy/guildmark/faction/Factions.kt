package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.progression.Talents
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.Enchantments
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * The factions' rules: joining, ranks, the rivalry, the leaders' talk and the work they give. Each faction's
 * questline is its leader's chapters ([StoryCharacter.steps]), handed out and turned in like any named
 * character's, with their own new kinds of job ([Trials]); where the player stands is kept in [FactionState].
 */
object Factions {
    /** Ticks between looks at the faction seats round each player. */
    private const val SEAT_CHECK = 40
    private const val TALK_RANGE = 8.0
    /** Days to do repeatable work in. */
    private const val WORK_DAYS = 3

    fun register(modBus: IEventBus) {
        FactionRegistry.ITEMS.register(modBus)
        FactionRegistry.ENTITIES.register(modBus)
        FactionRegistry.SOUNDS.register(modBus)
        FactionRegistry.COMPONENTS.register(modBus)
        FactionRegistry.ATTACHMENTS.register(modBus)
        modBus.addListener(EntityAttributeCreationEvent::class.java) {
            it.put(FactionRegistry.WARD_LIGHT.get(), WardLight.createAttributes().build())
            it.put(FactionRegistry.SWORN_KNIGHT.get(), SwornKnight.createAttributes().build())
            it.put(FactionRegistry.RENEGADE.get(), Renegade.createAttributes().build())
        }
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            event.registrar("1")
                .playToServer(FactionWorkPayload.TYPE, FactionWorkPayload.STREAM_CODEC, FactionWorkPayload::handle)
                .playToClient(SuspicionPayload.TYPE, SuspicionPayload.STREAM_CODEC, SuspicionPayload::handle)
        }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey != ModCreativeTabs.MAIN.key) return@addListener
            FactionRegistry.TAB.forEach { event.accept(it.get()) }
            for (heist in Heist.entries) event.accept(HeistPrizeItem.of(heist.prize))
        }
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            val level = player.level()
            Trials.tick(level, player)
            if (player.tickCount % SEAT_CHECK == 11) FactionSeats.tick(level, player)
        }
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent::class.java) { event -> Trials.onDeath(event.entity, event.source) }
        // After the fighting's own say (a guarded blow is less), so a spar sees what would really land
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, LivingIncomingDamageEvent::class.java) { Trials.onIncomingDamage(it) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { Trials.forget(it.entity) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            Trials.clear()
            FactionSeats.forget()
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { FactionCommand.register(it.dispatcher) }
    }

    fun state(player: Player): FactionState = player.getData(FactionRegistry.FACTIONS)

    /** Replaces where [player] stands with the factions, and puts the perks of their ranks in place. */
    fun set(player: Player, state: FactionState) {
        player.setData(FactionRegistry.FACTIONS, state)
        Talents.apply(player)
    }

    /** The faction [p] leads, if they're one of the leaders. */
    fun ledBy(p: Townsperson): Faction? = if (p.isLeader) Faction.of(p.role) else null

    // ---- Talking to a leader ----

    /** Flags for the conversation (OpenCharacterPayload.flags). */
    const val GATED = 1
    const val RIVAL = 2
    const val SHUT = 4
    const val WOKEN = 8
    const val WORK = 16

    /** Someone used [leader]: hand in what's theirs, or start talking. */
    fun talk(level: ServerLevel, player: ServerPlayer, leader: Townsperson) {
        val faction = ledBy(leader) ?: return
        val character = faction.leader
        val held = player.mainHandItem
        val heldNote = held.get(ModDataComponents.QUEST_NOTE.get())
        if (!state(player).isShut(faction) && heldNote != null && held.has(ModDataComponents.CONTRACT_STATE.get()) &&
            StoryCharacter.ownerOf(heldNote.chain) == character) {
            val before = state(player).chapters(faction)
            Contracts.turnIn(held, player, level, leader.blockPosition(), character)
            val after = state(player).chapters(faction)
            // A chapter done: they have the next to tell, or their goodbye
            if (after > before) open(player, leader, faction, finale = after >= character.steps.size)
            return
        }
        open(player, leader, faction)
    }

    private fun open(player: ServerPlayer, leader: Townsperson, faction: Faction, finale: Boolean = false) {
        val character = faction.leader
        val state = state(player)
        val chapters = state.chapters(faction)
        var flags = 0
        if (state.isShut(faction)) flags = flags or SHUT
        if (leader.dozing) flags = flags or WOKEN
        val carrying = carries(player, character, work = false)
        if (chapters < character.steps.size && !carrying && gated(player, character, chapters)) flags = flags or GATED
        val rival = faction.rival
        if (rival != null && chapters == Faction.RIVAL_CHAPTER && state.member(rival)) flags = flags or RIVAL
        if (state.member(faction) && !carries(player, character, work = true)) flags = flags or WORK
        leader.listen(player)
        leader.playSound(SoundEvents.VILLAGER_AMBIENT, 0.6f, 0.9f)
        PacketDistributor.sendToPlayer(player, OpenCharacterPayload(leader.id, character.id, chapters, carrying, finale, flags))
    }

    /** Whether [character]'s chapter [step] wants an adventurer rated higher than [player]. */
    private fun gated(player: Player, character: StoryCharacter, step: Int): Boolean =
        character.steps[step].rank > Progression.get(player).adventurerRank + 1

    /** Whether [player] carries one of [character]'s chapters (or, with [work], other work they gave). */
    fun carries(player: Player, character: StoryCharacter, work: Boolean): Boolean = (0 until player.inventory.containerSize).any { slot ->
        val chain = player.inventory.getItem(slot).get(ModDataComponents.QUEST_NOTE.get())?.chain ?: return@any false
        if (StoryCharacter.ownerOf(chain) != character) return@any false
        (StoryCharacter.parseChain(chain) == null) == work
    }

    /** The leader at [entityId] whom [player] is near enough to deal with, if any. */
    private fun leaderNear(player: Player, entityId: Int): Pair<Townsperson, Faction>? {
        val leader = player.level().getEntity(entityId) as? Townsperson ?: return null
        val faction = ledBy(leader) ?: return null
        if (player.distanceTo(leader) > TALK_RANGE) return null
        return leader to faction
    }

    /** The player said yes to [leader]'s next chapter. */
    fun accept(player: Player, leader: Townsperson) {
        val level = player.level() as? ServerLevel ?: return
        val (_, faction) = leaderNear(player, leader.id) ?: return
        leader.stopListening(player)
        val character = faction.leader
        val state = state(player)
        val step = state.chapters(faction)
        if (state.isShut(faction) || step >= character.steps.size || carries(player, character, work = false) || gated(player, character, step)) return
        val stack = Contracts.take(character.note(step), level, leader.blockPosition(), Direction.NORTH, player, fromHall = true) ?: return
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.story_accepted", character.title, step + 1, character.steps.size))
        level.playSound(null, leader.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0f, 1.0f)
    }

    /** The player asked [entityId] for work between chapters: a job in the faction's own line of work. */
    fun giveWork(player: Player, entityId: Int) {
        val level = player.level() as? ServerLevel ?: return
        val (leader, faction) = leaderNear(player, entityId) ?: return
        val character = faction.leader
        if (!state(player).member(faction) || carries(player, character, work = true)) return
        val rank = Progression.get(player).adventurerRank
        val stack = Contracts.take(work(faction, rank, level.random.nextInt(4)), level, leader.blockPosition(), Direction.NORTH, player, fromHall = true) ?: return
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
        player.sendSystemMessage(Component.translatable("message.guildmark.faction_work", character.title))
        level.playSound(null, leader.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0f, 1.1f)
    }

    /** One of four jobs [faction] gives between chapters ([pick]), rated at the player's own [rank]. */
    private fun work(faction: Faction, rank: Int, pick: Int): QuestNote {
        val pay = 8 + 3 * rank
        val (type, target, count) = when (faction) {
            Faction.STARWATCH -> listOf(
                Triple(QuestType.FETCH, "lapis_lazuli", 12), Triple(QuestType.FETCH, "amethyst_shard", 8),
                Triple(QuestType.HUNT, "phantom", 4), Triple(QuestType.TRIAL, "guildmark:ward_lights", 5),
            )
            Faction.HEARTHSWORN -> listOf(
                Triple(QuestType.HUNT, "guildmark:hollow_soldier", 6), Triple(QuestType.HUNT, "zombie", 12),
                Triple(QuestType.HUNT, "skeleton", 10), Triple(QuestType.FETCH, "iron_ingot", 12),
            )
            Faction.QUIET_HAND -> listOf(
                Triple(QuestType.TRIAL, "guildmark:purses", 3), Triple(QuestType.FETCH, "gold_ingot", 8),
                Triple(QuestType.HUNT, "pillager", 5), Triple(QuestType.FETCH, "emerald", 6),
            )
        }[pick]
        return QuestNote(
            type, faction.leader.displayName, Identifier.parse(target), count, pay, WORK_DAYS,
            "character.guildmark.${faction.leader.id}.work", rank, chain = "${faction.leader.id}:work",
        )
    }

    /** The player closed the conversation with [entityId]: the leader goes back to what they were at. */
    fun endTalk(player: Player, entityId: Int) {
        (player.level().getEntity(entityId) as? Townsperson)?.stopListening(player)
    }

    // ---- Rising, and the rivalry ----

    /**
     * Chapter [step] of [faction]'s questline was handed in: a rank (the first joins), the perks that come with
     * it, and at the end the leader's gift. Robbing the rival ([Faction.RIVAL_CHAPTER]) shuts the player out
     * of it for good, expelled if they were in it.
     */
    fun onChapterDone(level: ServerLevel, player: Player, faction: Faction, step: Int) {
        val character = faction.leader
        val before = state(player)
        val chapters = maxOf(before.chapters(faction), step + 1).coerceAtMost(character.steps.size)
        var next = before.withChapters(faction, chapters)
        val rival = faction.rival
        if (step == Faction.RIVAL_CHAPTER && rival != null && !next.isShut(rival)) {
            val wasIn = next.chapters(rival) > 0
            next = next.shutOut(rival)
            player.sendSystemMessage(Component.translatable(if (wasIn) "message.guildmark.faction_expelled" else "message.guildmark.faction_barred", rival.title))
            level.playSound(null, player.blockPosition(), FactionRegistry.EXPELLED.get(), SoundSource.PLAYERS, 0.9f, 1f)
            if (wasIn) GuildNews.add(level, "news.guildmark.faction_expelled", player.name.string, rival.title.string)
        }
        set(player, next)
        if (before.chapters(faction) == 0) {
            player.sendSystemMessage(Component.translatable("message.guildmark.faction_joined", faction.title, faction.rankTitle(chapters)))
            GuildNews.add(level, "news.guildmark.faction_joined", player.name.string, faction.title.string)
        } else {
            player.sendSystemMessage(Component.translatable("message.guildmark.faction_rank", character.title, faction.rankTitle(chapters)))
        }
        level.playSound(null, player.blockPosition(), FactionRegistry.rankSound(faction), SoundSource.PLAYERS, 1f, 1f)
        if (chapters < character.steps.size) return
        Milestones.award(player, Milestones.CHARACTER)
        player.inventory.placeItemBackInInventory(gift(level, faction), Prediction.SERVER_ONLY)
        level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f)
        GuildNews.add(level, "news.guildmark.faction_done", player.name.string, faction.title.string)
    }

    /** Repeatable work for [owner] was handed in. */
    fun onWorkDone(level: ServerLevel, player: Player, owner: StoryCharacter) {
        val faction = owner.faction ?: return
        level.playSound(null, player.blockPosition(), FactionRegistry.rankSound(faction), SoundSource.PLAYERS, 0.35f, 1.2f)
    }

    /** What a leader gives when their questline ends. */
    private fun gift(level: ServerLevel, faction: Faction): ItemStack {
        val enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
        fun ItemStack.with(vararg list: Pair<ResourceKey<Enchantment>, Int>): ItemStack = also { stack ->
            for ((key, at) in list) stack.enchant(enchantments.getOrThrow(key), at)
        }
        val stack = when (faction) {
            Faction.STARWATCH -> ItemStack(FactionRegistry.STARWATCH_STAFF.get())
            Faction.HEARTHSWORN -> ItemStack(FactionRegistry.HEARTHBRAND.get()).with(
                Enchantments.FIRE_ASPECT to 2, Enchantments.SHARPNESS to 4, Enchantments.UNBREAKING to 3,
            )
            Faction.QUIET_HAND -> ItemStack(FactionRegistry.WIDOWS_KISS.get()).with(
                Enchantments.LOOTING to 3, Enchantments.SHARPNESS to 3, Enchantments.UNBREAKING to 3,
            )
        }
        val id = stack.typeHolder().unwrapKey().map { it.identifier().path }.orElse("")
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("item.guildmark.$id.lore"))))
        return stack
    }
}
