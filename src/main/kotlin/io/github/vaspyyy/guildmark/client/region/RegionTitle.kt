package io.github.vaspyyy.guildmark.client.region

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.atlas.PlaceBanner
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.region.RegionKind
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.min

/**
 * A region's name across the upper screen as you come into it, larger than a place's: the name in the
 * carved capitals, a gold rule, and what sort of land it is under that, with "new region" over it and a
 * chime the first time. A region's card shows again only after a while away, and waits for a place's
 * banner to finish rather than talk over it.
 */
object RegionTitle : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "region_title")

    private const val FADE_IN = 1.1f
    private const val FADE_OUT = 1.8f
    /** A region's card shows again on entering only after this long (game ticks) since it last did. */
    private const val REPEAT = 20L * 60 * 10
    /** How long a card may wait for a place's banner before it's dropped (game ticks). */
    private const val PATIENCE = 20L * 12
    private const val SCALE = 3f

    private class Card(val name: Component, val subtitle: Component, val first: Boolean, val seconds: Float, val asked: Long) {
        var start = 0L
    }

    private var card: Card? = null
    private var waiting: Card? = null
    private val lastShown = HashMap<Long, Long>()

    private fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.REGION_TITLES.get()

    /** The player came into region [id], called [name], a [kind] of land, for the [first] time or not. */
    fun announce(id: Long, name: String, kind: RegionKind, first: Boolean) {
        val level = Minecraft.getInstance().level ?: return
        val now = level.gameTime
        val last = lastShown[id]
        if (!first && last != null && now - last in 0 until REPEAT) return
        lastShown[id] = now
        if (!enabled()) return
        waiting = Card(Component.literal(name), Component.translatable(kind.titleKey), first, if (first) 6.5f else 5f, now)
    }

    /** Every tick: bring on a waiting card once nothing else is across the screen. */
    fun tick() {
        val next = waiting ?: return
        val level = Minecraft.getInstance().level ?: return
        if (level.gameTime - next.asked > PATIENCE) {
            waiting = null
            return
        }
        if (PlaceBanner.showing()) return
        waiting = null
        next.start = Util.getMillis()
        card = next
        if (next.first) Ui.play(ModSounds.UI_DISCOVER.get(), 0.75f, 0.9f)
    }

    fun reset() {
        card = null
        waiting = null
        lastShown.clear()
    }

    /** Whether a region's card is up or about to be. */
    fun showing(): Boolean = card != null || waiting != null

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val shown = card ?: return
        val minecraft = Minecraft.getInstance()
        val time = (Util.getMillis() - shown.start) / 1000f
        if (time > shown.seconds) {
            card = null
            return
        }
        // A place's banner has the floor while it's up
        if (minecraft.gui.hud.isHidden || PlaceBanner.showing()) return
        val alpha = min(min(time / FADE_IN, (shown.seconds - time) / FADE_OUT), 1f).coerceIn(0f, 1f)
        if (alpha < 0.02f) return
        val font = minecraft.font
        val centerX = graphics.guiWidth() / 2
        // Rises a little as it comes in
        val rise = ((1 - min(time / FADE_IN, 1f)) * 4).toInt()
        val top = (graphics.guiHeight() * 0.2f).toInt() + rise

        if (shown.first) {
            val over = Ui.heading("region.guildmark.new")
            graphics.text(font, over, centerX - font.width(over) / 2, top - 14 + Ui.HEADING_RISE, ARGB.color(alpha, Ui.GOLD), true)
        }

        // The name in the carved capitals, three times the size (less if a long name wouldn't fit)
        val title = Ui.heading(shown.name)
        val width = font.width(title)
        val scale = min(SCALE, (graphics.guiWidth() - 40f) / width.coerceAtLeast(1))
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centerX.toFloat(), top.toFloat())
        pose.scale(scale, scale)
        graphics.text(font, title, -width / 2, Ui.HEADING_RISE, ARGB.color(alpha, Ui.TEXT), true)
        pose.popMatrix()

        val below = top + (Ui.HEADING_HEIGHT * scale).toInt() + 8
        val rule = min((width * scale).toInt() + 80, graphics.guiWidth() - 40)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIVIDER, centerX - rule / 2, below, rule, 3, alpha)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND_LARGE, centerX - 3, below - 2, 7, 7, alpha)
        val subtitle = shown.subtitle
        graphics.text(font, subtitle, centerX - font.width(subtitle) / 2, below + 9, ARGB.color(alpha, Ui.TEXT_SOFT), true)
    }
}
