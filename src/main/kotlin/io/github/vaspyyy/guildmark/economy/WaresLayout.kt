package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.plan.Look
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How a market display sets out what it holds. Each of its goods has a [Lane]: the places its items take one after
 * another, so the first [Lane.full] make a neat display at the town's usual stock (a crate filled to the rim, a full
 * rack) and the rest heap it over the rim. A heap is dropped item by item onto a height field of the crate's floor:
 * each comes to rest on what's under it, rolls off a slope too steep to hold it, and lies tilted as the heap slopes
 * under it, turned its own way, so it looks piled rather than laid on a grid; racks, hooks, stacks and rows of standing
 * things are set out by hand. Whatever ends up buried out of sight under the items after it is marked, to be left out.
 * The same display, by its position's seed, always heaps the same.
 *
 * Pure arithmetic in the display's own frame (x east, y up, z south, its front to the north; worked in pixels, a
 * sixteenth of a block, and given in blocks), shared by the server, which counts how many items a display shows (see
 * [count]), and the client, which draws them.
 */
object WaresLayout {
    /** How an item's model is laid before it's turned: on its back, standing facing the front, or a block's item squashed flat. */
    enum class Pose { FLAT, STAND, BLOCK }

    /**
     * One item of a display: its middle at [x], [y], [z] (blocks), turned [yaw] about up, then tipped [pitch] about
     * across and [roll] about its length (degrees), laid as [pose] with its picture spun [spin] in its own plane, and
     * scaled [sx], [sy], [sz]. [rank] (0 to 1) says how soon it's left out when the display is seen from far off.
     */
    class Slot(
        val x: Float, val y: Float, val z: Float,
        val yaw: Float, val pitch: Float, val roll: Float, val spin: Float,
        val pose: Pose, val sx: Float, val sy: Float, val sz: Float,
        val rank: Float,
    )

    /**
     * One good's places on a display, in the order they fill: [full] of them at the town's usual stock, all of them in a
     * glut. Slot i is out of sight under the others once [buried]`[i]` of them are there (never if Int.MAX_VALUE).
     */
    class Lane(val good: String, val slots: List<Slot>, val full: Int, val buried: IntArray) {
        /** How many it holds at most, in a glut. */
        val max: Int get() = slots.size
    }

    /** The lanes of a display of [look] holding [goods] (by id; the first three), for the display whose position gives [seed]. */
    fun of(look: Look, goods: List<String>, seed: Long): List<Lane> {
        if (goods.isEmpty()) return listOf()
        val r = Random(seed * 0x2545F4914F6CDD1DL + look.ordinal)
        val shown = goods.take(3)
        return when (look) {
            Look.BASKET -> basket(shown, r)
            Look.FISH -> fish(shown, r)
            Look.MEAT -> meat(shown, r)
            Look.CLOTH -> cloth(shown, r)
            Look.ARROWS -> arrows(shown, r)
            Look.CANDLES -> candles(shown, r)
            Look.BREAD -> bread(shown, r)
            Look.SACKS -> sacks(shown, r)
        }
    }

    /**
     * How many items show in a lane holding [full] at the usual stock and [max] at most, for a market holding [fill]
     * times what its town likes to keep: none when it's out (or the fill is no number), [full] when it holds that,
     * [max] in a glut (twice it).
     */
    fun count(full: Int, max: Int, fill: Double): Double =
        if (!(fill > 0.0)) 0.0 else if (fill <= 1.0) full * fill else full + (max - full) * min(1.0, fill - 1.0)

    /** The full and most counts of each lane of a display of [look] holding [goods], in pairs: the same wherever it stands. */
    fun capacity(look: Look, goods: List<String>): IntArray = capacities.getOrPut(look.id + ":" + goods.joinToString(",")) {
        val lanes = of(look, goods, 0L)
        IntArray(lanes.size * 2) { if (it % 2 == 0) lanes[it / 2].full else lanes[it / 2].max }
    }

    private val capacities = ConcurrentHashMap<String, IntArray>()

    // ---- The goods as a display lays them ----

    /**
     * A kind of item as a display lays it, from its picture (16 pixels a side, drawn [side] pixels big): [spin] turns the
     * picture so its length runs up it, and it's then [long] by [wide] of the picture's pixels, its ends [foot] and [head]
     * from the picture's middle. [thick] is how far a layer of them rises (pixels).
     */
    private class Item(val side: Float, val spin: Float, long: Float, wide: Float, val thick: Float, foot: Float = long / 2f, head: Float = long / 2f) {
        val long = long * side / 16f
        val wide = wide * side / 16f
        val foot = foot * side / 16f
        val head = head * side / 16f
    }

