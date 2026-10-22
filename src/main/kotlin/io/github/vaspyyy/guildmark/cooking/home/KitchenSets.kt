package io.github.vaspyyy.guildmark.cooking.home

import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.homestead.plan.Packed
import io.github.vaspyyy.guildmark.homestead.plan.PieceKind
import io.github.vaspyyy.guildmark.homestead.plan.Step
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import io.github.vaspyyy.guildmark.homestead.plan.TierPlan

/**
 * What the kitchen fit-out puts in (living-world/cook/design.md 6.7): the table of Part K as data, and the finding of each
 * item's cell from the tier's own plan. This file knows the plan and no Minecraft, so a plan can be asked without a world:
 * [KitchenSets.of] reads the blocks a tier's builders were given to lay and says, for each item of the tier, which of them
 * the item takes the place of. A rule that finds nothing says why, and the item is then "not found". No item is ever given a
 * cell the plan did not draw, and what the world holds at a cell is [HomesteadKitchens]'s to check (the block must still be
 * the plan's own, and hold nothing).
 */
enum class Thing(val word: String, val m: M?) {
    POT("pot", M.STEWPOT), OVEN("oven", M.BAKE_OVEN), HOB("hob", M.HOB), BOARD("board", M.PREP_BOARD), PLATE("plate", M.PLATE),
    LARDER("larder", M.LARDER), MENU("menu board", M.MENU_BOARD), SPIT("spit", M.SPIT), HERB("herb", null),
}

/**
 * How the world is read at a [Pick]: [BLOCK] the plan's own block stands there and holds nothing; [TOP] what the plan drew on
 * a table (a carpet, a candle, or nothing) is there or gone, over the plan's own slab; [OPEN] the plan drew nothing there, so
 * it is air over a solid floor; [CROP] the plan's crop is there, or nothing, over farmland.
 */
enum class Take { BLOCK, TOP, OPEN, CROP }

/**
 * One cell an item takes. [step] is the step of tier [tier]'s plan that drew the block the item takes the place of (-1: the
 * plan drew nothing here), [under] the step of the block it stands on (for a top or a crop), [dir] the way it faces in the
 * plan's frame (null: it has no front). x, y and z are the plan's own.
 */
class Pick(
    val take: Take,
    val tier: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val step: Int,
    val under: Int = -1,
    val dir: Dir? = null,
)

/**
 * One item of a tier's kitchen: its stable [key], what it is, the cells it may take in the order they are tried ([picks]; none
 * when the rule found nothing, and then [why] says so), and how many are wanted ([want]: two plates, up to six, five herb
 * plots in all).
 */
class Item(val key: String, val thing: Thing, val picks: List<Pick>, val want: Int, val why: String) {
    val found: Boolean get() = picks.isNotEmpty()
}

object KitchenSets {
    /** Each tier's keys, in the order the tier is fitted: the hearth's pot first, the garden last. */
    private val KEYS: List<List<String>> = listOf(
        emptyList(),
        listOf("c.pot", "c.board", "c.plate", "c.herbs"),
        listOf("f.pot", "f.oven", "f.hob", "f.larder", "f.plates", "f.menu", "f.herbs"),
        listOf("m.pot", "m.oven", "m.larder", "m.larder2", "m.plates", "m.spit", "m.herbs"),
    )

    fun keys(tier: Int): List<String> = KEYS.getOrElse(tier) { emptyList() }

    /** The tier a key belongs to (`c.` the cottage's, `f.` the farmstead's, `m.` the manor's; 0 for a key that is none). */
    fun tierOf(key: String): Int = when {
        key.startsWith("c.") -> 1
        key.startsWith("f.") -> 2
        key.startsWith("m.") -> 3
        else -> 0
    }

    /** The herb plots the kitchen bed has in all once tier [tier] is fitted: three, five, eight. */
    fun herbsIn(tier: Int): Int = when (tier) {
        1 -> 3
        2 -> 5
        3 -> 8
        else -> 0
    }

