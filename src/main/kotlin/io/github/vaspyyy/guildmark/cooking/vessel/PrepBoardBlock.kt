package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The Prep Board (living-world/cook/design.md 1.1, 11.1): a short counter with a chopping board, where the Cook's Knife
 * prepares an ingredient. Part B's; until it lands it is an inert block.
 */
class PrepBoardBlock(properties: Properties) : StationBlock(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PrepBoardBlockEntity(pos, state)

    companion object {
        private val SHAPE: VoxelShape = Block.box(1.0, 0.0, 1.0, 15.0, 3.0, 15.0)

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion() }
    }
}
