package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.UnaryOperator

/**
 * The herb (living-world/cook/design.md 6.7, 11.1): a crop of four ages, a sprout to a flowering bush, planted from a
 * sprig on farmland. It grows as a crop does, paced by the seasons ([HomesteadKitchens] puts it in
 * [io.github.vaspyyy.guildmark.season.SeasonCrops]), takes bone meal like beetroot, and gives its sprigs two ways: a
 * right-click on the ripe plant picks one or two and leaves a sprout that grows again, and a break gives one sprig, or two
 * when ripe (`loot_table/blocks/herb.json`). A ripe herb sheds a few lilac motes of pollen, so a gardener can see it from
 * across the bed.
 */
class HerbBlock(properties: Properties) : CropBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(AGE, 0))
    }

    override fun getAgeProperty(): IntegerProperty = AGE

    override fun getMaxAge(): Int = MAX

    override fun getBaseSeedId(): ItemLike = CookRegistry.HERB_SPRIG.get()

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPES[getAge(state)]

    /** Bone meal brings it on by a stage or none, as it does a beetroot (a third of the usual two to five). */
    override fun getBonemealAgeIncrease(level: Level): Int = super.getBonemealAgeIncrease(level) / 3

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AGE)
    }

    /** A click on the ripe plant picks it (an unripe one lets the click through, so bone meal and seeds still work). */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (!isMaxAge(state)) return InteractionResult.PASS
        if (level is ServerLevel) pick(level, pos, state, player)
        return InteractionResult.SUCCESS
    }

    /** Ripe, the herb lets go of a mote of pollen now and then, of the colour of its flowers. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (!isMaxAge(state) || random.nextInt(POLLEN_ODDS) != 0) return
        level.addParticle(
            POLLEN,
            pos.x + 0.15 + random.nextDouble() * 0.7, pos.y + 0.35 + random.nextDouble() * 0.3, pos.z + 0.15 + random.nextDouble() * 0.7,
            (random.nextDouble() - 0.5) * 0.01, 0.012 + random.nextDouble() * 0.01, (random.nextDouble() - 0.5) * 0.01,
        )
    }

    /** A thing walking through a ripe herb shakes a little pollen off it (the plant has no collision, so this is the walker's own brush). */
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity, applier: InsideBlockEffectApplier, isPrecise: Boolean) {
        super.entityInside(state, level, pos, entity, applier, isPrecise)
        if (level is ServerLevel && isMaxAge(state) && (entity.xo != entity.x || entity.zo != entity.z) && level.random.nextInt(BRUSH_ODDS) == 0) {
            level.sendParticles(POLLEN, pos.x + 0.5, pos.y + 0.55, pos.z + 0.5, 3, 0.25, 0.1, 0.25, 0.0)
        }
    }

    companion object {
        const val MAX = 3
        val AGE: IntegerProperty = BlockStateProperties.AGE_3

        /** The age a picked herb goes back to: a sprout with a stem, not a seed, so a gardener's next sprig is two stages away. */
        const val PICKED_AGE = 1

        /** Lilac, like the flowers of the ripe herb's picture. */
        private val POLLEN = DustParticleOptions(0xC8B0E0, 0.55f)
        private const val POLLEN_ODDS = 22
        private const val BRUSH_ODDS = 6

        private val SHAPES: Array<VoxelShape> = Array(MAX + 1) { age -> Block.box(0.0, 0.0, 0.0, 16.0, 4.0 + age * 3.0, 16.0) }

        /** How the block is made (hardness, sound, light...): the owner's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<BlockBehaviour.Properties> = UnaryOperator {
            it.mapColor(MapColor.PLANT).noCollision().randomTicks().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.POPPED)
        }

        /**
         * Picks the ripe herb at [pos]: one or two sprigs on the ground, the sweet-berry pick sound, a puff of leaves, and the
         * plant back to [PICKED_AGE]. The number of sprigs is the one that fell. Nothing happens to a herb that is not ripe.
         */
        fun pick(level: ServerLevel, pos: BlockPos, state: BlockState, player: Player?): Int {
            val block = state.block as? HerbBlock ?: return 0
            if (!block.isMaxAge(state)) return 0
            val count = 1 + level.random.nextInt(2)
            Block.popResource(level, pos, ItemStack(CookRegistry.HERB_SPRIG.get(), count))
            level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0f, 0.8f + level.random.nextFloat() * 0.4f)
            val picked = state.setValue(AGE, PICKED_AGE)
            level.setBlock(pos, picked, Block.UPDATE_CLIENTS)
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, picked))
            level.sendParticles(ParticleTypes.COMPOSTER, pos.x + 0.5, pos.y + 0.45, pos.z + 0.5, 5, 0.25, 0.15, 0.25, 0.0)
            level.sendParticles(POLLEN, pos.x + 0.5, pos.y + 0.55, pos.z + 0.5, 6, 0.25, 0.1, 0.25, 0.0)
            return count
        }
    }
}
