package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.townsfolk.Activity
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Where a dancer is at one moment, which way they face and the step they're dancing. */
class DancePose {
    var x = 0.0
    var z = 0.0
    var yaw = 0f
    var activity: Activity = Activity.NONE
}

/**
 * The festivals' dances, worked out from the bar alone so that every dancer, and every player's view of them, keeps
 * the same count. A set is 36 bars of 40 ticks (the music's four 8-bar phrases and a 4-bar finale, at 120 beats a
 * minute). Each phrase is danced as a few figures, every figure whole bars long, each starting where the last left
 * off, and the set ends with everyone back on the spot they started from:
 *
 * - Ribbonwake, the ribbon weave: one even ring, every other dancer going each way and passing in and out of each
 *   other (the ribbons from the pole braid over their heads), then a promenade, twirls and steps in to the pole.
 * - Sunhearth, the couples' reel: couples side by side promenading round the fire, swinging each other round,
 *   twirling, and stepping in to the fire and back.
 * - Sheafmoot, the turning rings: the ring splits into two going opposite ways, comes together to stamp and clap,
 *   steps in and twirls.
 * - Lanternrise, the lantern ring: a slow walk with lanterns held out, raised high together as the ring steps in.
 *
 * Positions are given round the dance's centre, [radius] blocks out; [n] dancers (an even number; dancer 2k leads
 * and 2k + 1 partners). [reach] is how far in or out of the ring a figure may take a dancer: a capital's ring runs
 * between the fountain's rim and its benches, so its figures keep tighter.
 */
class FestivalDance(val kind: FestivalKind, val n: Int, val radius: Double, val reach: Double, val start: Double) {
    enum class Fig { CIRCLE, WEAVE, DOUBLE, IN_OUT, TURN, TWIRL, BOW, HURRAH, HOLD, RAISE, CLAP }

    /** One figure: [bars] long, going [dir] round the ring (+1 the way the angle grows), by [amount] (blocks a bar, or passes). */
    class Section(val fig: Fig, val bars: Int, val dir: Int = 1, val amount: Double = 0.0)

    /** Couples side by side (the reel), or one even ring. */
    val couples: Boolean = kind == FestivalKind.SUNHEARTH

    private val pairs = n / 2

    /** How far a couple stand either side of the ring, side by side. */
    private val side: Double = if (couples) min(0.45, reach * 0.75) else 0.0

    val sections: List<Section> = program(kind)

    /** The bar each section starts on. */
    private val firstBar: IntArray

    /** Each dancer's angle round the ring at the start of each section. */
    private val angles: Array<DoubleArray>

    init {
        firstBar = IntArray(sections.size + 1)
        for (i in sections.indices) firstBar[i + 1] = firstBar[i] + sections[i].bars
        angles = Array(sections.size + 1) { DoubleArray(n) }
        for (i in 0 until n) angles[0][i] = homeAngle(i)
        for (s in sections.indices) for (i in 0 until n) angles[s + 1][i] = angles[s][i] + travel(sections[s], 1.0, i)
    }

    /** The whole set's length in bars (36). */
    val bars: Int get() = firstBar[sections.size]

    /** Where dancer [i] starts and finishes a set. */
    fun homeAngle(i: Int): Double = if (couples) start + 2 * PI * (i / 2) / pairs else start + 2 * PI * i / n

    fun homeRadius(i: Int): Double = radius + formationOffset(i)

    private fun formationOffset(i: Int): Double = if (!couples) 0.0 else if (i % 2 == 0) -side else side

    private fun lead(i: Int): Boolean = i % 2 == 0

