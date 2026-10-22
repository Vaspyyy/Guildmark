package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Bought
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Suppliers
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The hauled ledger (living-world/trader/design.md, Part 2): which of the goods a trader carries came a long way. A unit is *hauled*
 * if its player bought it at a capital's market at least `tradeHaulMin` (600) blocks from the market it is sold or delivered at, within the
 * last [Suppliers.KEPT] (10) days. The ledger is not new state of its own: it reads what every capital already remembers a player bought
 * ([io.github.vaspyyy.guildmark.economy.Standing.bought], which is written for every purchase), plus the parcels a player keeps out of
 * those records ([TradeBooks.Book.held]: goods a Store hands back, and what `trade ledger add` puts in), which keep their name for
 * [HELD_DAYS] days from the day they were bought. It is spent first in, first out, a unit coming off exactly once however it leaves: sold
 * at a dealer's share, delivered to a ticket, lost on the road, bonded in a Store.
 *
 * Nothing is stamped on the items, so they still stack; the ledger is a count, and a ledger can never go below nothing. Only the player's own
 * purchases count (a party does not share hauls). Reading it makes nothing: a player nothing is written for has no record in it.
 */
object Ledger {
    /** Days a parcel in [TradeBooks.Book.held] keeps its name, counted from the day it was bought (a Store keeps goods this long). */
    const val HELD_DAYS = 30L

    /** The most parcels one player's books hold out of the markets' records; a longer list drops its oldest. */
    private const val MOST_HELD = 64

    /** One parcel as the ledger lists it: [daysLeft] more days (counting today) it keeps its name, and whether it is one of the books' ([kept]) and not a market's record. */
    class Line(val parcel: Parcel, val daysLeft: Int, val kept: Boolean)

    /** A parcel with the place it is written, so it can be taken down: a market's record ([record]) or an entry of the books' [held] list ([index]). */
    private class Hold(val origin: BlockPos, val good: Good, val day: Long, val units: Int, val record: Bought?, val index: Int, val keep: Long)

    /** Whether goods bought at [origin] count as hauled at [market]: another market, [TradeConfig.haulMin] blocks or more away. */
    private fun far(origin: BlockPos, market: BlockPos): Boolean = origin != market && Hauls.distance(origin, market) >= TradeConfig.haulMin()

    /** Everything [player] has of [good] (any good if null) that counts at [market] (anywhere if null), oldest first. */
    private fun holds(level: ServerLevel, player: UUID, good: Good?, market: BlockPos?): List<Hold> {
        val today = Suppliers.today(level)
        val out = ArrayList<Hold>()
        for ((capital, standing) in Suppliers.get(level).everywhere(player)) {
            if (market != null && !far(capital, market)) continue
            standing.forget(today)
            for (b in standing.bought) {
                if (b.count <= 0 || good != null && b.good != good) continue
                out.add(Hold(capital, b.good, b.day, b.count, b, -1, Suppliers.KEPT))
            }
        }
        val held = TradeBooks.peek(level)?.get(player)?.held
        if (held != null) {
            for ((i, p) in held.withIndex()) {
                if (p.units <= 0 || p.boughtAt + HELD_DAYS <= today || good != null && p.good != good) continue
                if (market != null && !far(p.origin, market)) continue
                out.add(Hold(p.origin, p.good, p.boughtAt, p.units, null, i, HELD_DAYS))
            }
        }
        out.sortBy { it.day }
        return out
    }

    /** The parcels of [good] [player] has that count as hauled at [market] (wherever they would, if null), oldest first. */
    fun hauled(level: ServerLevel, player: UUID, good: Good, market: BlockPos? = null): List<Parcel> =
        holds(level, player, good, market).map { Parcel(it.good, it.units, it.origin, it.day) }

    /** How many units of [good] [player] has that count as hauled at [market] (wherever they would, if null). */
    fun count(level: ServerLevel, player: UUID, good: Good, market: BlockPos? = null): Int =
        holds(level, player, good, market).sumOf { it.units }

    /** Every parcel [player] has, of every good, with the days it has left, oldest first (for `trade ledger`). */
    fun lines(level: ServerLevel, player: UUID): List<Line> {
        val today = Suppliers.today(level)
        return holds(level, player, null, null).map { Line(Parcel(it.good, it.units, it.origin, it.day), (it.day + it.keep - today).toInt().coerceAtLeast(0), it.record == null) }
    }

