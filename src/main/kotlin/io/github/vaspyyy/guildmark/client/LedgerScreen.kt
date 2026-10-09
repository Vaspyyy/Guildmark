package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.network.SpendPerkPayload
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.progression.Perk
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/** The Guild Ledger: guild level, XP toward the next one, and the perk board. */
class LedgerScreen : Screen(Component.translatable("gui.guildmark.ledger")) {
    private companion object {
        const val PANEL_WIDTH = 324
        const val PANEL_HEIGHT = 240
        const val CARD_WIDTH = 154
        const val CARD_HEIGHT = 36
        const val COLUMNS = 2
        val PARCHMENT = 0xFFE9DCB8.toInt()
        val EDGE = 0xFF5A3D22.toInt()
        val CARD = 0xFFDCCB9E.toInt()
        val INK = 0xFF3B2A1A.toInt()
        val FADED_INK = 0xFF6B5A44.toInt()
        val GOLD = 0xFFB8860B.toInt()
        val BAR_BACK = 0xFF8B7355.toInt()
    }

    private var shown: GuildProgress? = null
    private val left get() = (width - PANEL_WIDTH) / 2
    private val top get() = (height - PANEL_HEIGHT) / 2

    private fun progress(): GuildProgress =
        minecraft.player?.getData(ModAttachments.GUILD_PROGRESS) ?: GuildProgress()

    override fun init() {
        val progress = progress()
        shown = progress
        Perk.entries.forEachIndexed { index, perk ->
            val (x, y) = cardPos(index)
            val rank = progress.rank(perk)
            val button = Button.builder(Component.literal("+")) {
                ClientPacketDistributor.sendToServer(SpendPerkPayload(perk.id))
            }.bounds(x + CARD_WIDTH - 22, y + 8, 18, 18).build()
            button.active = progress.points > 0 && rank < perk.maxRank
            addRenderableWidget(button)
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 - 50, top + PANEL_HEIGHT - 26, 100, 20).build()
        )
    }

    /** The server synced new progress (a perk bought, XP earned): redraw the buttons. */
    override fun tick() {
        super.tick()
        if (progress() != shown) rebuildWidgets()
    }

    private fun cardPos(index: Int): Pair<Int, Int> {
        val column = index % COLUMNS
        val row = index / COLUMNS
        return Pair(left + 6 + column * (CARD_WIDTH + 4), top + 52 + row * (CARD_HEIGHT + 4))
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.fill(left - 2, top - 2, left + PANEL_WIDTH + 2, top + PANEL_HEIGHT + 2, EDGE)
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PARCHMENT)
        Perk.entries.indices.forEach { index ->
            val (x, y) = cardPos(index)
            graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, CARD)
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val progress = progress()
        graphics.centeredText(font, title.copy().withStyle { it.withBold(true) }, width / 2, top + 8, INK)

        val level = Component.translatable("gui.guildmark.ledger.level", progress.level)
        graphics.text(font, level, left + 8, top + 22, INK, false)
        val points = Component.translatable("gui.guildmark.ledger.points", progress.points)
        graphics.text(font, points, left + PANEL_WIDTH - 8 - font.width(points), top + 22, if (progress.points > 0) GOLD else FADED_INK, false)

        // XP bar
        val barX = left + 8
        val barY = top + 36
        val barWidth = PANEL_WIDTH - 16
        graphics.fill(barX, barY, barX + barWidth, barY + 6, BAR_BACK)
        val filled = if (progress.isMaxLevel()) barWidth else barWidth * progress.xp / progress.xpToNext()
        graphics.fill(barX, barY, barX + filled, barY + 6, GOLD)
        val xpText = if (progress.isMaxLevel()) Component.translatable("gui.guildmark.ledger.max")
        else Component.translatable("gui.guildmark.ledger.xp", progress.xp, progress.xpToNext())
        graphics.text(font, xpText, width / 2 - font.width(xpText) / 2, top + 22, FADED_INK, false)

        Perk.entries.forEachIndexed { index, perk ->
            val (x, y) = cardPos(index)
            val rank = progress.rank(perk)
            graphics.text(font, perk.title.copy().withStyle { it.withBold(true) }, x + 4, y + 4, INK, false)
            val rankText = Component.literal("$rank/${perk.maxRank}")
            graphics.text(font, rankText, x + CARD_WIDTH - 26 - font.width(rankText), y + 4, if (rank == perk.maxRank) GOLD else FADED_INK, false)
            graphics.textWithWordWrap(font, perk.description, x + 4, y + 16, CARD_WIDTH - 30, FADED_INK, false)
        }
    }

    override fun isPauseScreen(): Boolean = false
}
