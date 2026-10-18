package io.github.vaspyyy.guildmark.client.season

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.atlas.PlaceBanner
import io.github.vaspyyy.guildmark.client.region.RegionTitle
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalRegistry
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.SeasonDate
import io.github.vaspyyy.guildmark.season.SeasonRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A new season across the upper screen as it comes in: its name in the carved capitals, a rule in its colour, the day
 * and the year, and a line of what it brings; petals, motes of light, leaves or snow drift across it as it shows, and
 * it comes in on its own short sting. It waits for a place's or a region's card to finish rather than talk over it.
 *
 * A festival has the same card as you come into its square ([showFestival]): its name in its own colour, the town over
 * it and what's on below, coming in on the festival fanfare.
 */
object SeasonBanner : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "season_banner")

    private const val SECONDS = 7.5f
    private const val FADE_IN = 1.2f
    private const val FADE_OUT = 1.8f
    private const val SCALE = 3f
    private const val PATIENCE = 20 * 15

    private class Card(val season: Season, val date: SeasonDate, val festival: FestivalKind? = null, val town: String = "") {
        var start = 0L
    }

    private var card: Card? = null
    private var waiting: Card? = null
    private var waited = 0

    private fun motes(season: Season): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "season/mote_${season.id}")

    fun show(date: SeasonDate, @Suppress("UNUSED_PARAMETER") was: SeasonDate) {
        waiting = Card(date.season, date)
        waited = 0
    }

    /** The festival of [kind] in [town], as you come into its square. */
    fun showFestival(kind: FestivalKind, town: String) {
        // A season's own card coming in has the say
        if (waiting?.festival == null && waiting != null) return
        waiting = Card(kind.season, SeasonClient.date, kind, town)
        waited = 0
    }

    fun tick() {
        val next = waiting ?: return
        if (++waited > PATIENCE) {
            waiting = null
            return
        }
        if (PlaceBanner.showing() || RegionTitle.showing()) return
        waiting = null
        next.start = Util.getMillis()
        card = next
        val sting = if (next.festival != null) FestivalRegistry.FANFARE.get() else SeasonRegistry.STINGS[next.season.ordinal].get()
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sting, 1f, if (next.festival != null) 0.7f else 0.9f))
    }

    fun reset() {
        card = null
        waiting = null
    }

    fun showing(): Boolean = card != null || waiting != null

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val shown = card ?: return
        val minecraft = Minecraft.getInstance()
        val time = (Util.getMillis() - shown.start) / 1000f
        if (time > SECONDS) {
            card = null
            return
        }
        if (minecraft.gui.hud.isHidden) return
        val alpha = min(min(time / FADE_IN, (SECONDS - time) / FADE_OUT), 1f).coerceIn(0f, 1f)
        if (alpha < 0.02f) return
        val font = minecraft.font
        val centerX = graphics.guiWidth() / 2
        val rise = ((1 - min(time / FADE_IN, 1f)) * 5).toInt()
        val top = (graphics.guiHeight() * 0.18f).toInt() + rise
        val season = shown.season

        // What the season brings, drifting across behind the words
        drift(graphics, season, centerX, top, time, alpha)

        val festival = shown.festival
        val over = Ui.heading(
            if (festival != null) Component.translatable("festival.guildmark.banner.over", shown.town)
            else Component.translatable("season.guildmark.banner.over", shown.date.year),
        )
        graphics.text(font, over, centerX - font.width(over) / 2, top - 14 + Ui.HEADING_RISE, ARGB.color(alpha, Ui.GOLD), true)

        val title = Ui.heading(festival?.title ?: season.title)
        val color = festival?.color ?: season.color
        val width = font.width(title)
        val scale = min(SCALE, (graphics.guiWidth() - 40f) / width.coerceAtLeast(1))
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centerX.toFloat(), top.toFloat())
        pose.scale(scale, scale)
        graphics.text(font, title, -width / 2, Ui.HEADING_RISE, ARGB.color(alpha, color), true)
        pose.popMatrix()

        val below = top + (Ui.HEADING_HEIGHT * scale).toInt() + 8
        // The rule draws out from the middle
        val grown = min(1f, time / 1.4f).let { it * it * (3f - 2f * it) }
        val rule = (min((width * scale).toInt() + 80, graphics.guiWidth() - 40) * grown).toInt().coerceAtLeast(8)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIVIDER, centerX - rule / 2, below, rule, 3, ARGB.color(alpha, color))
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND_LARGE, centerX - 3, below - 2, 7, 7, alpha)
        val line = if (festival != null) Component.translatable("festival.guildmark.${festival.id}.about") else Component.translatable("season.guildmark.banner.${season.id}")
        graphics.text(font, line, centerX - font.width(line) / 2, below + 9, ARGB.color(alpha, Ui.TEXT_SOFT), true)
    }

    /** A score of the season's motes crossing the banner on paths of their own. */
    private fun drift(graphics: GuiGraphicsExtractor, season: Season, centerX: Int, top: Int, time: Float, alpha: Float) {
        val sprite = motes(season)
        val pose = graphics.pose()
        val span = min(graphics.guiWidth() - 20, 360)
        for (i in 0 until 22) {
            val seed = i * 0.6180339f
            val speed = 0.7f + (seed * 7.3f % 1f) * 0.6f
            val t = (time * speed * 0.12f + seed) % 1f
            // Summer's motes rise; the rest come down and across
            val x = centerX - span / 2 + span * when (season) {
                Season.SUMMER -> (seed * 3.7f % 1f) + sin(time * 0.9f + i) * 0.02f
                else -> ((seed * 3.7f + t * 0.35f) % 1f)
            }
            val y = top - 22 + 70 * when (season) {
                Season.SUMMER -> 1f - t
                else -> t
            } + sin(time * 1.3f + i * 1.7f) * 4f
            val edge = min(1f, min(t / 0.15f, (1f - t) / 0.15f))
            val a = alpha * edge * (0.45f + 0.4f * (seed * 5.1f % 1f))
            if (a < 0.03f) continue
            val size = 5
            pose.pushMatrix()
            pose.translate(x, y)
            if (season != Season.SUMMER) pose.rotate(time * (0.8f + seed) + i + cos(time + i) * 0.4f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, -size / 2, -size / 2, size, size, ARGB.color(a, 0xFFFFFF))
            pose.popMatrix()
        }
    }
}
