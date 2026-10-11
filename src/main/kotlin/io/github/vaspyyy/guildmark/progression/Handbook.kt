package io.github.vaspyyy.guildmark.progression

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.network.Filterable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.WrittenBookContent

/** The Adventurer's Handbook: a short written guide to everything Guildmark adds. Also craftable (book + Guild Mark). */
object Handbook {
    const val PAGES = 12

    fun create(): ItemStack {
        val stack = ItemStack(Items.WRITTEN_BOOK)
        val pages = (0 until PAGES).map { Filterable.passThrough<Component>(Component.translatable("handbook.guildmark.page$it")) }
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(Filterable.passThrough("Adventurer's Handbook"), "The Guild", 0, pages, true))
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.guildmark.handbook").withStyle { it.withColor(0xE8C26A).withItalic(false) })
        return stack
    }
}
