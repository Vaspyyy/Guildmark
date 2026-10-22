package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.MapColor
import java.util.function.UnaryOperator

/**
 * The Bake oven (living-world/cook/design.md 1.6, 11.1): a domed clay-brick oven with an arched door, a firebox mouth and a
 * damper plate (the `notch`, 0 to 2), facing the way its door opens. Part B's: its [OvenBlockEntity] holds the vessel and answers
 * every click the base class sends it.
 */
class OvenBlock(properties: Properties) : StationBlock(properties) {
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

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = OvenBlockEntity(pos, state)

    companion object {
        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator {
            it.mapColor(MapColor.TERRACOTTA_ORANGE).strength(3.0f).sound(SoundType.STONE).lightLevel { state -> if (state.getValue(StationProps.LIT)) Fuel.LIGHT else 0 }
        }
    }
}
