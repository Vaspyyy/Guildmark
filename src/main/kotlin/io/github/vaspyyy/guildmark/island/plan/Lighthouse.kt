package io.github.vaspyyy.guildmark.island.plan

import io.github.vaspyyy.guildmark.city.plan.FarmBuilding
import io.github.vaspyyy.guildmark.city.plan.FarmBuildings
import io.github.vaspyyy.guildmark.city.plan.FarmKind
import io.github.vaspyyy.guildmark.city.plan.Frame
import io.github.vaspyyy.guildmark.city.plan.Loot
import io.github.vaspyyy.guildmark.city.plan.Rect
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A lighthouse on a rock: a tall round tower, whitewashed and banded red over a footing of grey stone, its stair
 * winding up inside round a stone post to a gallery and the lamp room, glazed between green copper posts under a
 * copper cap. The keeper's cottage stands at its foot on the sheltered side, and a stair cut down the rock leads to a
 * landing stage with the keeper's boat tied up at it.
 */
class Lighthouse(plan: IslandPlan) : Landmark(plan) {
    private val dice = Dice(plan.spec.seed xor 0x11647L)
    private val style = IslandStyle(plan.climate)
    private val seed = Noise.seed(plan.spec.seed, 60)

    /** The tower's middle column, and how high its ground stands over the sea. */
    private var tx = plan.spec.cx
    private var tz = plan.spec.cz
    private var level = 4

    /** How tall the shaft is, from the ground to the gallery, and where its two red bands start. */
    private val shaft = dice.int(18, 22)
    private val red1 = (shaft * 0.36).roundToInt()
    private val red2 = (shaft * 0.68).roundToInt()

    /** Which way from the tower the cottage stands, and its lot. */
    private var side = Dir.S
    private var lot = Rect(0, 0, 0, 0)

    /** The way down to the landing stage, and which way the stage runs out over the water. */
    private var way = Way.NONE
    private var out = Dir.N

    /** The first block of air over the site's levelled ground. */
    private val ground: Int get() = sea + level

    /** Where the stair's first step lies round the post: just past the door, as you come in. */
    private val firstStep: Double get() = heading(side) + PI / SECTORS

    override fun lay() {
        val t = terrain
        // The tower on the island's crown, a little toward its middle
        var best = Double.NEGATIVE_INFINITY
        for (x in t.x0 until t.x0 + t.size) for (z in t.z0 until t.z0 + t.size) {
            if (t.distance(x, z) < 4.5) continue
            val score = t.h(x, z) - 0.15 * hypot((x - plan.spec.cx).toDouble(), (z - plan.spec.cz).toDouble())
            if (score > best) {
                best = score
                tx = x
                tz = z
            }
        }
        level = t.h(tx, tz).roundToInt().coerceIn(3, 30)

        // The cottage on whichever side has the most land under it, out of the wind if it can be
        val lee = t.windFrom + PI
        var bestSide = Double.NEGATIVE_INFINITY
        for (d in Dir.entries) {
            val r = lotFor(d)
            var dry = 0
            var all = 0
            for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
                all++
                if (t.distance(x, z) > 1.5) dry++
            }
            val score = 10.0 * dry / all - 1.5 * gap(heading(d), lee)
            if (score > bestSide) {
                bestSide = score
                side = d
            }
        }
        lot = lotFor(side)

        // Level the rock for the tower and the cottage, and keep the trees off
        t.flatten(tx - 6, tz - 6, tx + 6, tz + 6, level, 3)
        t.flatten(lot.x0 - 1, lot.z0 - 1, lot.x1 + 1, lot.z1 + 1, level, 3)
        plan.claim(tx - 7, tz - 7, tx + 7, tz + 7)
        plan.claim(lot.x0 - 2, lot.z0 - 2, lot.x1 + 2, lot.z1 + 2)

