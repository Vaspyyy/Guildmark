package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * What a cook learns (living-world/cook/design.md 3.4): the journal on the player, filled by the verdicts of the ladle and
 * the keepers and the batches they make. Part D's; until it lands nothing is learned or recorded.
 */
object Journal {
    /** [player]'s journal. */
    fun of(player: ServerPlayer): CookJournal = player.getData(CookRegistry.COOK_JOURNAL)

    /** [player] has learned something about [kind] from [verdict] (stub: nothing is learned). */
    fun learn(player: ServerPlayer, kind: DishKind, verdict: Verdict) {}

    /** [player] has made a batch of [kind] at [tier] from [items] (stub: nothing is recorded). */
    fun batch(player: ServerPlayer, kind: DishKind, items: List<ItemStack>, tier: Tier) {}

    /** Hands [player] their Cook's Book, once, the first time they use a station (stub: nothing is given). */
    fun give(player: ServerPlayer) {}
}
