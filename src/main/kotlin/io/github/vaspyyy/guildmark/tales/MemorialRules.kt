package io.github.vaspyyy.guildmark.tales

import kotlin.math.abs

/**
 * Where a memorial may stand (living-world/stories/design.md 6.4, 14.10): the safe-site check and the spiral search that never overrules it.
 * A stone a town sets for its dead must never look careless, so a spot is taken only if all of this holds:
 *
 * - the ground under it is natural ground (grass, dirt, sand, a natural stone), level under the whole of its foot, with the room above
 *   it free (the stone is two blocks high; the cairn is three blocks across and two high), so it is never in a building, on a road or a
 *   path, in a crop, under water, or on a block somebody placed;
 * - the town's plan agrees: the foot is on open ground (a garden, a yard, a free patch), not on a street, a lot, a field or a wall, and
 *   no building, field or wall is within a block of it (a stone hard against a wall looks careless);
 * - no door is within [DOOR_CLEAR] blocks of it;
 * - there is room in front of the carved face for the lantern and the one who kneels (`Sets.laying`, S5): the cell before the stone, flat
 *   and open ground, for the lantern, and one more beyond it that a body can stand in, on open ground or a street (the cairn's foot is
 *   three across, so its two are the cells beyond the foot);
 * - it keeps [APART] blocks from another memorial;
 *
 * and it is turned square to the street: the carved face looks the way the nearest street is (a building in the way hides it), else the
 * way with the most room. The search goes outward from the place asked for, nearest first, up to [STONE_REACH] blocks ([CAIRN_REACH]
 * for a cairn), and takes the first spot that passes.
 *
 * Pure Kotlin on a small [Terrain] of its own and no Minecraft class, so that the game ([MemorialSites], over the level and the
 * capital's plan) and the city-preview kit (over the voxel maps the city is drawn into) ask the very same rules.
 */
object MemorialRules {
    /** How far from the place asked for a stone may be put, and a cairn (6.4). */
    const val STONE_REACH = 8
    const val CAIRN_REACH = 16

    /** No door within this many blocks of a memorial's foot (6.4). */
    const val DOOR_CLEAR = 2

    /** The least distance (a square) between two memorials, a stone's and a cairn's. */
    const val APART = 5
    const val APART_CAIRN = 7

    /** How far along a facing the nearest street is looked for. */
    const val STREET_LOOK = 16

    /** The cells above the ground that stay clear over the middle of the foot: the stone's two blocks and a third. */
    const val HEADROOM = 3

    /** The four ways the carved face can look, in the order of the block's `facing` (north is -z, east is +x). */
    enum class Face(val dx: Int, val dz: Int) {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        companion object {
            fun of(turns: Int): Face = entries[Math.floorMod(turns, 4)]
        }
    }

    /** What is in one cell of the world, as far as the rules care. */
    enum class Cell {
        /** Nothing (also cave air). */
        AIR,

        /** A plant anything may build over: short grass, a fern, a thin snow layer (what `Streets.place` builds into). */
        PLANT,

        /** Any fluid. */
        WATER,

        /** Natural ground to set a stone on: grass, dirt, coarse dirt, podzol, sand, gravel, clay, a natural stone. */
        GROUND,

        /** Anything else solid to stand on: a path, paving, planks, bricks, a wall's top. */
        PAVED,

        /** Farmland or a crop. */
        FARM,

        /** Anything else: a wall, a roof, leaves, a log, a fence, a flower, a bench, a torch, a sign. */
        OTHER,
    }

    /** What the town's plan says of a column (a column outside any plan is [WILD]: only its blocks tell). */
    enum class Plot {
        /** A garden, a yard or a free patch: open ground. */
        OPEN,

        /** A street, a square, a lane, a stair, a road, a quay. */
        STREET,

        /** A building's lot. */
        LOT,

        /** A field or a farmyard. */
        FIELD,

        /** A wall, a tower, a gate, the ground sloped up to them. */
        WALL,

        /** No plan here: natural land. */
        WILD,
    }

