package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.village.VillageNames
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.advance.Advance
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.ClaimVillagePayload
import io.github.vaspyyy.guildmark.network.OpenAdvancesPayload
import net.minecraft.client.gui.components.Tooltip
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import io.github.vaspyyy.guildmark.village.StandingTier
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
class AdvanceScreen(private val info: OpenAdvancesPayload) : Screen(Component.translatable("gui.guildmark.advances.village", info.village, VillageNames.size(info.advanceIndex))) {
    private val anchor: BlockPos = info.anchor

    private companion object {
        const val PADDING = 12
        const val HEADER_HEIGHT = 64
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
        val rows = Advance.entries.mapIndexed { i, advance ->
            val state = when {
                i < index -> AdvanceRow.State.BUILT
                i == index -> AdvanceRow.State.CURRENT
                else -> AdvanceRow.State.LOCKED
            }
            AdvanceRow(advance, state, points)
        }.toMutableList()
        if (index >= Advance.entries.size) rows.add(AdvanceRow(Advance.TRADE_ROAD, AdvanceRow.State.CURRENT, points))
        // Two columns, so a grown town's list still fits the screen
        for (pair in rows.chunked(2)) {
            val line = LinearLayout.horizontal().spacing(4)
            for (row in pair) line.addChild(row)
            content.addChild(line) { it.alignHorizontallyCenter() }
        }
        claimButton()?.let { content.addChild(it) { s -> s.alignHorizontallyCenter().paddingTop(4) } }
        val buttons = LinearLayout.horizontal().spacing(4)
        if (info.coachStops > 0) {
            buttons.addChild(Button.builder(Component.translatable("gui.guildmark.coach", info.coachStops)) {
                minecraft.player?.connection?.sendCommand("coach")
                onClose()
            }.width(100).build())
        }
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(100).build())
        content.addChild(buttons) { it.alignHorizontallyCenter().paddingTop(4) }
        content.arrangeElements()

        panelWidth = content.width + PADDING * 2
        panelHeight = HEADER_HEIGHT + content.height + PADDING
        panelX = (width - panelWidth) / 2
        panelY = (height - panelHeight) / 2
        content.setPosition(panelX + PADDING, panelY + HEADER_HEIGHT)
        content.visitWidgets { addRenderableWidget(it) }
    }

    /** "Raise your banner" for guild leaders, greyed out with the reason when they can't yet. */
    private fun claimButton(): Button? {
        val status = TownControl.ClaimStatus.entries.getOrNull(info.claimStatus) ?: return null
        if (status == TownControl.ClaimStatus.NO_GUILD || status == TownControl.ClaimStatus.YOURS || status == TownControl.ClaimStatus.HELD) return null
        val label = if (info.rivalHeld) Component.translatable("gui.guildmark.challenge", info.holder) else Component.translatable("gui.guildmark.claim", info.claimFee)
        val button = Button.builder(label) {
            ClientPacketDistributor.sendToServer(ClaimVillagePayload(anchor))
            onClose()
        }.width(200).build()
        button.active = status == TownControl.ClaimStatus.OK || status == TownControl.ClaimStatus.RIVAL
        if (!button.active) button.setTooltip(Tooltip.create(Component.translatable("gui.guildmark.claim.${status.key}", info.claimFee, info.holdingCap)))
        return button
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
        val standing = standingLine()
        graphics.text(font, standing, width / 2 - font.width(standing) / 2, panelY + 36, INK, false)
        val holder = if (info.holder.isEmpty()) Component.translatable("gui.guildmark.holder.none", info.village).withColor(SOFT_INK)
        else Component.translatable("gui.guildmark.holder", Component.literal(info.holder).withColor(darken(info.holderColor)), info.loyalty).withColor(INK)
        graphics.text(font, holder, width / 2 - font.width(holder) / 2, panelY + 48, INK, false)
    }

    /** "Your standing: Trusted (82 / 200)" for whoever is looking. */
    private fun standingLine(): Component {
        val points = minecraft.player?.let { board()?.standingOf(it.uuid) } ?: 0
        val tier = StandingTier.of(points)
        val next = tier.next
        return if (next == null) Component.translatable("gui.guildmark.standing.max", tier.title, points)
        else Component.translatable("gui.guildmark.standing", tier.title, points, next.threshold)
    }

    /** Dye colours are bright; on parchment they read better a shade darker. */
    private fun darken(rgb: Int): Int {
        val r = (rgb shr 16 and 0xFF) * 3 / 4
        val g = (rgb shr 8 and 0xFF) * 3 / 4
        val b = (rgb and 0xFF) * 3 / 4
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun isPauseScreen(): Boolean = false
}
