package io.github.vaspyyy.guildmark.region.terrain

import io.github.vaspyyy.guildmark.region.RegionData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.SpeleothemBlock
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SpeleothemThickness
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

// The wild lands' own blocks (see RegionTerrain for the list, and TerrainFeatures for where they're laid).
// Bog rot, ash, the charred log and glimmerstone are plain vanilla kinds and need no class of their own.

/**
 * A bog lantern: a crooked marsh stalk hung with pale pods that glow. It grows where other plants do, gives a
 * little light, and now and then a wisp drifts up off it and hangs about.
 */
class BogLanternBlock(properties: BlockBehaviour.Properties) : VegetationBlock(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(8) != 0) return
        val offset = state.getOffset(pos)
        level.addParticle(
            RegionData.MARSH_WISP.get(), pos.x + 0.5 + offset.x + (random.nextDouble() - 0.5) * 0.5, pos.y + 0.6 + random.nextDouble() * 0.4,
            pos.z + 0.5 + offset.z + (random.nextDouble() - 0.5) * 0.5, 0.0, 0.0, 0.0,
        )
    }

    companion object {
        private val SHAPE: VoxelShape = Block.column(12.0, 0.0, 15.0)
    }
}

/**
 * Cattails, two blocks tall, in the marsh's shallows and on its banks: the lower half stands in the water if
 * there is any (and keeps it when the plant goes), the upper half holds its brown heads up out of it.
 */
class CattailBlock(properties: BlockBehaviour.Properties) : DoublePlantBlock(properties), SimpleWaterloggedBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(BlockStateProperties.WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HALF, BlockStateProperties.WATERLOGGED)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun mayPlaceOn(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean =
        super.mayPlaceOn(state, level, pos) || state.`is`(BlockTags.SAND) || state.`is`(Blocks.CLAY) || state.`is`(Blocks.GRAVEL)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val state = super.getStateForPlacement(context) ?: return null
        return copyWaterloggedFrom(context.level, context.clickedPos, state)
    }

    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(BlockStateProperties.WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    override fun updateShape(
        state: BlockState, level: LevelReader, ticks: ScheduledTickAccess, pos: BlockPos, directionToNeighbour: Direction,
        neighbourPos: BlockPos, neighbourState: BlockState, random: RandomSource,
    ): BlockState {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    companion object {
        private val SHAPE: VoxelShape = Block.column(12.0, 0.0, 16.0)
    }
}

/**
 * An ember vent: the ashen highlands' rock split by cracks still glowing from below. It smokes, spits embers and
 * now and then a gout of something hotter, and it burns the feet of anything that walks on it without care,
 * as magma does.
 */
class EmberVentBlock(properties: BlockBehaviour.Properties) : Block(properties) {
    override fun stepOn(level: Level, pos: BlockPos, onState: BlockState, entity: Entity) {
        if (!entity.isSteppingCarefully && entity is LivingEntity) entity.hurt(level.damageSources().hotFloor(), 1f)
        super.stepOn(level, pos, onState, entity)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val above = pos.above()
        // Capped over, it keeps its smoke in
        if (level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN)) return
        val x = pos.x + 0.5
        val y = pos.y + 1.0
        val z = pos.z + 0.5
        // A thread of smoke going up, a puff or two at a time
        for (i in 0..random.nextInt(2)) {
            level.addAlwaysVisibleParticle(
                ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x + (random.nextDouble() - 0.5) * 0.5, y + random.nextDouble() * 0.4,
                z + (random.nextDouble() - 0.5) * 0.5, 0.0, 0.04 + random.nextDouble() * 0.02, 0.0,
            )
        }
        if (random.nextInt(2) == 0) {
            level.addParticle(RegionData.ASH_EMBER.get(), x + (random.nextDouble() - 0.5) * 0.7, y + 0.05, z + (random.nextDouble() - 0.5) * 0.7, 0.0, 0.0, 0.0)
        }
        if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0)
        if (random.nextInt(5) == 0) {
            level.playLocalSound(x, y, z, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.5f + random.nextFloat() * 0.3f, 0.6f + random.nextFloat() * 0.3f, false)
        }
        if (random.nextInt(12) == 0) {
            level.playLocalSound(x, y, z, SoundEvents.LAVA_AMBIENT, SoundSource.BLOCKS, 0.3f, 0.7f + random.nextFloat() * 0.2f, false)
        }
    }
}

/**
 * An icicle, grown as pointed dripstone grows but of ice: hanging from ledges and the roofs of frozen caves, or
 * standing up from their floors, in pieces that thin to a point. It doesn't grow any longer. An arrow (or
 * anything else thrown hard enough) snaps it; one snapped at its root drops, point first, and shatters where it
 * lands. Meltwater beads at a hanging tip.
 */
