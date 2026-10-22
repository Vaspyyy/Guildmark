package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.city.plan.Census
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Grid
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Use
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
 *   three across, so its two are the cells beyond the foot); and the one who kneels is not walled in: from that cell a body can walk, a
 *   block up or down at a time and through no gate or door, to a street (or out into open land);
 * - it keeps [APART] blocks from another memorial;
 *
 * and it is turned square to the street: the carved face looks the way the nearest street is (a building in the way hides it), else the
 * way with the most open ground. The search goes outward from the place asked for, nearest first, up to [STONE_REACH] blocks
 * ([CAIRN_REACH] for a cairn), and takes the first spot that passes. A memorial that is given no place starts from where its kind belongs
 * in the town ([anchors]) and goes through them all, near ones first ([findFrom]); a cairn tries the road out of a gate, near and far,
 * before the town's places.
 *
 * Pure Kotlin on a small [Terrain] of its own and no Minecraft class (only the city plan's own classes, which have none), so that the
 * game ([MemorialSites], over the level and the capital's plan) and the city-preview kit (over the voxel maps the city is drawn into)
 * ask the very same rules, work out the very same places a memorial belongs ([anchors]) and read the plan's word for a column the very
 * same way ([plotOf]).
 */
object MemorialRules {
    /**
     * How far from the place asked for a stone may be put, and a cairn. The design's 6.4 says 8 and 16; on the drawn cities (every
     * temple's door, well and square) a reach of 8 finds a stone a place at a third of the temple doors and none at the market, 16 at all
     * but a few doorsteps, so the reach is 16 and 24, and a search through several places keeps to [STONE_NEAR] and [CAIRN_NEAR] first.
     */
    const val STONE_REACH = 16
    const val CAIRN_REACH = 24

    /** The reach a search through all the places a memorial belongs tries at each of them before it widens to [STONE_REACH] and [CAIRN_REACH]. */
    const val STONE_NEAR = 8
    const val CAIRN_NEAR = 16

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

    /** What the plan's [use] of a column says of it to the rules: open ground, a street, a lot, a field, a wall, or no plan's business. */
    fun plotOf(use: Use): Plot = when (use) {
        Use.GARDEN, Use.YARD, Use.FREE -> Plot.OPEN
        Use.LOT, Use.HAMLET, Use.FARMYARD -> Plot.LOT
        Use.FIELD -> Plot.FIELD
        Use.WALL, Use.TOWER, Use.GATE, Use.KEEP_WALL, Use.INNER_WALL, Use.GLACIS -> Plot.WALL
        Use.OUTSIDE, Use.CLEARING, Use.PASTURE, Use.ORCHARD, Use.VERGE -> Plot.WILD
        // The streets, the squares, the lanes, the roads, the stairs, the quays and the drill ground
        else -> Plot.STREET
    }

    /** How far past the glacis' foot the road is looked for. */
    const val ROAD_FROM = 4

    /**
     * The first column of a road out of the capital's gates in its plan's own frame, beyond the glacis' foot ([levelled]) and short of
     * the plan's [reach] (south, east, west, north); null if none is found on the axes.
     */
    fun roadside(grid: Grid, levelled: Int, reach: Int): Pair<Int, Int>? {
        for ((dx, dz) in listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1)) {
            for (k in levelled + ROAD_FROM until reach) if (grid[dx * k, dz * k] == Use.ROAD) return dx * k to dz * k
        }
        return null
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
        NO_WAY("walled in"),
        CROWDED("by another memorial"),
    }

    /** What the rules ask of the world. */
    interface Terrain {
        /** What is in the cell at [x], [y], [z]; null if it is not known (an unloaded chunk): no rule counts an unknown cell as free. */
        fun cell(x: Int, y: Int, z: Int): Cell?

        /** What the town's plan says of column [x], [z]. */
        fun plot(x: Int, z: Int): Plot

        /** Whether a door of a building the plan knows is within [r] blocks (a square) of column [x], [z], at any height. */
        fun door(x: Int, z: Int, r: Int): Boolean

        /**
         * Whether any door is within [r] blocks (a square) of the cell [x], [y], [z]: one the plan knows (as [door]), or a door block
         * standing there that no plan knows (a house somebody built by the town). By default only the plan's.
         */
        fun doorNear(x: Int, y: Int, z: Int, r: Int): Boolean = door(x, z, r)

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

    // ---- Where a memorial belongs ----

    /** The [Anchor.kind] of the road out of a gate, where a cairn belongs first. */
    const val ROAD = "road"

    /** A place a search starts from: what it is ([kind]: a temple's door, a well, the market...), the column, and about the height of the ground there. */
    class Anchor(val kind: String, val x: Int, val y: Int, val z: Int) {
        /** The place in words after "at" or "N blocks from", for the command's reply and the log: "the temple's door", "a well". */
        val words: String
            get() = when (kind) {
                ROAD -> "the road out of a gate"
                "temple" -> "the temple's door"
                "temple corner" -> "the corner of the temple's lot"
                "well" -> "a well"
                "market corner" -> "a corner of the market"
                "market" -> "the middle of the market"
                else -> "the place asked for"
            }
    }

    /** How many temples a memorial is set by (the ones nearest the centre), and how many wells (the nearest the centre) are tried. */
    const val TEMPLES = 2
    const val WELLS = 6

    /** How far outside a lot's or the market's edge the corner of it is asked for. */
    const val CORNER = 2

    /**
     * The places a memorial belongs in a capital when its tale names none (6.4), the best first, in the plan's own frame: a stone at the
     * door of a temple (the two nearest the centre), and if that has no room round the corners of their lots; else by a well in the town
     * (the six nearest the centre; the farms' wells and troughs are not the town's); else in the market, by the corners of its square and
     * in the middle of it. A cairn goes first beside the road a gate leads out by, and then where a stone would go.
     */
    fun anchors(plan: CityPlan, census: Census, cairn: Boolean): List<Anchor> {
        val out = ArrayList<Anchor>()
        if (cairn) roadside(plan.grid, plan.levelled, plan.reach)?.let { (x, z) -> out += Anchor(ROAD, x, plan.level(x, z), z) }
        val temples = census.lodges.values.filter { it.kind == Kind.CATHEDRAL.name || it.kind == Kind.TEMPLE.name }
            .sortedBy { val cx = (it.lot.x0 + it.lot.x1) / 2; val cz = (it.lot.z0 + it.lot.z1) / 2; cx * cx + cz * cz }
            .take(TEMPLES)
        for (t in temples) {
            val door = t.doors.minByOrNull { it.at.y } ?: continue
            out += Anchor("temple", door.at.x - door.inward.dx, door.at.y, door.at.z - door.inward.dz)
        }
        for (t in temples) for ((x, z) in corners(t.lot.x0, t.lot.z0, t.lot.x1, t.lot.z1)) out += Anchor("temple corner", x, plan.level(x, z), z)
        for (w in census.wells.filter { maxOf(abs(it.x), abs(it.z)) < plan.outer }.sortedBy { it.x * it.x + it.z * it.z }.take(WELLS)) out += Anchor("well", w.x, w.y, w.z)
        val m = plan.market
        for ((x, z) in corners(m.x0, m.z0, m.x1, m.z1)) out += Anchor("market corner", x, plan.level(x, z), z)
        val mx = (m.x0 + m.x1) / 2
        val mz = (m.z0 + m.z1) / 2
        out += Anchor("market", mx, plan.level(mx, mz), mz)
        return out
    }

    /** The four columns [CORNER] blocks outside the corners of a rectangle. */
    private fun corners(x0: Int, z0: Int, x1: Int, z1: Int): List<Pair<Int, Int>> =
        listOf(x0 - CORNER to z0 - CORNER, x1 + CORNER to z0 - CORNER, x0 - CORNER to z1 + CORNER, x1 + CORNER to z1 + CORNER)

    // ---- The search ----

    /** What [findFrom] found: the [found] (a spot, or why not), the [index] of the anchor it was found near (-1 if none) and in which [pass] (0 near, 1 far). */
    class Hit(val found: Found, val index: Int, val pass: Int)

    /**
     * Looks for a spot for a stone (a [cairn] if so) near any of the [anchors] (the best first): at each of them as far as [STONE_NEAR]
     * ([CAIRN_NEAR]) blocks, and then, if none has room, at each again as far as [STONE_REACH] ([CAIRN_REACH]). The road a cairn belongs
     * by ([ROAD]) is a place of its own kind: it goes through both reaches before the town's places are looked at, so a cairn is set
     * in the town only if the road has no room at all. An anchor that is not [loaded] is passed over (and counted as not loaded), for
     * no rule looks at ground that is not there. [others] are the memorials already standing and [seed] breaks the ties, as for
     * [find]. The counts of why places were refused are all the searches' together.
     */
    fun findFrom(
        t: Terrain, cairn: Boolean, anchors: List<Anchor>, others: List<Column> = listOf(), seed: Long = 0L, loaded: (Anchor) -> Boolean = { true },
    ): Hit {
        val refused = java.util.EnumMap<Refusal, Int>(Refusal::class.java)
        var tried = 0
        val indexed = anchors.withIndex().toList()
        val roads = indexed.filter { it.value.kind == ROAD }
        for (group in listOf(roads, indexed.filter { it.value.kind != ROAD })) {
            var within = 0
            for ((pass, reach) in listOf(if (cairn) CAIRN_NEAR else STONE_NEAR, if (cairn) CAIRN_REACH else STONE_REACH).withIndex()) {
                for ((i, a) in group) {
                    if (!loaded(a)) {
                        refused.merge(Refusal.UNLOADED, 1, Int::plus)
                        continue
                    }
                    val f = find(t, cairn, a.x, a.y, a.z, others, seed, reach, within)
                    tried += f.tried
                    if (f.spot != null) return Hit(Found(f.spot, tried, refused), i, pass)
                    for ((why, n) in f.refused) refused.merge(why, n, Int::plus)
                }
                within = reach
            }
            // Ground by the road that is not loaded yet: the cairn waits for it, it is not put in the town for want of it
            if (group === roads && (refused[Refusal.UNLOADED] ?: 0) > 0) break
        }
        return Hit(Found(null, tried, refused), -1, 1)
    }

    /**
     * Looks for a spot for a stone (a [cairn] if so) as near column [x], [z] as the rules allow, [y] being about the height of the
     * ground there; [others] are the memorials already standing (their columns), [seed] breaks the ties between spots as far from
     * the place as each other, so that two stones asked for the same place are not always in the same corner. Only the places more than
     * [beyond] blocks from it are tried (a search that has been through those nearer does not go through them again).
     */
    fun find(
        t: Terrain, cairn: Boolean, x: Int, y: Int, z: Int, others: List<Column> = listOf(), seed: Long = 0L,
        reach: Int = if (cairn) CAIRN_REACH else STONE_REACH, beyond: Int = 0,
    ): Found {
        val refused = java.util.EnumMap<Refusal, Int>(Refusal::class.java)
        var tried = 0
        for (o in offsets(reach, beyond, seed)) {
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

    /** The offsets more than [beyond] and within [reach] blocks of the place, nearest first, those equally near in an order [seed] picks. */
    private fun offsets(reach: Int, beyond: Int, seed: Long): List<Pair<Int, Int>> {
        val all = ArrayList<Pair<Int, Int>>()
        for (dx in -reach..reach) for (dz in -reach..reach) {
            val d = dx * dx + dz * dz
            if (d <= reach * reach && (beyond == 0 || d > beyond * beyond)) all += dx to dz
        }
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
        }

        // No door within DOOR_CLEAR of any cell of the foot: the square about its middle that far and the foot's own radius more
        if (t.doorNear(x, y, z, DOOR_CLEAR + radius)) return no(Refusal.DOOR)

        // Crops and farmland on the ring about the foot
        for (dx in -(radius + 1)..(radius + 1)) for (dz in -(radius + 1)..(radius + 1)) {
            if (abs(dx) <= radius && abs(dz) <= radius) continue
            val ground = t.cell(x + dx, y - 1, z + dz)
            val body = t.cell(x + dx, y, z + dz)
            if (ground == Cell.FARM || body == Cell.FARM) return no(Refusal.CROP)
        }

        // Which way the carved face looks: the faces with room before them, the one the street is along (else the most open ground) first
        val looks = ArrayList<Pair<Face, Room>>()
        var sawUnloaded = false
        for (f in Face.entries) {
            if (only != null && f != only) continue
            val room = room(t, cairn, x, y, z, f)
            if (room.unknown) sawUnloaded = true else if (room.kneel != null) looks += f to room
        }
        if (looks.isEmpty()) return no(if (sawUnloaded) Refusal.UNLOADED else Refusal.NO_ROOM)
        for ((f, room) in looks.sortedByDescending { score(t, x, z, it.first, seed) }) {
            val k = room.kneel!!
            if (hasWayOut(t, k[0], k[1], k[2])) return null to Spot(x, y, z, f)
        }
        return no(Refusal.NO_WAY)
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

    /** What lies before a face: [unknown] if a cell of it is not loaded, else the cell where the one who kneels stands ([kneel]: x, y, z), or null if there is no room. */
    private class Room(val unknown: Boolean, val kneel: IntArray?)

    private val NO_SPACE = Room(false, null)
    private val NOT_LOADED = Room(true, null)

    /**
     * The room before a face of the memorial at [x], [y], [z] as the Laying needs it (S5): the lantern's cell (the first cell before a
     * stone, the first beyond a cairn's foot) open ground, flat, with room for a lantern; and one more beyond it that a body can stand in,
     * at the same height or a step up or down, which is where the one who kneels stands.
     */
    private fun room(t: Terrain, cairn: Boolean, x: Int, y: Int, z: Int, f: Face): Room {
        val lamp = if (cairn) 2 else 1
        val lx = x + f.dx * lamp
        val lz = z + f.dz * lamp
        when (t.plot(lx, lz)) {
            Plot.OPEN, Plot.WILD -> {}
            else -> return NO_SPACE
        }
        val floor = t.cell(lx, y - 1, lz) ?: return NOT_LOADED
        val body = t.cell(lx, y, lz) ?: return NOT_LOADED
        if (floor != Cell.GROUND || body != Cell.AIR && body != Cell.PLANT) return NO_SPACE
        val mx = x + f.dx * (lamp + 1)
        val mz = z + f.dz * (lamp + 1)
        // The one who kneels does not kneel in a field, on a wall, or in somebody's lot
        when (t.plot(mx, mz)) {
            Plot.OPEN, Plot.WILD, Plot.STREET -> {}
            else -> return NO_SPACE
        }
        for (dy in STEPS) {
            val w = t.walk(mx, y + dy, mz) ?: return NOT_LOADED
            if (w) return Room(false, intArrayOf(mx, y + dy, mz))
        }
        return NO_SPACE
    }

    /** The steps a body takes from one cell to the next: level, up a block, down a block. */
    private val STEPS = intArrayOf(0, 1, -1)

    /** How many cells [hasWayOut] looks over before it calls the land open. */
    private const val WALK = 1500

    /**
     * Whether the one who kneels at [x], [y], [z] is not walled in: from that cell a body can walk (the steps of [STEPS]; a gate is not
     * opened, nor a door) to a street, or on over more than [WALK] cells, or to ground that is not loaded, which is all as good as open
     * land. A garden with a fence all round it, or a courtyard, is walled in. Where the plan has nothing to say ([Plot.WILD]: natural
     * land, a clearing in a wood) there is no street to walk to and nobody is walled in.
     */
    private fun hasWayOut(t: Terrain, x: Int, y: Int, z: Int): Boolean {
        val here = t.plot(x, z)
        if (here == Plot.STREET || here == Plot.WILD) return true
        fun key(a: Int, b: Int, c: Int): Long = ((a - x + 2048).toLong() shl 40) or ((c - z + 2048).toLong() shl 20) or (b - y + 512).toLong()
        val seen = HashSet<Long>()
        val queue = ArrayDeque<IntArray>()
        seen.add(key(x, y, z))
        queue.addLast(intArrayOf(x, y, z))
        var cells = 0
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (f in Face.entries) {
                val nx = p[0] + f.dx
                val nz = p[2] + f.dz
                for (dy in STEPS) {
                    val ny = p[1] + dy
                    val w = t.walk(nx, ny, nz) ?: return true
                    if (!w) continue
                    if (!seen.add(key(nx, ny, nz))) break
                    if (t.plot(nx, nz) == Plot.STREET || ++cells >= WALK) return true
                    queue.addLast(intArrayOf(nx, ny, nz))
                    break
                }
            }
        }
        return false
    }

    /**
     * How well the face [f] of the memorial at [x], [z] looks: a street along it is best, the nearer the better (a building in the way hides
     * it); with none, the more open ground before it the better (a face to a wall is the worst); then a breath of chance between equals.
     */
    private fun score(t: Terrain, x: Int, z: Int, f: Face, seed: Long): Double {
        var street = 0.0
        var clear = 0
        for (d in 1..STREET_LOOK) {
            val p = t.plot(x + f.dx * d, z + f.dz * d)
            if (p == Plot.STREET) {
                street = (STREET_LOOK + 1 - d).toDouble()
                break
            }
            if (p == Plot.LOT || p == Plot.WALL) break
            clear++
        }
        val view = if (street > 0.0) street else clear / (STREET_LOOK + 1.0)
        return view + (mix(seed, x * 31 + f.ordinal, z) and 0xFF) / 16384.0
    }
}