    // Measured from the game's own pictures, turned so their length runs up them
    private val ITEMS = mapOf(
        "wheat" to Item(7.0f, -45f, 20f, 8f, 1.2f, foot = 10.4f, head = 9.7f),
        "bread" to Item(5.6f, -45f, 17.3f, 9.5f, 1.8f),
        "carrot" to Item(5.0f, -45f, 17.3f, 7f, 1.1f),
        "potato" to Item(4.6f, -45f, 12.3f, 9.5f, 1.6f),
        "beetroot" to Item(4.8f, 0f, 12f, 10f, 1.6f),
        "apple" to Item(4.6f, 0f, 14f, 12f, 2.0f),
        "cod" to Item(5.6f, -45f, 16.6f, 8.8f, 1.1f),
        "salmon" to Item(5.6f, -45f, 16.6f, 8.8f, 1.1f),
        "beef" to Item(5.8f, -45f, 14.4f, 9.5f, 1.0f, foot = 7.6f, head = 6.9f),
        "pork" to Item(5.8f, -45f, 14.4f, 8.8f, 1.0f, foot = 7.6f, head = 6.9f),
        "mutton" to Item(5.8f, -45f, 17.3f, 9.5f, 1.2f, foot = 9f, head = 8.3f),
        "chicken" to Item(5.6f, 0f, 14f, 13f, 1.8f, foot = 7f, head = 7f),
        "honey" to Item(5.4f, 0f, 13f, 9f, 1.4f, foot = 7f, head = 6f),
        "bottle" to Item(5.4f, 0f, 13f, 9f, 1.4f, foot = 7f, head = 6f),
        "candle" to Item(5.4f, 0f, 15f, 7f, 1.4f, foot = 8f, head = 7f),
        "string" to Item(4.6f, 0f, 14f, 13f, 0.7f),
        "leather" to Item(7.2f, 0f, 14f, 12f, 0.45f),
        "arrow" to Item(9.6f, 135f, 17.3f, 4.5f, 0.6f, foot = 9f, head = 8.3f),
        "feather" to Item(6.6f, -45f, 15.9f, 6.7f, 0.6f, foot = 8.3f, head = 7.6f),
        "flint" to Item(4.0f, 0f, 13f, 13f, 1.2f),
        "iron" to Item(4.8f, 0f, 16f, 12f, 1.4f),
        "coal" to Item(4.2f, 0f, 13f, 13f, 1.3f),
    )
    private val PLAIN = Item(5f, 0f, 14f, 12f, 1.5f)

    private fun item(good: String): Item = ITEMS[good] ?: PLAIN

    // ---- The looks ----

    /** A wicker basket: one good heaped in it, or two or three side by side. */
    private fun basket(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val b = Builder(goods[i])
        val pile = Pile(BASKET.part(goods.size, i), BASKET, floor = 3.6f, rim = 6.5f, lean = 20f)
        b.one().heap(pile, item(goods[i]), null, layers = 2, mound = if (goods.size == 1) 10 else 4, r)
        b.lane()
    }

    /** A crate of fish on ice: a row of each good across it, heads to the front, laid on in layers and heaped in a glut. */
    private fun fish(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val b = Builder(goods[i])
        b.one().heap(Pile(CRATE.part(goods.size, i, along = false), CRATE, floor = 3f, rim = 6f, lean = 15f), item(goods[i]), 0f, layers = 3, mound = 6, r)
        b.lane()
    }

    /** Meat on a rack: two pieces to each of a good's hooks, then laid on the board below and heaped there in a glut. */
    private fun meat(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val kind = item(goods[i])
        val b = Builder(goods[i])
        val hooks = when (goods.size) {
            1 -> HOOKS
            2 -> if (i == 0) HOOKS.take(2) else HOOKS.drop(2)
            else -> listOf(HOOKS.take(1), HOOKS.subList(1, 3), HOOKS.drop(3))[i]
        }
        val hanging = b.part()
        for (k in 0 until 2) for (x in hooks) {
            // The second piece on a hook hangs in front of the first, a little lower, turned its own way
            val y = HOOK - 0.3f - k * 0.9f - kind.head
            hanging.stand(x + r.jitter(0.25f), y, HUNG - k * 1.1f, kind, yaw = r.jitter(18f), pitch = r.jitter(5f) - 4f, roll = r.jitter(7f), r)
        }
        val board = b.part()
        board.heap(Pile(Area(1.8f, 2.6f, 14.2f, 11f).part(goods.size, i), BUTCHER, floor = 1.5f, rim = -1.5f, lean = 10f), kind, null, layers = 1, mound = 5, r)
        b.lane(full = hanging.size + board.full)
    }

