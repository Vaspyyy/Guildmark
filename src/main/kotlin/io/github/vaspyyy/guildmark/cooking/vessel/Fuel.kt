package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/** What burns under a pot (living-world/cook/design.md 1.3). Part B's; until it lands, nothing burns and nothing is free. */
object Fuel {
    /** How many ticks [stack] burns for: the furnace's own two lines, wrapped (stub: 0, not a fuel). */
    fun burnTicks(level: ServerLevel, stack: ItemStack): Int = 0

    /**
     * Whether the place at [pos] burns no fuel: the tavern's guest pot while the inn is open, else a homestead's kitchen
     * ([io.github.vaspyyy.guildmark.cooking.home.HomesteadKitchens.freeAt]), else a cookshop's
     * ([io.github.vaspyyy.guildmark.cooking.shop.CookShops.freeAt]) (stub: false).
     */
    fun free(level: ServerLevel, pos: BlockPos): Boolean = false
}