    /** Why a spot was not taken, for the count that `tale memorial place` shows (the first rule a spot fails). */
    enum class Refusal(val words: String) {
        UNLOADED("not loaded"),
        NO_GROUND("no ground"),
        STREET("on a street"),
        BUILDING("in or by a building"),
        WALL("in or by a wall"),
        CROP("in or by a crop"),
        WATER("in water"),
        BLOCKED("something in the way"),
        PAVED("on a path or paving"),
        PLACED("on a block somebody placed"),
        UNEVEN("uneven ground"),
        DOOR("by a door"),
        NO_ROOM("no room in front"),
        CROWDED("by another memorial"),
    }

    /** What the rules ask of the world. */
    interface Terrain {
        /** What is in the cell at [x], [y], [z]; null if it is not known (an unloaded chunk): no rule counts an unknown cell as free. */
        fun cell(x: Int, y: Int, z: Int): Cell?

        /** What the town's plan says of column [x], [z]. */
        fun plot(x: Int, z: Int): Plot

        /** Whether a door of a building is within [r] blocks (a square) of column [x], [z]. */
        fun door(x: Int, z: Int, r: Int): Boolean

        /** Whether a body can stand with its feet in the cell at [x], [y], [z] (a solid top below, room for a body); null if it is not known. */
        fun walk(x: Int, y: Int, z: Int): Boolean?
    }

    /** A column of the world. */
    class Column(val x: Int, val z: Int)

    /** The cell the memorial's block stands in ([x], [y], [z]) and the way its carved face looks. */
    class Spot(val x: Int, val y: Int, val z: Int, val face: Face) {
        override fun toString(): String = "$x $y $z ${face.name.lowercase()}"
    }

    /** What a search found: the [spot], or null, how many places it [tried] and the [refused] count of why each was not taken. */
    class Found(val spot: Spot?, val tried: Int, val refused: Map<Refusal, Int>) {
        /** Whether some place could not be judged because it is not loaded: the search may find room once it is. */
        val unloaded: Boolean get() = (refused[Refusal.UNLOADED] ?: 0) > 0
    }

    // ---- The search ----

    /**
     * Looks for a spot for a stone (a [cairn] if so) as near column [x], [z] as the rules allow, [y] being about the height of the
     * ground there; [others] are the memorials already standing (their columns), [seed] breaks the ties between spots as far from
     * the place as each other, so that two stones asked for the same place are not always in the same corner.
     */
    fun find(t: Terrain, cairn: Boolean, x: Int, y: Int, z: Int, others: List<Column> = listOf(), seed: Long = 0L, reach: Int = if (cairn) CAIRN_REACH else STONE_REACH): Found {
        val refused = java.util.EnumMap<Refusal, Int>(Refusal::class.java)
        var tried = 0
        for (o in offsets(reach, seed)) {
            tried++
            val spot = try1(t, cairn, x + o.first, y, z + o.second, others, seed, refused)
            if (spot != null) return Found(spot, tried, refused)
        }
        return Found(null, tried, refused)
    }

    /** Whether the spot [s] stands the rules as they are now (for a memorial already set down). Null if it does; else the first rule it fails. */
    fun check(t: Terrain, cairn: Boolean, s: Spot, others: List<Column> = listOf()): Refusal? {
        val refused = java.util.EnumMap<Refusal, Int>(Refusal::class.java)
        return if (try1(t, cairn, s.x, s.y, s.z, others, 0L, refused, only = s.face) != null) null else refused.keys.firstOrNull()
    }

    /** The offsets within [reach] blocks of the place, nearest first, those equally near in an order [seed] picks. */
    private fun offsets(reach: Int, seed: Long): List<Pair<Int, Int>> {
        val all = ArrayList<Pair<Int, Int>>()
        for (dx in -reach..reach) for (dz in -reach..reach) if (dx * dx + dz * dz <= reach * reach) all += dx to dz
        return all.sortedWith(compareBy<Pair<Int, Int>> { it.first * it.first + it.second * it.second }.thenBy { mix(seed, it.first, it.second) })
    }

