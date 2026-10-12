package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How big a capital is. [outer] and [inner] are the outer faces of its town walls, out from the middle
 * along the axes; the glacis slopes from the outer wall's foot down (or up) to the land, and the farms
 * and suburbs reach [outskirts] beyond it. [spread] is the most the lower town's land may rise and fall
 * before it's levelled, and [chance] how many capitals in a hundred set out to be this big.
 */
enum class Tier(val outer: Int, val inner: Int, val glacis: Int, val outskirts: Int, val spread: Int, val chance: Int) {
    MEDIUM(86, 44, 12, 40, 12, 40),
    LARGE(116, 56, 12, 48, 14, 35),
    GRAND(146, 70, 14, 56, 16, 25);

    /** Out to the foot of the glacis: everything nearer is the city's own ground. */
    val levelled: Int get() = outer + glacis
    /** Out to the edge of the farms. */
    val reach: Int get() = levelled + outskirts
}

/** The land as the world will generate it, for choosing where a capital stands. */
interface Ground {
    /** The surface's height at a world column: the first block down from the sky that isn't air (water counts). */
    fun height(x: Int, z: Int): Int

    /** Whether a capital may have its middle here, at standing height [y] (whether the country suits one). */
    fun fits(x: Int, z: Int, y: Int): Boolean

    /** Whether water stands over the land at a column whose surface is at [surface] (a sea, lake, river or marsh). */
    fun wet(x: Int, z: Int, surface: Int): Boolean = surface <= seaLevel + 1

    val seaLevel: Int
}

/**
 * Where a capital stands and how. [ground] is the lower town's standing height; the old town stands
 * [upper] above it and the citadel [citadel] above that. [turn] is how many quarter turns clockwise the
 * city's own frame (citadel to the north, main gate to the south) is turned in the world, and [harbour]
 * the side, east or west in that frame, that opens onto water.
 */
class Site(
    val x: Int,
    val z: Int,
    val tier: Tier,
    val ground: Int,
    val upper: Int,
    val citadel: Int,
    val turn: Int,
    val harbour: Dir?,
    val score: Double,
) {
    override fun toString(): String = "$tier at $x,$z y=$ground +$upper +$citadel turn=$turn harbour=$harbour score=${"%.1f".format(score)}"
}

/**
 * Looks round a spot for the best land for a capital: dry, open and level enough for the lower town, and
 * better still where the land rises toward the middle, so the old town and the citadel stand on a hill
 * or a plateau. Heights are asked of [ground] only as needed and kept, so trying several sizes and
 * places costs little more than trying one.
 */
class Survey(private val ground: Ground) {
    private val heights = HashMap<Long, Int>()

    /** Why candidates were turned down, for tuning. */
    val rejected = HashMap<String, Int>()

    fun height(x: Int, z: Int): Int = heights.getOrPut((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)) { ground.height(x, z) }

    private val wets = HashMap<Long, Boolean>()

    /** Water at a column whose surface is [h]: only low columns are asked about. */
    private fun wet(x: Int, z: Int, h: Int): Boolean =
        h <= ground.seaLevel + 1 && wets.getOrPut((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)) { ground.wet(x, z, h) }

    /**
     * The sites round ([x0], [z0]), best first: as big as [random] says this capital wants to be, then
     * smaller. Worked out lazily, so a caller that takes the first site it can use pays for no more.
     */
    fun sites(x0: Int, z0: Int, random: Random): Sequence<Site> = sequence {
        val roll = random.nextInt(100)
        val wanted = when {
            roll < Tier.GRAND.chance -> Tier.GRAND
            roll < Tier.GRAND.chance + Tier.LARGE.chance -> Tier.LARGE
            else -> Tier.MEDIUM
        }
        for (tier in Tier.entries.reversed()) {
            if (tier > wanted) continue
            // A first look on a coarse grid, then a closer one at the most promising few
            val coarse = ArrayList<Pair<Int, Int>>()
            val scores = HashMap<Pair<Int, Int>, Double>()
            for (i in -SHIFT_STEPS..SHIFT_STEPS) for (j in -SHIFT_STEPS..SHIFT_STEPS) {
                val cx = x0 + i * COARSE
                val cz = z0 + j * COARSE
                val score = glance(tier, cx, cz) ?: continue
                val at = cx to cz
                coarse.add(at)
                scores[at] = score - sqrt((i * i + j * j).toDouble()) * NEAR_WEIGHT
            }
            coarse.sortByDescending { scores[it] }
            for ((cx, cz) in coarse.take(CLOSE_LOOKS)) {
                val site = look(tier, cx, cz, sqrt(((cx - x0) * (cx - x0) + (cz - z0) * (cz - z0)).toDouble())) ?: continue
                yield(site)
            }
        }
    }

