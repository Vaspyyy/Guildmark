package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The lie of an island's land: a grid over everything it reaches (its shallows and the slopes down to the sea bed
 * too), holding for every column how high the ground stands over the sea, how far it is from the coast, and how
 * much of a cliff the coast is there. Worked out once from the spec, then shaped further by whatever is built on it
 * (see [flatten]), and read by every chunk that draws a share of it.
 */
class Terrain(val spec: IslandSpec) {
    val r = spec.radius.toDouble()

    /** How far from the middle the grid reaches each way. */
    val bound: Int = reach(spec.radius)
    val size = 2 * bound + 1
    val x0 = spec.cx - bound
    val z0 = spec.cz - bound

    private val dice = Dice(spec.seed xor 0x7E44A1L)

    /** Which way the island's length runs, in radians (0 east, pi/2 south). */
    val angle = dice.range(0.0, 2 * PI)

    /** How wide it is for its length. */
    val aspect = when (spec.form) {
        Form.RIDGE -> dice.range(0.36, 0.5)
        Form.TWIN -> dice.range(0.42, 0.56)
        Form.ATOLL -> dice.range(0.78, 1.0)
        Form.REEF -> dice.range(0.5, 0.85)
        Form.STACKS -> dice.range(0.6, 1.0)
        else -> dice.range(0.62, 0.95)
    }

    /** The heading the wind mostly blows from, in radians: the cliffs face into it and the trees lean away from it. */
    val windFrom = dice.range(0.0, 2 * PI)

    /** The heading of the cove's or bay's mouth from the middle (COVE and BAY), in radians. */
    val mouth = dice.range(0.0, 2 * PI)

    /** How high the hills rise in the middle, and the cliffs at the coast. */
    val hills: Double = when (spec.form) {
        Form.ATOLL -> 1.6
        Form.REEF -> 0.0
        Form.STACKS -> 0.0
        Form.RIDGE -> (r * 0.42).coerceIn(6.0, 22.0) * dice.range(0.8, 1.25)
        Form.COVE -> (r * 0.42).coerceIn(12.0, 20.0) * dice.range(0.9, 1.15)
        else -> (r * 0.34).coerceIn(4.0, 18.0) * dice.range(0.65, 1.3)
    } * (if (spec.kind == IslandKind.LIGHTHOUSE) 0.45 else 1.0)
    val cliffTop: Double = when (spec.kind) {
        IslandKind.LIGHTHOUSE -> dice.range(9.0, 14.0)
        IslandKind.COVE -> dice.range(10.0, 15.0)
        else -> (4.0 + r * 0.17).coerceAtMost(13.0) * dice.range(0.75, 1.25)
    }
    private val rockBias = when (spec.rock) {
        Rock.GRANITE -> 0.04
        Rock.LIMESTONE -> 0.08
        Rock.BASALT -> 0.06
        Rock.SLATE -> 0.05
        Rock.SANDSTONE -> -0.07
    } + when (spec.climate) {
        Climate.WARM -> -0.06
        Climate.COLD, Climate.FROZEN -> 0.05
        else -> 0.0
    } + dice.range(-0.08, 0.08)

    private val sWarpU = Noise.seed(spec.seed, 1)
    private val sWarpV = Noise.seed(spec.seed, 2)
    private val sCoast = Noise.seed(spec.seed, 3)
    private val sCliff = Noise.seed(spec.seed, 4)
    private val sHills = Noise.seed(spec.seed, 5)
    private val sDetail = Noise.seed(spec.seed, 6)
    private val sBeach = Noise.seed(spec.seed, 7)
    private val sEdge = Noise.seed(spec.seed, 8)
    private val sUnder = Noise.seed(spec.seed, 9)
    private val sReef = Noise.seed(spec.seed, 10)

    /** The pillars of a STACKS island, and the sea stacks standing off other islands' cliffs: x, z, radius, height. */
    class Stack(val x: Double, val z: Double, val radius: Double, val height: Double)

    val stacks = ArrayList<Stack>()

    /** Room left open in the rock: under an arch, a sea cave. */
    val carves = ArrayList<Carve>()

    /** Planned ground height over the sea per column: the ground's top block is at sea + round(h) - 1. */
    val height = FloatArray(size * size)

    /** Signed distance to the coast in blocks: above 0 inland, below 0 out to sea. */
    val dist = FloatArray(size * size)

