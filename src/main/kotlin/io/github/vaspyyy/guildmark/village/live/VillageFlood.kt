package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Spot
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs

/** The four ways across the ground, by the step they take (north is -z). */
internal object Ways {
    val ALL: List<Direction> = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    /** The way a step of ([dx], [dz]) goes. */
    fun of(dx: Int, dz: Int): Direction = when {
        dz < 0 -> Direction.NORTH
        dx > 0 -> Direction.EAST
        dz > 0 -> Direction.SOUTH
        else -> Direction.WEST
    }
}

/**
 * The inside of one building, found by flooding from the cells its people sleep or work in: out through every open cell that
 * has a roof over it, never through a wall (a bottom slab is one: a floor of them is not flooded under), and never out under the
 * open sky. A door that has the open sky on its far side is a door of the building. So is one that has only a porch or a yard
 * under the eaves beyond; [roomBeyond] tells that from a room, which is shut in, and through whose door the flood goes on. It
 * reads a cell once (the cells it has seen are marked), asks the world nothing else but the height of a column once, and stops
 * at [MAX_CELLS]. It is a queue that keeps its place, so [run] can stop for the tick and go on in the next.
 */
internal class Flood {
    private var x0 = 0
    private var y0 = 0
    private var z0 = 0
    private var w = 0
    private var d = 0
    private var h = 0

    /** By cell: [LOOKED] at, in the [CELL]s of the flood, and in a [FAILED] region (what a look beyond a door found to be no room). */
    private var seen = ByteArray(0)
    private var tops = IntArray(0)
    private var queue = IntArray(0)
    private var head = 0
    private var tail = 0

    /** The look beyond a door: the cells it has found, and which look (by number) last passed each cell. */
    private var around = IntArray(0)
    private val ring = IntArray(ROOM_CELLS + 2)
    private var looks = 0

    /** The cells found to be inside, and the box round them. */
    var cells = 0
        private set
    var minX = 0
        private set
    var maxX = 0
        private set
    var minY = 0
        private set
    var maxY = 0
        private set
    var minZ = 0
        private set
    var maxZ = 0
        private set

    /**
     * What the flood met at the sides of its cells, to tell a room from a roof on posts: [solid] sides are walls and doors, [loose] ones
     * posts and fences and open ground under the sky.
     */
    var solid = 0
        private set
    var loose = 0
        private set

    /** Whether a cell the flood needed was in a chunk that is not loaded; whether it stopped at [MAX_CELLS]. */
    var unloaded = false
        private set
    var capped = false
        private set

    /** The doors out to the open sky (at most [MAX_DOORS]), nearest the seeds first. */
    val doors = ArrayList<CityPlaces.Door>()

    /** Starts afresh on the box from ([ax], [ay], [az]) to ([bx], [by], [bz]); nothing outside it is looked at. */
    fun begin(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) {
        x0 = minOf(ax, bx)
        y0 = minOf(ay, by)
        z0 = minOf(az, bz)
        w = minOf(maxOf(ax, bx) - x0 + 1, MAX_SIDE)
        d = minOf(maxOf(az, bz) - z0 + 1, MAX_SIDE)
        h = minOf(maxOf(ay, by) - y0 + 1, MAX_HIGH)
        val n = w * d * h
        if (seen.size < n) {
            seen = ByteArray(n)
            queue = IntArray(n)
            around = IntArray(n)
        } else {
            java.util.Arrays.fill(seen, 0, n, 0.toByte())
        }
        val columns = w * d
        if (tops.size < columns) tops = IntArray(columns)
        java.util.Arrays.fill(tops, 0, columns, UNSET)
        head = 0
        tail = 0
        cells = 0
        minX = Int.MAX_VALUE
        maxX = Int.MIN_VALUE
        minY = Int.MAX_VALUE
        maxY = Int.MIN_VALUE
        minZ = Int.MAX_VALUE
        maxZ = Int.MIN_VALUE
        solid = 0
        loose = 0
        unloaded = false
        capped = false
        doors.clear()
    }

