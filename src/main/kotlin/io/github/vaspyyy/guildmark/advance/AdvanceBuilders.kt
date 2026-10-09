package io.github.vaspyyy.guildmark.advance

import io.github.vaspyyy.guildmark.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Builds each [Advance] into the village. Each returns false if it found nowhere to build. */
object AdvanceBuilders {
    fun build(advance: Advance, level: ServerLevel, center: BlockPos, radius: Int): Boolean = when (advance) {
        Advance.LAMP_POSTS -> lampPosts(level, center, radius)
        Advance.PALISADE -> palisade(level, center, radius)
        Advance.ARCHER_TOWER -> archerTower(level, center)
    }

    /** First free block above the ground at this column. */
    private fun surface(level: ServerLevel, x: Int, z: Int): BlockPos =
        level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))

    private fun free(level: ServerLevel, pos: BlockPos): Boolean =
        level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty

    private fun solidGround(level: ServerLevel, top: BlockPos): Boolean {
        val ground = top.below()
        val state = level.getBlockState(ground)
        return !state.`is`(Blocks.DIRT_PATH) && level.getFluidState(ground).isEmpty && state.isFaceSturdy(level, ground, Direction.UP)
    }

    private fun place(level: ServerLevel, pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, Block.UPDATE_ALL)
    }

    /** Fence posts topped with lanterns beside the village paths, spaced out. */
    private fun lampPosts(level: ServerLevel, center: BlockPos, radius: Int): Boolean {
        val paths = mutableListOf<BlockPos>()
        for (dx in -radius..radius) for (dz in -radius..radius) {
            if (dx * dx + dz * dz > radius * radius) continue
            val top = surface(level, center.x + dx, center.z + dz)
            if (level.getBlockState(top.below()).`is`(Blocks.DIRT_PATH)) paths.add(top)
        }
        paths.shuffle()

        val posts = mutableListOf<BlockPos>()
        for (path in paths) {
            if (posts.size >= 20) break
            if (posts.any { it.distSqr(path) < 10.0 * 10.0 }) continue
            val spot = Direction.Plane.HORIZONTAL.map { path.relative(it) }.firstOrNull { side ->
                val top = surface(level, side.x, side.z)
                abs(top.y - path.y) <= 1 && solidGround(level, top) && (0..3).all { free(level, top.above(it)) }
            } ?: continue
            val base = surface(level, spot.x, spot.z)
            for (i in 0..2) place(level, base.above(i), Blocks.SPRUCE_FENCE.defaultBlockState())
            place(level, base.above(3), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
            posts.add(path)
        }
        return posts.isNotEmpty()
    }

    /** A ring of upright logs around the village; paths through it stay open as gates. */
    private fun palisade(level: ServerLevel, center: BlockPos, radius: Int): Boolean {
        val log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y)
        val columns = LinkedHashSet<Pair<Int, Int>>()
        val steps = (radius * 2 * Math.PI * 2).toInt()
        for (i in 0 until steps) {
            val angle = 2 * Math.PI * i / steps
            columns.add(Pair(center.x + Math.round(radius * cos(angle)).toInt(), center.z + Math.round(radius * sin(angle)).toInt()))
        }
        var placed = 0
        for ((x, z) in columns) {
            val top = surface(level, x, z)
            if (abs(top.y - center.y) > 10 || !solidGround(level, top)) continue
            if (!(0..2).all { free(level, top.above(it)) }) continue
            for (i in 0..2) place(level, top.above(i), log)
            placed++
        }
        return placed > 0
    }

    /** A 5x5 stone tower with a ladder, a lookout floor and an Archer Post that shoots monsters. */
    private fun archerTower(level: ServerLevel, center: BlockPos): Boolean {
        val random = level.random
        repeat(80) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 8 + random.nextInt(10)
            val x = center.x + (distance * cos(angle)).toInt()
            val z = center.z + (distance * sin(angle)).toInt()
            val tops = (-2..2).flatMap { dx -> (-2..2).map { dz -> surface(level, x + dx, z + dz) } }
            val base = tops.maxOf { it.y }
            if (tops.any { base - it.y > 2 || !solidGround(level, it) }) return@repeat
            if (tops.any { top -> (top.y until base + 11).any { !free(level, BlockPos(top.x, it, top.z)) } }) return@repeat

            val door = Direction.getApproximateNearest((center.x - x).toFloat(), 0f, (center.z - z).toFloat())
            buildTower(level, BlockPos(x, base, z), tops, door)
            return true
        }
        return false
    }

    private fun buildTower(level: ServerLevel, base: BlockPos, tops: List<BlockPos>, door: Direction) {
        val random = level.random
        fun wall(): BlockState = if (random.nextInt(5) == 0) Blocks.MOSSY_STONE_BRICKS.defaultBlockState() else Blocks.STONE_BRICKS.defaultBlockState()

        // Foundation down to the ground on uneven terrain
        for (top in tops) for (y in top.y until base.y) place(level, BlockPos(top.x, y, top.z), Blocks.COBBLESTONE.defaultBlockState())

        val ladderSide = door.opposite
        val ladder = base.relative(ladderSide)
        for (dy in 0..7) for (dx in -2..2) for (dz in -2..2) {
            val pos = base.offset(dx, dy, dz)
            val edge = abs(dx) == 2 || abs(dz) == 2
            place(level, pos, if (edge) wall() else Blocks.AIR.defaultBlockState())
        }
        // Doorway facing the village
        place(level, base.relative(door, 2), Blocks.AIR.defaultBlockState())
        place(level, base.relative(door, 2).above(), Blocks.AIR.defaultBlockState())

        // Lookout floor with a hole for the ladder
        for (dx in -2..2) for (dz in -2..2) place(level, base.offset(dx, 8, dz), Blocks.SPRUCE_PLANKS.defaultBlockState())
        val ladderState = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, door)
        for (dy in 0..8) place(level, ladder.above(dy), ladderState)

        // Crenellations around the top, lanterns on the corners
        for (dx in -2..2) for (dz in -2..2) {
            if (abs(dx) != 2 && abs(dz) != 2) continue
            val pos = base.offset(dx, 9, dz)
            val corner = abs(dx) == 2 && abs(dz) == 2
            if (corner) {
                place(level, pos, Blocks.STONE_BRICK_WALL.defaultBlockState())
                place(level, pos.above(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
            } else if ((dx + dz) % 2 == 0) {
                place(level, pos, wall())
            }
        }
        place(level, base.above(9), ModBlocks.ARCHER_POST.get().defaultBlockState())
    }
}