    /** The most plates a manor's long table is laid with. */
    const val PLATES = 6

    /** How near a fireplace must be to the long table for a spit to turn there, blocks. */
    const val SPIT_REACH = 8.0

    /** A manor's kitchen is a small room, four blocks across and four deep, its row of tools along the back wall (the most z). */
    private const val KITCHEN = 4

    /**
     * Tier [tier]'s items found in its [plan]; [first] is the cottage's plan, which has the kitchen bed's crops that the herb
     * plots of every tier take the place of, and [bed] the bed's rectangle (null: there is none, and the herbs are not found).
     */
    fun of(tier: Int, plan: TierPlan, first: TierPlan, bed: Rect?): List<Item> {
        val look = Look(plan)
        val herbs = Look(first)
        return when (tier) {
            1 -> cottage(look, herbs, bed)
            2 -> farmhouse(look, herbs, bed)
            3 -> manor(look, herbs, bed)
            else -> emptyList()
        }
    }

    // ---- The plan, asked ----

    /** A tier's plan, with its blocks findable by place. */
    private class Look(val plan: TierPlan) {
        private val grid = HashMap<Long, Int>()
        private val houses = HashMap<Int, List<Int>>()

        init {
            for ((i, s) in plan.steps.withIndex()) if (s.kind == StepKind.BLOCK && s.b.m != M.AIR) grid[Packed.of(s.x, s.y, s.z)] = i
        }

        operator fun get(i: Int): Step = plan.steps[i]

        /** The step that draws a block at the place, or -1 (the plan leaves it bare, or clears it). */
        fun at(x: Int, y: Int, z: Int): Int = grid[Packed.of(x, y, z)] ?: -1

        fun m(i: Int): M = plan.steps[i].b.m

        /** Whether the plan draws a table's top-half slab at the place. */
        fun table(x: Int, y: Int, z: Int): Boolean {
            val i = at(x, y, z)
            return i >= 0 && m(i) == M.PLANK_SLAB && plan.steps[i].b.top
        }

        /** The block steps of building [piece], in the plan's order. */
        fun house(piece: Int): List<Int> = houses.getOrPut(piece) {
            plan.steps.indices.filter { plan.steps[it].piece == piece && plan.steps[it].kind == StepKind.BLOCK && plan.steps[it].b.m != M.AIR }
        }

        /** The building that has a campfire lit under its roof, the hearth (the one on the chimney's top does not count), or -1. */
        fun hearthHouse(): Int {
            for ((i, p) in plan.pieces.withIndex()) {
                if (p.kind != PieceKind.HOUSE) continue
                if (house(i).any { m(it) == M.CAMPFIRE && plan.steps[it].y < p.roofBase }) return i
            }
            return -1
        }

        /** The hearth's campfire of a building: the lowest one under its roof, or -1. */
        fun fire(piece: Int): Int {
            val roof = plan.pieces[piece].roofBase
            return house(piece).filter { m(it) == M.CAMPFIRE && plan.steps[it].y < roof }
                .minWithOrNull(compareBy({ plan.steps[it].y }, { plan.steps[it].z }, { plan.steps[it].x })) ?: -1
        }

        /** The door of a building, the lower half of the first one in the plan's order, or -1. */
        fun door(piece: Int): Int = house(piece).firstOrNull { m(it) == M.DOOR && plan.steps[it].b.part == 0 } ?: -1

        fun pick(take: Take, i: Int, under: Int = -1, dir: Dir? = null): Pick {
            val s = plan.steps[i]
            return Pick(take, plan.tier, s.x, s.y, s.z, i, under, dir)
        }
    }

    private fun d2(a: Step, b: Step): Int = (a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z)

    private fun found(key: String, thing: Thing, picks: List<Pick>, want: Int = picks.size): Item = Item(key, thing, picks, want, "")

    private fun missing(key: String, thing: Thing, why: String, want: Int = 1): Item = Item(key, thing, emptyList(), want, why)

    // ---- The cottage ----

