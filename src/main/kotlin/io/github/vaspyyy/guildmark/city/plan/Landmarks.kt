package io.github.vaspyyy.guildmark.city.plan

import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The great buildings of a grand capital: the cathedral on the old town's square, the palace keep on the
 * citadel, and the manors of the noble quarter. Each is drawn in its own frame, facing north (see [Frame]),
 * scaled to its lot and kept inside it.
 */
class Landmarks(private val f: Frame, private val b: Building, private val r: Random) {
    private val w = f.w
    private val d = f.d

    fun draw() {
        when (b.kind) {
            Kind.CATHEDRAL -> Cathedral().draw()
            Kind.PALACE -> Palace().draw()
            Kind.MANOR -> Manor().draw()
            else -> {}
        }
    }

    // ---- The cathedral ----

    /**
     * A Gothic cathedral with its west front to the square: twin towers with spires (or one great tower)
     * either side of a deep portal under a rose window, a tall nave between lower aisles held up by flying
     * buttresses with pinnacles, a crossing with transepts reaching the sides of the lot (a rose window in
     * each gable), and an apse of glass at the far end where the altar stands on its steps. Inside, pale
     * pillars carry pointed arches and a ribbed vault high over the pews, lit by chandeliers on long chains.
     */
    private inner class Cathedral {
        /** Small, middling or grand, by the width of the lot. */
        val size = when {
            w >= 27 -> 2
            w >= 24 -> 1
            else -> 0
        }
        /** One great tower over the portal, or a tower either side of it. */
        val single = r.nextInt(3) == 0
        val glass = GLASS_SETS[r.nextInt(GLASS_SETS.size)]
        val stoneSpires = r.nextInt(3) == 0
        val banner = BANNER_COLORS[r.nextInt(BANNER_COLORS.size)]

        // Across the front: room for the buttresses outside the aisle walls, the aisles, the arcades, the nave
        val ext = if (size == 2) 3 else 2
        val aisle = if (size == 0) 3 else 4
        val wallL = ext
        val wallR = w - 1 - ext
        val pierL = ext + aisle + 1
        val pierR = w - 1 - pierL
        val mid = (w - 1) / 2.0
        val midL = (w - 1) / 2
        val midR = w / 2

        // Up: the aisle walls and their arcades, the vault's springing over the clerestory, the nave walls, the ridge
        val aisleTop = 9 + size
        val apex = aisleTop - 2
        val spring = aisleTop + 10
        val naveTop = spring + 1
        val ridge = naveTop + 1 + PITCH * (midL - pierL)
        val towerTop = ridge + if (single) 4 else 3

        // From the front: the towers, the nave's bays, the crossing, the choir's bays, the apse
        val front = if (single) pierR - pierL + 1 else pierL + 1
        /** Where the nave's vault and roof begin: behind the recessed front between twin towers, behind a great tower. */
        val naveStart = if (single) front else 2
        val aisleStart = if (single) 1 else front
        val cross = pierR - pierL
        val apseRadius = cross / 2.0
        val apseReach = apseRadius.roundToInt() + if (size == 0) 2 else 3
        val bay: Int
        val naveBays: Int
        val choirBays: Int
        val c0: Int
        val c1: Int
        val apse: Int

        init {
            // As many bays as fit, of four blocks or five, whichever leaves less over
            val room = d - front - cross - apseReach
            bay = if (room / 5 >= 2 && room % 5 <= room % 4) 5 else 4
            val bays = max(1, room / bay)
            choirBays = if (bays >= 4) 1 else 0
            naveBays = bays - choirBays
            c0 = front - 1 + bay * naveBays
            c1 = c0 + cross
            apse = c1 + bay * choirBays
        }

        /** The rows of pillars down the nave between the towers and the crossing, and down the choir to the apse. */
        val naveRows = (1 until naveBays).map { front - 1 + it * bay }
        val choirRows = (1..choirBays).map { c1 + it * bay }
        /** The bays, as the rows either side of each. */
        val naveSpans = (listOf(front - 1) + naveRows + listOf(c0)).zipWithNext()
        val choirSpans = (listOf(c1) + choirRows).zipWithNext()
        val crossMid = (c0 + c1) / 2

        fun draw() {
            ground()
            masses()
            vaults()
            arcades()
            windows()
            buttresses()
            if (single) greatTower() else twinTowers()
            transepts()
            crossingSpire()
            furnish()
        }

        // -- Where things are --

        fun inNave(u: Int, v: Int): Boolean = u in pierL + 1 until pierR && v in naveStart..apse
        fun inAisle(u: Int, v: Int): Boolean =
            (u in wallL + 1 until pierL || u in pierR + 1 until wallR) && (v in aisleStart until c0 || v in c1 + 1 until apse)
        fun inTransept(u: Int, v: Int): Boolean = v in c0 + 1 until c1 && u in 1 until w - 1
        fun apseDist(u: Int, v: Int): Double = sqrt((u - mid) * (u - mid) + (v - apse) * (v - apse).toDouble())
        fun inApse(u: Int, v: Int): Boolean = v > apse && apseDist(u, v) < apseRadius - 0.5
        fun apseWall(u: Int, v: Int): Boolean = v > apse && apseDist(u, v) in apseRadius - 0.5..apseRadius + 0.6
        fun inside(u: Int, v: Int): Boolean = inNave(u, v) || inAisle(u, v) || inTransept(u, v) || inApse(u, v)

        /** The underside of the vault over a column inside, or null where there's none. */
        fun vault(u: Int, v: Int): Int? {
            var h: Int? = null
            // The bay behind a recessed front is vaulted higher, to clear its rose window
            if (inNave(u, v)) h = spring + rise(min(u - pierL - 1, pierR - 1 - u))
            if (inApse(u, v)) h = spring + rise((apseRadius - 0.5 - apseDist(u, v)).roundToInt())
            if (inTransept(u, v)) {
                val t = spring + rise(min(v - c0 - 1, c1 - 1 - v))
                h = max(h ?: t, t)
            }
            if (inAisle(u, v)) h = aisleTop - 1
            return h
        }

        /** The top of the wall standing on a column, or null where there's none. */
        fun wallTop(u: Int, v: Int): Int? = when {
            // The aisles' walls, their fronts beside a great tower, and the ends of the choir's aisles
            (u == wallL || u == wallR) && (v in aisleStart until c0 || v in c1 + 1 until apse) -> aisleTop
            single && v == 0 && (u in wallL until pierL || u in pierR + 1..wallR) -> aisleTop
            choirBays > 0 && v == apse && (u in wallL until pierL || u in pierR + 1..wallR) -> aisleTop
            // The transepts' walls and their gable ends
            (v == c0 || v == c1) && (u < pierL || u > pierR) -> naveTop
            (u == 0 || u == w - 1) && v in c0..c1 -> naveTop
            // The clerestory over the arcades, open where the crossing meets the transepts; the apse
            (u == pierL || u == pierR) && v in naveStart..apse && v !in c0 + 1 until c1 -> naveTop
            apseWall(u, v) -> naveTop
            else -> null
        }

        /** The top of the roofs over a column, or null where there's none. */
        fun roofTop(u: Int, v: Int): Int? {
            var h: Int? = null
            fun take(t: Int) {
                h = max(h ?: t, t)
            }
            if (u in pierL..pierR && v in naveStart..apse) take(naveTop + 1 + PITCH * min(u - pierL, pierR - u))
            if (v > apse && apseDist(u, v) <= apseRadius + 0.6) take(naveTop + 1 + (PITCH * (apseRadius + 0.6 - apseDist(u, v))).toInt())
            if (v in c0..c1 && u in 0 until w) take(naveTop + 1 + PITCH * min(v - c0, c1 - v))
            return h
        }

        // -- Ground, walls and roofs --

        /** Paving round about; inside, a checkered way up the middle and a star under the crossing. */
        fun ground() {
            for (u in 0 until w) for (v in 0 until d) {
                val paving = when {
                    !inside(u, v) -> if (u == 0 || v == 0 || u == w - 1 || v == d - 1) 1 else 0
                    v in c0..c1 && u in pierL..pierR -> if (abs(u - mid) == abs(v - crossMid.toDouble()) || abs(u - mid) < 0.6 || v == crossMid) 2 else 1
                    abs(u - mid) <= 1.0 && v < c0 -> if ((u + v) % 2 == 0) 1 else 2
                    else -> 0
                }
                set(u, -1, v, B(M.PAVE_PLAZA, v = paving))
            }
        }

        /**
         * The stone of the walls and of everything up under the roofs, the slate on top, the aisles' terraces;
         * string courses at the sills and the eaves, and a corbel table under the clerestory's eaves.
         */
        fun masses() {
            for (u in 0 until w) for (v in 0 until d) {
                val top = roofTop(u, v) ?: continue
                val from = vault(u, v) ?: wallTop(u, v)?.plus(1) ?: (naveTop + 1)
                for (y in from until top) set(u, y, v, WALL)
            }
            roof(SLATE) { u, v -> roofTop(u, v) }
            for (u in 0 until w) for (v in 0 until d) {
                val top = wallTop(u, v) ?: continue
                for (y in 0..top) set(u, y, v, if (y == 0) DARK else if (y == top || y == 1) TRIM else WALL)
            }
            for (u in 0 until w) for (v in 0 until d) {
                if (!inAisle(u, v)) continue
                for (y in aisleTop - 1..aisleTop) set(u, y, v, WALL)
                set(u, aisleTop + 1, v, B(M.WALL_SLAB))
            }
            for (s in sides) for (v in front until c0) set(s.u(pierL - 1), naveTop, v, B(M.WALL_STAIRS, s.inward, top = true))
        }

        /** Carves the inside out under its vaults, rounding each vault's foot, with ribs of pale stone. */
        fun vaults() {
            for (u in 0 until w) for (v in 0 until d) {
                val h = vault(u, v) ?: continue
                for (y in 0 until h) set(u, y, v, AIR)
                val toward = Dir.entries.filter { n -> (vault(u + n.dx, v + n.dz) ?: -1) < h }
                    .minByOrNull { n -> vault(u + n.dx, v + n.dz) ?: -1 }
                if (toward != null) set(u, h, v, B(M.WALL_STAIRS, toward, top = true))
            }
            // Ribs across the nave and the choir at every row of pillars and round the crossing
            for (row in listOf(front - 1) + naveRows + choirRows + listOf(c0, c1)) for (u in pierL + 1 until pierR) {
                val h = vault(u, row) ?: continue
                rib(u, h, row, if (u < midL) Dir.W else if (u > midR) Dir.E else null)
            }
            for (s in sides) for (v in c0 + 1 until c1) {
                val h = vault(s.u(pierL), v) ?: continue
                rib(s.u(pierL), h, v, if (v < crossMid) Dir.N else if (v > crossMid) Dir.S else null)
            }
        }

        /** A rib a block under the vault at [h], its foot rounded toward [toward] (none at the crown). */
        fun rib(u: Int, h: Int, v: Int, toward: Dir?) {
            set(u, h - 1, v, if (toward == null) FINE else B(M.FINE_STAIRS, toward, top = true))
            set(u, h, v, FINE)
        }

        /** Pale pillars down each side of the nave and choir, pointed arches between them into the aisles. */
        fun arcades() {
            for (s in sides) {
                val u = s.u(pierL)
                for ((a, z) in naveSpans + choirSpans) opening(z - a - 1, 0, apex, Dir.S) { i, y, block -> set(u, y, a + 1 + i, block) }
                // The pillars, carried on up the wall as shafts to the vault
                for (row in naveRows + choirRows + listOf(c0, c1)) for (y in 0 until spring) set(u, y, row, PILLAR)
                // From the aisles into the transept
                opening(pierL - wallL - 1, 0, apex, s.inward) { i, y, block -> set(s.u(wallL + 1 + i), y, c0, block) }
                if (choirBays > 0) opening(pierL - wallL - 1, 0, apex, s.inward) { i, y, block -> set(s.u(wallL + 1 + i), y, c1, block) }
            }
        }

        /** A window in every bay of the aisles and of the clerestory over them, and a wall of glass round the apse. */
        fun windows() {
            val aisleSpans = (if (single) listOf(0 to front - 1) else listOf()) + naveSpans + choirSpans
            for (s in sides) {
                for ((a, z) in aisleSpans) {
                    val n = z - a - 1
                    val width = if (n % 2 == 0) 2 else min(n, 3)
                    val start = a + 1 + (n - width) / 2
                    opening(width, 2, aisleTop - 3, Dir.S) { i, y, block -> set(s.u(wallL), y, start + i, stained(block, i, y)) }
                    if (a >= front - 1) opening(width, aisleTop + 2, spring - 2, Dir.S) { i, y, block -> set(s.u(pierL), y, start + i, stained(block, i, y)) }
                }
                // A parapet along the terrace's edge
                for (v in aisleStart until c0) set(s.u(wallL), aisleTop + 1, v, B(M.WALL_POST))
                if (choirBays > 0) for (v in c1 + 1..apse) set(s.u(wallL), aisleTop + 1, v, B(M.WALL_POST))
                if (single) for (k in wallL until pierL) set(s.u(k), aisleTop + 1, 0, B(M.WALL_POST))
            }
            // The apse: glass from the floor to the vault between slender piers
            for (u in 0 until w) for (v in apse + 1 until d) {
                if (!apseWall(u, v)) continue
                val angle = Math.toDegrees(atan2(v - apse.toDouble(), u - mid))
                if (APSE_PIERS.any { abs(it - angle) < 9 }) continue
                for (y in 2 until spring - 1) set(u, y, v, B(M.STAINED, color = glass[Math.floorMod(y / 3 + (angle / 20).toInt(), glass.size)]))
            }
        }

        /** Glass of the cathedral's colours where an opening is open, the opening's own stone where it isn't. */
        fun stained(block: B, i: Int, y: Int): B = if (block.m == M.AIR) B(M.STAINED, color = glass[Math.floorMod(y / 3 + i, glass.size)]) else block

        // -- Buttresses --

        /**
         * A buttress against the aisle wall at every row of pillars, stepping back as it rises to a pier with a
         * pinnacle, and a flying arch from the pier over the terrace to the clerestory; buttresses against the
         * transepts' walls; and round the apse, buttresses out along its radii.
         */
        fun buttresses() {
            val pierTop = aisleTop + 3
            val rows = naveRows + choirRows.filter { it < apse } + (if (single) listOf(front - 1) else listOf())
            for (s in sides) for (v in rows) {
                for (k in 0 until wallL) {
                    val top = pierTop - 3 * (wallL - 1 - k)
                    for (y in 0..top) set(s.u(k), y, v, if (y == 0) DARK else WALL)
                    if (k < wallL - 1) set(s.u(k), top + 1, v, B(M.WALL_STAIRS, s.inward))
                }
                pinnacle(s.u(wallL - 1), pierTop + 1, v)
                if (v > front - 1) flyer(s, v, wallL, pierTop, pierL - 1)
            }
            for (s in sides) for ((v, out) in listOf(c0 - 1 to Dir.N, c1 + 1 to Dir.S)) {
                for (k in 0..1) {
                    val top = naveTop - 4 - k * 5
                    val at = if (out == Dir.N) v - k else v + k
                    for (y in 0..top) set(s.u(0), y, at, if (y == 0) DARK else WALL)
                    set(s.u(0), top + 1, at, B(M.WALL_STAIRS, out.opposite))
                }
            }
            for (deg in APSE_PIERS) {
                val rad = Math.toRadians(deg)
                val steps = apseReach - apseRadius.roundToInt()
                for (t in 1..steps) {
                    val dist = apseRadius + t
                    val u = (mid + dist * Math.cos(rad)).roundToInt()
                    val v = (apse + dist * Math.sin(rad)).roundToInt()
                    val top = naveTop - 2 - t * 4
                    for (y in 0..top) set(u, y, v, if (y == 0) DARK else WALL)
                    if (t == steps) pinnacle(u, top + 1, v)
                    else set(u, top + 1, v, B(M.WALL_STAIRS, if (abs(Math.cos(rad)) > 0.7) (if (Math.cos(rad) > 0) Dir.W else Dir.E) else Dir.N))
                }
            }
        }

        /** A flying buttress on [s]'s side at row [v]: an arch of stone rising at a slant from [k0] (at [y0]) to the wall at [k1]. */
        fun flyer(s: Side, v: Int, k0: Int, y0: Int, k1: Int) {
            for (k in k0..k1) {
                val y = y0 + (k - k0)
                set(s.u(k), y, v, B(M.WALL_STAIRS, s.inward))
                set(s.u(k), y - 1, v, B(M.WALL_STAIRS, s.out, top = true))
            }
        }

        // -- The west front --

        /** Two towers beside the front, each with a belfry and a spire, the portal, rose window and gable between them. */
        fun twinTowers() {
            val tw = pierL + 1
            val belfry = towerTop - 8
            for (s in sides) {
                val u0 = min(s.u(0), s.u(tw - 1))
                val looks = setOf(Dir.N, s.out)
                tower(u0, u0 + tw - 1, 0, front - 1, looks, looks, listOf(aisleTop + 1, belfry), belfry - 3, s.left)
                spire(u0 + (tw - 1) / 2.0, (front - 1) / 2.0, (tw - 2) / 2.0, towerTop + 1, stoneSpires)
                f.door(s.u((wallL + pierL) / 2), 0, front - 1, inward = Dir.N)
            }
            val u0 = pierL + 1
            val u1 = pierR - 1
            // The portal's block with a balustrade along its top, and the front set back over it up into the gable
            for (u in u0..u1) {
                for (v in 0..2) for (y in 0..apex + 3) set(u, y, v, if (y == 0) DARK else if (y == apex + 3) TRIM else WALL)
                set(u, apex + 4, 0, B(M.WALL_POST))
                for (y in apex + 4..naveTop) set(u, y, 1, WALL)
            }
            portal(u0, u1, apex + 1)
            val radius = min((u1 - u0 + 1) / 2.0 - 1.0, (spring + 3 - (apex + 5)) / 2.0 - 1.4)
            rose(mid, apex + 5 + (radius + 1.4).toInt(), radius) { i, y, block -> set(i, y, 1, block) }
            frontGable(pierL, pierR, 1)
            set(midL, apex + 4, 2, B(M.LANTERN))
        }

        /** One great tower over the portal with a tall window over it, the aisles' low fronts beside it. */
        fun greatTower() {
            val tw = pierR - pierL + 1
            val belfry = towerTop - 8
            tower(pierL, pierR, 0, front - 1, setOf(Dir.N, Dir.W, Dir.E), setOf(Dir.W, Dir.E), listOf(naveTop + 1, belfry), naveTop - 2, true)
            spire(mid, (front - 1) / 2.0, (tw - 4) / 2.0, towerTop + 1, stoneSpires)
            // The portal in the tower's foot; behind it a hall open up to the first floor and into the nave under a tall arch
            for (u in pierL + 1 until pierR) for (v in 1..2) for (y in 0..aisleTop + 1) set(u, y, v, if (y == 0) DARK else if (y == aisleTop + 1) TRIM else WALL)
            portal(pierL + 1, pierR - 1, apex + 2)
            opening(tw - 4, 0, naveTop - 1, Dir.E) { i, y, block -> set(pierL + 2 + i, y, front - 1, block) }
            val glassWidth = tw - 6
            opening(glassWidth, aisleTop + 3, naveTop - 1, Dir.E) { i, y, block ->
                set(pierL + 3 + i, y, 1, if (i % 2 == 1 && block.m == M.AIR) PILLAR else stained(block, i, y))
            }
            chandelier(midL, (front - 1) / 2, naveTop + 1, aisleTop - 2)
            // Buttresses at the aisles' front corners, windows in their fronts
            for (s in sides) {
                for (k in 0 until wallL) {
                    for (y in 0..aisleTop + 1) set(s.u(k), y, 0, if (y == 0) DARK else WALL)
                    if (k == wallL - 1) pinnacle(s.u(k), aisleTop + 2, 0)
                }
                opening(aisle - 2, 2, aisleTop - 3, s.inward) { i, y, block -> set(s.u(wallL + 2 + i), y, 0, stained(block, i, y)) }
            }
        }

        /**
         * A square tower over [u0]..[u1], [v0]..[v1] up to [towerTop]: the faces in [faces] set back over its foot
         * between buttresses clasping the corners, a tall window up each face in [lancets] to [lancetTop], an open
         * belfry on the last of [floors] and a parapet with pinnacles. Inside, a floor at each of [floors], a ladder
         * up a notch in a back corner, and (if [bell]) the bell hanging in the belfry.
         */
        fun tower(u0: Int, u1: Int, v0: Int, v1: Int, faces: Set<Dir>, lancets: Set<Dir>, floors: List<Int>, lancetTop: Int, bell: Boolean) {
            val top = towerTop
            val foot = aisleTop + 1
            val belfry = floors.last()
            fun corner(u: Int, v: Int) = (u - u0 <= 1 || u1 - u <= 1) && (v - v0 <= 1 || v1 - v <= 1)
            /** The face a column stands on the rim of (or [inset] in from), away from the corners. */
            fun face(u: Int, v: Int, inset: Int): Dir? = when {
                corner(u, v) -> null
                v == v0 + inset -> Dir.N
                v == v1 - inset -> Dir.S
                u == u0 + inset -> Dir.W
                u == u1 - inset -> Dir.E
                else -> null
            }
            for (u in u0..u1) for (v in v0..v1) {
                val rim = u == u0 || u == u1 || v == v0 || v == v1
                val onFace = face(u, v, 0)
                val behind = face(u, v, 1)
                for (y in 0..top) {
                    val block = when {
                        rim && y > foot && onFace in faces -> if (y == foot + 1) B(M.WALL_STAIRS, onFace!!.opposite) else AIR
                        rim || corner(u, v) || (behind in faces && y > foot) ->
                            if (y == 0) DARK else if (y == foot || y == belfry - 1 || y == top) TRIM else WALL
                        y in floors -> B(M.PLANKS, v = 1)
                        else -> AIR
                    }
                    set(u, y, v, block)
                }
                // The parapet: a corbel table and a pierced rail on the set-back faces, a rail elsewhere
                if (rim && onFace in faces) {
                    set(u, top, v, B(M.WALL_STAIRS, onFace!!.opposite, top = true))
                    set(u, top + 1, v, B(M.WALL_POST))
                } else if (rim && !corner(u, v)) set(u, top + 1, v, B(M.WALL_POST))
            }
            for (u in listOf(u0, u1)) for (v in listOf(v0, v1)) {
                set(u, top + 1, v, TRIM)
                pinnacle(u, top + 2, v)
            }
            // The belfry's open arches, two to a face, and the tall windows under them
            for (dir in Dir.entries) {
                val inset = if (dir in faces) 1 else 0
                val n = (if (dir.alongX) v1 - v0 else u1 - u0) - 3
                val along = if (dir.alongX) Dir.S else Dir.E
                fun put(k: Int, y: Int, block: B) = when (dir) {
                    Dir.N -> set(u0 + 2 + k, y, v0 + inset, block)
                    Dir.S -> set(u0 + 2 + k, y, v1 - inset, block)
                    Dir.W -> set(u0 + inset, y, v0 + 2 + k, block)
                    Dir.E -> set(u1 - inset, y, v0 + 2 + k, block)
                }
                val each = (n - 1) / 2
                for (start in listOf(0, n - each)) opening(each, belfry + 1, top - 2, along) { i, y, block -> put(start + i, y, block) }
                if (dir in lancets) {
                    val width = if (n % 2 == 0) 2 else if (n >= 7) 3 else 1
                    opening(width, foot + 3, lancetTop, along) { i, y, block -> put((n - width) / 2 + i, y, stained(block, i, y)) }
                }
            }
            val bu = (u0 + u1) / 2
            val bv = (v0 + v1) / 2
            if (bell) {
                for (u in u0 + 2..u1 - 2) set(u, top - 1, bv, B(M.TIMBER, axis = Axis.X))
                set(bu, top - 2, bv, B(M.BELL, Dir.S, top = true))
            }
            // A ladder up a notch in the back corner on the inner side, a lantern under every floor and one in the belfry;
            // the lanterns hang on chains low enough to light the room's floor (a great tower's hall has its chandelier)
            val lu = if (Dir.W in faces && Dir.E !in faces) u1 - 1 else u0 + 1
            for (y in 0..belfry) set(lu, y, v1 - 1, B(M.LADDER, Dir.N))
            for (y in belfry + 1..belfry + 2) set(lu, y, v1 - 1, AIR)
            var roomFloor = 0
            for (y in floors) {
                val lamp = if (single) y - 1 else min(y - 1, roomFloor + TOWER_LAMP)
                for (k in lamp + 1 until y) set(bu, k, bv, B(M.CHAIN))
                set(bu, lamp, bv, B(M.LANTERN, top = true))
                roomFloor = y + 1
            }
            set(bu, belfry + 1, bv - 1, B(M.LANTERN))
        }

        /**
         * The great portal from [u0] to [u1]: pointed arches stepping in through the thick foot of the front, columns
         * in the jambs, and a pair of doors under a carved tympanum at the back ([top] is the outer arch's apex).
         */
        fun portal(u0: Int, u1: Int, top: Int) {
            val n = u1 - u0 + 1
            for (depth in 0..1) {
                val width = n - 2 - 2 * depth
                opening(width, 0, top - depth, Dir.E) { i, y, block ->
                    set(u0 + 1 + depth + i, y, depth, if (block.m == M.AIR) AIR else B(M.FINE_STAIRS, block.dir, top = true))
                }
                // Columns in the jambs
                for (y in 0 until top - depth - 1) {
                    set(u0 + depth, y, depth, PILLAR)
                    set(u1 - depth, y, depth, PILLAR)
                }
            }
            // The doors with a pillar between them, a tympanum over them with a golden boss
            val m = (u0 + u1) / 2
            for (u in u0 + 2..u1 - 2) for (y in 0 until top - 1) set(u, y, 2, if (y >= 3) FINE else AIR)
            if (n % 2 == 1) {
                f.door(m - 1, 0, 2, hingeRight = true)
                f.door(m + 1, 0, 2)
                for (y in 0..2) set(m, y, 2, PILLAR)
                for (u in u0 + 2 until m - 1) for (y in 0..2) set(u, y, 2, FINE)
                for (u in m + 2..u1 - 2) for (y in 0..2) set(u, y, 2, FINE)
            } else {
                f.door(m, 0, 2, hingeRight = true)
                f.door(m + 1, 0, 2)
            }
            set(m, 4, 2, B(M.GOLD))
            // Lanterns hanging in the porch
            set(m, top - 1, 1, B(M.LANTERN, top = true))
        }

        /** The gable at the front from [u0] to [u1] (the towers' inner walls) in the wall at [v], following the roof behind. */
        fun frontGable(u0: Int, u1: Int, v: Int) {
            for (u in u0 + 1 until u1) {
                val top = naveTop + 1 + PITCH * min(u - u0, u1 - u)
                for (y in naveTop + 1..top) set(u, y, v, WALL)
                val crown = min(u - u0, u1 - u) * 2 >= u1 - u0 - 1
                set(u, top + 1, v, if (crown) TRIM else B(M.WALL_STAIRS, if (u - u0 < u1 - u) Dir.E else Dir.W))
            }
            val peak = naveTop + 2 + PITCH * ((u1 - u0) / 2)
            set(midL, peak + 1, v, B(M.WALL_POST))
            set(midL, peak + 2, v, B(M.WALL_POST))
        }

        // -- The transepts and the crossing --

        /** The transepts' gables: a rose window high up, lancets under it, pinnacles at its feet, a door where the side is open. */
        fun transepts() {
            val radius = (c1 - c0) / 2.0 - 1.5
            for (s in sides) {
                val u = s.u(0)
                rose((c0 + c1) / 2.0, naveTop - 1 - (radius + 1.4).toInt(), radius) { i, y, block -> set(u, y, i, block) }
                val lancetTop = naveTop - 3 - 2 * (radius + 1.4).toInt()
                for (v in c0 + 2..c1 - 2 step 2) opening(1, 3, lancetTop, Dir.S) { _, y, block -> set(u, y, v, stained(block, 0, y)) }
                gable(u, c0, c1)
                for (v in listOf(c0, c1)) pinnacle(u, naveTop + 1, v)
                val side = if (s.left) Dir.W else Dir.E
                if (!b.shared[side.ordinal]) {
                    opening(3, 0, 3, Dir.S) { i, y, block -> set(u, y, crossMid - 1 + i, if (block.m == M.AIR) FINE else block) }
                    f.door(u, 0, crossMid, inward = s.inward)
                    set(u, 2, crossMid, B(M.STAINED, color = Dye.YELLOW))
                }
            }
        }

        /** A gable end in the wall at [u] from [v0] to [v1], over the roof's edge, finished with a coping and a finial. */
        fun gable(u: Int, v0: Int, v1: Int) {
            for (v in v0..v1) {
                val top = naveTop + 1 + PITCH * min(v - v0, v1 - v)
                for (y in naveTop + 1..top) set(u, y, v, WALL)
                val crown = min(v - v0, v1 - v) * 2 >= v1 - v0 - 1
                set(u, top + 1, v, if (crown) TRIM else B(M.WALL_STAIRS, if (v - v0 < v1 - v) Dir.S else Dir.N))
            }
            val peak = naveTop + 2 + PITCH * ((v1 - v0) / 2)
            set(u, peak + 1, (v0 + v1) / 2, B(M.WALL_POST))
            set(u, peak + 2, (v0 + v1) / 2, B(M.WALL_POST))
        }

        /** Over the crossing, a slender spire of slate on an open lantern (twin towers only: a great tower stands alone). */
        fun crossingSpire() {
            if (single) return
            val base = ridge + 1
            for (du in -1..1) for (dv in -1..1) {
                val u = midL + du
                val v = crossMid + dv
                val roof = roofTop(u, v) ?: continue
                for (y in roof + 1 until base) set(u, y, v, WALL)
                for (y in base until base + 3) set(u, y, v, if (du != 0 && dv != 0) TRIM else AIR)
            }
            set(midL, base, crossMid, B(M.LANTERN))
            spire(midL.toDouble(), crossMid.toDouble(), 1.5, base + 3)
        }

        // -- Inside --

        /** Pews, the altar on its steps, banners on the pillars, a font by the door; chandeliers and lanterns throughout. */
        fun furnish() {
            for (v in front + 1 until c0 - 1 step 2) for (u in pierL + 1 until pierR) {
                if (abs(u - mid) <= 1.0) continue
                set(u, 0, v, B(M.PLANK_STAIRS, Dir.N))
            }
            // The font by the door, a lectern at the crossing
            set(pierL + 1, 0, front, B(M.CAULDRON, v = 2))
            set(pierL + 1, 0, c0 + 1, B(M.LECTERN, Dir.N))
            // Banners down the pillars
            for (row in naveRows + listOf(c0)) for (s in sides) set(s.u(pierL + 1), apex - 1, row, B(M.BANNER, s.inward, color = banner))
            // The sanctuary: a step up into the apse, the altar on its own step
            for (u in pierL + 1 until pierR) set(u, 0, apse, B(M.FINE_STAIRS, Dir.S))
            for (u in 0 until w) for (v in apse + 1 until d) if (inApse(u, v)) set(u, 0, v, FINE)
            val av = apse + 2
            for (u in midL - 1..midR + 1) {
                set(u, 1, av - 1, B(M.FINE_STAIRS, Dir.S))
                set(u, 1, av, FINE)
                set(u, 2, av, if (u == midL - 1 || u == midR + 1) B(M.CANDLE) else B(M.GOLD))
            }
            f.chest(midR + 2, 1, av, Dir.W, Loot.TEMPLE)
            set(midL - 2, 1, av, B(M.BREWING_STAND))
            f.spawn(Spawn.VILLAGER, midL, 1, av - 2, Dir.N)
            // The priest at the altar, a lectern beside him to read from; in the larger churches another keeps a chapel in the transept
            set(midL - 3, 1, av - 1, B(M.LECTERN, Dir.E))
            f.resident(Folk.PRIEST, midL - 2, 1, av - 1, work = f.at(midL - 3, 1, av - 1), facing = Dir.W)
            if (size > 0) {
                val s = sides[0]
                set(s.u(1), 0, crossMid - 2, B(M.LECTERN, s.inward))
                f.resident(Folk.PRIEST, s.u(2), 0, crossMid - 2, work = f.at(s.u(1), 0, crossMid - 2), facing = s.out)
            }
            // Choir stalls facing each other, where there's a choir
            if (choirBays > 0) for (s in sides) for (v in c1 + 2 until apse - 1) set(s.u(pierL + 1), 0, v, B(M.PLANK_STAIRS, s.out))
            // Chandeliers down the nave, at the crossing and over the altar, and one more in the deep west end between the grandest church's towers
            val hang = aisleTop - 4
            val west = if (!single && size == 2) listOf(naveStart + (front - naveStart) / 2) else emptyList()
            for (v in west + listOf(front + 1) + naveRows.map { it + bay / 2 } + listOf(crossMid, apse + 1) + choirRows.filter { it < apse }) {
                val h = vault(midL, v) ?: continue
                chandelier(midL, v, h, hang)
            }
            // Lanterns in the aisles, each on a link of chain from the vault, chandeliers in the transepts
            for (s in sides) {
                val lampU = s.u(wallL + (aisle + 1) / 2)
                for (v in (naveSpans + choirSpans).map { (a, z) -> (a + z) / 2 } + listOfNotNull(if (single) front / 2 else null)) {
                    set(lampU, aisleTop - 2, v, B(M.CHAIN))
                    set(lampU, aisleTop - 3, v, B(M.LANTERN, top = true))
                }
                val tu = s.u((pierL + 1) / 2)
                vault(tu, crossMid)?.let { chandelier(tu, crossMid, it, hang) }
            }
        }
    }

