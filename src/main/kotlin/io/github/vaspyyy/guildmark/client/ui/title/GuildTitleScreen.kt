package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.TextAlignment
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.LogoRenderer
import net.minecraft.client.gui.components.SplashRenderer
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import org.joml.Matrix3x2f
import java.lang.reflect.Field
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Guildmark's title screen: the vanilla one, every button and all, over the painted dusk (see [Backdrop]),
 * with the Guildmark name in gold for the logo, a Guildmark splash beside it and the buttons dressed in
 * the kit's iron and gold (see [MenuSkins]). Because it stays a TitleScreen, everything that adds to or
 * looks for the title screen (the crest by the Mods button, other mods) carries on working.
 *
 * The first time it shows in a session, the scene rises out of black, the name comes up, and then the
 * buttons, as the vanilla screen fades its own in.
 */
class GuildTitleScreen private constructor(intro: Boolean, private val logo: TitleLogo) : TitleScreen(intro, logo) {
    constructor(intro: Boolean) : this(intro, TitleLogo())

    private var openedAt = if (intro) 0L else -1L
    /** How far the buttons have faded in: the vanilla screen's own fade sets it. */
    private var buttonsShown = if (intro) 0f else 1f
    private var mouseX = -1f
    private var mouseY = -1f

    override fun init() {
        // A Guildmark splash, chosen once per screen like the vanilla one
        val field = SPLASH
        if (field != null && field.get(this) == null) MenuTexts.splash()?.let { field.set(this, GuildSplash(it, logo)) }
        super.init()
    }

    private fun sinceOpened(): Float = if (openedAt < 0L) 99f else (Util.getMillis() - openedAt) / 1000f

    // The backdrop is drawn first thing in extractRenderState instead, so the buttons' skins can go between it and the rest
    override fun extractPanorama(graphics: GuiGraphicsExtractor, a: Float) {}

    override fun fadeWidgets(widgetFade: Float) {
        buttonsShown = widgetFade
        seeThrough()
    }

    /** The dressed buttons are drawn invisible underneath their skins; the rest fade as usual. */
    private fun seeThrough() {
        for (child in children()) {
            if (child is AbstractWidget) child.alpha = if (MenuSkins.dressed(child)) 0f else buttonsShown
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (openedAt == 0L) openedAt = Util.getMillis()
        this.mouseX = mouseX.toFloat()
        this.mouseY = mouseY.toFloat()
        logo.shown = ((sinceOpened() - 0.5f) / 1.2f).coerceIn(0f, 1f)
        seeThrough()
        Backdrop.draw(graphics, width, height, this.mouseX, this.mouseY, ((sinceOpened() - 0.1f) / 1.4f).coerceIn(0f, 1f))
        // The skins under everything the vanilla screen draws (the see-through buttons, the name, the splash, Realms' badges)
        for (child in children()) {
            if (child is AbstractWidget && child.visible && MenuSkins.dressed(child)) MenuSkins.draw(graphics, font, child, buttonsShown)
        }
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    companion object {
        private val SPLASH: Field? = try {
            TitleScreen::class.java.getDeclaredField("splash").also { it.isAccessible = true }
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Can't set the title screen's splash, keeping the vanilla ones", e)
            null
        }
    }
}

/**
 * The Guildmark name in carved gold where the Minecraft logo goes, with a slow warm glow behind it. As
 * large as the room above the buttons allows, up to [MAX_WIDTH] wide.
 */
class TitleLogo : LogoRenderer(false) {
    /** How far it has faded in. */
    var shown = 1f
    /** Where it was last drawn, for the splash. */
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f

    override fun extractRenderState(graphics: GuiGraphicsExtractor, width: Int, alpha: Float) = draw(graphics, width)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, width: Int, alpha: Float, heightOffset: Int) = draw(graphics, width)

    private fun draw(graphics: GuiGraphicsExtractor, width: Int) {
        val height = graphics.guiHeight()
        // The room between the top (clear of NeoForge's own notice) and the first button
        val room = height / 4 + 32 - TOP - 6f
        w = min(MAX_WIDTH, min(width - 24f, room * ASPECT)).coerceAtLeast(MIN_WIDTH)
        h = w / ASPECT
        x = (width - w) / 2f
        y = TOP + max(0f, room - h) * 0.4f
        paint(graphics, x, y, w, shown)
    }

    companion object {
        private val LOGO = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/title/logo.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/title/logo_glow.png")
        private const val TEX_W = 1280f
        private const val TEX_H = 272f
        const val ASPECT = TEX_W / TEX_H

        /** The name [w] wide with its top left at ([x], [y]), [alpha] faded in. */
        fun paint(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, alpha: Float) {
            if (alpha <= 0.01f) return
            val t = Util.getMillis() / 1000f
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x, y)
            pose.scale(w / TEX_W)
            // The glow breathes slowly, and rises with the name as it fades in
            val glow = (0.55f + 0.2f * sin(t * 1.1f)) * alpha
            val v = (glow * 255f).toInt().coerceIn(0, 255)
            graphics.blit(RenderPipelines.GUI_NAUSEA_OVERLAY, GLOW, 0, 0, 0f, 0f, TEX_W.toInt(), TEX_H.toInt(), 640, 136, 640, 136, ARGB.color(255, v, v, v))
            graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, 0, 0, 0f, 0f, TEX_W.toInt(), TEX_H.toInt(), 1280, 272, 1280, 272, ARGB.white(alpha))
            pose.popMatrix()
        }

        private const val TOP = 26f
        private const val MAX_WIDTH = 400f
        private const val MIN_WIDTH = 140f
    }
}

/** A splash line by the end of the Guildmark name, bobbing like the vanilla ones but in warm gold. */
class GuildSplash(text: String, private val logo: TitleLogo) : SplashRenderer(Component.literal(text)) {
    private val line: Component = Component.literal(text).withColor(COLOR)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, screenWidth: Int, font: Font, alpha: Float) {
        if (logo.w <= 0f) return
        val textWidth = font.width(line)
        val collector = graphics.textRenderer()
        val beat = 1.8f - abs(sin((Util.getMillis() % 1000L) / 1000f * 2f * PI.toFloat())) * 0.1f
        // Scaled to the logo, so it sits the same on a small screen as on a big one
        val scale = beat * 100f / (textWidth + 32) * (logo.w / 320f).coerceIn(0.75f, 1.25f)
        val transform = Matrix3x2f(collector.defaultParameters().pose())
            .translate(logo.x + logo.w * 0.9f, logo.y + logo.h * 0.8f)
            .rotate(-PI.toFloat() / 9f)
            .scale(scale)
        collector.accept(TextAlignment.LEFT, -textWidth / 2, -8, collector.defaultParameters().withOpacity(alpha * logo.shown).withPose(transform), line)
    }

    companion object {
        private const val COLOR = 0xFFE27A
    }
}
