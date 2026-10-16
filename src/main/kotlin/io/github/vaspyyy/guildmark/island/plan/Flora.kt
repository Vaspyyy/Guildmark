package io.github.vaspyyy.guildmark.island.plan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a tree's or a rock's blocks go: soft ones (leaves) first, then hard ones (wood, stone) over them. */
class Growth {
    val soft = ArrayList<Pair<Long, Bk>>()
    val hard = ArrayList<Pair<Long, Bk>>()

    fun leaf(x: Int, y: Int, z: Int, b: Bk) {
        soft.add(Pair(pack(x, y, z), b))
    }

    fun wood(x: Int, y: Int, z: Int, b: Bk) {
        hard.add(Pair(pack(x, y, z), b))
    }

    companion object {
        fun pack(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
        fun x(p: Long): Int = ((p shr 38).toInt() shl 6) shr 6
        fun z(p: Long): Int = (((p shr 12) and 0x3FFFFFF).toInt() shl 6) shr 6
        fun y(p: Long): Int = ((p and 0xFFF).toInt() shl 20) shr 20
    }
}

/** Something that grows or lies on an island, spanning a few columns: a tree, a bush, a boulder, a drift log. */
abstract class Feature(val x: Int, val y: Int, val z: Int, val reach: Int, val seed: Long) {
    /** How far its hard blocks may sink into the ground (a boulder bedded in the turf); its leaves never do. */
    open val buried: Int get() = 0

    /** The columns it might reach. */
    fun touches(canvas: IslandCanvas): Boolean = canvas.touches(x - reach, z - reach, x + reach, z + reach)

    abstract fun grow(g: Growth)
}

private fun leaves(kind: String): Bk = Bk.of("${kind}_leaves", "persistent" to "true")

private fun log(kind: String, axis: String = "y"): Bk = Bk.of("${kind}_log", "axis" to axis)

/**
 * Lays a trunk or a limb of [wood] through [points], one face to the next so it never joins only at an edge: a
 * slanting run becomes a staircase that keeps as close to the straight line as it can. Each log lies the way the limb
 * leaves it, so where a trunk turns aside the log at the turn shows bark, not the rings of a sawn top.
 */
private fun limb(g: Growth, wood: String, points: List<Triple<Int, Int, Int>>) {
    var (cx, cy, cz) = points[0]
    val path = arrayListOf(Triple(cx, cy, cz))
    val ways = ArrayList<String>()
    for (k in 1 until points.size) {
        val (tx, ty, tz) = points[k]
        val ax = abs(tx - cx)
        val ay = abs(ty - cy)
        val az = abs(tz - cz)
        val n = ax + ay + az
        var sx = 0
        var sy = 0
        var sz = 0
        for (step in 1..n) {
            val t = step.toDouble() / n
            // Step along whichever axis lags furthest behind its share of the run
            val lx = if (sx < ax) ax * t - sx else -1.0
            val ly = if (sy < ay) ay * t - sy else -1.0
            val lz = if (sz < az) az * t - sz else -1.0
            when {
                lx >= ly && lx >= lz -> { sx++; cx += if (tx > cx) 1 else -1; ways.add("x") }
                lz >= ly -> { sz++; cz += if (tz > cz) 1 else -1; ways.add("z") }
                else -> { sy++; cy += if (ty > cy) 1 else -1; ways.add("y") }
            }
            path.add(Triple(cx, cy, cz))
        }
    }
    for ((k, p) in path.withIndex()) {
        val axis = ways.getOrNull(k) ?: ways.lastOrNull() ?: "y"
        g.wood(p.first, p.second, p.third, log(wood, axis))
    }
}

/**
 * An oak bent by the wind off the sea: its trunk leans away from the wind, its crown is swept downwind and sheared off
 * on the windward side. [lean] is the heading it leans to, in radians.
 */
class WindswornOak(x: Int, y: Int, z: Int, seed: Long, private val lean: Double, private val wood: String = "oak") : Feature(x, y, z, 7, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val height = dice.int(4, 7)
        val bend = dice.range(0.25, 0.75)
        val lx = cos(lean)
        val lz = sin(lean)
        val leaf = leaves(wood)
        val trunk = (0 until height).map { i ->
            val t = i.toDouble() / height
            Triple(x + (lx * bend * height * t * t).roundToInt(), y + i, z + (lz * bend * height * t * t).roundToInt())
        }
        limb(g, wood, trunk)
        val px = trunk.last().first
        val pz = trunk.last().third
        val topY = y + height - 1
        // A branch or two, mostly downwind
        val branches = dice.int(1, 3)
        for (b in 0 until branches) {
            val a = lean + dice.range(-1.3, 1.3)
            val len = dice.int(2, 3)
            val by = topY - dice.int(0, 2)
            for (k in 1..len) {
                val bx = px + (cos(a) * k).roundToInt()
                val bz = pz + (sin(a) * k).roundToInt()
                g.wood(bx, by + if (k == len) 1 else 0, bz, log(wood, if (abs(cos(a)) > abs(sin(a))) "x" else "z"))
                blob(g, bx, by + 1, bz, 1.6, 1.1, leaf, dice)
            }
        }
        // The crown: wide downwind, sheared flat on the windward side
        val cx = px + lx * 1.2
        val cz = pz + lz * 1.2
        val ra = dice.range(2.6, 3.6)
        val rv = dice.range(1.5, 2.2)
        val cy = topY + 0.6
        val r = (ra + 1).toInt()
        for (dx in -r..r) for (dz in -r..r) for (dy in -2..3) {
            val wx = px + dx
            val wz = pz + dz
            val fx = wx - cx
            val fz = wz - cz
            val along = fx * lx + fz * lz
            val across = -fx * lz + fz * lx
            val upwind = if (along < 0) 1.35 else 0.85
            val e = (along * upwind / ra).let { it * it } + (across / (ra * 0.85)).let { it * it } + ((topY + dy - cy) / rv).let { it * it }
            if (e > 1.0) continue
            if (e > 0.62 && Noise.unit(wx, topY + dy, wz, seed.toInt()) < 0.35) continue
            g.leaf(wx, topY + dy, wz, leaf)
        }
    }
}

