package io.github.vaspyyy.guildmark.client.trade

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The inks and papers of the Tally: the world map's own browns, with the blue, green and red that a clerk's pens add. Each is an opaque colour;
 * [fade] makes a paler one.
 */
object Ink {
    /** The map's ink, and a softer brown for what is only noted. */
    val BLACK = 0xFF3A2614.toInt()
    val SOFT = 0xFF6A4C2E.toInt()

    /** Pencil: the lightest mark, for what is only guessed. */
    val PENCIL = 0xFF9C8661.toInt()
    val RED = 0xFF8C2219.toInt()
    val BLUE = 0xFF243F73.toInt()
    val GREEN = 0xFF2F6A34.toInt()
    val AMBER = 0xFFB8741A.toInt()
    val GOLD = 0xFFB98A2B.toInt()
    val PAPER = 0xFFF4EAD0.toInt()
    val WASH = 0x8CEFE3C2.toInt()
    val NOTE = 0xEEEADBB4.toInt()
    val SHADE = 0xFF1A1008.toInt()
    val WHITE = 0xFFFFFFFF.toInt()

    /** [color] with its alpha taken to [k] (0 to 1) of what it was. */
    fun fade(color: Int, k: Float): Int {
        val a = ((color ushr 24) * k.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }

    /** A colour [t] of the way from [a] to [b], alpha too. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int = (((a ushr shift) and 255) * (1 - k) + ((b ushr shift) and 255) * k).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Easings and the small deterministic noises that make a hand wobble; every one takes its time as an argument, so a picture can be had at any moment. */
object Beat {
    fun clamp(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    /** 0 to 1, slow at both ends. */
    fun smooth(t: Float): Float {
        val c = clamp(t)
        return c * c * (3f - 2f * c)
    }

    /** 0 to 1, fast at first and settling. */
    fun out(t: Float): Float {
        val c = 1f - clamp(t)
        return 1f - c * c * c
    }

    /** 0 to 1 with a little overshoot past 1 before it settles. */
    fun back(t: Float): Float {
        val c = clamp(t) - 1f
        return 1f + 2.70158f * c * c * c + 1.70158f * c * c
    }

    /** 0 up to 1 and back to 0 over [t] from 0 to 1. */
    fun hump(t: Float): Float = sin(PI.toFloat() * clamp(t))

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** How far [t] is through the stretch from [from] to [to] (0 before it, 1 after). */
    fun between(t: Float, from: Float, to: Float): Float = if (to <= from) (if (t >= to) 1f else 0f) else clamp((t - from) / (to - from))

    /** A number from 0 up to 1 that depends on [i] alone. */
    fun hash(i: Int): Float {
        var h = i * -0x61c88647
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        h *= 0x297a2d39
        h = h xor (h ushr 15)
        return (h ushr 8) / 16777216f
    }

    /** Smooth noise from -1 to 1 along [x], one picture to a [seed]. */
    fun noise(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = hash(i * 31 + seed * 7919) * 2f - 1f
        val b = hash((i + 1) * 31 + seed * 7919) * 2f - 1f
        return a + (b - a) * u
    }

    /** A damped swing: 1 at the start, running through zero and settling at 0, with [turns] turns in [t] from 0 to 1. */
    fun swing(t: Float, turns: Float, damping: Float): Float {
        val c = clamp(t)
        return cos(2f * PI.toFloat() * turns * c) * kotlin.math.exp(-damping * c)
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------
// What a pen is shown to do by hand: lines with a tremor, rings drawn round, dotted leaders, gauges, and letters set in a measure.
// ---------------------------------------------------------------------------------------------------------------------------------

fun Pen.dot(x: Int, y: Int, color: Int) = fill(x, y, x + 1, y + 1, color)

/** A box's four sides, one pixel wide. */
fun Pen.frame(x: Int, y: Int, w: Int, h: Int, color: Int) {
    fill(x, y, x + w, y + 1, color)
    fill(x, y + h - 1, x + w, y + h, color)
    fill(x, y + 1, x + 1, y + h - 1, color)
    fill(x + w - 1, y + 1, x + w, y + h - 1, color)
}

/**
 * A line a hand might ink from {[x0], [y0]} to {[x1], [y1]}: [width] pixels thick, with a tremor of up to [shake] pixels that is the same every time for one [seed].
 * Runs of one row (or, for a steep line, one column) are one box each, so a long rule costs a few boxes and not a hundred.
 */
fun Pen.stroke(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, shake: Float = 0.5f, seed: Int = 0, width: Int = 1) {
    val dx = x1 - x0
    val dy = y1 - y0
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.5f) {
        fill(x0.roundToInt(), y0.roundToInt(), x0.roundToInt() + width, y0.roundToInt() + width, color)
        return
    }
    val steps = max(abs(dx), abs(dy)).roundToInt().coerceAtLeast(1)
    val nx = -dy / len
    val ny = dx / len
    val flat = abs(dx) >= abs(dy)
    var runA = Int.MIN_VALUE
    var runB = Int.MIN_VALUE
    var runPos = Int.MIN_VALUE
    fun flush() {
        if (runPos == Int.MIN_VALUE) return
        if (flat) fill(runA, runPos, runB + 1, runPos + width, color) else fill(runPos, runA, runPos + width, runB + 1, color)
        runPos = Int.MIN_VALUE
    }
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val tremor = if (shake > 0f) shake * Beat.noise(t * len * 0.11f, seed) else 0f
        val px = (x0 + dx * t + nx * tremor).roundToInt()
        val py = (y0 + dy * t + ny * tremor).roundToInt()
        val along = if (flat) px else py
        val across = if (flat) py else px
        if (runPos == across && along <= runB + 1) {
            if (along > runB) runB = along
        } else {
            flush()
            runPos = across
            runA = along
            runB = along
        }
    }
    flush()
}

fun Pen.stroke(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, shake: Float = 0.5f, seed: Int = 0, width: Int = 1) =
    stroke(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), color, shake, seed, width)

/** A ruled line across, as a clerk draws one with a straight-edge and a little unsteadiness: [x0] to [x1] at [y]. */
fun Pen.rule(x0: Int, x1: Int, y: Int, color: Int, seed: Int = 0) = stroke(x0.toFloat(), y.toFloat(), x1.toFloat(), y.toFloat(), color, 0.45f, seed)

/** A line of dots, one every [gap] pixels from {[x0], [y0]} to {[x1], [y1]}, the first [skip] pixels of the way left blank. */
fun Pen.dotted(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, gap: Float = 3f, skip: Float = 0f) {
    val dx = x1 - x0
    val dy = y1 - y0
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.5f) return
    var d = skip
    while (d <= len) {
        val t = d / len
        dot((x0 + dx * t).roundToInt(), (y0 + dy * t).roundToInt(), color)
        d += gap
    }
}

/**
 * A ring drawn the way a pen draws one, clockwise from the top: [sweep] of the way round (0 to 1), [width] 1 or 2 pixels, with
 * [dashes] (0 to 1) of it left out in short gaps (a pen running dry), a tremor of [shake] pixels in its radius, one picture to a [seed].
 * Gives where the pen's point has got to, for a nib to be drawn there.
 */
fun Pen.ring(cx: Float, cy: Float, r: Float, color: Int, width: Int = 1, sweep: Float = 1f, dashes: Float = 0f, seed: Int = 0, shake: Float = 0.35f): FloatArray {
    val whole = sweep.coerceIn(0f, 1f)
    val steps = ceil(2f * PI.toFloat() * r * whole * 1.7f).toInt().coerceAtLeast(1)
    var lastX = Int.MIN_VALUE
    var lastY = Int.MIN_VALUE
    val tau = 2f * PI.toFloat()
    val phase = Beat.hash(seed * 13 + 5)
    var endX = cx
    var endY = cy - r
    for (i in 0..steps) {
        val a = -PI.toFloat() / 2f + tau * whole * i / steps
        val rr = r + (if (shake > 0f) shake * Beat.noise(a * 2.4f + 20f, seed) else 0f)
        val px = cx + cos(a) * rr
        val py = cy + sin(a) * rr
        endX = px
        endY = py
        if (dashes > 0f) {
            val q = (a + PI.toFloat() / 2f) / 0.5f + phase * 4f
            val frac = q - floor(q)
            if (frac > 1f - dashes) continue
        }
        val ix = px.roundToInt()
        val iy = py.roundToInt()
        if (ix == lastX && iy == lastY) continue
        lastX = ix
        lastY = iy
        fill(ix, iy, ix + width, iy + width, color)
    }
    return floatArrayOf(endX, endY)
}

/** A filled disc of radius [r] at {[cx], [cy]}, in rows. */
fun Pen.disc(cx: Int, cy: Int, r: Int, color: Int) {
    for (dy in -r..r) {
        val half = sqrt((r * r + r - dy * dy).toFloat().coerceAtLeast(0f)).toInt()
        fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color)
    }
}

