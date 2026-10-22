package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.LevelReader
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

/**
 * A cook's tool in the hand (living-world/cook/design.md 1.1): what it does is the station's to say, when it is used on one
 * (the ladle stirs and serves, the knife carves and chops, the turner turns and lifts).
 */
class CookToolItem(properties: Properties, val tool: CookTool) : Item(properties) {
    /**
     * A cook sneaking with a tool still works the station it is used on: the ladle tastes with sneak (2.6) and the knife and the
     * turner have nothing else to be told apart by. Anything else a sneaking player uses a tool on is as it always was.
     */
    override fun doesSneakBypassUse(stack: ItemStack, level: LevelReader, pos: BlockPos, player: Player): Boolean =
        level.getBlockState(pos).block is StationBlock

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
