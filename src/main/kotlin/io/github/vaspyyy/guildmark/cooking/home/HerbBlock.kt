package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The herb (living-world/cook/design.md 6.7, 11.1): a crop of four ages, a sprout to a flowering bush, planted from a
 * sprig on farmland. Part K's (its seasons, the wild sprigs, the kitchen garden); until it lands it grows as a crop does,
 * with the game's own rules, and drops the sprig.
 */
class HerbBlock(properties: Properties) : CropBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(AGE, 0))
    }

    override fun getAgeProperty(): IntegerProperty = AGE

    override fun getMaxAge(): Int = MAX

    override fun getBaseSeedId(): ItemLike = CookRegistry.HERB_SPRIG.get()

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES[getAge(state)]

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AGE)
    }

    companion object {
        const val MAX = 3
        val AGE: IntegerProperty = BlockStateProperties.AGE_3

        private val SHAPES: Array<VoxelShape> = Array(MAX + 1) { age -> Block.box(0.0, 0.0, 0.0, 16.0, 4.0 + age * 3.0, 16.0) }

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator {
            it.mapColor(MapColor.PLANT).noCollision().randomTicks().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.POPPED)
        }
    }
}