    private fun mix(seed: Long, a: Int, b: Int): Long {
        var h = seed xor (a.toLong() * -0x61c8864680b583ebL) xor (b.toLong() * 0x2545F4914F6CDD1DL)
        h = (h xor (h ushr 31)) * -0x4b47_2f66_8a9a_6f1fL
        return (h xor (h ushr 29)) and 0x7FFFFFFF
    }

    /** The spot at column [x], [z] (the ground found about height [hint]) if it passes every rule, else null with the reason counted. */
    private fun try1(
        t: Terrain, cairn: Boolean, x: Int, hint: Int, z: Int, others: List<Column>, seed: Long, refused: MutableMap<Refusal, Int>, only: Face? = null,
    ): Spot? {
        val why = judge(t, cairn, x, hint, z, others, seed, only)
        if (why.first != null) {
            refused.merge(why.first!!, 1, Int::plus)
            return null
        }
        return why.second
    }

    private fun judge(t: Terrain, cairn: Boolean, x: Int, hint: Int, z: Int, others: List<Column>, seed: Long, only: Face?): Pair<Refusal?, Spot?> {
        fun no(r: Refusal) = r to null
        val apart = if (cairn) APART_CAIRN else APART
        if (others.any { abs(it.x - x) < apart && abs(it.z - z) < apart }) return no(Refusal.CROWDED)
        val radius = if (cairn) 1 else 0

        // What the plan says: the foot on open ground, nothing built within a block of it
        for (dx in -(radius + 1)..(radius + 1)) for (dz in -(radius + 1)..(radius + 1)) {
            val foot = abs(dx) <= radius && abs(dz) <= radius
            when (t.plot(x + dx, z + dz)) {
                Plot.OPEN, Plot.WILD -> {}
                Plot.STREET -> if (foot) return no(Refusal.STREET)
                Plot.LOT -> return no(Refusal.BUILDING)
                Plot.FIELD -> return no(Refusal.CROP)
                Plot.WALL -> return no(Refusal.WALL)
            }
        }

        // The ground at the middle of the foot, the nearest to where the place asked for is
        val y = when (val g = ground(t, x, hint, z)) {
            is Ground.At -> g.y
            is Ground.Not -> return no(g.why)
        }

        // The whole foot: natural ground, level, the room above it free, no door near
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val fx = x + dx
            val fz = z + dz
            val floor = t.cell(fx, y - 1, fz) ?: return no(Refusal.UNLOADED)
            if (floor != Cell.GROUND) return no(floorWhy(floor))
            val rooms = if (dx == 0 && dz == 0) HEADROOM else 2
            for (k in 0 until rooms) {
                val c = t.cell(fx, y + k, fz) ?: return no(Refusal.UNLOADED)
                if (c != Cell.AIR && c != Cell.PLANT) return no(bodyWhy(c))
            }
            if (t.door(fx, fz, DOOR_CLEAR)) return no(Refusal.DOOR)
        }

        // Crops and farmland on the ring about the foot
        for (dx in -(radius + 1)..(radius + 1)) for (dz in -(radius + 1)..(radius + 1)) {
            if (abs(dx) <= radius && abs(dz) <= radius) continue
            val ground = t.cell(x + dx, y - 1, z + dz)
            val body = t.cell(x + dx, y, z + dz)
            if (ground == Cell.FARM || body == Cell.FARM) return no(Refusal.CROP)
        }

