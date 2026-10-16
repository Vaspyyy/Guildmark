package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A merchant cog run onto a reef and broken in two. The hull is worked out in the ship's own frame (clinker planking
 * over ribs, a flat keel and round bilge, a deck, a castle aft and a smaller one forward, one mast) and then laid in
 * the world at an angle: the bow half stranded high on the rocks and listing, the stern half slid off the edge into
 * the deep and sunk to its castle. Where she broke the planking is torn away to bare ribs. Her cargo, her captain's
 * chest, her fallen mast and torn sail lie about the reef, with coral, weed and bones taking her back.
 */
class Wreck(plan: IslandPlan) : Landmark(plan) {
    private val dice = Dice(plan.spec.seed xor 0x3EC4L)
    private val seed = Noise.seed(plan.spec.seed, 61)
    private val warm = plan.climate == Climate.WARM || plan.climate == Climate.LUKEWARM

    /** The wood she's built of: the cool seas' spruce or dark oak, the warm seas' jungle, mangrove or acacia. */
    private val wood: String = if (warm) dice.pick(listOf("jungle", "mangrove", "acacia")) else dice.pick(listOf("spruce", "dark_oak"))

    /** The darker strake along her sides: tarred dark oak on the paler woods, smooth black stone on the dark oak. */
    private val waleFamily = if (wood == "dark_oak") "polished_blackstone" else "dark_oak"
    private val waleBlock = if (wood == "dark_oak") "polished_blackstone" else "dark_oak_planks"

    /** How long she is from stem to transom, and how wide at her fullest. */
    private val length = dice.int(21, 27).toDouble()
    private val beam = dice.int(7, 9)
    private val hl = length / 2.0
    private val bh = beam / 2.0

    /** Her heights in her own frame, up from the underside of the keel: the waist's sides, the main deck and the two castles. */
    private val rm = 6.8
    private val dk = 5.0
    private val cd = 9.0
    private val fd = 8.2

    /** Where the stern castle's front wall stands, where the forecastle begins, and where the mast is stepped. */
    private val uCs = -hl + length * dice.range(0.28, 0.32)
    private val uFs = hl - length * dice.range(0.16, 0.2)
    private val uMast = length * dice.range(0.07, 0.11)

    /** Which side she lists to (+1 starboard down), and how far. */
    private val list = if (dice.chance(0.5)) 1.0 else -1.0
    private val heel = Math.toRadians(dice.range(12.0, 25.0))

    /** How far the rudder has swung out from the sternpost, in radians. */
    private val swing = dice.range(0.35, 0.6)

    /** The ribs: the first one's station and the spacing, and the break between the halves, on a rib. */
    private val ribFirst = -hl + 2.0
    private val ribGap = 2.6
    private val ubBow: Double
    private val ubStern: Double

    /** Where each half lies, and what it's made of once drawn in the world (block shape and material by column and height). */
    private var bowPose = Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private var sternPose = Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private var bowCells = HashMap<Long, Int>()
    private var sternCells = HashMap<Long, Int>()

    /** The columns the wreck and the ground built up round it cover. */
    private var fx0 = 0
    private var fx1 = 0
    private var fz0 = 0
    private var fz1 = 0

    /** Every block of both halves by position, the cells the fittings have taken, and the highest hull block of each column. */
    private val hull = HashMap<Long, Int>()
    private val taken = HashSet<Long>()
    private val hullTop = HashMap<Long, Int>()

    /** How many of her chests of cargo are stowed so far. */
    private var cargoChests = 0

    /** The fence posts the fittings stand, to be joined up and laid once they're all in. */
    private val posts = HashSet<Long>()

    /** The top of the mast's stump (x, y, z), the ends of the fallen yard, and the columns her halves cover. */
    private var stump: IntArray? = null
    private val yardEnds = ArrayList<IntArray>()
    private var hullCols = IntArray(0)

    init {
        // Break on a rib a little aft of amidships, so the bow half's torn end shows a bare frame and the stern half's another
        val k = ((-0.07 * length - ribFirst) / ribGap).roundToInt()
        ubBow = ribFirst + k * ribGap - 0.45
        ubStern = ribFirst + (k - 1) * ribGap + 0.45
    }

    /** Where one piece of the ship lies: its frame's origin in the world, and where its three axes point there. */
    private class Pose(val ox: Double, val oy: Double, val oz: Double, yaw: Double, pitch: Double, roll: Double) {
        /** The ship's forward, starboard and up axes in the world. */
        val fx: Double
        val fy: Double
        val fz: Double
        val sx: Double
        val sy: Double
        val sz: Double
        val ux: Double
        val uy: Double
        val uz: Double

        init {
            val cy = cos(yaw)
            val sn = sin(yaw)
            val cp = cos(pitch)
            val sp = sin(pitch)
            val cr = cos(roll)
            val sr = sin(roll)
            // Yawed flat, then the bow lifted, then heeled over to starboard
            val f1x = cp * cy
            val f1z = cp * sn
            val u1x = -sp * cy
            val u1z = -sp * sn
            fx = f1x
            fy = sp
            fz = f1z
            sx = cr * -sn - sr * u1x
            sy = -sr * cp
            sz = cr * cy - sr * u1z
            ux = sr * -sn + cr * u1x
            uy = cr * cp
            uz = sr * cy + cr * u1z
        }

        /** The world's x, y and z of the point ([u], [v], [w]) in the ship's frame. */
        fun x(u: Double, v: Double, w: Double): Double = ox + u * fx + v * sx + w * ux
        fun y(u: Double, v: Double, w: Double): Double = oy + u * fy + v * sy + w * uy
        fun z(u: Double, v: Double, w: Double): Double = oz + u * fz + v * sz + w * uz

        /** The ship-frame fore and aft, across and up coordinates of the world point ([x], [y], [z]). */
        fun u(x: Double, y: Double, z: Double): Double = (x - ox) * fx + (y - oy) * fy + (z - oz) * fz
        fun v(x: Double, y: Double, z: Double): Double = (x - ox) * sx + (y - oy) * sy + (z - oz) * sz
        fun w(x: Double, y: Double, z: Double): Double = (x - ox) * ux + (y - oy) * uy + (z - oz) * uz
    }

    // ---- where she lies

    /**
     * Lays her on the reef: the way she came in and where each half comes to rest, the ground built up under and round
     * them, and her footprint kept clear of trees and boulders.
     */
    override fun lay() {
        val t = terrain
        val cx = plan.spec.cx.toDouble()
        val cz = plan.spec.cz.toDouble()
        // She came in over the reef from the open sea, bow first: of a few headings along the reef's length, the one
        // with the most rock under where her bow comes to rest
        val flip = if (dice.chance(0.5)) 0.0 else PI
        var out = t.angle + flip
        var edge = 0.0
        var best = Double.NEGATIVE_INFINITY
        for (k in 0 until 12) {
            val a = t.angle + flip + dice.range(-0.6, 0.6)
            var s = 0.0
            while (s < 70.0 && t.distance((cx + cos(a) * s).roundToInt(), (cz + sin(a) * s).roundToInt()) >= 0.0) s += 0.5
            var rock = 0.0
            for (j in 0..5) {
                val q = s - 1.5 - 2.5 * j
                if (q < 0.0) break
                rock += t.h((cx + cos(a) * q).roundToInt(), (cz + sin(a) * q).roundToInt()).coerceIn(-3.0, 2.5)
            }
            val score = rock + dice.range(0.0, 2.0)
            if (score > best) {
                best = score
                out = a
                edge = s
            }
        }
        val inland = dice.range(1.5, 4.5)
        val lateral = dice.range(-1.5, 1.5)
        val bx = cx + cos(out) * (edge - inland) - sin(out) * lateral
        val bz = cz + sin(out) * (edge - inland) + cos(out) * lateral

        // The bow half: its torn end at that spot, the keel there about at the surface, the bow cocked up and listing
        val yawBow = out + PI + dice.range(-0.35, 0.35)
        val pitchBow = Math.toRadians(dice.range(4.0, 9.0))
        val keelBreak = sea - 3.0 + dice.range(-0.3, 1.5)
        bowPose = placed(bx, keelBreak, bz, ubBow, yawBow, pitchBow, heel * list)

        // The stern half: slid back off the edge, its own way round, down by the stern, sunk to its castle
        val gap = dice.range(3.5, 6.0)
        val yawStern = yawBow + dice.range(-0.35, 0.35)
        val pitchStern = Math.toRadians(dice.range(7.0, 13.0))
        val rollStern = heel * list + Math.toRadians(dice.range(-5.0, 8.0)) * list
        val sx = bx - cos(yawBow) * gap - sin(yawBow) * dice.range(-1.5, 1.5)
        val sz = bz - sin(yawBow) * gap + cos(yawBow) * dice.range(-1.5, 1.5)
        val trial = Pose(0.0, 0.0, 0.0, yawStern, pitchStern, rollStern)
        val castleMid = (-hl + uCs) / 2.0
        val sunk = sea + dice.range(0.8, 2.8)
        // The castle's deck at its middle stands at [sunk]: that fixes how high the torn end is
        val breakY = sunk - ((castleMid - ubStern) * trial.fy + cd * trial.uy)
        sternPose = placed(sx, breakY, sz, ubStern, yawStern, pitchStern, rollStern)

        bowCells = voxelize(bowPose, BOW, ubBow - 2.0, hl + 2.0)
        sternCells = voxelize(sternPose, STERN, -hl - 4.5, ubStern + 2.0)
        raise()
        var x0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var z0 = Int.MAX_VALUE
        var z1 = Int.MIN_VALUE
        for (cells in listOf(bowCells, sternCells)) for (p in cells.keys) {
            x0 = min(x0, Growth.x(p))
            x1 = max(x1, Growth.x(p))
            z0 = min(z0, Growth.z(p))
            z1 = max(z1, Growth.z(p))
        }
        fx0 = x0 - RAMP
        fx1 = x1 + RAMP
        fz0 = z0 - RAMP
        fz1 = z1 + RAMP
        plan.claim(x0 - 6, z0 - 6, x1 + 6, z1 + 6)
    }

    /** The pose whose frame puts the point ([ub], 0, 0) (on the keel at the torn end) at ([x], [y], [z]) in the world. */
    private fun placed(x: Double, y: Double, z: Double, ub: Double, yaw: Double, pitch: Double, roll: Double): Pose {
        val p = Pose(0.0, 0.0, 0.0, yaw, pitch, roll)
        return Pose(x - ub * p.fx, y - ub * p.fy, z - ub * p.fz, yaw, pitch, roll)
    }

