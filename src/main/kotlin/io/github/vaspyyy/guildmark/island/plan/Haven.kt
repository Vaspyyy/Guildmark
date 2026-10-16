package io.github.vaspyyy.guildmark.island.plan

import io.github.vaspyyy.guildmark.city.plan.FarmBuilding
import io.github.vaspyyy.guildmark.city.plan.FarmBuildings
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.FarmProp
import io.github.vaspyyy.guildmark.city.plan.FarmPropKind
import io.github.vaspyyy.guildmark.city.plan.FarmProps
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Ships
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A fishing hamlet round a sheltered bay. Its cottages stand on the slopes above the beach looking out over the water,
 * paths wind down between them to a green at the bay's head where the hamlet's bell hangs (its folk meet there, and a
 * quest board goes up by it), and from the beach below a pier runs out into the bay with boats tied up along it and
 * creels stacked at its end. More boats are drawn up on the sand, and beside the pier's foot a smokehouse puffs away
 * with nets hung out to dry.
 */
class Haven(plan: IslandPlan) : Landmark(plan) {
    private val dice = Dice(plan.spec.seed xor 0x4A7E5L)
    private val seed = Noise.seed(plan.spec.seed, 65)
    private val style = IslandStyle(plan.climate)

    /** A building's lot, the way its front looks, and the height its floor's laid at (the first block of air over it). */
    private class Lot(val rect: Rect, val front: Dir, val ground: Int, val seed: Long)

    private val homes = ArrayList<Lot>()

    /** The smokehouse on the beach beside the pier's foot. */
    private var shed: Lot? = null

    /** The pier's foot on the beach, and the way it runs out over the bay. */
    private var bx = 0
    private var bz = 0
    private var out = Dir.N

    /** The pier: its first column out over the water and how far it runs (none if 0). */
    private var px = 0
    private var pz = 0
    private var length = 0

    /** The green where the bell hangs, and the height of its ground over the sea. */
    private var gx = 0
    private var gz = 0
    private var green = 0

    /** The paths from the green to each door and down to the pier. */
    private val paths = ArrayList<Way>()

    /** Boats drawn up on the beach: where each lies, and the way its bow points. */
    private val beached = ArrayList<Pair<Rect, Dir>>()

    /**
     * The bay's sheltered water: the water round which most of the horizon is land. For every column of the island's
     * grid, how many steps it is to the nearest of that water, and which column of it that is.
     */
    private inner class Bay {
        val size = terrain.size
        val water = BooleanArray(size * size)
        val steps = IntArray(size * size) { Int.MAX_VALUE }
        val near = IntArray(size * size) { -1 }
        var cells = 0
        var x0 = Int.MAX_VALUE
        var z0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var z1 = Int.MIN_VALUE
        var midX = 0.0
        var midZ = 0.0

        fun index(x: Int, z: Int): Int = (x - terrain.x0) * size + (z - terrain.z0)

        fun has(x: Int, z: Int): Boolean = terrain.inside(x, z) && water[index(x, z)]

        fun steps(x: Int, z: Int): Int = if (terrain.inside(x, z)) steps[index(x, z)] else Int.MAX_VALUE

        /** The way from [x], [z] to the bay's nearest water; null if it's in the water or nowhere near it. */
        fun toward(x: Int, z: Int): Dir? {
            val n = if (terrain.inside(x, z)) near[index(x, z)] else -1
            if (n < 0) return null
            val nx = n / size + terrain.x0
            val nz = n % size + terrain.z0
            if (nx == x && nz == z) return null
            return Dir.nearest(atan2((nz - z).toDouble(), (nx - x).toDouble()))
        }
    }

    override fun lay() {
        val bay = findBay() ?: return
        layPier(bay)
        layGreen()
        layShed(bay)
        layHomes(bay)
        layBoats()
        layPaths()
    }

