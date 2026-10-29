package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.trade.HaulBoards
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
 * A capital's Haul Board: the market board's twin, a 3 by 2 board in dark oak and iron that stands beside it in the market
 * square. Its left half is a slate with a row for each town round about (how old the news of it is, what it is short of and
 * has too much of, how its worst road is); its right half is a rail of this town's haul tickets. The hall's merchant chalks
 * it up and hangs the tickets (see [HaulBoards] and [io.github.vaspyyy.guildmark.trade.HaulClerk]); using it opens the
 * board's screen, where a ticket is booked. Only its anchor (bottom middle) cell keeps the board's state
 * ([HaulBoardBlockEntity]).
 */
class HaulBoardBlock(properties: BlockBehaviour.Properties) : NoticeBoardBlock(properties) {
    /** A board just put up stays bare until the clerk comes by (or the town writes it up unseen). */
    override fun fill(level: Level, anchor: BlockPos, facing: Direction) {}

    override fun tick(level: ServerLevel, anchor: BlockPos, state: BlockState) = HaulBoards.tick(level, anchor)

    /** The slate with its cap and header, and the shelf that stands out before the rail. */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(PART) == BoardPart.ANCHOR) HaulBoardBlockEntity(pos, state) else null

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
        if (level is ServerLevel && player is ServerPlayer) HaulBoards.read(level, player, anchorPos(pos, state))
        return InteractionResult.SUCCESS
    }

    companion object {
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Block.box(0.0, 0.0, 8.0, 16.0, 16.0, 16.0))
    }
}
