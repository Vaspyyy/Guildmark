package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.quest.Expeditions
import io.github.vaspyyy.guildmark.road.Road
import io.github.vaspyyy.guildmark.road.RoadNetwork
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The ways the wagons drive into each capital (design-road.md section 1): one leg per road into it, worked out once,
 * off the server thread, and kept until the server stops. A leg is the road's last stretch from just outside the
 * village at its far end (the last [DRIVEN] blocks before the farms at most), then the plan's country road on that
 * side, row by row at the plan's heights, then the streets from the gate to the wagon stand (the market square behind
 * the south gate, the street just inside any other), its corners rounded so a wagon turns in arcs. A capital with no
 * road has the plan's own main road from the farm edge in, and, with [PAVE_ROADS], asks the road network for a road
 * to its nearest village, paved as the land loads.
 */
object CaravanRoutes {
    /**
     * Open question 1, "Pave roads": every capital gets a road to its nearest village, surveyed by the road network
     * and paved as the land loads, old worlds too. False is "Village roads only": a capital with no road of its own
     * keeps its wagons on its own main road from the farm edge in, struck out of sight with no wreck to find.
     */
    const val PAVE_ROADS = true

    /** Of a long road, how much before the farms is driven for real, and how far past the far end's village it starts. */
    private const val DRIVEN = 512.0
    private const val CLEAR = 48.0

    /** How far a strike point keeps from the start of its leg and from the farm edge. */
    const val STRIKE_START = 64.0
    const val STRIKE_FARMS = 96.0

    /** How far off a road its band may camp. */
    const val BAND_REACH = 256.0

    /** Rows of a country road between the leg's points, and how many times its corners are rounded. */
    private const val ROW_STEP = 3
    private const val ROUNDING = 2

    /** How near a road's end must be to a capital's heart to be one of its roads (its bell stands within a few dozen blocks of it). */
    private const val ROAD_END = 64.0

    /** Game ticks between asking again for a capital that couldn't be found, or a leg that couldn't be worked out. */
    private const val RETRY = 6000L