    private fun inside(x: Int, y: Int, z: Int): Boolean = x >= x0 && x < x0 + w && y >= y0 && y < y0 + h && z >= z0 && z < z0 + d

    private fun index(x: Int, y: Int, z: Int): Int = ((y - y0) * d + (z - z0)) * w + (x - x0)

    /** The height of the column ([x], [z]); read once if it is in the box. */
    private fun topOf(t: Terrain, x: Int, z: Int): Int {
        if (x < x0 || x >= x0 + w || z < z0 || z >= z0 + d) return t.top(x, z)
        val i = (z - z0) * w + (x - x0)
        var top = tops[i]
        if (top == UNSET) {
            top = t.top(x, z)
            tops[i] = top
        }
        return top
    }

    /**
     * Whether the cell is solid enough to be a wall (a ladder is no wall; a door is dealt with before this). A bottom slab is one too:
     * a floor of them has a crawl space under it that leads out under the eaves, which no room is.
     */
    private fun wall(c: Int): Boolean = c and (Cls.FLOOR or Cls.HALF) != 0 && c and Cls.LADDER == 0

    /** Puts the cell ([x], [y], [z]) in the queue as a start, if it is in the box, new, open and roofed. */
    fun seed(t: Terrain, x: Int, y: Int, z: Int) {
        if (!inside(x, y, z)) return
        val i = index(x, y, z)
        if (seen[i].toInt() != 0) return
        seen[i] = 1
        val s = t.state(x, y, z)
        if (s == null) {
            unloaded = true
            return
        }
        if (wall(Cls.of(s)) || Cls.of(s) and Cls.DOOR != 0) return
        val top = topOf(t, x, z)
        if (top == Terrain.UNLOADED) {
            unloaded = true
            return
        }
        if (y < top) add(x, y, z, i)
    }

    private fun add(x: Int, y: Int, z: Int, i: Int) {
        seen[i] = (seen[i].toInt() or LOOKED or CELL).toByte()
        queue[tail++] = i
        cells++
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        if (z < minZ) minZ = z
        if (z > maxZ) maxZ = z
    }

    /** Goes on until the queue is empty (true) or [deadline] (a `System.nanoTime()`) has passed (false: call again). */
    fun run(t: Terrain, deadline: Long): Boolean {
        var n = 0
        while (head < tail) {
            if (cells >= MAX_CELLS) {
                capped = true
                head = tail
                return true
            }
            if ((++n and 31) == 0 && System.nanoTime() >= deadline) return false
            val i = queue[head++]
            val x = x0 + i % w
            val z = z0 + (i / w) % d
            val y = y0 + i / (w * d)
            for (k in 0 until 6) {
                val dx = DX[k]
                val dy = DY[k]
                val dz = DZ[k]
                val nx = x + dx
                val ny = y + dy
                val nz = z + dz
                if (!inside(nx, ny, nz)) {
                    // Open ground that runs on past what is looked at is no room
                    if (dy == 0 && openBeyond(t, nx, ny, nz)) loose++
                    continue
                }
                val j = index(nx, ny, nz)
                val m = seen[j].toInt()
                if (m != 0) {
                    // Ground that a look found to be no room is open to us as well, where we meet it at the side
                    if (m and FAILED != 0 && dy == 0) loose++
                    continue
                }
                seen[j] = LOOKED.toByte()
                visit(t, x, y, z, nx, ny, nz, dx, dy, dz, j)
            }
        }
        return true
    }

    /** Whether the cell outside the box is one a body fits in (not a wall, not a door, and loaded). */
    private fun openBeyond(t: Terrain, x: Int, y: Int, z: Int): Boolean {
        val s = t.state(x, y, z) ?: return false
        val c = Cls.of(s)
        return c and Cls.DOOR == 0 && !wall(c)
    }