    /** 0 to 1: how much of a cliff the coast is, here. */
    val cliff = FloatArray(size * size)

    /** Shallow water inside an atoll's ring. */
    val lagoon = BooleanArray(size * size)

    /** A TWIN's bar of sand. */
    val bar = BooleanArray(size * size)

    // A STACKS island's platform (in the island's frame) and arch, set by layStacks
    private var platform = false
    private var platformU = 0.0
    private var platformV = 0.0
    private var arch: Pair<Stack, Stack>? = null

    /** A COVE's sea cave, set by layCave. */
    var cave: Carve.Cave? = null
        private set

    init {
        if (spec.form == Form.STACKS) layStacks()
        val land = FloatArray(size * size)
        for (i in 0 until size) for (j in 0 until size) {
            val px = (i - bound).toDouble()
            val pz = (j - bound).toDouble()
            land[i * size + j] = landness(px, pz).toFloat()
            cliff[i * size + j] = cliffness(px, pz).toFloat()
        }
        distances(land)
        for (i in 0 until size) for (j in 0 until size) height[i * size + j] = shape(i, j, land[i * size + j].toDouble()).toFloat()
        if (spec.form == Form.STACKS) archCarve()
        if (spec.form != Form.STACKS && spec.form != Form.REEF && spec.form != Form.ATOLL) layOffshoreStacks()
        if (spec.form == Form.COVE) layCave()
    }

    fun inside(x: Int, z: Int): Boolean = x - x0 in 0 until size && z - z0 in 0 until size

    companion object {
        /** How far an island of [radius] reaches from its middle, its shallows and the slopes down to the sea bed with it. */
        fun reach(radius: Int): Int = (radius * 1.4 + 38).toInt()

        /** How high anything on an island stands over the sea at most: its tallest stack, a tree on it, a lighthouse's lamp. */
        const val HEADROOM = 72

        /** How far under the sea its slopes may run down to meet the sea bed. */
        const val DEPTH = 64
    }

    private fun index(x: Int, z: Int): Int = (x - x0) * size + (z - z0)

    /** The planned height over the sea at a world column (deep water outside the grid). */
    fun h(x: Int, z: Int): Double = if (inside(x, z)) height[index(x, z)].toDouble() else -60.0

    /** The y of the first block over the ground (air or water) in a world column. */
    fun top(x: Int, z: Int): Int = spec.sea + h(x, z).roundToInt()

    fun distance(x: Int, z: Int): Double = if (inside(x, z)) dist[index(x, z)].toDouble() else -99.0

    fun cliffAt(x: Int, z: Int): Double = if (inside(x, z)) cliff[index(x, z)].toDouble() else 0.0

    fun inLagoon(x: Int, z: Int): Boolean = inside(x, z) && lagoon[index(x, z)]

    fun onBar(x: Int, z: Int): Boolean = inside(x, z) && bar[index(x, z)]

    /** Dry land: the ground's top block stands over the sea's surface. */
    fun dry(x: Int, z: Int): Boolean = top(x, z) > spec.sea

    /** How much the ground drops to the lowest of the four columns round it. */
    fun drop(x: Int, z: Int): Int {
        val t = top(x, z)
        return max(max(t - top(x + 1, z), t - top(x - 1, z)), max(t - top(x, z + 1), t - top(x, z - 1)))
    }

    /** How much the ground rises to the highest of the four columns round it. */
    fun rise(x: Int, z: Int): Int {
        val t = top(x, z)
        return max(max(top(x + 1, z) - t, top(x - 1, z) - t), max(top(x, z + 1) - t, top(x, z - 1) - t))
    }

    /** Sets the planned height in a world column (for levelling a site). */
    fun set(x: Int, z: Int, h: Double) {
        if (inside(x, z)) height[index(x, z)] = h.toFloat()
    }

    /**
     * Levels the ground in a rectangle to stand at [level] blocks over the sea, blending into the land round it over
     * [blend] blocks. Ground under water is raised to it too, so a site can reach out over the shallows.
     */
    fun flatten(xa: Int, za: Int, xb: Int, zb: Int, level: Int, blend: Int = 4) {
        for (x in xa - blend..xb + blend) for (z in za - blend..zb + blend) {
            if (!inside(x, z)) continue
            val dx = max(0, max(xa - x, x - xb))
            val dz = max(0, max(za - z, z - zb))
            val d = hypot(dx.toDouble(), dz.toDouble())
            if (d > blend) continue
            val t = if (blend == 0) 1.0 else 1.0 - smooth(0.0, blend + 0.5, d)
            set(x, z, lerp(h(x, z), level.toDouble(), t))
        }
    }

