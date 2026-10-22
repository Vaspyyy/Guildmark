package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyParcel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Ledger (living-world/trader/design.md, Part 1): the goods the trader carries that were bought away from where they are, each with where it came from, whether it came far enough to
 * count as hauled and how many days it has left; on the right the parcel picked as a tally stick, a notch to every day, which a small knife cuts as the days go (it cuts the notches that are gone
 * whenever the parcel is looked at, and the one that has just gone as it goes, with the sound of it).
 */
class LedgerPage : TallyPage("ledger") {
    // ---------------------------------------------------------------- the parcels

    override fun left(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        pen.title(Words.t("page.ledger"), 9, 8, 80, 41)
        val hauled = view.parcels.filter { it.counts }.sumOf { it.units }
        val k = comes(c.since - 0.1, 0.4)
        val sum = if (hauled > 0) Words.t("ledger.sum", Words.num(rolls(hauled, c.since, 0.15, 0.6))) else Words.t("ledger.sum.none")
        pen.right(sum, 141, 12, Ink.fade(if (hauled > 0) Ink.GREEN else Ink.PENCIL, k), false, true)
        c.hot(84, 8, 57, 14, if (c.over(84, 8, 57, 14)) listOf(Words.t("ledger.sum.title"), Words.t("ledger.sum.tip")) else null)
        if (view.parcels.isEmpty()) {
            emptyLeft(c)
            return
        }
        val top = top(book)
        for (i in top until min(view.parcels.size, top + ROWS)) row(c, view.parcels[i], i, i - top)
        // more above and below: little arrows that bob
        if (top > 0) arrow(pen, 134, 27, true, c.t)
        if (top + ROWS < view.parcels.size) arrow(pen, 134, 191 - 4, false, c.t)
    }

    /** The first parcel shown: the list follows the pick, scrolling a row at a time once it is past the fourth. */
    private fun top(book: TallyBook): Int = (book.parcel - ROWS / 2).coerceIn(0, max(0, book.view.parcels.size - ROWS))

    private fun arrow(pen: Pen, x: Int, y: Int, up: Boolean, t: Double) {
        val bob = (sin(t * 4.0) * 1.0).roundToInt()
        val yy = y + (if (up) -bob else bob)
        for (r in 0 until 3) {
            val w = if (up) r else 2 - r
            pen.fill(x - w, yy + r, x + w + 1, yy + r + 1, Ink.SOFT)
        }
    }

    private fun row(c: Ctx, p: TallyParcel, i: Int, slot: Int) {
        val pen = c.pen
        val book = c.book
        val x0 = 13
        val w = 128
        val h = 19
        val y = 30 + slot * 20
        val k = comes(c.since - 0.08 - 0.06 * slot, 0.3)
        if (k <= 0f) return
        val x = x0 + ((1f - k) * 14f).roundToInt()
        val picked = i == book.parcel
        val lit = c.over(x0, y, w, h)
        pen.rowPaper(x, y, w, h, 0, picked, lit, c.t, k, 200 + i)
        // a row lifted under the mouse carries what is written on it up with it
        val oy = y - (if (lit && !picked) 1 else 0)
        pen.good(p.good, x + 3, oy + 1)
        if (p.bonded) store(pen, x + 14, oy + 1, k)
        val urgent = p.left <= 2
        val days = Words.t("ledger.days", p.left)
        pen.right(days, x + w - 4, oy + 1, Ink.fade(if (urgent) Ink.RED else Ink.SOFT, k * (if (urgent && p.left <= 1) 0.7f + 0.3f * sin(c.t * 6.0).toFloat() else 1f)))
        pen.text(pen.fit(Words.goods(p.good, p.units), w - 25 - pen.width(days) - 4), x + 21, oy + 1, Ink.fade(Ink.BLACK, k))
        pen.text(pen.fit(Words.t("ledger.from", Words.town(p.place)), w - 37), x + 21, oy + 10, Ink.fade(Ink.SOFT, k), false, true)
        verdict(pen, x + w - 9, oy + 13, p.counts, c.since - 0.2 - 0.06 * slot, k)
        // the days that are left: the foot of the slip is inked as far as they reach
        val fill = Beat.out(Beat.clamp(((c.since - 0.25 - 0.06 * slot) / 0.5).toFloat()))
        val frac = if (p.of > 0) (p.left.toFloat() / p.of).coerceIn(0f, 1f) * fill else 0f
        val len = ((w - 2) * frac).roundToInt()
        if (len > 0) pen.fill(x + 1, oy + h - 1, x + 1 + len, oy + h, Ink.fade(if (urgent) Ink.RED else Ink.AMBER, k))
        c.hot(x0, y, w, h, if (lit) rowTip(p) else null) { book.pickParcel(i) }
    }