    // ---- The palace ----

    /**
     * The royal palace on the citadel, its front to the courtyard over a broad flight of steps. In the middle of
     * the front stands the keep, a great square tower with a deep portal at its foot and a crown of spires over its
     * parapet, high over everything; the throne hall runs back from it under a steep slate roof, and either side
     * lies a wing of two tall storeys with a round tower at each corner, its long sides broken into bays by
     * pilasters and into gables by the roof.
     */
    private inner class Palace {
        /** Medium, large or grand, by the width of the lot. */
        val size = when {
            w >= 45 -> 2
            w >= 39 -> 1
            else -> 0
        }
        val stoneSpires = r.nextInt(3) == 0
        val glass = PALACE_GLASS[r.nextInt(PALACE_GLASS.size)]

        // Across the front: a wing, the keep and the hall behind it, a wing; a third of the width each
        val cw = w / 3
        val c0 = cw
        val c1 = w - 1 - cw
        val mid = (w - 1) / 2
        /** From the middle of the keep to its sides. */
        val half = (cw - 1) / 2

        // From the front: the courtyard's steps and a landing, the keep, the hall; the wings stop short of the back
        val rec = if (size == 2) 6 else 5
        val k0 = rec
        val k1 = rec + cw - 1
        val wv1 = d - 1 - if (size == 0) 3 else 4
        /** The corner towers' centres: this far in from the sides, and from the front and from the wings' back wall. */
        val tcu = 4
        val tcv = listOf(4, wv1 - 3)
        /** The porch's half-width and depth. */
        val pw = if (size == 2) 5 else 4
        val pd = rec - 3

        // Up: the plinth, the floors, the wings' eaves, the hall's vault, the keep's floors and terrace
        val g = 3
        val f1 = g + 9
        val eaves = f1 + 6
        val towerTop = eaves + 6
        val spring = eaves + 4 + size
        val naveTop = spring + 1
        val porchTop = f1 - 1
        val storey = KEEP_STOREY[size]
        val keepFloors = (0 until KEEP_FLOORS[size]).map { eaves + 1 + storey * it }
        val top = eaves + 1 + storey * KEEP_FLOORS[size]
        val cv = (k0 + k1) / 2
        val spireHeight = 9 + 2 * size

        // The wings' sides: pilasters at the posts, a gable over each bay between them
        val vA = 7
        val vB = wv1 - 6
        val bayCount = max(1, (vB - vA + 3) / 6)
        val posts = (0..bayCount).map { vA + (it * (vB - vA) + bayCount / 2) / bayCount }
        val bays = posts.zipWithNext()

        fun draw() {
            ground()
            masses()
            roofs()
            wings()
            keepFaces()
            hallFaces()
            porch()
            crown()
            furnish()
        }

        // -- Where things are --

        /** How far a column is from the nearer side of the lot. */
        fun sym(u: Int): Int = min(u, w - 1 - u)

        fun towerDist(u: Int, v: Int): Double {
            val a = (sym(u) - tcu).toDouble()
            return tcv.minOf { sqrt(a * a + (v - it) * (v - it).toDouble()) }
        }

        fun inTower(u: Int, v: Int): Boolean = sym(u) < cw && towerDist(u, v) <= TOWER

        /** The corner outside a tower, left open so the tower stands round. */
        fun cut(u: Int, v: Int): Boolean = sym(u) < tcu && (v < tcv[0] || v > tcv[1])

        /** The small turret at the inner front corner of each wing, beside the courtyard. */
        fun inTurret(u: Int, v: Int): Boolean = sym(u) in cw - 3 until cw && v in 1..3

        fun inWing(u: Int, v: Int): Boolean = sym(u) in 1 until cw && v in 1..wv1 && (!cut(u, v) || inTower(u, v))
        fun inKeep(u: Int, v: Int): Boolean = u in c0..c1 && v in k0..k1
        fun inHall(u: Int, v: Int): Boolean = u in c0..c1 && v in k1 + 1 until d
        fun inPorch(u: Int, v: Int): Boolean = u in mid - pw..mid + pw && v in k0 - pd until k0
        fun inside(u: Int, v: Int): Boolean = inWing(u, v) || inKeep(u, v) || inHall(u, v) || inPorch(u, v)

        /** The top of the wall standing on a column, or null where there's none. */
        fun wallTop(u: Int, v: Int): Int? {
            var h: Int? = null
            fun take(t: Int) {
                h = max(h ?: t, t)
            }
            if (inWing(u, v)) take(eaves)
            if (inTower(u, v)) take(towerTop)
            if (inTurret(u, v)) take(eaves + 5)
            if (inKeep(u, v)) take(top - 1)
            if (inHall(u, v)) take(naveTop)
            if (inPorch(u, v)) take(porchTop)
            return h
        }

        /** The bay of the wings' sides a row is in, as its two posts. */
        fun bayAt(v: Int): Pair<Int, Int>? = bays.firstOrNull { v in it.first..it.second }

        /** How high the gable over a bay stands at [v], above the eaves. */
        fun gableRise(bay: Pair<Int, Int>, v: Int): Int = 2 * min(v - bay.first, bay.second - v)

        /**
         * The top of the wings' roofs over a column, or null: hipped, with a gable over each bay of the side
         * (the stone of the gable's end standing in the wall, so the roof begins two columns in there).
         */
        fun wingRoof(u: Int, v: Int): Int? {
            val s = sym(u)
            if (s >= cw || v !in 0..wv1 + 1 || cut(u, v) || inTower(u, v) || inTurret(u, v)) return null
            val hip = eaves + 1 + min(min(s, cw - 1 - s), min(v, wv1 + 1 - v))
            val bay = bayAt(v) ?: return hip
            if (s < 2) return null
            return if (s <= half) max(hip, eaves + 1 + gableRise(bay, v)) else hip
        }

        /** The top of the hall's roof over a column, or null. */
        fun hallRoof(u: Int, v: Int): Int? = if (inHall(u, v)) naveTop + 1 + min(u - c0, c1 - u) else null

        /** The top of the towers' cones, which overhang the walls by a block. */
        fun coneRoof(u: Int, v: Int): Int? {
            if (sym(u) >= cw) return null
            val dist = towerDist(u, v)
            return if (dist <= TOWER + 1) towerTop + 1 + ((TOWER + 1 - dist) * 2.2).toInt() else null
        }

        // -- Ground, walls and roofs --

        /** Paving round about, the steps up from the courtyard to the keep. */
        fun ground() {
            for (u in 0 until w) for (v in 0 until d) {
                if (inside(u, v)) continue
                val border = u == 0 || v == 0 || u == w - 1 || v == d - 1
                set(u, -1, v, B(M.PAVE_PLAZA, v = if (border) 1 else 0))
            }
            // The steps across the courtyard side, a stair a block up at each, the landing behind them
            for (u in c0..c1) for (v in 0 until rec) {
                val rise = min(v, g)
                for (y in 0 until rise) set(u, y, v, if (y == 0) DARK else WALL)
                if (v < g) set(u, v, v, B(M.WALL_STAIRS, Dir.S))
                else set(u, g - 1, v, TRIM)
            }
        }

        /** The stone of everything, in courses: a plinth, string courses at the floors and a cornice under the eaves. */
        fun masses() {
            for (u in 0 until w) for (v in 0 until d) {
                val wall = wallTop(u, v) ?: continue
                val roof = max(max(wingRoof(u, v) ?: 0, hallRoof(u, v) ?: 0), coneRoof(u, v) ?: 0)
                for (y in 0 until max(wall + 1, roof)) {
                    val block = when {
                        y > wall -> WALL
                        y == 0 -> DARK
                        isBand(u, v, y) -> TRIM
                        else -> WALL
                    }
                    set(u, y, v, block)
                }
            }
        }

        fun isBand(u: Int, v: Int, y: Int): Boolean = when {
            y == g - 1 || y == f1 - 1 -> true
            inKeep(u, v) -> y == top - 1 || y in keepFloors.map { it - 1 }
            inHall(u, v) -> y == naveTop
            else -> y == wallTop(u, v)
        }

        fun roofs() {
            roof(SLATE) { u, v -> wingRoof(u, v) }
            roof(SLATE) { u, v -> hallRoof(u, v) }
            roof(SLATE) { u, v -> coneRoof(u, v) }
            // Poles on the cones, a pennant in the city's colour
            for (cu in listOf(tcu, w - 1 - tcu)) for (cz in tcv) {
                val peak = coneRoof(cu, cz) ?: continue
                for (y in peak + 2..peak + 5) set(cu, y, cz, B(M.FENCE))
                for (n in 1..2) set(cu + (if (cu < mid) 1 else -1) * n, peak + 5, cz, B(M.WOOL, color = BANNER))
            }
            // The turrets' spires
            for (sd in sides) spire(sd.u(cw - 2).toDouble(), 2.0, 1.5, eaves + 6, stone = stoneSpires)
        }

        // -- The wings --

        /** A pilaster against a wall at ([u], [v]): from the plinth up to [top], capped with a stone sloping toward [toward]. */
        fun pilaster(u: Int, v: Int, top: Int, toward: Dir) {
            for (y in 0 until top) set(u, y, v, if (y == 0) DARK else if (y == g - 1 || y == f1 - 1) TRIM else WALL)
            set(u, top, v, B(M.WALL_STAIRS, toward))
        }

        /** Glass where an opening is open, the opening's own stone where it isn't. */
        fun glazed(block: B, i: Int, y: Int, stained: Boolean): B = when {
            block.m != M.AIR -> block
            stained -> B(M.STAINED, color = glass[Math.floorMod(y / 3 + i, glass.size)])
            else -> WINDOW
        }

        /** A pointed window [n] wide in a wall along v at column [u], from row [v0]. */
        fun lancetV(u: Int, v0: Int, n: Int, y0: Int, apex: Int, stained: Boolean = false) =
            opening(n, y0, apex, Dir.S) { i, y, block -> set(u, y, v0 + i, glazed(block, i, y, stained)) }

        /** A pointed window [n] wide in a wall along u at row [v], from column [u0]. */
        fun lancetU(u0: Int, v: Int, n: Int, y0: Int, apex: Int, stained: Boolean = false) =
            opening(n, y0, apex, Dir.E) { i, y, block -> set(u0 + i, y, v, glazed(block, i, y, stained)) }

        /** How wide a window to put in a gap of [n] columns between pilasters, leaving a column either side or two. */
        fun windowIn(n: Int): Int = (n - 2).coerceIn(1, if (n % 2 == 1) 3 else 4)

        /**
         * The wings' sides: pilasters at the posts with a pinnacle on each, a gable over each bay (its end of stone with
         * a coping, a window with a niche behind it and a finial) and tall windows below; windows in the ends and
         * slits in the round towers.
         */
        fun wings() {
            for (sd in sides) {
                val u = sd.u(1)
                for (p in posts) {
                    pilaster(sd.u(0), p, eaves, sd.inward)
                    set(sd.u(0), eaves + 1, p, TRIM)
                    set(sd.u(0), eaves + 2, p, B(M.WALL_POST))
                    set(sd.u(0), eaves + 3, p, B(M.WALL_POST))
                }
                for ((a, z) in bays) {
                    val n = windowIn(z - a - 1)
                    val start = a + 1 + (z - a - 1 - n) / 2
                    lancetV(u, start, n, 5, 10)
                    lancetV(u, start, n, f1 + 1, eaves - 1)
                    // The gable: stone to the roof's edge, a coping over it, a window in it
                    val peak = (z - a) / 2
                    for (v in a..z) {
                        val rise = gableRise(a to z, v)
                        for (y in eaves + 1..eaves + 1 + rise) set(u, y, v, WALL)
                        val crown = rise == 2 * peak
                        set(u, eaves + 2 + rise, v, if (crown) TRIM else B(M.WALL_STAIRS, if (v - a < z - v) Dir.S else Dir.N))
                    }
                    set(u, eaves + 3 + 2 * peak, a + peak, B(M.WALL_POST))
                    val wn = if ((z - a) % 2 == 0) 1 else 2
                    val ws = a + (z - a) / 2
                    for (i in 0 until wn) {
                        for (y in eaves + 2..eaves + 3) set(u, y, ws + i, WINDOW)
                        set(sd.u(2), eaves + 2, ws + i, B(M.LANTERN))
                        set(sd.u(2), eaves + 3, ws + i, AIR)
                    }
                }
                // Slits in the towers: to the side, to the corner and to the front
                for ((ti, cz) in tcv.withIndex()) {
                    val end = if (ti == 0) 1 else wv1
                    val corner = if (ti == 0) 2 else wv1 - 1
                    for (y in listOf(5, f1 + 1, eaves + 2)) for (h in 0..2) {
                        set(sd.u(1), y + h, cz, WINDOW)
                        set(sd.u(2), y + h, corner, WINDOW)
                        set(sd.u(tcu), y + h, end, WINDOW)
                    }
                }
                // Windows in the ends, between the towers and the courtyard
                for (v in listOf(1, wv1)) {
                    val u0 = min(sd.u(9), sd.u(10))
                    if (cw >= 13) {
                        lancetU(u0, v, 2, 5, 10)
                        lancetU(u0, v, 2, f1 + 1, eaves - 1)
                    }
                }
            }
        }

        // -- The keep --

        /**
         * The keep's faces: tall windows on every floor, a great window of coloured glass in the middle of each, pilasters up the
         * corners; a corbel table under the parapet.
         */
        fun keepFaces() {
            val floors = listOf(f1) + keepFloors
            for ((i, fl) in floors.withIndex()) {
                val y0 = if (i == 0) fl + 1 else fl + 2
                val apex = if (i == 0) eaves - 1 else fl + storey - 2
                // The front, the sides above the wings' roofs, the back above the hall's
                lancetU(mid - 1, k0, 3, y0, apex, stained = true)
                if (half >= 6) {
                    for (du in listOf(-(half - 2), half - 3)) lancetU(mid + du, k0, 2, y0, apex)
                }
                if (fl > eaves) {
                    for (u in listOf(c0, c1)) {
                        lancetV(u, cv - 1, 3, y0, apex, stained = true)
                        if (half >= 6) for (dv in listOf(-(half - 2), half - 3)) lancetV(u, cv + dv, 2, y0, apex)
                    }
                }
                if (y0 > naveTop + 1 + half + 1) {
                    lancetU(mid - 1, k1, 3, y0, apex, stained = true)
                    if (half >= 6) for (du in listOf(-(half - 2), half - 3)) lancetU(mid + du, k1, 2, y0, apex)
                }
            }
            // Pilasters up the front corners, banners either side of the porch
            for (u in listOf(c0, c1)) {
                for (y in 0 until top - 1) set(u, y, k0 - 1, if (y == 0) DARK else if (y == g - 1 || y == f1 - 1) TRIM else WALL)
                set(u, top - 1, k0 - 1, B(M.WALL_STAIRS, Dir.S))
                pinnacle(u, top, k0 - 1)
            }
            for (u in listOf(c0 + 1, c1 - 1)) for (y in f1 + 1..f1 + 8) set(u, y, k0 - 1, B(M.BANNER, Dir.N, color = BANNER))
        }

        // -- The hall --

        /** The hall's clerestory windows over the wings' roofs, the great window of coloured glass in its back wall. */
        fun hallFaces() {
            var v = k1 + 3
            while (v + 1 <= d - 3) {
                for (u in listOf(c0, c1)) lancetV(u, v, 2, eaves + 2, naveTop - 1, stained = true)
                v += 4
            }
            val n = if (size == 0) 3 else if (size == 1) 5 else 7
            lancetU(mid - n / 2, d - 1, n, g + 3, naveTop + 1 + half - 3, stained = true)
        }

        // -- The porch --

        /**
         * A porch against the keep's foot, deep pointed arches stepping in through it, columns in their jambs, a pair of
         * doors under a tympanum of coloured glass; over it a balcony with a stone rail and a door to the keep's first floor.
         */
        fun porch() {
            val n0 = 2 * pw - 3
            for (layer in 0 until pd) {
                val n = n0 - 2 * layer
                val v = k0 - pd + layer
                val apex = g + 7 - layer
                opening(n, g, apex, Dir.E) { i, y, block ->
                    set(mid - (n - 1) / 2 + i, y, v, if (block.m == M.AIR) AIR else B(M.FINE_STAIRS, block.dir, top = true))
                }
                for (y in g until g + 4) for (u in listOf(mid - (n + 1) / 2, mid + (n + 1) / 2)) set(u, y, v, PILLAR)
            }
            // The doors in the keep's wall, a pillar between them, the glass above
            f.door(mid - 1, g, k0, hingeRight = true)
            f.door(mid + 1, g, k0)
            for (y in g..g + 2) set(mid, y, k0, PILLAR)
            opening(3, g + 3, g + 7, Dir.E) { i, y, block ->
                set(mid - 1 + i, y, k0, if (block.m == M.AIR) B(M.STAINED, color = glass[Math.floorMod(y + i, glass.size)]) else block)
            }
            set(mid, g + 6, k0 - 1, B(M.LANTERN, top = true))
            // The balcony: a rail round it, pinnacles at the corners, the door and windows in the keep's wall
            for (u in mid - pw..mid + pw) set(u, f1, k0 - pd, B(M.WALL_POST))
            for (v in k0 - pd until k0) for (u in listOf(mid - pw, mid + pw)) set(u, f1, v, B(M.WALL_POST))
            for (u in listOf(mid - pw, mid + pw)) pinnacle(u, f1 + 1, k0 - pd)
            f.door(mid, f1, k0)
            for (u in listOf(mid - 1, mid + 1)) for (y in f1..f1 + 1) set(u, y, k0, WINDOW)
        }

        // -- The crown --

        /** The keep's parapet and corner spires, and over its middle an open lantern with the needle of the crown. */
        fun crown() {
            for (u in c0..c1) for (v in k0..k1) {
                if (u != c0 && u != c1 && v != k0 && v != k1) continue
                set(u, top, v, WALL)
                if ((u + v) % 2 == 0) set(u, top + 1, v, WALL)
            }
            for (cu in listOf(c0 + 1, c1 - 1)) for (cz in listOf(k0 + 1, k1 - 1)) {
                for (du in -1..1) for (dv in -1..1) for (y in top..top + 2) set(cu + du, y, cz + dv, if (y == top + 2) TRIM else WALL)
                spire(cu.toDouble(), cz.toDouble(), 1.5, top + 3, stone = stoneSpires)
            }
            val rad = if (size == 0) 2.5 else 3.5
            fun octagon(du: Int, dv: Int, r: Double): Boolean = abs(du) <= r + 0.01 && abs(dv) <= r + 0.01 && abs(du) + abs(dv) <= r * 1.45 + 0.01
            for (du in -3..3) for (dv in -3..3) {
                if (!octagon(du, dv, rad)) continue
                val rim = !(octagon(du + 1, dv, rad) && octagon(du - 1, dv, rad) && octagon(du, dv + 1, rad) && octagon(du, dv - 1, rad))
                for (y in top..top + 7) set(mid + du, y, cv + dv, if (rim) (if (y == top + 7) TRIM else WALL) else if (y == top + 7) WALL else AIR)
            }
            // Windows in its four sides, and a lamp hung inside
            val reach = rad.toInt()
            val across = if (size == 0) 0 else 1
            for (y in top + 2..top + 5) for (n in -across..across) for (sgn in listOf(-1, 1)) {
                set(mid + sgn * reach, y, cv + n, WINDOW)
                set(mid + n, y, cv + sgn * reach, WINDOW)
            }
            set(mid, top + 6, cv, B(M.LANTERN, top = true))
            spire(mid.toDouble(), cv.toDouble(), rad, top + 8, stone = stoneSpires, height = spireHeight, finial = false)
            val tip = top + 8 + spireHeight
            set(mid, tip, cv, B(M.GOLD))
            for (y in tip + 1..tip + 5) set(mid, y, cv, B(M.FENCE))
            for (n in 1..3) for (y in tip + 3..tip + 4) set(mid + n, y, cv, B(M.WOOL, color = BANNER))
        }

        // -- Inside --

        // The keep's inner columns and rows, the hall's inner rows, and the keep's floors from the ground up
        val hu0 = c0 + 1
        val hu1 = c1 - 1
        val kv0 = k0 + 1
        val kv1 = k1 - 1
        val hv0 = k1 + 1
        val hv1 = d - 2
        val levels = listOf(g, f1) + keepFloors
        /** How far the hall's pillars stand from its middle, the first row of its dais, and the carpet's half-width. */
        val po = half - 2
        val dv0 = hv1 - 3
        val rw = if (size == 2) 2 else 1
        /** The middle of the wings' rooms, across; the rows of pillars in the lobby and in the hall. */
        val kc = cw / 2
        val lobbyRows = (kv0 + 2..kv1 - 2 step 4).toList()
        val hallRows = (k1 + 3..dv0 - 1 step 3).toList()
        /** The wings' rooms, the innermost column and the rows of the long middle room. */
        val kin = cw - 2
        val mv0 = vA + 1
        val mv1 = vB - 1

        /** Between a wing's walls, in the rooms. */
        fun inWingRoom(u: Int, v: Int): Boolean =
            sym(u) in 2..cw - 2 && inWing(u, v) && Dir.entries.all { inWing(u + it.dx, v + it.dz) }

        /** Under a tower's cone, in the room at its top. */
        fun inTowerRoom(u: Int, v: Int): Boolean = sym(u) < cw && towerDist(u, v) <= TOWER - 1

        /** The underside of the hall's vault over a column. */
        fun vault(u: Int): Int = spring + rise(min(u - hu0, hu1 - u))

        fun marble(u: Int, v: Int): B = if ((u + v) % 2 == 0) FINE else DARK

        /** Whether [k] columns in from [sd]'s side is inside a wing's room at [v]; what's put there is put only if it is. */
        fun room(sd: Side, k: Int, v: Int): Boolean = inWingRoom(sd.u(k), v)

        fun put(sd: Side, k: Int, y: Int, v: Int, block: B) {
            if (room(sd, k, v)) set(sd.u(k), y, v, block)
        }

        fun furnish() {
            carve()
            partitions()
            passages()
            stairs()
            lobby()
            hall()
            for (sd in sides) {
                if (sd.left) {
                    barracks(sd)
                    banquet(sd)
                    kitchen(sd)
                    royalRooms(sd)
                } else {
                    armoury(sd)
                    library(sd)
                    study(sd)
                    courtRooms(sd)
                }
                towerTops(sd)
            }
            audience()
            chamber()
            if (size == 2) {
                stacks(levels[3])
                treasury(levels[4])
            } else treasury(levels[3])
            terrace()
            outside()
            folk()
        }

        /** Hollows out the inside: two storeys in each wing, a room over them in each tower, a room to each of the keep's floors, the hall under its vault. */
        fun carve() {
            for (u in 0 until w) for (v in 0 until d) {
                if (!inWingRoom(u, v)) continue
                fill(u, g, v, u, f1 - 2, v, AIR)
                fill(u, f1, v, u, eaves - 1, v, AIR)
                set(u, g - 1, v, FLOOR)
                set(u, f1 - 1, v, FLOOR)
                if (inTowerRoom(u, v)) {
                    set(u, eaves, v, FLOOR)
                    fill(u, eaves + 1, v, u, towerTop - 1, v, AIR)
                }
            }
            for ((i, y0) in levels.withIndex()) {
                fill(hu0, y0, kv0, hu1, (levels.getOrNull(i + 1) ?: top) - 2, kv1, AIR)
                for (u in hu0..hu1) for (v in kv0..kv1) set(u, y0 - 1, v, if (i == 0) marble(u, v) else FLOOR)
            }
            for (u in hu0..hu1) {
                val h = vault(u)
                val lower = listOf(Dir.W, Dir.E).filter { u + it.dx in hu0..hu1 && vault(u + it.dx) < h }
                for (v in hv0..hv1) {
                    fill(u, g, v, u, h - 1, v, AIR)
                    set(u, g - 1, v, marble(u, v))
                    set(u, h, v, if (lower.size == 1) B(M.WALL_STAIRS, lower[0], top = true) else WALL)
                }
            }
        }

        /** The walls between the rooms of a wing, at its first and last posts, each with a pointed doorway in the middle. */
        fun partitions() {
            for (sd in sides) for (v in listOf(vA, vB)) {
                for (k in 2..cw - 2) {
                    if (!room(sd, k, v)) continue
                    fill(sd.u(k), g, v, sd.u(k), f1 - 2, v, WALL)
                    fill(sd.u(k), f1, v, sd.u(k), eaves - 1, v, WALL)
                }
                for (y0 in listOf(g, f1)) opening(3, y0, y0 + 5, Dir.E) { i, y, block -> set(sd.u(kc) - 1 + i, y, v, block) }
            }
        }

        /** An archway [n] wide through the double wall between a wing and the keep or the hall, centred on row [v]. */
        fun passage(sd: Side, v: Int, n: Int, y0: Int, apex: Int) =
            opening(n, y0, apex, Dir.S) { i, y, block ->
                for (u in listOf(sd.u(cw - 1), sd.u(cw))) set(u, y, v - (n - 1) / 2 + i, block)
            }

        /** The ways between the rooms: lobby to wings and to the hall, hall to the wings' back rooms, the first floor's to the left wing and over the hall. */
        fun passages() {
            for (sd in sides) {
                passage(sd, cv, 3, g, g + 5)
                passage(sd, wv1 - 3, 3, g, g + 5)
            }
            passage(sides[0], cv, 3, f1, f1 + 4)
            val n = if (size == 0) 5 else 7
            opening(n, g, f1 - 2, Dir.E) { i, y, block -> set(mid - (n - 1) / 2 + i, y, k1, block) }
            opening(3, f1, f1 + 4, Dir.E) { i, y, block -> set(mid - 1 + i, y, k1, block) }
        }

        /**
         * A flight of [rise] steps up column [u] from the floor at [y0], the first step on row [v0] and the flight climbing
         * toward [dv] (1 for the back): stone under every step, the ceiling opened over the last ones with a rail round the
         * opening on the [side] of it.
         */
        fun flight(u: Int, v0: Int, dv: Int, y0: Int, rise: Int, side: Int) {
            for (i in 0 until rise) {
                val v = v0 + dv * i
                fill(u, y0, v, u, y0 + i - 1, v, WALL)
                set(u, y0 + i, v, B(M.WALL_STAIRS, if (dv > 0) Dir.S else Dir.N))
            }
            val ceiling = y0 + rise - 1
            for (i in rise - 3..rise - 2) {
                set(u, ceiling, v0 + dv * i, AIR)
                set(u + side, ceiling + 1, v0 + dv * i, B(M.FENCE))
            }
            set(u, ceiling + 1, v0 + dv * (rise - 4), B(M.FENCE))
        }

        /** Stairs from the ground floor of each wing, a flight to each of the keep's floors, up to its terrace; ladders up the towers. */
        fun stairs() {
            for (sd in sides) flight(sd.u(2), vA + 1, 1, g, f1 - g, if (sd.left) 1 else -1)
            for (j in 1 until levels.size) {
                val y0 = levels[j]
                val rise = (levels.getOrNull(j + 1) ?: top) - y0
                if (j % 2 == 1) flight(hu1, kv0, 1, y0, rise, -1) else flight(hu0, kv1, -1, y0, rise, 1)
            }
            for (sd in sides) for (ti in tcv.indices) {
                val v = if (ti == 0) vA - 1 else vB + 1
                for (y in f1..eaves + 1) set(sd.u(3), y, v, B(M.LADDER, if (ti == 0) Dir.N else Dir.S))
            }
        }
        // -- The lobby and the hall --

        /** The carpet, red between blue edges, from row [v0] to row [v1] at height [y]. */
        fun carpet(v0: Int, v1: Int, y: Int) {
            for (v in v0..v1) for (u in mid - rw..mid + rw) set(u, y, v, B(M.CARPET, color = if (abs(u - mid) == rw) BANNER else Dye.RED))
        }

        /** A banner [n] long hanging on a wall, its top at [top], facing [dir]. */
        fun banner(u: Int, top: Int, v: Int, dir: Dir, n: Int = 4) {
            for (y in top - n + 1..top) set(u, y, v, B(M.BANNER, dir, color = BANNER))
        }

        /** A pillar of the lobby or the hall, up to [top], with a torch and the city's banner (to [flag]) on the side toward the middle. */
        fun pillar(u: Int, v: Int, top: Int, flag: Int) {
            val inward = if (u < mid) Dir.E else Dir.W
            fill(u, g, v, u, top, v, PILLAR)
            set(u + inward.dx, 5, v, B(M.TORCH, inward))
            banner(u + inward.dx, flag, v, inward, 3)
        }

        /** A rib across the hall's vault at row [v]. */
        fun rib(v: Int) {
            for (u in hu0..hu1) {
                val h = vault(u)
                val lower = listOf(Dir.W, Dir.E).filter { u + it.dx in hu0..hu1 && vault(u + it.dx) < h }
                set(u, h - 1, v, if (lower.size == 1) B(M.FINE_STAIRS, lower[0], top = true) else FINE)
                set(u, h, v, FINE)
            }
        }

        /** The lobby behind the doors: a runner on the marble, rows of pillars, banners on the walls, chandeliers. */
        fun lobby() {
            carpet(kv0, kv1, g)
            for (r in lobbyRows) for (u in listOf(mid - po, mid + po)) pillar(u, r, f1 - 2, f1 - 2)
            for (sd in sides) for (dv in listOf(-3, 3)) banner(sd.u(cw + 1), 9, cv + dv, sd.inward)
            val n = if (size == 0) 5 else 7
            for (u in listOf(mid - n / 2 - 1, mid + n / 2 + 1)) banner(u, 9, kv1, Dir.N)
            for (u in listOf(mid - 3, mid + 3)) banner(u, 9, kv0, Dir.S)
            for (v in lobbyRows.zipWithNext { a, b -> (a + b) / 2 } + (kv0 + 1)) chandelier(mid, v, f1 - 1, f1 - 4)
        }

        /**
         * The throne hall: pillars and ribs carrying the vault, a runner to the dais, chandeliers on long chains, banners
         * and torches; over the arch from the lobby a little balcony from the keep's first floor.
         */
        fun hall() {
            for (r in hallRows) {
                rib(r)
                for (u in listOf(mid - po, mid + po)) pillar(u, r, vault(u) - 2, 12)
            }
            carpet(hv0, dv0 - 1, g)
            for ((a, z) in (hallRows + dv0).zipWithNext()) if (z - a >= 3) chandelier(mid, (a + z) / 2, vault(mid), g + 8)
            // Torches and banners down the walls of the aisles, but not where a way goes through them
            for (sd in sides) for (v in hv0 + 2..hv1 - 1 step 3) {
                if (v in wv1 - 5..wv1 - 1) continue
                set(sd.u(cw + 1), 5, v, B(M.TORCH, sd.inward))
                banner(sd.u(cw + 1), 10, v + 1, sd.inward, 3)
            }
            // The balcony: a ledge on corbels, a stone rail round it
            for (u in mid - 2..mid + 2) {
                set(u, f1 - 2, hv0, TRIM)
                set(u, f1 - 2, hv0 + 1, B(M.WALL_STAIRS, Dir.N, top = true))
                for (v in hv0..hv0 + 1) set(u, f1 - 1, v, TRIM)
                set(u, f1, hv0 + 1, B(M.WALL_POST))
            }
            for (u in listOf(mid - 2, mid + 2)) set(u, f1, hv0, B(M.WALL_POST))
            throne()
        }

        /** The dais across the end of the hall, a step to the platform, another to the podium where the throne stands before its window. */
        fun throne() {
            for (u in mid - po..mid + po) {
                set(u, g, dv0, B(M.FINE_STAIRS, Dir.S))
                fill(u, g, dv0 + 1, u, g, hv1, TRIM)
            }
            carpet(dv0 + 1, hv1 - 1, g + 1)
            for (u in mid - 2..mid + 2) {
                set(u, g + 1, hv1, TRIM)
                set(u, g + 1, hv1 - 1, if (abs(u - mid) <= 1) B(M.FINE_STAIRS, Dir.S) else TRIM)
            }
            set(mid, g + 2, hv1, B(M.FINE_STAIRS, Dir.S))
            for (du in listOf(-1, 1)) {
                set(mid + du, g + 2, hv1, B(M.GOLD))
                set(mid + du, g + 3, hv1, B(M.CANDLE))
            }
            fill(mid, g + 3, hv1, mid, g + 4, hv1, FINE)
            set(mid, g + 5, hv1, B(M.GOLD))
            for (u in listOf(mid - po, mid + po)) set(u, g + 1, hv1 - 2, B(M.LANTERN))
            val n = if (size == 0) 3 else if (size == 1) 5 else 7
            for (u in listOf(mid - n / 2 - 2, mid + n / 2 + 2)) banner(u, 10, hv1, Dir.N, 6)
        }
        // -- The wings --

        /** A hanging lamp: a lantern on [drop] links of chain from the ceiling at [ceiling]. */
        fun lamp(u: Int, v: Int, ceiling: Int, drop: Int = 2) {
            for (y in ceiling - drop until ceiling) set(u, y, v, B(M.CHAIN))
            set(u, ceiling - drop - 1, v, B(M.LANTERN, top = true))
        }

        /** Lays a floor over a wing's rooms from row [v0] to row [v1], tile by tile. */
        fun pave(sd: Side, v0: Int, v1: Int, y: Int, tile: (Int, Int) -> B) {
            for (k in 2..kin) for (v in v0..v1) if (room(sd, k, v)) set(sd.u(k), y, v, tile(sd.u(k), v))
        }

        /** A bed with its head against the wall at row [v1], its foot at [k], [v1] - 1: it's put only where both ends are in the room. */
        fun bedAt(sd: Side, k: Int, y: Int, v1: Int, color: Dye) {
            if (room(sd, k, v1) && room(sd, k, v1 - 1)) f.bed(sd.u(k), y, v1 - 1, Dir.S, color)
        }

        /** The front room: beds for the guard, a chest, a barrel. */
        fun barracks(sd: Side) {
            for (k in 4..kin - 1 step 2) bedAt(sd, k, g, vA - 1, Dye.BLUE)
            if (room(sd, kin, 3)) f.chest(sd.u(kin), g, 3, sd.out, Loot.BARRACKS)
            put(sd, kin, g, 4, B(M.BARREL, sd.out))
            put(sd, 3, g, 3, B(M.GRINDSTONE, sd.inward))
            lamp(sd.u(kc), 4, f1 - 1, 3)
        }

        /** The banquet hall: marble underfoot, a long table with a chair at every other place, candles on it, lamps over it, banners on the wall. */
        fun banquet(sd: Side) {
            pave(sd, mv0, mv1, g - 1) { u, v -> marble(u, v) }
            val t0 = mv0 + 1
            val t1 = mv1 - 1
            for (v in t0..t1) for (k in kc..kc + 1) put(sd, k, g, v, TABLE)
            for (v in t0..t1 step 2) {
                put(sd, kc - 1, g, v, chair(sd.inward))
                put(sd, kc + 2, g, v, chair(sd.out))
            }
            put(sd, kc, g, mv0, chair(Dir.S))
            put(sd, kc, g, mv1, chair(Dir.N))
            for (v in t0 + 1..t1 step 3) put(sd, kc, g + 1, v, B(M.CANDLE))
            for (v in t0 + 1..t1 step 4) lamp(sd.u(kc), v, f1 - 1, 3)
            for (dv in listOf(-3, 3)) if (cv + dv in mv0..mv1) banner(sd.u(kin), 9, cv + dv, sd.out)
        }

        /** The kitchen in the back room, next to the hall: a range along the wall, a table to work at, and the servant at the bench. */
        fun kitchen(sd: Side) {
            val r0 = vB + 1
            val r1 = wv1 - 1
            pave(sd, r0, r1, g - 1) { _, _ -> STONE }
            val line = listOf(M.BARREL, M.FURNACE, M.SMOKER, M.CRAFTING_TABLE, M.FURNACE, M.BARREL, M.CAULDRON, M.BARREL, M.COMPOSTER)
            for ((i, m) in line.withIndex()) put(sd, 3 + i, g, r1, B(m, Dir.N))
            for (k in 5..6) put(sd, k, g, r0 + 1, TABLE)
            put(sd, 3, g, r0, B(M.BARREL, Dir.S))
            put(sd, 3, g, r0 + 1, B(M.BARREL, Dir.S))
            lamp(sd.u(kc), wv1 - 3, f1 - 1, 3)
        }

        /** The front room: an armourer's bench, a chest. */
        fun armoury(sd: Side) {
            pave(sd, 2, vA - 1, g - 1) { _, _ -> STONE }
            val line = listOf(M.ANVIL, M.SMITHING_TABLE, M.GRINDSTONE, M.BLAST_FURNACE, M.BARREL)
            for ((i, m) in line.withIndex()) put(sd, 3 + i, g, vA - 1, B(m, Dir.N))
            if (room(sd, kin, 3)) f.chest(sd.u(kin), g, 3, sd.out, Loot.BARRACKS)
            put(sd, kin, g, 4, B(M.BARREL, sd.out))
            lamp(sd.u(kc), 4, f1 - 1, 3)
        }

        /** The library: shelves up the wall and in stacks across the room, a table to read at between lamps. */
        fun library(sd: Side) {
            for (v in mv0..mv1) if (v !in cv - 1..cv + 1) for (y in g..g + 2) put(sd, kin, y, v, B(M.BOOKSHELF))
            for (v in mv0 + 2..mv1 - 2 step 4) for (k in 4..kin - 2) for (y in g..g + 1) put(sd, k, y, v, B(M.BOOKSHELF))
            for (v in mv0 + 4..mv1 - 4 step 4) {
                for (k in kc..kc + 1) put(sd, k, g, v, TABLE)
                put(sd, kc - 1, g, v, chair(sd.inward))
                lamp(sd.u(kc), v, f1 - 1, 3)
            }
            put(sd, 4, g, mv0, B(M.LECTERN, sd.inward))
        }

        /** The back room: a desk and chairs, shelves, a lectern. */
        fun study(sd: Side) {
            val r0 = vB + 1
            val r1 = wv1 - 1
            for (k in 3..kin - 1) put(sd, k, g, r1, B(M.BOOKSHELF))
            for (k in 3..kin - 1) put(sd, k, g + 1, r1, B(M.BOOKSHELF))
            for (k in kc..kc + 1) put(sd, k, g, r0 + 1, TABLE)
            put(sd, kc, g, r0 + 2, chair(Dir.N))
            put(sd, kc + 1, g, r0, chair(Dir.S))
            put(sd, 3, g, r0, B(M.LECTERN, Dir.S))
            lamp(sd.u(kc), wv1 - 3, f1 - 1, 3)
        }

        /** Upstairs on the left: the royal bedchamber in the long room, a guest's and a child's at the ends. */
        fun royalRooms(sd: Side) {
            lamp(sd.u(kc), 4, eaves, 2)
            bedAt(sd, 4, f1, vA - 1, Dye.WHITE)
            bedAt(sd, kc + 2, f1, vA - 1, Dye.WHITE)
            for (k in kc - 1..kc + 1) for (v in mv0 + 1..mv1 - 1) put(sd, k, f1, v, B(M.CARPET, color = Dye.RED))
            // A pair of beds at the far end, unless they would stand where the stairs come up
            for (k in if (kc - 1 < 5) listOf(kc) else listOf(kc - 1, kc + 1)) bedAt(sd, k, f1, vB - 1, Dye.RED)
            for (v in mv0 + 2..mv1 - 2 step 4) lamp(sd.u(kc), v, eaves, 2)
            bedAt(sd, kc, f1, wv1 - 1, Dye.YELLOW)
            lamp(sd.u(kc), wv1 - 3, eaves, 2)
        }

        /** Upstairs on the right: a reading room in front, the war room with its map table, a guest room behind. */
        fun courtRooms(sd: Side) {
            for (k in 4..kin - 1) put(sd, k, f1, vA - 1, B(M.BOOKSHELF))
            put(sd, kc, f1, 4, B(M.LECTERN, Dir.N))
            lamp(sd.u(kc), 4, eaves, 2)
            for (k in kc..kc + 1) for (v in mv0 + 2..mv1 - 2) put(sd, k, f1, v, TABLE)
            for (k in kc..kc + 1) put(sd, k, f1, (mv0 + mv1) / 2, B(M.CARTOGRAPHY_TABLE))
            for (v in mv0 + 2..mv1 - 2 step 2) {
                put(sd, kc - 1, f1, v, chair(sd.inward))
                put(sd, kc + 2, f1, v, chair(sd.out))
            }
            for (v in mv0 + 2..mv1 - 2 step 4) lamp(sd.u(kc), v, eaves, 2)
            bedAt(sd, kc, f1, wv1 - 1, Dye.GREEN)
            lamp(sd.u(kc), wv1 - 3, eaves, 2)
        }

        /** The little rooms at the tops of the towers. */
        fun towerTops(sd: Side) {
            for (cz in tcv) lamp(sd.u(tcu), cz, towerTop, 2)
        }
        // -- The keep --

        /** The slab over the keep's floor at [y]. */
        fun ceilingOf(y: Int): Int = (levels.getOrNull(levels.indexOf(y) + 1) ?: top) - 1

        /** The audience chamber over the lobby: a runner from the balcony door to the balcony over the hall, benches either side, shelves in the back corners. */
        fun audience() {
            val y = f1
            carpet(kv0, kv1, y)
            for (v in kv0 + 2..kv1 - 2 step 2) {
                set(mid - rw - 2, y, v, chair(Dir.E))
                set(mid + rw + 2, y, v, chair(Dir.W))
            }
            for (u in listOf(hu0..mid - 3, mid + 3..hu1).flatten()) for (h in 0..1) set(u, y + h, kv1, B(M.BOOKSHELF))
            lamp(mid, kv0 + 2, ceilingOf(y), 3)
            lamp(mid, kv1 - 2, ceilingOf(y), 3)
        }

        /** The royal chamber: a pair of beds under the back wall with a candle between them, a rug, chests. */
        fun chamber() {
            val y = levels[2]
            for (u in listOf(mid - 1, mid + 1)) f.bed(u, y, kv1 - 1, Dir.S, Dye.RED)
            set(mid, y, kv1, B(M.BARREL, Dir.N))
            set(mid, y + 1, kv1, B(M.CANDLE))
            for (u in mid - 2..mid + 2) for (v in cv - 1..cv + 1) {
                set(u, y, v, B(M.CARPET, color = if (abs(u - mid) == 2 || abs(v - cv) == 1) BANNER else Dye.RED))
            }
            for (u in listOf(mid - 3, mid + 3)) f.chest(u, y, kv0, Dir.S, Loot.HOUSE)
            lamp(mid, cv, ceilingOf(y), 2)
            for (sgn in listOf(-1, 1)) for (v in listOf(kv0 + 1, kv1 - 1)) lamp(mid + sgn * (half - 2), v, ceilingOf(y), 2)
        }

        /** A library: shelves along the back wall and a table to read at. */
        fun stacks(y: Int) {
            for (u in hu0 + 1..hu1 - 1) for (h in 0..2) set(u, y + h, kv1, B(M.BOOKSHELF))
            for (v in cv - 1..cv + 1) set(mid, y, v, TABLE)
            for (v in cv - 1..cv + 1) {
                set(mid - 1, y, v, chair(Dir.E))
                set(mid + 1, y, v, chair(Dir.W))
            }
            set(mid, y + 1, cv, B(M.CANDLE))
            lamp(mid, cv, ceilingOf(y), 3)
        }

        /** The treasury: chests of the city's wealth along the back wall, gold stacked between them, candles. */
        fun treasury(y: Int) {
            val n = if (size == 2) 4 else 3
            for (du in 2..n) for (sgn in listOf(-1, 1)) {
                f.chest(mid + sgn * du, y, kv1, Dir.N, Loot.TREASURY)
                set(mid + sgn * du, y + 1, kv1, B(M.CANDLE))
            }
            for (u in mid - 1..mid + 1) {
                set(u, y, kv1, B(M.GOLD))
                if (u != mid) set(u, y + 1, kv1, B(M.GOLD))
            }
            set(mid, y, cv, B(M.GOLD))
            set(mid, y + 1, cv, B(M.CANDLE))
            lamp(mid - 2, cv, ceilingOf(y), 2)
            lamp(mid + 2, cv, ceilingOf(y), 2)
        }

        /** Lanterns on the parapet of the terrace. */
        fun terrace() {
            for (v in listOf(k0, k1)) for (du in listOf(-4, 4)) set(mid + du + (if ((mid + du + v) % 2 == 0) 1 else 0), top + 1, v, B(M.LANTERN))
            for (u in listOf(c0, c1)) for (dv in listOf(-2, 2)) set(u, top + 1, cv + dv + (if ((u + cv + dv) % 2 == 0) 1 else 0), B(M.LANTERN))
        }

        /** Torches on the walls round about, under the eaves that overhang them: one at each tower, one in the middle of each wing's front and back. */
        fun outside() {
            for (sd in sides) {
                for (cz in tcv) set(sd.u(0), 3, cz, B(M.TORCH, sd.out))
                set(sd.u(8), 3, 0, B(M.TORCH, Dir.N))
                set(sd.u(8), 3, wv1 + 1, B(M.TORCH, Dir.S))
            }
        }

        /** The people of the palace: guards, nobles, a servant at the range, and the hall's beasts and villager. */
        fun folk() {
            // Guards at the porch, and either side of the throne
            for (u in listOf(mid - pw + 2, mid + pw - 2)) f.resident(Folk.GUARD, u, g, k0 - pd, facing = Dir.N)
            for (u in listOf(mid - 2, mid + 2)) f.resident(Folk.GUARD, u, g + 2, hv1, facing = Dir.N)
            // Lords and ladies of the court
            f.resident(Folk.NOBLE, mid - 2, g, dv0 - 2)
            f.resident(Folk.NOBLE, mid + 2, g, dv0 - 3)
            f.resident(Folk.NOBLE, mid + 2, g, kv1 - 1)
            if (size >= 1) f.resident(Folk.NOBLE, mid, f1, cv)
            if (size == 2) f.resident(Folk.NOBLE, sides[0].u(3), g, cv + 1)
            // The servant at the range
            val sd = sides[0]
            f.resident(Folk.CITIZEN, sd.u(6), g, wv1 - 2, work = f.at(sd.u(6), g, wv1 - 1))
            f.spawn(Spawn.GOLEM, mid - 2, g, hv0 + 3)
            f.spawn(Spawn.GOLEM, mid + 2, g, hv0 + 3)
            f.spawn(Spawn.VILLAGER, mid, g, hv0 + 1)
        }
    }
    // ---- The manor ----

