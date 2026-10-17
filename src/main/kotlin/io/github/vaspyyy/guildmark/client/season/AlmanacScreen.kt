package io.github.vaspyyy.guildmark.client.season

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.SeasonCrops
import io.github.vaspyyy.guildmark.season.SeasonDate
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Almanac: the wheel of the year on the left, its needle swinging round to today as the book opens, the festivals
 * marked on its rim; on the right, three pages: the seasons and what each brings, the festivals with their days and
 * how long to go, and every crop's pace through the year with today's column lit.
 */
class AlmanacScreen : Screen(Component.translatable("gui.guildmark.almanac")) {
    companion object {
        fun open() {
            Minecraft.getInstance().gui.setScreen(AlmanacScreen())
        }

        private const val W = 324
        private const val H = 206
        private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "season/$name")
        private val WHEEL = sprite("wheel")
        private val NEEDLE = sprite("needle")
        private val GLOW = sprite("glow")
        private val GLYPHS = Season.entries.associateWith { sprite(it.id) }
        private val EMBLEMS = FestivalKind.entries.associateWith { sprite("festival_${it.id}") }
        private val MOTES = Season.entries.associateWith { sprite("mote_${it.id}") }

        /** Which page was open last, kept for the session. */
        private var lastPage = 0
    }

    private enum class Page(val key: String) { SEASONS("seasons"), FESTIVALS("festivals"), CROPS("crops") }

    private var left = 0
    private var top = 0
    private var page = Page.entries[lastPage]
    private var pageChanged = 0L
    private val opened = Util.getMillis()
    private var ticks = 0

    override fun init() {
        left = (width - W) / 2
        top = (height - H) / 2
        Ui.open()
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun tick() {
        ticks++
    }

    private fun tabAt(x: Double, y: Double): Page? {
        for ((i, p) in Page.entries.withIndex()) {
            val tx = left + 168 + i * 50
            if (x >= tx && x < tx + 48 && y >= top + 22 && y < top + 36) return p
        }
        return null
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val p = tabAt(event.x(), event.y())
            if (p != null && p != page) {
                page = p
                lastPage = p.ordinal
                pageChanged = Util.getMillis()
                Ui.page()
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val fadeIn = min(1f, (Util.getMillis() - opened) / 250f)
        graphics.fill(0, 0, width, height, ARGB.color(fadeIn * 0.72f, 0x06060B))
        Ui.panel(graphics, left, top, W, H)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val date = SeasonClient.date
        val seconds = (Util.getMillis() % 600_000L) / 1000f
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.almanac"), left + W / 2, top + 7)
        Ui.divider(graphics, left + 12, top + 18, W - 24)
        wheel(graphics, date, seconds)
        // A line down the spine between the two pages
        graphics.fill(left + 160, top + 24, left + 161, top + H - 10, ARGB.color(0.35f, Ui.GOLD))

        // The tabs
        for ((i, p) in Page.entries.withIndex()) {
            val tx = left + 168 + i * 50
            val hot = tabAt(mouseX.toDouble(), mouseY.toDouble()) == p
            val on = p == page
            graphics.fill(tx, top + 22, tx + 48, top + 36, ARGB.color(if (on) 0.55f else if (hot) 0.35f else 0.18f, if (on) 0x5A4628 else 0x2A2418))
            if (on) graphics.fill(tx, top + 35, tx + 48, top + 36, Ui.GOLD)
            val label = Component.translatable("gui.guildmark.almanac.${p.key}")
            graphics.text(font, label, tx + 24 - font.width(label) / 2, top + 25, if (on) Ui.GOLD_BRIGHT else if (hot) Ui.TEXT else Ui.TEXT_SOFT, true)
        }

        // The page, sliding in as it's turned to
        val turn = min(1f, (Util.getMillis() - pageChanged) / 220f).let { it * it * (3f - 2f * it) }
        val alpha = turn
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - turn) * 10f, 0f)
        when (page) {
            Page.SEASONS -> seasons(graphics, date, seconds, alpha, mouseX, mouseY)
            Page.FESTIVALS -> festivals(graphics, date, seconds, alpha, mouseX, mouseY)
            Page.CROPS -> crops(graphics, date, alpha, mouseX, mouseY)
        }
        pose.popMatrix()
    }

    // ---- The wheel ----

    /** The angle of a day of the year on the wheel: spring's first day at the top, the year going round clockwise. */
    private fun angle(position: Double): Float = (position / 4.0 * 2 * PI - PI / 2).toFloat()

    private fun wheel(graphics: GuiGraphicsExtractor, date: SeasonDate, seconds: Float) {
        val cx = left + 82
        val cy = top + 92
        val r = 54
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, WHEEL, cx - 60, cy - 60, 120, 120)
        // A tick for every day on the rim, longer at each season's turn
        val yearDays = 4 * date.length
        for (d in 0 until yearDays) {
            val a = angle(d.toDouble() / date.length)
            val long = d % date.length == 0
            val r0 = if (long) r - 7 else r - 3
            for (k in r0..r) {
                val x = cx + cos(a) * k
                val y = cy + sin(a) * k
                graphics.fill(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1, ARGB.color(if (long) 0.9f else 0.45f, Ui.TEXT_SOFT))
            }
        }
        // The seasons' glyphs round the inside, each at its season's middle
        for (season in Season.entries) {
            val a = angle(season.ordinal + 0.5)
            val gx = cx + cos(a) * (r - 22)
            val gy = cy + sin(a) * (r - 22)
            val now = season == date.season
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(gx, gy)
            if (now) pose.scale(1.15f + 0.05f * sin(seconds * 2f), 1.15f + 0.05f * sin(seconds * 2f))
            pose.rotate(sin(seconds * 1.3f + season.ordinal) * 0.08f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLYPHS.getValue(season), -6, -6, 12, 12, ARGB.color(if (now) 1f else 0.6f, 0xFFFFFF))
            pose.popMatrix()
        }
        // The festivals on the rim
        for (kind in FestivalKind.entries) {
            val festivalDate = FestivalCalendar.dateOf(kind, date)
            val a = angle(festivalDate.position)
            val fx = cx + cos(a) * (r + 1)
            val fy = cy + sin(a) * (r + 1)
            val today = FestivalCalendar.on(date) == kind
            if (today) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, (fx - 10).toInt(), (fy - 10).toInt(), 20, 20, ARGB.color(0.5f + 0.3f * sin(seconds * 3f), kind.color))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EMBLEMS.getValue(kind), (fx - 6).toInt(), (fy - 6 + sin(seconds * 2f + kind.ordinal) * 0.6f).toInt(), 12, 12)
        }
        // The needle, swinging round to today as the book opens and settling with a little overshoot
        val target = angle(date.position)
        val t = min(1f, (Util.getMillis() - opened) / 1300f)
        val back = 1.70158f
        val eased = 1f + (back + 1f) * (t - 1f) * (t - 1f) * (t - 1f) + back * (t - 1f) * (t - 1f)
        val from = target - PI.toFloat() * 0.75f
        val needle = from + (target - from) * eased
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(cx.toFloat(), cy.toFloat())
        pose.rotate(needle + PI.toFloat() / 2)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, NEEDLE, -3, -r + 4, 6, r - 2)
        pose.popMatrix()
        val tipX = cx + cos(needle) * (r - 6)
        val tipY = cy + sin(needle) * (r - 6)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, (tipX - 6).toInt(), (tipY - 6).toInt(), 12, 12, ARGB.color(0.4f + 0.3f * sin(seconds * 2.5f), date.season.color))

        // Today, under the wheel
        val title = date.title
        graphics.text(font, title, cx - font.width(title) / 2, cy + 64, Ui.TEXT, true)
        val sub = Component.translatable("gui.guildmark.almanac.year", date.year, date.phase.of(date.season))
        graphics.text(font, sub, cx - font.width(sub) / 2, cy + 75, Ui.TEXT_SOFT, true)
        val (kind, days) = FestivalCalendar.next(date)
        val next = when (days) {
            0 -> Component.translatable("gui.guildmark.almanac.festival_today", kind.title)
            1 -> Component.translatable("gui.guildmark.almanac.festival_tomorrow", kind.title)
            else -> Component.translatable("gui.guildmark.almanac.festival_in", kind.title, days)
        }
        graphics.text(font, next, cx - font.width(next) / 2, cy + 86, ARGB.color(1f, kind.color), true)

        // The season's motes drifting over the left page
        val sprite = MOTES.getValue(date.season)
        for (i in 0 until 9) {
            val seed = i * 0.6180339f
            val u = (seconds * (0.02f + (seed * 3.1f % 1f) * 0.02f) + seed) % 1f
            val x = left + 14 + ((seed * 7.7f + u * 0.4f) % 1f) * 136
            val y = top + 26 + (if (date.season == Season.SUMMER) 1f - u else u) * 160
            val k = min(1f, min(u / 0.1f, (1f - u) / 0.1f)) * 0.35f
            graphics.pose().pushMatrix()
            graphics.pose().translate(x, y)
            graphics.pose().rotate(seconds * (0.5f + seed) + i)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, -2, -2, 5, 5, ARGB.color(k, 0xFFFFFF))
            graphics.pose().popMatrix()
        }
    }

    // ---- The pages ----

    private fun seasons(graphics: GuiGraphicsExtractor, date: SeasonDate, seconds: Float, alpha: Float, mouseX: Int, mouseY: Int) {
        val x0 = left + 168
        var y = top + 42
        for (season in Season.entries) {
            val now = season == date.season
            val hot = mouseX in x0..(x0 + 148) && mouseY in y..(y + 38)
            if (now || hot) graphics.fill(x0 - 2, y - 2, x0 + 148, y + 37, ARGB.color(alpha * (if (now) 0.28f else 0.14f), season.color))
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x0 + 8f, y + 9f)
            pose.rotate(sin(seconds * 1.5f + season.ordinal * 1.3f) * (if (now) 0.12f else 0.05f))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLYPHS.getValue(season), -6, -6, 12, 12, ARGB.color(alpha, 0xFFFFFF))
            pose.popMatrix()
            graphics.text(font, season.title, x0 + 20, y + 1, ARGB.color(alpha, season.color), true)
            if (now) {
                val tag = Component.translatable("gui.guildmark.almanac.now")
                graphics.text(font, tag, x0 + 146 - font.width(tag), y + 1, ARGB.color(alpha, Ui.GOLD), true)
            }
            graphics.textWithWordWrap(font, Component.translatable("gui.guildmark.almanac.season.${season.id}"), x0 + 20, y + 12, 126, ARGB.color(alpha, Ui.TEXT_SOFT), false)
            y += 40
        }
    }

    private fun festivals(graphics: GuiGraphicsExtractor, date: SeasonDate, seconds: Float, alpha: Float, mouseX: Int, mouseY: Int) {
        val x0 = left + 168
        var y = top + 42
        val (next, _) = FestivalCalendar.next(date)
        for (kind in FestivalKind.entries) {
            val on = FestivalCalendar.dateOf(kind, date)
            var ahead = on.calendarDay - date.calendarDay
            if (ahead < 0) ahead += 4L * date.length
            val today = ahead == 0L
            val hot = mouseX in x0..(x0 + 148) && mouseY in y..(y + 38)
            if (today || kind == next || hot) graphics.fill(x0 - 2, y - 2, x0 + 148, y + 37, ARGB.color(alpha * (if (today) 0.32f else if (kind == next) 0.2f else 0.12f), kind.color))
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x0 + 8f, y + 9f + sin(seconds * 2f + kind.ordinal) * (if (today) 1f else 0.4f))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EMBLEMS.getValue(kind), -6, -6, 12, 12, ARGB.color(alpha, 0xFFFFFF))
            pose.popMatrix()
            graphics.text(font, kind.title, x0 + 20, y + 1, ARGB.color(alpha, kind.color), true)
            val whenText = when (ahead) {
                0L -> Component.translatable("gui.guildmark.almanac.today")
                1L -> Component.translatable("gui.guildmark.almanac.tomorrow")
                else -> Component.translatable("gui.guildmark.almanac.in_days", ahead)
            }
            graphics.text(font, whenText, x0 + 146 - font.width(whenText), y + 1, ARGB.color(alpha, if (today) Ui.GOLD_BRIGHT else Ui.TEXT_FAINT), true)
            val dayText = Component.translatable("season.guildmark.short", kind.season.title, SeasonDate.festivalDay(date.length))
            graphics.text(font, dayText, x0 + 20, y + 11, ARGB.color(alpha, Ui.TEXT_FAINT), false)
            graphics.textWithWordWrap(font, Component.translatable("gui.guildmark.almanac.festival.${kind.id}"), x0 + 20, y + 20, 126, ARGB.color(alpha, Ui.TEXT_SOFT), false)
            y += 40
        }
    }

    private fun crops(graphics: GuiGraphicsExtractor, date: SeasonDate, alpha: Float, mouseX: Int, mouseY: Int) {
        val x0 = left + 168
        val y0 = top + 42
        val col0 = x0 + 72
        // The seasons across the top, today's lit
        for (season in Season.entries) {
            val cx = col0 + season.ordinal * 18
            if (season == date.season) graphics.fill(cx - 1, y0 - 2, cx + 16, y0 + 12 + SeasonCrops.SHOWN.size * 11, ARGB.color(alpha * 0.22f, season.color))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLYPHS.getValue(season), cx + 1, y0 - 1, 12, 12, ARGB.color(alpha, 0xFFFFFF))
        }
        var y = y0 + 13
        var hovered: Component? = null
        for ((item, block) in SeasonCrops.SHOWN) {
            val profile = SeasonCrops.profile(block) ?: continue
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x0.toFloat(), y.toFloat() - 1f)
            pose.scale(0.62f, 0.62f)
            graphics.item(ItemStack(item), 0, 0)
            pose.popMatrix()
            val name = item.getName(ItemStack(item))
            graphics.text(font, font.substrByWidth(name, 58).string, x0 + 12, y + 1, ARGB.color(alpha, Ui.TEXT_SOFT), false)
            for (season in Season.entries) {
                val pace = profile.pace(season)
                val cx = col0 + season.ordinal * 18 + 7
                val (mark, color) = when {
                    pace >= 1.08f -> "+" to 0x8FD46A
                    pace >= 0.95f -> "=" to 0xD8D0B0
                    pace >= 0.6f -> "-" to 0xE0A050
                    else -> "*" to 0x9AB4D8
                }
                graphics.text(font, mark, cx - font.width(mark) / 2 + 1, y + 1, ARGB.color(alpha, color), false)
                if (mouseX in (cx - 7)..(cx + 8) && mouseY in y..(y + 10)) {
                    hovered = Component.translatable("gui.guildmark.almanac.pace", name, season.title, "%d%%".format((pace * 100).toInt()))
                }
            }
            y += 11
        }
        val foot = hovered ?: Component.translatable("gui.guildmark.almanac.shelter")
        graphics.textWithWordWrap(font, foot, x0, y + 3, 146, ARGB.color(alpha, if (hovered != null) Ui.TEXT else Ui.TEXT_FAINT), false)
    }
}
