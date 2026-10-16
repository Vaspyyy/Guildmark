package io.github.vaspyyy.guildmark.island.plan

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Something built on an island: a lighthouse, a wreck, a hamlet. It's laid out once while the plan is made (levelling
 * its site, claiming its ground so no tree or boulder lands on it), then worked out block by block the first time a
 * chunk draws it ([build]) and drawn chunk by chunk like the land, after the ground and before the trees.
 */
abstract class Landmark(val plan: IslandPlan) {
    protected val terrain: Terrain get() = plan.terrain
    protected val sea: Int get() = plan.sea

    /** Everything it's made of, worked out once. */
    val made: Build by lazy { Build().also { build(it) } }

    /** The columns it may draw in, inclusive. */
    open val minX: Int get() = made.minX
    open val maxX: Int get() = made.maxX
    open val minZ: Int get() = made.minZ
    open val maxZ: Int get() = made.maxZ

    /** Levels its site and claims its ground. Called once, before the ground is classed. */
    open fun lay() {}

    /** Paints the ground under and round it (paths, yards, a boat beach), once the ground is classed. */
    open fun paint() {}

    /** Works out every block it's made of, once the land round it is settled. */
    protected abstract fun build(b: Build)

    /** Draws its share of itself in [canvas]: only what falls in the canvas's columns lands. */
    open fun draw(canvas: IslandCanvas) = made.draw(canvas)

    fun touches(canvas: IslandCanvas): Boolean = canvas.touches(minX, minZ, maxX, maxZ)

    /** The y of the first block over the ground (air or water) in a world column, as the plan leaves it. */
    protected fun top(x: Int, z: Int): Int = terrain.top(x, z)

    /**
     * Shapes the planned ground along [way]: each column is cut down or built up to the height the way stands at
     * there, the way being [width] columns either side of its middle; past that the banks ease back to the land, steep
     * where they're cut into it and gentler where they're built up over it. Columns [keep] says yes to (a house's
     * levelled lot) are left as they are.
     */
    protected fun shape(way: Way, width: Int = 1, keep: (Int, Int) -> Boolean = { _, _ -> false }) {
        val on = HashSet<Long>()
        for (p in way.cells) on.add(Growth.pack(p.first, 0, p.second))
        for ((k, p) in way.cells.withIndex()) {
            val (x, z) = p
            val level = way.heights[k].toDouble()
            terrain.set(x, z, level)
            val reach = width + 4
            for (dx in -reach..reach) for (dz in -reach..reach) {
                val off = max(abs(dx), abs(dz))
                if (off == 0 || on.contains(Growth.pack(x + dx, 0, z + dz)) || keep(x + dx, z + dz)) continue
                val here = terrain.h(x + dx, z + dz)
                val eased = when {
                    off <= width -> level
                    here > level -> min(here, level + 2.0 * (off - width))
                    else -> max(here, level - (off - width))
                }
                terrain.set(x + dx, z + dz, eased)
            }
        }
    }

    /** A way over the land: its columns one step apart, and how high over the sea each stands once it's cut or built. */
    class Way(val cells: List<Pair<Int, Int>>, val heights: IntArray) {
        val size: Int get() = cells.size
        fun isEmpty(): Boolean = cells.isEmpty()
        fun last(): Pair<Int, Int> = cells.last()

        companion object {
            val NONE = Way(emptyList(), IntArray(0))
        }
    }

