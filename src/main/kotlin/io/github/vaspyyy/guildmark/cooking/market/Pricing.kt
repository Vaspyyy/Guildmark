package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.economy.Good

/**
 * What a portion is worth (living-world/cook/design.md 5.1):
 *
 *     value = M_BASE x factor[kind] x tier value x index x season[kind] x palate[style][kind] x freshness
 *
 * [M_BASE] is the price of a plain meal at the town's habit, worked out from the goods themselves. The index is how
 * dear food has been this week: the meal price of each of the last [WINDOW] hours as a multiple of [M_BASE], averaged and
 * clipped to [LOW] to [HIGH], so a push on the staples is worth a 168th of its height an hour and a famine is no bonanza.
 * This object is the arithmetic and nothing else (no world, no market), so it can be checked on its own; the ring of
 * hours is [Rec]'s and the reading of the live market is [Index]'s.
 */
object Pricing {
    /** Hours of meal prices the index averages: seven days. */
    const val WINDOW = 168

    /** The lowest and the highest the index goes (5.1). */
    const val LOW = 0.90
    const val HIGH = 1.15

    /** What a plain meal costs at the town's habit: the sum over the foods of the share it is eaten by, times its base price, per meal it makes. 0.1374 Marks. */
    val M_BASE: Double = Good.FOODS.sumOf { it.share * it.base / it.food }

    /**
     * What a meal costs a town whose foods go for [price] each, as a multiple of [M_BASE]: each food by the share it is
     * eaten by habit (not by the choice the economy makes of them at a meal, which leans to what is cheap), per meal it
     * makes (the model's `meal_price`, `analysis.py` table 6).
     */
    fun mealRatio(price: (Good) -> Double): Double = Good.FOODS.sumOf { it.share * price(it) / it.food } / M_BASE

    /** The index a mean of the ring's meal prices comes to, clipped. */
    fun clip(mean: Double): Double = mean.coerceIn(LOW, HIGH)

    /** What freshness does to the value (3.2): fresh 1, stale 0.6, gone off nothing. */
    fun freshness(stage: Stage): Double = when (stage) {
        Stage.FRESH -> 1.0
        Stage.STALE -> 0.6
        Stage.GONE -> 0.0
    }

    /** What a Sound portion of [kind] is worth at [index], before the season, the palate and age. */
    fun base(kind: DishKind, tier: Tier, index: Double): Double = M_BASE * kind.factor * tier.value * index

    /** The whole formula for one portion of [kind] in Marks. */
    fun value(kind: DishKind, tier: Tier, stage: Stage, index: Double, season: Double, palate: Double): Double =
        base(kind, tier, index) * season * palate * freshness(stage)
}
