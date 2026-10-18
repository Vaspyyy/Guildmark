package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.plan.Look
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A display of a capital's market goods: a basket, a crate, a rack, a board, a tray, a shelf or sacks ([look]), its
 * front toward [FACING], where the buyers stand. The goods on it are the game's own items, heaped as full as the
 * market's stock of them is ([WaresBlockEntity] keeps the count, client/economy/WaresRenderer draws them). The city
 * plan sets them out on the stalls and counters (Canvas.wares); put down by hand, one shows the first of its look's
 * [io.github.vaspyyy.guildmark.city.plan.Wares], from the market of the capital it stands in.
 */
class WaresBlock(val look: Look, properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    /** Facing whoever puts it down, so they stand at its front. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(look).getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WaresBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide() || type != WaresRegistry.ENTITY.get()) return null
        return BlockEntityTicker { tickLevel, _, _, entity -> if (tickLevel is ServerLevel) (entity as WaresBlockEntity).serverTick(tickLevel) }
    }

    companion object {
        /** The way its front faces. */
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING

        /** Each look's outline, authored facing north (as the models are, art/scripts/wares_art.py) and turned for each facing. */
        private val SHAPES: Map<Look, Map<Direction, VoxelShape>> = Look.entries.associateWith { look ->
            Shapes.rotateHorizontal(
                when (look) {
                    Look.BASKET -> box(1.0, 0.0, 2.0, 15.0, 6.5, 14.0)
                    Look.FISH -> box(1.0, 0.0, 1.0, 15.0, 6.5, 15.0)
                    Look.MEAT -> Shapes.or(box(1.0, 0.0, 2.0, 15.0, 1.5, 12.5), box(1.0, 0.0, 12.5, 15.0, 15.0, 14.0))
                    Look.CLOTH -> box(1.0, 0.0, 2.0, 15.0, 2.6, 14.0)
                    Look.ARROWS -> Shapes.or(box(1.0, 0.0, 1.6, 15.0, 2.4, 14.0), box(1.75, 0.0, 7.75, 14.0, 8.0, 13.25))
                    Look.CANDLES -> Shapes.or(box(1.0, 0.0, 2.0, 15.0, 2.5, 8.0), box(1.0, 0.0, 8.0, 15.0, 5.0, 14.0))
                    Look.BREAD -> Shapes.or(box(1.0, 0.0, 2.0, 15.0, 1.5, 14.0), box(1.0, 0.0, 7.0, 15.0, 10.5, 14.0))
                    Look.SACKS -> box(1.0, 0.0, 3.5, 15.0, 9.0, 12.5)
                },
            )
        }
    }
}