    /**
     * Dancer [i] at [bar] bars into the set (fractions within a bar): their position round the centre ([cx], [cz]),
     * facing and step, into [out].
     */
    fun pose(bar: Double, i: Int, cx: Double, cz: Double, out: DancePose) {
        val b = bar.coerceIn(0.0, bars - 1e-6)
        var s = 0
        while (s < sections.size - 1 && b >= firstBar[s + 1]) s++
        val sec = sections[s]
        val f = (b - firstBar[s]) / sec.bars
        val within = b - floor(b)
        val a0 = angles[s][i]
        var angle = a0 + travel(sec, f, i)
        var r = radius + formationOffset(i)
        var facing = Facing.CENTRE
        var activity = Activity.NONE
        val lantern = kind == FestivalKind.LANTERNRISE
        when (sec.fig) {
            Fig.CIRCLE -> {
                facing = if (sec.dir > 0) Facing.AHEAD else Facing.BACK
                activity = if (lantern) Activity.LANTERN_WALK else step(sec.dir > 0)
            }
            Fig.WEAVE -> {
                val going = if (lead(i)) sec.dir else -sec.dir
                // In and out of each other: the leads pass outside the first they meet, inside the next
                val u = abs(travel(sec, f, i))
                r += (if (lead(i)) 1 else -1) * weaveDepth() * sin(n * u / 2.0)
                facing = if (going > 0) Facing.AHEAD else Facing.BACK
                activity = step(going > 0)
            }
            Fig.DOUBLE -> {
                val going = if (lead(i)) sec.dir else -sec.dir
                r += (if (lead(i)) -1 else 1) * reach * envelope(f, sec.bars)
                facing = if (going > 0) Facing.AHEAD else Facing.BACK
                activity = step(going > 0)
            }
            Fig.IN_OUT -> {
                r -= inDepth() * sin(PI * within)
                activity = if (lantern) Activity.LANTERN_RAISE else Activity.RING_IN
            }
            Fig.TURN -> {
                // The couple swings round the middle between them, once in two bars
                val turn = 2 * PI * eased(f, sec.bars)
                val mid = radius
                val off = formationOffset(i)
                val mx = cx + cos(a0) * mid
                val mz = cz + sin(a0) * mid
                val ox = cos(a0) * off
                val oz = sin(a0) * off
                val rx = ox * cos(turn) - oz * sin(turn)
                val rz = ox * sin(turn) + oz * cos(turn)
                out.x = mx + rx
                out.z = mz + rz
                // Facing the partner: across the middle
                out.yaw = yawOf(-rx, -rz)
                out.activity = Activity.COUPLE_TURN
                return
            }
            Fig.TWIRL -> activity = Activity.TWIRL
            Fig.BOW -> activity = Activity.FEST_BOW
            Fig.HURRAH -> activity = Activity.FEST_HURRAH
            Fig.HOLD -> activity = Activity.NONE
            Fig.RAISE -> activity = Activity.LANTERN_RAISE
            Fig.CLAP -> activity = Activity.CLAP_ALONG
        }
        out.x = cx + cos(angle) * r
        out.z = cz + sin(angle) * r
        out.yaw = when (facing) {
            Facing.CENTRE -> yawOf(cx - out.x, cz - out.z)
            Facing.AHEAD -> yawOf(-sin(angle), cos(angle))
            Facing.BACK -> yawOf(sin(angle), -cos(angle))
        }
        out.activity = activity
    }

    /** Where in its home spot dancer [i] stands between sets, facing in. */
    fun home(i: Int, cx: Double, cz: Double, out: DancePose) {
        val a = homeAngle(i)
        val r = homeRadius(i)
        out.x = cx + cos(a) * r
        out.z = cz + sin(a) * r
        out.yaw = yawOf(cx - out.x, cz - out.z)
        out.activity = Activity.NONE
    }

    private enum class Facing { CENTRE, AHEAD, BACK }

    /** How far round the ring (radians) dancer [i] has gone [f] of the way through [sec]. */
    private fun travel(sec: Section, f: Double, i: Int): Double = when (sec.fig) {
        Fig.CIRCLE -> sec.dir * sec.amount * sec.bars / radius * eased(f, sec.bars)
        Fig.WEAVE, Fig.DOUBLE -> {
            // Each pass is a whole space round the ring for both: leads one way, partners the other
            val total = sec.amount * 2 * PI / n
            (if (lead(i)) sec.dir else -sec.dir) * total * eased(f, sec.bars)
        }
        else -> 0.0
    }

    private fun step(rightInward: Boolean): Activity = if (rightInward) Activity.RING_STEP else Activity.RING_STEP_L

