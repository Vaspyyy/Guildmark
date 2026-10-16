package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A shrine to the Maelgrasp, the great grasping thing of the deep, which seafolk raised on the shore and the sea has
 * since half swallowed. A stepped platform of prismarine and weathered stone climbs out of the deep onto the land: grand
 * steps run down from it into the sea, a broken colonnade marches down beside them, and behind a flooded round pit, at
 * the head of it all, the idol of the Maelgrasp rears up with its arms curled over the water. In the pit's drowned sanctum
 * a conduit burns in its frame of prismarine over an altar, with offerings lying in the sand about it.
 *
 * Everything is laid out in a frame of its own: [u] runs out to sea from the middle of the pit and [v] across, to the
 * right of that.
 */
class Shrine(plan: IslandPlan) : Landmark(plan) {
    private val dice = Dice(plan.spec.seed xor 0x5A12EL)
    private val seed = Noise.seed(plan.spec.seed, 62)
    private val climate = plan.climate
    private val warm = climate == Climate.WARM || climate == Climate.LUKEWARM
    private val cold = climate == Climate.COLD || climate == Climate.FROZEN

    /** The weathered stone the shrine's land half is raised of, by the sea it stands in. */
    private val stone: Stone = when (climate) {
        Climate.WARM -> Stone.SAND
        Climate.LUKEWARM, Climate.TEMPERATE -> Stone.BRICK
        Climate.COLD, Climate.FROZEN -> Stone.DEEP
    }

    /** How wide the sanctum's pit is, how many petals of arms the idol has to a side and how tall it stands, and what its accents are. */
    private val pit = dice.int(5, 6)
    private val petals = dice.int(2, 3)
    private val idolHeight = dice.int(13, 15)
    private val crimson = dice.chance(0.5)

    /** How far the terrace stands over the sea, how deep the steps go, and how tall a whole pillar is. */
    private val lift = dice.int(3, 4)
    private val deepEnd = dice.int(14, 17)
    private val flank = dice.int(9, 13)
    private val pillarHeight = dice.int(6, 8)

    /** Which way round the sanctum the winding stair goes down (1 clockwise), and how much of the colonnade still stands. */
    private val hand = if (dice.chance(0.5)) 1 else -1
    private val standing = dice.range(0.55, 0.85)

    /** How much of the idol has broken: the share of its petals snapped off short; and the colour the faded banners were. */
    private val ruin = dice.range(0.1, 0.5)
    private val banner = dice.pick(listOf("cyan", "light_blue", "blue"))

    /** The corals the reef round its feet grows (none in cold seas), and the way the pillars' beams run. */
    private val corals: List<String> = if (warm) {
        val all = arrayListOf("tube", "brain", "bubble", "fire", "horn")
        List(3) { all.removeAt(dice.int(all.size)) }
    } else emptyList()

    // The plan of it, in blocks from the pit's middle: the pit's back wall, the idol's end, the terrace's width and front, the steps
    private val uWall = 1 - pit
    private val uBack = uWall - IDOL
    private val half = pit + 3
    private val uFront = pit + 3
    private val uSteps = uFront + 2 * TREAD
    private val uFlank = uSteps + flank
    private val terrace: Int = sea + lift
    private val floorTop: Int = sea - FLOOR_DEPTH
    private val uEnd = uSteps + lift - 4 + deepEnd

    /** Where the pit's middle is, and which way is out to sea. */
    private var ax = plan.spec.cx
    private var az = plan.spec.cz
    private var out = Dir.E
    private val right: Dir get() = out.cw

    /** Everything it's made of, as it's built. */
    private lateinit var b: Build

    /** Walls and stairs placed, to be joined up with their neighbours once all's in. */
    private val joins = ArrayList<Long>()

    private enum class Stone { BRICK, SAND, DEEP }

    /** The four families of block the shrine is raised of. */
    private enum class Mat { PRISMARINE, BRICKS, DARK, STONE }

    // ---- the site

    override fun lay() {
        chooseSite()
        reshape()
        // Keep the trees off all of it, and a little way round
        var x0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var z0 = Int.MAX_VALUE
        var z1 = Int.MIN_VALUE
        for (u in uBack..uEnd) for (v in -half..half) {
            if (levelAt(u, v) == NONE) continue
            x0 = min(x0, wx(u, v))
            x1 = max(x1, wx(u, v))
            z0 = min(z0, wz(u, v))
            z1 = max(z1, wz(u, v))
        }
        plan.claim(x0 - 2, z0 - 2, x1 + 2, z1 + 2)
    }

    override fun paint() {
        for (u in uBack..uEnd) for (v in -half..half) if (levelAt(u, v) != NONE) plan.paint(wx(u, v), wz(u, v), Ground.BUILT)
    }

    /**
     * Finds where on the coast it stands and which way it faces: the shore must run across the hall, with land behind
     * wide enough for the terrace and open sea ahead for the steps, on a coast that's not a cliff, clear of the bar of a
     * twin island and of the sea stacks.
     */
    private fun chooseSite() {
        val t = terrain
        val shore = uFront + TREAD
        var best = Double.NEGATIVE_INFINITY
        for (d in Dir.entries) {
            for (x in t.x0 + 10 until t.x0 + t.size - 10) for (z in t.z0 + 10 until t.z0 + t.size - 10) {
                if (abs(t.distance(x + d.dx * shore, z + d.dz * shore)) > 3.0) continue
                val score = siteScore(x, z, d, shore)
                if (score > best) {
                    best = score
                    ax = x
                    az = z
                    out = d
                }
            }
        }
    }

    /** How well the shrine would sit with its pit's middle at [x], [z] and its steps running out [d]: higher is better. */
    private fun siteScore(x: Int, z: Int, d: Dir, shore: Int): Double {
        val t = terrain
        val rt = d.cw
        fun px(u: Int, v: Int) = x + d.dx * u + rt.dx * v
        fun pz(u: Int, v: Int) = z + d.dz * u + rt.dz * v
        for (u in intArrayOf(uBack - BANK, uEnd)) for (v in intArrayOf(-half - BANK, half + BANK)) {
            if (!t.inside(px(u, v), pz(u, v))) return Double.NEGATIVE_INFINITY
        }
        var score = 0.0
        // Land enough behind the shore, and not too high a hill to cut into
        var dry = 0
        var all = 0
        var cut = 0.0
        for (u in uBack..uFront step 2) for (v in -half..half step 3) {
            all++
            if (t.dry(px(u, v), pz(u, v))) {
                dry++
                cut += max(0.0, t.h(px(u, v), pz(u, v)) - lift)
            }
        }
        score -= 26.0 * (1.0 - dry.toDouble() / all) + 1.2 * cut / max(1, dry)
        // Open sea ahead for the hall and the steps
        var wet = 0
        all = 0
        for (u in uFront + 5..uEnd step 3) for (v in -HALL..HALL step 4) {
            all++
            if (!t.dry(px(u, v), pz(u, v))) wet++
        }
        score -= 40.0 * (1.0 - wet.toDouble() / all)
        // The shore where the hall meets the sea, on a coast that's no cliff
        score -= 1.5 * abs(t.distance(px(shore, 0), pz(shore, 0)))
        var cliffs = 0.0
        var n = 0
        for (u in uFront - 6..uFront + 10 step 2) for (v in -HALL..HALL step 4) {
            cliffs += t.cliffAt(px(u, v), pz(u, v))
            n++
        }
        score -= 18.0 * cliffs / n
        // Not across the twin's bar, nor through a sea stack
        var bar = 0
        for (u in uBack..uFront step 2) for (v in -half..half step 2) if (t.onBar(px(u, v), pz(u, v))) bar++
        score -= 0.7 * min(bar, 30)
        for (s in t.stacks) {
            val sx = plan.spec.cx + s.x
            val sz = plan.spec.cz + s.z
            for (u in uBack..uEnd step 4) for (v in -HALL..HALL step 4) {
                if (hypot(px(u, v) - sx, pz(u, v) - sz) < s.radius + 3) {
                    score -= 12.0
                    break
                }
            }
        }
        // Near the island's middle, but any of the good sites will do
        score -= 0.05 * hypot((x - plan.spec.cx).toDouble(), (z - plan.spec.cz).toDouble())
        return score + 2.0 * Noise.unit(x, d.ordinal, z, seed + 1)
    }

    /**
     * Raises, lowers and levels the ground under the whole of it to the heights it stands at; the land round its back and
     * sides is cut or built up to meet the terrace over a few blocks, and the sea there is banked up with sand.
     */
    private fun reshape() {
        val t = terrain
        for (u in uBack - BANK..uFront + 4) for (v in -half - BANK..half + BANK) {
            if (levelAt(u, v) != NONE) continue
            val d = hypot(max(uBack - u, 0).toDouble(), max(abs(v) - half, 0).toDouble())
            if (d > BANK) continue
            val x = wx(u, v)
            val z = wz(u, v)
            val h = t.h(x, z)
            val top = if (u > uFront) lift - 2.0 else lift.toDouble()
            if (h > 0.0) {
                t.set(x, z, lerp(h, top, 1.0 - smooth(0.0, BANK + 0.5, d)))
            } else {
                t.set(x, z, max(h, top - 1.0 - 0.95 * d))
            }
        }
        // Then every column of it stands where its level says
        for (u in uBack..uEnd) for (v in -half..half) {
            val level = levelAt(u, v)
            if (level != NONE) t.set(wx(u, v), wz(u, v), (level - sea).toDouble())
        }
    }

    // ---- the lie of it

    /** The pit: a round hole, cut off flat at the idol's end. */
    private fun inPit(u: Int, v: Int): Boolean = u >= uWall && u * u + v * v <= pit * pit + pit

    /** The pit's ring: the cells by its wall, which its winding stair goes round. */
    private fun inRing(u: Int, v: Int): Boolean {
        if (!inPit(u, v)) return false
        for (du in -1..1) for (dv in -1..1) if (!inPit(u + du, v + dv)) return true
        return false
    }

    /** The terrace's back corners are cut off. */
    private fun chamfered(u: Int, v: Int): Boolean = (u - uBack) + (half - abs(v)) < 2

    /** Which tier of the hall [u] is on, counting from the terrace's front. */
    private fun tierOf(u: Int): Int = (u - uFront - 1) / TREAD + 1

    /** The level of the hall's tiers at [u]: the first block of air over the floor, flat for four blocks and then two lower. */
    private fun tierLevel(u: Int): Int = terrace - 2 * tierOf(u)

    /** The level of the way down the middle at [u], from the head of the steps: a block lower with every block out. */
    private fun stepLevel(u: Int): Int = terrace - 4 - (u - uSteps)

