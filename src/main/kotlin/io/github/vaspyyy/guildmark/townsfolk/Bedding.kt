package io.github.vaspyyy.guildmark.townsfolk

/**
 * Where a bed can be made up in someone's home (see [Furnish]), worked out from what each block is ([Look]) and the
 * home's doors, ladders and the things its people use. It knows nothing of the game, so the same rules run in the
 * world and offline against a city's plan.
 *
 * A bed goes only into air, on a full solid floor, under a roof with room over it. Never in a ladder's column or
 * before its rungs, in a doorway or just inside or outside it, or in front of anything people use (a work block, a
 * chest or a barrel, a seat); never cutting any of a floor off from the way in, nor leaving a bed (new or old) with
 * no side to get into it from, or something people use with no side to get at it from. Its head goes against a
 * wall, and its length along one where it can, out of the way of the ladder and the door. Upper floors come first
 * (people climb up to bed), then the ground floor.
 */
object Bedding {
    /** Air: a bed can go in. */
    const val EMPTY = 1

    /** Nothing to trip on at the feet: air, a carpet, a flower. */
    const val OPEN = 2

    /** Nothing at all in the way: room for a head, or over a bed. */
    const val CLEAR = 4

    /** Something to stand on. */
    const val GROUND = 8

    /** A full solid block: a bed can stand on it, and its head go against it. */
    const val FULL = 16

    const val BED = 32
    const val DOOR = 64
    const val LADDER = 128

    /** Something people use: a work block, a chest or a barrel, a seat. */
    const val USED = 256

    /**
     * Where to stand to use it, for something used that has a front (an oven's mouth, a chest's lid, a seat's
     * front): the side as 1 + its place in N, E, S, W, shifted up this far. Without one, any side does.
     */
    const val FRONT_SHIFT = 9

    /** What the block at x, y, z is, as the bits above. */
    fun interface Look {
        fun at(x: Int, y: Int, z: Int): Int
    }

    /** A door's lower half at [x], [y], [z], opening a step toward [dx], [dz] (its inside). */
    class Door(val x: Int, val y: Int, val z: Int, val dx: Int, val dz: Int)

    /** A ladder's column at [x], [z] from [bottom] to [top]; it's climbed from (and left onto) the block a step toward [dx], [dz]. */
    class Ladder(val x: Int, val z: Int, val bottom: Int, val top: Int, val dx: Int, val dz: Int)

    /**
     * A home: its box (all inclusive), the standing height of its ground floor and of any others known, its doors
     * and ladders, and the blocks its people use ([used], each x, y, z and, if it has a front, the step to it).
     * [seed] picks between places as good as each other, so the same home always gets the same answer.
     */
    class Home(
        val x0: Int, val y0: Int, val z0: Int, val x1: Int, val y1: Int, val z1: Int,
        val ground: Int, val floors: List<Int>, val doors: List<Door>, val ladders: List<Ladder>,
        val used: List<IntArray>, val seed: Long,
    )

    /** Where a bed goes: its head at [x], [y], [z], pointing [dx], [dz]; the foot is the block behind it. */
    class Spot(val x: Int, val y: Int, val z: Int, val dx: Int, val dz: Int) {
        val footX: Int get() = x - dx
        val footZ: Int get() = z - dz

        override fun toString(): String = "$x $y $z toward $dx,$dz"
    }

