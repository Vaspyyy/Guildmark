package io.github.vaspyyy.guildmark.advance

import io.github.vaspyyy.guildmark.advance.AdvanceBuilders.free
import io.github.vaspyyy.guildmark.advance.AdvanceBuilders.naturalGround
import io.github.vaspyyy.guildmark.advance.AdvanceBuilders.place
import io.github.vaspyyy.guildmark.advance.AdvanceBuilders.solidGround
import io.github.vaspyyy.guildmark.advance.AdvanceBuilders.surface
import io.github.vaspyyy.guildmark.guild.Holdings
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.village.live.build.BuildSink
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.RotationSegment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The later, grander advances: a market square, a stone keep (stone walls reuse the palisade's plan). */
object GrandBuilders {
    private val JOB_BLOCKS = listOf(
        Blocks.SMOKER, Blocks.FLETCHING_TABLE, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE, Blocks.BARREL, Blocks.COMPOSTER,
        Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.BREWING_STAND,
    )
    private val CANOPIES = listOf(
        DyeColor.RED to DyeColor.WHITE, DyeColor.BLUE to DyeColor.YELLOW, DyeColor.GREEN to DyeColor.WHITE,
        DyeColor.ORANGE to DyeColor.BROWN, DyeColor.PURPLE to DyeColor.YELLOW, DyeColor.CYAN to DyeColor.WHITE,
    )
    private val GOODS = listOf(Blocks.HAY_BLOCK, Blocks.PUMPKIN, Blocks.MELON, Blocks.BARREL, Blocks.BEEHIVE, Blocks.DRIED_KELP_BLOCK)

    /**
     * Market stalls around the bell: four fence posts, a striped wool canopy, goods stacked at the back and
     * a workstation at the counter. A new villager moves in for each stall and takes up its trade.
     */
    fun market(level: ServerLevel, center: BlockPos): Boolean {
        val random = level.random
        val stalls = mutableListOf<BlockPos>()
        repeat(120) {
            if (stalls.size >= 5) return@repeat
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 6 + random.nextInt(12)
            val x = center.x + (distance * cos(angle)).toInt()
            val z = center.z + (distance * sin(angle)).toInt()
            if (stalls.any { abs(it.x - x) < 6 && abs(it.z - z) < 6 }) return@repeat
            val tops = (-1..1).flatMap { dx -> (-1..1).map { dz -> surface(level, x + dx, z + dz) } }
            val base = tops[4].y
            if (tops.any { it.y != base || !solidGround(level, it) || !naturalGround(level, it) }) return@repeat
            if (tops.any { top -> (0..3).any { !free(level, top.above(it)) } }) return@repeat
            val facing = Direction.getApproximateNearest((center.x - x).toFloat(), 0f, (center.z - z).toFloat())
            buildStall(level, BlockPos(x, base, z), facing, stalls.size)
            stalls.add(BlockPos(x, base, z))
        }
        if (stalls.isEmpty()) return false
        // Each stall's keeper moves in once it stands (at once, unless a crew is to build it: village/live/build/BuildSink)
        for (stall in stalls) BuildSink.afterwards(level, "stall_keeper", stall.relative(Direction.Plane.HORIZONTAL.getRandomDirection(random), 2))
        return true
    }

    /** A villager moves in at [spot] beside a stall, on the ground there, to take up its trade. */
    fun keeper(level: ServerLevel, spot: BlockPos) {
        EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT)?.let { villager ->
            val ground = surface(level, spot.x, spot.z)
            villager.snapTo(spot.x + 0.5, ground.y.toDouble(), spot.z + 0.5)
            villager.finalizeSpawn(level, level.getCurrentDifficultyAt(ground), EntitySpawnReason.EVENT, null)
            level.addFreshEntity(villager)
        }
    }

    private fun buildStall(level: ServerLevel, base: BlockPos, facing: Direction, index: Int) {
        val random = level.random
        val (stripe, plain) = CANOPIES[(index + random.nextInt(CANOPIES.size)) % CANOPIES.size]
        val fence = Blocks.SPRUCE_FENCE.defaultBlockState()
        for (dx in listOf(-1, 1)) for (dz in listOf(-1, 1)) {
            for (dy in 0..1) place(level, base.offset(dx, dy, dz), fence)
        }
        for (dx in -1..1) for (dz in -1..1) {
            val along = if (facing.axis == Direction.Axis.X) dz else dx
            place(level, base.offset(dx, 2, dz), Blocks.WOOL.pick(if (abs(along) == 1) stripe else plain).defaultBlockState())
        }
        // Counter at the front, goods at the back
        place(level, base.relative(facing), JOB_BLOCKS[random.nextInt(JOB_BLOCKS.size)].defaultBlockState())
        val back = base.relative(facing.opposite)
        place(level, back, GOODS[random.nextInt(GOODS.size)].defaultBlockState())
        place(level, base.above(1).relative(facing.opposite), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
    }

    /**
     * A three-storey stone keep near the village: thick walls with arrow slits, floors joined by a ladder,
     * battlements and Archer Posts on the roof, and the holding guild's banners over the door.
     */
    fun keep(level: ServerLevel, center: BlockPos): Boolean {
        val random = level.random
        repeat(200) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 10 + random.nextInt(20)
            val x = center.x + (distance * cos(angle)).toInt()
            val z = center.z + (distance * sin(angle)).toInt()
            val tops = (-4..4).flatMap { dx -> (-4..4).map { dz -> surface(level, x + dx, z + dz) } }
            val base = tops.maxOf { it.y }
            // Paths may run under it (foundations fill in), but not houses
            if (tops.any { base - it.y > 4 || !naturalGround(level, it) || (!solidGround(level, it) && !level.getBlockState(it.below()).`is`(Blocks.DIRT_PATH)) }) return@repeat
            if (tops.any { top -> (top.y until base + 18).any { !free(level, BlockPos(top.x, it, top.z)) } }) return@repeat
            val door = Direction.getApproximateNearest((center.x - x).toFloat(), 0f, (center.z - z).toFloat())
            buildKeep(level, BlockPos(x, base, z), tops, door, center)
            return true
        }
        return false
    }

    private fun buildKeep(level: ServerLevel, base: BlockPos, tops: List<BlockPos>, door: Direction, center: BlockPos) {
        val random = level.random
        fun wall(): BlockState = when (random.nextInt(8)) {
            0 -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
            1 -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
            else -> Blocks.STONE_BRICKS.defaultBlockState()
        }
        for (top in tops) for (y in top.y until base.y) place(level, BlockPos(top.x, y, top.z), Blocks.COBBLESTONE.defaultBlockState())

        val height = 15
        for (dy in 0 until height) for (dx in -4..4) for (dz in -4..4) {
            val pos = base.offset(dx, dy, dz)
            val edge = abs(dx) == 4 || abs(dz) == 4
            val corner = abs(dx) == 4 && abs(dz) == 4
            val floor = dy % 5 == 4
            val state = when {
                corner -> Blocks.POLISHED_ANDESITE.defaultBlockState()
                edge -> if (dy % 5 == 2 && (dx == 0 || dz == 0)) Blocks.AIR.defaultBlockState() else wall()
                floor -> Blocks.SPRUCE_PLANKS.defaultBlockState()
                dy == 0 -> Blocks.STONE_BRICKS.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
            place(level, pos, state)
        }
        // Doorway, with a lantern either side
        val doorway = base.relative(door, 4)
        for (dy in 1..3) place(level, doorway.above(dy), Blocks.AIR.defaultBlockState())
        place(level, doorway.above(0), Blocks.STONE_BRICKS.defaultBlockState())
        val side = door.clockWise
        for (s in listOf(side, side.opposite)) {
            val column = doorway.relative(door).relative(s, 2)
            val post = surface(level, column.x, column.z)
            if (abs(post.y - (base.y + 1)) <= 3 && solidGround(level, post) && free(level, post) && free(level, post.above())) {
                place(level, post, Blocks.STONE_BRICK_WALL.defaultBlockState())
                place(level, post.above(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
            }
        }
        // Steps down to the ground in front of the door
        for (i in 1..5) {
            val column = doorway.relative(door, i)
            val ground = surface(level, column.x, column.z)
            val stepY = base.y - i + 1
            if (ground.y >= stepY) break
            val stair = BlockPos(column.x, stepY, column.z)
            if (!free(level, stair)) break
            for (y in ground.y until stepY) place(level, BlockPos(column.x, y, column.z), Blocks.COBBLESTONE.defaultBlockState())
            place(level, stair, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, door.opposite))
        }
        // Ladder up the back wall through every floor, one block off the arrow slits so it always has wall behind it
        val ladderWall = door.opposite
        val ladder = base.relative(ladderWall, 3).relative(side)
        val ladderState = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, door)
        for (dy in 1 until height) place(level, ladder.above(dy), ladderState)
        // Furnishing: a hall below, a barracks, a lookout
        place(level, base.offset(0, 1, 0).relative(side, 2), Blocks.CRAFTING_TABLE.defaultBlockState())
        place(level, base.offset(0, 1, 0).relative(side.opposite, 2), Blocks.CHEST.defaultBlockState())
        place(level, base.offset(0, 5, 0).relative(side, 2), Blocks.BARREL.defaultBlockState())
        for (dy in listOf(3, 8, 13)) place(level, base.above(dy), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        // Battlements, corner lanterns, Archer Posts on the roof
        for (dx in -4..4) for (dz in -4..4) {
            if (abs(dx) != 4 && abs(dz) != 4) continue
            val pos = base.offset(dx, height, dz)
            val corner = abs(dx) == 4 && abs(dz) == 4
            if (corner) {
                place(level, pos, Blocks.STONE_BRICKS.defaultBlockState())
                place(level, pos.above(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
            } else if ((dx + dz) % 2 == 0) place(level, pos, wall())
        }
        for (dx in -3..3) for (dz in -3..3) place(level, base.offset(dx, height - 1, dz), Blocks.STONE_BRICKS.defaultBlockState())
        place(level, ladder.above(height - 1), ladderState)
        place(level, base.offset(2, height, 2), ModBlocks.ARCHER_POST.get().defaultBlockState())
        place(level, base.offset(-2, height, -2), ModBlocks.ARCHER_POST.get().defaultBlockState())
        // The holding guild's colours over the door
        val color = Holdings.get(level).holdings.firstOrNull { it.anchor.closerThan(center, 24.0) }?.let { TownControl.holderColor(level, it.guild) }
        if (color != null) {
            val bannerPos = doorway.relative(door).above(4)
            if (free(level, bannerPos)) {
                place(level, bannerPos, Blocks.WALL_BANNER.pick(color).defaultBlockState().setValue(WallBannerBlock.FACING, door))
            }
        }
    }
}
