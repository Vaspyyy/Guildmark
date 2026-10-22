package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.DishKind
import net.minecraft.world.item.Item
import java.util.function.UnaryOperator

/**
 * A portion of a dish (living-world/cook/design.md 3.1): one item for each of the twelve [DishKind]s, and the two that
 * belong to no kind, Gruel and Scorched mess ([kind] null). One item is one portion; its tier, day and cook are the
 * component `guildmark:dish`. Part D's (the food values, the eating, the freshness, the tooltip); until it lands a dish is
 * an item that can be held, given and stacked, and nothing more.
 */
class DishItem(properties: Properties, val kind: DishKind?) : Item(properties) {
    companion object {
        /** How a dish item is made (stack size, food, use...): Part D's to tune, so the registry never needs to change. Stacks of 8, rations 16. */
        fun properties(kind: DishKind?): UnaryOperator<Item.Properties> =
            UnaryOperator<Item.Properties> { it.stacksTo(if (kind == DishKind.LOAF || kind == DishKind.STRIPS) 16 else 8) }
    }
}