    private fun cottage(look: Look, first: Look, bed: Rect?): List<Item> {
        val house = look.hearthHouse()
        val fire = if (house < 0) -1 else look.fire(house)
        val out = ArrayList<Item>()
        if (fire < 0) {
            val why = "the plan has no hearth under a roof"
            out.add(missing("c.pot", Thing.POT, why))
            out.add(missing("c.board", Thing.BOARD, why))
            out.add(missing("c.plate", Thing.PLATE, why))
            out.add(herbs("c.herbs", 1, first, bed))
            return out
        }
        val f = look[fire]
        val steps = look.house(house)
        val door = look.door(house).let { if (it < 0) f else look[it] }
        out.add(found("c.pot", Thing.POT, listOf(look.pick(Take.BLOCK, fire))))

        // The barrel by the door
        val barrel = steps.filter { look.m(it) == M.BARREL && look[it].y == f.y }.minWithOrNull(compareBy({ d2(look[it], door) }, { look[it].z }, { look[it].x }))
        out.add(if (barrel != null) found("c.board", Thing.BOARD, listOf(look.pick(Take.BLOCK, barrel))) else missing("c.board", Thing.BOARD, "the plan has no barrel in the cottage"))

        // The carpet on the table slab, or the slab's top when the plan has none
        out.add(plateOnTable("c.plate", look, steps, f))
        out.add(herbs("c.herbs", 1, first, bed))
        return out
    }

    /** One plate on the table nearest the fire: where the carpet lies, or on the bare top of a slab. */
    private fun plateOnTable(key: String, look: Look, steps: List<Int>, fire: Step): Item {
        val carpet = steps.filter { look.m(it) == M.CARPET && look.table(look[it].x, look[it].y - 1, look[it].z) }
            .minWithOrNull(compareBy({ d2(look[it], fire) }, { look[it].z }, { look[it].x }))
        if (carpet != null) {
            val s = look[carpet]
            return found(key, Thing.PLATE, listOf(look.pick(Take.TOP, carpet, look.at(s.x, s.y - 1, s.z))))
        }
        val slab = steps.filter { look.m(it) == M.PLANK_SLAB && look[it].b.top && look[it].y == fire.y && look.at(look[it].x, look[it].y + 1, look[it].z) < 0 }
            .minWithOrNull(compareBy({ d2(look[it], fire) }, { look[it].z }, { look[it].x }))
        if (slab == null) return missing(key, Thing.PLATE, "the plan has no table in the cottage")
        val s = look[slab]
        return found(key, Thing.PLATE, listOf(Pick(Take.TOP, look.plan.tier, s.x, s.y + 1, s.z, -1, slab)))
    }

    // ---- The farmhouse ----

    private fun farmhouse(look: Look, first: Look, bed: Rect?): List<Item> {
        val house = look.hearthHouse()
        val fire = if (house < 0) -1 else look.fire(house)
        val out = ArrayList<Item>()
        if (fire < 0) {
            val why = "the plan has no hearth under a roof"
            for ((key, thing) in listOf("f.pot" to Thing.POT, "f.oven" to Thing.OVEN, "f.hob" to Thing.HOB, "f.larder" to Thing.LARDER, "f.plates" to Thing.PLATE, "f.menu" to Thing.MENU)) {
                out.add(missing(key, thing, why))
            }
            out.add(herbs("f.herbs", 2, first, bed))
            return out
        }
        val f = look[fire]
        val steps = look.house(house)
        val d = look.door(house)
        val door = if (d < 0) f else look[d]
        out.add(found("f.pot", Thing.POT, listOf(look.pick(Take.BLOCK, fire))))

        // The footings either side of the fire: the oven on the side toward the back wall, the hob on the side toward the door
        val sides = footings(look, house, f)
        if (sides == null) {
            out.add(missing("f.oven", Thing.OVEN, "the plan has no footings either side of the fire"))
            out.add(missing("f.hob", Thing.HOB, "the plan has no footings either side of the fire"))
        } else {
            val (a, b, room) = sides
            val far = if (d2(look[a], door) != d2(look[b], door)) d2(look[a], door) > d2(look[b], door) else (look[a].z > look[b].z || (look[a].z == look[b].z && look[a].x > look[b].x))
            val oven = if (far) a else b
            val hob = if (far) b else a
            out.add(found("f.oven", Thing.OVEN, listOf(look.pick(Take.BLOCK, oven, dir = room))))
            out.add(found("f.hob", Thing.HOB, listOf(look.pick(Take.BLOCK, hob))))
        }

        // The barrel by the door
        val barrel = steps.filter { look.m(it) == M.BARREL && look[it].y == f.y }.minWithOrNull(compareBy({ d2(look[it], door) }, { look[it].z }, { look[it].x }))
        out.add(if (barrel != null) found("f.larder", Thing.LARDER, listOf(look.pick(Take.BLOCK, barrel))) else missing("f.larder", Thing.LARDER, "the plan has no barrel in the farmhouse"))

        out.add(tableEnds(look, steps, f))
        out.add(menuCell(look, d))
        out.add(herbs("f.herbs", 2, first, bed))
        return out
    }

