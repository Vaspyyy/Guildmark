package io.github.vaspyyy.guildmark.guild

import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.DyeColor
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs

/**
 * The village guild hall: a timber-framed hall with a gabled roof, its door facing the bell, and a
 * receptionist behind the counter who runs rank trials, hall contracts and the guild's news.
 */
object GuildHall {
    /** Width across the front and depth from the door to the back wall. */
    private const val WIDTH = 9
    private const val DEPTH = 11
    private const val WALL_HEIGHT = 5
    /** Most the ground may rise or fall across the site; the hall sits on a stone foundation. */
    private const val MAX_SLOPE = 3

    private val NATURAL_GROUND = setOf(
        Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.SAND, Blocks.RED_SAND,
        Blocks.SNOW_BLOCK, Blocks.GRAVEL, Blocks.STONE, Blocks.MUD, Blocks.TERRACOTTA,
    )

    /** Lays out positions relative to the hall: x across the front (left to right), z from front to back. */
    private class Frame(val origin: BlockPos, val front: Direction) {
        val back: Direction = front.opposite
        val right: Direction = front.clockWise

        fun at(x: Int, y: Int, z: Int): BlockPos = origin.relative(right, x).relative(back, z).above(y)
    }

    /** Build the hall on open ground near [center] and seat its receptionist. False if there's no room. */
    fun build(level: ServerLevel, center: BlockPos, radius: Int, anchor: BlockPos): Boolean {
        val (frame, floor) = findSite(level, center, radius) ?: return false
        clearAndFound(level, frame, floor)
        walls(level, frame, floor)
        roof(level, frame, floor)
        furnish(level, frame, floor)
        seatReceptionist(level, frame, floor, anchor)
        return true
    }

    /** Nearest spot at least 12 blocks from the bell where the whole footprint is open, gently sloped land. */
    private fun findSite(level: ServerLevel, center: BlockPos, radius: Int): Pair<Frame, Int>? {
        val reach = radius + 12
        val spots = mutableListOf<BlockPos>()
        for (dx in -reach..reach step 2) for (dz in -reach..reach step 2) {
            val d2 = dx * dx + dz * dz
            if (d2 < 12 * 12 || d2 > reach * reach) continue
            spots.add(center.offset(dx, 0, dz))
        }
        spots.sortBy { it.distSqr(center) }
        for (spot in spots) {
            val dx = center.x - spot.x
            val dz = center.z - spot.z
            val front = if (abs(dx) > abs(dz)) (if (dx > 0) Direction.EAST else Direction.WEST)
            else (if (dz > 0) Direction.SOUTH else Direction.NORTH)
            // spot is the middle of the front wall; the frame starts at its left corner
            val origin = spot.relative(front.clockWise, -WIDTH / 2)
            val frame = Frame(BlockPos(origin.x, 0, origin.z), front)
            val floor = siteFloor(level, frame) ?: continue
            return Pair(frame, floor)
        }
        return null
    }