/** [text] with its middle at [cx]. */
fun Pen.centered(text: String, cx: Int, y: Int, color: Int, shade: Boolean = false, italic: Boolean = false) =
    text(text, cx - width(text) / 2, y, color, shade, italic)

/** [text] ending at [rx]. */
fun Pen.right(text: String, rx: Int, y: Int, color: Int, shade: Boolean = false, italic: Boolean = false) =
    text(text, rx - width(text), y, color, shade, italic)

/** [text] cut short, with two dots in place of what is left off, to be no wider than [max]. */
fun Pen.fit(text: String, max: Int): String {
    if (width(text) <= max) return text
    var s = text
    while (s.isNotEmpty() && width("$s..") > max) s = s.dropLast(1)
    return if (s.isEmpty()) "" else "$s.."
}

/** [text] broken into lines no wider than [max], at its spaces. */
fun Pen.wrap(text: String, max: Int): List<String> {
    val lines = ArrayList<String>()
    var line = ""
    for (word in text.split(' ')) {
        if (word.isEmpty()) continue
        val next = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && width(next) > max) {
            lines.add(line)
            line = word
        } else {
            line = next
        }
    }
    if (line.isNotEmpty()) lines.add(line)
    return lines
}

/** [text] written as a paragraph at {[x], [y]} in a measure of [max] pixels, [lead] pixels from line to line; gives the y below its last line. At most [lines] are written, the last one cut short. */
fun Pen.paragraph(text: String, x: Int, y: Int, max: Int, color: Int, lead: Int = 10, lines: Int = Int.MAX_VALUE, italic: Boolean = false): Int {
    var at = y
    val broken = wrap(text, max)
    for ((i, line) in broken.withIndex()) {
        if (i >= lines) break
        val shown = if (i == lines - 1 && broken.size > lines) fit("$line ...", max) else line
        text(shown, x, at, color, false, italic)
        at += lead
    }
    return at
}

