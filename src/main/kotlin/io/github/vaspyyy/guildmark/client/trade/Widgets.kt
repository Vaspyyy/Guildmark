package io.github.vaspyyy.guildmark.client.trade

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The small pictures and the few controls the Tally's pages are made of, each drawn from a time so that it can be had at any moment: the trade's chips,
 * badges, pennants, hourglass and seals out of X1's one atlas ([HaulBoardArt]), the notch and the knife that cuts it, a button, a slip of paper to lay
 * a row on, a page's heading. The pages put them together; they hold no state.
 */
val ATLAS = Tex(HaulBoardArt.GUI_ATLAS, HaulBoardArt.ATLAS_W, HaulBoardArt.ATLAS_H)

private val GOOD_AT: Map<String, Int> = HaulBoardArt.GOODS.withIndex().associate { it.value to it.index }
private val EVENT_AT: Map<String, Int> = HaulBoardArt.EVENTS.withIndex().associate { it.value to it.index }

/** Whether the atlas has a chip for the good [id]. */
fun hasChip(id: String): Boolean = GOOD_AT.containsKey(id)

private fun Pen.cell(cell: IntArray, x: Int, y: Int, w: Int, h: Int, color: Int = Pen.WHITE) =
    tex(ATLAS, x, y, w, h, cell[0], cell[1], cell[2], cell[3], color)

/** The chip of the good [id] at {[x], [y]}, 16 by 16: a dashed red ring for what a town is short of ([short]), a solid green one for what it has too much of. */
fun Pen.chip(id: String, short: Boolean, x: Int, y: Int, color: Int = Pen.WHITE) {
    val i = GOOD_AT[id] ?: return
    cell(if (short) HaulBoardArt.CHIP_RED[i] else HaulBoardArt.CHIP_GREEN[i], x, y, HaulBoardArt.CHIP_PX, HaulBoardArt.CHIP_PX, color)
}

/** The badge of a market's event [kind] ("blight", "fever"...) at {[x], [y]}, 16 by 16; false if there is none for it. */
fun Pen.badge(kind: String, x: Int, y: Int, color: Int = Pen.WHITE): Boolean {
    val i = EVENT_AT[kind] ?: return false
    cell(HaulBoardArt.EVENT[i], x, y, HaulBoardArt.CHIP_PX, HaulBoardArt.CHIP_PX, color)
    return true
}

/** Whether an event [kind] is good news (a bumper harvest, a good catch). */
fun goodNews(kind: String): Boolean = HaulBoardArt.GOOD_EVENTS.contains(kind) || kind == "feast"

/**
 * A road's pennant, 10 by 7, flown from {[x], [y]} (its hoist, on the left): [stage] 0 open (green) to 3 closed (black), the cloth rippling on a
 * wave that runs from the hoist to the fly, each column its own height. [t] is the time in seconds.
 */
fun Pen.pennant(x: Int, y: Int, stage: Int, t: Double, calm: Boolean = false) {
    val cell = HaulBoardArt.PENNANT[stage.coerceIn(0, 3)]
    val cols = cell[2] / 2
    for (i in 0 until cols) {
        val amp = i / (cols - 1f)
        val lift = if (calm) 0 else (sin(t * 5.2 - i * 0.9) * 1.1 * amp).roundToInt()
        tex(ATLAS, x + i, y + lift, 1, cell[3] / 2, cell[0] + i * 2, cell[1], 2, cell[3])
    }
}

/** A pole under a [pennant]: 2 by [h] px, its top at {[x], [y]}. */
fun Pen.pole(x: Int, y: Int, h: Int) {
    fill(x, y, x + 1, y + h, 0xFF4A3020.toInt())
    fill(x + 1, y, x + 2, y + h, 0xFF2A1A10.toInt())
}

/**
 * The hourglass at a texel to a pixel (16 by 28): the empty glass in its frame, with [upper] (0 to 1) of the sand of the upper bulb still in it and [lower] of
 * the lower's, and, while there is sand above and room below, the thread of it falling (its frame from [t]).
 */
fun Pen.hourglass(x: Int, y: Int, upper: Float, lower: Float, t: Double) {
    val neck = HaulBoardArt.HOURGLASS_NECK_Y
    val w = HaulBoardArt.HOURGLASS_W
    cell(HaulBoardArt.HOURGLASS_FRAME, x, y, w, HaulBoardArt.HOURGLASS_H)
    // The upper bulb's sand lies in its bottom: its top rows are cut away as it runs out
    val upperRows = neck - HaulBoardArt.HOURGLASS_SAND_TOP
    val up = (upperRows * upper.coerceIn(0f, 1f)).roundToInt()
    if (up > 0) {
        val c = HaulBoardArt.HOURGLASS_SAND_UPPER
        tex(ATLAS, x, y + neck - up, w, up, c[0], c[1] + neck - up, w, up)
    }
    // The lower bulb's heap grows from its floor
    val lowerRows = HaulBoardArt.HOURGLASS_SAND_BOTTOM + 1 - (neck + 1)
    val low = (lowerRows * lower.coerceIn(0f, 1f)).roundToInt()
    if (low > 0) {
        val c = HaulBoardArt.HOURGLASS_SAND_LOWER
        val top = HaulBoardArt.HOURGLASS_SAND_BOTTOM + 1 - low
        tex(ATLAS, x, y + top, w, low, c[0], c[1] + top, w, low)
    }
    if (up > 0 && low < lowerRows) {
        val c = HaulBoardArt.HOURGLASS_STREAM[((t * 9.0).toInt() and 0x7fffffff) % HaulBoardArt.HOURGLASS_STREAM_FRAMES]
        val from = neck
        val heap = HaulBoardArt.HOURGLASS_SAND_BOTTOM + 1 - low
        val h = (heap - from).coerceIn(0, c[3])
        if (h > 0) tex(ATLAS, x + HaulBoardArt.HOURGLASS_STREAM_X, y + from, c[2], h, c[0], c[1], c[2], h)
    }
}