    /** The first block of air over the ground of the shrine at local ([u], [v]), or [NONE] if the shrine doesn't reach there. */
    private fun levelAt(u: Int, v: Int): Int {
        val av = abs(v)
        if (inPit(u, v)) return floorTop
        if (u in uBack..uFront && av <= half && !chamfered(u, v)) return terrace
        if (u in uFront + 1..uEnd && av <= HALL) {
            if (u >= uSteps && av <= WAY) return stepLevel(u)
            if (u <= uFlank) return tierLevel(u)
        }
        return NONE
    }

    /** What lies at ([u], [v]) as far as the shrine's concerned: its own level, or the planned ground beside it. */
    private fun levelOrGround(u: Int, v: Int): Int {
        val level = levelAt(u, v)
        return if (level != NONE) level else terrain.top(wx(u, v), wz(u, v))
    }

    // ---- the frame it's laid in

    private fun wx(u: Int, v: Int): Int = ax + out.dx * u + right.dx * v

    private fun wz(u: Int, v: Int): Int = az + out.dz * u + right.dz * v

    /** The way in the world that local direction ([du], [dv]) (one of them 0) points. */
    private fun dirOf(du: Int, dv: Int): Dir {
        val x = out.dx * du + right.dx * dv
        val z = out.dz * du + right.dz * dv
        return Dir.entries.first { it.dx == x && it.dz == z }
    }

    /** Noise in [0, 1) at a local cell, for the use [k]. */
    private fun unit(u: Int, v: Int, y: Int, k: Int): Double = Noise.unit(wx(u, v), y, wz(u, v), seed + k)

    // ---- building it

    override fun build(b: Build) {
        this.b = b
        platform()
        faces()
        risers()
        steps()
        sanctum()
        rim()
        colonnade()
        idol()
        offerings()
        sea()
        land()
        eldritch()
        debris()
        lights()
        creatures()
        join()
    }

    /** The platform: every column paved at its level, faced in masonry down to whatever lies lower beside it, and the sea written in over what's sunk. */
    private fun platform() {
        for (u in uBack..uEnd) for (v in -half..half) {
            val level = levelAt(u, v)
            if (level == NONE || inPit(u, v)) continue
            val x = wx(u, v)
            val z = wz(u, v)
            var low = level
            for ((du, dv) in NEIGHBOURS) low = min(low, levelOrGround(u + du, v + dv))
            val bordersPit = NEIGHBOURS.any { (du, dv) -> inPit(u + du, v + dv) }
            for (y in max(low, level - 24) until level - 1) put(x, y, z, if (bordersPit) pitWall(u, v, x, y, z) else faceBlock(x, y, z))
            for (y in level until sea) put(x, y, z, Bk.WATER)
            // The steps lay their own
            if (u >= uSteps && abs(v) <= WAY) continue
            put(x, level - 1, z, pave(u, v, x, level - 1, z))
        }
    }

    /**
     * The platform's drops dressed: a rib of dark masonry down every fourth block of them with a cornice of upside-down
     * stairs under the paving between, and along the steps out in the deep a low parapet with a lit post every fourth.
     */
    private fun faces() {
        for (u in uBack..uEnd) for (v in -half..half) {
            val level = levelAt(u, v)
            if (level == NONE || inPit(u, v)) continue
            for ((du, dv) in NEIGHBOURS) {
                val nu = u + du
                val nv = v + dv
                if (levelAt(nu, nv) != NONE) continue
                val nx = wx(nu, nv)
                val nz = wz(nu, nv)
                val ground = terrain.top(nx, nz)
                if (level - ground < 2) continue
                val rib = Math.floorMod(if (du != 0) v else u, 4) == 0
                val parapet = u > uFlank && abs(v) == WAY
                if (parapet) {
                    // Out in the deep: a pier beside the steps, a low wall along it, a lit post every fourth
                    for (y in ground until level) put(nx, y, nz, faceBlock(nx, y, nz))
                    put(nx, level, nz, if (rib) Bk.of("sea_lantern") else wall(Mat.PRISMARINE, nx, level, nz))
                } else if (rib) {
                    for (y in ground until level - 2) put(nx, y, nz, block(Mat.DARK, nx, y, nz))
                    put(nx, level - 2, nz, block(Mat.BRICKS, nx, level - 2, nz))
                } else {
                    put(nx, level - 2, nz, stairs(Mat.DARK, dirOf(-du, -dv), true, nx, level - 2, nz))
                }
            }
        }
    }

    /** Where the hall steps down a tier along the way, two stairs, so it can be walked: half a block each. */
    private fun risers() {
        for (a in intArrayOf(uFront, uFront + TREAD)) for (v in -WAY..WAY) {
            val upper = levelAt(a, v)
            val lower = levelAt(a + 1, v)
            if (upper == NONE || lower == NONE || upper - lower != 2) continue
            val mat = if (abs(v) == WAY) Mat.DARK else Mat.BRICKS
            at(a, v, upper - 1, stairs(mat, out.opposite, false, wx(a, v), upper - 1, wz(a, v)))
            at(a + 1, v, lower, stairs(mat, out.opposite, false, wx(a + 1, v), lower, wz(a + 1, v)))
        }
    }

    /** The grand steps: nine blocks wide, a block lower with every block out, every fourth course dark. */
    private fun steps() {
        for (u in uSteps..uEnd) for (v in -WAY..WAY) {
            val level = stepLevel(u)
            val x = wx(u, v)
            val z = wz(u, v)
            val mat = when {
                (u - uSteps) % 4 == 0 || abs(v) == WAY -> Mat.DARK
                unit(u, v, level, 3) < 0.2 -> Mat.PRISMARINE
                else -> Mat.BRICKS
            }
            at(u, v, level - 1, stairs(mat, out.opposite, false, x, level - 1, z))
        }
    }

    // ---- the sanctum

    /** The levels of the winding stair's steps round the pit, by cell: it starts at the terrace's level by the front and goes down, a turn, to the floor. */
    private val ledge: Map<Long, Int> by lazy {
        val levels = HashMap<Long, Int>()
        val depth = terrace - (floorTop + 1)
        val start = -hand * 0.5
        for (u in -pit - 1..pit + 1) for (v in -pit - 1..pit + 1) {
            if (!inRing(u, v)) continue
            var turn = (atan2(v.toDouble(), u.toDouble()) - start) * hand / (2 * PI)
            turn -= floor(turn)
            levels[Growth.pack(u, 0, v)] = terrace - min(depth, floor(turn * (depth + 1)).toInt())
        }
        levels
    }

    /** The flooded sanctum: a floor of sand over the pit, the sea in it, the winding stair round its wall, and the altar at its middle. */
    private fun sanctum() {
        for (u in -pit..pit) for (v in -pit..pit) {
            if (!inPit(u, v)) continue
            val x = wx(u, v)
            val z = wz(u, v)
            at(u, v, floorTop - 3, Bk.of("sandstone"))
            at(u, v, floorTop - 2, Bk.of("sand"))
            at(u, v, floorTop - 1, Bk.of("sand"))
            for (y in floorTop until sea) put(x, y, z, Bk.WATER)
        }
        winding()
        altar()
    }

    /** The winding stair: the pit's ring built up to the levels [ledge] gives, with a stair wherever a step goes up. */
    private fun winding() {
        for ((p, level) in ledge) {
            val u = Growth.x(p)
            val v = Growth.z(p)
            val x = wx(u, v)
            val z = wz(u, v)
            for (y in floorTop - 1 until level - 1) put(x, y, z, pitWall(u, v, x, y, z))
            for (y in level until sea) put(x, y, z, Bk.WATER)
            // A stair where a neighbour is a block lower, rising away from it
            var top: Bk = pave(u, v, x, level - 1, z)
            for ((du, dv) in NEIGHBOURS) {
                if (ledge[Growth.pack(u + du, 0, v + dv)] == level - 1) {
                    top = stairs(Mat.BRICKS, dirOf(-du, -dv), false, x, level - 1, z)
                    break
                }
            }
            put(x, level - 1, z, top)
        }
    }

    /**
     * The altar: a footing five by five with a cross a step higher, a sea lantern on three of its corners and the chest
     * on the fourth, and over its middle the conduit, hung in a frame of three rings of prismarine bricks, dark prismarine
     * and sea lanterns, with the water all round it. The frame stands on the altar's four legs.
     */
    private fun altar() {
        val cy = sea - CONDUIT_DEPTH
        for (u in -2..2) for (v in -2..2) {
            val x = wx(u, v)
            val z = wz(u, v)
            put(x, floorTop, z, block(if (max(abs(u), abs(v)) == 2) Mat.DARK else Mat.BRICKS, x, floorTop, z))
            if (abs(u) <= 1 && abs(v) <= 1) put(x, floorTop + 1, z, block(Mat.BRICKS, x, floorTop + 1, z))
        }
        // The legs under the frame's four ends, and the lights and the chest in the corners
        for ((u, v) in listOf(Pair(2, 0), Pair(-2, 0), Pair(0, 2), Pair(0, -2))) at(u, v, floorTop + 1, Bk.of("prismarine_bricks"))
        for ((u, v) in listOf(Pair(-2, -2), Pair(-2, 2), Pair(2, -2))) at(u, v, floorTop + 1, Bk.of("sea_lantern"))
        at(2, 2, floorTop + 1, Bk.of("chest", "facing" to out.id, "type" to "single", "waterlogged" to "true"))
        b.loot(wx(2, 2), floorTop + 1, wz(2, 2), LOOT_ALTAR)
        // The frame: every block of the three rings, the ends of the axes lit, the corners dark
        for (i in -2..2) for (j in -2..2) for (k in -2..2) {
            val a = abs(i)
            val c = abs(j)
            val d = abs(k)
            val onFrame = (a > 1 || c > 1 || d > 1) && ((i == 0 && (c == 2 || d == 2)) || (j == 0 && (a == 2 || d == 2)) || (k == 0 && (a == 2 || c == 2)))
            val zeros = (if (a == 0) 1 else 0) + (if (c == 0) 1 else 0) + (if (d == 0) 1 else 0)
            val bk = when {
                i == 0 && j == 0 && k == 0 -> Bk.of("conduit", "waterlogged" to "true")
                !onFrame -> Bk.WATER
                zeros == 2 -> Bk.of("sea_lantern")
                a == 2 && c == 2 || a == 2 && d == 2 || c == 2 && d == 2 -> Bk.of("dark_prismarine")
                else -> Bk.of("prismarine_bricks")
            }
            at(i, k, cy + j, bk)
        }
        // Its drone, its chimes and its whispers by night
        b.sound(SoundSpot.SHRINE, wx(0, 0), cy, wz(0, 0))
    }

    // ---- the pit's rim

    /** The pit's rim: the cells of the terrace by its edge. */
    private fun onRim(u: Int, v: Int): Boolean {
        if (levelAt(u, v) != terrace || inPit(u, v)) return false
        for ((du, dv) in NEIGHBOURS) if (inPit(u + du, v + dv)) return true
        return false
    }

