package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** What a column of the city is for. Streets are paved and kept clear. */
enum class Use(val street: Boolean = false) {
    OUTSIDE, CLEARING, WALL, TOWER, GATE, KEEP_WALL,
    POMERIUM(true), AVENUE(true), PLAZA(true), LANE(true), COURT(true),
    LOT, YARD, GARDEN, FREE,
}

enum class Kind {
    HOUSE, WORKSHOP, GUILD_HALL, TEMPLE, TAVERN, SMITHY, LIBRARY, MARKET_HALL, COUNTING_HOUSE, BAKERY,
    KEEP, BARRACKS, STABLES,
}

/** A job site a workshop is set up round, so a villager takes up that trade there. */
enum class Trade(val site: M) {
    MASON(M.STONECUTTER), FLETCHER(M.FLETCHING_TABLE), SHEPHERD(M.LOOM), CARTOGRAPHER(M.CARTOGRAPHY_TABLE),
    LEATHERWORKER(M.CAULDRON), FARMER(M.COMPOSTER), FISHERMAN(M.BARREL), BUTCHER(M.SMOKER),
}

class Building(val id: Int, val kind: Kind, val lot: Rect, val front: Dir, val seed: Long) {
    /** Sides, in the building's own frame (north is its front), that back onto another building. */
    val shared = BooleanArray(4)
    var trade: Trade? = null

    /** Across the front and from front to back. */
    val width: Int get() = if (front.alongX) lot.depth else lot.width
    val depth: Int get() = if (front.alongX) lot.width else lot.depth

    /** Where its front door is, across the front. */
    val door: Int get() = width / 2
}

enum class TowerKind { CORNER, MID }

/** A tower on the walls: its centre column, and for a mid-wall tower the side of the city it's on. */
class Tower(val kind: TowerKind, val x: Int, val z: Int, val side: Dir?)

/** A flight of steps up the inside of the walls: [length] steps from [x], [z], climbing toward [up]. */
class WallStair(val x: Int, val z: Int, val up: Dir, val length: Int)

/** Something small standing in the street: a lamp, a tree, a market stall, a well. */
class Prop(val kind: PropKind, val x: Int, val z: Int, val facing: Dir = Dir.N, val v: Int = 0)

enum class PropKind { LAMP, TREE, STALL, WELL, BENCH, PLANTER, TARGET, HAY_CART }

/**
 * The layout of a capital, all worked out from its [seed]: a square curtain wall with round towers at the
 * corners and gatehouses south, east and west; a keep in its own walled ward to the north; a great
 * plaza in the middle with the guild hall, temple and market round it; avenues out to the gates and
 * lanes between the quarters, lined with houses and workshops, with yards and gardens behind them.
 */
class CityPlan(val seed: Long) {
    val grid = Grid(REACH)
    val buildings = ArrayList<Building>()
    val towers = ArrayList<Tower>()
    val gates = listOf(Dir.S, Dir.E, Dir.W)
    val stairs = ArrayList<WallStair>()
    val props = ArrayList<Prop>()
    private val streets = ArrayList<Pair<Rect, Use>>()
    private val random = Random(seed)

    // The plaza's furniture: the bell, the board (its anchor and which way it faces) and the Wardfire
    val bell = Pos(11, -8)
    val board = Pos(13, -13)
    val boardFacing = Dir.W
    val wardfire = Pos(-10, 10)

    init {
        lay()
    }

    data class Pos(val x: Int, val z: Int)

