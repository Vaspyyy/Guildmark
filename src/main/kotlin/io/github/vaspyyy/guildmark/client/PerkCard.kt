package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.Perk
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/** One perk in the Guild Ledger: icon, name and rank pips. Click to spend a point. */
class PerkCard(
    private val perk: Perk,
    private val icon: ItemStack,
    private val rank: Int,
    private val canBuy: Boolean,
    private val onBuy: (Perk) -> Unit,
) : AbstractButton(0, 0, WIDTH, HEIGHT, perk.title) {
    companion object {
        const val WIDTH = 150
        const val HEIGHT = 30
        private const val PIP_SIZE = 7
        private val INK = 0xFF2B1D0E.toInt()
        private val GOLD_INK = 0xFF8A5A00.toInt()

        private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/$name")
        private val CARD = sprite("perk")
        private val CARD_HIGHLIGHTED = sprite("perk_highlighted")
        private val CARD_DISABLED = sprite("perk_disabled")
        private val CARD_MAXED = sprite("perk_maxed")
        private val PIP_FULL = sprite("pip_full")
        private val PIP_EMPTY = sprite("pip_empty")
    }

    private val maxed get() = rank >= perk.maxRank

    init {
        val status = when {
            maxed -> Component.translatable("gui.guildmark.ledger.perk_maxed").withStyle(ChatFormatting.GOLD)
            canBuy -> Component.translatable("gui.guildmark.ledger.perk_buy").withStyle(ChatFormatting.GREEN)
            else -> Component.translatable("gui.guildmark.ledger.perk_no_points").withStyle(ChatFormatting.GRAY)
        }
        setTooltip(Tooltip.create(Component.empty().append(perk.description).append("\n").append(status)))
    }

    override fun onPress(input: InputWithModifiers) {
        if (canBuy) onBuy(perk)
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val background = when {
            maxed -> CARD_MAXED
            !canBuy -> CARD_DISABLED
            isHoveredOrFocused -> CARD_HIGHLIGHTED
            else -> CARD
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, background, x, y, width, height)
        graphics.item(icon, x + 7, y + 7)

        val font = Minecraft.getInstance().font
        graphics.text(font, perk.title.copy().withStyle(ChatFormatting.BOLD), x + 28, y + 6, INK, false)
        for (i in 0 until perk.maxRank) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, if (i < rank) PIP_FULL else PIP_EMPTY, x + 28 + i * (PIP_SIZE + 2), y + 17, PIP_SIZE, PIP_SIZE)
        }
        if (canBuy) {
            graphics.text(font, "+", x + width - 12, y + 11, GOLD_INK, false)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }
}
