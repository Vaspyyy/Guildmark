package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.progression.Milestones
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.lair.LoreBooks
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModEntities
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max

/** Where a named character lives: the board of their village, and their villager. */
data class Placement(val id: String, val board: BlockPos, val entity: UUID) {
    companion object {
        val CODEC: Codec<Placement> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("id").forGetter(Placement::id),
                BlockPos.CODEC.fieldOf("board").forGetter(Placement::board),
                UUIDUtil.CODEC.fieldOf("entity").forGetter(Placement::entity),
            ).apply(i, ::Placement)
        }
    }
}

/**
 * The named characters in the world. Each lives in one village: they turn up in the first village that
 * knows an adventurer of high enough rank, wander it like any villager, and come back if they ever go
 * missing. Using one opens their story; handing them a finished chapter moves it on.
 */
class Characters(val placements: MutableList<Placement> = mutableListOf()) : SavedData() {
    fun of(character: StoryCharacter): Placement? = placements.firstOrNull { it.id == character.id }
    private fun at(board: BlockPos): Placement? = placements.firstOrNull { it.board == board }

    private fun place(placement: Placement) {
        placements.removeIf { it.id == placement.id }
        placements.add(placement)
        setDirty()
    }

    companion object {
        private const val INTERACT_RANGE = 8.0

        private val CODEC: Codec<Characters> = RecordCodecBuilder.create { i ->
            i.group(Placement.CODEC.listOf().fieldOf("placements").forGetter { it.placements })
                .apply(i) { placements -> Characters(placements.toMutableList()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "characters"), Supplier { Characters() }, CODEC)

        fun get(level: ServerLevel): Characters = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        fun chapters(player: Player, character: StoryCharacter): Int = player.getData(ModAttachments.STORY)[character.id] ?: 0

        /** Every 200 ticks per player: keep the nearby village's character in place, or bring a new one. */
        fun tick(level: ServerLevel, player: Player) {
            if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || !GuildmarkConfig.CHARACTERS.get()) return
            val board = VillageBoards.villageBoard(level, player.blockPosition()) ?: return
            val characters = get(level)
            val placed = characters.at(board)
            if (placed != null) {
                val character = StoryCharacter.byId(placed.id) ?: return
                val entity = level.getEntity(placed.entity)
                when {
                    // Characters from before they had their own looks were villagers: swap them over
                    entity != null && entity !is StoryNpc -> spawn(level, character, board)
                    entity == null && player.blockPosition().closerThan(board, 40.0) -> spawn(level, character, board)
                    entity != null && !entity.blockPosition().closerThan(board, 64.0) -> standNear(level, entity as StoryNpc, board)
                }
                return
            }
            if (Standing.tier(level, board, player) < StandingTier.KNOWN) return
            val rank = Progression.get(player).adventurerRank
            val next = StoryCharacter.entries.firstOrNull { it.givesJobs && characters.of(it) == null && it.minRank <= rank } ?: return
            if (spawn(level, next, board)) {
                player.sendSystemMessage(Component.translatable("message.guildmark.character_arrived", next.title))
                GuildNews.add(level, "news.guildmark.character_arrived", next.displayName)
            }
        }

        /** Bring [character] to live in the village of [board], wherever they were before. */
        fun spawn(level: ServerLevel, character: StoryCharacter, board: BlockPos): Boolean {
            get(level).of(character)?.let { old -> level.getEntity(old.entity)?.discard() }
            val npc = create(level, character) ?: return false
            standNear(level, npc, board)
            npc.setHomeTo(board, 24)
            level.addFreshEntity(npc)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, npc.x, npc.y + 1.0, npc.z, 12, 0.4, 0.5, 0.4, 0.0)
            get(level).place(Placement(character.id, board, npc.uuid))
            return true
        }

