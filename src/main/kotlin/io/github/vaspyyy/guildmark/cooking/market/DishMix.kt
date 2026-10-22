package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import kotlin.math.roundToInt

/**
 * The mix a batch was cooked with, kept on its stack so a keeper can taste it (living-world/cook/design.md 2.6, 7.7).
 *
 * `guildmark:dish` records a dish's tier, day and cook, and a keeper's verdict is worked from the batch's mix against the
 * hidden ideal (`Hints.verdict`), which the component does not carry. So the pot that serves a batch writes its mix here,
 * the seven roles' shares in thousandths in a small int array of the stack's custom data (`guildmark_mix`), and the back
 * door reads it. A stack with none (a dish given by command, cooked before this existed) is tasted for its tier only: the
 * keeper gives no verdict, because there is nothing to taste. Batches of different mixes do not stack, which is true to
 * them being different batches.
 */
object DishMix {
    private const val KEY = "guildmark_mix"
    private const val UNIT = 1000.0

    /** The shares of the seven roles (in [Role] order, summing to about 1) that [stack] was cooked with, or null if it carries none. */
    fun of(stack: ItemStack): DoubleArray? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val ints = data.copyTag().getIntArray(KEY).orElse(null) ?: return null
        if (ints.size != Role.entries.size) return null
        val share = DoubleArray(ints.size) { ints[it] / UNIT }
        return if (share.sum() > 0.0) share else null
    }

    /** Writes [share] (the seven roles' shares, in [Role] order) on [stack] as its mix. */
    fun stamp(stack: ItemStack, share: DoubleArray) {
        val ints = IntArray(Role.entries.size) { (share.getOrElse(it) { 0.0 } * UNIT).roundToInt().coerceIn(0, UNIT.toInt()) }
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { tag -> tag.putIntArray(KEY, ints) }
    }

    /** Takes the mix off [stack], so it is tasted for its tier alone. */
    fun clear(stack: ItemStack) {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return
        if (!data.contains(KEY)) return
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { tag -> tag.remove(KEY) }
    }
}
