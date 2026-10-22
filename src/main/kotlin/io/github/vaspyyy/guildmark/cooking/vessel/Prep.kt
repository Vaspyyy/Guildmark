package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookRegistry
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.Tags

/**
 * What the Prep Board and the batch's `care` make of an ingredient (living-world/cook/design.md 1.1, 2.5): only vegetables,
 * roots, fruit, meat and fish can be prepared; wheat, honey, eggs, milk, bones and herbs cannot, and do not count against `care`.
 */
object Prep {
    /** Whether the Knife can prepare [stack]: it is a vegetable, a fruit, a meat or a fish (raw or cooked). */
    fun preparable(stack: ItemStack): Boolean =
        stack.`is`(Tags.Items.FOODS_VEGETABLE) || stack.`is`(Tags.Items.FOODS_FRUIT) ||
            stack.`is`(Tags.Items.FOODS_RAW_MEAT) || stack.`is`(Tags.Items.FOODS_COOKED_MEAT) ||
            stack.`is`(Tags.Items.FOODS_RAW_FISH) || stack.`is`(Tags.Items.FOODS_COOKED_FISH)

    /** Whether [stack] has been through the board (`guildmark:prepared`). */
    fun prepared(stack: ItemStack): Boolean = stack.get(CookRegistry.PREPARED.get()) == true

    /**
     * The batch's `care` (2.5): the share of the preparable items among [items] that were Prepared; a batch with none
     * preparable has none to be careful with and uses [attention] instead.
     */
    fun care(items: List<ItemStack>, attention: Double): Double {
        var preparable = 0
        var done = 0
        for (stack in items) {
            if (!preparable(stack)) continue
            preparable++
            if (prepared(stack)) done++
        }
        return if (preparable == 0) attention else done.toDouble() / preparable
    }
}
