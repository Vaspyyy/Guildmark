package io.github.vaspyyy.guildmark.cooking.feast

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishTooltip
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.festival.FestivalPlan
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.season.Season
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The Harvest Table at Sheafmoot (living-world/cook/design.md 6.8, Part L): the cook-off beside the Produce Show, held at a
 * capital's Sheafmoot only. What a dish is worth ([score]) is worked out here; the entries are the festival's contest's
 * (`FestivalContest.taste`, `cookResults`).
 */
object HarvestTable {
    /** The language key of the table's name, "Harvest Table". */
    const val TITLE = "festival.guildmark.harvest_table"

    /** A tier's points before its kind and the season: Plain 4, Sound 8, Fine 13, Hallmarked 20; Spoilt nothing. */
    fun tierPoints(tier: Tier): Int = when (tier) {
        Tier.SPOILT -> 0
        Tier.PLAIN -> 4
        Tier.SOUND -> 8
        Tier.FINE -> 13
        Tier.HALLMARKED -> 20
    }

    /** What a kind asks of its cook, as a factor: 1 + 0.15 for each step of its complexity (2.5), pottage 1 to the roast's 1.6. */
    fun kindFactor(kind: DishKind): Double = 1.0 + KIND_STEP * kind.complexity

    /** Whether autumn favours [kind] (the season table, 2.8: stew, roots, pie, tart and roast), so it scores [AUTUMN] times more at Sheafmoot. */
    fun autumn(kind: DishKind): Boolean = Tastes.season(kind, Season.AUTUMN) > 1.0

    /** What a dish of [kind] at [tier] scores: the tier, the kind and the autumn, rounded. A Hallmarked roast is 38, a Fine pie 23. */
    fun score(kind: DishKind, tier: Tier): Int =
        Math.round(tierPoints(tier) * kindFactor(kind) * (if (autumn(kind)) AUTUMN else 1.0)).toInt()

    /** How a dish's points are reached, as the host and `cook feast score` tell it: "Hallmarked 20 × Spit Roast 1.6 × autumn 1.2". */
    fun working(kind: DishKind, tier: Tier): Component = Component.translatable(
        if (autumn(kind)) "message.guildmark.feast.working.autumn" else "message.guildmark.feast.working",
        DishTooltip.tierWord(tier), tierPoints(tier), dishName(kind), factor(kind),
    )

    /** The kind's own name, "Spit Roast". */
    fun dishName(kind: DishKind): MutableComponent = Component.translatable("item.guildmark.dish_${kind.id}")

    /** The kind's factor as it is written: "1", "1.15", "1.6". */
    fun factor(kind: DishKind): String = BigDecimal.valueOf(kindFactor(kind)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Seeds the town's own entries when the fair opens at [site] (stub: nothing). */
    fun townEntries(level: ServerLevel, site: FestivalSite) {}

    /** Lays the entries of [contest] on the trestle of [plan] (stub: nothing). */
    fun show(level: ServerLevel, plan: FestivalPlan, contest: FestivalContest) {}

    /** Clears the plates left on the trestle of [plan] (stub: nothing). */
    fun clear(level: ServerLevel, plan: FestivalPlan) {}

    /** Part L's registration: the `cook feast` command (stub: nothing). */
    fun register() {}

    /** Each step of a kind's complexity, and autumn's favour. */
    private const val KIND_STEP = 0.15
    const val AUTUMN = 1.2
}
