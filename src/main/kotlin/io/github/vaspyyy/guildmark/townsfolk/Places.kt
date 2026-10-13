package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.StairsShape
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import java.util.WeakHashMap

/** A place to stand and the way to face there. */
data class Spot(val pos: BlockPos, val facing: Direction) {
    val yaw: Float get() = facing.toYRot()
}

/**
 * Looking about the world for where townsfolk work, sit, sleep and walk. Every search first checks the land it
 * covers is loaded, so a townsperson at the edge of the loaded world never pulls chunks in.
 */
object Places {
    /** Whether every chunk within [r] blocks of [pos] is loaded. */
    fun loaded(level: Level, pos: BlockPos, r: Int): Boolean {
        for (cx in ((pos.x - r) shr 4)..((pos.x + r) shr 4)) for (cz in ((pos.z - r) shr 4)..((pos.z + r) shr 4)) {
            if (!level.hasChunk(cx, cz)) return false
        }
        return true
    }

    /** Whether something can be stood on: a solid top at least most of a block high (farmland and paths count). */
    fun floor(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.isAir || !state.fluidState.isEmpty && !state.hasProperty(BlockStateProperties.WATERLOGGED)) return false
        val shape = state.getCollisionShape(level, pos)
        return !shape.isEmpty && shape.max(Direction.Axis.Y) >= 0.8
    }

    /** Whether a body fits in the block: nothing to bump into (carpets, crops, flowers and open doors are fine). */
    fun free(level: Level, pos: BlockPos, feet: Boolean = false): Boolean {
        val state = level.getBlockState(pos)
        if (!state.fluidState.isEmpty) return false
        val shape = state.getCollisionShape(level, pos)
        return shape.isEmpty || feet && shape.max(Direction.Axis.Y) <= 0.2
    }

    /** Whether a townsperson can stand with their feet in [pos]. */
    fun standable(level: Level, pos: BlockPos): Boolean =
        level.isLoaded(pos) && floor(level, pos.below()) && free(level, pos, feet = true) && free(level, pos.above())

    /** The nearest block within [r] (and [dy] up or down) of [center] that passes [test] and isn't claimed by someone else. */
    fun nearest(level: Level, center: BlockPos, r: Int, dy: Int, who: Entity? = null, test: (BlockState, BlockPos) -> Boolean): BlockPos? {
        if (!loaded(level, center, r)) return null
        var best: BlockPos? = null
        var bestDist = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (y in center.y - dy..center.y + dy) for (x in center.x - r..center.x + r) for (z in center.z - r..center.z + r) {
            val dist = center.distSqr(cursor.set(x, y, z))
            if (dist >= bestDist) continue
            val state = level.getBlockState(cursor)
            if (!test(state, cursor)) continue
            if (who != null && !Claims.free(level, cursor, who)) continue
            best = cursor.immutable()
            bestDist = dist
        }
        return best
    }

    /** Every block within [r] (and [dy]) of [center] that passes [test], up to [limit] of them. */
    fun all(level: Level, center: BlockPos, r: Int, dy: Int, limit: Int = 64, test: (BlockState, BlockPos) -> Boolean): List<BlockPos> {
        if (!loaded(level, center, r)) return listOf()
        val found = ArrayList<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()
        for (y in center.y - dy..center.y + dy) for (x in center.x - r..center.x + r) for (z in center.z - r..center.z + r) {
            val state = level.getBlockState(cursor.set(x, y, z))
            if (state.isAir || !test(state, cursor)) continue
            found.add(cursor.immutable())
            if (found.size >= limit) return found
        }
        return found
    }

    /**
     * Where to stand to work at the block at [station], facing it: a free side, the front first for blocks that
     * have one (an oven's mouth, a lectern's reading side, an anvil's long sides), else the side nearest [from].
     */
    fun beside(level: Level, station: BlockPos, from: BlockPos): Spot? {
        val state = level.getBlockState(station)
        val preferred: List<Direction> = when {
            state.block is AnvilBlock -> state.getValue(AnvilBlock.FACING).let { listOf(it.clockWise, it.counterClockWise) }
            state.block is LecternBlock -> listOf(state.getValue(LecternBlock.FACING))
            state.`is`(Blocks.FURNACE) || state.`is`(Blocks.SMOKER) || state.`is`(Blocks.BLAST_FURNACE) ->
                listOf(state.getValue(BlockStateProperties.HORIZONTAL_FACING))
            else -> listOf()
        }
        val sides = Direction.Plane.HORIZONTAL.stream().toList()
            .filter { standable(level, station.relative(it)) }
            .sortedWith(compareBy({ if (it in preferred) 0 else 1 }, { station.relative(it).distSqr(from) }))
        val side = sides.firstOrNull() ?: return null
        return Spot(station.relative(side), side.opposite)
    }

    /** A free seat near [center]: the low half of a straight stair with room above and a way off at the front. */
    fun seat(level: Level, center: BlockPos, r: Int, who: Entity): BlockPos? {
        val seats = all(level, center, r, 2, 32) { state, pos -> isSeat(level, state, pos) }
            .filter { Claims.free(level, it, who) && level.getEntitiesOfClass(Townsperson::class.java, AABB(it)).all { other -> other === who } }
        return if (seats.isEmpty()) null else seats[who.random.nextInt(seats.size)]
    }

    fun isSeat(level: Level, state: BlockState, pos: BlockPos): Boolean {
        if (state.block !is StairBlock || !state.`is`(BlockTags.STAIRS)) return false
        if (state.getValue(StairBlock.HALF) != Half.BOTTOM || state.getValue(StairBlock.SHAPE) != StairsShape.STRAIGHT) return false
        if (state.getValue(StairBlock.WATERLOGGED)) return false
        if (!free(level, pos.above()) || !free(level, pos.above(2))) return false
        // Somewhere to step off onto, so they can get up again (a chair's front is often its table)
        return access(level, pos, state) != null
    }

    /** Where to stand to sit down on (and get up from) the seat at [pos]: the front if it's free, else a side. */
    fun access(level: Level, pos: BlockPos, state: BlockState = level.getBlockState(pos)): BlockPos? {
        val front = seatFacing(state)
        return listOf(front, front.clockWise, front.counterClockWise)
            .map { pos.relative(it) }
            .firstOrNull { free(level, it, feet = true) && free(level, it.above()) && floor(level, it.below()) }
    }

    /** The way someone sitting on the stair at [state] faces: away from its back. */
    fun seatFacing(state: BlockState): Direction = state.getValue(StairBlock.FACING).opposite

    /** A bed's head near [home] that nobody sleeps in. */
    fun bed(level: Level, home: BlockPos, who: Entity): BlockPos? =
        nearest(level, home, 10, 3, who) { state, _ ->
            state.`is`(BlockTags.BEDS) && state.getValue(AbstractBedBlock.PART) == BedPart.HEAD && !state.getValue(AbstractBedBlock.OCCUPIED)
        }

    /**
     * A place on a bank or pier near [center] to fish from: standing ground with open water ahead, facing it.
     * Water a block lower (a quay) or level (a beach) both do.
     */
    fun bank(level: Level, center: BlockPos, r: Int, who: Entity): Spot? {
        if (!loaded(level, center, r)) return null
        val random = who.random
        var best: Spot? = null
        var bestScore = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (y in center.y - 4..center.y + 3) for (x in center.x - r..center.x + r) for (z in center.z - r..center.z + r) {
            cursor.set(x, y, z)
            if (!water(level, cursor) || water(level, cursor.above())) continue
            for (side in Direction.Plane.HORIZONTAL) {
                // Standing ground behind this water, at the water's level or a block above it
                for (up in 0..1) {
                    val stand = cursor.relative(side.opposite).above(1 + up).immutable()
                    if (water(level, stand.below()) || !standable(level, stand) || !Claims.free(level, stand, who)) continue
                    val out = cursor.relative(side, 2)
                    if (!water(level, out) || !water(level, cursor.relative(side, 1))) continue
                    val score = stand.distSqr(center) + random.nextInt(24)
                    if (score < bestScore) {
                        bestScore = score
                        best = Spot(stand, side)
                    }
                }
            }
        }
        return best
    }

    fun water(level: Level, pos: BlockPos): Boolean = level.getFluidState(pos).`is`(FluidTags.WATER)

    /** Grown-up crops near [center] (any age if [ripe] is false). */
    fun crops(level: Level, center: BlockPos, r: Int, ripe: Boolean): List<BlockPos> =
        all(level, center, r, 2, 48) { state, _ -> val crop = state.block as? CropBlock; crop != null && (!ripe || crop.isMaxAge(state)) }

    /** Where to stand next to the crop at [crop] to tend it, facing it. */
    fun besideCrop(level: Level, crop: BlockPos, from: BlockPos): Spot? {
        val sides = Direction.Plane.HORIZONTAL.stream().toList()
            .filter { standable(level, crop.relative(it)) }
            .sortedBy { crop.relative(it).distSqr(from) }
        val side = sides.firstOrNull() ?: return null
        return Spot(crop.relative(side), side.opposite)
    }

    /**
     * Somewhere out in the streets within [r] blocks of [center]: open to the sky, on solid ground (not a roof or a
     * room's ceiling: the ground under it is solid three deep), dry, and not too far above or below [center].
     */
    fun street(level: Level, center: BlockPos, r: Int, random: RandomSource, minR: Int = 0, tries: Int = 12): BlockPos? {
        repeat(tries) {
            val angle = random.nextDouble() * Math.PI * 2
            val dist = minR + random.nextDouble() * (r - minR)
            val x = center.x + (kotlin.math.cos(angle) * dist).toInt()
            val z = center.z + (kotlin.math.sin(angle) * dist).toInt()
            if (!level.hasChunk(x shr 4, z shr 4)) return@repeat
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            if (kotlin.math.abs(y - center.y) > 12) return@repeat
            val pos = BlockPos(x, y, z)
            if (outdoorGround(level, pos)) return pos
        }
        return null
    }

    /** Whether [pos] is a free spot out of doors on real ground. */
    fun outdoorGround(level: Level, pos: BlockPos): Boolean {
        if (!standable(level, pos) || !level.canSeeSky(pos)) return false
        if (!level.getBlockState(pos.below()).fluidState.isEmpty) return false
        for (d in 2..3) if (level.getBlockState(pos.below(d)).getCollisionShape(level, pos.below(d)).isEmpty) return false
        return true
    }
}

