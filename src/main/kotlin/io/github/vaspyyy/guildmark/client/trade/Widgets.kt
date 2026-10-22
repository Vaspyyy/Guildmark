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
fun Pen.pennant(x: Int, y: Int, stage: Int, t: Double, calm: Boolean = false, flip: Boolean = false) {
    val cell = HaulBoardArt.PENNANT[stage.coerceIn(0, 3)]
    val cols = cell[2] / 2
    for (i in 0 until cols) {
        val amp = i / (cols - 1f)
        val lift = if (calm) 0 else (sin(t * 5.2 - i * 0.9) * 1.1 * amp).roundToInt()
        // [flip]: the hoist is on the right, the cloth flown to the left
        tex(ATLAS, x + (if (flip) cols - 1 - i else i), y + lift, 1, cell[3] / 2, cell[0] + i * 2, cell[1], 2, cell[3])
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
fun Pen.hourglass(x: Int, y: Int, upper: Float, lower: Float, t: Double, stream: Boolean = true) {
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
    if (stream && up > 0 && low < lowerRows) {
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

/** [text] with a halo of paper round it, to be read over lines and rings. */
fun Pen.halo(text: String, x: Int, y: Int, color: Int, italic: Boolean = false, halo: Int = 0xD0F4EAD0.toInt()) {
    text(text, x + 1, y, halo, false, italic)
    text(text, x - 1, y, halo, false, italic)
    text(text, x, y + 1, halo, false, italic)
    text(text, x, y - 1, halo, false, italic)
    text(text, x, y, color, false, italic)
}

/**
 * What the age of a sight is inked as: a fresh one in a thick, dark, whole ring; an older one thinner, paler and broken in more places the more the hours run, as the sand
 * of its glass does; at three days ([struck]) it is struck through.
 */
class AgeInk(val width: Int, val alpha: Float, val dashes: Float, val struck: Boolean)

fun ageInk(hours: Double, live: Boolean): AgeInk {
    if (live) return AgeInk(2, 1f, 0f, false)
    val f = (hours / 72.0).toFloat().coerceIn(0f, 1f)
    return AgeInk(if (hours < 12.0) 2 else 1, 1f - 0.55f * f, if (hours < 12.0) 0f else 0.14f + 0.36f * f, hours >= 72.0)
}

/** A ring round a town inked to [hours] of age (see [ageInk]), [r] its radius, [sweep] how much of it the pen has drawn so far; [thin] for a small one. Gives where the pen is. */
fun Pen.ageRing(cx: Float, cy: Float, r: Float, hours: Double, live: Boolean, color: Int, sweep: Float = 1f, seed: Int = 0, thin: Boolean = false): FloatArray {
    val ink = ageInk(hours, live)
    return ring(cx, cy, r, Ink.fade(color, ink.alpha), if (thin) 1 else ink.width, sweep, ink.dashes, seed, if (thin) 0.15f else 0.35f)
}

/** Ease [t] seconds into a 0 to 1 over [dur], for something that comes in. */
fun comes(t: Double, dur: Double = 0.35): Float = Beat.out(Beat.clamp((t / dur).toFloat()))

/** A figure that counts up to [value] over [dur] seconds from [at]: the page's own numbers roll to what they are. */
fun rolls(value: Int, since: Double, at: Double = 0.1, dur: Double = 0.6): Int =
    (value * Beat.out(Beat.clamp(((since - at) / dur).toFloat()))).roundToInt()

internal const val TAU = (2.0 * PI).toFloat()

/** The good [id] drawn [size] pixels square at {[x], [y]} (its item picture is sixteen). */
fun Pen.goodAt(id: String, x: Int, y: Int, size: Int = 16) {
    if (size == 16) {
        good(id, x, y)
        return
    }
    save()
    translate(x.toFloat(), y.toFloat())
    val s = size / 16f
    scale(s, s)
    good(id, 0, 0)
    restore()
}

/** A tick inked by hand, a short stroke down and a long one up, in a box 10 across at {[x], [y]}; [p] (0 to 1) is how much of it the pen has done. */
fun Pen.tick(x: Int, y: Int, p: Float, color: Int) {
    val a = Beat.clamp(p / 0.35f)
    val b = Beat.clamp((p - 0.35f) / 0.65f)
    if (a > 0f) stroke(x + 0.5f, y + 4.5f, x + 0.5f + 2.5f * a, y + 4.5f + 2.5f * a, color, 0.1f, 31, 2)
    if (b > 0f) stroke(x + 3f, y + 7f, x + 3f + 6f * b, y + 7f - 8f * b, color, 0.1f, 32, 2)
}

/**
 * The paper of a list's row: a slip a little paler than the page, ruled round by hand, lifted a pixel under the mouse ([lit]) and, if it is the one [picked], brighter
 * and ringed in red with an arrow that bobs at its left. [kind] 1 is a relief ticket's (a red stripe down its edge) and 2 a feast's (a green one). [a] is how far it has come in, [seed] one hand's wobble.
 */
fun Pen.rowPaper(x: Int, y: Int, w: Int, h: Int, kind: Int, picked: Boolean, lit: Boolean, t: Double, a: Float = 1f, seed: Int = 0) {
    val up = if (lit && !picked) 1 else 0
    val top = y - up
    val paper = if (picked) 0xFFFBF3DC.toInt() else if (lit) 0xFFF6ECD2.toInt() else 0xFFEFE3C4.toInt()
    fill(x + 1, top + h, x + w, top + h + 1 + up, Ink.fade(0xFF2A1A0A.toInt(), 0.22f * a))
    fill(x + 1, top, x + w - 1, top + h, Ink.fade(paper, a))
    fill(x, top + 1, x + w, top + h - 1, Ink.fade(paper, a))
    val rim = Ink.fade(if (picked) Ink.RED else Ink.SOFT, (if (picked) 0.8f else 0.45f) * a)
    stroke((x + 1).toFloat(), top.toFloat(), (x + w - 1).toFloat(), top.toFloat(), rim, 0.3f, seed)
    stroke((x + 1).toFloat(), (top + h - 1).toFloat(), (x + w - 1).toFloat(), (top + h - 1).toFloat(), rim, 0.3f, seed + 1)
    fill(x, top + 1, x + 1, top + h - 1, rim)
    fill(x + w - 1, top + 1, x + w, top + h - 1, rim)
    val stripe = when (kind) {
        1 -> Ink.RED
        2 -> Ink.GREEN
        else -> 0
    }
    if (stripe != 0) fill(x + 1, top + 2, x + 3, top + h - 2, Ink.fade(stripe, 0.85f * a))
    if (picked) {
        val bob = (sin(t * 4.0) * 1.0).roundToInt()
        val ax = x - 5 + bob
        val ay = top + h / 2 - 3
        val c = Ink.fade(Ink.RED, a)
        fill(ax, ay, ax + 1, ay + 7, c)
        fill(ax + 1, ay + 1, ax + 2, ay + 6, c)
        fill(ax + 2, ay + 2, ax + 3, ay + 5, c)
        fill(ax + 3, ay + 3, ax + 4, ay + 4, c)
    }
}

/** A seal pressed on paper at {[cx], [cy]}: it comes down large and settles in a third of a second, a ring of dust goes out, and it fades after [hold] seconds. [since] is the time since it was pressed. */
fun Pen.stamp(cx: Int, cy: Int, since: Double, seal: Int = 1, hold: Double = 1.2) {
    if (since < 0.0) return
    val p = (since / 0.35).toFloat()
    val size = (12 * (1f + 1.4f * (1f - Beat.out(Beat.clamp(p))))).roundToInt().coerceAtLeast(1)
    val fade = if (since > hold) 1f - ((since - hold) / 0.6).toFloat() else 1f
    if (fade <= 0f) return
    sprite(HaulBoardArt.SEAL_SPRITES[seal.coerceIn(0, HaulBoardArt.SEAL_SPRITES.size - 1)], cx - size / 2, cy - size / 2, size, size, Ink.fade(Ink.WHITE, fade))
    if (since in 0.3..0.9) {
        val q = ((since - 0.3) / 0.6).toFloat()
        ring(cx.toFloat(), cy.toFloat(), 8f + 14f * Beat.out(q), Ink.fade(Ink.PENCIL, 1f - q), 1, 1f, 0.3f, 4, 0f)
    }
}

/** A brass padlock about 14 across with its shackle up at {[cx], [cy]} (the middle of its shackle's arc); it rattles as [rattle] (radians, to and fro) swings it. */
fun Pen.padlock(cx: Int, cy: Int, rattle: Float) {
    save()
    translate(cx.toFloat(), (cy + 6).toFloat())
    rotate(rattle)
    translate(-cx.toFloat(), -(cy + 6).toFloat())
    ring(cx.toFloat() + 0.5f, cy.toFloat() - 0.5f, 4.5f, 0xFF4A3A22.toInt(), 2, 1f, 0f, 3, 0f)
    fill(cx - 7, cy, cx + 8, cy + 11, 0xFF5A4318.toInt())
    fill(cx - 6, cy + 1, cx + 7, cy + 10, 0xFF9A7A30.toInt())
    fill(cx - 6, cy + 1, cx + 7, cy + 2, 0xFFD2AE5A.toInt())
    fill(cx - 6, cy + 9, cx + 7, cy + 10, 0xFF7A5C20.toInt())
    fill(cx - 1, cy + 4, cx + 2, cy + 7, 0xFF2A1A08.toInt())
    fill(cx, cy + 7, cx + 1, cy + 9, 0xFF2A1A08.toInt())
    restore()
}

/** The words of an ask, broken to the rows [askRow] sets them in: [most] lines at the most, the last cut short with two dots if the words are longer (the tip says them whole). */
private fun Pen.askLines(ask: io.github.vaspyyy.guildmark.network.TallyAsk, w: Int, most: Int): List<String> {
    val broken = wrap(Words.tr(ask.key), w - 14)
    val lines = broken.take(most).toMutableList()
    if (broken.size > most) lines[most - 1] = fit(lines[most - 1] + " ...", w - 14)
    return lines
}

/** How high [askRow] sets [ask] in a column [w] wide, in at most [most] lines of words. */
fun askHeight(pen: Pen, ask: io.github.vaspyyy.guildmark.network.TallyAsk, w: Int, most: Int = 2): Int =
    pen.askLines(ask, w, most).size * 10 + (if (ask.need > 1) 6 else 0) + 4

/**
 * One thing the next rung asks, as its words ([most] lines at the most, cut with two dots if they are longer; the tip says them whole) with its tick or its bar: a box at the left that
 * has a tick in it when the ask is met (the pen inks it when the page is opened, or when it was met), and under the words, if it counts more than one, a bar of how far it is. [delay] is when
 * the row comes in. Gives the height it took, so that the next row can be put below it.
 */
fun askRow(c: Ctx, x: Int, y: Int, w: Int, ask: io.github.vaspyyy.guildmark.network.TallyAsk, delay: Double, most: Int = 2): Int {
    val pen = c.pen
    val full = Words.tr(ask.key)
    val lines = pen.askLines(ask, w, most)
    val bar = ask.need > 1
    val h = lines.size * 10 + (if (bar) 6 else 0) + 4
    val k = comes(c.since - delay, 0.3)
    if (k <= 0f) return h
    val met = ask.have >= ask.need
    val shown = (1f - k) * 6f
    pen.save()
    pen.translate(shown, 0f)
    pen.frame(x, y + 1, 10, 10, Ink.fade(if (met) Ink.GREEN else Ink.SOFT, k))
    if (met) {
        val at = c.book.metAt[ask.key]
        val p = if (at != null) ((c.t - at) / 0.5).toFloat() else ((c.since - delay - 0.2) / 0.5).toFloat()
        pen.tick(x + 1, y + 1, p, Ink.fade(Ink.GREEN, k))
        if (at != null && c.t - at in 0.0..0.9) {
            val q = ((c.t - at) / 0.9).toFloat()
            pen.ring(x + 5f, y + 6f, 6f + 10f * Beat.out(q), Ink.fade(Ink.GOLD, 1f - q), 1, 1f, 0f, 9, 0f)
        }
    }
    for ((i, line) in lines.withIndex()) pen.text(line, x + 14, y + 2 + i * 10, Ink.fade(if (met) Ink.GREEN else Ink.BLACK, k))
    if (bar) {
        val by = y + 3 + lines.size * 10
        val fill = Beat.out(Beat.clamp(((c.since - delay - 0.15) / 0.5).toFloat()))
        val frac = (ask.have.toFloat() / ask.need).coerceIn(0f, 1f) * fill
        val figure = Words.t("ask.of", Words.num(ask.have), Words.num(ask.need))
        val fw = pen.width(figure)
        pen.gauge(x + 14, by, w - 14 - fw - 4, 4, frac, Ink.fade(if (met) Ink.GREEN else Ink.AMBER, k))
        pen.text(figure, x + w - fw, by - 2, Ink.fade(Ink.SOFT, k), false, true)
    }
    pen.restore()
    val tip = if (c.over(x, y, w, h)) listOf(full, Words.t("ask.of", Words.num(ask.have), Words.num(ask.need))) else null
    c.hot(x, y, w, h, tip)
    return h
}