    /** The island's own frame: along its length and across it, both in units of [r] (across stretched by [aspect]). */
    private fun frame(px: Double, pz: Double): Pair<Double, Double> {
        val c = cos(angle)
        val s = sin(angle)
        return Pair((px * c + pz * s) / r, (-px * s + pz * c) / (r * aspect))
    }

    /** Above 0 on the island's land (or, on a reef, its shallows), below 0 at sea: about one unit per [r] blocks. */
    private fun landness(px: Double, pz: Double): Double {
        val (u, v) = frame(px, pz)
        val wu = u + 0.30 * (Noise.fbm(u * 1.4 + 9.1, v * 1.4, sWarpU, 3) * 2 - 1)
        val wv = v + 0.30 * (Noise.fbm(u * 1.4, v * 1.4 + 4.7, sWarpV, 3) * 2 - 1)
        val coast = 0.17 * (Noise.fbm(wu * 3.1, wv * 3.1, sCoast, 4) * 2 - 1)
        val d = sqrt(wu * wu + wv * wv)
        return when (spec.form) {
            Form.BLOB, Form.RIDGE -> 1 - d + coast
            Form.TWIN -> {
                val (lobes, bar) = twin(wu, wv)
                max(lobes + coast, bar)
            }
            Form.ATOLL -> {
                // A ring round a lagoon, broken by a channel where the reef opened to the sea
                val ring = 0.17 - abs(d - 0.78) + coast * 0.6
                val gap = angleBetween(atan2(wv, wu), mouth)
                ring - 0.45 * exp(-(gap / 0.22).pow(2))
            }
            Form.REEF -> 1 - d + coast * 1.3
            Form.STACKS -> {
                // Only the platform some stacks stand on; the stacks themselves are laid in [shape]
                if (platform) 0.36 - hypot(wu - platformU, wv - platformV) + coast * 0.5 else -1.0
            }
            Form.COVE -> {
                val (along, across) = turned(wu, wv, mouth)
                // A narrow inlet cut in from the coast nearly to the middle
                val inlet = 1.4 * exp(-(across / 0.11).pow(2)) * smooth(0.04, 0.32, along)
                1 - d + coast - inlet
            }
            Form.BAY -> {
                val (along, across) = turned(wu, wv, mouth)
                // A round basin in under the hills, opening to the sea through a narrower mouth between two arms of land
                val basin = 1.1 * (1 - smooth(0.16, 0.34, hypot((along - 0.52) * 1.15, across)))
                val strait = 1.1 * exp(-(across / 0.15).pow(2)) * smooth(0.5, 0.82, along)
                1 - d + coast - max(basin, strait)
            }
        }
    }

    /** A TWIN's two bodies of land, and the bar of sand between them (a few blocks wide, curving a little). */
    private fun twin(wu: Double, wv: Double): Pair<Double, Double> {
        val a = 1 - hypot((wu - 0.6) / 0.5, wv / 0.9)
        val b = 1 - hypot((wu + 0.62) / 0.42, wv / 0.82)
        val bar = if (abs(wu) < 0.7) (0.09 - abs(wv + 0.06 * sin(wu * 4))) * 2.2 else -1.0
        return Pair(max(a, b), bar)
    }

    /** Is this point on a TWIN's sand bar rather than on either body of land? */
    private fun barAt(px: Double, pz: Double): Boolean {
        val (u, v) = frame(px, pz)
        val wu = u + 0.30 * (Noise.fbm(u * 1.4 + 9.1, v * 1.4, sWarpU, 3) * 2 - 1)
        val wv = v + 0.30 * (Noise.fbm(u * 1.4, v * 1.4 + 4.7, sWarpV, 3) * 2 - 1)
        val (lobes, bar) = twin(wu, wv)
        return bar > 0 && bar > lobes + 0.17 * (Noise.fbm(wu * 3.1, wv * 3.1, sCoast, 4) * 2 - 1)
    }

