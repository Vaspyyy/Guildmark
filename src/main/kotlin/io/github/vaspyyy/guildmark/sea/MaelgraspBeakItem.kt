package io.github.vaspyyy.guildmark.sea

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** A slain Maelgrasp's beak: used on a cog, it's lashed to her stem (Cog.mountFigurehead), and Gnashfins keep clear of her. */
class MaelgraspBeakItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, adder: Consumer<Component>, flag: TooltipFlag) {
        adder.accept(Component.translatable("item.guildmark.maelgrasp_beak.tip").withStyle(ChatFormatting.DARK_GRAY))
    }
}
