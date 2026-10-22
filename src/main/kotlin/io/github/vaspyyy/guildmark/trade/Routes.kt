package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Lot
import io.github.vaspyyy.guildmark.economy.Lots
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Town
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One good of one market cut down to what a lot needs (the model's `GV`, Appendix A): the price is a function of the supply the market
 * holds ([Market.price]), the room is a function of the stock, and a lot bought or sold moves both by its count. So a trader's book can try a
 * hundred lots, and a thousand baskets, without touching a market: a shelf is a copy, made from a live market ([of] a market and its town) or
 * from a [Sight] of one, and what is done to it is done to the copy.
 *
 * The lots are priced as [Lots.buy] and [Lots.sell] price them (the same rounding to whole Marks, the same corrections once the lot has
 * gone), so what a shelf says a lot costs is what the stall asks.
 */
class Shelf(val good: Good, var stock: Double, var supply: Double, val target: Double, private val lean: Double) {
    /** What a unit goes for while the supply stands at [at] (the shelf's own now, if none is said): [Market.price]. */
    fun price(at: Double = supply): Double {
        val ratio = sqrt(target / max(at, target * 0.01)).coerceIn(Market.LOW, Market.HIGH)
        return good.base * (ratio * lean).coerceIn(Market.LOW, Market.HIGHEST)
    }

    /** How many more it will take from a trader, [extra] units of haul room beyond its own ([Lots.wants]). */
    fun wants(extra: Double = 0.0): Int = floor(target * Lots.ROOM + extra - stock).toInt().coerceAtLeast(0)

    /** The lot a trader can buy now, or null if there is none to sell ([Lots.buy]). */
    fun buyLot(): Lot? {
        val have = floor(stock + 1e-6).toInt()
        if (have < 1) return null
        var lot = Lots.dear(price()) ?: return null
        repeat(2) { lot = Lots.dear(price(supply - lot.count)) ?: return null }
        if (lot.count <= have) {
            var count = lot.count
            var marks = lot.marks
            while (marks < count * price(supply - count)) if (count > 1) count-- else marks++
            return Lot(count, marks)
        }
        // Too few left for a whole lot: what there is, at the price of the last of it, rounded up
        return Lot(have, ceil(have * price(supply - stock)).toInt())
    }

    /** The lot it will buy from a trader now, paying [share] of its price, with [extra] units of haul room; null if it wants no more ([Lots.sell]). */
    fun sellLot(share: Double, extra: Double = 0.0): Lot? {
        val room = wants(extra)
        if (room < 1) return null
        var lot = Lots.cheap(price() * share) ?: return null
        repeat(2) { lot = Lots.cheap(price(supply + lot.count) * share) ?: return null }
        var count = lot.count
        while (lot.marks > count * price(supply + count) * share) count++
        lot = Lot(count, lot.marks)
        if (lot.count <= room) return lot
        val marks = floor(room * price(supply + room) * share).toInt()
        if (marks >= 1) return Lot(room, marks)
        if (stock > target) return null
        var least = ceil(1.0 / (price() * share)).toInt().coerceAtLeast(1)
        while (least * price(supply + least) * share < 1.0) least++
        return Lot(least, 1)
    }

    /** A trader bought [lot] here: it leaves the shelf and the supply falls with it. */
    fun bought(lot: Lot) {
        stock -= lot.count
        supply -= lot.count
    }

    /** A trader sold [count] here: they come onto the shelf and the supply rises with them. */
    fun sold(count: Int) {
        stock += count
        supply += count
    }

    /**
     * The room a hauled good has beyond the market's own while it is short of the target: [days] days of the wagons' load, three wagons a day, never
     * more than the shortfall (the model's `extra_room`; Part 2's `Hauls.room`). [carry] is what the town's wagons usually bring of it
     * ([Sight.carry]).
     */
    fun room(days: Int, carry: Double): Double {
        if (days <= 0 || stock >= target) return 0.0
        val most = max(max(carry, (target - stock) * Economy.CATCH_UP), Economy.LEAST / 4) / 3
        return days * 3 * max(min(target - stock, most), 0.0)
    }

    companion object {
        /** The shelf of [good] in a live market. */
        fun of(m: Market, town: Town, good: Good): Shelf =
            Shelf(good, m.stock(good), m.held[good.ordinal] + m.stock(good) - m.mark[good.ordinal], town.target(good), m.freight(good) * m.outlook(good))

        /** The shelf of [good] as [sight] saw it. */
        fun of(sight: Sight, good: Good): Shelf = Shelf(good, sight.stock(good), sight.supply(good), sight.target(good), sight.freight(good) * sight.outlook(good))
    }
}

/**
 * The Tally's Routes page (living-world/trader/design.md, Part 1), and what the House's planner asks (part G): from the town where you stand,
 * the other towns ranked by what an hour of walking pays, each with the basket of goods the book would buy here for it.
 *
 * It is the model's trader (Appendix A, `agent.py`) and the same greedy rule: for each town it works out what each good would sell for
 * there, first to the haul tickets the news says are open (best pay first), then lot by lot to the stalls at the dealer's share for goods
 * that came 600 blocks or more; then what each lot of the good costs here, as long as a lot costs no more than one and a half times the best a
 * unit sells for; and fills the free slots with the lots that pay the most for a slot, best first. A leg is scored by its profit over the
 * hours it takes, the walk at 215 blocks an hour and an hour at the stalls, and only a leg that pays half a Mark is shown.
 *
 * Everything here is arithmetic on copies ([Shelf]) and on what a [Sight] says, which can be old: nothing is bought, booked or changed, and the
 * same news gives the same answer, which is why the functions can be used by anything that wants a plan.
 */
object Routes {
    /** The walk, in blocks an hour; the hour at the stalls at each end of a leg; the least a leg must pay to be shown, in Marks. */
    const val WALK = 215.0
    const val VISIT = 1.0
    const val LEAST = 0.5

    /** The most lots of one good tried in a basket or sold in one town (the model's `LOTS_MAX`). */
    private const val LOTS = 120

    /** How much dearer than the best a unit sells for a lot may cost and still be tried. */
    private const val DEAR = 1.5

    /** Where the goods are bought: a copy of the shelf of each good in the market where the trader stands, made afresh for each town tried. */
    fun interface Here {
        fun shelf(good: Good): Shelf?
    }

    /** A town the goods might be taken to: where it is, how it stood as far as the news goes, and the share its stalls pay the trader for goods that are not hauled. */
    class Dest(val capital: BlockPos, val name: String, val sight: Sight, val share: Double)

    /**
     * What the plan is made under: [slots] free in the pack, the [dealer]'s share a stall pays for goods that came [haulMin] blocks or more, the
     * [roomDays] days of the wagons' load a stall has room for beyond its own for them, and which kinds of ticket the trader may take [may]. With
     * the haul tickets off ([tickets] false) there are no tickets, no dealer's share and no haul room, and the stalls are as they were. [bonus] is
     * what more than its own pay a ticket gives this trader (the Trade tree's pay perk, `Perk.HAUL_PAY`): a tenth is 0.1.
     */
    class Terms(
        val slots: Int,
        val tickets: Boolean = TradeConfig.hauls(),
        val dealer: Double = TradeConfig.dealerShare(),
        val haulMin: Int = TradeConfig.haulMin(),
        val roomDays: Int = TradeConfig.haulRoomDays(),
        val may: (HaulKind) -> Boolean = { true },
        val bonus: Double = 0.0,
    )

    /** Some of a ticket's units a basket fills. */
    class Serve(val ticket: Sight.Ticket, val units: Int)

    /** The units of [good] a basket buys, what they cost here, what they sell for there ([pay], whole Marks and the tickets' own pay), the slots they take and the tickets they fill. */
    class Line(val good: Good, val units: Int, val cost: Int, val pay: Double, val slots: Int, val serves: List<Serve>)

    /** The way to [to], [distance] blocks away and [hours] there and back to the stalls: the [lines] to buy, their [cost] and their [pay]. */
    class Leg(val to: Dest, val distance: Double, val hours: Double, val lines: List<Line>) {
        val cost: Int get() = lines.sumOf { it.cost }
        val pay: Double get() = lines.sumOf { it.pay }
        val profit: Double get() = pay - cost
        val perHour: Double get() = profit / hours
        val slots: Int get() = lines.sumOf { it.slots }
    }

    /** One step of buying a good: after [units] bought for [cost], they would sell for [pay] and take [slots]. */
    private class Step(val units: Int, val cost: Double, val pay: Double, val slots: Int)

    /** A sale in the order they would be made: [units] for [marks], to [ticket] if it is a ticket's. */
    private class Sale(val units: Int, val marks: Double, val ticket: Sight.Ticket?)

    /** How many slots [units] of [good] take. */
    fun slotsFor(good: Good, units: Int): Int = ceil(units / stack(good).toDouble() - 1e-9).toInt()

    private val STACKS = HashMap<Good, Int>()

    private fun stack(good: Good): Int = STACKS.getOrPut(good) { good.item?.let { ItemStack(it).maxStackSize } ?: 64 }

    /** The hours a leg of [distance] blocks takes, with the stay at the stalls. */
    fun hours(distance: Double): Double = distance / WALK + VISIT

    /**
     * The towns in [dests], ranked by what an hour of the leg from [origin] pays, best first; only those whose basket pays [LEAST]. [here] is the
     * market where the goods are bought.
     */
    fun rank(here: Here, origin: BlockPos, dests: List<Dest>, terms: Terms): List<Leg> {
        val legs = ArrayList<Leg>()
        for (dest in dests) {
            if (dest.capital == origin) continue
            val leg = leg(here, origin, dest, terms)
            if (leg.profit >= LEAST) legs.add(leg)
        }
        return legs.sortedByDescending { it.perHour }
    }

    /** The leg from [origin] to [dest] with the basket that pays best for [terms]'s slots (it may come out empty). */
    fun leg(here: Here, origin: BlockPos, dest: Dest, terms: Terms): Leg {
        val distance = hypot((dest.capital.x - origin.x).toDouble(), (dest.capital.z - origin.z).toDouble())
        val hauled = distance >= terms.haulMin
        val inc = ArrayList<Increment>()
        val selling = HashMap<Good, List<Sale>>()
        for (good in Good.TRADED) {
            val sell = sales(dest, good, hauled, terms)
            if (sell.isEmpty()) continue
            val steps = steps(here, good, sell) ?: continue
            selling[good] = sell
            var prev = ZERO
            var last = Double.MAX_VALUE
            for (st in steps) {
                val dProfit = (st.pay - st.cost) - (prev.pay - prev.cost)
                val dSlots = st.slots - prev.slots
                var ratio = if (dSlots > 0) dProfit / dSlots else if (dProfit > 0) 1e12 else -1e12
                ratio = min(ratio, last)
                last = ratio
                inc.add(Increment(ratio, good, st, prev))
                prev = st
            }
        }
        inc.sortByDescending { it.ratio }
        var used = 0
        val taken = LinkedHashMap<Good, Step>()
        for (i in inc) {
            if (i.ratio <= 0.0) break
            val held = taken[i.good]
            if (held != null) {
                if (held.units != i.prev.units) continue
            } else if (i.prev.units != 0) {
                continue
            }
            val ds = i.step.slots - i.prev.slots
            if (used + ds > terms.slots) continue
            used += ds
            taken[i.good] = i.step
        }
        val lines = taken.map { (good, step) -> line(good, step, selling.getValue(good)) }
        return Leg(dest, distance, hours(distance), lines)
    }

    private class Increment(val ratio: Double, val good: Good, val step: Step, val prev: Step)

    private val ZERO = Step(0, 0.0, 0.0, 0)

    /** What [good] would sell for at [dest], in the order the sales would be made: the tickets the news says are open, best pay first, then lots at the stalls. */
    private fun sales(dest: Dest, good: Good, hauled: Boolean, terms: Terms): List<Sale> {
        val sight = dest.sight
        val shelf = Shelf.of(sight, good)
        val out = ArrayList<Sale>()
        if (terms.tickets && hauled) {
            // A ticket's goods come onto the shelf as they are handed over, and the price stays down for them
            for (t in sight.ticketsFor(good).filter { terms.may(it.kind) }.sortedByDescending { it.each }) {
                out.add(Sale(t.units, t.pay * (1.0 + terms.bonus), t))
                shelf.sold(t.units)
            }
        }
        val share = if (terms.tickets && hauled) max(dest.share, terms.dealer) else dest.share
        val carry = sight.carry(good)
        repeat(LOTS) {
            val extra = if (terms.tickets && hauled) shelf.room(terms.roomDays, carry) else 0.0
            val lot = shelf.sellLot(share, extra) ?: return out
            shelf.sold(lot.count)
            out.add(Sale(lot.count, lot.marks.toDouble(), null))
        }
        return out
    }

    /**
     * The steps of buying [good] here lot by lot for the [sell] it would make there: after each, the units, what they cost, what they would sell
     * for and the slots they take, as long as a lot costs no more than one and a half times the best a unit sells for and the sales can take
     * them. Null if nothing is worth a step.
     */
    private fun steps(here: Here, good: Good, sell: List<Sale>): List<Step>? {
        val shelf = here.shelf(good) ?: return null
        if (shelf.stock < 1) return null
        val top = sell.maxOf { it.marks / it.units }
        val total = sell.sumOf { it.units }
        val out = ArrayList<Step>()
        var units = 0
        var cost = 0.0
        repeat(LOTS) {
            val lot = shelf.buyLot() ?: return out.ifEmpty { null }
            if (lot.marks.toDouble() / lot.count > DEAR * top) return out.ifEmpty { null }
            shelf.bought(lot)
            units += lot.count
            cost += lot.marks
            if (units > total) return out.ifEmpty { null }
            out.add(Step(units, cost, revenue(sell, units), slotsFor(good, units)))
        }
        return out.ifEmpty { null }
    }

    /** What the first [units] of [sell] bring in (a lot cut part way pays in proportion). */
    private fun revenue(sell: List<Sale>, units: Int): Double {
        var left = units.toDouble()
        var marks = 0.0
        for (s in sell) {
            if (left <= 1e-9) break
            val n = min(left, s.units.toDouble())
            marks += s.marks * n / s.units
            left -= n
        }
        return marks
    }

    /** The line for [good]'s [step]: the first [Step.units] of [sell] told as the tickets they fill and the rest. */
    private fun line(good: Good, step: Step, sell: List<Sale>): Line {
        var left = step.units
        val serves = ArrayList<Serve>()
        for (s in sell) {
            if (left <= 0) break
            val n = min(left, s.units)
            if (s.ticket != null) serves.add(Serve(s.ticket, n))
            left -= n
        }
        return Line(good, step.units, step.cost.toInt(), step.pay, step.slots, serves)
    }
}
