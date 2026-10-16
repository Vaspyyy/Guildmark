package io.github.vaspyyy.guildmark.island.plan

import io.github.vaspyyy.guildmark.city.plan.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rookery: sea stacks white with gulls, their nests packed along every top and ledge. Egg gatherers row out to it in
 * the season, so the broadest stack has a ladder pinned up its sheltered face from a little landing where they tie up,
 * a hoist rope beside it for the baskets, a rope bridge slung across to a neighbouring stack, and on its top a
 * turf-roofed stone store where the eggs are kept.
 */
class Rookery(plan: IslandPlan) : Landmark(plan) {
    private val seed = Noise.seed(plan.spec.seed, 64)
    private val style = IslandStyle(plan.climate)

    /** A nest at [x], [y], [z] (the block it sits in) holding [eggs], on a ledge of rock jutting from a cliff if [ledge]. */
    private class Nest(val x: Int, val y: Int, val z: Int, val eggs: Int, val ledge: Boolean)

    private val nests = ArrayList<Nest>()

    /** The gatherers' stack; the face their ladder climbs, the column it stands in and the heights it runs between. */
    private var home: Terrain.Stack? = null
    private var face = Dir.N
    private var lx = 0
    private var lz = 0
    private var ladderFrom = 0
    private var ladderTo = 0

    /** The rope bridge's deck columns, the way it runs, and its ends' standing heights. */
    private var span: List<Pair<Int, Int>> = emptyList()
    private var along = Dir.E
    private var standA = 0
    private var standB = 0

    /** The egg store on the home stack's top, its floor's height and the way its door looks; null for a cairn. */
    private var store: Rect? = null
    private var storeFloor = 0
    private var storeDoor = Dir.N

    /** The cairn the gatherers pile where there's no room for a store: its column and ground. */
    private var cairnX = 0
    private var cairnZ = 0

    override fun lay() {
        val t = terrain
        if (t.stacks.isEmpty()) return
        // The broadest stack tall enough to want a ladder
        val h = t.stacks.filter { it.height >= 9.0 }.maxByOrNull { it.radius } ?: t.stacks.maxBy { it.radius }
        home = h
        val hx = floor(plan.spec.cx + h.x).toInt()
        val hz = floor(plan.spec.cz + h.z).toInt()
        layLadder(h, hx, hz)
        layStore(h, hx, hz)
        layBridge(h, hx, hz)
        layNests()
    }

    /** The ladder on the face most out of the wind that drops sheer to the water, and the landing at its foot. */
    private fun layLadder(h: Terrain.Stack, hx: Int, hz: Int) {
        val t = terrain
        val lee = t.windFrom + PI
        for (d in Dir.entries.sortedBy { Lighthouse.gap(Lighthouse.heading(it), lee) }) {
            for (k in 1..12) {
                val qx = hx + d.dx * k
                val qz = hz + d.dz * k
                val px = qx + d.dx
                val pz = qz + d.dz
                if (t.h(px, pz) >= 1.5) continue
                // The rock behind the ladder must stand high all the way up
                if (t.h(qx, qz) < h.height * 0.7) break
                face = d
                lx = px
                lz = pz
                ladderFrom = sea + 1
                ladderTo = t.top(qx, qz) - 1
                // The ladder, the rock behind it and the landing out in front
                val ox = px + d.dx * 2
                val oz = pz + d.dz * 2
                plan.claim(min(qx, ox) - 1, min(qz, oz) - 1, max(qx, ox) + 1, max(qz, oz) + 1)
                return
            }
        }
    }

