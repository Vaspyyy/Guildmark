package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import java.util.function.UnaryOperator

/**
 * The cook's three tools and the prize ladle (living-world/cook/design.md 1.1, 6.8): the Cook's Ladle stirs, tastes and
 * serves a pot, the Cook's Knife chops on the Prep Board and carves and slices, the Turner flips on the Hob and serves from
 * the oven, and the Gilded Ladle (the Harvest Table's prize) does what the ladle does and never breaks. [id] is the item's.
 */
enum class CookTool(val id: String) {
    LADLE("cooks_ladle"),
    KNIFE("cooks_knife"),
    TURNER("turner"),
    GILDED_LADLE("gilded_ladle"),
}

/** A cook's tool in the hand. Part B's: until it lands, it can be held and nothing more. */
class CookToolItem(properties: Properties, val tool: CookTool) : Item(properties) {
    companion object {
        /**
         * How a tool is made (1.1): the ladle lasts 250 uses, the knife and the turner 200, and the Gilded Ladle has no
         * durability to lose. Part B's to tune, so the registry never needs to change.
         */
        fun properties(tool: CookTool): UnaryOperator<Item.Properties> = when (tool) {
            CookTool.LADLE -> UnaryOperator<Item.Properties> { it.durability(250) }
            CookTool.KNIFE -> UnaryOperator<Item.Properties> { it.durability(200) }
            CookTool.TURNER -> UnaryOperator<Item.Properties> { it.durability(200) }
            CookTool.GILDED_LADLE -> UnaryOperator<Item.Properties> { it.stacksTo(1).rarity(Rarity.RARE) }
        }
    }
}
