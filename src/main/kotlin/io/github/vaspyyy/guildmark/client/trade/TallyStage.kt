package io.github.vaspyyy.guildmark.client.trade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Tally as an open book on the screen (living-world/trader/design.md, Part 1): the cover and its two sheets, the five leather tabs, the ribbon, and
 * the five pages written on the sheets, the book opening as it comes up and a leaf turning across the spine when the page changes. It draws on a [Pen] and
 * is told what the mouse and keys do, so the game's screen is only a frame round it ([TallyScreen]) and a preview sheet can draw the same book at any moment.
 */
class TallyStage(val book: TallyBook) {
    private val pages: List<TallyPage> = listOf(WordPage(), RoutesPage(), HeldPage(), LedgerPage(), StandingPage())

    /** Where the book's corner was on the last frame, and its tabs and corners (screen px). */
    var left = 0
        private set
    var top = 0
        private set

    private var flipFrom = -1
    private var flipTo = -1
    private var flipAt = -10.0
    private var kickAt = -10.0
    private var lastLeft: Ctx? = null
    private var lastRight: Ctx? = null
    private var tip: List<String>? = null

    /** Whether a leaf is turning at [t]. */
    private fun flipping(t: Double): Boolean = flipTo >= 0 && t - flipAt < FLIP

    // ---- Drawing ----

    /** Draws the book for a screen {[w] x [h]} with the mouse at {[mouseX], [mouseY]}; [book]'s time is the moment it is drawn for. */
    fun draw(pen: Pen, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val t = book.t
        left = (w - Book.W) / 2
        top = (h - Book.H) / 2 - 6
        if (flipTo >= 0 && t - flipAt >= FLIP) {
            flipFrom = -1
            flipTo = -1
        }
        // The book comes up and opens: a little rise and a widening
        val up = Beat.back(Beat.clamp((t / OPEN).toFloat()))
        val grow = 0.82f + 0.18f * up
        val rise = (1f - up) * 16f
        val cx = (left + Book.W / 2).toFloat()
        val cy = (top + Book.H / 2).toFloat()
        val settled = t >= OPEN
        pen.save()
        if (!settled) {
            pen.translate(cx, cy + rise)
            pen.scale(grow, 0.9f + 0.1f * up)
            pen.translate(-cx, -cy)
        }
        val live = settled && !flipping(t)
        tip = null
        tabs(pen, mouseX, mouseY)
        pen.tex(Book.ART, left - Book.SHADOW, top - Book.SHADOW, Book.ART.w / 2, Book.ART.h / 2, 0, 0, Book.ART.w, Book.ART.h)
        sheets(pen, mouseX, mouseY, live)
        Ribbon.draw(pen, left + Book.W / 2, top + 5, top + Book.H - 1, top + Book.H + 17, t, t - kickAt)
        curls(pen, mouseX, mouseY, live)
        pen.restore()
        tip = (lastRight?.tip ?: lastLeft?.tip) ?: tabTip(mouseX, mouseY)
        tip?.let { drawTip(pen, it, mouseX, mouseY, w, h) }
    }

    private fun pageX(side: Int): Int = left + Book.MARGIN + side * (Book.PAGE_W + Book.GAP)

    private fun pageY(): Int = top + Book.MARGIN

    private fun half(pen: Pen, page: Int, side: Int, mouseX: Int, mouseY: Int, live: Boolean): Ctx {
        val ox = pageX(side)
        val oy = pageY()
        pen.save()
        pen.translate(ox.toFloat(), oy.toFloat())
        pen.clip(0, 0, Book.PAGE_W, Book.PAGE_H)
        val c = Ctx(pen, book, side, if (live) mouseX - ox else FAR, if (live) mouseY - oy else FAR)
        if (side == 0) pages[page].left(c) else pages[page].right(c)
        pen.unclip()
        pen.restore()
        return c
    }

