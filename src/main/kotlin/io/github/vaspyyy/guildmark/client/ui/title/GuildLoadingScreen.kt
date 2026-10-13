package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen.Reason
import net.minecraft.client.multiplayer.LevelLoadTracker
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.progress.ChunkLoadStatusView
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.minecraft.world.level.chunk.status.ChunkStatus
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The wait while a world loads, in Guildmark's colours: the painted dusk behind, dimmed, the name of the
 * place you're entering in carved gold, a gold bar for how far along it is, the land surveyed in ink on
 * parchment as it's raised, and a tip along the bottom. Only the plain wait is dressed: going through a
 * portal keeps its swirl.
 */
class GuildLoadingScreen(tracker: LevelLoadTracker, reason: Reason, private val arriving: Boolean) : LevelLoadingScreen(tracker, reason) {
    private var tracker = tracker
    private var reason = reason
    private val tip = TipLine()
    private var shownProgress = 0f
    private var lastFrame = -1L
    private var lastNarration = -1L

    override fun update(loadTracker: LevelLoadTracker, reason: Reason) {
        super.update(loadTracker, reason)
        tracker = loadTracker
        this.reason = reason
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (reason == Reason.OTHER) LoadingLook.background(graphics, width, height, mouseX, mouseY)
        else super.extractBackground(graphics, mouseX, mouseY, a)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val now = Util.getMillis()
        if (now - lastNarration > 2000L) {
            lastNarration = now
            triggerImmediateNarration(true)
        }
        // Eased by the frame, not the tick: nothing ticks this screen while a world on this computer starts up
        val dt = if (lastFrame < 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.25f)
        lastFrame = now
        if (tracker.hasProgress()) shownProgress += (tracker.serverProgress() - shownProgress) * (1f - exp(-dt * 5f))

        val centerX = width / 2
        val view = tracker.statusView()
        val cell = if (view == null) 0 else (MAP_SIZE / (view.radius() * 2 + 1)).coerceIn(2, 5)
        val mapSize = if (view == null) 0 else (view.radius() * 2 + 1) * cell
        // The heading, the bar under it, then the map, the whole block centred a little above the middle
        val block = 30 + if (view == null) 0 else mapSize + 22
        // The name at the top as you arrive, if there's room for it
        val logo = arriving && height >= 250
        val top = max(if (logo) LoadingLook.logoBottom(width) + 10 else 10, (height - block) / 2 - 6)
        if (logo) LoadingLook.logo(graphics, width)
        LoadingLook.heading(graphics, font, title(), centerX, top, width)
        if (tracker.hasProgress()) LoadingLook.bar(graphics, centerX, top + 17, shownProgress)
        else LoadingLook.dots(graphics, centerX, top + 17)
        if (view != null) drawMap(graphics, view, centerX - mapSize / 2, top + 40, cell)
        tip.draw(graphics, font, width, height)
    }

    /** Where you're going: the world or server by name as you arrive, the usual words on a respawn. */
    private fun title(): Component {
        if (!arriving) return Component.translatable("multiplayer.downloadingTerrain")
        val minecraft = Minecraft.getInstance()
        val name = minecraft.singleplayerServer?.worldData?.levelName ?: minecraft.currentServer?.name
        return if (name.isNullOrBlank()) Component.translatable("gui.guildmark.loading.world") else Component.translatable("gui.guildmark.loading.entering", name)
    }

    /** The land around the spawn, inked chunk by chunk as it's raised, framed as the world map is. */
    private fun drawMap(graphics: GuiGraphicsExtractor, view: ChunkLoadStatusView, left: Int, top: Int, cell: Int) {
        val diameter = view.radius() * 2 + 1
        val size = diameter * cell
        Ui.panel(graphics, left - 6, top - 6, size + 12, size + 12)
        graphics.blit(RenderPipelines.GUI_TEXTURED, PARCHMENT, left, top, 0f, 0f, size, size, 256, 256)
        val gap = if (cell >= 4) 1 else 0
        val full = ChunkStatus.FULL.index + 1f
        for (x in 0 until diameter) {
            for (z in 0 until diameter) {
                val status = view.get(x, z) ?: continue
                val ink = ARGB.color(0.14f + 0.72f * (status.index + 1f) / full, INK)
                val cx = left + x * cell
                val cy = top + z * cell
                graphics.fill(cx, cy, cx + cell - gap, cy + cell - gap, ink)
            }
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MAP_FRAME, left, top, size, size)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, YOU, left + size / 2 - 5, top + size / 2 - 5, 11, 11)
    }

    companion object {
        private val PARCHMENT = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/map/parchment.png")
        private val MAP_FRAME = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "map/frame")
        private val YOU = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "map/player")
        private val INK = 0xFF3A2614.toInt()
        /** About how wide the map is, in GUI pixels. */
        private const val MAP_SIZE = 76
    }
}

/**
 * GenericMessageScreen in Guildmark's colours: the waits with a line of text (preparing a new world,
 * reading one, saving on the way out), shown as the loading screen is.
 */
class GuildMessageScreen(title: Component) : GenericMessageScreen(title) {
    private val tip = TipLine()

