package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.progression.Milestones
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.network.Filterable
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.WrittenBookContent

/**
 * Pip's songs about the adventurer. Every milestone worth singing about has a verse (in the language
 * file as character.guildmark.pip.verse.<milestone>, with the hero's name); Pip sings the grandest one
 * earned so far, and the Ballad he hands over at the end of his story has a page for each.
 */
object Ballads {
    /** Grandest first. */
    private val DEEDS = listOf(
        Milestones.HOLLOW_KING, Milestones.SEVEN_SHARDS, Milestones.TAKEOVER, Milestones.CITY, Milestones.LEGENDARY,
        Milestones.REPELLED, Milestones.SIEGE, Milestones.BANNER, Milestones.GUILD_FOUNDED, Milestones.FIRST_SHARD,
        Milestones.LAIR, Milestones.TRADE_ROAD, Milestones.CHAMPION, Milestones.ADVANCE_BUILT, Milestones.TALES_THREE, Milestones.PAID,
    )

    private fun deeds(player: Player): List<String> = DEEDS.filter { Milestones.done(player, it) }

    fun verse(player: Player): Component {
        val deed = deeds(player).firstOrNull() ?: return Component.translatable("character.guildmark.pip.verse.none", player.name)
        return Component.translatable("character.guildmark.pip.verse.$deed", player.name)
    }

    /** "The Ballad of <name>": a title page, then the deeds in the order they'd be sung, smallest first. */
    fun book(player: Player): ItemStack {
        val pages = listOf(Component.translatable("character.guildmark.pip.ballad.intro", player.name)) +
            deeds(player).reversed().map { Component.translatable("character.guildmark.pip.verse.$it", player.name) } +
            Component.translatable("character.guildmark.pip.ballad.outro", player.name)
        val stack = ItemStack(Items.WRITTEN_BOOK)
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(Filterable.passThrough("Ballad"), "Pip Larkspur", 0, pages.map { Filterable.passThrough(it) }, true))
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("character.guildmark.pip.gift", player.name))
        stack.set(DataComponents.LORE, ItemLore(listOf(Component.translatable("character.guildmark.pip.gift.lore"))))
        return stack
    }
}
