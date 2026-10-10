package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.OpenReceptionPayload
import io.github.vaspyyy.guildmark.network.ReceptionActionPayload
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/**
 * The guild hall desk: your adventurer rank and what the next one needs, buttons for a rank trial and
 * today's hall contract, and the latest guild news.
 */
class ReceptionScreen(private val data: OpenReceptionPayload) : Screen(Component.translatable("gui.guildmark.hall")) {
    private companion object {
        const val PANEL_WIDTH = 260
        const val PADDING = 12
        const val NEWS_SHOWN = 5
        val INK = 0xFF2B1D0E.toInt()
        val SOFT_INK = 0xFF4A3622.toInt()
        val FADED_INK = 0xFF6B5236.toInt()
        val READY_INK = 0xFF2E6B1F.toInt()
        val PANEL: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/panel")
    }

    private var panelX = 0
    private var panelY = 0
    private var panelHeight = 0
    private var buttonsY = 0
    private var newsY = 0

    private val receptionistName: Component
        get() = minecraft.level?.getEntity(data.entityId)?.name ?: Component.empty()

    private fun progress() = minecraft.player!!.getData(ModAttachments.GUILD_PROGRESS)

    private fun carriesTrial(): Boolean {
        val inventory = minecraft.player?.inventory ?: return false
        return (0 until inventory.containerSize).any { inventory.getItem(it).get(ModDataComponents.QUEST_NOTE.get())?.trial == true }
    }

    private fun newsHeight(): Int {
        val width = PANEL_WIDTH - PADDING * 2
        return data.news.take(NEWS_SHOWN).sumOf { font.wordWrapHeight(newsLine(it), width) + 3 }.coerceAtLeast(12)
    }

    private fun newsLine(item: io.github.vaspyyy.guildmark.guild.NewsItem): Component {
        val ago = data.today - item.day
        val day = when {
            ago <= 0 -> Component.translatable("gui.guildmark.hall.today")
            ago == 1L -> Component.translatable("gui.guildmark.hall.yesterday")
            else -> Component.translatable("gui.guildmark.hall.days_ago", ago)
        }
        return Component.translatable("gui.guildmark.hall.news_line", day, item.text())
    }

    override fun init() {
        panelHeight = 30 + 34 + 26 + 14 + newsHeight() + 32
        panelX = (width - PANEL_WIDTH) / 2
        panelY = (height - panelHeight) / 2
        buttonsY = panelY + 30 + 34
        newsY = buttonsY + 26 + 14

        val progress = progress()
        val next = progress.rank.next
        val ready = progress.trialReady()
        val trialLabel = if (next == null) Component.translatable("gui.guildmark.hall.trial_none")
        else Component.translatable("gui.guildmark.hall.trial", next.letter)
        val trialButton = Button.builder(trialLabel) {
            ClientPacketDistributor.sendToServer(ReceptionActionPayload(data.entityId, ReceptionActionPayload.TRIAL))
            onClose()
        }.bounds(panelX + PADDING, buttonsY, 114, 20).build()
        trialButton.active = ready != null && !carriesTrial()
        trialButton.setTooltip(Tooltip.create(when {
            next == null -> Component.translatable("gui.guildmark.hall.trial_tip_max")
            carriesTrial() -> Component.translatable("gui.guildmark.hall.trial_tip_active")
            ready == null -> Component.translatable("gui.guildmark.hall.trial_tip_level", next.minLevel)
            else -> Component.translatable("gui.guildmark.hall.trial_tip", next.letter)
        }))
        addRenderableWidget(trialButton)

        val hallButton = Button.builder(Component.translatable("gui.guildmark.hall.contract")) {
            ClientPacketDistributor.sendToServer(ReceptionActionPayload(data.entityId, ReceptionActionPayload.HALL_CONTRACT))
            onClose()
        }.bounds(panelX + PANEL_WIDTH - PADDING - 114, buttonsY, 114, 20).build()
        hallButton.active = data.hallContractReady
        hallButton.setTooltip(Tooltip.create(Component.translatable(
            if (data.hallContractReady) "gui.guildmark.hall.contract_tip" else "gui.guildmark.hall.contract_tip_taken", progress.rank.letter
        )))
        addRenderableWidget(hallButton)

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 - 50, panelY + panelHeight - PADDING - 20, 100, 20).build()
        )
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, panelX, panelY, PANEL_WIDTH, panelHeight)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD).withColor(INK), width / 2, panelY + 10, INK)
        val name = receptionistName
        graphics.text(font, name, width / 2 - font.width(name) / 2, panelY + 20, FADED_INK, false)

        // Your rank: the badge, then what the next rank needs
        val progress = progress()
        val left = panelX + PADDING
        val rowY = panelY + 32
        RankBadge.draw(graphics, font, progress.rank, left, rowY)
        graphics.text(font, Component.translatable("gui.guildmark.hall.your_rank", progress.rank.letter, progress.level), left + RankBadge.WIDTH + 6, rowY + 3, INK, false)
        val next = progress.rank.next
        val (status, color) = when {
            next == null -> Pair(Component.translatable("gui.guildmark.hall.top_rank"), READY_INK)
            progress.trialReady() != null -> Pair(Component.translatable("gui.guildmark.hall.ready", next.letter), READY_INK)
            else -> Pair(Component.translatable("gui.guildmark.hall.next", next.letter, next.minLevel), SOFT_INK)
        }
        graphics.text(font, status, left + RankBadge.WIDTH + 6, rowY + 14, color, false)

        // The news
        graphics.text(font, Component.translatable("gui.guildmark.hall.news").withStyle(ChatFormatting.BOLD), left, newsY - 12, INK, false)
        if (data.news.isEmpty()) {
            graphics.text(font, Component.translatable("gui.guildmark.hall.no_news"), left, newsY, FADED_INK, false)
        }
        var y = newsY
        for (item in data.news.take(NEWS_SHOWN)) {
            y = graphics.textWithWordWrap(font, newsLine(item), left, y, PANEL_WIDTH - PADDING * 2, SOFT_INK, false) + 3
        }
    }

    override fun isPauseScreen(): Boolean = false
}
