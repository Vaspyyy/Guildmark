package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a column of the city is for. Streets are paved and kept clear. [laid] columns get the city's own
 * ground, so nothing may grow there before the city is drawn; the rest keep the land's own.
 */
enum class Use(val street: Boolean = false, val laid: Boolean = true) {
    OUTSIDE(laid = false), CLEARING, WALL, TOWER, GATE, KEEP_WALL,
    POMERIUM(true), AVENUE(true), PLAZA(true), LANE(true), COURT(true),
    LOT, YARD, GARDEN, FREE,

    // The old town's wall (holding up its terrace), flights of steps, the lower town's squares, the training yard's sand
    INNER_WALL, STAIR(true), SQUARE(true), TRAINING,

    // Outside the walls: the glacis sloping from the walls' foot to the land, and the roads out
    GLACIS, ROAD(true),

    // The harbour (see Harbour): quays and yards on land, piers and moorings over the water
    QUAY, PIER, MOORING(laid = false),

    // The farms (see Outskirts)
    FIELD, FARMYARD, PASTURE(laid = false), ORCHARD(laid = false), HAMLET,
}

enum class Kind {
    HOUSE, WORKSHOP, GUILD_HALL, TEMPLE, TAVERN, SMITHY, LIBRARY, MARKET_HALL, COUNTING_HOUSE, BAKERY,
    KEEP, BARRACKS, STABLES,

    // The great buildings of a grand capital (see Landmarks)
    CATHEDRAL, PALACE, MANOR,
}

/** A job site a workshop is set up round, so a villager takes up that trade there. */
enum class Trade(val site: M) {
    MASON(M.STONECUTTER), FLETCHER(M.FLETCHING_TABLE), SHEPHERD(M.LOOM), CARTOGRAPHER(M.CARTOGRAPHY_TABLE),
    LEATHERWORKER(M.CAULDRON), FARMER(M.COMPOSTER), FISHERMAN(M.BARREL), BUTCHER(M.SMOKER),
}

/** A building on its [lot], its front toward [front], standing at height [y] (its ground floor's) in the city. */
class Building(val id: Int, val kind: Kind, val lot: Rect, val front: Dir, val seed: Long, val y: Int = 0) {
    /** Sides, in the building's own frame (north is its front), that back onto another building. */
    val shared = BooleanArray(4)
    var trade: Trade? = null

    /** Whether a villager may live here: a big city has room for far more than the game can keep busy. */
    var tenant = true

    /** Across the front and from front to back. */
    val width: Int get() = if (front.alongX) lot.depth else lot.width
    val depth: Int get() = if (front.alongX) lot.width else lot.depth

    /** Where its front door is, across the front. */
    val door: Int get() = width / 2
}

/** Round towers at the outer and inner walls' corners and the citadel's, and square ones along the outer walls. */
enum class TowerKind { CORNER, MID, INNER, CITADEL }

/** A tower on the walls: its centre column, and for a mid-wall tower the side of the city it's on. */
class Tower(val kind: TowerKind, val x: Int, val z: Int, val side: Dir?)

/** A flight of steps up the inside of a wall: [length] steps from [x], [z] on ground at [y], climbing toward [up]. */
class WallStair(val x: Int, val z: Int, val up: Dir, val length: Int, val y: Int = 0)

/** Something small standing in the street: a lamp, a tree, a market stall, a well. */
class Prop(val kind: PropKind, val x: Int, val z: Int, val facing: Dir = Dir.N, val v: Int = 0)

enum class PropKind { LAMP, TREE, STALL, WELL, BENCH, PLANTER, TARGET, HAY_CART, RING }

/**
 * The layout of a capital, all worked out from its [seed] and the [layout] its site allows. Two walls: the
 * outer one round the lower town, with gatehouses on every side and towers along it, and the inner one
 * holding up the old town on its terrace, [Layout.upper] higher. The citadel stands astride the old
 * town's north wall, higher again, with the palace in it and a great stair down to the plaza. The plaza
 * has the guild hall, tavern, library and counting house round it and the cathedral off its south-east
 * corner; the noble quarter is to the south-west. Down in the lower town: the market inside the south
 * gate, the craftsmen's quarter on one side (the harbour's, if there is one), the training yard with the
 * barracks and stables on the other, and houses everywhere else. Outside: the glacis, the roads, the
 * harbour and the farms.
 */
class CityPlan(val seed: Long, val layout: Layout = Layout.DEFAULT, val land: Land = Land.FLAT) {
    val tier: Tier get() = layout.tier

    /** The furthest any part of the city (its farms included) reaches from its middle. */
    val reach: Int = layout.tier.reach

    /** The outer wall's outer face, out from the middle along each axis. */
    val outer: Int = layout.tier.outer

    /** Out to the foot of the glacis: everything nearer stands on the city's own ground. */
    val levelled: Int = layout.tier.levelled

    /** The inner wall's outer face; the old town stands inside it, [upper] over the lower town. */
    val inner: Int = layout.tier.inner
    val upper: Int = layout.upper

    /** The citadel's standing height over the lower town. */
    val high: Int = layout.upper + layout.citadel

    /** Standing height on the outer wall walk. */
    val walk: Int = OUTER_WALK[layout.tier.ordinal]

    /** Half the plaza's width; it runs from the foot of the citadel's stair ([plazaNorth]) south to [plaza]. */
    val plaza: Int = PLAZA_HALF[layout.tier.ordinal]

    /** The citadel's walls, to their outer faces. */
    val citadel: Rect = CITADEL[layout.tier.ordinal]

