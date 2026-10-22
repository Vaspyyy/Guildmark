package io.github.vaspyyy.guildmark.client.trade

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The bookmark ribbon: crimson silk that lies down the spine from the top of the book and hangs out below its foot, with a forked tail. It is never
 * still. The part that hangs free sways on a slow swing; the whole length shimmers, a light running down it; and when a page is turned a half-twist runs
 * down it from the head (the silk shows its darker back as it turns) and the tail is flicked and swings itself out. Every figure is worked from the time
 * given, so a picture of it can be had at any moment.
 */
object Ribbon {
    private val FRONT = 0xFF7C2430.toInt()
    private val LIT = 0xFF9C3A40.toInt()
    private val EDGE_L = 0xFFA6504E.toInt()
    private val EDGE_R = 0xFF46101C.toInt()
    private val BACK = 0xFF541824.toInt()
    private val BACK_L = 0xFF6C2630.toInt()
    private val SHADOW = 0x40120808

    private const val WIDTH = 4.5f

    /**
     * Draws the ribbon with its head at {[cx], [top]}: it lies straight to [free] (where it leaves the book) and hangs to [end]. [t] is the book's time in seconds and
     * [kick] the time since a page was last turned (a large number for none).
     */
    fun draw(pen: Pen, cx: Int, top: Int, free: Int, end: Int, t: Double, kick: Double) {
        val rows = end - top
        if (rows <= 0) return
        val swingIdle = 1.5f * sin((t * 1.9).toFloat()) + 0.8f * sin((t * 3.1 + 1.0).toFloat())
        val swingKick = if (kick in 0.0..3.0) 5.5f * Beat.swing((kick / 3.0).toFloat(), 2.2f, 3.2f) else 0f
        val twistAt = if (kick in 0.0..1.6) (kick * 190.0).toFloat() else -999f
        var run = 0
        var prevL = Int.MIN_VALUE
        var prevR = Int.MIN_VALUE
        var prevC = 0
        var runTop = top
        fun flush(y: Int) {
            if (run <= 0) return
            val width = prevR - prevL
            pen.fill(prevL + 1, runTop + 1, prevR + 1, y + 1, SHADOW)
            pen.fill(prevL, runTop, prevR, y, prevC)
            if (prevC == FRONT || prevC == LIT) {
                pen.fill(prevL, runTop, prevL + 1, y, EDGE_L)
                if (width > 2) pen.fill(prevR - 1, runTop, prevR, y, EDGE_R)
            } else {
                pen.fill(prevL, runTop, prevL + 1, y, BACK_L)
            }
            run = 0
        }
        for (i in 0 until rows) {
            val y = top + i
            // how far below the book's foot this row hangs, 0 to 1: only that part sways
            val hang = if (y <= free) 0f else ((y - free).toFloat() / (end - free).coerceAtLeast(1)).coerceIn(0f, 1f)
            val sway = (swingIdle * 0.6f + swingKick) * hang * hang
            // the half-twist that runs down it when a page is turned, and a slight lazy turn of the free part
            val wave = if (twistAt > -900f) exp(-(((y - twistAt) / 16f) * ((y - twistAt) / 16f)).toDouble()).toFloat() else 0f
            val lazy = 0.45f * sin((t * 1.4 + y * 0.05).toFloat()) * hang
            val angle = PI.toFloat() * wave + lazy
            val c = cos(angle)
            val width = (WIDTH * abs(c)).coerceAtLeast(1.4f)
            val l = (cx + sway - width / 2f).roundToInt()
            val r = (cx + sway + width / 2f).roundToInt().coerceAtLeast(l + 1)
            // the fork of the tail: the last six rows cut up in a V
            val tail = end - y
            val cut = tail < 6
            val glint = ((y * 0.5 - t * 14.0) % 46.0 + 46.0) % 46.0
            val shade = if (c < 0f) BACK else if (glint < 2.5) LIT else FRONT
            if (cut) {
                flush(y)
                val notch = 6 - tail
                val mid = (l + r) / 2
                pen.fill(l, y, (mid - notch / 2).coerceAtLeast(l), y + 1, shade)
                pen.fill((mid + notch / 2 + 1).coerceAtMost(r), y, r, y + 1, shade)
                continue
            }
            if (l == prevL && r == prevR && shade == prevC && run > 0) {
                run++
            } else {
                flush(y)
                prevL = l
                prevR = r
                prevC = shade
                runTop = y
                run = 1
            }
        }
        flush(end - 6)
    }
}
