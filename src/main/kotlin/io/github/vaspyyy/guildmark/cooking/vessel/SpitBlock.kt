package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The Spit (living-world/cook/design.md 1.6, 11.1): two iron uprights over a fire pit and a drip tray, a rod turned by a
 * crank, its height (the `notch`, 0 to 2) over the coals, facing along the rod. Part B's: its [SpitBlockEntity] holds the vessel and
 * answers every click the base class sends it.
 */
class SpitBlock(properties: Properties) : StationBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(StationProps.NOTCH, 1).setValue(StationProps.LIT, false).setValue(StationProps.FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(StationProps.NOTCH, StationProps.LIT, StationProps.FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(StationProps.FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(StationProps.FACING, rotation.rotate(state.getValue(StationProps.FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(StationProps.FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(StationProps.FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = SpitBlockEntity(pos, state)

    companion object {
        /**
         * The outline: the stone base, the two uprights at their finials (13.9 px) and the rod and crank out to the east end, authored
         * facing north as the model is (art/scripts/cook_spit.py) and turned for each facing.
         */
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Block.box(0.4, 0.0, 3.2, 16.0, 14.0, 12.8))

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator {
            it.mapColor(MapColor.METAL).strength(2.5f).sound(SoundType.METAL).noOcclusion().lightLevel { state -> if (state.getValue(StationProps.LIT)) Fuel.LIGHT else 0 }
        }
    }
}
