package io.github.vaspyyy.guildmark.cooking.shop

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.market.NO_DAY
import net.minecraft.core.UUIDUtil
import net.minecraft.world.item.ItemStack
import java.util.Optional
import java.util.UUID

/**
 * A cookshop's record (living-world/cook/design.md 6.4, 9.1): the 18 stacks of its Larder, the whole Marks in its till, how
 * many portions of each kind it sold today and what is owed back to its owner. Held for each capital in
 * [io.github.vaspyyy.guildmark.cooking.market.Kitchens.shops], by owner, so the hourly sale never needs the shop's chunk.
 * What is saved beyond those: the owner's name (for the slate and the keeper's words), the day [sold] is for, the keeper's
 * wage not yet paid out of the till, the look of the town (its palate, learned when its ground was loaded), the Marks a
 * closing owes and the week's takings ([week]). The fraction of a Mark the till carries is the ledger's, kept in
 * `Rec.carry` at the shop door, so it follows the owner from one shop to the next. Every field is optional with a default.
 *
 * [returns] and [owed] are the closing's: when the works is taken down the stock goes into [returns] and the till into [owed],
 * to be handed to the owner at once if they are on and else at their next login; a record that is [closed] stays only until then.
 */
class ShopRec(val owner: UUID, val stock: Array<ItemStack>, var till: Int, val sold: IntArray, val returns: MutableList<ItemStack>) {
    /** The owner's name as last known: the slate's heading. */
    var name: String = ""

    /** The town's day [sold] counts portions for. */
    var day: Long = NO_DAY

    /** The keeper's wage earned and not yet paid out of the till, in Marks (2 a week, a twenty-first at each selling hour). */
    var wages: Double = 0.0

    /** The look of the capital ([io.github.vaspyyy.guildmark.city.CityStyle] ordinal), which sets the palate; learned when its ground is loaded. */
    var style: Int = 0

    /** Whole Marks owed to the owner by a closing, waiting for their next login. */
    var owed: Int = 0

    /** Whether the works is gone: the record is kept only until [owed] and [returns] have been handed over. */
    var closed: Boolean = false

    /** The last seven days' takings and portions. */
    val week: ShopWeek = ShopWeek()

    // Not saved: what the shoppers and the slate work from

    /** Whether the stock has changed since the plates and the slate were dressed. */
    var touched: Boolean = true

    /** Portions sold at the last selling hours and not yet fetched by a shopper (a dish for each, one portion), and since when (game time). */
    val waiting: ArrayList<ItemStack> = ArrayList()
    var waitingSince: Long = -1L

    /** What the last selling hour came to: when (game time), how many portions and how many Marks (before the keeper's wage). */
    var lastAt: Long = -1L
    var lastPortions: Int = 0
    var lastMarks: Double = 0.0

    /** Brings [sold] to the town's [today]: another day's counts are zeroed. */
    fun roll(today: Long) {
        if (day == today) return
        sold.fill(0)
        day = today
    }

    /** Whether there is nothing worth writing down but the owner. */
    fun blank(): Boolean = till == 0 && owed == 0 && stock.all { it.isEmpty } && returns.isEmpty() && wages == 0.0 && week.blank()

    companion object {
        /** The slots of a shop's Larder. */
        const val SLOTS = 18

        /** A new, empty shop for [owner]. */
        fun empty(owner: UUID): ShopRec = ShopRec(owner, Array(SLOTS) { ItemStack.EMPTY }, 0, IntArray(DishKind.entries.size), ArrayList())

        val CODEC: Codec<ShopRec> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter { it.owner },
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("stock", listOf()).forGetter { it.stock.toList() },
                Codec.INT.optionalFieldOf("till", 0).forGetter { it.till },
                Codec.INT.listOf().optionalFieldOf("sold", listOf()).forGetter { it.sold.toList() },
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("returns", listOf()).forGetter { it.returns.toList() },
                Codec.STRING.optionalFieldOf("name", "").forGetter { it.name },
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter { it.day },
                Codec.DOUBLE.optionalFieldOf("wages", 0.0).forGetter { it.wages },
                Codec.INT.optionalFieldOf("style", 0).forGetter { it.style },
                Codec.INT.optionalFieldOf("owed", 0).forGetter { it.owed },
                Codec.BOOL.optionalFieldOf("closed", false).forGetter { it.closed },
                ShopWeek.CODEC.optionalFieldOf("week").forGetter { if (it.week.blank()) Optional.empty() else Optional.of(it.week) },
            ).apply(i) { owner, stock, till, sold, returns, name, day, wages, style, owed, closed, week ->
                val kept = Array(SLOTS) { stock.getOrElse(it) { ItemStack.EMPTY } }
                val counts = IntArray(DishKind.entries.size) { sold.getOrElse(it) { 0 }.coerceAtLeast(0) }
                val shop = ShopRec(owner, kept, till.coerceAtLeast(0), counts, returns.filter { !it.isEmpty }.toMutableList())
                shop.name = name
                shop.day = day
                shop.wages = wages.coerceAtLeast(0.0)
                shop.style = style
                shop.owed = owed.coerceAtLeast(0)
                shop.closed = closed
                week.ifPresent { shop.week.copyFrom(it) }
                shop
            }
        }
    }
}