        /** A character steps into the world at [pos] for a scene, leaving after [lifespan] ticks (or never, if -1). */
        fun appear(level: ServerLevel, character: StoryCharacter, pos: BlockPos, lifespan: Int): StoryNpc? {
            val npc = create(level, character) ?: return null
            val ground = footing(level, pos)
            npc.snapTo(ground.x + 0.5, ground.y.toDouble(), ground.z + 0.5, level.random.nextFloat() * 360f, 0f)
            npc.setHomeTo(ground, 6)
            if (lifespan > 0) npc.leaveAt = level.gameTime + lifespan
            level.addFreshEntity(npc)
            level.sendParticles(ParticleTypes.SOUL, npc.x, npc.y + 1.0, npc.z, 30, 0.4, 0.8, 0.4, 0.02)
            level.playSound(null, npc.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.NEUTRAL, 1.5f, 0.8f)
            for (player in level.players()) {
                if (player.blockPosition().closerThan(npc.blockPosition(), 48.0)) {
                    player.sendSystemMessage(Component.translatable("message.guildmark.character_appears", character.title))
                }
            }
            return npc
        }

        /** A visiting character leaves the way they came. */
        fun fade(level: ServerLevel, npc: StoryNpc) {
            level.sendParticles(ParticleTypes.SOUL, npc.x, npc.y + 1.0, npc.z, 30, 0.4, 0.8, 0.4, 0.02)
            level.playSound(null, npc.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.NEUTRAL, 1.5f, 1.2f)
            npc.discard()
        }

        private fun create(level: ServerLevel, character: StoryCharacter): StoryNpc? {
            val npc = ModEntities.STORY_NPC.get().create(level, EntitySpawnReason.EVENT) ?: return null
            npc.character = character.id
            npc.customName = character.title
            npc.isCustomNameVisible = true
            return npc
        }