/**
 * Who has taken which seat, bed, anvil or fishing spot, so two townsfolk don't share one. A claim lapses unless
 * its holder renews it, so nothing stays taken by someone who left the world.
 */
object Claims {
    private class Claim(val holder: Int, var until: Long)

    private val byLevel = WeakHashMap<Level, HashMap<Long, Claim>>()

    private fun of(level: Level): HashMap<Long, Claim> = byLevel.getOrPut(level) { HashMap() }

    fun free(level: Level, pos: BlockPos, who: Entity): Boolean {
        val claim = of(level)[pos.asLong()] ?: return true
        return claim.holder == who.id || claim.until < level.gameTime
    }

    /** Takes [pos] for [who] for the next [ticks] ticks, if nobody else holds it. */
    fun claim(level: Level, pos: BlockPos, who: Entity, ticks: Int = 200): Boolean {
        if (!free(level, pos, who)) return false
        val map = of(level)
        if (map.size > 4096) map.values.removeIf { it.until < level.gameTime }
        map[pos.asLong()] = Claim(who.id, level.gameTime + ticks)
        return true
    }

    fun release(level: Level, pos: BlockPos?, who: Entity) {
        if (pos == null) return
        val map = of(level)
        if (map[pos.asLong()]?.holder == who.id) map.remove(pos.asLong())
    }
}
