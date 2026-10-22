package io.github.vaspyyy.guildmark.economy

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** [count] of a good for [marks] Guild Marks. */
class Lot(val count: Int, val marks: Int) {
    val each: Double get() = marks.toDouble() / count

    override fun toString(): String = "$count for $marks"
}

/**
 * How a market's goods change hands with players, a lot at a time since a Guild Mark doesn't split: "11 for 2
 * Marks", "3 for 4 Marks". A buyer always pays at least the price and a seller gets at most [SELL_SHARE] of it (a
 * supplier in good standing a few hundredths more, never so much as three quarters: see [SupplierRank]), each
 * reckoned at what the market will hold once the lot has changed hands, so buying a lot and selling it straight back
 * always loses, and a stall never pays more than it can sell for. The market only buys what it has room for: up to
 * [ROOM] times what it likes to keep. A thin market, whose room is worth less than a Mark, still takes the smallest
 * lot that's worth one while it holds no more than it likes to keep.
 */
object Lots {
    /** Of its own price, what the market pays for what it buys. */
    const val SELL_SHARE = 0.6

    /** The market buys until it holds this many times its target. */
    const val ROOM = 1.25

    /** The most Marks a single lot is priced at. */
    private const val MOST = 16

    /** How far a lot's price may be off the market's for the sake of a round number. */
    private const val CLOSE = 0.08

    /** A lot as near [price] a unit as round numbers allow, never cheaper: for selling to a player. */
    fun dear(price: Double): Lot? = pick(price) { marks -> floor(marks / price).toInt() }

    /** A lot as near [price] a unit as round numbers allow, never dearer: for buying from a player. */
    fun cheap(price: Double): Lot? = pick(price) { marks -> ceil(marks / price).toInt() }

    private fun pick(price: Double, count: (Int) -> Int): Lot? {
        if (price <= 0.0) return null
        var best: Lot? = null
        var bestOff = Double.MAX_VALUE
        for (marks in 1..MOST) {
            val n = count(marks)
            if (n < 1) continue
            val off = abs(marks.toDouble() / n - price) / price
            if (off <= CLOSE) return Lot(n, marks)
            if (off < bestOff) {
                bestOff = off
                best = Lot(n, marks)
            }
        }
        return best
    }

    /** The lot of [good] a player can buy from [m] now, or null if it has none to sell. */
    fun buy(m: Market, town: Town, good: Good): Lot? {
        if (!good.traded) return null
        val stock = floor(m.stock(good) + 1e-6).toInt()
        if (stock < 1) return null
        var lot = dear(m.price(town, good)) ?: return null
        // Reckoned at what's left once it's gone, which is dearer
        repeat(2) { lot = dear(m.price(town, good, m.stock(good) - lot.count)) ?: return null }
        if (lot.count <= stock) {
            var count = lot.count
            var marks = lot.marks
            // Still short of the price once it's gone: a unit fewer, and a Mark more only for a lot of one
            while (marks < count * m.price(town, good, m.stock(good) - count)) if (count > 1) count-- else marks++
            return Lot(count, marks)
        }
        // Too few left for a whole lot: what there is, at the price of the last of it, rounded up
        return Lot(stock, ceil(stock * m.price(town, good, 0.0)).toInt())
    }

    /**
     * How many of [good] [m] will still take from players, [extra] more units than it usually has room for (the
     * haul room a dealer's lot is given, see [io.github.vaspyyy.guildmark.trade.Hauls.room]).
     */
    fun wants(m: Market, town: Town, good: Good, extra: Double = 0.0): Int =
        if (!good.traded) 0 else floor(town.target(good) * ROOM + extra - m.stock(good)).toInt().coerceAtLeast(0)

    /**
     * The lot of [good] [m] will buy from a player now, paying [share] of its price (a supplier's standing lifts it a
     * little over [SELL_SHARE], see [Suppliers.share]), or null if it wants no more. [extra] is room on top of the
     * usual, as for [wants].
     */
    fun sell(m: Market, town: Town, good: Good, share: Double = SELL_SHARE, extra: Double = 0.0): Lot? {
        val room = wants(m, town, good, extra)
        if (room < 1) return null
        var lot = cheap(m.price(town, good) * share) ?: return null
        // Reckoned at what it will hold once it's in, which is cheaper
        repeat(2) { lot = cheap(m.price(town, good, m.stock(good) + lot.count) * share) ?: return null }
        var count = lot.count
        while (lot.marks > count * m.price(town, good, m.stock(good) + count) * share) count++
        lot = Lot(count, lot.marks)
        if (lot.count <= room) return lot
        // Room for less than a lot: what that's worth, rounded down
        val marks = floor(room * m.price(town, good, m.stock(good) + room) * share).toInt()
        if (marks >= 1) return Lot(room, marks)
        // Worth less than a Mark: a thin market, holding no more than it likes to keep, takes the smallest lot that's worth one
        if (m.stock(good) > town.target(good)) return null
        var least = ceil(1.0 / (m.price(town, good) * share)).toInt().coerceAtLeast(1)
        while (least * m.price(town, good, m.stock(good) + least) * share < 1.0) least++
        return Lot(least, 1)
    }

    /** A player has bought [lot] of [good]: it leaves the market, and the price stays up for it (see [Market.traded]). */
    fun bought(m: Market, good: Good, lot: Lot) {
        m.traded(good, -lot.count.toDouble())
        m.tally(Tally.BOUGHT, good, lot.count.toDouble())
    }

    /** A player has sold [lot] of [good]: it's the market's now, and the price stays down for it (see [Market.traded]). */
    fun sold(m: Market, good: Good, lot: Lot) {
        m.traded(good, lot.count.toDouble())
        m.tally(Tally.SOLD, good, lot.count.toDouble())
    }
}