    /**
     * A wagon's way into a capital: [points] from the start to the stand, each with how far along it lies; where the
     * farms begin ([farms]) and the gate ([gate], how far along); the gate's side; the capital's [bell] and [name]; and
     * the village at the road's far end ([far]; for the plan's own road, where it leaves the farms).
     */
    class Leg(
        val capital: Long, val road: Int, val side: Dir, val points: List<Caravans.Point>, val farms: Double, val gate: Double,
        val bell: BlockPos, val name: String, val far: BlockPos,
    ) {
        val length: Double = points.last().along
        val start: Vec3 get() = points.first().pos
        val stand: Vec3 get() = points.last().pos

        /** The cells along it have been settled, whether a band camps in them (see [OutlawCamps.settle]). */
        var settled = false

        private val minX = points.minOf { it.pos.x }
        private val maxX = points.maxOf { it.pos.x }
        private val minZ = points.minOf { it.pos.z }
        private val maxZ = points.maxOf { it.pos.z }

        /** Where [along] blocks along it is. */
        fun at(along: Double): Vec3 {
            val i = index(along)
            if (i >= points.size - 1) return points.last().pos
            val a = points[i]
            val b = points[i + 1]
            val span = b.along - a.along
            val t = if (span <= 1e-6) 0.0 else ((along - a.along) / span).coerceIn(0.0, 1.0)
            return a.pos.lerp(b.pos, t)
        }

        /** Which way it heads [along] blocks along, as a yaw (degrees, 0 facing south as entities face). */
        fun yaw(along: Double): Float {
            val i = index(along).coerceAtMost(points.size - 2).coerceAtLeast(0)
            val a = points[i].pos
            val b = points[i + 1].pos
            return (Math.toDegrees(atan2(-(b.x - a.x), b.z - a.z))).toFloat()
        }

        /** How far [x], [z] lies from it, across the ground. */
        fun distance(x: Double, z: Double): Double {
            var best = Double.MAX_VALUE
            for (k in 0 until points.size - 1) best = minOf(best, segment(x, z, points[k].pos, points[k + 1].pos).first)
            return best
        }

        /** How far along it the spot nearest [x], [z] lies. */
        fun nearest(x: Double, z: Double): Double {
            var best = Double.MAX_VALUE
            var along = 0.0
            for (k in 0 until points.size - 1) {
                val (d, t) = segment(x, z, points[k].pos, points[k + 1].pos)
                if (d < best) {
                    best = d
                    along = points[k].along + (points[k + 1].along - points[k].along) * t
                }
            }
            return along
        }

        /** Whether any of it comes within [range] of [x], [z]. */
        fun near(x: Double, z: Double, range: Double): Boolean {
            if (x < minX - range || x > maxX + range || z < minZ - range || z > maxZ + range) return false
            return distance(x, z) <= range
        }

        /** The last point at or before [along]. */
        private fun index(along: Double): Int {
            var lo = 0
            var hi = points.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (points[mid].along <= along) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** How far [x], [z] is from the segment [a] to [b], and how far along it the nearest spot is (0 to 1). */
        private fun segment(x: Double, z: Double, a: Vec3, b: Vec3): Pair<Double, Double> {
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len = dx * dx + dz * dz
            val t = if (len <= 1e-9) 0.0 else (((x - a.x) * dx + (z - a.z) * dz) / len).coerceIn(0.0, 1.0)
            val px = a.x + dx * t - x
            val pz = a.z + dz * t - z
            return sqrt(px * px + pz * pz) to t
        }
    }

    private val pieces = HashMap<Long, CapitalPiece>()
    private val asked = HashMap<Long, Long>()
    private val legs = HashMap<Pair<Long, Int>, Leg>()
    private val jobs = HashMap<Pair<Long, Int>, CompletableFuture<Leg?>>()
    private val failed = HashMap<Pair<Long, Int>, Long>()

    /** The server has stopped: everything worked out goes, as the next world may be another. */
    fun forget() {
        pieces.clear()
        asked.clear()
        legs.clear()
        jobs.clear()
        failed.clear()
    }

    /** The capital whose heart is [center], once found: from the land loaded round it, or its centre start. */
    fun capital(level: ServerLevel, center: Long): CapitalPiece? {
        pieces[center]?.let { return it }
        val last = asked[center]
        if (last != null && level.gameTime - last in 0 until RETRY) return null
        asked[center] = level.gameTime
        val pos = BlockPos.of(center)
        val piece = Furnish.capitalAt(level, pos) ?: Capitals.at(level, pos) ?: return null
        if (piece.layout == null || piece.proxy) return null
        pieces[center] = piece.inWorld(level)
        return piece
    }

    /** The network's roads into the capital whose heart is [center], finished, being built or being paved as the land loads; oldest first. */
    fun roads(level: ServerLevel, center: Long): List<Road> {
        val heart = BlockPos.of(center)
        return RoadNetwork.get(level.server.overworld()).roads.filter { it.touches(heart, ROAD_END) }.sortedBy { it.id }
    }

    /** A leg already worked out, if it is. */
    fun cached(capital: Long, road: Int): Leg? = legs[capital to road]

    /**
     * Every way into the capital whose heart is [center]: one leg per road, or the plan's own road if it has none.
     * Null while they're still being worked out (or its plan can't be read), when its wagons' goods come at the hour
     * as they always have.
     */
    fun legs(level: ServerLevel, center: Long): List<Leg>? {
        val piece = capital(level, center) ?: return null
        val roads = roads(level, center)
        if (roads.isEmpty()) return leg(level, piece, null)?.let { listOf(it) }
        val out = ArrayList<Leg>()
        var waiting = false
        for (road in roads) {
            val leg = leg(level, piece, road)
            if (leg != null) out.add(leg) else if (jobs.containsKey(center to road.id)) waiting = true
        }
        if (out.isEmpty()) return if (waiting) null else leg(level, piece, null)?.let { listOf(it) }
        return out
    }

    /** [road]'s leg into [piece] (the plan's own road for null): worked out, or started on now and null for the while. */
    private fun leg(level: ServerLevel, piece: CapitalPiece, road: Road?): Leg? {
        val key = piece.center.asLong() to (road?.id ?: -1)
        legs[key]?.let { return it }
        failed[key]?.let { if (level.gameTime - it in 0 until RETRY) return null }
        val job = jobs[key]
        if (job != null) {
            if (!job.isDone) return null
            jobs.remove(key)
            val leg = try {
                job.join()
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Couldn't work out the wagons' way into the capital at {} by road {}", piece.center, key.second, e)
                null
            }
            if (leg == null) {
                failed[key] = level.gameTime
                return null
            }
            legs[key] = leg
            return leg
        }
        val heart = piece.center
        val far = road?.otherEnd(heart)
        val points = road?.pointsFrom(far!!)?.toList()
        jobs[key] = CompletableFuture.supplyAsync({ build(piece, road?.id ?: -1, points, far) }, Util.backgroundExecutor())
        return null
    }

    // ---- Working a leg out ----

    private fun build(capital: CapitalPiece, road: Int, points: List<BlockPos>?, far: BlockPos?): Leg? {
        val plan = capital.plan
        val center = capital.center
        val gates = plan.outerGates.filter { it != plan.layout.harbour }.ifEmpty { plan.outerGates }
        val country = plan.outskirts.roads
        // The gate the road was laid to (as Capitals.wayOut picks it), or the main road's
        val gate = if (far != null) {
            gates.maxBy { side ->
                val d = capital.direction(side)
                d.stepX * (far.x - center.x).toDouble() + d.stepZ * (far.z - center.z).toDouble()
            }
        } else country.firstOrNull { it.main && it.side in gates }?.side ?: gates.first()
        val way = ArrayList<Vec3>()

        // The road, from just outside the village at its far end to the farm edge
        if (points != null) {
            val outside = points.takeWhile { !capital.reaches(it.x, it.z) }.map { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
            if (outside.size >= 2) {
                val total = (0 until outside.size - 1).sumOf { flat(outside[it], outside[it + 1]) }
                val from = if (total > DRIVEN + CLEAR) total - DRIVEN else minOf(CLEAR, total * 0.25)
                way.addAll(cut(outside, from))
            }
        }
        val farmFirst = way.size

        // The country road on that side, row by row from the farm edge in; or straight in across the farms
        val lane = country.firstOrNull { it.side == gate }
        if (lane != null && lane.rows > 0) {
            var i = lane.rows - 1
            while (i >= 0) {
                val p = lane.at(i, 0)
                way.add(Vec3.atBottomCenterOf(capital.world(p.x, lane.heights[i], p.z)))
                if (i == 0) break
                i = maxOf(0, i - ROW_STEP)
            }
        } else {
            way.add(Vec3.atBottomCenterOf(capital.standing(plan, plan.sidePos(gate, 0, plan.reach))))
        }
        way.add(Vec3.atBottomCenterOf(capital.standing(plan, plan.sidePos(gate, 0, plan.levelled))))
        val gateFirst = way.size

        // Through the gate to the stand
        val streets = plan.wayOut(gate).dropLast(2).reversed()
        var inside = 0
        for (p in streets) {
            way.add(Vec3.atBottomCenterOf(capital.standing(plan, p)))
            if (gate == Dir.S && plan.market.contains(p.x, p.z)) break
            if (plan.within(p.x, p.z) && ++inside >= 2) break
        }
        val clean = ArrayList<Vec3>()
        for (p in way) if (clean.isEmpty() || flat(clean.last(), p) >= 2.0) clean.add(p)
        if (clean.size < 2) return null
        val farmMark = clean.getOrNull(minOf(farmFirst, clean.size - 1)) ?: clean.first()
        val gateMark = way.getOrNull(minOf(gateFirst, way.size - 1)) ?: clean.last()
        val round = round(clean)
        val out = ArrayList<Caravans.Point>(round.size)
        var along = 0.0
        for ((k, p) in round.withIndex()) {
            if (k > 0) along += flat(round[k - 1], p)
            out.add(Caravans.Point(p, along))
        }
        val leg0 = Leg(center.asLong(), road, gate, out, 0.0, 0.0, capital.bell, VillageNames.of(capital.boardAnchor), far ?: BlockPos.containing(out.first().pos))
        val farms = if (points == null) 0.0 else leg0.nearest(farmMark.x, farmMark.z)
        val gateAt = leg0.nearest(gateMark.x, gateMark.z)
        return Leg(center.asLong(), road, gate, out, farms, gateAt, leg0.bell, leg0.name, leg0.far)
    }

    /** [points] from [from] blocks along on. */
    private fun cut(points: List<Vec3>, from: Double): List<Vec3> {
        var along = 0.0
        for (k in 0 until points.size - 1) {
            val step = flat(points[k], points[k + 1])
            if (along + step >= from) {
                val t = if (step <= 1e-6) 0.0 else (from - along) / step
                return listOf(points[k].lerp(points[k + 1], t)) + points.subList(k + 1, points.size)
            }
            along += step
        }
        return points.takeLast(2)
    }

    /** [points] with their corners rounded (Chaikin's corner cutting, [ROUNDING] times), the ends kept where they are. */
    private fun round(points: List<Vec3>): List<Vec3> {
        var p = points
        repeat(ROUNDING) {
            if (p.size < 3) return p
            val next = ArrayList<Vec3>(p.size * 2)
            next.add(p.first())
            for (k in 0 until p.size - 1) {
                next.add(p[k].lerp(p[k + 1], 0.25))
                next.add(p[k].lerp(p[k + 1], 0.75))
            }
            next.add(p.last())
            p = next
        }
        return p
    }

    private fun flat(a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    // ---- The band on the road ----

    /**
     * Settles, once, whether a band camps in each cell [leg] passes near enough for its band to work it (see
     * [OutlawCamps.settle]), so its road's band is known before anyone wanders near.
     */
    fun settle(level: ServerLevel, leg: Leg) {
        if (leg.settled) return
        leg.settled = true
        val camps = OutlawCamps.get(level)
        var along = 0.0
        while (along <= leg.length) {
            val p = leg.at(along)
            for (dx in intArrayOf(-1, 0, 1)) for (dz in intArrayOf(-1, 0, 1)) {
                camps.settle(level, (p.x + dx * BAND_REACH).toInt(), (p.z + dz * BAND_REACH).toInt())
            }
            along += BAND_REACH / 2
        }
    }

    /** The band that works [leg]'s road: the nearest at large camped within [BAND_REACH] of it (its start, for the plan's own road), or null. */
    fun band(level: ServerLevel, leg: Leg): OutlawBand? = OutlawCamps.get(level).bands.asSequence()
        .filter { !it.slain }
        .map { it to reach(leg, it) }
        .filter { it.second <= BAND_REACH }
        .minByOrNull { it.second }?.first

    /** How far [band]'s camp lies from [leg]. */
    fun reach(leg: Leg, band: OutlawBand): Double = if (leg.road < 0) {
        val s = leg.start
        val dx = band.pos.x + 0.5 - s.x
        val dz = band.pos.z + 0.5 - s.z
        sqrt(dx * dx + dz * dz)
    } else leg.distance(band.pos.x + 0.5, band.pos.z + 0.5)

    /** Where on [leg] its band strikes: where it would ([natural]), or out of sight of a tower's watch if there's somewhere out of it (economy/works/WorksWatch). */
    fun strikeAt(leg: Leg, band: OutlawBand): Double = io.github.vaspyyy.guildmark.economy.works.WorksWatch.aside(leg, natural(leg, band), STRIKE_START, leg.farms - STRIKE_FARMS)

    /** Where on [leg] its band would strike with no watch to keep out of: the spot nearest its camp, kept [STRIKE_START] from the start and [STRIKE_FARMS] from the farms; 0 for the plan's own road, before the wagon is ever seen. */
    fun natural(leg: Leg, band: OutlawBand): Double {
        if (leg.road < 0) return 0.0
        val near = leg.nearest(band.pos.x + 0.5, band.pos.z + 0.5)
        val last = leg.farms - STRIKE_FARMS
        if (last < STRIKE_START) return max(0.0, minOf(leg.farms * 0.5, STRIKE_START))
        return near.coerceIn(STRIKE_START, last)
    }

    // ---- A road for a capital that has none (open question 1) ----

    /**
     * With [PAVE_ROADS], asks the road network for a road from [bell], the heart of a capital with no road, to its
     * nearest village with none to it yet, paved as the land loads (Caravans asks every few days until there's one).
     * [done] hears whether one was found.
     */
    fun survey(level: ServerLevel, bell: BlockPos, done: (Boolean) -> Unit) {
        if (!PAVE_ROADS) return done(false)
        val network = RoadNetwork.get(level.server.overworld())
        val village = Expeditions.findUnconnectedVillage(level, bell, network) ?: return done(false)
        network.survey(level, bell, village, build = false) { road -> done(road != null) }
    }
}
