package io.github.vaspyyy.guildmark.story

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
            if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return
            val board = VillageBoards.villageBoard(level, player.blockPosition()) ?: return
            val characters = get(level)
            val placed = characters.at(board)
            if (placed != null) {
                val character = StoryCharacter.byId(placed.id) ?: return
                val entity = level.getEntity(placed.entity)
                when {
                    entity == null && player.blockPosition().closerThan(board, 40.0) -> spawn(level, character, board)
                    entity != null && !entity.blockPosition().closerThan(board, 64.0) -> standNear(level, entity as Villager, board)
                }
                return
            }
            if (Standing.tier(level, board, player) < StandingTier.KNOWN) return
            val rank = Progression.get(player).adventurerRank
            val next = StoryCharacter.entries.firstOrNull { characters.of(it) == null && it.minRank <= rank } ?: return
            if (spawn(level, next, board)) {
                player.sendSystemMessage(Component.translatable("message.guildmark.character_arrived", next.title))
                GuildNews.add(level, "news.guildmark.character_arrived", next.displayName)
            }
        }

        /** Bring [character] to the village of [board], wherever they were before. */
        fun spawn(level: ServerLevel, character: StoryCharacter, board: BlockPos): Boolean {
            get(level).of(character)?.let { old -> level.getEntity(old.entity)?.discard() }
            val villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT) ?: return false
            standNear(level, villager, board)
            villager.finalizeSpawn(level, level.getCurrentDifficultyAt(board), EntitySpawnReason.EVENT, null)
            val profession = level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).getOrThrow(character.profession)
            villager.villagerData = villager.villagerData.withProfession(profession).withLevel(5)
            villager.customName = character.title
            villager.isCustomNameVisible = true
            villager.isPermanentlyInvulnerable = true
            villager.setPersistenceRequired()
            villager.setData(ModAttachments.CHARACTER, character.id)
            level.addFreshEntity(villager)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.x, villager.y + 1.0, villager.z, 12, 0.4, 0.5, 0.4, 0.0)
            get(level).place(Placement(character.id, board, villager.uuid))
            return true
        }

        private fun standNear(level: ServerLevel, villager: Villager, board: BlockPos) {
            val offset = BlockPos(level.random.nextInt(7) - 3, 0, 3 + level.random.nextInt(3))
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, board.offset(offset))
            villager.snapTo(ground.x + 0.5, ground.y.toDouble(), ground.z + 0.5, level.random.nextFloat() * 360f, 0f)
        }

        fun onInteract(event: PlayerInteractEvent.EntityInteract) {
            val villager = event.target as? Villager ?: return
            if (!villager.hasData(ModAttachments.CHARACTER)) return
            val character = StoryCharacter.byId(villager.getData(ModAttachments.CHARACTER)) ?: return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            val level = event.level as? ServerLevel ?: return
            if (event.hand != InteractionHand.MAIN_HAND) return
            val player = event.entity as? ServerPlayer ?: return

            val held = player.mainHandItem
            val heldChain = held.get(ModDataComponents.QUEST_NOTE.get())?.chain
            if (held.has(ModDataComponents.CONTRACT_STATE.get()) && heldChain != null && StoryCharacter.parseChain(heldChain)?.first == character) {
                Contracts.turnIn(held, player, level, villager.blockPosition(), character)
                return
            }
            villager.playSound(SoundEvents.VILLAGER_AMBIENT, 1.0f, 1.0f)
            PacketDistributor.sendToPlayer(player, OpenCharacterPayload(villager.id, character.id, chapters(player, character), carries(player, character)))
        }

        /** The player said yes to the next chapter of [villager]'s story. */
        fun accept(player: Player, entityId: Int) {
            val level = player.level() as? ServerLevel ?: return
            val villager = level.getEntity(entityId) as? Villager ?: return
            val character = StoryCharacter.byId(villager.getData(ModAttachments.CHARACTER)) ?: return
            if (player.distanceTo(villager) > INTERACT_RANGE) return
            val step = chapters(player, character)
            if (step >= character.steps.size || carries(player, character)) return
            val board = get(level).of(character)?.board ?: villager.blockPosition()
            val stack = Contracts.take(character.note(step), level, board, Direction.NORTH, player, fromHall = true) ?: return
            player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
            player.sendSystemMessage(Component.translatable("message.guildmark.story_accepted", character.title, step + 1, character.steps.size))
            level.playSound(null, villager.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0f, 1.0f)
        }

        /** A story contract was handed in: move the story on, and finish it with a gift. */
        fun onChapterDone(level: ServerLevel, player: Player, character: StoryCharacter, step: Int) {
            val chapters = maxOf(chapters(player, character), step + 1)
            player.setData(ModAttachments.STORY, player.getData(ModAttachments.STORY) + (character.id to chapters))
            if (chapters < character.steps.size) {
                player.sendSystemMessage(Component.translatable("message.guildmark.story_chapter", character.title, chapters, character.steps.size))
                return
            }
            player.sendSystemMessage(Component.translatable("gui.guildmark.character.says", character.title, character.line("finale")))
            for (index in character.lore) player.inventory.placeItemBackInInventory(LoreBooks.fragment(index), Prediction.SERVER_ONLY)
            player.inventory.placeItemBackInInventory(gift(level, character), Prediction.SERVER_ONLY)
            level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f)
            GuildNews.add(level, "news.guildmark.story_done", player.name.string, character.displayName)
        }

        /** The keepsake a character gives when their story ends. */
        private fun gift(level: ServerLevel, character: StoryCharacter): ItemStack {
            val stack = when (character) {
                StoryCharacter.WREN -> ItemStack(Items.AMETHYST_SHARD)
                StoryCharacter.TOBIN -> ItemStack(Items.IRON_SWORD).also {
                    val enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    it.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3)
                    it.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3)
                }
                StoryCharacter.ILSA -> ItemStack(Items.TOTEM_OF_UNDYING)
            }
            stack.set(DataComponents.CUSTOM_NAME, character.line("gift"))
            stack.set(DataComponents.LORE, ItemLore(listOf(character.line("gift.lore"))))
            if (character == StoryCharacter.WREN) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            return stack
        }

        private fun carries(player: Player, character: StoryCharacter): Boolean = (0 until player.inventory.containerSize).any { slot ->
            val chain = player.inventory.getItem(slot).get(ModDataComponents.QUEST_NOTE.get())?.chain ?: return@any false
            StoryCharacter.parseChain(chain)?.first == character
        }
    }
}