    /** Cloth folded in stacks on a board, hides in a pile, string coiled in a heap. */
    private fun cloth(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val good = goods[i]
        val lane = Area(1.8f, 2.8f, 14.2f, 13.2f).part(goods.size, i)
        val (x0, x1) = lane.x0 to lane.x1
        val b = Builder(good)
        val part = b.one()
        when (good) {
            "wool" -> {
                val xs = if (x1 - x0 > 9f) listOf(x0 + (x1 - x0) * 0.26f, x0 + (x1 - x0) * 0.74f) else listOf((x0 + x1) / 2f)
                part.stacks(xs.flatMap { x -> listOf(x to 5.4f, x to 10.6f) }, BOLT_HIGH, full = 3, max = 5, yawJitter = 5f, null, r)
            }
            "leather" -> part.stacks(listOf((x0 + x1) / 2f to 8f), item(good).thick, full = 5, max = 9, yawJitter = 16f, item(good), r)
            else -> part.heap(Pile(lane, TRESTLE, floor = STACK_BASE, rim = STACK_BASE - 3f, lean = 8f), item(good), null, layers = 1, mound = 4, r)
        }
        b.lane()
    }

    /** A bin of arrows standing fletching up and a basket of feathers behind, flint in a tray at the front. */
    private fun arrows(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val good = goods[i]
        val b = Builder(good)
        val part = b.one()
        when (good) {
            "arrow" -> part.bundle(11.5f, 10.5f, 1.6f, floor = 1.8f, item(good), full = 9, max = 14, lean = 7f, faceFront = 40f, r)
            "feather" -> part.bundle(4.5f, 10.5f, 1.4f, floor = 2.6f, item(good), full = 7, max = 11, lean = 10f, faceFront = 55f, r)
            else -> part.heap(Pile(TRAY, TRAY, floor = 1.6f, rim = 2.4f, lean = 15f), item(good), null, layers = 1, mound = 5, r)
        }
        b.lane()
    }

    /** A stepped tray with a column of each good standing in rows, two to a row; crowded in a glut. */
    private fun candles(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val kind = item(goods[i])
        val lane = Area(1.6f, 2.6f, 14.4f, 14f).part(goods.size, i)
        val mid = (lane.x0 + lane.x1) / 2f
        val half = min(1.2f, (lane.x1 - lane.x0) / 4f)
        // The front step first, its front row first, so the last few left stand where they're seen
        val rows = listOf(3.6f to STEP_LOW, 6.4f to STEP_LOW, 9.6f to STEP_HIGH, 12.4f to STEP_HIGH)
        val spots = ArrayList<Triple<Float, Float, Float>>()
        for ((z, y) in rows) for (x in listOf(mid - half, mid + half)) spots.add(Triple(x, y, z))
        val full = spots.size
        // A glut squeezes one more into the middle of each row
        for ((z, y) in rows) spots.add(Triple(mid, y, z + 0.7f))
        val b = Builder(goods[i])
        val part = b.one()
        for ((x, y, z) in spots) part.stand(x + r.jitter(0.25f), y + kind.foot, z + r.jitter(0.3f), kind, yaw = r.jitter(16f), pitch = r.jitter(3f), roll = r.jitter(4f), r)
        b.lane(full = full)
    }

    /** A baker's shelf: loaves lengthways on the bottom board, front to back, then on the shelf above; heaped on both in a glut. */
    private fun bread(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val kind = item(goods[i])
        val lane = Area(1.9f, 2.4f, 14.1f, 13.4f).part(goods.size, i)
        val (x0, x1) = lane.x0 to lane.x1
        val b = Builder(goods[i])
        val lowPile = Pile(lane, Area(1.9f, 2.2f, 14.1f, 13.4f), floor = 1.5f, rim = -1.5f, lean = 8f)
        val highPile = Pile(Area(x0, 7.6f, x1, 13.2f), Area(1.9f, 7.2f, 14.1f, 13.4f), floor = SHELF, rim = SHELF - 3f, lean = 8f)
        val low = b.part()
        low.heap(lowPile, kind, 90f, layers = 1, mound = 0, r, frontFirst = true)
        val high = b.part()
        high.heap(highPile, kind, 90f, layers = 1, mound = 0, r, frontFirst = true)
        val lowFull = low.size
        val highFull = high.size
        // The glut: loaves piled on the front of the bottom board (under the shelf there's no room) and on the shelf
        low.heap(lowPile, kind, 90f, layers = 0, mound = 4, r, middle = (x0 + x1) / 2f to 4.6f)
        high.heap(highPile, kind, 90f, layers = 0, mound = 3, r)
        val order = ArrayList<Int>()
        repeat(lowFull) { order.add(0) }
        repeat(highFull) { order.add(1) }
        for (k in 0 until max(low.size - lowFull, high.size - highFull)) {
            if (k < low.size - lowFull) order.add(0)
            if (k < high.size - highFull) order.add(1)
        }
        b.lane(order, full = lowFull + highFull)
    }

