package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyTown
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a capital's key puts it in the world: the key is the packed block position of its heart (a block position's own packing). */
object Pos {
    fun x(key: Long): Int = (key shr 38).toInt()

    fun z(key: Long): Int = ((key shl 26) shr 38).toInt()
}

/**
 * The chart of the Word page (living-world/trader/design.md, Part 1): where each town of the book is put on the page, found from where it lies in the world,
 * north at the top. The towns are put as large as the chart lets them be (a block is never more than a sixth of a pixel, so two near towns are not spread across
 * the page), each with the ring that tells how old the news of it is and, if it keeps a road, the pole that flies the road's pennant. The trader is marked where they stand.
 * Nothing here draws: it works out where things go, and the page draws them.
 */
class WordMap(towns: List<TallyTown>, stand: Stand?, private val frame: IntArray) {
    /** A road's stub leaving a town: a short dotted road from the ring, a pole at its end and the pennant on the pole (offsets from the town's heart). */
    class Stub(val roadX0: Int, val roadY0: Int, val roadX1: Int, val roadY1: Int, val poleX: Int, val poleY: Int, val flagX: Int, val flagY: Int, val flip: Boolean, val bx: Int, val by: Int)

    /** A town on the chart: its heart ([x], [y]) in screen pixels from the chart's own corner of the page, and the stub of its road. */
    class Spot(val town: TallyTown, val x: Float, val y: Float, val stub: Stub?) {
        val key: Long get() = town.capital
    }

    val spots: List<Spot>

    /** How many blocks a pixel of the chart is. */
    val perPx: Float

    /** Where the trader is marked: a position on the chart, or where an edge of it is nearest if they are far from every town; [off] says which edge (0 none). */
    var meX = 0f
        private set
    var meY = 0f
        private set
    var off = 0
        private set
    var hasMe = false
        private set

    /** Where each town's name is set (x, y, w, h), found by [place]. */
    private val labels = HashMap<Long, IntArray>()

    /** Where the world's origin lies on the chart, and the blocks to a pixel, for [px] and [py]. */
    private var ox = 0.0
    private var oz = 0.0
    private var pp = MIN_PER_PX.toDouble()

    init {
        val n = towns.size
        val wx = DoubleArray(n + 1)
        val wz = DoubleArray(n + 1)
        val l = IntArray(n + 1)
        val r = IntArray(n + 1)
        val t = IntArray(n + 1)
        val b = IntArray(n + 1)
        val stubs = arrayOfNulls<Stub>(n)
        for ((i, town) in towns.withIndex()) {
            wx[i] = Pos.x(town.capital).toDouble()
            wz[i] = Pos.z(town.capital).toDouble()
            val stub = stub(town)
            stubs[i] = stub
            l[i] = RING + 1
            r[i] = RING + 1
            t[i] = RING + 1
            b[i] = RING + 1
            if (stub != null) {
                l[i] = max(l[i], -stub.bx)
                r[i] = max(r[i], stub.bx + FLAG_W + 2)
                t[i] = max(t[i], -stub.by)
                b[i] = max(b[i], stub.by + POLE)
            }
        }
        // The trader is on the chart if they are near a town; far off, they are marked at its edge
        var count = n
        var near = false
        if (stand != null && n > 0) {
            near = towns.any { hypot(Pos.x(it.capital) - stand.x, Pos.z(it.capital) - stand.z) < NEAR }
            if (near) {
                wx[n] = stand.x
                wz[n] = stand.z
                l[n] = 6
                r[n] = 6
                t[n] = 8
                b[n] = 4
                count = n + 1
            }
        }
        val x0 = frame[0]
        val y0 = frame[1]
        val w = frame[2] - frame[0]
        val h = frame[3] - frame[1]
        pp = max(MIN_PER_PX.toDouble(), max(need(wx, l, r, count, w), need(wz, t, b, count, h)))
        val sx = shift(wx, l, r, count, w, pp)
        val sz = shift(wz, t, b, count, h, pp)
        ox = x0 + sx
        oz = y0 + sz
        perPx = pp.toFloat()
        spots = List(n) { i -> Spot(towns[i], (x0 + wx[i] / pp + sx).toFloat(), (y0 + wz[i] / pp + sz).toFloat(), stubs[i]) }
        if (stand != null && n > 0) {
            hasMe = true
            if (near) {
                meX = (x0 + stand.x / pp + sx).toFloat()
                meY = (y0 + stand.z / pp + sz).toFloat()
            } else {
                // Off the chart: put them at the edge, toward where they are from the middle of the towns
                val mx = (x0 + w / 2.0).toFloat()
                val my = (y0 + h / 2.0).toFloat()
                val ax = (stand.x - wx.take(n).average()) / pp
                val az = (stand.z - wz.take(n).average()) / pp
                val k = if (hypot(ax, az) < 1e-6) 0.0 else min((w / 2.0 - 6) / max(1e-6, abs(ax)), (h / 2.0 - 8) / max(1e-6, abs(az)))
                meX = (mx + ax * k).toFloat()
                meY = (my + az * k).toFloat()
                off = if (abs(ax) * h / w > abs(az)) (if (ax > 0) 2 else 4) else (if (az > 0) 3 else 1)
            }
        }
    }

    /** Where the world position [wx] (a block's x) lies across the chart, and [wz] (a block's z) down it. */
    fun px(wx: Double): Float = (ox + wx / pp).toFloat()

    fun py(wz: Double): Float = (oz + wz / pp).toFloat()

    /** The spot of the town whose key is [key]. */
    fun spot(key: Long): Spot? = spots.firstOrNull { it.key == key }

    /** Where the name of the town [key] is set, if [place] has set it. */
    fun label(key: Long): IntArray? = labels[key]