    /**
     * A noble family's town manor, its front to the street across a court behind a low wall and an open gate.
     * The house is one full-width block of two or three tall storeys (timber and plaster over stone, banded
     * plaster, or dressed stone) under a steep gabled roof with a chimney near each end, a porch at its door with
     * a balcony over it, and by the lot a wing or two reaching forward into the court. Inside, the hall holds
     * the stairs, with a dining room and the kitchen on one hand, a study and a parlour on the other, and
     * bedrooms above; behind lies a garden. The plan is drawn with its wing on the left and turned over now and
     * then. Everything is built up in a buffer first, so a room can be asked what stands in it.
     */
    private inner class Manor {
        // What this manor is like: always the same draws, whatever the lot
        val planRoll = r.nextInt(6)
        val flip = r.nextBoolean()
        val style = r.nextInt(3)
        val coin = r.nextBoolean()
        val tallRoll = r.nextInt(3)
        val orielRoll = r.nextInt(2)
        val gardenKind = r.nextInt(3)
        val courtKind = r.nextInt(2)
        val colour = HOUSE_COLORS[r.nextInt(HOUSE_COLORS.size)]
        val tableKind = r.nextInt(2)
        val twoNobles = r.nextBoolean()
        val childRoll = r.nextInt(3)
        val bedTone = r.nextInt(Buildings.BED_COLORS.size)

        /** None, one or two wings reaching forward into the court. */
        val wings = when {
            planRoll < 2 -> 0
            planRoll < 4 || w < 19 -> 1
            else -> 2
        }
        val leftWing = wings >= 1
        val rightWing = wings == 2
        val floors = if (tallRoll == 0 && w >= 17) 3 else 2
        /** The highest course of the walls. */
        val top = floors * MANOR_STOREY - 1
        val pal = if (style == STONEWORK || !coin) SLATE else RED

        // Across the lot: a wing, the hall, a wing, the hall as wide as its stairs and door want
        val hall = if (w % 2 == 1) (if (w >= 19) 5 else 3) else (if (w >= 20) 6 else 4)
        val ww = (w - hall) / 2
        val hu0 = ww
        val hu1 = w - 1 - ww
        val pL = ww - 1
        val pR = w - ww
        val dc0 = (w - 1) / 2
        val dc1 = w / 2

        // From the street: the court, the house, the garden
        val cr = if (wings == 0) 5 + (d - 18) / 4 else 5 + (d - 18) / 3
        val dm = min(11, d - 2 - (2 + (d - 18) / 3) - cr)
        val fv0 = cr + 1
        val bv = fv0 + dm - 1
        val br = bv - 1
        /** The garden's depth behind the house. */
        val bd = d - 2 - bv
        val p = (cr - 2).coerceIn(3, 5)
        val wf = fv0 - p
        /** The row of wall between the front rooms and the back rooms at the sides. */
        val q = fv0 + 1 + (dm - 2) / 2
        val kw = (ww - 1) / 2
        val kc = (hall + 1) / 2

        /** Whether the wings' upper fronts hold a bay window instead of plain ones, how wide its nook is and where the left wing's starts. */
        val bayed = orielRoll == 1 && wings >= 1
        val bayWidth = if (ww % 2 == 0) 2 else 3
        val bayLeft = (ww - bayWidth) / 2

        val rooms = ArrayList<Room>()

        init {
            for (level in 0 until floors) {
                rooms.add(Room(level, if (level == 0) Purpose.HALL else Purpose.LANDING, hu0, fv0 + 1, hu1, br))
                val use = when (level) {
                    0 -> listOf(Purpose.DINING, Purpose.KITCHEN, Purpose.STUDY, Purpose.PARLOUR)
                    1 -> listOf(Purpose.BEDROOM, Purpose.BEDROOM, Purpose.BEDROOM, Purpose.LIBRARY)
                    else -> listOf(Purpose.BEDROOM, Purpose.STORE, Purpose.BEDROOM, Purpose.BEDROOM)
                }
                val lf = if (leftWing) wf + 1 else fv0 + 1
                val rf = if (rightWing) wf + 1 else fv0 + 1
                rooms.add(Room(level, use[0], 1, lf, ww - 2, q - 1))
                rooms.add(Room(level, use[1], 1, q + 1, ww - 2, br))
                rooms.add(Room(level, use[2], w - ww + 1, rf, w - 2, q - 1))
                rooms.add(Room(level, use[3], w - ww + 1, q + 1, w - 2, br))
            }
        }

        // ---- The buffer ----

        val buffer = arrayOfNulls<B>(w * d * LAYERS)
        val late = ArrayList<() -> Unit>()

        fun index(u: Int, y: Int, v: Int): Int = (u * d + v) * LAYERS + y + 2

        /** Mirrored, as the plan is drawn with its wing on the left. */
        fun mu(u: Int): Int = if (flip) w - 1 - u else u

        fun md(dir: Dir): Dir = if (flip && dir.alongX) dir.opposite else dir

        fun mb(block: B): B {
            val dir = block.dir
            if (!flip || dir == null || !dir.alongX) return block
            return B(block.m, dir.opposite, block.top, block.axis, block.color, block.v, block.part, block.open)
        }

        /** Puts [block] in the buffer at a place of the real lot, air clearing it. */
        fun raw(u: Int, y: Int, v: Int, block: B) {
            if (u !in 0 until w || v !in 0 until d || y + 2 !in 0 until LAYERS) return
            buffer[index(u, y, v)] = if (block.m == M.AIR) null else block
        }

        fun put(u: Int, y: Int, v: Int, block: B) = raw(mu(u), y, v, mb(block))

        fun get(u: Int, y: Int, v: Int): B? =
            if (u !in 0 until w || v !in 0 until d || y + 2 !in 0 until LAYERS) null else buffer[index(mu(u), y, v)]

        /** A box of [block] from corner to corner. */
        fun box(u0: Int, y0: Int, v0: Int, u1: Int, y1: Int, v1: Int, block: B) {
            for (u in u0..u1) for (v in v0..v1) for (y in y0..y1) put(u, y, v, block)
        }

        fun flush() {
            for (u in 0 until w) for (v in 0 until d) for (y in -2 until LAYERS - 2) buffer[index(u, y, v)]?.let { f.set(u, y, v, it) }
            for (action in late) action()
        }

        // ---- The shape ----

        /** Whether the house stands on a place of the plan. */
        fun foot(u: Int, v: Int): Boolean {
            if (u !in 0 until w) return false
            if (v in fv0..bv) return true
            return v in wf until fv0 && ((leftWing && u < ww) || (rightWing && u >= w - ww))
        }

        /** The roof's height over a column (the eaves' at the walls), or null where the house isn't. */
        fun roofAt(u: Int, v: Int): Int? {
            if (!foot(u, v)) return null
            var h = if (v >= fv0) top + 1 + min(v - fv0, bv - v) else 0
            if (leftWing && u < ww && v <= fv0 + kw) h = max(h, top + 1 + min(u, ww - 1 - u))
            if (rightWing && u >= w - ww && v <= fv0 + kw) h = max(h, top + 1 + min(u - (w - ww), w - 1 - u))
            if (wings == 0 && u in pL..pR && v in fv0..fv0 + kc) h = max(h, top + 1 + min(u - pL, pR - u))
            if (wings == 1 && u >= w - ww && v in fv0..fv0 + kw) h = max(h, top + 1 + min(u - (w - ww), w - 1 - u))
            return h
        }

        /** The sides of a wall column that look out of the house. */
        fun faces(u: Int, v: Int): List<Dir> = Dir.entries.filter { foot(u, v) && !foot(u + it.dx, v + it.dz) }

        /** Whether a side of the house, in the plan's own turn, backs onto a neighbour's. */
        fun sharedAt(side: Dir): Boolean = b.shared[(if (flip && side.alongX) side.opposite else side).ordinal]

        /** Whether a wall column looks out through [side] to open air. */
        fun looksOut(u: Int, v: Int, side: Dir): Boolean {
            if (!foot(u, v) || foot(u + side.dx, v + side.dz)) return false
            if (u == 0 && side == Dir.W) return !sharedAt(Dir.W)
            if (u == w - 1 && side == Dir.E) return !sharedAt(Dir.E)
            return true
        }

        fun draw() {
            grounds()
            house()
            roofs()
            openings()
            if (bayed) {
                bay(bayLeft)
                if (rightWing) bay(w - bayLeft - bayWidth)
            }
            chimneys()
            inside()
            flush()
        }

        // ---- The grounds ----

        fun grounds() {
            for (u in 0 until w) for (v in 0 until d) put(u, -1, v, B(M.GRASS))
            // The way from the gate to the door, as wide as the hall
            for (v in 0 until fv0) for (u in hu0..hu1) put(u, -1, v, B(M.PAVE_PLAZA, v = if (u == hu0 || u == hu1) 1 else 0))
            boundary()
            court()
            garden()
        }

        /** A low wall under iron bars, with posts and an open gate for the path. */
        fun fence(u: Int, v: Int) {
            put(u, 0, v, WALL)
            put(u, 1, v, B(M.BARS))
            put(u, 2, v, B(M.BARS))
        }

        fun gatepost(u: Int, v: Int, height: Int, lantern: Boolean) {
            for (y in 0 until height) put(u, y, v, WALL)
            put(u, height, v, TRIM)
            if (lantern) put(u, height + 1, v, B(M.LANTERN))
        }

        /** A hedge, two blocks high. */
        fun hedge(u: Int, v: Int) {
            put(u, 0, v, B(M.LEAVES))
            put(u, 1, v, B(M.LEAVES))
        }

        fun boundary() {
            // The street: bars all along but the gate, with posts at the corners, either side of the gate and between
            for (u in 0 until w) if (u !in hu0..hu1) fence(u, 0)
            gatepost(0, 0, 3, false)
            gatepost(w - 1, 0, 3, false)
            gatepost(hu0 - 1, 0, 4, true)
            gatepost(hu1 + 1, 0, 4, true)
            if (hu0 - 1 >= 7) for (u in listOf((hu0 - 1) / 2, w - 1 - (hu0 - 1) / 2)) gatepost(u, 0, 3, false)
            // The sides and the back: bars if they face the street, a hedge where the neighbours are
            for (v in 1 until d - 1) for (u in listOf(0, w - 1)) {
                if (foot(u, v)) continue
                val side = if (u == 0) Dir.W else Dir.E
                if (sharedAt(side)) hedge(u, v) else fence(u, v)
            }
            for (u in 0 until w) hedge(u, d - 1)
            gatepost(0, d - 1, 3, false)
            gatepost(w - 1, d - 1, 3, false)
        }

        /** A lamp on a post. */
        fun lampPost(u: Int, v: Int) {
            put(u, 0, v, B(M.WALL_POST))
            put(u, 1, v, B(M.FENCE))
            put(u, 2, v, B(M.FENCE))
            put(u, 3, v, B(M.LANTERN))
        }

        /** A little tree: a trunk and a rounded crown. */
        fun tree(u: Int, v: Int, trunk: Int, lantern: Boolean) {
            for (y in 0 until trunk) put(u, y, v, B(M.LOG))
            for (du in -2..2) for (dv in -2..2) for (dy in -1..1) {
                if (abs(du) == 2 && abs(dv) == 2) continue
                if (abs(dy) == 1 && (abs(du) == 2 || abs(dv) == 2)) continue
                if (du == 0 && dv == 0 && dy < 0) continue
                put(u + du, trunk - 1 + dy + 1, v + dv, B(M.LEAVES))
            }
            if (lantern) put(u + 1, trunk - 1, v, B(M.LANTERN, top = true))
        }

        /** A bed of flowers, each place with its own bloom. */
        fun flowers(u0: Int, v0: Int, u1: Int, v1: Int, seed: Int) {
            for (u in u0..u1) for (v in v0..v1) {
                if (!foot(u, v) && get(u, 0, v) == null && get(u, -1, v)?.m == M.GRASS) {
                    put(u, 0, v, B(M.FLOWER, v = Math.floorMod(u * 3 + v * 5 + seed, 5)))
                }
            }
        }

        fun court() {
            // Hedges along the path, with lamps in them, and flowers along the street
            for (v in 1 until fv0) for (u in listOf(hu0 - 1, hu1 + 1)) if (u in 1 until w - 1 && !foot(u, v)) put(u, 0, v, B(M.LEAVES))
            for (u in listOf(hu0 - 1, hu1 + 1)) {
                lampPost(u, 2)
                if (!foot(u, fv0 - 3)) lampPost(u, fv0 - 3)
            }
            flowers(1, 1, hu0 - 2, 1, 0)
            flowers(hu1 + 2, 1, w - 2, 1, 2)
            // The lawns either side of the path: a tree or a pool in the middle of each when there's room, flowers in the rest
            for (side in 0..1) {
                val u0 = if (side == 0) 1 else hu1 + 2
                val u1 = if (side == 0) hu0 - 2 else w - 2
                val v1 = if ((side == 0 && leftWing) || (side == 1 && rightWing)) wf - 1 else fv0 - 1
                if (v1 < 2 || u1 < u0) continue
                if (u1 - u0 >= 4 && v1 >= 6) {
                    val cu = (u0 + u1) / 2
                    val cv = min((2 + v1) / 2 + 1, v1 - 2)
                    if (courtKind == 0) tree(cu, cv, 3, true) else pool(cu, cv)
                    flowers(u0, 2, u1, 2, side + 1)
                    flowers(u0, v1, u1, v1, side + 3)
                } else {
                    flowers(u0, 2, u1, v1, side)
                }
            }
        }

        /** A little pool with a stone rim and a lantern on a pillar in the middle. */
        fun pool(cu: Int, cv: Int) {
            for (du in -2..2) for (dv in -2..2) {
                val edge = abs(du) == 2 || abs(dv) == 2
                if (abs(du) == 2 && abs(dv) == 2) continue
                put(cu + du, -1, cv + dv, if (edge) FINE else B(M.WATER))
                if (edge) put(cu + du, 0, cv + dv, B(M.FINE_SLAB))
            }
            put(cu, -1, cv, FINE)
            put(cu, 0, cv, PILLAR)
            put(cu, 1, cv, PILLAR)
            put(cu, 2, cv, B(M.LANTERN))
        }

        /** A bench of three seats looking toward [looks]. */
        fun bench(u: Int, v: Int, looks: Dir) {
            val along = looks.cw
            for (k in -1..1) put(u + along.dx * k, 0, v + along.dz * k, chair(looks))
        }

        /** The garden behind the house: a gravel path from the back door and, by the draw, an orchard, a kitchen garden or flower beds. */
        fun garden() {
            val v0 = bv + 1
            val v1 = d - 2
            if (v1 < v0) return
            for (v in v0..v1) for (u in dc0..dc1) put(u, -1, v, B(M.TRACK))
            lampPost(dc0 - 2, v0 + 1)
            lampPost(dc1 + 2, v0 + 1)
            flowers(1, v0, dc0 - 3, v0, 1)
            flowers(dc1 + 3, v0, w - 2, v0, 4)
            // The beds either side of the path
            val left = 2..dc0 - 2
            val right = dc1 + 2..w - 3
            when {
                bd < 3 -> {
                    flowers(1, v1, dc0 - 1, v1, 0)
                    flowers(dc1 + 1, v1, w - 2, v1, 3)
                }
                gardenKind == 0 -> {
                    // An orchard: two trees, one in blossom, and a bench at the end of the path
                    if (bd >= 5) {
                        tree(left.first + 1, v1 - 2, 3, true)
                        tree(right.last - 1, v1 - 2, 3, true)
                        for (u in listOf(left.first + 1, right.last - 1)) for (dv in -1..1) for (du in -1..1) {
                            if (get(u + du, 3, v1 - 2 + dv)?.m == M.LEAVES && (u + du + dv) % 3 == 0) put(u + du, 3, v1 - 2 + dv, B(M.LEAVES, v = 1))
                        }
                    }
                    bench((dc0 + dc1) / 2, v1, Dir.N)
                    flowers(left.first, v0 + 1, left.last, v1 - if (bd >= 5) 5 else 1, 2)
                    flowers(right.first, v0 + 1, right.last, v1 - if (bd >= 5) 5 else 1, 0)
                }
                gardenKind == 1 -> {
                    // A kitchen garden: rows of crops either side of the path, a composter and a scarecrow
                    for (bed in listOf(left, right)) for (u in bed) for (v in v0 + 1..v1 - 1) {
                        put(u, -1, v, B(M.FARMLAND))
                        put(u, 0, v, B(M.CROP, v = (u / 2) % 4))
                    }
                    put(left.last, 0, v0 + 1, B(M.COMPOSTER))
                    put(right.first, 0, v0 + 1, B(M.BARREL, Dir.W))
                    val su = (left.first + left.last) / 2
                    put(su, 0, v1 - 1, B(M.FENCE))
                    put(su, 1, v1 - 1, B(M.FENCE))
                    put(su, 2, v1 - 1, B(M.PUMPKIN, Dir.S, v = 1))
                    put(su - 1, 1, v1 - 1, B(M.HAY))
                }
                else -> {
                    // Flower beds with a low hedge round them, and a sundial at the end of the path
                    for (bed in listOf(left, right)) {
                        for (u in bed) for (v in v0 + 1..v1 - 1) {
                            val rim = u == bed.first || u == bed.last || v == v0 + 1 || v == v1 - 1
                            put(u, 0, v, if (rim) B(M.LEAVES) else B(M.FLOWER, v = Math.floorMod(u + 2 * v, 5)))
                        }
                    }
                    val mid = (dc0 + dc1) / 2
                    put(mid, 0, v1, FINE)
                    put(mid, 1, v1, PILLAR)
                    put(mid, 2, v1, B(M.FINE_SLAB))
                }
            }
        }

        // ---- The house ----

        fun skin(u: Int, v: Int, y: Int, faces: List<Dir>): B {
            val corner = faces.any { it.alongX } && faces.any { !it.alongX }
            val along = if (faces.any { !it.alongX }) Axis.X else Axis.Z
            val band = (y + 1) % MANOR_STOREY == 0
            val ground = y < MANOR_STOREY - 1
            if (y == 0) return DARK
            return when (style) {
                STONEWORK -> when {
                    band -> TRIM
                    corner -> if ((y + u + v) % 2 == 0) TRIM else WALL
                    else -> WALL
                }
                TIMBERED -> when {
                    ground -> STONE
                    corner -> B(M.TIMBER)
                    band -> B(M.TIMBER, axis = along)
                    else -> PLASTER
                }
                else -> when {
                    band -> TRIM
                    corner -> if (ground) STONE else TRIM
                    ground -> STONE
                    else -> PLASTER
                }
            }
        }

        /** What fills a gable above the walls. */
        fun infill(): B = if (style == STONEWORK) WALL else PLASTER

        fun house() {
            for (u in 0 until w) for (v in 0 until d) {
                val h = roofAt(u, v) ?: continue
                put(u, -1, v, B(M.PLANKS, v = 1))
                val faces = faces(u, v)
                for (y in 0 until h) {
                    val block = when {
                        faces.isEmpty() -> if (y <= top) PLASTER else B(M.ROOF, v = pal)
                        y <= top -> skin(u, v, y, faces)
                        else -> infill()
                    }
                    put(u, y, v, block)
                }
            }
        }

        fun roofs() {
            for (u in 0 until w) for (v in 0 until d) {
                val h = roofAt(u, v) ?: continue
                var up: Dir? = null
                var high = h
                for (n in Dir.entries) {
                    val nh = roofAt(u + n.dx, v + n.dz) ?: continue
                    if (nh > high) {
                        high = nh
                        up = n
                    }
                }
                if (up != null) put(u, h, v, B(M.ROOF_STAIRS, up, v = pal))
                else {
                    put(u, h, v, B(M.ROOF, v = pal))
                    put(u, h + 1, v, B(M.ROOF_SLAB, v = pal))
                }
                // A window in the peak of a gable
                for (n in Dir.entries) {
                    if (!looksOut(u, v, n) || h - 1 < top + 2) continue
                    val a = n.cw
                    val before = roofAt(u + a.dx, v + a.dz) ?: 0
                    val after = roofAt(u - a.dx, v - a.dz) ?: 0
                    if (before <= h && after <= h && foot(u + a.dx, v + a.dz) && foot(u - a.dx, v - a.dz)) put(u, top + 2, v, WINDOW)
                }
            }
            // The eaves stand out on a row of upside-down stairs
            for (u in 0 until w) for (v in 0 until d) {
                val h = roofAt(u, v) ?: continue
                for (n in Dir.entries) {
                    val ou = u + n.dx
                    val ov = v + n.dz
                    if (foot(ou, ov) || ou !in 0 until w || ov !in 0 until d) continue
                    val inner = roofAt(u - n.dx, v - n.dz) ?: continue
                    if (inner <= h) continue
                    if (get(ou, top, ov) == null) {
                        put(ou, top, ov, B(if (style == STONEWORK) M.WALL_STAIRS else M.ROOF_STAIRS, n.opposite, top = true, v = pal))
                    }
                }
            }
        }

        // ---- Windows, doors and the porch ----

        /** Which windows a wall of [n] cells has, as the places along it that hold glass. */
        fun spans(n: Int): List<IntRange> = when {
            n < 3 -> emptyList()
            n == 3 -> listOf(1..1)
            n == 4 -> listOf(1..2)
            n == 5 -> listOf(1..1, 3..3)
            n == 6 -> listOf(1..1, 4..4)
            n == 7 -> listOf(1..2, 4..5)
            n == 8 -> listOf(1..2, 5..6)
            n == 9 -> listOf(1..2, 6..7)
            else -> (1..n - 3 step 3).map { it..it + 1 }
        }

        /** Whether a place of a wing's front is left to its bay window: the wall behind it and a little to each side. */
        fun inBay(level: Int, u: Int, v: Int): Boolean {
            if (!bayed || level != 1 || (v != wf && v != wf - 1)) return false
            val near = bayLeft - 2..bayLeft + bayWidth + 1
            return u in near || rightWing && w - 1 - u in near
        }

        fun window(level: Int, side: Dir, stretch: List<Pair<Int, Int>>, span: IntRange, shutters: Boolean) {
            val y0 = level * MANOR_STOREY
            val height = if (style == STONEWORK) 3 else 2
            for (i in span) {
                val (u, v) = stretch[i]
                if (inBay(level, u, v)) continue
                for (h in 1..height) put(u, y0 + h, v, WINDOW)
                if (style == STONEWORK && level > 0) put(u, y0, v, TRIM)
            }
            for (i in listOf(span.first - 1, span.last + 1)) {
                if (i !in stretch.indices) continue
                val (u, v) = stretch[i]
                if (style == STONEWORK || !shutters) continue
                for (h in 1..2) {
                    val su = u + side.dx
                    val sv = v + side.dz
                    if (inBay(level, su, sv)) continue
                    if (su in 0 until w && sv in 0 until d && !foot(su, sv) && get(su, y0 + h, sv) == null) {
                        put(su, y0 + h, sv, B(M.SHUTTER, side, open = true))
                    }
                }
            }
        }

        /**
         * A bay window on a wing's upper floor: a nook boxed out over the court on corbels from [u0] along, glazed on
         * three sides under a small roof that leans on the gable, open to the room behind it.
         */
        fun bay(u0: Int) {
            val u1 = u0 + bayWidth - 1
            val outer = wf - 2
            val inner = wf - 1
            val frame = if (style == TIMBERED) B(M.TIMBER) else TRIM
            val corbel = B(if (style == TIMBERED) M.PLANK_STAIRS else M.WALL_STAIRS, Dir.S, top = true)
            val floor = MANOR_STOREY - 1
            for (u in u0 - 1..u1 + 1) {
                val side = u == u0 - 1 || u == u1 + 1
                put(u, floor - 1, outer, corbel)
                put(u, floor - 1, inner, frame)
                put(u, floor, outer, frame)
                put(u, floor, inner, if (side) frame else B(M.PLANKS))
                put(u, floor + 1, outer, frame)
                if (side) put(u, floor + 1, inner, frame)
                for (y in floor + 2..floor + 3) {
                    put(u, y, outer, if (side) frame else WINDOW)
                    if (side) put(u, y, inner, WINDOW)
                }
                put(u, floor + 4, outer, frame)
                if (side) put(u, floor + 4, inner, frame)
                put(u, floor + 5, outer, B(M.ROOF_STAIRS, Dir.S, v = pal))
                put(u, floor + 5, inner, if (side) frame else B(M.PLANKS))
                put(u, floor + 6, inner, B(M.ROOF_STAIRS, Dir.S, v = pal))
            }
            for (u in u0..u1) {
                for (y in floor + 1..floor + 4) put(u, y, wf, AIR)
                // The room's front must stay open to it
                keep(1, u, wf + 1)
            }
            put((u0 + u1) / 2, floor + 4, inner, B(M.LANTERN, top = true))
        }

        /** Timber posts where a stretch of wall ends, at the walls inside meeting it. */
        fun posts(level: Int, side: Dir, stretch: List<Pair<Int, Int>>) {
            val y0 = level * MANOR_STOREY
            val du = if (side.alongX) 0 else 1
            val dv = if (side.alongX) 1 else 0
            val first = stretch.first()
            val last = stretch.last()
            for ((u, v) in listOf(first.first - du to first.second - dv, last.first + du to last.second + dv)) {
                if (!foot(u, v) || foot(u + side.dx, v + side.dz)) continue
                for (y in y0..y0 + 3) put(u, y, v, B(M.TIMBER))
            }
        }

        fun windows() {
            for (room in rooms) for (side in Dir.entries) {
                val line = when (side) {
                    Dir.N -> (room.u0..room.u1).map { it to room.v0 - 1 }
                    Dir.S -> (room.u0..room.u1).map { it to room.v1 + 1 }
                    Dir.W -> (room.v0..room.v1).map { room.u0 - 1 to it }
                    Dir.E -> (room.v0..room.v1).map { room.u1 + 1 to it }
                }
                // The hall's door takes up its front and back on the lower floors
                val hallDoor = room.purpose == Purpose.HALL && (side == Dir.N || side == Dir.S) ||
                    room.purpose == Purpose.LANDING && room.level == 1 && side == Dir.N
                if (hallDoor && hall <= 4) continue
                var stretch = ArrayList<Pair<Int, Int>>()
                for (cell in line + listOf(null)) {
                    if (cell != null && looksOut(cell.first, cell.second, side)) {
                        stretch.add(cell)
                        continue
                    }
                    for (span in spans(stretch.size)) window(room.level, side, stretch, span, !hallDoor)
                    if (style == TIMBERED && room.level > 0 && stretch.isNotEmpty()) posts(room.level, side, stretch)
                    stretch = ArrayList()
                }
            }
        }

        fun door(u: Int, y: Int, v: Int, inward: Dir, hingeRight: Boolean) {
            raw(u, y, v, B(M.DOOR, inward, part = 0, v = if (hingeRight) 1 else 0))
            raw(u, y + 1, v, B(M.DOOR, inward, part = 1, v = if (hingeRight) 1 else 0))
            late.add { f.door(u, y, v, inward, hingeRight) }
        }

        fun openings() {
            windows()
            // The front door under a transom, the back door under another
            for (u in dc0..dc1) {
                door(u, 0, fv0, Dir.S, u == dc0 && dc0 != dc1)
                put(u, 2, fv0, WINDOW)
                door(u, 0, bv, Dir.N, u == dc0 && dc0 != dc1)
                put(u, 2, bv, WINDOW)
            }
            // The porch: two pillars, a balcony on top with a rail, a lantern hung under it and the colours either side of the door
            for (u in hu0..hu1) for (v in fv0 - 2 until fv0) {
                put(u, -1, v, B(M.PAVE_PLAZA, v = 2))
                put(u, MANOR_STOREY - 1, v, TRIM)
            }
            for (u in listOf(hu0, hu1)) for (y in 0 until MANOR_STOREY - 1) put(u, y, fv0 - 2, PILLAR)
            put(dc0, MANOR_STOREY - 2, fv0 - 1, B(M.LANTERN, top = true))
            for (u in listOf(hu0, hu1)) put(u, 2, fv0 - 1, B(M.BANNER, Dir.N, color = colour))
            for (u in hu0..hu1) put(u, MANOR_STOREY, fv0 - 2, B(M.FENCE))
            for (u in listOf(hu0, hu1)) {
                val out = if (u == hu0) u - 1 else u + 1
                if (!foot(out, fv0 - 1)) put(u, MANOR_STOREY, fv0 - 1, B(M.FENCE))
            }
            // The balcony's French door
            for (u in dc0..dc1) {
                door(u, MANOR_STOREY, fv0, Dir.S, u == dc0 && dc0 != dc1)
                put(u, MANOR_STOREY + 2, fv0, WINDOW)
            }
            // Lamps by the back door
            put(hu0, 2, bv + 1, B(M.TORCH, Dir.S))
            put(hu1, 2, bv + 1, B(M.TORCH, Dir.S))
        }

        // ---- Inside ----

        fun inside() {
            for (room in rooms) carve(room)
            doorways()
            stairs()
            for (room in rooms) furnish(room)
            for (room in rooms) lights(room)
            folk()
        }

        fun floorOf(level: Int): B = if (level == 0) B(M.PLANKS, v = 1) else B(M.PLANKS)

        fun carve(room: Room) {
            val y0 = room.level * MANOR_STOREY
            for (u in room.u0..room.u1) for (v in room.v0..room.v1) {
                put(u, y0 - 1, v, floorOf(room.level))
                for (y in y0 until y0 + MANOR_STOREY - 1) put(u, y, v, AIR)
                put(u, y0 + MANOR_STOREY - 1, v, B(M.PLANKS))
            }
        }

        /** A doorway, two blocks high, through the wall at ([u], [v]) on [level]. */
        fun doorway(level: Int, u: Int, v: Int) {
            put(u, level * MANOR_STOREY - 1, v, floorOf(level))
            for (y in 0..1) put(u, level * MANOR_STOREY + y, v, AIR)
        }

        fun doorways() {
            for (level in 0 until floors) {
                for (u in listOf(pL, pR)) {
                    doorway(level, u, fv0 + 1)
                    doorway(level, u, br)
                }
                doorway(level, ww - 2, q)
                doorway(level, w - ww + 1, q)
                // Nothing stands in front of a doorway
                for (u in listOf(pL, pR)) for (v in listOf(fv0 + 1, br)) {
                    keep(level, u - 1, v)
                    keep(level, u + 1, v)
                }
                for (u in listOf(ww - 2, w - ww + 1)) {
                    keep(level, u, q - 1)
                    keep(level, u, q + 1)
                }
            }
            for (u in dc0..dc1) {
                keep(0, u, fv0 + 1)
                keep(0, u, br)
                keep(1, u, fv0 + 1)
            }
        }

        /**
         * A flight of five stairs up from [level], in column [u] from row [v0] one way along [dv], with solid under it
         * and a hole in the floor over all but its first and last steps, a rail round the hole on the side [side].
         */
        fun flight(level: Int, u: Int, v0: Int, dv: Int, side: Int) {
            val y0 = level * MANOR_STOREY
            val ceiling = y0 + MANOR_STOREY - 1
            val ascent = if (dv > 0) Dir.S else Dir.N
            for (k in 0 until MANOR_STOREY) {
                val v = v0 + dv * k
                for (y in y0 until y0 + k) put(u, y, v, B(M.PLANKS))
                put(u, y0 + k, v, B(M.PLANK_STAIRS, ascent))
                if (k in 1..3) {
                    put(u, ceiling, v, AIR)
                    put(u + side, ceiling + 1, v, B(M.FENCE))
                }
            }
            put(u, ceiling + 1, v0, B(M.FENCE))
            // Nothing stands where the flight is stepped on to and where it lets out
            keep(level, u, v0 - dv)
            keep(level + 1, u, v0 + dv * (MANOR_STOREY - 1))
            keep(level + 1, u, v0 + dv * MANOR_STOREY)
        }

        fun stairs() {
            flight(0, hu0, br - MANOR_STOREY, 1, 1)
            if (floors == 3) flight(1, hu1, br - 1, -1, -1)
        }

        // ---- Furnishing ----

        /** Floor spots left clear to walk through: (level, u, v). */
        val kept = HashSet<Triple<Int, Int, Int>>()

        fun keep(level: Int, u: Int, v: Int) {
            kept.add(Triple(level, u, v))
        }

        /** The height of a room's floor. */
        fun base(room: Room): Int = room.level * MANOR_STOREY

        /** Whether the floor of a room is bare at ([u], [v]), there is a floor there and it's wanted for nothing else. */
        fun free(room: Room, u: Int, v: Int): Boolean =
            room.has(u, v) && get(u, base(room) - 1, v) != null && get(u, base(room), v) == null && Triple(room.level, u, v) !in kept

        /** Whether someone could stand at ([u], [v]) of a room: a floor under it, nothing but a rug on it. */
        fun walkable(room: Room, u: Int, v: Int): Boolean {
            if (!room.has(u, v) || get(u, base(room) - 1, v) == null) return false
            val here = get(u, base(room), v)
            return here == null || here.m == M.CARPET
        }

        /** How many separate stretches of floor a room has to walk on, if the spots [shut] were built over. */
        fun stretches(room: Room, shut: Set<Pair<Int, Int>>): Int {
            val seen = HashSet<Pair<Int, Int>>()
            var count = 0
            for (u in room.u0..room.u1) for (v in room.v0..room.v1) {
                val start = u to v
                if (start in seen || start in shut || !walkable(room, u, v)) continue
                count++
                seen.add(start)
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(start)
                while (queue.isNotEmpty()) {
                    val (cu, cv) = queue.removeFirst()
                    for (n in Dir.entries) {
                        val next = (cu + n.dx) to (cv + n.dz)
                        if (next in seen || next in shut || !walkable(room, next.first, next.second)) continue
                        seen.add(next)
                        queue.add(next)
                    }
                }
            }
            return count
        }

        /** Whether building over [spots] would shut a corner or a side of a room off from the rest of it. */
        fun cuts(room: Room, vararg spots: Pair<Int, Int>): Boolean = stretches(room, spots.toSet()) > stretches(room, emptySet())

        /** Puts [block] on a bare spot of a room's floor, [height] up, unless that would wall off a part of the room. */
        fun place(room: Room, u: Int, v: Int, block: B, height: Int = 0): Boolean {
            if (!free(room, u, v) || cuts(room, u to v)) return false
            put(u, base(room) + height, v, block)
            return true
        }

        /** Whether the wall beside a floor spot has a window in it. */
        fun glazed(room: Room, u: Int, v: Int): Boolean =
            Dir.entries.any { get(u + it.dx, base(room) + 1, v + it.dz)?.m == M.WINDOW }

        /** A lantern hung from the ceiling over ([u], [v]). */
        fun lamp(room: Room, u: Int, v: Int) {
            val y = base(room) + MANOR_STOREY - 2
            if (get(u, y, v) == null && get(u, y + 1, v) != null) put(u, y, v, B(M.LANTERN, top = true))
        }

        fun chest(room: Room, u: Int, v: Int, facing: Dir, loot: Loot) {
            if (!place(room, u, v, B(M.CHEST, facing))) return
            val y = base(room)
            late.add { f.chest(mu(u), y, v, md(facing), loot) }
        }

        /** A bed with its foot at ([u], [v]) and its head toward [toward]. */
        fun bed(room: Room, u: Int, v: Int, toward: Dir, color: Dye) {
            val hu = u + toward.dx
            val hv = v + toward.dz
            if (!free(room, u, v) || !free(room, hu, hv) || cuts(room, u to v, hu to hv)) return
            val y = base(room)
            put(u, y, v, B(M.BED, toward, color = color, part = 0))
            put(hu, y, hv, B(M.BED, toward, color = color, part = 1))
            late.add { f.bed(mu(u), y, v, md(toward), color) }
        }

        /** A rug of [color] over whatever floor is left bare in a box. */
        fun rug(room: Room, u0: Int, v0: Int, u1: Int, v1: Int, color: Dye) {
            for (u in u0..u1) for (v in v0..v1) if (free(room, u, v)) put(u, base(room), v, B(M.CARPET, color = color))
        }

        fun shelf(room: Room, u: Int, v: Int, height: Int) {
            if (!free(room, u, v) || cuts(room, u to v)) return
            for (h in 0 until if (glazed(room, u, v)) 1 else height) put(u, base(room) + h, v, B(M.BOOKSHELF))
        }

        /** The bare spots of a room, nearest its middle first. */
        fun middling(room: Room): List<Pair<Int, Int>> {
            val cu = (room.u0 + room.u1) / 2.0
            val cv = (room.v0 + room.v1) / 2.0
            return (room.u0..room.u1).flatMap { u -> (room.v0..room.v1).map { v -> u to v } }
                .filter { (u, v) -> free(room, u, v) && get(u, base(room) + 1, v) == null }
                .sortedBy { (u, v) -> abs(u - cu) + abs(v - cv) }
        }

        fun roomOf(level: Int, purpose: Purpose): Room? = rooms.firstOrNull { it.level == level && it.purpose == purpose }

        /** A fire in the chimney's breast, where a front room's back wall meets the end wall. */
        fun hearth(room: Room) {
            val u = if (room.u0 == 1) 1 else if (room.u1 == w - 2) w - 2 else return
            if (room.v1 != q - 1) return
            val y = base(room)
            if (get(u, y, q)?.m != M.STONE) return
            put(u, y, q, B(M.FURNACE, Dir.N))
            keep(room.level, u, q - 1)
        }

        fun furnish(room: Room) {
            hearth(room)
            when (room.purpose) {
                Purpose.HALL -> hallway(room)
                Purpose.LANDING -> landing(room)
                Purpose.DINING -> dining(room)
                Purpose.KITCHEN -> kitchen(room)
                Purpose.STUDY -> study(room)
                Purpose.PARLOUR -> parlour(room)
                Purpose.BEDROOM -> bedroom(room)
                Purpose.LIBRARY -> library(room)
                Purpose.STORE -> store(room)
            }
        }

        /** A runner of the family's colour down the hall, the colours on the walls, a chest and a table with flowers. */
        fun hallway(room: Room) {
            val y = base(room)
            for (v in room.v0..room.v1) for (u in dc0..dc1) if (get(u, y, v) == null) put(u, y, v, B(M.CARPET, color = colour))
            for (u in listOf(hu0, hu1)) put(u, y + 2, fv0 + 1, B(M.BANNER, Dir.S, color = colour))
            chest(room, hu1, fv0 + 3, Dir.W, Loot.HOUSE)
            if (place(room, hu1, fv0 + 5, TABLE)) put(hu1, y + 1, fv0 + 5, B(M.FLOWER_POT, v = 2))
            place(room, hu0, fv0 + 2, chair(Dir.E))
            for (v in listOf(fv0 + 2, (fv0 + br) / 2 + 1)) lamp(room, dc0, v)
            lamp(room, dc0, br - 1)
        }

        /** The landing, with the runner, a pot of flowers on a table, a seat. */
        fun landing(room: Room) {
            val y = base(room)
            for (v in room.v0..room.v1) for (u in dc0..dc1) if (get(u, y, v) == null) put(u, y, v, B(M.CARPET, color = colour))
            if (place(room, hu1, room.v0 + 2, TABLE)) put(hu1, y + 1, room.v0 + 2, B(M.FLOWER_POT, v = 4))
            place(room, hu0, room.v0 + 1, chair(Dir.E))
            for (v in listOf(room.v0 + 1, br - 1)) lamp(room, dc0, v)
        }

        /** A long table down the middle of the room, chairs along both sides and at its ends, a sideboard. */
        fun dining(room: Room) {
            val y = base(room)
            val alongV = room.depth >= room.width
            val cu = (room.u0 + room.u1) / 2
            val cv = (room.v0 + room.v1) / 2
            val table = if (alongV) {
                val len = min(room.depth - 2, 7)
                val start = room.v0 + 1 + (room.depth - 2 - len) / 2
                (start until start + len).map { cu to it }
            } else {
                val len = min(room.width - 2, 5)
                val start = room.u0 + 1 + (room.width - 2 - len) / 2
                (start until start + len).map { it to cv }
            }
            for ((u, v) in table) place(room, u, v, TABLE)
            val across = if (alongV) 1 to 0 else 0 to 1
            val first = table.first()
            val last = table.last()
            // The head of the table at the sideboard end, then the chest and the sideboard along the back wall, then the rest
            place(room, last.first + across.second, last.second + across.first, chair(if (alongV) Dir.N else Dir.W))
            chest(room, room.u0 + 1, room.v1, Dir.N, Loot.HOUSE)
            for (u in room.u0..room.u1) if (u != cu) place(room, u, room.v1, if (u % 2 == 0) B(M.BARREL, Dir.N) else TABLE)
            for ((u, v) in table) {
                place(room, u - across.first, v - across.second, chair(if (alongV) Dir.E else Dir.S))
                place(room, u + across.first, v + across.second, chair(if (alongV) Dir.W else Dir.N))
            }
            place(room, first.first - across.second, first.second - across.first, chair(if (alongV) Dir.S else Dir.E))
            for ((i, c) in table.withIndex()) {
                if (get(c.first, y, c.second)?.m != M.PLANK_SLAB) continue
                if (i == table.size / 2) put(c.first, y + 1, c.second, B(M.FLOWER_POT, v = (c.first + c.second) % 7))
                else if (i % 2 == 0) put(c.first, y + 1, c.second, B(M.CANDLE))
            }
            for (c in table.filterIndexed { i, _ -> i % 4 == 1 }) lamp(room, c.first, c.second)
            lamp(room, table[table.size / 2].first, table[table.size / 2].second)
        }

        /** The kitchen: tools along the back wall, barrels and a composter by the side, a cook at the work table. */
        fun kitchen(room: Room) {
            val y = base(room)
            val tools = listOf(if (tableKind == 0) M.CRAFTING_TABLE else M.LOOM, M.FURNACE, M.SMOKER, M.BARREL, M.CAULDRON)
            keep(room.level, room.u0, room.v1 - 1)
            for ((i, m) in tools.withIndex()) place(room, room.u0 + i, room.v1, B(m, Dir.N))
            for ((i, m) in listOf(M.COMPOSTER, M.BARREL, M.BARREL).withIndex()) place(room, room.u0, room.v0 + i, B(m, Dir.E))
            if (room.width >= 4 && room.depth >= 4) {
                for (u in room.u0 + 2..room.u1 - 1) place(room, u, room.v0 + 1, TABLE)
                place(room, room.u0 + 2, room.v0 + 2, chair(Dir.N))
            }
            val tu = room.u0
            val tv = room.v1
            late.add { f.resident(Folk.CITIZEN, mu(tu), y, tv - 1, work = f.at(mu(tu), y, tv)) }
        }

        /** The study: shelves up the walls, a desk with a lectern and a chair, a rug, a strongbox. */
        fun study(room: Room) {
            val y = base(room)
            val cu = (room.u0 + room.u1) / 2
            val cv = (room.v0 + room.v1) / 2
            chest(room, cu, room.v0, Dir.S, Loot.LIBRARY)
            for (u in room.u0..room.u1) for (v in listOf(room.v0, room.v1)) shelf(room, u, v, 3)
            for (v in room.v0..room.v1) shelf(room, room.u1, v, 3)
            place(room, cu, cv, TABLE)
            place(room, cu + 1, cv, TABLE)
            place(room, cu - 1, cv, B(M.LECTERN, Dir.E))
            place(room, cu, cv + 1, chair(Dir.N))
            if (get(cu, y, cv)?.m == M.PLANK_SLAB) put(cu, y + 1, cv, B(M.CANDLE))
            rug(room, cu - 1, cv - 1, cu + 1, cv + 1, colour)
        }

        /** The parlour: a settee, armchairs round a low table, a rug and flowers. */
        fun parlour(room: Room) {
            val cu = (room.u0 + room.u1) / 2
            val cv = (room.v0 + room.v1) / 2
            place(room, cu, cv, TABLE)
            place(room, cu - 1, cv, chair(Dir.E))
            place(room, cu + 1, cv, chair(Dir.W))
            for (u in cu - 1..cu + 1) place(room, u, room.v1, chair(Dir.N))
            for (u in listOf(room.u0, room.u1)) place(room, u, room.v0, B(M.FLOWER_POT, v = u % 5))
            rug(room, cu - 1, cv - 1, cu + 1, cv + 1, colour)
            if (get(cu, base(room), cv)?.m == M.PLANK_SLAB) put(cu, base(room) + 1, cv, B(M.FLOWER_POT, v = 1))
        }

        /** A bedroom: the bed's head to the outer wall, a chest at its foot, a wardrobe, a bedside table, a rug. */
        fun bedroom(room: Room) {
            val y = base(room)
            val left = room.u1 < w / 2
            val toward = if (left) Dir.W else Dir.E
            val head = if (left) room.u0 else room.u1
            val foot = head - toward.dx
            val cv = (room.v0 + room.v1) / 2
            val color = Buildings.BED_COLORS[(bedTone + room.level + room.v0) % Buildings.BED_COLORS.size]
            bed(room, foot, cv, toward, if (room.purpose == Purpose.BEDROOM && room.level == 1 && room.v0 < q) colour else color)
            if (room.depth >= 5) bed(room, foot, cv + 1, toward, color)
            chest(room, foot - toward.dx, cv, toward.opposite, Loot.HOUSE)
            if (place(room, head, cv - 1, TABLE)) put(head, y + 1, cv - 1, B(M.CANDLE))
            place(room, head, room.v0, B(M.BARREL, Dir.S))
            place(room, foot, room.v0, B(M.BARREL, Dir.S))
            rug(room, min(head, foot) - 0, cv + 1, max(head, foot), cv + 1, colour)
            if (room.depth >= 5) for (u in room.u0..room.u1) shelf(room, u, room.v1, 2)
        }

        /** The upstairs library: shelves along three walls and a reading table. */
        fun library(room: Room) {
            val y = base(room)
            for (u in room.u0..room.u1) for (v in listOf(room.v0, room.v1)) shelf(room, u, v, 3)
            for (v in room.v0..room.v1) shelf(room, room.u1, v, 3)
            val cu = (room.u0 + room.u1) / 2
            val cv = (room.v0 + room.v1) / 2
            place(room, cu, cv, TABLE)
            place(room, cu + 1, cv, TABLE)
            place(room, cu, cv - 1, chair(Dir.S))
            place(room, cu, cv + 1, chair(Dir.N))
            if (get(cu, y, cv)?.m == M.PLANK_SLAB) put(cu, y + 1, cv, B(M.CANDLE))
            rug(room, cu - 1, cv - 1, cu + 1, cv + 1, colour)
        }

        /** The store room: barrels and chests along the walls. */
        fun store(room: Room) {
            for (u in room.u0..room.u1) place(room, u, room.v1, B(M.BARREL, Dir.N))
            for (v in room.v0..room.v1) place(room, room.u0, v, B(M.BARREL, Dir.E))
            chest(room, room.u1, room.v0, Dir.S, Loot.HOUSE)
            place(room, room.u1, room.v0 + 1, B(M.COMPOSTER))
        }

        /** A lantern or two hung in a room. */
        fun lights(room: Room) {
            if (room.purpose == Purpose.HALL || room.purpose == Purpose.LANDING) return
            val cu = (room.u0 + room.u1) / 2
            val cv = (room.v0 + room.v1) / 2
            lamp(room, cu, cv)
            if (room.depth >= 7) {
                lamp(room, cu, room.v0 + 1)
                lamp(room, cu, room.v1 - 1)
            }
        }

        /** Who lives here besides the cook: a noble or two, now and then a child. */
        fun folk() {
            val spots = listOfNotNull(roomOf(0, Purpose.PARLOUR), roomOf(0, Purpose.STUDY), roomOf(0, Purpose.DINING))
            val noble = ArrayList<Pair<Room, Int>>()
            noble.add(spots[0] to 0)
            if (twoNobles) noble.add(spots[1] to 0)
            if (childRoll == 0) noble.add(spots[0] to 1)
            for ((i, entry) in noble.withIndex()) {
                val (room, skip) = entry
                val spot = middling(room).getOrNull(skip) ?: continue
                val kind = if (childRoll == 0 && i == noble.size - 1) Folk.CHILD else Folk.NOBLE
                val y = base(room)
                late.add { f.resident(kind, mu(spot.first), y, spot.second) }
                keep(room.level, spot.first, spot.second)
            }
        }

        // ---- Chimneys ----

        fun chimneys() {
            for (u in listOf(1, w - 2)) {
                val h = roofAt(u, q) ?: continue
                for (y in 0 until h + 4) put(u, y, q, STONE)
                put(u, h + 4, q, B(M.CAMPFIRE))
            }
        }
    }

