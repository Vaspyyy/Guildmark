package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore

/**
 * The Carrier's Warrant (living-world/trader/design.md, Part 5): a rolled sheet sealed with the hall's red wax and tied with a ribbon,
 * which a Chapman is given at the hall's counter. It is proof and a keepsake and gates nothing: what the ladder reads is the trader's book
 * ([TradeBooks.Book.warrant]), so a Warrant that is lost, burnt or given away costs nothing but the walk to the counter for another (free).
 * Its art is part X1's, under the same name.
 */
class WarrantItem(properties: Item.Properties) : Item(properties) {
    companion object {
        /** A Warrant made out for [owner], and sealed at the hall of [town] if that is known: the lore says so. */
        fun stack(owner: Component, town: String?): ItemStack {
            val stack = ItemStack(ModItems.CARRIERS_WARRANT.get())
            val sealed = if (town.isNullOrEmpty()) Component.translatable("item.guildmark.carriers_warrant.owner", owner)
            else Component.translatable("item.guildmark.carriers_warrant.sealed", owner, town)
            stack.set(DataComponents.LORE, ItemLore(listOf(sealed.withStyle(ChatFormatting.GRAY))))
            return stack
        }
    }
}