        layWay()
    }

    /** The cottage's lot on side [d] of the tower: [WIDTH] across, [DEPTH] deep, its front [NEAR] from the tower's middle. */
    private fun lotFor(d: Dir): Rect {
        val across = d.cw
        val ax = tx + d.dx * NEAR - across.dx * HALF
        val az = tz + d.dz * NEAR - across.dz * HALF
        val bx = tx + d.dx * (NEAR + DEPTH - 1) + across.dx * HALF
        val bz = tz + d.dz * (NEAR + DEPTH - 1) + across.dz * HALF
        return Rect(min(ax, bx), min(az, bz), max(ax, bx), max(az, bz))
    }

    /**
     * Finds the landing on the shore below that's easiest to reach from the yard, low at the water's edge, out of the
     * wind and not under a cliff, and cuts the way down to it.
     */
    private fun layWay() {
        val t = terrain
        val across = side.cw
        val lee = t.windFrom + PI
        // Round the tower's plinth, its lanterns and the cottage with its woodpile and rain barrel
        val house = lot.grow(1)
        val blocked = { x: Int, z: Int -> hypot((x - tx).toDouble(), (z - tz).toDouble()) < PLINTH + 1.2 || house.contains(x, z) }
        val ways = listOf(1, -1).map { sign ->
            Ways(Pair(tx + side.dx * 5 + across.dx * sign * (HALF + 2), tz + side.dz * 5 + across.dz * sign * (HALF + 2)), level, seed, blocked)
        }
        var best = Double.POSITIVE_INFINITY
        var bestWays: Ways? = null
        var lx = 0
        var lz = 0
        for (x in t.x0 until t.x0 + t.size) for (z in t.z0 until t.z0 + t.size) {
            val d = t.distance(x, z)
            if (d < 0.0 || d > 1.2 || !t.dry(x, z)) continue
            val shore = 8.0 * t.cliffAt(x, z) + 1.5 * gap(atan2((z - tz).toDouble(), (x - tx).toDouble()), lee) + 2.0 * max(0.0, t.h(x, z) - 1.0)
            for (w in ways) {
                val score = w.cost(x, z) + 2.0 * shore
                if (score < best) {
                    best = score
                    bestWays = w
                    lx = x
                    lz = z
                }
            }
        }
        way = bestWays?.to(lx, lz) ?: return
        if (way.isEmpty()) return
        shape(way)
        // The stage runs out the way the shore falls away to the sea
        out = Dir.entries.minBy { t.distance(lx + it.dx * 2, lz + it.dz * 2) }
        plan.claim(lx - 2, lz - 2, lx + 2, lz + 2)
        for ((x, z) in way.cells) plan.claim(x - 1, z - 1, x + 1, z + 1)
    }

    override fun paint() {
        for ((x, z) in way.cells) plan.paint(x, z, Ground.PATH)
        // The yard between the tower's door and the cottage's
        for (k in 5 until NEAR) plan.paint(tx + side.dx * k, tz + side.dz * k, Ground.PATH)
        // Trodden from the yard's ends round to the way
        val across = side.cw
        for (sign in listOf(1, -1)) for (c in 0..HALF + 2) plan.paint(tx + side.dx * 5 + across.dx * sign * c, tz + side.dz * 5 + across.dz * sign * c, Ground.PATH)
    }

    override fun build(b: Build) {
        tower(b)
        cottage(b)
        stairs(b)
        stage(b)
        yard(b)
    }

    // ---- the tower

    private fun radius(k: Int): Double = 3.9 - 0.9 * k / (shaft - 1).toDouble()

    private fun sector(dx: Int, dz: Int): Int {
        val a = atan2(dz.toDouble(), dx.toDouble()) - firstStep
        return Math.floorMod(floor(a / (2 * PI / SECTORS)).toInt(), SECTORS)
    }

    /** Is ([dx], [dz]) from the post one of step [k]'s blocks? */
    private fun isStep(dx: Int, dz: Int, k: Int): Boolean {
        if (k < 0 || k > shaft) return false
        val q = hypot(dx.toDouble(), dz.toDouble())
        return q > 0.9 && q <= radius(min(k, shaft - 1)) - WALL && sector(dx, dz) == Math.floorMod(k, SECTORS)
    }

    /** The step's stair, rising round the post the way the next step lies. */
    private fun stepBlock(dx: Int, dz: Int): Bk =
        Bk.of("stone_brick_stairs", "facing" to Dir.nearest(atan2(dz.toDouble(), dx.toDouble()) + PI / 2).id, "half" to "bottom")

    private fun tower(b: Build) {
        val g = ground
        val h = shaft
        val top = radius(h - 1)
        for (dx in -6..6) for (dz in -6..6) {
            val q = hypot(dx.toDouble(), dz.toDouble())
            val x = tx + dx
            val z = tz + dz
            // The footing under the plinth and the floor inside
            if (q <= PLINTH) {
                b.set(x, g - 2, z, worn("stone_bricks", x, g - 2, z))
                b.set(x, g - 1, z, if (q <= radius(0) - WALL) (if (dx == 0 && dz == 0) Bk.of("chiseled_stone_bricks") else Bk.of("polished_andesite")) else worn("stone_bricks", x, g - 1, z))
            }
            // The plinth round the tower's foot: a course of stone and a sloped course over it
            if (q > radius(0) && q <= PLINTH) {
                b.set(x, g, z, worn("stone_bricks", x, g, z))
                b.set(x, g + 1, z, Bk.of("stone_brick_stairs", "facing" to inward(dx, dz).id, "half" to "bottom"))
            }
            // The shaft, its stair and the air inside
            for (k in 0 until h) {
                val r = radius(k)
                if (q > r) continue
                val y = g + k
                when {
                    q > r - WALL -> b.set(x, y, z, wall(k, x, y, z))
                    dx == 0 && dz == 0 -> b.set(x, y, z, Bk.of("polished_andesite"))
                    isStep(dx, dz, k) -> b.set(x, y, z, stepBlock(dx, dz))
                    else -> b.air(x, y, z)
                }
            }
            // A corbelled course carrying the gallery out over the shaft's top
            if (q > top && q <= top + 1.0) b.set(x, g + h - 1, z, Bk.of("stone_brick_stairs", "facing" to inward(dx, dz).id, "half" to "top"))
            // The gallery, with the stair coming up through it into the lamp room
            if (q <= GALLERY) {
                val y = g + h
                when {
                    isStep(dx, dz, h) -> b.set(x, y, z, stepBlock(dx, dz))
                    isStep(dx, dz, h - 1) || isStep(dx, dz, h - 2) -> b.air(x, y, z)
                    else -> b.set(x, y, z, Bk.of("polished_deepslate"))
                }
            }
            if (q > RAIL && q <= GALLERY) b.set(x, g + h + 1, z, Bk.of("iron_bars"))
            // The lamp room: glazed between copper posts, a door out onto the gallery
            for (k in h + 1..h + 3) {
                val y = g + k
                if (q > LAMP_ROOM) continue
                if (q > LAMP_ROOM - 0.9) {
                    val post = abs(dx) == 2 && abs(dz) == 2
                    val door = dx == 2 * side.dx + side.cw.dx && dz == 2 * side.dz + side.cw.dz && k < h + 3
                    b.set(x, y, z, if (post) Bk.of("waxed_oxidized_copper") else if (door) Bk.AIR else Bk.of("glass_pane"))
                } else if (!(dx == 0 && dz == 0)) {
                    b.air(x, y, z)
                }
            }
            // The cap: copper sloping in from the eaves to a rod at the top
            for ((k, reach) in listOf(Pair(h + 4, 3.3), Pair(h + 5, 2.3), Pair(h + 6, 1.3))) {
                if (q > reach) continue
                val y = g + k
                b.set(
                    x, y, z,
                    if (q > reach - 1.0) Bk.of("waxed_oxidized_cut_copper_stairs", "facing" to inward(dx, dz).id, "half" to "bottom")
                    else Bk.of("waxed_oxidized_cut_copper"),
                )
            }
        }
        // The lamp on its pedestal over the post, and the rod
        b.set(tx, g + h + 1, tz, Bk.of("waxed_copper_block"))
        b.set(tx, g + h + 2, tz, Bk.of("guildmark:lighthouse_lamp"))
        b.sound(SoundSpot.LIGHTHOUSE, tx, g + h + 2, tz)
        b.air(tx, g + h + 3, tz)
        b.set(tx, g + h + 7, tz, Bk.of("waxed_oxidized_lightning_rod", "facing" to "up"))

        // The door, through a gap in the plinth
        for (j in 2..5) {
            val x = tx + side.dx * j
            val z = tz + side.dz * j
            val q = j.toDouble()
            if (q > radius(0) - WALL && q <= radius(0)) {
                b.set(x, g, z, Bk.of("${style.wood}_door", "facing" to side.opposite.id, "half" to "lower", "hinge" to "left", "open" to "false"))
                b.set(x, g + 1, z, Bk.of("${style.wood}_door", "facing" to side.opposite.id, "half" to "upper", "hinge" to "left", "open" to "false"))
                b.set(x, g + 2, z, Bk.of("stone_bricks"))
            } else if (q > radius(0) && q <= PLINTH) {
                b.air(x, g, z)
                b.air(x, g + 1, z)
            }
        }
        // Lanterns on posts either side of the way in
        for (c in listOf(-1, 1)) {
            val x = tx + side.dx * 5 + side.cw.dx * c
            val z = tz + side.dz * 5 + side.cw.dz * c
            b.set(x, g, z, Bk.of("stone_brick_wall"))
            b.set(x, g + 1, z, Bk.of("lantern", "hanging" to "false"))
        }

        // Small windows lighting the stair, and lanterns hung under the steps
        for (k in intArrayOf(5, 10, 15, 20)) {
            if (k > h - 3) continue
            val a = firstStep + (Math.floorMod(k, SECTORS) + 0.5) * 2 * PI / SECTORS
            val r = radius(k) - 0.5
            b.set(tx + (cos(a) * r).roundToInt(), g + k + 1, tz + (sin(a) * r).roundToInt(), Bk.of("glass_pane"))
        }
        for (k in intArrayOf(7, 13, 19)) {
            if (k > h - 2) continue
            val a = firstStep + (Math.floorMod(k, SECTORS) + 0.5) * 2 * PI / SECTORS
            val dx = cos(a).roundToInt()
            val dz = sin(a).roundToInt()
            if (isStep(dx, dz, k)) b.set(tx + dx, g + k - 1, tz + dz, Bk.of("lantern", "hanging" to "true"))
        }

        // The keeper's stores on the ground floor, across from the stair's foot
        for ((n, s) in listOf(4, 5, 6).withIndex()) {
            val a = firstStep + (s + 0.5) * 2 * PI / SECTORS
            val dx = (cos(a) * 2).roundToInt()
            val dz = (sin(a) * 2).roundToInt()
            if (hypot(dx.toDouble(), dz.toDouble()) > radius(0) - WALL) continue
            val facing = inward(dx, dz)
            when (n) {
                1 -> b.chest(tx + dx, g, tz + dz, facing, LOOT_TOWER)
                else -> b.chest(tx + dx, g, tz + dz, facing, LOOT_OIL, barrel = true)
            }
        }
    }

    /** The tower's wall at height [k] up the shaft: grey stone at the foot, then whitewash with two bands of red. */
    private fun wall(k: Int, x: Int, y: Int, z: Int): Bk {
        val roll = Noise.unit(x, y, z, seed)
        return when {
            k < 3 -> worn("stone_bricks", x, y, z)
            k in red1 until red1 + 3 || k in red2 until red2 + 3 -> if (roll < 0.07) Bk.of("terracotta") else Bk.of("red_terracotta")
            else -> if (roll < 0.06) Bk.of("polished_diorite") else Bk.of("calcite")
        }
    }

    // ---- the cottage, the way down and the landing stage

    private fun cottage(b: Build) {
        val bridge = CityBridge(b, style, seed) { loot -> if (loot == Loot.HOUSE) LOOT_KEEPER else LOOT_KEEPER }
        val front = CityBridge.city(side.opposite)
        val house = FarmBuilding(FarmKind.COTTAGE, lot, front, ground, plan.spec.seed xor 0xC077A6EL)
        FarmBuildings(Frame(bridge, lot, front, ground), house, Random(house.seed)).draw()
    }

    /** A step cut in the rock wherever the way down drops or climbs, on the lower column, rising toward the higher. */
    private fun stairs(b: Build) {
        val stone = stepStone()
        val cells = way.cells
        val h = way.heights
        for (k in 1 until way.size) {
            if (h[k] == h[k - 1]) continue
            val (low, high) = if (h[k] < h[k - 1]) Pair(k, k - 1) else Pair(k - 1, k)
            val (x, z) = cells[low]
            b.set(x, sea + h[low], z, Bk.of(stone, "facing" to dirTo(cells[low], cells[high]).id, "half" to "bottom"))
        }
    }

    /**
     * Stone steps on from the landing down to the water's edge where the shore stands high, then a plank landing stage
     * out over the water on posts, a lantern at its end and the keeper's boat beside it.
     */
    private fun stage(b: Build) {
        if (way.isEmpty()) return
        val (lx, lz) = way.last()
        val across = out.cw
        val stone = stepStone()
        val footing = when (plan.climate) {
            Climate.WARM, Climate.LUKEWARM -> "sandstone"
            Climate.COLD, Climate.FROZEN -> "cobblestone"
            Climate.TEMPERATE -> "stone_bricks"
        }
        var stand = sea + way.heights.last()
        var steps = 0
        while (stand > sea + 1) {
            steps++
            stand--
            for (c in -1..1) {
                val x = lx + out.dx * steps + across.dx * c
                val z = lz + out.dz * steps + across.dz * c
                for (y in max(terrain.top(x, z), sea - 14) until stand) b.set(x, y, z, Bk.of(footing))
                b.set(x, stand, z, Bk.of(stone, "facing" to out.opposite.id, "half" to "bottom"))
                b.air(x, stand + 1, z)
                b.air(x, stand + 2, z)
            }
        }
        val deck = sea
        val planks = "${style.wood}_planks"
        val length = 5 + Math.floorMod(seed, 2)
        var end = 0
        for (j in 1..length) {
            val i = steps + j
            val x = lx + out.dx * i
            val z = lz + out.dz * i
            if (terrain.dry(x, z) && j < length) continue
            end = i
            val wide = i >= length - 1
            for (c in (if (wide) -1..1 else 0..0)) {
                val px = x + across.dx * c
                val pz = z + across.dz * c
                b.set(px, deck, pz, Bk.of(planks))
                b.air(px, deck + 1, pz)
                b.air(px, deck + 2, pz)
            }
            // Posts down to the bottom every other plank, either side
            if (i % 2 == 0 || i == length) for (c in listOf(-1, 1)) {
                val px = x + across.dx * c
                val pz = z + across.dz * c
                val bottom = max(terrain.top(px, pz), sea - 14)
                for (y in bottom until deck) b.set(px, y, pz, Bk.of("${style.wood}_log", "axis" to "y"))
                if (!wide) b.set(px, deck, pz, Bk.of("${style.wood}_log", "axis" to "y"))
            }
        }
        if (end == 0) return
        // A lantern on a post at the end, and a barrel of the day's catch
        val ex = lx + out.dx * end
        val ez = lz + out.dz * end
        b.set(ex + across.dx, deck + 1, ez + across.dz, Bk.of("${style.wood}_fence"))
        b.set(ex + across.dx, deck + 2, ez + across.dz, Bk.of("lantern", "hanging" to "false"))
        b.chest(ex - across.dx, deck + 1, ez - across.dz, out, LOOT_CATCH, barrel = true)
        // The keeper's boat, tied up alongside
        val bx = ex + across.dx * 2 - out.dx
        val bz = ez + across.dz * 2 - out.dz
        if (!terrain.dry(bx, bz)) b.spawn("minecraft:${style.wood}_boat", bx + 0.5, sea - 0.6, bz + 0.5, CityBridge.yaw(out))
    }

    /** The yard about the tower and the cottage: a woodpile, a rain barrel, a bench looking out to sea, the keeper and the cat. */
    private fun yard(b: Build) {
        val g = ground
        val across = side.cw
        // Who keeps the light, out by the tower's door, and the cat
        b.spawn("minecraft:villager", tx + side.dx * 5 + 0.5, g.toDouble(), tz + side.dz * 5 + 0.5, CityBridge.yaw(side))
        b.spawn("minecraft:cat", tx + side.dx * 6 + across.dx * 2 + 0.5, g.toDouble(), tz + side.dz * 6 + across.dz * 2 + 0.5, CityBridge.yaw(side.opposite))
        // A woodpile along the cottage's far side, and a rain barrel at its corner
        val far = HALF + 1
        for (k in 1..3) for (y in 0..1) {
            val x = tx + side.dx * (NEAR + k) - across.dx * far
            val z = tz + side.dz * (NEAR + k) - across.dz * far
            if (y == 1 && k == 3) continue
            b.set(x, g + y, z, Bk.of("${style.wood}_log", "axis" to side.axis))
        }
        b.set(tx + side.dx * NEAR + across.dx * far, g, tz + side.dz * NEAR + across.dz * far, Bk.of("water_cauldron", "level" to "3"))
        // A bench at the cliff's edge, looking out to sea on the windward side
        val wind = terrain.windFrom
        for (r in 9 downTo 6) {
            val x = tx + (cos(wind) * r).roundToInt()
            val z = tz + (sin(wind) * r).roundToInt()
            if (!terrain.dry(x, z) || abs(terrain.top(x, z) - g) > 1) continue
            val landward = Dir.nearest(wind + PI)
            val y = terrain.top(x, z)
            val along = landward.cw
            for (c in 0..1) b.set(x + along.dx * c, y, z + along.dz * c, Bk.of("${style.wood}_stairs", "facing" to landward.id, "half" to "bottom"))
            break
        }
    }

    // ---- helpers

    /** What the steps down to the landing are cut in. */
    private fun stepStone(): String = when (plan.climate) {
        Climate.WARM, Climate.LUKEWARM -> "sandstone_stairs"
        Climate.COLD, Climate.FROZEN -> "cobblestone_stairs"
        Climate.TEMPERATE -> "stone_brick_stairs"
    }

    private fun worn(name: String, x: Int, y: Int, z: Int): Bk {
        val roll = Noise.unit(x, y, z, seed + 7)
        return when {
            name == "stone_bricks" && roll < 0.12 -> Bk.of("mossy_stone_bricks")
            name == "stone_bricks" && roll < 0.2 -> Bk.of("cracked_stone_bricks")
            else -> Bk.of(name)
        }
    }

    companion object {
        /** The stair's eight steps a turn. */
        private const val SECTORS = 8

        /** How thick the tower's wall is, and how far out its plinth, the gallery, the gallery's rail and the lamp room's glass reach. */
        private const val WALL = 1.15
        private const val PLINTH = 4.9
        private const val GALLERY = 4.4
        private const val RAIL = 3.5
        private const val LAMP_ROOM = 2.9

        /** The cottage: across its front, from front to back, and how far its front stands from the tower's middle. */
        private const val WIDTH = 7
        private const val HALF = WIDTH / 2
        private const val DEPTH = 6
        private const val NEAR = 8

        const val LOOT_TOWER = "guildmark:chests/island/lighthouse"
        const val LOOT_OIL = "guildmark:chests/island/lamp_stores"
        const val LOOT_KEEPER = "guildmark:chests/island/keeper"
        const val LOOT_CATCH = "guildmark:chests/island/catch"

        fun heading(d: Dir): Double = atan2(d.dz.toDouble(), d.dx.toDouble())

        /** How far apart two headings are, in radians (0 to pi). */
        fun gap(a: Double, b: Double): Double = abs(atan2(sin(a - b), cos(a - b)))

        /** The way from ([dx], [dz]) in toward the middle. */
        fun inward(dx: Int, dz: Int): Dir = Dir.nearest(atan2(-dz.toDouble(), -dx.toDouble()))
    }
}