        /**
         * Where someone can stand at or beside [pos], a step up or down at most, so a scene underground
         * stays underground. Only with nowhere like that close by is it the open ground above.
         */
        private fun footing(level: ServerLevel, pos: BlockPos): BlockPos {
            for (r in 0..3) for (dx in -r..r) for (dz in -r..r) {
                if (max(abs(dx), abs(dz)) != r) continue
                for (dy in intArrayOf(0, -1, 1)) {
                    val at = pos.offset(dx, dy, dz)
                    if (level.getBlockState(at).getCollisionShape(level, at).isEmpty && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty &&
                        level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP) && level.getFluidState(at).isEmpty) return at
                }
            }
            return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos)
        }

        private fun standNear(level: ServerLevel, npc: StoryNpc, board: BlockPos) {
            val offset = BlockPos(level.random.nextInt(7) - 3, 0, 3 + level.random.nextInt(3))
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, board.offset(offset))
            npc.snapTo(ground.x + 0.5, ground.y.toDouble(), ground.z + 0.5, level.random.nextFloat() * 360f, 0f)
        }

        /** Someone used a character: hand in their story contract, or start talking. */
        fun talk(level: ServerLevel, player: ServerPlayer, npc: StoryNpc) {
            val character = StoryCharacter.byId(npc.character) ?: return
            if (character == StoryCharacter.MAREN) {
                val state = MainQuest.get(player)
                val scene = when {
                    state.finished -> 2
                    state.found >= MainQuestState.SHARDS -> 1
                    else -> 0
                }
                npc.listen(player)
                PacketDistributor.sendToPlayer(player, OpenCharacterPayload(npc.id, character.id, scene, false))
                Milestones.award(player, Milestones.MAREN)
                return
            }
            val held = player.mainHandItem
            val heldChain = held.get(ModDataComponents.QUEST_NOTE.get())?.chain
            if (held.has(ModDataComponents.CONTRACT_STATE.get()) && heldChain != null && StoryCharacter.parseChain(heldChain)?.first == character) {
                val before = chapters(player, character)
                Contracts.turnIn(held, player, level, npc.blockPosition(), character)
                val after = chapters(player, character)
                // The story moved on: they have the next chapter to tell, or their goodbye
                if (after > before) {
                    npc.listen(player)
                    val finale = after >= character.steps.size
                    PacketDistributor.sendToPlayer(player, OpenCharacterPayload(npc.id, character.id, after, carries(player, character), finale))
                }
                return
            }
            // Wren keeps the main story going: the first shard if it was missed, and a new compass if one was lost
            if (character == StoryCharacter.WREN && chapters(player, character) >= character.steps.size) {
                val state = MainQuest.get(player)
                if (!state.started) MainQuest.start(level, player)
                else if (state.active && !MainQuest.carriesCompass(player)) {
                    player.inventory.placeItemBackInInventory(MainQuest.compass(), Prediction.SERVER_ONLY)
                    MainQuest.say(player, character, "main.guildmark.new_compass")
                    MainQuest.refreshCompasses(player)
                }
            }
            // Pip, story done, sings of the adventurer's greatest deed so far
            if (character == StoryCharacter.PIP && chapters(player, character) >= character.steps.size) {
                player.sendSystemMessage(Component.translatable("gui.guildmark.character.says", character.title, Ballads.verse(player)))
                level.playSound(null, npc.blockPosition(), SoundEvents.NOTE_BLOCK_GUITAR.value(), SoundSource.NEUTRAL, 1.0f, 1.0f + level.random.nextFloat() * 0.3f)
            }
            npc.playSound(SoundEvents.VILLAGER_AMBIENT, 1.0f, 1.2f)
            npc.listen(player)
            PacketDistributor.sendToPlayer(player, OpenCharacterPayload(npc.id, character.id, chapters(player, character), carries(player, character)))
        }

        /** The player said yes to the next chapter of [villager]'s story. */
        fun accept(player: Player, entityId: Int) {
            val level = player.level() as? ServerLevel ?: return
            val villager = level.getEntity(entityId) as? StoryNpc ?: return
            val character = StoryCharacter.byId(villager.character) ?: return
            if (player.distanceTo(villager) > INTERACT_RANGE) return
            villager.stopListening(player)
            if (character == StoryCharacter.MAREN) {
                HollowThrone.get(level).begin(level, player)
                return
            }
            val step = chapters(player, character)
            if (step >= character.steps.size || carries(player, character)) return
            val board = get(level).of(character)?.board ?: villager.blockPosition()
            val stack = Contracts.take(character.note(step), level, board, Direction.NORTH, player, fromHall = true) ?: return
            player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
            player.sendSystemMessage(Component.translatable("message.guildmark.story_accepted", character.title, step + 1, character.steps.size))
            level.playSound(null, villager.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0f, 1.0f)
        }

        /** The player closed the conversation with [entityId]: they go back to what they were doing. */
        fun endTalk(player: Player, entityId: Int) {
            (player.level().getEntity(entityId) as? StoryNpc)?.stopListening(player)
        }

        /** A story contract was handed in: move the story on, and finish it with a gift. */
        fun onChapterDone(level: ServerLevel, player: Player, character: StoryCharacter, step: Int) {
            val chapters = maxOf(chapters(player, character), step + 1)
            player.setData(ModAttachments.STORY, player.getData(ModAttachments.STORY) + (character.id to chapters))
            if (chapters < character.steps.size) {
                player.sendSystemMessage(Component.translatable("message.guildmark.story_chapter", character.title, chapters, character.steps.size))
                return
            }
            Milestones.award(player, Milestones.CHARACTER)
            for (index in character.lore) player.inventory.placeItemBackInInventory(LoreBooks.fragment(index), Prediction.SERVER_ONLY)
            if (character == StoryCharacter.WREN) MainQuest.start(level, player)
            else player.inventory.placeItemBackInInventory(if (character == StoryCharacter.PIP) Ballads.book(player) else gift(level, character), Prediction.SERVER_ONLY)
            level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f)
            GuildNews.add(level, "news.guildmark.story_done", player.name.string, character.displayName)
        }

        /** The keepsake a character gives when their story ends. */
        private fun gift(level: ServerLevel, character: StoryCharacter): ItemStack {
            val stack = when (character) {
                StoryCharacter.WREN, StoryCharacter.MAREN -> ItemStack(Items.AMETHYST_SHARD)
                StoryCharacter.TOBIN -> ItemStack(Items.IRON_SWORD).also {
                    val enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    it.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3)
                    it.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3)
                }
                StoryCharacter.ILSA -> ItemStack(Items.TOTEM_OF_UNDYING)
                StoryCharacter.PIP -> ItemStack(Items.WRITABLE_BOOK)
            }
            stack.set(DataComponents.CUSTOM_NAME, character.line("gift"))
            stack.set(DataComponents.LORE, ItemLore(listOf(character.line("gift.lore"))))
            return stack
        }

        private fun carries(player: Player, character: StoryCharacter): Boolean = (0 until player.inventory.containerSize).any { slot ->
            val chain = player.inventory.getItem(slot).get(ModDataComponents.QUEST_NOTE.get())?.chain ?: return@any false
            StoryCharacter.parseChain(chain)?.first == character
        }
    }
}