/** A white-barked birch, straight up, with a narrow crown. */
class Birch(x: Int, y: Int, z: Int, seed: Long) : Feature(x, y, z, 3, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val height = dice.int(5, 7)
        for (i in 0 until height) g.wood(x, y + i, z, log("birch"))
        val leaf = leaves("birch")
        for (dy in -3..1) {
            val rr = when (dy) { -3 -> 1.5; -2, -1 -> 2.3; 0 -> 1.6; else -> 1.0 }
            val r = rr.toInt() + 1
            for (dx in -r..r) for (dz in -r..r) {
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d > rr) continue
                if (d > rr - 0.7 && Noise.unit(x + dx, y + dy, z + dz, seed.toInt()) < 0.4) continue
                g.leaf(x + dx, y + height - 1 + dy, z + dz, leaf)
            }
        }
    }
}

/** A tall cold-sea pine in tiers, a little bent by the wind. */
class Pine(x: Int, y: Int, z: Int, seed: Long, private val lean: Double, private val small: Boolean = false) : Feature(x, y, z, 4, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val height = if (small) dice.int(4, 6) else dice.int(7, 13)
        val leaf = leaves("spruce")
        val shift = dice.chance(0.5)
        val way = Dir.nearest(lean)
        val tx = if (shift) x + way.dx else x
        val tz = if (shift) z + way.dz else z
        // A kink two thirds of the way up, where an old storm bent it
        val kink = height * 2 / 3
        limb(g, "spruce", if (shift) listOf(Triple(x, y, z), Triple(x, y + kink - 1, z), Triple(tx, y + kink - 1, tz), Triple(tx, y + height - 1, tz)) else listOf(Triple(x, y, z), Triple(x, y + height - 1, z)))
        // Tiers of boughs from a third of the way up, wider at the bottom
        val first = max(2, height / 3)
        var level = first
        while (level <= height) {
            val t = (level - first).toDouble() / max(1, height - first)
            val rr = (if (small) 2.0 else 3.2) * (1 - t) + 0.9
            val r = rr.toInt() + 1
            val cx = if (level >= height * 2 / 3 && shift) tx else x
            val cz = if (level >= height * 2 / 3 && shift) tz else z
            for (dx in -r..r) for (dz in -r..r) {
                // A star more than a disc: the boughs reach furthest along the four ways and the diagonals
                val d = hypot(dx.toDouble(), dz.toDouble())
                val star = if (dx == 0 || dz == 0 || abs(dx) == abs(dz)) 0.0 else 0.8
                if (d > rr - star) continue
                if (d > 1.2 && Noise.unit(cx + dx, y + level, cz + dz, seed.toInt()) < 0.18) continue
                g.leaf(cx + dx, y + level, cz + dz, leaf)
            }
            if (rr > 1.6) for (dx in -1..1) for (dz in -1..1) if (abs(dx) + abs(dz) == 1) g.leaf(cx + dx, y + level + 1, cz + dz, leaf)
            level += if (rr > 2.2) 2 else 1
        }
        g.leaf(tx, y + height, tz, leaf)
        g.leaf(tx, y + height + 1, tz, leaf)
    }
}