    // ---- Pieces ----

    /** One side of a symmetrical building: [u] counts columns in from that side's edge. */
    private inner class Side(val left: Boolean) {
        fun u(k: Int): Int = if (left) k else w - 1 - k
        /** Out of the building on this side, and back in. */
        val out: Dir = if (left) Dir.W else Dir.E
        val inward: Dir = if (left) Dir.E else Dir.W
    }

    private val sides = listOf(Side(true), Side(false))

    /** What the rooms of a manor are for. */
    private enum class Purpose { HALL, LANDING, DINING, KITCHEN, STUDY, PARLOUR, BEDROOM, LIBRARY, STORE }

    /** A room of a manor: its floor, what it's for, and its inside from corner to corner. */
    private class Room(val level: Int, val purpose: Purpose, val u0: Int, val v0: Int, val u1: Int, val v1: Int) {
        val width: Int get() = u1 - u0 + 1
        val depth: Int get() = v1 - v0 + 1

        fun has(u: Int, v: Int): Boolean = u in u0..u1 && v in v0..v1
    }

    /** Only what falls in the lot is drawn. */
    private fun set(u: Int, y: Int, v: Int, block: B) {
        if (u in 0 until w && v in 0 until d) f.set(u, y, v, block)
    }

    /** A seat looking toward [looks]. */
    private fun chair(looks: Dir): B = B(M.PLANK_STAIRS, looks.opposite)

