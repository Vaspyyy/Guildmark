package io.github.vaspyyy.guildmark.advance

import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.village.VillageNames
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.Expeditions
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.guild.GuildNews
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
        if (board.roadOwed) buildOwedRoad(level, anchor, board)
    }

    /** Add progress to the village's current advance, building it once it's fully funded. */
    fun addPoints(level: ServerLevel, anchor: BlockPos, amount: Int, player: Player) {
        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return
        val advance = Advance.at(board.advanceIndex)
        // Already paid for and waiting on something else (room to build, somewhere for a road to go): just try again
        val funded = board.advancePoints >= advance.cost
        val points = (board.advancePoints + amount).coerceAtMost(advance.cost)
        board.setAdvanceProgress(board.advanceIndex, points)
        if (!funded) player.sendSystemMessage(Component.translatable("message.guildmark.advance_progress", amount, advance.title, points, advance.cost))
        Milestones.award(player, Milestones.BUILDER)
        if (points >= advance.cost) {
            if (!funded) {
                Milestones.award(player, Milestones.ADVANCE_BUILT)
                if (advance == Advance.TRADE_ROAD) Milestones.award(player, Milestones.TRADE_ROAD)
                if (advance == Advance.KEEP) Milestones.award(player, Milestones.CITY)
            }
            tryComplete(level, anchor, board, advance)
        }
    }

    private fun tryComplete(level: ServerLevel, anchor: BlockPos, board: QuestBoardBlockEntity, advance: Advance) {
        val center = villageCenter(level, anchor)
        if (advance == Advance.TRADE_ROAD) return startTradeRoad(level, anchor, board, center)
        val failure = AdvanceBuilders.build(advance, level, center, villageRadius(level, center), anchor)
        if (failure != null) {
            announce(level, center, Component.translatable(failure, advance.title))
            return
        }
        moveOn(board)
        announce(level, center, Component.translatable("message.guildmark.advance_complete", advance.title))
        GuildNews.add(level, "news.guildmark.advance", VillageNames.of(anchor), "#advance.guildmark.${advance.id}")
        celebrate(level, center)
    }

    /**
     * On to the village's next advance. Past the fixed chain every advance is another Trade Road, so a
     * road still owed from earlier becomes the first of them, already paid for.
     */
    private fun moveOn(board: QuestBoardBlockEntity) {
        val next = board.advanceIndex + 1
        if (next >= Advance.entries.size && board.roadOwed) {
            board.oweRoad(false)
            board.setAdvanceProgress(next, Advance.TRADE_ROAD.cost)
        } else board.setAdvanceProgress(next, 0)
    }

    /**
     * Send surveyors to the nearest unconnected village. The survey runs in the background; once it
     * finds a route the whole road is built. If there's nowhere to go, or no way there, the Trade Road
     * of the fixed chain doesn't hold up the rest: the village moves on and owes itself the road, built
     * once there's somewhere to go (see [buildOwedRoad]). The roads after the chain wait, funded.
     */
    private fun startTradeRoad(level: ServerLevel, anchor: BlockPos, board: QuestBoardBlockEntity, center: BlockPos) {
        val network = RoadNetwork.get(level)
        val index = board.advanceIndex
        val inChain = index < Advance.entries.size
        val destination = Expeditions.findUnconnectedVillage(level, center, network) ?: run {
            if (inChain) {
                moveOn(board)
                board.oweRoad(true)
                announce(level, center, Component.translatable("message.guildmark.road_skipped", Advance.TRADE_ROAD.title, Advance.at(index + 1).title))
            } else announce(level, center, Component.translatable("message.guildmark.no_road_target", Advance.TRADE_ROAD.title))
            return
        }
        moveOn(board)
        announce(level, center, Component.translatable("message.guildmark.road_surveying", destination.x, destination.z))
        network.survey(level, center, destination) { road ->
            if (road == null) {
                val now = level.getBlockEntity(anchor) as? QuestBoardBlockEntity
                if (inChain) {
                    now?.oweRoad(true)
                    announce(level, center, Component.translatable("message.guildmark.road_skipped_route", Advance.TRADE_ROAD.title))
                } else {
                    now?.let { if (it.advanceIndex == index + 1) it.setAdvanceProgress(index, Advance.TRADE_ROAD.cost) }
                    announce(level, center, Component.translatable("message.guildmark.no_road_route", Advance.TRADE_ROAD.title))
                }
                return@survey
            }
            announce(level, center, Component.translatable("message.guildmark.road_started", destination.x, destination.z))
            celebrate(level, center)
        }
    }

    /**
     * The road a village paid for while it had nowhere to go: each day a contract is turned in there, it
     * looks again, and once a village is in reach and a route found, the road is built at no further cost.
     */
    private fun buildOwedRoad(level: ServerLevel, anchor: BlockPos, board: QuestBoardBlockEntity) {
        if (!board.lookForRoad(VillageBoards.day(level))) return
        val center = villageCenter(level, anchor)
        val network = RoadNetwork.get(level)
        val destination = Expeditions.findUnconnectedVillage(level, center, network) ?: return
        network.survey(level, center, destination) { road ->
            if (road == null) return@survey
            (level.getBlockEntity(anchor) as? QuestBoardBlockEntity)?.oweRoad(false)
            announce(level, center, Component.translatable("message.guildmark.road_owed_started", Advance.TRADE_ROAD.title, destination.x, destination.z))
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
