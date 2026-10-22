package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyHeld
import io.github.vaspyyy.guildmark.network.TallyNeed
import io.github.vaspyyy.guildmark.network.TallyRoutes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Held (living-world/trader/design.md, Part 1): the haul tickets the trader holds booked, each a slip with its good, its pay and its notches (a day left to a notch, the notches
 * along its edge), how much of the good is already in the pack, and the hooks that hold them; on the right the goods they still need to find and where the book knows them cheapest,
 * with the ticket picked set out whole on its stamped paper.
 */
class HeldPage : TallyPage("held") {
    // ---------------------------------------------------------------- the tickets

    override fun left(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        pen.title(Words.t("page.held"), 9, 8, 74, 31)
        rail(c)
        if (view.held.isEmpty()) {
            emptyLeft(c)
            return
        }
        for ((i, h) in view.held.take(MAX).withIndex()) row(c, h, i)
        // the hooks the rung gives that have nothing on them: rows pencilled where a ticket would be written
        for (i in view.held.size until min(view.standing.limit, MAX)) free(c, i)
    }

    /** A hook with nothing on it: the row a ticket would be written in, pencilled, with a hand's note, breathing a little. */
    private fun free(c: Ctx, i: Int) {
        val pen = c.pen
        val k = comes(c.since - 0.1 - 0.06 * i, 0.35)
        if (k <= 0f) return
        val y = 30 + i * 20
        val a = k * (0.8f + 0.2f * sin(c.t * 1.8 + i).toFloat())
        pen.frame(13, y, 128, 19, Ink.fade(Ink.PENCIL, 0.5f * a))
        // a hook, drawn in pencil
        pen.stroke(21f, (y + 4).toFloat(), 21f, (y + 11).toFloat(), Ink.fade(Ink.PENCIL, a), 0.1f, 140 + i)
        pen.stroke(21f, (y + 11).toFloat(), 18f, (y + 14).toFloat(), Ink.fade(Ink.PENCIL, a), 0.1f, 141 + i)
        pen.text(Words.t("held.free"), 34, y + 6, Ink.fade(Ink.PENCIL, a), false, true)
        c.hot(13, y, 128, 19, if (c.over(13, y, 128, 19)) listOf(Words.t("held.free.title"), Words.t("held.free.tip")) else null)
    }

    /** The rail the tickets hang from: a hook for every ticket the rung lets the trader hold, a slip swinging on each that is taken, and a ghost of the next that comes to the first hook while none is. */
    private fun rail(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        val n = min(max(view.standing.limit, view.held.size), MAX)
        val k = comes(c.since - 0.05, 0.4)
        val x1 = 141
        val x0 = x1 - n * PITCH
        pen.fill(x0 - 2, 9, x1 + 1, 11, Ink.fade(0xFF4A3020.toInt(), k))
        pen.fill(x0 - 2, 9, x1 + 1, 10, Ink.fade(0xFF7A5A3A.toInt(), k))
        for (i in 0 until n) {
            val hx = x0 + i * PITCH + PITCH / 2
            pen.fill(hx, 11, hx + 1, 14, Ink.fade(0xFFB48A34.toInt(), k))
            pen.fill(hx - 1, 13, hx, 15, Ink.fade(0xFFB48A34.toInt(), k))
            val h = view.held.getOrNull(i)
            if (h != null) {
                val born = book.arrived[h.id]
                val drop = if (born != null) 1f - Beat.back(Beat.clamp(((c.t - born) / 0.5).toFloat())) else 0f
                val urgent = book.ticks() < h.due && h.due - book.ticks() < TallyBook.DAY
                val swing = (sin(c.t * (if (urgent) 4.2 else 1.6) + i * 0.9) * (if (urgent) 0.22 else 0.1)).toFloat()
                hanging(pen, hx, 14 + (drop * -14f).roundToInt(), swing, h, k, urgent)
            }
        }
        // no ticket: a ghost of one comes down onto the first hook now and again, to show where it will hang
        if (view.held.isEmpty()) {
            val ph = ((c.t % 4.2) / 4.2).toFloat()
            if (ph < 0.6f) {
                val f = Beat.back(Beat.clamp(ph / 0.3f))
                val a = if (ph < 0.3f) 0.7f else 0.7f * (1f - (ph - 0.3f) / 0.3f)
                hanging(pen, x0 + PITCH / 2, 14 - ((1f - f) * 16f).roundToInt(), 0f, null, a * k, false)
            }
        }
        c.hot(x0 - 3, 6, n * PITCH + 5, 20, if (c.over(x0 - 3, 6, n * PITCH + 5, 20)) listOf(Words.t("held.rail.title"), Words.t("held.rail.tip", view.held.size, n)) else null)
    }