        // Which way the carved face looks: the room in front of it, then the street
        var best: Face? = null
        var bestScore = -1.0
        var sawUnloaded = false
        for (f in Face.entries) {
            if (only != null && f != only) continue
            val room = room(t, cairn, x, y, z, f)
            if (room == null) {
                sawUnloaded = true
                continue
            }
            if (!room) continue
            val score = score(t, x, z, f, seed)
            if (score > bestScore) {
                best = f
                bestScore = score
            }
        }
        if (best == null) return no(if (sawUnloaded) Refusal.UNLOADED else Refusal.NO_ROOM)
        return null to Spot(x, y, z, best)
    }

    /** The ground at column [x], [z]: the cell a body stands in, nearest to height [hint], or why there is none. */
    private sealed interface Ground {
        class At(val y: Int) : Ground
        class Not(val why: Refusal) : Ground
    }

    private fun ground(t: Terrain, x: Int, hint: Int, z: Int): Ground {
        var unknown = false
        var water = false
        for (k in 0..SCAN) {
            for (sign in if (k == 0) intArrayOf(1) else intArrayOf(-1, 1)) {
                val y = hint + sign * k
                val floor = t.cell(x, y - 1, z)
                val body = t.cell(x, y, z)
                if (floor == null || body == null) {
                    unknown = true
                    continue
                }
                if (floor == Cell.WATER || body == Cell.WATER) water = true
                if ((body == Cell.AIR || body == Cell.PLANT) && floor != Cell.AIR && floor != Cell.PLANT && floor != Cell.WATER && floor != Cell.OTHER) return Ground.At(y)
            }
        }
        return Ground.Not(if (unknown) Refusal.UNLOADED else if (water) Refusal.WATER else Refusal.NO_GROUND)
    }

    /** How far above and below the height asked for the ground is looked for. */
    private const val SCAN = 6

    private fun floorWhy(c: Cell): Refusal = when (c) {
        Cell.AIR, Cell.PLANT -> Refusal.UNEVEN
        Cell.WATER -> Refusal.WATER
        Cell.PAVED -> Refusal.PAVED
        Cell.FARM -> Refusal.CROP
        else -> Refusal.PLACED
    }

    private fun bodyWhy(c: Cell): Refusal = when (c) {
        Cell.WATER -> Refusal.WATER
        Cell.FARM -> Refusal.CROP
        Cell.GROUND -> Refusal.UNEVEN
        else -> Refusal.BLOCKED
    }

    /**
     * Whether the room before a face of the memorial at [x], [y], [z] is as the Laying needs it (S5): the lantern's cell (the first cell
     * before a stone, the first beyond a cairn's foot) open ground, flat, with room for a lantern; and one more beyond it that a body can
     * stand in, at the same height or a step up or down. Null if a cell of it is not loaded.
     */
    private fun room(t: Terrain, cairn: Boolean, x: Int, y: Int, z: Int, f: Face): Boolean? {
        val lamp = if (cairn) 2 else 1
        val lx = x + f.dx * lamp
        val lz = z + f.dz * lamp
        when (t.plot(lx, lz)) {
            Plot.OPEN, Plot.WILD -> {}
            else -> return false
        }
        val floor = t.cell(lx, y - 1, lz) ?: return null
        val body = t.cell(lx, y, lz) ?: return null
        if (floor != Cell.GROUND || body != Cell.AIR && body != Cell.PLANT) return false
        val mx = x + f.dx * (lamp + 1)
        val mz = z + f.dz * (lamp + 1)
        // The one who kneels does not kneel in a field, on a wall, or in somebody's lot
        when (t.plot(mx, mz)) {
            Plot.OPEN, Plot.WILD, Plot.STREET -> {}
            else -> return false
        }
        for (dy in intArrayOf(0, 1, -1)) {
            val w = t.walk(mx, y + dy, mz) ?: return null
            if (w) return true
        }
        return false
    }

    /** How well the face [f] of the memorial at [x], [z] looks to the street: the nearer the street along it the better (a building in the way hides it), then a breath of chance. */
    private fun score(t: Terrain, x: Int, z: Int, f: Face, seed: Long): Double {
        var street = 0.0
        for (d in 1..STREET_LOOK) {
            val p = t.plot(x + f.dx * d, z + f.dz * d)
            if (p == Plot.STREET) {
                street = (STREET_LOOK + 1 - d).toDouble()
                break
            }
            if (p == Plot.LOT || p == Plot.WALL) break
        }
        return street + (mix(seed, x * 31 + f.ordinal, z) and 0xFF) / 4096.0
    }
}