/** The seal numbered [i] (0 to 5: red, blue, green, amber, violet, teal), 12 by 12. */
fun Pen.seal(i: Int, x: Int, y: Int, color: Int = Pen.WHITE) =
    sprite(HaulBoardArt.SEAL_SPRITES[i.coerceIn(0, HaulBoardArt.SEAL_SPRITES.size - 1)], x, y, HaulBoardArt.SEAL_PX, HaulBoardArt.SEAL_PX, color)

/** A notch of a ticket's edge, 4 by 5: a V bitten out for a day still to run, filled and struck across for a day gone. */
fun Pen.notch(x: Int, y: Int, spent: Boolean, color: Int = Pen.WHITE) =
    sprite(if (spent) HaulBoardArt.NOTCH_SPENT_SPRITE else HaulBoardArt.NOTCH_SPRITE, x, y, HaulBoardArt.NOTCH_PX_W, HaulBoardArt.NOTCH_PX_H, color)

/** The quill of X1's atlas, 8 by 8, a clerk's mark for a report. */
fun Pen.quill(x: Int, y: Int, color: Int = Pen.WHITE) = cell(HaulBoardArt.QUILL, x, y, 8, 8, color)

/** The knife that cuts a notch: its blade to the left, turned [angle] radians about its point at {[x], [y]}. */
fun Pen.knife(x: Int, y: Int, angle: Float) {
    save()
    translate(x.toFloat(), y.toFloat())
    rotate(angle)
    sprite("trade/tally_knife", -1, -5, 16, 8)
    restore()
}

/** A slip of paper for a row to be written on: X1's ticket row (a nine-slice), plain, [kind] 1 for a relief ticket's and 2 for a feast's, tinted by [color] (to dim it, or lift it under the mouse). */
fun Pen.slip(x: Int, y: Int, w: Int, h: Int, kind: Int = 0, color: Int = Pen.WHITE) =
    sprite(when (kind) { 1 -> HaulBoardArt.TICKET_ROW_RELIEF; 2 -> HaulBoardArt.TICKET_ROW_FEAST; else -> HaulBoardArt.TICKET_ROW }, x, y, w, h, color)

/**
 * A button of the book's own make: a slip of paper inked round, its words in the middle. [lit] is the mouse over it, [down] the mouse held on it; a button that
 * is [on] false is pencilled in and does nothing. Gives whether the mouse is over it, for the page to mark.
 */
fun Pen.button(x: Int, y: Int, w: Int, h: Int, label: String, on: Boolean, lit: Boolean, down: Boolean = false, accent: Int = Ink.RED) {
    val dy = if (down) 1 else 0
    if (on) {
        fill(x + 1, y + h, x + w + 1, y + h + 1, 0x40200C04)
        fill(x + w, y + 1, x + w + 1, y + h, 0x40200C04)
    }
    val paper = if (!on) 0xFFD9CBA4.toInt() else if (lit) 0xFFFBF3DC.toInt() else 0xFFF1E4C2.toInt()
    fill(x + 1, y + dy, x + w - 1, y + h + dy, paper)
    fill(x, y + 1 + dy, x + w, y + h - 1 + dy, paper)
    val rim = if (!on) Ink.PENCIL else if (lit) accent else Ink.SOFT
    fill(x + 1, y + dy, x + w - 1, y + 1 + dy, rim)
    fill(x + 1, y + h - 1 + dy, x + w - 1, y + h + dy, rim)
    fill(x, y + 1 + dy, x + 1, y + h - 1 + dy, rim)
    fill(x + w - 1, y + 1 + dy, x + w, y + h - 1 + dy, rim)
    val ink = if (!on) Ink.PENCIL else if (lit) accent else Ink.BLACK
    val tw = width(label)
    text(label, x + (w - tw) / 2, y + (h - 8) / 2 + dy, ink)
    if (on && lit) fill(x + (w - tw) / 2, y + (h - 8) / 2 + dy + 9, x + (w + tw) / 2, y + (h - 8) / 2 + dy + 10, Ink.fade(accent, 0.7f))
}

/**
 * A page's heading: the [text] in carved capitals at {[x], [y]} in the map's ink, with the hand-inked flourish under it and a ruled line running on from it to
 * [x1]. Gives where the page's matter may begin.
 */
fun Pen.title(text: String, x: Int, y: Int, x1: Int, seed: Int = 0): Int {
    heading(text, x, y, Ink.BLACK)
    val span = max(64, headingWidth(text) + 6)
    sprite("trade/tally_flourish", x - 2, y + 12, span, 6, Ink.fade(Ink.WHITE, 0.92f))
    if (x1 > x + span) stroke((x + span - 2).toFloat(), (y + 15).toFloat(), x1.toFloat(), (y + 15).toFloat(), Ink.fade(Ink.SOFT, 0.5f), 0.4f, seed)
    return y + 24
}

/** Ease [t] seconds into a 0 to 1 over [dur], for something that comes in. */
fun comes(t: Double, dur: Double = 0.35): Float = Beat.out(Beat.clamp((t / dur).toFloat()))

/** A figure that counts up to [value] over [dur] seconds from [at]: the page's own numbers roll to what they are. */
fun rolls(value: Int, since: Double, at: Double = 0.1, dur: Double = 0.6): Int =
    (value * Beat.out(Beat.clamp(((since - at) / dur).toFloat()))).roundToInt()

internal const val TAU = (2.0 * PI).toFloat()