    /** A box of [block] from corner to corner; nothing at all if it's empty (an end before its start). */
    private fun fill(u0: Int, y0: Int, v0: Int, u1: Int, y1: Int, v1: Int, block: B) {
        for (u in u0..u1) for (v in v0..v1) for (y in y0..y1) set(u, y, v, block)
    }

    /** A pinnacle: a block of trim and a slender stone point on it. */
    private fun pinnacle(u: Int, y: Int, v: Int) {
        set(u, y, v, TRIM)
        set(u, y + 1, v, B(M.WALL_POST))
        set(u, y + 2, v, B(M.WALL_POST))
    }

    /** How far a pointed vault rises over its springing, [k] columns in from its wall. */
    private fun rise(k: Int): Int = if (k <= 0) 0 else k + 1

    /**
     * A pointed opening [n] wide in a wall, open from [y0] to its [apex]; [put] places a block at (i, y), i
     * counting across the opening in direction [along]. The head steps in with upside-down stairs; what's
     * open is put as air, for the caller to fill as it likes.
     */
    private fun opening(n: Int, y0: Int, apex: Int, along: Dir, put: (Int, Int, B) -> Unit) {
        if (n <= 0) return
        val centre = (n - 1) / 2.0
        for (i in 0 until n) {
            val off = abs(i - centre)
            val head = apex - floor(off).toInt()
            for (y in y0 until head) put(i, y, AIR)
            put(i, head, if (off >= 0.5) B(M.WALL_STAIRS, if (i < centre) along.opposite else along, top = true) else AIR)
        }
    }

