package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.registry.ModSounds
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the talk screens are built on, so that talking with a named character ([DialogueScreen]) and with a townsperson
 * ([TalkScreen]) look and behave alike. The world stays in view under a shade, the camera settles over the player's
 * shoulder for as long as the screen is open (see Cutscenes.talk), and a panel rises at the foot of the screen with the
 * speaker's name on a plate across its top edge and what they are beside it. Their words type out a few letters a
 * tick, with a breath after each sentence and a shorter one after a comma, and the tick of a pen when no one's saying
 * them aloud. The answers come up in a box above the panel's right end, dimmed once asked: Enter, Space or a click
 * moves the talk on, and the arrow keys, W and S, the wheel, the mouse or the number keys pick an answer. A blow, or
 * the speaker more than 12 blocks off, ends the talk.
 *
 * Each screen says what's said and what the answers are ([rows], [advance], [pick]) and runs its own tick; the kit
 * draws and handles the input.
 */
abstract class DialogueKit(title: Component) : Screen(title) {
    /** Who's talking: an entity id, or -1 for nobody. */
    abstract val entityId: Int

    /** One of the answers as the box shows it: its label, and whether it's dimmed (asked already). */
    protected class Row(val label: Component, val dim: Boolean)

    /** Where the answers' box is on screen. */
    protected class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** The answers as they stand. */
    protected abstract fun rows(): List<Row>

    /** Enter, Space or a click while the speaker is talking: the rest of the words, or on with the talk. */
    protected abstract fun advance()

    /** The answer at [index] was picked. */
    protected abstract fun pick(index: Int)

    /** The pitch of the pen's tick for this speaker. */
    protected abstract val blipPitch: Float

    /** Ticks since the screen opened. */
    protected var ticks = 0

    /** Letters of the words typing now shown so far (a line break counts as one), and ticks before the next. */
    protected var shown = 0f
    protected var wait = 0

    /** The answer that's lit, which Enter picks. */
    protected var selected = 0

    /** When the answers came up, or -1 while the speaker is still talking. */
    protected var choicesAt = -1

    /**
     * Answers can't be picked until the key or button used to skip to them has been let go (held keys
     * repeat), so skipping fast through the last line can't pick one by accident.
     */
    protected var armed = false

    /** The player's hurt flash last tick, to notice a new hit. */
    private var lastHurt = -1

    /** Where the speech panel sits (its left, top and width), and how wide its words may run. */
    protected var panelX = 0
    protected var panelY = 0
    protected var panelWidth = 0
    protected val textWidth get() = panelWidth - PAD * 2 - 10

    /** Lays the panel out across the foot of the screen. */
    override fun init() {
        panelWidth = min(width - 24, 400)
        panelX = (width - panelWidth) / 2
        panelY = height - PANEL_HEIGHT - 10
    }

    /** The camera settles over the player's shoulder, facing the speaker. */
    override fun added() {
        minecraft.level?.getEntity(entityId)?.let { Cutscenes.talk(it, this) }
    }

    // ---- Every tick ----

    /** Whether [player] has just been struck (poison, hunger or fire don't count); asked once a tick. */
    protected fun struck(player: LocalPlayer?): Boolean {
        val blow = player?.getLastDamageSource(2)
        val struck = player != null && lastHurt >= 0 && player.hurtTime > lastHurt && blow != null && (blow.entity != null || blow.directEntity != null)
        lastHurt = player?.hurtTime ?: 0
        return struck
    }

    /** The answers can be picked once they've been up a moment and the key or button that skipped to them is let go. */
    protected fun arm() {
        if (choicesAt >= 0 && !armed && ticks - choicesAt >= CHOICE_GUARD && !skipHeld()) armed = true
    }

    private fun skipHeld(): Boolean =
        InputConstants.isKeyDown(InputConstants.KEY_RETURN) || InputConstants.isKeyDown(InputConstants.KEY_SPACE) ||
            InputConstants.isKeyDown(InputConstants.KEY_NUMPADENTER) || minecraft.mouseHandler.isLeftPressed

