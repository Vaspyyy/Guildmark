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
 * steep ones and water cost more, and anything steeper than a walkable grade is ruled out, so roads
 * wind around hills and lakes like a real track. Thread-safe: runs off the server thread.
 */
object RoadPlanner {
    /** Grid spacing in blocks; also the spacing of the waypoints travellers walk between. */
    private const val STEP = 8
    /** Most height change allowed over half a step (4 blocks, or about 6 on a diagonal). */
    private const val MAX_HALF_CLIMB = 3
    // Plans run in the background, so this only bounds how long a hopeless search keeps trying
    private const val MAX_EXPANSIONS = 20000
    /** How far the route may stray sideways from the straight line, in blocks. */
    private const val CORRIDOR = 400.0

    private class Cell(val x: Int, val z: Int, val height: Int, val water: Int)

    fun plan(level: ServerLevel, from: BlockPos, to: BlockPos): List<BlockPos>? {
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        val cells = HashMap<Long, Cell>()
        fun cell(gx: Int, gz: Int): Cell = cells.getOrPut(pack(gx, gz)) {
            // Sampled at the cell centre, where the waypoint goes
            val x = gx * STEP + STEP / 2
            val z = gz * STEP + STEP / 2
            val surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)
            val floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState)
            Cell(gx, gz, surface, surface - floor)
        }
        // Ground height halfway between two cells, so a cliff between them isn't missed
        val midpoints = HashMap<Long, Int>()
        fun midHeight(a: Cell, b: Cell): Int {
            val hx = a.x * 2 + (b.x - a.x)
            val hz = a.z * 2 + (b.z - a.z)
            return midpoints.getOrPut(pack(hx, hz)) {
                generator.getBaseHeight(hx * STEP / 2 + STEP / 2, hz * STEP / 2 + STEP / 2, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)
            }
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
                val step = stepCost(here, midHeight(here, next), next, if (dx != 0 && dz != 0) 1.414 else 1.0) ?: continue
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

    /** Cost to walk between neighbouring cells, or null if it's too steep for a road. */
    private fun stepCost(a: Cell, mid: Int, b: Cell, diagonal: Double): Double? {
        // Water levels out the surface, so only dry ground is checked for climbing
        val first = if (a.water > 0 && mid <= a.height) 0 else abs(mid - a.height)
        val second = if (b.water > 0 && mid <= b.height) 0 else abs(b.height - mid)
        if (first > MAX_HALF_CLIMB || second > MAX_HALF_CLIMB) return null
        var cost = STEP * diagonal * (1.0 + (first * first + second * second) * 0.35 / diagonal)
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
