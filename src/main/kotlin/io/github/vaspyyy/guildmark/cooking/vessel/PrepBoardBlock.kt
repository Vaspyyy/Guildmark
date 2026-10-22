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
        /** The counter: its slab overhangs the legs, 0.6 to 15.4 and 2 to 14 px, and the board on it reaches 10.2 px (art/scripts/cook_helpers.py). */
        private val SHAPE: VoxelShape = Block.box(0.6, 0.0, 2.0, 15.4, 10.2, 14.0)

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator { it.mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion() }
    }
}
