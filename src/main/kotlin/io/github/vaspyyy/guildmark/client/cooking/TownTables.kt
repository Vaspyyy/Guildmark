package io.github.vaspyyy.guildmark.client.cooking

import io.github.vaspyyy.guildmark.townsfolk.Places
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where a plate goes on the table in front of a seated townsperson (living-world/cook/design.md 7.4), worked out from the
 * blocks as they stand, so it is right for every table and chair the capitals use and for ones they do not use yet: the
 * seat is a bottom stair, the table is the block in front of it (whatever shape it has), the plate lies on that block's top
 * wherever that is, and what stands on the table (a candle, a lantern) is kept clear of. Where there is no table, or its top
 * is not one flat height, or there is no room for a plate between the sitter and what stands on it, there is no [Spot], and
 * the sitter is simply not shown eating (they keep what they had in their hand).
 *
 * Distances here are in blocks along the way the sitter faces ("along", from the sitter's own place) and across it
 * ("across", positive to the sitter's right hand). A sitter sits 0.12 of a block forward of the middle of the stair
 * (`Townsperson.sitOn`), so a table the next block over begins 0.38 in front of them and is a block deep.
 */
internal object TownTables {
    /** A plate's place: the middle of its underside on the table ([x], [y], [z]), the way the sitter faces ([dx], [dz]), and its width. */
    class Spot(val x: Double, val y: Double, val z: Double, val dx: Int, val dz: Int, val size: Float)

    /** How far in front of the middle of the stair a sitter is put (`Townsperson.sitOn`). */
    private const val SEAT_SHIFT = 0.12

    /** The nearest the plate may come to the sitter: just past the near edge of the table (0.38 from them), with a finger's width to spare. */
    private const val NEAR = 0.40

    /** The farthest along the table a plate may reach, a little short of its far edge (1.38 from the sitter). */
    private const val FAR = 1.34

    /** The gap kept between the plate and whatever stands on the table. */
    private const val GAP = 0.03

    /** The widest and the narrowest a plate is laid: a room for less than [LEAST] is no room. */
    private const val MOST = 0.42
    private const val LEAST = 0.26

    /** How far to the sitter's right the plate sits: where the right hand rests on the table (the clip holds the mug 3 px to the right). */
    private const val ACROSS = 0.10

    /** How far either side of the plate's middle a thing on the table is taken to be in the way of it. */
    private const val SIDE = 0.26

    /** How high above the table a thing on it still counts (a plate is flat; its steam is not in the way of anything). */
    private const val LOW = 0.22

    /** The heights above the seat's block that a table top may be at: the usual one is a block's height (a top slab is half a block over the seat). */
    private const val TOP_LOW = 0.70
    private const val TOP_HIGH = 1.25

    /** How far two heights of a table top may differ across the plate and still be one flat top. */
    private const val FLAT = 0.03

    /**
     * The plate's place for the sitter on the stair at [seat], or null if there is no place for one: [seat] is not a stair a
     * townsperson sits on, there is no table in front of it, or the table has no room for a plate.
     */
    fun spot(level: Level, seat: BlockPos): Spot? {
        val stair = level.getBlockState(seat)
        if (stair.block !is StairBlock || stair.getValue(StairBlock.HALF) != Half.BOTTOM) return null
        val facing = Places.seatFacing(stair)
        val fx = facing.stepX
        val fz = facing.stepZ
        // The sitter's own place, and the way their right hand lies
        val sx = seat.x + 0.5 + fx * SEAT_SHIFT
        val sz = seat.z + 0.5 + fz * SEAT_SHIFT
        val rx = -fz
        val rz = fx
        val table = seat.relative(facing)
        val top = level.getBlockState(table).getCollisionShape(level, table)
        if (top.isEmpty) return null

        // What stands on the table, in the block above it: how far along it begins, for whatever is in the plate's lane
        var far = FAR
        val above = table.above()
        val stands = level.getBlockState(above)
        if (!stands.isAir) {
            for (box in stands.getShape(level, above).toAabbs()) {
                if (box.minY > LOW) continue
                val along = alongRange(box, above, sx, sz, fx, fz)
                val across = acrossRange(box, above, sx, sz, rx, rz)
                if (across.second < ACROSS - SIDE || across.first > ACROSS + SIDE || along.second <= NEAR) continue
                far = min(far, along.first - GAP)
            }
        }
        val room = far - NEAR
        if (room < LEAST) return null
        val size = min(MOST, room)
        val middle = NEAR + size / 2

        // The table's top under the whole plate: one height, a block's height or so above the seat's block
        val half = size / 2
        var height = Double.NaN
        for (a in doubleArrayOf(middle - half, middle, middle + half)) {
            for (c in doubleArrayOf(ACROSS - half, ACROSS, ACROSS + half)) {
                val x = sx + fx * a + rx * c
                val z = sz + fz * a + rz * c
                if (BlockPos.containing(x, table.y + 0.5, z) != table) return null
                val h = heightAt(top, x - table.x, z - table.z)
                if (h < 0.0) return null
                if (height.isNaN()) height = h else if (abs(h - height) > FLAT) return null
            }
        }
        val relative = table.y + height - seat.y
        if (relative < TOP_LOW || relative > TOP_HIGH) return null
        return Spot(sx + fx * middle + rx * ACROSS, table.y + height, sz + fz * middle + rz * ACROSS, fx, fz, size.toFloat())
    }

    /** The highest top of [shape] over the point ([x], [z]) of its block, or -1 where nothing of it is. */
    private fun heightAt(shape: VoxelShape, x: Double, z: Double): Double {
        var found = -1.0
        for (box in shape.toAabbs()) {
            if (x >= box.minX - EPS && x <= box.maxX + EPS && z >= box.minZ - EPS && z <= box.maxZ + EPS) found = max(found, box.maxY)
        }
        return found
    }

    private const val EPS = 1e-6

    /** The range along the sitter's way of [box] (in the block [at]), as (nearest, farthest). */
    private fun alongRange(box: AABB, at: BlockPos, sx: Double, sz: Double, fx: Int, fz: Int): Pair<Double, Double> {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (cx in doubleArrayOf(box.minX, box.maxX)) for (cz in doubleArrayOf(box.minZ, box.maxZ)) {
            val d = (at.x + cx - sx) * fx + (at.z + cz - sz) * fz
            lo = min(lo, d)
            hi = max(hi, d)
        }
        return lo to hi
    }

    /** The range across the sitter's way (to their right) of [box] (in the block [at]), as (leftmost, rightmost). */
    private fun acrossRange(box: AABB, at: BlockPos, sx: Double, sz: Double, rx: Int, rz: Int): Pair<Double, Double> {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (cx in doubleArrayOf(box.minX, box.maxX)) for (cz in doubleArrayOf(box.minZ, box.maxZ)) {
            val d = (at.x + cx - sx) * rx + (at.z + cz - sz) * rz
            lo = min(lo, d)
            hi = max(hi, d)
        }
        return lo to hi
    }
}