    /** A quick score from the coarse grid, or null if the spot plainly won't do. */
    private fun glance(tier: Tier, cx: Int, cz: Int): Double? {
        val lower = ArrayList<Int>()
        val old = ArrayList<Int>()
        val r = (tier.outer + WALL_MARGIN) / COARSE
        var wet = 0
        var all = 0
        for (i in -r..r) for (j in -r..r) {
            val x = cx + i * COARSE
            val z = cz + j * COARSE
            val h = height(x, z)
            all++
            val d = max(abs(i), abs(j)) * COARSE
            if (wet(x, z, h)) {
                wet++
                if (d <= tier.inner / 2) return reject("glance: wet middle")
                continue
            }
            if (d <= tier.inner) old.add(h) else lower.add(h)
        }
        if (wet > all * MAX_WET_INSIDE + 1 || lower.size < 4 || old.isEmpty()) return reject("glance: wet")
        lower.sort()
        val spread = lower[lower.size * 9 / 10] - lower[lower.size / 10]
        if (spread > tier.spread + COARSE_SLACK) return reject("glance: spread")
        val y0 = lower[lower.size * 2 / 5]
        old.sort()
        val rise = old[old.size / 2] - y0
        if (rise < MIN_RISE) return reject("glance: basin")
        return rise.coerceIn(-4, MAX_UPPER).toDouble() - spread * SPREAD_WEIGHT - wet.toDouble() / all * WET_WEIGHT
    }

    /** A closer look at a site, with every height the city needs worked out, or null if it won't do. */
    private fun look(tier: Tier, cx: Int, cz: Int, shift: Double): Site? {
        val step = FINE[tier.ordinal]
        val r = (tier.levelled + BAND) / step
        val lower = ArrayList<Int>()
        val old = ArrayList<Int>()
        // Heights toward each side, for the citadel (which wants the high ground) and for water
        val citadelSide = Array(4) { ArrayList<Int>() }
        val bandWet = IntArray(4)
        val bandAll = IntArray(4)
        val glacisWet = IntArray(4)
        var wetInside = 0
        var allInside = 0
        var wetOld = 0
        for (i in -r..r) for (j in -r..r) {
            val dx = i * step
            val dz = j * step
            val d = max(abs(dx), abs(dz))
            val h = height(cx + dx, cz + dz)
            val side = sideOf(dx, dz)
            if (d <= tier.outer + WALL_MARGIN) {
                allInside++
                if (wet(cx + dx, cz + dz, h)) {
                    wetInside++
                    if (d <= tier.inner) wetOld++
                    continue
                }
                if (d <= tier.inner) {
                    old.add(h)
                    // The citadel stands in the third of the old town furthest toward its side
                    for (s in Dir.entries) if (dx * s.dx + dz * s.dz >= tier.inner / 3) citadelSide[s.ordinal].add(h)
                } else if (d <= tier.outer) lower.add(h)
            } else {
                bandAll[side.ordinal]++
                if (wet(cx + dx, cz + dz, h)) {
                    bandWet[side.ordinal]++
                    if (d <= tier.levelled) glacisWet[side.ordinal]++
                }
            }
        }
        // Ponds and streams inside are filled as the city's ground is laid; a lake or the sea isn't
        if (wetInside > allInside * MAX_WET_INSIDE || wetOld > 2) return reject("look: wet inside")
        if (lower.size < 8 || old.size < 4) return reject("look: wet inside")
        lower.sort()
        val spread = lower[lower.size * 9 / 10] - lower[lower.size / 10]
        if (spread > tier.spread) return reject("look: spread")
        val y0 = lower[lower.size * 2 / 5]
        if (y0 <= ground.seaLevel + 1) return reject("look: low")
        if (!ground.fits(cx, cz, y0)) return reject("look: country")
        old.sort()
        val oldRise = old[old.size / 2] - y0
        if (oldRise < MIN_RISE) return reject("look: basin")
        val upper = oldRise.coerceIn(MIN_UPPER, MAX_UPPER)

        // Water along a side: a harbour if the town is low enough to reach it, a bank down to it if not too high
        var harbourSide: Dir? = null
        var most = 0.0
        for (s in Dir.entries) {
            val wetShare = if (bandAll[s.ordinal] == 0) 0.0 else bandWet[s.ordinal].toDouble() / bandAll[s.ordinal]
            if (wetShare >= HARBOUR_WATER && y0 <= ground.seaLevel + HARBOUR_HEIGHT && wetShare > most) {
                harbourSide = s
                most = wetShare
            }
        }
        for (s in Dir.entries) {
            if (glacisWet[s.ordinal] > 0 && s != harbourSide && y0 > ground.seaLevel + BANK_HEIGHT) return reject("look: wet glacis")
        }

        // Turned so the citadel takes the high ground; with a harbour, the water must lie east or west of it
        val turns = if (harbourSide == null) (0..3).toList() else listOf(Math.floorMod(harbourSide.ordinal - 1, 4), Math.floorMod(harbourSide.ordinal + 1, 4))
        val turn = turns.maxBy { k -> citadelSide[k].average() }
        val high = citadelSide[turn].sorted()
        val citadelRise = high[high.size * 3 / 4] - (y0 + upper)
        val citadel = citadelRise.coerceIn(MIN_CITADEL, MAX_CITADEL)
        val harbour = harbourSide?.let { if (Math.floorMod(it.ordinal - turn, 4) == Dir.E.ordinal) Dir.E else Dir.W }

        val score = oldRise.coerceIn(-4, MAX_UPPER) + 0.5 * citadelRise.coerceIn(0, MAX_CITADEL) - spread * SPREAD_WEIGHT -
            shift / COARSE * NEAR_WEIGHT + (if (harbour != null) HARBOUR_BONUS else 0.0) - wetInside.toDouble() / allInside * WET_WEIGHT
        return Site(cx, cz, tier, y0, upper, citadel, turn, harbour, score)
    }