    /** A spot on the home stack's top for the store, levelled; or for a cairn, if the top's too small. */
    private fun layStore(h: Terrain.Stack, hx: Int, hz: Int) {
        val t = terrain
        val level = t.h(hx, hz).roundToInt()
        cairnX = hx
        cairnZ = hz
        // The store's door looks away from the ladder's top, so whoever climbs up walks round to it
        storeDoor = face
        for ((w, d) in listOf(Pair(5, 4), Pair(4, 4), Pair(4, 3))) {
            for (ox in -2..2) for (oz in -2..2) {
                val r = if (face.alongX) Rect(hx + ox - d / 2, hz + oz - w / 2, hx + ox - d / 2 + d - 1, hz + oz - w / 2 + w - 1)
                else Rect(hx + ox - w / 2, hz + oz - d / 2, hx + ox - w / 2 + w - 1, hz + oz - d / 2 + d - 1)
                var fits = true
                for (x in r.x0 - 1..r.x1 + 1) for (z in r.z0 - 1..r.z1 + 1) {
                    if (t.h(x, z) < level - 1.6 || (x == lx && z == lz)) fits = false
                }
                if (!fits) continue
                store = r
                storeFloor = sea + level
                t.flatten(r.x0, r.z0, r.x1, r.z1, level, 0)
                plan.claim(r.x0 - 1, r.z0 - 1, r.x1 + 1, r.z1 + 1)
                return
            }
        }
        plan.claim(hx - 1, hz - 1, hx + 1, hz + 1)
    }

    /** A rope bridge to the nearest stack of much the same height that lies square to the home one. */
    private fun layBridge(h: Terrain.Stack, hx: Int, hz: Int) {
        val t = terrain
        val far = t.stacks.filter { it !== h && abs(it.height - h.height) <= 5.0 }.sortedBy { hypot(it.x - h.x, it.z - h.z) }
        for (b in far) {
            val dx = b.x - h.x
            val dz = b.z - h.z
            if (hypot(dx, dz) > 20.0 || min(abs(dx), abs(dz)) > 0.35 * max(abs(dx), abs(dz))) continue
            val d = if (abs(dx) > abs(dz)) (if (dx > 0) Dir.E else Dir.W) else (if (dz > 0) Dir.S else Dir.N)
            val bx = floor(plan.spec.cx + b.x).toInt()
            val bz = floor(plan.spec.cz + b.z).toInt()
            // The deck's line, halfway between the two stacks' middles
            val row = if (d.alongX) (hz + bz) / 2 else (hx + bx) / 2
            fun at(k: Int): Pair<Int, Int> = if (d.alongX) Pair(k, row) else Pair(row, k)
            val from = if (d.alongX) hx else hz
            val to = if (d.alongX) bx else bz
            val step = if (to > from) 1 else -1
            // Each end: the last column of each top before it falls away
            var a = from
            while (a != to && t.h(at(a + step).first, at(a + step).second) >= h.height - 3) a += step
            var e = to
            while (e != from && t.h(at(e - step).first, at(e - step).second) >= b.height - 3) e -= step
            if ((e - a) * step < 3) continue
            val ha = t.top(at(a).first, at(a).second)
            val hb = t.top(at(e).first, at(e).second)
            // Nothing in the way underneath (an arch, a third stack)
            val cells = ArrayList<Pair<Int, Int>>()
            var k = a + step
            var clear = true
            while (k != e) {
                val c = at(k)
                if (t.top(c.first, c.second) > min(ha, hb) - 4) clear = false
                cells.add(c)
                k += step
            }
            if (!clear || abs(ha - hb) > 4) continue
            span = cells
            along = d
            standA = ha
            standB = hb
            val ea = at(a)
            val eb = at(e)
            plan.claim(ea.first - 1, ea.second - 1, ea.first + 1, ea.second + 1)
            plan.claim(eb.first - 1, eb.second - 1, eb.first + 1, eb.second + 1)
            return
        }
    }