    /** A tiny brass padlock over a parcel's good, for goods put by in a Store. */
    private fun store(pen: Pen, x: Int, y: Int, a: Float) {
        pen.fill(x + 1, y, x + 4, y + 1, Ink.fade(0xFF4A3A22.toInt(), a))
        pen.fill(x, y + 1, x + 1, y + 3, Ink.fade(0xFF4A3A22.toInt(), a))
        pen.fill(x + 4, y + 1, x + 5, y + 3, Ink.fade(0xFF4A3A22.toInt(), a))
        pen.fill(x - 1, y + 3, x + 6, y + 7, Ink.fade(0xFF5A4318.toInt(), a))
        pen.fill(x, y + 4, x + 5, y + 6, Ink.fade(0xFFD2AE5A.toInt(), a))
    }

    /** Whether a parcel counts as hauled: a green seal with a tick inked in it, or a pencilled one with a dash. */
    private fun verdict(pen: Pen, cx: Int, cy: Int, counts: Boolean, since: Double, a: Float) {
        val color = if (counts) Ink.GREEN else Ink.PENCIL
        pen.ring(cx.toFloat(), cy.toFloat(), 4.5f, Ink.fade(color, a), 1, Beat.out(Beat.clamp((since / 0.4).toFloat())), if (counts) 0f else 0.3f, 3, 0.1f)
        if (counts) pen.tick(cx - 5, cy - 6, ((since - 0.2) / 0.4).toFloat(), Ink.fade(Ink.GREEN, a)) else pen.fill(cx - 2, cy, cx + 3, cy + 1, Ink.fade(Ink.PENCIL, a))
    }

