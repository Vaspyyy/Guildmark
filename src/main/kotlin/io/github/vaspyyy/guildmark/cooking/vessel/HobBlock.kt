package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.MapColor
import java.util.function.UnaryOperator

/**
 * The Hob (living-world/cook/design.md 1.6, 11.1): a low brick plinth with an iron top, a firebox mouth and a slide-damper
 * (the `notch`, 0 to 2) for the pan. Part B's; until it lands it is an inert block.
 */
class HobBlock(properties: Properties) : StationBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(StationProps.NOTCH, 1).setValue(StationProps.LIT, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(StationProps.NOTCH, StationProps.LIT)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = HobBlockEntity(pos, state)

    companion object {
        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator {
            it.mapColor(MapColor.COLOR_RED).strength(3.0f).sound(SoundType.STONE).lightLevel { state -> if (state.getValue(StationProps.LIT)) Fuel.LIGHT else 0 }
        }
    }
}
