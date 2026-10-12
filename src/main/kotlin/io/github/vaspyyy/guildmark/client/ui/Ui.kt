package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.sounds.SoundManager
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import java.util.Locale

/**
 * The look every new screen shares: dark iron panels with a tarnished-gold line (the combat HUD's
 * palette), gold headings in carved capitals, and the menu sounds. Sprites come from
 * art/scripts/ui_art.py, the heading font from art/scripts/heading_font.py.
 */
object Ui {
    private fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    val PANEL = sprite("ui/panel")
    val PANEL_PLAIN = sprite("ui/panel_plain")
    val INSET = sprite("ui/inset")
    val SLOT = sprite("ui/slot")
    val NAME_PLATE = sprite("ui/name_plate")
    val CHOICE_GLOW = sprite("ui/choice_glow")
    val DIVIDER = sprite("ui/divider")
    val DIAMOND = sprite("ui/diamond")
    val DIAMOND_LARGE = sprite("ui/diamond_large")
    val BUTTON = WidgetSprites(sprite("ui/button"), sprite("ui/button_disabled"), sprite("ui/button_highlighted"))

    // Text on the dark panels
    val TEXT = 0xFFE8DEC6.toInt()
    val TEXT_SOFT = 0xFFB4A88E.toInt()
    val TEXT_FAINT = 0xFF7D7466.toInt()
    val GOLD = 0xFFD9B46A.toInt()
    val GOLD_BRIGHT = 0xFFF2D58C.toInt()
    val RED = 0xFFD0573F.toInt()
    val GREEN = 0xFF8FCB7A.toInt()
    val BLUE = 0xFF86A9E6.toInt()

    private val HEADING_FONT = FontDescription.Resource(sprite("heading"))

    /** [text] in the carved heading capitals (anything they don't cover falls back to the usual font). */
    fun heading(text: Component): Component =
        Component.literal(text.string.uppercase(Locale.ROOT)).withStyle { it.withFont(HEADING_FONT) }

    fun heading(key: String, vararg args: Any): Component = heading(Component.translatable(key, *args))

    /** How far above its y a heading's capitals reach: they stand taller than ordinary text. */
    const val HEADING_RISE = 2
    const val HEADING_HEIGHT = 9

    fun panel(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, width, height)
    }

    fun inset(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INSET, x, y, width, height)
    }

    /** A gold rule across [width], fading at both ends, with a small diamond at its middle. */
    fun divider(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, gem: Boolean = true) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, DIVIDER, x, y, width, 3)
        if (gem) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, DIAMOND, x + width / 2 - 2, y - 1, 5, 5)
    }

    /** A heading centred on [centerX], its capitals' top at [top]. */
    fun centeredHeading(graphics: GuiGraphicsExtractor, font: Font, text: Component, centerX: Int, top: Int, color: Int = GOLD) {
        val shown = heading(text)
        graphics.text(font, shown, centerX - font.width(shown) / 2, top + HEADING_RISE, color, true)
    }

    fun headingAt(graphics: GuiGraphicsExtractor, font: Font, text: Component, x: Int, top: Int, color: Int = GOLD) {
        graphics.text(font, heading(text), x, top + HEADING_RISE, color, true)
    }

    // ---- Sounds ----

    fun play(sound: SoundEvent, pitch: Float = 1f, volume: Float = 0.6f) {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
    }

    fun open() = play(ModSounds.UI_OPEN.get(), 1f, 0.5f)
    fun close() = play(ModSounds.UI_CLOSE.get(), 1f, 0.45f)
    fun hover() = play(ModSounds.UI_HOVER.get(), 0.95f + Math.random().toFloat() * 0.1f, 0.25f)
    fun select() = play(ModSounds.UI_SELECT.get(), 1f, 0.45f)
    fun page() = play(ModSounds.UI_PAGE.get(), 1f, 0.5f)

    /** A button in the kit's look: dark iron with a gold line that brightens under the cursor. */
    class KitButton(x: Int, y: Int, width: Int, height: Int, message: Component, onPress: OnPress) :
        Button(x, y, width, height, message, onPress, CreateNarration { it.get() }) {

        override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
            val lit = isHoveredOrFocused
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON.get(active, lit), x, y, width, height)
            val font = Minecraft.getInstance().font
            val color = when {
                !active -> TEXT_FAINT
                lit -> GOLD_BRIGHT
                else -> TEXT
            }
            graphics.centeredText(font, message, x + width / 2, y + (height - 8) / 2, color)
        }

        override fun playDownSound(manager: SoundManager) {
            manager.play(SimpleSoundInstance.forUI(ModSounds.UI_SELECT.get(), 1f, 0.35f))
        }
    }

    fun button(message: Component, width: Int = 120, height: Int = 20, onPress: (Button) -> Unit): KitButton =
        KitButton(0, 0, width, height, message) { onPress(it) }
}