class IcicleBlock(properties: BlockBehaviour.Properties) : SpeleothemBlock(Blocks.PACKED_ICE.defaultBlockState(), properties) {
    override fun getStalactiteLandingSound(): Int = LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK

    override fun onBrokenAfterFall(level: Level, pos: BlockPos, entity: FallingBlockEntity) {
        if (!entity.isSilent) level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(entity.blockState))
    }

    override fun onProjectileHit(level: Level, state: BlockState, blockHit: BlockHitResult, projectile: Projectile) {
        val server = level as? ServerLevel ?: return
        val pos = blockHit.blockPos
        if (projectile.mayInteract(server, pos) && projectile.mayBreak(server, pos) && projectile.deltaMovement.length() > SNAP_SPEED) {
            server.destroyBlock(pos, true)
        }
    }

    override fun fallOn(level: Level, state: BlockState, pos: BlockPos, entity: Entity, fallDistance: Double) {
        // A point standing up is as bad to land on as dripstone's
        if (state.getValue(TIP_DIRECTION) == Direction.UP && state.getValue(THICKNESS) == SpeleothemThickness.TIP) {
            entity.causeFallDamage(fallDistance + 2.5, 2f, level.damageSources().stalagmite())
        } else {
            super.fallOn(level, state, pos, entity, fallDistance)
        }
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(14) != 0 || state.getValue(TIP_DIRECTION) != Direction.DOWN || state.getValue(WATERLOGGED)) return
        if (state.getValue(THICKNESS) != SpeleothemThickness.TIP) return
        val offset = state.getOffset(pos)
        level.addParticle(ParticleTypes.DRIPPING_WATER, pos.x + 0.5 + offset.x, pos.y + 0.12, pos.z + 0.5 + offset.z, 0.0, 0.0, 0.0)
    }

    companion object {
        /** How fast (blocks a tick) a thrown thing must be going to snap one. An arrow loosed at full draw is near 3. */
        private const val SNAP_SPEED = 0.6
    }
}

/**
 * Hoarfrost: feathery frost grown over the stone of the frozen peaks' cliffs and caves, on any face. A torch's
 * warmth (block light above 11) melts it, as it does snow.
 */
class HoarfrostBlock(properties: BlockBehaviour.Properties) : MultifaceBlock(properties) {
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (level.getBrightness(LightLayer.BLOCK, pos) > 11) level.removeBlock(pos, false)
    }

    override fun propagatesSkylightDown(state: BlockState): Boolean = state.fluidState.isEmpty
}

/**
 * A cluster of glimmer crystal, grown from any face of the crystal deeps' rock as amethyst grows in a geode:
 * points that glow from within and glint now and then. Mined with a pickaxe, it comes away whole.
 */
class GlimmerClusterBlock(height: Float, width: Float, properties: BlockBehaviour.Properties) :
    AmethystClusterBlock(height, width, properties) {
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(3) != 0) return
        level.addParticle(
            RegionData.CRYSTAL_GLINT.get(), pos.x + 0.2 + random.nextDouble() * 0.6, pos.y + 0.2 + random.nextDouble() * 0.6,
            pos.z + 0.2 + random.nextDouble() * 0.6, 0.0, 0.0, 0.0,
        )
    }
}

/**
 * Glimmer crystal: the deeps' crystal grown into a solid block, clear enough to see light through and lit from
 * within. It chimes when struck, as amethyst does.
 */
class GlimmerCrystalBlock(properties: BlockBehaviour.Properties) : TransparentBlock(properties) {
    override fun onProjectileHit(level: Level, state: BlockState, blockHit: BlockHitResult, projectile: Projectile) {
        if (level.isClientSide) return
        val pos = blockHit.blockPos
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 1f, 0.5f + level.random.nextFloat() * 1.2f)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(4) != 0) return
        val face = Direction.getRandom(random)
        val there = pos.relative(face)
        if (level.getBlockState(there).isSolidRender) return
        level.addParticle(
            RegionData.CRYSTAL_GLINT.get(), pos.x + 0.5 + face.stepX * 0.55 + (random.nextDouble() - 0.5) * (1 - Math.abs(face.stepX)),
            pos.y + 0.5 + face.stepY * 0.55 + (random.nextDouble() - 0.5) * (1 - Math.abs(face.stepY)),
            pos.z + 0.5 + face.stepZ * 0.55 + (random.nextDouble() - 0.5) * (1 - Math.abs(face.stepZ)), 0.0, 0.0, 0.0,
        )
    }
}