    /** Nests along every stack's top, thickest at the rim, and on ledges jutting from their faces. */
    private fun layNests() {
        val t = terrain
        val dice = Dice(plan.spec.seed xor 0x600CL)
        for (s in t.stacks) {
            val sx = plan.spec.cx + s.x
            val sz = plan.spec.cz + s.z
            val reach = (s.radius + 3).toInt()
            for (x in floor(sx).toInt() - reach..floor(sx).toInt() + reach) for (z in floor(sz).toInt() - reach..floor(sz).toInt() + reach) {
                val here = t.h(x, z)
                if (here < s.height * 0.75 || plan.claimed(x, z) || onWay(x, z)) continue
                // On the rim, where the top breaks off into the cliff, the nests crowd together
                val rim = Dir.entries.any { t.h(x + it.dx, z + it.dz) < here - 2 }
                val roll = Noise.unit(x, 1, z, seed)
                if (roll > (if (rim) 0.42 else 0.14)) continue
                nests.add(Nest(x, t.top(x, z), z, eggs(Noise.unit(x, 2, z, seed)), false))
                plan.claim(x, z, x, z)
            }
            // Ledges on the sheer faces, a few to each stack
            for (x in floor(sx).toInt() - reach..floor(sx).toInt() + reach) for (z in floor(sz).toInt() - reach..floor(sz).toInt() + reach) {
                val q = t.h(x, z)
                if (q < 6) continue
                for (d in Dir.entries) {
                    val px = x + d.dx
                    val pz = z + d.dz
                    if (t.h(px, pz) > q - 5 || (px == lx && pz == lz) || onWay(px, pz)) continue
                    if (Noise.unit(px, 3 + d.ordinal, pz, seed) > 0.07) continue
                    val low = max(sea + 2, t.top(px, pz) + 2)
                    val high = sea + q.roundToInt() - 3
                    if (high < low) continue
                    nests.add(Nest(px, low + dice.int(high - low + 1), pz, eggs(dice.next()), true))
                }
            }
        }
    }

    /** How many eggs a nest holds, from a roll of 0 to 1: most have one or two, some none, a few three. */
    private fun eggs(roll: Double): Int = when {
        roll < 0.15 -> 0
        roll < 0.45 -> 1
        roll < 0.82 -> 2
        else -> 3
    }

    /** Whether a column is the ladder's or its landing's, or under the bridge. */
    private fun onWay(x: Int, z: Int): Boolean {
        if (abs(x - lx) <= 2 && abs(z - lz) <= 2) return true
        return span.any { abs(it.first - x) <= 1 && abs(it.second - z) <= 1 }
    }

    override fun paint() {
        // A worn patch before the store's door
        val r = store ?: return
        val door = storeDoor
        val x = when (door) {
            Dir.E -> r.x1 + 1
            Dir.W -> r.x0 - 1
            else -> (r.x0 + r.x1) / 2
        }
        val z = when (door) {
            Dir.S -> r.z1 + 1
            Dir.N -> r.z0 - 1
            else -> (r.z0 + r.z1) / 2
        }
        plan.paint(x, z, Ground.PATH)
    }

    override fun build(b: Build) {
        if (home == null) return
        ladder(b)
        bridge(b)
        store(b)
        for (n in nests) {
            if (n.ledge) {
                b.soft(n.x, n.y - 1, n.z, Bk.of(ledgeRock(n.x, n.y - 1, n.z)))
            }
            b.soft(n.x, n.y, n.z, Bk.of("guildmark:gull_nest", "eggs" to n.eggs.toString()))
        }
        // The colony's clamour, from the thick of its nests: a spot or two, far enough apart to be heard apart
        val hearts = ArrayList<Nest>()
        for (n in nests.sortedBy { Noise.unit(it.x, it.y, it.z, seed + 11) }) {
            if (hearts.size >= 3) break
            if (hearts.none { (it.x - n.x) * (it.x - n.x) + (it.z - n.z) * (it.z - n.z) < 18 * 18 }) hearts.add(n)
        }
        for (n in hearts) b.sound(SoundSpot.COLONY, n.x, n.y, n.z)
        // The gulls themselves, sitting their nests
        var gulls = 0
        for ((k, n) in nests.withIndex()) {
            if (gulls >= 18 || Noise.unit(n.x, n.y, n.z, seed + 5) > 0.45) continue
            b.spawn("guildmark:gull", n.x + 0.5, n.y + 0.3, n.z + 0.5, (Noise.unit(k, 0, 0, seed + 6) * 360).toFloat())
            gulls++
        }
    }

