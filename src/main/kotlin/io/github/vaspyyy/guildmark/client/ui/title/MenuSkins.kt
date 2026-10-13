package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.FriendsButton
import net.minecraft.client.gui.components.SpriteIconButton
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.neoforged.neoforge.client.gui.widget.ModsButton
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * Dresses the title screen's own buttons in the kit's iron and gold without swapping them out, so
 * whatever looks for them (the crest by the Mods button, other mods, narration, keyboard focus, tooltips)
 * still finds them as they were. The screen draws these skins, then the buttons see-through on top.
 * Only buttons whose look is known are dressed: plain text buttons and the vanilla and NeoForge icon
 * buttons. Anything a mod draws its own way keeps its look.
 */
internal object MenuSkins {
    private class Icon(val sprites: WidgetSprites, val width: Int, val height: Int, val dx: Int, val dy: Int)

    /** SpriteIconButton keeps its icon to itself; read once per button. */
    private val iconFields: List<Field>? = try {
        listOf("sprite", "spriteWidth", "spriteHeight", "spriteOffsetX", "spriteOffsetY").map { name ->
            SpriteIconButton::class.java.getDeclaredField(name).also { it.isAccessible = true }
        }
    } catch (e: Exception) {
        Guildmark.LOGGER.warn("Can't read the title screen's icon buttons, leaving them as they are", e)
        null
    }

    private val icons = WeakHashMap<AbstractWidget, Icon>()

    private val NOTIFICATIONS = listOf("1", "2", "3", "4", "5", "more").map { Identifier.withDefaultNamespace("notification/$it") }

    /** Whether [widget] gets a skin (and so is drawn see-through over it). */
    fun dressed(widget: AbstractWidget): Boolean = isText(widget) || icon(widget) != null

    private fun isText(widget: AbstractWidget): Boolean = widget.javaClass == Button.Plain::class.java

    private fun icon(widget: AbstractWidget): Icon? {
        if (widget !is SpriteIconButton) return null
        val known = widget.javaClass == SpriteIconButton.CenteredIcon::class.java ||
            widget.javaClass == ModsButton::class.java || widget.javaClass == FriendsButton::class.java
        if (!known) return null
        icons[widget]?.let { return it }
        val fields = iconFields ?: return null
        val icon = try {
            Icon(fields[0].get(widget) as WidgetSprites, fields[1].getInt(widget), fields[2].getInt(widget), fields[3].getInt(widget), fields[4].getInt(widget))
        } catch (e: ReflectiveOperationException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        icons[widget] = icon
        return icon
    }

    /** Draws [widget]'s skin, [alpha] faded. */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, widget: AbstractWidget, alpha: Float) {
        if (!widget.visible || alpha <= 0.01f) return
        val active = widget.isActive
        val lit = widget.isHoveredOrFocused
        val x = widget.x
        val y = widget.y
        val w = widget.width
        val h = widget.height
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(active, lit), x, y, w, h, ARGB.white(alpha))
        val icon = icon(widget)
        if (icon != null) {
            val ix = x + w / 2 - icon.width / 2 + icon.dx
            val iy = y + h / 2 - icon.height / 2 + icon.dy
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon.sprites.get(active, lit), ix, iy, icon.width, icon.height, ARGB.white(if (active) alpha else alpha * 0.5f))
            badge(graphics, widget, alpha)
            return
        }
        val color = ARGB.color(alpha, when {
            !active -> Ui.TEXT_FAINT
            lit -> Ui.GOLD_BRIGHT
            else -> Ui.TEXT
        })
        // The carved capitals when they fit, the usual letters (scrolling if need be) when they don't
        val carved = Ui.heading(widget.message)
        if (font.width(carved) <= w - 8) {
            graphics.text(font, carved, x + (w - font.width(carved)) / 2, y + (h - Ui.HEADING_HEIGHT) / 2 + Ui.HEADING_RISE, color, true)
        } else if (font.width(widget.message) <= w - 8) {
            graphics.text(font, widget.message, x + (w - font.width(widget.message)) / 2, y + (h - 8) / 2, color, true)
        } else {
            val text = graphics.textRenderer()
            val plain = widget.message.copy().withStyle { it.withColor(color and 0xFFFFFF) }
            text.acceptScrolling(plain, x + w / 2, x + 4, x + w - 4, y, y + h, text.defaultParameters().withOpacity(alpha))
        }
    }

    /** Friend requests waiting, over the corner of the friends button (it draws its own badge see-through with the rest of it). */
    private fun badge(graphics: GuiGraphicsExtractor, widget: AbstractWidget, alpha: Float) {
        val minecraft = Minecraft.getInstance()
        if (widget is FriendsButton && widget.isActive) {
            val social = minecraft.playerSocialManager
            val waiting = if (social.isFriendListEnabled) social.incomingRequests.size else 0
            if (waiting > 0) {
                val sprite = NOTIFICATIONS[(minOf(waiting, NOTIFICATIONS.size) - 1)]
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, widget.x + widget.width - 5, widget.y - 3, 8, 8, ARGB.white(alpha))
            }
        }
        // The Mods button draws its own update badge whatever its alpha, on top of this skin
    }
}
