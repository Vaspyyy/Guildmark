package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.trade.Ladder
import io.github.vaspyyy.guildmark.trade.Rung
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Standing (living-world/trader/design.md, Part 1 and Part 5): the ladder as a rope with five knots, the ones the trader has tied in gold and the rest in grey hemp, each with what it opens;
 * on the right the rung held and what the next one asks, each ask a tick or a bar, the trader's standing in the town where they stand, and the deliveries they have made as a clerk's tally strokes.
 * When a rung is climbed the next knot is tied: the loop of the rope draws in, the knot is pulled tight and turns gold, and a seal's sound is made.
 */
class StandingPage : TallyPage("standing") {
    // ---------------------------------------------------------------- the rope

    override fun left(c: Ctx) {
        val pen = c.pen
        val book = c.book
        pen.title(Words.t("page.standing"), 9, 8, 141, 51)
        // a rung climbed while the book was at another page is tied as this one is shown
        book.seeTie(book.pageAt + 0.5)
        rope(c)
        for (i in 0 until 5) knot(c, i)
        for (i in 0 until 5) label(c, i)
    }

    /** How far the rope is blown from straight at height [y], by the time [t]: nothing at the peg, a slow swing at the foot. */
    private fun sway(y: Float, t: Double): Float {
        val d = ((y - TOP) / (END - TOP)).coerceIn(0f, 1f)
        return (2.2f * d * d * sin(t * 1.25 - y * 0.018) + 0.7f * d * sin(t * 2.3 + 1.0)).toFloat()
    }

    /** How much of the rope is let down from the peg: 0 to 1 as the page is opened. */
    private fun laid(c: Ctx): Float = Beat.out(Beat.clamp(((c.since - 0.1) / 0.7).toFloat()))