    private fun lay() {
        // The land round about, the walls and the ring road inside them
        for (x in -REACH..REACH) for (z in -REACH..REACH) {
            val ring = max(abs(x), abs(z))
            grid[x, z] = when {
                ring > CLEAR -> Use.OUTSIDE
                ring > WALL_OUT -> Use.CLEARING
                ring >= WALL_IN -> Use.WALL
                ring > INNER -> Use.POMERIUM
                else -> Use.FREE
            }
        }
        // Round towers at the corners, standing out from the walls
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val cx = sx * CORNER_TOWER
            val cz = sz * CORNER_TOWER
            towers.add(Tower(TowerKind.CORNER, cx, cz, null))
            for (x in cx - 7..cx + 7) for (z in cz - 7..cz + 7) {
                if (inRound(x - cx, z - cz, CORNER_RADIUS)) grid[x, z] = Use.TOWER
            }
        }
        // A square tower halfway between each corner and the middle of each side
        for (side in Dir.entries) for (along in intArrayOf(-MID_TOWER, MID_TOWER)) {
            val r = sideRect(side, along - 3, along + 3, WALL_IN, WALL_IN + 6)
            val (cx, cz) = sidePos(side, along, WALL_IN + 3)
            towers.add(Tower(TowerKind.MID, cx, cz, side))
            grid.fill(r, Use.TOWER)
        }
        // Gatehouses: a tower each side of the gate, standing out from the wall
        for (side in gates) {
            grid.fill(sideRect(side, -8, 8, WALL_IN, WALL_IN + 7), Use.GATE)
            // The road out through the clearing
            street(sideRect(side, -2, 2, WALL_IN + 8, CLEAR), Use.LANE)
        }

        // The keep's ward, walled off against the north wall
        grid.fill(KEEP_WARD, Use.KEEP_WALL)
        grid.fill(Rect(KEEP_WARD.x0 + 2, KEEP_WARD.z0, KEEP_WARD.x1 - 2, KEEP_WARD.z1 - 2), Use.COURT)
        grid.fill(Rect(-2, KEEP_WARD.z1 - 1, 2, KEEP_WARD.z1), Use.COURT)

        // Lanes between the quarters, then the avenues over them, the keep road and the plaza
        for (sx in intArrayOf(-1, 1)) {
            street(Rect(if (sx > 0) LANE_X else -LANE_X - 2, KEEP_WARD.z0 + 4, if (sx > 0) LANE_X + 2 else -LANE_X, INNER), Use.LANE)
        }
        street(Rect(-INNER, LANE_Z, INNER, LANE_Z + 2), Use.LANE)
        street(Rect(-INNER, KEEP_ROAD, INNER, KEEP_ROAD + 2), Use.LANE)
        street(Rect(-INNER, -3, INNER, 3), Use.AVENUE)
        street(Rect(-3, 4, 3, INNER), Use.AVENUE)
        street(Rect(-3, KEEP_ROAD + 3, 3, -4), Use.AVENUE)
        street(Rect(-PLAZA, -PLAZA, PLAZA, PLAZA), Use.PLAZA)

        // The great buildings round the plaza and in the keep's ward
        special(Kind.GUILD_HALL, Rect(16, -15, 32, -4), Dir.W)
        special(Kind.TEMPLE, Rect(-32, -15, -16, -4), Dir.E)
        special(Kind.TAVERN, Rect(16, 4, 32, 15), Dir.W)
        special(Kind.LIBRARY, Rect(-32, 4, -16, 15), Dir.E)
        special(Kind.MARKET_HALL, Rect(4, KEEP_ROAD + 3, 15, -16), Dir.S)
        special(Kind.COUNTING_HOUSE, Rect(-15, KEEP_ROAD + 3, -4, -16), Dir.S)
        special(Kind.SMITHY, Rect(4, 16, 15, 32), Dir.N)
        special(Kind.BAKERY, Rect(-15, 16, -4, 32), Dir.N)
        special(Kind.KEEP, Rect(-10, KEEP_WARD.z0 + 2, 10, KEEP_WARD.z0 + 17), Dir.S)
        special(Kind.BARRACKS, Rect(KEEP_WARD.x0 + 2, KEEP_WARD.z0 + 2, KEEP_WARD.x0 + 13, KEEP_WARD.z0 + 17), Dir.E)
        special(Kind.STABLES, Rect(KEEP_WARD.x1 - 13, KEEP_WARD.z0 + 2, KEEP_WARD.x1 - 2, KEEP_WARD.z0 + 15), Dir.W)

        // Houses along every street, the busiest first
        for ((rect, _) in streets.sortedBy { it.second.ordinal - if (it.second == Use.PLAZA) 10 else 0 }) {
            for (side in Dir.entries) pack(rect, side)
        }
        packAlongWalls()

        // What's left behind the houses: gardens where there's room, yards where there isn't
        settle()
        sharedWalls()
        trades()