    /**
     * The easiest ways over dry land from [from], standing [level] blocks over the sea, to everywhere it can reach,
     * 4-connected: climbing or dropping a block a step at most (so a stair can take it), keeping off every column
     * [blocked] says no to, and dearer the deeper it must be cut into the ground or the higher built up over it, so it
     * winds down a slope or cuts a stair into the rock rather than leaping off a cliff. It wanders a little ([seed]) so
     * it doesn't run ruler-straight. [to] gives the way to any column, arriving at the ground's own height there.
     */
    protected inner class Ways(from: Pair<Int, Int>, level: Int, seed: Int, blocked: (Int, Int) -> Boolean) {
        private val t = terrain
        private val heights: Int
        private val ax: Int
        private val az: Int
        private val wide: Int
        private val deep: Int
        private val cost: DoubleArray
        private val back: IntArray

        init {
            // Only the island's dry land, and the way's own start, need searching, up to the land's highest
            var x0 = from.first
            var x1 = from.first
            var z0 = from.second
            var z1 = from.second
            var top = level
            for (x in t.x0 until t.x0 + t.size) for (z in t.z0 until t.z0 + t.size) {
                if (!t.dry(x, z)) continue
                x0 = min(x0, x)
                x1 = max(x1, x)
                z0 = min(z0, z)
                z1 = max(z1, z)
                top = max(top, t.h(x, z).roundToInt())
            }
            heights = top + 3
            ax = x0
            az = z0
            wide = x1 - x0 + 1
            deep = z1 - z0 + 1
            val n = wide * deep * heights
            cost = DoubleArray(n) { Double.POSITIVE_INFINITY }
            back = IntArray(n) { -1 }
            class Node(val s: Int, val c: Double)
            val open = PriorityQueue<Node>(compareBy<Node> { it.c }.thenBy { it.s })
            val first = state(from.first, from.second, level.coerceIn(1, heights - 1))
            cost[first] = 0.0
            open.add(Node(first, 0.0))
            while (open.isNotEmpty()) {
                val node = open.poll()
                if (node.c > cost[node.s]) continue
                val cell = node.s / heights
                val h = node.s % heights
                val x = ax + cell / deep
                val z = az + cell % deep
                for (d in Dir.entries) {
                    val nx = x + d.dx
                    val nz = z + d.dz
                    if (nx < ax || nz < az || nx >= ax + wide || nz >= az + deep || !t.dry(nx, nz) || blocked(nx, nz)) continue
                    // A column stands at one height, so the way never comes back over its own track
                    if (behind(node.s, nx, nz)) continue
                    val ground = t.h(nx, nz).roundToInt()
                    val step = 1.0 + 0.5 * Noise.unit(nx, 0, nz, seed)
                    for (h2 in max(1, h - 1)..min(heights - 1, h + 1)) {
                        if (abs(h2 - ground) > DEEPEST) continue
                        val c = node.c + step + (if (h2 != h) 0.25 else 0.0) +
                            FILL * max(0, h2 - ground) + CUT * max(0, ground - h2)
                        val s = state(nx, nz, h2)
                        if (c >= cost[s]) continue
                        cost[s] = c
                        back[s] = node.s
                        open.add(Node(s, c))
                    }
                }
            }
        }

        private fun state(x: Int, z: Int, h: Int): Int = ((x - ax) * deep + (z - az)) * heights + h

        /** Whether the way to state [s] already passed over ([x], [z]) in its last few dozen steps. */
        private fun behind(s: Int, x: Int, z: Int): Boolean {
            val cell = (x - ax) * deep + (z - az)
            var k = s
            var n = 0
            while (k >= 0 && n++ < 32) {
                if (k / heights == cell) return true
                k = back[k]
            }
            return false
        }

        private fun arrival(x: Int, z: Int): Int {
            if (x < ax || z < az || x >= ax + wide || z >= az + deep) return -1
            return state(x, z, t.h(x, z).roundToInt().coerceIn(1, heights - 1))
        }

        /** What the way to ([x], [z]) costs; infinite if there's none. */
        fun cost(x: Int, z: Int): Double = arrival(x, z).let { if (it < 0) Double.POSITIVE_INFINITY else cost[it] }

        /** The way to ([x], [z]), first column the start and last the end; [Way.NONE] if there's none. */
        fun to(x: Int, z: Int): Way {
            var s = arrival(x, z)
            if (s < 0 || cost[s].isInfinite()) return Way.NONE
            val cells = ArrayList<Pair<Int, Int>>()
            val hs = ArrayList<Int>()
            while (s >= 0) {
                val cell = s / heights
                cells.add(Pair(ax + cell / deep, az + cell % deep))
                hs.add(s % heights)
                s = back[s]
            }
            cells.reverse()
            hs.reverse()
            return Way(cells, hs.toIntArray())
        }
    }

    companion object {
        /** What a way pays for each block it's built up over the ground, and each it's cut down into it, a column. */
        private const val FILL = 1.5
        private const val CUT = 1.0

        /** How deep a way may be cut into the ground, or how high built up over it, at most. */
        private const val DEEPEST = 7

        /** The way from [a] to [b], one step apart. */
        fun dirTo(a: Pair<Int, Int>, b: Pair<Int, Int>): Dir = when {
            b.first > a.first -> Dir.E
            b.first < a.first -> Dir.W
            b.second > a.second -> Dir.S
            else -> Dir.N
        }
    }
}

/**
 * Every block a landmark is made of, its chests and who's there, in the world's frame. Built once, drawn into any
 * number of chunks. A [soft] block (a plant, a carpet of moss) only lands where there's room for it.
 */
class Build {
    private val blocks = LinkedHashMap<Long, Bk>()
    private val softs = LinkedHashMap<Long, Bk>()
    private val chests = ArrayList<Chest>()
    private val loots = ArrayList<Loot>()
    val spawns = ArrayList<IslandPlan.Spawn>()
    /** Where the island's own sounds come from (see [SoundSpot]): never drawn, sent to whoever comes into its waters. */
    val sounds = ArrayList<Sound>()

    class Sound(val spot: SoundSpot, val x: Int, val y: Int, val z: Int)

    class Chest(val x: Int, val y: Int, val z: Int, val facing: Dir, val loot: String, val barrel: Boolean)
    class Loot(val x: Int, val y: Int, val z: Int, val table: String)

    var minX = Int.MAX_VALUE
        private set
    var maxX = Int.MIN_VALUE
        private set
    var minZ = Int.MAX_VALUE
        private set
    var maxZ = Int.MIN_VALUE
        private set

