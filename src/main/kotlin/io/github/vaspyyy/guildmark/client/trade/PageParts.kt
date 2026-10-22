package io.github.vaspyyy.guildmark.client.trade

import kotlin.math.min
import kotlin.math.sin

/*
 * Small parts the Held, Ledger and Standing pages share: a coin to set after a figure of Marks, the notched edge of a ticket, and the days a thing has left.
 */

/** A little gold coin, 7 across, with its middle at {[cx], [cy]}: set after a figure of Marks. */
fun Pen.coin(cx: Int, cy: Int, a: Float = 1f) {
    disc(cx, cy, 3, Ink.fade(0xFF7A5A14.toInt(), a))
    disc(cx, cy, 2, Ink.fade(0xFFD8AC3C.toInt(), a))
    fill(cx - 1, cy - 1, cx, cy, Ink.fade(0xFFF4DA80.toInt(), a))
    fill(cx, cy, cx + 1, cy + 1, Ink.fade(0xFFA67F20.toInt(), a))
}

/** A figure of Marks as the pages write it: the number, then the coin; ends at [rx]. Gives where it begins. */
fun Pen.marksRight(n: Int, rx: Int, y: Int, color: Int, a: Float = 1f): Int {
    val figure = Words.num(n)
    val x = rx - 9 - width(figure)
    text(figure, x, y, color)
    coin(rx - 4, y + 4, a)
    return x
}

/** The days a ticket has left, counted whole as its notches are: a day begun is a day left. Zero or less when it is due. */
fun daysLeft(ticksLeft: Double): Int = if (ticksLeft <= 0.0) 0 else Math.ceil(ticksLeft / TallyBook.DAY).toInt()

/**
 * The notched edge of a ticket: one notch bitten out for each of [days] still to run (at most seven are drawn, with a plus after them if there are more), right-aligned at [rx], their
 * base on the line [y] + 5. [urgent] makes them throb, for a ticket on its last day.
 */
fun Pen.notches(rx: Int, y: Int, days: Int, t: Double, a: Float = 1f, urgent: Boolean = false) {
    val shown = min(days, NOTCHES)
    val alpha = if (urgent) a * (0.65f + 0.35f * sin(t * 6.0).toFloat()) else a
    for (j in 0 until shown) notch(rx - (shown - j) * 5, y, false, Ink.fade(Ink.WHITE, alpha))
    if (days > NOTCHES) text("+", rx - shown * 5 - 5, y - 1, Ink.fade(Ink.SOFT, a))
}

/** How wide [notches] sets [days] notches: five pixels to each, and a plus after seven. */
fun notchesWidth(days: Int): Int = min(days, NOTCHES) * 5 + (if (days > NOTCHES) 5 else 0)

private const val NOTCHES = 7