    /** The floor height if the footprint (and a block around it) is buildable, else null. */
    private fun siteFloor(level: ServerLevel, frame: Frame): Int? {
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        for (x in -1..WIDTH) for (z in -1..DEPTH) {
            val column = frame.at(x, 0, z)
            if (level.chunkSource.getChunkNow(column.x shr 4, column.z shr 4) == null) return null
            val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)
            val ground = level.getBlockState(top.below())
            if (ground.block !in NATURAL_GROUND || !level.getFluidState(top.below()).isEmpty) return null
            low = minOf(low, top.y)
            high = maxOf(high, top.y)
            if (high - low > MAX_SLOPE) return null
        }
        return high
    }

    private fun set(level: ServerLevel, pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, Block.UPDATE_ALL)
    }

    /** Clear the site down to the floor and raise a stone foundation under any low ground. */
    private fun clearAndFound(level: ServerLevel, frame: Frame, floor: Int) {
        for (x in -1..WIDTH) for (z in -1..DEPTH) {
            for (y in floor..floor + 14) set(level, frame.at(x, y, z), Blocks.AIR.defaultBlockState())
            val inside = x in 0 until WIDTH && z in 0 until DEPTH
            var y = floor - 1
            while (y > floor - 8) {
                val pos = frame.at(x, y, z)
                val state = level.getBlockState(pos)
                val solidBelow = !state.canBeReplaced() && level.getFluidState(pos).isEmpty
                if (!inside) {
                    // Around the hall: just fill hollows so the edge isn't a drop
                    if (solidBelow) break
                    set(level, pos, Blocks.COBBLESTONE.defaultBlockState())
                } else {
                    val edge = x == 0 || x == WIDTH - 1 || z == 0 || z == DEPTH - 1
                    if (y == floor - 1) set(level, pos, if (edge) Blocks.STONE_BRICKS.defaultBlockState() else Blocks.SPRUCE_PLANKS.defaultBlockState())
                    else if (solidBelow) break
                    else set(level, pos, Blocks.COBBLESTONE.defaultBlockState())
                }
                y--
            }
        }
        // A path up to the door
        set(level, frame.at(WIDTH / 2, -1, -1), Blocks.DIRT_PATH.defaultBlockState())
    }

    private fun walls(level: ServerLevel, frame: Frame, floor: Int) {
        val logUp = Blocks.SPRUCE_LOG.defaultBlockState()
        val beamAcross = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, frame.right.axis)
        val beamDeep = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, frame.back.axis)
        val door = WIDTH / 2
        for (x in 0 until WIDTH) for (z in 0 until DEPTH) {
            val frontOrBack = z == 0 || z == DEPTH - 1
            val side = x == 0 || x == WIDTH - 1
            if (!frontOrBack && !side) continue
            val post = (x == 0 || x == WIDTH - 1) && (z == 0 || z == DEPTH - 1 || z == DEPTH / 2)
            for (h in 0 until WALL_HEIGHT) {
                val pos = frame.at(x, floor - frame.origin.y + h, z)
                val state = when {
                    post -> logUp
                    h == WALL_HEIGHT - 1 -> if (frontOrBack) beamAcross else beamDeep
                    z == 0 && x == door && h <= 1 -> continue
                    h == 0 -> Blocks.STONE_BRICKS.defaultBlockState()
                    h in 1..2 && frontOrBack && (x == 2 || x == WIDTH - 3) -> Blocks.GLASS_PANE.defaultBlockState()
                    h in 1..2 && side && (z in 2..3 || z in DEPTH - 4..DEPTH - 3) -> Blocks.GLASS_PANE.defaultBlockState()
                    else -> Blocks.OAK_PLANKS.defaultBlockState()
                }
                set(level, pos, state)
            }
        }
        val doorBase = frame.at(door, floor - frame.origin.y, 0)
        val doorState = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, frame.back)
        set(level, doorBase, doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))
        set(level, doorBase.above(), doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        // Panes join up with their neighbours
        for (x in 0 until WIDTH) for (z in 0 until DEPTH) for (h in 1..2) {
            val pos = frame.at(x, floor - frame.origin.y + h, z)
            val state = level.getBlockState(pos)
            if (state.`is`(Blocks.GLASS_PANE)) set(level, pos, Block.updateFromNeighbourShapes(state, level, pos))
        }
    }

    /** A steep gable running front to back, overhanging the walls by a block. */
    private fun roof(level: ServerLevel, frame: Frame, floor: Int) {
        val base = floor - frame.origin.y + WALL_HEIGHT
        val half = WIDTH / 2
        val leftStair = Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, frame.right)
        val rightStair = Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, frame.right.opposite)
        for (z in -1..DEPTH) {
            for (k in 0..half) {
                set(level, frame.at(k - 1, base + k, z), leftStair)
                set(level, frame.at(WIDTH - k, base + k, z), rightStair)
            }
            set(level, frame.at(half, base + half + 1, z), Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM))
            // A ridge beam under the peak to hang the lanterns from
            if (z in 0 until DEPTH) set(level, frame.at(half, base + half, z), Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, frame.back.axis))
        }
        // Fill the gable ends under the roof
        for (h in 0..half) for (x in h until WIDTH - h) for (z in listOf(0, DEPTH - 1)) {
            if (h == half && x == half) continue
            set(level, frame.at(x, base + h, z), if (h == 2 && x == half) Blocks.GLASS_PANE.defaultBlockState() else Blocks.OAK_PLANKS.defaultBlockState())
        }
    }

    private fun furnish(level: ServerLevel, frame: Frame, floor: Int) {
        val y = floor - frame.origin.y
        val counterZ = DEPTH - 4
        val half = WIDTH / 2
        // A runner from the door to the counter
        for (z in 1 until counterZ) set(level, frame.at(half, y, z), Blocks.CARPET.pick(DyeColor.RED).defaultBlockState())
        // The counter, with a way round the end
        for (x in 2 until WIDTH - 1) set(level, frame.at(x, y, counterZ), Blocks.POLISHED_ANDESITE.defaultBlockState())
        set(level, frame.at(2, y + 1, counterZ), Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, frame.front))
        set(level, frame.at(WIDTH - 3, y + 1, counterZ), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
        // Shelves along the back wall
        for (x in 1 until WIDTH - 1) for (h in 0..1) {
            if (x == half) continue
            set(level, frame.at(x, y + h, DEPTH - 2), Blocks.BOOKSHELF.defaultBlockState())
        }
        // Lit barrels in the front corners
        for (x in listOf(1, WIDTH - 2)) {
            set(level, frame.at(x, y, 1), Blocks.BARREL.defaultBlockState())
            set(level, frame.at(x, y + 1, 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false))
        }
        // Lanterns hanging from the ridge
        val ridge = y + WALL_HEIGHT + half
        for (z in listOf(2, DEPTH - 3)) {
            set(level, frame.at(half, ridge - 1, z), Blocks.IRON_CHAIN.defaultBlockState())
            set(level, frame.at(half, ridge - 2, z), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))
        }
    }

    private fun seatReceptionist(level: ServerLevel, frame: Frame, floor: Int, anchor: BlockPos) {
        val villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT) ?: return
        val spot = frame.at(WIDTH / 2, floor - frame.origin.y, DEPTH - 3)
        villager.snapTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, frame.front.toYRot(), 0.0f)
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null)
        val librarian = level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).getOrThrow(VillagerProfession.LIBRARIAN)
        villager.villagerData = villager.villagerData.withProfession(librarian).withLevel(5)
        villager.yHeadRot = frame.front.toYRot()
        villager.customName = Component.translatable("entity.guildmark.receptionist", QuestGenerator.nameFor(level.random.nextLong()))
        villager.isCustomNameVisible = true
        // Stays at the desk: no wandering, no harm, never despawns
        villager.isNoAi = true
        villager.isPermanentlyInvulnerable = true
        villager.setPersistenceRequired()
        villager.setData(ModAttachments.RECEPTIONIST, anchor)
        level.addFreshEntity(villager)
    }
}
