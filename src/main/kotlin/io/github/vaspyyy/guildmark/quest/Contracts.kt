package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.advance.Advances
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.road.Travellers
import io.github.vaspyyy.guildmark.village.Standing
import net.minecraft.world.entity.npc.villager.Villager
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
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
    /**
     * Turn a board note into a contract for [player]. Deliveries and escorts pick a destination village
     * (paying more the further it is), escorts spawn their traveller and champions spawn the champion.
     * Returns null, with a message to the player, if there's nowhere to send them or nowhere to spawn.
     */
    fun take(taken: QuestNote, level: ServerLevel, boardPos: BlockPos, facing: Direction, player: Player): ItemStack? {
        var note = taken
        val needed = note.type.minStanding
        if (Standing.tier(level, boardPos, player) < needed) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.standing_needed", needed.title))
            return null
        }
        val deadline = level.gameTime + (note.deadlineDays + Progression.bonusDays(player)) * ContractState.TICKS_PER_DAY
        var state = ContractState(0, deadline, boardPos, level.dimension())

        when (note.type) {
            QuestType.DELIVER, QuestType.ESCORT -> {
                // Deliveries and escorts travel the village's roads, so they need a Trade Road first
                val origin = Advances.villageCenter(level, boardPos)
                val road = RoadNetwork.get(level).roadsFrom(origin).randomOrNull() ?: run {
                    player.sendOverlayMessage(Component.translatable("message.guildmark.no_road"))
                    return null
                }
                val destination = road.otherEnd(origin)
                val distance = Expeditions.horizontalDistance(boardPos, destination)
                note = note.copy(reward = note.reward + (distance / 100).toInt())
                state = state.copy(destination = destination, road = road.id)
                if (note.type == QuestType.ESCORT) {
                    val name = "Traveller ${QuestGenerator.nameFor(level.random.nextLong())}"
                    val traveller = Expeditions.spawnTraveller(level, boardPos, facing, name) ?: return null
                    state = state.copy(bound = traveller.uuid, label = name)
                }
            }
            QuestType.CHAMPION -> {
                val name = Expeditions.championName(level)
                val tier = QuestGenerator.tierAt(boardPos.x, boardPos.z)
                val champion = Expeditions.spawnChampion(level, boardPos, note.target, name, tier) ?: run {
                    player.sendOverlayMessage(Component.translatable("message.guildmark.no_champion_spot"))
                    return null
                }
                state = state.copy(destination = champion.blockPosition(), bound = champion.uuid, label = name)
            }
            else -> {}
        }

        val stack = ItemStack(ModItems.CONTRACT.get())
        stack.set(ModDataComponents.QUEST_NOTE.get(), note)
        stack.set(ModDataComponents.CONTRACT_STATE.get(), state)
        refreshLore(stack, note, state)
        return stack
    }

    /** Every 10 ticks: escorted travellers keep up with whoever carries their contract. */
    fun tickEscorts(level: ServerLevel, player: Player) {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.ESCORT) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.isExpired(level.gameTime)) continue
            val villager = level.getEntity(state.bound ?: continue) as? Villager ?: continue
            val road = RoadNetwork.get(level).road(state.road)
            // With a road the traveller walks it on their own; without one they tag along behind the player
            if (road != null) Travellers.tick(level, player, villager, road.pointsFrom(state.boardPos)) else Expeditions.followPlayer(level, player, villager)
        }
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
            // The village that posted it remembers
            if (level is ServerLevel && level.dimension() == state.dimension) Standing.add(level, state.boardPos, player, -(note.reward / 2).coerceAtLeast(3))
            player.sendOverlayMessage(Component.translatable("message.guildmark.expired", note.title()))
            level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0f, 1.0f)
            return
        }

        val serverLevel = level as? ServerLevel ?: return
        when (note.type) {
            QuestType.DELIVER, QuestType.ESCORT -> if (!Expeditions.atDestination(state, pos)) {
                val destination = state.destination ?: BlockPos.ZERO
                player.sendOverlayMessage(Component.translatable("message.guildmark.wrong_village", destination.x, destination.z))
                level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0f, 1.0f)
                return
            }
            else -> {}
        }
        if (note.type == QuestType.ESCORT && !Expeditions.travellerNear(serverLevel, state.bound, pos)) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.traveller_missing", state.label))
            level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0f, 1.0f)
            return
        }

        val done = when (note.type) {
            QuestType.FETCH -> takeFetchItems(player, note)
            QuestType.HUNT, QuestType.CLEAR, QuestType.CHAMPION -> state.progress >= note.count
            QuestType.DELIVER, QuestType.ESCORT -> true
        }
        if (!done) {
            val have = if (note.type == QuestType.FETCH) countFetchItems(player, note) else state.progress
            player.sendOverlayMessage(Component.translatable("message.guildmark.not_done", note.title(), have, note.count))
            level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0f, 1.0f)
            return
        }

        stack.shrink(1)
        val origin = state.boardPos.takeIf { level.dimension() == state.dimension }
        val bonus = origin?.let { Standing.bonus(level, it, player, note.reward) } ?: 0
        val marks = Progression.reward(player, note.reward + bonus)
        val xp = note.reward * Progression.XP_PER_MARK
        player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), marks), Prediction.SERVER_ONLY)
        player.sendOverlayMessage(Component.translatable("message.guildmark.complete", marks, xp))
        level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f)
        Progression.addXp(player, xp)
        if (origin != null) Standing.add(serverLevel, origin, player, note.reward)
        // Deliveries and escorts also make a name with the village at the other end of the road
        if (note.type.needsRoad) Standing.add(serverLevel, pos, player, note.reward / 2)
        if (level is ServerLevel) {
            val state = level.getBlockState(pos)
            if (state.block is QuestBoardBlock) Advances.contribute(level, QuestBoardBlock.anchorPos(pos, state), note, player)
        }
    }

    private fun counts(note: QuestNote, state: ContractState, victim: LivingEntity): Boolean = when (note.type) {
        QuestType.HUNT -> EntityType.getKey(victim.type) == note.target
        QuestType.CLEAR -> victim.type.category == MobCategory.MONSTER &&
            victim.level().dimension() == state.dimension &&
            victim.blockPosition().closerThan(state.boardPos, QuestNote.CLEAR_RADIUS.toDouble())
        QuestType.CHAMPION -> victim.uuid == state.bound
        QuestType.FETCH, QuestType.DELIVER, QuestType.ESCORT -> false
    }

    private fun isFetchItem(note: QuestNote, stack: ItemStack): Boolean =
        stack.`is`(BuiltInRegistries.ITEM.getValue(note.target))

    fun countFetchItems(player: Player, note: QuestNote): Int =
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { isFetchItem(note, it) }, Int.MAX_VALUE, true)

    private fun takeFetchItems(player: Player, note: QuestNote): Boolean {
        if (countFetchItems(player, note) < note.count) return false
        ContainerHelper.clearOrCountMatchingItems(player.inventory, { isFetchItem(note, it) }, note.count, false)
        return true
    }

    private fun refreshLore(stack: ItemStack, note: QuestNote, state: ContractState) {
        val lines = mutableListOf(note.title())
        lines.addAll(contractDetails(note, state))
        if (note.type.showsProgress) {
            lines.add(Component.translatable("quest.guildmark.progress", state.progress, note.count))
        }
        lines.add(note.rewardLine())
        stack.set(DataComponents.LORE, ItemLore(lines))
    }

    /** Where to go and who's involved, for the contract's tooltip and screen. */
    fun contractDetails(note: QuestNote, state: ContractState): List<Component> {
        val lines = mutableListOf<Component>()
        val destination = state.destination
        when (note.type) {
            QuestType.DELIVER, QuestType.ESCORT -> if (destination != null) {
                lines.add(Component.translatable("quest.guildmark.destination", destination.x, destination.z))
                if (state.road >= 0) lines.add(Component.translatable("quest.guildmark.road"))
            }
            QuestType.CHAMPION -> if (destination != null) {
                lines.add(Component.translatable("quest.guildmark.champion_seen", state.label, destination.x, destination.z))
            }
            else -> {}
        }
        if (note.type == QuestType.ESCORT && state.label.isNotEmpty()) {
            lines.add(Component.translatable("quest.guildmark.traveller", state.label))
        }
        return lines
    }
}
