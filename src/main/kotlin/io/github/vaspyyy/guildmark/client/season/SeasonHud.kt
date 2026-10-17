package io.github.vaspyyy.guildmark.client.season

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.atlas.CompassHud
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.season.Season
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The season and the day, hanging under the left end of the compass strip: a small medallion whose glyph lives (spring's
 * sprig sways, summer's sun turns and glows, autumn's leaf swings, winter's snowflake turns and glints) and beside it
 * "Spring 5". On a festival's day the festival's own emblem takes the medallion and its name the line; in the three
 * days before, its emblem waits beside the date with the days to go. A new day sends a shimmer across it. It steps
 * aside when the strip's name below reaches it, and goes when the hud does.
 */
object SeasonHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "season")

    private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "season/$name")
    private val MEDALLION = sprite("medallion")
    private val GLOW = sprite("glow")
    private val GLINT = sprite("glint")
    private val GLYPHS = Season.entries.associateWith { sprite(it.id) }
    private val EMBLEMS = FestivalKind.entries.associateWith { sprite("festival_${it.id}") }

    /** When the last day began (millis), for the shimmer; and whether it was the first the session heard. */
    private var dayStart = 0L
    private var fade = 0f
    private var lastFrame = 0L

    fun newDay(first: Boolean) {
        dayStart = if (first) Util.getMillis() - 1500 else Util.getMillis()
    }

    fun reset() {
        dayStart = 0L
        fade = 0f
    }

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        if (!SeasonClient.active && !SeasonClient.festivals) return
        if (minecraft.player == null || minecraft.gui.hud.isHidden || minecraft.debugOverlay.showDebugScreen() || Cutscenes.holdsCamera()) return
        val font = minecraft.font
        val date = SeasonClient.date
        val festival = if (SeasonClient.festivals) FestivalCalendar.on(date) else null
        val (coming, days) = FestivalCalendar.next(date)
        val soon = SeasonClient.festivals && festival == null && days in 1..3
        // With the seasons off the calendar only shows for the festivals: on the day, and in the three days before
        val waiting = !SeasonClient.active && soon
        if (!SeasonClient.active && festival == null && !soon) return

        // Under the strip's left end, or along the top if the strip is off
        val compass = CompassHud.shown()
        val width = (graphics.guiWidth() - 320).coerceIn(96, 182) / 2 * 2
        val left = if (compass) CompassHud.stripLeft else graphics.guiWidth() / 2 - width / 2
        val top = if (compass) 18 else 3

        val text: Component = when {
            festival != null -> festival.title.copy()
            SeasonClient.active -> Component.translatable("season.guildmark.short", date.season.title, date.day)
            days == 1 -> Component.translatable("season.guildmark.tomorrow", coming.title)
            else -> Component.translatable("season.guildmark.soon", coming.title, days)
        }
        val textWidth = font.width(text)
        val right = left + 17 + textWidth + if (soon && !waiting) 18 else 0
        // Make way for the name of what's under the notch
        val now = Util.getMillis()
        val step = min(0.1f, (now - lastFrame) / 1000f).coerceAtLeast(0f)
        lastFrame = now
        val clash = compass && CompassHud.labelLeft < right + 4
        fade = if (clash) max(0f, fade - step * 5f) else min(1f, fade + step * 3f)
        if (fade <= 0.01f) return
        val alpha = fade

        val seconds = (now % 600_000L) / 1000f
        val sinceDay = (now - dayStart) / 1000f

        // The medallion, and the glyph alive in it
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MEDALLION, left, top, 14, 14, ARGB.color(alpha, 0xFFFFFF))
        val pose = graphics.pose()
        val cx = left + 7f
        val cy = top + 7f
        if (festival != null) {
            // The festival's emblem bobs in a pulse of gold
            val pulse = 0.5f + 0.5f * sin(seconds * 2.6f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, left - 3, top - 3, 20, 20, ARGB.color(alpha * (0.35f + 0.4f * pulse), festival.color))
            pose.pushMatrix()
            pose.translate(cx, cy + sin(seconds * 3.1f) * 0.6f)
            pose.rotate(sin(seconds * 1.7f) * 0.12f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EMBLEMS.getValue(festival), -6, -6, 12, 12, ARGB.color(alpha, 0xFFFFFF))
            pose.popMatrix()
        } else if (SeasonClient.active) {
            glyph(graphics, date.season, cx, cy, seconds, alpha)
        } else {
            // The coming festival's emblem, waiting
            pose.pushMatrix()
            pose.translate(cx, cy + sin(seconds * 2.2f) * 0.5f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EMBLEMS.getValue(coming), -6, -6, 12, 12, ARGB.color(alpha * 0.85f, 0xFFFFFF))
            pose.popMatrix()
        }
        // A shimmer passing over it as the day turns
        if (sinceDay in 0f..1.6f) {
            val t = sinceDay / 1.6f
            val glow = sin(t * PI.toFloat())
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, left - 3, top - 3, 20, 20, ARGB.color(alpha * glow * 0.8f, 0xFFF4D8))
        }

        // The date, and a festival's emblem waiting by it
        val color = when {
            festival != null -> ARGB.color(alpha, festival.color)
            else -> ARGB.color(alpha, Ui.TEXT_SOFT)
        }
        graphics.text(font, text, left + 17, top + 3, color, true)
        if (soon && !waiting) {
            val ex = left + 17 + textWidth + 3
            val bob = sin(seconds * 2.2f) * 0.5f
            pose.pushMatrix()
            pose.translate(ex + 6f, top + 7f + bob)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EMBLEMS.getValue(coming), -6, -6, 12, 12, ARGB.color(alpha * 0.9f, 0xFFFFFF))
            pose.popMatrix()
            val count = days.toString()
            graphics.text(font, count, ex + 12, top + 7, ARGB.color(alpha, Ui.GOLD), true)
        }
    }

    /** The season's glyph, alive: centred on ([cx], [cy]). */
    private fun glyph(graphics: GuiGraphicsExtractor, season: Season, cx: Float, cy: Float, seconds: Float, alpha: Float) {
        val pose = graphics.pose()
        val icon = GLYPHS.getValue(season)
        pose.pushMatrix()
        when (season) {
            Season.SPRING -> {
                // The sprig sways from its stem in a light breeze
                pose.translate(cx, cy + 5f)
                pose.rotate(sin(seconds * 1.6f) * 0.14f + sin(seconds * 3.7f) * 0.04f)
                pose.translate(0f, -5f)
            }
            Season.SUMMER -> {
                // The sun turns slowly in its own glow
                val pulse = 0.5f + 0.5f * sin(seconds * 1.4f)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, (cx - 9.5f).toInt(), (cy - 9.5f).toInt(), 19, 19, ARGB.color(alpha * (0.2f + 0.25f * pulse), 0xFFD27A))
                pose.translate(cx, cy)
                pose.rotate(seconds * 0.35f)
            }
            Season.AUTUMN -> {
                // The leaf swings on its stalk
                pose.translate(cx, cy - 4f)
                pose.rotate(sin(seconds * 1.9f) * 0.3f)
                pose.translate(0f, 4f + sin(seconds * 3.8f) * 0.3f)
            }
            Season.WINTER -> {
                pose.translate(cx, cy)
                pose.rotate(seconds * 0.2f)
                val beat = sin(seconds * 2.4f)
                pose.scale(1f + 0.05f * beat, 1f + 0.05f * beat)
            }
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, -6, -6, 12, 12, ARGB.color(alpha, 0xFFFFFF))
        pose.popMatrix()
        // Now and then a glint runs over winter's flake
        if (season == Season.WINTER) {
            val t = (seconds % 5f) / 5f
            if (t < 0.12f) {
                val k = sin(t / 0.12f * PI.toFloat())
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLINT, (cx + 1f).toInt(), (cy - 6f).toInt(), 5, 5, ARGB.color(alpha * k, 0xFFFFFF))
            }
        }
    }
}