/** A dead pine, stripped grey by the wind, a stub or two of branches left on it. */
class Snag(x: Int, y: Int, z: Int, seed: Long) : Feature(x, y, z, 2, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val height = dice.int(4, 8)
        for (i in 0 until height) g.wood(x, y + i, z, Bk.of("stripped_spruce_log", "axis" to "y"))
        for (b in 0 until dice.int(1, 2)) {
            val d = Dir.entries[dice.int(4)]
            g.wood(x + d.dx, y + dice.int(2, height - 1), z + d.dz, Bk.of("stripped_spruce_log", "axis" to d.axis))
        }
    }
}

/**
 * A palm on a warm beach: its trunk curving up and out towards [lean] (the sea, mostly), a head of drooping fronds,
 * and coconuts hanging under them.
 */
class Palm(x: Int, y: Int, z: Int, seed: Long, private val lean: Double) : Feature(x, y, z, 8, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val height = dice.int(6, 9)
        val reach = dice.range(1.5, 3.5)
        val lx = cos(lean)
        val lz = sin(lean)
        val trunk = (0 until height).map { i ->
            val t = i.toDouble() / (height - 1)
            Triple(x + (lx * reach * t * t).roundToInt(), y + i, z + (lz * reach * t * t).roundToInt())
        }
        limb(g, "jungle", trunk)
        val px = trunk.last().first
        val pz = trunk.last().third
        val top = y + height
        val leaf = leaves("jungle")
        g.leaf(px, top, pz, leaf)
        val fronds = dice.int(6, 8)
        val turn = dice.range(0.0, 2 * PI)
        for (f in 0 until fronds) {
            val a = turn + f * 2 * PI / fronds + dice.range(-0.2, 0.2)
            val len = dice.int(3, 5)
            for (k in 1..len) {
                val fx = px + (cos(a) * k).roundToInt()
                val fz = pz + (sin(a) * k).roundToInt()
                // Up a little, then drooping down towards its tip
                val fy = top - (if (k <= 1) 0 else (k - 1) * (k - 1) / 3)
                g.leaf(fx, fy, fz, leaf)
                if (k == len) g.leaf(fx, fy - 1, fz, leaf)
            }
        }
        // Coconuts under the head, hung on the topmost log
        for (d in Dir.entries) {
            if (dice.chance(0.45)) g.wood(px + d.dx, top - 1, pz + d.dz, Bk.of("cocoa", "age" to "2", "facing" to d.opposite.id))
        }
    }
}

