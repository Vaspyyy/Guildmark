package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The smugglers' den in a cove's sea cave. The cave itself is cut by the terrain (a tunnel from the back of the cove
 * in to a domed chamber with the sea in its floor); this builds the den inside it: a plank dock on log posts round
 * the pool with a pier and moorings, a pulley rig and rowing boats; a deck across the back of the chamber with the
 * long table, the galley fire, the smoking rack, the map corner and stacked cargo; a gallery of bunks along the
 * walls, lanterns hung from the roof, a lookout ledge over the tunnel and a strongbox in an alcove at the back. A
 * ladder shaft cut up through the rock comes out in a thicket on the knoll above, and a small hide on the cliff top
 * by the cove's mouth keeps watch over the sea.
 */
class CoveDen(plan: IslandPlan) : Landmark(plan) {
    private val dice = Dice(plan.spec.seed xor 0xC0DE5L)
    private val seed = Noise.seed(plan.spec.seed, 63)

    /** The sea cave the terrain cut, if the island has one. */
    private val cave: Carve.Cave? = plan.terrain.cave

    /** Which way the den turns: whether its right hand is on the left of the chamber. */
    private val mirrored = dice.chance(0.5)

    /** Which side of the tunnel the catwalk and the lookout run on (the sign of the tunnel's side). */
    private val catwalk = if (dice.chance(0.5)) 1 else -1

    /** The woods the den is built of: the deck and furnishings, and the posts and beams that frame them. */
    private val deckWood: Timber
    private val frameWood: Timber

    /** Which way from the chamber's middle the tunnel runs, as a whole-block direction, and the way into the den's back. */
    private var front = Dir.S
    private var back = Dir.N
    private var side = Dir.E

    /** The column holding the chamber's middle, and where its true middle lies from the column's own, along [side] and [back]. */
    private var ox = 0
    private var oz = 0
    private var ci = 0.0
    private var cj = 0.0

    /** How far into the chamber, from its middle toward the back, the deck reaches out from the back wall. */
    private var chord = 0.0

    /** The lookout hide on the cliff top: its middle column, the ground's level there, and which way it looks out (null if the cliffs gave no site). */
    private var hideX = 0
    private var hideZ = 0
    private var hideLevel = 0
    private var hideOut = Dir.S
    private var hideSite = false

    /** Where the shaft's hatch lies on the surface, and which way its ladder faces. */
    private var hatchX = 0
    private var hatchY = 0
    private var hatchZ = 0
    private var hatchOut = Dir.N

    /** How far round the back, from the catwalk's side, the gallery runs onto the other flank (radians). */
    private var sweep = 0.0

    /** What the den itself is made of: worked out as the island is planned, since where its hatch comes up must be known to claim the ground round it. */
    private val denBuild = Build()

    /** Where the den's own sounds come from, handed to the island's build when it's asked for them. */
    private val spots = ArrayList<Spot>()

    /** One place a sound comes from. */
    private class Spot(val kind: SoundSpot, val x: Int, val y: Int, val z: Int)

    init {
        val woods = when (plan.climate) {
            Climate.WARM -> listOf(Timber.JUNGLE, Timber.MANGROVE, Timber.BAMBOO, Timber.ACACIA)
            Climate.LUKEWARM -> listOf(Timber.OAK, Timber.JUNGLE, Timber.MANGROVE, Timber.BIRCH)
            Climate.TEMPERATE -> listOf(Timber.SPRUCE, Timber.OAK, Timber.DARK_OAK, Timber.SPRUCE)
            Climate.COLD, Climate.FROZEN -> listOf(Timber.SPRUCE, Timber.DARK_OAK, Timber.SPRUCE)
        }
        deckWood = dice.pick(woods)
        val frames = when (plan.climate) {
            Climate.WARM -> listOf(Timber.JUNGLE, Timber.DARK_OAK, Timber.MANGROVE)
            Climate.LUKEWARM -> listOf(Timber.DARK_OAK, Timber.OAK, Timber.JUNGLE)
            else -> listOf(Timber.DARK_OAK, Timber.SPRUCE)
        }
        frameWood = frames.filter { it != deckWood }.let { dice.pick(it.ifEmpty { frames }) }
    }

    override fun lay() {
        val c = cave ?: return
        ox = floor(c.ex).toInt()
        oz = floor(c.ez).toInt()
        // Whole-block frame: the tunnel's way in is toward the front, the den lies to the back
        front = Dir.nearest(atan2(c.mz - c.ez, c.mx - c.ex))
        back = front.opposite
        side = if (mirrored) back.ccw else back.cw
        val dx = c.ex - (ox + 0.5)
        val dz = c.ez - (oz + 0.5)
        ci = dx * side.dx + dz * side.dz
        cj = dx * back.dx + dz * back.dz
        chord = dice.range(-0.5, 0.6)
        sweep = dice.range(0.2, 1.3)
        layHide(c)
        Den(denBuild, c).draw()
        claimSurface(c)
    }

    /**
     * Keeps the island's trees, shrubs and boulders off the hatch's thicket and off the ground where the cave's roof is
     * thin (the tunnel under the cliff's edge), so nothing of theirs can reach into it.
     */
    private fun claimSurface(c: Carve.Cave) {
        val t = terrain
        if (hatchY != 0) plan.claim(hatchX - 3, hatchZ - 3, hatchX + 3, hatchZ + 3)
        var thin = 0
        for (x in c.minX..c.maxX) for (z in c.minZ..c.maxZ) {
            if (!t.inside(x, z) || t.top(x, z) <= sea + 1) continue
            var y = sea + 16
            while (y >= sea && !c.contains(x + 0.5, y + 0.5, z + 0.5)) y--
            if (y >= sea && t.top(x, z) - (y + 1) < COVER) {
                plan.claim(x, z, x, z)
                thin++
            }
        }
    }

    /**
     * Finds a level, dry spot on the cliff top near where the cove meets the open sea, a little back from the edge,
     * with the sea in view, and levels it for the hide. It looks out over whichever side the water lies widest. Low or
     * lumpy coasts are taken in turn if the high, level ground is wanting.
     */
    private fun layHide(c: Carve.Cave) {
        val t = terrain
        // Where the cove opens to the sea: out along its axis from the tunnel until the water is wide
        val ux = cos(t.mouth)
        val uz = sin(t.mouth)
        var sMouth = 2.0
        for (s in 1..70) {
            sMouth = s.toDouble()
            if (t.distance((c.mx + ux * s).roundToInt(), (c.mz + uz * s).roundToInt()) < -6.0) break
        }
        val mouthX = c.mx + ux * sMouth
        val mouthZ = c.mz + uz * sMouth
        val cx = floor(mouthX).toInt()
        val cz = floor(mouthZ).toInt()
        var bestOut = Dir.S
        for (tier in HIDE_TIERS) {
            var best = Double.NEGATIVE_INFINITY
            for (px in cx - tier.reach..cx + tier.reach) for (pz in cz - tier.reach..cz + tier.reach) {
                val near = hypot(px - mouthX, pz - mouthZ)
                if (near > tier.reach || hypot(px - c.ex, pz - c.ez) < c.chamber + 6.0) continue
                val level = padLevel(c, px, pz, tier) ?: continue
                // The way it looks: where the most water lies in view
                var look = Dir.S
                var water = -1
                for (d in Dir.entries) {
                    val seen = (3..14).count { k -> !t.dry(px + d.dx * k, pz + d.dz * k) }
                    if (seen > water) {
                        water = seen
                        look = d
                    }
                }
                if (water < 4) continue
                val edge = t.distance(px, pz)
                val score = -abs(near - 9.0) * 0.3 - abs(edge - 4.0) * 0.5 + level * 0.12 + water * 0.2 + 1.5 * Noise.unit(px, 89, pz, seed)
                if (score > best) {
                    best = score
                    hideX = px
                    hideZ = pz
                    hideLevel = level
                    bestOut = look
                    hideSite = true
                }
            }
            if (hideSite) break
        }
        if (!hideSite) return
        hideOut = bestOut
        levelHide(hideLevel)
        plan.claim(hideX - 3, hideZ - 3, hideX + 3, hideZ + 3)
    }

    /** How demanding a hide's site is: how high over the sea, how far from level, and how far from the cove's mouth it may lie. */
    private class Tier(val height: Int, val slope: Int, val reach: Int)


    /**
     * The level (over the sea) the ground round ([px], [pz]) would be levelled to for the hide, or null if the spot
     * won't do: its five-by-five must be dry land a block or more in from the shore, high, nearly level and well clear
     * of the cave.
     */
    private fun padLevel(c: Carve.Cave, px: Int, pz: Int, tier: Tier): Int? {
        val t = terrain
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        var sum = 0
        for (x in px - 2..px + 2) for (z in pz - 2..pz + 2) {
            if (!t.inside(x, z) || !t.dry(x, z)) {
                return null
            }
            if (t.distance(x, z) < 1.0) {
                return null
            }
            lo = min(lo, t.top(x, z))
            hi = max(hi, t.top(x, z))
            sum += t.top(x, z) - sea
        }
        if (hi - lo > tier.slope) {
            return null
        }
        if (lo - sea < tier.height) {
            return null
        }
        // Rock enough over the cave under it, even where the levelling cuts the ground down
        for (x in px - 4..px + 4) for (z in pz - 4..pz + 4) {
            for (y in lo - 4 until lo) if (c.contains(x + 0.5, y + 0.5, z + 0.5)) {
                return null
            }
        }
        return (sum / 25.0).roundToInt()
    }

    /** Levels the hide's five-by-five to [level], easing the dry ground two blocks round it into the slope (the shore itself is left alone). */
    private fun levelHide(level: Int) {
        val t = terrain
        for (x in hideX - 4..hideX + 4) for (z in hideZ - 4..hideZ + 4) {
            if (!t.inside(x, z) || !t.dry(x, z)) continue
            val d = hypot(max(0, abs(x - hideX) - 2).toDouble(), max(0, abs(z - hideZ) - 2).toDouble())
            if (d > 2.0) continue
            t.set(x, z, lerp(t.h(x, z), level.toDouble(), 1.0 - smooth(0.0, 2.5, d)))
        }
    }