/**
 * The last seven days of a shop, a slot for each (the town's day modulo seven): the Marks it took, and the portions sold of each
 * kind. A slot is for a day, and is zeroed when another day writes to it, so the sum of the slots less than a week old is the
 * week: what the keeper says when the owner takes the till ("14 Marks this week, and the pies went first").
 */
class ShopWeek {
    val days: LongArray = LongArray(DAYS) { NO_DAY }
    val marks: DoubleArray = DoubleArray(DAYS)
    val kinds: IntArray = IntArray(DAYS * KINDS)

    /** Writes [portions] of [kind] sold for [takings] Marks on the town's [day]. */
    fun add(day: Long, kind: DishKind, portions: Int, takings: Double) {
        val slot = Math.floorMod(day, DAYS.toLong()).toInt()
        if (days[slot] != day) {
            days[slot] = day
            marks[slot] = 0.0
            for (k in 0 until KINDS) kinds[slot * KINDS + k] = 0
        }
        marks[slot] += takings
        kinds[slot * KINDS + kind.ordinal] += portions
    }

    /** Whether [slot] is a day of the week ending on [today]. */
    private fun within(slot: Int, today: Long): Boolean = days[slot] != NO_DAY && today - days[slot] in 0 until DAYS

    /** The Marks the shop took in the seven days up to [today]. */
    fun takings(today: Long): Double = (0 until DAYS).filter { within(it, today) }.sumOf { marks[it] }

    /** The portions of each kind sold in the seven days up to [today]. */
    fun portions(today: Long): IntArray {
        val out = IntArray(KINDS)
        for (slot in 0 until DAYS) {
            if (!within(slot, today)) continue
            for (k in 0 until KINDS) out[k] += kinds[slot * KINDS + k]
        }
        return out
    }

    /** The kind that sold the most portions in the week up to [today] (the dearer kind when two sold as many), or null when none sold. */
    fun top(today: Long): DishKind? {
        val all = portions(today)
        val best = all.indices.filter { all[it] > 0 }.maxWithOrNull(compareBy<Int> { all[it] }.thenBy { DishKind.entries[it].factor }) ?: return null
        return DishKind.entries[best]
    }

    fun blank(): Boolean = days.all { it == NO_DAY }

    fun copyFrom(other: ShopWeek) {
        for (slot in 0 until DAYS) days[slot] = other.days[slot]
        for (slot in 0 until DAYS) marks[slot] = other.marks[slot]
        for (n in kinds.indices) kinds[n] = other.kinds[n]
    }

    companion object {
        const val DAYS = 7
        private val KINDS = DishKind.entries.size

        val CODEC: Codec<ShopWeek> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.listOf().optionalFieldOf("days", listOf()).forGetter { it.days.toList() },
                Codec.DOUBLE.listOf().optionalFieldOf("marks", listOf()).forGetter { it.marks.toList() },
                Codec.INT.listOf().optionalFieldOf("kinds", listOf()).forGetter { it.kinds.toList() },
            ).apply(i) { days, marks, kinds ->
                val week = ShopWeek()
                for (slot in 0 until DAYS) {
                    week.days[slot] = days.getOrElse(slot) { NO_DAY }
                    week.marks[slot] = marks.getOrElse(slot) { 0.0 }.coerceAtLeast(0.0)
                }
                for (n in week.kinds.indices) week.kinds[n] = kinds.getOrElse(n) { 0 }.coerceAtLeast(0)
                week
            }
        }
    }
}