    private fun visit(t: Terrain, x: Int, y: Int, z: Int, nx: Int, ny: Int, nz: Int, dx: Int, dy: Int, dz: Int, j: Int) {
        val s = t.state(nx, ny, nz)
        if (s == null) {
            unloaded = true
            return
        }
        val c = Cls.of(s)
        if (c and Cls.DOOR != 0) {
            if (dy == 0) {
                solid++
                if (c and Cls.DOOR_LOWER != 0) door(t, nx, ny, nz, dx, dz)
            }
            return
        }
        if (wall(c)) {
            if (dy == 0) {
                if (c and Cls.POST != 0) loose++ else solid++
            }
            return
        }
        val top = topOf(t, nx, nz)
        if (top == Terrain.UNLOADED) {
            unloaded = true
            return
        }
        if (ny < top) {
            add(nx, ny, nz, j)
        } else if (dy == 0) {
            loose++
            if (doors.size < MAX_DOORS) doorway(t, x, y, z, nx, ny, nz, dx, dz)
        }
    }

    /** A door's lower half at ([px], [py], [pz]), reached by stepping ([sx], [sz]) from a cell inside. */
    private fun door(t: Terrain, px: Int, py: Int, pz: Int, sx: Int, sz: Int) {
        val ox = px + sx
        val oz = pz + sz
        val beyond = t.state(ox, py, oz)
        if (beyond == null) {
            unloaded = true
            return
        }
        // A wall behind the door leads nowhere
        if (wall(Cls.of(beyond))) return
        val top = topOf(t, ox, oz)
        if (top == Terrain.UNLOADED) {
            unloaded = true
            return
        }
        if (py >= top || !inside(ox, py, oz)) {
            addDoor(px, py, pz, sx, sz)
            return
        }
        val m = seen[index(ox, py, oz)].toInt()
        // Where the flood is already, the door is a way through; where a look found no room, it is a door of the building
        if (m and CELL != 0) return
        if (m and FAILED != 0 || !roomBeyond(t, ox, py, oz)) addDoor(px, py, pz, sx, sz)
    }

    private fun addDoor(px: Int, py: Int, pz: Int, sx: Int, sz: Int) {
        if (doors.size < MAX_DOORS) doors.add(CityPlaces.Door(BlockPos(px, py, pz), Ways.of(-sx, -sz)))
    }

    /**
     * Whether the roofed ground at ([sx], [sy], [sz]), beyond a door, is a room of the building: a look at it, out through its open
     * cells as the flood goes, but never through a door, and at most [ROOM_CELLS] of them. A porch, or a yard under the eaves, has
     * the open sky at its sides (or a fence, or the edge of what is looked at) where a room has walls: it is a room if it is shut
     * in all round and has walls and doors on at least twice as many sides as it has open ones. A room's cells join the flood; the
     * cells of what is no room are marked, so that the next door to it is told at once, and the flood, meeting it at its side,
     * counts it open.
     */
    private fun roomBeyond(t: Terrain, sx: Int, sy: Int, sz: Int): Boolean {
        if (looks == Int.MAX_VALUE) {
            java.util.Arrays.fill(around, 0)
            looks = 0
        }
        val look = ++looks
        val start = index(sx, sy, sz)
        around[start] = look
        ring[0] = start
        var n = 1
        var next = 0
        var walls = 0
        var gaps = 0
        var shut = true
        while (next < n) {
            val i = ring[next++]
            val x = x0 + i % w
            val z = z0 + (i / w) % d
            val y = y0 + i / (w * d)
            for (k in 0 until 6) {
                val dx = DX[k]
                val dy = DY[k]
                val dz = DZ[k]
                val nx = x + dx
                val ny = y + dy
                val nz = z + dz
                if (!inside(nx, ny, nz)) {
                    shut = false
                    continue
                }
                val j = index(nx, ny, nz)
                if (around[j] == look) continue
                val m = seen[j].toInt()
                if (m and CELL != 0) continue
                if (m and FAILED != 0) {
                    shut = false
                    continue
                }
                around[j] = look
                val s = t.state(nx, ny, nz)
                if (s == null) {
                    unloaded = true
                    shut = false
                    continue
                }
                val c = Cls.of(s)
                if (c and Cls.DOOR != 0) {
                    if (dy == 0) walls++
                    continue
                }
                if (wall(c)) {
                    if (dy == 0) {
                        if (c and Cls.POST != 0) gaps++ else walls++
                    }
                    continue
                }
                val top = topOf(t, nx, nz)
                if (top == Terrain.UNLOADED) {
                    unloaded = true
                    shut = false
                    continue
                }
                if (ny < top) {
                    ring[n++] = j
                    if (n > ROOM_CELLS) {
                        mark(n, FAILED)
                        return false
                    }
                } else if (dy == 0) {
                    gaps++
                }
            }
        }
        if (!shut || walls < 2 * gaps) {
            mark(n, FAILED)
            return false
        }
        for (r in 0 until n) {
            val i = ring[r]
            add(x0 + i % w, y0 + i / (w * d), z0 + (i / w) % d, i)
        }
        return true
    }

