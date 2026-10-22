package io.github.vaspyyy.guildmark.cooking.dish

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * The dish on a Plate, for show (living-world/cook/design.md 3.5). Part D's; the cookshop (6.4) and the Harvest Table
 * (6.8) call it. Until it lands no plate shows anything.
 */
object Plate {
    /** Lays [stack] on the Plate at [pos] for show only, with nothing to take; false when there is no plate or it did not fit (stub: false). */
    fun show(level: ServerLevel, pos: BlockPos, stack: ItemStack): Boolean = false
}
