package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.Census
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.FarmPropKind
import io.github.vaspyyy.guildmark.city.plan.PropKind
import io.github.vaspyyy.guildmark.city.plan.Use
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A capital's street lamps as its plan lays them out, in the city's own frame: the lamps down its streets (see
 * Props) and out in its country (see FarmProps), which farmhouse's farmer sees to each lamp at a farm's gate, and the
 * lamplighters' rounds among the street lamps. Worked out from the plan alone, the same every time (StreetLamps does it
 * off the server thread), so a round's lamps and their order never change.
 *
 * The rounds follow the streets: one walk through every street lamp of the city, each step to the nearest lamp not yet
 * lit along the streets (the plan's own street columns, walked a column at a time), straightened out where it crosses
 * itself, then cut into stretches each a lamplighter can walk and light in about [ROUND_TICKS].
 */
class LampPlan(val plan: CityPlan, census: Census) {
    /**
     * One lamp: its lantern at [at] (the post's foot three blocks under it); [city] for a street lamp inside the walls,
     * [farm] the lodging id (see [Census.lodges]) of the farmhouse whose farmer sees to it, or -1; the ways from the post
     * a lamplighter may stand to it, best first ([stands]: the street's side for a street lamp); [phase], from 0 to 1,
     * how far into the lighting-up its clock has it catch when nobody lights it.
     */
    class Lamp(val index: Int, val at: At, val city: Boolean, val farm: Int, val stands: List<Dir>, val phase: Double) {
        override fun toString(): String = "lamp#$index(${at.x},${at.y},${at.z})"
    }

    /** A lamplighter's round: its [lamps] in the order they're lit, and the blocks walked between each and the one before ([legs], 0 for the first). */
    class Round(val index: Int, val lamps: IntArray, val legs: IntArray) {
        /** Blocks walked from its first lamp to its last. */
        val walk: Int get() = legs.sum()

        /** About how long it takes to walk and light, in ticks (see [ticks]). */
        val ticks: Int get() = ticks(lamps.size, walk)
    }

    val lamps: List<Lamp>
    val rounds: List<Round>

    /** For each lamp, its round's index (or -1), and its place in it. */
    val roundOf: IntArray
    val placeOf: IntArray

    /** The columns the street's other things stand in (trees, benches, wells, stalls), where nobody stands to a lamp. */
    private val props: Set<Long> = plan.props.filter { it.kind != PropKind.LAMP }.mapTo(HashSet()) { key(it.x, it.z) }

    init {
        val list = ArrayList<Lamp>()
        for (a in census.lamps) list.add(Lamp(list.size, a, true, -1, stands(a), phase(a.x, a.z)))
        // The country's: by the farmhouses at the gates, on the roads and the bridges
        val houses = plan.outskirts.buildings.withIndex().filter { it.value.kind == FarmKind.FARMHOUSE }
        for (p in plan.outskirts.props) {
            if (p.kind != FarmPropKind.LAMP) continue
            val house = houses.minByOrNull { (_, b) -> gap(b.lot.x0, b.lot.z0, b.lot.x1, b.lot.z1, p.x, p.z) }
                ?.takeIf { (_, b) -> gap(b.lot.x0, b.lot.z0, b.lot.x1, b.lot.z1, p.x, p.z) <= FARM_REACH }
            val at = At(p.x, p.y + 3, p.z)
            list.add(Lamp(list.size, at, false, house?.let { Census.FARM + it.index } ?: -1, Dir.entries.toList(), phase(p.x, p.z)))
        }
        lamps = list
        rounds = plan(list.filter { it.city })
        roundOf = IntArray(list.size) { -1 }
        placeOf = IntArray(list.size) { -1 }
        for (r in rounds) r.lamps.forEachIndexed { i, l ->
            roundOf[l] = r.index
            placeOf[l] = i
        }
    }

    /** The ways a lamplighter may stand to the street lamp [a]: onto the street, the side with the most street beyond first. */
    private fun stands(a: At): List<Dir> {
        val y = plan.level(a.x, a.z)
        return Dir.entries.filter { d ->
            val x = a.x + d.dx
            val z = a.z + d.dz
            walkable(x, z) && plan.level(x, z) == y && key(x, z) !in props
        }.sortedByDescending { d -> (1..3).count { k -> walkable(a.x + d.dx * k, a.z + d.dz * k) } }
    }

    private fun walkable(x: Int, z: Int): Boolean {
        val use = plan.grid[x, z]
        return use.street || use == Use.GATE
    }

    // ---- The rounds ----

    /** The street lamps' rounds: one walk through them all along the streets, cut into stretches of about [ROUND_TICKS]. */
    private fun plan(city: List<Lamp>): List<Round> {
        if (city.isEmpty()) return listOf()
        val n = city.size
        val near = Array(n) { HashMap<Int, Int>() }
        val cellOf = HashMap<Long, Int>()
        city.forEachIndexed { i, l -> cellOf[key(l.at.x, l.at.z)] = i }
        for (i in 0 until n) streetDistances(city[i], cellOf, near[i])
        fun d(i: Int, j: Int): Int = near[i][j] ?: near[j][i] ?: far(city[i], city[j])

        // Nearest lamp not yet visited, from the one nearest the plaza's middle
        val start = (0 until n).minBy { city[it].at.x * city[it].at.x + city[it].at.z * city[it].at.z }
        val tour = ArrayList<Int>(n)
        val seen = BooleanArray(n)
        var at = start
        while (true) {
            tour.add(at)
            seen[at] = true
            var best = -1
            var bestD = Int.MAX_VALUE
            for ((j, dj) in near[at]) if (!seen[j] && (dj < bestD || dj == bestD && j < best)) {
                best = j
                bestD = dj
            }
            if (best < 0) {
                // Nothing left near along the streets: the nearest anywhere
                for (j in 0 until n) if (!seen[j]) {
                    val dj = d(at, j)
                    if (dj < bestD) {
                        best = j
                        bestD = dj
                    }
                }
            }
            if (best < 0) break
            at = best
        }
        untangle(tour, ::d)

        // Cut into as few stretches as keep each within about ROUND_TICKS, the longest as short as it can be (a long walk
        // between two lamps is where a cut saves most: neither round walks it)
        val legs = IntArray(n) { k -> if (k == 0) 0 else d(tour[k - 1], tour[k]) }
        val total = ticks(n, legs.sum())
        var count = max(1, min(ceil(total / ROUND_TICKS.toDouble()).toInt(), n / MIN_LAMPS))
        var bounds = cut(legs, count)
        while (count < n / MIN_LAMPS && longest(legs, bounds) > ROUND_TICKS * 11 / 10) bounds = cut(legs, ++count)
        val out = ArrayList<Round>()
        for (r in 0 until bounds.size - 1) {
            val from = bounds[r]
            val to = bounds[r + 1]
            if (to <= from) continue
            val ids = IntArray(to - from) { k -> city[tour[from + k]].index }
            val roundLegs = IntArray(to - from) { k -> if (k == 0) 0 else legs[from + k] }
            out.add(Round(out.size, ids, roundLegs))
        }
        return out
    }

    /**
     * About how long the stretch of the walk from its [a]th lamp up to (not taking in) its [b]th takes, with [walked] the
     * blocks walked from the first lamp to each (so the walk to the stretch's first lamp is left out).
     */
    private fun span(walked: IntArray, a: Int, b: Int): Int = ticks(b - a, walked[b - 1] - walked[a])

    private fun walked(legs: IntArray): IntArray {
        val out = IntArray(legs.size)
        for (k in 1 until legs.size) out[k] = out[k - 1] + legs[k]
        return out
    }

    private fun longest(legs: IntArray, bounds: List<Int>): Int {
        val walked = walked(legs)
        return (0 until bounds.size - 1).maxOf { span(walked, bounds[it], bounds[it + 1]) }
    }

    /**
     * Where to cut the walk (with [legs] the walks between its lamps) into [count] stretches of at least [MIN_LAMPS]
     * lamps, the longest of them as short as can be: the stretches' bounds, from 0 to the number of lamps.
     */
    private fun cut(legs: IntArray, count: Int): List<Int> {
        val n = legs.size
        if (count <= 1 || n < 2 * MIN_LAMPS) return listOf(0, n)
        val walked = walked(legs)
        // best[j][i]: the shortest the longest of j stretches covering the first i lamps can be; from[j][i] where the last begins
        val none = Int.MAX_VALUE
        val best = Array(count + 1) { IntArray(n + 1) { none } }
        val from = Array(count + 1) { IntArray(n + 1) }
        best[0][0] = 0
        for (j in 1..count) for (i in j * MIN_LAMPS..n) {
            for (a in (j - 1) * MIN_LAMPS..i - MIN_LAMPS) {
                if (best[j - 1][a] == none) continue
                val worst = max(best[j - 1][a], span(walked, a, i))
                if (worst < best[j][i]) {
                    best[j][i] = worst
                    from[j][i] = a
                }
            }
        }
        if (best[count][n] == none) return listOf(0, n)
        val bounds = ArrayList<Int>()
        var i = n
        for (j in count downTo 1) {
            bounds.add(i)
            i = from[j][i]
        }
        bounds.add(0)
        return bounds.reversed()
    }

    /**
     * The blocks along the streets from [lamp] to every street lamp within [NEAR] blocks of walking, into [out] (by its
     * index among [cellOf]'s): the plan's street columns walked a column at a time, as a lamplighter would.
     */
    private fun streetDistances(lamp: Lamp, cellOf: Map<Long, Int>, out: HashMap<Int, Int>) {
        val dist = HashMap<Long, Int>()
        val queue = ArrayDeque<Long>()
        val k0 = key(lamp.at.x, lamp.at.z)
        dist[k0] = 0
        queue.add(k0)
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            val d = dist[k]!!
            val (x, z) = unkey(k)
            cellOf[k]?.let { j -> if (d > 0) out[j] = d }
            if (d >= NEAR) continue
            for (dir in Dir.entries) {
                val nx = x + dir.dx
                val nz = z + dir.dz
                val nk = key(nx, nz)
                if (nk in dist || !walkable(nx, nz)) continue
                dist[nk] = d + 1
                queue.add(nk)
            }
        }
    }

    /** Straightens out [tour] where it doubles back across itself (each stretch turned round if that walks less). */
    private fun untangle(tour: MutableList<Int>, d: (Int, Int) -> Int) {
        val n = tour.size
        if (n < 4) return
        repeat(UNTANGLE_PASSES) {
            var better = false
            for (i in 0 until n - 2) for (j in i + 2 until n) {
                val a = tour[i]
                val b = tour[i + 1]
                val c = tour[j]
                val e = if (j + 1 < n) tour[j + 1] else -1
                val before = d(a, b) + (if (e >= 0) d(c, e) else 0)
                val after = d(a, c) + (if (e >= 0) d(b, e) else 0)
                if (after < before) {
                    tour.subList(i + 1, j + 1).reverse()
                    better = true
                }
            }
            if (!better) return
        }
    }

    companion object {
        /**
         * About how long a round should take to walk and light, in ticks: an hour and a half and a bit, so that one begun
         * at a little after five is done by seven (lamps stand about ten blocks apart along the streets, so that's 8 to 15
         * lamps).
         */
        const val ROUND_TICKS = 1600

        /** Fewest lamps a round has (fewer and its lamplighter would hardly be worth fetching the pole). */
        const val MIN_LAMPS = 8

        /** How far along the streets a lamp's neighbours are looked for, in blocks. */
        const val NEAR = 64

        /** How near a farmhouse's lot a lamp must stand to be its farmer's to see to. */
        const val FARM_REACH = 10

        private const val UNTANGLE_PASSES = 6

        /** Ticks a block takes at the lamplighter's brisk walk, and ticks at each lamp: stepping up, turning, lighting it. */
        const val WALK_TICKS = 7
        const val LAMP_TICKS = 56

        /** About how long [lamps] lamps [walk] blocks apart in all take to walk and light, in ticks. */
        fun ticks(lamps: Int, walk: Int): Int = walk * WALK_TICKS + lamps * LAMP_TICKS

        private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        private fun unkey(k: Long): Pair<Int, Int> = Pair((k shr 32).toInt(), k.toInt())

        /** Blocks between a column and the nearest of a rectangle's (0 inside it). */
        private fun gap(x0: Int, z0: Int, x1: Int, z1: Int, x: Int, z: Int): Int =
            max(max(x0 - x, x - x1), max(z0 - z, z - z1)).coerceAtLeast(0)

        /** Two lamps with no way between them along the streets within reach: as the crow flies, and a half again. */
        private fun far(a: Lamp, b: Lamp): Int {
            val dx = (a.at.x - b.at.x).toDouble()
            val dz = (a.at.z - b.at.z).toDouble()
            return (sqrt(dx * dx + dz * dz) * 1.5).roundToInt() + abs(a.at.y - b.at.y) * 2
        }

        /** A number from 0 to 1 that looks random but is fixed for the spot. */
        private fun phase(x: Int, z: Int): Double {
            var h = x * 0x9E3779B97F4A7C15uL.toLong() xor z * 0xC2B2AE3D27D4EB4FuL.toLong()
            h = (h xor (h ushr 31)) * -0x40a7b892e31b1a47L
            h = h xor (h ushr 29)
            return ((h ushr 11).toDouble() / (1L shl 53).toDouble())
        }
    }
}