    private fun rowTip(p: TallyParcel): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.goods(p.good, p.units))
        lines.add(Words.t("ledger.tip.bought", Words.town(p.place), Words.num(p.distance)))
        lines.add(Words.t(if (p.counts) "ledger.tip.counts" else "ledger.tip.near"))
        lines.add(Words.t("ledger.tip.left", p.left, p.of))
        if (p.bonded) lines.add(Words.t("ledger.tip.bonded"))
        return lines
    }

    /** Nothing carried that counts: three rows pencilled where parcels would be written, breathing a little, and a word on what makes a parcel. */
    private fun emptyLeft(c: Ctx) {
        val pen = c.pen
        for (i in 0 until 3) {
            val k = comes(c.since - 0.05 * i, 0.35)
            val a = k * (0.8f + 0.2f * sin(c.t * 1.8 + i * 1.3).toFloat())
            val y = 30 + i * 20
            pen.frame(13, y, 128, 19, Ink.fade(Ink.PENCIL, 0.45f * a))
            pen.frame(16, y + 3, 13, 13, Ink.fade(Ink.PENCIL, 0.4f * a))
            pen.rule(34, 80 + 10 * (i % 2), y + 6, Ink.fade(Ink.PENCIL, 0.45f * a), 210 + i)
            pen.rule(34, 66 + 12 * ((i + 1) % 3), y + 13, Ink.fade(Ink.PENCIL, 0.35f * a), 220 + i)
            pen.rule(112, 134, y + 6, Ink.fade(Ink.PENCIL, 0.4f * a), 230 + i)
        }
        val k = comes(c.since - 0.25, 0.5)
        pen.centered(Words.t("ledger.empty.title"), 75, 112, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("ledger.empty.body"), 15, 126, 120, Ink.fade(Ink.SOFT, k), 10, 6, true)
    }

    // ---------------------------------------------------------------- the tally stick

    override fun right(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val p = book.view.parcels.getOrNull(book.parcel)
        if (p == null) {
            emptyRight(c)
            return
        }
        val since = min(c.since, c.t - book.parcelAt)
        val name = Words.good(p.good)
        pen.title(if (pen.headingWidth(name) <= 124) name else pen.fit(name, 124), RX0, 8, 126, 42)
        pen.text(pen.fit(Words.goods(p.good, p.units), RX1 - RX0), RX0, 29, Ink.BLACK)
        pen.text(pen.fit(Words.t("ledger.from", Words.town(p.place)), RX1 - RX0), RX0, 39, Ink.SOFT, false, true)
        pen.text(pen.fit(Words.t("ledger.away", Words.num(p.distance)), RX1 - RX0), RX0, 49, Ink.SOFT, false, true)
        pen.rule(RX0, RX1, 60, Ink.fade(Ink.SOFT, 0.45f), 43)
        stick(c, p, since)
        days(c, p, since)
        facts(c, p, since)
    }

    private fun emptyRight(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.5)
        pen.title(Words.t("ledger.stick"), RX0, 8, 126, 44)
        pen.paragraph(Words.t("ledger.stick.body"), RX0, 36, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 6, true)
        // a bare stick, and the knife beside it waiting
        woodStick(pen, RX0, 100, RX1 - RX0, 0, k)
        knifeRest(pen, 100)
    }

    /**
     * The stick: a length of oak with a hole at one end for a cord, a notch in its upper edge for each day the parcel keeps its name as hauled. The notches of days gone are cut (dark, bitten
     * into the wood), the rest are only scored. The knife cuts them in turn, the last of them, if the day has only just gone, now.
     */
    private fun stick(c: Ctx, p: TallyParcel, since: Double) {
        val pen = c.pen
        val book = c.book
        val x = RX0
        val y = STICK_Y
        val w = RX1 - RX0
        val k = comes(since - 0.05, 0.4)
        val of = max(1, p.of)
        val gone = (of - p.left).coerceIn(0, of)
        val key = TallyBook.key(p)
        // when each notch is cut: a day gone just now is cut now; the rest, in turn, as the parcel is looked at
        val fresh = book.cut[key]?.let { it > book.parcelAt && c.t - it < 8.0 }
        val cutAt = DoubleArray(gone)
        val start = book.parcelAt.coerceAtLeast(book.pageAt) + 0.45
        val step = min(0.16, 1.5 / max(1, gone))
        for (j in 0 until gone) cutAt[j] = start + step * j
        if (fresh == true && gone > 0) cutAt[gone - 1] = max(start, (book.cut[key] ?: 0.0) + 0.35)
        val spacing = (w - 26f) / of
        val cutNow = BooleanArray(of) { it < gone && c.t >= cutAt[it] }
        woodStick(pen, x, y, w, of, k, cutNow, spacing)
        // the knife
        val xs = IntArray(gone) { (x + 18 + (it + 0.5f) * spacing).roundToInt() }
        val pose = knifePose(c.t, cutAt, xs, y)
        pen.knife(pose[0].roundToInt(), pose[1].roundToInt(), pose[2])
        // chips fly off a notch as it is cut
        for (j in 0 until gone) {
            val dt = c.t - cutAt[j]
            if (dt in 0.0..0.6) chips(pen, xs[j], y, dt.toFloat(), j)
            // the sound: for the notch just cut, the last few of a replay (a long run is not a rattle of them)
            if (j >= gone - 4 && book.due("notch:$key:$j:${book.parcelAt}:${book.cut[key] ?: 0.0}", cutAt[j])) c.sound("trade.tally_notch", 0.9f + 0.2f * Beat.hash(j * 31 + of), if (fresh == true && j == gone - 1) 0.8f else 0.5f)
        }
        val tip = if (c.over(x, y - 4, w, 30)) listOf(Words.t("ledger.stick.title"), Words.t("ledger.stick.tip", of)) else null
        c.hot(x, y - 4, w, 30, tip)
    }

    /** The oak: its body, grain, hole and cord, and a notch for each of [of] days, [cut] ones bitten out, the rest scored. */
    private fun woodStick(pen: Pen, x: Int, y: Int, w: Int, of: Int, a: Float, cutNow: BooleanArray = BooleanArray(max(of, 0)), spacing: Float = 0f) {
        val h = 20
        // a soft shadow under it
        pen.fill(x + 2, y + h, x + w, y + h + 3, Ink.fade(0xFF2A1A0A.toInt(), 0.22f * a))
        pen.fill(x, y + 1, x + w, y + h - 1, Ink.fade(0xFFC9A06A.toInt(), a))
        pen.fill(x + 1, y, x + w - 1, y + h, Ink.fade(0xFFC9A06A.toInt(), a))
        pen.fill(x + 1, y, x + w - 1, y + 1, Ink.fade(0xFFE6CB92.toInt(), a))
        pen.fill(x + 1, y + h - 1, x + w - 1, y + h, Ink.fade(0xFF74562C.toInt(), a))
        // the grain: long strokes with a slow wander
        for (g in 0 until 5) {
            val gy = y + 4 + g * 3
            pen.stroke((x + 3).toFloat(), gy.toFloat(), (x + w - 3).toFloat(), gy + (if (g % 2 == 0) 1f else -1f), Ink.fade(0xFFA27A44.toInt(), 0.55f * a), 0.8f, 300 + g)
        }
        // the hole at the left, and the cord through it
        pen.disc(x + 7, y + 11, 2, Ink.fade(0xFF3A2614.toInt(), a))
        pen.stroke((x + 5).toFloat(), (y + 3).toFloat(), (x + 9).toFloat(), (y - 1).toFloat(), Ink.fade(0xFFD6C4A0.toInt(), a), 0f, 1, 1)
        // a score at the head of each day's place, and a V bitten where the day is gone
        for (j in 0 until of) {
            val nx = (x + 18 + (j + 0.5f) * spacing).roundToInt()
            val fifth = j % 5 == 4
            if (cutNow.getOrElse(j) { false }) {
                val depth = if (fifth) 7 else 5
                for (r in 0 until depth) {
                    val half = (depth - r) / 2 + (if (fifth) 1 else 0)
                    pen.fill(nx - half, y + r, nx + half + 1, y + r + 1, Ink.fade(0xFF3A2614.toInt(), a))
                    pen.fill(nx - half - 1, y + r, nx - half, y + r + 1, Ink.fade(0xFFEBD3A0.toInt(), 0.9f * a))
                }
            } else {
                pen.fill(nx, y, nx + 1, y + (if (fifth) 4 else 2), Ink.fade(0xFF7A5A30.toInt(), 0.9f * a))
            }
        }
    }

    /** Where the knife is at [t]: at a notch as it is cut (coming to it, biting, drawing back), or lying at rest. {x, y, angle}. */
    private fun knifePose(t: Double, cutAt: DoubleArray, xs: IntArray, y: Int): FloatArray {
        var near = -1
        for (j in cutAt.indices) if (t - cutAt[j] > -0.34 && t - cutAt[j] < 0.3 && (near < 0 || abs(t - cutAt[j]) < abs(t - cutAt[near]))) near = j
        val rest = floatArrayOf((RX1 - 31).toFloat(), (y + 27).toFloat(), 0.1f)
        if (near < 0) return rest
        val dt = (t - cutAt[near]).toFloat()
        val nx = xs[near].toFloat()
        val prev = if (near > 0 && cutAt[near] - cutAt[near - 1] < 0.5) floatArrayOf(xs[near - 1].toFloat(), y - 6f, -0.8f) else rest
        val next = if (near < cutAt.size - 1 && cutAt[near + 1] - cutAt[near] < 0.5) floatArrayOf(xs[near + 1].toFloat(), y - 6f, -0.8f) else rest
        val above = floatArrayOf(nx + 5f, y - 8f, -0.85f)
        return when {
            dt < -0.1f -> mix(prev, above, Beat.smooth((dt + 0.34f) / 0.24f))
            dt < 0f -> mix(above, floatArrayOf(nx, y + 1f, -0.3f), Beat.smooth((dt + 0.1f) / 0.1f))
            else -> mix(floatArrayOf(nx, y + 1f, -0.3f), next, Beat.smooth(dt / 0.3f))
        }
    }

    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray = floatArrayOf(Beat.lerp(a[0], b[0], t), Beat.lerp(a[1], b[1], t), Beat.lerp(a[2], b[2], t))

    /** A few flecks of wood thrown off the notch as it is cut. */
    private fun chips(pen: Pen, nx: Int, y: Int, dt: Float, seed: Int) {
        val a = 1f - dt / 0.6f
        for (i in 0 until 4) {
            val vx = (Beat.hash(seed * 7 + i) - 0.5f) * 26f
            val vy = -12f - Beat.hash(seed * 11 + i * 3) * 14f
            val px = nx + vx * dt
            val py = y + vy * dt + 60f * dt * dt
            pen.fill(px.roundToInt(), py.roundToInt(), px.roundToInt() + 1, py.roundToInt() + 1, Ink.fade(0xFFEBD3A0.toInt(), a.coerceIn(0f, 1f)))
        }
    }

    /** The knife as it lies when it is not cutting: below the end of the stick whose top is at [y]. */
    private fun knifeRest(pen: Pen, y: Int) {
        pen.knife(RX1 - 31, y + 27, 0.1f)
    }

    /** The days left, big, in carved figures, and under them how long to the next notch (the town's day turns once in a game day, in the small hours). */
    private fun days(c: Ctx, p: TallyParcel, since: Double) {
        val pen = c.pen
        val k = comes(since - 0.4, 0.4)
        val shown = rolls(p.left, since, 0.45, 0.5)
        val num = Words.num(shown)
        pen.heading(num, RX0 + 2, 104, Ink.fade(if (p.left <= 2) Ink.RED else Ink.BLACK, k))
        val nw = pen.headingWidth(num)
        pen.text(Words.t("ledger.of", p.of), RX0 + 8 + nw, 105, Ink.fade(Ink.SOFT, k))
        // how long until the next notch is cut
        val until = c.book.untilDay()
        val frac = (1.0 - until / TallyBook.DAY).toFloat()
        pen.gauge(RX0 + 2, 119, RX1 - RX0 - 4, 4, frac * Beat.out(Beat.clamp(((since - 0.5) / 0.5).toFloat())), Ink.fade(Ink.AMBER, k))
        pen.text(pen.fit(Words.t(if (p.left <= 1) "ledger.last" else "ledger.next", Words.left(until)), RX1 - RX0 - 4), RX0 + 2, 126, Ink.fade(Ink.SOFT, k), false, true)
        c.hot(RX0, 98, RX1 - RX0, 38, if (c.over(RX0, 98, RX1 - RX0, 38)) listOf(Words.t("ledger.next.title"), Words.t("ledger.next.tip")) else null)
    }

    /** What the parcel is and where it stands: whether it counts as hauled, and whether it is put by in a Store. */
    private fun facts(c: Ctx, p: TallyParcel, since: Double) {
        val pen = c.pen
        val y = 144
        val k = comes(since - 0.55, 0.4)
        pen.rule(RX0, RX1, y - 5, Ink.fade(Ink.SOFT, 0.45f * k), 45)
        verdict(pen, RX0 + 5, y + 5, p.counts, since - 0.6, k)
        pen.text(Words.t(if (p.counts) "ledger.counts" else "ledger.near"), RX0 + 14, y, Ink.fade(if (p.counts) Ink.GREEN else Ink.SOFT, k))
        val end = pen.paragraph(Words.t(if (p.counts) "ledger.counts.body" else "ledger.near.body"), RX0, y + 11, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 2, true)
        if (p.bonded) {
            store(pen, RX0 + 3, end + 1, k)
            pen.text(Words.t("ledger.bonded"), RX0 + 14, end, Ink.fade(Ink.BLACK, k))
            val tip = if (c.over(RX0, end - 2, 110, 12)) listOf(Words.t("ledger.bonded"), Words.t("ledger.bonded.body")) else null
            c.hot(RX0, end - 2, 110, 12, tip)
        }
    }

    // ---------------------------------------------------------------- keys

    override fun key(book: TallyBook, code: Int): Boolean = when (code) {
        Keys.DOWN -> step(book, 1)
        Keys.UP -> step(book, -1)
        else -> false
    }

    override fun wheel(book: TallyBook, up: Boolean): Boolean = step(book, if (up) -1 else 1)

    private fun step(book: TallyBook, by: Int): Boolean {
        if (book.view.parcels.size < 2) return false
        book.pickParcel(book.parcel + by)
        return true
    }

    private companion object {
        /** The right page's text column; the rows a page shows; and the top of the stick. */
        const val RX0 = 17
        const val RX1 = 149
        const val ROWS = 8
        const val STICK_Y = 67
    }
}