    /**
     * One tick of the pen across [text]: [SPEED] more letters, a breath after each sentence and a shorter one after a
     * comma, and, when [ticking] (no one's saying it aloud), the pen's tick every few letters. True once it's all shown
     * and the pen isn't resting.
     */
    protected fun type(text: String, ticking: Boolean): Boolean {
        if (wait > 0) {
            wait--
            return false
        }
        if (shown >= text.length) return true
        val from = shown.toInt()
        shown = min(text.length.toFloat(), shown + SPEED)
        // The letters just shown: a tick of the pen for every few (when no one's speaking), a breath after each sentence
        for (i in from until shown.toInt()) {
            val ch = text[i]
            if (ticking && i % 4 == 0 && !ch.isWhitespace()) blip()
            val next = text.getOrNull(i + 1)
            if (next != null && !next.isWhitespace()) continue
            val pause = when (ch) {
                '.', '!', '?' -> 6
                ',', ';', ':' -> 2
                else -> 0
            }
            if (pause > 0 && next != null) {
                shown = (i + 1).toFloat()
                wait = pause
                break
            }
        }
        return false
    }

    /** Lights the answer [by] rows on, round from the last to the first. */
    protected fun move(by: Int) {
        val count = rows().size
        if (count == 0) return
        selected = Math.floorMod(selected + by, count)
        Ui.hover()
    }

    // ---- Sound ----

    protected fun blip() = Ui.play(ModSounds.UI_TEXT.get(), blipPitch + (Math.random().toFloat() - 0.5f) * 0.08f, 0.2f)

