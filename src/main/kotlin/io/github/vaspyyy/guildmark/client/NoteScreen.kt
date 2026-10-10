package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.network.TakeNotePayload
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/**
 * A quest note drawn on the vanilla book page. Opened from a board it offers Take Contract;
 * opened from a contract item it shows progress and time left.
 */
class NoteScreen(
    private val note: QuestNote,
    private val boardPos: BlockPos?,
    private val contract: ContractState?,
) : Screen(note.title()) {
    private companion object {
        const val PAGE_SIZE = 192
        const val TEXT_X = 36
        const val TEXT_Y = 30
        const val TEXT_WIDTH = 114
        /** Room between the top text margin and the page's bottom edge. */
        const val TEXT_HEIGHT = 136
        val INK = 0xFF3B2A1A.toInt()
        val FADED_INK = 0xFF6B5A44.toInt()
    }

    private val left get() = (width - PAGE_SIZE) / 2
    private val top get() = 2

    override fun init() {
        val buttonY = top + PAGE_SIZE + 4
        if (boardPos != null) {
            addRenderableWidget(
                Button.builder(Component.translatable("gui.guildmark.take_contract")) {
                    ClientPacketDistributor.sendToServer(TakeNotePayload(boardPos))
                    onClose()
                }.bounds(width / 2 - 100, buttonY, 98, 20).build()
            )
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(width / 2 + 2, buttonY, 98, 20).build())
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(width / 2 - 100, buttonY, 200, 20).build())
        }
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.blit(RenderPipelines.GUI_TEXTURED, BookViewScreen.BOOK_LOCATION, left, top, 0.0f, 0.0f, PAGE_SIZE, PAGE_SIZE, 256, 256)
    }

    /** One block of text on the page: its color and the gap left below it. */
    private class Line(val text: Component, val color: Int, val gapAfter: Int)

    private fun pageLines(): List<Line> {
        val lines = mutableListOf(Line(note.title().copy().withStyle { it.withBold(true) }, INK, 6))
        note.storyLine()?.let { lines.add(Line(it.copy().withStyle { s -> s.withItalic(true) }, FADED_INK, 6)) }
        lines.add(Line(note.description(), INK, 8))
        lines.add(Line(note.rewardLine(), INK, 2))
        if (contract == null) {
            lines.add(Line(note.deadlineLine(), INK, 8))
        } else {
            val details = contractLines(contract)
            details.forEachIndexed { i, line -> lines.add(Line(line, INK, if (i == details.size - 1) 8 else 2)) }
        }
        lines.add(Line(note.posterLine(), FADED_INK, 0))
        return lines
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val lines = pageLines()
        // Long notes (escorts, contracts with details) shrink to fit the page instead of running off it
        val height = lines.sumOf { font.wordWrapHeight(it.text, TEXT_WIDTH) + it.gapAfter }
        val scale = (TEXT_HEIGHT.toFloat() / height).coerceAtMost(1.0f)
        val wrapWidth = (TEXT_WIDTH / scale).toInt()

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((left + TEXT_X).toFloat(), (top + TEXT_Y).toFloat())
        pose.scale(scale, scale)
        var y = 0
        for (line in lines) {
            y = graphics.textWithWordWrap(font, line.text, 0, y, wrapWidth, line.color, false) + line.gapAfter
        }
        pose.popMatrix()
    }

    private fun contractLines(state: ContractState): List<Component> {
        val gameTime = minecraft.level?.gameTime ?: 0L
        val lines = mutableListOf<Component>()
        lines.addAll(Contracts.contractDetails(note, state))
        if (note.type.showsProgress) {
            lines.add(Component.translatable("quest.guildmark.progress", state.progress, note.count))
        }
        lines.add(
            if (state.isExpired(gameTime)) Component.translatable("quest.guildmark.expired")
            else Component.translatable("quest.guildmark.due_in", state.daysLeft(gameTime))
        )
        lines.add(Component.translatable("quest.guildmark.turn_in_hint"))
        return lines
    }

    override fun isPauseScreen(): Boolean = false
}
