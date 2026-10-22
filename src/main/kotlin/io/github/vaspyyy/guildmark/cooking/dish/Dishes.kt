package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * Dishes as things (living-world/cook/design.md 3.1, 3.2). [make] is what every part that serves a dish calls; the reading
 * of a stack and how it has kept are here too, and are worked out from the day the dish was cooked, never trusted from
 * what the stack last said (see [Freshness]).
 */
object Dishes {
    /** [count] portions of [kind] with [data]: the item of that kind, the component, the cosmetic tier its model reads and its food (fresh; a held stack brings itself up to date by its own tick). */
    fun make(kind: DishKind, data: DishData, count: Int): ItemStack {
        val stack = ItemStack(CookRegistry.DISHES.getValue(kind).get(), count)
        stack.set(CookRegistry.DISH.get(), data)
        stack.set(CookRegistry.DISH_TIER.get(), data.tier.ordinal)
        stack.set(DataComponents.FOOD, DishFood.food(kind, data.tier, Stage.FRESH))
        return stack
    }

    /** The kind [stack] is a portion of, or null when it is not a dish or is Gruel or Scorched mess. */
    fun kindOf(stack: ItemStack): DishKind? = (stack.item as? DishItem)?.kind

    /** What [stack] carries, or null when it is not a dish at all; a dish with no component reads as Plain, fresh and anonymous. */
    fun dataOf(stack: ItemStack): DishData? =
        if (stack.item is DishItem) stack.get(CookRegistry.DISH.get()) ?: DishData.PLAIN else null

    /**
     * How [stack] has kept in [level]'s world, recomputed from its day and never trusted (3.2). Fresh when it is not a
     * dish, when it is Scorched mess, and always while `dishSpoilage` is off.
     */
    fun stage(level: ServerLevel, stack: ItemStack): Stage = Freshness.stageOf(level, stack)

    /** How many days old [stack] is (3.2): 0 when it is not a dish, was cooked today, or was cooked "in the future" (9.3). */
    fun age(level: ServerLevel, stack: ItemStack): Long {
        val data = dataOf(stack) ?: return 0L
        return Freshness.age(Cooking.today(level), data)
    }

    /**
     * Part D's registration: its readout for `cook perf`, the hand-over of the Cook's Book at the first use of a station, the
     * Larder's menu, and the return of a dish to its plate when a meal at it is stopped.
     */
    fun register() {
        CookStats.readout("dishes") { "${Freshness.looks} stack looks, ${Freshness.writes} stage writes" }
        Journal.register()
        LarderMenus.register()
        PlateEating.register()
    }
}