    /** Marks the first [n] cells of the look with [bit]. */
    private fun mark(n: Int, bit: Int) {
        for (r in 0 until n) {
            val i = ring[r]
            seen[i] = (seen[i].toInt() or bit).toByte()
        }
    }

    /**
     * A way out with no door in it, at the ground: a roofed cell ([x], [y], [z]) next to an open one ([nx], [ny], [nz]) under the sky, with a floor
     * under both and room to walk through. The cell inside is the door.
     */
    private fun doorway(t: Terrain, x: Int, y: Int, z: Int, nx: Int, ny: Int, nz: Int, sx: Int, sz: Int) {
        for (door in doors) if (abs(door.pos.x - x) + abs(door.pos.z - z) <= 2) return
        val under = t.state(x, y - 1, z) ?: return
        if (Cls.of(under) and Cls.FLOOR == 0) return
        val outUnder = t.state(nx, ny - 1, nz) ?: return
        if (Cls.of(outUnder) and Cls.FLOOR == 0) return
        val room = t.state(x, y + 1, z) ?: return
        if (Cls.of(room) and Cls.FLOOR != 0) return
        val outRoom = t.state(nx, ny + 1, nz) ?: return
        if (Cls.of(outRoom) and Cls.FLOOR != 0) return
        doors.add(CityPlaces.Door(BlockPos(x, y, z), Ways.of(-sx, -sz)))
    }

    companion object {
        /** The most cells one building's inside may have, the doors it keeps, and the biggest box a flood may look in. */
        const val MAX_CELLS = 2400
        const val MAX_DOORS = 4
        const val MAX_SIDE = 48
        const val MAX_HIGH = 40

        /** The most cells a look beyond a door follows: a place bigger than that is not taken for a room of the building. */
        const val ROOM_CELLS = 160

        private const val LOOKED = 1
        private const val CELL = 2
        private const val FAILED = 8
        private const val UNSET = Int.MAX_VALUE
        private val DX = intArrayOf(1, -1, 0, 0, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1, 0, 0)
        private val DZ = intArrayOf(0, 0, 0, 0, 1, -1)
    }
}

/**
 * What stands in a box of the world: the beds, seats, tables, stores and work blocks, the ladders, the Archer Posts and the
 * counters of polished andesite. It reads every cell of the box once, and keeps its place like [Flood] does.
 */
internal class Scanner {
    private var x0 = 0
    private var y0 = 0
    private var z0 = 0
    private var x1 = -1
    private var y1 = -1
    private var z1 = -1
    private var cx = 0
    private var cy = 0
    private var cz = 0