    /** The two sheets with the page on them; while a leaf turns, the page it leaves and the page it comes to, and the leaf between. */
    private fun sheets(pen: Pen, mouseX: Int, mouseY: Int, live: Boolean) {
        val t = book.t
        if (!flipping(t)) {
            lastLeft = half(pen, book.page, 0, mouseX, mouseY, live)
            lastRight = half(pen, book.page, 1, mouseX, mouseY, live)
            return
        }
        lastLeft = null
        lastRight = null
        val p = Beat.smooth(((t - flipAt) / FLIP).toFloat())
        val spine = (left + Book.W / 2).toFloat()
        val forward = flipTo > flipFrom
        // What lies under the leaf: the page it leaves on the side it leaves from's opposite, the page it comes to on the side it lands on's opposite
        if (forward) {
            half(pen, flipFrom, 0, mouseX, mouseY, false)
            half(pen, flipTo, 1, mouseX, mouseY, false)
        } else {
            half(pen, flipTo, 0, mouseX, mouseY, false)
            half(pen, flipFrom, 1, mouseX, mouseY, false)
        }
        // The leaf: a page edge-on at the middle of the turn, shaded as it turns, a bright line at its free edge
        val first = p < 0.5f
        val w = if (first) cos(PI.toFloat() * p) else -cos(PI.toFloat() * p)
        val page = if (forward == first) flipFrom else flipTo
        val side = if (forward) (if (first) 1 else 0) else (if (first) 0 else 1)
        pen.save()
        pen.translate(spine, 0f)
        pen.scale(w, 1f)
        pen.translate(-spine, 0f)
        half(pen, page, side, mouseX, mouseY, false)
        pen.restore()
        val dark = ((1f - w) * 150f).toInt().coerceIn(0, 150)
        val sx0: Int
        val sx1: Int
        if (side == 1) {
            sx0 = spine.roundToInt() + Book.GAP / 2
            sx1 = (spine + (Book.PAGE_W + Book.GAP / 2) * w).roundToInt()
        } else {
            sx1 = spine.roundToInt() - Book.GAP / 2
            sx0 = (spine - (Book.PAGE_W + Book.GAP / 2) * w).roundToInt()
        }
        if (sx1 > sx0) {
            pen.fill(sx0, pageY(), sx1, pageY() + Book.PAGE_H, (dark shl 24) or 0x1A1008)
            val edge = if (side == 1) sx1 - 1 else sx0
            pen.fill(edge, pageY(), edge + 1, pageY() + Book.PAGE_H, 0xB0F8F0DC.toInt())
            val shadowFrom = if (side == 1) sx1 else sx0 - 6
            pen.fill(shadowFrom, pageY(), shadowFrom + 6, pageY() + Book.PAGE_H, 0x28000000)
        }
    }

    // ---- The tabs ----

    private fun tabY(i: Int): Int = top + 22 + i * (TAB_H + 5)

    private fun tabX(i: Int): Int = left + Book.W - 3 + (if (i == book.page) 3 else 0)

    private fun tabs(pen: Pen, mouseX: Int, mouseY: Int) {
        for (i in 0 until Book.PAGES) {
            val on = i == book.page
            val x = tabX(i)
            val y = tabY(i)
            val lit = !on && inTab(i, mouseX, mouseY)
            pen.sprite(if (on) "trade/tally_tab_on" else "trade/tally_tab", x + (if (lit) 2 else 0), y, TAB_W, TAB_H)
            pen.sprite("trade/tally_ico_${Book.NAMES[i]}", x + 5 + (if (lit) 2 else 0), y + 7, 10, 10, if (on || lit) Pen.WHITE else 0xFFC0B8A8.toInt())
        }
    }

    private fun inTab(i: Int, x: Int, y: Int): Boolean = x >= left + Book.W - 3 && x < left + Book.W - 3 + TAB_W + 3 && y >= tabY(i) && y < tabY(i) + TAB_H

    private fun tabTip(x: Int, y: Int): List<String>? {
        for (i in 0 until Book.PAGES) if (inTab(i, x, y)) return listOf(Words.t("page.${Book.NAMES[i]}"), Words.t("page.key", i + 1))
        return null
    }

    // ---- The corners ----

    private fun curls(pen: Pen, mouseX: Int, mouseY: Int, live: Boolean) {
        if (book.page > 0) {
            val x = pageX(0)
            val y = pageY() + Book.PAGE_H - 12
            val hot = live && mouseX >= x && mouseX < x + 14 && mouseY >= y - 2 && mouseY < y + 14
            pen.sprite("trade/tally_curl_0", x, y, 12, 12, if (hot) Pen.WHITE else Ink.fade(Ink.WHITE, 0.8f))
        }
        if (book.page < Book.PAGES - 1) {
            val x = pageX(1) + Book.PAGE_W - 12
            val y = pageY() + Book.PAGE_H - 12
            val hot = live && mouseX >= x - 2 && mouseX < x + 12 && mouseY >= y - 2 && mouseY < y + 14
            pen.sprite("trade/tally_curl_1", x, y, 12, 12, if (hot) Pen.WHITE else Ink.fade(Ink.WHITE, 0.8f))
        }
    }

