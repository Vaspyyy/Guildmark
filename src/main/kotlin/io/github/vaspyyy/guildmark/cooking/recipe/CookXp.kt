package io.github.vaspyyy.guildmark.cooking.recipe

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import net.minecraft.server.level.ServerPlayer

/** The COOKING XP a batch pays (living-world/cook/design.md 2.5), with the soft cap of 5.6 inside. Part A's; until it lands, it pays nothing. */
object CookXp {
    /** The first this much COOKING XP a day is paid at the full rate; the rest of the day's, at [COOK_XP_PAST_CAP] of it (5.6). */
    const val COOK_XP_SOFT_CAP = 900

    /** The share of the full rate that XP past [COOK_XP_SOFT_CAP] in a day is paid at (5.6). */
    const val COOK_XP_PAST_CAP = 0.4

    /** Pays [player] their [share] of a batch of [portions] of [kind] at [tier], and answers the XP given (stub: 0). */
    fun award(player: ServerPlayer, kind: DishKind, tier: Tier, portions: Int, share: Double): Int = 0
}