    /** The rock a ledge juts out in: the island's own, a little weathered. */
    private fun ledgeRock(x: Int, y: Int, z: Int): String {
        val bands = plan.spec.rock.bands
        return bands[Math.floorMod(Noise.hash(x, y, z, seed + 9), bands.size)]
    }

    /** The landing at the ladder's foot, the ladder up the face, and the hoist rope beside it. */
    private fun ladder(b: Build) {
        if (ladderTo < ladderFrom) return
        val d = face
        val across = d.cw
        val planks = Bk.of("${style.wood}_planks")
        // The landing: planks on the water, posts at its outer corners from the sea bed up through the deck
        for (o in 0..1) for (c in -1..1) {
            val x = lx + d.dx * o + across.dx * c
            val z = lz + d.dz * o + across.dz * c
            val post = o == 1 && c != 0
            if (post) for (y in max(terrain.top(x, z), sea - 14)..sea) b.set(x, y, z, Bk.of("${style.wood}_log", "axis" to "y"))
            else b.set(x, sea, z, planks)
            b.air(x, sea + 1, z)
            b.air(x, sea + 2, z)
        }
        // A mooring post at the landing's corner, and the gatherers' boat tied up off it
        b.set(lx + d.dx + across.dx, sea + 1, lz + d.dz + across.dz, Bk.of("${style.wood}_fence"))
        val bx = lx + d.dx * 2 - across.dx
        val bz = lz + d.dz * 2 - across.dz
        if (!terrain.dry(bx, bz)) {
            b.spawn("minecraft:${style.wood}_boat", bx + 0.5, sea - 0.6, bz + 0.5, CityBridge.yaw(across))
            b.sound(SoundSpot.MOORING, bx, sea, bz)
        }
        // The ladder, pinned to the rock all the way up
        for (y in ladderFrom..ladderTo) b.set(lx, y, lz, Bk.of("ladder", "facing" to d.id, "waterlogged" to "false"))
        // The hoist: an arm out over the face from a post at the top, a rope down and a basket half hauled up
        val qx = lx - d.dx + across.dx
        val qz = lz - d.dz + across.dz
        val base = terrain.top(qx, qz)
        val hx = lx + across.dx
        val hz = lz + across.dz
        if (terrain.top(hx, hz) < base - 4) {
            b.set(qx, base, qz, Bk.of("${style.wood}_fence"))
            b.set(qx, base + 1, qz, Bk.of("${style.wood}_fence"))
            b.set(hx, base + 1, hz, Bk.of("${style.wood}_fence"))
            val drop = min(6, max(2, (ladderTo - ladderFrom) / 3))
            for (y in base + 1 - drop..base) b.soft(hx, y, hz, Bk.of("iron_chain", "axis" to "y", "waterlogged" to "false"))
            b.soft(hx, base - drop, hz, Bk.of("barrel", "facing" to "up", "open" to "false"))
        }
    }

    /** The rope bridge: a sagging deck of planks and slabs between two posts at each end, chains strung for hand ropes. */
    private fun bridge(b: Build) {
        if (span.isEmpty()) return
        val across = along.cw
        val n = span.size + 1
        val sag = 0.6 + 0.09 * n
        val planks = "${style.wood}_planks"
        val slab = "${style.wood}_slab"
        for ((k, c) in span.withIndex()) {
            val f = (k + 1).toDouble() / n
            val stand = standA + (standB - standA) * f - sag * 4 * f * (1 - f)
            val half = (stand * 2).roundToInt()
            val y = Math.floorDiv(half, 2)
            // A plank or two gone, here and there
            val missing = k in 1 until span.size - 1 && Noise.unit(c.first, 7, c.second, seed) < 0.12
            if (!missing) {
                if (half % 2 == 0) b.set(c.first, y - 1, c.second, Bk.of(planks))
                else b.set(c.first, y, c.second, Bk.of(slab, "type" to "bottom", "waterlogged" to "false"))
            }
            for (s in listOf(-1, 1)) {
                val rx = c.first + across.dx * s
                val rz = c.second + across.dz * s
                b.set(rx, y + 1, rz, Bk.of("iron_chain", "axis" to along.axis, "waterlogged" to "false"))
            }
        }
        // Posts at both ends, either side, the hand ropes tied off to them
        val ends = listOf(Pair(span.first(), standA), Pair(span.last(), standB))
        for ((i, e) in ends.withIndex()) {
            val (c, stand) = e
            val back = if (i == 0) along.opposite else along
            for (s in listOf(-1, 1)) {
                val x = c.first + back.dx + across.dx * s
                val z = c.second + back.dz + across.dz * s
                b.set(x, stand, z, Bk.of("${style.wood}_fence"))
                b.set(x, stand + 1, z, Bk.of("${style.wood}_fence"))
            }
        }
    }