    /**
     * Builds the reef up under both halves so they lie bedded on rock and sand, easing out into the reef round them.
     * Under the high end of the bow, where she stands clear of the ground, only a little rubble props her.
     */
    private fun raise() {
        val low = HashMap<Long, Int>()
        for (cells in listOf(bowCells, sternCells)) for (p in cells.keys) {
            val c = Growth.pack(Growth.x(p), 0, Growth.z(p))
            val y = Growth.y(p)
            val old = low[c]
            if (old == null || y < old) low[c] = y
        }
        val want = HashMap<Long, Double>()
        for ((c, y) in low) {
            val x = Growth.x(c)
            val z = Growth.z(c)
            val now = terrain.top(x, z) - 1
            // Mostly the ground comes right up to the hull, here and there a block short of it, here and there into it
            val bed = Noise.unit(x, 0, z, seed + 1)
            var ground = y - 1 + (if (bed < 0.15) -1 else if (bed > 0.7) 1 else 0)
            val reach = if (y < sea - 1) 9 else 3
            if (ground - now > reach) ground = if (Noise.unit(x, 1, z, seed + 1) < 0.5) now + 2 else now
            if (ground > now) want[c] = (ground - sea + 1).toDouble()
        }
        // The rubble slopes away from the hull at about a block a block
        val ramp = HashMap<Long, Double>(want)
        for ((c, h) in want) {
            val x = Growth.x(c)
            val z = Growth.z(c)
            for (dx in -RAMP..RAMP) for (dz in -RAMP..RAMP) {
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d > RAMP) continue
                val k = Growth.pack(x + dx, 0, z + dz)
                val here = h - d * (0.9 + 0.5 * Noise.unit(x + dx, 1, z + dz, seed + 2))
                if (here > (ramp[k] ?: Double.NEGATIVE_INFINITY)) ramp[k] = here
            }
        }
        for ((c, h) in ramp) {
            val x = Growth.x(c)
            val z = Growth.z(c)
            if (h > terrain.h(x, z) + 0.5) terrain.set(x, z, h)
        }
        rocks(low.keys.toList())
    }

    /**
     * Rocks the reef has thrown up round her: mounds beside her bow and along her flanks, some of them breaking the
     * surface, that she struck and now lies among. [cols] are the columns her halves cover.
     */
    private fun rocks(cols: List<Long>) {
        if (cols.isEmpty()) return
        val bowCols = bowCells.keys.map { Growth.pack(Growth.x(it), 0, Growth.z(it)) }.distinct()
        var made = 0
        var tries = 0
        val want = dice.int(5, 8)
        while (made < want && tries < 120) {
            tries++
            val near = if (dice.chance(0.75)) bowCols else cols
            val c = near[dice.int(near.size)]
            val a = dice.range(0.0, 2 * PI)
            val r = dice.range(1.8, 5.5)
            val x = Growth.x(c) + (cos(a) * r).roundToInt()
            val z = Growth.z(c) + (sin(a) * r).roundToInt()
            var clear = Double.MAX_VALUE
            for (k in cols) clear = min(clear, hypot((Growth.x(k) - x).toDouble(), (Growth.z(k) - z).toDouble()))
            if (clear < 1.6) continue
            val rad = dice.range(1.7, 3.3)
            val top = dice.range(0.8, 3.4)
            for (dx in -3..3) for (dz in -3..3) {
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d > rad) continue
                val h = top * (1.0 - (d / rad).pow(1.25)) + 0.7 * (Noise.unit(x + dx, 2, z + dz, seed + 12) - 0.3)
                if (h > terrain.h(x + dx, z + dz) + 0.4) terrain.set(x + dx, z + dz, h)
            }
            made++
        }
    }

    /**
     * Repaints the dry ground built up round her: bare rock and scree where the reef is thrown up high, sand or shingle
     * on the low banks, and never turf, which the plan would grow there from the height alone.
     */
    override fun paint() {
        for (x in fx0..fx1) for (z in fz0..fz1) {
            if (!terrain.dry(x, z)) continue
            val g = plan.groundAt(x, z)
            val rocky = terrain.h(x, z) > 1.3
            val turf = g == Ground.GRASS || g == Ground.COARSE || g == Ground.MOSS || g == Ground.PODZOL
            val beach = g == Ground.SAND || g == Ground.SHINGLE || g == Ground.DUNE
            // A beach is left alone unless the rock has been thrown up through it
            if (!turf && !(beach && rocky)) continue
            val n = Noise.unit(x, 3, z, seed + 8)
            plan.paint(
                x, z,
                when {
                    rocky -> if (n < 0.7) Ground.BARE else Ground.SCREE
                    warm -> if (n < 0.12) Ground.SCREE else Ground.SAND
                    else -> if (n < 0.5) Ground.SHINGLE else if (n < 0.75) Ground.BARE else Ground.SAND
                },
            )
        }
    }

    // ---- the hull's lines, in the ship's own frame: u fore and aft (the bow is +), v across (starboard +), w up from the keel

    /** The underside of the hull at station [u]: flat amidships, sweeping up a little toward both ends. */
    private fun keelW(u: Double): Double {
        val b = max(0.0, (u - 0.25 * hl) / (0.75 * hl))
        val s = max(0.0, (-u - 0.3 * hl) / (0.7 * hl))
        return 1.1 * b * b + 0.6 * s * s
    }

    /** How far forward the hull reaches at height [w]: the stem, raking forward a little as it rises. */
    private fun fore(w: Double): Double = hl - 1.0 + 0.1 * w

    /** How far aft the hull reaches at height [w]: the transom, tucked in at the keel and overhanging above the deck. */
    private fun aft(w: Double): Double = if (w > dk) -hl - 0.12 * (w - dk) else -hl + 0.1 * (dk - w)

    /** How full the section is at height [w]: a flat bottom nearly half the beam, a round bilge, then straight up. */
    private fun fullness(w: Double): Double {
        val h = ((w - 0.4) / (rm - 0.4)).coerceIn(0.0, 1.0)
        return 0.44 + 0.56 * (1.0 - (1.0 - h).pow(3.4))
    }

    /** The hull's smooth outer half-beam at station [u] and height [w]: bluff and round at the bow, broad at the transom. */
    private fun lines(u: Double, w: Double): Double {
        val f = fore(w)
        val a = aft(w)
        if (u >= f || u <= a) return 0.0
        val full = bh * fullness(w)
        return when {
            u > 0.28 * hl -> full * sqrt(1.0 - ((u - 0.28 * hl) / (f - 0.28 * hl)).pow(2.2))
            u < -0.35 * hl -> {
                val t = (-u - 0.35 * hl) / (-a - 0.35 * hl)
                full * (1.0 - 0.3 * t * t)
            }
            else -> full
        }
    }

    /** The hull's outer half-beam at station [u] and height [w] with the planking lapped over the lines; 0 off either end. */
    private fun halfBeam(u: Double, w: Double): Double {
        val base = lines(u, w)
        if (base <= 0.0) return 0.0
        val q = w - 0.8 * keelW(u)
        val lap = 0.5 - (q - floor(q))
        val strake = if (q >= WALE_LOW && q < WALE_LOW + 1.0) 0.4 else 0.0
        return base + (LAP * lap + strake) * min(1.0, base)
    }

    /** The top of the sides at station [u]: the castles' decks at either end, the waist's sheer rising to meet them. */
    private fun railH(u: Double): Double = when {
        u <= uCs + 1.0 -> cd
        u >= uFs -> fd
        else -> rm + 1.8 * (abs(u - 0.3) / hl).pow(1.3)
    }

    /** The first rib's station at or after [u] (ribs stand [ribGap] apart). */
    private fun ribAt(u: Double): Double = ribFirst + ((u - ribFirst) / ribGap).roundToInt() * ribGap

    /** How far the planking has been stripped back from the break at [v], [w]: ragged, a strake at a time. */
    private fun stripped(v: Double, w: Double, k: Int): Double =
        3.4 * Noise.value3(v * 0.55 + 7.0 * k, w * 0.55, 3.0 * k, seed + 40 + k).pow(1.5) + 1.4 * Noise.unit(floor(w).toInt(), floor(v).toInt(), k, seed + 44)

    /** The torn end of the bow half at [v], [w]: ragged, and the deck gone further forward than the keel. */
    private fun cutBow(v: Double, w: Double): Double =
        ubBow + 0.25 * (w - 3.0) + 1.5 * (Noise.value(v * 0.5, w * 0.5, seed + 20) * 2 - 1) + 1.1 * (Noise.unit(floor(v).toInt(), floor(w).toInt(), 1, seed + 21) - 0.5)

    /** The torn end of the stern half at [v], [w]: ragged. */
    private fun cutStern(v: Double, w: Double): Double =
        ubStern + 0.25 * (w - 3.0) + 1.5 * (Noise.value(v * 0.5 + 9.0, w * 0.5, seed + 22) * 2 - 1) + 1.1 * (Noise.unit(floor(v).toInt(), floor(w).toInt(), 2, seed + 23) - 0.5)

    /**
     * What the ship is made of at a point in her frame, or [NONE]: [half] says which half of her it is, so the torn
     * ends are cut and the planking stripped back from them.
     */
    private fun mat(u: Double, v: Double, w: Double, half: Int): Int {
        if (half == BOW && u < cutBow(v, w)) return NONE
        if (half == STERN && u > cutStern(v, w)) return NONE
        val m = hullMat(u, v, w, half)
        return if (m == NONE && half == STERN) rudder(u, v, w) else m
    }

    /** The hull's own material at a point: her skin, her frames and decks, the keel and the posts, the mast's stump. */
    private fun hullMat(u: Double, v: Double, w: Double, half: Int): Int {
        val av = abs(v)
        val top = railH(u)
        if (w > top) return above(u, v, av, w, top, half)
        val hb = halfBeam(u, w)
        if (hb <= 0.0) return NONE
        // The gaps to each of her faces, and so how far in from her skin this point is
        val e = 0.35
        val hbw = (halfBeam(u, w + e) - halfBeam(u, w - e)) / (2 * e)
        val hbu = (halfBeam(u + e, w) - halfBeam(u - e, w)) / (2 * e)
        val gs = (hb - av) / sqrt(1.0 + hbw * hbw + hbu * hbu)
        val kw = keelW(u)
        val ks = (keelW(u + e) - keelW(u - e)) / (2 * e)
        val gb = (w - kw) / sqrt(1.0 + ks * ks)
        val gf = (fore(w) - u) / 1.01
        val ga = (u - aft(w)) / 1.01
        val d = min(min(gs, gb), min(gf, ga))
        if (d < 0.0) return NONE
        // The keel and the posts at her ends
        if (av < 0.65 && w < kw + 1.6) return KEEL
        if (av < 0.65 && (fore(w) - u < 1.5 || u - aft(w) < 1.4)) return STEM
        // The mast's foot, and its stump
        if (half == BOW) {
            val m = mast(u, v, w)
            if (m != NONE) return m
        }
        if (d < SKIN) {
            if (window(u, av, w, gs, ga)) return NONE
            if (!torn(u, v, w, half)) {
                if (w > top - 0.9) return WALE
                val q = w - 0.8 * kw
                if (gs <= min(gb, min(gf, ga)) + 0.05 && q >= WALE_LOW && q < WALE_LOW + 1.0) return WALE
                return PLANK
            }
        }
        // Inside her: the ribs, the deck beams, the keelson, the decks and the castles' walls
        val rib = abs(u - ribAt(u)) < 0.5
        if (d >= SKIN && d < SKIN + 1.0 && rib) return if (gb < gs) RIB_FLOOR else RIB
        if (rib && w >= dk - 2.0 && w < dk - 1.0 && d >= SKIN) return BEAM
        if (av < 0.55 && w >= kw + 1.2 && w < kw + 2.3) return KEELSON
        if (w >= dk - 1.0 && w < dk && !hatch(u, v)) return deck(u, v, w)
        if (u <= uCs + 1.0 && w >= cd - 1.0 && w < cd) return deck(u, v, w)
        if (u >= uFs && w >= fd - 1.0 && w < fd) return deck(u, v, w)
        if (w >= dk && w < dk + 0.5 && coaming(u, v)) return COAMING
        if (u > uCs && u <= uCs + 1.0 && w >= dk && w < cd - 1.0 && !(av < 0.7 && w < dk + 2.2)) return CABIN
        if (u >= uFs - 1.0 && u < uFs && w >= dk && w < fd - 1.0 && !(av < 0.7 && w < dk + 2.2)) return CABIN
        return NONE
    }

    /** A plank of a deck: stove in here and there where the break has racked her. */
    private fun deck(u: Double, v: Double, w: Double): Int =
        if (Noise.value3(u * 0.5, v * 0.5, w * 0.5, seed + 6) > 0.8) NONE else DECK

    /** What stands above the sides: the mast's stump, the stem and the sternpost, the rails round the castles' decks. */
    private fun above(u: Double, v: Double, av: Double, w: Double, top: Double, half: Int): Int {
        if (half == BOW) {
            val m = mast(u, v, w)
            if (m != NONE) return m
        }
        if (av <= 0.65) {
            if (u > uFs && u <= fore(w) && fore(w) - u < 1.5 && w < fd + 1.8) return STEM
            if (u < uCs && u >= aft(w) && u - aft(w) < 1.4 && w < cd + 1.6) return STEM
        }
        val aftCastle = u <= uCs + 1.0
        val foreCastle = u >= uFs
        if (w < top + 1.0 && (aftCastle || foreCastle)) {
            val hb = lines(u, top - 0.5)
            if (hb > 0.0 && av <= hb + 0.1) {
                val side = av > hb - 0.85
                val transom = aftCastle && u - aft(top) < 1.0
                val front = av > 0.95 && ((aftCastle && u > uCs - 0.2) || (foreCastle && u < uFs + 1.0))
                if ((side || transom || front) && Noise.value3(u * 0.8, av * 0.8, w * 0.8, seed + 9) < 0.72) return RAIL
            }
        }
        return NONE
    }

    /** Are the windows of the stern cabin cut through the planking here? */
    private fun window(u: Double, av: Double, w: Double, gs: Double, ga: Double): Boolean {
        if (w < dk + 0.9 || w >= dk + 2.3 || u > uCs - 0.3) return false
        if (ga < gs) return abs(av - 1.7) < 0.5
        val k = (u + hl - 2.0) / 3.0
        return u > -hl + 1.8 && k - floor(k) < 0.4
    }

    /** The hatch in the main deck forward of the mast. */
    private fun hatch(u: Double, v: Double): Boolean = u > uMast + 1.8 && u < uMast + 4.6 && abs(v) < 1.3

    /** The low coaming round the hatch. */
    private fun coaming(u: Double, v: Double): Boolean = u > uMast + 0.8 && u < uMast + 5.6 && abs(v) < 2.3 && !hatch(u, v)

    /** The mast through the deck from the keelson, broken off short in a ragged stump. */
    private fun mast(u: Double, v: Double, w: Double): Int {
        val du = u - uMast
        if (hypot(du, v) > 0.95 || w < keelW(u) + 1.0) return NONE
        val a = atan2(v, du)
        val stump = dk + 1.6 + 3.2 * Noise.value(a * 1.7 + 4.0, 3.0, seed + 50)
        return if (w < stump) MAST else NONE
    }

    /** The rudder, torn from its upper pintle's twin and swung out aft from the sternpost. */
    private fun rudder(u: Double, v: Double, w: Double): Int {
        if (abs(v) > 0.55) return NONE
        val ru = u - (-hl - 0.35)
        val rw = w - (dk + 0.5)
        val along = -ru * sin(swing) - rw * cos(swing)
        val across = ru * cos(swing) - rw * sin(swing)
        if (abs(across) > 0.9 || along < -0.4) return NONE
        if (along > 5.4 + 1.3 * Noise.value(across * 1.3 + 3.0, 1.0, seed + 51)) return NONE
        return if ((along > 0.5 && along < 1.2) || (along > 3.2 && along < 3.9)) WALE else PLANK
    }

    /** Has the planking gone here: a patch stove in, a plank torn out, or stripped back from the break? */
    private fun torn(u: Double, v: Double, w: Double, half: Int): Boolean {
        if (Noise.value3(u * 0.6, v * 0.6, w * 0.6, seed + 5) > 0.82) return true
        val back = if (half == BOW) u - cutBow(v, w) else cutStern(v, w) - u
        return back < stripped(v, w, half)
    }

    // ---- laying her in the world

    /**
     * Fills a half's share of the world: for each voxel, samples her at eight points (two a side each way) and fits the
     * block shape that best covers them (full, slab or stair), as the material most of them are made of.
     */
    private fun voxelize(pose: Pose, half: Int, uLo: Double, uHi: Double): HashMap<Long, Int> {
        val cells = HashMap<Long, Int>()
        var x0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y0 = Int.MAX_VALUE
        var y1 = Int.MIN_VALUE
        var z0 = Int.MAX_VALUE
        var z1 = Int.MIN_VALUE
        for (u in doubleArrayOf(uLo, uHi)) for (v in doubleArrayOf(-bh - 2.0, bh + 2.0)) for (w in doubleArrayOf(-2.0, cd + 12.0)) {
            val px = floor(pose.x(u, v, w)).toInt()
            val py = floor(pose.y(u, v, w)).toInt()
            val pz = floor(pose.z(u, v, w)).toInt()
            x0 = min(x0, px - 1)
            x1 = max(x1, px + 1)
            y0 = min(y0, py - 1)
            y1 = max(y1, py + 1)
            z0 = min(z0, pz - 1)
            z1 = max(z1, pz + 1)
        }
        val m = IntArray(8)
        for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) {
            var occ = 0
            for (o in 0 until 8) {
                val px = x + 0.25 + 0.5 * (o and 1)
                val pz = z + 0.25 + 0.5 * ((o shr 1) and 1)
                val py = y + 0.25 + 0.5 * ((o shr 2) and 1)
                val u = pose.u(px, py, pz)
                val v = pose.v(px, py, pz)
                val w = pose.w(px, py, pz)
                m[o] = if (u < uLo || u > uHi || abs(v) > bh + 1.5 || w < -1.5) NONE else mat(u, v, w, half)
                if (m[o] != NONE) occ = occ or (1 shl o)
            }
            if (occ == 0) continue
            // The material most of the octants are
            val count = IntArray(MATERIALS)
            for (o in 0 until 8) if (m[o] != NONE) count[m[o]]++
            var best = 1
            for (k in 1 until MATERIALS) if (count[k] > count[best]) best = k
            var shape = shapeFor(occ)
            // Logs only come whole, and a rail's posts fill a block if they fill a quarter of it
            if (best in LOGS && shape != AIR) shape = if (Integer.bitCount(occ) >= 4) FULL else AIR
            if (best == RAIL) shape = if (Integer.bitCount(occ) >= 2) FULL else AIR
            if (shape == AIR) continue
            cells[Growth.pack(x, y, z)] = shape or (best shl 8)
        }
        return cells
    }

    /** The block shape that best fills a voxel whose octants are filled as [occ] (bit 4 y + 2 z + x): [AIR], [FULL], a slab or a stair. */
    private fun shapeFor(occ: Int): Int {
        val n = Integer.bitCount(occ)
        var best = AIR
        var cost = n.toDouble()
        val full = 8.0 - n - 0.1
        if (full < cost) {
            best = FULL
            cost = full
        }
        val low = Integer.bitCount(occ and 0x0F)
        val up = Integer.bitCount(occ ushr 4)
        if (abs(low - up) < 2) return best
        var c = Integer.bitCount(occ xor 0x0F) + 0.3
        if (c < cost) {
            best = SLAB_BOTTOM
            cost = c
        }
        c = Integer.bitCount(occ xor 0xF0) + 0.3
        if (c < cost) {
            best = SLAB_TOP
            cost = c
        }
        for (k in STAIRS.indices) {
            c = Integer.bitCount(occ xor STAIRS[k]) + (if (k % 5 == 0) 0.7 else 1.0)
            if (c < cost) {
                best = STAIR + k
                cost = c
            }
        }
        return best
    }

    /** Builds her: both halves, her broken timber, then all that is in and about her. */
    override fun build(b: Build) {
        hullBlocks(b, bowPose, bowCells)
        hullBlocks(b, sternPose, sternCells)
        splinters(b, bowPose, bowCells, BOW)
        splinters(b, sternPose, sternCells, STERN)
        for (cells in listOf(bowCells, sternCells)) for ((p, code) in cells) {
            hull[p] = code
            val c = colKey(Growth.x(p), Growth.z(p))
            val old = hullTop[c]
            if (old == null || Growth.y(p) > old) hullTop[c] = Growth.y(p)
        }
        val cols = HashSet<Long>()
        for (cells in listOf(bowCells, sternCells)) for (p in cells.keys) cols.add(colKey(Growth.x(p), Growth.z(p)))
        hullCols = IntArray(cols.size * 2)
        for ((i, c) in cols.withIndex()) {
            hullCols[2 * i] = Growth.x(c)
            hullCols[2 * i + 1] = Growth.z(c)
        }
        fallenMast(b)
        furnish(b)
        rigging(b)
        spill(b)
        debris(b)
        seaLife(b)
        flushPosts(b)
        spawns(b)
        // Her timbers groaning, from the middle of each half of her
        hullSound(b, bowCells)
        hullSound(b, sternCells)
    }

    /** Where one half of her groans on the rocks (see [SoundSpot.HULL]): the middle of its timbers. */
    private fun hullSound(b: Build, cells: HashMap<Long, Int>) {
        if (cells.isEmpty()) return
        var sx = 0L
        var sy = 0L
        var sz = 0L
        for (p in cells.keys) {
            sx += Growth.x(p)
            sy += Growth.y(p)
            sz += Growth.z(p)
        }
        b.sound(SoundSpot.HULL, (sx / cells.size).toInt(), (sy / cells.size).toInt(), (sz / cells.size).toInt())
    }

    // ---- what's lying about

    /** The key of a column in the maps that go by column. */
    private fun colKey(x: Int, z: Int): Long = Growth.pack(x, 0, z)

    /** The y of the top block of the ground in a column, as the plan leaves it. */
    private fun ground(x: Int, z: Int): Int = terrain.top(x, z) - 1

    /** The y of the highest block in a column, ground or hull. */
    private fun surface(x: Int, z: Int): Int = max(ground(x, z), hullTop[colKey(x, z)] ?: Int.MIN_VALUE)

    /** Is there room at a place: no ground, no hull and nothing of the fittings in it? */
    private fun free(x: Int, y: Int, z: Int): Boolean {
        val p = Growth.pack(x, y, z)
        return y > ground(x, z) && !hull.containsKey(p) && !taken.contains(p)
    }

    /** Is there something under a place to rest a block on: the ground, a hull block or one of the fittings? */
    private fun firm(x: Int, y: Int, z: Int): Boolean {
        val p = Growth.pack(x, y - 1, z)
        val c = hull[p]
        if (c != null) return (c shr 8) != RAIL
        return y - 1 <= ground(x, z) || taken.contains(p)
    }

    /** Sets a block of the fittings, and marks its cell taken. */
    private fun put(b: Build, x: Int, y: Int, z: Int, bk: Bk) {
        b.set(x, y, z, bk)
        taken.add(Growth.pack(x, y, z))
    }

    /** [name] with its properties and, under the sea, water in it. */
    private fun wet(name: String, y: Int, vararg props: Pair<String, String>): Bk = Bk.of(name, *props, "waterlogged" to "${y < sea}")

    /**
     * The cells of a straight run from [a] to [c], one face to the next so it never joins only at an edge, each with the
     * axis the run leaves it by (0 x, 1 y, 2 z).
     */
    private fun run(a: IntArray, c: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        val n = max(abs(c[0] - a[0]), max(abs(c[1] - a[1]), abs(c[2] - a[2])))
        var x = a[0]
        var y = a[1]
        var z = a[2]
        out.add(intArrayOf(x, y, z, 0))
        for (i in 1..max(n, 1)) {
            val tx = a[0] + Math.round((c[0] - a[0]) * i.toDouble() / max(n, 1)).toInt()
            val ty = a[1] + Math.round((c[1] - a[1]) * i.toDouble() / max(n, 1)).toInt()
            val tz = a[2] + Math.round((c[2] - a[2]) * i.toDouble() / max(n, 1)).toInt()
            // One axis at a time, the one furthest to go first
            while (x != tx || y != ty || z != tz) {
                val rx = abs(tx - x)
                val ry = abs(ty - y)
                val rz = abs(tz - z)
                val axis = if (rx >= ry && rx >= rz) 0 else if (rz >= ry) 2 else 1
                out[out.size - 1][3] = axis
                when (axis) {
                    0 -> x += Integer.signum(tx - x)
                    1 -> y += Integer.signum(ty - y)
                    else -> z += Integer.signum(tz - z)
                }
                out.add(intArrayOf(x, y, z, axis))
            }
        }
        return out
    }

    // ---- what's inside her

    /** One free cell standing on something, and where it lies in the frame of the half of her it's in. */
    private class Cell(val x: Int, val y: Int, val z: Int, val u: Double, val v: Double, val w: Double)

    /** Where a half of her lies. */
    private fun poseOf(half: Int): Pose = if (half == BOW) bowPose else sternPose

    /** The voxels of a half of her. */
    private fun cellsOf(half: Int): HashMap<Long, Int> = if (half == BOW) bowCells else sternCells

    /** The way the ship's bow points, as the nearest of the four directions. */
    private fun forward(pose: Pose): Dir = Dir.nearest(atan2(pose.fz, pose.fx))

    /** Every free cell standing on the hull or the ground inside a half's lines, with its place in her frame. */
    private fun floors(half: Int): ArrayList<Cell> {
        val pose = poseOf(half)
        var x0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y0 = Int.MAX_VALUE
        var y1 = Int.MIN_VALUE
        var z0 = Int.MAX_VALUE
        var z1 = Int.MIN_VALUE
        for (p in cellsOf(half).keys) {
            x0 = min(x0, Growth.x(p))
            x1 = max(x1, Growth.x(p))
            y0 = min(y0, Growth.y(p))
            y1 = max(y1, Growth.y(p))
            z0 = min(z0, Growth.z(p))
            z1 = max(z1, Growth.z(p))
        }
        val out = ArrayList<Cell>()
        for (x in x0 - 1..x1 + 1) for (z in z0 - 1..z1 + 1) for (y in y0..y1 + 3) {
            if (!free(x, y, z) || !firm(x, y, z)) continue
            val u = pose.u(x + 0.5, y + 0.5, z + 0.5)
            val v = pose.v(x + 0.5, y + 0.5, z + 0.5)
            val w = pose.w(x + 0.5, y + 0.5, z + 0.5)
            if (if (half == BOW) u < ubBow - 1.0 else u > ubStern + 1.0) continue
            if (w < 0.5 || abs(v) > lines(u, w) - 0.4) continue
            out.add(Cell(x, y, z, u, v, w))
        }
        return out
    }

    /** What a place in her frame is: the hold, the waist's deck, the captain's cabin, the forecastle's store, or the tops of the two castles. */
    private fun zone(u: Double, w: Double): Int {
        if (w < keelW(u) + 0.8) return NOWHERE
        if (w < dk - 0.6) return if (u > aft(w) + 1.0 && u < fore(w) - 1.0) HOLD else NOWHERE
        if (u <= uCs + 0.3) return if (w < cd - 1.1) CABIN_ZONE else if (w >= cd - 0.6 && w < cd + 1.9) POOP_ZONE else NOWHERE
        if (u >= uFs) return if (w < fd - 1.1) STORE_ZONE else if (w >= fd - 0.6 && w < fd + 1.9) FORE_ZONE else NOWHERE
        return if (w < dk + 2.2) DECK_ZONE else NOWHERE
    }

    /** The topmost free cell in the column of [x], [z] between [lo] and [hi] that has something under it, or null. */
    private fun stand(x: Int, z: Int, lo: Int, hi: Int): Int? {
        for (y in hi downTo lo) if (free(x, y, z) && firm(x, y, z)) return y
        return null
    }

    /** Stands her cabin and her holds with what they carried, the bell on her forecastle and the flag on her post. */
    private fun furnish(b: Build) {
        val stern = floors(STERN)
        val bow = floors(BOW)
        cabin(b, stern)
        stow(b, stern)
        stow(b, bow)
        bell(b, bow)
        flag(b)
    }

    /**
     * The captain's cabin in the stern castle, flooded and fallen to ruin: his chest against the transom, a table with
     * a bench each side and a candle on it, his bunk along the high wall, shelves of charts, and the lantern that fell.
     */
    private fun cabin(b: Build, floor: List<Cell>) {
        val fwd = forward(sternPose)
        val high = -list
        val room = floor.filter { zone(it.u, it.w) == CABIN_ZONE }
        // The nearest free cell of the cabin to a point of her frame, within [reach] blocks
        fun near(u: Double, v: Double, reach: Double): Cell? {
            var best: Cell? = null
            var d = reach
            for (c in room) {
                if (!free(c.x, c.y, c.z)) continue
                val e = hypot(c.u - u, c.v - v)
                if (e < d) {
                    d = e
                    best = c
                }
            }
            return best
        }
        // The chest on the high side against the transom: failing that, anywhere in the cabin, and failing that, anywhere in her
        val chest = near(-hl + 2.4, high * 1.5, 2.6) ?: near(-hl + 2.4, high * 1.5, 99.0) ?: floor.filter { free(it.x, it.y, it.z) && zone(it.u, it.w) != NOWHERE }.minByOrNull { hypot(it.u + hl, it.v) }
        if (chest != null) {
            b.chest(chest.x, chest.y, chest.z, fwd, LOOT_CAPTAIN)
            if (chest.y < sea) b.set(chest.x, chest.y, chest.z, Bk.WATER)
            taken.add(Growth.pack(chest.x, chest.y, chest.z))
        }
        // A table with a bench either side
        val table = near(-hl + 5.0, -high * 0.3, 2.0)
        if (table != null) {
            post(b, table.x, table.y, table.z)
            if (free(table.x, table.y + 1, table.z)) {
                put(b, table.x, table.y + 1, table.z, wet("${wood}_slab", table.y + 1, "type" to "bottom"))
                if (free(table.x, table.y + 2, table.z) && Noise.unit(table.x, table.y, table.z, seed + 80) < 0.7) {
                    put(b, table.x, table.y + 2, table.z, wet("candle", table.y + 2, "candles" to "${1 + (Noise.unit(table.x, 1, table.z, seed + 80) * 3).toInt()}", "lit" to "false"))
                }
            }
            for (side in listOf(fwd.cw, fwd.ccw)) {
                val sx = table.x + side.dx
                val sz = table.z + side.dz
                val sy = stand(sx, sz, table.y - 1, table.y + 1) ?: continue
                put(b, sx, sy, sz, wet("${wood}_stairs", sy, "facing" to side.opposite.id, "half" to "bottom", "shape" to "straight"))
            }
        }
        // The lantern that fell with its chain, on the floor by the table
        val lamp = near(-hl + 6.4, high * 1.6, 2.4)
        if (lamp != null) {
            put(b, lamp.x, lamp.y, lamp.z, wet(if (warm) "lantern" else "soul_lantern", lamp.y, "hanging" to "false"))
        }
        // The bunk along the high side, foot to head the way she points: the pair of cells nearest where it stood
        var foot: Cell? = null
        var head: Cell? = null
        var d = 3.2
        for (c in room) {
            if (!free(c.x, c.y, c.z)) continue
            val h = room.firstOrNull { it.x == c.x + fwd.dx && it.z == c.z + fwd.dz && it.y == c.y && free(it.x, it.y, it.z) } ?: continue
            val e = hypot(c.u - (-hl + 3.9), c.v - high * (bh - 2.0))
            if (e < d) {
                d = e
                foot = c
                head = h
            }
        }
        if (foot != null && head != null) {
            val roll = Noise.unit(foot.x, foot.y, foot.z, seed + 81)
            val colour = if (roll < 0.4) "red" else if (roll < 0.7) "blue" else "white"
            put(b, foot.x, foot.y, foot.z, Bk.of("${colour}_bed", "facing" to fwd.id, "part" to "foot", "occupied" to "false"))
            put(b, head.x, head.y, head.z, Bk.of("${colour}_bed", "facing" to fwd.id, "part" to "head", "occupied" to "false"))
        }
        // His charts: shelves two high against the transom on the low side
        for (k in 0..1) {
            val shelf = near(-hl + 2.2, -high * (1.1 + 1.4 * k), 1.2) ?: continue
            put(b, shelf.x, shelf.y, shelf.z, Bk.of("bookshelf"))
            if (free(shelf.x, shelf.y + 1, shelf.z)) put(b, shelf.x, shelf.y + 1, shelf.z, Bk.of("bookshelf"))
        }
    }

    /**
     * Cargo stowed in her holds and left on her decks: barrels (some stacked, some on their sides), bales of wool,
     * hay and honeycomb, sealed pots and a few chests, in heaps that thin out where the floor is bare.
     */
    private fun stow(b: Build, floor: List<Cell>) {
        for (c in floor) {
            if (!free(c.x, c.y, c.z)) continue
            val zn = zone(c.u, c.w)
            val base = when (zn) {
                HOLD -> 0.5
                STORE_ZONE -> 0.32
                DECK_ZONE -> 0.2
                FORE_ZONE -> 0.1
                POOP_ZONE -> 0.07
                else -> 0.0
            }
            if (base == 0.0) continue
            val clump = Noise.value3(c.x * 0.4, c.y * 0.4, c.z * 0.4, seed + 83)
            if (Noise.unit(c.x, c.y, c.z, seed + 82) > base * (0.3 + 1.4 * clump)) continue
            val roll = Noise.unit(c.x, c.y, c.z, seed + 84)
            val inHold = zn == HOLD || zn == STORE_ZONE
            when {
                roll < 0.4 -> {
                    // Upright in the holds, now and then tipped over on the decks
                    val tipped = !inHold && Noise.unit(c.x, c.y, c.z, seed + 85) < 0.3
                    put(b, c.x, c.y, c.z, Bk.of("barrel", "facing" to (if (tipped) Dir.entries[(Noise.unit(c.x, c.y, c.z, seed + 86) * 4).toInt().coerceAtMost(3)].id else "up"), "open" to "false"))
                    if (!tipped && Noise.unit(c.x, c.y, c.z, seed + 87) < (if (inHold) 0.4 else 0.2) && free(c.x, c.y + 1, c.z)) put(b, c.x, c.y + 1, c.z, Bk.of("barrel", "facing" to "up", "open" to "false"))
                }
                roll < 0.56 -> put(b, c.x, c.y, c.z, Bk.of("${BALES[(Noise.unit(c.x, c.y, c.z, seed + 88) * BALES.size).toInt().coerceAtMost(BALES.size - 1)]}_wool"))
                roll < 0.66 -> put(b, c.x, c.y, c.z, Bk.of("hay_block", "axis" to (if (Noise.unit(c.x, c.y, c.z, seed + 89) < 0.5) "x" else "z")))
                roll < 0.72 -> put(b, c.x, c.y, c.z, Bk.of("honeycomb_block"))
                roll < 0.82 && cargoChests < 4 -> {
                    cargoChests++
                    b.chest(c.x, c.y, c.z, Dir.entries[(Noise.unit(c.x, c.y, c.z, seed + 90) * 4).toInt().coerceAtMost(3)], LOOT_CARGO)
                    if (c.y < sea) b.set(c.x, c.y, c.z, Bk.WATER)
                    taken.add(Growth.pack(c.x, c.y, c.z))
                }
                roll < 0.92 -> {
                    put(b, c.x, c.y, c.z, wet("decorated_pot", c.y, "facing" to Dir.entries[(Noise.unit(c.x, c.y, c.z, seed + 91) * 4).toInt().coerceAtMost(3)].id, "cracked" to "false"))
                    if (Noise.unit(c.x, c.y, c.z, seed + 92) < 0.5) b.loot(c.x, c.y, c.z, LOOT_CARGO)
                }
                else -> put(b, c.x, c.y, c.z, Bk.of("barrel", "facing" to "up", "open" to "false"))
            }
        }
    }

    /**
     * The ship's bell, on her forecastle if that's left, else on the deck below it: hung from a beam between two posts
     * that stand each on its own bit of deck, or, where there's less room, from the arm of a single post.
     */
    private fun bell(b: Build, floor: List<Cell>) {
        val across = forward(bowPose).cw
        val target = (uFs + fore(fd)) / 2.0
        val candidates = floor.filter { (zone(it.u, it.w) == FORE_ZONE || zone(it.u, it.w) == DECK_ZONE) && free(it.x, it.y, it.z) }
            .sortedBy { hypot(it.u - target, it.v) + (if (zone(it.u, it.w) == FORE_ZONE) 0.0 else 2.5) }
        for (twin in listOf(false, true)) for (c in candidates.take(80)) {
            val mx = c.x + across.dx
            val mz = c.z + across.dz
            val ex = c.x + 2 * across.dx
            val ez = c.z + 2 * across.dz
            val ye = if (twin) stand(ex, ez, c.y - 2, c.y + 2) ?: continue else c.y
            val top = max(c.y, ye) + 2
            // The beam and the bell's own cell clear, and the posts' columns clear up to the beam
            var clear = free(mx, top, mz) && free(mx, top - 1, mz) && free(c.x, top, c.z) && (!twin || free(ex, top, ez))
            for (y in c.y until top) clear = clear && free(c.x, y, c.z)
            if (twin) for (y in ye until top) clear = clear && free(ex, y, ez)
            if (!clear) continue
            for (y in c.y until top) post(b, c.x, y, c.z)
            if (twin) for (y in ye until top) post(b, ex, y, ez)
            val beam = Bk.of("stripped_${wood}_log", "axis" to across.axis)
            put(b, c.x, top, c.z, beam)
            put(b, mx, top, mz, beam)
            if (twin) put(b, ex, top, ez, beam)
            put(b, mx, top - 1, mz, Bk.of("bell", "attachment" to "ceiling", "facing" to forward(bowPose).id, "powered" to "false"))
            b.sound(SoundSpot.SHIP_BELL, mx, top - 1, mz)
            return
        }
    }

    /** What's left of her flag: a banner standing on the top of whichever of her posts still stands clear of the sea. */
    private fun flag(b: Build) {
        var best: IntArray? = null
        for (cells in listOf(sternCells, bowCells)) for ((p, code) in cells) {
            if ((code shr 8) != STEM) continue
            val y = Growth.y(p)
            if (y < sea || !free(Growth.x(p), y + 1, Growth.z(p))) continue
            if (best == null || y > best[1]) best = intArrayOf(Growth.x(p), y, Growth.z(p))
        }
        val at = best ?: return
        val colour = FLAGS[(Noise.unit(at[0], 0, at[2], seed + 93) * FLAGS.size).toInt().coerceAtMost(FLAGS.size - 1)]
        put(b, at[0], at[1] + 1, at[2], Bk.of("${colour}_banner", "rotation" to "${(Noise.unit(at[0], 1, at[2], seed + 94) * 16).toInt().coerceAtMost(15)}"))
    }

    /** A fence post of the fittings, joined up with its neighbours when all the posts are in. */
    private fun post(b: Build, x: Int, y: Int, z: Int) {
        posts.add(Growth.pack(x, y, z))
        taken.add(Growth.pack(x, y, z))
    }

    /** Sets every post of the fittings, joined to the posts and the whole blocks beside it. */
    private fun flushPosts(b: Build) {
        for (p in posts) {
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            fun joined(dx: Int, dz: Int): String {
                val q = Growth.pack(x + dx, y, z + dz)
                if (posts.contains(q)) return "true"
                val c = hull[q] ?: return "${y <= ground(x + dx, z + dz)}"
                return "${(c shr 8) == RAIL || (c and 0xFF) == FULL}"
            }
            b.set(x, y, z, Bk.of("${wood}_fence", "north" to joined(0, -1), "east" to joined(1, 0), "south" to joined(0, 1), "west" to joined(-1, 0), "waterlogged" to "${y < sea}"))
        }
    }

    // ---- rigging, spilled cargo and the sea taking her back

    /** How far the column ([x], [z]) is from the nearest column either half of her covers, in blocks. */
    private fun hullDist(x: Int, z: Int): Double {
        var best = Double.MAX_VALUE
        var i = 0
        while (i < hullCols.size) {
            val dx = (x - hullCols[i]).toDouble()
            val dz = (z - hullCols[i + 1]).toDouble()
            best = min(best, dx * dx + dz * dz)
            i += 2
        }
        return sqrt(best)
    }

    /** A link of chain along [axis] (0 x, 1 y, 2 z), if there's room for it. */
    private fun link(b: Build, x: Int, y: Int, z: Int, axis: Int): Boolean {
        if (!free(x, y, z)) return false
        put(b, x, y, z, wet("iron_chain", y, "axis" to arrayOf("x", "y", "z")[axis]))
        return true
    }

    /**
     * A line of chain from [a] toward [c], sagging [sag] blocks in the middle and parted [cut] of the way along, its
     * loose end trailing [droop] links down.
     */
    private fun line(b: Build, a: IntArray, c: IntArray, sag: Double, cut: Double, droop: Int, lamp: Boolean = false) {
        val len = max(abs(c[0] - a[0]), max(abs(c[1] - a[1]), abs(c[2] - a[2]))).coerceAtLeast(1)
        val stops = max(2, len / 2)
        val path = ArrayList<IntArray>()
        var prev: IntArray? = null
        for (k in 0..stops) {
            val t = k.toDouble() / stops
            val p = intArrayOf(
                (a[0] + (c[0] - a[0]) * t).roundToInt(),
                (a[1] + (c[1] - a[1]) * t - sag * 4.0 * t * (1.0 - t)).roundToInt(),
                (a[2] + (c[2] - a[2]) * t).roundToInt(),
            )
            if (prev == null) {
                path.add(intArrayOf(p[0], p[1], p[2], 0))
            } else {
                val seg = run(prev, p)
                for (i in 1 until seg.size) path.add(seg[i])
            }
            prev = p
        }
        val n = (path.size * cut).toInt().coerceIn(1, path.size)
        for (i in 0 until n) link(b, path[i][0], path[i][1], path[i][2], path[i][3])
        val end = path[n - 1]
        var hung = 0
        for (d in 1..droop) {
            if (!link(b, end[0], end[1] - d, end[2], 1)) break
            hung = d
        }
        if (lamp && free(end[0], end[1] - hung - 1, end[2])) put(b, end[0], end[1] - hung - 1, end[2], wet(if (warm) "lantern" else "soul_lantern", end[1] - hung - 1, "hanging" to "true"))
    }

    /**
     * What's left of her rigging: shrouds from the sides up toward the mast's head, each parted short of it, lines
     * trailing from the stump, and chains hanging from the yard's ends and from the stem.
     */
    private fun rigging(b: Build) {
        val s = stump
        if (s != null) {
            // The tops of the sides near the stump, for the shrouds to start from, a few on each side
            val anchors = ArrayList<IntArray>()
            for (dx in -7..7) for (dz in -7..7) {
                val x = s[0] + dx
                val z = s[2] + dz
                val d = hypot(dx.toDouble(), dz.toDouble())
                val top = hullTop[colKey(x, z)] ?: continue
                if (d < 2.0 || d > 7.0 || top < s[1] - 4) continue
                val m = hull[Growth.pack(x, top, z)]?.shr(8) ?: continue
                if (m != PLANK && m != WALE && m != RAIL) continue
                val v = bowPose.v(x + 0.5, top + 0.5, z + 0.5)
                if (abs(v) < bh - 2.2) continue
                anchors.add(intArrayOf(x, top + 1, z, if (v > 0) 1 else -1))
            }
            for (side in intArrayOf(-1, 1)) {
                val mine = anchors.filter { it[3] == side }.sortedBy { Noise.unit(it[0], it[1], it[2], seed + 100) }
                for (a in mine.take(2)) {
                    val r = Noise.unit(a[0], a[1], a[2], seed + 101)
                    val head = intArrayOf(s[0] + ((r - 0.5) * 3).roundToInt(), s[1] + 4 + (r * 2).toInt(), s[2] + ((0.5 - r) * 3).roundToInt())
                    line(b, a, head, 0.0, 0.5 + 0.35 * Noise.unit(a[0], a[1], a[2], seed + 102), 1 + (r * 3).toInt())
                }
            }
            // Lines trailing from the stump's top, arcing out and hanging down its side
            for (k in 0 until 3) {
                val ang = Noise.unit(k, 5, s[0] + s[2], seed + 103) * 2 * PI
                val out = intArrayOf(s[0] + (cos(ang) * 3).roundToInt(), s[1] + 2, s[2] + (sin(ang) * 3).roundToInt())
                line(b, intArrayOf(s[0], s[1] + 1, s[2]), out, 1.0, 1.0, 2 + k, lamp = k == 1)
            }
        }
        // From the yard's ends hang the lifts that held it, parted
        for (e in yardEnds) for (d in 1..(2 + (Noise.unit(e[0], e[1], e[2], seed + 104) * 3).toInt())) if (!link(b, e[0], e[1] - d, e[2], 1)) break
        // From the bow half's stem hang the stays that ran down to the water
        var stem: IntArray? = null
        for ((p, code) in bowCells) if ((code shr 8) == STEM && (stem == null || Growth.y(p) > stem[1])) stem = intArrayOf(Growth.x(p), Growth.y(p), Growth.z(p))
        if (stem != null) {
            val f = forward(bowPose)
            for (d in 1..4) if (!link(b, stem[0] + f.dx, stem[1] + 1 - d, stem[2] + f.dz, 1)) break
        }
    }

    /**
     * Cargo spilled when she broke: barrels and bales strewn over the reef below the break, and a chest half sunk in a
     * drift of sand.
     */
    private fun spill(b: Build) {
        val cx = (bowPose.x(ubBow, 0.0, dk) + sternPose.x(ubStern, 0.0, dk)) / 2.0 + bowPose.sx * list * 2.0
        val cz = (bowPose.z(ubBow, 0.0, dk) + sternPose.z(ubStern, 0.0, dk)) / 2.0 + bowPose.sz * list * 2.0
        var left = dice.int(6, 10)
        var tries = 0
        var chest = false
        while (left > 0 && tries < 160) {
            tries++
            val a = dice.range(0.0, 2 * PI)
            val r = dice.range(1.5, 9.5)
            val x = (cx + cos(a) * r).roundToInt()
            val z = (cz + sin(a) * r).roundToInt()
            val g = ground(x, z)
            if (g > sea + 1 || g < sea - 6 || hullDist(x, z) < 1.6 || !free(x, g + 1, z)) continue
            val roll = dice.next()
            left--
            when {
                !chest && roll < 0.2 && g < sea - 1 -> {
                    chest = true
                    // The chest on a bed of sand, banked up on three sides so only its front shows
                    val front = Dir.entries[dice.int(4)]
                    b.set(x, g, z, Bk.of("sand"))
                    b.chest(x, g + 1, z, front, LOOT_CARGO)
                    b.set(x, g + 1, z, Bk.WATER)
                    taken.add(Growth.pack(x, g + 1, z))
                    for (d in Dir.entries) {
                        if (d == front) continue
                        val sx = x + d.dx
                        val sz = z + d.dz
                        val sg = ground(sx, sz)
                        if (sg + 1 <= g + 1 && free(sx, g + 1, sz)) {
                            for (y in max(sg, g - 1)..g + 1) put(b, sx, y, sz, Bk.of("sand"))
                        }
                    }
                    if (free(x, g + 2, z) && dice.chance(0.6)) put(b, x, g + 2, z, Bk.of("sand"))
                }
                roll < 0.6 -> put(b, x, g + 1, z, Bk.of("barrel", "facing" to (if (dice.chance(0.5)) "up" else Dir.entries[dice.int(4)].id), "open" to "false"))
                roll < 0.75 -> put(b, x, g + 1, z, Bk.of("${BALES[dice.int(BALES.size)]}_wool"))
                roll < 0.82 -> put(b, x, g + 1, z, Bk.of("hay_block", "axis" to (if (dice.chance(0.5)) "x" else "z")))
                roll < 0.9 -> put(b, x, g + 1, z, wet("decorated_pot", g + 1, "facing" to Dir.entries[dice.int(4)].id, "cracked" to "${dice.chance(0.3)}"))
                else -> put(b, x, g + 1, z, wet("${wood}_slab", g + 1, "type" to (if (dice.chance(0.5)) "bottom" else "top")))
            }
        }
    }

    /**
     * Wreckage afloat on the water round her: planks and slabs, trapdoors, a barrel or two and scraps of her sail,
     * lying at the surface, thickest on the stern's side where the current takes it.
     */
    private fun debris(b: Build) {
        val sx = sternPose.x(-hl + 3.0, 0.0, dk)
        val sz = sternPose.z(-hl + 3.0, 0.0, dk)
        val mx = (bowPose.x(ubBow, 0.0, dk) + sx) / 2.0
        val mz = (bowPose.z(ubBow, 0.0, dk) + sz) / 2.0
        var left = dice.int(10, 16)
        var tries = 0
        while (left > 0 && tries < 300) {
            tries++
            val a = dice.range(0.0, 2 * PI)
            val r = dice.range(1.5, 11.0)
            val aft = dice.chance(0.6)
            val x = ((if (aft) sx else mx) + cos(a) * r).roundToInt()
            val z = ((if (aft) sz else mz) + sin(a) * r).roundToInt()
            val y = sea - 1
            if (ground(x, z) >= y || !free(x, y, z) || hullDist(x, z) < 1.2) continue
            left--
            val roll = dice.next()
            when {
                roll < 0.3 -> put(b, x, y, z, wet("${wood}_slab", y, "type" to (if (dice.chance(0.5)) "bottom" else "top")))
                roll < 0.45 -> put(b, x, y, z, wet("${wood}_trapdoor", y, "facing" to Dir.entries[dice.int(4)].id, "half" to (if (dice.chance(0.5)) "bottom" else "top"), "open" to "false", "powered" to "false"))
                roll < 0.6 -> put(b, x, y, z, Bk.of("barrel", "facing" to (if (dice.chance(0.5)) "up" else Dir.entries[dice.int(4)].id), "open" to "false"))
                roll < 0.8 -> put(b, x, y, z, Bk.of(if (dice.chance(0.7)) "white_carpet" else "light_gray_carpet"))
                roll < 0.9 -> put(b, x, y, z, wet("${wood}_stairs", y, "facing" to Dir.entries[dice.int(4)].id, "half" to (if (dice.chance(0.5)) "bottom" else "top"), "shape" to "straight"))
                else -> post(b, x, y, z)
            }
        }
    }

    /**
     * The sea taking her back: sand drifted into her hull, bones in it, weed and kelp and sea pickles on the reef
     * round her, and in warm seas coral growing over her planking and the rocks beside it.
     */
    private fun seaLife(b: Build) {
        drift(b)
        bones(b)
        weed(b)
        if (warm) coral(b)
    }

    /**
     * Sand banked on her floors below the surface, a block deep here and two there, where her hull has let the sea in,
     * with seagrass rooted in it.
     */
    private fun drift(b: Build) {
        for (half in intArrayOf(BOW, STERN)) for (c in floors(half)) {
            if (c.y >= sea - 1 || !free(c.x, c.y, c.z)) continue
            val n = Noise.value(c.x * 0.3 + 5.0, c.z * 0.3, seed + 110)
            if (n < 0.52) continue
            put(b, c.x, c.y, c.z, Bk.of("sand"))
            var top = c.y
            if (n > 0.7 && free(c.x, c.y + 1, c.z) && c.y + 1 < sea - 1) {
                put(b, c.x, c.y + 1, c.z, Bk.of("sand"))
                top = c.y + 1
            }
            // Weed in the sand, here and there a tall blade
            val w = Noise.unit(c.x, c.y, c.z, seed + 117)
            if (w > 0.24 || top + 1 > sea - 1 || !free(c.x, top + 1, c.z)) continue
            if (w < 0.08 && top + 2 <= sea - 1 && free(c.x, top + 2, c.z)) {
                b.soft(c.x, top + 1, c.z, Bk.of("tall_seagrass", "half" to "lower"))
                b.soft(c.x, top + 2, c.z, Bk.of("tall_seagrass", "half" to "upper"))
            } else {
                b.soft(c.x, top + 1, c.z, Bk.of("seagrass"))
            }
        }
    }

    /** Bone: fragments of a skeleton in the sand of her holds and on the reef, a skull or two among them. */
    private fun bones(b: Build) {
        var skulls = dice.int(1, 2)
        var bones = dice.int(5, 9)
        var tries = 0
        while ((skulls > 0 || bones > 0) && tries < 500) {
            tries++
            val half = if (dice.chance(0.5)) BOW else STERN
            val cells = if (half == BOW) bowCells else sternCells
            // Inside her, or on the sea bed beside her
            val x: Int
            val z: Int
            if (dice.chance(0.5)) {
                val keys = cells.keys.toList()
                val k = keys[dice.int(keys.size)]
                x = Growth.x(k) + dice.int(-3, 3)
                z = Growth.z(k) + dice.int(-3, 3)
            } else {
                val a = dice.range(0.0, 2 * PI)
                val r = dice.range(2.0, 10.0)
                x = (sternPose.x(-hl + 4.0, 0.0, dk) + cos(a) * r).roundToInt()
                z = (sternPose.z(-hl + 4.0, 0.0, dk) + sin(a) * r).roundToInt()
            }
            // The lowest free cell standing on something, in sand or on the ground
            val g = ground(x, z)
            var y = g + 1
            while (y < sea + 8 && !(free(x, y, z) && firm(x, y, z))) y++
            if (y >= sea || y > g + 6 || !free(x, y, z)) continue
            if (skulls > 0 && dice.chance(0.3)) {
                skulls--
                put(b, x, y, z, Bk.of("skeleton_skull", "rotation" to "${dice.int(16)}", "powered" to "false"))
            } else if (bones > 0) {
                bones--
                val axis = arrayOf("x", "y", "z")[dice.int(3)]
                put(b, x, y, z, Bk.of("bone_block", "axis" to axis))
                if (dice.chance(0.4) && free(x + 1, y, z) && firm(x + 1, y, z)) put(b, x + 1, y, z, Bk.of("bone_block", "axis" to "x"))
            }
        }
    }

    /** Seagrass, kelp and sea pickles on the reef round her, thinning out with the distance. */
    private fun weed(b: Build) {
        var x0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var z0 = Int.MAX_VALUE
        var z1 = Int.MIN_VALUE
        for (i in hullCols.indices step 2) {
            x0 = min(x0, hullCols[i] - 10)
            x1 = max(x1, hullCols[i] + 10)
            z0 = min(z0, hullCols[i + 1] - 10)
            z1 = max(z1, hullCols[i + 1] + 10)
        }
        for (x in x0..x1) for (z in z0..z1) {
            val d = hullDist(x, z)
            if (d > 10.0) continue
            val g = ground(x, z)
            val depth = sea - 1 - g
            if (depth < 1 || !free(x, g + 1, z)) continue
            // Patches of weed: more where the noise is high, less far from the wreck
            val n = Noise.value(x * 0.22, z * 0.22, seed + 111)
            val roll = Noise.unit(x, 3, z, seed + 112)
            val thin = if (d < 2.0) 0.5 else 1.0 - (d - 2.0) / 10.0
            val p = (0.06 + 0.3 * n * n) * thin
            if (roll > p) continue
            val kind = Noise.unit(x, 4, z, seed + 113)
            when {
                kind < 0.38 -> b.soft(x, g + 1, z, Bk.of("seagrass"))
                kind < 0.62 && depth >= 2 -> {
                    b.soft(x, g + 1, z, Bk.of("tall_seagrass", "half" to "lower"))
                    b.soft(x, g + 2, z, Bk.of("tall_seagrass", "half" to "upper"))
                }
                kind < 0.84 && depth >= 4 -> {
                    // Kelp, up toward the surface but not through it
                    var h = min(depth - 1, 3 + (Noise.unit(x, 5, z, seed + 114) * 5).toInt())
                    // Not through her hull or her fittings
                    for (k in 1..h) if (!free(x, g + k, z)) {
                        h = k - 1
                        break
                    }
                    if (h < 2) continue
                    for (k in 1 until h) b.soft(x, g + k, z, Bk.of("kelp_plant"))
                    b.soft(x, g + h, z, Bk.of("kelp", "age" to "${(Noise.unit(x, 6, z, seed + 115) * 25).toInt()}"))
                }
                else -> b.soft(x, g + 1, z, Bk.of("sea_pickle", "pickles" to "${1 + (Noise.unit(x, 7, z, seed + 116) * 4).toInt().coerceAtMost(3)}", "waterlogged" to "true"))
            }
        }
    }

    /**
     * Coral growing over her: blocks of it clustered on her planking below the surface and on the reef beside her,
     * with coral and fans on them, and sea pickles among the fans.
     */
    private fun coral(b: Build) {
        val kinds = listOf("tube", "brain", "bubble", "fire", "horn")
        // Her hull below the surface: where the noise makes a colony, a coral block takes the place of a plank
        for (cells in listOf(bowCells, sternCells)) for ((p, code) in cells) {
            if ((code and 0xFF) != FULL) continue
            val mat = code shr 8
            if (mat != PLANK && mat != WALE && mat != DECK && mat != CABIN) continue
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            if (y >= sea - 1) continue
            val n = Noise.value3(x * 0.22, y * 0.22, z * 0.22, seed + 120)
            if (n < 0.7 || Noise.unit(x, y, z, seed + 121) > 0.55 + (n - 0.7) * 2) continue
            val kind = kinds[(Noise.value3(x * 0.12, y * 0.12, z * 0.12, seed + 122) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
            b.set(x, y, z, Bk.of("${kind}_coral_block"))
        }
        // Coral and fans on any surface of her or the ground that a coral block of hers or the reef stands beside
        val faces = arrayOf(intArrayOf(0, 1, 0), intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))
        val facing = arrayOf("up", "west", "east", "north", "south")
        for (cells in listOf(bowCells, sternCells)) for ((p, code) in cells) {
            if ((code and 0xFF) != FULL) continue
            val x0 = Growth.x(p)
            val y0 = Growth.y(p)
            val z0 = Growth.z(p)
            if (y0 >= sea - 2) continue
            val n = Noise.value3(x0 * 0.2 + 3.0, y0 * 0.2, z0 * 0.2, seed + 123)
            if (n < 0.62) continue
            for ((i, f) in faces.withIndex()) {
                val x = x0 + f[0]
                val y = y0 + f[1]
                val z = z0 + f[2]
                if (!free(x, y, z) || y >= sea - 1) continue
                if (Noise.unit(x, y, z, seed + 124) > 0.22) continue
                val kind = kinds[(Noise.value3(x * 0.15, y * 0.15, z * 0.15, seed + 125) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
                val r = Noise.unit(x, y, z, seed + 126)
                if (i == 0) {
                    when {
                        r < 0.45 -> b.soft(x, y, z, Bk.of("${kind}_coral_fan", "waterlogged" to "true"))
                        r < 0.8 -> b.soft(x, y, z, Bk.of("${kind}_coral", "waterlogged" to "true"))
                        else -> b.soft(x, y, z, Bk.of("sea_pickle", "pickles" to "${1 + (r * 12).toInt() % 3}", "waterlogged" to "true"))
                    }
                } else {
                    b.soft(x, y, z, Bk.of("${kind}_coral_wall_fan", "facing" to facing[i], "waterlogged" to "true"))
                }
            }
        }
        // Colonies on the reef: blocks of coral heaped on the ground, fans and coral on top
        val colonies = dice.int(3, 5)
        var made = 0
        var tries = 0
        while (made < colonies && tries < 200) {
            tries++
            val a = dice.range(0.0, 2 * PI)
            val r = dice.range(2.5, 9.0)
            val cx = (sternPose.x(-hl + 2.0, 0.0, dk) * 0.4 + bowPose.x(ubBow, 0.0, dk) * 0.6 + cos(a) * r).roundToInt()
            val cz = (sternPose.z(-hl + 2.0, 0.0, dk) * 0.4 + bowPose.z(ubBow, 0.0, dk) * 0.6 + sin(a) * r).roundToInt()
            val g = ground(cx, cz)
            if (g > sea - 2 || g < sea - 7 || hullDist(cx, cz) < 2.0) continue
            made++
            val main = kinds[dice.int(kinds.size)]
            val other = kinds[dice.int(kinds.size)]
            val rad = dice.range(1.6, 3.0)
            for (dx in -3..3) for (dz in -3..3) {
                val x = cx + dx
                val z = cz + dz
                val d = hypot(dx.toDouble(), dz.toDouble())
                val gg = ground(x, z)
                if (d > rad || gg >= sea - 2 || !free(x, gg + 1, z)) continue
                val h = Noise.unit(x, 8, z, seed + 127)
                if (h > 1.0 - 0.9 * (1.0 - d / rad)) continue
                val kind = if (Noise.unit(x, 9, z, seed + 128) < 0.7) main else other
                put(b, x, gg + 1, z, Bk.of("${kind}_coral_block"))
                var top = gg + 1
                if (d < rad * 0.5 && h < 0.4 && free(x, gg + 2, z)) {
                    put(b, x, gg + 2, z, Bk.of("${kind}_coral_block"))
                    top = gg + 2
                }
                if (free(x, top + 1, z)) {
                    val r2 = Noise.unit(x, 10, z, seed + 129)
                    when {
                        r2 < 0.4 -> put(b, x, top + 1, z, wet("${kind}_coral_fan", top + 1))
                        r2 < 0.75 -> put(b, x, top + 1, z, wet("${kind}_coral", top + 1))
                        r2 < 0.9 -> put(b, x, top + 1, z, wet("sea_pickle", top + 1, "pickles" to "${1 + (r2 * 20).toInt() % 3}"))
                    }
                }
            }
        }
    }

    /** Her drowned in the flooded hold, and a few fish about her: tropical among her coral in warm seas, cod in the cool. */
    private fun spawns(b: Build) {
        // Cells where a drowned can stand: under the surface, with two free cells above the floor, in her holds and cabin
        val spots = ArrayList<IntArray>()
        val loose = ArrayList<IntArray>()
        for (half in intArrayOf(STERN, BOW)) for (c in floors(half)) {
            if (c.y + 1 > sea - 1 || !free(c.x, c.y + 1, c.z)) continue
            val zn = zone(c.u, c.w)
            if (zn == HOLD || zn == CABIN_ZONE || zn == STORE_ZONE) spots.add(intArrayOf(c.x, c.y, c.z)) else loose.add(intArrayOf(c.x, c.y, c.z))
        }
        spots.sortBy { Noise.unit(it[0], it[1], it[2], seed + 130) }
        loose.sortBy { Noise.unit(it[0], it[1], it[2], seed + 131) }
        spots.addAll(loose)
        val drowned = dice.int(1, 3)
        for (i in 0 until min(drowned, spots.size)) {
            val c = spots[i]
            b.spawn("minecraft:drowned", c[0] + 0.5, c[1].toDouble(), c[2] + 0.5, dice.range(0.0, 360.0).toFloat())
        }
        // Fish about her: swimming up from the reef, clear of her hull
        var fish = dice.int(2, 4)
        val kind = if (warm) "minecraft:tropical_fish" else "minecraft:cod"
        var tries = 0
        while (fish > 0 && tries < 200) {
            tries++
            val a = dice.range(0.0, 2 * PI)
            val r = dice.range(1.0, 9.0)
            val x = (sternPose.x(-hl + 3.0, 0.0, dk) * 0.5 + bowPose.x(ubBow, 0.0, dk) * 0.5 + cos(a) * r).roundToInt()
            val z = (sternPose.z(-hl + 3.0, 0.0, dk) * 0.5 + bowPose.z(ubBow, 0.0, dk) * 0.5 + sin(a) * r).roundToInt()
            val g = ground(x, z)
            val y = g + 2 + dice.int(3)
            if (g > sea - 4 || y >= sea - 1 || !free(x, y, z)) continue
            fish--
            b.spawn(kind, x + 0.5, y.toDouble(), z + 0.5, dice.range(0.0, 360.0).toFloat())
        }
    }

    /**
     * The mast's top, fallen from its stump: a log lying off down the reef with its yard across it, and the torn sail
     * hanging from the yard, draped over the rocks and into the water, with a few lines of rigging cut loose.
     */
    private fun fallenMast(b: Build) {
        // The stump's top
        var topY = Int.MIN_VALUE
        var sx = 0.0
        var sz = 0.0
        var n = 0
        for ((p, code) in bowCells) {
            if ((code shr 8) != MAST) continue
            val y = Growth.y(p)
            if (y > topY) {
                topY = y
                sx = 0.0
                sz = 0.0
                n = 0
            }
            if (y == topY) {
                sx += Growth.x(p)
                sz += Growth.z(p)
                n++
            }
        }
        if (n == 0) return
        val px = floor(sx / n).toInt()
        val pz = floor(sz / n).toInt()
        val py = topY + 1
        stump = intArrayOf(px, topY, pz)
        val len = dice.range(8.5, 11.0)
        // It fell square across her, down the low side of the list if the reef there will take it: of the four ways, the one
        // that leans most to the low side and lands the far end at the sea's edge
        val h0 = atan2(bowPose.sz * list, bowPose.sx * list)
        var fall = Dir.nearest(h0)
        var bestEnd = Double.NEGATIVE_INFINITY
        for (d in Dir.entries) {
            val g = surface(px + (d.dx * len).roundToInt(), pz + (d.dz * len).roundToInt())
            val score = -abs(g - (sea - 1.0)) + 3.0 * (d.dx * cos(h0) + d.dz * sin(h0)) + dice.range(0.0, 1.5)
            if (score > bestEnd) {
                bestEnd = score
                fall = d
            }
        }
        val dx = fall.dx.toDouble()
        val dz = fall.dz.toDouble()
        val ex = px + (dx * len).roundToInt()
        val ez = pz + (dz * len).roundToInt()
        val ey = min(py - 2, surface(ex, ez) + 1)
        val mast = run(intArrayOf(px, py, pz), intArrayOf(ex, ey, ez))
        // Across the log, for its thick end
        val acrossX = if (abs(dx) >= abs(dz)) 0 else 1
        val acrossZ = 1 - acrossX
        for ((i, c) in mast.withIndex()) {
            val axis = arrayOf("x", "y", "z")[c[3]]
            val log = Bk.of("${wood}_log", "axis" to axis)
            if (free(c[0], c[1], c[2])) put(b, c[0], c[1], c[2], log)
            if (i < mast.size * 0.4) {
                if (free(c[0] + acrossX, c[1], c[2] + acrossZ)) put(b, c[0] + acrossX, c[1], c[2] + acrossZ, log)
                if (i < mast.size * 0.25 && free(c[0], c[1] + 1, c[2])) put(b, c[0], c[1] + 1, c[2], log)
            }
        }
        // Where the yard crosses it, and the sail from the yard
        val at = mast[(mast.size * dice.range(0.5, 0.62)).toInt().coerceIn(0, mast.size - 1)]
        val ex2 = -dz
        val ez2 = dx
        val half = dice.range(4.4, 5.4)
        val tilt = dice.range(-0.22, 0.22)
        val yardY = DoubleArray(2 * half.toInt() + 3)
        val yardN = half.toInt()
        for (k in -yardN - 1..yardN + 1) {
            val x = at[0] + (ex2 * k).roundToInt()
            val z = at[2] + (ez2 * k).roundToInt()
            val y = max(at[1] + (tilt * k).roundToInt() + 1, surface(x, z) + 1)
            yardY[k + yardN + 1] = y.toDouble()
        }
        for (k in -yardN - 1..yardN + 1) {
            val x = at[0] + (ex2 * k).roundToInt()
            val z = at[2] + (ez2 * k).roundToInt()
            val y = yardY[k + yardN + 1].toInt()
            if (free(x, y, z)) put(b, x, y, z, Bk.of("stripped_${wood}_log", "axis" to (if (abs(ex2) >= abs(ez2)) "x" else "z")))
            if (abs(k) >= yardN) yardEnds.add(intArrayOf(x, y, z))
        }
        // The sail hangs from the yard on the hull's side, so it lies over the rail and the deck
        drape(b, at, ex2, ez2, -dx, -dz, yardN, yardY)
        bundle(b)
    }

    /**
     * What's left of the sail's foot: canvas heaped about the mast's stump and trailing over the planking of the deck,
     * white and weathered grey.
     */
    private fun bundle(b: Build) {
        val s = stump ?: return
        for (dx in -4..4) for (dz in -4..4) {
            val d = hypot(dx.toDouble(), dz.toDouble())
            if (d < 0.9 || d > 4.2) continue
            val x = s[0] + dx
            val z = s[2] + dz
            val top = hullTop[colKey(x, z)] ?: continue
            val code = hull[Growth.pack(x, top, z)] ?: continue
            // Only on the planked deck, not along a rail
            if ((code shr 8) != DECK || !free(x, top + 1, z)) continue
            val n = Noise.unit(x, top, z, seed + 52)
            if (n > 0.7 - 0.12 * d) continue
            val tone = Noise.unit(x, top, z, seed + 53)
            val colour = if (tone < 0.55) "white" else if (tone < 0.9) "light_gray" else "gray"
            // A heap near the stump, flattening out to carpet away from it
            val heap = d < 2.4 && n < 0.3
            put(b, x, top + 1, z, Bk.of(if (heap) "${colour}_wool" else "${colour}_carpet"))
            if (heap && d < 1.6 && n < 0.12 && free(x, top + 2, z)) put(b, x, top + 2, z, Bk.of("${colour}_carpet"))
        }
    }

    /**
     * Drapes the torn sail from the yard: columns of wool hanging from it, ragged at the foot and with holes and rips
     * in them, and where a column meets the ground or her hull the rest of it lying out over that as carpet, toward
     * [cx], [cz]. Light grey and white, some of it under the water.
     */
    private fun drape(b: Build, at: IntArray, ex: Double, ez: Double, cx: Double, cz: Double, yardN: Int, yardY: DoubleArray) {
        // Where it flaps and cracks in the wind
        b.sound(SoundSpot.SAIL, at[0], at[1] - 4, at[2])
        val height = dice.range(7.0, 9.0)
        val reach = yardN.toDouble()
        // Two long rips run down it on a slant, so it hangs in ragged pieces
        val ripA = dice.range(-reach * 0.7, reach * 0.7)
        val ripB = dice.range(-reach * 0.7, reach * 0.7)
        val slant = dice.range(0.12, 0.4) * (if (dice.chance(0.5)) 1.0 else -1.0)
        for (a in -yardN - 1..yardN + 1) {
            // How far out of the yard's line the piece belly or sags, toward the hull
            val belly = Noise.value(a * 0.45 + 4.0, 3.0, seed + 36) * 2.6 - 0.5
            val top = yardY[a + yardN + 1].toInt() - 1
            val x0 = at[0] + (ex * a).roundToInt()
            val z0 = at[2] + (ez * a).roundToInt()
            // A square sail: full length, ragged at the hem, with strips torn up from it here and there
            var len = height * (0.6 + 0.4 * Noise.value(a * 0.9 + 2.0, 6.0, seed + 34))
            if (Noise.unit(a, 7, at[0] + at[2], seed + 40) < 0.3) len *= 0.3 + 0.4 * Noise.unit(a, 8, at[0] + at[2], seed + 40)
            val total = len.roundToInt()
            var x = x0
            var z = z0
            var y = top
            var used = 0
            while (used < total) {
                // The sail swells out from the yard through its middle and comes back in at the foot
                val out = (belly * sin(PI * (used + 1.0) / (total + 2.0))).roundToInt()
                x = x0 + (cx * out).roundToInt()
                z = z0 + (cz * out).roundToInt()
                if (!free(x, y, z)) break
                val torn = used >= 2 && (abs(a - ripA - slant * used) < 0.8 || abs(a - ripB + slant * used) < 0.6)
                val hole = used >= 1 && Noise.value3(a * 0.7 + 3.0, y * 0.6, 1.0, seed + 35) > 0.74
                if (!torn && !hole) cloth(b, x, y, z, a, total - used, true)
                y--
                used++
            }
            // The rest lies out over what stopped it, a block a step, up and down with the ground
            var px = x
            var pz = z
            var py = y + 1
            for (j in 1..total - used) {
                val nx = x + (cx * j).roundToInt()
                val nz = z + (cz * j).roundToInt()
                if (nx == px && nz == pz) continue
                px = nx
                pz = nz
                val ny = surface(nx, nz) + 1
                if (abs(ny - py) > 2 || !free(nx, ny, nz)) break
                py = ny
                if (Noise.value3(a * 0.7 + 3.0, j * 0.8, ny * 0.5, seed + 37) > 0.74) continue
                cloth(b, nx, ny, nz, a, total - used - j, ny < sea - 1 && Noise.unit(nx, ny, nz, seed + 38) < 0.5)
            }
        }
    }

    /**
     * A piece of the sail, at column [a] across it and [left] cloths from its hem: wool, or carpet where it lies flat.
     * Panels of white and weathered grey run up and down it, and the hem is sooty.
     */
    private fun cloth(b: Build, x: Int, y: Int, z: Int, a: Int, left: Int, wool: Boolean) {
        val panel = Math.floorDiv(a + 12, 3)
        val grey = Noise.unit(panel, 3, 1, seed + 39) < 0.3
        val colour = when {
            left <= 2 && Noise.unit(x, y, z, seed + 41) < 0.35 -> "gray"
            grey || Noise.unit(x, y, z, seed + 38) < 0.07 -> "light_gray"
            else -> "white"
        }
        if (free(x, y, z)) put(b, x, y, z, Bk.of(if (wool) "${colour}_wool" else "${colour}_carpet"))
    }

    /** Lays every voxel of a half's hull: the block its shape and material make. */
    private fun hullBlocks(b: Build, pose: Pose, cells: HashMap<Long, Int>) {
        for ((p, code) in cells) {
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            val mat = code shr 8
            val bk = if (mat == RAIL) fence(cells, x, y, z) else blockFor(mat, code and 0xFF, pose, x, y, z)
            if (bk != null) b.set(x, y, z, bk)
        }
    }

    /** A post of the rail, joined to the rail posts and planking beside it. */
    private fun fence(cells: HashMap<Long, Int>, x: Int, y: Int, z: Int): Bk {
        fun joined(dx: Int, dz: Int): String {
            val c = cells[Growth.pack(x + dx, y, z + dz)] ?: return "false"
            return "${(c shr 8) == RAIL || (c and 0xFF) == FULL}"
        }
        return Bk.of("${wood}_fence", "north" to joined(0, -1), "east" to joined(1, 0), "south" to joined(0, 1), "west" to joined(-1, 0), "waterlogged" to "${y < sea}")
    }

    /**
     * Broken timber standing out from a half's torn end: fence posts, open trapdoors and stray slabs and stairs
     * hanging from the planks the break left ragged.
     */
    private fun splinters(b: Build, pose: Pose, cells: HashMap<Long, Int>, half: Int) {
        val sg = if (half == BOW) -1.0 else 1.0
        val ax = sg * pose.fx
        val az = sg * pose.fz
        val dx = if (abs(ax) >= abs(az)) (if (ax > 0) 1 else -1) else 0
        val dz = if (abs(ax) < abs(az)) (if (az > 0) 1 else -1) else 0
        val toward = if (dx != 0) (if (dx > 0) Dir.E else Dir.W) else (if (dz > 0) Dir.S else Dir.N)
        for ((p, code) in cells) {
            val mat = code shr 8
            if (mat == RAIL || mat == MAST || mat == STEM || mat == COAMING) continue
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            if (Noise.unit(x, y, z, seed + 70) > 0.4) continue
            val u = pose.u(x + 0.5, y + 0.5, z + 0.5)
            val v = pose.v(x + 0.5, y + 0.5, z + 0.5)
            val w = pose.w(x + 0.5, y + 0.5, z + 0.5)
            val back = if (half == BOW) u - cutBow(v, w) else cutStern(v, w) - u
            if (back > 1.3) continue
            val nx = x + dx
            val nz = z + dz
            if (cells.containsKey(Growth.pack(nx, y, nz))) continue
            val wet = y < sea
            val kind = Noise.unit(x, y, z, seed + 71)
            val flip = if (Noise.unit(x, y, z, seed + 72) < 0.5) "bottom" else "top"
            val bk = when {
                kind < 0.4 -> Bk.of("${wood}_fence", toward.opposite.id to "true", "waterlogged" to "$wet")
                kind < 0.65 -> Bk.of("${wood}_trapdoor", "facing" to toward.id, "half" to flip, "open" to "true", "powered" to "false", "waterlogged" to "$wet")
                kind < 0.85 -> Bk.of("${wood}_slab", "type" to flip, "waterlogged" to "$wet")
                else -> Bk.of("${wood}_stairs", "facing" to Dir.entries[(Noise.unit(x, y, z, seed + 73) * 4).toInt().coerceAtMost(3)].id, "half" to "bottom", "shape" to "straight", "waterlogged" to "$wet")
            }
            b.set(nx, y, nz, bk)
        }
    }

    /** The block of [mat] in [shape] at a place, waterlogged under the sea where it can hold water. */
    private fun blockFor(mat: Int, shape: Int, pose: Pose, x: Int, y: Int, z: Int): Bk? {
        val wet = y < sea
        val roll = Noise.unit(x, y, z, seed + 3)
        val plank = when (mat) {
            WALE, COAMING -> waleBlock
            PLANK, CABIN -> patched(x, y, z, roll)
            DECK -> if (roll < 0.15) "${wood}_planks" else deckPlanks()
            else -> "${wood}_planks"
        }
        if (shape == FULL) {
            val axis = when (mat) {
                KEEL, KEELSON -> axisOf(pose.fx, pose.fy, pose.fz)
                STEM, MAST, RIB -> axisOf(pose.ux, pose.uy, pose.uz)
                BEAM, RIB_FLOOR -> axisOf(pose.sx, pose.sy, pose.sz)
                else -> null
            }
            return when (mat) {
                KEEL, KEELSON, STEM, RIB, RIB_FLOOR, BEAM -> Bk.of("stripped_${wood}_log", "axis" to axis!!)
                MAST -> Bk.of("${wood}_log", "axis" to axis!!)
                else -> Bk.of(plank)
            }
        }
        val family = if (mat == WALE || mat == COAMING) waleFamily else wood
        return when {
            shape == SLAB_BOTTOM -> Bk.of("${family}_slab", "type" to "bottom", "waterlogged" to "$wet")
            shape == SLAB_TOP -> Bk.of("${family}_slab", "type" to "top", "waterlogged" to "$wet")
            shape >= STAIR -> {
                val k = shape - STAIR
                Bk.of("${family}_stairs", "facing" to FACINGS[k / 10], "half" to (if ((k / 5) % 2 == 0) "bottom" else "top"), "shape" to SHAPES[k % 5], "waterlogged" to "$wet")
            }
            else -> null
        }
    }

    /** The decks are paler than the sides, bleached by the sun. */
    private fun deckPlanks(): String = when (wood) {
        "dark_oak" -> "spruce_planks"
        "spruce" -> "oak_planks"
        else -> "${wood}_planks"
    }

    /**
     * A plank of her planking: her own wood, with patches of newer, paler timber and older, darker where she's been
     * mended, and in the cool seas some of it bleached grey by salt.
     */
    private fun patched(x: Int, y: Int, z: Int, roll: Double): String {
        val patch = Noise.value3(x * 0.4, y * 0.55, z * 0.4, seed + 4)
        val dark = wood == "dark_oak"
        // The darkest wood needs more of the paler, or the hull is a black mass
        val newer = if (dark) 0.64 else 0.76
        if (!warm && Noise.value3(x * 0.3, y * 0.45, z * 0.3, seed + 5) > (if (dark) 0.66 else 0.78)) return "pale_oak_planks"
        return when {
            patch > newer || roll < 0.02 -> if (dark) "spruce_planks" else "oak_planks"
            (patch < 0.2 || roll > 0.985) && !dark -> "dark_oak_planks"
            else -> "${wood}_planks"
        }
    }

    /** The axis a log lying along (x, y, z) runs on. */
    private fun axisOf(x: Double, y: Double, z: Double): String {
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return if (ay >= ax && ay >= az) "y" else if (ax >= az) "x" else "z"
    }

    companion object {
        /** What a point of the ship is made of. */
        const val NONE = 0
        const val PLANK = 1
        const val WALE = 2
        const val DECK = 3
        const val KEEL = 4
        const val KEELSON = 5
        const val STEM = 6
        const val RIB = 7
        const val RIB_FLOOR = 8
        const val BEAM = 9
        const val MAST = 10
        const val CABIN = 11
        const val RAIL = 12
        const val COAMING = 13
        const val MATERIALS = 14

        /** The materials that only come as whole logs. */
        val LOGS = setOf(KEEL, KEELSON, STEM, RIB, RIB_FLOOR, BEAM, MAST)

        /** Which half of the ship. */
        const val BOW = 0
        const val STERN = 1

        /** The rooms of her: nowhere, the hold, the waist's deck, the captain's cabin, the forecastle's store and the tops of the forecastle and the stern castle. */
        const val NOWHERE = 0
        const val HOLD = 1
        const val DECK_ZONE = 2
        const val CABIN_ZONE = 3
        const val STORE_ZONE = 4
        const val FORE_ZONE = 5
        const val POOP_ZONE = 6

        /** The colours of the bales in her holds and of the flag she flew. */
        val BALES = listOf("white", "red", "blue", "yellow", "green", "brown", "orange", "purple", "light_gray")
        val FLAGS = listOf("red", "blue", "black", "yellow", "white")

        /** How thick her planking is, so a tilted skin still holds together. */
        const val SKIN = 1.25

        /** How far each strake's lower edge stands proud of the one below, and where the wale's strake starts above the keel's line. */
        const val LAP = 0.4
        const val WALE_LOW = 3.0

        /** How far the new ground slopes out from the hull. */
        const val RAMP = 5

        /** Block shapes: nothing, a whole block, a slab (bottom or top), and from [STAIR] up the 40 stairs. */
        const val AIR = 0
        const val FULL = 1
        const val SLAB_BOTTOM = 2
        const val SLAB_TOP = 3
        const val STAIR = 4

        val FACINGS = arrayOf("east", "south", "west", "north")
        val SHAPES = arrayOf("straight", "inner_left", "inner_right", "outer_left", "outer_right")

        /** The octants (bit 4 y + 2 z + x) each stair fills, by facing (east, south, west, north), half (bottom, top), then shape. */
        val STAIRS = intArrayOf(
            0xAF, 0xBF, 0xEF, 0x2F, 0x8F, 0xFA, 0xFB, 0xFE, 0xF2, 0xF8,
            0xCF, 0xEF, 0xDF, 0x8F, 0x4F, 0xFC, 0xFE, 0xFD, 0xF8, 0xF4,
            0x5F, 0xDF, 0x7F, 0x4F, 0x1F, 0xF5, 0xFD, 0xF7, 0xF4, 0xF1,
            0x3F, 0x7F, 0xBF, 0x1F, 0x2F, 0xF3, 0xF7, 0xFB, 0xF1, 0xF2,
        )

        const val LOOT_CAPTAIN = "guildmark:chests/island/wreck_captain"
        const val LOOT_CARGO = "guildmark:chests/island/wreck_cargo"
    }
}