    val beds = ArrayList<BlockPos>()
    val seats = ArrayList<Spot>()
    val tables = ArrayList<BlockPos>()
    val stores = ArrayList<BlockPos>()
    val stations = ArrayList<CityPlaces.Station>()
    val posts = ArrayList<BlockPos>()

    /** Of the blocks that tell what a building is: polished andesite (a counter), hay, and each work block by its place in [Cls.STATIONS]. */
    var andesite = 0
        private set
    var hay = 0
        private set
    private val counted = IntArray(Cls.STATIONS.size)

    /** The ladders by column: (x, z) as a `BlockPos.asLong()` with y 0, and the lowest and highest rung and the way the rungs face. */
    private val rungs = HashMap<Long, IntArray>()

    fun begin(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) {
        x0 = minOf(ax, bx)
        y0 = minOf(ay, by)
        z0 = minOf(az, bz)
        x1 = maxOf(ax, bx)
        y1 = maxOf(ay, by)
        z1 = maxOf(az, bz)
        cx = x0
        cy = y0
        cz = z0
        beds.clear()
        seats.clear()
        tables.clear()
        stores.clear()
        stations.clear()
        posts.clear()
        andesite = 0
        hay = 0
        java.util.Arrays.fill(counted, 0)
        rungs.clear()
    }

    /** How many of work block [m] stand in the box (all of them, though only the first few are listed). */
    fun count(m: M): Int {
        val k = Cls.STATIONS.indexOf(m)
        return if (k < 0) 0 else counted[k]
    }

    /** Goes on until the box is read (true) or [deadline] has passed (false: call again). */
    fun run(t: Terrain, deadline: Long): Boolean {
        var n = 0
        while (cy <= y1) {
            while (cz <= z1) {
                while (cx <= x1) {
                    if ((++n and 63) == 0 && System.nanoTime() >= deadline) return false
                    cell(t, cx, cy, cz)
                    cx++
                }
                cx = x0
                cz++
            }
            cz = z0
            cy++
        }
        return true
    }

    private fun cell(t: Terrain, x: Int, y: Int, z: Int) {
        val s = t.state(x, y, z) ?: return
        if (s.isAir) return
        val c = Cls.of(s)
        if (c and Cls.BED_HEAD != 0) beds.add(BlockPos(x, y, z))
        if (c and Cls.LADDER != 0) rung(x, y, z, s.getValue(LadderBlock.FACING))
        if (c and Cls.SEAT != 0 && seats.size < MAX_SEATS) seat(t, x, y, z, s)?.let { seats.add(it) }
        when (val f = Cls.feature(c)) {
            0 -> {}
            Cls.F_TABLE -> if (tables.size < MAX_TABLES) tables.add(BlockPos(x, y, z))
            Cls.F_STORE -> if (stores.size < MAX_STORES) stores.add(BlockPos(x, y, z))
            Cls.F_POST -> posts.add(BlockPos(x, y, z))
            Cls.F_ANDESITE -> andesite++
            else -> {
                val m = Cls.station(c) ?: return
                val k = f - Cls.F_STATION
                counted[k]++
                if (m == M.HAY) hay++
                else if (counted[k] <= MAX_EACH && stations.size < MAX_STATIONS) stations.add(CityPlaces.Station(BlockPos(x, y, z), m))
            }
        }
    }

    private fun rung(x: Int, y: Int, z: Int, facing: Direction) {
        val key = BlockPos.asLong(x, 0, z)
        val r = rungs[key]
        if (r == null) rungs[key] = intArrayOf(y, y, facing.ordinal)
        else {
            if (y < r[0]) r[0] = y
            if (y > r[1]) r[1] = y
        }
    }

    /** The ladders found, one for each column of rungs. */
    fun ladders(): List<CityPlaces.Ladder> = rungs.entries.sortedBy { it.key }.map { (key, r) ->
        CityPlaces.Ladder(BlockPos.getX(key), BlockPos.getZ(key), r[0], r[1], Direction.entries[r[2]])
    }

