package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.registry.ModParticles
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.server.level.ServerPlayer.RespawnPosAngle
import java.util.Optional

/**
 * A Wardfire: a ring of old stones round a sword driven into the ash. Use a cold one to kindle it, a lit
 * one to rest. Resting heals you, refills your flask and makes it where you wake after a fall. Found out in
 * the world; it can't be mined.
 */
class WardfireBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(LIT, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIT)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = COLLISION

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) Wardfires.use(level, pos, state, player)
        return InteractionResult.SUCCESS
    }

    /** Wake beside the fire, facing it. A fire that has gone out wakes nobody. */
    override fun getRespawnPosition(state: BlockState, type: EntityType<*>, level: LevelReader, pos: BlockPos, orientation: Float): Optional<RespawnPosAngle> {
        if (!state.getValue(LIT)) return Optional.empty()
        return RespawnAnchorBlock.findStandUpPosition(type, level, pos).map { RespawnPosAngle.of(it, pos, 0f) }
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val x = pos.x + 0.5
        val y = pos.y.toDouble()
        val z = pos.z + 0.5
        if (!state.getValue(LIT)) {
            // A thread of smoke from the cold ash now and then
            if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.SMOKE, x + random.nextGaussian() * 0.12, y + 0.2, z + random.nextGaussian() * 0.12, 0.0, 0.02, 0.0)
            return
        }
        if (random.nextInt(8) == 0) {
            level.playLocalSound(x, y + 0.3, z, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.6f + random.nextFloat() * 0.4f, 0.7f + random.nextFloat() * 0.3f, false)
        }
        // Embers rise out of the flames and drift
        repeat(1 + random.nextInt(2)) {
            level.addParticle(
                ModParticles.WARD_EMBER.get(),
                x + random.nextGaussian() * 0.16, y + 0.35 + random.nextFloat() * 0.4, z + random.nextGaussian() * 0.16,
                random.nextGaussian() * 0.006, 0.025 + random.nextFloat() * 0.03, random.nextGaussian() * 0.006,
            )
        }
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x + random.nextGaussian() * 0.1, y + 0.9, z + random.nextGaussian() * 0.1, 0.0, 0.03, 0.0)
        }
    }

    companion object {
        val LIT: BooleanProperty = BlockStateProperties.LIT

        /** The ring of stones and the sword standing out of it, for picking the block out. */
        private val SHAPE: VoxelShape = Shapes.or(box(1.0, 0.0, 1.0, 15.0, 7.0, 15.0), box(6.5, 0.0, 6.5, 9.5, 16.0, 9.5))
        /** Low enough to step over the stones; the sword still stops you walking through the middle. */
        private val COLLISION: VoxelShape = Shapes.or(box(1.0, 0.0, 1.0, 15.0, 3.0, 15.0), box(7.0, 0.0, 7.0, 9.0, 16.0, 9.0))

        fun light(state: BlockState): Int = if (state.getValue(LIT)) 13 else 0
    }
}
