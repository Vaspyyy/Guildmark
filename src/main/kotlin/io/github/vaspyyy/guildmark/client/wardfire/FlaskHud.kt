package io.github.vaspyyy.guildmark.client.wardfire

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.gui.GuiLayer

/**
 * The flask's quick slot, low on the left beside the hotbar: the Warden's Flask, as full as your
 * draughts, with how many are left. It shows once the flask is yours.
 */
object FlaskHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "flask_slot")
    private val SLOT = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/flask_slot")
    private val flask: ItemStack by lazy { ItemStack(ModItems.WARDENS_FLASK.get()) }

    private const val SIZE = 24
    private const val MARGIN = 6
    private const val EMPTY_COLOR = 0xFFE05A4A.toInt()

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.gui.hud.isHidden || player.isSpectator) return
        val state = player.getExistingDataOrNull(ModAttachments.FLASK) ?: return
        if (!state.owned) return
        val x = MARGIN
        val y = graphics.guiHeight() - SIZE - 1
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x, y, SIZE, SIZE)
        graphics.item(player, flask, x + 4, y + 4, 0)
        // The cooldown between quick draughts, when the new combat is off
        graphics.itemDecorations(minecraft.font, flask, x + 4, y + 4)
        val count = state.charges.toString()
        val font = minecraft.font
        graphics.text(font, count, x + SIZE - 3 - font.width(count), y + SIZE - 11, if (state.charges > 0) -1 else EMPTY_COLOR, true)
    }
}