    override fun paint() {
        if (hideSite) for (x in hideX - 3..hideX + 3) for (z in hideZ - 3..hideZ + 3) {
            val d = hypot((x - hideX).toDouble(), (z - hideZ).toDouble())
            if (d <= 2.9 && plan.groundAt(x, z).dry) plan.paint(x, z, if (d <= 1.5) Ground.BUILT else Ground.PATH)
        }
    }

    override fun build(b: Build) {
        for (k in spots) b.sound(k.kind, k.x, k.y, k.z)
        if (hatchY != 0) Thicket(b).draw()
        if (hideSite) Hide(b).draw()
    }

    /** The columns it may draw in: the den's and the surface's together. */
    override val minX: Int get() = min(denBuild.minX, made.minX)
    override val maxX: Int get() = max(denBuild.maxX, made.maxX)
    override val minZ: Int get() = min(denBuild.minZ, made.minZ)
    override val maxZ: Int get() = max(denBuild.maxZ, made.maxZ)

    /** Draws the den, then what stands on the surface over it. */
    override fun draw(canvas: IslandCanvas) {
        denBuild.draw(canvas)
        super.draw(canvas)
    }

    /** The den's chests first (it's laid out with the plan), then the surface's. */
    override fun find(table: String): IntArray? = denBuild.find(table) ?: made.find(table)

    // ---- the den

    /** What a column of the den's floor is. */
    private enum class Floor { POOL, DOCK, DECK }

    /** One column of the chamber seen from the den's own frame: where it is, how far from the walls, and what it's for. */
    private class Col(val i: Int, val j: Int, val x: Int, val z: Int) {
        /** Where its middle lies from the chamber's, across ([u], toward the side) and deep ([v], toward the back), its range and bearing. */
        var u = 0.0
        var v = 0.0
        var r = 0.0
        var ang = 0.0

        /** How far to the nearest rock at head height, and the highest open block over the sea. */
        var wall = 99.0
        var ceil = 0

        /** Air over water here, in the cave. */
        var open = false
        var inChamber = false

        /** Which side of the tunnel's middle it lies (-1, 0, 1) and how far it is from the boats' channel (negative inside it). */
        var tide = 0
        var offLane = 0.0

        var floor = Floor.POOL
        var pier = false
        var curb = false

        /** Whether a gallery floor stands over it, whether its edge is open to the room, and whether a ladder or stair keeps it clear. */
        var gallery = false
        var edge = false
        var hole = false
        var gap = false
    }

    /** Draws the den into [b]: every block it's made of is put through here so sunk blocks are waterlogged and fences join up. */
    private inner class Den(val b: Build, val c: Carve.Cave) {
        private val joins = ArrayList<Long>()

        /** Every column of the chamber's disc and a margin, by local cell. */
        private val cols = HashMap<Long, Col>()

        /** Every block put or reserved so far, so nothing else is put there. */
        private val used = HashSet<Long>()

        /** Where the pier starts, which way it runs and how far. */
        private var pierAt: Col? = null
        private var pierDir = Dir.N
        private var pierLen = 0

        /** Where the alcove opens, the table's cells. */
        private var alcoveAt: Col? = null
        private var tableCells: List<Col> = emptyList()

        /** The tunnel's own length. */
        private val tunnelLen = hypot(c.ex - c.mx, c.ez - c.mz)

        /** The world column of the local cell ([i] across toward [side], [j] deep toward [back]). */
        fun wx(i: Int, j: Int): Int = ox + side.dx * i + back.dx * j
        fun wz(i: Int, j: Int): Int = oz + side.dz * i + back.dz * j

        /** How far the local cell's middle lies from the chamber's, deep and across. */
        fun depth(j: Int): Double = j - cj
        fun across(i: Int): Double = i - ci

        /** Is there open cave (water or air) at the block? */
        fun open(x: Int, y: Int, z: Int): Boolean = c.contains(x + 0.5, y + 0.5, z + 0.5) || y >= terrain.top(x, z)

        /** Is the block open cave and nothing of the den's yet? */
        fun free(x: Int, y: Int, z: Int): Boolean = open(x, y, z) && !used.contains(Growth.pack(x, y, z))

        /** The y of the highest open block over the water in a column, or sea - 1 if there is none. */
        fun ceiling(x: Int, z: Int): Int {
            var y = sea + 16
            while (y >= sea) {
                if (c.contains(x + 0.5, y + 0.5, z + 0.5)) return y
                y--
            }
            return sea - 1
        }

        /** The tunnel's lie at a column: how far along it from the mouth (0) to the chamber's middle (1), and how far to the side (signed). */
        fun along(x: Int, z: Int): Double = ((x + 0.5 - c.mx) * (c.ex - c.mx) + (z + 0.5 - c.mz) * (c.ez - c.mz)) / (tunnelLen * tunnelLen)

        fun aside(x: Int, z: Int): Double = ((x + 0.5 - c.mx) * (c.ez - c.mz) - (z + 0.5 - c.mz) * (c.ex - c.mx)) / tunnelLen

        fun col(i: Int, j: Int): Col? = cols[Growth.pack(i, 0, j)]

        /** The column at a world column, if it's one of the den's. */
        fun colAt(x: Int, z: Int): Col? {
            val dx = x - ox
            val dz = z - oz
            return col(dx * side.dx + dz * side.dz, dx * back.dx + dz * back.dz)
        }

        /** The column next to [col] on its [d] side. */
        fun nb(col: Col, d: Dir): Col? = colAt(col.x + d.dx, col.z + d.dz)

        /** Puts [bk] at the block, waterlogged if it's under the sea and can hold water. */
        fun put(x: Int, y: Int, z: Int, bk: Bk) {
            val fixed = if (y < sea) wet(bk) else bk
            b.set(x, y, z, fixed)
            used.add(Growth.pack(x, y, z))
            if (isJoining(fixed)) joins.add(Growth.pack(x, y, z))
        }

        fun put(x: Int, y: Int, z: Int, name: String, vararg props: Pair<String, String>) = put(x, y, z, Bk.of(name, *props))

        /** Marks a block as taken without putting anything there. */
        fun reserve(x: Int, y: Int, z: Int) {
            used.add(Growth.pack(x, y, z))
        }

        fun draw() {
            survey()
            floors()
            pier()
            curbs()
            planks()
            supports()
            gallery()
            moorings()
            alcove()
            shaft()
            table()
            galley()
            mapCorner()
            cargo()
            bunks()
            rig()
            clutter()
            boats()
            lights()
            spawns()
            finish()
            // Water dripping in the chamber and, fainter, down the tunnel
            spots.add(Spot(SoundSpot.CAVE, floor(c.ex).toInt(), sea + 3, floor(c.ez).toInt()))
            spots.add(Spot(SoundSpot.CAVE, floor((c.ex + c.mx) / 2).toInt(), sea + 2, floor((c.ez + c.mz) / 2).toInt()))
        }

        /** Works out what each column of the chamber is: how high its roof, how far from a wall, whether the tunnel's water runs there. */
        fun survey() {
            for (i in -10..10) for (j in -10..10) {
                val x = wx(i, j)
                val z = wz(i, j)
                val col = Col(i, j, x, z)
                col.u = across(i)
                col.v = depth(j)
                col.r = hypot(col.u, col.v)
                col.ang = atan2(col.u, col.v)
                col.inChamber = col.r < c.chamber
                col.open = open(x, sea, z) && open(x, sea + 1, z) && open(x, sea + 2, z) && c.contains(x + 0.5, sea - 0.5, z + 0.5)
                col.ceil = ceiling(x, z)
                var wall = 99.0
                for (dx in -3..3) for (dz in -3..3) if (!open(x + dx, sea + 1, z + dz)) wall = min(wall, hypot(dx.toDouble(), dz.toDouble()))
                col.wall = wall
                val t = along(x, z)
                val a = aside(x, z)
                col.tide = if (a > 0.5) 1 else if (a < -0.5) -1 else 0
                col.offLane = if (t > -0.2 && col.r >= c.chamber - 0.5) abs(a) - LANE else 99.0
                cols[Growth.pack(i, 0, j)] = col
            }
        }

        /** The first row of the deck, toward the back. */
        fun firstDeckRow(): Int {
            var j = -10
            while (j < 10 && depth(j) < chord) j++
            return j
        }

        /** Lays out the floors: the deck over the back of the chamber and the dock round its shoulders and along one side of the tunnel. */
        fun floors() {
            for (col in cols.values) {
                if (!col.open) continue
                col.floor = when {
                    col.inChamber && col.v >= chord -> Floor.DECK
                    col.wall <= RING && col.offLane > 0 && (col.inChamber || col.tide == catwalk) -> Floor.DOCK
                    else -> Floor.POOL
                }
            }
            // Dock that doesn't join the deck is no use: drop it
            val seen = HashSet<Col>()
            val queue = ArrayDeque<Col>()
            for (col in cols.values) if (col.floor == Floor.DECK) {
                seen.add(col)
                queue.add(col)
            }
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (d in Dir.entries) {
                    val n = nb(at, d) ?: continue
                    if (n.floor == Floor.DOCK && seen.add(n)) queue.add(n)
                }
            }
            for (col in cols.values) if (col.floor == Floor.DOCK && col !in seen) col.floor = Floor.POOL
        }

        /** Is this a column of open water the pier or a boat may take? */
        fun clearWater(col: Col?, margin: Double = 0.8): Boolean = col != null && col.open && col.floor == Floor.POOL && col.offLane > margin