    /**
     * The water of the bay: every column of sea near the land with land on at least [enough] of sixteen ways round it
     * (a straight shore has it on half), keeping only the largest body of it. Null if the island has no such bay.
     */
    private fun findBay(): Bay? {
        val t = terrain
        val spec = plan.spec
        val reach = (spec.radius * 1.2).toInt()
        val hits = HashMap<Int, Int>()
        val rays = Array(16) { k -> Pair(cos(k * Math.PI / 8), sin(k * Math.PI / 8)) }
        val bay = Bay()
        for (x in spec.cx - reach..spec.cx + reach) for (z in spec.cz - reach..spec.cz + reach) {
            if (!t.inside(x, z) || t.dry(x, z) || t.distance(x, z) < -16) continue
            if (hypot((x - spec.cx).toDouble(), (z - spec.cz).toDouble()) > reach) continue
            var n = 0
            for ((dx, dz) in rays) {
                for (s in 1..26) if (t.dry((x + dx * s).roundToInt(), (z + dz * s).roundToInt())) {
                    n++
                    break
                }
            }
            hits[bay.index(x, z)] = n
        }
        for (enough in intArrayOf(10, 9)) {
            // The largest body of water sheltered that well
            val seen = HashSet<Int>()
            var best = emptyList<Int>()
            for ((i, n) in hits) {
                if (n < enough || i in seen) continue
                val body = ArrayList<Int>()
                val queue = ArrayDeque<Int>()
                queue.add(i)
                seen.add(i)
                while (queue.isNotEmpty()) {
                    val k = queue.removeFirst()
                    body.add(k)
                    for (o in intArrayOf(bay.size, -bay.size, 1, -1)) {
                        val j = k + o
                        if (j !in seen && (hits[j] ?: 0) >= enough && (o == bay.size || o == -bay.size || j / bay.size == k / bay.size)) {
                            seen.add(j)
                            queue.add(j)
                        }
                    }
                }
                if (body.size > best.size) best = body
            }
            if (best.size < 30) continue
            for (k in best) {
                bay.water[k] = true
                val x = k / bay.size + t.x0
                val z = k % bay.size + t.z0
                bay.x0 = min(bay.x0, x)
                bay.z0 = min(bay.z0, z)
                bay.x1 = max(bay.x1, x)
                bay.z1 = max(bay.z1, z)
                bay.midX += x
                bay.midZ += z
            }
            bay.cells = best.size
            bay.midX /= best.size
            bay.midZ /= best.size
            // How far every column is from it, and which of its water is nearest
            val queue = ArrayDeque<Int>()
            for (k in best) {
                bay.steps[k] = 0
                bay.near[k] = k
                queue.add(k)
            }
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                val i = k / bay.size
                val j = k % bay.size
                for (di in -1..1) for (dj in -1..1) {
                    val ni = i + di
                    val nj = j + dj
                    if (ni !in 0 until bay.size || nj !in 0 until bay.size) continue
                    val n = ni * bay.size + nj
                    if (bay.steps[n] != Int.MAX_VALUE) continue
                    bay.steps[n] = bay.steps[k] + 1
                    bay.near[n] = bay.near[k]
                    queue.add(n)
                }
            }
            return bay
        }
        return null
    }

    /**
     * The pier: from whichever spot on the bay's beach a straight run of it reaches furthest out over open water,
     * best from near the bay's head and out toward its middle.
     */
    private fun layPier(bay: Bay) {
        val t = terrain
        val want = dice.int(9, 15)
        // The bay's head: its beach nearest the island's middle
        var hx = bay.midX.roundToInt()
        var hz = bay.midZ.roundToInt()
        var hd = Double.MAX_VALUE
        for (x in bay.x0 - 1..bay.x1 + 1) for (z in bay.z0 - 1..bay.z1 + 1) {
            if (!t.dry(x, z) || bay.steps(x, z) != 1) continue
            val d = hypot((x - plan.spec.cx).toDouble(), (z - plan.spec.cz).toDouble())
            if (d < hd) {
                hd = d
                hx = x
                hz = z
            }
        }
        var best = -Double.MAX_VALUE
        for (x in bay.x0 - 1..bay.x1 + 1) for (z in bay.z0 - 1..bay.z1 + 1) {
            val h = t.h(x, z).roundToInt()
            if (h !in 1..2 || plan.claimed(x, z)) continue
            for (d in Dir.entries) {
                if (!bay.has(x + d.dx, z + d.dz)) continue
                val run = pierRun(x, z, d, want)
                if (run == 0) continue
                val ex = x + d.dx * run
                val ez = z + d.dz * run
                val toMid = hypot(bay.midX - x, bay.midZ - z)
                val into = if (toMid < 1) 0.0 else (d.dx * (bay.midX - x) + d.dz * (bay.midZ - z)) / toMid
                val score = run + 0.8 * min(4.0, -t.h(ex, ez)) + 4.0 * into - 0.2 * hypot((x - hx).toDouble(), (z - hz).toDouble()) -
                    1.5 * (h - 1) + Noise.unit(x, 7, z, seed)
                if (score > best) {
                    best = score
                    bx = x
                    bz = z
                    out = d
                    length = run
                }
            }
        }
        if (length == 0) {
            // No run long enough: the hamlet still stands at the bay's head, looking out over it
            bx = hx
            bz = hz
            out = bay.toward(hx, hz) ?: Dir.N
            return
        }
        px = bx + out.dx
        pz = bz + out.dz
        val ex = px + out.dx * (length - 1)
        val ez = pz + out.dz * (length - 1)
        plan.claim(min(bx, ex) - 4, min(bz, ez) - 4, max(bx, ex) + 4, max(bz, ez) + 4)
    }

    /**
     * How far a pier from the beach at [fx], [fz] can run out [d] (up to [want]) with water under its deck and a
     * boat's width of it either side, and more round its wider head; 0 if not five blocks.
     */
    private fun pierRun(fx: Int, fz: Int, d: Dir, want: Int): Int {
        val across = d.cw
        fun wet(i: Int, c: Int): Boolean = !terrain.dry(fx + d.dx * (i + 1) + across.dx * c, fz + d.dz * (i + 1) + across.dz * c)
        var n = 0
        while (n < want && (-1..2).all { c -> wet(n, c) }) n++
        while (n >= 5 && !(n - 3 until n).all { i -> (-2..3).all { c -> wet(i, c) } }) n--
        return if (n >= 5) n else 0
    }

    /** The green a little up from the pier's foot: the levellest patch of ground a few steps inland. */
    private fun layGreen() {
        val t = terrain
        gx = bx - out.dx * 5
        gz = bz - out.dz * 5
        var best = Double.MAX_VALUE
        for (dx in -11..11) for (dz in -11..11) {
            val back = -(dx * out.dx + dz * out.dz)
            if (back < 3 || hypot(dx.toDouble(), dz.toDouble()) > 11) continue
            val x = bx + dx
            val z = bz + dz
            if (!t.dry(x, z) || t.distance(x, z) < 3 || plan.claimed(x, z)) continue
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var ok = true
            for (ox in -3..3) for (oz in -3..3) {
                if (!t.dry(x + ox, z + oz)) ok = false
                val h = t.h(x + ox, z + oz)
                lo = min(lo, h)
                hi = max(hi, h)
            }
            if (!ok) continue
            val score = hypot((dx + out.dx * 6).toDouble(), (dz + out.dz * 6).toDouble()) + 2.5 * (hi - lo)
            if (score < best) {
                best = score
                gx = x
                gz = z
            }
        }
        green = max(2, t.h(gx, gz).roundToInt())
        t.flatten(gx - 3, gz - 3, gx + 3, gz + 3, green, 2)
        plan.claim(gx - 4, gz - 4, gx + 4, gz + 4)
    }

    /** The smokehouse by the water near the pier's foot, its door to the bay. */
    private fun layShed(bay: Bay) {
        val t = terrain
        var best = Double.MAX_VALUE
        var pick: Pair<Rect, Dir>? = null
        for (dx in -14..14) for (dz in -14..14) {
            val d = hypot(dx.toDouble(), dz.toDouble())
            if (d < 4 || d > 14) continue
            val x = bx + dx
            val z = bz + dz
            if (!t.dry(x, z) || bay.steps(x, z) > 6 || plan.claimed(x, z)) continue
            val front = bay.toward(x, z) ?: continue
            val r = lotAt(x, z, 5, 4, front)
            if (!fits(r, 1, 2.0)) continue
            val score = d + 2.0 * spread(r)
            if (score < best) {
                best = score
                pick = Pair(r, front)
            }
        }
        val (r, front) = pick ?: return
        val level = max(2, averageHeight(r).roundToInt())
        t.flatten(r.x0, r.z0, r.x1, r.z1, level, 2)
        shed = Lot(r, front, sea + level, plan.spec.seed xor 0x5ED)
        plan.claim(r.x0 - 2, r.z0 - 2, r.x1 + 2, r.z1 + 2)
    }

    /** The cottages: on the slopes round the bay, each looking out over the nearest of its water. */
    private fun layHomes(bay: Bay) {
        val t = terrain
        val want = dice.int(4, 6)
        class Spot(val x: Int, val z: Int, val score: Double)
        val spots = ArrayList<Spot>()
        for (x in bay.x0 - 18..bay.x1 + 18 step 2) for (z in bay.z0 - 18..bay.z1 + 18 step 2) {
            if (!t.dry(x, z) || t.distance(x, z) < 3 || t.h(x, z) > 14 || plan.claimed(x, z)) continue
            val steps = bay.steps(x, z)
            if (steps !in 3..16) continue
            spots.add(Spot(x, z, 0.5 * steps + 0.35 * hypot((x - bx).toDouble(), (z - bz).toDouble()) + 5.0 * Noise.unit(x, 4, z, seed)))
        }
        spots.sortBy { it.score }
        val taken = ArrayList<Rect>()
        shed?.let { taken.add(it.rect.grow(2)) }
        taken.add(Rect(gx - 4, gz - 4, gx + 4, gz + 4))
        for (s in spots) {
            if (homes.size >= want) break
            val front = bay.toward(s.x, s.z) ?: continue
            val w = 6 + Math.floorMod(Noise.hash(s.x, 5, s.z, seed), 3)
            val d = 6 + Math.floorMod(Noise.hash(s.x, 6, s.z, seed), 3)
            val r = lotAt(s.x, s.z, w, d, front)
            if (taken.any { it.intersects(r.grow(2)) } || !fits(r, 2, 4.5)) continue
            // The door must open onto dry ground
            val door = doorOf(r, front)
            if (!t.dry(door.first, door.second)) continue
            homes.add(Lot(r, front, sea + max(2, averageHeight(r).roundToInt()), plan.spec.seed xor (s.x.toLong() shl 20) xor s.z.toLong()))
            taken.add(r.grow(1))
        }
        for (h in homes) {
            val r = h.rect
            t.flatten(r.x0 - 1, r.z0 - 1, r.x1 + 1, r.z1 + 1, h.ground - sea, 3)
            plan.claim(r.x0 - 2, r.z0 - 2, r.x1 + 2, r.z1 + 2)
        }
    }

    /** One to three boats drawn up on the beach near the pier's foot, bows up the sand. */
    private fun layBoats() {
        val t = terrain
        val want = dice.int(1, 3)
        class Spot(val x: Int, val z: Int, val score: Double)
        val spots = ArrayList<Spot>()
        for (dx in -22..22) for (dz in -22..22) {
            val d = hypot(dx.toDouble(), dz.toDouble())
            val x = bx + dx
            val z = bz + dz
            if (d < 3 || d > 22 || !t.dry(x, z) || t.h(x, z) > 3.4 || t.distance(x, z) !in 1.5..5.5) continue
            spots.add(Spot(x, z, d + 3.0 * Noise.unit(x, 8, z, seed)))
        }
        spots.sortBy { it.score }
        for (s in spots) {
            if (beached.size >= want) break
            // Bow up the beach: the way the land runs away from the water
            val bow = Dir.entries.maxBy { t.distance(s.x + it.dx * 2, s.z + it.dz * 2) }
            val r = lotAt(s.x, s.z, 3, 5, bow)
            if (!fits(r, 0, 2.6) || beached.any { it.first.grow(1).intersects(r) } || shed?.rect?.grow(1)?.intersects(r) == true) continue
            beached.add(Pair(r, bow))
            plan.claim(r.x0 - 1, r.z0 - 1, r.x1 + 1, r.z1 + 1)
        }
    }

    /** Paths from the green to each cottage's door and down to the pier's foot, cut and stepped where they climb. */
    private fun layPaths() {
        val lots = homes.map { it.rect } + listOfNotNull(shed?.rect) + beached.map { it.first }
        val keep = { x: Int, z: Int -> lots.any { it.grow(1).contains(x, z) } || (abs(x - gx) <= 3 && abs(z - gz) <= 3) }
        val blocked = { x: Int, z: Int -> lots.any { it.contains(x, z) } }
        val ways = Ways(Pair(gx, gz), green, seed, blocked)
        for (h in homes) {
            val door = doorOf(h.rect, h.front)
            val w = ways.to(door.first, door.second)
            if (w.isEmpty()) continue
            paths.add(w)
        }
        if (length > 0) {
            val w = ways.to(bx, bz)
            if (!w.isEmpty()) paths.add(w)
        }
        for (w in paths) {
            shape(w, 0, keep)
            for ((x, z) in w.cells) plan.claim(x, z, x, z)
        }
    }

    override fun paint() {
        for (w in paths) for ((x, z) in w.cells) if (plan.groundAt(x, z) != Ground.SAND) plan.paint(x, z, Ground.PATH)
        for (x in gx - 3..gx + 3) for (z in gz - 3..gz + 3) {
            if (abs(x - gx) + abs(z - gz) <= 1) plan.paint(x, z, Ground.PATH)
            else if (plan.groundAt(x, z) != Ground.PATH) plan.paint(x, z, Ground.GRASS)
        }
    }

    override fun build(b: Build) {
        if (green == 0) return
        val bridge = CityBridge(b, style, seed) { LOOT_HOME }
        for (h in homes) {
            val front = CityBridge.city(h.front)
            val house = FarmBuilding(FarmKind.COTTAGE, h.rect, front, h.ground, h.seed)
            FarmBuildings(Frame(bridge, h.rect, front, h.ground), house, Random(house.seed)).draw()
        }
        val props = FarmProps(bridge, plan.spec.seed)
        homeProps(props)
        pier(b)
        smokehouse(b)
        boats(bridge)
        steps(b)
        bell(b, props)
        lamps(props)
        folk(b)
    }

    // ---- the pier

    /** Planks on posts out over the bay, a wider head with creels and the day's catch, lamps, and boats tied up. */
    private fun pier(b: Build) {
        if (length == 0) return
        val across = out.cw
        val planks = Bk.of("${style.wood}_planks")
        val log = Bk.of("${style.wood}_log", "axis" to "y")
        // At its foot on the beach, planks level with its deck, or a step down to it where the sand stands higher
        for (c in 0..1) {
            val x = bx + across.dx * c
            val z = bz + across.dz * c
            val top = terrain.top(x, z)
            if (top <= sea + 1) b.set(x, sea, z, planks)
            else b.set(x, top - 1, z, Bk.of("${style.wood}_stairs", "facing" to out.opposite.id, "half" to "bottom"))
        }
        for (i in 0 until length) {
            val head = i >= length - 3
            val cx = px + out.dx * i
            val cz = pz + out.dz * i
            for (c in (if (head) -2..3 else 0..1)) {
                val x1 = cx + across.dx * c
                val z1 = cz + across.dz * c
                b.set(x1, sea, z1, planks)
                b.air(x1, sea + 1, z1)
                b.air(x1, sea + 2, z1)
            }
            // Posts down to the bed every third plank along the sides, standing up a block as bollards
            val edges = if (head) listOf(-2, 3) else listOf(0, 1)
            if (i % 3 == 0 || i == length - 1) for (c in edges) {
                val x1 = cx + across.dx * c
                val z1 = cz + across.dz * c
                for (y in max(terrain.top(x1, z1), sea - 16) until sea) b.set(x1, y, z1, log)
                b.set(x1, sea, z1, log)
                if (i % 6 == 0 || i == length - 1) b.set(x1, sea + 1, z1, Bk.of("${style.wood}_fence"))
            }
        }
        // Lamps at the head's corners
        val ex = px + out.dx * (length - 1)
        val ez = pz + out.dz * (length - 1)
        for (c in listOf(-2, 3)) {
            val x1 = ex + across.dx * c
            val z1 = ez + across.dz * c
            b.set(x1, sea + 1, z1, Bk.of("${style.wood}_fence"))
            b.set(x1, sea + 2, z1, Bk.of("${style.wood}_fence"))
            b.set(x1, sea + 3, z1, Bk.of("lantern", "hanging" to "false"))
        }
        // Creels stacked on the head, the catch in barrels (where the fisherfolk work), a coil of chain
        val hx = ex - out.dx
        val hz = ez - out.dz
        b.set(hx + across.dx * -1, sea + 1, hz + across.dz * -1, Bk.of("composter", "level" to "0"))
        b.set(hx + across.dx * -1, sea + 2, hz + across.dz * -1, Bk.of("composter", "level" to "0"))
        b.set(ex + across.dx * -1, sea + 1, ez + across.dz * -1, Bk.of("composter", "level" to "0"))
        b.chest(hx + across.dx * 2, sea + 1, hz + across.dz * 2, across.opposite, Lighthouse.LOOT_CATCH, barrel = true)
        b.set(ex + across.dx * 2, sea + 1, ez + across.dz * 2, Bk.of("barrel", "facing" to "up", "open" to "false"))
        // Boats tied up along the pier, knocking together in the swell, and the head's ropes creaking
        for ((n, i) in listOf(length / 3, length * 2 / 3).withIndex()) {
            val c = if (n == 0) -1 else 2
            val x1 = px + out.dx * i + across.dx * c
            val z1 = pz + out.dz * i + across.dz * c
            if (!terrain.dry(x1, z1)) b.spawn("minecraft:${style.wood}_boat", x1 + 0.5, sea - 0.6, z1 + 0.5, CityBridge.yaw(out))
        }
        b.sound(SoundSpot.MOORING, px + out.dx * (length / 2), sea, pz + out.dz * (length / 2))
        b.sound(SoundSpot.PIER, ex, sea + 1, ez)
    }

    // ---- the smokehouse

    /**
     * A plank smokehouse on a stone footing, its roof pitched in stairs, a smoking chimney and a smoker inside with
     * barrels for the fisherfolk; outside, a net hung out to dry on a frame and creels piled by the door.
     */
    private fun smokehouse(b: Build) {
        val s = shed ?: return
        val r = s.rect
        val y = s.ground
        val front = s.front
        val across = front.cw
        val planks = Bk.of("${style.wood}_planks")
        val log = Bk.of("${style.wood}_log", "axis" to "y")
        // The shed's own frame: u across its front, v from its front back
        val w = if (front.alongX) r.depth else r.width
        val d = if (front.alongX) r.width else r.depth
        fun at(u: Int, v: Int): Pair<Int, Int> = when (front) {
            Dir.N -> Pair(r.x0 + u, r.z0 + v)
            Dir.S -> Pair(r.x1 - u, r.z1 - v)
            Dir.E -> Pair(r.x1 - v, r.z0 + u)
            Dir.W -> Pair(r.x0 + v, r.z1 - u)
        }
        val door = w / 2
        for (u in 0 until w) for (v in 0 until d) {
            val (x, z) = at(u, v)
            val ring = u == 0 || u == w - 1 || v == 0 || v == d - 1
            val corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1)
            b.set(x, y - 1, z, if (ring) Bk.of("cobblestone") else planks)
            for (k in 0..2) when {
                corner -> b.set(x, y + k, z, log)
                ring && v == 0 && u == door && k < 2 -> b.air(x, y + k, z)
                ring && k == 1 && (u == 1 || u == w - 2) && v == 0 -> b.set(x, y + k, z, Bk.of("${style.wood}_trapdoor", "facing" to front.id, "half" to "bottom", "open" to "true"))
                ring -> b.set(x, y + k, z, planks)
                else -> b.air(x, y + k, z)
            }
        }
        // The door, and the roof: stairs sloping down to front and back from a ridge along the middle
        val (dx0, dz0) = at(door, 0)
        b.set(dx0, y, dz0, Bk.of("${style.wood}_door", "facing" to front.opposite.id, "half" to "lower", "hinge" to "left", "open" to "false"))
        b.set(dx0, y + 1, dz0, Bk.of("${style.wood}_door", "facing" to front.opposite.id, "half" to "upper", "hinge" to "left", "open" to "false"))
        val roof = if (plan.climate == Climate.WARM || plan.climate == Climate.LUKEWARM) "bamboo_mosaic_stairs" else "${style.wood}_stairs"
        for (u in -1..w) for (v in -1..d) {
            val (x, z) = at(u, v)
            val rise = min(v + 1, d - v)
            val yy = y + 3 + rise - 1
            val face = if (v < d / 2) front else front.opposite
            if (d % 2 == 1 && v == d / 2) b.set(x, yy, z, Bk.of(if (roof.startsWith("bamboo")) "bamboo_mosaic" else "${style.wood}_planks"))
            else b.set(x, yy, z, Bk.of(roof, "facing" to face.opposite.id, "half" to "bottom"))
            // Fill the gables under the slopes
            if (u == 0 || u == w - 1) for (k in y + 3 until yy) if (v in 0 until d) b.set(x, k, z, planks)
        }
        // The smoker and the chimney over the fire, barrels and a table inside
        val (sx, sz) = at(w - 2, d - 2)
        b.set(sx, y, sz, Bk.of("smoker", "facing" to front.id, "lit" to "true"))
        val (cx, cz) = at(w - 2, d)
        val stack = 4 + (d + 1) / 2
        for (k in -1..stack) b.set(cx, y + k, cz, Bk.of(if (k > 1 && Noise.unit(cx, y + k, cz, seed) < 0.3) "mossy_cobblestone" else "cobblestone"))
        b.set(cx, y + stack + 1, cz, Bk.of("campfire", "lit" to "true", "signal_fire" to "false"))
        val (ax, az) = at(1, d - 2)
        b.chest(ax, y, az, front, Lighthouse.LOOT_CATCH, barrel = true)
        val (tx, tz) = at(1, 1)
        if (d > 3) b.set(tx, y, tz, Bk.of("barrel", "facing" to "up", "open" to "false"))
        // The net frame beside the shed: two posts and a rail, the net hung from it
        val (nx, nz) = at(-2, 1)
        for (k in 0..2) {
            b.set(nx, y + k, nz, Bk.of("${style.wood}_fence"))
            b.set(nx + front.opposite.dx * 3, y + k, nz + front.opposite.dz * 3, Bk.of("${style.wood}_fence"))
        }
        for (j in 1..2) {
            b.set(nx + front.opposite.dx * j, y + 2, nz + front.opposite.dz * j, Bk.of("${style.wood}_fence"))
            b.set(nx + front.opposite.dx * j, y + 1, nz + front.opposite.dz * j, Bk.of("cobweb"))
        }
        // Creels by the door
        val (kx, kz) = at(door + 1, -1)
        b.set(kx, y, kz, Bk.of("composter", "level" to "0"))
        b.set(kx + across.dx, y, kz + across.dz, Bk.of("composter", "level" to "0"))
    }

    // ---- the rest of the hamlet

    /** A woodpile or a compost heap at each cottage's side. */
    private fun homeProps(props: FarmProps) {
        for ((k, h) in homes.withIndex()) {
            val r = h.rect
            val side = h.front.cw
            val (x, z) = when (side) {
                Dir.E -> Pair(r.x1 + 1, (r.z0 + r.z1) / 2)
                Dir.W -> Pair(r.x0 - 1, (r.z0 + r.z1) / 2)
                Dir.S -> Pair((r.x0 + r.x1) / 2, r.z1 + 1)
                Dir.N -> Pair((r.x0 + r.x1) / 2, r.z0 - 1)
            }
            val kind = if (k % 2 == 0) FarmPropKind.WOODPILE else FarmPropKind.COMPOST
            props.draw(FarmProp(kind, x, z, h.ground, CityBridge.city(h.front)))
        }
    }

    /** The boats drawn up on the beach. */
    private fun boats(bridge: CityBridge) {
        for ((k, p) in beached.withIndex()) {
            val (r, bow) = p
            val y = terrain.top((r.x0 + r.x1) / 2, (r.z0 + r.z1) / 2)
            Ships(Frame(bridge, r, CityBridge.city(bow), y), Random(plan.spec.seed + k)).boat()
        }
    }

    /** Steps wherever a path climbs or drops, on the lower column, rising toward the higher. */
    private fun steps(b: Build) {
        val stone = when (plan.climate) {
            Climate.WARM, Climate.LUKEWARM -> "sandstone_stairs"
            Climate.COLD, Climate.FROZEN -> "cobblestone_stairs"
            Climate.TEMPERATE -> "stone_brick_stairs"
        }
        for (w in paths) {
            val cells = w.cells
            val h = w.heights
            for (k in 1 until w.size) {
                if (h[k] == h[k - 1]) continue
                val (low, high) = if (h[k] < h[k - 1]) Pair(k, k - 1) else Pair(k - 1, k)
                val (x, z) = cells[low]
                b.set(x, sea + h[low], z, Bk.of(stone, "facing" to dirTo(cells[low], cells[high]).id, "half" to "bottom"))
            }
        }
    }

    /** The hamlet's bell, hung in a frame of two posts and a beam on the green, a well beside it. */
    private fun bell(b: Build, props: FarmProps) {
        val y = sea + green
        val across = out.cw
        val log = "${style.wood}_log"
        for (c in listOf(-1, 1)) for (k in 0..2) b.set(gx + across.dx * c, y + k, gz + across.dz * c, Bk.of(log, "axis" to "y"))
        b.set(gx, y + 2, gz, Bk.of(log, "axis" to across.axis))
        b.set(gx, y + 1, gz, Bk.of("bell", "attachment" to "ceiling", "facing" to across.id))
        props.draw(FarmProp(FarmPropKind.WELL, gx - out.dx * 2 + across.dx * 2, gz - out.dz * 2 + across.dz * 2, y, CityBridge.city(out)))
        // A bench looking out over the bay
        for (c in listOf(-3, -2)) b.set(gx + across.dx * c + out.dx * 2, y, gz + across.dz * c + out.dz * 2, Bk.of("${style.wood}_stairs", "facing" to out.opposite.id, "half" to "bottom"))
    }

    /** Lamp posts along the paths, every so often, at the side of the way. */
    private fun lamps(props: FarmProps) {
        val placed = ArrayList<Pair<Int, Int>>()
        for (w in paths) {
            for (k in 4 until w.size - 2 step 7) {
                val (x, z) = w.cells[k]
                val along = dirTo(w.cells[k - 1], w.cells[k])
                // On whichever side the ground stands level with the path
                val side = listOf(along.cw, along.ccw).firstOrNull { abs(terrain.h(x + it.dx, z + it.dz) - w.heights[k]) < 0.5 } ?: continue
                val sx = x + side.dx
                val sz = z + side.dz
                if (placed.any { abs(it.first - sx) + abs(it.second - sz) < 5 }) continue
                if (homes.any { it.rect.grow(1).contains(sx, sz) } || paths.any { p -> p.cells.contains(Pair(sx, sz)) }) continue
                props.draw(FarmProp(FarmPropKind.LAMP, sx, sz, sea + w.heights[k], CityBridge.city(along)))
                placed.add(Pair(sx, sz))
            }
        }
    }

    /** Who's about besides the cottagers: a fisher at the pier's head, the hamlet's cats and hens on the green. */
    private fun folk(b: Build) {
        val y = sea + green
        if (length > 0) {
            val x = px + out.dx * (length - 2)
            val z = pz + out.dz * (length - 2)
            b.spawn("minecraft:villager", x + 0.5, sea + 1.0, z + 0.5, CityBridge.yaw(out))
        }
        b.spawn("minecraft:cat", gx + 2.5, y.toDouble(), gz - 1.5, 0f)
        for (k in 0 until 3) b.spawn("minecraft:chicken", gx - 2.5 + k, y.toDouble(), gz + 2.5, k * 120f)
    }

    // ---- helpers

    /** A [w] by [d] lot (across its front, front to back) round [x], [z], its front toward [front]. */
    private fun lotAt(x: Int, z: Int, w: Int, d: Int, front: Dir): Rect =
        if (front.alongX) Rect(x - d / 2, z - w / 2, x - d / 2 + d - 1, z - w / 2 + w - 1)
        else Rect(x - w / 2, z - d / 2, x - w / 2 + w - 1, z - d / 2 + d - 1)

    /** The column just outside the middle of [r]'s front. */
    private fun doorOf(r: Rect, front: Dir): Pair<Int, Int> = when (front) {
        Dir.N -> Pair((r.x0 + r.x1) / 2, r.z0 - 1)
        Dir.S -> Pair((r.x0 + r.x1) / 2, r.z1 + 1)
        Dir.E -> Pair(r.x1 + 1, (r.z0 + r.z1) / 2)
        Dir.W -> Pair(r.x0 - 1, (r.z0 + r.z1) / 2)
    }

    /** Whether every column of [r] is dry land at least [inland] from the water, unclaimed, its heights within [spread]. */
    private fun fits(r: Rect, inland: Int, spread: Double): Boolean {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            if (!terrain.dry(x, z) || terrain.distance(x, z) < inland || plan.claimed(x, z)) return false
            val h = terrain.h(x, z)
            lo = min(lo, h)
            hi = max(hi, h)
        }
        return hi - lo <= spread
    }

    private fun averageHeight(r: Rect): Double {
        var sum = 0.0
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) sum += terrain.h(x, z)
        return sum / ((r.x1 - r.x0 + 1) * (r.z1 - r.z0 + 1))
    }

    /** How far apart the lowest and highest ground in [r] stand. */
    private fun spread(r: Rect): Double {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            val h = terrain.h(x, z)
            lo = min(lo, h)
            hi = max(hi, h)
        }
        return hi - lo
    }

    companion object {
        const val LOOT_HOME = "guildmark:chests/island/haven"
    }
}
