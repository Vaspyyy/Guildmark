package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.economy.Markets

/**
 * How dear food has been this week (living-world/cook/design.md 5.1): the reading of the live market behind
 * [Pricing]'s arithmetic. The meal price of every market hour a capital's record has seen is kept in its ring
 * ([Rec.ring], one slot for each of the last [Pricing.WINDOW] hours) and the index is their mean, clipped to 0.90 to
 * 1.15, so a push on the staples is worth a 168th of its height an hour. A record starts its week when it is first
 * made, so until it has seen a week the mean is of the hours it has seen, and with none it is the price now.
 */
object Index {
    /** The meal price of [entry]'s town now as a multiple of the plain meal's: each food at the price the market asks, by the share it is eaten by habit. */
    fun ratio(entry: Markets.Entry): Double = Pricing.mealRatio { entry.market.price(entry.town, it) }

    /** The slot of market hour [hour] in the ring. */
    fun slot(hour: Long): Int = Math.floorMod(hour, Pricing.WINDOW.toLong()).toInt()

    /**
     * Writes the meal price of market [hour] into [rec]'s week, called before the hour is worked out. Hours nobody saw
     * leave their slots empty, and a clock set back empties the hours that are now to come again.
     */
    fun record(rec: Rec, entry: Markets.Entry, hour: Long) {
        val last = rec.ringHour
        if (last == NO_DAY) {
            rec.ring.fill(0f)
        } else if (hour <= last) {
            if (last - hour >= Pricing.WINDOW) rec.ring.fill(0f) else for (h in hour..last) rec.ring[slot(h)] = 0f
        } else if (hour - last > 1) {
            if (hour - last - 1 >= Pricing.WINDOW) rec.ring.fill(0f) else for (h in last + 1 until hour) rec.ring[slot(h)] = 0f
        }
        rec.ring[slot(hour)] = ratio(entry).toFloat()
        rec.ringHour = hour
    }

    /** The index for [entry]'s town: the mean of the hours [rec] has seen, or the price now when it has seen none, clipped. */
    fun of(rec: Rec?, entry: Markets.Entry): Double {
        var sum = 0.0
        var n = 0
        if (rec != null && rec.ringHour != NO_DAY) {
            for (v in rec.ring) {
                if (v > 0f) {
                    sum += v
                    n++
                }
            }
        }
        return Pricing.clip(if (n == 0) ratio(entry) else sum / n)
    }

    /** How many hours of the week [rec] has seen. */
    fun seen(rec: Rec?): Int = if (rec == null || rec.ringHour == NO_DAY) 0 else rec.ring.count { it > 0f }
}
