package io.github.vaspyyy.guildmark.economy

import kotlin.math.sqrt

/**
 * What it costs each capital to bring its goods in, as a share of their base price, for all of a good brought (see
 * [Market.freight], which charges it on the share the caravans have lately had to bring). A good another open
 * capital has to spare, making more of it in a week than it gets through, comes from the nearest such capital along
 * the road: [PER_BLOCK] of its base a block, the road reckoned [WINDING] times the way as the crow flies, and [FAR] at
 * most. One no open capital has to spare comes from afar, at [Economy.FREIGHT] as it always has. So what a capital
 * makes is cheap there and dearer the farther it has to come (the coast's fish inland above all), and a trader
 * carrying it between capitals is paid for the road; at any one market a good's price is the same bought or sold, so
 * buying and selling there still never pays.
 *
 * Worked out afresh as each market opens, as its town is counted again, and as the markets are loaded (see
 * [Markets]); not kept with the world. Until another capital's market opens, everything comes from afar.
 */
object Carriage {
    /** Of a good's base, what bringing it costs a block along the road. */
    const val PER_BLOCK = 0.001

    /** The road between two capitals against the crow's way. */
    const val WINDING = 1.25

    /** The most bringing a good in costs, of its base, however far it comes. */
    const val FAR = 2.0

    /** Of a week's days, the share a town's makers work: all but the rest day (see [Conditions.restDay]). */
    const val WORKING = 6.0 / 7.0

    /** Whether [town] has [good] to spare: over a week, rest day and all, it makes more of it than it uses and loses to spoiling. */
    fun spare(town: Town, good: Good): Boolean = town.made(good) > 0.0 && town.made(good) * WORKING >= town.use(good) + town.spoils(good)

    /** Sets the carry of each of [entries]' markets (see [Market.carry]) from where the others stand and what they have to spare. */
    fun reckon(entries: Collection<Markets.Entry>) {
        val all = entries.toList()
        for (entry in all) {
            val here = entry.capital
            for (good in Good.entries) {
                var nearest = Double.MAX_VALUE
                if (good.traded) for (other in all) {
                    if (other === entry || !spare(other.town, good)) continue
                    val dx = (other.capital.x - here.x).toDouble()
                    val dz = (other.capital.z - here.z).toDouble()
                    nearest = minOf(nearest, sqrt(dx * dx + dz * dz))
                }
                entry.market.carry[good.ordinal] = if (nearest == Double.MAX_VALUE) Economy.FREIGHT else minOf(FAR, nearest * WINDING * PER_BLOCK)
            }
        }
    }
}
