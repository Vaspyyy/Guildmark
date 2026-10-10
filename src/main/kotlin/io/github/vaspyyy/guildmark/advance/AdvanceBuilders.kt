package io.github.vaspyyy.guildmark.advance

import io.github.vaspyyy.guildmark.guild.GuildHall
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

/** Builds each [Advance] into the village. */
object AdvanceBuilders {
    /** Returns null once building has started, or the translation key explaining why it couldn't. */
    fun build(advance: Advance, level: ServerLevel, center: BlockPos, radius: Int, anchor: BlockPos): String? {
        val built = when (advance) {
            Advance.LAMP_POSTS -> lampPosts(level, center, radius)
            Advance.GUILD_HALL -> GuildHall.build(level, center, radius, anchor)
            Advance.TRADE_ROAD -> error("Trade Roads are surveyed by Advances.startTradeRoad")
            Advance.PALISADE -> palisade(level, center, radius)
            Advance.ARCHER_TOWER -> archerTower(level, center)
        }
        return if (built) null else "message.guildmark.advance_no_room"
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

    /**
     * Fence posts topped with lanterns beside the village paths, spaced out. Villages without dirt paths
     * (desert streets are plain sand) get them on open ground between the houses instead.
     */
    private fun lampPosts(level: ServerLevel, center: BlockPos, radius: Int): Boolean {
        val paths = mutableListOf<BlockPos>()
        val open = mutableListOf<BlockPos>()
        for (dx in -radius..radius) for (dz in -radius..radius) {
            if (dx * dx + dz * dz > radius * radius) continue
            val top = surface(level, center.x + dx, center.z + dz)
            if (level.getBlockState(top.below()).`is`(Blocks.DIRT_PATH)) paths.add(top)
            else if (dx * dx + dz * dz >= 36 && openGround(level, top)) open.add(top)
        }
        paths.shuffle()
        open.shuffle()

        val posts = mutableListOf<BlockPos>()
        fun spaced(pos: BlockPos) = posts.none { it.distSqr(pos) < 10.0 * 10.0 }
        fun post(base: BlockPos) {
            for (i in 0..2) place(level, base.above(i), Blocks.SPRUCE_FENCE.defaultBlockState())
            place(level, base.above(3), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
            posts.add(base)
        }
        for (path in paths) {
            if (posts.size >= 20) break
            if (!spaced(path)) continue
            val spot = Direction.Plane.HORIZONTAL.map { path.relative(it) }.firstOrNull { side ->
                val top = surface(level, side.x, side.z)
                abs(top.y - path.y) <= 1 && solidGround(level, top) && (0..3).all { free(level, top.above(it)) }
            } ?: continue
            post(surface(level, spot.x, spot.z))
        }
        if (paths.isEmpty()) {
            for (spot in open) {
                if (posts.size >= 12) break
                if (spaced(spot)) post(spot)
            }
        }
        return posts.isNotEmpty()
    }

    private val NATURAL_GROUND = setOf(
        Blocks.SAND, Blocks.RED_SAND, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL,
        Blocks.SNOW_BLOCK, Blocks.GRAVEL, Blocks.TERRACOTTA, Blocks.MUD,
    )

    /**
     * Natural, level ground with nothing built within a block of it: somewhere a lamp post won't block a
     * door or sit on a roof.
     */
    private fun openGround(level: ServerLevel, top: BlockPos): Boolean {
        if (level.getBlockState(top.below()).block !in NATURAL_GROUND || !solidGround(level, top)) return false
        for (dx in -1..1) for (dz in -1..1) {
            val side = surface(level, top.x + dx, top.z + dz)
            if (abs(side.y - top.y) > 1) return false
            for (dy in 0..3) if (!free(level, top.offset(dx, dy, dz))) return false
        }
        return true
    }

    /**
     * A log wall that hugs the village's walkable land. Starting at the bell, it spreads over ground a
     * mob could walk (steps of one block, no water) out to [radius]; water and cliffs stop the spread
     * and need no wall. Walls go only where walkable ground carries on past the edge, and paths
     * crossing that edge stay open as gates.
     */
    private fun palisade(level: ServerLevel, center: BlockPos, radius: Int): Boolean {
        val log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y)
        val reach = radius + 8
        val tops = HashMap<Long, BlockPos>()
        fun top(x: Int, z: Int): BlockPos = tops.getOrPut(BlockPos.asLong(x, 0, z)) { surface(level, x, z) }
        fun water(top: BlockPos) = !level.getFluidState(top.below()).isEmpty
        fun walkable(from: BlockPos, to: BlockPos) = !water(to) && abs(to.y - from.y) <= 1
        fun inside(x: Int, z: Int): Boolean {
            val dx = x - center.x
            val dz = z - center.z
            return dx * dx + dz * dz <= reach * reach
        }

        // Flood fill the village's walkable land
        val start = top(center.x, center.z)
        val area = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        area.add(BlockPos.asLong(start.x, 0, start.z))
        queue.add(start)
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            for (direction in Direction.Plane.HORIZONTAL) {
                val x = here.x + direction.stepX
                val z = here.z + direction.stepZ
                val key = BlockPos.asLong(x, 0, z)
                if (key in area || !inside(x, z)) continue
                val next = top(x, z)
                if (!walkable(here, next)) continue
                area.add(key)
                queue.add(next)
            }
        }

        // Wall the edge cells where walkable ground continues outward
        var placed = 0
        for (key in area) {
            val x = BlockPos.getX(key)
            val z = BlockPos.getZ(key)
            val here = top(x, z)
            val open = Direction.Plane.HORIZONTAL.any { direction ->
                val nx = x + direction.stepX
                val nz = z + direction.stepZ
                !inside(nx, nz) && walkable(here, top(nx, nz))
            }
            if (!open || !solidGround(level, here)) continue
            if (!(0..2).all { free(level, here.above(it)) }) continue
            for (i in 0..2) place(level, here.above(i), log)
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