    /** Two open sacks with sheaves standing in their mouths, laid along their fronts in a glut. */
    private fun sacks(goods: List<String>, r: Random): List<Lane> = goods.indices.map { i ->
        val kind = item(goods[i])
        val b = Builder(goods[i])
        val mine = if (goods.size == 1) MOUTHS else listOf(MOUTHS[i % 2])
        val bundles = mine.map { (x, z, y) -> b.part().also { it.bundle(x, z, 1.4f, y, kind, full = 5, max = 7, lean = 9f, faceFront = 50f, r) } }
        val spill = b.part()
        // On the ground in front of them, which runs on flat past where they're dropped
        spill.heap(Pile(Area(1.5f, 0.8f, 14.5f, 2.4f), Area(0.5f, 0.3f, 15.5f, 3.2f), floor = 0f, rim = 0f), kind, 90f, layers = 0, mound = 4 * mine.size, r)
        // The sacks fill side by side, five sheaves to a full sack, then the glut stands two more in each and spills
        val order = ArrayList<Int>()
        for (k in 0 until 7) for (p in bundles.indices) order.add(p)
        repeat(spill.size) { order.add(bundles.size) }
        b.lane(order, full = 5 * bundles.size)
    }

    // ---- Laying out ----

    /** The heights of the tray's two steps and the bread shelf's upper board (pixels). */
    private const val STEP_LOW = 2f
    private const val STEP_HIGH = 5f
    private const val SHELF = 8.5f

    /** The meat rack's hooks: where they hang along it, the height of their tips and how far back the meat hangs (pixels). */
    private val HOOKS = listOf(12.5f, 9.5f, 6.5f, 3.5f)
    private const val HOOK = 12.4f
    private const val HUNG = 12.2f

    /** The grain sacks' mouths: the middle of each and the height the sheaves stand in it from (pixels). */
    private val MOUTHS = listOf(Triple(4.5f, 8.5f, 5.0f), Triple(11.5f, 7.5f, 4.4f))

    /** The cloth board's top, and a folded bolt's size (pixels). */
    private const val STACK_BASE = 2.6f
    private const val BOLT_WIDE = 5.6f
    private const val BOLT_LONG = 4.4f
    private const val BOLT_HIGH = 1.4f

    /** The floors things are heaped on: inside the basket's and the crate's walls, the butcher's board, the cloth board's top, inside the flint tray. */
    private val BASKET = Area(2.2f, 3.2f, 13.8f, 12.8f)
    private val CRATE = Area(2.2f, 2.2f, 13.8f, 13.8f)
    private val BUTCHER = Area(1.2f, 2.2f, 14.8f, 11.8f)
    private val TRESTLE = Area(1.2f, 2.2f, 14.8f, 13.8f)
    private val TRAY = Area(2.8f, 2.4f, 13.2f, 7.0f)

    /** A rectangle of a display's floor, [x0] to [x1] across and [z0] to [z1] front to back (pixels). */
    private class Area(val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
        /** Whether [x], [z] is in it, at least [m] in from its edges. */
        fun has(x: Float, z: Float, m: Float = 0f): Boolean = x in x0 + m..x1 - m && z in z0 + m..z1 - m

        /** The [i]th of [n] equal parts of it with a little gap between: east to west along x (the customer's left first), or front to back. */
        fun part(n: Int, i: Int, along: Boolean = true): Area {
            if (n <= 1) return this
            val k = if (along) n - 1 - i else i
            if (!along) {
                val w = (z1 - z0) / n
                return Area(x0, z0 + w * k + 0.2f, x1, z0 + w * (k + 1) - 0.2f)
            }
            val w = (x1 - x0) / n
            return Area(x0 + w * k + 0.2f, z0, x0 + w * (k + 1) - 0.2f, z1)
        }
    }