/** A flat-topped acacia on a warm island's hills, its trunk forking. */
class Acacia(x: Int, y: Int, z: Int, seed: Long) : Feature(x, y, z, 6, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val height = dice.int(4, 6)
        limb(g, "acacia", listOf(Triple(x, y, z), Triple(x, y + height - 1, z)))
        val leaf = leaves("acacia")
        val forks = if (dice.chance(0.6)) 2 else 1
        val first = dice.range(0.0, 2 * PI)
        for (f in 0 until forks) {
            val a = first + f * PI + dice.range(-0.4, 0.4)
            val len = dice.int(2, 3)
            val bx = x + (cos(a) * len).roundToInt()
            val bz = z + (sin(a) * len).roundToInt()
            limb(g, "acacia", listOf(Triple(x, y + height - 1, z), Triple(bx, y + height - 1 + len, bz)))
            val cy = y + height + len
            for (dx in -3..3) for (dz in -3..3) {
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d <= 3.2 && !(abs(dx) == 3 && abs(dz) == 3)) g.leaf(bx + dx, cy, bz + dz, leaf)
                if (d <= 1.8) g.leaf(bx + dx, cy + 1, bz + dz, leaf)
            }
        }
    }
}

/** A low bush: a knot of wood under a mound of leaves. */
class Shrub(x: Int, y: Int, z: Int, seed: Long, private val kind: String) : Feature(x, y, z, 2, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val wood = when (kind) {
            "spruce" -> "spruce"
            "jungle" -> "jungle"
            else -> "oak"
        }
        g.wood(x, y, z, log(wood))
        val leaf = when (kind) {
            "azalea" -> if (dice.chance(0.5)) Bk.of("flowering_azalea_leaves", "persistent" to "true") else Bk.of("azalea_leaves", "persistent" to "true")
            else -> leaves(kind)
        }
        blob(g, x, y + 1, z, dice.range(1.2, 1.9), dice.range(0.9, 1.3), leaf, dice)
        g.leaf(x, y, z + 1, leaf)
        g.leaf(x, y, z - 1, leaf)
        g.leaf(x + 1, y, z, leaf)
        g.leaf(x - 1, y, z, leaf)
    }
}

private fun blob(g: Growth, cx: Int, cy: Int, cz: Int, rh: Double, rv: Double, leaf: Bk, dice: Dice) {
    val r = rh.toInt() + 1
    val salt = dice.int(1 shl 20)
    for (dx in -r..r) for (dz in -r..r) for (dy in -1..1) {
        val e = (dx * dx + dz * dz) / (rh * rh) + (dy * dy) / (rv * rv)
        if (e > 1.0) continue
        if (e > 0.6 && Noise.unit(cx + dx, cy + dy, cz + dz, salt) < 0.3) continue
        g.leaf(cx + dx, cy + dy, cz + dz, leaf)
    }
}

/** A boulder half sunk in the turf, mossy on its sheltered side. */
class Boulder(x: Int, y: Int, z: Int, seed: Long, private val stone: String) : Feature(x, y, z, 2, seed) {
    override val buried: Int get() = 2

    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val rr = dice.range(0.9, 1.9)
        val r = rr.toInt() + 1
        for (dx in -r..r) for (dz in -r..r) for (dy in -1..r) {
            val e = (dx * dx + dz * dz + (dy + 0.6) * (dy + 0.6) * 1.4) / (rr * rr)
            if (e > 1.0) continue
            val b = when {
                dy >= 0 && Noise.unit(x + dx, y + dy, z + dz, seed.toInt()) < 0.3 -> "mossy_cobblestone"
                Noise.unit(x + dx, y + dy, z + dz, seed.toInt() + 1) < 0.35 -> "cobblestone"
                else -> stone
            }
            g.wood(x + dx, y + dy, z + dz, Bk.of(b))
        }
    }
}

