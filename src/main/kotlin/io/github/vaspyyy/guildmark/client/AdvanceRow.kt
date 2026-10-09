package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.advance.Advance
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** One village project in the advances list: built, in progress (with a bar) or locked. */
class AdvanceRow(
    private val advance: Advance,
    private val state: State,
    private val points: Int,
) : AbstractWidget(0, 0, WIDTH, HEIGHT, advance.title) {
    enum class State { BUILT, CURRENT, LOCKED }

    companion object {
        const val WIDTH = 260
        const val HEIGHT = 34
        private const val BAR_HEIGHT = 7
        private val INK = 0xFF2B1D0E.toInt()
        private val SOFT_INK = 0xFF4A3622.toInt()
        private val LOCKED_INK = 0xFF7A6A55.toInt()

        private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/$name")
        private val CARD = sprite("perk")
        private val CARD_DONE = sprite("perk_maxed")
        private val CARD_LOCKED = sprite("perk_disabled")
        private val BAR_BACKGROUND = sprite("xp_bar_background")
        private val BAR_PROGRESS = sprite("xp_bar_progress")
    }

    init {
        setTooltip(Tooltip.create(
            Component.empty().append(advance.description).append("\n")
                .append(Component.translatable("gui.guildmark.advances.wants", advance.wants).withStyle(ChatFormatting.GOLD))
        ))
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val card = when (state) {
            State.BUILT -> CARD_DONE
            State.CURRENT -> CARD
            State.LOCKED -> CARD_LOCKED
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, card, x, y, width, height)
        graphics.item(advance.icon, x + 8, y + 9)

        val font = Minecraft.getInstance().font
        val nameColor = if (state == State.LOCKED) LOCKED_INK else INK
        graphics.text(font, advance.title.copy().withStyle(ChatFormatting.BOLD), x + 30, y + 6, nameColor, false)

        val status = when (state) {
            State.BUILT -> Component.translatable("gui.guildmark.advances.built")
            State.CURRENT -> Component.translatable("gui.guildmark.advances.progress", points, advance.cost)
            State.LOCKED -> Component.translatable("gui.guildmark.advances.locked")
        }
        graphics.text(font, status, x + width - 8 - font.width(status), y + 6, if (state == State.LOCKED) LOCKED_INK else SOFT_INK, false)

        if (state == State.CURRENT) {
            val barX = x + 30
            val barY = y + 19
            val barWidth = width - 38
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_BACKGROUND, barX, barY, barWidth, BAR_HEIGHT)
            val filled = barWidth * points / advance.cost
            if (filled > 0) {
                graphics.enableScissor(barX, barY, barX + filled, barY + BAR_HEIGHT)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_PROGRESS, barX, barY, barWidth, BAR_HEIGHT)
                graphics.disableScissor()
            }
        } else {
            graphics.text(font, advance.description, x + 30, y + 19, if (state == State.LOCKED) LOCKED_INK else SOFT_INK, false)
        }
    }

    override fun playDownSound(soundManager: net.minecraft.client.sounds.SoundManager) {}

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, advance.title)
    }
}