    private fun Random.jitter(by: Float): Float = (nextFloat() * 2f - 1f) * by

    /** A good's lane in the making: one or more parts (a rack's hooks and its board), merged in the order they fill. */
    private class Builder(val good: String) {
        val parts = ArrayList<Part>()
        private var only: Part? = null

        fun part(): Part = Part().also { parts.add(it) }

        /** The part of a lane that has only one. */
        fun one(): Part = only ?: part().also { only = it }

        /** The lane: its parts' slots taken in [order] (by part; one after another if null), the first [full] making it full. */
        fun lane(order: List<Int>? = null, full: Int? = null): Lane {
            val o = order ?: parts.flatMapIndexed { p, part -> List(part.size) { p } }
            val slots = ArrayList<Slot>()
            // Where each part's k'th slot falls in the lane, to turn the counts a part buries its slots at into the lane's
            val at = parts.map { IntArray(it.size) }
            val taken = IntArray(parts.size)
            for ((g, p) in o.withIndex()) {
                at[p][taken[p]] = g
                slots.add(parts[p].slots[taken[p]])
                taken[p]++
            }
            taken.fill(0)
            val buried = IntArray(o.size)
            for ((g, p) in o.withIndex()) {
                val local = parts[p].buried(taken[p]++)
                buried[g] = if (local == Int.MAX_VALUE || local > at[p].size) Int.MAX_VALUE else at[p][local - 1] + 1
            }
            return Lane(good, slots, (full ?: parts.sumOf { it.full }).coerceAtMost(slots.size), buried)
        }
    }

    /** One part of a lane: its slots, how many of them make it full, and for those dropped on a [Pile], when each is buried. */
    private class Part {
        val slots = ArrayList<Slot>()
        var full = 0
        private val starts = ArrayList<Pair<Pile, Int>>()
        private val dropped = HashMap<Int, Pair<Pile, Int>>()

        val size: Int get() = slots.size

        /** How many of this part's slots are there when its [k]th is buried out of sight (Int.MAX_VALUE: never). */
        fun buried(k: Int): Int {
            val (pile, i) = dropped[k] ?: return Int.MAX_VALUE
            val local = pile.buried(i)
            if (local == Int.MAX_VALUE) return Int.MAX_VALUE
            // A pile's items are this part's from where its first one went
            return starts.first { it.first === pile }.second + local
        }

        /**
         * Neat [layers] on a grid across [pile] (each laid in an order of its own, each on the one under it and offset half
         * a step), then a [mound] dropped round its [middle] to roll down the slopes. [align] lays each item's length that
         * way (as a yaw: 0 front to back, 90 across) give or take a little; null turns each any way. [frontFirst] lays
         * each layer from the front back. A pile laid on again carries on from what's on it.
         */
        fun heap(
            pile: Pile, item: Item, align: Float?, layers: Int, mound: Int, r: Random,
            frontFirst: Boolean = false, middle: Pair<Float, Float>? = null,
        ) {
            if (starts.none { it.first === pile }) starts.add(pile to slots.size)
            val stepA = if (align != null) item.long * 1.02f else (item.long + item.wide) * 0.5f * 0.92f
            val stepC = if (align != null) item.wide * 0.98f else stepA
            // Lying across, the grid runs their length along x
            val across = align != null && abs(align - 90f) < 1f
            val spanX = pile.x1 - pile.x0
            val spanZ = pile.z1 - pile.z0
            val nx = max(1, (spanX / if (across) stepA else stepC).roundToInt())
            val nz = max(1, (spanZ / if (across) stepC else stepA).roundToInt())
            val gx = spanX / nx
            val gz = spanZ / nz
            var made = 0
            for (layer in 0 until layers) {
                val offset = layer % 2 == 1
                val cells = ArrayList<Pair<Float, Float>>()
                val cx = if (offset && nx > 1) nx - 1 else nx
                val cz = if (offset && nz > 1) nz - 1 else nz
                for (ix in 0 until cx) for (iz in 0 until cz) {
                    val x = pile.x0 + gx * (ix + if (offset && nx > 1) 1f else 0.5f)
                    val z = pile.z0 + gz * (iz + if (offset && nz > 1) 1f else 0.5f)
                    cells.add(x to z)
                }
                if (frontFirst) cells.sortBy { it.second + r.nextFloat() * 0.1f } else cells.shuffle(r)
                for ((x, z) in cells) {
                    val yaw = if (align != null) align + r.jitter(9f) else r.nextFloat() * 360f
                    // Laid end to end, they shift less along their length than across it
                    val jx = if (align == null || !across) 0.12f else 0.05f
                    val jz = if (align == null || across) 0.12f else 0.05f
                    drop(pile, item, x + r.jitter(gx * jx), z + r.jitter(gz * jz), yaw, tilt = 4f + layer * 3f, r)
                    made++
                }
            }
            full += made
            // The glut: dropped near the middle, each rolling off any slope too steep to hold it
            val (mx, mz) = middle ?: (((pile.x0 + pile.x1) / 2f) to ((pile.z0 + pile.z1) / 2f))
            repeat(mound) {
                var x = mx + gauss(r) * spanX * 0.2f
                var z = mz + gauss(r) * spanZ * 0.2f
                val yaw = if (align != null) align + r.jitter(24f) else r.nextFloat() * 360f
                for (step in 0 until 10) {
                    val here = pile.rest(x, z, yaw, item)
                    var best = here
                    var bx = x
                    var bz = z
                    for (d in 0 until 8) {
                        val a = d * PI.toFloat() / 4f + r.nextFloat() * 0.3f
                        val tx = x + cos(a) * ROLL
                        val tz = z + sin(a) * ROLL
                        if (!pile.holds(tx, tz, item)) continue
                        val h = pile.rest(tx, tz, yaw, item)
                        if (h < best) {
                            best = h
                            bx = tx
                            bz = tz
                        }
                    }
                    if (here - best <= REPOSE * ROLL) break
                    x = bx
                    z = bz
                }
                drop(pile, item, x.coerceIn(pile.x0, pile.x1), z.coerceIn(pile.z0, pile.z1), yaw, tilt = 10f, r)
            }
        }