    /** The two footings at the fire's own height either side of it, and the way into the room (away from the chimney's wall). */
    private fun footings(look: Look, house: Int, f: Step): Triple<Int, Int, Dir>? {
        for (side in listOf(Dir.N, Dir.E)) {
            val a = look.at(f.x + side.dx, f.y, f.z + side.dz)
            val b = look.at(f.x - side.dx, f.y, f.z - side.dz)
            if (a < 0 || b < 0 || look.m(a) != M.FOUNDATION || look.m(b) != M.FOUNDATION) continue
            // The room lies on the open side of the fire; the other side is the chimney's wall
            val across = side.cw
            val opens = listOf(across, across.opposite).filter { look.at(f.x + it.dx, f.y, f.z + it.dz) < 0 }
            val room = if (opens.size == 1) opens[0] else towardMiddle(look.plan.pieces[house].lot, f, across)
            return Triple(a, b, room)
        }
        return null
    }

    /** Of [axis] and its opposite, the way from [f] toward the middle of [lot]. */
    private fun towardMiddle(lot: Rect, f: Step, axis: Dir): Dir {
        val mid = if (axis.alongX) (lot.x0 + lot.x1) / 2.0 - f.x else (lot.z0 + lot.z1) / 2.0 - f.z
        val along = if (axis.alongX) axis.dx else axis.dz
        return if (mid * along >= 0) axis else axis.opposite
    }

    /** The free tops of the long table's two ends: the candle on the middle slab stays. */
    private fun tableEnds(look: Look, steps: List<Int>, fire: Step): Item {
        val key = "f.plates"
        val candle = steps.filter { look.m(it) == M.CANDLE && look.table(look[it].x, look[it].y - 1, look[it].z) }
            .minWithOrNull(compareBy({ d2(look[it], fire) }, { look[it].z }, { look[it].x }))
        val ends = ArrayList<Pick>()
        if (candle != null) {
            val c = look[candle]
            for (side in listOf(Dir.N, Dir.E)) {
                val lo = look.at(c.x - side.dx, c.y - 1, c.z - side.dz)
                val hi = look.at(c.x + side.dx, c.y - 1, c.z + side.dz)
                if (lo < 0 || hi < 0 || !look.table(look[lo].x, look[lo].y, look[lo].z) || !look.table(look[hi].x, look[hi].y, look[hi].z)) continue
                for (slab in listOf(lo, hi)) {
                    val s = look[slab]
                    if (look.at(s.x, s.y + 1, s.z) >= 0) continue
                    ends.add(Pick(Take.TOP, look.plan.tier, s.x, s.y + 1, s.z, -1, slab))
                }
                break
            }
        }
        if (ends.isEmpty()) return missing(key, Thing.PLATE, "the plan has no table with a free end in the farmhouse", 2)
        return found(key, Thing.PLATE, ends, 2)
    }