        stairsUpTheWalls()
        furnish()
    }

    private fun street(r: Rect, use: Use) {
        streets.add(Pair(r, use))
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            val here = grid[x, z]
            if (here == Use.FREE || here.street || (use == Use.LANE && here == Use.CLEARING)) grid[x, z] = use
        }
    }

    private fun special(kind: Kind, lot: Rect, front: Dir) {
        val b = Building(buildings.size, kind, lot, front, seed * 31 + buildings.size * 7919L)
        buildings.add(b)
        grid.fill(lot, Use.LOT, b.id)
    }

    /**
     * Houses side by side along one side of a street, each with its front on the street, a few blocks
     * wide and deep, skipping wherever there's no room.
     */
    private fun pack(street: Rect, side: Dir) {
        val front = side.opposite
        val alongX = !side.alongX
        val start = if (alongX) street.x0 else street.z0
        val end = if (alongX) street.x1 else street.z1
        var t = start
        while (t <= end - MIN_WIDTH + 1) {
            val width = MIN_WIDTH + random.nextInt(MAX_WIDTH - MIN_WIDTH + 1)
            val depth = MIN_DEPTH + random.nextInt(MAX_DEPTH - MIN_DEPTH + 1)
            val lot = fit(street, side, t, minOf(width, end - t + 1), depth)
            if (lot == null) {
                t++
                continue
            }
            val kind = if (random.nextInt(4) == 0) Kind.WORKSHOP else Kind.HOUSE
            val b = Building(buildings.size, kind, lot, front, seed * 31 + buildings.size * 7919L)
            buildings.add(b)
            grid.fill(lot, Use.LOT, b.id)
            t += if (alongX) lot.width else lot.depth
            // Now and then an alley between houses
            if (random.nextInt(3) == 0) t++
        }
    }

    /** The biggest lot up to [width] by [depth] that fits at [t] on [side] of [street], or null. */
    private fun fit(street: Rect, side: Dir, t: Int, width: Int, depth: Int): Rect? {
        for (w in width downTo MIN_WIDTH) for (d in depth downTo MIN_DEPTH) {
            val lot = when (side) {
                Dir.N -> Rect(t, street.z0 - d, t + w - 1, street.z0 - 1)
                Dir.S -> Rect(t, street.z1 + 1, t + w - 1, street.z1 + d)
                Dir.W -> Rect(street.x0 - d, t, street.x0 - 1, t + w - 1)
                Dir.E -> Rect(street.x1 + 1, t, street.x1 + d, t + w - 1)
            }
            if (grid.allFree(lot)) return lot
        }
        return null
    }

    /** Houses with their backs to the ring road along the walls, where the quarters run out to it. */
    private fun packAlongWalls() {
        for (side in Dir.entries) {
            val r = sideRect(side, -INNER, INNER, INNER + 1, INNER + 4)
            pack(r, side.opposite)
        }
    }

    /** Free ground left over becomes gardens (if there's room to plant) or paved yards. */
    private fun settle() {
        val seen = HashSet<Long>()
        for (x in -INNER..INNER) for (z in -INNER..INNER) {
            if (grid[x, z] != Use.FREE || !seen.add(key(x, z))) continue
            // Flood the patch
            val patch = ArrayList<Pos>()
            val queue = ArrayDeque<Pos>()
            queue.add(Pos(x, z))
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                patch.add(p)
                for (d in Dir.entries) {
                    val nx = p.x + d.dx
                    val nz = p.z + d.dz
                    if (abs(nx) <= INNER && abs(nz) <= INNER && grid[nx, nz] == Use.FREE && seen.add(key(nx, nz))) queue.add(Pos(nx, nz))
                }
            }
            val minX = patch.minOf { it.x }
            val maxX = patch.maxOf { it.x }
            val minZ = patch.minOf { it.z }
            val maxZ = patch.maxOf { it.z }
            val garden = patch.size >= 16 && maxX - minX >= 3 && maxZ - minZ >= 3
            for (p in patch) grid[p.x, p.z] = if (garden) Use.GARDEN else Use.YARD
            if (garden && patch.size >= 30) {
                // A tree in the middle of a big enough garden
                val cx = (minX + maxX) / 2
                val cz = (minZ + maxZ) / 2
                if (grid[cx, cz] == Use.GARDEN && Dir.entries.all { grid[cx + it.dx * 2, cz + it.dz * 2] == Use.GARDEN }) {
                    props.add(Prop(PropKind.TREE, cx, cz, v = random.nextInt(3)))
                }
            }
        }
    }

    /** Note which sides of each building back onto another, so they get no windows or doors. */
    private fun sharedWalls() {
        for (b in buildings) {
            val frame = Frame(NullCanvas, b.lot, b.front)
            for (local in Dir.entries) {
                // Walk the cells just outside that side
                var touching = 0
                var total = 0
                val (us, vs) = when (local) {
                    Dir.N -> Pair(0 until frame.w, -1..-1)
                    Dir.S -> Pair(0 until frame.w, frame.d..frame.d)
                    Dir.W -> Pair(-1..-1, 0 until frame.d)
                    Dir.E -> Pair(frame.w..frame.w, 0 until frame.d)
                }
                for (u in us) for (v in vs) {
                    total++
                    val x = frame.x(u, v)
                    val z = frame.z(u, v)
                    if (grid.inside(x, z) && grid[x, z] == Use.LOT && grid.owner(x, z) != b.id) touching++
                }
                b.shared[local.ordinal] = touching * 2 > total
            }
        }
    }

    /** Some houses are workshops: give each a trade, so the city has every kind of villager. */
    private fun trades() {
        val shops = buildings.filter { it.kind == Kind.WORKSHOP }
        for ((i, b) in shops.withIndex()) b.trade = Trade.entries[(i + random.nextInt(2)) % Trade.entries.size]
    }

    /** Two flights up to the wall walk on each side, beside the ring road. */
    private fun stairsUpTheWalls() {
        for (side in Dir.entries) for (along in intArrayOf(-44, 44)) {
            // Climbing toward the corner, away from the middle of the side
            val climb = sideDir(side, if (along > 0) 1 else -1)
            val (x, z) = sidePos(side, along - (STAIR_STEPS - 1) / 2 * (if (along > 0) 1 else -1), WALL_IN - 1)
            stairs.add(WallStair(x, z, climb, STAIR_STEPS))
        }
    }

    /** Lamps down the avenues and round the plaza, and the plaza's trees, stalls and well. */
    private fun furnish() {
        // Lamps down both edges of each avenue, clear of the doors facing them
        for (x in -INNER..INNER step 8) for (z in intArrayOf(-3, 3)) lampAt(x, z)
        for (z in -INNER..INNER step 8) for (x in intArrayOf(-3, 3)) lampAt(x, z)
        for (x in -INNER..INNER step 10) lampAt(x, KEEP_ROAD + 2)
        // The plaza: planted trees at its corners, stalls round the edge
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            // The quest board takes one corner
            if (abs(sx * 12 - board.x) <= 2 && abs(sz * 12 - board.z) <= 2) continue
            props.add(Prop(PropKind.PLANTER, sx * 12, sz * 12))
        }
        val stallSpots = listOf(Pos(-12, 6), Pos(-12, -7), Pos(7, 12), Pos(-7, 12), Pos(-7, -12), Pos(7, -12))
        for ((i, p) in stallSpots.withIndex()) {
            val facing = when {
                abs(p.x) >= abs(p.z) -> if (p.x < 0) Dir.E else Dir.W
                else -> if (p.z < 0) Dir.S else Dir.N
            }
            props.add(Prop(PropKind.STALL, p.x, p.z, facing, i))
        }
        // The keep's courtyard: a well and archery butts
        props.add(Prop(PropKind.WELL, -8, KEEP_WARD.z1 - 4))
        for (x in intArrayOf(-26, -24)) props.add(Prop(PropKind.TARGET, x, KEEP_WARD.z1 - 5, Dir.S))
        props.add(Prop(PropKind.HAY_CART, 15, KEEP_WARD.z1 - 5, Dir.W))
    }

    private fun lampAt(x: Int, z: Int) {
        val use = grid[x, z]
        if (!use.street || use == Use.PLAZA && (abs(x) < 4 || abs(z) < 4)) return
        // Not in front of a door
        for (b in buildings) {
            val frame = Frame(NullCanvas, b.lot, b.front)
            val dx = frame.x(b.door, -1)
            val dz = frame.z(b.door, -1)
            if (abs(dx - x) <= 1 && abs(dz - z) <= 1) return
        }
        props.add(Prop(PropKind.LAMP, x, z))
    }

    /** A rectangle on [side] of the city: [a0]..[a1] along the wall, [n0]..[n1] out from the middle. */
    fun sideRect(side: Dir, a0: Int, a1: Int, n0: Int, n1: Int): Rect = when (side) {
        Dir.N -> Rect(a0, -n1, a1, -n0)
        Dir.S -> Rect(a0, n0, a1, n1)
        Dir.W -> Rect(-n1, a0, -n0, a1)
        Dir.E -> Rect(n0, a0, n1, a1)
    }

    fun sidePos(side: Dir, along: Int, out: Int): Pos = when (side) {
        Dir.N -> Pos(along, -out)
        Dir.S -> Pos(along, out)
        Dir.W -> Pos(-out, along)
        Dir.E -> Pos(out, along)
    }

    /** The direction along [side]'s wall in which "along" grows ([sign] 1) or shrinks. */
    fun sideDir(side: Dir, sign: Int): Dir = if (side.alongX) (if (sign > 0) Dir.S else Dir.N) else (if (sign > 0) Dir.E else Dir.W)

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    companion object {
        /** The furthest any part of a city reaches from its middle. */
        const val REACH = 72
        /** How far round the walls the land is levelled. */
        const val CLEAR = 71
        /** The curtain wall's inner and outer faces. */
        const val WALL_IN = 59
        const val WALL_OUT = 62
        /** The ring road runs inside the walls; the quarters reach this far. */
        const val INNER = 54
        const val PLAZA = 15
        const val CORNER_TOWER = 63
        const val CORNER_RADIUS = 6.5
        const val MID_TOWER = 31
        const val LANE_X = 33
        const val LANE_Z = 33
        const val KEEP_ROAD = -30
        val KEEP_WARD = Rect(-32, -58, 32, -31)
        const val STAIR_STEPS = 10
        private const val MIN_WIDTH = 5
        private const val MAX_WIDTH = 9
        private const val MIN_DEPTH = 6
        private const val MAX_DEPTH = 10

        fun inRound(dx: Int, dz: Int, radius: Double): Boolean = dx * dx + dz * dz <= radius * radius
    }
}

