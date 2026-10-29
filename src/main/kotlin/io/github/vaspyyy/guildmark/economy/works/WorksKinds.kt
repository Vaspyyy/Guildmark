package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Town
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import kotlin.math.roundToInt

/**
 * What each works costs and takes (design-influence.md section 1): its bill of materials (by [Stuff.PAID], counted the
 * homestead's way), its crew (labourers, under a master builder who counts as one more), its days of daylight work,
 * and its price, made of parts the works screen shows: the builders' wages, the materials, a granary's first
 * filling and the pitch of a stall or a cookshop. Each part is rounded to the nearest five Marks. A works' price is fixed when it's
 * wanted, so it doesn't drift as the town changes.
 */
internal object WorksKinds {
    /** Marks a builder-day; the master builder counts as one builder. */
    const val WAGE = 30

    /** Marks a unit of each paid material, by [Stuff.PAID]: timber, stone, glass, iron, wool. */
    private val UNIT = doubleArrayOf(0.3, 0.25, 1.0, 1.5, 1.0)

    /** The town's charge for a place in its square, for a stall or a cookshop of one's own. */
    const val PITCH = 40

    /** The parts of a price, as [WorksView.parts] names them. */
    const val WAGES = "wages"
    const val MATERIALS = "materials"
    const val FILLING = "filling"
    const val PLACE = "pitch"

    /** Days of each granary grade's room: the granary's wheat, the great granary's wheat and its roots. */
    const val GRANARY_DAYS = 7.0
    const val GREAT_DAYS = 21.0
    const val ROOT_DAYS = 7.0

    /** The roots a great granary's cellar keeps. */
    val ROOTS = listOf(Good.CARROT, Good.POTATO, Good.BEETROOT)

    /** [kind]'s bill: units of timber, stone, glass, iron and wool. */
    fun bill(kind: WorksKind): IntArray = when (kind) {
        WorksKind.OVEN -> intArrayOf(30, 140, 0, 6, 0)
        WorksKind.GRANARY -> intArrayOf(220, 160, 6, 8, 0)
        WorksKind.GREAT_GRANARY -> intArrayOf(260, 320, 10, 12, 0)
        WorksKind.TOWER -> intArrayOf(160, 300, 4, 12, 0)
        WorksKind.STALL -> intArrayOf(40, 0, 0, 2, 6)
        WorksKind.COOKSHOP -> intArrayOf(60, 80, 4, 6, 4)
        WorksKind.STORE -> intArrayOf(150, 90, 4, 6, 0)
        WorksKind.COUNTING_HOUSE -> intArrayOf(260, 220, 10, 10, 8)
    }

    /** The labourers [kind] takes, the master builder besides. */
    fun crew(kind: WorksKind): Int = when (kind) {
        WorksKind.OVEN -> 2
        WorksKind.GRANARY -> 3
        WorksKind.GREAT_GRANARY -> 4
        WorksKind.TOWER -> 2
        WorksKind.STALL -> 1
        WorksKind.COOKSHOP -> 2
        WorksKind.STORE -> 2
        WorksKind.COUNTING_HOUSE -> 3
    }

    /** Days of daylight work [kind] takes at `townWorksBuildSpeed` 1. */
    fun days(kind: WorksKind): Double = when (kind) {
        WorksKind.OVEN -> 1.0
        WorksKind.GRANARY -> 1.5
        WorksKind.GREAT_GRANARY -> 2.0
        WorksKind.TOWER -> 2.0
        WorksKind.STALL -> 0.5
        WorksKind.COOKSHOP -> 1.0
        WorksKind.STORE -> 2.0
        WorksKind.COUNTING_HOUSE -> 2.5
    }

    /**
     * [kind]'s price in [town], by its parts, in the order the screen lists them: the wages (a builder-day each for
     * the crew and the master), the materials, a granary's first filling (the room it adds, at the goods' base
     * prices, so it scales with the planned town) and the pitch of a stall or a cookshop.
     */
    fun parts(kind: WorksKind, town: Town): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        out[WAGES] = fives(WAGE * (crew(kind) + 1) * days(kind))
        val bill = bill(kind)
        out[MATERIALS] = fives(Stuff.PAID.indices.sumOf { bill[it] * UNIT[it] })
        if (kind == WorksKind.GRANARY || kind == WorksKind.GREAT_GRANARY) {
            val filling = filling(kind, town)
            out[FILLING] = fives(Good.entries.sumOf { filling[it.ordinal] * it.base })
        }
        // A Store and a House stand on free ground by the hall, not in the square: the town sells no place there
        if (kind.per == WorksKind.Per.PLAYER && kind != WorksKind.STORE && kind != WorksKind.COUNTING_HOUSE) out[PLACE] = PITCH
        return out
    }

    /** Rounded to the nearest five. */
    private fun fives(x: Double): Int = (x / 5.0).roundToInt() * 5

    /**
     * The town granary's room at [grade] in [town] (as it was planned), by good: none at 0; seven days of its wheat
     * for the granary; twenty-one of wheat and seven of each root for the great granary.
     */
    fun room(grade: Int, town: Town): DoubleArray {
        val out = DoubleArray(Good.entries.size)
        if (grade <= 0) return out
        val planned = town.planned ?: town
        out[Good.WHEAT.ordinal] = planned.use(Good.WHEAT) * (if (grade >= 2) GREAT_DAYS else GRANARY_DAYS)
        if (grade >= 2) for (g in ROOTS) out[g.ordinal] = planned.use(g) * ROOT_DAYS
        return out
    }

    /** The first filling a granary of [kind] brings, paid for in its price: the room it adds over the grade before. */
    fun filling(kind: WorksKind, town: Town): DoubleArray {
        val now = room(kind.grade, town)
        val before = room(kind.grade - 1, town)
        return DoubleArray(now.size) { (now[it] - before[it]).coerceAtLeast(0.0) }
    }
}