    companion object {
        const val MAX_SEATS = 24
        const val MAX_TABLES = 8
        const val MAX_STORES = 16
        const val MAX_STATIONS = 24
        const val MAX_EACH = 6

        /**
         * The seat a bottom stair at ([x], [y], [z]) is, if it is one: with room above it for a body, a way to step off at its front
         * or sides, and not a step in a stair (which has another of its own way above it in front or below it behind). A cell it
         * needs that is in a chunk that is not loaded makes it none. It faces away from its back, as `Places.seatFacing` has it.
         */
        fun seat(t: Terrain, x: Int, y: Int, z: Int, s: BlockState): Spot? {
            val above = t.state(x, y + 1, z) ?: return null
            if (Cls.of(above) and Cls.FREE == 0) return null
            val higher = t.state(x, y + 2, z) ?: return null
            if (Cls.of(higher) and Cls.FREE == 0) return null
            val back = s.getValue(StairBlock.FACING)
            val front = back.opposite
            var access = false
            for (side in listOf(front, front.clockWise, front.counterClockWise)) {
                val ax = x + side.stepX
                val az = z + side.stepZ
                val feet = t.state(ax, y, az) ?: continue
                val head = t.state(ax, y + 1, az) ?: continue
                val floor = t.state(ax, y - 1, az) ?: continue
                if (Cls.of(feet) and Cls.PASS != 0 && Cls.of(head) and Cls.FREE != 0 && Cls.of(floor) and Cls.FLOOR != 0) {
                    access = true
                    break
                }
            }
            if (!access) return null
            // What is not known (a chunk not loaded) is not taken to be no stair: the stairs of a roof, at the edge of the loaded ground, are no benches
            val up = t.state(x + back.stepX, y + 1, z + back.stepZ) ?: return null
            if (up.block is StairBlock && up.getValue(StairBlock.FACING) == back) return null
            val down = t.state(x + front.stepX, y - 1, z + front.stepZ) ?: return null
            if (down.block is StairBlock && down.getValue(StairBlock.FACING) == back) return null
            return Spot(BlockPos(x, y, z), front)
        }

        /** How many blocks under a bench must be solid, and how far round it the ground is looked for. */
        const val BENCH_SUPPORT = 4
        const val BENCH_AROUND = 3

        /** The most a bench may stand above the lowest ground [BENCH_AROUND] blocks round it. */
        const val BENCH_RISE = 3

        /**
         * A bench out of doors: a [seat] that stands on the ground, and not on a roof. Under it the ground is solid for [BENCH_SUPPORT]
         * blocks (a roof has the room it covers under it), and it is no more than [BENCH_RISE] blocks above the lowest ground within
         * [BENCH_AROUND] blocks of it (a stair along the edge of a roof stands on a wall, which is solid, but the ground is a step
         * away and far below). A cell it needs that is in a chunk that is not loaded makes it none.
         */
        fun bench(t: Terrain, x: Int, y: Int, z: Int, s: BlockState): Spot? {
            val spot = seat(t, x, y, z, s) ?: return null
            for (d in 1..BENCH_SUPPORT) {
                val under = t.state(x, y - d, z) ?: return null
                if (Cls.of(under) and Cls.FLOOR == 0) return null
            }
            var low = Int.MAX_VALUE
            for (dz in -BENCH_AROUND..BENCH_AROUND) {
                for (dx in -BENCH_AROUND..BENCH_AROUND) {
                    val top = t.top(x + dx, z + dz)
                    if (top == Terrain.UNLOADED) return null
                    if (top < low) low = top
                }
            }
            // A column's top is the first free cell above its highest block, so the ground itself is one below it
            return if (y - (low - 1) > BENCH_RISE) null else spot
        }
    }
}
