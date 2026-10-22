package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * Dishes as things (living-world/cook/design.md 3.1, 3.2). [make] is real from the first commit, because every part
 * that serves a dish makes one this way; the reading of a stack and its freshness are Part D's, and until they land a
 * dish is always fresh.
 */
object Dishes {
    /** [count] portions of [kind] with [data]: the item of that kind, the component, and the cosmetic tier its model reads. */
    fun make(kind: DishKind, data: DishData, count: Int): ItemStack {
        val stack = ItemStack(CookRegistry.DISHES.getValue(kind).get(), count)
        stack.set(CookRegistry.DISH.get(), data)
        stack.set(CookRegistry.DISH_TIER.get(), data.tier.ordinal)
        return stack
    }

    /** The kind [stack] is a portion of, or null when it is not a dish or is Gruel or Scorched mess. */
    fun kindOf(stack: ItemStack): DishKind? = (stack.item as? DishItem)?.kind

    /** What [stack] carries, or null when it is not a dish at all; a dish with no component reads as Plain, fresh and anonymous. */
    fun dataOf(stack: ItemStack): DishData? =
        if (stack.item is DishItem) stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN else null

    /** How [stack] has kept in [level]'s world, recomputed from its day and never trusted (3.2; stub: always fresh). */
    fun stage(level: ServerLevel, stack: ItemStack): Stage = Stage.FRESH

    /** How many days old [stack] is (3.2; stub: 0). */
    fun age(level: ServerLevel, stack: ItemStack): Long = 0L

    /** Part D's registration: the dish commands and the Larder's ageing (stub: nothing). */
    fun register() {}
}
