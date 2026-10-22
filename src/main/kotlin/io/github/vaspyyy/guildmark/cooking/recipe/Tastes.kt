package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.season.Season

/**
 * What the seasons and the towns like to eat (living-world/cook/design.md 2.8), as multipliers of what a dish fetches:
 * a season moves a kind's value by up to 10% (warming food is worth more in winter, orchard food in autumn, light food in
 * summer); a capital's [CityStyle] likes two kinds (+12%) and dislikes one (-12%).
 */
object Tastes {
    /** How far a season moves a kind's value, at most. */
    const val SEASON_STEP = 0.10

    /** How far a town's liking or disliking moves a kind's value. */
    const val PALATE_STEP = 0.12

    /** Per kind, what each season does to its value in steps of [SEASON_STEP]: spring, summer, autumn, winter (-1, 0 or +1). */
    private val SEASONS: Map<DishKind, IntArray> = mapOf(
        DishKind.POTTAGE to intArrayOf(0, -1, 0, 1),
        DishKind.BROTH to intArrayOf(0, -1, 0, 1),
        DishKind.CHOWDER to intArrayOf(0, -1, 0, 1),
        DishKind.STEW to intArrayOf(0, -1, 1, 1),
        DishKind.ROAST to intArrayOf(0, -1, 1, 1),
        DishKind.PIE to intArrayOf(0, -1, 1, 1),
        DishKind.ROOTS to intArrayOf(0, -1, 1, 0),
        DishKind.TART to intArrayOf(0, 0, 1, 0),
        DishKind.CAKES to intArrayOf(1, 0, 0, 0),
        DishKind.FISH to intArrayOf(1, 1, 0, -1),
        DishKind.LOAF to intArrayOf(0, 0, 0, 0),
        DishKind.STRIPS to intArrayOf(0, 0, 0, 0),
    )

    /** The two kinds each style likes. */
    private val LIKES: Map<CityStyle, Set<DishKind>> = mapOf(
        CityStyle.TEMPERATE to setOf(DishKind.PIE, DishKind.TART),
        CityStyle.NORTHERN to setOf(DishKind.STEW, DishKind.STRIPS),
        CityStyle.DESERT to setOf(DishKind.ROOTS, DishKind.LOAF),
        CityStyle.SAVANNA to setOf(DishKind.ROAST, DishKind.CAKES),
    )

    /** The one kind each style dislikes. */
    private val DISLIKES: Map<CityStyle, DishKind> = mapOf(
        CityStyle.TEMPERATE to DishKind.STRIPS,
        CityStyle.NORTHERN to DishKind.TART,
        CityStyle.DESERT to DishKind.CHOWDER,
        CityStyle.SAVANNA to DishKind.BROTH,
    )

    /** How much more or less [season] values [kind]: 1.1, 1.0 or 0.9. */
    fun season(kind: DishKind, season: Season): Double = 1.0 + SEASON_STEP * (SEASONS[kind]?.getOrNull(season.ordinal) ?: 0)

    /** How much more or less a town of [style] values [kind]: 1.12 if it likes it, 0.88 if it does not, else 1. */
    fun palate(style: CityStyle, kind: DishKind): Double = when {
        LIKES[style]?.contains(kind) == true -> 1.0 + PALATE_STEP
        DISLIKES[style] == kind -> 1.0 - PALATE_STEP
        else -> 1.0
    }
}