    /** The great stair from the citadel's gate down to the plaza: a landing in the middle, a flight either side. */
    val stair: Rect = Rect(-3 - layout.citadel, citadel.z1 + 1, 3 + layout.citadel, citadel.z1 + STAIR_DEPTH)
    val plazaNorth: Int = citadel.z1 + STAIR_DEPTH + 1

    /** The fountain in the plaza, and how far its rim is from its middle. */
    val fountain = Pos(0, (plazaNorth + plaza) / 2)
    val fountainRadius: Int = FOUNTAIN_RADIUS[layout.tier.ordinal]

    /** The lower town's quarters lie between these rings: past the road round the old town, inside the one round the walls. */
    val bandIn: Int = inner + RING_ROAD + 1
    val bandOut: Int = outer - WALL_THICK - RING_ROAD
    val bandMid: Int = (bandIn + bandOut) / 2

    /** The side of the lower town the craftsmen have (the harbour's, if there is one), and the soldiers' side. */
    val crafts: Dir = if (layout.harbour == Dir.W) Dir.W else Dir.E
    val training: Dir = crafts.opposite

    /** The market square inside the south gate. */
    val market: Rect = MARKET[layout.tier.ordinal].let { (half, deep) -> Rect(-half, bandMid - deep, half, bandMid + deep) }

    val grid = Grid(reach)
    val buildings = ArrayList<Building>()
    val towers = ArrayList<Tower>()
    val stairs = ArrayList<WallStair>()
    val props = ArrayList<Prop>()
    private val streets = ArrayList<Street>()
    private val random = Random(seed)

    /** The harbour on the [Layout.harbour] side, if the city has one. */
    val harbour: Harbour? = if (layout.harbour != null) Harbour(this) else null

    /** The farms and hamlets round the city. */
    val outskirts = Outskirts(this)

    // The plaza's furniture: the bell, the board (its anchor and which way it faces) and the Wardfire
    val bell = Pos(plaza - 4, 7)
    val board = Pos(plaza - 2, max(plazaNorth, -GREAT_WIDTH - 3) + 2)
    val boardFacing = Dir.W
    val wardfire = Pos(-(plaza - 4), plaza - 4)

    init {
        lay()
    }

    data class Pos(val x: Int, val z: Int)

    /** A flat stretch of the city's ground, all at standing height [y]. */
    class Terrace(val rect: Rect, val y: Int)

    /** A street houses can face onto. */
    private class Street(val rect: Rect, val use: Use)

    private fun lay() {
        rings()
        outerWalls()
        innerWalls()
        citadelWard()
        innerGates()
        lanes()
        avenues()
        squares()
        greatBuildings()
        manors()
        // Houses along every street, the grandest first
        for (s in streets.sortedByDescending { rank(it.use) }) for (side in Dir.entries) pack(s.rect, side)
        settle()
        sharedWalls()
        trades()
        tenants()
        households()
        stairsUpTheWalls()
        furnish()

        // Outside the walls, last: they fit round everything else
        harbour?.lay(Random(seed * 7 + 1))
        outskirts.lay(Random(seed * 7 + 2))
    }

    // ---- The rings: walls, the roads along them, and the ground between ----

    private fun rings() {
        for (x in -reach..reach) for (z in -reach..reach) {
            val ring = max(abs(x), abs(z))
            grid[x, z] = when {
                ring > levelled -> Use.OUTSIDE
                ring > outer -> if (layout.harbour != null && Survey.sideOf(x, z) == layout.harbour) Use.OUTSIDE else Use.GLACIS
                ring > outer - WALL_THICK -> Use.WALL
                ring > outer - WALL_THICK - RING_ROAD -> Use.POMERIUM
                ring > inner + RING_ROAD -> Use.FREE
                ring > inner -> Use.POMERIUM
                ring > inner - INNER_THICK -> Use.INNER_WALL
                ring > inner - INNER_THICK - RING_ROAD -> Use.POMERIUM
                else -> Use.FREE
            }
            if (ring <= inner) grid.setHeight(x, z, upper)
        }
        // Houses face the roads along the walls from the side away from the wall
        for (side in Dir.entries) {
            streets.add(Street(sideRect(side, -(outer - WALL_THICK), outer - WALL_THICK, bandOut + 1, outer - WALL_THICK), Use.POMERIUM))
            streets.add(Street(sideRect(side, -(inner + RING_ROAD), inner + RING_ROAD, inner + 1, inner + RING_ROAD), Use.POMERIUM))
            val oldRing = inner - INNER_THICK
            streets.add(Street(sideRect(side, -oldRing, oldRing, oldRing - RING_ROAD + 1, oldRing), Use.POMERIUM))
        }
    }