    /**
     * The cell in front of the wall beside the door, on the right hand of whoever comes in, or the left if that is taken: the
     * board stands on the ground there, facing outward. The plan may hang a shutter on the wall above it, which does not touch it.
     */
    private fun menuCell(look: Look, door: Int): Item {
        val key = "f.menu"
        if (door < 0) return missing(key, Thing.MENU, "the plan has no door in the farmhouse")
        val s = look[door]
        val inward = s.b.dir ?: return missing(key, Thing.MENU, "the door has no facing in the plan")
        val outward = inward.opposite
        for (side in listOf(inward.cw, inward.ccw)) {
            val x = s.x + side.dx + outward.dx
            val z = s.z + side.dz + outward.dz
            if (look.at(x, s.y, z) >= 0) continue
            return found(key, Thing.MENU, listOf(Pick(Take.OPEN, look.plan.tier, x, s.y, z, -1, -1, outward)))
        }
        return missing(key, Thing.MENU, "the plan has something at both sides of the door")
    }

    // ---- The manor ----

    private fun manor(look: Look, first: Look, bed: Rect?): List<Item> {
        val out = ArrayList<Item>()
        // The manor is the building with the kitchen's smoker, else the biggest one
        var piece = -1
        var area = -1
        for ((i, p) in look.plan.pieces.withIndex()) {
            if (p.kind != PieceKind.HOUSE) continue
            val a = p.lot.width * p.lot.depth + if (look.house(i).any { look.m(it) == M.SMOKER }) 1_000_000 else 0
            if (a > area) {
                area = a
                piece = i
            }
        }
        if (piece < 0) {
            val why = "the plan has no manor"
            for ((key, thing) in listOf("m.pot" to Thing.POT, "m.oven" to Thing.OVEN, "m.larder" to Thing.LARDER, "m.larder2" to Thing.LARDER, "m.plates" to Thing.PLATE, "m.spit" to Thing.SPIT)) {
                out.add(missing(key, thing, why))
            }
            out.add(herbs("m.herbs", 3, first, bed))
            return out
        }
        val floor = look.plan.pieces[piece].y
        val steps = look.house(piece).filter { look[it].y == floor }
        fun all(m: M) = steps.filter { look.m(it) == m }
        val smoker = all(M.SMOKER).minWithOrNull(compareBy({ look[it].z }, { look[it].x }))
        val furnaces = all(M.FURNACE)

        // The oven where the furnace with a smoker beside it stood
        val oven = smoker?.let { sm -> furnaces.firstOrNull { d1(look[it], look[sm]) == 1 } }
        val kitchen: (Int) -> Boolean = { i ->
            smoker != null && look[i].z in (look[smoker].z - KITCHEN + 1)..look[smoker].z && kotlin.math.abs(look[i].x - look[smoker].x) <= KITCHEN - 1
        }

        // The pot: the cauldron at the row's end; a kitchen so narrow that it has none gives its composter's place
        val cauldron = all(M.CAULDRON).filter { smoker == null || kitchen(it) }.minWithOrNull(compareBy({ if (smoker == null) 0 else d2(look[it], look[smoker]) }, { look[it].z }, { look[it].x }))
        val composter = all(M.COMPOSTER).filter(kitchen).minWithOrNull(compareBy({ d2(look[it], look[smoker!!]) }, { look[it].z }, { look[it].x }))
        out.add(
            when {
                cauldron != null -> found("m.pot", Thing.POT, listOf(look.pick(Take.BLOCK, cauldron)))
                composter != null -> Item("m.pot", Thing.POT, listOf(look.pick(Take.BLOCK, composter)), 1, "no cauldron stands in this kitchen, so the pot takes the compost bin's place")
                else -> missing("m.pot", Thing.POT, "the plan has no cauldron or compost bin in the kitchen")
            },
        )
        out.add(
            if (oven != null) found("m.oven", Thing.OVEN, listOf(look.pick(Take.BLOCK, oven, dir = look[oven].b.dir)))
            else missing("m.oven", Thing.OVEN, if (smoker == null) "the plan has no smoker in the manor" else "the plan has no furnace beside the smoker"),
        )

        // The larder in the row: the barrel in line with the smoker, else any barrel of the kitchen
        val barrels = all(M.BARREL).filter(kitchen)
        val row = barrels.filter { smoker != null && look[it].z == look[smoker].z }
        val kitchenBarrel = (row.ifEmpty { barrels }).minWithOrNull(compareBy({ d2(look[it], look[smoker!!]) }, { look[it].z }, { look[it].x }))
        out.add(if (smoker != null && kitchenBarrel != null) found("m.larder", Thing.LARDER, listOf(look.pick(Take.BLOCK, kitchenBarrel))) else missing("m.larder", Thing.LARDER, "the plan has no barrel in the kitchen"))

        // The long table, then the sideboard and the plates and the spit that go by it
        val table = longTable(look, steps, floor)
        out.add(sideboard(look, steps, floor, table, kitchenBarrel, kitchen))

        // The spit: the fireplace nearest the long table (a furnace with no smoker beside it), within reach of it
        val hearths = furnaces.filter { it != oven && (smoker == null || d1(look[it], look[smoker]) != 1) }
        val spit = if (table == null) null else hearths.filter { nearTable(look, table, look[it], SPIT_REACH) }.minWithOrNull(compareBy({ tableDistance(look, table, look[it]) }, { look[it].z }, { look[it].x }))

        out.add(plates(look, floor, table))
        out.add(
            if (spit != null) found("m.spit", Thing.SPIT, listOf(look.pick(Take.BLOCK, spit, dir = look[spit].b.dir)))
            else missing("m.spit", Thing.SPIT, if (table == null) "the plan has no long table" else "no fireplace stands within ${SPIT_REACH.toInt()} blocks of the long table"),
        )
        out.add(herbs("m.herbs", 3, first, bed))
        return out
    }