        /** A short pier out into the pool, two planks wide, from wherever the shore gives it the most reach and open water either side. */
        fun pier() {
            var bestScore = -1e9
            var bestAt: Col? = null
            var bestDir = Dir.N
            var bestLen = 0
            for (p in cols.values) {
                if (p.floor == Floor.POOL || !p.open) continue
                for (d in Dir.entries) {
                    val t = d.cw
                    val q = nb(p, t) ?: continue
                    if (q.floor == Floor.POOL) continue
                    val behind = nb(p, d.opposite)
                    if (behind == null || behind.floor == Floor.POOL) continue
                    var len = 0
                    var sides = 0
                    for (k in 1..4) {
                        val a = colAt(p.x + d.dx * k, p.z + d.dz * k)
                        val a2 = colAt(q.x + d.dx * k, q.z + d.dz * k)
                        if (!clearWater(a) || !clearWater(a2)) break
                        val l = colAt(p.x + d.dx * k - t.dx, p.z + d.dz * k - t.dz)
                        val r = colAt(q.x + d.dx * k + t.dx, q.z + d.dz * k + t.dz)
                        if (clearWater(l, -1.0) && clearWater(r, -1.0)) sides++
                        len = k
                    }
                    if (len < 2 || sides < len - 1) continue
                    // A jetty ends in open water; one that runs into the shore is a causeway
                    val tip = colAt(p.x + d.dx * (len + 1), p.z + d.dz * (len + 1))
                    val open = tip != null && tip.open && tip.floor == Floor.POOL
                    val score = min(len, 4) * 6.0 + sides * 3.0 + (if (p.floor == Floor.DECK) 3.0 else 0.0) + (if (d == front) 6.0 else 0.0) +
                        (if (open) 4.0 else -12.0) + 4.0 * Noise.unit(p.x, 5, p.z, seed)
                    if (score > bestScore) {
                        bestScore = score
                        bestAt = p
                        bestDir = d
                        bestLen = len
                    }
                }
            }
            val p = bestAt ?: return
            val t = bestDir.cw
            for (k in 1..bestLen) for (s in 0..1) {
                val col = colAt(p.x + bestDir.dx * k + t.dx * s, p.z + bestDir.dz * k + t.dz * s) ?: continue
                col.floor = Floor.DOCK
                col.pier = true
            }
            pierAt = p
            pierDir = bestDir
            pierLen = bestLen
            spots.add(Spot(SoundSpot.PIER, p.x + bestDir.dx * bestLen, sea + 1, p.z + bestDir.dz * bestLen))
            // A cross-piece at the tip makes it a T, sometimes
            if (bestLen >= 3 && dice.chance(0.5)) for (s in -1..2) {
                val col = colAt(p.x + bestDir.dx * bestLen + t.dx * s, p.z + bestDir.dz * bestLen + t.dz * s) ?: continue
                if (col.floor == Floor.POOL && col.open) {
                    col.floor = Floor.DOCK
                    col.pier = true
                }
            }
        }

        /** Marks the floor columns whose edge meets open water: they take a log curb. */
        fun curbs() {
            for (col in cols.values) {
                if (col.floor == Floor.POOL || col.pier) continue
                col.curb = Dir.entries.any { d -> nb(col, d)?.let { it.open && it.floor == Floor.POOL } == true }
            }
        }

        /** Lays the planks, with a log curb along the water's edge. */
        fun planks() {
            for (col in cols.values) {
                if (col.floor == Floor.POOL) continue
                put(col.x, sea, col.z, if (col.curb) curbLog(col) else boards(col))
            }
        }

        /** The log along a curb column, lying along the water's edge. */
        fun curbLog(col: Col): Bk {
            val toWater = Dir.entries.firstOrNull { d -> nb(col, d)?.let { it.open && it.floor == Floor.POOL } == true } ?: Dir.N
            val axis = if (toWater.alongX) "z" else "x"
            return if (Noise.unit(col.x, 7, col.z, seed) < 0.3) frameWood.log(axis) else frameWood.strippedLog(axis)
        }

        /** The planks of a deck column: mostly the deck's wood, some boards of a darker or lighter kind. */
        fun boards(col: Col): Bk {
            val n = Noise.unit(col.x, 3, col.z, seed)
            return when {
                n < 0.10 -> frameWood.planks
                n < 0.16 -> frameWood.strippedLog(if (Noise.unit(col.x, 4, col.z, seed) < 0.5) "x" else "z")
                else -> deckWood.planks
            }
        }

        /** Log piles under the dock and deck, standing on the pool's floor; the pier has a pair at every other row. */
        fun supports() {
            for (col in cols.values) {
                if (col.floor == Floor.POOL) continue
                val post = Math.floorMod(col.i, 3) == 0 && Math.floorMod(col.j, 3) == 0
                if (post) for (y in sea - 3 until sea) put(col.x, y, col.z, frameWood.log("y"))
            }
            val p = pierAt ?: return
            val t = pierDir.cw
            for (k in 1..pierLen step 2) for (s in 0..1) {
                val col = colAt(p.x + pierDir.dx * k + t.dx * s, p.z + pierDir.dz * k + t.dz * s) ?: continue
                for (y in sea - 3 until sea) if (free(col.x, y, col.z) || b.at(col.x, y, col.z) == null) put(col.x, y, col.z, frameWood.log("y"))
            }
        }

        /** Mooring posts at the pier's tip and a few bollards along the dock's edge, some with a lantern on top. */
        fun moorings() {
            val p = pierAt
            if (p != null) {
                val t = pierDir.cw
                val tipCols = ArrayList<Col>()
                for (s in -1..2) colAt(p.x + pierDir.dx * pierLen + t.dx * s, p.z + pierDir.dz * pierLen + t.dz * s)?.let { if (it.pier) tipCols.add(it) }
                val ends = listOfNotNull(tipCols.firstOrNull(), tipCols.lastOrNull()).distinct()
                for (col in ends) {
                    for (y in sea + 1..sea + 2) put(col.x, y, col.z, frameWood.log("y"))
                    put(col.x, sea + 3, col.z, "lantern", "hanging" to "false")
                }
            }
            // Bollards along the curb, well apart
            val bollards = ArrayList<Col>()
            for (col in cols.values.sortedBy { it.ang }) {
                if (!col.curb || col.pier || col.gallery || col.gap || !free(col.x, sea + 1, col.z)) continue
                if (Noise.unit(col.x, 19, col.z, seed) > 0.25) continue
                if (bollards.any { abs(it.i - col.i) + abs(it.j - col.j) < 4 }) continue
                bollards.add(col)
                put(col.x, sea + 1, col.z, frameWood.log("y"))
                if (Noise.unit(col.x, 20, col.z, seed) < 0.5) put(col.x, sea + 2, col.z, "lantern", "hanging" to "false")
            }
        }

        /** One or two rowing boats on the open water, near the pier or the dock's edge. */
        fun boats() {
            val want = if (dice.chance(0.45)) 2 else 1
            val chosen = ArrayList<Col>()
            repeat(want) {
                var best: Col? = null
                var bestScore = -1e9
                for (col in cols.values) {
                    if (!clearWater(col, 0.3)) continue
                    // Room for a hull: the block and its neighbours are open pool
                    if (Dir.entries.any { d -> !clearWater(nb(col, d), 0.0) }) continue
                    if (chosen.any { abs(it.i - col.i) + abs(it.j - col.j) < 4 }) continue
                    var near = 9.0
                    for (o in cols.values) if (o.floor != Floor.POOL) near = min(near, hypot((o.i - col.i).toDouble(), (o.j - col.j).toDouble()))
                    val score = -abs(near - 2.0) * 2 + 3.0 * Noise.unit(col.x, 23, col.z, seed) + (if (col.pier) 0.0 else 0.0)
                    if (score > bestScore) {
                        bestScore = score
                        best = col
                    }
                }
                val col = best ?: return@repeat
                if (chosen.isEmpty()) spots.add(Spot(SoundSpot.MOORING, col.x, sea, col.z))
                chosen.add(col)
                val turn = dice.pick(Dir.entries)
                val yaw = yaw(turn) + dice.range(-25.0, 25.0).toFloat()
                b.spawn("minecraft:${deckWood.boat}", col.x + 0.5, sea - 0.6, col.z + 0.5, yaw)
            }
        }

        /** Hangs a lantern on a chain from the roof over a column, its bottom at [bottom] or at the roof if that's lower. */
        fun hang(col: Col, bottom: Int): Boolean {
            val top = col.ceil
            val y = min(bottom, top)
            if (y < sea + 2 || top < sea + 4) return false
            for (k in y..top) if (!free(col.x, k, col.z)) return false
            for (k in y + 1..top) put(col.x, k, col.z, "iron_chain", "axis" to "y")
            put(col.x, y, col.z, "lantern", "hanging" to "true")
            return true
        }

        /** Lanterns hung from the roof: over the deck, over the pool, along the tunnel, and under the gallery floor. */
        fun lights() {
            val hung = ArrayList<Col>()
            fun spaced(col: Col, gap: Int) = hung.none { abs(it.i - col.i) + abs(it.j - col.j) < gap }
            // Over the pool and the tunnel: dim, so the water shows them
            for (col in cols.values.sortedBy { Noise.unit(it.x, 29, it.z, seed) }) {
                if (!col.open || col.floor != Floor.POOL || col.wall < 2.5 || !spaced(col, 5)) continue
                if (col.r > c.chamber + 3.5) continue
                if (hang(col, sea + 4)) hung.add(col)
            }
            // Over the deck's middle: low enough to light the table
            for (col in cols.values.sortedBy { Noise.unit(it.x, 31, it.z, seed) }) {
                if (col.floor != Floor.DECK || col.gallery || col.wall < 3.0 || !spaced(col, 4)) continue
                if (hang(col, sea + 5)) hung.add(col)
            }
            // Under the gallery floor, one every few columns
            for (col in cols.values.sortedBy { it.ang }) {
                if (!col.gallery || col.edge || col.hole || !spaced(col, 4)) continue
                if (free(col.x, sea + 2, col.z)) {
                    put(col.x, sea + 2, col.z, "lantern", "hanging" to "true")
                    hung.add(col)
                }
            }
        }