    private fun rope(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.05, 0.5)
        // the peg it hangs from: a small timber driven into the page, a nail's head in it
        pen.fill(CX - 8, TOP - 5, CX + 9, TOP - 1, Ink.fade(0xFF4A3020.toInt(), k))
        pen.fill(CX - 8, TOP - 5, CX + 9, TOP - 4, Ink.fade(0xFF7A5A3A.toInt(), k))
        pen.fill(CX, TOP - 4, CX + 1, TOP - 3, Ink.fade(0xFFD2AE5A.toInt(), k))
        // the rope, let down from the peg as the page is opened, a length at a time
        val laid = laid(c)
        val reach = TOP + ((END - TOP) * laid).roundToInt()
        pen.clip(0, 0, 158, min(END, reach))
        var y = TOP - 1
        while (y < END) {
            val dx = sway(y + 8f, c.t).roundToInt()
            pen.sprite("trade/tally_rope", CX - 3 + dx, y, 6, 16)
            y += 16
        }
        pen.unclip()
        // the loose end, frayed
        if (laid >= 1f) {
            val dx = sway(END.toFloat(), c.t)
            for (i in -2..2) pen.stroke(CX + dx, END.toFloat(), CX + dx + i * 1.2f, END + 4f + (if (i % 2 == 0) 1f else 0f), 0xFFB8AC8C.toInt(), 0.2f, 5 + i, 1)
        }
    }

    /** Where the knot of rung [i] hangs. */
    private fun knotY(i: Int): Int = KNOT0 + i * PITCH

    private fun knot(c: Ctx, i: Int) {
        val pen = c.pen
        val book = c.book
        val rung = book.view.standing.rung.coerceIn(0, 4)
        val ky = knotY(i)
        val kx = CX + sway(ky.toFloat(), c.t)
        if (TOP + (END - TOP) * laid(c) < ky + 7) return
        val tiedFor = c.t - book.tiedAt
        val tying = i == book.tiedRung && tiedFor in 0.0..TIE
        var gold = i <= rung
        var scale = 1f
        if (tying) {
            if (tiedFor < PULL) {
                // the loop draws in round the rope
                gold = false
                val p = (tiedFor / PULL).toFloat()
                pen.ring(kx, ky.toFloat(), 4f + 11f * (1f - Beat.smooth(p)), Ink.fade(0xFFB8AC8C.toInt(), 0.9f), 1, 1f, 0f, 7 + i, 0.4f)
                scale = 0.7f + 0.3f * Beat.smooth(p)
            } else {
                // pulled tight: the knot swells and settles, a ring of gold goes out of it, a few sparks
                val q = ((tiedFor - PULL) / 0.35).toFloat()
                scale = 1f + 0.8f * (1f - Beat.out(q))
                val w = ((tiedFor - PULL) / (TIE - PULL)).toFloat()
                pen.ring(kx, ky.toFloat(), 8f + 14f * Beat.out(w), Ink.fade(Ink.GOLD, 0.95f * (1f - w)), 2, 1f, 0f, 3 + i, 0.2f)
                pen.ring(kx, ky.toFloat(), 6f + 8f * Beat.out(min(1f, w * 1.4f)), Ink.fade(Ink.WHITE, 0.6f * (1f - w)), 1, 1f, 0f, 5 + i, 0f)
                for (s in 0 until 4) {
                    val a = s * 1.57f + 0.5f + 0.4f * Beat.hash(s + i * 5)
                    val r = 7f + 13f * Beat.out(w)
                    pen.sparkle((kx + cos(a) * r).roundToInt(), (ky + sin(a) * r).roundToInt(), 2, Ink.fade(Ink.GOLD, 1f - w))
                }
            }
            if (book.due("tie@${book.tiedAt}", book.tiedAt + PULL)) c.sound("trade.seal_press", 0.9f, 0.8f)
        } else if (i == rung) {
            // the rung held: a halo of gold that breathes, and now and again a twinkle
            pen.ring(kx, ky.toFloat(), 9.5f + 0.6f * sin(c.t * 2.4).toFloat(), Ink.fade(Ink.GOLD, 0.55f), 1, 1f, 0f, 11 + i, 0.15f)
            val ph = ((c.t * 0.35 + i * 0.37) % 1.0).toFloat()
            if (ph < 0.18f) pen.sparkle((kx + 8).roundToInt(), ky - 8, 2, Ink.fade(Ink.GOLD, 1f - ph / 0.18f))
        }
        val entry = comes(c.since - 0.25 - 0.08 * i, 0.3)
        pen.save()
        pen.translate(kx, ky.toFloat())
        pen.scale(scale * (0.6f + 0.4f * entry), scale * (0.6f + 0.4f * entry))
        pen.sprite(if (gold) "trade/tally_knot" else "trade/tally_knot_off", -7, -7, 14, 14, Ink.fade(Ink.WHITE, entry))
        pen.restore()
    }

    /** What a rung is called, and in two short lines of a hand's note what it is for; the tip, when the mouse is on it, says what it opens. */
    private fun label(c: Ctx, i: Int) {
        val pen = c.pen
        val rung = c.book.view.standing.rung.coerceIn(0, 4)
        val k = comes(c.since - 0.3 - 0.08 * i, 0.35)
        if (k <= 0f) return
        val ky = knotY(i)
        val x = LX + ((1f - k) * 8f).roundToInt()
        val held = i <= rung
        val next = i == rung + 1
        val id = Words.RUNGS[i]
        val ink = if (held) Ink.BLACK else if (next) Ink.SOFT else Ink.PENCIL
        val name = pen.fit(Words.rung(i), 141 - LX)
        pen.text(name, x, ky - 9, Ink.fade(ink, k))
        // the rung held is underlined, as a hand underlines it, the line drawn from the left
        if (i == rung) pen.stroke(x.toFloat(), ky - 1f, x + pen.width(name) * Beat.out(Beat.clamp(((c.since - 0.5) / 0.4).toFloat())), ky - 0.4f, Ink.fade(Ink.RED, k), 0.2f, 40 + i, 1)
        val teaser = pen.wrap(Words.t("rung.$id.teaser", Ladder.heldLimit(Rung.entries[i])), 141 - LX)
        for ((j, line) in teaser.take(2).withIndex()) pen.text(line, x, ky + 1 + j * 9, Ink.fade(if (held) Ink.SOFT else Ink.PENCIL, k), false, true)
        // the knot that is to be tied next has a dotted line drawn to it from the rope
        if (next) pen.dotted((CX + 9).toFloat(), ky.toFloat(), (LX - 3).toFloat(), ky.toFloat(), Ink.fade(Ink.PENCIL, 0.8f * k), 3f, ((c.t * 6.0) % 3.0).toFloat())
        val state = when {
            i == rung -> "standing.knot.here"
            i < rung -> "standing.knot.held"
            next -> "standing.knot.next"
            else -> "standing.knot.far"
        }
        val box = intArrayOf(CX - 12, ky - 12, 141 - CX + 12, 25)
        c.hot(box[0], box[1], box[2], box[3], if (c.over(box[0], box[1], box[2], box[3])) listOf(Words.rung(i), Words.t(state)) + opens(i) else null)
    }

    /** What a rung opens, for a tip: the words of it, then the tickets it lets a trader hold and the guards it lets them hire, as the ladder's own rules have them. */
    private fun opens(i: Int): List<String> {
        val rung = Rung.entries[i.coerceIn(0, Rung.entries.size - 1)]
        val lines = arrayListOf(Words.t("rung.${rung.id}.opens"), Words.t("standing.limit.tickets", Ladder.heldLimit(rung)))
        val guards = Ladder.guardLimit(rung)
        if (guards > 0) lines.add(Words.t("standing.limit.guards", guards))
        return lines
    }

    // ---------------------------------------------------------------- what the next rung asks

    override fun right(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        val st = view.standing
        val rung = st.rung.coerceIn(0, 4)
        val id = Words.RUNGS[rung]
        pen.title(Words.t("rung.$id.title"), RX0, 8, 126, 52)
        val k = comes(c.since - 0.1, 0.4)
        val holding = Words.t("standing.holding", Words.num(rolls(view.held.size, c.since, 0.15, 0.4)), Words.num(st.limit))
        pen.text(pen.fit(holding, RX1 - RX0), RX0, 29, Ink.fade(Ink.SOFT, k), false, true)
        c.hot(RX0, 27, RX1 - RX0, 11, if (c.over(RX0, 27, RX1 - RX0, 11)) listOf(Words.rung(rung)) + opens(rung) else null)
        if (rung >= 4) top(c) else asks(c, rung)
        here(c)
        tally(c)
    }

    private fun asks(c: Ctx, rung: Int) {
        val pen = c.pen
        val st = c.book.view.standing
        val k = comes(c.since - 0.15, 0.4)
        pen.text(pen.fit(Words.t("ask.head", Words.rung(rung + 1)), RX1 - RX0), RX0, 40, Ink.fade(Ink.BLACK, k))
        var y = 52
        if (st.asks.isEmpty()) {
            pen.paragraph(Words.t("ask.none"), RX0, y, RX1 - RX0, Ink.fade(Ink.PENCIL, k), 10, 3, true)
            return
        }
        // every ask is shown: one line of words to each, then a line more in turn to those that want it while the page has room above the standing; the tip says them whole
        val w = RX1 - RX0
        val want = st.asks.map { pen.wrap(Words.tr(it.key), w - 14).size.coerceIn(1, 3) }
        val most = IntArray(st.asks.size) { 1 }
        var room = ASKS_END - y - st.asks.sumOf { askHeight(pen, it, w, 1) }
        var grew = true
        while (grew) {
            grew = false
            for (i in most.indices) {
                if (most[i] < want[i] && room >= 10) {
                    most[i]++
                    room -= 10
                    grew = true
                }
            }
        }
        for ((i, ask) in st.asks.withIndex()) y += askRow(c, RX0, y, w, ask, 0.25 + 0.1 * i, most[i])
    }

    /** The highest rung: nothing more is asked, and a gold knot, large and slowly turning in its light, stands for it. */
    private fun top(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.5)
        val cx = RX0 + 66
        pen.ring(cx.toFloat(), 74f, 20f + 1.5f * sin(c.t * 2.0).toFloat(), Ink.fade(Ink.GOLD, 0.5f * k), 1, 1f, 0f, 5, 0.2f)
        pen.save()
        pen.translate(cx.toFloat(), 74f)
        val s = 2.4f + 0.12f * sin(c.t * 2.0).toFloat()
        pen.scale(s, s)
        pen.sprite("trade/tally_knot", -7, -7, 14, 14, Ink.fade(Ink.WHITE, k))
        pen.restore()
        val ph = ((c.t * 0.5) % 1.0).toFloat()
        if (ph < 0.2f) pen.sparkle(cx + 13, 62, 3, Ink.fade(Ink.GOLD, 1f - ph / 0.2f))
        pen.centered(Words.t("standing.top.title"), cx, 98, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("standing.top.body"), RX0, 110, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 3, true)
    }

    // ---------------------------------------------------------------- the town's name for you, and the strokes

    /** The standing the trader has in the town where they stand: the name of it, and the points they have against the points of the next. */
    private fun here(c: Ctx) {
        val pen = c.pen
        val view = c.book.view
        val st = view.standing
        val k = comes(c.since - 0.5, 0.4)
        pen.rule(RX0, RX1, 147, Ink.fade(Ink.SOFT, 0.45f * k), 53)
        if (st.rank < 0) {
            pen.paragraph(Words.t("standing.here.none"), RX0, 152, RX1 - RX0, Ink.fade(Ink.PENCIL, k), 10, 2, true)
            return
        }
        val town = Words.town(view.place)
        pen.text(pen.fit(Words.t("standing.here", town), RX1 - RX0), RX0, 151, Ink.fade(Ink.SOFT, k), false, true)
        pen.text(pen.fit(Words.rank(st.rank, town), RX1 - RX0), RX0, 160, Ink.fade(Ink.BLACK, k))
        if (st.next > 0) {
            val fill = Beat.out(Beat.clamp(((c.since - 0.6) / 0.6).toFloat()))
            val frac = (st.points.toFloat() / st.next).coerceIn(0f, 1f) * fill
            val figure = Words.t("ask.of", Words.num(rolls(st.points, c.since, 0.6, 0.6)), Words.num(st.next))
            val fw = pen.width(figure)
            pen.gauge(RX0, 170, RX1 - RX0 - fw - 6, 4, frac, Ink.fade(Ink.AMBER, k))
            pen.text(figure, RX1 - fw, 168, Ink.fade(Ink.SOFT, k), false, true)
        } else {
            pen.text(pen.fit(Words.t("standing.points.top", Words.num(st.points)), RX1 - RX0), RX0, 170, Ink.fade(Ink.GREEN, k), false, true)
        }
        val next = if (st.next > 0) Words.t("standing.next.tip", Words.rank((st.rank + 1).coerceAtMost(4), town), Words.num(st.next)) else Words.t("standing.top.tip")
        val tip = if (c.over(RX0, 149, RX1 - RX0, 28)) listOf(Words.rank(st.rank, town), Words.t("standing.here.tip", Words.num(st.points)), next) else null
        c.hot(RX0, 149, RX1 - RX0, 28, tip)
    }

    /** The deliveries made, as a clerk tallies them in fives: a stroke for each (the last twenty are drawn, the figure is the whole), the fifth drawn across the four. */
    private fun tally(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val st = book.view.standing
        val k = comes(c.since - 0.6, 0.4)
        val y = 180
        val shown = min(st.filled, 20)
        for (i in 0 until shown) {
            val g = i / 5
            val j = i % 5
            val gx = RX0 + 3 + g * 11
            val p = Beat.clamp(((c.since - 0.7 - 0.03 * i) / 0.25).toFloat())
            val fresh = if (i == shown - 1 && c.t - book.filledAt in 0.0..0.5) Beat.clamp(((c.t - book.filledAt) / 0.4).toFloat()) else 1f
            val a = min(p, fresh)
            if (a <= 0f) continue
            if (j < 4) pen.stroke(gx + j * 2f, y.toFloat(), gx + j * 2f, y + 9f * a, Ink.fade(Ink.BLACK, k), 0.15f, 60 + i, 1)
            else pen.stroke(gx - 1f, y + 7f, gx + 8f * a, y + 7f - 6f * a, Ink.fade(Ink.RED, k), 0.15f, 60 + i, 1)
        }
        val figure = Words.t("standing.filled", Words.num(rolls(st.filled, c.since, 0.7, 0.6)))
        pen.text(figure, RX0 + 3 + 4 * 11 + 8, y + 1, Ink.fade(if (st.filled > 0) Ink.BLACK else Ink.PENCIL, k))
        c.hot(RX0, y - 3, 110, 14, if (c.over(RX0, y - 3, 110, 14)) listOf(Words.t("standing.filled.title"), Words.t("standing.filled.tip")) else null)
    }

    private companion object {
        /** The right page's text column, and the lowest the asks may reach (the standing is set below). */
        const val RX0 = 17
        const val RX1 = 149
        const val ASKS_END = 144

        /** The rope: where it hangs, from where to where, where its first knot is and the gap between knots; and where the names are set. */
        const val CX = 24
        const val TOP = 34
        const val END = 186
        const val KNOT0 = 58
        const val PITCH = 28
        const val LX = 34

        /** Seconds a knot is tied in: the loop drawn in, then the pull, then the gold dying away. */
        const val PULL = 0.45
        const val TIE = 1.9
    }
}
