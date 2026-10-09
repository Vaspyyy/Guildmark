package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.item.ContractItem
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A 3 wide, 2 tall notice board. Every cell is its own block with its own note,
 * and the cells hold each other up the way bed halves do.
 */
class QuestBoardBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {
    companion object {
        /** The side the notes face. */
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING
        val PART: EnumProperty<BoardPart> = EnumProperty.create("part", BoardPart::class.java)
        val NOTE: EnumProperty<NoteKind> = EnumProperty.create("note", NoteKind::class.java)

        // Thin panel against the back of the cell; models are authored facing north
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Block.box(0.0, 0.0, 12.0, 16.0, 16.0, 16.0))

        /** Direction of increasing column, i.e. the viewer's right when looking at the front. */
        fun rightOf(facing: Direction): Direction = facing.counterClockWise

        fun cellPos(anchor: BlockPos, facing: Direction, part: BoardPart): BlockPos =
            anchor.relative(rightOf(facing), part.col).above(part.row)

        fun anchorPos(pos: BlockPos, state: BlockState): BlockPos {
            val part = state.getValue(PART)
            return pos.relative(rightOf(state.getValue(FACING)), -part.col).below(part.row)
        }
    }

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, BoardPart.ANCHOR)
                .setValue(NOTE, NoteKind.NONE)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PART, NOTE)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val anchor = context.clickedPos
        val level = context.level
        val fits = BoardPart.entries.all { part ->
            val pos = cellPos(anchor, facing, part)
            !level.isOutsideBuildHeight(pos) &&
                level.worldBorder.isWithinBounds(pos) &&
                level.getBlockState(pos).canBeReplaced(context)
        }
        return if (fits) defaultBlockState().setValue(FACING, facing) else null
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, by: LivingEntity?, itemStack: ItemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack)
        val facing = state.getValue(FACING)
        for (part in BoardPart.entries) {
            if (part != BoardPart.ANCHOR) {
                level.setBlock(cellPos(pos, facing, part), state.setValue(PART, part), UPDATE_ALL)
            }
        }
        if (!level.isClientSide()) {
            // Placeholder: fill every cell right away until villagers post notes themselves
            for (part in BoardPart.entries) {
                val be = level.getBlockEntity(cellPos(pos, facing, part)) as? QuestBoardBlockEntity
                be?.updateNote(QuestGenerator.generate(level.random))
            }
        }
    }

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        val facing = state.getValue(FACING)
        val part = state.getValue(PART)
        val right = rightOf(facing)
        val (dc, dr) = when (directionToNeighbour) {
            right -> 1 to 0
            right.opposite -> -1 to 0
            Direction.UP -> 0 to 1
            Direction.DOWN -> 0 to -1
            else -> return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        }
        val expected = BoardPart.at(part.col + dc, part.row + dr)
            ?: return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        val intact = neighbourState.`is`(this) &&
            neighbourState.getValue(FACING) == facing &&
            neighbourState.getValue(PART) == expected
        return if (intact) state else Blocks.AIR.defaultBlockState()
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        // Creative: remove the anchor silently first so the collapsing board drops nothing
        if (!level.isClientSide() && player.preventsBlockDrops() && state.getValue(PART) != BoardPart.ANCHOR) {
            val anchor = anchorPos(pos, state)
            val anchorState = level.getBlockState(anchor)
            if (anchorState.`is`(this)) {
                level.setBlock(anchor, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
                level.levelEvent(player, 2001, anchor, getId(anchorState))
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (stack.item !is ContractItem) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (!level.isClientSide()) Contracts.turnIn(stack, player, level, pos)
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level.isClientSide()) {
            val note = (level.getBlockEntity(pos) as? QuestBoardBlockEntity)?.note
            if (note != null) {
                ClientHooks.openNote(note, pos)
            } else {
                player.sendOverlayMessage(Component.translatable("message.guildmark.no_note"))
            }
        }
        return InteractionResult.SUCCESS
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = QuestBoardBlockEntity(pos, state)
}
