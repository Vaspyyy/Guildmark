package io.github.vaspyyy.guildmark.island

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A gull's nest on a ledge or a cliff top: a shallow cup of twigs, dry grass and down, holding up to three eggs. Take
 * one with an empty hand (and every gull round about will take it badly: see [Gull.robbed]), or put one back. The gulls
 * keep their nests: while any are about, one lays now and then, up to a clutch of three, and while the colony's thin an
 * egg hatches into a young gull.
 */
class GullNestBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(EGGS, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(EGGS)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val below = pos.below()
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
    }

    override fun updateShape(
        state: BlockState, level: LevelReader, ticks: ScheduledTickAccess, pos: BlockPos, direction: Direction,
        neighborPos: BlockPos, neighborState: BlockState, random: RandomSource,
    ): BlockState =
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) Blocks.AIR.defaultBlockState()
        else super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val eggs = state.getValue(EGGS)
        if (eggs == 0) return InteractionResult.PASS
        if (level is ServerLevel) {
            level.setBlock(pos, state.setValue(EGGS, eggs - 1), Block.UPDATE_ALL)
            val egg = ItemStack(IslandRegistry.GULL_EGG.get())
            player.inventory.placeItemBackInInventory(egg, Prediction.SERVER_ONLY)
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.5f, 1.4f + level.random.nextFloat() * 0.2f)
            Gull.robbed(level, pos, player)
        }
        return InteractionResult.SUCCESS
    }

    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hitResult: BlockHitResult,
    ): InteractionResult {
        if (!stack.`is`(IslandRegistry.GULL_EGG.get())) return InteractionResult.TRY_WITH_EMPTY_HAND
        val eggs = state.getValue(EGGS)
        if (eggs >= MAX_EGGS) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level is ServerLevel) {
            level.setBlock(pos, state.setValue(EGGS, eggs + 1), Block.UPDATE_ALL)
            stack.consume(1, player)
            level.playSound(null, pos, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.6f, 1.3f)
        }
        return InteractionResult.SUCCESS
    }

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val eggs = state.getValue(EGGS)
        val gulls = level.getEntitiesOfClass(Gull::class.java, AABB(pos).inflate(KEEP_RANGE)) { it.isAlive }
        when {
            // A gull about the nest lays, now and then, up to a full clutch
            eggs < MAX_EGGS && gulls.isNotEmpty() && random.nextInt(LAY) == 0 -> level.setBlock(pos, state.setValue(EGGS, eggs + 1), Block.UPDATE_ALL)
            // While the colony's thin, an egg hatches
            eggs > 0 && gulls.size < THIN && random.nextInt(HATCH) == 0 -> {
                level.setBlock(pos, state.setValue(EGGS, eggs - 1), Block.UPDATE_ALL)
                val chick = IslandRegistry.GULL.get().create(level, EntitySpawnReason.BREEDING) ?: return
                chick.snapTo(pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, random.nextFloat() * 360f, 0f)
                chick.hatched(pos)
                level.addFreshEntity(chick)
                level.playSound(null, pos, SoundEvents.TURTLE_EGG_HATCH, SoundSource.BLOCKS, 0.7f, 1.4f)
                level.sendParticles(ParticleTypes.EGG_CRACK, pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, 10, 0.2, 0.1, 0.2, 0.02)
            }
        }
    }

    companion object {
        val EGGS: IntegerProperty = IntegerProperty.create("eggs", 0, 3)
        const val MAX_EGGS = 3

        private val SHAPE: VoxelShape = column(14.0, 0.0, 4.0)
        /** How far round a nest its gulls are counted. */
        private const val KEEP_RANGE = 16.0
        /** One random tick in this many lays an egg, or hatches one while there are fewer than [THIN] gulls about. */
        private const val LAY = 6
        private const val HATCH = 30
        private const val THIN = 10
    }
}