    /**
     * A round window of stained glass in a wall, [radius] about ([c], [cy]): a golden heart in a ring of red,
     * petals of blue and purple round it, all framed in pale stone. [put] places a block at (column, y).
     */
    private fun rose(c: Double, cy: Int, radius: Double, put: (Int, Int, B) -> Unit) {
        val reach = (radius + 1.5).toInt()
        for (i in floor(c - reach).toInt()..(c + reach).toInt()) for (y in cy - reach..cy + reach) {
            val dx = i - c
            val dy = (y - cy).toDouble()
            val dist = sqrt(dx * dx + dy * dy)
            val petal = Math.floorMod(floor(atan2(dy, dx) / (Math.PI / 4) + 0.5).toInt(), 2)
            val block = when {
                dist <= 0.8 -> B(M.STAINED, color = Dye.YELLOW)
                dist <= 1.7 -> B(M.STAINED, color = Dye.RED)
                dist <= radius + 0.5 -> B(M.STAINED, color = if (petal == 0) Dye.BLUE else Dye.PURPLE)
                dist <= radius + 1.4 -> FINE
                else -> continue
            }
            put(i, y, block)
        }
    }

    /**
     * An eight-sided spire round ([cu], [cv]) (halves for an even width), [radius] out at its foot on [y] and
     * tapering to a point [height] high (six times the radius, unless said otherwise): solid slate (or stone)
     * stepped in with stairs as it narrows, and a finial on top (unless [finial] is false, for a needle of its own to rise from it).
     */
    private fun spire(
        cu: Double, cv: Double, radius: Double, y: Int, stone: Boolean = false,
        height: Int = (radius * 6).roundToInt() + 1, finial: Boolean = true,
    ) {
        val block = if (stone) WALL else B(M.ROOF, v = SLATE)
        fun stairs(dir: Dir) = if (stone) B(M.WALL_STAIRS, dir) else B(M.ROOF_STAIRS, dir, v = SLATE)
        fun inside(du: Double, dv: Double, rad: Double): Boolean =
            abs(du) <= rad + 0.01 && abs(dv) <= rad + 0.01 && abs(du) + abs(dv) <= rad * 1.45 + 0.01
        val reach = radius.toInt() + 2
        val u0 = floor(cu).toInt() - reach
        val v0 = floor(cv).toInt() - reach
        for (k in 0 until height) {
            val rk = radius * (1 - k.toDouble() / height)
            val next = radius * (1 - (k + 1).toDouble() / height)
            for (u in u0..u0 + 2 * reach + 1) for (v in v0..v0 + 2 * reach + 1) {
                val du = u - cu
                val dv = v - cv
                if (!inside(du, dv, rk)) continue
                if (inside(du, dv, next)) set(u, y + k, v, block)
                else set(u, y + k, v, stairs(if (abs(du) >= abs(dv)) (if (du > 0) Dir.W else Dir.E) else (if (dv > 0) Dir.N else Dir.S)))
            }
        }
        if (!finial) return
        val fu = cu.roundToInt()
        val fv = cv.roundToInt()
        set(fu, y + height, fv, B(M.WALL_POST))
        set(fu, y + height + 1, fv, B(M.FENCE))
        set(fu, y + height + 2, fv, B(M.FENCE))
    }