    private fun widen(x: Int, z: Int) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (z < minZ) minZ = z
        if (z > maxZ) maxZ = z
    }

    fun set(x: Int, y: Int, z: Int, b: Bk) {
        val p = Growth.pack(x, y, z)
        blocks[p] = b
        softs.remove(p)
        widen(x, z)
    }

    fun set(x: Int, y: Int, z: Int, name: String, vararg props: Pair<String, String>) = set(x, y, z, Bk.of(name, *props))

    /** A block that lands only where there's room (air, water or a plant), and never over one already set. */
    fun soft(x: Int, y: Int, z: Int, b: Bk) {
        val p = Growth.pack(x, y, z)
        if (blocks.containsKey(p)) return
        softs[p] = b
        widen(x, z)
    }

    fun air(x: Int, y: Int, z: Int) = set(x, y, z, Bk.AIR)

    fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, b: Bk) {
        for (x in min(x0, x1)..max(x0, x1)) for (y in min(y0, y1)..max(y0, y1)) for (z in min(z0, z1)..max(z0, z1)) set(x, y, z, b)
    }

    /** What's been set here so far, if anything. */
    fun at(x: Int, y: Int, z: Int): Bk? = blocks[Growth.pack(x, y, z)]

    fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: String, barrel: Boolean = false) {
        blocks.remove(Growth.pack(x, y, z))
        chests.add(Chest(x, y, z, facing, loot, barrel))
        widen(x, z)
    }

    /** Fills whatever holds things at [x], [y], [z] (a pot, suspicious sand) from the loot table [table]. */
    fun loot(x: Int, y: Int, z: Int, table: String) {
        loots.add(Loot(x, y, z, table))
    }

    fun spawn(what: String, x: Double, y: Double, z: Double, yaw: Float = 0f) {
        spawns.add(IslandPlan.Spawn(what, x, y, z, yaw))
        widen(floor(x).toInt(), floor(z).toInt())
    }

    /** A place [spot]'s sounds come from: the lamp's turning and its foghorn, a wreck's groaning hull. */
    fun sound(spot: SoundSpot, x: Int, y: Int, z: Int) {
        sounds.add(Sound(spot, x, y, z))
    }

    fun draw(canvas: IslandCanvas) {
        for ((p, b) in blocks) {
            val x = Growth.x(p)
            val z = Growth.z(p)
            if (canvas.contains(x, z)) canvas.set(x, Growth.y(p), z, b)
        }
        for ((p, b) in softs) {
            val x = Growth.x(p)
            val z = Growth.z(p)
            val y = Growth.y(p)
            if (canvas.contains(x, z) && canvas.open(x, y, z)) canvas.set(x, y, z, b)
        }
        for (c in chests) if (canvas.contains(c.x, c.z)) canvas.chest(c.x, c.y, c.z, c.facing, c.loot, c.barrel)
        for (l in loots) if (canvas.contains(l.x, l.z)) canvas.loot(l.x, l.y, l.z, l.table)
        for (s in spawns) if (canvas.contains(floor(s.x).toInt(), floor(s.z).toInt())) canvas.spawn(s.what, s.x, s.y, s.z, s.yaw)
    }
}

/**
 * What makes a sound of its own on an island, and where (see Build.sound): a client plays each the way its kind does,
 * the foghorn on a wet night, the creak of a wreck's timbers, the whistle from a smugglers' lookout.
 */
enum class SoundSpot {
    /** A lighthouse's lamp: its turning all night, the foghorn in rain and murk, the fog bell by day in rain. */
    LIGHTHOUSE,
    /** The heart of a gull colony: its clamour by day, quieter at night, squabbles breaking out. */
    COLONY,
    /** A pier's head: its ropes creaking. */
    PIER,
    /** Boats tied up or drawn up: knocking together and creaking. */
    MOORING,
    /** A wreck's hull, groaning on the rocks. */
    HULL,
    /** A torn sail still hanging from a wreck's spar. */
    SAIL,
    /** A wreck's bell, clunking with the swell. */
    SHIP_BELL,
    /** Deep in a sea cave: water dripping. */
    CAVE,
    /** A camp's fire and its folk stirring. */
    CAMP,
    /** A lookout, whistling signals in the dark. */
    LOOKOUT,
    /** A drowned shrine: its drone, its chimes, its whispers by night. */
    SHRINE,
    /** Where a drowned shrine's air bubbles up through the water. */
    BUBBLES,
}

/** What an island of the plan's kind has built on it. */
fun landmarksFor(plan: IslandPlan): List<Landmark> = when (plan.spec.kind) {
    IslandKind.LIGHTHOUSE -> listOf(Lighthouse(plan))
    IslandKind.WRECK -> listOf(Wreck(plan))
    IslandKind.COVE -> listOf(CoveDen(plan))
    IslandKind.SHRINE -> listOf(Shrine(plan))
    IslandKind.STACKS -> listOf(Rookery(plan))
    IslandKind.HAVEN -> listOf(Haven(plan))
    else -> emptyList()
}