        /** An item laid at [x], [z] on [pile], its length along [yaw], tilted as the pile slopes under it and up to [tilt] more. */
        private fun drop(pile: Pile, item: Item, x: Float, z: Float, yaw: Float, tilt: Float, r: Random) {
            val a = yaw * DEG
            val dx = sin(a)
            val dz = cos(a)
            val hl = item.long * 0.42f
            val hw = item.wide * 0.4f
            val rest = pile.rest(x, z, yaw, item)
            val ahead = pile.under(x + dx * hl, z + dz * hl)
            val behind = pile.under(x - dx * hl, z - dz * hl)
            // Its right as it lies (the way its local x is turned to)
            val right = pile.under(x + dz * hw, z - dx * hw)
            val left = pile.under(x - dz * hw, z + dx * hw)
            val mean = (ahead + behind + right + left) / 4f
            // Tipped up at whichever end the pile is higher (pitch about its local x tips its far end down), and rolled
            // toward its lower side, less than the slope under it: it settles into the hollows as much as it rides the tops
            var pitch = -atan((ahead - behind) / max(1f, 2 * hl)) / DEG * SETTLE
            var roll = atan((right - left) / max(item.long * 0.5f, 2 * hw)) / DEG * SETTLE
            // Set out to be seen: its face turned [Pile.lean] toward the front, whichever way it lies, its front edge
            // lifted clear of what's under it
            pitch -= pile.lean * cos(a)
            roll -= pile.lean * sin(a)
            val lift = sin(pile.lean * DEG) * (abs(dz) * hl + abs(dx) * hw) * 0.6f
            val y = max(rest - item.thick * 0.35f, (rest + mean) / 2f) + item.thick * 0.5f + lift
            dropped[slots.size] = pile to pile.count
            pile.stamp(x, z, yaw, item, y + item.thick * 0.5f)
            slots.add(
                slot(
                    x, y, z, yaw, (pitch + r.jitter(tilt)).coerceIn(-TIP, TIP), (roll + r.jitter(tilt)).coerceIn(-TIP, TIP),
                    item.spin, Pose.FLAT, item.side / 16f, r,
                ),
            )
        }

        /** An item standing with its middle at [x], [y], [z], facing the front turned [yaw], leaning [pitch] forward and [roll] aside. */
        fun stand(x: Float, y: Float, z: Float, item: Item, yaw: Float, pitch: Float, roll: Float, r: Random) {
            slots.add(slot(x, y, z, yaw, pitch, roll, item.spin, Pose.STAND, item.side / 16f, r))
        }

