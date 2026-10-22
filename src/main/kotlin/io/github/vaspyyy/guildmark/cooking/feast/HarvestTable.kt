package io.github.vaspyyy.guildmark.cooking.feast

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.festival.FestivalPlan
import io.github.vaspyyy.guildmark.festival.FestivalSite
import net.minecraft.server.level.ServerLevel

/**
 * The Harvest Table at Sheafmoot (living-world/cook/design.md 6.8): the cook-off beside the Produce Show. Part L's;
 * until it lands the festival has only its Produce Show.
 */
object HarvestTable {
    /** What a dish of [kind] at [tier] scores: the tier, the kind and the autumn, rounded (stub: 0). */
    fun score(kind: DishKind, tier: Tier): Int = 0

    /** Seeds the town's own entries when the fair opens at [site] (stub: nothing). */
    fun townEntries(level: ServerLevel, site: FestivalSite) {}

    /** Lays the entries of [contest] on the trestle of [plan] (stub: nothing). */
    fun show(level: ServerLevel, plan: FestivalPlan, contest: FestivalContest) {}

    /** Clears the plates left on the trestle of [plan] (stub: nothing). */
    fun clear(level: ServerLevel, plan: FestivalPlan) {}

    /** Part L's registration: the `cook feast` command (stub: nothing). */
    fun register() {}
}