        /** The gallery along the back and sides of the chamber, three blocks over the deck: floor, stair, ladders, edge beam, rail and posts. */
        fun gallery() {
            val j0 = firstDeckRow()
            for (col in cols.values) {
                if (col.floor == Floor.POOL || col.pier || !col.open) continue
                if (col.wall > GALLERY || col.ceil < sea + 5) continue
                val onCatwalkSide = col.tide == catwalk || col.tide == 0
                val flank = if (col.floor == Floor.DECK) col.j >= j0 + (if (col.tide == catwalk) 0 else 1) else col.inChamber && col.tide == catwalk
                // Round the back and onto the far flank only as far as the sweep
                if (flank && (onCatwalkSide || abs(col.ang) <= sweep)) col.gallery = true
            }
            // Lonely planks make no walkway: keep only the longest run
            keepLargest()
            stair()
            ladders()
            // The floor, its beam where it meets the room, the rail on the beam and the posts under it
            for (col in cols.values) {
                if (!col.gallery) continue
                val room = Dir.entries.filter { d -> opening(col, d) }
                col.edge = room.isNotEmpty()
                val bk = if (col.edge) frameWood.strippedLog(if (room[0].alongX) "z" else "x") else deckWood.planks
                put(col.x, sea + 3, col.z, bk)
            }
            val posts = ArrayList<Col>()
            for (col in cols.values.sortedBy { it.ang }) {
                if (!col.gallery || !col.edge || !railed(col)) continue
                put(col.x, sea + 4, col.z, deckWood.fence)
                if (posts.none { abs(it.i - col.i) <= 2 && abs(it.j - col.j) <= 2 }) {
                    posts.add(col)
                    for (y in sea + 1..sea + 2) put(col.x, y, col.z, frameWood.log("y"))
                    if (Noise.unit(col.x, 11, col.z, seed) < 0.7) put(col.x, sea + 5, col.z, "lantern", "hanging" to "false")
                }
            }
        }

        /** Is there room to fall to on the [d] side of a gallery column: open air, with no gallery floor there? */
        fun opening(col: Col, d: Dir): Boolean {
            val x = col.x + d.dx
            val z = col.z + d.dz
            if (!open(x, sea + 3, z)) return false
            val n = colAt(x, z)
            return n == null || !n.gallery
        }

        /** Does a gallery column take a rail: an opening on some side that isn't a stair or ladder's? */
        fun railed(col: Col): Boolean {
            if (col.gap) return false
            return Dir.entries.any { d -> opening(col, d) && nb(col, d)?.hole != true }
        }

        /** The stair up to the gallery: three steps hugging the wall from the deck or dock to the gallery's end. */
        fun stair() {
            var bestScore = -1e9
            var bestAt: Col? = null
            var bestDir = Dir.N
            for (pass in 0..1) {
                for (g in cols.values) {
                    if (!g.gallery) continue
                    for (d in Dir.entries) {
                        val steps = ArrayList<Col>()
                        var at: Col = g
                        for (k in 1..3) {
                            at = nb(at, d) ?: break
                            steps.add(at)
                        }
                        if (steps.size < 3 || steps.any { !it.open || it.floor == Floor.POOL || it.gallery || it.pier || it.offLane <= 0.5 || it.ceil < sea + 6 }) continue
                        // Hugging the wall (the first pass insists), off the lookout side, and at the end of the run
                        val hug = steps.sumOf { it.wall }
                        if (pass == 0 && hug > 6.5) continue
                        val ends = Dir.entries.count { nb(g, it)?.gallery == true }
                        val score = -hug * 2 + (if (g.tide != catwalk) 3.0 else 0.0) - ends + 2.0 * Noise.unit(g.x, 13, g.z, seed)
                        if (score > bestScore) {
                            bestScore = score
                            bestAt = g
                            bestDir = d
                        }
                    }
                }
                if (bestAt != null) break
            }
            val g = bestAt ?: return
            g.gap = true
            var at: Col = g
            val up = bestDir.opposite
            for (k in 1..3) {
                at = nb(at, bestDir) ?: break
                at.hole = true
                val y = sea + 4 - k
                put(at.x, y, at.z, deckWood.stairs, "facing" to up.id, "half" to "bottom", "shape" to "straight")
                for (f in sea + 1 until y) put(at.x, f, at.z, deckWood.planks)
                reserve(at.x, y + 1, at.z)
                reserve(at.x, y + 2, at.z)
            }
        }

        /** Ladders up the wall into the gallery, each in a gap in the floor with the wall behind it. */
        fun ladders() {
            val want = if (dice.chance(0.5)) 2 else 1
            val chosen = ArrayList<Col>()
            repeat(want) {
                var best: Col? = null
                var bestDir = Dir.N
                var bestScore = -1e9
                for (h in cols.values) {
                    if (!h.gallery || h.wall > 1.01 || h.gap) continue
                    val w = Dir.entries.firstOrNull { d -> (sea + 1..sea + 4).all { y -> !open(h.x + d.dx, y, h.z + d.dz) } } ?: continue
                    // The climber steps off onto the gallery beside it, and comes to it from the deck in front
                    val onto = Dir.entries.count { d -> nb(h, d)?.let { it.gallery && !it.hole } == true }
                    val inward = nb(h, w.opposite) ?: continue
                    if (onto < 2 || inward.floor == Floor.POOL || inward.hole) continue
                    if (chosen.any { abs(it.i - h.i) + abs(it.j - h.j) < 6 }) continue
                    val score = Noise.unit(h.x, 17, h.z, seed) * 4 + (if (h.floor == Floor.DECK) 2.0 else 0.0) + (if (abs(h.ang) < 1.2) 2.0 else 0.0)
                    if (score > bestScore) {
                        bestScore = score
                        best = h
                        bestDir = w
                    }
                }
                val h = best ?: return@repeat
                chosen.add(h)
                h.gallery = false
                h.hole = true
                for (y in sea + 1..sea + 4) put(h.x, y, h.z, "ladder", "facing" to bestDir.opposite.id)
                reserve(h.x - bestDir.dx, sea + 1, h.z - bestDir.dz)
                reserve(h.x - bestDir.dx, sea + 2, h.z - bestDir.dz)
            }
        }

