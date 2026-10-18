package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Town
import io.github.vaspyyy.guildmark.townsfolk.Comings

/**
 * The bakehouse as the town's works see it (design-influence.md section 1, "The second oven"), only with the works on:
 * each oven bakes [SPARE] times the planned town's bread a day over the baking hours, so one oven keeps the town in
 * bread with a quarter to spare and a second covers a cracked one or a grown town. The plan's ovens are its bakeries'
 * (a watermill's stove among them), a baker to each; the works' oven is worked by the journeyman baker it sends for
 * (section 6), or with comings off by the town's own baker beside their first. A cracked oven bakes at its event's
 * factor (one oven of them); anything else under way (a fever) slows them all, and worn tools too. With the works
 * off, [Economy] bakes as it always has.
 */
internal object Ovens {
    /** How far one oven's day outbakes the planned town's bread. */
    const val SPARE = 1.6

    /** Loaves one oven bakes in an hour, for [town] as it was planned. */
    fun each(town: Town): Double = SPARE * (town.planned ?: town).use(Good.BREAD) / Economy.BAKE_HOURS

    /** The ovens the plan gave [town]: its bakeries' (the town as built keeps the count). */
    fun planned(town: Town): Int = minOf(town.planned?.ovens ?: town.ovens, town.ovens)

    /**
     * The ovens at work in [town]: one for each baker, the journeyman the works sent for among them; with comings off,
     * the plan's ovens a baker each and the works' ones beside them. Without the ones the works [added], the plan's
     * own. None with no baker at all.
     */
    fun working(town: Town, added: Boolean = true): Int {
        if (town.bakers <= 0) return 0
        val plan = planned(town)
        val ovens = if (added) town.ovens else plan
        return if (Comings.enabled()) minOf(ovens, town.bakers) else minOf(plan, town.bakers) + (ovens - plan)
    }

    /**
     * What [town]'s ovens can bake this hour in [m], before the tools: [working] ovens, less what a cracked one has
     * lost, at the pace anything else under way leaves them. Without the ones the works [added], what the plan's own
     * could have (to see what a second oven eased).
     */
    fun bake(m: Market, town: Town, added: Boolean = true): Double {
        var n = working(town, added).toDouble()
        if (n <= 0.0) return 0.0
        val all = m.factor(Maker.OVENS, Good.BREAD)
        val cracked = m.events.firstOrNull { it.kind == EventKind.OVEN_CRACKED && (it.good == null || it.good == Good.BREAD) }
        var others = all
        if (cracked != null) {
            // One oven bakes at the crack's pace, the rest as they were
            n = (n - 1.0 + cracked.factor).coerceAtLeast(0.0)
            others = if (cracked.factor > 1e-9) all / cracked.factor else 1.0
        }
        return n * each(town) * others
    }
}