    private fun d1(a: Step, b: Step): Int = kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.z - b.z)

    /**
     * The larder at the sideboard along the dining room's back wall: the barrel the plan drew there, if it drew one; else the
     * sideboard's bare slab (the plan's builders leave out any barrel that would shut off a corner of a small room, and a
     * homestead's dining room is small), the nearest to the head of the table.
     */
    private fun sideboard(look: Look, steps: List<Int>, floor: Int, table: List<Int>?, kitchenBarrel: Int?, kitchen: (Int) -> Boolean): Item {
        val key = "m.larder2"
        if (table == null) return missing(key, Thing.LARDER, "the plan has no long table")
        val top = look[head(look, table)]
        val inTable = table.toSet()
        val barrel = steps.filter { look.m(it) == M.BARREL && it != kitchenBarrel && !kitchen(it) && nearTable(look, table, look[it], 4.0) }
            .minWithOrNull(compareBy({ d2(look[it], top) }, { look[it].z }, { look[it].x }))
        if (barrel != null) return found(key, Thing.LARDER, listOf(look.pick(Take.BLOCK, barrel)))
        val slab = steps.filter {
            look.m(it) == M.PLANK_SLAB && look[it].b.top && it !in inTable && look[it].y == floor && look.at(look[it].x, floor + 1, look[it].z) < 0 &&
                look[it].z in top.z..top.z + 2 && kotlin.math.abs(look[it].x - top.x) <= 3
        }.minWithOrNull(compareBy({ d2(look[it], top) }, { look[it].z }, { look[it].x }))
        if (slab != null) return Item(key, Thing.LARDER, listOf(look.pick(Take.BLOCK, slab)), 1, "no barrel stands at the sideboard, so the larder takes the place of its slab")
        return missing(key, Thing.LARDER, "the plan has neither a barrel nor a bare slab at the sideboard")
    }

    /**
     * The long table of the dining room: the longest line of top-half slabs, at least two, with a pot of flowers on one of
     * them (a study's desk has none, and a hall's table is one slab), as its slabs' steps; null if the plan has none.
     */
    private fun longTable(look: Look, steps: List<Int>, floor: Int): List<Int>? {
        val slabs = steps.filter { look.m(it) == M.PLANK_SLAB && look[it].b.top }
        val byCell = slabs.associateBy { look[it].x to look[it].z }
        val seen = HashSet<Pair<Int, Int>>()
        var best: List<Int>? = null
        for (start in slabs.sortedWith(compareBy({ look[it].z }, { look[it].x }))) {
            val cell = look[start].x to look[start].z
            if (cell in seen) continue
            val run = ArrayList<Int>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(cell)
            seen.add(cell)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                run.add(byCell.getValue(c))
                for (d in Dir.entries) {
                    val n = (c.first + d.dx) to (c.second + d.dz)
                    if (n in byCell && seen.add(n)) queue.add(n)
                }
            }
            if (run.size < 2) continue
            if (run.none { look.at(look[it].x, floor + 1, look[it].z).let { t -> t >= 0 && look.m(t) == M.FLOWER_POT } }) continue
            if (best == null || run.size > best.size) best = run
        }
        return best
    }

    private fun tableDistance(look: Look, table: List<Int>, s: Step): Double = table.minOf { Math.sqrt(d2(look[it], s).toDouble()) }

    private fun nearTable(look: Look, table: List<Int>, s: Step, reach: Double): Boolean = tableDistance(look, table, s) <= reach

    /** The slab at the head of the table: the end toward the back of the house (the dining room's fireplace and sideboard are there). */
    private fun head(look: Look, table: List<Int>): Int = table.maxWithOrNull(compareBy({ look[it].z }, { look[it].x }))!!

    /** The plates on the long table: its free tops and its candles', the pot of flowers and the head's candle left. */
    private fun plates(look: Look, floor: Int, table: List<Int>?): Item {
        val key = "m.plates"
        if (table == null) return missing(key, Thing.PLATE, "the plan has no long table", PLATES)
        val head = head(look, table)
        val picks = ArrayList<Pick>()
        // From the foot toward the head, so that the head is the last to be laid
        for (slab in table.sortedWith(compareBy({ -d2(look[it], look[head]) }, { look[it].z }, { look[it].x }))) {
            val s = look[slab]
            val on = look.at(s.x, floor + 1, s.z)
            when {
                on < 0 -> picks.add(Pick(Take.TOP, look.plan.tier, s.x, floor + 1, s.z, -1, slab))
                look.m(on) == M.CANDLE && slab != head -> picks.add(Pick(Take.TOP, look.plan.tier, s.x, floor + 1, s.z, on, slab))
            }
        }
        val chosen = picks.take(PLATES)
        return if (chosen.isEmpty()) missing(key, Thing.PLATE, "the long table has no free top", PLATES) else found(key, Thing.PLATE, chosen, PLATES)
    }

    // ---- The kitchen bed ----

    /** The herb plots: the cells of the bed's far row that hold one of the cottage's crops, in order from its first column. */
    private fun herbs(key: String, tier: Int, first: Look, bed: Rect?): Item {
        val want = herbsIn(tier)
        if (bed == null) return missing(key, Thing.HERB, "the plan has no kitchen bed", want)
        val picks = ArrayList<Pick>()
        for (x in bed.x0..bed.x1) {
            val crop = first.plan.steps.indices.firstOrNull { first.m(it) == M.CROP && first[it].x == x && first[it].z == bed.z1 && first[it].kind == StepKind.BLOCK } ?: continue
            val s = first[crop]
            picks.add(Pick(Take.CROP, 1, s.x, s.y, s.z, crop, first.at(s.x, s.y - 1, s.z), null))
        }
        return if (picks.isEmpty()) missing(key, Thing.HERB, "the plan has no crops in the far row of the bed", want) else found(key, Thing.HERB, picks, want)
    }
}