    private fun reject(why: String): Nothing? {
        rejected.merge(why, 1, Int::plus)
        return null
    }

    companion object {
        /** The first look's grid, and how far (in its steps) a capital may move from where it was due. */
        const val COARSE = 32
        const val SHIFT_STEPS = 4
        /** How many of the first look's best spots get a closer look, per size. */
        const val CLOSE_LOOKS = 3
        /** The closer look's grid, by size. */
        val FINE = intArrayOf(16, 20, 24)
        /** Kept dry past the walls' outer faces, for the plinth and the wall's foot. */
        const val WALL_MARGIN = 6
        /** How far past the glacis water is looked for. */
        const val BAND = 24
        /** Extra rise and fall the coarse look lets by, since it sees less. */
        const val COARSE_SLACK = 4
        /** A middle this far below the lower town is a basin, not a site. */
        const val MIN_RISE = -8
        /** The old town stands this far over the lower town at least (on a terrace with its wall), at most. */
        const val MIN_UPPER = 4
        const val MAX_UPPER = 14
        /** And the citadel over the old town. */
        const val MIN_CITADEL = 5
        const val MAX_CITADEL = 12
        /** A side this wet past the walls, with the town at most this high over the sea, gets a harbour. */
        const val HARBOUR_WATER = 0.3
        const val HARBOUR_HEIGHT = 6
        /** Water below the glacis is fine with the town at most this far above it (the glacis is a bank down to it). */
        const val BANK_HEIGHT = 12
        /** Most of the walled town that may stand over water (ponds and streams, filled as it's built). */
        const val MAX_WET_INSIDE = 0.12
        const val WET_WEIGHT = 20.0
        const val SPREAD_WEIGHT = 0.5
        const val NEAR_WEIGHT = 0.6
        const val HARBOUR_BONUS = 6.0

        /** Which side of the middle an offset lies toward. */
        fun sideOf(dx: Int, dz: Int): Dir = if (abs(dx) >= abs(dz)) (if (dx >= 0) Dir.E else Dir.W) else (if (dz >= 0) Dir.S else Dir.N)
    }
}
