package io.github.vaspyyy.guildmark.lair

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.network.Filterable
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.WrittenBookContent

/**
 * Torn pages of the world's history, found in cleared lairs. Each is a written book whose text lives in
 * the language file (lore.guildmark.<n>.title / .page<k>), so it reads in the player's language.
 */
object LoreBooks {
    const val COUNT = 8
    private const val PAGES = 2

    fun random(random: RandomSource): ItemStack = fragment(random.nextInt(COUNT))

    fun fragment(index: Int): ItemStack {
        val stack = ItemStack(Items.WRITTEN_BOOK)
        val pages = (0 until PAGES).map { Filterable.passThrough<Component>(Component.translatable("lore.guildmark.$index.page$it")) }
        // Titles must be plain text, so the book is named by a custom name instead
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(Filterable.passThrough("Lore"), "?", 3, pages, true))
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("lore.guildmark.$index.title"))
        return stack
    }
}
