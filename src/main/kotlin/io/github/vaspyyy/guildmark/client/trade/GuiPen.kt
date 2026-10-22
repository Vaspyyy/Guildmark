package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/** The [Pen] of the game's own screens: what the Tally draws is put on the [graphics] of the frame, in the game's [font]. */
class GuiPen(private val graphics: GuiGraphicsExtractor, private val font: Font) : Pen {
    override fun width(text: String): Int = font.width(text)

    override fun text(text: String, x: Int, y: Int, color: Int, shade: Boolean, italic: Boolean) {
        if (italic) graphics.text(font, Component.literal(text).withStyle { it.withItalic(true) }, x, y, color, shade)
        else graphics.text(font, text, x, y, color, shade)
    }

    override fun headingWidth(text: String): Int = font.width(Ui.heading(Component.literal(text)))

    override fun heading(text: String, x: Int, y: Int, color: Int) {
        Ui.headingAt(graphics, font, Component.literal(text), x, y, color)
    }

    override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        graphics.fill(x0, y0, x1, y1, color)
    }

    override fun sprite(name: String, x: Int, y: Int, w: Int, h: Int, color: Int) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprites.getOrPut(name) { id(name) }, x, y, w, h, color)
    }

    override fun tex(tex: Tex, x: Int, y: Int, w: Int, h: Int, u: Int, v: Int, uw: Int, vh: Int, color: Int) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, textures.getOrPut(tex.path) { id(tex.path) }, x, y, u.toFloat(), v.toFloat(), w, h, uw, vh, tex.w, tex.h, color)
    }

    override fun good(id: String, x: Int, y: Int) {
        val stack = stacks.getOrPut(id) { Good.byId(id)?.item?.let { ItemStack(it) } ?: ItemStack.EMPTY }
        if (!stack.isEmpty) graphics.item(stack, x, y)
    }

    override fun save() {
        graphics.pose().pushMatrix()
    }

    override fun restore() {
        graphics.pose().popMatrix()
    }

    override fun translate(x: Float, y: Float) {
        graphics.pose().translate(x, y)
    }

    override fun scale(x: Float, y: Float) {
        graphics.pose().scale(x, y)
    }

    override fun rotate(radians: Float) {
        graphics.pose().rotate(radians)
    }

    override fun clip(x0: Int, y0: Int, x1: Int, y1: Int) {
        graphics.enableScissor(x0, y0, x1, y1)
    }

    override fun unclip() {
        graphics.disableScissor()
    }

    private companion object {
        private val sprites = HashMap<String, Identifier>()
        private val textures = HashMap<String, Identifier>()
        private val stacks = HashMap<String, ItemStack>()

        private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, path)
    }
}