    /** (along, across) a heading from the middle, for a point in the island's frame. */
    private fun turned(u: Double, v: Double, heading: Double): Pair<Double, Double> {
        // The heading is the world's; bring it into the island's frame first
        val h = heading - angle
        return Pair(u * cos(h) + v * sin(h), -u * sin(h) + v * cos(h))
    }

    private fun angleBetween(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return abs(d)
    }

    private fun cliffness(px: Double, pz: Double): Double {
        when (spec.form) {
            Form.ATOLL, Form.REEF -> return 0.0
            Form.STACKS -> return 1.0
            else -> {}
        }
        val n = Noise.fbm(px / (r * 0.85) + 2.1, pz / (r * 0.85) - 7.3, sCliff, 2)
        val facing = atan2(pz, px)
        val windward = cos(facing - windFrom)
        var c = n + 0.16 * windward + rockBias
        when (spec.kind) {
            IslandKind.LIGHTHOUSE -> c += 0.32 - 0.6 * exp(-(angleBetween(facing, mouth) / 0.45).pow(2))
            else -> {}
        }
        when (spec.form) {
            Form.COVE -> c += 0.45 * exp(-(angleBetween(facing, mouth) / 0.7).pow(2))
            Form.BAY -> c -= 0.5 * exp(-(angleBetween(facing, mouth) / 0.8).pow(2))
            Form.RIDGE -> c += 0.05
            else -> {}
        }
        if (spec.radius < 16 && spec.kind == IslandKind.ISLE) c -= 0.06
        return smooth(0.5, 0.62, c)
    }

    /** Distances to the coast, both ways, by two sweeps over the grid. */
    private fun distances(land: FloatArray) {
        val big = 1e6f
        val toWater = FloatArray(size * size) { if (land[it] > 0) big else 0f }
        val toLand = FloatArray(size * size) { if (land[it] > 0) 0f else big }
        // Past the grid's edge is open sea: the land's distance to water counts it, the sea's to land doesn't
        sweep(toWater, true)
        sweep(toLand, false)
        for (k in 0 until size * size) {
            dist[k] = if (land[k] > 0) toWater[k] - 0.5f else -(toLand[k] - 0.5f)
        }
    }

    private fun sweep(f: FloatArray, edgeIsWater: Boolean) {
        val d1 = 1f
        val d2 = 1.4142f
        for (i in 0 until size) for (j in 0 until size) {
            var v = f[i * size + j]
            if (v == 0f) continue
            if (i > 0) v = min(v, f[(i - 1) * size + j] + d1)
            if (j > 0) v = min(v, f[i * size + j - 1] + d1)
            if (i > 0 && j > 0) v = min(v, f[(i - 1) * size + j - 1] + d2)
            if (i > 0 && j < size - 1) v = min(v, f[(i - 1) * size + j + 1] + d2)
            if (edgeIsWater && (i == 0 || j == 0 || i == size - 1 || j == size - 1)) v = min(v, 1f)
            f[i * size + j] = v
        }
        for (i in size - 1 downTo 0) for (j in size - 1 downTo 0) {
            var v = f[i * size + j]
            if (v == 0f) continue
            if (i < size - 1) v = min(v, f[(i + 1) * size + j] + d1)
            if (j < size - 1) v = min(v, f[i * size + j + 1] + d1)
            if (i < size - 1 && j < size - 1) v = min(v, f[(i + 1) * size + j + 1] + d2)
            if (i < size - 1 && j > 0) v = min(v, f[(i + 1) * size + j - 1] + d2)
            f[i * size + j] = v
        }
    }

    /** The height over the sea of the column at grid [i], [j], whose landness is [l]. */
    private fun shape(i: Int, j: Int, l: Double): Double {
        val px = (i - bound).toDouble()
        val pz = (j - bound).toDouble()
        val k = i * size + j
        val d = dist[k].toDouble()
        val c = cliff[k].toDouble()
        return when (spec.form) {
            Form.STACKS -> stacksHeight(px, pz, d)
            Form.REEF -> reefHeight(px, pz, d)
            else -> {
                if (spec.form == Form.ATOLL && d < 0) {
                    val (u, v) = frame(px, pz)
                    if (hypot(u, v) < 0.64) {
                        lagoon[k] = true
                        return -(0.9 + min(3.2, -d * 0.32) + 0.8 * (Noise.fbm(px / 7, pz / 7, sUnder, 2) - 0.5))
                    }
                }
                if (spec.form == Form.TWIN && d >= 0 && barAt(px, pz)) {
                    // The bar stands a block or two out of the water, all the way across
                    bar[k] = true
                    cliff[k] = 0f
                    return 1.2 + 0.7 * Noise.fbm(px / 6, pz / 6, sBeach, 2)
                }
                if (d >= 0) landHeight(px, pz, d, c) else underHeight(px, pz, -d, c)
            }
        }
    }

