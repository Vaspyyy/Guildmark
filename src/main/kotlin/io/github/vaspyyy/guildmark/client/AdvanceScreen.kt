package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** Shows a village's advances: what's built, what contracts are funding now, and what's next. */
class AdvanceScreen(private val anchor: BlockPos) : Screen(Component.translatable("gui.guildmark.advances")) {
    private companion object {
        const val PADDING = 12
        const val HEADER_HEIGHT = 40
        val INK = 0xFF2B1D0E.toInt()
        val SOFT_INK = 0xFF4A3622.toInt()
        val PANEL: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/panel")
    }

    private var shown: Pair<Int, Int>? = null
    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private var panelHeight = 0

    private fun board(): QuestBoardBlockEntity? = minecraft.level?.getBlockEntity(anchor) as? QuestBoardBlockEntity
    private fun progress(): Pair<Int, Int> = board()?.let { Pair(it.advanceIndex, it.advancePoints) } ?: Pair(0, 0)

    override fun init() {
        val (index, points) = progress()
        shown = Pair(index, points)

        val content = LinearLayout.vertical().spacing(4)
        Advance.entries.forEachIndexed { i, advance ->
            val state = when {
                i < index -> AdvanceRow.State.BUILT
                i == index -> AdvanceRow.State.CURRENT
                else -> AdvanceRow.State.LOCKED
            }
            content.addChild(AdvanceRow(advance, state, points))
        }
        content.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(100).build()) { it.alignHorizontallyCenter().paddingTop(4) }
        content.arrangeElements()

        panelWidth = content.width + PADDING * 2
        panelHeight = HEADER_HEIGHT + content.height + PADDING
        panelX = (width - panelWidth) / 2
        panelY = (height - panelHeight) / 2
        content.setPosition(panelX + PADDING, panelY + HEADER_HEIGHT)
        content.visitWidgets { addRenderableWidget(it) }
    }

    /** Someone turned in a contract while this was open: refresh the bars. */
    override fun tick() {
        super.tick()
        if (board() == null) onClose() else if (progress() != shown) rebuildWidgets()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, panelX, panelY, panelWidth, panelHeight)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD).withColor(INK), width / 2, panelY + 10, INK)
        val hint = Component.translatable("gui.guildmark.advances.hint")
        graphics.text(font, hint, width / 2 - font.width(hint) / 2, panelY + 24, SOFT_INK, false)
    }

    override fun isPauseScreen(): Boolean = false
}
