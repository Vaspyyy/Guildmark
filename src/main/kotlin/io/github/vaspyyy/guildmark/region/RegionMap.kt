package io.github.vaspyyy.guildmark.region

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.QuartPos
import net.minecraft.server.level.ServerLevel
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** One region: where it lies is in its cell's plan, this is what it is. */
class RegionInfo(
    val id: Long,
    val kind: RegionKind,
    val name: String,
    /** Where its name is lettered on the map: the spot furthest inside it. */
    val labelX: Int,
    val labelZ: Int,
    /** How many lattice points it covers. */
    val size: Int,
)

/**
 * One cell's regions: which region each lattice point (every [RegionMap.STEP] blocks) over the cell's box
 * belongs to, as an index into [regions], or [RegionMap.OUTSIDE] where the point lies in another cell.
 */
class CellPlan(
    val cell: Long,
    /** The box's corner, in lattice steps, and its size. */
    val i0: Int,
    val j0: Int,
    val width: Int,
    val height: Int,
    val grid: ByteArray,
    val regions: List<RegionInfo>,
) {
    /** The region at lattice point ([i], [j]), or null if it lies in another cell. */
    fun at(i: Int, j: Int): RegionInfo? {
        val x = i - i0
        val z = j - j0
        if (x < 0 || z < 0 || x >= width || z >= height) return null
        val index = grid[z * width + x].toInt()
        return if (index < 0) null else regions.getOrNull(index)
    }
}

/**
 * Where each region lies, worked out cell by cell (see RegionCells) on a thread of its own: the land every
 * 32 blocks is sorted by its kind, the patches of one kind that run together become regions, and patches
 * too small to count go to the neighbour they share the most border with. A cell is worked out the first
 * time anyone asks about it and kept while the server runs. Nothing is saved: it all follows from the seed.
 */
class RegionMap private constructor(private val level: ServerLevel) {
    private val seed = level.seed
    private val cells = RegionCells.of(seed)
    private val plans = ConcurrentHashMap<Long, CellPlan>()
    private val asked = ConcurrentHashMap.newKeySet<Long>()
    private val caves = ConcurrentHashMap<Long, RegionInfo>()

    /** Cell [cell]'s plan if it's been worked out, without putting it in hand. */
    fun ready(cell: Long): CellPlan? = plans[cell]

    /** Cell [cell]'s plan if it's been worked out; if not, it's put in hand and null comes back for now. */
    fun plan(cell: Long): CellPlan? {
        plans[cell]?.let { return it }
        if (asked.add(cell)) {
            WORKER.execute {
                try {
                    plans[cell] = analyse(cell)
                } catch (e: Exception) {
                    Guildmark.LOGGER.warn("Couldn't work out the regions of cell {}, {}", RegionCells.keyX(cell), RegionCells.keyZ(cell), e)
                    asked.remove(cell)
                }
            }
        }
        return null
    }

    /**
     * The region on the surface at ([x], [z]), or null while its cell is still being worked out. Borders
     * are as fine as the lattice: the spot goes by the lattice point whose square it stands in.
     */
    fun region(x: Int, z: Int): RegionInfo? {
        val i = Math.floorDiv(x, STEP)
        val j = Math.floorDiv(z, STEP)
        val plan = plan(cells.cellAt(i * STEP + STEP / 2, j * STEP + STEP / 2)) ?: return null
        return plan.at(i, j) ?: plan.regions.firstOrNull()
    }

    /** The caves of one sort under cell [cell]: [slot] is CAVE_SLOT plus the flavour, DEEP_SLOT or CRYSTAL_SLOT. */
    fun underground(cell: Long, slot: Int): RegionInfo = caves.computeIfAbsent(regionId(cell, slot)) { id ->
        val cx = RegionCells.keyX(cell)
        val cz = RegionCells.keyZ(cell)
        val kind = when (slot) {
            DEEP_SLOT -> RegionKind.DEEP
            CRYSTAL_SLOT -> RegionKind.CRYSTAL
            else -> RegionKind.CAVERN
        }
        val name = if (kind == RegionKind.CAVERN) RegionNames.cavern(seed, cx, cz, slot - CAVE_SLOT) else RegionNames.name(seed, cx, cz, slot, kind)
        RegionInfo(id, kind, name, cells.centerX(cx, cz).toInt(), cells.centerZ(cx, cz).toInt(), 0)
    }

    // ---- Working a cell out ----