    /** A ticket hanging by its hook at {[hx], [hy]}: a small slip of paper turned [swing] radians about the hook, its edge the colour of its kind. */
    private fun hanging(pen: Pen, hx: Int, hy: Int, swing: Float, held: TallyHeld?, a: Float, urgent: Boolean) {
        pen.save()
        pen.translate(hx + 0.5f, hy.toFloat())
        pen.rotate(swing)
        val paper = Ink.fade(if (urgent) 0xFFF0D2B8.toInt() else 0xFFF1E4C2.toInt(), a)
        pen.fill(-2, 1, 3, 11, paper)
        pen.fill(-2, 1, 3, 2, Ink.fade(Ink.SOFT, 0.7f * a))
        pen.fill(-2, 10, 3, 11, Ink.fade(Ink.SOFT, 0.7f * a))
        pen.fill(-3, 2, -2, 10, Ink.fade(Ink.SOFT, 0.7f * a))
        pen.fill(3, 2, 4, 10, Ink.fade(Ink.SOFT, 0.7f * a))
        val stripe = when (held?.kind) {
            "relief" -> Ink.RED
            "feast" -> Ink.GREEN
            null -> Ink.PENCIL
            else -> Ink.BLUE
        }
        pen.fill(-1, 3, 2, 5, Ink.fade(stripe, 0.85f * a))
        pen.fill(-1, 6, 2, 7, Ink.fade(Ink.PENCIL, 0.7f * a))
        pen.fill(-1, 8, 1, 9, Ink.fade(Ink.PENCIL, 0.7f * a))
        pen.restore()
    }