    private val SIDES = arrayOf(intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0))

    /** The [FRONT_SHIFT] bits for a front a step toward [dx], [dz]. */
    fun front(dx: Int, dz: Int): Int {
        val side = SIDES.indexOfFirst { it[0] == dx && it[1] == dz }
        return if (side < 0) 0 else (side + 1) shl FRONT_SHIFT
    }

    /** The best place in [home] for a bed, or null if there's no room for one anywhere in it. */
    fun choose(home: Home, look: Look): Spot? {
        val view = View(home, look)
        var best: Spot? = null
        var bestKey = DoubleArray(0)
        for (y in floors(home, view)) {
            val floor = Floor(home, view, y)
            if (!floor.entered) continue
            val rank = if (y > home.ground) 0.0 else if (y == home.ground) 1.0 else 2.0
            for (x in home.x0..home.x1) for (z in home.z0..home.z1) for ((side, d) in SIDES.withIndex()) {
                val score = floor.score(x, z, d[0], d[1]) ?: continue
                val key = doubleArrayOf(rank, -score, y.toDouble(), noise(home.seed, x, y, z, side))
                if (best == null || less(key, bestKey)) {
                    best = Spot(x, y, z, d[0], d[1])
                    bestKey = key
                }
            }
        }
        return best
    }

    /** The standing heights in [home] worth a look: those known, and wherever a ladder lets off onto a floor. */
    private fun floors(home: Home, view: View): List<Int> {
        val ys = sortedSetOf(home.ground)
        ys.addAll(home.floors)
        for (l in home.ladders) for (y in l.bottom..l.top + 1) if (view.walkable(l.x + l.dx, y, l.z + l.dz)) ys.add(y)
        return ys.toList()
    }

    private fun less(a: DoubleArray, b: DoubleArray): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    private fun noise(seed: Long, x: Int, y: Int, z: Int, side: Int): Double {
        var h = seed * -0x61c8864680b583ebL + x * 73856093L + y * 19349663L + z * 83492791L + side * 2654435761L
        h = (h xor (h ushr 31)) * 0x7FB5D329728EA185L
        h = (h xor (h ushr 27)) * -0x7F3A1F4C9C3A8E6BL
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** The blocks of a home and round it, each looked at once. */
    private class View(home: Home, private val look: Look) {
        private val x0 = home.x0 - 1
        private val z0 = home.z0 - 1
        private val y0 = minOf(home.y0, home.ground) - 2
        private val sx = home.x1 - home.x0 + 3
        private val sz = home.z1 - home.z0 + 3
        private val sy = maxOf(home.y1, home.ground) + ROOF_LOOK + 2 - y0
        private val known = IntArray(sx * sy * sz) { -1 }

        fun at(x: Int, y: Int, z: Int): Int {
            val i = x - x0
            val k = z - z0
            val j = y - y0
            if (i < 0 || k < 0 || j < 0 || i >= sx || k >= sz || j >= sy) return look.at(x, y, z)
            val index = (i * sz + k) * sy + j
            var bits = known[index]
            if (bits < 0) {
                bits = look.at(x, y, z)
                known[index] = bits
            }
            return bits
        }

        fun has(x: Int, y: Int, z: Int, bit: Int): Boolean = at(x, y, z) and bit != 0

        /** Whether someone can stand here, or pass through (a door). */
        fun walkable(x: Int, y: Int, z: Int): Boolean =
            has(x, y - 1, z, GROUND) && (has(x, y, z, OPEN) && has(x, y + 1, z, CLEAR) || has(x, y, z, DOOR))

        /** Something a bed's head can go against: a wall or a block in the way, but not a bed, a door or a ladder. */
        fun wall(x: Int, y: Int, z: Int): Boolean = at(x, y, z) and (OPEN or CLEAR or BED or DOOR or LADDER) == 0

        /** Whether something stands over [x], [y], [z] within [ROOF_LOOK] blocks of [top]: a ceiling or a roof. */
        fun roofed(x: Int, y: Int, z: Int, top: Int): Boolean {
            for (k in y + 1..top + ROOF_LOOK) if (!has(x, k, z, EMPTY)) return true
            return false
        }
    }

    /** One floor of a home at standing height [y]: where people walk, how they get in, and what must stay in reach. */
    private class Floor(val home: Home, val view: View, val y: Int) {
        private val w = home.x1 - home.x0 + 1
        private val d = home.z1 - home.z0 + 1

        private fun index(x: Int, z: Int): Int =
            if (x < home.x0 || x > home.x1 || z < home.z0 || z > home.z1) -1 else (x - home.x0) * d + (z - home.z0)

        private val walk = BooleanArray(w * d)

        /** Where no bed may go. */
        private val kept = BooleanArray(w * d)

        /** Where a bed would be in the way, if not badly: by the way in, or at the side of something used. */
        private val crowded = BooleanArray(w * d)

        /** The ways onto this floor: inside its doors, and before its ladders. */
        private val entries = ArrayList<Int>()

        /** Blocks at least one of which must stay in reach: in front of or round each thing used, and beside each bed. */
        private val groups = ArrayList<IntArray>()

        private val reach: BooleanArray
        val entered: Boolean

        init {
            for (x in home.x0..home.x1) for (z in home.z0..home.z1) walk[index(x, z)] = view.walkable(x, y, z)
            for (door in home.doors) {
                if (door.y != y) continue
                for (step in -1..1) keep(door.x + door.dx * step, door.z + door.dz * step)
                crowd(door.x + door.dx, door.z + door.dz)
                enter(door.x + door.dx, door.z + door.dz)
            }
            for (l in home.ladders) {
                if (y < l.bottom - 1 || y > l.top + 2) continue
                keep(l.x, l.z)
                keep(l.x + l.dx, l.z + l.dz)
                crowd(l.x, l.z)
                crowd(l.x + l.dx, l.z + l.dz)
                if (y in l.bottom..l.top + 1) enter(l.x + l.dx, l.z + l.dz)
            }

            // The things people use, as the plan has them and as they stand now: the block in front of each kept
            // clear, and some side of each left in reach (the front, if that's in the room)
            val fronts = HashMap<Int, IntArray?>()
            for (u in home.used) {
                if (u[1] != y) continue
                val i = index(u[0], u[2])
                if (i >= 0 && fronts[i] == null) fronts[i] = if (u.size >= 5 && (u[3] != 0 || u[4] != 0)) intArrayOf(u[3], u[4]) else null
            }
            for (x in home.x0..home.x1) for (z in home.z0..home.z1) {
                val bits = view.at(x, y, z)
                if (bits and USED == 0) continue
                val side = (bits shr FRONT_SHIFT) and 7
                val i = index(x, z)
                if (side in 1..4) fronts[i] = SIDES[side - 1] else if (i !in fronts) fronts[i] = null
            }
            for ((i, front) in fronts) {
                val x = home.x0 + i / d
                val z = home.z0 + i % d
                if (front != null) {
                    keep(x + front[0], z + front[1])
                    val ahead = index(x + front[0], z + front[1])
                    if (ahead >= 0 && walk[ahead]) {
                        groups.add(intArrayOf(ahead))
                        continue
                    }
                }
                val round = ArrayList<Int>()
                for (s in SIDES) {
                    val n = index(x + s[0], z + s[1])
                    if (n < 0 || !walk[n]) continue
                    round.add(n)
                    crowded[n] = true
                }
                if (round.isNotEmpty()) groups.add(round.toIntArray())
            }

            // The beds already made up: each must keep a side to get in from
            val seen = HashSet<Int>()
            for (x in home.x0..home.x1) for (z in home.z0..home.z1) {
                val i = index(x, z)
                if (i in seen || !view.has(x, y, z, BED)) continue
                seen.add(i)
                val other = SIDES.firstOrNull { s ->
                    val n = index(x + s[0], z + s[1])
                    n >= 0 && n !in seen && view.has(x + s[0], y, z + s[1], BED)
                }
                if (other != null) seen.add(index(x + other[0], z + other[1]))
                val round = beside(x, z, other?.get(0) ?: 0, other?.get(1) ?: 0).filter { it >= 0 && walk[it] }
                if (round.isNotEmpty()) groups.add(round.toIntArray())
            }
            reach = flood(-1, -1)
            entered = entries.any { reach[it] }
        }

        private fun keep(x: Int, z: Int) {
            val i = index(x, z)
            if (i >= 0) kept[i] = true
        }

        /** Marks the blocks round [x], [z] as in the way. */
        private fun crowd(x: Int, z: Int) {
            for (s in SIDES) {
                val i = index(x + s[0], z + s[1])
                if (i >= 0) crowded[i] = true
            }
        }

        private fun enter(x: Int, z: Int) {
            val i = index(x, z)
            if (i >= 0 && walk[i]) entries.add(i)
        }

        /**
         * The blocks to step into a bed from, for a bed at [x], [z] running a step toward [dx], [dz]: either side of
         * both its blocks (from the end of the foot is too far from the pillow). For a lone block, every side of it.
         */
        private fun beside(x: Int, z: Int, dx: Int, dz: Int): List<Int> {
            if (dx == 0 && dz == 0) return SIDES.map { index(x + it[0], z + it[1]) }
            return listOf(
                index(x - dz, z + dx), index(x + dz, z - dx),
                index(x + dx - dz, z + dz + dx), index(x + dx + dz, z + dz - dx),
            )
        }

        /** Every block in reach from the first way onto the floor, with the blocks [a] and [b] taken up. */
        private fun flood(a: Int, b: Int): BooleanArray {
            val seen = BooleanArray(w * d)
            val start = entries.firstOrNull { it != a && it != b } ?: return seen
            val queue = IntArray(w * d)
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i / d
                val z = i % d
                for (s in SIDES) {
                    val nx = x + s[0]
                    val nz = z + s[1]
                    if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue
                    val n = nx * d + nz
                    if (seen[n] || !walk[n] || n == a || n == b) continue
                    seen[n] = true
                    queue[tail++] = n
                }
            }
            return seen
        }

        /**
         * How good a bed with its head at [x], [z] pointing [dx], [dz] would be, or null if one can't go there:
         * the head against a solid block is best (a window or a piece of furniture will do), then a side along a
         * wall; in the way of the ladder, the door or something used is worse.
         */
        fun score(x: Int, z: Int, dx: Int, dz: Int): Double? {
            val fx = x - dx
            val fz = z - dz
            val head = index(x, z)
            val foot = index(fx, fz)
            if (head < 0 || foot < 0 || kept[head] || kept[foot] || !reach[head] || !reach[foot]) return null
            if (!fits(x, z) || !fits(fx, fz) || !view.wall(x + dx, y, z + dz)) return null

            // Nothing cut off: every way in still joined, everything used and every bed still in reach, this one too
            val after = flood(head, foot)
            if (entries.any { reach[it] && !after[it] }) return null
            for (g in groups) if (g.any { reach[it] } && g.none { after[it] }) return null
            if (beside(fx, fz, dx, dz).none { it >= 0 && after[it] }) return null

            var score = if (view.has(x + dx, y, z + dz, FULL)) 4.0 else 1.0
            for (s in arrayOf(intArrayOf(-dz, dx), intArrayOf(dz, -dx))) {
                val a = view.wall(x + s[0], y, z + s[1])
                val b = view.wall(fx + s[0], y, fz + s[1])
                score += if (a && b) 3.0 else if (a || b) 1.0 else 0.0
                // Beds side by side line up, or keep apart
                if (view.has(x + s[0], y, z + s[1], BED) != view.has(fx + s[0], y, fz + s[1], BED)) score -= 2.0
            }
            if (view.wall(fx - dx, y, fz - dz)) score += 0.5
            if (view.has(fx - dx, y, fz - dz, BED)) score -= 1.0
            if (crowded[head] || crowded[foot]) score -= 2.0
            return score
        }

        /** Whether half a bed fits at [x], [z]: into air, on a full floor, with room over it and a roof over that. */
        private fun fits(x: Int, z: Int): Boolean =
            view.has(x, y, z, EMPTY) && view.has(x, y - 1, z, FULL) && view.has(x, y + 1, z, CLEAR) && view.roofed(x, y + 1, z, home.y1)
    }

    /** How far over a home's top to look for a roof. */
    private const val ROOF_LOOK = 4
}