    /**
     * Roofing over every column [top] gives a height for: a stair on top facing up the slope, or a block with
     * a slab on it where nothing round about stands higher (along a ridge, on a peak), and roofing under it.
     */
    private fun roof(palette: Int, top: (Int, Int) -> Int?) {
        for (u in 0 until w) for (v in 0 until d) {
            val h = top(u, v) ?: continue
            var up: Dir? = null
            var high = h
            for (n in Dir.entries) {
                val nh = top(u + n.dx, v + n.dz) ?: continue
                if (nh > high) {
                    high = nh
                    up = n
                }
            }
            if (up != null) set(u, h, v, B(M.ROOF_STAIRS, up, v = palette))
            else {
                set(u, h, v, B(M.ROOF, v = palette))
                set(u, h + 1, v, B(M.ROOF_SLAB, v = palette))
            }
            for (y in h - 3 until h) set(u, y, v, B(M.ROOF, v = palette))
        }
    }

    /** A chandelier: a chain down from the vault at [ceiling] to a cross of lanterns hanging at [y]. */
    private fun chandelier(u: Int, v: Int, ceiling: Int, y: Int) {
        for (k in y + 2 until ceiling) set(u, k, v, B(M.CHAIN))
        set(u, y + 1, v, B(M.FENCE))
        set(u, y, v, B(M.LANTERN, top = true))
        for (n in Dir.entries) {
            set(u + n.dx, y + 1, v + n.dz, B(M.FENCE))
            set(u + n.dx, y, v + n.dz, B(M.LANTERN, top = true))
        }
    }