    /**
     * Sets every town's name (and, under it for the town that is picked, how old its news is) where it does not run over a ring, a pole, another name, the chart's
     * edge or what is [blocked] (boxes the page has drawn things in): below its ring if that is clear, else above, else beside. [first] are put first, so that the town
     * that matters has its pick of the places. [wide] gives how wide a town's lines are and [tall] how tall they are together.
     */
    fun place(first: List<Long>, wide: (TallyTown) -> Int, tall: (TallyTown) -> Int, blocked: List<IntArray> = listOf()) {
        labels.clear()
        val taken = ArrayList<IntArray>(blocked)
        for (s in spots) {
            taken.add(intArrayOf(s.x.roundToInt() - RING - 1, s.y.roundToInt() - RING - 1, 2 * RING + 3, 2 * RING + 3))
            s.stub?.let { taken.add(intArrayOf(s.x.roundToInt() + it.bx, s.y.roundToInt() + it.by, FLAG_W + 2, POLE)) }
        }
        if (hasMe) taken.add(intArrayOf(meX.roundToInt() - 6, meY.roundToInt() - 8, 12, 12))
        val order = spots.sortedBy { s -> first.indexOf(s.key).let { if (it < 0) Int.MAX_VALUE else it } }
        for (s in order) {
            val w = wide(s.town)
            val h = tall(s.town)
            val cx = s.x.roundToInt()
            val cy = s.y.roundToInt()
            val tries = listOf(
                intArrayOf(cx - w / 2, cy + RING + 2, w, h),
                intArrayOf(cx - w / 2, cy - RING - 2 - h, w, h),
                intArrayOf(cx + RING + 3, cy - h / 2, w, h),
                intArrayOf(cx - RING - 3 - w, cy - h / 2, w, h),
                intArrayOf(cx - w / 2, cy + RING + 2 + h, w, h),
            )
            var best: IntArray? = null
            var bestCost = Int.MAX_VALUE
            for (c in tries) {
                val clamped = intArrayOf(c[0].coerceIn(frame[0] + 2, max(frame[0] + 2, frame[2] - 2 - c[2])), c[1], c[2], c[3])
                var cost = 0
                for (o in taken) cost += overlap(clamped, o)
                if (clamped[1] < frame[1] + 2) cost += (frame[1] + 2 - clamped[1]) * clamped[2]
                if (clamped[1] + clamped[3] > frame[3] - 2) cost += (clamped[1] + clamped[3] - frame[3] + 2) * clamped[2]
                if (cost < bestCost) {
                    bestCost = cost
                    best = clamped
                }
                if (cost == 0) break
            }
            val chosen = best ?: tries[0]
            labels[s.key] = chosen
            taken.add(chosen)
        }
    }

    /** The fewest blocks to a pixel at which every town, with what it carries beside it (reach [lo] and [hi] either way along the axis), still fits in [span] pixels. */
    private fun need(w: DoubleArray, lo: IntArray, hi: IntArray, count: Int, span: Int): Double {
        var pp = 0.0
        for (i in 0 until count) for (j in 0 until count) {
            val d = w[i] - w[j]
            if (d <= 0.0) continue
            val room = max(1, span - hi[i] - lo[j])
            pp = max(pp, d / room)
        }
        return pp
    }

    /** Where, along an axis, the towns are laid so that they stand as far in from both edges as their reach asks and as evenly as may be: the shift to add to a world position over [pp]. */
    private fun shift(w: DoubleArray, lo: IntArray, hi: IntArray, count: Int, span: Int, pp: Double): Double {
        var low = -1e18
        var high = 1e18
        for (i in 0 until count) {
            low = max(low, lo[i] - w[i] / pp)
            high = min(high, span - hi[i] - w[i] / pp)
        }
        return if (count == 0) 0.0 else (low + high) / 2.0
    }

    companion object {
        /** The area two boxes {x, y, w, h} share. */
        fun overlap(a: IntArray, b: IntArray): Int {
            val w = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
            val h = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
            return if (w > 0 && h > 0) w * h else 0
        }

        /** The radius of a town's ring, in pixels. */
        const val RING = 8

        /** The height of a road's pole, and how wide its pennant is flown. */
        const val POLE = 10
        const val FLAG_W = 10

        /** The most blocks a pixel of the chart may be less than (so that near towns are not spread). */
        const val MIN_PER_PX = 6f

        /** How far from the nearest town the trader may be and still be marked on the chart itself, in blocks. */
        const val NEAR = 900.0

        /**
         * The stub of a road that leaves a town on [side] ("n", "e", "s", "w"; "any" is flown beside the ring at the upper right): a few dotted pixels of road
         * and a pole with a pennant at the end of them. Null for a town whose road is not kept.
         */
        fun stub(town: TallyTown): Stub? {
            if (town.road < 0) return null
            val r = RING
            return when (town.side) {
                "e" -> Stub(r + 1, 0, r + 5, 0, r + 6, -POLE + 1, r + 8, -POLE + 1, false, r + 6, -POLE + 1)
                "w" -> Stub(-r - 1, 0, -r - 5, 0, -r - 7, -POLE + 1, -r - 7 - FLAG_W, -POLE + 1, true, -r - 7 - FLAG_W, -POLE + 1)
                "n" -> Stub(0, -r - 1, 0, -r - 5, -1, -r - 5 - POLE, 1, -r - 5 - POLE, false, -1, -r - 5 - POLE)
                "s" -> Stub(0, r + 1, 0, r + 5, -1, r + 5, 1, r + 5, false, -1, r + 5)
                else -> Stub(0, 0, 0, 0, 6, -r - POLE + 1, 8, -r - POLE + 1, false, 6, -r - POLE + 1)
            }
        }
    }
}
