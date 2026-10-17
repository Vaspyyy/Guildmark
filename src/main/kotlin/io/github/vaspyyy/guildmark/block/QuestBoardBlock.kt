package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.guild.TownControl
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.EnumProperty

/**
 * A village's quest board: villagers pin its notes (see VillageBoards), and it keeps the village's standing with each
 * player and the advances it's building. Sneak-use opens the advances.
 */
class QuestBoardBlock(properties: BlockBehaviour.Properties) : NoticeBoardBlock(properties) {
    companion object {
        val FACING: EnumProperty<Direction> = NoticeBoardBlock.FACING
        val PART: EnumProperty<BoardPart> = NoticeBoardBlock.PART
        val NOTE: EnumProperty<NoteKind> = NoticeBoardBlock.NOTE

        fun rightOf(facing: Direction): Direction = NoticeBoardBlock.rightOf(facing)

        fun cellPos(anchor: BlockPos, facing: Direction, part: BoardPart): BlockPos = NoticeBoardBlock.cellPos(anchor, facing, part)

        fun anchorPos(pos: BlockPos, state: BlockState): BlockPos = NoticeBoardBlock.anchorPos(pos, state)
    }

    override fun fill(level: Level, anchor: BlockPos, facing: Direction) = VillageBoards.setUp(level, anchor, facing)

    /**
     * One board to a village: a second near the same villagers would only split it in two, its standing, its advances,
     * its notes and its Guildmaster. Only the server knows where the boards are, so it refuses, says why and puts the
     * board back in the hand the client already took it from.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val state = super.getStateForPlacement(context) ?: return null
        val level = context.level as? ServerLevel ?: return state
        if (VillageBoards.boardInReach(level, context.clickedPos) == null) return state
        (context.player as? ServerPlayer)?.let {
            it.sendOverlayMessage(Component.translatable("message.guildmark.village_has_board"))
            it.containerMenu.sendAllDataToRemote()
        }
        return null
    }

    // Rotating notes and calling villagers over
    override fun tick(level: ServerLevel, anchor: BlockPos, state: BlockState) = VillageBoards.tickBoard(level, anchor, state)

    override fun sneakUse(level: ServerLevel, player: ServerPlayer, anchor: BlockPos) = TownControl.openAdvances(level, player, anchor)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = QuestBoardBlockEntity(pos, state)
}
