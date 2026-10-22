package io.github.vaspyyy.guildmark.cooking.dish

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.MapColor
import java.util.function.UnaryOperator

/**
 * The Larder (living-world/cook/design.md 1.1, 3.2, 11.1): a slatted cupboard on feet with a door, 18 slots, that keeps
 * dishes twice as long. Part D's; until it lands it is an inert block.
 */
class LarderBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(OPEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(OPEN)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = LarderBlockEntity(pos, state)

    companion object {
        /** Whether a player has the door open. */
        val OPEN: BooleanProperty = BlockStateProperties.OPEN

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD) }
    }
}