    // ---- Input ----

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isEscape()) return super.keyPressed(event)
        val key = event.key()
        if (choicesAt < 0) {
            if (!event.isSelection()) return super.keyPressed(event)
            advance()
            return true
        }
        when {
            event.isUp() || key == InputConstants.KEY_W -> move(-1)
            event.isDown() || key == InputConstants.KEY_S -> move(1)
            event.isSelection() -> if (armed) pick(selected)
            key in InputConstants.KEY_1..InputConstants.KEY_9 -> if (armed) pick(key - InputConstants.KEY_1)
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        if (choicesAt < 0) advance()
        else if (armed) rowAt(event.x(), event.y()).takeIf { it >= 0 }?.let(::pick)
        return true
    }

    override fun mouseMoved(x: Double, y: Double) {
        if (choicesAt < 0) return
        val row = rowAt(x, y)
        if (row >= 0 && row != selected) {
            selected = row
            Ui.hover()
        }
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (choicesAt < 0 || scrollY == 0.0) return false
        move(if (scrollY > 0) -1 else 1)
        return true
    }

    // ---- Layout ----

    /** The answers, in a panel above the right end of the speech panel. */
    protected fun choiceBox(): Box {
        val rows = rows()
        val w = max(150, (rows.maxOfOrNull { font.width(it.label) } ?: 0) + 34).coerceAtMost(panelWidth)
        val h = rows.size * ROW + 10
        return Box(panelX + panelWidth - w, panelY - 12 - h, w, h)
    }

    /** The answer under ([x], [y]), or -1. */
    protected fun rowAt(x: Double, y: Double): Int {
        val box = choiceBox()
        if (x < box.x + 4 || x >= box.x + box.w - 4 || y < box.y + 5) return -1
        val row = ((y - box.y - 5) / ROW).toInt()
        return if (row in rows().indices) row else -1
    }

    // ---- Drawing ----

    /** How much of the screen is there (1 once it's up): a screen fading away as it goes gives less. */
    protected open fun presence(a: Float): Float = 1f

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // The world stays in view: only a shade rising behind the panel and a thin one from the top
        val k = min(1f, (ticks + a) / 8f) * presence(a)
        graphics.fillGradient(0, height * 11 / 20, width, height, 0, ARGB.color((170 * k).toInt(), 0, 0, 0))
        graphics.fillGradient(0, 0, width, 36, ARGB.color((100 * k).toInt(), 0, 0, 0), 0)
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    /** The speech panel, [rise] of the way up into place (0 to 1); returns where its top is. */
    protected fun drawPanel(graphics: GuiGraphicsExtractor, rise: Float): Int {
        val y = panelY + ((1f - rise) * 14f).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, panelX, y, panelWidth, PANEL_HEIGHT, ARGB.white(rise))
        return y
    }

    /**
     * Their [name] on a plate across the top edge of the panel whose top is at [y], and what they're known as
     * ([epithet]) beside it, cut short of [right]; the plate comes in with [rise], the words with [fade].
     */
    protected fun drawPlate(graphics: GuiGraphicsExtractor, name: Component, epithet: Component, y: Int, rise: Float, right: Int, fade: Float = 1f) {
        val heading = Ui.heading(name)
        val plateWidth = font.width(heading) + 20
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.NAME_PLATE, panelX + 10, y - 7, plateWidth, 15, ARGB.white(rise))
        if (fade < 0.05f) return
        graphics.text(font, heading, panelX + 20, y - 4 + Ui.HEADING_RISE, faded(Ui.GOLD_BRIGHT, fade), true)
        val epithetX = panelX + 10 + plateWidth + 8
        val room = right - epithetX
        if (room > 30) {
            val shownText = font.substrByWidth(epithet, room)
            graphics.text(font, Language.getInstance().getVisualOrder(shownText), epithetX, y + 9, faded(Ui.TEXT_FAINT, fade), false)
        }
    }

    /** The first [letters] letters of [rows] (a line break counting as one), a line each from [y] down, in [color]. */
    protected fun drawTyped(graphics: GuiGraphicsExtractor, rows: List<String>, letters: Int, x: Int, y: Int, color: Int) {
        var left = letters
        for ((i, row) in rows.withIndex()) {
            if (left <= 0) break
            graphics.text(font, if (left >= row.length) row else row.substring(0, left), x, y + i * LINE, color, false)
            left -= row.length + 1
        }
    }

    /** More to come: a diamond bobbing in the corner of the panel whose top is at [y]. */
    protected fun drawMore(graphics: GuiGraphicsExtractor, y: Int, t: Float) {
        val bob = (sin(t * 0.25f) * 1.5f).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND_LARGE, panelX + panelWidth - PAD - 4, y + PANEL_HEIGHT - 16 + bob, 7, 7)
    }

    /**
     * The [answers] in [box], come in [k] of the way (the box sliding in from the right as it fades in), the one
     * at [lit] lit. [rowIn], when given, says how far each row has come in by itself, its words fading and sliding in
     * after the box's; without it they're all there at once.
     */
    protected fun drawChoices(graphics: GuiGraphicsExtractor, box: Box, k: Float, answers: List<Row> = rows(), lit: Int = selected, rowIn: ((Int) -> Float)? = null) {
        val x = box.x + ((1f - k) * 12f).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, x, box.y, box.w, box.h, ARGB.white(k))
        for ((i, choice) in answers.withIndex()) {
            val come = rowIn?.invoke(i)
            if (come != null && come < 0.05f) continue
            val rowX = if (come == null) x else x + ((1f - come) * 8f).roundToInt()
            val rowY = box.y + 5 + i * ROW
            val isLit = i == lit
            if (isLit) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x + 4, rowY, box.w - 8, ROW, ARGB.white(k))
            val color = when {
                isLit -> Ui.GOLD_BRIGHT
                choice.dim -> Ui.TEXT_FAINT
                else -> Ui.TEXT
            }
            val number = if (isLit) Ui.GOLD else Ui.TEXT_FAINT
            graphics.text(font, "${i + 1}", rowX + 10, rowY + 4, if (come == null) number else faded(number, come), false)
            graphics.text(font, choice.label, rowX + 22, rowY + 4, if (come == null) color else faded(color, come), false)
        }
    }

    /** [color] at [k] of its strength (as it was when [k] is 1). */
    protected fun faded(color: Int, k: Float): Int = if (k >= 1f) color else ARGB.multiplyAlpha(color, k.coerceAtLeast(0f))

    /** [k] (0 to 1) eased: quick at first, settling into place. */
    protected fun ease(k: Float): Float = 1f - (1f - k) * (1f - k) * (1f - k)

    /** The world goes on while they talk. */
    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    companion object {
        /** The panel's inner margin, a line's height, and the lines it holds. */
        const val PAD = 14
        const val LINE = 11
        const val MAX_LINES = 4
        const val TEXT_TOP = 23
        const val PANEL_HEIGHT = TEXT_TOP + MAX_LINES * LINE + 9
        const val ROW = 16

        /** Letters shown each tick. */
        const val SPEED = 2.2f

        /** Ticks after the answers come up before one can be picked. */
        const val CHOICE_GUARD = 6

        /** How far off the speaker may be before the talk is over. */
        const val REACH = 12.0
    }
}
