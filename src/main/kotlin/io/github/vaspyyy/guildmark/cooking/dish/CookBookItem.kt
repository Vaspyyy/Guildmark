package io.github.vaspyyy.guildmark.cooking.dish

import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import java.util.function.UnaryOperator

/**
 * The Cook's Book (living-world/cook/design.md 3.4): the player's record of what they have learned, given once at the
 * first use of a station. Its data is the player's [CookJournal], so losing the item loses nothing. Part D's (it opens a
 * screen on use); until it lands it is an item that can be held.
 */
class CookBookItem(properties: Properties) : Item(properties) {
    companion object {
        /** How the item is made (stack size, rarity...): Part D's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<Item.Properties> = UnaryOperator { it.stacksTo(1).rarity(Rarity.UNCOMMON) }
    }
}