/** Uses and owners of every column of the city. */
class Grid(val reach: Int) {
    private val size = reach * 2 + 1
    private val uses = ByteArray(size * size) { Use.OUTSIDE.ordinal.toByte() }
    private val owners = IntArray(size * size) { -1 }

    fun inside(x: Int, z: Int): Boolean = abs(x) <= reach && abs(z) <= reach

    operator fun get(x: Int, z: Int): Use = if (inside(x, z)) Use.entries[uses[index(x, z)].toInt()] else Use.OUTSIDE

    operator fun set(x: Int, z: Int, use: Use) {
        if (inside(x, z)) uses[index(x, z)] = use.ordinal.toByte()
    }

    fun owner(x: Int, z: Int): Int = if (inside(x, z)) owners[index(x, z)] else -1

    fun fill(r: Rect, use: Use, owner: Int = -1) {
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            if (!inside(x, z)) continue
            uses[index(x, z)] = use.ordinal.toByte()
            owners[index(x, z)] = owner
        }
    }

    fun allFree(r: Rect): Boolean {
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) if (this[x, z] != Use.FREE) return false
        return true
    }

    private fun index(x: Int, z: Int): Int = (x + reach) * size + (z + reach)
}

/** Draws nothing: for working out where a building's parts fall without drawing it. */
object NullCanvas : Canvas {
    override val clip = Rect(0, 0, -1, -1)
    override fun set(x: Int, y: Int, z: Int, b: B) {}
    override fun ground(x: Int, z: Int, surface: B, fill: B) {}
    override fun clearing(x: Int, z: Int) {}
    override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {}
    override fun board(x: Int, y: Int, z: Int, facing: Dir) {}
    override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {}
}
