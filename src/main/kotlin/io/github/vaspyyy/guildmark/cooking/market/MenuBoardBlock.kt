package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The Menu board (living-world/cook/design.md 5.4, 11.1): a chalk slate on a post, or on a wall, that shows the day's
 * wanted dishes at an inn, a cookshop's stock or a farmhouse's pantry. Part G's; until it lands it is an inert block.
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

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MenuBoardBlockEntity(pos, state)

    companion object {
        private val SHAPE: VoxelShape = Block.box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0)

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.WOOD).noOcclusion() }
    }
}