    private fun landHeight(px: Double, pz: Double, d: Double, c: Double): Double {
        val hillT = smooth(0.0, r * 0.55, d)
        var hill = hills * hillT.pow(1.3) * (0.5 + 0.9 * Noise.fbm(px / (r * 0.45), pz / (r * 0.45), sHills, 3))
        hill += 2.4 * (Noise.fbm(px / 9, pz / 9, sDetail, 3) - 0.5) * hillT
        if (spec.form == Form.RIDGE) {
            val (_, v) = frame(px, pz)
            hill += hills * 0.55 * Noise.ridged(px / (r * 0.35), pz / (r * 0.35), sHills + 1, 2) * (1 - smooth(0.0, 0.7, abs(v))) * hillT
        }
        val beachW = 3.0 + 5.0 * Noise.fbm(px / 14, pz / 14, sBeach, 2)
        val beachTop = 1.1 + 1.3 * Noise.fbm(px / 11 + 3, pz / 11, sBeach + 1, 2)
        val beach = max(beachTop * smooth(0.0, beachW, d), hill * smooth(beachW * 0.4, beachW * 1.7, d))
        val edge = 0.9 + 1.6 * Noise.fbm(px / 6, pz / 6, sEdge, 2)
        val top = cliffTop * (0.72 + 0.56 * Noise.fbm(px / 13, pz / 13, sEdge + 1, 2))
        val cliffLand = max(top * smooth(0.0, edge, d) * (1 - 0.45 * smooth(8.0, 22.0, d)), hill + top * 0.25 * (1 - smooth(6.0, 18.0, d)))
        return lerp(beach, cliffLand, c)
    }

    private fun underHeight(px: Double, pz: Double, sd: Double, c: Double): Double {
        val warm = spec.climate == Climate.WARM || spec.climate == Climate.LUKEWARM
        val shelf = lerp(if (warm) 10.0 else 6.5, 1.5, c)
        val steep = lerp(if (warm) 0.22 else 0.32, 1.3, c)
        val deep = 0.7 + steep * min(sd, shelf) + 1.05 * max(0.0, sd - shelf)
        return -(deep + 1.6 * (Noise.fbm(px / 8, pz / 8, sUnder, 3) - 0.5) * smooth(0.0, 6.0, sd))
    }

    private fun reefHeight(px: Double, pz: Double, d: Double): Double {
        if (d < 0) return -(2.0 + 0.95 * -d + 1.2 * (Noise.fbm(px / 8, pz / 8, sUnder, 2) - 0.5))
        val flat = -1.9 + 1.5 * (Noise.fbm(px / 9, pz / 9, sReef, 3) - 0.5) - 0.9 * (1 - smooth(0.0, 5.0, d))
        val crest = Noise.ridged(px / 7.0, pz / 7.0, sReef + 1, 2)
        val rocks = if (crest > 0.58) flat + (crest - 0.58) * 13.0 else flat
        return max(flat, rocks)
    }

    // ---- stacks

    private fun layStacks() {
        val n = dice.int(4, 7)
        var tries = 0
        while (stacks.size < n && tries++ < 200) {
            val a = dice.range(0.0, 2 * PI)
            val rr = sqrt(dice.next()) * r * 0.75
            val x = cos(a) * rr
            val z = sin(a) * rr * aspect
            val radius = dice.range(2.4, 5.6)
            if (stacks.any { hypot(it.x - x, it.z - z) < it.radius + radius + 2.5 }) continue
            stacks.add(Stack(x, z, radius, dice.range(9.0, 23.0)))
        }
        platform = dice.chance(0.5)
        if (platform) {
            val big = stacks.maxBy { it.radius }
            val (u, v) = frame(big.x, big.z)
            platformU = u
            platformV = v
        }
        // An arch: the nearest two stacks, bridged high up, open beneath
        var best: Pair<Stack, Stack>? = null
        var bestGap = 1e9
        for (a in stacks) for (b in stacks) {
            if (a === b) continue
            val gap = hypot(a.x - b.x, a.z - b.z) - a.radius - b.radius
            if (gap in 3.0..9.0 && gap < bestGap) {
                bestGap = gap
                best = Pair(a, b)
            }
        }
        if (best != null && dice.chance(0.8)) arch = best
    }

