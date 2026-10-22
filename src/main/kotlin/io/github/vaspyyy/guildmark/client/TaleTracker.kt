package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.tales.TaleRow
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The tales' part of the contract tracker (living-world/stories/design.md 5.1, Part D): what a tale's row says, in the inks that
 * read on the tracker's dark panel, and how it moves.
 *
 * A row comes in as the quill pops up with a light behind it and the words slide in from the left. When the step turns, the old
 * line rolls up out of its place and the new one rolls in under it, and the quill beats once in the new line's ink. When the ink
 * turns (amber to red on the step's last two days, to green when only the telling is left) it turns over four-fifths of a second
 * and the quill beats once more, and while the step's last days run the quill glows a little red. When the contract a step is
 * working is in the pack, its count sits at the end of the status line and flashes as it climbs. A tale that leaves the tracker
 * fades where it stood, and the rows below close the gap afterwards. The panel itself grows and shrinks with the rows, and the rows
 * glide to their places, whichever kind they are.
 *
 * The motion keeps its own clock, [clock], which runs only while the game runs and no screen but the chat is open: a row said yes
 * to in a talk arrives after the talk, a row that ends in one fades after it, so neither is missed behind a screen. The geometry
 * (the panel's height, the rows' places) glides in real time, so a contract taken at the board is never drawn beyond its panel.
 * The contract tracker calls [frame] first in every frame it draws, [observe] with the tale rows it has, [height] and [slide] for
 * where things sit, [draw] for each tale row and [sweep] last.
 */
internal object TaleTracker {
    /** The little quill every tale row is marked with (a plain item, never given out). */
    val QUILL: ItemStack by lazy { ItemStack(ModItems.TALE_QUILL.get()) }

    // ---- What a tale row says ----

    /**
     * How far the contract that a tale row has taken up has come: [have] of [of] (zero for a note that counts nothing), and
     * whether it is [done] (to be handed in) or has [lapsed].
     */
    class Count(val have: Int, val of: Int, val done: Boolean, val lapsed: Boolean)

    /**
     * One tale as the tracker draws it: its [title], the [status] (the contract's word when it is done or has lapsed), the [tone]
     * of its ink, the [count] of the contract it carries, [tip] (the full status and the days left, for the map's tooltip), how
     * far it has [gone] from the tracker (0 while it is on it, up to 1) and where it [index] stands among the tales.
     */
    class Line(
        val id: Int,
        val title: Component,
        val status: Component,
        val tone: Int,
        val count: Count?,
        val tip: List<Component>,
        val gone: Float = 0f,
        val index: Int = 0,
    )

    /** The tale and the step a contract's chain names (`tale:<tale>:<step>`), or null for a contract that is none of a tale's. */
    fun chain(chain: String): Pair<Int, Int>? {
        if (!chain.startsWith("tale:")) return null
        val parts = chain.split(':')
        if (parts.size < 3) return null
        val tale = parts[1].toIntOrNull() ?: return null
        val step = parts[2].toIntOrNull() ?: return null
        return tale to step
    }

    private val AMBER = 0xFFE3AE4A.toInt()
    private val RED = Ui.RED
    private val WHITE = 0xFFFFFFFF.toInt()

    /** The ink of a [TaleRow] tone: amber by default, red on the step's last two days, green when only the telling is left. */
    fun inkOf(tone: Int): Int = when (tone) {
        TaleRow.RED -> RED
        TaleRow.GREEN -> Ui.GREEN
        else -> AMBER
    }

    /** The ink the distance under a tale's arrow is written in. */
    val DISTANCE_INK: Int get() = Ui.TEXT_SOFT

    /**
     * [row] as the tracker draws it, [index]th among the tales. A [count] says how far the contract it has taken up has come: when
     * that is done the row says to take it to the board, and when it has lapsed it says so, each in its own ink.
     */
    fun line(row: TaleRow, count: Count?, index: Int): Line {
        var status: Component = Component.translatable(row.status, *row.statusArgs.toTypedArray())
        var tone = row.tone
        var shown: Count? = null
        if (count != null) {
            when {
                count.lapsed -> {
                    status = Component.translatable("gui.guildmark.tracker.expired")
                    tone = TaleRow.RED
                }
                count.done -> {
                    status = Component.translatable("gui.guildmark.tale.row.hand_in")
                    tone = TaleRow.GREEN
                }
                count.of > 1 -> shown = count
            }
        }
        val days = if (row.days <= 1) Component.translatable("gui.guildmark.tale.days.last") else Component.translatable("gui.guildmark.tracker.days", row.days)
        val tip = listOf(
            Component.empty().append(status).withColor(inkOf(tone) and 0xFFFFFF),
            if (row.days <= 2) Component.empty().append(days).withColor(RED and 0xFFFFFF) else days,
        )
        return Line(row.id, Component.translatable(row.title, *row.titleArgs.toTypedArray()), status, tone, shown, tip, 0f, index)
    }

    // ---- The clock ----

    /** Seconds of the tracker's own time (see the head of this file), the seconds the last frame gave the geometry, and the time of the last frame. */
    private var clock = 0f
    private var dt = 0f
    private var last = 0L

    /** Which frame this is, so that what was not seen in it can be told from what was. */
    private var stamp = 0

    /** A frame this long after the last means the tracker was not drawn in between (hidden, off, a screen over it): its places are no longer true. */
    private const val AWAY = 1000L

    /** First in every frame the tracker draws. */
    fun frame(minecraft: Minecraft) {
        val now = Util.getMillis()
        val gap = now - last
        last = now
        if (gap > AWAY) {
            panel = -1f
            slides.clear()
        }
        val passed = if (minecraft.isPaused) 0f else min(0.1f, gap / 1000f)
        val screen = minecraft.gui.screen()
        dt = passed
        if (screen == null || screen is ChatScreen) clock += passed
        stamp++
    }

    // ---- What it remembers of each tale ----

    /** What the tracker has seen of one tale: when it came, what it last said and in what ink, and when each thing last moved. */
    private class Slot(val born: Float) {
        var statusText = ""
        var status: Component = Component.empty()
        var title: Component = Component.empty()
        var tone = -1
        var index = 0
        var count = -1

        /** The line that is rolling out of its place, the ink it had, and when it began to. */
        var rolling: Component? = null
        var rollingInk = 0
        var turned = -99f

        /** The ink is turning from [inkFrom] to [inkTo], from [toned]. */
        var inkFrom = 0
        var inkTo = 0
        var toned = -99f

        /** The quill beat last at [pulse], in [pulseInk]; the count rose last at [counted]. */
        var pulse = -99f
        var pulseInk = 0
        var counted = -99f

        /** When it left the tracker, or -1 while it is on it; and the frame it was last seen in. */
        var leftAt = -1f
        var seen = 0

        /** The ink it is in at [now]. */
        fun ink(now: Float): Int = ARGB.srgbLerp(smooth(((now - toned) / TURN).coerceIn(0f, 1f)), inkFrom, inkTo)
    }

    private val slots = LinkedHashMap<Int, Slot>()

    /**
     * Once in every frame, with the tales the tracker has: one seen for the first time is born; one whose status reads otherwise
     * has its old line roll out and its quill beat; one whose tone changed turns its ink; a count that went up flashes; and one
     * that is not there any more is let go once it has faded ([GHOST] seconds), staying as a [ghosts] entry until then.
     */
    fun observe(lines: List<Line>) {
        for (line in lines) {
            val ink = inkOf(line.tone)
            val text = line.status.string
            val slot = slots.getOrPut(line.id) {
                Slot(clock).also {
                    it.statusText = text
                    it.tone = line.tone
                    it.inkFrom = ink
                    it.inkTo = ink
                    it.count = line.count?.have ?: -1
                }
            }
            slot.leftAt = -1f
            slot.seen = stamp
            if (text != slot.statusText) {
                slot.rolling = slot.status
                slot.rollingInk = slot.ink(clock)
                slot.turned = clock
                slot.pulse = clock
                slot.pulseInk = ink
                slot.statusText = text
            }
            if (line.tone != slot.tone) {
                slot.inkFrom = slot.ink(clock)
                slot.inkTo = ink
                slot.toned = clock
                slot.tone = line.tone
                slot.pulse = clock
                slot.pulseInk = ink
            }
            val have = line.count?.have ?: -1
            if (have != slot.count) {
                if (slot.count >= 0 && have > slot.count) slot.counted = clock
                slot.count = have
            }
            slot.status = line.status
            slot.title = line.title
            slot.index = line.index
        }
        val gone = slots.values.iterator()
        while (gone.hasNext()) {
            val slot = gone.next()
            if (slot.seen == stamp) continue
            if (slot.leftAt < 0f) slot.leftAt = clock
            if (clock - slot.leftAt > GHOST) gone.remove()
        }
    }

    /** The tales that have just left, as lines to draw once more where they stood (see [draw]). */
    fun ghosts(): List<Line> = slots.entries.filter { it.value.leftAt >= 0f }.map { (id, slot) ->
        Line(id, slot.title, slot.status, slot.tone, null, listOf(), ((clock - slot.leftAt) / GHOST).coerceIn(0f, 1f), slot.index)
    }

    // ---- Drawing a row ----

    /**
     * Draws [line]'s row as it has come to be: its quill at ([x], [y] + 2), its words from [x] + 20 across [room] pixels. The
     * quill pops up (an item has no alpha, so it is only ever scaled), the words fade and slide. Gives how visible the row is, 0 to
     * 1, for the arrow beside it.
     */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, line: Line, x: Int, y: Int, room: Int): Float {
        val slot = slots[line.id] ?: return 0f
        val age = clock - slot.born
        val into = (age / ARRIVE).coerceIn(0f, 1f)
        val arrive = easeOut(into)
        val leave = smooth(line.gone)
        val fade = arrive * (1f - leave)
        if (fade < 0.02f) return 0f
        val ink = slot.ink(clock)
        val left = x + 20 + ((1f - arrive) * -12f + leave * 8f).roundToInt()

        // The light behind the quill: a flare when the row comes, a beat when its status or its ink turns, and a slow glow while the step's days run out
        val cx = x + 8
        val cy = y + 10
        val beat = beat(slot)
        light(graphics, cx, cy, ink, max(0f, 0.7f * (1f - age / 1.6f)) * (1f - leave))
        light(graphics, cx, cy, slot.pulseInk, 0.85f * beat * (1f - leave))
        if (line.tone == TaleRow.RED && line.gone == 0f) light(graphics, cx, cy, RED, (0.2f + 0.12f * sin(clock * 3f)) * arrive)

        // The quill pops up with a little overshoot, swells with each beat, and shrinks away when its tale goes
        val scale = (0.2f + 0.8f * back(into)) * (1f + 0.22f * beat) * (1f - leave)
        if (scale > 0.05f) {
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(cx.toFloat(), cy.toFloat())
            pose.scale(scale, scale)
            graphics.item(QUILL, -8, -8)
            pose.popMatrix()
        }

        graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(line.title, room)), left, y + 1, ARGB.color(fade, Ui.TEXT), false)

        // The count of the contract the step is working, at the end of the status line; it hops and flashes white as it climbs
        var statusRoom = room
        val count = line.count
        if (count != null) {
            val text = "${count.have}/${count.of}"
            val width = font.width(text)
            val lit = 1f - ((clock - slot.counted) / COUNTED).coerceIn(0f, 1f)
            val hop = if (lit > 0.75f) -1 else 0
            graphics.text(font, text, left + room - width, y + 11 + hop, ARGB.color(fade, ARGB.srgbLerp(lit, ink, WHITE)), false)
            statusRoom = room - width - 6
        }

        // The status: the old line rolls up out of its place as the new one rolls up into it
        val roll = ((clock - slot.turned) / ROLL).coerceIn(0f, 1f)
        val rolling = slot.rolling
        if (rolling != null && roll < 1f) {
            val e = easeOut(roll)
            graphics.enableScissor(left - 1, y + 10, left + statusRoom + 2, y + 21)
            status(graphics, font, rolling, left, y + 11 - (6f * e).roundToInt(), statusRoom, slot.rollingInk, fade * (1f - e))
            status(graphics, font, line.status, left, y + 11 + (6f * (1f - e)).roundToInt(), statusRoom, ink, fade * e)
            graphics.disableScissor()
        } else {
            status(graphics, font, line.status, left, y + 11, statusRoom, ink, fade)
        }
        return fade
    }

    private fun status(graphics: GuiGraphicsExtractor, font: Font, text: Component, x: Int, y: Int, room: Int, ink: Int, alpha: Float) {
        if (alpha < 0.02f) return
        graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(text, room)), x, y, ARGB.color(alpha, ink), false)
    }

    /** A soft light of [color] behind the quill at ([cx], [cy]). */
    private fun light(graphics: GuiGraphicsExtractor, cx: Int, cy: Int, color: Int, alpha: Float) {
        if (alpha < 0.02f) return
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.GLOW, cx - 15, cy - 15, 30, 30, ARGB.color(min(alpha, 1f), color))
    }

    /** How strongly the quill is beating, 0 to 1: one swell over [PULSE] seconds after each turn. */
    private fun beat(slot: Slot): Float {
        val k = (clock - slot.pulse) / PULSE
        return if (k <= 0f || k >= 1f) 0f else sin(k * Math.PI.toFloat())
    }

    // ---- Where things sit ----

    private class Slide(var y: Float, var stamp: Int)

    /** Where each row sat last, by its key, and the height the panel was drawn at (-1 while there is no panel). */
    private val slides = HashMap<String, Slide>()
    private var panel = -1f

    /** The height the panel is drawn at: it grows and shrinks toward [target] rather than jumping, and goes when it reaches nothing. */
    fun height(target: Int): Int {
        if (panel < 0f) {
            if (target <= 0) return 0
            panel = target.toFloat()
        } else {
            panel = glide(panel, target.toFloat())
            if (target <= 0 && panel < 0.5f) {
                panel = -1f
                slides.clear()
                return 0
            }
        }
        return panel.roundToInt()
    }

    /** Where the row called [key] sits inside the panel: it glides to [target] when the rows above it come or go. */
    fun slide(key: String, target: Int): Int {
        val slide = slides.getOrPut(key) { Slide(target.toFloat(), stamp) }
        slide.y = glide(slide.y, target.toFloat())
        slide.stamp = stamp
        return slide.y.roundToInt()
    }

    /** After the rows are drawn: the places of rows that were not drawn in this frame are forgotten. */
    fun sweep() {
        slides.values.removeAll { it.stamp != stamp }
    }

    /** [from] part of the way to [to], by the time that has passed: the same pace at any frame rate, settling on it. */
    private fun glide(from: Float, to: Float): Float {
        val next = from + (to - from) * (1f - exp(-dt * 16f))
        return if (abs(next - to) < 0.4f) to else next
    }

    /** Leaving a world: nothing carries over to the next. */
    fun reset() {
        slots.clear()
        slides.clear()
        panel = -1f
        clock = 0f
    }

    // ---- The motions ----

    /** Seconds the quill and the words take to arrive. */
    private const val ARRIVE = 0.5f

    /** Seconds a beat of the quill's light lasts, an ink takes to turn, a status takes to roll, and a count takes to stop flashing. */
    private const val PULSE = 0.7f
    private const val TURN = 0.8f
    private const val ROLL = 0.38f
    private const val COUNTED = 0.6f

    /** Seconds a tale that has left the tracker takes to fade. */
    private const val GHOST = 0.5f

    /** Fast, then settling: how a thing arrives. */
    private fun easeOut(x: Float): Float {
        val k = 1f - x
        return 1f - k * k * k
    }

    /** Slow at both ends. */
    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** Past the mark and back: how the quill settles. */
    private fun back(x: Float): Float {
        val c = 2.2f
        val k = x - 1f
        return 1f + (c + 1f) * k * k * k + c * k * k
    }
}
