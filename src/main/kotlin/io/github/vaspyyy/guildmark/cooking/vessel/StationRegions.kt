package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/** The three places on a station a click can land (living-world/cook/design.md 1.1). */
enum class Region {
    /** The top: the pot's mouth, the pan, the oven door, the spit's rod. */
    VESSEL,

    /** The bottom: the firebox, where fuel and a light go. */
    FIRE,

    /** The control: the pot's chain, the hob's slide, the oven's damper, the spit's rest. */
    HEAT,

    /** The spit's crank handle at the end of its rod (the only station that has one): the art puts it inside the heat box, and the click is split here. */
    CRANK,
}

/**
 * Finding which [Region] of a station a click is on (living-world/cook/design.md 1.1), from where it lands in the block.
 * The boxes are the art's own: `STATION_BOXES` in `art/scripts/cook_art.py` (M1), in pixels of the block's cell, for a
 * station facing north; a station that faces another way (the oven and the spit) has its click turned back to north
 * first. Most of a station is one solid shape, so a click lands on its outside and the box the player means is inside:
 * the eye-to-click ray is followed through the block, and the first box it enters wins; a ray that meets none picks the box
 * nearest the click.
 */
object StationRegions {
    private val REGIONS = Region.entries

    /** The box of a region as (x0, y0, z0, x1, y1, z1), pixels. */
    private fun box(vararg v: Double): DoubleArray = v

    // Vessel, fire, heat: copied from STATION_BOXES (the spit's heat box is cut at x 14.6 where its crank handle begins, and the handle is the fourth)
    private val STEWPOT_CAMP = arrayOf(box(4.0, 4.0, 4.0, 12.0, 10.5, 12.0), box(2.5, 0.0, 2.5, 13.5, 4.0, 13.5), box(6.0, 10.5, 6.0, 10.0, 15.4, 10.0))
    private val STEWPOT_HEARTH = arrayOf(box(4.0, 4.0, 4.0, 12.0, 10.5, 12.0), box(3.2, 0.0, 3.2, 12.8, 4.0, 12.8), box(6.0, 10.5, 6.0, 10.0, 15.4, 10.0))
    private val HOB = arrayOf(box(2.5, 9.5, 2.5, 13.5, 14.0, 13.5), box(3.5, 0.0, 0.0, 12.5, 4.5, 6.0), box(1.0, 5.0, 0.0, 15.0, 9.0, 5.0))
    private val OVEN = arrayOf(box(3.6, 8.0, 0.0, 12.4, 14.6, 4.0), box(4.5, 0.0, 0.0, 11.5, 4.0, 6.0), box(3.4, 4.0, 0.0, 12.6, 8.0, 4.0))
    private val SPIT = arrayOf(
        box(2.4, 6.4, 5.5, 12.0, 11.8, 10.5), box(2.4, 0.0, 4.0, 12.6, 4.0, 12.0), box(11.6, 5.5, 5.5, 14.6, 13.4, 10.5), box(14.6, 5.5, 4.5, 16.0, 13.0, 11.5),
    )

    /**
     * The region of the station at [pos] that a click at [hit] lands on, the player's eye at [eye]. [hearth] is the
     * Stewpot's style (its fire is narrower) and [facing] the way the oven or spit faces (north for a station that has none).
     */
    fun of(station: Station, hearth: Boolean, facing: Direction, pos: BlockPos, eye: Vec3, hit: Vec3): Region {
        val boxes = when (station) {
            Station.STEWPOT -> if (hearth) STEWPOT_HEARTH else STEWPOT_CAMP
            Station.HOB -> HOB
            Station.OVEN -> OVEN
            Station.SPIT -> SPIT
        }
        // The click and the eye in the block's own pixels, turned back to the model that faces north
        val o = local(eye, pos, facing)
        val h = local(hit, pos, facing)
        val d = doubleArrayOf(h[0] - o[0], h[1] - o[1], h[2] - o[2])
        var best = -1
        var bestT = Double.MAX_VALUE
        for (i in boxes.indices) {
            val t = enters(boxes[i], o, d)
            if (t >= 0.0 && t < bestT) {
                bestT = t
                best = i
            }
        }
        if (best < 0) {
            var bestD = Double.MAX_VALUE
            for (i in boxes.indices) {
                val dist = distanceSq(boxes[i], h)
                if (dist < bestD) {
                    bestD = dist
                    best = i
                }
            }
        }
        return REGIONS[best.coerceIn(0, REGIONS.size - 1)]
    }

    /** [world] in pixels of the cell at [pos], turned about its middle so that a station facing [facing] faces north. */
    private fun local(world: Vec3, pos: BlockPos, facing: Direction): DoubleArray {
        val x = (world.x - pos.x) * 16.0 - 8.0
        val y = (world.y - pos.y) * 16.0
        val z = (world.z - pos.z) * 16.0 - 8.0
        return when (facing) {
            Direction.EAST -> doubleArrayOf(z + 8.0, y, -x + 8.0)
            Direction.SOUTH -> doubleArrayOf(-x + 8.0, y, -z + 8.0)
            Direction.WEST -> doubleArrayOf(-z + 8.0, y, x + 8.0)
            else -> doubleArrayOf(x + 8.0, y, z + 8.0)
        }
    }

    /**
     * How far along the ray from [o] in the direction [d] it first meets [box], or -1 if it never does. The slab test: the
     * ray is in the box where it is inside all three pairs of planes at once. A ray that starts inside meets it at 0.
     */
    private fun enters(box: DoubleArray, o: DoubleArray, d: DoubleArray): Double {
        var near = -1.0e18
        var far = 1.0e18
        for (axis in 0..2) {
            val lo = box[axis]
            val hi = box[axis + 3]
            if (Math.abs(d[axis]) < 1.0e-9) {
                if (o[axis] < lo || o[axis] > hi) return -1.0
            } else {
                var t1 = (lo - o[axis]) / d[axis]
                var t2 = (hi - o[axis]) / d[axis]
                if (t1 > t2) {
                    val swap = t1
                    t1 = t2
                    t2 = swap
                }
                if (t1 > near) near = t1
                if (t2 < far) far = t2
                if (near > far) return -1.0
            }
        }
        if (far < 0.0) return -1.0
        return if (near < 0.0) 0.0 else near
    }

    /** The square of the distance from the point [p] to [box], 0 inside it. */
    private fun distanceSq(box: DoubleArray, p: DoubleArray): Double {
        var sum = 0.0
        for (axis in 0..2) {
            val c = p[axis].coerceIn(box[axis], box[axis + 3])
            val diff = p[axis] - c
            sum += diff * diff
        }
        return sum
    }
}
