package io.github.vaspyyy.guildmark.advance

import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.Expeditions
import io.github.vaspyyy.guildmark.road.RoadNetwork
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.player.Player
import kotlin.math.sqrt

/** Village projects: contracts turned in at a board fund its village's next [Advance]. */
object Advances {
    private const val SEARCH_RADIUS = 64

    /** [anchor] is the board's anchor cell, which holds the village's progress. */
    fun contribute(level: ServerLevel, anchor: BlockPos, note: QuestNote, player: Player) {
        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return
        val advance = Advance.at(board.advanceIndex)
        addPoints(level, anchor, advance.pointsFor(note), player)
    }

    /** Add progress to the village's current advance, building it once it's fully funded. */
    fun addPoints(level: ServerLevel, anchor: BlockPos, amount: Int, player: Player) {
        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return
        val advance = Advance.at(board.advanceIndex)
        val points = (board.advancePoints + amount).coerceAtMost(advance.cost)
        board.setAdvanceProgress(board.advanceIndex, points)
        player.sendSystemMessage(Component.translatable("message.guildmark.advance_progress", amount, advance.title, points, advance.cost))
        if (points >= advance.cost) tryComplete(level, anchor, board, advance)
    }

    private fun tryComplete(level: ServerLevel, anchor: BlockPos, board: QuestBoardBlockEntity, advance: Advance) {
        val center = villageCenter(level, anchor)
        if (advance == Advance.TRADE_ROAD) return startTradeRoad(level, anchor, board, center)
        val failure = AdvanceBuilders.build(advance, level, center, villageRadius(level, center))
        if (failure != null) {
            announce(level, center, Component.translatable(failure, advance.title))
            return
        }
        board.setAdvanceProgress(board.advanceIndex + 1, 0)
        announce(level, center, Component.translatable("message.guildmark.advance_complete", advance.title))
        celebrate(level, center)
    }

    /**
     * Send surveyors to the nearest unconnected village. The survey runs in the background; once it
     * finds a route the whole road is built, and if it can't, the funding goes back on the board.
     */
    private fun startTradeRoad(level: ServerLevel, anchor: BlockPos, board: QuestBoardBlockEntity, center: BlockPos) {
        val network = RoadNetwork.get(level)
        val destination = Expeditions.findUnconnectedVillage(level, center, network) ?: run {
            announce(level, center, Component.translatable("message.guildmark.no_road_target", Advance.TRADE_ROAD.title))
            return
        }
        val index = board.advanceIndex
        board.setAdvanceProgress(index + 1, 0)
        announce(level, center, Component.translatable("message.guildmark.road_surveying", destination.x, destination.z))
        network.survey(level, center, destination) { road ->
            if (road == null) {
                (level.getBlockEntity(anchor) as? QuestBoardBlockEntity)?.let { if (it.advanceIndex == index + 1) it.setAdvanceProgress(index, Advance.TRADE_ROAD.cost) }
                announce(level, center, Component.translatable("message.guildmark.no_road_route", Advance.TRADE_ROAD.title))
                return@survey
            }
            announce(level, center, Component.translatable("message.guildmark.road_started", destination.x, destination.z))
            celebrate(level, center)
        }
    }

    private fun celebrate(level: ServerLevel, center: BlockPos) {
        level.playSound(null, center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.NEUTRAL, 1.0f, 1.0f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x + 0.5, center.y + 1.5, center.z + 0.5, 40, 3.0, 1.5, 3.0, 0.0)
    }

    fun announce(level: ServerLevel, center: BlockPos, message: Component) {
        for (player in level.players()) {
            if (player.blockPosition().closerThan(center, 96.0)) player.sendSystemMessage(message)
        }
    }

    /** The village bell nearest the board, or the board itself if there's no bell. */
    fun villageCenter(level: ServerLevel, anchor: BlockPos): BlockPos =
        level.poiManager.findClosest({ it.`is`(PoiTypes.MEETING) }, anchor, SEARCH_RADIUS, PoiManager.Occupancy.ANY).orElse(anchor)

    /** Just outside the furthest bed, kept between 20 and 48 blocks. */
    fun villageRadius(level: ServerLevel, center: BlockPos): Int {
        val furthest = level.poiManager
            .findAll({ it.`is`(PoiTypes.HOME) }, { true }, center, SEARCH_RADIUS, PoiManager.Occupancy.ANY)
            .map { val dx = (it.x - center.x).toDouble(); val dz = (it.z - center.z).toDouble(); sqrt(dx * dx + dz * dz) }
            .toList().maxOrNull() ?: 18.0
        return (furthest.toInt() + 6).coerceIn(20, 48)
    }
}