    /**
     * Takes [units] of [good] off [player]'s ledger, the oldest first, from what counts at [market] (from everything, if null: a Store
     * moving goods in, or goods lost wherever they were). Gives the parcels taken, as many as there were up to [units]: nothing if there
     * were none. A unit that comes off here is gone from the ledger for good; whoever took it keeps its parcel if it has a use for it
     * (a Store bonds it, [add] gives it back).
     */
    fun take(level: ServerLevel, player: UUID, good: Good, units: Int, market: BlockPos? = null): List<Parcel> {
        if (units <= 0) return listOf()
        var left = units
        val picks = ArrayList<Pair<Hold, Int>>()
        for (h in holds(level, player, good, market)) {
            if (left <= 0) break
            val n = minOf(h.units, left)
            picks.add(h to n)
            left -= n
        }
        return commit(level, player, picks)
    }

    /** [units] of [good] are gone from [player] some way the ledger could not see: they come off, oldest first, wherever they stand. Gives how many came off. */
    fun lose(level: ServerLevel, player: UUID, good: Good, units: Int): Int = take(level, player, good, units).sumOf { it.units }

    /**
     * [taken] of the [held] units of [good] [player] carried were lost on the road (the Hunt): the same share of each of their hauled
     * parcels goes, `(held - taken) / held` of each surviving, rounded down. Gives how many hauled units came off.
     */
    fun lose(level: ServerLevel, player: UUID, good: Good, held: Int, taken: Int): Int {
        if (held <= 0 || taken <= 0) return 0
        val keep = (held - taken).coerceIn(0, held).toDouble() / held
        val picks = ArrayList<Pair<Hold, Int>>()
        for (h in holds(level, player, good, null)) {
            val n = h.units - Math.floor(h.units * keep + 1e-9).toInt()
            if (n > 0) picks.add(h to n)
        }
        return commit(level, player, picks).sumOf { it.units }
    }

    /** Writes the takings down and gives the parcels they were. */
    private fun commit(level: ServerLevel, player: UUID, picks: List<Pair<Hold, Int>>): List<Parcel> {
        if (picks.isEmpty()) return listOf()
        val out = ArrayList<Parcel>()
        var suppliers = false
        val books = TradeBooks.peek(level)
        val book = books?.get(player)
        val held = book?.held
        for ((h, n) in picks) {
            out.add(Parcel(h.good, n, h.origin, h.day))
            val record = h.record
            if (record != null) {
                record.count -= n
                suppliers = true
            } else if (held != null) {
                held[h.index] = held[h.index].copy(units = held[h.index].units - n)
            }
        }
        if (held != null && books != null) {
            held.removeAll { it.units <= 0 }
            books.changed()
        }
        if (suppliers) Suppliers.get(level).changed()
        return out
    }

    /**
     * Puts [parcels] into [player]'s books as hauled goods that no market's record holds (a Store hands its goods back; `trade ledger
     * add`), to count wherever they are far enough from, for [HELD_DAYS] days from the day they were bought: ones older than that are
     * let go. A player's books hold only so many, the oldest dropped.
     */
    fun add(level: ServerLevel, player: UUID, parcels: List<Parcel>) {
        val today = Suppliers.today(level)
        val fresh = parcels.filter { it.units > 0 && it.boughtAt + HELD_DAYS > today }
        if (fresh.isEmpty()) return
        val books = TradeBooks.get(level)
        val held = books.of(player).held
        for (p in fresh) {
            val same = held.indexOfFirst { it.good == p.good && it.origin == p.origin && it.boughtAt == p.boughtAt }
            if (same >= 0) held[same] = held[same].copy(units = held[same].units + p.units) else held.add(p)
        }
        held.sortBy { it.boughtAt }
        while (held.size > MOST_HELD) held.removeAt(0)
        books.changed()
    }

    /** Empties [player]'s ledger: every record of what they bought at a market and every parcel in their books (`trade ledger clear`). Gives how many units went. */
    fun clear(level: ServerLevel, player: UUID): Int {
        val all = holds(level, player, null, null)
        val units = all.sumOf { it.units }
        commit(level, player, all.map { it to it.units })
        return units
    }
}
