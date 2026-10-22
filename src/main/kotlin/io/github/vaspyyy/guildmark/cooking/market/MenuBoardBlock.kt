package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The Menu board (living-world/cook/design.md 5.4, 11.1): a chalk slate on a post, or on a wall, that shows the day's
 * wanted dishes at an inn, a cookshop's stock or a farmhouse's pantry. Its lines are its block entity's
 * ([MenuBoardBlockEntity]); an inn's are written by the ledger ([MenuBoards]) and drawn by the block's renderer (Part C).
 * Used with an empty hand it reads its lines out in chat, which is also how a cookshop's owner takes the till (Part H adds
 * that with [MenuBoards.onUse]).
 */
class MenuBoardBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(StationProps.FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(StationProps.FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(StationProps.FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(StationProps.FACING, rotation.rotate(state.getValue(StationProps.FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(StationProps.FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(StationProps.FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MenuBoardBlockEntity(pos, state)

    /** The server's half only: a board at an inn looks to its lines every few seconds (see [MenuBoards.tick]). */
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide() || type != CookRegistry.MENU_BOARD_ENTITY.get()) return null
        return BlockEntityTicker { world, pos, _, entity -> if (world is ServerLevel && entity is MenuBoardBlockEntity) MenuBoards.tick(world, pos, entity) }
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) MenuBoards.read(level, pos, player)
        return InteractionResult.SUCCESS
    }

    companion object {
        // Authored facing north, the slate's face to the north: a post, and the slate standing out in front of it
        private val POST: VoxelShape = box(7.0, 0.0, 7.0, 9.0, 13.0, 9.0)
        private val SLATE: VoxelShape = box(1.0, 4.0, 5.0, 15.0, 15.0, 7.0)
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Shapes.or(POST, SLATE))

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.WOOD).noOcclusion() }
    }
}
