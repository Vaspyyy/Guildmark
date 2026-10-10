package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.guild.Recruiting
import io.github.vaspyyy.guildmark.network.FoundGuildPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.neoforged.neoforge.client.network.ClientPacketDistributor

/** The charter: name your guild and pick the colour of its banner. */
class FoundGuildScreen(private val entityId: Int) : Screen(Component.translatable("gui.guildmark.found")) {
    private companion object {
        const val PANEL_WIDTH = 220
        const val PANEL_HEIGHT = 124
        const val PADDING = 12
        val INK = 0xFF2B1D0E.toInt()
        val SOFT_INK = 0xFF4A3622.toInt()
        val PANEL: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ledger/panel")
    }

    private var panelX = 0
    private var panelY = 0
    private var color = DyeColor.RED
    private lateinit var nameBox: EditBox
    private lateinit var foundButton: Button

    override fun init() {
        panelX = (width - PANEL_WIDTH) / 2
        panelY = (height - PANEL_HEIGHT) / 2
        val left = panelX + PADDING
        val inner = PANEL_WIDTH - PADDING * 2

        nameBox = EditBox(font, left, panelY + 36, inner, 18, Component.translatable("gui.guildmark.found.name"))
        nameBox.setMaxLength(24)
        nameBox.setHint(Component.translatable("gui.guildmark.found.hint"))
        nameBox.setResponder { updateFoundButton() }
        addRenderableWidget(nameBox)
        setInitialFocus(nameBox)

        addRenderableWidget(Button.builder(colorLabel()) { button ->
            color = DyeColor.byId((color.id + 1) % DyeColor.entries.size)
            button.message = colorLabel()
        }.bounds(left, panelY + 60, inner, 20).build())

        foundButton = Button.builder(Component.translatable("gui.guildmark.found.confirm", Recruiting.FOUNDING_FEE)) {
            ClientPacketDistributor.sendToServer(FoundGuildPayload(entityId, nameBox.value.trim(), color.id))
            onClose()
        }.bounds(left, panelY + PANEL_HEIGHT - PADDING - 20, inner / 2 - 2, 20).build()
        addRenderableWidget(foundButton)
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(left + inner / 2 + 2, panelY + PANEL_HEIGHT - PADDING - 20, inner / 2 - 2, 20).build()
        )
        updateFoundButton()
    }

    private fun colorLabel(): Component = Component.translatable(
        "gui.guildmark.found.color",
        Component.translatable("color.minecraft.${color.serializedName}").withColor(color.textColor),
    )

    private fun updateFoundButton() {
        foundButton.active = nameBox.value.trim().length in 3..24
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD).withColor(INK), width / 2, panelY + 10, INK)
        graphics.centeredText(font, Component.translatable("gui.guildmark.found.sub").withColor(SOFT_INK), width / 2, panelY + 22, SOFT_INK)
    }

    override fun isPauseScreen(): Boolean = false
}
