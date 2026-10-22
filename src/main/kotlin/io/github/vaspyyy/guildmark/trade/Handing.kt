package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.core.BlockPos
import kotlin.math.hypot
import kotlin.math.min

/**
 * What a trader carries that a ticket will take (the Tally's Held page). A ticket takes only goods that came a long way: ones the trader bought at a
 * capital's market [far] from the town the ticket is for, within the days the ledger keeps (living-world/trader/design.md, Part 2; `Hauls.deliverable`
 * is the same sum for one ticket). So a pack full of wheat from the stall round the corner is not "carried" as far as a ticket for wheat is
 * concerned, and the page must not say it is. Pure: it works on the facts it is given, and changes nothing.
 */
internal object Handing {
    /** A ticket the trader holds: it wants [owes] more units of [good] at the town whose heart is [at]. */
    class Want(val good: Good, val at: BlockPos, val owes: Int)

    /** Whether goods bought at the market whose heart is [origin] count as hauled at the one at [market]: another market, [haulMin] blocks or more away. */
    fun far(origin: BlockPos, market: BlockPos, haulMin: Int): Boolean =
        origin != market && hypot((origin.x - market.x).toDouble(), (origin.z - market.z).toDouble()) >= haulMin

    /**
     * For each of [wants], in the order given (soonest due first), how many of the units it still owes the trader could hand in now: [carried] says how many
     * of a good the pack and the carriers hold, [parcels] are the hauled goods the ledger lists (oldest first, as they are spent), and only a parcel that
     * [far] counts for that ticket's town. A unit is counted once, for the first ticket that can use it, so that a pack of sixteen is not sixteen for each of
     * two tickets.
     */
    fun ready(wants: List<Want>, parcels: List<Parcel>, carried: (Good) -> Int, haulMin: Int): List<Int> {
        val pack = HashMap<Good, Int>()
        val units = IntArray(parcels.size) { parcels[it].units }
        val out = ArrayList<Int>(wants.size)
        for (want in wants) {
            var have = pack.getOrPut(want.good) { carried(want.good) }
            var give = 0
            for ((i, parcel) in parcels.withIndex()) {
                if (give >= want.owes || have <= 0) break
                if (parcel.good != want.good || units[i] <= 0 || !far(parcel.origin, want.at, haulMin)) continue
                val n = min(min(units[i], have), want.owes - give)
                units[i] -= n
                have -= n
                give += n
            }
            pack[want.good] = have
            out.add(give)
        }
        return out
    }
}
