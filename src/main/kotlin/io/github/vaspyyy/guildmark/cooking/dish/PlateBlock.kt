package io.github.vaspyyy.guildmark.cooking.dish

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The Plate (living-world/cook/design.md 3.5, 11.1): a flat plate or board on a table that holds one dish, to show or to
 * eat. Part D's; until it lands it is an inert block.
 */
class PlateBlock(properties: Properties) : Block(properties), EntityBlock {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PlateBlockEntity(pos, state)

    companion object {
        private val SHAPE: VoxelShape = Block.box(2.0, 0.0, 2.0, 14.0, 2.0, 14.0)

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.TERRACOTTA_WHITE).strength(0.5f).sound(SoundType.STONE).noOcclusion() }
    }
}