    /** One ticket's row: its good, how many, its pay, the town, and what is left of its time as notches (or hours on its last day), with the share of the goods already carried as a line under it. */
    private fun row(c: Ctx, h: TallyHeld, i: Int) {
        val pen = c.pen
        val book = c.book
        val x0 = 13
        val w = 128
        val hh = 19
        val y = 30 + i * 20
        val born = book.arrived[h.id]
        // a ticket that came into the book while the page was open is pinned to its hook with a flutter of paper
        if (born != null && book.due("hung@${h.id}", born)) c.sound("trade.tally_page", 1.15f, 0.45f)
        val k = if (born != null) comes(c.t - born, 0.4) else comes(c.since - 0.08 - 0.06 * i, 0.3)
        if (k <= 0f) return
        val left = h.due - book.ticks()
        val urgent = left in 0.0..TallyBook.DAY
        val full = h.units > 0 && h.carried >= h.units
        val picked = i == book.held
        val lit = c.over(x0, y, w, hh)
        val kind = kindOf(h.kind)
        val dy = if (born != null) -((1f - Beat.back(Beat.clamp(((c.t - born) / 0.5).toFloat()))) * 12f).roundToInt() else 0
        val x = x0 + (if (born != null) 0 else ((1f - k) * 14f).roundToInt())
        pen.rowPaper(x, y + dy, w, hh, kind, picked, lit, c.t, k, 100 + i)
        // a row lifted under the mouse carries what is written on it up with it
        val oy = y + dy - (if (lit && !picked) 1 else 0)
        pen.good(h.good, x + 3, oy + 1)
        val from = pen.marksRight(h.pay, x + w - 3, oy + 1, Ink.fade(Ink.GREEN, k), k)
        pen.text(pen.fit(Words.goods(h.good, h.units), from - x - 23), x + 21, oy + 1, Ink.fade(if (full) Ink.GREEN else Ink.BLACK, k))
        // what is left of its time: the word "gone", the hours of its last day, or a notch for each day
        val days = daysLeft(left)
        val tail = if (left <= 0.0) pen.width(Words.t("age.gone")) else if (urgent) pen.width(Words.left(left)) else notchesWidth(days)
        pen.text(pen.fit(Words.town(h.place), w - 21 - tail - 8), x + 21, oy + 10, Ink.fade(Ink.SOFT, k), false, true)
        if (left <= 0.0) {
            pen.right(Words.t("age.gone"), x + w - 4, oy + 10, Ink.fade(Ink.RED, k), false, true)
        } else if (urgent) {
            pen.right(Words.left(left), x + w - 4, oy + 10, Ink.fade(Ink.RED, k * (0.7f + 0.3f * sin(c.t * 6.0).toFloat())), false, true)
        } else {
            pen.notches(x + w - 4, oy + 11, days, c.t, k)
        }
        // the share carried: the foot of the slip is inked green as far as the goods are in the pack
        val fill = Beat.out(Beat.clamp(((c.since - 0.25 - 0.06 * i) / 0.5).toFloat()))
        val frac = if (h.units > 0) (h.carried.toFloat() / h.units).coerceIn(0f, 1f) * fill else 0f
        val len = ((w - 2) * frac).roundToInt()
        if (len > 0) pen.fill(x + 1, oy + hh - 1, x + 1 + len, oy + hh, Ink.fade(Ink.GREEN, k))
        c.hot(x0, y, w, hh, if (lit) rowTip(c, h) else null) { book.pickHeld(i) }
    }

    private fun kindOf(id: String): Int = when (id) {
        "relief" -> 1
        "feast" -> 2
        else -> 0
    }

