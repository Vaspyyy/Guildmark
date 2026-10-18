package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Site
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.Town

/**
 * The town granary's rules (design-influence.md section 1, "The granary"), plain arithmetic on a [Market] like the
 * rest of its hour (see [Economy]). Its bins are [Site.GRANARY], with room by the town's granary grade
 * ([Town.granary]): seven days of the planned town's wheat, and with the great granary twenty-one of wheat and seven
 * of each root. Its grain never spoils and isn't the market's stock, so prices and the caravans go by the market.
 *
 * - It gives: while the mill holds under half a day's grain and the market's wheat is under half its target, the
 *   carters bring its grain to the mill ([release], Route.RESERVE, tallied RELEASED); before each meal the great
 *   granary puts out a meal's worth of any root the market is under half its target of ([roots]).
 * - It fills: at nine, what the morning caravan would take away goes into it first while it has room ([store],
 *   tallied STORED); while the market holds 90% of a good's target or more, each caravan brings a thirtieth of its
 *   room of it more ([trade], on the wagons as freight, see [WorksFreight]); and its first filling, paid for in its
 *   price, comes on the six caravans after it opens.
 *
 * With the town's works off it neither gives nor fills (what's in it stays in it).
 */
internal object Reserve {
    /** Of its room, how much more of a good each caravan brings for it while the market is well stocked. */
    const val TRADE_SHARE = 1.0 / 30.0

    /** The share of a good's target the market must hold for the caravans to bring the granary more. */
    const val WELL_STOCKED = 0.9

    /** The caravans its first filling comes on. */
    const val FILLING_CARAVANS = 6

    /** The granary's room by good in [town] (nothing without one). */
    fun room(town: Town): DoubleArray = WorksKinds.room(town.granary, town)

    /** Whether the granary gives and fills: one stands, and the works are on. */
    fun open(town: Town): Boolean = town.granary > 0 && Works.enabled()

    /** How full the granary is of [good], against its room: 0 with no room for it. */
    fun full(m: Market, town: Town, good: Good): Double {
        val room = room(town)[good.ordinal]
        return if (room <= 0.0) 0.0 else (m[Site.GRANARY, good] / room).coerceIn(0.0, 1.0)
    }

    /**
     * How much grain the carters fetch from the granary for the mill this carting hour, after the market hall's
     * grain has gone ([millDay] is a day's milling): while the mill still holds under half a day and the market's
     * wheat is under half its target, enough to bring it to half a day, which is at most half a day's milling.
     */
    fun release(m: Market, town: Town, millDay: Double): Double {
        if (!open(town)) return 0.0
        val held = m[Site.MILL, Good.WHEAT]
        if (held >= millDay * 0.5 || m.stock(Good.WHEAT) >= town.target(Good.WHEAT) * 0.5) return 0.0
        return millDay * 0.5 - held
    }

    /**
     * Of [n] of [good] the morning caravan would take away, what goes into the granary instead while it has room:
     * moved there off the market now, and tallied STORED. What's left of [n] goes with the caravan.
     */
    fun store(m: Market, town: Town, good: Good, n: Double): Double {
        if (n <= 0.0 || !open(town)) return 0.0
        val space = room(town)[good.ordinal] - m[Site.GRANARY, good]
        if (space <= 0.0) return 0.0
        val took = m.take(Site.MARKET, good, minOf(n, space))
        if (took <= 0.0) return 0.0
        m.add(Site.GRANARY, good, took)
        m.tally(Tally.STORED, good, took)
        return took
    }

    /** Before a meal: the great granary puts out up to a meal's worth of each root the market holds under half its target of (RELEASED). */
    fun roots(m: Market, town: Town) {
        if (town.granary < 2 || !Works.enabled()) return
        for (g in WorksKinds.ROOTS) {
            if (m.stock(g) >= town.target(g) * 0.5) continue
            val n = m.take(Site.GRANARY, g, town.use(g) / Economy.MEALS)
            if (n <= 0.0) continue
            m.add(Site.MARKET, g, n)
            m.tally(Tally.RELEASED, g, n)
        }
    }

    /**
     * What [slots] caravans bring for the granary over the town's trade, by good: while the market holds
     * [WELL_STOCKED] of a good's target or more, [TRADE_SHARE] of the room for it each, as far as there's room with
     * what's in, what's on its way already ([coming]) and what's still owed of its first filling ([owed]).
     */
    fun trade(m: Market, town: Town, coming: DoubleArray, owed: DoubleArray, slots: Int): DoubleArray {
        val out = DoubleArray(Good.entries.size)
        if (!open(town)) return out
        val room = room(town)
        for (g in Good.entries) {
            val r = room[g.ordinal]
            if (r <= 0.0 || m.stock(g) < town.target(g) * WELL_STOCKED) continue
            val space = r - m[Site.GRANARY, g] - coming[g.ordinal] - owed[g.ordinal]
            out[g.ordinal] = minOf(r * TRADE_SHARE * slots, space).coerceAtLeast(0.0)
        }
        return out
    }
}