    /** A low wall round the pit's rim, open by the front where the way in comes, and lit posts every eighth of the way round. */
    private fun rim() {
        val posts = HashMap<Long, Int>()
        for (k in 1 until 8) {
            val angle = k * PI / 4
            var bestCell: Pair<Int, Int>? = null
            var bestGap = 1e9
            for (u in -pit - 3..pit + 3) for (v in -pit - 3..pit + 3) {
                if (!onRim(u, v)) continue
                val gap = abs(atan2(v.toDouble(), u.toDouble()) - angle).let { min(it, 2 * PI - it) }
                if (gap < bestGap) {
                    bestGap = gap
                    bestCell = Pair(u, v)
                }
            }
            val cell = bestCell ?: continue
            posts[Growth.pack(cell.first, 0, cell.second)] = k
        }
        for (u in -pit - 3..pit + 3) for (v in -pit - 3..pit + 3) {
            if (!onRim(u, v)) continue
            val x = wx(u, v)
            val z = wz(u, v)
            val angle = atan2(v.toDouble(), u.toDouble())
            val post = posts[Growth.pack(u, 0, v)]
            if (post != null) {
                val whole = unit(u, v, 0, 20) < 0.7
                put(x, terrace, z, Bk.of("dark_prismarine"))
                if (whole || unit(u, v, 1, 20) < 0.5) put(x, terrace + 1, z, Bk.of("prismarine_bricks"))
                if (whole) put(x, terrace + 2, z, Bk.of("sea_lantern"))
            } else if (abs(angle) > 0.62 && unit(u, v, 0, 21) < 0.72) {
                put(x, terrace, z, wall(if (unit(u, v, 1, 21) < 0.5) Mat.PRISMARINE else Mat.STONE, x, terrace, z))
            }
        }
    }

    // ---- the colonnade

    /** The pillars marching down both sides of the way, from the head of the hall into the deep: whole, snapped, stumps. */
    private fun colonnade() {
        val rows = arrayListOf(uFront - 1)
        var u = uFront + 2
        while (u <= uFlank - 1) {
            rows.add(u)
            u += TREAD
        }
        for ((i, pu) in rows.withIndex()) for (side in intArrayOf(-1, 1)) {
            val base = if (pu < uFront) terrace else tierLevel(pu)
            val roll = unit(pu, side, 7, 5)
            val whole = standing - 0.06 * i
            val kept = when {
                roll < whole -> pillarHeight
                roll < whole + 0.2 -> 2 + (unit(pu, side, 8, 5) * (pillarHeight - 4)).toInt()
                else -> 1 + (unit(pu, side, 9, 5) * 2).toInt()
            }
            pillar(pu, side * PILLAR_V, base, kept)
            if (kept < pillarHeight) fallen(pu, side * PILLAR_V, pillarHeight - kept)
        }
    }

    /**
     * What came down of a pillar [count] blocks tall at local ([u], [v]): drums lying in a row on the floor beyond it, mostly
     * across the hall toward the way, the last of them broken, and the capital's stairs thrown about.
     */
    private fun fallen(u: Int, v: Int, count: Int) {
        val r = Dice(plan.spec.seed xor (0x7A11L * (u * 131 + v + 1000)))
        val across = r.chance(0.7)
        val du = if (across) 0 else if (r.chance(0.5)) 1 else -1
        val dv = if (across) (if (v > 0) -1 else 1) else 0
        val len = min(count, 2 + r.int(3))
        for (k in 1..len) {
            val cu = u + du * (k + 1)
            val cv = v + dv * (k + 1)
            val level = levelAt(cu, cv)
            if (level == NONE || inPit(cu, cv)) break
            val x = wx(cu, cv)
            val z = wz(cu, cv)
            if (!vacant(x, level, z)) break
            put(x, level, z, if (k == len) slab(Mat.BRICKS, false, x, level, z) else block(if (k % 3 == 0) Mat.DARK else Mat.BRICKS, x, level, z))
        }
        // The capital: stairs scattered about the foot
        if (count >= 2) for (k in 0 until 2) {
            val cu = u + r.int(-3, 3)
            val cv = v + r.int(-3, 3)
            val level = levelAt(cu, cv)
            if (level == NONE || inPit(cu, cv) || inRing(cu, cv)) continue
            val x = wx(cu, cv)
            val z = wz(cu, cv)
            if (vacant(x, level, z)) put(x, level, z, stairs(Mat.BRICKS, Dir.entries[r.int(4)], r.chance(0.4), x, level, z))
        }
    }

