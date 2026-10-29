package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.trade.Carriers
import io.github.vaspyyy.guildmark.trade.Hauls
import io.github.vaspyyy.guildmark.trade.HaulData
import io.github.vaspyyy.guildmark.trade.Ledger
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.ContainerHelper

/**
 * What a party takes from a trader (living-world/trader/design.md, Part 4, "The share"): between half and seven tenths of the units of goods
 * the trader carries within eight blocks of them, the dearest first, by the rule a waylaid wagon's share is taken by ([Caravans.taken]). Only
 * goods are touched, the market's `Good` items; a tool, a weapon, armour, Marks, a Tally, a Warrant or a harness is not one, so none is ever
 * looked at. Where the goods are is asked of [Carriers] and not a list of this file's own, so a cart or a wagon that joins the registry joins
 * the share with it; a pack animal that has bolted is beyond the eight blocks and takes its load with it.
 */
internal object HuntShare {
    /** [units] of [good] taken off a trader who carried [held] of it. */
    class Lot(val good: Good, val units: Int, val held: Int)

    /** The goods [player] carries within eight blocks, by unit, as [Caravans.taken] reads a load. */
    fun load(player: ServerPlayer): DoubleArray {
        val boxes = Carriers.holdings(player, Carriers.REACH)
        val out = DoubleArray(Good.entries.size)
        for (good in Good.TRADED) {
            val item = good.item ?: continue
            var n = 0
            for (box in boxes) n += ContainerHelper.clearOrCountMatchingItems(box, { it.`is`(item) }, Int.MAX_VALUE, true)
            out[good.ordinal] = n.toDouble()
        }
        return out
    }

    /** How many of [good] [player] carries within eight blocks now. */
    fun carried(player: ServerPlayer, good: Good): Int {
        val item = good.item ?: return 0
        var n = 0
        for (box in Carriers.holdings(player, Carriers.REACH)) n += ContainerHelper.clearOrCountMatchingItems(box, { it.`is`(item) }, Int.MAX_VALUE, true)
        return n
    }

    /** What the goods in [load] come to at their base prices, in Marks. */
    fun worth(load: DoubleArray): Double = Good.TRADED.sumOf { it.base * load[it.ordinal] }

    /** The share of a trader's goods a party takes this time: half to seven tenths, by [random]'s dice (a wagon's is the same). */
    fun share(random: RandomSource): Double = 0.5 + 0.2 * random.nextDouble()

    /** What [share] of [load] would be: nothing for a trader with no goods. */
    fun plan(load: DoubleArray, share: Double): List<Lot> =
        Caravans.taken(load, share).map { (good, n) -> Lot(good, n, Math.floor(load[good.ordinal] + 1e-6).toInt()) }

    /**
     * Takes [lots] off [player]: from their pack first and then each carrier within eight blocks, the units of each good in the order the
     * containers are held. Gives what really came off (a container may have changed since the plan was made), the dearest first.
     */
    fun take(player: ServerPlayer, lots: List<Lot>): List<Lot> {
        val boxes = Carriers.holdings(player, Carriers.REACH)
        val out = ArrayList<Lot>()
        for (lot in lots) {
            val item = lot.good.item ?: continue
            var left = lot.units
            for (box in boxes) {
                if (left <= 0) break
                val took = ContainerHelper.clearOrCountMatchingItems(box, { it.`is`(item) }, left, false)
                if (took > 0) box.setChanged()
                left -= took
            }
            val done = lot.units - left
            if (done > 0) out.add(Lot(lot.good, done, lot.held))
        }
        return out
    }

    /**
     * [lots] shared out among [n] sacks, one to each of the party: each good's units are cut into as many parts as there are sacks (or units)
     * and the parts dealt round, so that the dearest good is carried by more than one and the weight is even.
     */
    fun sacks(lots: List<Lot>, n: Int): List<MutableList<Pair<Good, Int>>> {
        val out = List(n.coerceAtLeast(1)) { ArrayList<Pair<Good, Int>>() }
        var start = 0
        for (lot in lots) {
            val parts = minOf(out.size, lot.units)
            if (parts <= 0) continue
            val base = lot.units / parts
            val extra = lot.units % parts
            for (i in 0 until parts) out[(start + i) % out.size].add(lot.good to base + if (i < extra) 1 else 0)
            start += parts
        }
        return out
    }

    /**
     * The books of the trader's life after [lots] came off [player]: the hauled ledger loses the same share of each good's hauled units
     * (Part 2), and a booking on a ticket for a good they can no longer fill is let go with its deposit given back (the Hunt is no fault of
     * theirs). Gives the Marks given back in all.
     */
    fun settle(level: ServerLevel, player: ServerPlayer, lots: List<Lot>): Int {
        var back = 0
        val uuid = player.uuid
        val data = HaulData.peek(level)
        for (lot in lots) {
            Ledger.lose(level, uuid, lot.good, lot.held, lot.units)
            if (data == null) continue
            var need = 0
            for (book in data.all()) for (t in book.tickets) {
                if (t.good != lot.good) continue
                t.bookings.firstOrNull { it.taker == uuid && !it.house && it.left > 0 }?.let { need += it.left }
            }
            if (need > 0 && carried(player, lot.good) < need) {
                val given = Hauls.release(level, uuid, lot.good, refund = true)
                if (given > 0) back += given
            }
        }
        return back
    }
}
