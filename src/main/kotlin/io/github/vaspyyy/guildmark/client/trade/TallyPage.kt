package io.github.vaspyyy.guildmark.client.trade

import kotlin.math.max

/**
 * One half of a page of the book as it is being drawn: the [pen] (its origin at the half's upper left corner, {[Book.PAGE_W] x [Book.PAGE_H]} of paper),
 * the [book] it is of, which [side] it is (0 the left, 1 the right) and where the mouse is on it ([mx], [my]; far off the page if it is not over it).
 * The page marks the places the mouse can act on as it draws them ([hot]), and the book answers a click from the places marked in the last frame.
 */
class Ctx(val pen: Pen, val book: TallyBook, val side: Int, private val mx: Int, private val my: Int) {
    /** Something the mouse can act on, with what hovering it says. */
    class Zone(val x: Int, val y: Int, val w: Int, val h: Int, val tip: List<String>?, val click: (() -> Unit)?)

    val zones = ArrayList<Zone>()

    /** What the mouse is over says, if it says anything: the last place marked under it. */
    var tip: List<String>? = null
        private set

    /** Seconds since the book opened, and since this page was turned to. */
    val t: Double get() = book.t
    val since: Double get() = max(0.0, book.t - book.pageAt)

    /** Whether the mouse is over the box. */
    fun over(x: Int, y: Int, w: Int, h: Int): Boolean = mx >= x && mx < x + w && my >= y && my < y + h

    /** Marks the box as a place the mouse acts on ([click]) and tells what it says ([tip]); gives whether the mouse is over it. */
    fun hot(x: Int, y: Int, w: Int, h: Int, tip: List<String>? = null, click: (() -> Unit)? = null): Boolean {
        val hit = over(x, y, w, h)
        if (tip != null || click != null) zones.add(Zone(x, y, w, h, tip, click))
        if (hit && tip != null) this.tip = tip
        return hit
    }

    /** Whether the mouse is over the page at all, and where. */
    val mouseX: Int get() = mx
    val mouseY: Int get() = my

    /** Does what the last place marked under [x], [y] does; gives whether there was one. */
    fun click(x: Int, y: Int): Boolean {
        for (zone in zones.asReversed()) {
            val act = zone.click ?: continue
            if (x >= zone.x && x < zone.x + zone.w && y >= zone.y && y < zone.y + zone.h) {
                act()
                return true
            }
        }
        return false
    }

    fun sound(name: String, pitch: Float = 1f, volume: Float = 0.7f) = book.env.sound(name, pitch, volume)
}

/**
 * A page of the Tally (living-world/trader/design.md, Part 1: Word, Routes, Held, Ledger, Standing): the two halves of the spread it fills, each drawn
 * when the book is open at it. A page keeps nothing of its own: what it shows is the [TallyBook]'s view and picks, and what it picks goes back to the book.
 */
abstract class TallyPage(val key: String) {
    abstract fun left(c: Ctx)

    abstract fun right(c: Ctx)

    /** A key pressed while the book is open at this page (an arrow moves the pick, say); gives whether it was used. */
    open fun key(book: TallyBook, code: Int): Boolean = false

    /** The wheel turned over the page, up ([up]) or down; gives whether it was used. */
    open fun wheel(book: TallyBook, up: Boolean): Boolean = false
}
