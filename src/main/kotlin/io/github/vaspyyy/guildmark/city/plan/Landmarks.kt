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
            Kind.PALACE -> shell(20)
            Kind.MANOR -> shell(10)
            else -> {}
        }
    }

    private fun shell(height: Int) {
        for (u in 0 until w) for (v in 0 until d) {
            val edge = u == 0 || v == 0 || u == w - 1 || v == d - 1
            for (y in 0 until height) f.set(u, y, v, if (edge) B(M.STONE) else B.AIR)
            f.set(u, height, v, B(M.ROOF))
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

        /** A pinnacle: a block of trim and a slender stone point on it. */
        fun pinnacle(u: Int, y: Int, v: Int) {
            set(u, y, v, TRIM)
            set(u, y + 1, v, B(M.WALL_POST))
            set(u, y + 2, v, B(M.WALL_POST))
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
            // A ladder up a notch in the back corner on the inner side, lanterns under every floor and in the belfry
            val lu = if (Dir.W in faces && Dir.E !in faces) u1 - 1 else u0 + 1
            for (y in 0..belfry) set(lu, y, v1 - 1, B(M.LADDER, Dir.N))
            for (y in belfry + 1..belfry + 2) set(lu, y, v1 - 1, AIR)
            for (y in floors) set(bu, y - 1, bv, B(M.LANTERN, top = true))
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
            // Choir stalls facing each other, where there's a choir
            if (choirBays > 0) for (s in sides) for (v in c1 + 2 until apse - 1) set(s.u(pierL + 1), 0, v, B(M.PLANK_STAIRS, s.out))
            // Chandeliers down the nave, at the crossing and over the altar
            val hang = aisleTop - 4
            for (v in listOf(front + 1) + naveRows.map { it + bay / 2 } + listOf(crossMid, apse + 1) + choirRows.filter { it < apse }) {
                val h = vault(midL, v) ?: continue
                chandelier(midL, v, h, hang)
            }
            // Lanterns in the aisles, chandeliers in the transepts
            for (s in sides) {
                for ((a, z) in naveSpans + choirSpans) set(s.u(wallL + (aisle + 1) / 2), aisleTop - 3, (a + z) / 2, B(M.LANTERN, top = true))
                if (single) set(s.u(wallL + (aisle + 1) / 2), aisleTop - 3, front / 2, B(M.LANTERN, top = true))
                val tu = s.u((pierL + 1) / 2)
                vault(tu, crossMid)?.let { chandelier(tu, crossMid, it, hang) }
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

    /** Only what falls in the lot is drawn. */
    private fun set(u: Int, y: Int, v: Int, block: B) {
        if (u in 0 until w && v in 0 until d) f.set(u, y, v, block)
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
     * tapering to a point six times as high: solid slate (or stone) stepped in with stairs as it narrows, and
     * a finial on top.
     */
    private fun spire(cu: Double, cv: Double, radius: Double, y: Int, stone: Boolean = false) {
        val height = (radius * 6).roundToInt() + 1
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
        /** How steeply the cathedral's roofs climb: blocks up for each block in. */
        const val PITCH = 2
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

        /** The cathedral's glass: deep blues and reds, or warmer, or cooler. */
        val GLASS_SETS = listOf(
            listOf(Dye.BLUE, Dye.RED, Dye.BLUE, Dye.YELLOW, Dye.BLUE, Dye.PURPLE),
            listOf(Dye.RED, Dye.ORANGE, Dye.YELLOW, Dye.RED, Dye.PURPLE),
            listOf(Dye.LIGHT_BLUE, Dye.BLUE, Dye.CYAN, Dye.WHITE, Dye.BLUE),
        )
    }
}
