package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.Level

/** Server-side contract rules: creating, progressing and turning in. */
object Contracts {
    fun create(note: QuestNote, level: Level, boardPos: BlockPos): ItemStack {
        val stack = ItemStack(ModItems.CONTRACT.get())
        val state = ContractState(0, level.gameTime + note.deadlineDays * ContractState.TICKS_PER_DAY, boardPos, level.dimension())
        stack.set(ModDataComponents.QUEST_NOTE.get(), note)
        stack.set(ModDataComponents.CONTRACT_STATE.get(), state)
        refreshLore(stack, note, state)
        return stack
    }

    /** A player killed [victim]: advance the first contract it counts for. */
    fun onKill(player: Player, victim: LivingEntity) {
        val gameTime = player.level().gameTime
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.isExpired(gameTime) || state.progress >= note.count || !counts(note, state, victim)) continue

            val updated = state.copy(progress = state.progress + 1)
            stack.set(ModDataComponents.CONTRACT_STATE.get(), updated)
            refreshLore(stack, note, updated)
            player.sendOverlayMessage(Component.translatable("message.guildmark.progress", note.title(), updated.progress, note.count))
            return
        }
    }

    /** Hand in [stack] at a board. Pays out when done, discards it when expired. */
    fun turnIn(stack: ItemStack, player: Player, level: Level, pos: BlockPos) {
        val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return
        val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: return

        if (state.isExpired(level.gameTime)) {
            stack.shrink(1)
            player.sendOverlayMessage(Component.translatable("message.guildmark.expired", note.title()))
            level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0f, 1.0f)
            return
        }

        val done = when (note.type) {
            QuestType.FETCH -> takeFetchItems(player, note)
            QuestType.HUNT, QuestType.CLEAR -> state.progress >= note.count
        }
        if (!done) {
            val have = if (note.type == QuestType.FETCH) countFetchItems(player, note) else state.progress
            player.sendOverlayMessage(Component.translatable("message.guildmark.not_done", note.title(), have, note.count))
            level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0f, 1.0f)
            return
        }

        stack.shrink(1)
        player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), note.reward), Prediction.SERVER_ONLY)
        player.sendOverlayMessage(Component.translatable("message.guildmark.complete", note.reward))
        level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f)
    }

    private fun counts(note: QuestNote, state: ContractState, victim: LivingEntity): Boolean = when (note.type) {
        QuestType.HUNT -> EntityType.getKey(victim.type) == note.target
        QuestType.CLEAR -> victim.type.category == MobCategory.MONSTER &&
            victim.level().dimension() == state.dimension &&
            victim.blockPosition().closerThan(state.boardPos, QuestNote.CLEAR_RADIUS.toDouble())
        QuestType.FETCH -> false
    }

    private fun isFetchItem(note: QuestNote, stack: ItemStack): Boolean =
        stack.`is`(BuiltInRegistries.ITEM.getValue(note.target))

    private fun countFetchItems(player: Player, note: QuestNote): Int =
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { isFetchItem(note, it) }, Int.MAX_VALUE, true)

    private fun takeFetchItems(player: Player, note: QuestNote): Boolean {
        if (countFetchItems(player, note) < note.count) return false
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { isFetchItem(note, it) }, note.count, false)
        return true
    }

    private fun refreshLore(stack: ItemStack, note: QuestNote, state: ContractState) {
        val lines = mutableListOf(note.title())
        if (note.type != QuestType.FETCH) {
            lines.add(Component.translatable("quest.guildmark.progress", state.progress, note.count))
        }
        lines.add(note.rewardLine())
        stack.set(DataComponents.LORE, ItemLore(lines))
    }
}