    /** Round towers at the outer wall's corners, square ones along it, a gatehouse in the middle of each side and the roads out. */
    private fun outerWalls() {
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            towers.add(Tower(TowerKind.CORNER, sx * outer, sz * outer, null))
            for (x in -8..8) for (z in -8..8) if (inRound(x, z, OUTER_CORNER_RADIUS)) grid[sx * outer + x, sz * outer + z] = Use.TOWER
        }
        for (side in Dir.entries) for (along in midTowers()) {
            val (cx, cz) = sidePos(side, along, outer)
            towers.add(Tower(TowerKind.MID, cx, cz, side))
            grid.fill(sideRect(side, along - 3, along + 3, outer - WALL_THICK + 1, outer + 3), Use.TOWER)
        }
        for (side in OUTER_GATES) {
            grid.fill(sideRect(side, -GATE_HALF, GATE_HALF, outer - WALL_THICK + 1, outer + GATE_OUT), Use.GATE)
            if (side != layout.harbour) grid.fill(sideRect(side, -2, 2, outer + GATE_OUT + 1, levelled), Use.ROAD)
        }
    }

    /** Where the square towers stand along each side of the outer wall, out from its middle. */
    fun midTowers(): List<Int> {
        val n = max(1, Math.round(outer / 38.0).toInt() - 1)
        return (1..n).flatMap { k -> (GATE_HALF + (outer - GATE_HALF) * k / (n + 1)).let { listOf(-it, it) } }
    }

    /** Round towers at the inner wall's corners. */
    private fun innerWalls() {
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val cx = sx * (inner - 1)
            val cz = sz * (inner - 1)
            towers.add(Tower(TowerKind.INNER, cx, cz, null))
            for (x in -4..4) for (z in -4..4) if (inRound(x, z, INNER_TOWER_RADIUS)) grid[cx + x, cz + z] = Use.TOWER
        }
    }

    /**
     * The citadel: a walled ward astride the old town's north wall, [Layout.citadel] over the old town,
     * round towers at its corners, a gatehouse in the middle of its south wall, the palace behind a
     * courtyard, gardens either side of it, and the great stair down to the plaza.
     */
    private fun citadelWard() {
        val c = citadel
        for (x in c.x0..c.x1) for (z in c.z0..c.z1) {
            val edge = min(min(x - c.x0, c.x1 - x), min(z - c.z0, c.z1 - z))
            grid[x, z] = if (edge < CITADEL_THICK) Use.KEEP_WALL else Use.COURT
            grid.setHeight(x, z, high)
        }
        for (cx in intArrayOf(c.x0 + 1, c.x1 - 1)) for (cz in intArrayOf(c.z0 + 1, c.z1 - 1)) {
            towers.add(Tower(TowerKind.CITADEL, cx, cz, null))
            for (x in -5..5) for (z in -5..5) if (inRound(x, z, CITADEL_TOWER_RADIUS)) {
                grid[cx + x, cz + z] = Use.TOWER
                grid.setHeight(cx + x, cz + z, high)
            }
        }
        grid.fill(Rect(-2, c.z1 - CITADEL_THICK + 1, 2, c.z1), Use.GATE)
        val p = PALACE[tier.ordinal]
        special(Kind.PALACE, p, Dir.S, high)
        // Gardens either side of the palace, with trees
        grid.fill(Rect(c.x0 + CITADEL_THICK, p.z0, p.x0 - 2, p.z1), Use.GARDEN)
        grid.fill(Rect(p.x1 + 2, p.z0, c.x1 - CITADEL_THICK, p.z1), Use.GARDEN)
        for (sx in intArrayOf(-1, 1)) {
            val gx = sx * (p.x1 + 2 + c.x1 - CITADEL_THICK) / 2
            var z = p.z0 + 3
            while (z <= p.z1 - 3) {
                props.add(Prop(PropKind.TREE, gx, z, v = random.nextInt(3)))
                z += 7
            }
        }
        // The square along the foot of the citadel's wall, then the stair: a landing at the gate and a flight down either side
        for (x in c.x0 - 4..c.x1 + 4) for (z in stair.z0..stair.z1) {
            if (grid[x, z] == Use.FREE || grid[x, z].street) grid[x, z] = Use.PLAZA
        }
        for (x in stair.x0..stair.x1) for (z in stair.z0..stair.z1) {
            grid[x, z] = Use.STAIR
            val k = abs(x) - 3
            grid.setHeight(x, z, if (k <= 0) high else high - k + 1)
        }
    }

    /**
     * The old town's gates on the south, east and west: an arch through the foot of the inner wall, a
     * vaulted passage on under the ring road behind it, then a flight of steps up to the old town in a
     * cutting, with a walk either side of it at the top.
     */
    private fun innerGates() {
        val tunnel = inner - INNER_THICK - RING_ROAD + 1
        for (side in INNER_GATES) {
            val passage = sideRect(side, -2, 2, tunnel, inner)
            for (x in passage.x0..passage.x1) for (z in passage.z0..passage.z1) {
                grid[x, z] = Use.GATE
                grid.setHeight(x, z, 0)
            }
            for (k in 0 until upper) {
                val r = sideRect(side, -3, 3, tunnel - 1 - k, tunnel - 1 - k)
                for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
                    grid[x, z] = Use.STAIR
                    grid.setHeight(x, z, k + 1)
                }
            }
            street(sideRect(side, -5, -4, tunnel - upper, tunnel - 1), Use.AVENUE)
            street(sideRect(side, 4, 5, tunnel - upper, tunnel - 1), Use.AVENUE)
        }
    }

    /** Where the cutting with the steps up from an inner gate ends, out from the middle: the old town's avenues reach this far. */
    val cuttingTop: Int get() = inner - INNER_THICK - RING_ROAD - upper

    // ---- Streets ----

    private fun lanes() {
        // The lower town: a ring road halfway between the walls, and lanes out across it every so often
        for (side in Dir.entries) street(sideRect(side, -bandMid - 1, bandMid + 1, bandMid - 1, bandMid + 1), Use.LANE)
        for (side in Dir.entries) for (sign in intArrayOf(-1, 1)) {
            var along = RADIAL_FIRST + random.nextInt(5)
            while (along < bandOut) {
                val from = max(inner + 1, along + 3)
                if (bandOut - from >= MIN_RADIAL) {
                    val a = sign * along
                    street(sideRect(side, a - 1, a + 1, from, bandOut), Use.LANE)
                }
                along += RADIAL_GAP + random.nextInt(7) - 3
            }
        }
        // Round the citadel where it stands out into the lower town
        val c = citadel
        street(Rect(c.x0 - 4, c.z0 - 4, c.x1 + 4, c.z0 - 1), Use.LANE)
        street(Rect(c.x0 - 4, c.z0 - 4, c.x0 - 1, -(inner + 1)), Use.LANE)
        street(Rect(c.x1 + 1, c.z0 - 4, c.x1 + 4, -(inner + 1)), Use.LANE)
        // The old town: lanes down beside the citadel, the west one on through the noble quarter
        val oldRing = inner - INNER_THICK - RING_ROAD + 1
        street(Rect(c.x0 - 4, -oldRing, c.x0 - 1, oldRing), Use.LANE)
        street(Rect(c.x1 + 1, -oldRing, c.x1 + 4, -4), Use.LANE)
        // And across the noble quarter, if it's big enough to want one
        val north = plaza + 4
        val south = oldRing - 1
        if (south - north >= 40) {
            val z = (north + south) / 2
            street(Rect(-oldRing, z - 1, -4, z + 1), Use.LANE)
        }
    }

    private fun avenues() {
        val pomerium = outer - WALL_THICK - RING_ROAD + 1
        // In from each gate to the old town's (the north one to the citadel's lane), and on from the old town's gates to the plaza
        for (side in INNER_GATES) street(sideRect(side, -3, 3, inner + 1, pomerium), Use.AVENUE)
        street(Rect(-3, -pomerium, 3, citadel.z0 - 1), Use.AVENUE)
        for (side in INNER_GATES) street(sideRect(side, -3, 3, plaza + 1, cuttingTop), Use.AVENUE)
    }

    private fun squares() {
        street(Rect(-plaza, plazaNorth, plaza, plaza), Use.PLAZA)
        // The market inside the south gate, the craftsmen's square, and a square inside every gate
        street(market, Use.SQUARE)
        val (craftAlong, craftOut) = CRAFT_SQUARE[tier.ordinal]
        street(sideRect(crafts, -craftAlong, craftAlong, bandMid - craftOut, bandMid + craftOut), Use.SQUARE)
        for (side in OUTER_GATES) street(sideRect(side, -6, 6, bandOut - 6, bandOut), Use.SQUARE)
        for (side in INNER_GATES) street(sideRect(side, -6, 6, inner + 1, inner + 6), Use.SQUARE)
        street(Rect(-6, citadel.z0 - 9, 6, citadel.z0 - 1), Use.SQUARE)
        // The training yard's sand, south of the avenue in from the gate
        grid.fill(sideRect(training, 5, 5 + TRAINING_DEPTH[tier.ordinal], bandIn + 1, bandOut), Use.TRAINING)
    }

    // ---- Buildings ----

    private fun greatBuildings() {
        // Round the plaza: the guild hall and the tavern either side of the stair, the counting house and the library south of them
        val north = max(plazaNorth, -GREAT_WIDTH - 3)
        val deep = citadel.x1 - plaza
        special(Kind.GUILD_HALL, Rect(plaza + 1, north, plaza + deep, -4), Dir.W, upper)
        special(Kind.TAVERN, Rect(-(plaza + deep), north, -(plaza + 1), -4), Dir.E, upper)
        val south = min(plaza + 2, 15)
        special(Kind.COUNTING_HOUSE, Rect(plaza + 1, 4, plaza + 12, south), Dir.W, upper)
        special(Kind.LIBRARY, Rect(-(plaza + 12), 4, -(plaza + 1), south), Dir.E, upper)
        // The cathedral off the plaza's south-east corner, its front on a forecourt beside the avenue
        val (wide, long) = CATHEDRAL[tier.ordinal]
        special(Kind.CATHEDRAL, Rect(7, plaza + 4, 6 + long, plaza + 3 + wide), Dir.W, upper)
        street(Rect(4, plaza + 1, 6, plaza + 3 + wide), Use.SQUARE)

        // The market hall and the bakery facing each other across the market, and an inn for travellers by the gate
        val m = market
        special(Kind.MARKET_HALL, Rect(m.x1 + 1, bandMid - 6, m.x1 + 12, bandMid + 5), Dir.W)
        special(Kind.BAKERY, Rect(m.x0 - 12, bandMid - 6, m.x0 - 1, bandMid + 5), Dir.E)
        if (bandMid + 20 <= bandOut) special(Kind.TAVERN, Rect(m.x1 + 1, bandMid + 8, m.x1 + 14, bandMid + 20), Dir.W)

        // The smithy on the craftsmen's square
        val (craftAlong, _) = CRAFT_SQUARE[tier.ordinal]
        special(Kind.SMITHY, sideRect(crafts, -craftAlong - 17, -craftAlong - 1, bandMid - 5, bandMid + 6), Dir.S)

        // The barracks across the avenue from the training yard, the stables at its far side
        special(Kind.BARRACKS, sideRect(training, -16, -5, bandIn + 1, bandIn + 16), Dir.S)
        val yard = 5 + TRAINING_DEPTH[tier.ordinal]
        special(Kind.STABLES, sideRect(training, yard + 1, yard + 12, bandIn + 1, bandIn + 14), Dir.N)
    }

    /** The noble quarter, south-west of the plaza in the old town. */
    val nobleQuarter: Rect get() = (inner - INNER_THICK - RING_ROAD).let { Rect(-it, 4, -4, it) }

    /** The noble quarter: manors with their gardens along the old town's streets there. */
    private fun manors() {
        val quarter = nobleQuarter
        for (s in streets.filter { it.rect.intersects(quarter.grow(1)) }) {
            for (side in Dir.entries) pack(s.rect, side, Kind.MANOR, MANOR_WIDTH, MANOR_DEPTH, quarter)
        }
    }

    private fun street(r: Rect, use: Use) {
        streets.add(Street(r, use))
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            val here = grid[x, z]
            if (here == Use.FREE || (here.street && rank(use) >= rank(here))) grid[x, z] = use
        }
    }

    /** Which street wins where two cross: squares over avenues over lanes. */
    private fun rank(use: Use): Int = when (use) {
        Use.LANE, Use.ROAD -> 1
        Use.POMERIUM -> 2
        Use.AVENUE, Use.COURT -> 3
        Use.SQUARE, Use.PLAZA -> 4
        Use.STAIR -> 5
        else -> 0
    }

    private fun special(kind: Kind, lot: Rect, front: Dir, y: Int = 0) {
        val b = Building(buildings.size, kind, lot, front, seed * 31 + buildings.size * 7919L, y)
        buildings.add(b)
        grid.fill(lot, Use.LOT, b.id)
    }

    /**
     * Buildings side by side along one side of a street, each with its front on the street, skipping
     * wherever there's no room: houses and workshops a few blocks wide and deep, unless [kind] says
     * otherwise, and only inside [within] if it's given.
     */
    private fun pack(
        street: Rect, side: Dir, kind: Kind = Kind.HOUSE,
        widths: IntRange = HOUSE_WIDTH, depths: IntRange = HOUSE_DEPTH, within: Rect? = null,
    ) {
        val front = side.opposite
        val alongX = !side.alongX
        val start = if (alongX) street.x0 else street.z0
        val end = if (alongX) street.x1 else street.z1
        var t = start
        while (t <= end - widths.first + 1) {
            val width = widths.first + random.nextInt(widths.last - widths.first + 1)
            val depth = depths.first + random.nextInt(depths.last - depths.first + 1)
            val lot = fit(street, side, t, minOf(width, end - t + 1), depth, widths.first, depths.first)
            if (lot == null || (within != null && !(within.contains(lot.x0, lot.z0) && within.contains(lot.x1, lot.z1)))) {
                t++
                continue
            }
            val y = grid.height((lot.x0 + lot.x1) / 2, (lot.z0 + lot.z1) / 2)
            val k = when {
                kind != Kind.HOUSE -> kind
                random.nextInt(100) < workshopChance(lot) -> Kind.WORKSHOP
                else -> Kind.HOUSE
            }
            special(k, lot, front, y)
            t += if (alongX) lot.width else lot.depth
            // Now and then an alley between houses
            if (kind == Kind.HOUSE && random.nextInt(3) == 0) t++
        }
    }

    /** More workshops in the craftsmen's quarter. */
    private fun workshopChance(lot: Rect): Int {
        val x = (lot.x0 + lot.x1) / 2
        val z = (lot.z0 + lot.z1) / 2
        return if (max(abs(x), abs(z)) > inner && Survey.sideOf(x, z) == crafts) 65 else 20
    }

    /** The biggest lot up to [width] by [depth] (and no smaller than [minWidth] by [minDepth]) that fits at [t] on [side] of [street], or null. */
    private fun fit(street: Rect, side: Dir, t: Int, width: Int, depth: Int, minWidth: Int, minDepth: Int): Rect? {
        for (w in width downTo minWidth) for (d in depth downTo minDepth) {
            val lot = when (side) {
                Dir.N -> Rect(t, street.z0 - d, t + w - 1, street.z0 - 1)
                Dir.S -> Rect(t, street.z1 + 1, t + w - 1, street.z1 + d)
                Dir.W -> Rect(street.x0 - d, t, street.x0 - 1, t + w - 1)
                Dir.E -> Rect(street.x1 + 1, t, street.x1 + d, t + w - 1)
            }
            if (grid.allFree(lot) && flat(lot)) return lot
        }
        return null
    }

    /** Is the lot all at one height (not across a terrace's edge)? */
    private fun flat(lot: Rect): Boolean {
        val y = grid.height(lot.x0, lot.z0)
        return grid.height(lot.x1, lot.z0) == y && grid.height(lot.x0, lot.z1) == y && grid.height(lot.x1, lot.z1) == y
    }

    /** Free ground left over becomes gardens (if there's room to plant) or paved yards. */
    private fun settle() {
        val inside = outer - WALL_THICK
        val seen = HashSet<Long>()
        for (x in -inside..inside) for (z in -inside..inside) {
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
                    if (abs(nx) <= inside && abs(nz) <= inside && grid[nx, nz] == Use.FREE && seen.add(key(nx, nz))) queue.add(Pos(nx, nz))
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

    /** Only so many houses have someone living in them: enough to fill the streets, not so many the game crawls. */
    private fun tenants() {
        val homes = buildings.filter { it.kind == Kind.HOUSE || it.kind == Kind.WORKSHOP }
        if (homes.isEmpty()) return
        // About a third of houses have a bed, and half the workshops a tradesman
        val share = (TENANTS[tier.ordinal] / (homes.size * 0.35)).coerceAtMost(1.0)
        for (b in homes) b.tenant = random.nextDouble() < share
    }

    /**
     * How likely a house no villager lives in is to have townsfolk in it: enough for [HOUSEHOLDS] of them,
     * a few of whom have a child with them, however many houses the city has.
     */
    var households = 0.0
        private set

    private fun households() {
        val empty = buildings.count { it.kind == Kind.HOUSE } * (1.0 - Buildings.BED_CHANCE / 100.0 * 0.5)
        households = if (empty <= 0.0) 0.0 else (HOUSEHOLDS[tier.ordinal] / empty).coerceAtMost(1.0 / 3)
    }

    /** Flights of steps up to the walks of the outer wall, the inner wall and the citadel's. */
    private fun stairsUpTheWalls() {
        // The outer wall: one each side of every gate, halfway to the first tower, beside the road along the wall
        val firstTower = midTowers().filter { it > 0 }.minOrNull() ?: (outer / 2)
        val middle = (GATE_HALF + firstTower) / 2
        for (side in Dir.entries) for (sign in intArrayOf(-1, 1)) {
            val climb = sideDir(side, sign)
            val (x, z) = sidePos(side, sign * (middle - walk / 2), outer - WALL_THICK)
            stairs.add(WallStair(x, z, climb, walk))
        }
        // The inner wall, from the old town's ring road
        val oldRing = inner - INNER_THICK
        for (side in Dir.entries) for (sign in intArrayOf(-1, 1)) {
            val along = if (side == Dir.N) (citadel.x1 + 6 + inner) / 2 else inner / 2
            val climb = sideDir(side, sign)
            val (x, z) = sidePos(side, sign * along, oldRing)
            stairs.add(WallStair(x, z, climb, INNER_PARAPET, upper))
        }
        // The citadel's, from the courtyard either side of the gate
        for (sign in intArrayOf(-1, 1)) {
            val climb = if (sign > 0) Dir.E else Dir.W
            stairs.add(WallStair(sign * 8, citadel.z1 - CITADEL_THICK, climb, CITADEL_PARAPET, high))
        }
    }

    // ---- Furniture ----

    /** Lamps down the streets, the plaza's planters and benches, the market's stalls, the training yard's butts and rings. */
    private fun furnish() {
        val doors = HashSet<Long>()
        for (b in buildings) {
            val f = Frame(NullCanvas, b.lot, b.front)
            for (u in b.door - 2..b.door + 2) doors.add(key(f.x(u, -1), f.z(u, -1)))
        }
        val used = HashSet<Long>()
        fun lampAt(x: Int, z: Int) {
            val use = grid[x, z]
            if (!use.street || use == Use.STAIR || key(x, z) in doors) return
            if ((-1..1).any { dx -> (-1..1).any { dz -> key(x + dx, z + dz) in used } }) return
            used.add(key(x, z))
            props.add(Prop(PropKind.LAMP, x, z))
        }
        // Down both edges of each avenue, along one edge of each lane, round the inside of the walls
        for (s in streets) {
            val r = s.rect
            val alongX = r.width >= r.depth
            val (step, edges) = when (s.use) {
                Use.AVENUE -> Pair(8, if (alongX) listOf(r.z0, r.z1) else listOf(r.x0, r.x1))
                Use.LANE -> Pair(12, if (alongX) listOf(r.z0) else listOf(r.x0))
                Use.POMERIUM -> Pair(14, if (alongX) listOf(r.z0) else listOf(r.x0))
                else -> continue
            }
            val from = if (alongX) r.x0 else r.z0
            val to = if (alongX) r.x1 else r.z1
            var t = from + step / 2
            while (t <= to) {
                for (e in edges) if (alongX) lampAt(t, e) else lampAt(e, t)
                t += step
            }
        }
        // The plaza: lamps round its edge, planted trees at its corners, benches round the fountain
        for (t in -plaza + 2..plaza - 2 step 6) {
            lampAt(t, plaza)
            lampAt(-plaza, t)
            lampAt(plaza, t)
        }
        for (sx in intArrayOf(-1, 1)) {
            props.add(Prop(PropKind.PLANTER, sx * (plaza - 2), plaza - 2))
            props.add(Prop(PropKind.PLANTER, sx * (plaza - 2), plazaNorth + 2))
        }
        val bench = fountainRadius + 3
        for (d in listOf(Dir.E, Dir.S, Dir.W)) {
            val x = fountain.x + d.dx * bench
            val z = fountain.z + d.dz * bench
            props.add(Prop(PropKind.BENCH, x + d.cw.dx * 3, z + d.cw.dz * 3, d.opposite))
        }
        // A few stalls on the plaza
        for ((i, sx) in intArrayOf(-1, 1).withIndex()) props.add(Prop(PropKind.STALL, sx * (plaza - 3), fountain.z + 1, if (sx < 0) Dir.E else Dir.W, i * 3))

        // The market: rows of stalls down either side of the avenue and along the square's edges, and two wells
        val m = market
        var z = m.z0 + 3
        var n = 0
        while (z <= m.z1 - 3) {
            for (sx in intArrayOf(-1, 1)) {
                if (key(sx * (m.x1 + 1) - sx, z) !in doors) props.add(Prop(PropKind.STALL, sx * (m.x1 - 2), z, if (sx < 0) Dir.E else Dir.W, n++))
                props.add(Prop(PropKind.STALL, sx * (m.x1 / 2 + 2), z, if (sx < 0) Dir.W else Dir.E, n++))
            }
            z += 7
        }
        for (sx in intArrayOf(-1, 1)) props.add(Prop(PropKind.WELL, sx * (m.x1 / 2 + 2), m.z1 - 1))
        for (t in m.x0 + 2..m.x1 - 2 step 7) if (abs(t) > 4) lampAt(t, m.z0)

        // The craftsmen's square: a well, a cart unloading
        val (craftAlong, craftOut) = CRAFT_SQUARE[tier.ordinal]
        val well = sidePos(crafts, craftAlong - 3, bandMid)
        props.add(Prop(PropKind.WELL, well.x, well.z))
        val cart = sidePos(crafts, -craftAlong + 2, bandMid + craftOut - 3)
        props.add(Prop(PropKind.HAY_CART, cart.x, cart.z, crafts.opposite))

        // The training yard: butts along its far end, sparring rings, a cart of hay for the horses
        val yard = 5 + TRAINING_DEPTH[tier.ordinal]
        for (along in 8..yard - 2 step 3) {
            val p = sidePos(training, along, bandOut - 1)
            props.add(Prop(PropKind.TARGET, p.x, p.z, training.opposite))
        }
        var out = bandIn + 6
        while (out <= bandOut - 8) {
            val p = sidePos(training, 5 + yard / 2, out)
            props.add(Prop(PropKind.RING, p.x, p.z, training))
            out += 11
        }
        val hay = sidePos(training, yard - 1, bandIn + 3)
        props.add(Prop(PropKind.HAY_CART, hay.x, hay.z, training))

        // The citadel's courtyard: a well, and lamps by the palace door
        props.add(Prop(PropKind.WELL, citadel.x0 + CITADEL_THICK + 4, citadel.z1 - CITADEL_THICK - 3))
        for (sx in intArrayOf(-1, 1)) props.add(Prop(PropKind.LAMP, sx * 5, PALACE[tier.ordinal].z1 + 2))
    }

    // ---- What the rest of the game asks of the plan ----

    /** Where a road leaves the outer wall: the middle of each side. */
    val outerGates: List<Dir> get() = OUTER_GATES

    /** Is this column (in the city's frame) inside the outer walls? */
    fun within(x: Int, z: Int): Boolean = max(abs(x), abs(z)) <= outer

    /**
     * The way from the plaza out through the outer gate on [gate]'s side: points along the streets a step
     * at a time, skipping any spot under a roof (a gate's arch or passage, where someone looking for the
     * ground would find the top of the arch), ending on the road just past the farms. Heights are [level]'s.
     */
    fun wayOut(gate: Dir): List<Pos> {
        val goal = sidePos(gate, 0, outer + GATE_OUT + 1)
        val path = route(Pos(0, plaza), goal) ?: listOf(goal)
        val points = ArrayList<Pos>()
        for ((i, p) in path.withIndex()) {
            if (i % 8 != 0 && i != path.size - 1) continue
            if (grid[p.x, p.z] == Use.GATE) continue
            points.add(p)
        }
        points.add(sidePos(gate, 0, levelled))
        points.add(sidePos(gate, 0, reach + 5))
        return points
    }

    /** The shortest walk along the streets (and through the gates) from [start] to [goal], or null. */
    private fun route(start: Pos, goal: Pos): List<Pos>? {
        val from = HashMap<Long, Long>()
        val queue = ArrayDeque<Pos>()
        from[key(start.x, start.z)] = key(start.x, start.z)
        queue.add(start)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (p == goal) {
                val path = ArrayList<Pos>()
                var k = key(p.x, p.z)
                while (true) {
                    path.add(Pos((k shr 32).toInt(), k.toInt()))
                    val back = from.getValue(k)
                    if (back == k) break
                    k = back
                }
                return path.reversed()
            }
            for (d in Dir.entries) {
                val nx = p.x + d.dx
                val nz = p.z + d.dz
                if (!walkable(nx, nz) || from.containsKey(key(nx, nz))) continue
                // Never more than a step up or down at a time
                if (abs(grid.height(nx, nz) - grid.height(p.x, p.z)) > 1) continue
                from[key(nx, nz)] = key(p.x, p.z)
                queue.add(Pos(nx, nz))
            }
        }
        return null
    }

    private fun walkable(x: Int, z: Int): Boolean {
        if (!grid.inside(x, z)) return false
        val use = grid[x, z]
        // Along the streets, and through a gate's passage but never round the side of its towers
        return use.street || (use == Use.GATE && (abs(x) <= 2 || abs(z) <= 2))
    }

    /**
     * The standing height of the city's ground at a column, over the lower town's: 0 in the lower town,
     * [upper] on the old town's terrace, [high] on the citadel's, sloping to the land across the glacis,
     * and the land's own height past it.
     */
    fun level(x: Int, z: Int): Int {
        val ring = max(abs(x), abs(z))
        if (ring <= outer) return grid.height(x, z)
        val use = grid[x, z]
        if (ring > levelled || use == Use.OUTSIDE) return land.height(x, z)
        return when (use) {
            Use.GATE -> 0
            // The roads climb from the gatehouse's front to the land
            Use.ROAD -> Math.round(land.height(x, z) * (ring - outer - GATE_OUT).toDouble() / (levelled - outer - GATE_OUT)).toInt()
            Use.GLACIS, Use.TOWER -> Math.round(land.height(x, z) * (ring - outer).toDouble() / (levelled - outer)).toInt()
            else -> land.height(x, z)
        }
    }

    /**
     * The city's flat terraces, each at one height and none overlapping: where the land is cut down or built
     * up to it before anything is drawn (see the capital's beard pieces). Between them stand the walls.
     */
    val terraces: List<Terrace> by lazy {
        val lower = outer - WALL_THICK
        val c = citadel
        val old = inner - INNER_THICK
        listOf(
            // The lower town, in bands round the old town and the citadel's foot
            Terrace(Rect(-lower, inner + 1, lower, lower), 0),
            Terrace(Rect(-lower, -lower, lower, c.z0 - 4), 0),
            Terrace(Rect(-lower, c.z0 - 3, c.x0 - 4, -(inner + 1)), 0),
            Terrace(Rect(c.x1 + 4, c.z0 - 3, lower, -(inner + 1)), 0),
            Terrace(Rect(-lower, -inner, -(inner + 1), inner), 0),
            Terrace(Rect(inner + 1, -inner, lower, inner), 0),
            // The old town, round the citadel
            Terrace(Rect(-old, c.z1 + 1, old, old), upper),
            Terrace(Rect(-old, -old, c.x0 - 1, c.z1), upper),
            Terrace(Rect(c.x1 + 1, -old, old, c.z1), upper),
            // The citadel, inside its walls
            Terrace(Rect(c.x0 + CITADEL_THICK, c.z0 + CITADEL_THICK, c.x1 - CITADEL_THICK, c.z1 - CITADEL_THICK), high),
        )
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

    /** Where [u], [y], [v] in the frame of the city's [kind] of building is in the city's, if it has one. */
    fun inside(kind: Kind, u: Int, y: Int, v: Int): At? =
        buildings.firstOrNull { it.kind == kind }?.let { Frame(NullCanvas, it.lot, it.front, it.y).at(u, y, v) }

    /** The direction along [side]'s wall in which "along" grows ([sign] 1) or shrinks. */
    fun sideDir(side: Dir, sign: Int): Dir = if (side.alongX) (if (sign > 0) Dir.S else Dir.N) else (if (sign > 0) Dir.E else Dir.W)

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    companion object {
        /** The biggest city's reach, outer wall and levelled ground: for code that only needs an upper bound. */
        val REACH = Tier.GRAND.reach
        val CLEAR = Tier.GRAND.levelled
        val WALL_OUT = Tier.GRAND.outer

        /** The outer walls' thickness, and how far a gatehouse stands out past their outer face. */
        const val WALL_THICK = 4
        const val GATE_OUT = 8
        /** Half a gatehouse's width along the wall, its passage's half-width being 2. */
        const val GATE_HALF = 8
        const val OUTER_CORNER_RADIUS = 7.5
        val OUTER_GATES = listOf(Dir.S, Dir.E, Dir.W, Dir.N)
        /** The old town's gates: the citadel takes the north side. */
        val INNER_GATES = listOf(Dir.S, Dir.E, Dir.W)
        /** The roads along the walls' inner faces, and the inner wall's thickness. */
        const val RING_ROAD = 4
        const val INNER_THICK = 3
        const val INNER_TOWER_RADIUS = 3.5
        /** How far the inner wall and the citadel's stand over the ground inside them. */
        const val INNER_PARAPET = 4
        const val CITADEL_PARAPET = 4
        const val CITADEL_THICK = 3
        const val CITADEL_TOWER_RADIUS = 4.5
        /** From the citadel's wall to the foot of its stair. */
        const val STAIR_DEPTH = 4

        // By size: medium, large, grand
        val OUTER_WALK = intArrayOf(10, 12, 14)
        val PLAZA_HALF = intArrayOf(11, 13, 16)
        val FOUNTAIN_RADIUS = intArrayOf(5, 6, 6)
        val CITADEL = arrayOf(Rect(-25, -62, 25, -19), Rect(-28, -73, 28, -22), Rect(-32, -87, 32, -26))
        val PALACE = arrayOf(Rect(-16, -55, 16, -29), Rect(-19, -66, 19, -36), Rect(-22, -80, 22, -44))
        /** Across its front, and from front to back. */
        val CATHEDRAL = arrayOf(21 to 31, 25 to 41, 27 to 49)
        /** Half the market's width across the avenue, and half its length along it. */
        val MARKET = arrayOf(16 to 9, 21 to 12, 26 to 15)
        /** Half the craftsmen's square across the avenue and along it. */
        val CRAFT_SQUARE = arrayOf(8 to 7, 10 to 9, 12 to 11)
        val TRAINING_DEPTH = intArrayOf(17, 23, 29)
        /** About how many villagers live in the houses and workshops. */
        val TENANTS = doubleArrayOf(36.0, 48.0, 60.0)
        /** About how many households of townsfolk live in the houses villagers don't. */
        val HOUSEHOLDS = doubleArrayOf(24.0, 30.0, 36.0)

        /** The great buildings round the plaza are no wider than this across their fronts. */
        const val GREAT_WIDTH = 14
        /** Lanes out across the lower town: the first this far from the avenue, then about this far apart. */
        const val RADIAL_FIRST = 22
        const val RADIAL_GAP = 27
        const val MIN_RADIAL = 10
        val HOUSE_WIDTH = 5..9
        val HOUSE_DEPTH = 6..10
        val MANOR_WIDTH = 15..21
        val MANOR_DEPTH = 18..26

        fun inRound(dx: Int, dz: Int, radius: Double): Boolean = dx * dx + dz * dz <= radius * radius
    }
}

/** Uses, owners and ground heights of every column of the city. */
class Grid(val reach: Int) {
    private val size = reach * 2 + 1
    private val uses = ByteArray(size * size) { Use.OUTSIDE.ordinal.toByte() }
    private val owners = IntArray(size * size) { -1 }
    private val heights = ShortArray(size * size)

    fun inside(x: Int, z: Int): Boolean = abs(x) <= reach && abs(z) <= reach

    operator fun get(x: Int, z: Int): Use = if (inside(x, z)) Use.entries[uses[index(x, z)].toInt()] else Use.OUTSIDE

    operator fun set(x: Int, z: Int, use: Use) {
        if (inside(x, z)) uses[index(x, z)] = use.ordinal.toByte()
    }

    fun owner(x: Int, z: Int): Int = if (inside(x, z)) owners[index(x, z)] else -1

    /** The city's standing height at a column, over the lower town's (inside the outer walls). */
    fun height(x: Int, z: Int): Int = if (inside(x, z)) heights[index(x, z)].toInt() else 0

    fun setHeight(x: Int, z: Int, y: Int) {
        if (inside(x, z)) heights[index(x, z)] = y.toShort()
    }

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
    override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {}
    override fun clearing(x: Int, z: Int, y: Int) {}
    override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {}
    override fun board(x: Int, y: Int, z: Int, facing: Dir) {}
    override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {}
    override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {}
}
