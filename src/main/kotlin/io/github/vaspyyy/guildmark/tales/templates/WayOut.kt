package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.scenes.Scenes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.sqrt

/**
 * The town's gates and the road beyond them, for the two tales that are set on the road out (the child, 8.4, and the cart, 8.8): where
 * a gate's front is, which gate a place on the road belongs to, a spot a seeded draw puts some way along a road from the town, and the
 * word for the side a gate is on. Everything is worked out from the plan and the road network alone, so a seed can point at once and a
 * place far from any player is still a place; nothing here loads a chunk or sets a block.
 */
internal object WayOut {
    /** A spot along a country road: the [gate] the road leaves by, the [point] itself and the way the road runs from the gate towards it. */
    class Spot(val gate: Dir, val point: BlockPos, val toward: Vec3)

    /** The outer gates that have a country road. */
    fun gates(places: CityPlaces): List<Dir> {
        val plan = places.census.plan
        return plan.outerGates.filter { g -> plan.outskirts.roads.any { it.side == g } }
    }

    /** Just outside the gatehouse on [gate]'s side, on the road. */
    fun front(places: CityPlaces, gate: Dir): BlockPos {
        val plan = places.census.plan
        val p = plan.sidePos(gate, 0, plan.outer + CityPlan.GATE_OUT + 1)
        return places.capital.world(p.x, plan.level(p.x, p.z), p.z)
    }

    /** The gate with a country road whose front is nearest [at], or null if the town has none. */
    fun gateNear(places: CityPlaces, at: BlockPos): Dir? = gates(places).minByOrNull { front(places, it).distSqr(at) }

    /** The word for the side [gate] is on ("north"), which the grey words of the talk are made with. */
    fun side(here: Scenes.Here, gate: Dir): String = when (here.capital.direction(gate)) {
        Direction.NORTH -> "north"
        Direction.EAST -> "east"
        Direction.SOUTH -> "south"
        else -> "west"
    }

    /** The key of the grey word for a side, `say.guildmark.talk.detail.tale.way.<side>`, as an argument of a detail. */
    fun sideKey(side: String): String = "say.guildmark.talk.detail.tale.way.$side"

    /**
     * A point along one of the town's country roads, [min] to [max] blocks from where the town's way out meets the road (counted along the
     * road), by [random]; null if the town has no road out, or none that reaches [min] blocks. With [gate], only a road that leaves by
     * that gate (the cart's family went out by the gate nearest their door).
     */
    fun spot(level: ServerLevel, here: Scenes.Here, random: Random, min: Int, max: Int, gate: Dir? = null): Spot? {
        val center = here.capital.center
        val roads = RoadNetwork.get(level).roadsFrom(center).filter { it.points.size >= 2 }.sortedBy { it.id }
        if (roads.isEmpty()) return null
        // Each road in turn from a seeded one, so a road that is too short does not decide it
        val first = random.nextInt(roads.size)
        for (k in roads.indices) {
            val road = roads[(first + k) % roads.size]
            val points = road.pointsFrom(center)
            val start = Capitals.wayOut(here.capital, road.otherEnd(center)).lastOrNull() ?: points.first()
            if (gate != null && gateNear(here.places, start) != gate) continue
            val i0 = points.indices.minByOrNull { points[it].distSqr(start) } ?: continue
            val want = min + random.nextInt(maxOf(1, max - min + 1))
            var walked = 0.0
            var at = i0
            while (at + 1 < points.size && walked < want) {
                walked += flat(points[at], points[at + 1])
                at++
            }
            if (walked < min) continue
            val point = points[at]
            val gate = gateNear(here.places, start) ?: continue
            val front = front(here.places, gate)
            val ahead = points[minOf(points.size - 1, i0 + 2)]
            return Spot(gate, point, Vec3(ahead.x + 0.5 - front.x, 0.0, ahead.z + 0.5 - front.z))
        }
        return null
    }

    /** The unit step along the road at [near] (from the waypoint before it to the one after), for the verge to the side of it; east if the road has no run there. */
    fun run(level: ServerLevel, here: Scenes.Here, near: BlockPos): Vec3 {
        val center = here.capital.center
        var best: Pair<Double, Vec3>? = null
        for (road in RoadNetwork.get(level).roadsFrom(center)) {
            val pts = road.points
            for (i in pts.indices) {
                val d = pts[i].distSqr(near).toDouble()
                if (best != null && d >= best.first) continue
                val a = pts[maxOf(0, i - 1)]
                val b = pts[minOf(pts.size - 1, i + 1)]
                val dx = (b.x - a.x).toDouble()
                val dz = (b.z - a.z).toDouble()
                val len = sqrt(dx * dx + dz * dz)
                if (len > 0.1) best = d to Vec3(dx / len, 0.0, dz / len)
            }
        }
        return best?.second ?: Vec3(1.0, 0.0, 0.0)
    }

    /**
     * The road home from [from]: the waypoints of the country road nearest it, from the one nearest [from] back toward the town, to the
     * gate's front and no further (a body that walks them is at the gate when it has walked them). Empty if the town has no road.
     */
    fun homeward(level: ServerLevel, here: Scenes.Here, from: BlockPos): List<BlockPos> {
        val center = here.capital.center
        var best: List<BlockPos> = listOf()
        var bestD = Double.MAX_VALUE
        for (road in RoadNetwork.get(level).roadsFrom(center)) {
            val points = road.pointsFrom(center)
            val i = points.indices.minByOrNull { points[it].distSqr(from) } ?: continue
            val d = points[i].distSqr(from).toDouble()
            if (d < bestD) {
                bestD = d
                best = points.subList(0, i + 1).asReversed()
            }
        }
        if (best.isEmpty()) return listOf()
        val gate = gateNear(here.places, best.last()) ?: return best
        val front = front(here.places, gate)
        val out = ArrayList<BlockPos>()
        for (p in best) {
            out += p
            if (flat(p, front) < GATE_NEAR) break
        }
        out += front
        return out
    }

    /** Blocks from a gate's front within which the road home is walked no further. */
    private const val GATE_NEAR = 12.0

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (b.x - a.x).toDouble()
        val dz = (b.z - a.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }
}
