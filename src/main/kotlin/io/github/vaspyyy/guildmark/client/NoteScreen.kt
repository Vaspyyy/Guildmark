package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.network.TakeNotePayload
import io.github.vaspyyy.guildmark.quest.QuestNote
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/** A quest note drawn on the vanilla book page. Shows a Take button when opened from a board. */
class NoteScreen(private val note: QuestNote, private val boardPos: BlockPos?) : Screen(note.title()) {
    private companion object {
        const val PAGE_SIZE = 192
        const val TEXT_X = 36
        const val TEXT_Y = 30
        const val TEXT_WIDTH = 114
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

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val x = left + TEXT_X
        var y = top + TEXT_Y
        y = graphics.textWithWordWrap(font, note.title().copy().withStyle { it.withBold(true) }, x, y, TEXT_WIDTH, INK, false) + 8
        y = graphics.textWithWordWrap(font, note.description(), x, y, TEXT_WIDTH, INK, false) + 12
        y = graphics.textWithWordWrap(font, note.rewardLine(), x, y, TEXT_WIDTH, INK, false) + 2
        y = graphics.textWithWordWrap(font, note.deadlineLine(), x, y, TEXT_WIDTH, INK, false) + 12
        graphics.textWithWordWrap(font, note.posterLine(), x, y, TEXT_WIDTH, FADED_INK, false)
    }

    override fun isPauseScreen(): Boolean = false
}