/** A log washed up on a beach, bleached grey, lying along [dir]; sometimes another across it. */
class Driftwood(x: Int, y: Int, z: Int, seed: Long, private val dir: Dir) : Feature(x, y, z, 4, seed) {
    override fun grow(g: Growth) {
        val dice = Dice(seed)
        val len = dice.int(2, 4)
        val wood = if (dice.chance(0.6)) "stripped_pale_oak_log" else "stripped_birch_log"
        for (k in 0 until len) g.wood(x + dir.dx * k, y, z + dir.dz * k, Bk.of(wood, "axis" to dir.axis))
        if (dice.chance(0.3)) {
            val other = dir.cw
            val mid = dice.int(0, len - 1)
            g.wood(x + dir.dx * mid + other.dx, y + 1, z + dir.dz * mid + other.dz, Bk.of(wood, "axis" to other.axis))
        }
    }
}

/** What an island's soil, sand and shallows grow, column by column. */
object Plants {
    /** A plant for a column of [ground] with its top at [top] (the first block over it), or null for bare ground; two-block plants give both. */
    fun cover(climate: Climate, ground: Ground, x: Int, top: Int, z: Int, seed: Int, lush: Double, flowers: Double, nearWater: Boolean): List<Bk>? {
        val roll = Noise.unit(x, top, z, seed)
        val pick = Noise.unit(x, top, z, seed + 7)
        when (ground) {
            Ground.SAND, Ground.DUNE -> {
                if (climate == Climate.FROZEN) return null
                val p = if (ground == Ground.DUNE) 0.22 else 0.045
                if (roll > p) return null
                return when {
                    climate == Climate.COLD -> listOf(Bk.of("short_grass"))
                    pick < 0.45 -> listOf(Bk.of("short_dry_grass"))
                    // One block tall, for all its name
                    pick < 0.7 -> listOf(Bk.of("tall_dry_grass"))
                    pick < 0.85 && ground == Ground.DUNE -> listOf(Bk.of("bush"))
                    else -> listOf(Bk.of("dead_bush"))
                }
            }
            Ground.GRASS, Ground.COARSE, Ground.PODZOL, Ground.MOSS -> {}
            else -> return null
        }
        if (climate == Climate.FROZEN) return if (roll < 0.08 * lush) listOf(Bk.of("short_grass")) else null
        if (ground == Ground.MOSS && roll < 0.35) return listOf(Bk.of("moss_carpet"))
        // Flowers in drifts, where their own noise says
        if (flowers > 0.62 && roll < 0.55 * (flowers - 0.62) / 0.38 + 0.08) return listOf(flower(climate, x, z, seed, pick))
        val p = 0.62 * lush
        if (roll > p) return null
        val t = roll / p
        return when (climate) {
            Climate.WARM, Climate.LUKEWARM -> when {
                t < 0.5 -> listOf(Bk.of("short_grass"))
                t < 0.68 -> tall("tall_grass")
                t < 0.8 -> listOf(Bk.of("fern"))
                t < 0.9 -> listOf(Bk.of("bush"))
                nearWater -> listOf(Bk.of("firefly_bush"))
                else -> tall("large_fern")
            }
            Climate.TEMPERATE -> when {
                t < 0.55 -> listOf(Bk.of("short_grass"))
                t < 0.74 -> tall("tall_grass")
                t < 0.84 -> listOf(Bk.of("fern"))
                t < 0.9 -> listOf(Bk.of("bush"))
                t < 0.96 -> listOf(Bk.of("sweet_berry_bush", "age" to "3"))
                nearWater -> listOf(Bk.of("firefly_bush"))
                else -> listOf(Bk.of("wildflowers", "flower_amount" to "${1 + (pick * 4).toInt().coerceAtMost(3)}", "facing" to Dir.entries[(pick * 16).toInt() % 4].id))
            }
            else -> when {
                t < 0.35 -> listOf(Bk.of("fern"))
                t < 0.55 -> listOf(Bk.of("short_grass"))
                t < 0.72 -> tall("large_fern")
                t < 0.84 -> tall("tall_grass")
                t < 0.93 -> listOf(Bk.of("sweet_berry_bush", "age" to "3"))
                else -> listOf(Bk.of("bush"))
            }
        }
    }

