package io.github.vaspyyy.guildmark.field

import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.RotationSegment
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A hill troll's camp: a trampled clearing round a big fire on a spit, the troll's nest of hay and moss
 * on one side with what it's stolen, mossy boulders round the edge, and old bones everywhere.
 */
object TrollCamp {
    /** How far the clearing reaches from the fire. */
    const val RADIUS = 7
    /** How far anything of the camp reaches from the fire: the boulders round its edge. */
    const val REACH = RADIUS + 6

    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    /** Where the troll sleeps: in its nest, a few blocks from the fire on the [facing] side. */
    fun bed(fire: BlockPos, facing: Direction): BlockPos = fire.relative(facing, 3)

    /** Builds the camp round [fire] (the open block at ground level), with its nest on the [facing] side. */
    fun build(level: ServerLevel, fire: BlockPos, facing: Direction, random: RandomSource) {
        val y = fire.y
        // The clearing: plants and snow gone, the ground trodden flat and bare
        for (dx in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            if (d > RADIUS + 0.5) continue
            val edge = d > RADIUS - 1.5
            if (edge && random.nextInt(3) == 0) continue
            for (h in 0..4) {
                val pos = fire.offset(dx, h, dz)
                val state = level.getBlockState(pos)
                if (state.canBeReplaced() && !state.isAir && state.fluidState.isEmpty) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
            }
            // Level the ground: fill a dip, cut a hump
            val ground = fire.offset(dx, -1, dz)
            if (level.getBlockState(ground).isAir || level.getBlockState(ground).canBeReplaced()) {
                var down = ground
                while (down.y > ground.y - 3 && (level.getBlockState(down).isAir || level.getBlockState(down).canBeReplaced())) {
                    level.setBlock(down, Blocks.DIRT.defaultBlockState(), FLAGS)
                    down = down.below()
                }
            }
            // Cut back a bank or a bush as high as the troll stands
            for (h in 0..4) {
                val pos = fire.offset(dx, h, dz)
                val state = level.getBlockState(pos)
                if (!state.isAir && (natural(state) || state.`is`(BlockTags.LEAVES))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
            }
            if (natural(level.getBlockState(ground)) && random.nextInt(if (edge) 3 else 5) != 0) {
                put(level, ground, pick(random, Blocks.COARSE_DIRT to 5, Blocks.PODZOL to 2, Blocks.ROOTED_DIRT to 1, Blocks.GRAVEL to 1))
            }
        }

        // The fire: a ring of stones, a spit of posts with a log across
        put(level, fire.below(), Blocks.GRAVEL.defaultBlockState())
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), FLAGS)
        for (d in Direction.Plane.HORIZONTAL) {
            val side = fire.relative(d)
            put(level, side.below(), Blocks.GRAVEL.defaultBlockState())
            val corner = side.relative(d.clockWise)
            level.setBlock(corner, pick(random, Blocks.MOSSY_COBBLESTONE to 2, Blocks.COBBLESTONE to 1), FLAGS)
        }
        val across = facing.clockWise
        for (s in intArrayOf(-2, 2)) {
            val post = fire.relative(across, s)
            level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), FLAGS)
            level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), FLAGS)
        }
        for (s in -2..2) level.setBlock(fire.relative(across, s).above(2),
            Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, across.axis), FLAGS)
        level.setBlock(fire.relative(across, 3).relative(facing.opposite), Blocks.CAULDRON.defaultBlockState(), FLAGS)

        // The nest: hay and moss heaped on the far side, with its plunder
        val nest = bed(fire, facing)
        for (a in -2..2) for (b in -1..2) {
            val pos = nest.relative(across, a).relative(facing, b)
            if (abs(a) == 2 && (b == -1 || b == 2)) continue
            val heap = b == 2 || abs(a) == 2
            level.setBlock(pos.below(), Blocks.HAY_BLOCK.defaultBlockState(), FLAGS)
            if (heap && random.nextInt(3) != 0) level.setBlock(pos, Blocks.HAY_BLOCK.defaultBlockState(), FLAGS)
            else if (random.nextInt(3) == 0) level.setBlock(pos, Blocks.MOSS_CARPET.defaultBlockState(), FLAGS)
        }
        val hoard = nest.relative(facing, 3).relative(across, 1)
        val barrel = nest.relative(facing, 3).relative(across, -1)
        val heap = nest.relative(facing, 3).relative(across, -2)
        for (pos in listOf(hoard, barrel, heap)) support(level, pos)
        hoardChest(level, hoard, facing.opposite, random)
        level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), FLAGS)
        (level.getBlockEntity(barrel) as? BarrelBlockEntity)?.setItem(random.nextInt(27), ItemStack(ModItems.GUILD_MARK.get(), 3 + random.nextInt(6)))
        level.setBlock(heap, pick(random, Blocks.HAY_BLOCK to 1, Blocks.COBBLESTONE to 1), FLAGS)

        // Boulders round the edge, mossy and half sunk
        val start = random.nextDouble() * Math.PI * 2
        for (n in 0 until 6) {
            val angle = start + Math.PI * 2 * n / 6 + random.nextDouble() * 0.4
            val r = RADIUS - 0.5 + random.nextDouble() * 1.5
            val base = fire.offset((cos(angle) * r).roundToInt(), 0, (sin(angle) * r).roundToInt())
            if (base.closerThan(nest, 3.5)) continue
            boulder(level, base, random)
        }

        // Bones and skulls of what it's eaten
        repeat(9) {
            val angle = random.nextDouble() * Math.PI * 2
            val r = 2.0 + random.nextDouble() * (RADIUS - 2.5)
            val pos = fire.offset((cos(angle) * r).roundToInt(), 0, (sin(angle) * r).roundToInt())
            if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return@repeat
            if (pos.closerThan(nest, 3.0) || pos.closerThan(fire, 1.6)) return@repeat
            when (random.nextInt(5)) {
                0, 1 -> level.setBlock(pos, Blocks.BONE_BLOCK.defaultBlockState()
                    .setValue(RotatedPillarBlock.AXIS, if (random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z), FLAGS)
                2 -> level.setBlock(pos, Blocks.SKELETON_SKULL.defaultBlockState()
                    .setValue(SkullBlock.ROTATION, RotationSegment.convertToSegment(random.nextFloat() * 360f)), FLAGS)
                3 -> level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), FLAGS)
                else -> level.setBlock(pos, Blocks.BROWN_MUSHROOM.defaultBlockState(), FLAGS)
            }
        }
    }

    /** A boulder of two to five stones heaped on the ground, moss on top. */
    private fun boulder(level: ServerLevel, base: BlockPos, random: RandomSource) {
        val stones = 2 + random.nextInt(4)
        val placed = ArrayList<BlockPos>()
        var column = base
        repeat(stones) {
            // Each stone comes to rest on whatever's under it (never in water, never hanging in the air)
            val at = rest(level, column, base.y)
            if (at != null) {
                level.setBlock(at, pick(random, Blocks.MOSSY_COBBLESTONE to 3, Blocks.STONE to 2, Blocks.ANDESITE to 2, Blocks.COBBLESTONE to 1), FLAGS)
                placed.add(at)
            }
            if (random.nextInt(3) != 0) column = column.relative(Direction.Plane.HORIZONTAL.getRandomDirection(random))
        }
        for (pos in placed) {
            val above = pos.above()
            if (level.getBlockState(above).isAir && random.nextInt(2) == 0) level.setBlock(above, Blocks.MOSS_CARPET.defaultBlockState(), FLAGS)
        }
    }

    /** Where a stone dropped in the column at [column] would come to rest, near [y]: on firm ground, out of water, at most two high. */
    private fun rest(level: ServerLevel, column: BlockPos, y: Int): BlockPos? {
        var at = column.atY(y + 2)
        while (at.y > y - 3) {
            val below = at.below()
            val state = level.getBlockState(below)
            if (!state.canBeReplaced()) {
                val here = level.getBlockState(at)
                val dry = here.fluidState.isEmpty && state.fluidState.isEmpty
                return if (dry && here.canBeReplaced() && state.isFaceSturdy(level, below, Direction.UP)) at else null
            }
            at = below
        }
        return null
    }

    /** Fills any hollow up to three deep under [pos] with earth, so what stands there stands on the ground. */
    private fun support(level: ServerLevel, pos: BlockPos) {
        var down = pos.below()
        while (down.y >= pos.y - 3 && level.getBlockState(down).canBeReplaced()) {
            level.setBlock(down, Blocks.DIRT.defaultBlockState(), FLAGS)
            down = down.below()
        }
    }

    /** What the troll took from the road: a chest of travellers' goods (the barrel beside it holds a little coin). */
    private fun hoardChest(level: ServerLevel, pos: BlockPos, facing: Direction, random: RandomSource) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), FLAGS)
        val chest = level.getBlockEntity(pos) as? ChestBlockEntity ?: return
        chest.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong())
    }

    /** Ground laid at [pos]; gravel over a hollow would drop into it, so there it's cobblestone instead. */
    private fun put(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val steady = if (state.block is FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) Blocks.COBBLESTONE.defaultBlockState() else state
        level.setBlock(pos, steady, FLAGS)
    }

    /** Ground and plants that may be cleared or trodden over: never anything a player built. */
    private fun natural(state: BlockState): Boolean =
        state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.SAND) ||
            state.`is`(Blocks.GRAVEL) || state.`is`(BlockTags.SNOW) || state.`is`(Blocks.CLAY)

    /**
     * Whether [state] could be the wild as it grew: ground, rock, snow and ice, trees and wild plants. Never
     * anything that holds things, and never a crop, a path or anything else people make.
     */
    fun wild(state: BlockState): Boolean {
        if (state.hasBlockEntity() || state.block is CropBlock) return false
        if (state.isAir || state.canBeReplaced() || natural(state)) return true
        return state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.FLOWERS) ||
            state.`is`(BlockTags.SAPLINGS) || state.`is`(BlockTags.ICE) || state.`is`(BlockTags.TERRACOTTA) ||
            state.`is`(BlockTags.ORES) ||
            state.block is VegetationBlock || state.`is`(Blocks.SANDSTONE) || state.`is`(Blocks.RED_SANDSTONE) ||
            state.`is`(Blocks.CALCITE) || state.`is`(Blocks.DRIPSTONE_BLOCK) || state.`is`(Blocks.POINTED_DRIPSTONE) ||
            state.`is`(Blocks.MOSSY_COBBLESTONE) || state.`is`(Blocks.MOSS_CARPET) || state.`is`(Blocks.CACTUS) ||
            state.`is`(Blocks.SUGAR_CANE) || state.`is`(Blocks.BAMBOO) || state.`is`(Blocks.PUMPKIN) || state.`is`(Blocks.MELON) ||
            state.`is`(Blocks.BROWN_MUSHROOM_BLOCK) || state.`is`(Blocks.RED_MUSHROOM_BLOCK) || state.`is`(Blocks.MUSHROOM_STEM)
    }

    private fun pick(random: RandomSource, vararg options: Pair<Block, Int>): BlockState {
        var roll = random.nextInt(options.sumOf { it.second })
        for ((block, weight) in options) {
            roll -= weight
            if (roll < 0) return block.defaultBlockState()
        }
        return options[0].first.defaultBlockState()
    }
}