    private fun analyse(cell: Long): CellPlan {
        val cx = RegionCells.keyX(cell)
        val cz = RegionCells.keyZ(cell)
        val i0 = Math.floorDiv(cx * RegionCells.CELL - MARGIN, STEP)
        val j0 = Math.floorDiv(cz * RegionCells.CELL - MARGIN, STEP)
        val width = (RegionCells.CELL + 2 * MARGIN) / STEP + 1
        val height = width
        val n = width * height
        val kinds = IntArray(n) { OUT }

        // The surface's kind every STEP blocks, read high above the ground so caves don't count
        val resolver = level.uncachedBiomeResolver()
        val quartY = QuartPos.fromBlock(level.maxY - 8)
        for (z in 0 until height) for (x in 0 until width) {
            val wx = (i0 + x) * STEP + STEP / 2
            val wz = (j0 + z) * STEP + STEP / 2
            if (cells.cellAt(wx, wz) != cell) continue
            val kind = RegionKind.ofBiome(resolver.getNoiseBiome(QuartPos.fromBlock(wx), quartY, QuartPos.fromBlock(wz)))
            kinds[z * width + x] = if (kind == null || kind.underground) SEAM else kind.ordinal
        }

        // Rivers and beaches belong to whatever lies beside them
        val queue = ArrayDeque<Int>()
        for (p in 0 until n) if (kinds[p] >= 0) queue.add(p)
        if (queue.isEmpty()) for (p in 0 until n) if (kinds[p] == SEAM) kinds[p] = RegionKind.HEARTLAND.ordinal
        while (queue.isNotEmpty()) {
            val p = queue.poll()
            forNeighbours(p, width, height) { q ->
                if (kinds[q] == SEAM) {
                    kinds[q] = kinds[p]
                    queue.add(q)
                }
            }
        }

        // Patches of one kind of land, running together
        val patch = IntArray(n) { -1 }
        val sizes = ArrayList<Int>()
        val patchKinds = ArrayList<Int>()
        for (start in 0 until n) {
            if (kinds[start] < 0 || patch[start] >= 0) continue
            val id = sizes.size
            var size = 0
            patch[start] = id
            queue.add(start)
            while (queue.isNotEmpty()) {
                val p = queue.poll()
                size++
                forNeighbours(p, width, height) { q ->
                    if (patch[q] < 0 && kinds[q] == kinds[start]) {
                        patch[q] = id
                        queue.add(q)
                    }
                }
            }
            sizes.add(size)
            patchKinds.add(kinds[start])
        }

        // How much border each pair of patches shares
        val borders = Array(sizes.size) { HashMap<Int, Int>() }
        for (p in 0 until n) {
            val a = patch[p]
            if (a < 0) continue
            val x = p % width
            if (x + 1 < width) patch[p + 1].let { b -> if (b >= 0 && b != a) share(borders, a, b) }
            if (p + width < n) patch[p + width].let { b -> if (b >= 0 && b != a) share(borders, a, b) }
        }

        // Too-small patches join the neighbour they share the most border with, smallest first
        val parent = IntArray(sizes.size) { it }
        fun root(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            return r
        }
        val size = sizes.toIntArray()
        val heap = PriorityQueue<IntArray>(compareBy { it[1] })
        for (i in size.indices) heap.add(intArrayOf(i, size[i]))
        while (heap.isNotEmpty()) {
            val (a, s) = heap.poll()
            if (parent[a] != a || s != size[a]) continue
            if (s >= MIN_SIZE) break
            val best = borders[a].maxByOrNull { it.value }?.key ?: continue
            // Fold a into its best neighbour
            parent[a] = best
            size[best] += size[a]
            for ((other, length) in borders[a]) {
                if (other == best) continue
                borders[best].merge(other, length, Int::plus)
                borders[other].remove(a)
                borders[other].merge(best, length, Int::plus)
            }
            borders[best].remove(a)
            borders[a].clear()
            heap.add(intArrayOf(best, size[best]))
        }

        // The regions that remain, the largest first
        val roots = size.indices.filter { parent[it] == it && size[it] > 0 }.sortedByDescending { size[it] }.take(MAX_REGIONS)
        val indexOf = HashMap<Int, Int>()
        for ((index, r) in roots.withIndex()) indexOf[r] = index
        val grid = ByteArray(n) { OUTSIDE }
        for (p in 0 until n) {
            if (patch[p] < 0) continue
            grid[p] = (indexOf[root(patch[p])] ?: 0).toByte()
        }
        val regions = roots.mapIndexed { index, r ->
            val kind = RegionKind.entries[patchKinds[r]]
            val (lx, lz) = labelSpot(grid, index, width, height)
            RegionInfo(
                regionId(cell, index), kind, RegionNames.name(seed, cx, cz, index, kind),
                (i0 + lx) * STEP + STEP / 2, (j0 + lz) * STEP + STEP / 2, size[r],
            )
        }
        return CellPlan(cell, i0, j0, width, height, grid, regions)
    }

