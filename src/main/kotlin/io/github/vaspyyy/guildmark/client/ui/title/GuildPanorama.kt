package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.Panorama

/**
 * The backdrop every menu draws while no world is open (behind the world list, the options, the
 * multiplayer screen and so on, blurred as usual): Guildmark's painted dusk instead of the spinning
 * panorama, or the vanilla one with Guildmark's menus switched off.
 */
class GuildPanorama : Panorama() {
    override fun extractRenderState(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        if (!TitleMenu.enabled()) {
            super.extractRenderState(graphics, width, height)
            return
        }
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val mouse = minecraft.mouseHandler
        val mouseX = if (window.screenWidth > 0) (mouse.xpos() / window.screenWidth * width).toFloat() else -1f
        val mouseY = if (window.screenHeight > 0) (mouse.ypos() / window.screenHeight * height).toFloat() else -1f
        Backdrop.draw(graphics, width, height, mouseX, mouseY)
    }

    companion object {
        private var tried = false

        /** Puts the painted backdrop in place of the game's panorama, once. */
        fun install() {
            if (tried) return
            // Not until the game renderer is there (the first screens open before it)
            val renderer: GameRenderer = Minecraft.getInstance().gameRenderer ?: return
            tried = true
            try {
                val field = GameRenderer::class.java.declaredFields.firstOrNull { it.type == Panorama::class.java }
                    ?: throw NoSuchFieldException("panorama")
                field.isAccessible = true
                val current = field.get(renderer)
                if (current is GuildPanorama) return
                if (current != null && current.javaClass != Panorama::class.java) {
                    Guildmark.LOGGER.info("Another mod has replaced the menu panorama ({}), leaving it be", current.javaClass.name)
                    return
                }
                field.set(renderer, GuildPanorama())
            } catch (e: Exception) {
                Guildmark.LOGGER.warn("Couldn't put Guildmark's backdrop behind the menus; only the title screen will show it", e)
            }
        }
    }
}