    companion object {
        const val SLATE = 0
        const val RED = 1
        const val WOOD = 2
        /** Floor to floor in a manor. */
        const val MANOR_STOREY = 5
        /** How high a manor's buffer goes: its tallest ridge and chimneys with room to spare. */
        const val LAYERS = 40
        /** How a manor's upper walls are made: timber and plaster over stone, dressed stone throughout, or plaster banded in stone. */
        const val TIMBERED = 0
        const val STONEWORK = 1
        const val PLASTERED = 2
        /** The colours the noble families hang over their doors and sleep under. */
        val HOUSE_COLORS = listOf(Dye.RED, Dye.GREEN, Dye.PURPLE, Dye.YELLOW, Dye.CYAN, Dye.ORANGE)
        /** The palace's corner towers' radius. */
        const val TOWER = 3.5
        /** How many floors the palace's keep has over its first floor, and how far apart they are, by size. */
        val KEEP_FLOORS = intArrayOf(2, 2, 3)
        val KEEP_STOREY = intArrayOf(6, 7, 6)
        val BANNER = CityBuilder.BANNER_COLOR
        /** How steeply the cathedral's roofs climb: blocks up for each block in. */
        const val PITCH = 2
        /** How far above the floor a lantern hangs in the cathedral's tower rooms. */
        const val TOWER_LAMP = 6
        /** The colours hung in the cathedral, one to a cathedral. */
        val BANNER_COLORS = listOf(Dye.RED, Dye.PURPLE, Dye.WHITE, Dye.BLUE)
        /** Where the buttresses stand round the apse, in degrees from across the front. */
        val APSE_PIERS = listOf(30.0, 60.0, 90.0, 120.0, 150.0)

        val AIR = B.AIR
        val WALL = B(M.WALL)
        val TRIM = B(M.WALL_TRIM)
        val DARK = B(M.WALL_DARK)
        val STONE = B(M.STONE)
        val FINE = B(M.FINE)
        val PILLAR = B(M.FINE_PILLAR)
        val PLASTER = B(M.PLASTER)
        val WINDOW = B(M.WINDOW)
        val TABLE = B(M.PLANK_SLAB, top = true)
        val FLOOR = B(M.PLANKS, v = 1)

        /** The palace's glass: the city's blue with gold, or with red, or with purple. */
        val PALACE_GLASS = listOf(
            listOf(Dye.BLUE, Dye.YELLOW, Dye.BLUE, Dye.LIGHT_BLUE, Dye.BLUE, Dye.YELLOW),
            listOf(Dye.BLUE, Dye.RED, Dye.BLUE, Dye.YELLOW, Dye.BLUE, Dye.RED),
            listOf(Dye.PURPLE, Dye.BLUE, Dye.YELLOW, Dye.PURPLE, Dye.BLUE, Dye.YELLOW),
        )

        /** The cathedral's glass: deep blues and reds, or warmer, or cooler. */
        val GLASS_SETS = listOf(
            listOf(Dye.BLUE, Dye.RED, Dye.BLUE, Dye.YELLOW, Dye.BLUE, Dye.PURPLE),
            listOf(Dye.RED, Dye.ORANGE, Dye.YELLOW, Dye.RED, Dye.PURPLE),
            listOf(Dye.LIGHT_BLUE, Dye.BLUE, Dye.CYAN, Dye.WHITE, Dye.BLUE),
        )
    }
}
