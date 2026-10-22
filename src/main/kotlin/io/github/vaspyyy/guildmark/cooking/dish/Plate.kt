package io.github.vaspyyy.guildmark.cooking.dish

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * The dish on a Plate, for show (living-world/cook/design.md 3.5). The cookshop (6.4) dresses its counter's plates with
 * its stock, and the Harvest Table (6.8) its entries; the dish is drawn on the plate and there is nothing to take, eat
 * or drop. A plate dressed this way stays the town's: a player cannot lay a dish on it, and an empty dish bares it
 * (the shop's plates are bare when the stock runs out). A plate with a player's dish on it is never overwritten.
 */
object Plate {
    /**
     * Lays one portion of [stack] on the Plate at [pos] for show only, with nothing to take; an empty [stack] bares it.
     * False when there is no plate there (or its chunk is not loaded), it holds a player's dish, or [stack] is not a
     * dish that can be laid (Scorched mess is not).
     */
    fun show(level: ServerLevel, pos: BlockPos, stack: ItemStack): Boolean {
        if (!level.isLoaded(pos)) return false
        val plate = level.getBlockEntity(pos) as? PlateBlockEntity ?: return false
        return plate.show(stack)
    }
}
