package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.SpendPerkPayload
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.progression.Perk
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.layouts.GridLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/** The Guild Ledger: guild level, XP toward the next one, and the perk cards. */
class LedgerScreen : Screen(Component.translatable("gui.guildmark.ledger")) {
    private companion object {
        const val PADDING = 12
        const val HEADER_HEIGHT = 52
        const val BAR_HEIGHT = 7
        val INK = 0xFF2B1D0E.toInt()
        val SOFT_INK = 0xFF4A3622.toInt()
        val GOLD_INK = 0xFF8A5A00.toInt()

        private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/$name")
        val PANEL = sprite("panel")
        val BAR_BACKGROUND = sprite("xp_bar_background")
        val BAR_PROGRESS = sprite("xp_bar_progress")
    }

    private var shown: GuildProgress? = null
    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private var panelHeight = 0

    private fun progress(): GuildProgress =
        minecraft.player?.getData(ModAttachments.GUILD_PROGRESS) ?: GuildProgress()

    private fun icon(perk: Perk): ItemStack = ItemStack(
        when (perk) {
            Perk.VITALITY -> Items.GOLDEN_APPLE
            Perk.MIGHT -> Items.IRON_SWORD
            Perk.SWIFTNESS -> Items.SUGAR
            Perk.MINER -> Items.IRON_PICKAXE
            Perk.REACH -> Items.TRIDENT
            Perk.FORTUNE -> Items.RABBIT_FOOT
            Perk.HAGGLER -> ModItems.GUILD_MARK.get()
            Perk.PATHFINDER -> Items.COMPASS
        }
    )

    override fun init() {
        val progress = progress()
        shown = progress

        val content = LinearLayout.vertical().spacing(8)
        val grid = GridLayout().spacing(4)
        val rows = grid.createRowHelper(2)
        for (perk in Perk.entries) {
            val canBuy = progress.points > 0 && progress.rank(perk) < perk.maxRank
            rows.addChild(PerkCard(perk, icon(perk), progress.rank(perk), canBuy) {
                ClientPacketDistributor.sendToServer(SpendPerkPayload(it.id))
            })
        }
        content.addChild(grid)
        content.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(100).build()) { it.alignHorizontallyCenter() }
        content.arrangeElements()

        panelWidth = content.width + PADDING * 2
        panelHeight = HEADER_HEIGHT + content.height + PADDING
        panelX = (width - panelWidth) / 2
        panelY = (height - panelHeight) / 2
        content.setPosition(panelX + PADDING, panelY + HEADER_HEIGHT)
        content.visitWidgets { addRenderableWidget(it) }
    }

    /** The server synced new progress (a perk bought, XP earned): rebuild the cards. */
    override fun tick() {
        super.tick()
        if (progress() != shown) rebuildWidgets()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, panelX, panelY, panelWidth, panelHeight)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val progress = progress()
        val left = panelX + PADDING
        val right = panelX + panelWidth - PADDING

        graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD).withColor(INK), width / 2, panelY + 10, INK)

        val rowY = panelY + 24
        graphics.text(font, Component.translatable("gui.guildmark.ledger.level", progress.level), left, rowY, INK, false)
        val xpText = if (progress.isMaxLevel()) Component.translatable("gui.guildmark.ledger.max")
        else Component.translatable("gui.guildmark.ledger.xp", progress.xp, progress.xpToNext())
        graphics.text(font, xpText, width / 2 - font.width(xpText) / 2, rowY, SOFT_INK, false)

        // Perk points with a Guild Mark coin next to the count
        val pointsText = Component.translatable("gui.guildmark.ledger.points", progress.points)
        val pointsX = right - font.width(pointsText)
        graphics.text(font, pointsText, pointsX, rowY, if (progress.points > 0) GOLD_INK else SOFT_INK, false)
        graphics.item(ItemStack(ModItems.GUILD_MARK.get()), pointsX - 18, rowY - 4)

        val barY = panelY + 38
        val barWidth = right - left
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_BACKGROUND, left, barY, barWidth, BAR_HEIGHT)
        val filled = if (progress.isMaxLevel()) barWidth else barWidth * progress.xp / progress.xpToNext()
        if (filled > 0) {
            graphics.enableScissor(left, barY, left + filled, barY + BAR_HEIGHT)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_PROGRESS, left, barY, barWidth, BAR_HEIGHT)
            graphics.disableScissor()
        }
    }

    override fun isPauseScreen(): Boolean = false
}
