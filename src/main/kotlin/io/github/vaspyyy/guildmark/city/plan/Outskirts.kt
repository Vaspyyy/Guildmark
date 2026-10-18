package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A country road out of the gate on [side], row by row from the foot of the glacis ([first] out from the
 * middle) to the edge of the farms: each row runs [halves] either side of its middle ([centres], across the
 * side from the gate's line) at standing height [heights]. The [main] road is the widest, gravelled all the
 * way; the others narrow to a dirt track past their hamlets.
 */
class CountryRoad(
    val side: Dir,
    val first: Int,
    val centres: IntArray,
    val halves: IntArray,
    val heights: IntArray,
    val main: Boolean,
) {
    val rows: Int get() = centres.size

    /** The column [k] across from the middle of row [i]. */
    fun at(i: Int, k: Int): CityPlan.Pos = across(side, centres[i] + k, first + i)

    /** Where it leaves the farms, heading straight out: the middle of its last row. */
    val exit: CityPlan.Pos get() = at(rows - 1, 0)
}

/** The buildings of the country, each drawn by [FarmBuildings]; [reach] is how far past its lot it may draw. */
enum class FarmKind(val reach: Int) { COTTAGE(1), FARMHOUSE(2), BARN(2), INN(2), STABLE(1), COOP(1), SHELTER(1), WINDMILL(5), MILL(2) }

/** A building in the country on its [lot], its front toward [front], standing at height [y]; [v] picks a variant. */
class FarmBuilding(val kind: FarmKind, val lot: Rect, val front: Dir, val y: Int, val seed: Long, val v: Int = 0) {
    val bounds: Rect get() = lot.grow(kind.reach)
}

/** The small things of the country, each drawn by [FarmProps]; [reach] is how far from its spot it may draw. */
enum class FarmPropKind(val reach: Int) {
    LAMP(0), WELL(1), CART(2), HAYSTACK(1), SCARECROW(1), WAYSTONE(1), SHRINE(2), TREE(3), FRUIT_TREE(2), TROUGH(1),
    WOODPILE(2), COMPOST(1), WHEEL(4),
}

/** One of the small things, at [x], [z] standing at [y], facing [facing]; [v] picks a variant. */
class FarmProp(val kind: FarmPropKind, val x: Int, val z: Int, val y: Int, val facing: Dir = Dir.N, val v: Int = 0) {
    val bounds: Rect get() = Rect(x - kind.reach, z - kind.reach, x + kind.reach, z + kind.reach)
}

/** A beast out at grass or in its pen. */
class Beast(val what: Spawn, val x: Int, val y: Int, val z: Int, val facing: Dir)

/**
 * The country round a capital, past the foot of its glacis: the roads out from the gates, each with a hamlet
 * of cottages along it by the walls and trees along it past them, and the inn by the main road; farmsteads
 * down their own lanes, a farmhouse and a great barn round a yard; windmills on the rises by the roads and a
 * watermill where a road bridges a stream; and between them fields of crops in long strips, pastures and
 * orchards, all following the lie of the land, with nothing on water, on a side a village has taken, or on
 * ground too steep for it.
 *
 * Everything is worked out once, in [lay], into what each column of the ring holds (a road, a crop, a ditch,
 * a hedge) and the buildings and things set down on it; [draw] then draws only what falls in a canvas's clip.
 */
class Outskirts(private val plan: CityPlan) {
    /** The first ring of columns past the foot of the glacis, the last, and the rows between. */
    private val first = plan.levelled + 1
    private val last = plan.reach
    private val rows = last - first + 1
    private val size = last * 2 + 1

    // What's laid in each column: a Cell, the standing height on it, and whatever more the cell needs
    private val cells = ByteArray(size * size)
    private val heights = ShortArray(size * size)
    private val details = ShortArray(size * size)

    // The land as the world made it, asked once of each column while laying out and let go after
    private var landHeights = IntArray(0)
    private var landWet = BooleanArray(0)

    /** The roads out of the gates, the main road (toward the south gate where there is one) first. */
    val roads = ArrayList<CountryRoad>()
    val buildings = ArrayList<FarmBuilding>()
    val props = ArrayList<FarmProp>()
    val beasts = ArrayList<Beast>()

    private var fields = 0
    private var pastures = 0
    private var orchards = 0
    private var bridges = 0

    /** How many rows out from the glacis the hamlet along each road runs. */
    private val hamletRows = when (plan.tier) {
        Tier.MEDIUM -> 14
        Tier.LARGE -> 18
        Tier.GRAND -> 22
    }

    /** Lays the farms out on the plan: called once, after the walls, the town and the harbour. */
    fun lay(random: Random) {
        survey()
        val sides = plan.outerGates.filter { it != plan.layout.harbour && it !in plan.layout.crowded }
        val main = if (Dir.S in sides) Dir.S else sides.firstOrNull()
        for (side in sides.sortedBy { if (it == main) 0 else 1 }) road(side, side == main, Random(random.nextLong()))
        // The inn before the hamlets, so that it has its pick of the ground by the road and the cottages fill in round it
        inn(Random(random.nextLong()))
        for (road in roads) hamlet(road, Random(random.nextLong()))
        windmills(Random(random.nextLong()))
        farmsteads(Random(random.nextLong()))
        watermill(Random(random.nextLong()))
        waystones(Random(random.nextLong()))
        roadsideTrees(Random(random.nextLong()))
        parcels(Random(random.nextLong()))
        landHeights = IntArray(0)
        landWet = BooleanArray(0)
    }

    /** Draws whatever of the farms falls in [canvas]'s clip. */
    fun draw(canvas: Canvas) {
        val clip = canvas.clip
        for (x in max(clip.x0, -last)..min(clip.x1, last)) for (z in max(clip.z0, -last)..min(clip.z1, last)) {
            val i = index(x, z)
            val cell = cells[i].toInt()
            if (cell != 0) column(canvas, x, z, Cell.entries[cell], heights[i].toInt(), details[i].toInt())
        }
        for (b in buildings) if (canvas.touches(b.bounds)) FarmBuildings(Frame(canvas, b.lot, b.front, b.y), b, Random(b.seed)).draw()
        val things = FarmProps(canvas, plan.seed, streetLamps = true)
        for (p in props) if (canvas.touches(p.bounds)) things.draw(p)
        for (b in beasts) if (clip.contains(b.x, b.z)) canvas.spawn(b.what, b.x, b.y, b.z, b.facing)
    }

    /** The standing height of whatever the farms laid at a column (a road, a field, a yard), or null if they laid nothing there. */
    fun level(x: Int, z: Int): Int? {
        if (!inSquare(x, z)) return null
        val i = index(x, z)
        return if (cells[i].toInt() == 0) null else heights[i].toInt()
    }

    override fun toString(): String =
        "${roads.size} roads, $bridges bridges, ${count(FarmKind.FARMHOUSE)} farmsteads, ${count(FarmKind.COTTAGE)} cottages, " +
            "${count(FarmKind.INN)} inn, ${count(FarmKind.WINDMILL)} windmills, ${count(FarmKind.MILL)} watermills, " +
            "$fields fields, $pastures pastures, $orchards orchards, ${props.count { it.kind == FarmPropKind.TREE }} trees, ${beasts.size} beasts"

    private fun count(kind: FarmKind) = buildings.count { it.kind == kind }

    // ---- The land ----

    private fun survey() {
        landHeights = IntArray(size * size)
        landWet = BooleanArray(size * size)
        for (x in -last..last) for (z in -last..last) {
            if (max(abs(x), abs(z)) < plan.levelled - 1) continue
            val i = index(x, z)
            landHeights[i] = plan.land.height(x, z)
            landWet[i] = plan.land.wet(x, z)
        }
    }

    private fun index(x: Int, z: Int): Int = (x + last) * size + (z + last)
    private fun inSquare(x: Int, z: Int): Boolean = abs(x) <= last && abs(z) <= last
    private fun inRing(x: Int, z: Int): Boolean = inSquare(x, z) && max(abs(x), abs(z)) >= first
    private fun land(x: Int, z: Int): Int = landHeights[index(x, z)]
    private fun wet(x: Int, z: Int): Boolean = inSquare(x, z) && landWet[index(x, z)]
    private fun cell(x: Int, z: Int): Cell = if (inSquare(x, z)) Cell.entries[cells[index(x, z)].toInt()] else Cell.NONE

    /** May the farms have this column: in the ring, dry, nobody else's, and not on a side a village has taken? */
    private fun free(x: Int, z: Int): Boolean =
        inRing(x, z) && plan.grid[x, z] == Use.OUTSIDE && !landWet[index(x, z)] && Survey.sideOf(x, z) !in plan.layout.crowded

    private fun free(r: Rect): Boolean {
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) if (!free(x, z)) return false
        return true
    }

    /** Water in the ring that nobody has claimed, which a bridge or a mill wheel may stand over. */
    private fun openWater(x: Int, z: Int): Boolean =
        inRing(x, z) && wet(x, z) && plan.grid[x, z] == Use.OUTSIDE && Survey.sideOf(x, z) !in plan.layout.crowded

    /** Marks a column as the farms' ([use], unless it's water nobody claims) and what's laid there. */
    private fun put(x: Int, z: Int, use: Use?, cell: Cell, y: Int, detail: Int = 0) {
        if (use != null) plan.grid[x, z] = use
        val i = index(x, z)
        cells[i] = cell.ordinal.toByte()
        heights[i] = y.toShort()
        details[i] = detail.toShort()
    }

    /** A number that looks random but is fixed for each column of this city. */
    private fun noise(x: Int, z: Int): Int {
        var h = plan.seed xor (x * 0x9E3779B97F4A7C15uL.toLong()) xor (z * 0xC2B2AE3D27D4EB4FuL.toLong())
        h = (h xor (h ushr 31)) * 0x7FB5D329728EA185L
        h = h xor (h ushr 27)
        return (h ushr 33).toInt()
    }

    /** The middle of the land's heights over [r], and how far from it the land strays at most. */
    private fun lie(r: Rect): Pair<Int, Int> {
        val hs = IntArray(r.width * r.depth)
        var n = 0
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) hs[n++] = land(x, z)
        hs.sort()
        val mid = hs[n / 2]
        return Pair(mid, max(mid - hs[0], hs[n - 1] - mid))
    }

    // ---- Roads ----

    /**
     * The road out of the gate on [side]: straight through the hamlet by the gate, then wandering a little
     * toward drier, flatter ground, and straight again as it leaves the farms. Each row is level across, and
     * no row stands more than a block over or under the next; over water it goes on a bridge.
     */
    private fun road(side: Dir, main: Boolean, r: Random) {
        val straightIn = hamletRows + 4
        val wide = if (main) rows else straightIn + 2 + r.nextInt(5)
        val halves = IntArray(rows) { if (it < wide) 2 else 1 }
        val centres = wander(side, halves, straightIn, STRAIGHT_OUT, r)
        // Where it runs over water, the deck stands clear of it, a block higher in the middle of a long span
        val wetRow = BooleanArray(rows)
        val water = IntArray(rows) { NO_FLOOR }
        val targets = IntArray(rows)
        for (i in 0 until rows) {
            var sum = 0
            var count = 0
            for (k in -halves[i] - 1..halves[i] + 1) {
                val p = across(side, centres[i] + k, first + i)
                if (wet(p.x, p.z)) {
                    water[i] = max(water[i], land(p.x, p.z))
                    if (abs(k) <= halves[i]) wetRow[i] = true
                } else if (abs(k) <= halves[i]) {
                    sum += land(p.x, p.z)
                    count++
                }
            }
            targets[i] = if (count > 0) (sum.toDouble() / count).roundToInt() else water[i] + 1
        }
        val floors = IntArray(rows) { i ->
            val inMiddle = (1..2).all { d -> wetRow.getOrElse(i - d) { false } && wetRow.getOrElse(i + d) { false } }
            if (water[i] == NO_FLOOR) NO_FLOOR else water[i] + 1 + if (wetRow[i] && inMiddle) 1 else 0
        }
        val gate = across(side, 0, plan.levelled)
        val start = plan.level(gate.x, gate.z)
        val road = CountryRoad(side, first, centres, halves, smooth(targets, floors, start - 1..start + 1), main)
        roads.add(road)

        // Cobbled from the gate through the hamlet, then gravel or a dirt track, the change ragged
        val cobbled = straightIn - 1 + r.nextInt(3)
        val beyond = if (main || r.nextInt(3) == 0) GRAVEL else DIRT
        for (i in 0 until rows) for (k in -halves[i]..halves[i]) {
            val p = road.at(i, k)
            if (!free(p.x, p.z)) continue
            val surface = if (i + Math.floorMod(noise(p.x, p.z), 4) < cobbled) COBBLES else beyond
            put(p.x, p.z, Use.ROAD, Cell.GROUND, road.heights[i], surface)
        }
        bridges(road, r)
        // A lamp either side where the country road begins
        for (k in intArrayOf(-halves[0] - 1, halves[0] + 1)) {
            val p = road.at(0, k)
            if (!free(p.x, p.z)) continue
            put(p.x, p.z, Use.ROAD, Cell.GROUND, road.heights[0], COBBLES)
            props.add(FarmProp(FarmPropKind.LAMP, p.x, p.z, road.heights[0]))
        }
    }

    /**
     * Where a road's middle runs, row by row, across its side (0 being straight out from the gate). It keeps
     * to the gate's line for [straightIn] rows and the last [straightOut]; between, it follows a gentle bow
     * one way or the other, drawn aside by water and broken ground, shifting a block at most every few rows.
     */
    private fun wander(side: Dir, halves: IntArray, straightIn: Int, straightOut: Int, r: Random): IntArray {
        val n = halves.size
        val offsets = WANDER * 2 + 1
        val bow = (1 + r.nextInt(WANDER)) * (if (r.nextBoolean()) 1.0 else -1.0) * 0.6
        val cost = Array(n) { i ->
            val t = (i - straightIn).toDouble() / max(1, n - straightIn - straightOut)
            val want = if (t in 0.0..1.0) bow * sin(PI * t) else 0.0
            DoubleArray(offsets) { o -> rowCost(side, i, o - WANDER, halves[i]) + abs(o - WANDER - want) * DRIFT_COST }
        }
        val runs = SHIFT_EVERY + 1
        val best = Array(n) { Array(offsets) { DoubleArray(runs) { Double.MAX_VALUE } } }
        val back = Array(n) { Array(offsets) { IntArray(runs) } }
        best[0][WANDER][SHIFT_EVERY] = cost[0][WANDER]
        for (i in 1 until n) {
            val pinned = i <= straightIn || i >= n - straightOut
            for (o in 0 until offsets) {
                if (pinned && o != WANDER) continue
                for (po in max(0, o - 1)..min(offsets - 1, o + 1)) for (pr in 0 until runs) {
                    val before = best[i - 1][po][pr]
                    if (before == Double.MAX_VALUE) continue
                    val run = if (po == o) min(pr + 1, SHIFT_EVERY) else if (pr == SHIFT_EVERY) 0 else continue
                    val c = before + cost[i][o]
                    if (c < best[i][o][run]) {
                        best[i][o][run] = c
                        back[i][o][run] = po * runs + pr
                    }
                }
            }
        }
        val centres = IntArray(n)
        var o = WANDER
        var run = (0 until runs).minBy { best[n - 1][WANDER][it] }
        for (i in n - 1 downTo 0) {
            centres[i] = o - WANDER
            if (i == 0) break
            val from = back[i][o][run]
            o = from / runs
            run = from % runs
        }
        return centres
    }

    /** What it costs a road to run row [i] with its middle at [c]: water to bridge, a slope across it, other people's land. */
    private fun rowCost(side: Dir, i: Int, c: Int, half: Int): Double {
        var cost = 0.0
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (k in c - half - 1..c + half + 1) {
            val p = across(side, k, first + i)
            val onRoad = abs(k - c) <= half
            when {
                wet(p.x, p.z) -> cost += if (onRoad) WET_COST else WET_COST / 4
                !free(p.x, p.z) -> if (onRoad) cost += 1000.0
                onRoad -> {
                    lo = min(lo, land(p.x, p.z))
                    hi = max(hi, land(p.x, p.z))
                }
            }
        }
        if (lo <= hi) cost += (hi - lo) * CROSS_COST
        return cost
    }

    /**
     * Standing heights for a run of rows as near [targets] as can be with no step of more than a block from
     * one to the next, kept up to [floors] where given and starting within [start] and ending within [end]
     * if asked. A step costs a little, so the way runs level wherever it nearly could.
     */
    private fun smooth(targets: IntArray, floors: IntArray? = null, start: IntRange? = null, end: IntRange? = null): IntArray {
        val n = targets.size
        var lo = targets.min()
        var hi = targets.max()
        floors?.forEach { if (it != NO_FLOOR) hi = max(hi, it) }
        start?.let { lo = min(lo, it.first); hi = max(hi, it.last) }
        end?.let { lo = min(lo, it.first); hi = max(hi, it.last) }
        lo -= 2
        hi += 2
        val span = hi - lo + 1
        val cost = Array(n) { DoubleArray(span) }
        val from = Array(n) { IntArray(span) }
        for (i in 0 until n) for (k in 0 until span) {
            val h = lo + k
            var here = abs(h - targets[i]).toDouble()
            if (floors != null && floors[i] != NO_FLOOR && h < floors[i]) here += 1000.0 * (floors[i] - h)
            if (i == 0 && start != null && h !in start) here += 1000.0 * min(abs(h - start.first), abs(h - start.last))
            if (i == n - 1 && end != null && h !in end) here += 1000.0 * min(abs(h - end.first), abs(h - end.last))
            if (i == 0) {
                cost[i][k] = here
                continue
            }
            var best = Double.MAX_VALUE
            for (dk in -1..1) {
                val pk = k + dk
                if (pk !in 0 until span) continue
                val c = cost[i - 1][pk] + if (dk != 0) STEP_COST else 0.0
                if (c < best) {
                    best = c
                    from[i][k] = pk
                }
            }
            cost[i][k] = best + here
        }
        val out = IntArray(n)
        var k = (0 until span).minBy { cost[n - 1][it] }
        for (i in n - 1 downTo 0) {
            out[i] = lo + k
            k = from[i][k]
        }
        return out
    }

    /** The bridges where [road] crosses water. */
    private fun bridges(road: CountryRoad, r: Random) {
        for ((i0, i1) in wetRuns(road)) bridge(road, i0, i1, r)
    }

    /** The runs of rows where [road] crosses water, first row to last. */
    private fun wetRuns(road: CountryRoad): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < road.rows) {
            if (!rowWet(road, i)) {
                i++
                continue
            }
            var j = i
            while (j + 1 < road.rows && rowWet(road, j + 1)) j++
            runs.add(Pair(i, j))
            i = j + 1
        }
        return runs
    }

    private fun rowWet(road: CountryRoad, i: Int): Boolean =
        (-road.halves[i]..road.halves[i]).any { k -> road.at(i, k).let { wet(it.x, it.z) } }

    /**
     * A bridge carrying [road] over water from row [i0] to [i1]: stone on arches where it's short, timber on
     * piers of logs where it's long, with a parapet or railing each side running a row onto either bank and a
     * lamp at each end. The water under it is nobody's; only its ends on the banks are the road's.
     */
    private fun bridge(road: CountryRoad, i0: Int, i1: Int, r: Random) {
        bridges++
        val length = i1 - i0 + 1
        val stone = length <= 7 && r.nextInt(4) != 0
        val piers = HashSet<Int>()
        if (length >= 5) {
            val spans = (length + 3) / 4
            for (s in 1 until spans) piers.add(i0 - 1 + (s * (length + 1)) / spans)
        }
        for (i in i0 - 1..i1 + 1) {
            if (i !in 0 until road.rows) continue
            val half = road.halves[i]
            val y = road.heights[i]
            for (k in -half - 1..half + 1) {
                val p = road.at(i, k)
                val rail = abs(k) == half + 1
                if ((i < i0 || i > i1) && !rail) continue
                val water = wet(p.x, p.z)
                val ours = cell(p.x, p.z) == Cell.GROUND && plan.grid[p.x, p.z] == Use.ROAD
                if (if (water) !openWater(p.x, p.z) else !free(p.x, p.z) && !ours) continue
                var detail = if (stone) STONE_BRIDGE else 0
                if (rail) detail = detail or RAILED
                if (!water || i in piers) detail = detail or GROUNDED
                else if (stone && y - 2 >= land(p.x, p.z)) {
                    // Under the deck, the arch springs from the pier or the bank beside it
                    val behind = i == i0 || (i - 1) in piers
                    val ahead = i == i1 || (i + 1) in piers
                    if (behind != ahead) detail = detail or (((if (behind) road.side.opposite else road.side).ordinal + 1) shl ARCH_SHIFT)
                }
                put(p.x, p.z, if (water) null else Use.ROAD, Cell.BRIDGE, y, detail)
            }
        }
        for ((i, k) in listOf(Pair(i0 - 1, -1), Pair(i1 + 1, 1))) {
            if (i !in 0 until road.rows) continue
            val p = road.at(i, k * (road.halves[i] + 1))
            if (cell(p.x, p.z) == Cell.BRIDGE) props.add(FarmProp(FarmPropKind.LAMP, p.x, p.z, road.heights[i]))
        }
    }

    /**
     * A lane [width] wide along row [row] out from the middle, on [side] (the rows past it too, if it's wider
     * than one), from [a0] to [a1] across the side: the [a0] end meets a road at standing height [near], the
     * other ends at [far] if given. Gives back its heights, or null if anything's in the way (more water than
     * a short bridge's worth, or someone else's ground). With [claim] it's laid, as [use].
     */
    private fun lane(side: Dir, row: Int, a0: Int, a1: Int, width: Int, near: Int, far: Int?, use: Use, claim: Boolean): IntArray? {
        val step = if (a1 >= a0) 1 else -1
        val length = abs(a1 - a0) + 1
        val lo = -(width - 1) / 2
        val hi = width / 2
        val targets = IntArray(length)
        val floors = IntArray(length) { NO_FLOOR }
        var wetRun = 0
        for (j in 0 until length) {
            val a = a0 + j * step
            var sum = 0
            var count = 0
            var wetHere = false
            for (dn in lo..hi) {
                val p = across(side, a, row + dn)
                if (wet(p.x, p.z)) {
                    if (!openWater(p.x, p.z)) return null
                    floors[j] = max(floors[j], land(p.x, p.z) + 1)
                    wetHere = true
                } else if (!free(p.x, p.z)) {
                    return null
                } else {
                    sum += land(p.x, p.z)
                    count++
                }
            }
            wetRun = if (wetHere) wetRun + 1 else 0
            if (wetRun > MAX_LANE_BRIDGE || (wetHere && (j == 0 || j == length - 1))) return null
            targets[j] = if (count > 0) (sum.toDouble() / count).roundToInt() else floors[j]
        }
        val heights = smooth(targets, floors, near - 1..near + 1, far?.let { it - 1..it + 1 })
        if (abs(heights[0] - near) > 1 || (far != null && abs(heights[length - 1] - far) > 1)) return null
        if (!claim) return heights
        for (j in 0 until length) {
            val a = a0 + j * step
            for (dn in lo..hi) {
                val p = across(side, a, row + dn)
                if (wet(p.x, p.z)) {
                    put(p.x, p.z, null, Cell.BRIDGE, heights[j], if (j % 4 == 0) GROUNDED else 0)
                    continue
                }
                // Wheel ruts with grass between down a cart lane; a footpath's just trodden earth
                val surface = if (width >= 3 && dn == 0) GRASS else if (width >= 3) DIRT else EARTH
                put(p.x, p.z, use, Cell.GROUND, heights[j], surface)
            }
        }
        return heights
    }

    // ---- Hamlets and the inn ----

    /** Cottages on their plots either side of [road] near the gate, wherever the land will take them, and a wayside shrine. */
    private fun hamlet(road: CountryRoad, r: Random) {
        for (sign in intArrayOf(-1, 1)) {
            var i = 1 + r.nextInt(2)
            while (i < hamletRows - 5) {
                val frontage = 8 + r.nextInt(3)
                val depth = 14 + r.nextInt(3)
                i += if (cottage(road, sign, i, frontage, depth, Random(r.nextLong()))) frontage + 1 + r.nextInt(3) else 2
            }
        }
        for (sign in intArrayOf(1, -1)) {
            val i = hamletRows - 3
            val p = road.at(i, sign * (road.halves[i] + 2))
            val spot = Rect(p.x - 1, p.z - 1, p.x + 1, p.z + 1)
            if (!free(spot) || lie(spot).second > 1 || abs(lie(spot).first - road.heights[i]) > 1) continue
            for (x in spot.x0..spot.x1) for (z in spot.z0..spot.z1) put(x, z, Use.HAMLET, Cell.GROUND, road.heights[i], GRASS)
            props.add(FarmProp(FarmPropKind.SHRINE, p.x, p.z, road.heights[i], crossDir(road.side, -sign), r.nextInt(4)))
            break
        }
    }

    /**
     * A plot by [road] on side [sign] of it, rows [i] on for [frontage], [depth] deep: a verge, a fence and gate
     * on the road, a front garden of flowers, the cottage, and a vegetable patch behind, three rows at least,
     * then a row for the compost and the woodpile. The plot is levelled a block at most from the road; false if
     * the land won't allow it.
     */
    private fun cottage(road: CountryRoad, sign: Int, i: Int, frontage: Int, depth: Int, r: Random): Boolean {
        val plot = plotRect(road, sign, i, frontage, depth) ?: return false
        val y = plotHeight(road, i, frontage, plot) ?: return false
        val front = crossDir(road.side, -sign)
        val f = Frame(NullCanvas, plot, front)
        val house = min(6 + r.nextInt(3), depth - 8)
        val hedged = r.nextInt(3) == 0
        val door = (frontage - 1) / 2
        for (u in 0 until frontage) for (v in 0 until depth) {
            val x = f.x(u, v)
            val z = f.z(u, v)
            val edge = u == 0 || u == frontage - 1 || v == depth - 1
            when {
                v == 0 -> put(x, z, Use.HAMLET, Cell.GROUND, y, if (u == door) DIRT else GRASS)
                v == 1 && u == door -> put(x, z, Use.HAMLET, Cell.GATE, y, front.ordinal)
                v == 1 || edge -> put(x, z, Use.HAMLET, if (hedged) Cell.HEDGE else Cell.FENCE, y)
                v == 2 -> if (u == door) put(x, z, Use.HAMLET, Cell.GROUND, y, DIRT)
                    else put(x, z, Use.HAMLET, Cell.FLOWERS, y, if (noise(x, z) % 3 == 0) NO_FLOWER else noise(x, z) % 5)
                v < 3 + house -> put(x, z, Use.HAMLET, Cell.GROUND, y, FOOTING)
                v < depth - 2 && u in 2..frontage - 3 -> put(x, z, Use.HAMLET, Cell.CROP, y, CROPS_KITCHEN[(v + u / 3) % CROPS_KITCHEN.size])
                else -> put(x, z, Use.HAMLET, Cell.GROUND, y, if (v == depth - 2) GRASS else EARTH)
            }
        }
        buildings.add(FarmBuilding(FarmKind.COTTAGE, frameRect(f, 1, 3, frontage - 2, 2 + house), front, y, r.nextLong()))
        // A compost heap and a woodpile at the back, and now and then someone out tending the garden
        val back = depth - 2
        props.add(FarmProp(FarmPropKind.COMPOST, f.x(1, back), f.z(1, back), y, front, if (r.nextInt(3) == 0) 1 else 0))
        props.add(FarmProp(FarmPropKind.WOODPILE, f.x(frontage - 3, back), f.z(frontage - 3, back), y, f.turn(Dir.E)))
        return true
    }

    /** The plot [frontage] rows long from row [i] beside [road], [depth] deep on side [sign] of it from just past its edge, if it's all free. */
    private fun plotRect(road: CountryRoad, sign: Int, i: Int, frontage: Int, depth: Int): Rect? {
        if (i < 0 || i + frontage > road.rows) return null
        val edge = (i until i + frontage).maxOf { edge(road, it, sign) }
        val plot = sideRect(road.side, sign, edge + 1, edge + depth, first + i, first + i + frontage - 1)
        return if (free(plot)) plot else null
    }

    /** How far out across the side, on its [sign] half, [road]'s row [i] reaches. */
    private fun edge(road: CountryRoad, i: Int, sign: Int): Int =
        if (sign > 0) road.centres[i] + road.halves[i] else -(road.centres[i] - road.halves[i])

    /**
     * A height for a plot along [road] (rows [i] on for [frontage]): near the land, at most a block from the road,
     * and cut into the land or built up from it no more than [cut].
     */
    private fun plotHeight(road: CountryRoad, i: Int, frontage: Int, plot: Rect, cut: Int = MAX_PLOT_CUT): Int? {
        val (mid, stray) = lie(plot)
        val lo = (i until i + frontage).maxOf { road.heights[it] } - 1
        val hi = (i until i + frontage).minOf { road.heights[it] } + 1
        if (lo > hi) return null
        val y = mid.coerceIn(lo, hi)
        return if (stray + abs(y - mid) > cut) null else y
    }

    /**
     * The inn by the main road at the end of its hamlet, on whichever side the land suits: its forecourt, stable
     * and yard. Where the main road's too steep or broken for it all the way out, it goes further out, then in
     * among the hamlet's plots, then by another road.
     */
    private fun inn(r: Random) {
        val frontage = 21
        val depth = 18
        val signs = if (r.nextBoolean()) intArrayOf(1, -1) else intArrayOf(-1, 1)
        for (road in roads) for (i in (hamletRows - 4 until road.rows) + (hamletRows - 5 downTo 1)) for (sign in signs) {
            val plot = plotRect(road, sign, i, frontage, depth) ?: continue
            val y = plotHeight(road, i, frontage, plot, MAX_YARD_CUT) ?: continue
            val front = crossDir(road.side, -sign)
            val f = Frame(NullCanvas, plot, front)
            for (u in 0 until frontage) for (v in 0 until depth) {
                val x = f.x(u, v)
                val z = f.z(u, v)
                when {
                    v == depth - 1 || ((u == 0 || u == frontage - 1) && v >= 3) -> put(x, z, Use.HAMLET, Cell.HEDGE, y, noise(x, z) % 3 / 2)
                    v <= 1 -> put(x, z, Use.HAMLET, Cell.GROUND, y, if (noise(x, z) % 4 != 0) GRAVEL else EARTH)
                    v in 2..10 && u in 1..13 -> put(x, z, Use.HAMLET, Cell.GROUND, y, FOOTING)
                    v in 2..12 && u in 15..19 -> put(x, z, Use.HAMLET, Cell.GROUND, y, FOOTING)
                    else -> put(x, z, Use.HAMLET, Cell.GROUND, y, if (noise(x, z) % 3 == 0) GRASS else EARTH)
                }
            }
            bank(plot, y)
            buildings.add(FarmBuilding(FarmKind.INN, frameRect(f, 1, 2, 13, 10), front, y, r.nextLong()))
            buildings.add(FarmBuilding(FarmKind.STABLE, frameRect(f, 15, 2, 19, 12), front, y, r.nextLong()))
            // Lamps on the road; in the yard a well, a cart, a haystack and the woodpile
            for (u in intArrayOf(0, frontage - 1)) props.add(FarmProp(FarmPropKind.LAMP, f.x(u, 0), f.z(u, 0), y))
            props.add(FarmProp(FarmPropKind.WELL, f.x(5, 14), f.z(5, 14), y))
            props.add(FarmProp(FarmPropKind.CART, f.x(10, 14), f.z(10, 14), y, f.turn(Dir.W), 1))
            props.add(FarmProp(FarmPropKind.HAYSTACK, f.x(17, 15), f.z(17, 15), y, front))
            props.add(FarmProp(FarmPropKind.WOODPILE, f.x(2, 14), f.z(2, 14), y, f.turn(Dir.S)))
            return
        }
    }

    // ---- Windmills ----

    /** Windmills on the rises near the roads, their sails turned to the road. */
    private fun windmills(r: Random) {
        val wanted = when (plan.tier) {
            Tier.MEDIUM -> 1
            Tier.LARGE -> 2
            Tier.GRAND -> 2 + r.nextInt(2)
        }
        class Spot(val road: CountryRoad, val sign: Int, val i: Int, val gap: Int, val centre: CityPlan.Pos, val score: Double)
        val spots = ArrayList<Spot>()
        for (road in roads) for (i in hamletRows / 2 until road.rows - WINDMILL_HALF - 3 step 2) for (sign in intArrayOf(-1, 1)) {
            for (gap in 2..20 step 3) {
                val c = across(road.side, sign * (edge(road, i, sign) + 1 + gap + WINDMILL_HALF), first + i)
                val lot = Rect(c.x - WINDMILL_HALF, c.z - WINDMILL_HALF, c.x + WINDMILL_HALF, c.z + WINDMILL_HALF)
                if (!free(lot)) continue
                val (mid, stray) = lie(lot.grow(-2))
                if (stray > 2) continue
                // How far it stands over the land round about
                var sum = 0
                var count = 0
                for (dx in -18..18 step 4) for (dz in -18..18 step 4) {
                    if (abs(dx) < 10 && abs(dz) < 10) continue
                    if (!inRing(c.x + dx, c.z + dz) || wet(c.x + dx, c.z + dz)) continue
                    sum += land(c.x + dx, c.z + dz)
                    count++
                }
                if (count < 4) continue
                val rise = mid - sum.toDouble() / count
                spots.add(Spot(road, sign, i, gap, c, rise - stray * 0.5 - gap * 0.08 + r.nextDouble()))
            }
        }
        spots.sortByDescending { it.score }
        val placed = ArrayList<CityPlan.Pos>()
        for (s in spots) {
            if (placed.size >= wanted) break
            if (placed.any { abs(it.x - s.centre.x) + abs(it.z - s.centre.z) < WINDMILL_SPACING }) continue
            if (windmill(s.road, s.sign, s.i, s.gap, s.centre, r)) placed.add(s.centre)
        }
    }

    /** A windmill on a round levelled top at [c], [gap] off [road]'s row [i], a footpath out to the road. */
    private fun windmill(road: CountryRoad, sign: Int, i: Int, gap: Int, c: CityPlan.Pos, r: Random): Boolean {
        val lot = Rect(c.x - WINDMILL_HALF, c.z - WINDMILL_HALF, c.x + WINDMILL_HALF, c.z + WINDMILL_HALF)
        if (!free(lot)) return false
        val y = lie(Rect(c.x - 3, c.z - 3, c.x + 3, c.z + 3)).first
        val near = sign * (edge(road, i, sign) + 1)
        val path = gap > 0
        if (path && lane(road.side, first + i, near, near + sign * (gap - 1), 2, road.heights[i], y, Use.FARMYARD, false) == null) return false
        if (!path && abs(road.heights[i] - y) > 1) return false
        if (path) lane(road.side, first + i, near, near + sign * (gap - 1), 2, road.heights[i], y, Use.FARMYARD, true)
        for (x in lot.x0..lot.x1) for (z in lot.z0..lot.z1) {
            val d2 = (x - c.x) * (x - c.x) + (z - c.z) * (z - c.z)
            when {
                d2 <= 13 -> put(x, z, Use.FARMYARD, Cell.GROUND, y, if (d2 <= 9) FOOTING else EARTH)
                d2 <= 30 -> put(x, z, Use.FARMYARD, Cell.GROUND, y, if (noise(x, z) % 4 == 0) EARTH else GRASS)
                else -> put(x, z, Use.FARMYARD, Cell.BANK, (y + land(x, z) + 1) / 2)
            }
        }
        val front = crossDir(road.side, -sign)
        buildings.add(FarmBuilding(FarmKind.WINDMILL, lot, front, y, r.nextLong()))
        // A cart of sacks by the door, out past the round top where it must, so the ground under it is levelled too
        props.add(FarmProp(FarmPropKind.CART, c.x + front.dx * 3 + front.cw.dx * 4, c.z + front.dz * 3 + front.cw.dz * 4, y, front.opposite, 2))
        for (ahead in 2..5) for (aside in 2..6) {
            val x = c.x + front.dx * ahead + front.cw.dx * aside
            val z = c.z + front.dz * ahead + front.cw.dz * aside
            if (cell(x, z) == Cell.BANK) put(x, z, Use.FARMYARD, Cell.GROUND, y, if (noise(x, z) % 4 == 0) EARTH else GRASS)
        }
        return true
    }

    // ---- Farmsteads ----

    /**
     * Farmsteads down lanes off the roads past the hamlets, often two facing each other across the road at a
     * crossroads, on the most level ground to be found.
     */
    private fun farmsteads(r: Random) {
        val wanted = when (plan.tier) {
            Tier.MEDIUM -> 2 + r.nextInt(2)
            Tier.LARGE -> 4
            Tier.GRAND -> 5 + r.nextInt(2)
        }
        val big = plan.tier != Tier.MEDIUM
        val width = if (big) 26 else 23
        val depth = if (big) 21 else 19
        class Spot(val road: CountryRoad, val sign: Int, val row: Int, val outer: Boolean, val a: Int, val score: Double)
        val spots = ArrayList<Spot>()
        for (road in roads) for (sign in intArrayOf(-1, 1)) for (row in hamletRows + 3 until road.rows - 3) {
            // The lane leaves the road where the road runs level
            if (road.heights[row - 1] != road.heights[row] || road.heights[row + 1] != road.heights[row]) continue
            for (outer in booleanArrayOf(true, false)) {
                val n0 = if (outer) row + 2 else row - 1 - depth
                if (n0 < 1 || n0 + depth > road.rows - 1) continue
                val edge = (row - 1..row + 1).maxOf { edge(road, it, sign) }
                var a = edge + 2
                while (a <= edge + MAX_LANE) {
                    val rect = sideRect(road.side, sign, a, a + width - 1, first + n0, first + n0 + depth - 1)
                    if (inSquare(rect.x0, rect.z0) && inSquare(rect.x1, rect.z1) && free(rect)) {
                        val (_, stray) = lie(rect)
                        if (stray <= MAX_YARD_CUT) spots.add(Spot(road, sign, row, outer, a, -roughness(rect) - stray * 0.3 - a * 0.02 + r.nextDouble() * 0.5))
                    }
                    a += 4
                }
            }
        }
        var placed = 0
        val lanes = HashSet<Pair<CountryRoad, Int>>()
        while (placed < wanted && spots.isNotEmpty()) {
            // Facing an earlier farmstead across the road, down the same lane, is better still
            val pick = spots.maxBy { it.score + if (Pair(it.road, it.row) in lanes) 1.5 else 0.0 }
            spots.remove(pick)
            if (farmstead(pick.road, pick.sign, pick.row, pick.outer, pick.a, width, depth, Random(r.nextLong()))) {
                placed++
                lanes.add(Pair(pick.road, pick.row))
            }
        }
    }

    /** How uneven the land is under [r]: how far it strays from its middle, on average. */
    private fun roughness(r: Rect): Double {
        val mid = lie(r).first
        var sum = 0
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) sum += abs(land(x, z) - mid)
        return sum.toDouble() / (r.width * r.depth)
    }

    /**
     * A farmstead beside the lane along row [row] off [road] (on its [sign] side, past the lane if [outer]),
     * [width] across from [a0] and [depth] deep: the farmhouse and the great barn facing each other across the
     * yard, the gate onto the lane between them, a kitchen garden, and a pen with its coop behind the house; in
     * the yard a well and a cart, and haystacks before and behind the barn.
     */
    private fun farmstead(road: CountryRoad, sign: Int, row: Int, outer: Boolean, a0: Int, width: Int, depth: Int, r: Random): Boolean {
        val n0 = if (outer) row + 2 else row - 1 - depth
        val rect = sideRect(road.side, sign, a0, a0 + width - 1, first + n0, first + n0 + depth - 1)
        if (!free(rect)) return false
        val y = lie(rect).first
        val front = if (outer) road.side.opposite else road.side
        val f = Frame(NullCanvas, rect, front)
        // The farmhouse usually stands nearer the road, the barn beyond the yard
        val uOut = alongOf(road.side, f.x(width - 1, 0), f.z(width - 1, 0)) * sign > alongOf(road.side, f.x(0, 0), f.z(0, 0)) * sign
        val mirror = uOut == (r.nextInt(4) == 0)
        fun mu(u: Int) = if (mirror) width - 1 - u else u
        val big = width >= 26
        val houseDepth = if (big) 8 else 7
        val houseWidth = if (big) 9 else 7
        val barnDepth = if (big) 9 else 7
        val barnLength = if (big) 15 else 13
        val yard = width - 2 - houseDepth - barnDepth
        val gate = 1 + houseDepth + (yard - 3) / 2
        val penTop = houseWidth + 5
        // The lane from the road to the gate
        val gateA = alongOf(road.side, f.x(mu(gate + 1), 0), f.z(mu(gate + 1), 0))
        val edge = (row - 1..row + 1).maxOf { edge(road, it, sign) }
        val laneFrom = sign * (edge + 1)
        val laneTo = gateA + sign
        if (lane(road.side, first + row, laneFrom, laneTo, 3, road.heights[row], y, Use.ROAD, false) == null) return false
        lane(road.side, first + row, laneFrom, laneTo, 3, road.heights[row], y, Use.ROAD, true)

        val hedged = r.nextInt(3) == 0
        for (u0 in 0 until width) for (v in 0 until depth) {
            val u = mu(u0)
            val x = f.x(u, v)
            val z = f.z(u, v)
            val boundary = u0 == 0 || u0 == width - 1 || v == 0 || v == depth - 1
            when {
                v == 0 && u0 in gate..gate + 2 -> put(x, z, Use.FARMYARD, Cell.GROUND, y, if (u0 == gate + 1) GRASS else DIRT)
                boundary -> put(x, z, Use.FARMYARD, if (hedged && v > 0) Cell.HEDGE else Cell.FENCE, y)
                // The farmhouse with flowers along its end, its kitchen garden behind, the pen behind that
                u0 in 1..houseDepth && v in 2..1 + houseWidth -> put(x, z, Use.FARMYARD, Cell.GROUND, y, FOOTING)
                u0 in 1..houseDepth && v == 1 -> put(x, z, Use.FARMYARD, Cell.FLOWERS, y, noise(x, z) % 5)
                u0 in 2..houseDepth - 1 && v in houseWidth + 3..houseWidth + 4 ->
                    put(x, z, Use.FARMYARD, Cell.CROP, y, CROPS_KITCHEN[(u0 / 2 + v) % CROPS_KITCHEN.size])
                u0 in 1..houseDepth + 1 && v == penTop -> put(x, z, Use.FARMYARD, if (u0 == houseDepth) Cell.GATE else Cell.FENCE, y, front.ordinal)
                u0 == houseDepth + 1 && v > penTop -> put(x, z, Use.FARMYARD, Cell.FENCE, y)
                u0 in 1..houseDepth && v > penTop -> put(x, z, Use.FARMYARD, Cell.GROUND, y, if (noise(x, z) % 3 == 0) GRASS else EARTH)
                // The barn across the yard
                u0 in width - 1 - barnDepth..width - 2 && v in 3 until 3 + barnLength -> put(x, z, Use.FARMYARD, Cell.GROUND, y, FOOTING)
                else -> {
                    val worn = u0 in gate..gate + 2 && v < depth / 2
                    put(x, z, Use.FARMYARD, Cell.GROUND, y, if (worn) DIRT else if (noise(x, z) % 5 < 2) GRASS else EARTH)
                }
            }
        }
        bank(rect, y)

        val east = f.turn(if (mirror) Dir.W else Dir.E)
        buildings.add(FarmBuilding(FarmKind.FARMHOUSE, frameRect(f, mu(1), 2, mu(houseDepth), 1 + houseWidth), east, y, r.nextLong()))
        buildings.add(FarmBuilding(FarmKind.BARN, frameRect(f, mu(width - 1 - barnDepth), 3, mu(width - 2), 2 + barnLength), east.opposite, y, r.nextLong()))
        // The coop in the pen's far corner, hens before it and pigs or sheep in the rest
        buildings.add(FarmBuilding(FarmKind.COOP, frameRect(f, mu(1), depth - 4, mu(3), depth - 2), east, y, r.nextLong()))
        // Pigs or sheep by the pen's gate; the hens out before the coop's door, clear of its eaves
        val penned = if (r.nextBoolean()) Spawn.PIG else Spawn.SHEEP
        for (k in 0 until 2 + r.nextInt(2)) {
            val u = mu(6 + k % 2)
            val v = penTop + 1 + k / 2
            beasts.add(Beast(penned, f.x(u, v), y, f.z(u, v), Dir.entries[r.nextInt(4)]))
        }
        for (k in 0 until 3) beasts.add(Beast(Spawn.CHICKEN, f.x(mu(5), depth - 4 + k), y, f.z(mu(5), depth - 4 + k), Dir.entries[r.nextInt(4)]))
        // The yard: a well, a hay cart, haystacks before and behind the barn, lamps at the gate, the farmer at the compost
        val mid = houseDepth + 1 + yard / 2
        props.add(FarmProp(FarmPropKind.WELL, f.x(mu(mid), depth / 2 - 1), f.z(mu(mid), depth / 2 - 1), y))
        props.add(FarmProp(FarmPropKind.CART, f.x(mu(mid), depth - 4), f.z(mu(mid), depth - 4), y, f.turn(Dir.N), 1))
        // Before the barn's end and behind it, to one side of the torches on its ends; the one behind faces the back fence
        val hay = mu(width - 4 - barnDepth / 2)
        props.add(FarmProp(FarmPropKind.HAYSTACK, f.x(hay, 1), f.z(hay, 1), y, front, r.nextInt(2)))
        props.add(FarmProp(FarmPropKind.HAYSTACK, f.x(hay, depth - 2), f.z(hay, depth - 2), y, front.opposite, r.nextInt(2)))
        for (u0 in intArrayOf(gate - 1, gate + 3)) props.add(FarmProp(FarmPropKind.LAMP, f.x(mu(u0), 0), f.z(mu(u0), 0), y))
        props.add(FarmProp(FarmPropKind.COMPOST, f.x(mu(houseDepth + 1), 2 + houseWidth), f.z(mu(houseDepth + 1), 2 + houseWidth), y, east, 1))
        props.add(FarmProp(FarmPropKind.WOODPILE, f.x(mu(houseDepth + 1), 2), f.z(mu(houseDepth + 1), 2), y, f.turn(Dir.S)))
        return true
    }

    /** Banks the land round [rect] (two columns out, where free) toward its standing height [y]. */
    private fun bank(rect: Rect, y: Int) {
        for (x in rect.x0 - 2..rect.x1 + 2) for (z in rect.z0 - 2..rect.z1 + 2) {
            if (rect.contains(x, z) || !free(x, z)) continue
            val out = max(max(rect.x0 - x, x - rect.x1), max(rect.z0 - z, z - rect.z1))
            val h = land(x, z)
            if (abs(h - y) < 2) continue
            put(x, z, Use.FARMYARD, Cell.BANK, y + (h - y) * out / 3)
        }
    }

    // ---- The watermill ----

    /** A watermill on the bank beside a bridge, its wheel in the stream, its door on the road. */
    private fun watermill(r: Random) {
        for (road in roads) for ((i0, i1) in wetRuns(road)) {
            for (sign in if (r.nextBoolean()) intArrayOf(1, -1) else intArrayOf(-1, 1)) {
                if (mill(road, sign, i0 - MILL_LENGTH, i0, 1, r) || mill(road, sign, i1 + 1, i1, -1, r)) return
            }
        }
    }

    /**
     * The mill house on [road]'s [sign] side from row [i] for [MILL_LENGTH] rows, beside the stream at row
     * [stream] (out from it if [toward] is 1, in from it if -1), with room in the water for the wheel.
     */
    private fun mill(road: CountryRoad, sign: Int, i: Int, stream: Int, toward: Int, r: Random): Boolean {
        if (i < 1 || i + MILL_LENGTH > road.rows) return false
        val edge = (i until i + MILL_LENGTH).maxOf { edge(road, it, sign) }
        val a0 = edge + 2
        val rect = sideRect(road.side, sign, a0, a0 + MILL_DEPTH - 1, first + i, first + i + MILL_LENGTH - 1)
        if (!free(rect)) return false
        val (y, stray) = lie(rect)
        if (stray > 3 || abs(y - road.heights[if (toward > 0) i + MILL_LENGTH - 1 else i]) > 2) return false
        // The wheel turns in the water just past the mill's end, its axle out from the end wall
        val mid = a0 + MILL_DEPTH / 2
        var water = Int.MIN_VALUE
        for (a in mid - 3..mid + 3) for (dn in 0..1) {
            val p = across(road.side, sign * a, first + stream + dn * toward)
            if (!openWater(p.x, p.z)) return false
            water = max(water, land(p.x, p.z))
        }
        for (x in rect.x0..rect.x1) for (z in rect.z0..rect.z1) put(x, z, Use.FARMYARD, Cell.GROUND, y, FOOTING)
        // Its forecourt on the road
        for (j in i until i + MILL_LENGTH) {
            val p = across(road.side, sign * (edge + 1), first + j)
            if (free(p.x, p.z)) put(p.x, p.z, Use.FARMYARD, Cell.GROUND, y, EARTH)
        }
        val wheelSide = if (toward > 0) road.side else road.side.opposite
        buildings.add(FarmBuilding(FarmKind.MILL, rect, crossDir(road.side, -sign), y, r.nextLong(), wheelSide.ordinal))
        val hub = across(road.side, sign * mid, first + stream)
        props.add(FarmProp(FarmPropKind.WHEEL, hub.x, hub.z, water + 1, wheelSide, y - water))
        return true
    }

    // ---- Waystones ----

    /** A waystone beside each road where it leaves the farms. */
    private fun waystones(r: Random) {
        for (road in roads) {
            val i = road.rows - 3
            val sign = if (r.nextBoolean()) 1 else -1
            val p = road.at(i, sign * (road.halves[i] + 1))
            if (!free(p.x, p.z) || abs(land(p.x, p.z) - road.heights[i]) > 2) continue
            put(p.x, p.z, Use.ROAD, Cell.GROUND, road.heights[i], GRASS)
            props.add(FarmProp(FarmPropKind.WAYSTONE, p.x, p.z, road.heights[i], crossDir(road.side, -sign)))
        }
    }

    // ---- Trees by the roads ----

    /**
     * Trees along the roads past the hamlets, a few rows apart and now one side, now the other: broad oaks most of
     * all and now and then a fir, or down the main road as often as not an avenue of tall poplars, a pair to a
     * row. Each stands on the land's own ground a verge's width off the road, where nothing else is.
     */
    private fun roadsideTrees(r: Random) {
        for (road in roads) {
            val avenue = road.main && r.nextBoolean()
            var i = hamletRows + 6 + r.nextInt(4)
            while (i < road.rows - 4) {
                if (avenue) {
                    for (sign in intArrayOf(-1, 1)) roadTree(road, i, sign, 1)
                    i += AVENUE_STEP
                } else {
                    val variant = if (r.nextInt(6) == 0) 2 else 0
                    i += if (roadTree(road, i, if (r.nextBoolean()) 1 else -1, variant)) 6 + r.nextInt(8) else 2
                }
            }
        }
    }

    /**
     * A tree of [variant] beside [road]'s row [i] on its [sign] side, if the ground there's free and even and no
     * higher than the road but a little (a fir's lowest boughs, over the road's edge, hang lower); false if not.
     */
    private fun roadTree(road: CountryRoad, i: Int, sign: Int, variant: Int): Boolean {
        val p = road.at(i, sign * (road.halves[i] + 3))
        val spot = Rect(p.x - 1, p.z - 1, p.x + 1, p.z + 1)
        val y = land(p.x, p.z)
        val rise = if (variant == 2) 0 else 1
        if (!free(spot) || !even(p.x, p.z) || road.heights[i] > y + rise || y - road.heights[i] > 2 || !crownClear(p.x, p.z, rise)) return false
        val bounds = spot.grow(FarmPropKind.TREE.reach - 1)
        if (props.any { it.bounds.intersects(bounds) } || buildings.any { it.bounds.intersects(bounds) }) return false
        for (x in spot.x0..spot.x1) for (z in spot.z0..spot.z1) plan.grid[x, z] = Use.VERGE
        props.add(FarmProp(FarmPropKind.TREE, p.x, p.z, y, Dir.entries[noise(p.x, p.z) % 4], variant))
        return true
    }

    // ---- Fields, pastures and orchards ----

    /**
     * Whatever's left is cut into parcels, a balk's width apart, and each becomes a field, a pasture or an
     * orchard as its ground allows, or is left wild.
     */
    private fun parcels(r: Random) {
        val l = plan.levelled
        val bands = listOf(
            Rect(-last, -last, l, -first - 1),
            Rect(first + 1, -last, last, l),
            Rect(-l, first + 1, last, last),
            Rect(-last, -l, -first - 1, last),
        )
        val leaves = ArrayList<Rect>()
        for (band in bands) split(band, r, leaves)
        for (leaf in leaves) parcel(leaf, Random(r.nextLong()))
    }

    private fun split(rc: Rect, r: Random, out: MutableList<Rect>) {
        val alongX = rc.width >= rc.depth
        val long = if (alongX) rc.width else rc.depth
        if (rc.width <= MAX_PARCEL && rc.depth <= MAX_PARCEL || long < MIN_PARCEL * 2 + 2) {
            out.add(rc)
            return
        }
        val at = MIN_PARCEL + r.nextInt(long - MIN_PARCEL * 2 - 1)
        val gap = if (r.nextInt(6) == 0) 2 else 1
        if (alongX) {
            split(Rect(rc.x0, rc.z0, rc.x0 + at - 1, rc.z1), r, out)
            split(Rect(rc.x0 + at + gap, rc.z0, rc.x1, rc.z1), r, out)
        } else {
            split(Rect(rc.x0, rc.z0, rc.x1, rc.z0 + at - 1), r, out)
            split(Rect(rc.x0, rc.z0 + at + gap, rc.x1, rc.z1), r, out)
        }
    }

    private fun parcel(rect: Rect, r: Random) {
        val mask = BooleanArray(rect.width * rect.depth)
        var count = 0
        for (x in rect.x0..rect.x1) for (z in rect.z0..rect.z1) if (free(x, z)) {
            mask[(x - rect.x0) * rect.depth + (z - rect.z0)] = true
            count++
        }
        if (count < MIN_AREA) return
        val share = count.toDouble() / (rect.width * rect.depth)
        val roll = r.nextInt(100)
        if (roll < FIELD_SHARE && share > 0.6 && field(rect, mask, r)) return
        // Too steep or broken for a field (or never meant for one)
        if (cliffs(rect, mask) > MAX_CLIFFS) return
        when {
            roll < FIELD_SHARE -> if (r.nextInt(3) == 0) orchard(rect, mask, r) else pasture(rect, mask, r)
            roll < FIELD_SHARE + PASTURE_SHARE -> pasture(rect, mask, r)
            roll < FIELD_SHARE + PASTURE_SHARE + ORCHARD_SHARE -> orchard(rect, mask, r)
        }
    }

    /** How much of a parcel's ground breaks off in steps of two or more. */
    private fun cliffs(rect: Rect, mask: BooleanArray): Double {
        var steep = 0
        var all = 0
        for (x in rect.x0 until rect.x1) for (z in rect.z0 until rect.z1) {
            if (!mask[(x - rect.x0) * rect.depth + (z - rect.z0)]) continue
            all++
            if (abs(land(x + 1, z) - land(x, z)) >= 2 || abs(land(x, z + 1) - land(x, z)) >= 2) steep++
        }
        return if (all == 0) 1.0 else steep.toDouble() / all
    }

    /**
     * A field in long strips the way the land lies most level: beds of crops in patches, an irrigation ditch
     * between each bed and the next, a hedge or a fence round it with a gate onto the lane, and a scarecrow or
     * two. Where the land slopes the beds step down a block at a time, the higher edged in stone above the
     * ditch; where it's too steep for that, false.
     */
    private fun field(rect: Rect, mask: BooleanArray, r: Random): Boolean {
        val w = rect.width
        val d = rect.depth
        fun inField(i: Int, j: Int) = i in 0 until w && j in 0 until d && mask[i * d + j]
        fun height(i: Int, j: Int) = land(rect.x0 + i, rect.z0 + j)
        // Lines run along x, one for each z, or the other way: whichever keeps them nearer level
        fun lineCost(alongX: Boolean): Double {
            var cost = 0.0
            val count = if (alongX) d else w
            val length = if (alongX) w else d
            for (j in 0 until count) {
                var sum = 0
                var n = 0
                for (i in 0 until length) {
                    val (ci, cj) = if (alongX) Pair(i, j) else Pair(j, i)
                    if (inField(ci, cj)) {
                        sum += height(ci, cj)
                        n++
                    }
                }
                if (n == 0) continue
                val mean = sum.toDouble() / n
                for (i in 0 until length) {
                    val (ci, cj) = if (alongX) Pair(i, j) else Pair(j, i)
                    if (inField(ci, cj)) cost += abs(height(ci, cj) - mean)
                }
            }
            return cost
        }
        val costX = lineCost(true)
        val costZ = lineCost(false)
        val alongX = if (abs(costX - costZ) <= 0.15 * min(costX, costZ) + 2) r.nextBoolean() else costX < costZ
        val lines = if (alongX) d else w
        val length = if (alongX) w else d
        fun col(line: Int, pos: Int): Pair<Int, Int> = if (alongX) Pair(pos, line) else Pair(line, pos)

        // How steeply the land climbs across the lines sets how wide the beds can be between steps
        val means = DoubleArray(lines) { Double.NaN }
        for (j in 0 until lines) {
            var sum = 0
            var n = 0
            for (i in 0 until length) {
                val (ci, cj) = col(j, i)
                if (inField(ci, cj)) {
                    sum += height(ci, cj)
                    n++
                }
            }
            if (n > 0) means[j] = sum.toDouble() / n
        }
        val slope = slopeOf(means)
        val bed = if (slope < 1.0 / (BED_WIDTH + 1)) BED_WIDTH else ((1.0 / slope).toInt() - 1).coerceIn(3, BED_WIDTH)
        val role = IntArray(lines)
        var beds = 0
        var run = 0
        for (j in 0 until lines) {
            if (j > 0 && j < lines - 1 && run >= bed && lines - 1 - j >= 4) {
                role[j] = DITCH_LINE
                beds++
                run = 0
                continue
            }
            role[j] = beds
            if (j > 0) run++
        }
        beds++
        val sums = IntArray(beds)
        val counts = IntArray(beds)
        for (j in 0 until lines) {
            if (role[j] < 0) continue
            for (i in 0 until length) {
                val (ci, cj) = col(j, i)
                if (inField(ci, cj)) {
                    sums[role[j]] += height(ci, cj)
                    counts[role[j]]++
                }
            }
        }
        val bedY = smooth(IntArray(beds) { b -> if (counts[b] == 0) 0 else (sums[b].toDouble() / counts[b]).roundToInt() })
        val lineY = IntArray(lines) { j -> if (role[j] >= 0) bedY[role[j]] else min(bedY[role[j - 1]], bedY[role[j + 1]]) }
        val kerb = BooleanArray(lines)
        for (j in 0 until lines) if (role[j] == DITCH_LINE) {
            if (lineY[j - 1] > lineY[j + 1]) kerb[j - 1] = true
            if (lineY[j + 1] > lineY[j - 1]) kerb[j + 1] = true
        }
        // Too much cutting and filling and it's no field; a little too much here and there and that bit's left out
        var bad = 0
        var n = 0
        for (j in 0 until lines) for (i in 0 until length) {
            val (ci, cj) = col(j, i)
            if (!inField(ci, cj)) continue
            n++
            val dev = abs(lineY[j] - height(ci, cj))
            if (dev > 2) bad++
            if (dev > MAX_FIELD_CUT) mask[ci * d + cj] = false
        }
        if (n - bad < MIN_AREA || bad > n / 10) {
            for (ci in 0 until w) for (cj in 0 until d) mask[ci * d + cj] = free(rect.x0 + ci, rect.z0 + cj)
            return false
        }
        fields++

        // Crops in patches along each bed, the field's main crop and another by turns, some not yet ripe
        val main = CROPS_FIELD[r.nextInt(CROPS_FIELD.size)]
        var second = CROPS_FIELD[r.nextInt(CROPS_FIELD.size)]
        if (second == main) second = (main + 1 + r.nextInt(3)) % 4
        val single = r.nextInt(4) == 0
        val patches = Array(beds) { IntArray(length) }
        for (b in 0 until beds) {
            var i = 0
            var turn = (b + r.nextInt(2)) % 2
            while (i < length) {
                val patch = 6 + r.nextInt(14)
                val kind = if (single || turn == 0) main else second
                val roll = r.nextInt(100)
                val young = when {
                    roll < 52 -> 0
                    roll < 72 -> 1
                    roll < 86 -> 2
                    roll < 95 -> 3
                    else -> -1
                }
                val v = if (young < 0) BARE else kind + young * 4
                for (k in i until min(length, i + patch)) patches[b][k] = v
                i += patch
                turn = 1 - turn
            }
        }
        val hedged = r.nextInt(10) < 7
        val gates = ArrayList<IntArray>()
        for (j in 0 until lines) for (i in 0 until length) {
            val (ci, cj) = col(j, i)
            if (!inField(ci, cj)) continue
            val x = rect.x0 + ci
            val z = rect.z0 + cj
            val y = lineY[j]
            var outside: Dir? = null
            for (dir in Dir.entries) if (!inField(ci + dir.dx, cj + dir.dz)) outside = dir
            when {
                outside != null -> {
                    put(x, z, Use.FIELD, if (hedged) Cell.HEDGE else Cell.FENCE, y, if (noise(x, z) % 5 == 0) 1 else 0)
                    val beyond = level(x + outside.dx, z + outside.dz)
                    if (cell(x + outside.dx, z + outside.dz) == Cell.GROUND && role[j] >= 0 && beyond != null && abs(beyond - y) <= 1) {
                        gates.add(intArrayOf(x, z, outside.ordinal))
                    }
                }
                role[j] == DITCH_LINE -> put(x, z, Use.FIELD, Cell.DITCH, y)
                kerb[j] -> put(x, z, Use.FIELD, Cell.KERB, y)
                else -> put(x, z, Use.FIELD, Cell.CROP, y, patches[role[j]][i])
            }
        }
        if (gates.isNotEmpty()) {
            val g = gates[gates.size / 2]
            put(g[0], g[1], Use.FIELD, Cell.GATE, heights[index(g[0], g[1])].toInt(), g[2])
        }
        // A scarecrow or two over the crops
        val scarecrows = if (n >= 420) 2 else if (n >= 160 && r.nextInt(3) != 0) 1 else 0
        for (s in 0 until scarecrows) for (tries in 0 until 20) {
            val j = 2 + r.nextInt(max(1, lines - 4))
            val i = 2 + r.nextInt(max(1, length - 4))
            if (j >= lines - 2 || i >= length - 2) continue
            val (ci, cj) = col(j, i)
            val x = rect.x0 + ci
            val z = rect.z0 + cj
            if ((-1..1).any { dx -> (-1..1).any { dz -> cell(x + dx, z + dz) != Cell.CROP } }) continue
            put(x, z, Use.FIELD, Cell.GROUND, lineY[j], EARTH)
            props.add(FarmProp(FarmPropKind.SCARECROW, x, z, lineY[j], Dir.entries[r.nextInt(4)]))
            break
        }
        return true
    }

    /** How steeply a run of line heights climbs, in blocks a line, by least squares. */
    private fun slopeOf(means: DoubleArray): Double {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (j in means.indices) {
            if (means[j].isNaN()) continue
            n++
            sx += j
            sy += means[j]
            sxx += j.toDouble() * j
            sxy += j * means[j]
        }
        if (n < 3) return 0.0
        val den = n * sxx - sx * sx
        return if (den == 0.0) 0.0 else abs((n * sxy - sx * sy) / den)
    }

    /**
     * A pasture on the land's own ground: a fence round it following the ground, a gate onto a road or lane if
     * one runs by, a field shelter if it's big, a water trough and an oak or two for shade, each well clear of
     * the fence and the shelter, and cattle, sheep or horses out at grass between them.
     */
    private fun pasture(rect: Rect, mask: BooleanArray, r: Random) {
        val d = rect.depth
        fun inside(x: Int, z: Int) = rect.contains(x, z) && mask[(x - rect.x0) * d + (z - rect.z0)]
        pastures++
        val inner = ArrayList<CityPlan.Pos>()
        val gates = ArrayList<IntArray>()
        for (x in rect.x0..rect.x1) for (z in rect.z0..rect.z1) {
            if (!inside(x, z)) continue
            var outside: Dir? = null
            for (dir in Dir.entries) if (!inside(x + dir.dx, z + dir.dz)) outside = dir
            if (outside == null) {
                plan.grid[x, z] = Use.PASTURE
                inner.add(CityPlan.Pos(x, z))
                continue
            }
            put(x, z, Use.PASTURE, Cell.RAIL, land(x, z), if (rise(x, z, ::inside) >= 2) 1 else 0)
            val beyond = level(x + outside.dx, z + outside.dz)
            if (cell(x + outside.dx, z + outside.dz) == Cell.GROUND && beyond != null && abs(beyond - land(x, z)) <= 1) {
                gates.add(intArrayOf(x, z, outside.ordinal))
            }
        }
        if (gates.isNotEmpty()) {
            val g = gates[gates.size / 2]
            put(g[0], g[1], Use.PASTURE, Cell.RAIL_GATE, land(g[0], g[1]), g[2])
        }
        if (inner.isEmpty()) return
        val roll = r.nextInt(100)
        val kind = when {
            roll < 42 -> Spawn.COW
            roll < 84 -> Spawn.SHEEP
            roll < 92 -> Spawn.HORSE
            else -> Spawn.PIG
        }
        // Nothing but grass for [by] round a spot, on the pasture's own ground
        fun open(p: CityPlan.Pos, by: Int) = (-by..by).all { dx -> (-by..by).all { dz -> inside(p.x + dx, p.z + dz) && cell(p.x + dx, p.z + dz) == Cell.NONE } }
        val taken = ArrayList<Rect>()
        if (inner.size >= 260 && kind != Spawn.PIG) shelter(rect, ::inside, r)?.let { taken.add(it.grow(FarmKind.SHELTER.reach)) }
        val troughs = inner.filter { open(it, 2) && settled(it.x, it.z) }
        if (troughs.isNotEmpty()) {
            val t = troughs[r.nextInt(troughs.size)]
            props.add(FarmProp(FarmPropKind.TROUGH, t.x, t.z, land(t.x, t.z), if (r.nextBoolean()) Dir.N else Dir.E))
            taken.add(Rect(t.x - 1, t.z - 1, t.x + 1, t.z + 1))
        }
        for (k in 0 until if (kind == Spawn.PIG) 0 else min(2, inner.size / SHADE_AREA)) {
            val spots = inner.filter { p -> open(p, 3) && taken.none { it.grow(4).contains(p.x, p.z) } && even(p.x, p.z) && crownClear(p.x, p.z, 1) }
            if (spots.isEmpty()) break
            val t = spots[r.nextInt(spots.size)]
            props.add(FarmProp(FarmPropKind.TREE, t.x, t.z, land(t.x, t.z), Dir.entries[r.nextInt(4)], 0))
            taken.add(Rect(t.x - 1, t.z - 1, t.x + 1, t.z + 1))
        }
        val grazing = inner.filter { p -> open(p, 1) && taken.none { it.contains(p.x, p.z) } }
        if (grazing.isEmpty()) return
        for (k in 0 until (inner.size / 110).coerceIn(2, 5)) {
            val p = grazing[r.nextInt(grazing.size)]
            beasts.add(Beast(kind, p.x, land(p.x, p.z), p.z, Dir.entries[r.nextInt(4)]))
        }
    }

    /** Whether the ground round a column is level with it or a block lower, so a trough stands on it without sinking in. */
    private fun settled(x: Int, z: Int): Boolean {
        val y = land(x, z)
        return (-1..1).all { dx -> (-1..1).all { dz -> land(x + dx, z + dz) in y - 1..y } }
    }

    /**
     * Whether nothing under a tree's crown at [x], [z] (three each way) stands more than [rise] over the foot of
     * its trunk, the ground or anything laid on it, so the lowest leaves hang clear.
     */
    private fun crownClear(x: Int, z: Int, rise: Int): Boolean {
        val y = land(x, z)
        return (-3..3).all { dx -> (-3..3).all { dz -> !inSquare(x + dx, z + dz) || (level(x + dx, z + dz) ?: land(x + dx, z + dz)) <= y + rise } }
    }

    /** Whether the ground round a column is within a block of it, for a tree to stand on. */
    private fun even(x: Int, z: Int): Boolean {
        val y = land(x, z)
        return (-1..1).all { dx -> (-1..1).all { dz -> abs(land(x + dx, z + dz) - y) <= 1 } }
    }

    /** How far the ground climbs from a fence post to its highest neighbour inside, so the post can stand taller there. */
    private fun rise(x: Int, z: Int, inside: (Int, Int) -> Boolean): Int {
        var high = land(x, z)
        for (dir in Dir.entries) if (inside(x + dir.dx, z + dir.dz)) high = max(high, land(x + dir.dx, z + dir.dz))
        return high - land(x, z)
    }

    /** An open-fronted field shelter on a little levelled spot well inside a pasture; its lot, or null if there's no room. */
    private fun shelter(rect: Rect, inside: (Int, Int) -> Boolean, r: Random): Rect? {
        for (tries in 0 until 12) {
            val x0 = rect.x0 + 2 + r.nextInt(max(1, rect.width - 8))
            val z0 = rect.z0 + 2 + r.nextInt(max(1, rect.depth - 8))
            val lot = Rect(x0, z0, x0 + 4, z0 + 3)
            val around = lot.grow(1)
            if ((around.x0..around.x1).any { x -> (around.z0..around.z1).any { z -> !inside(x, z) || plan.grid[x, z] != Use.PASTURE } }) continue
            val (y, stray) = lie(lot)
            if (stray > 1) continue
            for (x in lot.x0..lot.x1) for (z in lot.z0..lot.z1) put(x, z, Use.FARMYARD, Cell.GROUND, y, EARTH)
            buildings.add(FarmBuilding(FarmKind.SHELTER, lot, Dir.entries[r.nextInt(4)], y, r.nextLong()))
            return lot
        }
        return null
    }

    /** An orchard of fruit trees in rows on the land's own ground, fenced or open, now and then with sheep under the trees. */
    private fun orchard(rect: Rect, mask: BooleanArray, r: Random) {
        val d = rect.depth
        fun inside(x: Int, z: Int) = rect.contains(x, z) && mask[(x - rect.x0) * d + (z - rect.z0)]
        orchards++
        val fenced = r.nextInt(10) < 6
        val spacing = 4 + r.nextInt(2)
        val ox = r.nextInt(spacing)
        val oz = r.nextInt(spacing)
        val blossom = r.nextInt(3) == 0
        for (x in rect.x0..rect.x1) for (z in rect.z0..rect.z1) {
            if (!inside(x, z)) continue
            val edge = Dir.entries.any { !inside(x + it.dx, z + it.dz) }
            if (edge && fenced) put(x, z, Use.ORCHARD, Cell.RAIL, land(x, z), if (rise(x, z, ::inside) >= 2) 1 else 0)
            else plan.grid[x, z] = Use.ORCHARD
        }
        val trees = ArrayList<CityPlan.Pos>()
        for (x in rect.x0..rect.x1) for (z in rect.z0..rect.z1) {
            if (Math.floorMod(x - rect.x0 - ox, spacing) != 0 || Math.floorMod(z - rect.z0 - oz, spacing) != 0) continue
            // Clear of the edge by a crown's width, on ground level enough to stand a tree, and no rail under the
            // crown standing high enough to reach into it
            if ((-2..2).any { dx -> (-2..2).any { dz -> !inside(x + dx, z + dz) } }) continue
            if ((-2..2).any { dx -> (-2..2).any { dz -> cell(x + dx, z + dz) == Cell.RAIL && land(x + dx, z + dz) > land(x, z) } }) continue
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (dx in -1..1) for (dz in -1..1) {
                lo = min(lo, land(x + dx, z + dz))
                hi = max(hi, land(x + dx, z + dz))
            }
            if (hi - lo > 1) continue
            val variant = if (blossom && r.nextInt(3) != 0) 1 else r.nextInt(3) * 2
            props.add(FarmProp(FarmPropKind.FRUIT_TREE, x, z, land(x, z), Dir.entries[r.nextInt(4)], variant))
            trees.add(CityPlan.Pos(x, z))
        }
        // Sheep in the gaps between the trees, where no bough hangs
        if (trees.size >= 4 && r.nextInt(3) == 0) for (k in 0 until 3) {
            val p = trees[r.nextInt(trees.size)]
            val bx = p.x + if (r.nextBoolean()) 2 else -2
            val bz = p.z + if (r.nextBoolean()) 2 else -2
            if (inside(bx, bz) && cell(bx, bz) == Cell.NONE && abs(land(bx, bz) - land(p.x, p.z)) <= 1) {
                beasts.add(Beast(Spawn.SHEEP, bx, land(bx, bz), bz, Dir.entries[r.nextInt(4)]))
            }
        }
    }

    // ---- Drawing ----

    private fun column(canvas: Canvas, x: Int, z: Int, cell: Cell, y: Int, detail: Int) {
        when (cell) {
            Cell.NONE -> {}
            Cell.GROUND -> canvas.ground(x, z, y, surface(detail), if (detail == FOOTING) FOUNDATION else FILL)
            Cell.BRIDGE -> bridgeColumn(canvas, x, z, y, detail)
            Cell.CROP -> {
                canvas.ground(x, z, y, FARMLAND, SOIL)
                if (detail != BARE) canvas.set(x, y, z, B(M.CROP, v = detail))
            }
            Cell.DITCH -> canvas.ground(x, z, y, WATER, SOIL)
            Cell.KERB -> canvas.ground(x, z, y, FOUNDATION, SOIL)
            Cell.HEDGE -> {
                canvas.ground(x, z, y, GRASS_BLOCK, SOIL)
                canvas.set(x, y, z, LEAVES)
                if (detail == 1) canvas.set(x, y + 1, z, LEAVES)
            }
            Cell.FENCE -> {
                canvas.ground(x, z, y, GRASS_BLOCK, SOIL)
                canvas.set(x, y, z, B(M.FENCE))
            }
            Cell.GATE -> {
                canvas.ground(x, z, y, GRASS_BLOCK, SOIL)
                canvas.set(x, y, z, B(M.FENCE_GATE, Dir.entries[detail]))
            }
            Cell.FLOWERS -> {
                canvas.ground(x, z, y, GRASS_BLOCK, SOIL)
                if (detail != NO_FLOWER) canvas.set(x, y, z, B(M.FLOWER, v = detail))
            }
            Cell.BANK -> canvas.clearing(x, z, y)
            Cell.RAIL -> {
                // On the land's own ground; where the ground inside climbs away, the post stands a block taller
                canvas.set(x, y, z, B(M.FENCE))
                canvas.set(x, y + 1, z, if (detail == 1) B(M.FENCE) else B.AIR)
            }
            Cell.RAIL_GATE -> {
                canvas.set(x, y, z, B(M.FENCE_GATE, Dir.entries[detail]))
                canvas.set(x, y + 1, z, B.AIR)
            }
        }
    }

    /** A bridge's deck, its parapet or railing, the pier or bank under it, the spring of an arch. */
    private fun bridgeColumn(canvas: Canvas, x: Int, z: Int, y: Int, detail: Int) {
        val stone = detail and STONE_BRIDGE != 0
        val deck = if (stone) B(M.STONE) else B(M.PLANKS)
        if (detail and GROUNDED != 0) {
            canvas.ground(x, z, y, deck, if (stone) B(M.STONE) else B(M.LOG))
        } else {
            canvas.set(x, y - 1, z, deck)
            for (k in 0..2) canvas.set(x, y + k, z, B.AIR)
        }
        if (detail and RAILED != 0) canvas.set(x, y, z, if (stone) B(M.WALL_POST) else B(M.FENCE))
        val arch = (detail shr ARCH_SHIFT) and 7
        if (arch != 0) canvas.set(x, y - 2, z, B(M.STONE_STAIRS, Dir.entries[arch - 1], top = true))
    }

    private fun surface(detail: Int): B = when (detail) {
        COBBLES -> B(M.PAVE_LANE)
        DIRT -> B(M.PAVE_LANE, v = 1)
        GRAVEL -> B(M.TRACK)
        GRASS -> GRASS_BLOCK
        FOOTING -> FOUNDATION
        else -> B(M.PAVE_YARD)
    }

    /** The rectangle in the city's frame of [f]'s columns from ([u0], [v0]) to ([u1], [v1]). */
    private fun frameRect(f: Frame, u0: Int, v0: Int, u1: Int, v1: Int): Rect {
        val ax = f.x(u0, v0)
        val az = f.z(u0, v0)
        val bx = f.x(u1, v1)
        val bz = f.z(u1, v1)
        return Rect(min(ax, bx), min(az, bz), max(ax, bx), max(az, bz))
    }

    /**
     * The rectangle on [side]'s [sign] half (across it from the gate's line) from [a0] to [a1] out from that
     * line, and [n0] to [n1] out from the middle.
     */
    private fun sideRect(side: Dir, sign: Int, a0: Int, a1: Int, n0: Int, n1: Int): Rect {
        val p = across(side, sign * a0, n0)
        val q = across(side, sign * a1, n1)
        return Rect(min(p.x, q.x), min(p.z, q.z), max(p.x, q.x), max(p.z, q.z))
    }

    /** What's laid in a column of the country. */
    private enum class Cell {
        NONE,
        /** Ground laid at standing height, its surface by the detail: a road's, a yard's, a lawn's, a footing. */
        GROUND,
        /** A bridge over water: its deck, and by the detail its railing, pier and arch. */
        BRIDGE,
        /** Tilled soil with a crop on it, or bare. */
        CROP,
        /** An irrigation ditch: water a block under standing height. */
        DITCH,
        /** The stone edge of a bed standing a step over the ditch beside it. */
        KERB,
        /** A hedge (two high if the detail says) or a fence on a grass edge, or a gate in one facing out. */
        HEDGE, FENCE, GATE,
        /** A flower bed. */
        FLOWERS,
        /** The land's own ground, banked to stand at its height round something levelled. */
        BANK,
        /** A fence or a gate on the land's own ground, round a pasture or an orchard. */
        RAIL, RAIL_GATE,
    }

    companion object {
        /** Rows at the end of a road kept straight out, for whatever road goes on from there. */
        const val STRAIGHT_OUT = 8
        /** How far a road may wander from its gate's line, how often it may shift, and what pulls it. */
        const val WANDER = 9
        const val SHIFT_EVERY = 3
        const val WET_COST = 6.0
        const val CROSS_COST = 2.5
        const val DRIFT_COST = 0.35
        /** What a block's step in a road or a field costs against keeping to the land. */
        const val STEP_COST = 0.4
        /** The longest a farm lane may bridge, and run from its road. */
        const val MAX_LANE_BRIDGE = 6
        const val MAX_LANE = 64
        const val NO_FLOOR = Int.MIN_VALUE

        /** Most a plot is cut into or built up from the land, a yard, and a field (past which a column's left out). */
        const val MAX_PLOT_CUT = 3
        const val MAX_YARD_CUT = 4
        const val MAX_FIELD_CUT = 4

        /** Lines of crops between ditches on the level. */
        const val BED_WIDTH = 8
        const val DITCH_LINE = -1
        /** Parcels' sides, and the least ground worth a field or a pasture. */
        const val MIN_PARCEL = 10
        const val MAX_PARCEL = 30
        const val MIN_AREA = 70
        /** How many parcels in a hundred are meant for fields, pastures and orchards (the rest left wild). */
        const val FIELD_SHARE = 62
        const val PASTURE_SHARE = 22
        const val ORCHARD_SHARE = 10
        /** A parcel with more of its ground than this in steps is left wild. */
        const val MAX_CLIFFS = 0.12

        /** Rows between the pairs of poplars down an avenue; a pasture's ground for each shade tree in it. */
        const val AVENUE_STEP = 5
        const val SHADE_AREA = 220

        /** A windmill's lot, out from its middle, and how far apart two stand at least. */
        const val WINDMILL_HALF = 6
        const val WINDMILL_SPACING = 60
        /** The watermill's house: along the road, and back from it. */
        const val MILL_LENGTH = 9
        const val MILL_DEPTH = 7

        // A ground cell's surface
        const val COBBLES = 0
        const val DIRT = 1
        const val GRAVEL = 2
        const val GRASS = 3
        const val EARTH = 4
        const val FOOTING = 5

        // A bridge cell's parts
        const val RAILED = 1
        const val GROUNDED = 2
        const val STONE_BRIDGE = 4
        const val ARCH_SHIFT = 3

        /** A crop cell with nothing growing yet; a flower bed with nothing in flower. */
        const val BARE = 0xFF
        const val NO_FLOWER = 0xFF

        /** What the fields grow (wheat most of all), and the kitchen gardens. */
        val CROPS_FIELD = intArrayOf(0, 0, 0, 1, 2, 3)
        val CROPS_KITCHEN = intArrayOf(1, 2, 3, 1, 5, 2)

        val FILL = B(M.FILL)
        val SOIL = B(M.SOIL)
        val FOUNDATION = B(M.FOUNDATION)
        val FARMLAND = B(M.FARMLAND)
        val WATER = B(M.WATER)
        val GRASS_BLOCK = B(M.GRASS)
        val LEAVES = B(M.LEAVES)
    }
}

/** The column [along] across [side] from the line out of its gate, [out] from the middle. */
private fun across(side: Dir, along: Int, out: Int): CityPlan.Pos = when (side) {
    Dir.N -> CityPlan.Pos(along, -out)
    Dir.S -> CityPlan.Pos(along, out)
    Dir.W -> CityPlan.Pos(-out, along)
    Dir.E -> CityPlan.Pos(out, along)
}

/** How far across [side] a column is, from the line out of its gate. */
private fun alongOf(side: Dir, x: Int, z: Int): Int = if (side.alongX) z else x

/** The way across [side] in which "along" grows ([sign] 1) or shrinks. */
private fun crossDir(side: Dir, sign: Int): Dir = if (side.alongX) (if (sign > 0) Dir.S else Dir.N) else (if (sign > 0) Dir.E else Dir.W)