        /**
         * Items standing in a bundle in an opening at [x], [z] of [radius] (pixels), their feet at [floor]: the first in
         * the middle, the rest round them leaning out more the further out they stand, [lean] degrees at the edge.
         * Pictures face the front give or take [faceFront] degrees, a few any way. [full] fill it; [max] at most.
         */
        fun bundle(x: Float, z: Float, radius: Float, floor: Float, item: Item, full: Int, max: Int, lean: Float, faceFront: Float, r: Random) {
            for (k in 0 until max) {
                // A sunflower's spiral spreads them evenly however many there are; a glut stands them further out
                val reach = radius * sqrt((k + 0.5f) / full).coerceAtMost(1.35f)
                val a = k * GOLDEN + r.jitter(0.3f)
                val ux = cos(a)
                val uz = sin(a)
                val tip = reach / radius * lean
                val yaw = if (r.nextInt(5) == 0) r.nextFloat() * 360f else r.jitter(faceFront)
                // Leaning outward: the lean turned into its own frame (pitch tips its top toward its local +z, roll toward -x)
                val s = sin(yaw * DEG)
                val c = cos(yaw * DEG)
                val pitch = tip * (ux * s + uz * c) + r.jitter(3f)
                val roll = -tip * (ux * c - uz * s) + r.jitter(3f)
                val sink = r.nextFloat() * 0.8f
                slots.add(slot(x + ux * reach, floor - sink + item.foot, z + uz * reach, yaw, pitch, roll, item.spin, Pose.STAND, item.side / 16f, r))
            }
            this.full += full
        }

        /**
         * Stacks at [spots], each item on the lowest stack (so they rise together), [high] pixels apiece: [full] to a
         * stack at the usual stock, [max] at most. Without an [item], a block's item squashed into a folded bolt of cloth;
         * with one, its pictures laid one on another, each turned a little.
         */
        fun stacks(spots: List<Pair<Float, Float>>, high: Float, full: Int, max: Int, yawJitter: Float, item: Item?, r: Random) {
            val heights = IntArray(spots.size)
            val turns = FloatArray(spots.size) { if (r.nextBoolean()) 0f else 180f }
            repeat(spots.size * max) {
                val low = heights.min()
                val s = heights.indices.filter { heights[it] == low }.let { it[r.nextInt(it.size)] }
                val (x, z) = spots[s]
                val y = STACK_BASE + heights[s]++ * high + high / 2f
                val yaw = turns[s] + r.jitter(yawJitter)
                slots.add(
                    if (item == null) {
                        // The block's item is half a block a side: squashed, a fold of cloth
                        slot(x + r.jitter(0.3f), y, z + r.jitter(0.3f), yaw, r.jitter(1.5f), r.jitter(1.5f), 0f, Pose.BLOCK, BOLT_WIDE / 8f, high * 0.92f / 8f, BOLT_LONG / 8f, r)
                    } else {
                        slot(x + r.jitter(0.4f), y, z + r.jitter(0.4f), yaw, r.jitter(4f), r.jitter(4f), item.spin, Pose.FLAT, item.side / 16f, r)
                    },
                )
            }
            this.full += spots.size * full
        }

        private fun slot(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float, spin: Float, pose: Pose, s: Float, r: Random): Slot =
            slot(x, y, z, yaw, pitch, roll, spin, pose, s, s, s, r)

        private fun slot(
            x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float, spin: Float, pose: Pose, sx: Float, sy: Float, sz: Float, r: Random,
        ): Slot = Slot(x / 16f, y / 16f, z / 16f, yaw, pitch, roll, spin, pose, sx, sy, sz, r.nextFloat())

        /** Near enough a bell curve: the mean of three. */
        private fun gauss(r: Random): Float = (r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f) * 1.4f

        private fun Random.jitter(by: Float): Float = (nextFloat() * 2f - 1f) * by
    }

    /**
     * A good's [area] of the floor of a crate, a board or a shelf, which runs over all of [box] at height [floor], as a
     * height field that items dropped on it raise. Past the box it's [rim]: the top of the walls, if that's above the
     * floor; where it's below, the floor drops away at the box's edges. Items laid on it face [lean] degrees toward the
     * front, to be seen over the side by a buyer standing at it. It keeps where each item lies, to tell when one is buried.
     */
    private class Pile(val area: Area, val box: Area, val floor: Float, val rim: Float, val lean: Float = 0f) {
        val x0: Float get() = area.x0
        val z0: Float get() = area.z0
        val x1: Float get() = area.x1
        val z1: Float get() = area.z1
        private val ox = x0 - MARGIN
        private val oz = z0 - MARGIN
        private val nx = ceil((x1 - x0 + 2 * MARGIN) / CELL).toInt()
        private val nz = ceil((z1 - z0 + 2 * MARGIN) / CELL).toInt()
        private val h = FloatArray(nx * nz) { i -> base(ox + (i % nx + 0.5f) * CELL, oz + (i / nx + 0.5f) * CELL) }