/** The measure of a gauge: [frac] (0 to 1) of {[w] x [h]} filled with [color] on a pale well, ruled round, with a tick at every quarter. */
fun Pen.gauge(x: Int, y: Int, w: Int, h: Int, frac: Float, color: Int, well: Int = 0x26000000, rim: Int = Ink.SOFT) {
    fill(x, y, x + w, y + h, well)
    val inner = w - 2
    val full = (inner * frac.coerceIn(0f, 1f)).roundToInt()
    if (full > 0) fill(x + 1, y + 1, x + 1 + full, y + h - 1, color)
    frame(x, y, w, h, rim)
    for (q in 1..3) {
        val tx = x + (w * q) / 4
        fill(tx, y + h - 2, tx + 1, y + h - 1, Ink.fade(rim, 0.7f))
    }
}

/** A little four-point sparkle, for a thing that has just changed. */
fun Pen.sparkle(cx: Int, cy: Int, size: Int, color: Int) {
    fill(cx, cy - size, cx + 1, cy + size + 1, color)
    fill(cx - size, cy, cx + size + 1, cy + 1, color)
    if (size > 2) {
        val k = size / 2
        fill(cx - k, cy - k, cx - k + 1, cy - k + 1, Ink.fade(color, 0.6f))
        fill(cx + k, cy - k, cx + k + 1, cy - k + 1, Ink.fade(color, 0.6f))
        fill(cx - k, cy + k, cx - k + 1, cy + k + 1, Ink.fade(color, 0.6f))
        fill(cx + k, cy + k, cx + k + 1, cy + k + 1, Ink.fade(color, 0.6f))
    }
}