    private fun stacksHeight(px: Double, pz: Double, d: Double): Double {
        var h = if (d >= 0) 0.4 + 0.7 * Noise.fbm(px / 5, pz / 5, sReef, 2) else underHeight(px, pz, -d, 0.6)
        for (s in stacks) {
            val wobble = 1 + 0.22 * (Noise.fbm(px / 2.5 + s.x, pz / 2.5, sEdge, 2) - 0.5) * 2
            val inner = s.radius * wobble - hypot(px - s.x, pz - s.z)
            if (inner > -1.5) {
                val top = s.height + min(1.5, max(0.0, inner) * 0.35)
                // Sheer sides with a little scree where they meet the sea
                h = max(h, top * smooth(-0.4, 0.9, inner))
            }
        }
        val pair = arch
        if (pair != null) {
            val (a, b) = pair
            val ax = b.x - a.x
            val az = b.z - a.z
            val len = hypot(ax, az)
            val t = ((px - a.x) * ax + (pz - a.z) * az) / (len * len)
            if (t in 0.0..1.0) {
                val across = abs((px - a.x) * az - (pz - a.z) * ax) / len
                if (across < 1.7) h = max(h, min(a.height, b.height) * 0.78)
            }
        }
        return h
    }

    /** The arch's opening, once the heights are known. */
    private fun archCarve() {
        val (a, b) = arch ?: return
        val bridge = min(a.height, b.height) * 0.78
        carves.add(Carve.Arch(spec.cx + a.x, spec.cz + a.z, spec.cx + b.x, spec.cz + b.z, spec.sea - 4.0, spec.sea + bridge - 2.6, 2.6))
    }

    /** Small stacks standing off the island's cliffs, a few blocks out to sea. */
    private fun layOffshoreStacks() {
        val want = when {
            spec.kind == IslandKind.LIGHTHOUSE -> 2
            else -> dice.int(0, (r / 12).toInt() + 1)
        }
        var tries = 0
        var laid = 0
        while (laid < want && tries++ < 300) {
            val i = dice.int(size)
            val j = dice.int(size)
            val k = i * size + j
            val d = dist[k]
            if (d > -3f || d < -8f || cliff[k] < 0.6f) continue
            val px = (i - bound).toDouble()
            val pz = (j - bound).toDouble()
            val radius = dice.range(1.3, 2.6)
            if (stacks.any { hypot(it.x - px, it.z - pz) < it.radius + radius + 4 }) continue
            val s = Stack(px, pz, radius, dice.range(3.0, max(4.0, cliffTop * 0.9)))
            stacks.add(s)
            laid++
            val reach = (radius + 2).toInt()
            for (x in -reach..reach) for (z in -reach..reach) {
                val ii = i + x
                val jj = j + z
                if (ii !in 0 until size || jj !in 0 until size) continue
                val inner = radius * (1 + 0.2 * (Noise.fbm((px + x) / 2.0, (pz + z) / 2.0, sEdge, 2) - 0.5) * 2) - hypot(x.toDouble(), z.toDouble())
                if (inner > -0.5) {
                    val kk = ii * size + jj
                    height[kk] = max(height[kk].toDouble(), s.height * smooth(-0.4, 0.8, inner)).toFloat()
                    cliff[kk] = 1f
                }
            }
        }
    }

    // ---- the cove's sea cave