    private fun tall(id: String): List<Bk> = listOf(Bk.of(id, "half" to "lower"), Bk.of(id, "half" to "upper"))

    private fun flower(climate: Climate, x: Int, z: Int, seed: Int, pick: Double): Bk {
        // Each drift is mostly one flower: pick it by the drift, not the column
        val drift = Noise.unit(Math.floorDiv(x, 6), 0, Math.floorDiv(z, 6), seed + 3)
        val kinds = when (climate) {
            Climate.WARM, Climate.LUKEWARM -> listOf("poppy", "orange_tulip", "red_tulip", "dandelion", "allium", "azure_bluet")
            Climate.TEMPERATE -> listOf("oxeye_daisy", "cornflower", "poppy", "dandelion", "azure_bluet", "wildflowers")
            else -> listOf("allium", "lily_of_the_valley", "cornflower", "azure_bluet", "oxeye_daisy")
        }
        val kind = if (pick < 0.75) kinds[(drift * kinds.size).toInt().coerceAtMost(kinds.size - 1)] else kinds[(pick * 31).toInt() % kinds.size]
        return if (kind == "wildflowers") Bk.of("wildflowers", "flower_amount" to "${1 + (pick * 7).toInt() % 4}", "facing" to Dir.entries[(pick * 13).toInt() % 4].id) else Bk.of(kind)
    }
}

/** What the top of a column is, which decides what it's made of and what grows on it. */
enum class Ground(val dry: Boolean) {
    SAND(true), SHINGLE(true), DUNE(true), GRASS(true), COARSE(true), MOSS(true), PODZOL(true),

    /** A trodden way: a landmark's path. */
    PATH(true),
    BARE(true), SCREE(true), SNOWCAP(true),
    UNDER_SAND(false), UNDER_GRAVEL(false), UNDER_ROCK(false), UNDER_CLAY(false), REEF(false), LAGOON(false),

    /** Whatever's built here lays its own floor. */
    BUILT(true),
}

/** The tree that suits a spot on an island of [climate], [coast] blocks from the sea (the lean is downwind or out to sea). */
fun treeFor(climate: Climate, x: Int, y: Int, z: Int, seed: Long, coast: Double, lean: Double, seaward: Double, pick: Double): Feature =
    when (climate) {
        Climate.WARM -> when {
            coast < 14 && pick < 0.8 -> Palm(x, y, z, seed, seaward)
            pick < 0.3 -> Acacia(x, y, z, seed)
            pick < 0.55 -> Palm(x, y, z, seed, lean)
            else -> Shrub(x, y, z, seed, "jungle")
        }
        Climate.LUKEWARM -> when {
            coast < 10 && pick < 0.65 -> Palm(x, y, z, seed, seaward)
            pick < 0.55 -> WindswornOak(x, y, z, seed, lean)
            pick < 0.7 -> Birch(x, y, z, seed)
            else -> Shrub(x, y, z, seed, "azalea")
        }
        Climate.TEMPERATE -> when {
            pick < 0.58 -> WindswornOak(x, y, z, seed, lean)
            pick < 0.8 -> Birch(x, y, z, seed)
            pick < 0.9 -> Shrub(x, y, z, seed, "oak")
            else -> Shrub(x, y, z, seed, "azalea")
        }
        Climate.COLD -> when {
            pick < 0.68 -> Pine(x, y, z, seed, lean)
            pick < 0.84 -> Pine(x, y, z, seed, lean, small = true)
            pick < 0.93 -> Birch(x, y, z, seed)
            else -> Snag(x, y, z, seed)
        }
        Climate.FROZEN -> when {
            pick < 0.5 -> Pine(x, y, z, seed, lean, small = true)
            pick < 0.8 -> Snag(x, y, z, seed)
            else -> Shrub(x, y, z, seed, "spruce")
        }
    }

/** Square root, never of less than nothing. */
internal fun root(v: Double): Double = sqrt(max(0.0, v))