        /** Each item laid: the cells under it and round it, the height of its top, and the count from which it's buried. */
        private val under = ArrayList<IntArray>()
        private val round = ArrayList<IntArray>()
        private val tops = ArrayList<Float>()
        private val buried = ArrayList<Int>()

        val count: Int get() = tops.size

        fun buried(i: Int): Int = buried[i]

        /** The bare field at [x], [z]: the floor inside the box, a wall or the drop off its edge outside. */
        private fun base(x: Float, z: Float): Float = if (box.has(x, z)) floor else rim

        private fun cell(x: Float, z: Float): Int {
            val i = floor((x - ox) / CELL).toInt()
            val k = floor((z - oz) / CELL).toInt()
            return if (i < 0 || k < 0 || i >= nx || k >= nz) -1 else k * nx + i
        }

        /** The height of the field at [x], [z]. */
        fun under(x: Float, z: Float): Float {
            val c = cell(x, z)
            return if (c >= 0) h[c] else base(x, z)
        }

        /** The cells an item at [x], [z] with its length along [yaw] covers (a little inside its picture's edge), [grow] more round. */
        private fun print(x: Float, z: Float, yaw: Float, item: Item, grow: Float = 0f): IntArray {
            val out = LinkedHashSet<Int>()
            cells(x, z, yaw, item, grow) { out.add(it) }
            return out.toIntArray()
        }

        /** Each cell (some twice) that [print] gives, without keeping them. */
        private inline fun cells(x: Float, z: Float, yaw: Float, item: Item, grow: Float, each: (Int) -> Unit) {
            val a = yaw * DEG
            val dx = sin(a)
            val dz = cos(a)
            val hl = item.long * 0.4f + grow
            val hw = item.wide * 0.36f + grow
            val reach = sqrt(hl * hl + hw * hw)
            var cz = z - reach
            while (cz <= z + reach) {
                var cx = x - reach
                while (cx <= x + reach) {
                    val px = cx - x
                    val pz = cz - z
                    if (abs(px * dx + pz * dz) <= hl && abs(px * dz - pz * dx) <= hw) {
                        val c = cell(cx, cz)
                        if (c >= 0) each(c)
                    }
                    cx += CELL
                }
                cz += CELL
            }
        }

        /** The height an item laid at [x], [z] would rest at: the highest point under it. */
        fun rest(x: Float, z: Float, yaw: Float, item: Item): Float {
            var top = base(x, z).coerceAtLeast(floor)
            cells(x, z, yaw, item, 0f) { top = max(top, h[it]) }
            return top
        }

        /** Whether an item can roll to [x], [z]: not out of its good's area (so over a wall or off the board's edge). */
        fun holds(x: Float, z: Float, item: Item): Boolean = area.has(x, z, item.wide * 0.3f)

        /** Lays an item whose top is at [top]: the field rises under it, and whatever it buries is marked so. */
        fun stamp(x: Float, z: Float, yaw: Float, item: Item, top: Float) {
            val cells = print(x, z, yaw, item)
            for (c in cells) h[c] = max(h[c], top)
            under.add(cells)
            val inside = cells.toHashSet()
            round.add(print(x, z, yaw, item, grow = CELL * 1.5f).filter { it !in inside }.toIntArray())
            tops.add(top)
            buried.add(Int.MAX_VALUE)
            val n = tops.size
            // Covered over by what's on it, with nothing lower beside it to be seen from: out of sight from now on
            for (i in 0 until n - 1) {
                if (buried[i] != Int.MAX_VALUE || under[i].isEmpty()) continue
                val t = tops[i]
                if (under[i].any { h[it] < t + 0.25f } || round[i].any { h[it] < t - 0.5f }) continue
                buried[i] = n
            }
        }
    }

    private const val DEG = (PI / 180.0).toFloat()

    /** The golden angle, to spread a bundle evenly. */
    private const val GOLDEN = 2.3999631f

    /** A pile's cells, how far round it its field reaches (pixels), how far an item rolls at a step and how steep a slope holds it. */
    private const val CELL = 0.5f
    private const val MARGIN = 4f
    private const val ROLL = 0.8f
    private const val REPOSE = 0.75f

    /** How much of the slope under an item it lies at, and the most it's tipped any way (degrees). */
    private const val SETTLE = 0.75f
    private const val TIP = 28f
}
