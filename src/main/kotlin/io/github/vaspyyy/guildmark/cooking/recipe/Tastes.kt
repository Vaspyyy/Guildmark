package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.season.Season

/** What the seasons and the towns like to eat (living-world/cook/design.md 2.8), as multipliers of what a dish fetches. Part A's; until it lands, no one has a preference. */
object Tastes {
    /** How much more or less [season] values [kind] (stub: 1, no preference). */
    fun season(kind: DishKind, season: Season): Double = 1.0

    /** How much more or less a town of [style] values [kind] (stub: 1, no preference). */
    fun palate(style: CityStyle, kind: DishKind): Double = 1.0
}