    private fun layCave() {
        // Walk in from the mouth along the inlet until the land closes over it: the cave goes on from there
        val dx = cos(mouth)
        val dz = sin(mouth)
        var start = -1.0
        for (step in 0..(r * 1.2).toInt()) {
            val s = r * 1.2 - step
            val x = spec.cx + (dx * s).roundToInt()
            val z = spec.cz + (dz * s).roundToInt()
            if (inside(x, z) && h(x, z) > 2.5) {
                start = s
                break
            }
        }
        if (start < 0) return
        val mouthX = spec.cx + dx * (start + 1.5)
        val mouthZ = spec.cz + dz * (start + 1.5)
        val depthIn = (start * 0.55).coerceIn(9.0, 18.0)
        val endX = spec.cx + dx * (start - depthIn)
        val endZ = spec.cz + dz * (start - depthIn)
        val roof = min(6.5, cliffTop - 3.5)
        val c = Carve.Cave(mouthX, mouthZ, endX, endZ, spec.sea - 3.0, spec.sea + roof, 3.2, dice.range(6.0, 7.5), spec.sea.toDouble())
        carves.add(c)
        cave = c
        // Keep the rock over it thick enough
        val reach = (c.chamber + 3).toInt()
        for (x in -reach..reach) for (z in -reach..reach) {
            val wx = (endX + x).roundToInt()
            val wz = (endZ + z).roundToInt()
            if (!inside(wx, wz) || hypot(x.toDouble(), z.toDouble()) > reach) continue
            set(wx, wz, max(h(wx, wz), roof + c.chamber * 0.6 + 3.0))
        }
    }
}

/** A space left open in an island's rock: the sea fills it below the surface, air above. */
sealed class Carve {
    abstract fun contains(x: Double, y: Double, z: Double): Boolean

    /** The columns it might reach. */
    abstract val minX: Int
    abstract val maxX: Int
    abstract val minZ: Int
    abstract val maxZ: Int

    /** The opening under an arch from stack [ax], [az] to stack [bx], [bz]: highest in the middle, closing down into the stacks. */
    class Arch(val ax: Double, val az: Double, val bx: Double, val bz: Double, val bottom: Double, val crown: Double, val half: Double) : Carve() {
        private val len = hypot(bx - ax, bz - az)
        override val minX = floor(min(ax, bx) - half - 1).toInt()
        override val maxX = floor(max(ax, bx) + half + 1).toInt()
        override val minZ = floor(min(az, bz) - half - 1).toInt()
        override val maxZ = floor(max(az, bz) + half + 1).toInt()

        override fun contains(x: Double, y: Double, z: Double): Boolean {
            val t = ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / (len * len)
            if (t < 0.12 || t > 0.88) return false
            val across = abs((x - ax) * (bz - az) - (z - az) * (bx - ax)) / len
            if (across > half + 0.5) return false
            val m = (t - 0.5) / 0.38
            val ceiling = crown - (crown - bottom) * 0.55 * m * m
            return y >= bottom && y <= ceiling
        }
    }

    /**
     * A sea cave: a tunnel from [mx], [mz] at the back of a cove in to a chamber at [ex], [ez], its roof at [roof], the
     * tunnel [half] blocks either side of its middle, the chamber [chamber] blocks round. [ledge] is the height of the
     * chamber's ledge, where whoever uses it lands their boats.
     */
    class Cave(
        val mx: Double, val mz: Double, val ex: Double, val ez: Double,
        val bottom: Double, val roof: Double, val half: Double, val chamber: Double, val ledge: Double,
    ) : Carve() {
        private val len = hypot(ex - mx, ez - mz)
        override val minX = floor(min(mx, ex) - chamber - 2).toInt()
        override val maxX = floor(max(mx, ex) + chamber + 2).toInt()
        override val minZ = floor(min(mz, ez) - chamber - 2).toInt()
        override val maxZ = floor(max(mz, ez) + chamber + 2).toInt()

        override fun contains(x: Double, y: Double, z: Double): Boolean {
            if (y < bottom) return false
            // The chamber: a low dome
            val dc = hypot(x - ex, z - ez)
            if (dc < chamber) {
                val ceiling = roof + (chamber - dc) * 0.45
                if (y <= ceiling) return true
            }
            val t = ((x - mx) * (ex - mx) + (z - mz) * (ez - mz)) / (len * len)
            if (t < -0.15 || t > 1.0) return false
            val across = abs((x - mx) * (ez - mz) - (z - mz) * (ex - mx)) / len
            // An arched tunnel, a little taller where it opens to the cove
            val w = half * (1 + 0.25 * (1 - t))
            if (across > w) return false
            val top = roof + (1 - t) * 1.0 - (across / w) * (across / w) * 2.2
            return y <= top
        }
    }
}
