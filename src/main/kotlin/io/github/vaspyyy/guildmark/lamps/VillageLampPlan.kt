package io.github.vaspyyy.guildmark.lamps

import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.Dir
import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A village's lamps as its census found them (living-world/villages/design.md 10.1): the lanterns on its posts, each a point
 * of the world itself (a village has no plan of its own, so the lamps' frame is the world's), and the lamplighters' rounds among
 * them. Worked out from the posts alone, whatever order the census listed them in, so the same posts always give the same lamps
 * and rounds, and they are numbered from the lantern with the lowest x, then z, then y.
 *
 * The rounds are [LampTour]'s, as a capital's are, from the lamp nearest the bell: one walk through every post, each step to the
 * nearest not yet lit, straightened out where it crosses itself, then cut into stretches of about [LampPlan.ROUND_TICKS] each
 * (a village's posts are ten blocks apart or more, so a dozen to twenty of them make one round or two). A village has no street
 * grid to walk along, so the distance between two posts is the crow's, and a half again, as a capital's is for lamps with no way
 * between them along its streets.
 *
 * Every lamp is a street lamp of the village's ([LampPlan.Lamp.city]): a lamp out on the road to the village is no post of its
 * own (the census finds none), and a village has no farm gates or quays to keep.
 */
class VillageLampPlan(val bell: BlockPos, posts: Collection<BlockPos>) : LampSet {
    override val lamps: List<LampPlan.Lamp>
    override val rounds: List<LampPlan.Round>

    init {
        val sorted = posts.distinct().sortedWith(compareBy<BlockPos>({ it.x }, { it.z }, { it.y }))
        lamps = sorted.mapIndexed { i, p ->
            LampPlan.Lamp(i, At(p.x, p.y, p.z), true, -1, stands(p), LampPlan.phase(p.x, p.z))
        }
        rounds = plan(lamps)
    }

    /** The ways a lamplighter may stand to the post at [p], the side towards the bell (and so the green and the paths to it) first. */
    private fun stands(p: BlockPos): List<Dir> =
        Dir.entries.sortedBy { d -> (p.x + d.dx - bell.x).toDouble().let { it * it } + (p.z + d.dz - bell.z).toDouble().let { it * it } }

    /** The village's rounds: one walk through its lamps from the one nearest the bell, cut into stretches of about [LampPlan.ROUND_TICKS]. */
    private fun plan(all: List<LampPlan.Lamp>): List<LampPlan.Round> {
        val n = all.size
        if (n == 0) return listOf()
        val far = Array(n) { i -> HashMap<Int, Int>(n * 2) }
        for (i in 0 until n) for (j in 0 until n) if (i != j) far[i][j] = crow(all[i], all[j])
        fun d(i: Int, j: Int): Int = far[i][j] ?: 0
        val start = (0 until n).minBy { i ->
            val dx = (all[i].at.x - bell.x).toDouble()
            val dz = (all[i].at.z - bell.z).toDouble()
            dx * dx + dz * dz
        }
        val walk = LampTour.walk(n, start, far, ::d)
        val out = ArrayList<LampPlan.Round>()
        for (r in 0 until walk.bounds.size - 1) {
            val from = walk.bounds[r]
            val to = walk.bounds[r + 1]
            if (to <= from) continue
            val ids = IntArray(to - from) { k -> all[walk.order[from + k]].index }
            val legs = IntArray(to - from) { k -> if (k == 0) 0 else walk.legs[from + k] }
            out.add(LampPlan.Round(out.size, ids, legs))
        }
        return out
    }

    private companion object {
        /** About the blocks a lamplighter walks between two posts: as the crow flies and a half again, and two for each block up or down. */
        fun crow(a: LampPlan.Lamp, b: LampPlan.Lamp): Int {
            val dx = (a.at.x - b.at.x).toDouble()
            val dz = (a.at.z - b.at.z).toDouble()
            return (sqrt(dx * dx + dz * dz) * 1.5).roundToInt() + abs(a.at.y - b.at.y) * 2
        }
    }
}