    /** The egg store: dry stone walls, a turf roof, barrels of eggs and down inside; or a cairn with a barrel by it. */
    private fun store(b: Build) {
        val r = store
        if (r == null) {
            val y = terrain.top(cairnX, cairnZ)
            b.set(cairnX, y, cairnZ, Bk.of("cobblestone"))
            b.set(cairnX, y + 1, cairnZ, Bk.of("mossy_cobblestone"))
            b.set(cairnX, y + 2, cairnZ, Bk.of("cobblestone_wall"))
            b.chest(cairnX + face.cw.dx, y, cairnZ + face.cw.dz, face, LOOT, barrel = true)
            return
        }
        val y = storeFloor
        val door = storeDoor
        // Which wall the door's in, and its column: the middle of the wall facing [door]
        val doorX = when (door) {
            Dir.E -> r.x1
            Dir.W -> r.x0
            else -> (r.x0 + r.x1) / 2
        }
        val doorZ = when (door) {
            Dir.S -> r.z1
            Dir.N -> r.z0
            else -> (r.z0 + r.z1) / 2
        }
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            val edge = x == r.x0 || x == r.x1 || z == r.z0 || z == r.z1
            b.set(x, y - 1, z, if (edge) stone(x, y - 1, z) else Bk.of("coarse_dirt"))
            for (k in 0..1) {
                when {
                    !edge -> b.air(x, y + k, z)
                    x == doorX && z == doorZ -> b.air(x, y + k, z)
                    else -> b.set(x, y + k, z, stone(x, y + k, z))
                }
            }
            // The roof: a course of stone laid over, and turf on that, rounded off at its edges
            b.set(x, y + 2, z, stone(x, y + 2, z))
            if (edge) {
                b.soft(x, y + 3, z, Bk.of("moss_carpet"))
            } else {
                b.set(x, y + 3, z, if (Noise.unit(x, y, z, seed + 11) < 0.3) Bk.of("moss_block") else Bk.of("grass_block", "snowy" to "false"))
                if (Noise.unit(x, y + 4, z, seed + 12) < 0.5) b.soft(x, y + 4, z, Bk.of("short_grass"))
            }
        }
        // Inside: barrels of eggs and of down along the back, a little straw underfoot
        val inside = ArrayList<Pair<Int, Int>>()
        for (x in r.x0 + 1..r.x1 - 1) for (z in r.z0 + 1..r.z1 - 1) inside.add(Pair(x, z))
        val far = inside.sortedByDescending { abs(it.first - doorX) + abs(it.second - doorZ) }
        far.getOrNull(0)?.let { b.chest(it.first, y, it.second, door, LOOT, barrel = true) }
        far.getOrNull(1)?.let { b.set(it.first, y, it.second, Bk.of("barrel", "facing" to "up", "open" to "false")) }
        far.getOrNull(2)?.let { b.set(it.first, y, it.second, Bk.of("white_carpet")) }
    }

    /** Dry stone: rough and mossy. */
    private fun stone(x: Int, y: Int, z: Int): Bk {
        val roll = Noise.unit(x, y, z, seed + 13)
        return Bk.of(
            when {
                roll < 0.22 -> "mossy_cobblestone"
                roll < 0.34 -> "stone"
                roll < 0.4 -> "andesite"
                else -> "cobblestone"
            },
        )
    }

    companion object {
        const val LOOT = "guildmark:chests/island/rookery"
    }
}