    private fun rowTip(c: Ctx, h: TallyHeld): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.t("held.tip.for", Words.goods(h.good, h.units), Words.shop(h.shop), Words.town(h.place)))
        lines.add(Words.t("held.tip.pay", Words.marks(h.pay)))
        lines.add(Words.t("held.tip.kind", Words.kind(h.kind)))
        val left = h.due - c.book.ticks()
        lines.add(if (left > 0.0) Words.t("held.tip.left", Words.left(left)) else Words.t("held.tip.late"))
        lines.add(if (h.carried >= h.units) Words.t("held.tip.all") else Words.t("held.tip.carried", Words.num(h.carried), Words.num(h.units)))
        return lines
    }

    /** No ticket held: the hooks stand empty above, the first rows are pencilled in where tickets would hang, and a word says how to get one. */
    private fun emptyLeft(c: Ctx) {
        val pen = c.pen
        for (i in 0 until min(max(c.book.view.standing.limit, 1), 3)) free(c, i)
        val k = comes(c.since - 0.25, 0.5)
        pen.centered(Words.t("held.empty.title"), 75, 112, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("held.empty.body"), 15, 126, 120, Ink.fade(Ink.SOFT, k), 10, 5, true)
    }

    // ---------------------------------------------------------------- what is still needed

    override fun right(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        pen.title(Words.t("held.needs"), RX0, 8, 126, 32)
        val k = comes(c.since - 0.1, 0.4)
        if (view.held.isEmpty()) {
            pen.paragraph(Words.t("held.needs.none"), RX0, 36, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 6, true)
            return
        }
        val total = view.held.sumOf { it.pay }
        pen.text(pen.fit(Words.t(if (view.held.size == 1) "held.sum.1" else "held.sum", view.held.size, Words.num(rolls(total, c.since, 0.15, 0.6))), RX1 - RX0), RX0, 29, Ink.fade(Ink.SOFT, k), false, true)
        pen.rule(RX0, RX1, 40, Ink.fade(Ink.SOFT, 0.45f), 33)
        if (view.needs.isEmpty()) {
            done(c)
        } else {
            // five rows fit above the ticket's card; with more, four, and a line to say how many are not shown
            val rows = if (view.needs.size > NEED_ROWS) NEED_ROWS - 1 else view.needs.size
            val ticket = view.held.getOrNull(book.held)
            for (i in 0 until rows) need(c, view.needs[i], i, ticket?.good == view.needs[i].good)
            if (view.needs.size > rows) pen.text(Words.t("held.needs.more", view.needs.size - rows), RX0 + 2, 44 + rows * 20 + 2, Ink.fade(Ink.SOFT, k), false, true)
        }
        card(c)
    }

    /** One good still to find: how many, the cheapest town the book knows it in with its price and stock and a ring of how old that news is; a click goes to that town on the Word page. */
    private fun need(c: Ctx, n: TallyNeed, i: Int, linked: Boolean) {
        val pen = c.pen
        val book = c.book
        val w = RX1 - RX0
        val y = 44 + i * 20
        val k = comes(c.since - 0.12 - 0.06 * i, 0.3)
        if (k <= 0f) return
        val lit = c.over(RX0, y, w, 19)
        val x = RX0 + ((1f - k) * 14f).roundToInt()
        pen.rowPaper(x, y, w, 19, 0, false, lit, c.t, k, 140 + i)
        if (linked) {
            val bob = (sin(c.t * 4.0) * 1.0).roundToInt()
            pen.fill(x - 5 + bob, y + 5, x - 4 + bob, y + 12, Ink.fade(Ink.RED, k))
            pen.fill(x - 4 + bob, y + 6, x - 3 + bob, y + 11, Ink.fade(Ink.RED, k))
            pen.fill(x - 3 + bob, y + 7, x - 2 + bob, y + 10, Ink.fade(Ink.RED, k))
            pen.fill(x - 2 + bob, y + 8, x - 1 + bob, y + 9, Ink.fade(Ink.RED, k))
        }
        pen.good(n.good, x + 3, y + 1)
        val known = n.at != TallyRoutes.NONE
        // what one costs at the cheapest town, a coin after it
        val price = if (known) Words.price(n.each) else ""
        pen.text(pen.fit(Words.goods(n.good, n.units), w - 25 - (if (known) pen.width(price) + 11 else 0) - 2), x + 21, y + 1, Ink.fade(Ink.BLACK, k))
        if (known) {
            pen.right(price, x + w - 12, y + 1, Ink.fade(Ink.SOFT, k))
            pen.coin(x + w - 7, y + 5, k)
            val news = max(0.0, book.ticks() - n.tick)
            // the cheapest town's name; if it has less than is wanted, how much, in red
            val short = n.stock < n.units
            pen.text(pen.fit(Words.town(n.place), if (short) 58 else 88), x + 21, y + 10, Ink.fade(Ink.SOFT, k), false, true)
            if (short) pen.right(Words.t("held.short", Words.num(n.stock)), x + w - 14, y + 10, Ink.fade(Ink.RED, k), false, true)
            pen.ageRing((x + w - 8).toFloat(), (y + 14).toFloat(), 3f, news / 1000.0, false, Ink.BLACK, k, 150 + i, true)
        } else {
            pen.text(pen.fit(Words.t("held.nowhere"), w - 24), x + 21, y + 10, Ink.fade(Ink.RED, k), false, true)
        }
        c.hot(RX0, y, w, 19, if (lit) needTip(c, n) else null) {
            if (known) {
                book.pick(n.at)
                book.go(TallyBook.WORD)
            }
        }
    }

    private fun needTip(c: Ctx, n: TallyNeed): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.good(n.good))
        lines.add(Words.t("held.need.tip", Words.num(n.units)))
        if (n.at == TallyRoutes.NONE) {
            lines.add(Words.t("held.need.tip.none"))
        } else {
            lines.add(Words.t("held.need.tip.cheap", Words.town(n.place), Words.price(n.each), Words.num(n.stock)))
            lines.add(Words.t("held.need.tip.news", Words.ago(max(0.0, c.book.ticks() - n.tick))))
            lines.add(Words.t("held.need.tip.go"))
        }
        return lines
    }

    /** Nothing more is needed: a tick inked in a ring, and a word. */
    private fun done(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.4)
        val sweep = Beat.out(Beat.clamp(((c.since - 0.25) / 0.6).toFloat()))
        pen.ring(RX0 + 66f, 74f, 13f, Ink.fade(Ink.GREEN, k), 2, sweep, 0f, 8, 0.3f)
        pen.tick(RX0 + 61, 69, ((c.since - 0.6) / 0.5).toFloat(), Ink.GREEN)
        pen.centered(Words.t("held.done.title"), RX0 + 66, 96, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("held.done.body"), RX0, 108, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 3, true)
    }

    /** The ticket picked, whole, on stamped paper: its good and pay, the shop and town, how much of it is carried, and its notched edge. */
    private fun card(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val h = book.view.held.getOrNull(book.held) ?: return
        val since = min(c.since, c.t - book.heldAt)
        val k = comes(since - 0.15, 0.4)
        if (k <= 0f) return
        val x = RX0
        val y = 146
        val w = RX1 - RX0 - 6
        val hh = 42
        pen.slip(x, y, w, hh, kindOf(h.kind), Ink.fade(Ink.WHITE, k))
        pen.good(h.good, x + 8, y + 5)
        pen.text(pen.fit(Words.goods(h.good, h.units), w - 28 - 26 - 8), x + 28, y + 6, Ink.fade(Ink.BLACK, k))
        pen.marksRight(h.pay, x + w - 8, y + 6, Ink.fade(Ink.GREEN, k), k)
        // its kind, and what is left of its time: the word "gone", or a notch for each day (they throb on the last)
        val left = h.due - book.ticks()
        val days = daysLeft(left)
        val tail = if (left > 0.0) notchesWidth(days) else pen.width(Words.t("age.gone"))
        pen.text(pen.fit(Words.kind(h.kind), w - 28 - tail - 12), x + 28, y + 15, Ink.fade(Ink.SOFT, k), false, true)
        if (left > 0.0) pen.notches(x + w - 9, y + 16, days, c.t, k, left <= TallyBook.DAY) else pen.right(Words.t("age.gone"), x + w - 9, y + 15, Ink.fade(Ink.RED, k), false, true)
        pen.text(pen.fit(Words.t("held.card.where", Words.shop(h.shop), Words.town(h.place)), w - 16), x + 8, y + 25, Ink.fade(Ink.SOFT, k), false, true)
        val frac = if (h.units > 0) (h.carried.toFloat() / h.units).coerceIn(0f, 1f) * Beat.out(Beat.clamp(((since - 0.3) / 0.5).toFloat())) else 0f
        pen.gauge(x + 8, y + 34, 54, 4, frac, Ink.fade(Ink.GREEN, k))
        pen.text(Words.t("ask.of", Words.num(h.carried), Words.num(h.units)), x + 68, y + 33, Ink.fade(Ink.SOFT, k), false, true)
        c.hot(x, y, w, hh, if (c.over(x, y, w, hh)) rowTip(c, h) else null)
    }

    // ---------------------------------------------------------------- keys

    override fun key(book: TallyBook, code: Int): Boolean = when (code) {
        Keys.DOWN -> step(book, 1)
        Keys.UP -> step(book, -1)
        else -> false
    }

    override fun wheel(book: TallyBook, up: Boolean): Boolean = step(book, if (up) -1 else 1)

    private fun step(book: TallyBook, by: Int): Boolean {
        if (book.view.held.size < 2) return false
        book.pickHeld(book.held + by)
        return true
    }

    private companion object {
        /** The right page's text column; the most tickets and needs drawn; and the width of a hook's place on the rail. */
        const val RX0 = 17
        const val RX1 = 149
        const val MAX = 8
        const val NEED_ROWS = 5
        const val PITCH = 7
    }
}