    private fun share(borders: Array<HashMap<Int, Int>>, a: Int, b: Int) {
        borders[a].merge(b, 1, Int::plus)
        borders[b].merge(a, 1, Int::plus)
    }

    /** The point of region [index] furthest from its edge, the nearer its middle if several are as far. */
    private fun labelSpot(grid: ByteArray, index: Int, width: Int, height: Int): Pair<Int, Int> {
        val n = width * height
        val distance = IntArray(n) { -1 }
        val queue = ArrayDeque<Int>()
        var sumX = 0L
        var sumZ = 0L
        var count = 0
        for (p in 0 until n) {
            if (grid[p].toInt() != index) continue
            sumX += p % width
            sumZ += p / width
            count++
            var edge = false
            val x = p % width
            if (x == 0 || x == width - 1 || p < width || p >= n - width) edge = true
            else forNeighbours(p, width, height) { q -> if (grid[q].toInt() != index) edge = true }
            if (edge) {
                distance[p] = 0
                queue.add(p)
            }
        }
        while (queue.isNotEmpty()) {
            val p = queue.poll()
            forNeighbours(p, width, height) { q ->
                if (grid[q].toInt() == index && distance[q] < 0) {
                    distance[q] = distance[p] + 1
                    queue.add(q)
                }
            }
        }
        if (count == 0) return width / 2 to height / 2
        val midX = sumX.toDouble() / count
        val midZ = sumZ.toDouble() / count
        var best = -1
        var bestDepth = -1
        var bestOff = Double.MAX_VALUE
        for (p in 0 until n) {
            val d = distance[p]
            if (d < 0) continue
            val off = abs(p % width - midX) + abs(p / width - midZ)
            if (d > bestDepth || d == bestDepth && off < bestOff) {
                best = p
                bestDepth = d
                bestOff = off
            }
        }
        return (best % width) to (best / width)
    }

    private inline fun forNeighbours(p: Int, width: Int, height: Int, each: (Int) -> Unit) {
        val x = p % width
        val z = p / width
        if (x > 0) each(p - 1)
        if (x < width - 1) each(p + 1)
        if (z > 0) each(p - width)
        if (z < height - 1) each(p + width)
    }

    companion object {
        /** Blocks between the points a cell's land is read at. */
        const val STEP = 32
        /** How far past its grid square a cell can reach (by its nudged centre and its bent borders), in blocks. */
        private const val MARGIN = 832
        /** A patch smaller than this many points (about 360 blocks across) isn't a region of its own. */
        private const val MIN_SIZE = 128
        private const val MAX_REGIONS = 100
        const val OUTSIDE: Byte = -1
        private const val OUT = -1
        private const val SEAM = -2

        /** Region ids from 100 up are the caves below a cell, not its lands. */
        const val CAVE_SLOT = 100
        const val DEEP_SLOT = 110
        const val CRYSTAL_SLOT = 120

        /** A region's id: its cell, and its place in it. */
        fun regionId(cell: Long, slot: Int): Long =
            ((RegionCells.keyX(cell).toLong() and 0xFFFFFFL) shl 40) or ((RegionCells.keyZ(cell).toLong() and 0xFFFFFFL) shl 16) or slot.toLong()

        fun slotOf(id: Long): Int = (id and 0xFFFFL).toInt()

        /** The cell a region id belongs to. */
        fun cellOf(id: Long): Long {
            val x = ((id shr 40) and 0xFFFFFFL).toInt().let { if (it and 0x800000 != 0) it or -0x1000000 else it }
            val z = ((id shr 16) and 0xFFFFFFL).toInt().let { if (it and 0x800000 != 0) it or -0x1000000 else it }
            return RegionCells.key(x, z)
        }

        /** Analysis runs on one thread of its own, a little below the server's priority. */
        private val WORKER: ExecutorService = Executors.newSingleThreadExecutor {
            Thread(it, "Guildmark regions").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }

        private val MAPS = WeakHashMap<ServerLevel, RegionMap>()

        fun of(level: ServerLevel): RegionMap = synchronized(MAPS) { MAPS.getOrPut(level) { RegionMap(level) } }

        /** The server stopped: let go of every world's regions. */
        fun clear() = synchronized(MAPS) { MAPS.clear() }
    }
}