        /** Keeps only the largest connected piece of the gallery. */
        fun keepLargest() {
            val seen = HashSet<Col>()
            var best: List<Col> = emptyList()
            for (start in cols.values) {
                if (!start.gallery || start in seen) continue
                val piece = ArrayList<Col>()
                val queue = ArrayDeque<Col>()
                seen.add(start)
                queue.add(start)
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    piece.add(at)
                    for (d in Dir.entries) {
                        val n = nb(at, d) ?: continue
                        if (n.gallery && seen.add(n)) queue.add(n)
                    }
                }
                if (piece.size > best.size) best = piece
            }
            for (col in cols.values) if (col.gallery && col !in best) col.gallery = false
        }


        // ---- furnishing

        /** The direction from a column to the rock beside it that lies most toward the chamber's rim, or null if it stands clear. */
        fun wallDir(col: Col): Dir? {
            var best: Dir? = null
            var bestDot = -1e9
            for (d in Dir.entries) {
                if (open(col.x + d.dx, sea + 2, col.z + d.dz)) continue
                val dot = d.dx * rimX(col) + d.dz * rimZ(col)
                if (dot > bestDot) {
                    bestDot = dot
                    best = d
                }
            }
            return best
        }

        /** Where a column lies from the chamber's middle, in the world's frame (east and south). */
        fun rimX(col: Col): Double = side.dx * col.u + back.dx * col.v
        fun rimZ(col: Col): Double = side.dz * col.u + back.dz * col.v

        /** The direction from a column toward the chamber's middle: the way something standing there faces. */
        fun inward(col: Col): Dir = Dir.entries.maxByOrNull { -(it.dx * rimX(col) + it.dz * rimZ(col)) } ?: Dir.N

        /** The column [f] steps in from [col] away from the wall on side [w], and [s] steps along the wall (to [w]'s clockwise side). */
        fun at(col: Col, w: Dir, f: Int, s: Int): Col? {
            val t = w.cw
            return colAt(col.x - w.dx * f + t.dx * s, col.z - w.dz * f + t.dz * s)
        }

        /** The y of the floor's top at a level: 0 the deck's and dock's, 1 the gallery's. */
        fun floorTop(level: Int): Int = if (level == 0) sea + 1 else sea + 4

        /** Is there floor at the given level with [h] blocks of free air over it? */
        fun roomOn(col: Col?, h: Int = 1, level: Int = 0): Boolean {
            if (col == null || !col.open || col.hole) return false
            if (level == 0 && col.floor == Floor.POOL) return false
            if (level == 1 && !col.gallery) return false
            val base = floorTop(level)
            return (0 until h).all { free(col.x, base + it, col.z) && base + it <= col.ceil }
        }

        /** The columns against the wall (rock on a side) at a level, in order round the chamber. */
        fun rim(level: Int): List<Col> = cols.values.filter { col ->
            col.wall <= 1.01 && !col.hole && !col.pier && (if (level == 0) col.floor != Floor.POOL else col.gallery)
        }.sortedBy { it.ang }

        /** Every run of [n] neighbouring columns along the wall at a level. */
        fun runs(level: Int, n: Int): List<List<Col>> {
            val ring = rim(level)
            val out = ArrayList<List<Col>>()
            for (k in 0..ring.size - n) {
                val w = ring.subList(k, k + n)
                val linked = (0 until n - 1).all { abs(w[it].i - w[it + 1].i) <= 1 && abs(w[it].j - w[it + 1].j) <= 1 && w[it] !== w[it + 1] }
                if (linked) out.add(w)
            }
            return out
        }

        /** Puts a chest (or a barrel that holds loot) of [loot] at the block, facing [facing]. */
        fun chestAt(x: Int, y: Int, z: Int, facing: Dir, loot: String, barrel: Boolean = false) {
            b.chest(x, y, z, facing, loot, barrel)
            used.add(Growth.pack(x, y, z))
        }

        /** Clears a block of rock to air, for a recess or a shaft. */
        fun carve(x: Int, y: Int, z: Int) {
            b.air(x, y, z)
            used.add(Growth.pack(x, y, z))
        }

        /** The recess in the wall that holds the strongbox: three wide, three deep and two high, a grille across its mouth. */
        fun alcove() {
            var best: Col? = null
            var bestW = Dir.N
            var bestScore = -1e9
            for (a in cols.values) {
                if (a.floor != Floor.DECK || a.hole || a.wall > 1.01) continue
                val w = wallDir(a) ?: continue
                val t = w.cw
                val row = (-1..1).map { s -> colAt(a.x + t.dx * s, a.z + t.dz * s) }
                if (row.any { it == null || it.floor != Floor.DECK || it.hole || !roomOn(it, 2) || it.wall > 1.5 }) continue
                // Solid rock all round it, and thick overhead
                var solid = true
                for (s in -1..1) for (k in 1..3) for (y in sea - 1..sea + 3) {
                    if (open(a.x + t.dx * s + w.dx * k, y, a.z + t.dz * s + w.dz * k)) solid = false
                }
                if (!solid || terrain.top(a.x + w.dx * 3, a.z + w.dz * 3) < sea + 8) continue
                val score = -abs(a.ang) * 3.0 + Noise.unit(a.x, 37, a.z, seed)
                if (score > bestScore) {
                    bestScore = score
                    best = a
                    bestW = w
                }
            }
            val a = best ?: return
            val w = bestW
            val t = w.cw
            for (s in -1..1) for (k in 1..3) {
                val x = a.x + t.dx * s + w.dx * k
                val z = a.z + t.dz * s + w.dz * k
                put(x, sea, z, deckWood.planks)
                carve(x, sea + 1, z)
                carve(x, sea + 2, z)
            }
            // The grille across the mouth, and a gate in it
            for (s in -1..1) {
                reserve(a.x + t.dx * s, sea + 1, a.z + t.dz * s)
                reserve(a.x + t.dx * s, sea + 2, a.z + t.dz * s)
            }
            for (s in listOf(-1, 1)) for (y in sea + 1..sea + 2) put(a.x + t.dx * s, y, a.z + t.dz * s, "iron_bars")
            put(a.x, sea + 1, a.z, deckWood.gate, "facing" to w.id, "open" to "false", "in_wall" to "false")
            put(a.x, sea + 2, a.z, "iron_bars")
            // The strongbox at the back, candles, barrels and a skull on the way
            val cx = a.x + w.dx * 3
            val cz = a.z + w.dz * 3
            chestAt(cx, sea + 1, cz, w.opposite, LOOT_HOARD)
            for (s in listOf(-1, 1)) {
                put(cx + t.dx * s, sea + 1, cz + t.dz * s, "barrel", "facing" to "up")
                put(cx + t.dx * s, sea + 2, cz + t.dz * s, "candle", "candles" to "3", "lit" to "true")
            }
            put(a.x + w.dx * 2, sea + 2, a.z + w.dz * 2, "lantern", "hanging" to "true")
            put(a.x + w.dx * 2 + t.dx, sea + 1, a.z + w.dz * 2 + t.dz, "skeleton_skull", "rotation" to "${dice.int(16)}")
            alcoveAt = a
        }

        /** The ladder shaft up from the gallery to the knoll: a niche in the wall, then a straight climb to a hatch hidden in a thicket. */
        fun shaft() {
            var best: Col? = null
            var bestW = Dir.N
            var bestScore = -1e9
            for (h in cols.values) {
                if (!h.gallery || h.hole || h.gap || h.wall > 1.01) continue
                val w = wallDir(h) ?: continue
                val sx = h.x + w.dx
                val sz = h.z + w.dz
                val top = terrain.top(sx, sz)
                if (top < sea + 9 || !roomOn(h, 2, 1)) continue
                var ok = true
                for (y in sea + 4 until top) if (open(sx, y, sz) || open(sx + w.dx, y, sz + w.dz)) ok = false
                if (!ok) continue
                if (alcoveAt?.let { abs(it.i - h.i) + abs(it.j - h.j) < 5 } == true) continue
                val score = Noise.unit(h.x, 41, h.z, seed) * 3 + (if (abs(h.ang) < 1.6) 2.0 else 0.0)
                if (score > bestScore) {
                    bestScore = score
                    best = h
                    bestW = w
                }
            }
            val h = best ?: return
            val w = bestW
            val sx = h.x + w.dx
            val sz = h.z + w.dz
            val top = terrain.top(sx, sz)
            for (y in sea + 4..top - 2) put(sx, y, sz, "ladder", "facing" to w.opposite.id)
            put(sx, top - 1, sz, deckWood.trapdoor, "facing" to w.opposite.id, "half" to "top", "open" to "false")
            hatchX = sx
            hatchZ = sz
            hatchY = top
            hatchOut = w.opposite
            hang(h, sea + 5)
            h.gap = true
        }

        /** Is this a deck column the table set may take: floor, clear air, no pier? */
        fun tableCell(col: Col?, h: Int): Boolean = roomOn(col, h) && col!!.floor == Floor.DECK && !col.pier

        /** The long table down the middle of the deck, with a bench either side, stools at the ends and a few things on it. */
        fun table() {
            var bestScore = -1e9
            var best: Col? = null
            var bestAx = side
            var bestLen = 0
            for (len in intArrayOf(5, 4, 3)) {
                for (ax in listOf(side, back)) {
                    val pp = ax.cw
                    for (base in cols.values) {
                        val cells = (0 until len).map { k -> colAt(base.x + ax.dx * k, base.z + ax.dz * k) }
                        if (cells.any { !tableCell(it, 2) }) continue
                        val benchA = cells.map { colAt(it!!.x + pp.dx, it.z + pp.dz) }
                        val benchB = cells.map { colAt(it!!.x - pp.dx, it.z - pp.dz) }
                        if (benchA.any { !tableCell(it, 1) } || benchB.any { !tableCell(it, 1) }) continue
                        val mid = cells[len / 2]!!
                        val score = len * 2.0 - hypot(mid.u * 0.7, mid.v - (chord + 2.8)) - 2.0 * cells.count { it!!.gallery } + 1.5 * Noise.unit(base.x, 43, base.z, seed)
                        if (score > bestScore) {
                            bestScore = score
                            best = base
                            bestAx = ax
                            bestLen = len
                        }
                    }
                }
                if (best != null) break
            }
            val base = best ?: return
            val len = bestLen
            val ax = bestAx
            val pp = ax.cw
            val cells = (0 until len).map { k -> colAt(base.x + ax.dx * k, base.z + ax.dz * k)!! }
            val stairsBench = dice.chance(0.5)
            for ((k, col) in cells.withIndex()) {
                put(col.x, sea + 1, col.z, deckWood.slab, "type" to "top")
                val a = colAt(col.x + pp.dx, col.z + pp.dz)!!
                val bcol = colAt(col.x - pp.dx, col.z - pp.dz)!!
                put(a.x, sea + 1, a.z, deckWood.stairs, "facing" to pp.id, "half" to "bottom", "shape" to "straight")
                if (stairsBench) put(bcol.x, sea + 1, bcol.z, deckWood.stairs, "facing" to pp.opposite.id, "half" to "bottom", "shape" to "straight")
                else put(bcol.x, sea + 1, bcol.z, deckWood.slab, "type" to "bottom")
                // On the table: candles, plates, the odd skull
                val n = Noise.unit(col.x, 47 + k, col.z, seed)
                when {
                    n < 0.25 -> put(col.x, sea + 2, col.z, "candle", "candles" to "${1 + dice.int(3)}", "lit" to "true")
                    n < 0.5 -> put(col.x, sea + 2, col.z, deckWood.plate)
                    n < 0.56 -> put(col.x, sea + 2, col.z, "skeleton_skull", "rotation" to "${dice.int(16)}")
                }
            }
            // Stools at the two ends
            for (e in listOf(-1, len)) {
                val col = colAt(base.x + ax.dx * e, base.z + ax.dz * e) ?: continue
                if (tableCell(col, 1)) put(col.x, sea + 1, col.z, deckWood.stairs, "facing" to (if (e < 0) ax.opposite else ax).id, "half" to "bottom", "shape" to "straight")
            }
            tableCells = cells
        }

        /** The galley: a stone hearth with a campfire and a cauldron against the wall, a stool and a barrel by it, and a smoking rack. */
        fun galley() {
            var bestScore = -1e9
            var best: List<Col>? = null
            var bestLevel = 0
            for (level in 0..1) for (run in runs(level, 3)) {
                if (run.any { !roomOn(it, 2, level) } || level == 1) continue
                val mid = run[1]
                val inside = inward(mid)
                val front = colAt(mid.x + inside.dx, mid.z + inside.dz)
                if (!roomOn(front, 1, level)) continue
                val score = -abs(abs(mid.ang) - 1.3) + Noise.unit(mid.x, 53, mid.z, seed) * 2 + (if (mid.gallery) -1.0 else 0.5)
                if (score > bestScore) {
                    bestScore = score
                    best = run
                    bestLevel = level
                }
            }
            val run = best ?: return
            val fire = run[1]
            val pot = run[0]
            val keg = run[2]
            val stone = dice.pick(listOf("cobblestone", "stone_bricks", "mossy_cobblestone", "smooth_stone"))
            put(fire.x, sea, fire.z, stone)
            put(pot.x, sea, pot.z, stone)
            val wf = inward(fire)
            put(fire.x, sea + 1, fire.z, "campfire", "facing" to wf.id, "lit" to "true", "signal_fire" to "false")
            spots.add(Spot(SoundSpot.CAMP, fire.x, sea + 1, fire.z))
            put(pot.x, sea + 1, pot.z, "cauldron")
            put(keg.x, sea + 1, keg.z, "barrel", "facing" to "up")
            val stool = colAt(fire.x + wf.dx, fire.z + wf.dz)!!
            put(stool.x, sea + 1, stool.z, deckWood.stairs, "facing" to wf.opposite.id, "half" to "bottom", "shape" to "straight")
            rack(fire)
        }

        /** A smoking rack by the fire: two fence posts, a beam and chains with dried kelp on them; or kelp hung from the gallery floor. */
        fun rack(fire: Col) {
            // Hung from the gallery over the cook's side, if there's one
            val hang = cols.values.filter { it.gallery && !it.hole && abs(it.i - fire.i) + abs(it.j - fire.j) in 1..3 && roomOn(it, 2) && it.floor != Floor.POOL }
            // A free frame: posts four apart in a line, two chains between them
            for (ax in Dir.entries) for (off in 1..3) {
                val cells = (0..3).map { k -> colAt(fire.x + ax.dx * (off + k) + ax.cw.dx, fire.z + ax.dz * (off + k) + ax.cw.dz) }
                if (cells.any { it == null || !roomOn(it, 4) || it.floor == Floor.POOL || it.pier }) continue
                for (y in sea + 1..sea + 4) {
                    put(cells[0]!!.x, y, cells[0]!!.z, deckWood.fence)
                    put(cells[3]!!.x, y, cells[3]!!.z, deckWood.fence)
                }
                for (k in 1..2) {
                    val c2 = cells[k]!!
                    put(c2.x, sea + 4, c2.z, deckWood.fence)
                    put(c2.x, sea + 3, c2.z, "iron_chain", "axis" to "y")
                    put(c2.x, sea + 2, c2.z, "dried_kelp_block")
                }
                return
            }
            for (col in hang.take(3)) {
                put(col.x, sea + 2, col.z, "iron_chain", "axis" to "y")
                put(col.x, sea + 1, col.z, "dried_kelp_block")
            }
        }

        /** The navigator's corner: charts on a cartography table, a loom, a lectern and shelves along a stretch of wall, on the deck or the gallery. */
        fun mapCorner() {
            var bestScore = -1e9
            var best: List<Col>? = null
            var bestLevel = 0
            for (level in 0..1) for (run in runs(level, 4)) {
                if (run.any { !roomOn(it, 2, level) }) continue
                val score = -abs(abs(run[1].ang) - 0.9) + Noise.unit(run[1].x, 59, run[1].z, seed) * 2 - level * 1.5
                if (score > bestScore) {
                    bestScore = score
                    best = run
                    bestLevel = level
                }
            }
            val run = best ?: return
            val y = floorTop(bestLevel)
            put(run[0].x, y, run[0].z, "bookshelf")
            put(run[0].x, y + 1, run[0].z, "bookshelf")
            put(run[1].x, y, run[1].z, "lectern", "facing" to inward(run[1]).id)
            put(run[2].x, y, run[2].z, "cartography_table")
            put(run[2].x, y + 1, run[2].z, "white_carpet")
            put(run[3].x, y, run[3].z, "loom", "facing" to inward(run[3]).id)
            put(run[3].x, y + 1, run[3].z, "candle", "candles" to "2", "lit" to "true")
        }

        /** Stacks of cargo against the walls and on the dock: barrels, chests and bales, some of them stocked. */
        fun cargo() {
            val clusters = dice.int(4, 6)
            val spots = ArrayList<Col>()
            for (level in 0..1) {
                for (run in runs(level, 2).sortedBy { Noise.unit(it[0].x, 61 + level, it[0].z, seed) }) {
                    if (spots.size >= clusters) break
                    if (spots.any { abs(it.i - run[0].i) + abs(it.j - run[0].j) < 5 }) continue
                    val cells = ArrayList<Col>()
                    for (c0 in run) {
                        val inside = inward(c0)
                        for (col in listOf(c0, colAt(c0.x + inside.dx, c0.z + inside.dz))) if (col != null && col !in cells && roomOn(col, 1, level)) cells.add(col)
                    }
                    if (cells.size < 3) continue
                    spots.add(run[0])
                    var stocked = false
                    for (col in cells) {
                        val cap = if (level == 1) 2 else if (col.gallery) 2 else 3
                        val h = dice.int(1, cap)
                        for (k in 0 until h) {
                            val y = floorTop(level) + k
                            if (!free(col.x, y, col.z) || y > col.ceil) break
                            val facing = inward(col)
                            val n = dice.next()
                            when {
                                n < 0.22 && !stocked -> {
                                    chestAt(col.x, y, col.z, facing, LOOT_STORES)
                                    stocked = true
                                }
                                n < 0.32 -> chestAt(col.x, y, col.z, facing, LOOT_STORES, barrel = true)
                                n < 0.78 -> put(col.x, y, col.z, "barrel", "facing" to dice.pick(listOf("up", "up", "up", "north", "east")))
                                n < 0.9 -> put(col.x, y, col.z, "hay_block", "axis" to "y")
                                else -> put(col.x, y, col.z, deckWood.strippedLog("y"))
                            }
                        }
                    }
                }
            }
        }

        /** Bunks along the gallery: beds with their heads to the wall (or lying along it), a candle by each, and a hammock or two. */
        fun bunks() {
            val beds = ArrayList<Col>()
            val colors = listOf("red", "blue", "brown", "gray", "black", "green", "white", "yellow")
            for (h in rim(1)) {
                if (beds.any { abs(it.i - h.i) + abs(it.j - h.j) < 3 }) continue
                if (Noise.unit(h.x, 67, h.z, seed) < 0.25) continue
                val w = wallDir(h) ?: continue
                if (!roomOn(h, 2, 1)) continue
                val color = dice.pick(colors)
                // Head to the wall, foot in front if the gallery's deep enough there; else lying along the wall
                val foot = at(h, w, 1, 0)
                var placed = false
                if (roomOn(foot, 2, 1)) {
                    put(foot!!.x, sea + 4, foot.z, "${color}_bed", "facing" to w.id, "part" to "foot")
                    put(h.x, sea + 4, h.z, "${color}_bed", "facing" to w.id, "part" to "head")
                    placed = true
                } else {
                    for (t in listOf(w.cw, w.ccw)) {
                        val other = colAt(h.x + t.dx, h.z + t.dz)
                        if (!roomOn(other, 2, 1) || other!!.wall > 1.01) continue
                        put(other.x, sea + 4, other.z, "${color}_bed", "facing" to t.opposite.id, "part" to "foot")
                        put(h.x, sea + 4, h.z, "${color}_bed", "facing" to t.opposite.id, "part" to "head")
                        beds.add(other)
                        placed = true
                        break
                    }
                }
                if (!placed) continue
                beds.add(h)
                // A candle on a barrel at the bed's side
                val t = w.cw
                val next = colAt(h.x + t.dx * 2, h.z + t.dz * 2)
                if (roomOn(next, 2, 1) && next!!.wall <= 1.01) {
                    put(next.x, sea + 4, next.z, "barrel", "facing" to "up")
                    put(next.x, sea + 5, next.z, "candle", "candles" to "${1 + dice.int(3)}", "lit" to "true")
                }
            }
        }

        /** The pulley rig: a mast at the dock's edge with a boom out over it, a chain from the tip and a barrel hanging from the chain. */
        fun rig() {
            var best: Col? = null
            var bestOut = Dir.N
            var bestScore = -1e9
            for (r in cols.values) {
                if (r.floor == Floor.POOL || r.hole || !(r.curb || r.pier)) continue
                if (!(1..5).all { y -> free(r.x, sea + y, r.z) } || r.ceil < sea + 5) continue
                for (o in Dir.entries) {
                    val cells = (1..3).map { k -> colAt(r.x + o.dx * k, r.z + o.dz * k) }
                    if (cells.any { it == null || !it.open || it.ceil < sea + 5 || !free(it.x, sea + 5, it.z) || !free(it.x, sea + 4, it.z) || !free(it.x, sea + 3, it.z) || !free(it.x, sea + 2, it.z) }) continue
                    // Out over the water is best
                    val wet = cells.count { it!!.floor == Floor.POOL }
                    val score = wet * 2.0 + Noise.unit(r.x, 71, r.z, seed) * 2 + (if (r.tide != catwalk) 1.0 else 0.0) - (if (r.pier) 1.0 else 0.0)
                    if (score > bestScore) {
                        bestScore = score
                        best = r
                        bestOut = o
                    }
                }
            }
            val r = best ?: return
            val o = bestOut
            for (y in sea + 1..sea + 5) put(r.x, y, r.z, frameWood.strippedLog("y"))
            for (k in 1..3) put(r.x + o.dx * k, sea + 5, r.z + o.dz * k, deckWood.fence)
            put(r.x + o.dx, sea + 4, r.z + o.dz, deckWood.stairs, "facing" to o.opposite.id, "half" to "top", "shape" to "straight")
            val tx = r.x + o.dx * 3
            val tz = r.z + o.dz * 3
            put(tx, sea + 4, tz, "iron_chain", "axis" to "y")
            put(tx, sea + 3, tz, "iron_chain", "axis" to "y")
            put(tx, sea + 2, tz, "barrel", "facing" to "up")
        }

        /** Odds and ends: bones from meals, a broken crate, spilled powder, candle stubs. */
        fun clutter() {
            for (col in cols.values.sortedBy { Noise.unit(it.x, 73, it.z, seed) }) {
                if (col.floor == Floor.POOL || col.pier || col.hole || !roomOn(col, 1)) continue
                val n = Noise.unit(col.x, 79, col.z, seed)
                when {
                    n < 0.035 -> put(col.x, sea + 1, col.z, "bone_block", "axis" to dice.pick(listOf("x", "z")))
                    n < 0.06 -> put(col.x, sea + 1, col.z, "skeleton_skull", "rotation" to "${dice.int(16)}")
                    n < 0.09 -> put(col.x, sea + 1, col.z, dice.pick(listOf("black_carpet", "white_carpet", "brown_carpet")))
                    n < 0.115 -> put(col.x, sea + 1, col.z, deckWood.trapdoor, "facing" to dice.pick(Dir.entries).id, "half" to "bottom", "open" to "true")
                }
            }
        }

        /** The smugglers themselves, a few on the deck. */
        fun spawns() {
            val want = if (hideSite) dice.int(2, 3) else dice.int(2, 4)
            var made = 0
            val taken = ArrayList<Col>()
            for (col in cols.values.sortedBy { Noise.unit(it.x, 83, it.z, seed) }) {
                if (made >= want) break
                if (col.floor != Floor.DECK || col.pier || col.hole || col.wall < 2.0 || !roomOn(col, 2)) continue
                if (taken.any { abs(it.i - col.i) + abs(it.j - col.j) < 3 }) continue
                taken.add(col)
                val kind = if (made % 2 == 0) "minecraft:vindicator#smuggler" else "minecraft:pillager#smuggler"
                b.spawn(kind, col.x + 0.5, sea + 1.0, col.z + 0.5, yaw(dice.pick(Dir.entries)))
                made++
            }
        }

        /** Joins every fence, pane and bar up with what stands by it, as the game does once the chunk is finished. */
        fun finish() {
            for (p in joins) {
                val x = Growth.x(p)
                val y = Growth.y(p)
                val z = Growth.z(p)
                val bk = b.at(x, y, z) ?: continue
                var out = bk
                for (d in Dir.entries) out = out.with(d.id, links(bk, x + d.dx, y, z + d.dz, d))
                b.set(x, y, z, out)
            }
        }

        /** Does [bk] (a fence, pane or bars) reach out to the block at ([x], [y], [z]) on its [d] side? */
        private fun links(bk: Bk, x: Int, y: Int, z: Int, d: Dir): Boolean {
            val n = b.at(x, y, z)
            if (n != null) {
                val name = n.name
                if (bk.name.endsWith("_fence")) {
                    if (name.endsWith("_fence")) return true
                    if (name.endsWith("_fence_gate")) return n.has("facing", d.cw.id) || n.has("facing", d.ccw.id)
                }
                if (bk.name == "iron_bars" || bk.name.endsWith("_pane")) {
                    if (name == "iron_bars" || name.endsWith("_pane")) return true
                }
                return isFull(n)
            }
            // Open air of the cave, or the cave's own rock
            return !open(x, y, z) && y >= sea - 3
        }
    }

    // ---- the surface

    /** Leaves that stay on the bough. */
    private fun leaves(kind: String): Bk = Bk.of("${kind}_leaves", "persistent" to "true")

    /** The leaves a thicket is made of in this climate; [n] (0 to 1) picks among the kinds this island grows. */
    private fun thatch(n: Double): Bk = when (plan.climate) {
        Climate.WARM -> if (n < 0.8) leaves("jungle") else leaves("azalea")
        Climate.LUKEWARM -> if (n < 0.4) leaves("oak") else if (n < 0.75) leaves("azalea") else Bk.of("flowering_azalea_leaves", "persistent" to "true")
        Climate.TEMPERATE -> if (n < 0.8) leaves("oak") else Bk.of("flowering_azalea_leaves", "persistent" to "true")
        Climate.COLD, Climate.FROZEN -> leaves("spruce")
    }

    /** The trunks that show in a thicket's heart: the wood of the island's own shrubs. */
    private fun stem(): Bk = Bk.of(
        when (plan.climate) {
            Climate.WARM -> "jungle_log"
            Climate.COLD, Climate.FROZEN -> "spruce_log"
            else -> "oak_log"
        },
        "axis" to "y",
    )

    /** Sets a shrub or boulder of the island's own making where [f] stands: its leaves where there's room, its wood and stone over the ground. */
    private fun plant(b: Build, f: Feature) {
        val g = Growth()
        f.grow(g)
        for ((p, bk) in g.soft) b.soft(Growth.x(p), Growth.y(p), Growth.z(p), bk)
        for ((p, bk) in g.hard) {
            val x = Growth.x(p)
            val y = Growth.y(p)
            val z = Growth.z(p)
            if (y >= terrain.top(x, z) - f.buried) b.set(x, y, z, bk)
        }
    }

    /** The island's meadow over the claimed columns of a yard, as the plan itself would have left it but for the claim. */
    private fun meadow(b: Build, x: Int, z: Int) {
        val t = terrain
        val top = t.top(x, z)
        if (!t.dry(x, z) || b.at(x, top, z) != null) return
        val plants = Plants.cover(plan.climate, plan.groundAt(x, z), x, top, z, seed, 0.55, 0.3, false) ?: return
        for ((k, bk) in plants.withIndex()) b.soft(x, top + k, z, bk)
    }

    /** The hatch's thicket: a hollow mound of leaves over the trapdoor, with the way in low on one side. */
    private inner class Thicket(val b: Build) {
        private val t = terrain

        /** The mound's reach from the hatch in the column ([x], [z]): a little ragged. */
        fun reach(x: Int, z: Int): Double = 2.8 + 0.7 * (Noise.unit(x, 101, z, seed) * 2 - 1)

        fun draw() {
            val hx = hatchX
            val hz = hatchZ
            val hy = hatchY
            // The way in runs along whichever side lies level with the hatch for two columns, the climber's own facing first
            val sides = listOf(hatchOut, hatchOut.cw, hatchOut.ccw, hatchOut.opposite)
            val exit = sides.minByOrNull { d -> (1..2).sumOf { k -> abs(t.top(hx + d.dx * k, hz + d.dz * k) - hy) } } ?: hatchOut
            val way = (0..2).map { k -> Pair(hx + exit.dx * k, hz + exit.dz * k) }
            mound(way)
            // Stems showing through at two corners of the hollow
            var shown = 0
            for ((sx, sz) in listOf(Pair(1, 1), Pair(-1, -1), Pair(1, -1), Pair(-1, 1))) {
                val x = hx + sx
                val z = hz + sz
                if (shown >= 2 || way.contains(Pair(x, z)) || !t.dry(x, z) || Noise.unit(x, 109, z, seed) < 0.35) continue
                b.set(x, t.top(x, z), z, stem())
                shown++
            }
            // The roof over the hatch and the first column of the way; the trapdoor lies hidden under moss or snow
            for (k in 0..1) {
                val (x, z) = way[k]
                b.set(x, t.top(x, z) + 2, z, thatch(Noise.unit(x, 113, z, seed)))
            }
            if (plan.climate == Climate.FROZEN) b.soft(hx, hy, hz, Bk.of("snow", "layers" to "2")) else b.soft(hx, hy, hz, Bk.of("moss_carpet"))
            foot(way)
            cairn(exit)
        }

        /** The mound: tallest by the way in, ragged at its rim. */
        fun mound(way: List<Pair<Int, Int>>) {
            val hx = hatchX
            val hz = hatchZ
            for (dx in -3..3) for (dz in -3..3) {
                val x = hx + dx
                val z = hz + dz
                if (way.contains(Pair(x, z)) || !t.dry(x, z)) continue
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d > reach(x, z)) continue
                val g = t.top(x, z)
                val byWay = way.any { abs(it.first - x) <= 1 && abs(it.second - z) <= 1 }
                var h = if (byWay || d < 1.9) 3 else if (d < 2.5) 2 else 1
                if (!byWay && d > 1.6 && Noise.unit(x, 103, z, seed) < 0.22) h--
                val topY = if (byWay) max(g + h - 1, hatchY + 2) else g + h - 1
                for (y in g..topY) b.set(x, y, z, thatch(Noise.unit(x, y, z, seed)))
                // A snow cap on the frozen island's mound
                if (plan.climate == Climate.FROZEN) b.soft(x, topY + 1, z, Bk.of("snow", "layers" to "1"))
            }
        }

        /** Plants round the mound's foot, and the meadow over what the claim left bare. */
        fun foot(way: List<Pair<Int, Int>>) {
            val hx = hatchX
            val hz = hatchZ
            for (dx in -5..5) for (dz in -5..5) {
                val x = hx + dx
                val z = hz + dz
                if (way.contains(Pair(x, z)) || !t.dry(x, z)) continue
                val d = hypot(dx.toDouble(), dz.toDouble())
                val g = t.top(x, z)
                if (d > reach(x, z) + 1.8) continue
                if (d < reach(x, z) - 0.2) continue
                val n = Noise.unit(x, 107, z, seed)
                when (plan.climate) {
                    Climate.FROZEN -> if (n < 0.5) b.soft(x, g, z, Bk.of("snow", "layers" to "${1 + (n * 6).toInt() % 3}"))
                    Climate.COLD -> when {
                        n < 0.3 -> b.soft(x, g, z, Bk.of("fern"))
                        n < 0.42 -> b.soft(x, g, z, Bk.of("sweet_berry_bush", "age" to "3"))
                    }
                    Climate.WARM -> when {
                        n < 0.25 -> b.soft(x, g, z, Bk.of("fern"))
                        n < 0.4 -> b.soft(x, g, z, Bk.of("bush"))
                        n < 0.48 -> tall(x, g, z)
                    }
                    else -> when {
                        n < 0.28 -> b.soft(x, g, z, Bk.of("fern"))
                        n < 0.4 -> b.soft(x, g, z, Bk.of("sweet_berry_bush", "age" to "${2 + (n * 100).toInt() % 2}"))
                        n < 0.48 -> tall(x, g, z)
                    }
                }
            }
            for (dx in -3..3) for (dz in -3..3) if (!way.contains(Pair(hx + dx, hz + dz)) && hypot(dx.toDouble(), dz.toDouble()) > 1.0) meadow(b, hx + dx, hz + dz)
        }

        /** A large fern, two blocks tall. */
        fun tall(x: Int, g: Int, z: Int) {
            b.soft(x, g, z, Bk.of("large_fern", "half" to "lower"))
            b.soft(x, g + 1, z, Bk.of("large_fern", "half" to "upper"))
        }

        /** A cairn of boulders by the mound, on the side away from the way in: always on the cold islands, now and then elsewhere. */
        fun cairn(exit: Dir) {
            if (plan.climate != Climate.COLD && plan.climate != Climate.FROZEN && Noise.unit(hatchX, 127, hatchZ, seed) >= 0.5) return
            val away = exit.opposite
            for (k in 0..1) {
                val x = hatchX + away.dx * (4 + k) + exit.cw.dx * (k * 2 - 1)
                val z = hatchZ + away.dz * (4 + k) + exit.cw.dz * (k * 2 - 1)
                if (!t.dry(x, z)) continue
                plant(b, Boulder(x, t.top(x, z), z, Noise.hash(x, 5, z, seed).toLong(), plan.rockAt(x, t.top(x, z) - 1, z).name))
            }
        }
    }

    /**
     * The lookout hide: a low hut of boards and logs dug into the cliff top, a turf roof over it, a slit under the eaves
     * looking out over the sea with a spyglass on its sill, a bench, a lantern, a barrel of stores and a watcher.
     */
    private inner class Hide(val b: Build) {
        private val t = terrain
        private val out = hideOut
        private val across = out.cw
        private val y0 = t.top(hideX, hideZ)

        /** The world column of the hide's local cell: [a] across it (toward the clockwise side of the way it looks) and [d] out toward the sea. */
        fun wx(a: Int, d: Int): Int = hideX + across.dx * a + out.dx * d
        fun wz(a: Int, d: Int): Int = hideZ + across.dz * a + out.dz * d

        /** Puts [bk] at the local cell, [h] blocks over the ground (the floor is -1). */
        fun put(a: Int, d: Int, h: Int, bk: Bk) = b.set(wx(a, d), y0 + h, wz(a, d), bk)

        fun put(a: Int, d: Int, h: Int, name: String, vararg props: Pair<String, String>) = put(a, d, h, Bk.of(name, *props))

        /** Lays the whole hide. */
        fun draw() {
            val post = frameWood.log("y")
            // Floor: boards inside, logs under the walls
            for (a in -2..2) for (d in -2..2) {
                val inside = abs(a) <= 1 && abs(d) <= 1
                put(a, d, -1, if (inside) deckWood.planks else frameWood.strippedLog(if (abs(a) == 2) out.axis else across.axis))
            }
            // Walls: boards below, a slit under the eaves out front and a window each side
            for (a in -2..2) for (d in -2..2) {
                if (abs(a) < 2 && abs(d) < 2) continue
                val corner = abs(a) == 2 && abs(d) == 2
                for (h in 0..1) {
                    when {
                        corner -> put(a, d, h, post)
                        d == -2 && a == 0 -> {}
                        d == 2 && h == 1 -> {}
                        abs(a) == 2 && d == 0 && h == 1 -> {}
                        else -> put(a, d, h, if (h == 1 && Noise.unit(wx(a, d), 131, wz(a, d), seed) < 0.3) frameWood.planks else deckWood.planks)
                    }
                }
            }
            // The door in the back wall
            put(0, -2, 0, deckWood.door, "facing" to out.id, "half" to "lower", "hinge" to "left", "open" to "false", "powered" to "false")
            put(0, -2, 1, deckWood.door, "facing" to out.id, "half" to "upper", "hinge" to "left", "open" to "false", "powered" to "false")
            // The ceiling: boards with a beam along every other row
            for (a in -2..2) for (d in -2..2) {
                put(a, d, 2, if (Math.floorMod(d, 2) == 0) frameWood.strippedLog(across.axis) else deckWood.planks)
            }
            // The eaves: stairs all round, their backs to the roof
            for (k in -2..2) {
                put(3, k, 2, deckWood.stairs, "facing" to across.opposite.id, "half" to "bottom", "shape" to "straight")
                put(-3, k, 2, deckWood.stairs, "facing" to across.id, "half" to "bottom", "shape" to "straight")
                put(k, 3, 2, deckWood.stairs, "facing" to out.opposite.id, "half" to "bottom", "shape" to "straight")
                put(k, -3, 2, deckWood.stairs, "facing" to out.id, "half" to "bottom", "shape" to "straight")
            }
            roof()
            fittings()
            yard()
        }

        /** The roof's turf: moss or thatch by the climate, with tufts of growth and a few leaves laid over it for cover. */
        fun roof() {
            val top: Bk = when (plan.climate) {
                Climate.WARM -> Bk.of("hay_block", "axis" to "y")
                Climate.FROZEN -> Bk.of("snow_block")
                Climate.COLD -> leaves("spruce")
                else -> Bk.of("moss_block")
            }
            for (a in -2..2) for (d in -2..2) {
                put(a, d, 3, top)
                val n = Noise.unit(wx(a, d), 137, wz(a, d), seed)
                if (plan.climate == Climate.FROZEN) {
                    if (n < 0.5) b.soft(wx(a, d), y0 + 4, wz(a, d), Bk.of("snow", "layers" to "${1 + (n * 8).toInt() % 3}"))
                } else if (n < 0.4) {
                    b.soft(wx(a, d), y0 + 4, wz(a, d), if (plan.climate == Climate.COLD) Bk.of("fern") else if (n < 0.1) Bk.of("fern") else Bk.of("moss_carpet"))
                }
            }
        }

        /** The inside and the yard: a log bench, a barrel of stores, a lantern, the spyglass on the counter, a signal lamp outside and the watcher. */
        fun fittings() {
            // The bench along the left wall, a candle at its end, a lantern hung over it
            for (d in -1..1) put(-1, d, 0, frameWood.log(out.axis))
            put(-1, 1, 1, "candle", "candles" to "2", "lit" to "true")
            put(-1, 0, 1, "lantern", "hanging" to "true")
            // A barrel of stores by the door
            b.chest(wx(1, -1), y0, wz(1, -1), out, LOOT_STORES, barrel = true)
            // The counter's spyglass: a brass rod laid on the sill pointing out to sea
            put(1, 2, 1, "lightning_rod", "facing" to out.id, "powered" to "false")
            put(-1, 2, 1, "lantern", "hanging" to "false")
            // A skull on the counter, a bit of cloth on the floor
            put(0, 2, 1, "skeleton_skull", "rotation" to "${out.ordinal * 4}")
            put(1, 0, 0, dice.pick(listOf("brown_carpet", "red_carpet", "gray_carpet")))
            // The signal lamp on a post at the corner of the yard
            put(3, 3, 0, deckWood.fence)
            put(3, 3, 1, deckWood.fence)
            put(3, 3, 2, "lantern", "hanging" to "false")
            // Stones for a doorstep and a woodpile against the wall
            put(0, -3, -1, "cobblestone")
            put(-2, -3, 0, frameWood.log("x"))
            put(-1, -3, 0, frameWood.log("x"))
            put(-2, -3, 1, frameWood.log("x"))
            // The watcher, and the sound of the lookout's signalling
            b.spawn("minecraft:pillager#smuggler", wx(0, 0) + 0.5, y0.toDouble(), wz(0, 0) + 0.5, yaw(out))
            b.sound(SoundSpot.LOOKOUT, wx(0, 1), y0 + 1, wz(0, 1))
        }

        /** The ring of claimed ground round the hut, dressed as meadow. */
        fun yard() {
            for (a in -4..4) for (d in -4..4) if (abs(a) > 2 || abs(d) > 2) meadow(b, wx(a, d), wz(a, d))
        }
    }

    // ---- palette

    /** A wood the den can be built of, and everything made from it. */
    private class Timber(val name: String, val boat: String, private val bamboo: Boolean = false) {
        val planks: Bk = Bk.of("${name}_planks")
        val stairs: String = "${name}_stairs"
        val slab: String = "${name}_slab"
        val fence: Bk = Bk.of("${name}_fence")
        val trapdoor: String = "${name}_trapdoor"
        val gate: String = "${name}_fence_gate"
        val door: String = "${name}_door"
        val plate: String = "${name}_pressure_plate"
        val stripped: String = if (bamboo) "stripped_bamboo_block" else "stripped_${name}_log"

        fun log(axis: String): Bk = Bk.of(if (bamboo) "bamboo_block" else "${name}_log", "axis" to axis)

        fun strippedLog(axis: String): Bk = Bk.of(stripped, "axis" to axis)

        companion object {
            val OAK = Timber("oak", "oak_boat")
            val SPRUCE = Timber("spruce", "spruce_boat")
            val BIRCH = Timber("birch", "birch_boat")
            val JUNGLE = Timber("jungle", "jungle_boat")
            val ACACIA = Timber("acacia", "acacia_boat")
            val DARK_OAK = Timber("dark_oak", "dark_oak_boat")
            val MANGROVE = Timber("mangrove", "mangrove_boat")
            val BAMBOO = Timber("bamboo", "bamboo_raft", bamboo = true)
        }
    }

    // ---- helpers

    /** Minecraft's yaw for facing [d]: south is 0, west 90. */
    private fun yaw(d: Dir): Float = when (d) {
        Dir.S -> 0f
        Dir.W -> 90f
        Dir.N -> 180f
        Dir.E -> 270f
    }

    /** Waterlogs [bk] if it's a kind of block water can fill, for something standing under the sea. */
    private fun wet(bk: Bk): Bk {
        val n = bk.name
        val floods = n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_fence") || n.endsWith("_wall") ||
            n.endsWith("_trapdoor") || n.endsWith("_pane") || n.endsWith("_bars") || n.endsWith("lantern") ||
            n.endsWith("chain") || n == "ladder" || n == "chest" || n.endsWith("candle") || n == "campfire" ||
            n.endsWith("lightning_rod") || n == "scaffolding" || n == "sea_pickle" || n == "decorated_pot"
        return if (floods && !bk.has("waterlogged", "true")) bk.with("waterlogged", "true") else bk
    }

    /** Is [bk] one of the blocks that reach out to their neighbours? */
    private fun isJoining(bk: Bk): Boolean {
        val n = bk.name
        return n.endsWith("_fence") || n.endsWith("_pane") || n == "iron_bars"
    }

    /** Does [bk] fill its whole block, so a fence or pane meets it? */
    private fun isFull(bk: Bk): Boolean {
        val n = bk.name
        if (n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_fence") || n.endsWith("_trapdoor") || n.endsWith("_wall")) return false
        return n.endsWith("_planks") || n.endsWith("_log") || n.endsWith("_block") || n.endsWith("bricks") || n.endsWith("stone") ||
            n.endsWith("cobblestone") || n.endsWith("_wood") || n == "barrel" || n == "bookshelf" || n.endsWith("wool") || n == "dirt" ||
            n == "moss_block" || n.endsWith("terracotta") || n.endsWith("deepslate") || n.endsWith("tuff") || n == "gravel" || n == "sand" ||
            n == "loom" || n == "cartography_table" || n == "fletching_table" || n == "smithing_table"
    }

    companion object {
        /** How wide the dock runs along the walls where the deck doesn't reach, in blocks from the wall. */
        private const val RING = 2.2

        /** How far from the wall the gallery's floor reaches, from a column's middle to the nearest rock's. */
        private const val GALLERY = 2.3

        /** Half the width of the channel the boats keep clear, either side of the tunnel's middle. */
        private const val LANE = 1.7

        /** The sites a hide will settle for, best first. */
        private val HIDE_TIERS = listOf(Tier(7, 3, 22), Tier(5, 4, 26), Tier(3, 5, 34))

        /** Where less than this much rock lies over the cave's roof, the ground is kept clear of the island's trees and stones. */
        private const val COVER = 5

        const val LOOT_STORES = "guildmark:chests/island/smugglers"
        const val LOOT_HOARD = "guildmark:chests/island/smugglers_hoard"
    }
}