    // The line is drawn below in carved gold, in place of the vanilla box of text
    override fun init() {}

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        LoadingLook.background(graphics, width, height, mouseX, mouseY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val logo = height >= 200
        if (logo) LoadingLook.logo(graphics, width)
        val top = max(if (logo) LoadingLook.logoBottom(width) + 10 else 10, height / 2 - 18)
        LoadingLook.heading(graphics, font, title, width / 2, top, width)
        LoadingLook.dots(graphics, width / 2, top + 17)
        tip.draw(graphics, font, width, height)
    }
}

/** What the loading screens share. */
internal object LoadingLook {
    /** How much of the painting shows through behind the words. */
    private const val SHOWN = 0.5f
    private const val LOGO_TOP = 14f
    private const val LOGO_MAX = 190f

    fun background(graphics: GuiGraphicsExtractor, width: Int, height: Int, mouseX: Int, mouseY: Int) {
        Backdrop.draw(graphics, width, height, mouseX.toFloat(), mouseY.toFloat(), SHOWN)
    }

    private fun logoWidth(width: Int): Float = min(LOGO_MAX, width * 0.4f)

    fun logoBottom(width: Int): Int = (LOGO_TOP + logoWidth(width) / TitleLogo.ASPECT).toInt()

    /** The Guildmark name, small, at the top. */
    fun logo(graphics: GuiGraphicsExtractor, width: Int) {
        val w = logoWidth(width)
        TitleLogo.paint(graphics, (width - w) / 2f, LOGO_TOP, w, 1f)
    }

    /** [text] in carved gold, its capitals' top at [top]; in the usual letters if the capitals won't fit. */
    fun heading(graphics: GuiGraphicsExtractor, font: Font, text: Component, centerX: Int, top: Int, width: Int) {
        val carved = Ui.heading(text)
        if (font.width(carved) <= width - 40) {
            Ui.centeredHeading(graphics, font, text, centerX, top, Ui.GOLD_BRIGHT)
        } else {
            for ((i, line) in font.split(text, width - 40).take(2).withIndex()) {
                graphics.centeredText(font, line, centerX, top + i * 10, Ui.GOLD_BRIGHT)
            }
        }
    }

    /** A bar of gold in an iron groove, [progress] of the way along. */
    fun bar(graphics: GuiGraphicsExtractor, centerX: Int, top: Int, progress: Float) {
        val left = centerX - BAR_WIDTH / 2
        Ui.inset(graphics, left - 2, top - 2, BAR_WIDTH + 4, 8)
        val filled = (BAR_WIDTH * progress.coerceIn(0f, 1f)).toInt()
        if (filled <= 0) return
        graphics.fill(left, top, left + filled, top + 4, Ui.GOLD)
        graphics.fill(left, top, left + filled, top + 1, Ui.GOLD_BRIGHT)
        // A spark at the leading edge
        val pulse = 0.6f + 0.4f * sin(Util.getMillis() / 1000f * 5f)
        graphics.fill(left + filled - 1, top - 1, left + filled, top + 5, ARGB.color(pulse, 0xFFF8E7B0.toInt()))
    }

    /** Three gold diamonds lighting in turn: still working, no telling how long. */
    fun dots(graphics: GuiGraphicsExtractor, centerX: Int, top: Int) {
        val t = Util.getMillis() / 1000f
        for (i in 0..2) {
            val glow = 0.25f + 0.75f * max(0f, sin(t * 3.2f - i * 0.9f))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, centerX - 14 + i * 12, top - 1, 5, 5, ARGB.white(glow))
        }
    }

    private const val BAR_WIDTH = 180
}

/** The tips, dealt out in turn, so the next loading screen carries on where the last one stopped. */
internal object Tips {
    private var deck: List<String> = emptyList()
    private var next = 0

    fun take(): String? {
        if (next >= deck.size) {
            deck = MenuTexts.tips()
            next = 0
        }
        return deck.getOrNull(next++)
    }
}

/** A tip along the bottom of a loading screen, changing every [SECONDS] seconds. */
internal class TipLine {
    private var tip: String? = Tips.take()
    private var since = Util.getMillis()
    private var first = true

    fun draw(graphics: GuiGraphicsExtractor, font: Font, width: Int, height: Int) {
        val now = Util.getMillis()
        var age = (now - since) / 1000f
        if (age > SECONDS) {
            Tips.take()?.let { tip = it }
            since = now
            age = 0f
            first = false
        }
        val text = tip ?: return
        // The first is there at once (a short wait may only show a frame); the rest fade in and out
        val fadeIn = if (first) 1f else min(1f, age / FADE)
        val alpha = (fadeIn * min(1f, (SECONDS - age) / FADE)).coerceIn(0f, 1f)
        if (alpha <= 0.02f) return
        val line = Component.empty().append(Component.translatable("gui.guildmark.loading.tip").withColor(Ui.GOLD and 0xFFFFFF)).append(" ").append(text)
        val lines = font.split(line, min(WIDTH, width - 40))
        var y = height - 16 - lines.size * 10
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIVIDER, width / 2 - 50, y - 9, 100, 3, ARGB.white(alpha))
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, width / 2 - 2, y - 10, 5, 5, ARGB.white(alpha))
        for (line in lines) {
            graphics.centeredText(font, line, width / 2, y, ARGB.color(alpha, Ui.TEXT_SOFT))
            y += 10
        }
    }

    companion object {
        private const val SECONDS = 9f
        private const val FADE = 0.6f
        private const val WIDTH = 320
    }
}