    private fun weaveDepth(): Double = min(0.5, reach * 0.8)

    private fun inDepth(): Double = if (couples) reach * 0.5 else reach

    companion object {
        /**
         * How far through a figure [bars] long its travel has got at [f]: steady, but easing in over its first
         * quarter bar and out over its last, so a figure starts and stops without a jolt.
         */
        fun eased(f: Double, bars: Int): Double {
            val a = min(0.25 / bars, 0.5)
            val v = 1.0 / (1.0 - a)
            val t = f.coerceIn(0.0, 1.0)
            return when {
                t < a -> v * t * t / (2 * a)
                t <= 1 - a -> v * (t - a / 2)
                else -> 1.0 - v * (1 - t) * (1 - t) / (2 * a)
            }
        }

        /** Out over a figure's first three quarters of a bar, back over its last: 0 at either end, 1 between. */
        fun envelope(f: Double, bars: Int): Double {
            val edge = min(f, 1.0 - f) * bars / 0.75
            val c = edge.coerceIn(0.0, 1.0)
            return c * c * (3 - 2 * c)
        }

        /** The yaw that looks along ([dx], [dz]). */
        fun yawOf(dx: Double, dz: Double): Float = (atan2(dz, dx) * (180.0 / PI)).toFloat() - 90f

        private fun abs(x: Double): Double = if (x < 0) -x else x

        /** The four dances, each A B A' B' (the second time round the other way) and the finale. */
        fun program(kind: FestivalKind): List<Section> {
            fun phrases(a: (Int) -> List<Section>, b: (Int) -> List<Section>, finale: List<Section>) = a(1) + b(1) + a(-1) + b(-1) + finale
            val finale = listOf(Section(Fig.IN_OUT, 1), Section(Fig.BOW, 1), Section(Fig.HURRAH, 1), Section(Fig.HOLD, 1))
            return when (kind) {
                FestivalKind.RIBBONWAKE -> phrases(
                    { d -> listOf(Section(Fig.WEAVE, 6, d, 2.0), Section(Fig.IN_OUT, 2)) },
                    { d -> listOf(Section(Fig.CIRCLE, 4, d, 2.0), Section(Fig.TWIRL, 2), Section(Fig.IN_OUT, 2)) },
                    finale,
                )
                FestivalKind.SUNHEARTH -> phrases(
                    { d -> listOf(Section(Fig.CIRCLE, 2, d, 2.0), Section(Fig.TURN, 2), Section(Fig.CIRCLE, 2, d, 2.0), Section(Fig.IN_OUT, 2)) },
                    { d -> listOf(Section(Fig.TURN, 2), Section(Fig.TWIRL, 2), Section(Fig.CIRCLE, 2, -d, 2.0), Section(Fig.IN_OUT, 2)) },
                    finale,
                )
                FestivalKind.SHEAFMOOT -> phrases(
                    { d -> listOf(Section(Fig.DOUBLE, 6, d, 2.0), Section(Fig.CLAP, 2)) },
                    { d -> listOf(Section(Fig.IN_OUT, 2), Section(Fig.TWIRL, 2), Section(Fig.CIRCLE, 2, d, 2.0), Section(Fig.CLAP, 2)) },
                    finale,
                )
                FestivalKind.LANTERNRISE -> phrases(
                    { d -> listOf(Section(Fig.CIRCLE, 6, d, 1.0), Section(Fig.RAISE, 2)) },
                    { d -> listOf(Section(Fig.IN_OUT, 2), Section(Fig.CIRCLE, 4, d, 1.0), Section(Fig.RAISE, 2)) },
                    listOf(Section(Fig.IN_OUT, 1), Section(Fig.BOW, 1), Section(Fig.RAISE, 1), Section(Fig.HOLD, 1)),
                )
            }
        }

        /** Bars a set lasts, and ticks a bar. */
        const val SET_BARS = 36
        const val BAR_TICKS = 40
        /** Each phrase of the music is 8 bars; the finale starts on bar 32. */
        const val PHRASE_BARS = 8
    }
}
