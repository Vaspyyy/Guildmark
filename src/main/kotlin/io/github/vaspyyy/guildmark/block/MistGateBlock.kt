package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.lair.Lairs
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult

/**
 * The veil of mist across a boss hall's door. Nothing walks through it; use it to step through. From
 * outside it always lets you in; from inside it holds fast while the hall's master still lives. [FACING]
 * points into the hall. Placed by dungeons, gone when the boss falls, and it can't be mined.
 */
class MistGateBlock(properties: Properties) : HorizontalDirectionalBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) Lairs.get(level).passGate(level, pos, state, player)
        return InteractionResult.SUCCESS
    }

    /** One veil, not a stack of boxes: the faces between gate blocks aren't drawn. */
    override fun skipRendering(state: BlockState, neighborState: BlockState, direction: Direction): Boolean =
        neighborState.`is`(this) || super.skipRendering(state, neighborState, direction)

    override fun propagatesSkylightDown(state: BlockState): Boolean = true

    override fun getShadeBrightness(state: BlockState, level: BlockGetter, pos: BlockPos): Float = 1.0f

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(3) != 0) return
        // Wisps curl off either face and drift upward
        val facing = state.getValue(FACING)
        val side = if (random.nextBoolean()) 0.58 else -0.58
        level.addParticle(
            ParticleTypes.WHITE_SMOKE,
            pos.x + 0.5 + facing.stepX * side + facing.stepZ * (random.nextDouble() - 0.5),
            pos.y + random.nextDouble(),
            pos.z + 0.5 + facing.stepZ * side + facing.stepX * (random.nextDouble() - 0.5),
            facing.stepX * side * 0.02, 0.012, facing.stepZ * side * 0.02,
        )
    }
}
