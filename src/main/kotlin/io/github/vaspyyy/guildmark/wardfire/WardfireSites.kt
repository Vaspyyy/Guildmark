package io.github.vaspyyy.guildmark.wardfire

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModVillagers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where Wardfires stand. Every village gets one a short walk from its bell, every lair one a safe way
 * outside its ring, the Hollow Throne one on the road in, and the wild has them here and there. Each
 * one stands cold in a clearing of trodden earth, with a few old standing stones round it.
 */
object WardfireSites {
    /** How far from a village's bell a Wardfire counts as the village's own. */
    const val VILLAGE_RANGE = 48

    /** How far the clearing reaches from the fire; the standing stones sit just outside it. */
    private const val CLEARING = 2

    /** No neighbour updates: the stones stay single posts and nothing pops off as we clear the ground. */
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    /** Whether Wardfires should be placed at all. Defaults to yes if asked before the world's config is read. */
    fun enabled(): Boolean = try {
        GuildmarkConfig.WARDFIRES.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Is there a Wardfire, lit or cold, within [radius] blocks of [pos]? Only land that's loaded is looked at (any more would be read from disk). */
    fun near(level: ServerLevel, pos: BlockPos, radius: Int): Boolean {
        val within = radius.toDouble() * radius
        for (cx in ((pos.x - radius) shr 4)..((pos.x + radius) shr 4)) for (cz in ((pos.z - radius) shr 4)..((pos.z + radius) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) continue
            val found = level.poiManager.getInChunk({ it.`is`(ModVillagers.WARDFIRE_POI.key!!) }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                .anyMatch { it.pos.distSqr(pos) <= within }
            if (found) return true
        }
        return false
    }

    /** The village round [bell] gets a Wardfire, unless it already has one. */
    fun forVillage(level: ServerLevel, bell: BlockPos) {
        if (!near(level, bell, VILLAGE_RANGE)) placeNear(level, bell, 10, 22)
    }

    /**
     * Raises a Wardfire somewhere between [min] and [max] blocks from [center], on the first spot that
     * suits one. Only land that's already loaded is tried, so this never generates chunks. Returns where
     * the Wardfire stands, or null if no spot did.
     */
    fun placeNear(level: ServerLevel, center: BlockPos, min: Int, max: Int): BlockPos? {
        val random = level.random
        repeat(40) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = min + random.nextDouble() * (max - min)
            val x = center.x + (cos(angle) * distance).roundToInt()
            val z = center.z + (sin(angle) * distance).roundToInt()
            if (!loaded(level, x, z)) return@repeat
            val pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
            if (fits(level, pos)) {
                build(level, pos, random)
                return pos
            }
        }
        return null
    }

    /** The land within the clearing and stones of a fire at [x], [z] is loaded. */
    private fun loaded(level: ServerLevel, x: Int, z: Int): Boolean {
        val reach = CLEARING + 2
        for (dx in intArrayOf(-reach, reach)) for (dz in intArrayOf(-reach, reach)) {
            if (level.chunkSource.getChunkNow((x + dx) shr 4, (z + dz) shr 4) == null) return false
        }
        return true
    }

    /**
     * A spot a Wardfire can stand: open and dry, on firm natural ground (not a path or a floor), with
     * nearly all the ground round it within a step of its own, so the clearing isn't a cliff.
     */
    fun fits(level: WorldGenLevel, pos: BlockPos): Boolean {
        if (level.isOutsideBuildHeight(pos.below()) || level.isOutsideBuildHeight(pos.above(2))) return false
        if (!open(level, pos) || !open(level, pos.above())) return false
        val below = pos.below()
        val ground = level.getBlockState(below)
        if (!natural(ground) || !ground.isFaceSturdy(level, below, Direction.UP)) return false
        var columns = 0
        var even = 0
        for (dx in -CLEARING - 1..CLEARING + 1) for (dz in -CLEARING - 1..CLEARING + 1) {
            if (dx * dx + dz * dz > (CLEARING + 1) * (CLEARING + 1)) continue
            columns++
            val top = standing(level, pos.x + dx, pos.z + dz, pos.y) ?: continue
            if (abs(top - pos.y) <= 1) even++
        }
        return even >= columns - 4
    }

    /** Builds a cold Wardfire at [pos] (which [fits] has passed) with its clearing and standing stones. */
    fun build(level: WorldGenLevel, pos: BlockPos, random: RandomSource) {
        // Trodden earth round the fire, ragged at the edge
        for (dx in -CLEARING..CLEARING) for (dz in -CLEARING..CLEARING) {
            val d2 = dx * dx + dz * dz
            if (d2 > CLEARING * CLEARING + 1 || (d2 > 2 && random.nextInt(3) == 0)) continue
            val top = standing(level, pos.x + dx, pos.z + dz, pos.y) ?: continue
            val ground = BlockPos(pos.x + dx, top - 1, pos.z + dz)
            if (!level.getBlockState(ground).`is`(BlockTags.SUBSTRATE_OVERWORLD)) continue
            val loose = random.nextInt(5) == 0 && level.getBlockState(ground.below()).isFaceSturdy(level, ground.below(), Direction.UP)
            level.setBlock(ground, (if (loose) Blocks.GRAVEL else Blocks.COARSE_DIRT).defaultBlockState(), FLAGS)
            if (!level.getBlockState(ground.above()).`is`(Blocks.SNOW)) clear(level, ground.above())
        }
        clear(level, pos)
        clear(level, pos.above())
        level.setBlock(pos, ModBlocks.WARDFIRE.get().defaultBlockState(), FLAGS)

        // Three to five standing stones in a loose ring, weathered down to stumps and posts
        val count = 3 + random.nextInt(3)
        val start = random.nextDouble() * Math.PI * 2
        for (i in 0 until count) {
            val angle = start + Math.PI * 2 * i / count + (random.nextDouble() - 0.5) * 0.7
            val distance = CLEARING + 0.8 + random.nextDouble() * 0.6
            val x = pos.x + (cos(angle) * distance).roundToInt()
            val z = pos.z + (sin(angle) * distance).roundToInt()
            val top = standing(level, x, z, pos.y) ?: continue
            val base = BlockPos(x, top, z)
            // Never across a path or someone's floor
            if (!natural(level.getBlockState(base.below())) || !open(level, base) || !open(level, base.above())) continue
            clear(level, base)
            clear(level, base.above())
            when (random.nextInt(5)) {
                // A fallen stone, lying in the grass
                0 -> level.setBlock(base, Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState(), FLAGS)
                // A stump of a stone
                1 -> level.setBlock(base, stone(random), FLAGS)
                // A stone still standing tall
                2 -> {
                    level.setBlock(base, stone(random), FLAGS)
                    level.setBlock(base.above(), post(random), FLAGS)
                }
                else -> level.setBlock(base, post(random), FLAGS)
            }
        }
    }

    private fun stone(random: RandomSource): BlockState =
        (if (random.nextInt(3) == 0) Blocks.COBBLESTONE else Blocks.MOSSY_COBBLESTONE).defaultBlockState()

    private fun post(random: RandomSource): BlockState = when (random.nextInt(3)) {
        0 -> Blocks.COBBLESTONE_WALL
        1 -> Blocks.MOSSY_STONE_BRICK_WALL
        else -> Blocks.MOSSY_COBBLESTONE_WALL
    }.defaultBlockState()

    /**
     * Where someone would stand in the column at [x], [z], looking a few blocks either side of [nearY]:
     * the lowest open space of two blocks over firm ground, searching down from above. Null if the
     * column has none so close.
     */
    private fun standing(level: WorldGenLevel, x: Int, z: Int, nearY: Int): Int? {
        val pos = BlockPos.MutableBlockPos()
        for (y in nearY + 2 downTo nearY - 3) {
            pos.set(x, y, z)
            if (level.isOutsideBuildHeight(pos)) continue
            if (!open(level, pos) || !open(level, pos.move(Direction.UP))) continue
            pos.set(x, y - 1, z)
            if (!level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) continue
            return y
        }
        return null
    }

    /** Air, or something that grows there and can be cleared away; never water. */
    private fun open(level: WorldGenLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return level.getFluidState(pos).isEmpty && (state.canBeReplaced() || state.`is`(BlockTags.SMALL_FLOWERS))
    }

    /** Ground the land made, rather than a path, a floor or a field. */
    private fun natural(state: BlockState): Boolean =
        state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
            state.`is`(BlockTags.TERRACOTTA) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.SNOW_BLOCK)

    /** Clears grass, flowers or snow from [pos], taking the other half of a tall plant with it. */
    private fun clear(level: WorldGenLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.isAir) return
        if (state.block is DoublePlantBlock) {
            val other = if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) pos.above() else pos.below()
            if (level.getBlockState(other).`is`(state.block)) level.setBlock(other, Blocks.AIR.defaultBlockState(), FLAGS)
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
    }
}
