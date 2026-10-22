package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.Vec3
import java.util.function.Consumer

/**
 * The Chapman's Cart as an item (living-world/trader/design.md, Part 3; build part D2): bought at the market hall's counter for 60 Marks (the
 * carter brings the cart itself round; this is what a cart is when it is taken apart, and what `give` hands out). Used on the ground it sets
 * a [io.github.vaspyyy.guildmark.cart.PlayerCart] down there, tipped forward onto its shafts with the shafts toward whoever set it down; the
 * stack's `dyed_color` is the pennant's colour (the pennant is a second layer of the item's picture that takes it), the owner's own if the
 * stack has none.
 *
 * With carrying switched off it does nothing at all.
 */
class ChapmansCartItem(properties: Properties) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        if (!TradeConfig.carry()) {
            if (player is ServerPlayer) player.sendOverlayMessage(Component.translatable("message.guildmark.trade.cart.off"))
            return InteractionResult.PASS
        }
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        // On the ground it was aimed at: the top of the block, or beside it where the side was clicked
        val at = if (context.clickedFace == Direction.UP) context.clickLocation else Vec3.atBottomCenterOf(context.clickedPos.relative(context.clickedFace))
        val colour = context.itemInHand.get(DataComponents.DYED_COLOR)?.rgb()
        val cart = CartHitch.setDown(level, player, at, player.position(), colour)
        if (cart == null) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.trade.cart.no_room"))
            return InteractionResult.FAIL
        }
        if (!player.isCreative) context.itemInHand.shrink(1)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.chapmans_cart.hint").withStyle { it.withColor(HINT) })
        builder.accept(Component.translatable("item.guildmark.chapmans_cart.hint2").withStyle { it.withColor(HINT) })
    }

    companion object {
        private const val HINT = 0x8A8A8A

        /** A Chapman's Cart item with the pennant in [colour] (the stack's `dyed_color`, whose own line in the tooltip is hidden). */
        fun stack(colour: Int): ItemStack {
            val stack = ItemStack(ModItems.CHAPMANS_CART.get())
            stack.set(DataComponents.DYED_COLOR, DyedItemColor(colour and 0xFFFFFF))
            stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.DYED_COLOR, true))
            return stack
        }
    }
}
