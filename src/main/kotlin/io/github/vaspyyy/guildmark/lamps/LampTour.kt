package io.github.vaspyyy.guildmark.lamps

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The lamplighters' tour, as pure functions of points and a distance: one walk through every point, each step to the
 * nearest not yet visited, straightened out where it crosses itself, then cut into rounds each a lamplighter can walk
 * and light in about [LampPlan.ROUND_TICKS]. A capital's [LampPlan] and a village's [VillageLampPlan] both work out their
 * rounds with it, from the points (their lamps, numbered from 0) and how far apart along the ways the plan walks they are.
 * Nothing here knows what a point is, so the same points and distances always give the same rounds.
 */
object LampTour {
    /**
     * A tour worked out: [order] the points in the order they're visited, [legs] the walk (in blocks) to each from the one
     * before it (0 for the first), and [bounds] where it's cut into rounds, from 0 to the number of points (round `r` is
     * `order` from `bounds[r]` up to, not taking in, `bounds[r + 1]`).
     */
    class Walk(val order: List<Int>, val legs: IntArray, val bounds: List<Int>)

    /**
     * The tour through [n] points, from [start]: [near] gives, for each point, the walk in blocks to each of the points
     * within reach of it along the ways, and [d] the walk between any two ([near]'s where it has one, a worse guess where
     * it doesn't). Each step goes to the nearest point not yet visited that is within reach, and where there is none, to
     * the nearest anywhere.
     */
    fun walk(n: Int, start: Int, near: Array<out Map<Int, Int>>, d: (Int, Int) -> Int): Walk {
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
        untangle(tour, d)

        // Cut into as few stretches as keep each within about ROUND_TICKS, the longest as short as it can be (a long walk
        // between two lamps is where a cut saves most: neither round walks it)
        val legs = IntArray(n) { k -> if (k == 0) 0 else d(tour[k - 1], tour[k]) }
        val total = LampPlan.ticks(n, legs.sum())
        var count = max(1, min(ceil(total / LampPlan.ROUND_TICKS.toDouble()).toInt(), n / LampPlan.MIN_LAMPS))
        var bounds = cut(legs, count)
        while (count < n / LampPlan.MIN_LAMPS && longest(legs, bounds) > LampPlan.ROUND_TICKS * 11 / 10) bounds = cut(legs, ++count)
        return Walk(tour, legs, bounds)
    }

    /**
     * About how long the stretch of the walk from its [a]th lamp up to (not taking in) its [b]th takes, with [walked] the
     * blocks walked from the first lamp to each (so the walk to the stretch's first lamp is left out).
     */
    private fun span(walked: IntArray, a: Int, b: Int): Int = LampPlan.ticks(b - a, walked[b - 1] - walked[a])

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
     * Where to cut the walk (with [legs] the walks between its lamps) into [count] stretches of at least [LampPlan.MIN_LAMPS]
     * lamps, the longest of them as short as can be: the stretches' bounds, from 0 to the number of lamps.
     */
    private fun cut(legs: IntArray, count: Int): List<Int> {
        val n = legs.size
        if (count <= 1 || n < 2 * LampPlan.MIN_LAMPS) return listOf(0, n)
        val walked = walked(legs)
        // best[j][i]: the shortest the longest of j stretches covering the first i lamps can be; from[j][i] where the last begins
        val none = Int.MAX_VALUE
        val best = Array(count + 1) { IntArray(n + 1) { none } }
        val from = Array(count + 1) { IntArray(n + 1) }
        best[0][0] = 0
        for (j in 1..count) for (i in j * LampPlan.MIN_LAMPS..n) {
            for (a in (j - 1) * LampPlan.MIN_LAMPS..i - LampPlan.MIN_LAMPS) {
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

    private const val UNTANGLE_PASSES = 6
}
