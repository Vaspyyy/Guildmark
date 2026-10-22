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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A capital's street lamps as its plan lays them out, in the city's own frame: the lamps down its streets (see
 * Props), out in its country (see FarmProps) and on its harbour's quays and piers (see Harbour.lamps), which farmhouse's
 * farmer sees to each lamp at a farm's gate, and the lamplighters' rounds among the street lamps. Worked out from the
 * plan alone, the same every time (StreetLamps does it off the server thread), so a round's lamps and their order never
 * change.
 *
 * The rounds follow the streets: one walk through every street lamp of the city, each step to the nearest lamp not yet
 * lit along the streets (the plan's own street columns, walked a column at a time), straightened out where it crosses
 * itself, then cut into stretches each a lamplighter can walk and light in about [ROUND_TICKS].
 */
class LampPlan(val plan: CityPlan, census: Census) : LampSet {
    /**
     * One lamp: its lantern at [at] (the post's foot three blocks under it); [city] for a street lamp inside the walls,
     * [farm] the lodging id (see [Census.lodges]) of the farmhouse whose farmer sees to it, or -1; the ways from the post
     * a lamplighter may stand to it, best first ([stands]: the street's side for a street lamp); [phase], from 0 to 1,
     * how far into the lighting-up its clock has it catch when nobody lights it; [quay] for one on the harbour's quays
     * and piers (no farm's, and no lamplighter's: it keeps its own time like a lamp out on a road).
     */
    class Lamp(val index: Int, val at: At, val city: Boolean, val farm: Int, val stands: List<Dir>, val phase: Double, val quay: Boolean = false) {
        override fun toString(): String = "lamp#$index(${at.x},${at.y},${at.z})"
    }

    /** A lamplighter's round: its [lamps] in the order they're lit, and the blocks walked between each and the one before ([legs], 0 for the first). */
    class Round(val index: Int, val lamps: IntArray, val legs: IntArray) {
        /** Blocks walked from its first lamp to its last. */
        val walk: Int get() = legs.sum()

        /** About how long it takes to walk and light, in ticks (see [ticks]). */
        val ticks: Int get() = ticks(lamps.size, walk)
    }

    override val lamps: List<Lamp>
    override val rounds: List<Round>

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
        // The harbour's, last of all so that no other lamp's place in the list moves: along its quay and lane and down its piers
        for (at in plan.harbour?.lamps ?: listOf()) list.add(Lamp(list.size, at, false, -1, Dir.entries.toList(), phase(at.x, at.z), quay = true))
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

        // One walk through them all from the lamp nearest the plaza's middle, cut into rounds (LampTour)
        val start = (0 until n).minBy { city[it].at.x * city[it].at.x + city[it].at.z * city[it].at.z }
        val walk = LampTour.walk(n, start, near, ::d)
        val out = ArrayList<Round>()
        for (r in 0 until walk.bounds.size - 1) {
            val from = walk.bounds[r]
            val to = walk.bounds[r + 1]
            if (to <= from) continue
            val ids = IntArray(to - from) { k -> city[walk.order[from + k]].index }
            val roundLegs = IntArray(to - from) { k -> if (k == 0) 0 else walk.legs[from + k] }
            out.add(Round(out.size, ids, roundLegs))
        }
        return out
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
