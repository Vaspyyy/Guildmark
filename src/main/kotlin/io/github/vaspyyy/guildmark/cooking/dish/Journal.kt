package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/**
 * What a cook learns (living-world/cook/design.md 3.4): the journal on the player, filled by the verdicts of the ladle and
 * the keepers and the batches they make. Part D's. It only ever records what really happened: a verdict that was given, a
 * batch that was served. It never holds the world's ideal, only what a cook has found out about it, and it is the player's
 * own (kept through death, and complete if the Book is lost).
 */
object Journal {
    /** The COOKING level from which a cook learns by how much a role is out, and not only which way (3.4). */
    const val AMOUNT_LEVEL = 25

    /** [player]'s journal. */
    fun of(player: ServerPlayer): CookJournal = player.getData(CookRegistry.COOK_JOURNAL)

    /**
     * [player] has learned something about [kind] from [verdict]: which way the role it names is out in the standard bill
     * (too much or too little), and, from COOKING [AMOUNT_LEVEL], by how many tenths of an item. A verdict that names no
     * difference teaches nothing. The newest verdict about a role is the one that is kept.
     */
    fun learn(player: ServerPlayer, kind: DishKind, verdict: Verdict) {
        if (!Cooking.enabled() || verdict.items <= 0.0) return
        val journal = of(player)
        val page = journal.page(kind)
        val role = verdict.role.ordinal
        page.direction[role] = if (verdict.over) 1 else -1
        if (Skills.level(player, Skill.COOKING) >= AMOUNT_LEVEL) page.amount[role] = (verdict.items * 10).roundToInt().coerceIn(1, 999)
        player.setData(CookRegistry.COOK_JOURNAL, journal)
    }

    /**
     * [player] has made a batch of [kind] at [tier] from [items]: one more made, and, if it is the best they have made (or
     * as good as it, the newest being the one kept), its mix: the foods that went in, counted and put in order of how
     * many, at most [CookJournal.MIX_MAX]. A batch that came out Spoilt is counted and never kept as the best.
     */
    fun batch(player: ServerPlayer, kind: DishKind, items: List<ItemStack>, tier: Tier) {
        if (!Cooking.enabled()) return
        val journal = of(player)
        val page = journal.page(kind)
        page.made++
        if (tier != Tier.SPOILT && tier.ordinal >= page.best) {
            val counts = LinkedHashMap<String, Int>()
            for (stack in items) {
                if (stack.isEmpty) continue
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count, Int::plus)
            }
            val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(CookJournal.MIX_MAX)
            page.best = tier.ordinal
            page.names = top.map { it.key }
            page.mix = top.map { it.value }.toIntArray()
        }
        player.setData(CookRegistry.COOK_JOURNAL, journal)
    }

    /**
     * Hands [player] their Cook's Book, once, the first time they use a station: into the pack, or at their feet if it is
     * full. It is a keepsake; the journal it reads is on the player, so a lost Book costs nothing (a new one is a recipe).
     */
    fun give(player: ServerPlayer) {
        if (!Cooking.enabled() || player.getData(CookRegistry.COOK_BOOK_GIVEN)) return
        player.setData(CookRegistry.COOK_BOOK_GIVEN, true)
        player.inventory.placeItemBackInInventory(ItemStack(CookRegistry.COOK_BOOK.get()), Prediction.SERVER_ONLY)
        player.sendOverlayMessage(Component.translatable("message.guildmark.cook.book_given"))
    }
}
