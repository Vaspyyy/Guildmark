package io.github.vaspyyy.guildmark.road

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds a road route between two places with A* over a coarse grid, reading terrain height straight
 * from the world generator so it works across land nobody has visited yet. Gentle slopes are cheap,
 * steep ones and water cost more, so roads wind around hills and lakes like a real track.
 */
object RoadPlanner {
    /** Grid spacing in blocks; also the spacing of the waypoints travellers walk between. */
    private const val STEP = 12
    // Each new cell reads the generator twice, so this keeps a worst-case plan to a fraction of a second
    private const val MAX_EXPANSIONS = 6000
    /** How far the route may stray sideways from the straight line, in blocks. */
    private const val CORRIDOR = 400.0

    private class Cell(val x: Int, val z: Int, val height: Int, val water: Int)

    fun plan(level: ServerLevel, from: BlockPos, to: BlockPos): List<BlockPos>? {
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        val cells = HashMap<Long, Cell>()
        fun cell(gx: Int, gz: Int): Cell = cells.getOrPut(pack(gx, gz)) {
            val x = gx * STEP
            val z = gz * STEP
            val surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)
            val floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState)
            Cell(gx, gz, surface, surface - floor)
        }

        val start = cell(Math.floorDiv(from.x, STEP), Math.floorDiv(from.z, STEP))
        val goal = cell(Math.floorDiv(to.x, STEP), Math.floorDiv(to.z, STEP))
        val lineX = (goal.x - start.x).toDouble()
        val lineZ = (goal.z - start.z).toDouble()
        val lineLength = sqrt(lineX * lineX + lineZ * lineZ).coerceAtLeast(1.0)
        fun inCorridor(gx: Int, gz: Int): Boolean {
            val offAxis = abs((gx - start.x) * lineZ - (gz - start.z) * lineX) / lineLength
            return offAxis * STEP <= CORRIDOR
        }
        fun heuristic(c: Cell): Double {
            val dx = (c.x - goal.x).toDouble()
            val dz = (c.z - goal.z).toDouble()
            return sqrt(dx * dx + dz * dz) * STEP * 1.2
        }

        val costSoFar = HashMap<Long, Double>()
        val cameFrom = HashMap<Long, Long>()
        val open = PriorityQueue<Pair<Double, Cell>>(compareBy { it.first })
        costSoFar[pack(start.x, start.z)] = 0.0
        open.add(Pair(heuristic(start), start))
        var expansions = 0

        while (open.isNotEmpty() && expansions++ < MAX_EXPANSIONS) {
            val here = open.poll().second
            val hereKey = pack(here.x, here.z)
            if (here === goal) return trace(cameFrom, hereKey, cells, from, to)
            val hereCost = costSoFar[hereKey] ?: continue
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val gx = here.x + dx
                val gz = here.z + dz
                if (!inCorridor(gx, gz)) continue
                val next = cell(gx, gz)
                val step = stepCost(here, next, if (dx != 0 && dz != 0) 1.414 else 1.0) ?: continue
                val nextKey = pack(gx, gz)
                val total = hereCost + step
                if (total < (costSoFar[nextKey] ?: Double.MAX_VALUE)) {
                    costSoFar[nextKey] = total
                    cameFrom[nextKey] = hereKey
                    open.add(Pair(total + heuristic(next), next))
                }
            }
        }
        return null
    }

    /** Cost to walk between neighbouring cells, or null if it's a cliff. */
    private fun stepCost(a: Cell, b: Cell, diagonal: Double): Double? {
        val climb = abs(b.height - a.height)
        if (climb > STEP) return null
        var cost = STEP * diagonal * (1.0 + climb * climb * 0.15)
        if (b.water > 0) cost += if (b.water > 4) 400.0 else 60.0
        return cost
    }

    private fun trace(cameFrom: Map<Long, Long>, end: Long, cells: Map<Long, Cell>, from: BlockPos, to: BlockPos): List<BlockPos> {
        val keys = ArrayList<Long>()
        var key: Long? = end
        while (key != null) {
            keys.add(key)
            key = cameFrom[key]
        }
        keys.reverse()
        val points = keys.map { k ->
            val c = cells.getValue(k)
            BlockPos(c.x * STEP + STEP / 2, c.height, c.z * STEP + STEP / 2)
        }.toMutableList()
        points[0] = from
        points[points.size - 1] = to
        return points
    }

    private fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
