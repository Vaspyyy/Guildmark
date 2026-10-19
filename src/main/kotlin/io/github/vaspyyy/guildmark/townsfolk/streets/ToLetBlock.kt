package io.github.vaspyyy.guildmark.townsfolk.streets

import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.festival.Festivities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
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
 * A "To let" board by the door of a home left empty (M6 Part D2, see [Signs]): its post knocked in, a bracket out from
 * its top toward the street ([FACING]) and the board hung from it on two hooks, swinging in the wind, twice as far in
 * rain, and creaking as it turns back (client/streets/ToLetRenderer and [ToLetBlockEntity]; the block's own model is
 * the post and bracket alone). Like the festivals' trim it has no item and no drop, can't be broken or moved, and is
 * put back to what stood there as it comes down ([FestivalRestores.Restorable]); a stray one goes on its next random
 * tick. (A board put up before it had a block of its own is the trim's TO_LET look, which hangs still.)
 */
class ToLetBlock(properties: Properties) : Block(properties), EntityBlock, FestivalRestores.Restorable {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        OUTLINE.getValue(state.getValue(FACING))

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = POST

    override fun propagatesSkylightDown(state: BlockState): Boolean = true

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ToLetBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        // The client's half only: its creak (client/streets)
        if (!level.isClientSide() || type != StreetsRegistry.TO_LET_ENTITY.get()) return null
        return BlockEntityTicker { _, _, _, entity -> ToLetBlockEntity.clientTick?.invoke(entity as ToLetBlockEntity) }
    }

    // Nobody wants it any more (left from a crash, say): put back as it was (see Festivities.checkPiece)
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) = Festivities.checkPiece(level, pos)

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack = ItemStack.EMPTY

    companion object {
        /** The way the bracket and the board's face look: out to the street. */
        val FACING: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING

        /** What bumps into it: the post (the board hangs above a child's head, and swings). */
        private val POST: VoxelShape = box(6.5, 0.0, 6.5, 9.5, 16.0, 9.5)

        /** Its outline: the post, and the board hung toward the street (authored facing north, turned for each facing). */
        private val OUTLINE: Map<Direction, VoxelShape> =
            Shapes.rotateHorizontal(Shapes.or(box(7.0, 0.0, 7.0, 9.0, 22.0, 9.0), box(3.0, 12.5, 3.5, 13.0, 19.5, 5.5)))

        fun state(facing: Direction): BlockState = StreetsRegistry.TO_LET.get().defaultBlockState().setValue(FACING, facing)
    }
}

/**
 * A "To let" board's block entity (see [ToLetBlock]): nothing kept, there to have the board drawn swinging and heard
 * creaking on the clients (client/streets).
 */
class ToLetBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(StreetsRegistry.TO_LET_ENTITY.get(), pos, state) {
    /** Client side: how wet the wind is here (0 dry to 1 in rain, easing as the rain comes and goes). */
    @JvmField
    var wet = -1f

    /** Client side: the swing a tick ago (degrees) and its way then (-1, 0, 1), and when it last creaked. */
    @JvmField
    var lastSwing = Float.NaN

    @JvmField
    var lastWay = 0

    @JvmField
    var creakAt = Long.MIN_VALUE / 2

    companion object {
        /** Client side: each tick of a board in the loaded world (its creak), set by client/streets/StreetsClient. */
        @JvmStatic
        var clientTick: ((ToLetBlockEntity) -> Unit)? = null
    }
}
