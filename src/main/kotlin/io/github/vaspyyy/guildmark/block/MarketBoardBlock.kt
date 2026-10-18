package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.economy.MarketBoards
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A capital's market board: a 3 by 2 slate in a frame in the market square, where the town crier chalks up every
 * good's price each morning and again at noon, after crying the news, and pins the news up beside them (see
 * [MarketBoards]). Only its anchor (bottom middle) cell keeps the board's state ([MarketBoardBlockEntity]).
 */
class MarketBoardBlock(properties: BlockBehaviour.Properties) : NoticeBoardBlock(properties) {
    /** A board just put up stays bare until the crier comes by (or the town writes it up unseen). */
    override fun fill(level: Level, anchor: BlockPos, facing: Direction) {}

    override fun tick(level: ServerLevel, anchor: BlockPos, state: BlockState) = MarketBoards.tick(level, anchor)

    /** The slate with its cap, header and ledge, which stand a little prouder than a notice board's. */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(PART) == BoardPart.ANCHOR) MarketBoardBlockEntity(pos, state) else null

    /** No contract is handed in here. */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult = InteractionResult.TRY_WITH_EMPTY_HAND

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) MarketBoards.read(level, player, anchorPos(pos, state))
        return InteractionResult.SUCCESS
    }

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Block.box(0.0, 0.0, 10.0, 16.0, 16.0, 16.0))
    }
}
