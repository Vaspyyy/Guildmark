package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.AcceptChapterPayload
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.story.StoryCharacter
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/**
 * A conversation with a named character: what they have to say at this point in their story, the job
 * they're asking for and what it pays, and a button to take it on.
 */
class CharacterScreen(private val data: OpenCharacterPayload) : Screen(Component.translatable("gui.guildmark.character")) {
    private companion object {
        const val PANEL_WIDTH = 260
        const val PADDING = 12
        const val TEXT_TOP = 40
        val INK = 0xFF2B1D0E.toInt()
        val SOFT_INK = 0xFF4A3622.toInt()
        val FADED_INK = 0xFF6B5236.toInt()
        val PANEL: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/panel")
    }

    private val character = StoryCharacter.byId(data.character)
    private val finished get() = character == null || data.chapters >= character.steps.size

    private var panelX = 0
    private var panelY = 0
    private var panelHeight = 0

    /** What they say, in paragraphs. */
    private fun speech(): List<Component> {
        val c = character ?: return emptyList()
        return when {
            finished -> listOf(c.line("done"))
            data.carrying -> listOf(c.line("waiting"))
            data.chapters == 0 -> listOf(c.line("intro"), c.line("step0"))
            else -> listOf(c.line("step${data.chapters}"))
        }
    }

    /** The job on offer, if any. */
    private fun offer(): List<Component> {
        val c = character ?: return emptyList()
        if (finished || data.carrying) return emptyList()
        val note = c.note(data.chapters)
        return listOf(
            Component.translatable("gui.guildmark.character.chapter", data.chapters + 1, c.steps.size, note.title()).withStyle(ChatFormatting.BOLD),
            note.rewardLine(),
        )
    }

    private val textWidth get() = PANEL_WIDTH - PADDING * 2

    private fun blockHeight(lines: List<Component>, gap: Int): Int = lines.sumOf { font.wordWrapHeight(it, textWidth) + gap }

    override fun init() {
        panelHeight = TEXT_TOP + blockHeight(speech(), 6) + blockHeight(offer(), 2) + 8 + 20 + PADDING
        panelX = (width - PANEL_WIDTH) / 2
        panelY = (height - panelHeight) / 2
        val buttonY = panelY + panelHeight - PADDING - 20
        val half = textWidth / 2 - 2

        if (!finished && !data.carrying) {
            addRenderableWidget(Button.builder(Component.translatable("gui.guildmark.character.accept")) {
                ClientPacketDistributor.sendToServer(AcceptChapterPayload(data.entityId))
                onClose()
            }.bounds(panelX + PADDING, buttonY, half, 20).build())
            addRenderableWidget(Button.builder(Component.translatable("gui.guildmark.character.later")) { onClose() }
                .bounds(panelX + PANEL_WIDTH - PADDING - half, buttonY, half, 20).build())
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.guildmark.character.farewell")) { onClose() }
                .bounds(width / 2 - 50, buttonY, 100, 20).build())
        }
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, panelX, panelY, PANEL_WIDTH, panelHeight)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val c = character ?: return
        graphics.centeredText(font, c.title.copy().withStyle(ChatFormatting.BOLD), width / 2, panelY + 10, INK)
        graphics.centeredText(font, c.epithet.copy().withStyle(ChatFormatting.ITALIC).withColor(FADED_INK), width / 2, panelY + 22, FADED_INK)

        val left = panelX + PADDING
        var y = panelY + TEXT_TOP
        for (paragraph in speech()) {
            y = graphics.textWithWordWrap(font, Component.translatable("gui.guildmark.character.quote", paragraph), left, y, textWidth, SOFT_INK, false) + 6
        }
        for (line in offer()) {
            y = graphics.textWithWordWrap(font, line, left, y, textWidth, INK, false) + 2
        }
    }

    override fun isPauseScreen(): Boolean = false
}