    private fun drawTip(pen: Pen, lines: List<String>, mx: Int, my: Int, w: Int, h: Int) {
        if (lines.isEmpty()) return
        var tw = 0
        for (line in lines) tw = max(tw, pen.width(line))
        val bw = tw + 12
        val bh = lines.size * 10 + 6
        var x = mx + 10
        var y = my + 10
        if (x + bw > w - 2) x = mx - bw - 6
        if (y + bh > h - 2) y = my - bh - 6
        x = max(2, x)
        y = max(2, y)
        pen.fill(x + 1, y + bh, x + bw + 1, y + bh + 1, 0x50000000)
        pen.fill(x, y, x + bw, y + bh, Ink.NOTE)
        pen.frame(x, y, bw, bh, Ink.SOFT)
        for ((i, line) in lines.withIndex()) pen.text(line, x + 6, y + 4 + i * 10, if (i == 0) Ink.BLACK else Ink.SOFT)
    }

    // ---- Input ----

    /** A click at {[x], [y]}; gives whether the book used it. */
    fun click(x: Int, y: Int, button: Int): Boolean {
        if (button != 0) return false
        for (i in 0 until Book.PAGES) {
            if (inTab(i, x, y)) {
                turnTo(i)
                return true
            }
        }
        if (book.t < OPEN || flipping(book.t)) return false
        if (book.page > 0 && x >= pageX(0) && x < pageX(0) + 14 && y >= pageY() + Book.PAGE_H - 14 && y < pageY() + Book.PAGE_H + 2) {
            turnTo(book.page - 1)
            return true
        }
        if (book.page < Book.PAGES - 1 && x >= pageX(1) + Book.PAGE_W - 14 && x < pageX(1) + Book.PAGE_W && y >= pageY() + Book.PAGE_H - 14 && y < pageY() + Book.PAGE_H + 2) {
            turnTo(book.page + 1)
            return true
        }
        val l = lastLeft
        val r = lastRight
        if (l != null && x >= pageX(0) && x < pageX(0) + Book.PAGE_W && l.click(x - pageX(0), y - pageY())) return true
        if (r != null && x >= pageX(1) && x < pageX(1) + Book.PAGE_W && r.click(x - pageX(1), y - pageY())) return true
        return false
    }

    /** Turns the leaf to page [to]. */
    fun turnTo(to: Int) {
        val next = to.coerceIn(0, Book.PAGES - 1)
        if (next == book.page) return
        val t = book.t
        flipFrom = if (flipping(t)) flipTo else book.page
        flipTo = next
        flipAt = t
        kickAt = t
        book.turn(next, t + FLIP * 0.5)
        book.env.sound("trade.tally_page", 0.95f + 0.1f * Beat.hash((t * 997).toInt()), 0.7f)
    }

    /** A key (a GLFW key code) pressed; gives whether the book used it. */
    fun key(code: Int): Boolean {
        when (code) {
            Keys.RIGHT, Keys.PAGE_DOWN -> if (book.page < Book.PAGES - 1) turnTo(book.page + 1) else return false
            Keys.LEFT, Keys.PAGE_UP -> if (book.page > 0) turnTo(book.page - 1) else return false
            Keys.HOME -> turnTo(0)
            Keys.END -> turnTo(Book.PAGES - 1)
            in Keys.ONE..Keys.FIVE -> turnTo(code - Keys.ONE)
            else -> return pages[book.page].key(book, code)
        }
        return true
    }

    /** The wheel turned: the page's own (a list scrolls), else a leaf. */
    fun wheel(up: Boolean): Boolean {
        if (pages[book.page].wheel(book, up)) return true
        return false
    }

    companion object {
        /** Seconds the book takes to come up, and a leaf to turn. */
        const val OPEN = 0.45
        const val FLIP = 0.55

        const val TAB_W = 16
        const val TAB_H = 24

        /** A mouse position far off every page. */
        const val FAR = -10000
    }
}

/** The GLFW key codes the book answers to (the game's own `InputConstants` has the same numbers). */
object Keys {
    const val ONE = 49
    const val FIVE = 53
    const val RIGHT = 262
    const val LEFT = 263
    const val DOWN = 264
    const val UP = 265
    const val PAGE_UP = 266
    const val PAGE_DOWN = 267
    const val HOME = 268
    const val END = 269
}
