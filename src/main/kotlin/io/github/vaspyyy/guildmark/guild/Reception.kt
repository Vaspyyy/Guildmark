package io.github.vaspyyy.guildmark.guild

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.network.OpenReceptionPayload
import io.github.vaspyyy.guildmark.network.ReceptionActionPayload
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The guild hall desk. Using the receptionist opens the hall screen (rank trials, a daily hall contract
 * and the guild's news); using them with a contract in hand turns it in, just like a board.
 */
object Reception {
    private const val DESK_RANGE = 8.0

    fun onInteract(event: PlayerInteractEvent.EntityInteract) {
        val villager = event.target as? Villager ?: return
        val level = event.level as? ServerLevel ?: return
        if (!villager.hasData(ModAttachments.RECEPTIONIST)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return

        val held = player.mainHandItem
        if (held.has(ModDataComponents.CONTRACT_STATE.get())) {
            Contracts.turnIn(held, player, level, villager.blockPosition())
            return
        }
        val today = VillageBoards.day(level)
        val ready = player.getData(ModAttachments.HALL_CONTRACT_DAY) != today
        val lairReady = player.getData(ModAttachments.LAIR_HUNT_DAY) != today
        PacketDistributor.sendToPlayer(player, OpenReceptionPayload(villager.id, GuildNews.get(level).items.toList(), ready, lairReady, today))
    }

    fun handleAction(player: Player, entityId: Int, action: Int) {
        val level = player.level() as? ServerLevel ?: return
        val villager = level.getEntity(entityId) as? Villager ?: return
        if (!villager.hasData(ModAttachments.RECEPTIONIST) || player.distanceTo(villager) > DESK_RANGE) return
        val anchor = villager.getData(ModAttachments.RECEPTIONIST)
        val poster = villager.name.string

        val contract = when (action) {
            ReceptionActionPayload.TRIAL -> {
                val next = Progression.get(player).trialReady() ?: run {
                    player.sendOverlayMessage(Component.translatable("message.guildmark.trial_not_ready"))
                    return
                }
                if (carriesTrial(player)) {
                    player.sendOverlayMessage(Component.translatable("message.guildmark.trial_active"))
                    return
                }
                val stack = Contracts.take(QuestGenerator.generateTrial(next, poster), level, anchor, Direction.NORTH, player, fromHall = true) ?: return
                player.sendSystemMessage(Component.translatable("message.guildmark.trial_given", next.letter))
                stack
            }
            ReceptionActionPayload.HALL_CONTRACT -> {
                val today = VillageBoards.day(level)
                if (player.getData(ModAttachments.HALL_CONTRACT_DAY) == today) {
                    player.sendOverlayMessage(Component.translatable("message.guildmark.hall_contract_taken"))
                    return
                }
                val note = QuestGenerator.generateHall(level.random, Progression.get(player).adventurerRank, VillageBoards.hasRoad(level, anchor), poster)
                val stack = Contracts.take(note, level, anchor, Direction.NORTH, player, fromHall = true) ?: return
                player.setData(ModAttachments.HALL_CONTRACT_DAY, today)
                stack
            }
            ReceptionActionPayload.LAIR_HUNT -> {
                val today = VillageBoards.day(level)
                if (player.getData(ModAttachments.LAIR_HUNT_DAY) == today) {
                    player.sendOverlayMessage(Component.translatable("message.guildmark.lair_hunt_taken"))
                    return
                }
                val note = QuestGenerator.generateLair(level.random, Progression.get(player).adventurerRank, poster)
                val stack = Contracts.take(note, level, anchor, Direction.NORTH, player, fromHall = true) ?: return
                player.setData(ModAttachments.LAIR_HUNT_DAY, today)
                stack
            }
            else -> return
        }
        player.inventory.placeItemBackInInventory(contract, Prediction.SERVER_ONLY)
        level.playSound(null, villager.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0f, 1.0f)
    }

    private fun carriesTrial(player: Player): Boolean = (0 until player.inventory.containerSize).any { slot ->
        player.inventory.getItem(slot).get(ModDataComponents.QUEST_NOTE.get())?.trial == true
    }
}
