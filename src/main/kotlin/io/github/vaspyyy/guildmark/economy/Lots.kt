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
 * Marks", "3 for 4 Marks". A buyer always pays at least the price and a seller gets at most [SELL_SHARE] of it, each
 * reckoned at what the market will hold once the lot has changed hands, so buying a lot and selling it straight back
 * always loses, and a stall never pays more than it can sell for. The market only buys what it has room for: up to
 * [ROOM] times what it likes to keep.
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
            var marks = lot.marks
            while (marks < lot.count * m.price(town, good, m.stock(good) - lot.count)) marks++
            return Lot(lot.count, marks)
        }
        // Too few left for a whole lot: what there is, at the price of the last of it, rounded up
        return Lot(stock, ceil(stock * m.price(town, good, 0.0)).toInt())
    }

    /** How many of [good] [m] will still take from players. */
    fun wants(m: Market, town: Town, good: Good): Int =
        if (!good.traded) 0 else floor(town.target(good) * ROOM - m.stock(good)).toInt().coerceAtLeast(0)

    /** The lot of [good] [m] will buy from a player now, or null if it wants no more. */
    fun sell(m: Market, town: Town, good: Good): Lot? {
        val room = wants(m, town, good)
        if (room < 1) return null
        var lot = cheap(m.price(town, good) * SELL_SHARE) ?: return null
        // Reckoned at what it will hold once it's in, which is cheaper
        repeat(2) { lot = cheap(m.price(town, good, m.stock(good) + lot.count) * SELL_SHARE) ?: return null }
        var count = lot.count
        while (lot.marks > count * m.price(town, good, m.stock(good) + count) * SELL_SHARE) count++
        lot = Lot(count, lot.marks)
        if (lot.count <= room) return lot
        // Room for less than a lot: what that's worth, rounded down
        val marks = floor(room * m.price(town, good, m.stock(good) + room) * SELL_SHARE).toInt()
        return if (marks >= 1) Lot(room, marks) else null
    }

    /** A player has bought [lot] of [good]: it leaves the market. */
    fun bought(m: Market, good: Good, lot: Lot) {
        m.take(Site.MARKET, good, lot.count.toDouble())
        m.tally(Tally.BOUGHT, good, lot.count.toDouble())
    }

    /** A player has sold [lot] of [good]: it's the market's now. */
    fun sold(m: Market, good: Good, lot: Lot) {
        m.add(Site.MARKET, good, lot.count.toDouble())
        m.tally(Tally.SOLD, good, lot.count.toDouble())
    }
}