    /**
     * A pillar on a plinth three blocks across at local ([u], [v]), standing on [base] (the first block of air over the
     * floor): a stepped foot, a shaft, a flaring capital and a slab on top; [kept] says how many blocks of it still stand.
     */
    private fun pillar(u: Int, v: Int, base: Int, kept: Int) {
        val cx = wx(u, v)
        val cz = wz(u, v)
        val height = pillarHeight
        for (k in 0 until min(kept, height)) {
            val y = base + k
            put(cx, y, cz, block(if (k % 3 == 2) Mat.DARK else Mat.BRICKS, cx, y, cz))
        }
        // The stepped foot round the shaft's first block
        for (d in Dir.entries) put(cx + d.dx, base, cz + d.dz, stairs(Mat.DARK, d.opposite, false, cx + d.dx, base, cz + d.dz))
        if (kept < height) return
        // The capital flares out over four upside-down stairs, and a slab laid over it
        val y = base + height - 2
        put(cx, y, cz, if (unit(u, v, 5, 80) < 0.3) Bk.of("sea_lantern") else block(Mat.DARK, cx, y, cz))
        for (d in Dir.entries) put(cx + d.dx, y, cz + d.dz, stairs(Mat.BRICKS, d.opposite, true, cx + d.dx, y, cz + d.dz))
        put(cx, y + 1, cz, block(Mat.BRICKS, cx, y + 1, cz))
        for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) put(cx + dx, y + 1, cz + dz, slab(Mat.BRICKS, false, cx + dx, y + 1, cz + dz))
        beam(u, v, y + 2)
        // A faded banner down the shaft's face toward the way, where it's clear of the water
        if (unit(u, v, 6, 82) < 0.5 && base + height - 4 >= sea) {
            val toward = dirOf(0, if (v > 0) -1 else 1)
            put(cx + toward.dx, base + height - 4, cz + toward.dz, Bk.of("${banner}_wall_banner", "facing" to toward.id))
        }
    }

    /**
     * A broken beam out from the top of a whole pillar at local ([u], [v]), at height [y]: it ran across the hall to the
     * pillar opposite and has fallen away but for a stub, which may hang a lantern from its end.
     */
    private fun beam(u: Int, v: Int, y: Int) {
        val r = Dice(plan.spec.seed xor (0xBEA3L * (u * 131 + v + 1000)))
        val reach = r.int(0, 5)
        if (reach == 0 && r.chance(0.5)) return
        val dv = if (v > 0) -1 else 1
        for (k in 0..reach) {
            val x = wx(u, v + dv * k)
            val z = wz(u, v + dv * k)
            val end = k == reach && reach > 0
            put(x, y, z, if (end) stairs(Mat.BRICKS, dirOf(0, dv), false, x, y, z) else block(if (k == 0) Mat.DARK else Mat.BRICKS, x, y, z))
        }
        if (reach >= 2 && y - 3 >= sea) {
            val x = wx(u, v + dv * reach)
            val z = wz(u, v + dv * reach)
            put(x, y - 1, z, Bk.of("iron_chain", "axis" to "y"))
            put(x, y - 2, z, Bk.of("soul_lantern", "hanging" to "true"))
        }
    }


    // ---- the idol

    /** The idol's centre, at the back of the terrace, and the first block of air over the top of its dais. */
    private val uIdol = uWall - 5
    private val idolY = terrace + 2

    /** The idol's cells in its own frame (a out to sea, b to the right, z up from the dais), and which part each is. */
    private val idolCells = HashMap<Long, Int>()

    /** The bearings (radians) and reach of the petals that broke, where their pieces lie. */
    private val fallenArms = ArrayList<Pair<Double, Double>>()

    /** The end of the arm swept last. */
    private var lastEnd = Triple(0, 0, 0)

    /** Where the petals' tips end (in the idol's frame), for hanging things from. */
    private val petalTips = ArrayList<Triple<Int, Int, Int>>()

    /** How far along its arm (in blocks) each of the arms' cells lies, for banding them. */
    private val idolArc = HashMap<Long, Int>()

    /** Where the idol's body leans: its middle's a at height [z], the head rearing back as it rises. */
    private fun lean(z: Int): Double = -0.15 * max(0.0, z - 0.4 * idolHeight)

    /** How wide the idol's body is at height [z]: a skirt, a waist, then the great swollen head and a tapering crown. */
    private fun bodyRadius(z: Int): Double {
        val t = z.toDouble() / idolHeight
        for (k in 1 until BODY_AT.size) {
            if (t <= BODY_AT[k]) return lerp(BODY_R[k - 1], BODY_R[k], (t - BODY_AT[k - 1]) / (BODY_AT[k] - BODY_AT[k - 1]))
        }
        return BODY_R.last()
    }

    /** Marks the idol's cell ([a], [bb], [z]) as [part], unless a part that outranks it is there. */
    private fun mark(a: Int, bb: Int, z: Int, part: Int) {
        val p = Growth.pack(a, z, bb)
        val old = idolCells[p]
        if (old == null || part > old) idolCells[p] = part
    }

    /** The idol of the Maelgrasp: a dais of dark stone, and on it the great hooded head and body with its arms curled over the water. */
    private fun idol() {
        dais()
        val h = idolHeight
        for (z in 0..h) {
            val r = bodyRadius(z)
            val c = lean(z)
            // The skirt is fluted, the fluting dying away up the body
            val flute = 0.17 * (1.0 - smooth(0.2 * h, 0.5 * h, z.toDouble()))
            for (a in -8..8) for (bb in -6..6) {
                val dx = a - c
                val rr = r * (1.0 + flute * cos(7 * atan2(bb.toDouble(), dx) + 0.6))
                if (dx * dx + bb * bb <= rr * rr + 0.2) mark(a, bb, z, BODY)
            }
        }
        face()
        arms()
        finishIdol()
    }

    /** The dais: two octagonal steps of dark stone, stairs all round each, so it can be climbed from any side. */
    private fun dais() {
        for ((level, radius) in listOf(Pair(0, 4), Pair(1, 3))) {
            val y = terrace + level
            for (a in -radius..radius) for (bb in -radius..radius) {
                if (!inOctagon(a, bb, radius)) continue
                val x = wx(uIdol + a, bb)
                val z = wz(uIdol + a, bb)
                val edge = NEIGHBOURS.any { (da, db) -> !inOctagon(a + da, bb + db, radius) }
                if (edge) {
                    val face = if (abs(a) >= abs(bb)) dirOf(if (a > 0) -1 else 1, 0) else dirOf(0, if (bb > 0) -1 else 1)
                    put(x, y, z, stairs(if (level == 0) Mat.DARK else Mat.BRICKS, face, false, x, y, z))
                } else {
                    put(x, y, z, block(if (Noise.unit(x, y, z, seed + 30) < 0.35) Mat.BRICKS else Mat.DARK, x, y, z))
                }
            }
        }
    }

    /** Whether ([a], [bb]) is in the octagon of the given [radius]: a square with its corners cut off. */
    private fun inOctagon(a: Int, bb: Int, radius: Int): Boolean = max(abs(a), abs(bb)) <= radius && abs(a) + abs(bb) <= radius + radius / 2

    /** The face: two slanted, glaring eyes on the front of the head, weeping, a brow over them, a beak under, and a crest along the back. */
    private fun face() {
        val h = idolHeight
        val ze = (0.5 * h).roundToInt()
        fun front(bb: Int, z: Int): Int {
            var a = 8
            while (a > -8 && idolCells[Growth.pack(a, z, bb)] != BODY) a--
            return a
        }
        for (side in intArrayOf(-1, 1)) {
            val af = front(side * 2, ze) + 1
            mark(af, side * 1, ze, PUPIL)
            mark(af, side * 2, ze, EYE)
            mark(af, side * 2, ze + 1, EYE)
            mark(af, side * 1, ze + 2, FIN)
            mark(af, side * 2, ze + 2, FIN)
            mark(af, side * 3, ze + 1, FIN)
        }
        val am = front(0, ze - 2) + 1
        mark(am, 0, ze - 1, FIN)
        mark(am, 0, ze - 2, FIN)
        mark(am - 1, -1, ze - 2, FIN)
        mark(am - 1, 1, ze - 2, FIN)
        // The crest: a fin down the back of the head, and a spike on top
        for (z in (0.5 * h).roundToInt()..h + 1) {
            val back = floor(lean(z) - bodyRadius(z)).toInt()
            mark(back, 0, z, FIN)
            if (z >= (0.7 * h).roundToInt()) mark(back - 1, 0, z - 1, FIN)
        }
        mark(floor(lean(h)).toInt(), 0, h + 1, FIN)
        mark(floor(lean(h)).toInt(), 0, h + 2, FIN)
    }

    /**
     * The idol's arms, of three kinds: petals that flare up and out round its head and curl in over it, leaving its face
     * clear; two that reach out over the pit and curl round a glowing pearl; and drapes that lie along the terrace with
     * their ends rising in spirals.
     */
    private fun arms() {
        val tall = 0.9 * idolHeight / 13.0
        var k = 0
        // The petals, an even spread of bearings to each side
        for (side in intArrayOf(-1, 1)) for (j in 0 until petals) {
            val r = Dice(plan.spec.seed xor (0x1D01L * ++k))
            val deg = 48.0 + 132.0 * (j + 0.5) / petals + r.range(-9.0, 9.0)
            val s = r.range(0.92, 1.1)
            val z = tall * r.range(0.92, 1.06)
            val inward = r.chance(0.3)
            val broken = r.chance(ruin)
            val until = if (broken) r.range(0.5, 0.8) else 1.0
            val tip = if (inward) arrayOf(doubleArrayOf(7.4 * s, 11.2 * z), doubleArrayOf(6.1 * s, 12.7 * z), doubleArrayOf(4.7 * s, 12.5 * z))
            else arrayOf(doubleArrayOf(8.8 * s, 11.0 * z), doubleArrayOf(9.9 * s, 12.0 * z), doubleArrayOf(10.7 * s, 10.8 * z))
            val root = arrayOf(doubleArrayOf(2.6, 0.3), doubleArrayOf(4.5, 1.4), doubleArrayOf(6.4 * s, 3.4 * z), doubleArrayOf(7.7 * s, 6.2 * z), doubleArrayOf(8.1 * s, 8.8 * z))
            sweep(root + tip, side * deg * PI / 180, side * r.range(-0.3, 0.5), r.range(1.3, 1.6), until, r.range(0.15, 0.4) * (if (r.chance(0.5)) 1 else -1))
            if (broken) fallenArms.add(Pair(side * deg * PI / 180, r.range(5.0, 7.5))) else if (inward) petalTips.add(lastEnd)
        }
        // The two that reach out over the pit and hold a pearl of light in their curl
        for (side in intArrayOf(-1, 1)) {
            val r = Dice(plan.spec.seed xor (0x1D01L * ++k))
            val s = r.range(0.95, 1.08)
            val pts = arrayOf(
                doubleArrayOf(2.4, 1.2), doubleArrayOf(4.5, 1.4), doubleArrayOf(7.0 * s, 1.2), doubleArrayOf(9.5 * s, 1.8), doubleArrayOf(11.5 * s, 3.6),
                doubleArrayOf(12.2 * s, 5.8), doubleArrayOf(11.4 * s, 7.6), doubleArrayOf(9.9 * s, 8.1), doubleArrayOf(9.0 * s, 7.0),
            )
            sweep(pts, side * r.range(20.0, 28.0) * PI / 180, side * 0.2, 2.0, 1.0, r.range(0.1, 0.25) * (if (r.chance(0.5)) 1 else -1))
            val bearing = side * 24.0 * PI / 180
            val oa = (10.5 * s * cos(bearing)).roundToInt()
            val ob = (10.5 * s * sin(bearing)).roundToInt()
            for (da in 0..1) for (db in 0..1) for (dz in 0..1) mark(oa + da, ob + db, 5 + dz, ORB)
        }
        // The beard: feelers hanging from under the beak down the front of the chest, their tips curling on the dais
        val mouth = (0.5 * idolHeight).roundToInt() - 2.0
        for (j in -1..1) {
            val r = Dice(plan.spec.seed xor (0x1D01L * ++k))
            val s = r.range(0.95, 1.1)
            val pts = arrayOf(
                doubleArrayOf(2.0, mouth + 0.6), doubleArrayOf(3.2, mouth - 0.6), doubleArrayOf(3.7 * s, mouth * 0.55), doubleArrayOf(4.0 * s, 1.8),
                doubleArrayOf(4.8 * s, 0.5), doubleArrayOf(5.9 * s, -0.4), doubleArrayOf(6.6 * s, 0.7),
            )
            sweep(pts, (j * 21.0 + r.range(-5.0, 5.0)) * PI / 180, j * r.range(0.0, 0.35), r.range(1.05, 1.3), 1.0, r.range(0.1, 0.25) * (if (r.chance(0.5)) 1 else -1))
        }
        // The drapes, along the terrace, curling up where they run out of room
        for (side in intArrayOf(-1, 1)) for (deg in intArrayOf(80, 128)) {
            val r = Dice(plan.spec.seed xor (0x1D01L * ++k))
            val s = r.range(0.9, 1.1)
            val pts = arrayOf(
                doubleArrayOf(2.8, -0.2), doubleArrayOf(4.2 * s, -1.2), doubleArrayOf(5.8 * s, -1.7), doubleArrayOf(7.0 * s, -1.5),
                doubleArrayOf(7.9 * s, -0.4), doubleArrayOf(7.7 * s, 1.3), doubleArrayOf(6.4 * s, 2.4), doubleArrayOf(5.3 * s, 1.9),
            )
            sweep(pts, side * (deg + r.range(-6.0, 6.0)) * PI / 180, side * r.range(0.2, 0.5), r.range(1.5, 1.8), 1.0, r.range(0.15, 0.35))
        }
    }

    /**
     * Sweeps an arm along the smooth curve through [pts] (each a reach from the idol's axis and a height), turned round the
     * axis to [bearing] and twisting [swirl] over its length: [thick] across at the root, thinning to a chain of single
     * blocks and then a thread of wall. An arm that has [until] less than 1 is snapped off that far along, and one with a [sway]
     * winds from side to side as it goes.
     */
    private fun sweep(pts: Array<DoubleArray>, bearing: Double, swirl: Double, thick: Double, until: Double = 1.0, sway: Double = 0.0) {
        lastEnd = Triple(0, 0, 0)
        var length = 0.0
        for (i in 1 until pts.size) length += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        val n = (length / 0.25).toInt()
        var pa = Int.MIN_VALUE
        var pb = 0
        var pz = 0
        for (i in 0..(n * until).toInt()) {
            val t = i.toDouble() / n
            val c = curve(pts, t)
            val angle = bearing + swirl * t + sway * sin(2 * PI * 1.4 * t) * t
            val ca = c[0] * cos(angle)
            val cb = c[0] * sin(angle)
            val radius = 0.45 + (thick - 0.45) * Math.pow(1 - t, 1.15)
            if (radius >= 0.95) {
                val ri = radius.toInt() + 1
                for (da in -ri..ri) for (db in -ri..ri) for (dz in -ri..ri) {
                    val a = ca.roundToInt() + da
                    val bb = cb.roundToInt() + db
                    val zz = c[1].roundToInt() + dz
                    if (zz < -2) continue
                    val dx = a - ca
                    val dy = bb - cb
                    val dh = zz - c[1]
                    // A broken end is ragged
                    if (until < 1.0 && t > until - 0.1 && Noise.unit(a, zz, bb, seed + 75) < 0.4) continue
                    if (dx * dx + dy * dy + dh * dh <= radius * radius) {
                        mark(a, bb, zz, ARM)
                        idolArc.putIfAbsent(Growth.pack(a, zz, bb), (t * length).toInt())
                    }
                }
            } else {
                // The thin end: one cell at a time, kept joined to the last
                val a = ca.roundToInt()
                val bb = cb.roundToInt()
                val zz = max(c[1].roundToInt(), -2)
                val kind = if (t < 0.93 || until < 1.0) TIP else THREAD
                if (pa != Int.MIN_VALUE) joinCells(pa, pb, pz, a, bb, zz, kind)
                mark(a, bb, zz, kind)
                idolArc.putIfAbsent(Growth.pack(a, zz, bb), (t * length).toInt())
                lastEnd = Triple(a, bb, zz)
                pa = a
                pb = bb
                pz = zz
            }
        }
    }

    /** The point [t] (0 to 1) along the smooth curve through [pts], each point as many numbers as the first. */
    private fun curve(pts: Array<DoubleArray>, t: Double): DoubleArray {
        val n = pts.size - 1
        val f = t.coerceIn(0.0, 1.0) * n
        val i = min(floor(f).toInt(), n - 1)
        val k = f - i
        fun p(j: Int) = pts[j.coerceIn(0, n)]
        val p0 = p(i - 1)
        val p1 = p(i)
        val p2 = p(i + 1)
        val p3 = p(i + 2)
        return DoubleArray(p1.size) { c ->
            0.5 * (2 * p1[c] + (p2[c] - p0[c]) * k + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * k * k + (3 * p1[c] - p0[c] - 3 * p2[c] + p3[c]) * k * k * k)
        }
    }

    /** Marks the cells that join ([a0], [b0], [z0]) to ([a1], [b1], [z1]) face to face, one step along one axis at a time, as [part]. */
    private fun joinCells(a0: Int, b0: Int, z0: Int, a1: Int, b1: Int, z1: Int, part: Int) {
        var a = a0
        var bb = b0
        var z = z0
        while (a != a1 || bb != b1 || z != z1) {
            if (a != a1) a += Integer.signum(a1 - a) else if (bb != b1) bb += Integer.signum(b1 - bb) else z += Integer.signum(z1 - z)
            mark(a, bb, z, part)
        }
    }

    /** Turns the idol's cells into blocks: stairs smoothing every step of its outline, dark in the shadow and pale where the light falls, suckers under the arms. */
    private fun finishIdol() {
        fun has(a: Int, bb: Int, z: Int) = idolCells.containsKey(Growth.pack(a, z, bb))
        for ((p, part) in idolCells) {
            val a = Growth.x(p)
            val z = Growth.y(p)
            val bb = Growth.z(p)
            val u = uIdol + a
            val x = wx(u, bb)
            val zw = wz(u, bb)
            val y = idolY + z
            val n = Noise.unit(x, y, zw, seed + 31)
            val topOpen = !has(a, bb, z + 1)
            val bottomOpen = !has(a, bb, z - 1)
            val bk: Bk = when (part) {
                EYE -> Bk.of(if (crimson) "shroomlight" else "sea_lantern")
                PUPIL -> Bk.of("crying_obsidian")
                ORB -> Bk.of("sea_lantern")
                THREAD -> Bk.of("prismarine_wall")
                FIN -> block(if (n < 0.7) Mat.PRISMARINE else Mat.BRICKS, x, y, zw)
                else -> {
                    // Smoothing: a stair rising toward the one neighbour a step higher, or hanging under the one a step lower
                    var made: Bk? = null
                    if (part != TIP) {
                        if (topOpen) {
                            val up = NEIGHBOURS.filter { (da, db) -> has(a + da, bb + db, z + 1) && !has(a + da, bb + db, z) }
                            if (up.size == 1) made = stairs(Mat.DARK, dirOf(up[0].first, up[0].second), false, x, y, zw)
                            // A ledge of the body under a wall that rises from it: sloped, so the crown tapers smoothly
                            if (made == null && part == BODY && z > 0.4 * idolHeight) {
                                val wall = NEIGHBOURS.filter { (da, db) -> has(a + da, bb + db, z + 1) }
                                if (wall.size == 1) made = stairs(Mat.DARK, dirOf(wall[0].first, wall[0].second), false, x, y, zw)
                            }
                        } else if (bottomOpen) {
                            val down = NEIGHBOURS.filter { (da, db) -> has(a + da, bb + db, z - 1) && !has(a + da, bb + db, z) }
                            if (down.size == 1) made = stairs(Mat.DARK, dirOf(down[0].first, down[0].second), true, x, y, zw)
                        }
                    }
                    // Dark in the shadow and pale where the light falls: bands of bricks round the body and along the arms, prismarine on top
                    val ring = (idolArc[p] ?: 0) / 3 % 2 == 0
                    val m = when {
                        part == BODY && z > 0.8 * idolHeight -> if (n < 0.6) Mat.PRISMARINE else Mat.BRICKS
                        part == BODY && topOpen -> Mat.PRISMARINE
                        part == BODY -> if (Math.floorMod(z, 3) == 2) Mat.BRICKS else Mat.DARK
                        bottomOpen && !topOpen -> Mat.DARK
                        topOpen -> if (ring) Mat.PRISMARINE else Mat.BRICKS
                        else -> if (ring) Mat.BRICKS else Mat.DARK
                    }
                    if (made != null) {
                        stairs(m, Dir.entries.first { made.has("facing", it.id) }, made.has("half", "top"), x, y, zw)
                    } else if (bottomOpen && part == ARM && Math.floorMod(a + bb + z, 3) == 0 && n < 0.6) {
                        Bk.of(if (crimson) "nether_wart_block" else "warped_wart_block")
                    } else {
                        block(m, x, y, zw)
                    }
                }
            }
            put(x, y, zw, bk)
        }
    }

    // ---- the offerings

    /** The sanctum's floor that's clear of the altar and the winding stair, as local cells. */
    private val floor: List<Pair<Int, Int>> by lazy {
        val cells = ArrayList<Pair<Int, Int>>()
        for (u in -pit..pit) for (v in -pit..pit) {
            if (!inPit(u, v) || inRing(u, v) || (abs(u) <= 2 && abs(v) <= 2)) continue
            cells.add(Pair(u, v))
        }
        cells
    }

    /** Up to [count] of [cells], the luckiest by noise [k], no two within [gap] blocks of each other. */
    private fun pick(cells: List<Pair<Int, Int>>, count: Int, gap: Int, k: Int): List<Pair<Int, Int>> {
        val chosen = ArrayList<Pair<Int, Int>>()
        for (c in cells.sortedBy { unit(it.first, it.second, 0, k) }) {
            if (chosen.size >= count) break
            if (chosen.any { abs(it.first - c.first) < gap && abs(it.second - c.second) < gap }) continue
            chosen.add(c)
        }
        return chosen
    }

    /** A decorated pot at local ([u], [v]) with its air at [y], its face turned to [facing], filled from the shrine's own loot. */
    private fun pot(u: Int, v: Int, y: Int, facing: Dir) {
        val x = wx(u, v)
        val z = wz(u, v)
        put(x, y, z, Bk.of("decorated_pot", "facing" to facing.id, "cracked" to (unit(u, v, y, 40) < 0.18).toString()))
        b.loot(x, y, z, LOOT_POT)
    }

    /**
     * What was left for the Maelgrasp and what the sea has kept: pots by the idol's dais, round the pit's rim and on the
     * hall's tiers; pots round the altar and on the winding stair; relics buried in the sanctum's sand; and vents in
     * its floor that send bubbles up through the water.
     */
    private fun offerings() {
        // By the idol: a row along the front of its dais, facing the pit
        for ((k, side) in intArrayOf(-3, -2, 2, 3).withIndex()) {
            val u = uIdol + 4
            if (levelAt(u, side) == terrace && vacant(wx(u, side), terrace, wz(u, side))) pot(u, side, terrace, out)
        }
        // On the terrace, about the pit
        for ((u, v) in pick(rimCells(2), 4, 4, 42)) pot(u, v, terrace, dirOf(if (u > 0) 1 else -1, 0))
        // On the hall's tiers, at the feet of the pillars' rows
        val tierCells = ArrayList<Pair<Int, Int>>()
        for (u in uFront + 1..uFlank) for (v in intArrayOf(-HALL + 1, -HALL + 2, HALL - 2, HALL - 1)) if (levelAt(u, v) != NONE) tierCells.add(Pair(u, v))
        for ((u, v) in pick(tierCells, 3, 5, 43)) {
            val y = levelAt(u, v)
            if (vacant(wx(u, v), y, wz(u, v))) pot(u, v, y, dirOf(0, if (v > 0) -1 else 1))
        }
        // Round the altar, on the sand
        val spots = pick(floor, 4, 3, 44)
        for ((u, v) in spots) pot(u, v, floorTop, dirOf(if (u > 0) -1 else 1, 0).let { if (abs(u) < abs(v)) dirOf(0, if (v > 0) -1 else 1) else it })
        // On the winding stair, here and there
        val steps = ledge.entries.filter { it.value in floorTop + 2..sea - 2 }.map { Pair(Growth.x(it.key), Growth.z(it.key)) }
        for ((u, v) in pick(steps, 2, 4, 45)) {
            val y = ledge[Growth.pack(u, 0, v)] ?: continue
            if (b.at(wx(u, v), y - 1, wz(u, v))?.name?.endsWith("_stairs") == false) pot(u, v, y, dirOf(if (u > 0) -1 else 1, 0))
        }
        // Buried in the sanctum's sand: relics, each under a drift of sand
        val taken = spots.toSet()
        for ((u, v) in pick(floor.filter { !taken.contains(it) }, 5, 2, 46)) {
            at(u, v, floorTop - 1, Bk.of("suspicious_sand", "dusted" to "0"))
            b.loot(wx(u, v), floorTop - 1, wz(u, v), LOOT_SAND)
        }
        // Vents of soul sand, with the bubbles streaming up from them
        for ((u, v) in pick(floor.filter { !taken.contains(it) && b.at(wx(it.first, it.second), floorTop - 1, wz(it.first, it.second))?.name == "sand" }, 3, 3, 47)) {
            at(u, v, floorTop - 1, Bk.of("soul_sand"))
            for (y in floorTop until sea) at(u, v, y, Bk.of("bubble_column", "drag" to "false"))
            b.sound(SoundSpot.BUBBLES, wx(u, v), sea - 1, wz(u, v))
        }
    }

    /** The cells of the terrace within [reach] blocks of the pit's rim, at least [reach] clear of the walls and posts. */
    private fun rimCells(reach: Int): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (u in -pit - 4..pit + 4) for (v in -pit - 4..pit + 4) {
            if (levelAt(u, v) != terrace || inPit(u, v) || onRim(u, v)) continue
            var near = false
            for (du in -reach..reach) for (dv in -reach..reach) if (onRim(u + du, v + dv)) near = true
            if (near && u > uWall - 3) cells.add(Pair(u, v))
        }
        return cells
    }

    /** Whether nothing of the shrine's stands at [x], [y], [z] (the sea written in doesn't count). */
    private fun vacant(x: Int, y: Int, z: Int): Boolean {
        val s = b.at(x, y, z)
        return s == null || s.name == "water" || s.name == "air"
    }

    // ---- the sea's share

    /** Whether [name] is a block that fills its cell and has a flat top to stand things on. */
    private fun whole(name: String?): Boolean =
        name != null && !holdsWater(name) && name != "air" && name != "water" && name != "seagrass" && name != "tall_seagrass" && name != "kelp" &&
            name != "kelp_plant" && name != "bubble_column" && name != "soul_sand" && name != "vine" && name != "moss_carpet" && name != "snow"

    /** The first cell of air or water over the floor at local ([u], [v]): the paving's level, the winding stair's, or the sanctum's. */
    private fun surface(u: Int, v: Int): Int {
        if (inRing(u, v)) return ledge[Growth.pack(u, 0, v)] ?: NONE
        return levelAt(u, v)
    }

    /**
     * What the sea has done to it: seagrass in drifts and kelp in tall stands on every flooded floor, sea pickles shining
     * among them, glow lichen on the dark walls, and in warm seas a reef grown over the lower tiers and the pit's rim.
     */
    private fun sea() {
        val cells = ArrayList<Triple<Int, Int, Int>>()
        for (u in uBack..uEnd) for (v in -half..half) {
            if (levelAt(u, v) == NONE) continue
            val level = surface(u, v)
            if (level == NONE || level >= sea - 1) continue
            if (!inPit(u, v) && u >= uSteps && abs(v) <= WAY) continue
            if (abs(u) <= 2 && abs(v) <= 2 && inPit(u, v)) continue
            cells.add(Triple(u, v, level))
        }
        // Sand drifted against the walls and in the corners, a block deep, two where it's thickest
        for ((u, v, level) in cells) {
            val x = wx(u, v)
            val z = wz(u, v)
            if (inPit(u, v) || !vacant(x, level, z) || !whole(b.at(x, level - 1, z)?.name)) continue
            val walls = NEIGHBOURS.count { (du, dv) -> levelAt(u + du, v + dv).let { it != NONE && it > level } }
            val m = Noise.fbm(x / 3.5, z / 3.5, seed + 81, 2) + 0.14 * walls
            if (m > 0.68) {
                put(x, level - 1, z, Bk.of(if (unit(u, v, level, 83) < 0.12) "gravel" else "sand"))
                if (m > 0.86 && sea - level >= 3) put(x, level, z, Bk.of("sand"))
            }
        }
        for ((u, v, level0) in cells) {
            val x = wx(u, v)
            val z = wz(u, v)
            val level = if (b.at(x, level0, z)?.name == "sand") level0 + 1 else level0
            if (!vacant(x, level, z) || !whole(b.at(x, level - 1, z)?.name) && !(inPit(u, v) && !inRing(u, v))) continue
            val depth = sea - level
            val patch = Noise.value(x / 3.5, z / 3.5, seed + 50)
            val grove = Noise.value(x / 5.0 + 40, z / 5.0, seed + 51)
            val r = unit(u, v, level, 52)
            val reef = if (corals.isEmpty()) 0.0 else Noise.value(x / 4.0, z / 4.0, seed + 53)
            when {
                corals.isNotEmpty() && reef > 0.64 && r < 0.62 -> coral(x, level, z, u, v, depth)
                grove > 0.68 && depth >= 3 && r < 0.4 -> kelp(x, level, z, min(depth - 2, 3 + (unit(u, v, level, 54) * 6).toInt()))
                patch > 0.55 && r < 0.5 -> {
                    if (depth >= 3 && r < 0.14 && vacant(x, level + 1, z)) {
                        put(x, level, z, Bk.of("tall_seagrass", "half" to "lower"))
                        put(x, level + 1, z, Bk.of("tall_seagrass", "half" to "upper"))
                    } else {
                        put(x, level, z, Bk.of("seagrass"))
                    }
                }
                r > 0.985 && !cold -> put(x, level, z, Bk.of("sea_pickle", "pickles" to "${1 + (unit(u, v, level, 55) * 4).toInt().coerceAtMost(3)}"))
            }
        }
    }

    /** A stand of kelp [height] blocks tall with its foot at [y]. */
    private fun kelp(x: Int, y: Int, z: Int, height: Int) {
        for (k in 0 until height) {
            if (!vacant(x, y + k, z)) return
            put(x, y + k, z, if (k == height - 1) Bk.of("kelp", "age" to "25") else Bk.of("kelp_plant"))
        }
    }

    /** A growth of coral at ([x], [y], [z]): a block of it where the reef's thickest, with fans and sprigs on its top and sides. */
    private fun coral(x: Int, y: Int, z: Int, u: Int, v: Int, depth: Int) {
        val reef = Noise.value(x / 4.0, z / 4.0, seed + 56)
        val kind = corals[(reef * corals.size).toInt().coerceAtMost(corals.size - 1)]
        val r = unit(u, v, y, 57)
        if (depth >= 2 && r < 0.45) {
            put(x, y, z, Bk.of("${kind}_coral_block"))
            if (depth >= 3 && vacant(x, y + 1, z)) {
                put(x, y + 1, z, if (r < 0.25) Bk.of("${kind}_coral_fan") else Bk.of("${kind}_coral"))
            }
        } else if (r < 0.8) {
            put(x, y, z, if (r < 0.62) Bk.of("${kind}_coral_fan") else Bk.of("${kind}_coral"))
        } else {
            put(x, y, z, Bk.of("sea_pickle", "pickles" to "${1 + (unit(u, v, y, 58) * 4).toInt().coerceAtMost(3)}"))
        }
    }

    // ---- the land's share

    /**
     * How buried the paving at local ([u], [v]) is under drifts of sand and soil, 0 (swept clean) to 1: least by the pit and
     * down the way, most toward the back and sides of the terrace and the hall's edges, in patches that come and go.
     */
    private fun buried(u: Int, v: Int): Double {
        val x = wx(u, v)
        val z = wz(u, v)
        val behind = smooth(uWall - 2.0, uBack + 2.0, u.toDouble())
        val sides = if (u <= uFront) smooth(half - 5.5, half + 0.5, abs(v).toDouble()) else smooth(HALL - 3.5, HALL + 0.5, abs(v).toDouble())
        val clear = smooth(1.5, 5.0, hypot(u.toDouble(), v.toDouble()) - pit)
        val patch = Noise.fbm(x / 4.5, z / 4.5, seed + 60, 2)
        return (max(behind, sides) * clear * 0.85 + (patch - 0.5) * 0.8 + 0.1 * clear).coerceIn(0.0, 1.0)
    }

    /** The ground of a drift at [x], [y], [z] in this sea's climate, and what kind of ground it is to what grows on it. */
    private fun drift(x: Int, y: Int, z: Int): Pair<Bk, Ground> {
        val n = Noise.unit(x, y, z, seed + 61)
        return when (climate) {
            Climate.WARM -> if (n < 0.86) Pair(Bk.of("sand"), Ground.SAND) else Pair(Bk.of("sandstone"), Ground.BARE)
            Climate.LUKEWARM -> when {
                n < 0.5 -> Pair(Bk.of("sand"), Ground.SAND)
                n < 0.85 -> Pair(Bk.of("grass_block"), Ground.GRASS)
                else -> Pair(Bk.of("moss_block"), Ground.MOSS)
            }
            Climate.TEMPERATE -> when {
                n < 0.55 -> Pair(Bk.of("grass_block"), Ground.GRASS)
                n < 0.75 -> Pair(Bk.of("moss_block"), Ground.MOSS)
                n < 0.85 -> Pair(Bk.of("coarse_dirt"), Ground.COARSE)
                else -> Pair(Bk.of("sand"), Ground.SAND)
            }
            Climate.COLD -> when {
                n < 0.4 -> Pair(Bk.of("grass_block"), Ground.GRASS)
                n < 0.65 -> Pair(Bk.of("podzol"), Ground.PODZOL)
                n < 0.8 -> Pair(Bk.of("moss_block"), Ground.MOSS)
                else -> Pair(Bk.of("coarse_dirt"), Ground.COARSE)
            }
            Climate.FROZEN -> when {
                n < 0.6 -> Pair(Bk.of("snow_block"), Ground.SNOWCAP)
                n < 0.75 -> Pair(Bk.of("packed_ice"), Ground.BARE)
                else -> Pair(Bk.of("gravel"), Ground.SCREE)
            }
        }
    }

    /**
     * The land half of the platform given back to the island: sand or soil drifted over the paving toward its edges, grown
     * with the plants of this climate (snow where it's cold), vines down the shaded stone and up the pillars.
     */
    private fun land() {
        for (u in uBack..uEnd) for (v in -half..half) {
            val level = levelAt(u, v)
            if (level == NONE || level < sea || inPit(u, v)) continue
            val x = wx(u, v)
            val z = wz(u, v)
            val under = b.at(x, level - 1, z)
            if (!whole(under?.name) || !vacant(x, level, z)) continue
            val m = buried(u, v) - (if (u > uFront) 0.12 else 0.0)
            if (m > 0.5) {
                val (ground, kind) = drift(x, level, z)
                put(x, level - 1, z, ground)
                // The thickest drifts rise a block, with a plant on top
                var top = level
                if (m > 0.8 && unit(u, v, level, 62) < 0.5 && abs(v) > WAY + 1 || m > 0.9 && u < uWall - 1) {
                    put(x, level, z, ground)
                    top = level + 1
                }
                val grown = Plants.cover(climate, kind, x, top, z, seed + 63, 0.8, 0.55, true)
                if (grown != null && grown.all { vacant(x, top + grown.indexOf(it), z) }) {
                    for ((k, plant) in grown.withIndex()) put(x, top + k, z, plant)
                } else if (climate == Climate.COLD && unit(u, v, top, 64) < 0.35) {
                    put(x, top, z, Bk.of("snow", "layers" to "${1 + (unit(u, v, top, 65) * 3).toInt().coerceAtMost(2)}"))
                    if (ground.name == "grass_block" || ground.name == "podzol") put(x, top - 1, z, ground.with("snowy", "true"))
                }
            } else if (climate == Climate.COLD && unit(u, v, level, 64) < 0.2) {
                // Bare paving with a dusting of snow
                put(x, level, z, Bk.of("snow", "layers" to "${1 + (unit(u, v, level, 65) * 2).toInt().coerceAtMost(1)}"))
            } else if (!cold && unit(u, v, level, 66) < 0.05 && stone == Stone.BRICK) {
                put(x, level, z, Bk.of("moss_carpet"))
            }
        }
        vines()
    }

    /** Vines down the shaded faces of the platform above the sea, and up the pillars, in the damper climates. */
    private fun vines() {
        val density = when (climate) {
            Climate.TEMPERATE -> 0.5
            Climate.LUKEWARM -> 0.3
            Climate.COLD -> 0.12
            else -> 0.0
        }
        if (density == 0.0) return
        for (u in uBack..uEnd) for (v in -half..half) {
            val level = levelAt(u, v)
            if (level == NONE || level <= sea + 1 || inPit(u, v)) continue
            for ((du, dv) in NEIGHBOURS) {
                if (levelAt(u + du, v + dv) != NONE || unit(u, v, du * 3 + dv, 67) > density) continue
                val nx = wx(u + du, v + dv)
                val nz = wz(u + du, v + dv)
                val bottom = max(terrain.top(nx, nz), sea)
                val len = 1 + (unit(u, v, du * 3 + dv, 68) * 5).toInt()
                val face = dirOf(-du, -dv).id
                for (y in level - 2 downTo max(bottom, level - 1 - len)) {
                    if (vacant(nx, y, nz) && whole(b.at(wx(u, v), y, wz(u, v))?.name)) put(nx, y, nz, Bk.of("vine", face to "true"))
                }
            }
        }
    }

    // ---- the eldritch

    /**
     * What clings to the idol and the pit: glow lichen on the dark walls of the sanctum and under the idol's arms, sculk
     * spreading over the paving round its dais with its veins and listening sensors, and a lightning rod on its crest.
     */
    private fun eldritch() {
        // Lichen on the pit's inner wall, wherever the stair behind it stands a block or more above the floor in front
        for ((p, level) in ledge) {
            val u = Growth.x(p)
            val v = Growth.z(p)
            for ((du, dv) in NEIGHBOURS) {
                val nu = u + du
                val nv = v + dv
                if (!inPit(nu, nv)) continue
                val low = if (inRing(nu, nv)) (ledge[Growth.pack(nu, 0, nv)] ?: continue) else floorTop
                for (y in low until level - 1) {
                    val x = wx(nu, nv)
                    val z = wz(nu, nv)
                    if (unit(nu, nv, y, 70) < 0.22 && vacant(x, y, z)) put(x, y, z, Bk.of("glow_lichen", dirOf(-du, -dv).id to "true"))
                }
            }
        }
        // Under the idol's arms, on a block above that's whole
        for ((p, part) in idolCells) {
            if (part != ARM) continue
            val a = Growth.x(p)
            val z = Growth.y(p)
            val bb = Growth.z(p)
            val x = wx(uIdol + a, bb)
            val zw = wz(uIdol + a, bb)
            val y = idolY + z
            if (idolCells.containsKey(Growth.pack(a, z - 1, bb)) || Noise.unit(x, y, zw, seed + 71) > 0.07) continue
            if (whole(b.at(x, y, zw)?.name) && vacant(x, y - 1, zw)) put(x, y - 1, zw, Bk.of("glow_lichen", "up" to "true"))
        }
        // Sculk round the dais, thinning out with distance, its veins creeping over the paving beside it, sensors among it
        val crest = idolHeight + idolY + 3
        for (u in uBack..uWall + 2) for (v in -half..half) {
            if (levelAt(u, v) != terrace) continue
            val d = hypot((u - uIdol).toDouble(), v.toDouble())
            if (d < 4.5 || d > 9.5) continue
            val x = wx(u, v)
            val z = wz(u, v)
            val under = b.at(x, terrace - 1, z)
            if (!whole(under?.name) || !vacant(x, terrace, z)) continue
            val n = unit(u, v, 0, 72)
            if (n < 0.5 * (1.0 - smooth(4.5, 9.5, d))) {
                put(x, terrace - 1, z, Bk.of("sculk"))
                if (n < 0.04) put(x, terrace, z, Bk.of("sculk_sensor"))
            } else if (n < 0.62 * (1.0 - smooth(4.5, 10.0, d)) + 0.1) {
                put(x, terrace, z, Bk.of("sculk_vein", "down" to "true"))
            }
        }
        // The lightning rod on the crest, weathered green
        val top = Triple(floor(lean(idolHeight)).toInt(), 0, idolHeight + 3)
        put(wx(uIdol + top.first, top.second), idolY + top.third, wz(uIdol + top.first, top.second), Bk.of("oxidized_lightning_rod", "facing" to "up"))
    }

    // ---- the debris

    /**
     * What has fallen: pieces of the idol's snapped petals lying on the terrace where they came down, and bits of
     * masonry at the foot of every drop of the platform, with sea growth already on them.
     */
    private fun debris() {
        for ((k, arm) in fallenArms.withIndex()) {
            val r = Dice(plan.spec.seed xor (0xDEB5L * (k + 1)))
            for (piece in 0 until 5 + r.int(4)) {
                val reach = arm.second + r.range(-2.5, 3.0)
                val bearing = arm.first + r.range(-0.35, 0.35)
                val u = uIdol + (reach * cos(bearing)).roundToInt()
                val v = (reach * sin(bearing)).roundToInt()
                val level = levelAt(u, v)
                if (level == NONE || inPit(u, v)) continue
                val x = wx(u, v)
                val z = wz(u, v)
                if (!vacant(x, level, z)) continue
                val m = if (r.chance(0.6)) Mat.DARK else if (r.chance(0.5)) Mat.BRICKS else Mat.PRISMARINE
                put(x, level, z, when (r.int(3)) {
                    0 -> block(m, x, level, z)
                    1 -> slab(m, false, x, level, z)
                    else -> stairs(m, Dir.entries[r.int(4)], r.chance(0.3), x, level, z)
                })
            }
        }
        // At the foot of the drops: a stray block or two, here and there
        for (u in uBack..uEnd) for (v in -half..half) {
            val level = levelAt(u, v)
            if (level == NONE || inPit(u, v)) continue
            for ((du, dv) in NEIGHBOURS) {
                if (levelAt(u + du, v + dv) != NONE || unit(u, v, du * 3 + dv, 76) > 0.1) continue
                val x = wx(u + 2 * du, v + 2 * dv)
                val z = wz(u + 2 * du, v + 2 * dv)
                if (levelAt(u + 2 * du, v + 2 * dv) != NONE) continue
                val y = terrain.top(x, z)
                if (level - y < 2 || !vacant(x, y, z)) continue
                val m = if (unit(u, v, du, 77) < 0.5) Mat.DARK else Mat.BRICKS
                put(x, y, z, when ((unit(u, v, dv, 78) * 3).toInt()) {
                    0 -> block(m, x, y, z)
                    1 -> slab(m, false, x, y, z)
                    else -> stairs(m, Dir.entries[(unit(u, v, 0, 79) * 4).toInt() % 4], false, x, y, z)
                })
            }
        }
    }

    // ---- the lights

    /**
     * The shrine's own lights, beside the sea lanterns: soul fire in braziers at the head of the steps, and lanterns
     * hanging on chains from the tips of the idol's petals.
     */
    private fun lights() {
        // Braziers either side of the way, at the front of the terrace
        for (side in intArrayOf(-1, 1)) {
            val u = uFront - 1
            val v = side * (half - 1)
            val x = wx(u, v)
            val z = wz(u, v)
            if (levelAt(u, v) == terrace && vacant(x, terrace, z) && whole(b.at(x, terrace - 1, z)?.name)) {
                put(x, terrace - 1, z, block(Mat.DARK, x, terrace - 1, z))
                put(x, terrace, z, Bk.of("soul_campfire", "facing" to out.id, "lit" to "true"))
            }
        }
        // Lanterns on chains from the petals' tips, some of the way down
        for ((k, tip) in petalTips.withIndex()) {
            if (unit(tip.first, tip.second, tip.third, 73) < 0.35) continue
            val x = wx(uIdol + tip.first, tip.second)
            val z = wz(uIdol + tip.first, tip.second)
            val y = idolY + tip.third
            val links = 2 + (unit(k, 0, 0, 74) * 3).toInt()
            var ok = true
            for (i in 1..links + 1) if (!vacant(x, y - i, z)) ok = false
            if (!ok) continue
            for (i in 1..links) put(x, y - i, z, Bk.of("iron_chain", "axis" to "y"))
            put(x, y - links - 1, z, Bk.of("soul_lantern", "hanging" to "true"))
        }
    }

    // ---- the living

    /**
     * Who's there: drowned out on the steps and among the drowned pillars, well clear of the conduit, whose light would
     * burn them, and glow squid and fish in the sanctum and the shallows.
     */
    private fun creatures() {
        val r = Dice(plan.spec.seed xor 0x5A12E5L)
        // The drowned, out along the steps and in the flooded hall
        val spots = listOf(Pair(uSteps + 6, r.int(-2, 2)), Pair(uSteps + 12, r.int(-3, 3)), Pair(uFront + 11, (if (r.chance(0.5)) -1 else 1) * r.int(5, 7)))
        for ((u, v) in spots.take(2 + r.int(2))) {
            val level = levelAt(u, v)
            if (level == NONE || level + 1 >= sea) continue
            b.spawn("minecraft:drowned", wx(u, v) + 0.5, level.toDouble(), wz(u, v) + 0.5, r.range(0.0, 360.0).toFloat())
        }
        // Glow squid circling in the sanctum
        for (k in 0 until 2) {
            val a = r.range(0.0, 2 * PI)
            val d = r.range(2.5, pit - 1.5)
            b.spawn("minecraft:glow_squid", wx((cos(a) * d).roundToInt(), (sin(a) * d).roundToInt()) + 0.5, sea - 4.0 + k, wz((cos(a) * d).roundToInt(), (sin(a) * d).roundToInt()) + 0.5, r.range(0.0, 360.0).toFloat())
        }
        // Frogs on the terrace, in the damper seas
        if (climate == Climate.TEMPERATE || climate == Climate.LUKEWARM) {
            for (k in 0 until 2) {
                val cell = rimCells(3).let { if (it.isEmpty()) null else it[r.int(it.size)] } ?: continue
                b.spawn("minecraft:frog", wx(cell.first, cell.second) + 0.5, terrace.toDouble(), wz(cell.first, cell.second) + 0.5, r.range(0.0, 360.0).toFloat())
            }
        }
        // Fish in the shallows by the hall
        val fish = when (climate) {
            Climate.WARM, Climate.LUKEWARM -> "minecraft:tropical_fish"
            Climate.TEMPERATE -> "minecraft:cod"
            else -> "minecraft:salmon"
        }
        for (k in 0 until 3) {
            val u = uFront + 5 + r.int(0, 10)
            val v = r.int(-HALL, HALL)
            val level = levelAt(u, v)
            if (level == NONE || level + 2 >= sea) continue
            b.spawn(fish, wx(u, v) + 0.5, level + 1.0, wz(u, v) + 0.5, r.range(0.0, 360.0).toFloat())
        }
    }

    // ---- materials

    /** The block of family [m] for the cell at [x], [y], [z], now and then worn to something else. */
    private fun block(m: Mat, x: Int, y: Int, z: Int): Bk = when (m) {
        Mat.PRISMARINE -> Bk.of("prismarine")
        Mat.BRICKS -> Bk.of("prismarine_bricks")
        Mat.DARK -> Bk.of("dark_prismarine")
        Mat.STONE -> Bk.of(stoneName(x, y, z, ""))
    }

    private fun stairs(m: Mat, facing: Dir, top: Boolean, x: Int, y: Int, z: Int): Bk {
        val name = when (m) {
            Mat.PRISMARINE -> "prismarine_stairs"
            Mat.BRICKS -> "prismarine_brick_stairs"
            Mat.DARK -> "dark_prismarine_stairs"
            Mat.STONE -> stoneName(x, y, z, "_stairs")
        }
        return Bk.of(name, "facing" to facing.id, "half" to if (top) "top" else "bottom", "shape" to "straight")
    }

    private fun slab(m: Mat, top: Boolean, x: Int, y: Int, z: Int): Bk {
        val name = when (m) {
            Mat.PRISMARINE -> "prismarine_slab"
            Mat.BRICKS -> "prismarine_brick_slab"
            Mat.DARK -> "dark_prismarine_slab"
            Mat.STONE -> stoneName(x, y, z, "_slab")
        }
        return Bk.of(name, "type" to if (top) "top" else "bottom")
    }

    private fun wall(m: Mat, x: Int, y: Int, z: Int): Bk = Bk.of(if (m == Mat.STONE) stoneName(x, y, z, "_wall") else "prismarine_wall")

    /** The weathered stone's block with [suffix] ("", "_stairs", "_slab", "_wall"): mossy, cracked or smooth here and there. */
    private fun stoneName(x: Int, y: Int, z: Int, suffix: String): String {
        val roll = Noise.unit(x, y, z, seed + 7)
        return when (stone) {
            Stone.BRICK -> when {
                roll < 0.28 -> "mossy_stone_brick" + (if (suffix.isEmpty()) "s" else suffix)
                suffix.isEmpty() && roll < 0.42 -> "cracked_stone_bricks"
                else -> if (suffix.isEmpty()) "stone_bricks" else "stone_brick$suffix"
            }
            Stone.SAND -> when {
                suffix == "_wall" -> "sandstone_wall"
                roll < 0.25 -> "smooth_sandstone$suffix"
                suffix.isEmpty() && roll < 0.5 -> "cut_sandstone"
                suffix.isEmpty() && roll < 0.54 -> "chiseled_sandstone"
                else -> "sandstone$suffix"
            }
            Stone.DEEP -> when {
                roll < 0.15 -> if (suffix.isEmpty()) "tuff_bricks" else "tuff_brick$suffix"
                suffix.isEmpty() && roll < 0.3 -> "cracked_deepslate_bricks"
                else -> if (suffix.isEmpty()) "deepslate_bricks" else "deepslate_brick$suffix"
            }
        }
    }

    /** The paving at local ([u], [v]): rings and spokes of inlay round the pit on the terrace, a dark-edged way down the hall. */
    private fun pave(u: Int, v: Int, x: Int, y: Int, z: Int): Bk {
        val n = Noise.unit(x, y, z, seed + 11)
        val r = hypot(u.toDouble(), v.toDouble())
        val mat = if (u <= uFront) {
            val ring = r - pit
            val spokes = atan2(v.toDouble(), u.toDouble()) / (PI / 4)
            val spoke = abs(spokes - round(spokes)) * r < 0.55
            when {
                ring < 1.3 -> Mat.DARK
                ring < 2.3 -> Mat.BRICKS
                spoke && u > uWall -> if (n < 0.5) Mat.PRISMARINE else Mat.BRICKS
                n < 0.18 -> Mat.BRICKS
                else -> Mat.STONE
            }
        } else {
            when {
                abs(v) == WAY -> Mat.DARK
                v == 0 -> Mat.PRISMARINE
                abs(v) < WAY -> if (n < 0.15) Mat.PRISMARINE else Mat.BRICKS
                n < 0.35 -> Mat.BRICKS
                else -> Mat.STONE
            }
        }
        return block(if (n > 0.95) Mat.DARK else mat, x, y, z)
    }

    /**
     * The masonry of the pit's wall at local ([u], [v]) and height [y]: panels of dark prismarine three blocks across, framed in
     * bricks by a pilaster every fourth cell round and a course every fourth block up, most with a sea lantern let into their
     * middle just under the waves; a panel here and there worn to rough prismarine or patched in the stone of the land.
     */
    private fun pitWall(u: Int, v: Int, x: Int, y: Int, z: Int): Bk {
        val n = Noise.unit(x, y, z, seed + 14)
        val along = if (abs(u) > abs(v)) v else u
        val pilaster = Math.floorMod(along, 4) == 0
        val course = Math.floorMod(sea - y, 4) == 0
        // The panel's number, so one panel is worn all of a piece
        val panel = Noise.unit(Math.floorDiv(along, 4), Math.floorDiv(sea - y + 2, 4), if (abs(u) > abs(v)) 1 + Integer.signum(u) else 4 + Integer.signum(v), seed + 15)
        return when {
            pilaster || course -> if (n < 0.14) block(Mat.PRISMARINE, x, y, z) else block(Mat.BRICKS, x, y, z)
            y == sea - 2 && Math.floorMod(along, 4) == 2 && panel < 0.75 -> Bk.of("sea_lantern")
            panel > 0.9 -> block(if (n < 0.6) Mat.PRISMARINE else Mat.DARK, x, y, z)
            panel > 0.8 && n < 0.5 -> block(Mat.STONE, x, y, z)
            else -> block(Mat.DARK, x, y, z)
        }
    }

    /** The masonry facing a drop of the platform at height [y]: stone above the waves, prismarine below them, a dark course every fifth and a dark tide line. */
    private fun faceBlock(x: Int, y: Int, z: Int): Bk {
        val n = Noise.unit(x, y, z, seed + 13)
        val m = when {
            Math.floorMod(y, 5) == 0 || (abs(y - sea) <= 1 && n < 0.55) -> Mat.DARK
            y < sea -> if (n < 0.5) Mat.BRICKS else if (n < 0.75) Mat.PRISMARINE else if (n < 0.88) Mat.DARK else Mat.STONE
            else -> if (n < 0.55) Mat.STONE else if (n < 0.8) Mat.BRICKS else if (n < 0.9) Mat.DARK else Mat.PRISMARINE
        }
        return block(m, x, y, z)
    }

    // ---- placing

    /** Sets [bk] at local ([u], [v]) and height [y]. */
    private fun at(u: Int, v: Int, y: Int, bk: Bk) = put(wx(u, v), y, wz(u, v), bk)

    /** Sets [bk] at [x], [y], [z], waterlogged if it's under the sea and can hold water. */
    private fun put(x: Int, y: Int, z: Int, bk: Bk) {
        var s = bk
        if (y < sea && holdsWater(bk.name) && bk.props.none { it.first == "waterlogged" }) s = bk.with("waterlogged", "true")
        b.set(x, y, z, s)
        if (bk.name.endsWith("_wall") || bk.name.endsWith("_stairs")) joins.add(Growth.pack(x, y, z))
    }

    /** Whether the block called [name] has a waterlogged state. */
    private fun holdsWater(name: String): Boolean =
        name.endsWith("_stairs") || name.endsWith("_slab") || name.endsWith("_wall") || name.endsWith("_trapdoor") ||
            name.endsWith("_fence") || name.endsWith("candle") || name.endsWith("_coral") || name.endsWith("_coral_fan") ||
            name.endsWith("_coral_wall_fan") || name == "lantern" || name == "soul_lantern" || name == "iron_chain" ||
            name == "iron_bars" || name == "chest" || name == "decorated_pot" || name == "glow_lichen" || name == "sculk_vein" ||
            name == "sea_pickle" || name == "conduit" || name == "ladder" || name == "sculk_sensor" || name == "soul_campfire"

    /** Whether a wall joins to [bk]: another wall, or a block that fills its cell. */
    private fun solid(bk: Bk?): Boolean {
        if (bk == null) return false
        val n = bk.name
        if (n.endsWith("_wall")) return true
        if (holdsWater(n) || n == "air" || n == "water" || n == "seagrass" || n == "tall_seagrass" || n == "kelp" || n == "kelp_plant" || n == "vine" || n == "moss_carpet") return false
        return true
    }

    /** Joins up the walls and the corners of the stairs the way the game will once it has them, so the preview shows them as they'll stand. */
    private fun join() {
        for (p in joins) {
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            val here = b.at(x, y, z) ?: continue
            if (here.name.endsWith("_wall")) {
                var s = here
                val sides = BooleanArray(4)
                for ((k, d) in Dir.entries.withIndex()) {
                    sides[k] = solid(b.at(x + d.dx, y, z + d.dz))
                    val tall = sides[k] && solid(b.at(x, y + 1, z))
                    s = s.with(d.id, if (!sides[k]) "none" else if (tall) "tall" else "low")
                }
                val straight = (sides[0] && sides[2] && !sides[1] && !sides[3]) || (sides[1] && sides[3] && !sides[0] && !sides[2])
                b.set(x, y, z, s.with("up", if (!straight || solid(b.at(x, y + 1, z))) "true" else "false"))
            } else if (here.name.endsWith("_stairs")) {
                b.set(x, y, z, here.with("shape", stairShape(x, y, z, here)))
            }
        }
    }

    /** The corner a stair at [x], [y], [z] makes with its neighbours, as the game works it out. */
    private fun stairShape(x: Int, y: Int, z: Int, here: Bk): String {
        val facing = Dir.entries.first { here.has("facing", it.id) }
        val side = here.props.first { it.first == "half" }.second
        fun stairAt(dx: Int, dz: Int): Bk? = b.at(x + dx, y, z + dz)?.takeIf { it.name.endsWith("_stairs") && it.has("half", side) }
        fun facingOf(s: Bk): Dir = Dir.entries.first { s.has("facing", it.id) }
        fun takes(face: Dir): Boolean {
            val n = b.at(x + face.dx, y, z + face.dz)
            return n == null || !n.name.endsWith("_stairs") || !n.has("facing", facing.id) || !n.has("half", side)
        }
        stairAt(facing.dx, facing.dz)?.let { s ->
            val f = facingOf(s)
            if (f.alongX != facing.alongX && takes(f.opposite)) return if (f == facing.ccw) "outer_left" else "outer_right"
        }
        stairAt(-facing.dx, -facing.dz)?.let { s ->
            val f = facingOf(s)
            if (f.alongX != facing.alongX && takes(f)) return if (f == facing.ccw) "inner_left" else "inner_right"
        }
        return "straight"
    }

    companion object {
        /** Nothing: the shrine doesn't reach here. */
        private const val NONE = Int.MIN_VALUE

        /** How deep each tier of the hall is, the way down the middle (half its width), the hall (half its width), where the pillars stand (across), and the idol's end. */
        private const val TREAD = 4
        private const val WAY = 4
        private const val HALL = 8
        private const val PILLAR_V = 6
        private const val IDOL = 9

        /** How far round the terrace's back and sides the land is eased to meet it. */
        private const val BANK = 5

        /** How far under the sea the sanctum's floor lies, and the conduit over it. */
        private const val FLOOR_DEPTH = 7
        private const val CONDUIT_DEPTH = 3

        private val NEIGHBOURS = listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))

        /** The parts of the idol, the later outranking the earlier where they meet: its body, an arm, the thin end of one, a fin, an eye and its pupil, a thread, a pearl. */
        private const val BODY = 1
        private const val ARM = 2
        private const val TIP = 3
        private const val FIN = 4
        private const val EYE = 5
        private const val PUPIL = 6
        private const val THREAD = 7
        private const val ORB = 8

        /** The idol's width at heights along its body (as fractions of its height): the skirt, the waist, the swollen head, the crown. */
        private val BODY_AT = doubleArrayOf(0.0, 0.1, 0.26, 0.4, 0.52, 0.66, 0.78, 0.88, 0.95, 1.0)
        private val BODY_R = doubleArrayOf(3.5, 3.1, 2.5, 2.9, 3.3, 3.4, 3.0, 2.3, 1.4, 0.6)

        const val LOOT_POT = "guildmark:chests/island/shrine"
        const val LOOT_SAND = "guildmark:chests/island/shrine_sand"
        const val LOOT_ALTAR = "guildmark:chests/island/shrine_altar"
    }
}
