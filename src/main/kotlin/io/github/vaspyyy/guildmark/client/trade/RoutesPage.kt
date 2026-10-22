package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyLeg
import io.github.vaspyyy.guildmark.network.TallyLine
import io.github.vaspyyy.guildmark.network.TallyRoutes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Routes (living-world/trader/design.md, Part 1): from the town where the trader stands (or, away from every capital, the one of their book nearest), the other towns ranked
 * by what an hour of the walk pays, each with the basket of goods the book would buy here for it; on the right the way picked, its basket line by line with what it costs and brings,
 * the road drawn with a walker on it, and the button that books the haul tickets the basket fills. Until the trader is a Hawker the page is locked, and says what a Hawker asks.
 */
class RoutesPage : TallyPage("routes") {
    // ---------------------------------------------------------------- the ways

    override fun left(c: Ctx) {
        val pen = c.pen
        val r = c.book.view.routes
        pen.title(Words.t("page.routes"), 9, 8, 141, 21)
        when {
            r.locked -> lockedLeft(c)
            r.from == TallyRoutes.NONE -> nowhere(c)
            else -> {
                origin(c, r)
                if (r.legs.isEmpty()) none(c) else ways(c, r)
            }
        }
    }

    /** Where the ways start from: its name inside a ring of how old the news of it is, whether it is live, and the slots the basket is sized to. */
    private fun origin(c: Ctx, r: TallyRoutes) {
        val pen = c.pen
        val book = c.book
        val k = comes(c.since - 0.05, 0.3)
        val age = max(0.0, book.ticks() - r.tick)
        pen.ageRing(14f, 34f, 4f, age / 1000.0, r.live, if (r.live) Ink.GREEN else Ink.BLACK, k, 3, true)
        val slots = Words.t(if (r.slots == 1) "routes.slot" else "routes.slots", Words.num(r.slots))
        val name = pen.fit(Words.town(r.name), 141 - 22 - pen.width(slots) - 8)
        pen.text(name, 22, 29, Ink.fade(Ink.BLACK, k))
        pen.right(slots, 141, 29, Ink.fade(Ink.SOFT, k), false, true)
        val line = if (r.live) Words.t("routes.live") else Words.t("routes.old", Words.ago(age))
        pen.text(pen.fit(line, 118), 22, 39, Ink.fade(if (r.live) Ink.GREEN else Ink.SOFT, k), false, !r.live)
        pen.rule(9, 141, 50, Ink.fade(Ink.SOFT, 0.45f), 22)
        val where = if (c.over(9, 26, 132 - 54, 22)) listOf(Words.town(r.name), Words.t(if (r.live) "routes.live.tip" else "routes.old.tip")) else null
        c.hot(9, 26, 132 - 54, 22, where)
        val room = if (c.over(141 - pen.width(slots) - 2, 27, pen.width(slots) + 4, 12)) listOf(Words.t("routes.slots.title"), Words.t("routes.slots.tip")) else null
        c.hot(141 - pen.width(slots) - 2, 27, pen.width(slots) + 4, 12, room)
    }

    private fun ways(c: Ctx, r: TallyRoutes) {
        val best = r.legs.maxOf { it.perHour }.coerceAtLeast(0.01f)
        for ((i, leg) in r.legs.withIndex()) way(c, leg, i, best)
    }

    /** One way: its rank, the town, the hours, the goods of the basket as little pictures, what an hour of it pays (as a number, and as the foot of the slip inked as far as it reaches against the best), and a ring of how old the news of the town is. */
    private fun way(c: Ctx, leg: TallyLeg, i: Int, best: Float) {
        val pen = c.pen
        val book = c.book
        val x0 = 13
        val w = 128
        val h = 21
        val y = 54 + i * 22
        val k = comes(c.since - 0.1 - 0.07 * i, 0.32)
        if (k <= 0f) return
        val x = x0 + ((1f - k) * 16f).roundToInt()
        val picked = i == book.leg
        val lit = c.over(x0, y, w, h)
        pen.rowPaper(x, y, w, h, 0, picked, lit, c.t, k, 40 + i)
        // a row lifted under the mouse carries what is written on it up with it
        val oy = y - (if (lit && !picked) 1 else 0)
        val news = max(0.0, book.ticks() - leg.news)
        pen.text("${i + 1}", x + 4, oy + 6, Ink.fade(if (i == 0) Ink.GOLD else Ink.SOFT, k), i == 0)
        pen.text(pen.fit(Words.town(leg.name), w - 28), x + 13, oy + 3, Ink.fade(Ink.BLACK, k))
        pen.text(Words.hours(leg.hours), x + 13, oy + 11, Ink.fade(Ink.SOFT, k), false, true)
        // the goods the basket holds, as little pictures
        val shown = min(3, leg.lines.size)
        for (j in 0 until shown) pen.goodAt(leg.lines[j].good, x + 39 + j * 9, oy + 11, 8)
        if (leg.lines.size > shown) pen.text("+", x + 39 + shown * 9, oy + 11, Ink.fade(Ink.SOFT, k))
        pen.right(Words.per(leg.perHour), x + w - 14, oy + 11, Ink.fade(Ink.GREEN, k))
        val fill = Beat.out(Beat.clamp(((c.since - 0.2 - 0.07 * i) / 0.5).toFloat()))
        val len = ((w - 2) * (leg.perHour / best).coerceIn(0f, 1f) * fill).roundToInt()
        if (len > 0) pen.fill(x + 1, oy + h - 2, x + 1 + len, oy + h, Ink.fade(if (i == 0) Ink.GREEN else Ink.AMBER, k))
        pen.ageRing((x + w - 8).toFloat(), (oy + 7).toFloat(), 3.5f, news / 1000.0, false, Ink.BLACK, k, 60 + i, true)
        val tip = if (lit) wayTip(c, leg, i) else null
        c.hot(x0, y, w, h, tip) { book.pickLeg(i) }
    }

    private fun wayTip(c: Ctx, leg: TallyLeg, i: Int): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.town(leg.name))
        if (i == 0) lines.add(Words.t("routes.way.best"))
        lines.add(Words.t("routes.way.trip", Words.num(leg.distance), Words.hours(leg.hours), Words.hours(TallyBook.VISIT.toFloat())))
        lines.add(Words.t("routes.way.news", Words.ago(max(0.0, c.book.ticks() - leg.news))))
        if (leg.lines.isNotEmpty()) lines.add(Words.t("routes.way.buy", leg.lines.take(4).joinToString(", ") { Words.goods(it.good, it.units) } + if (leg.lines.size > 4) ", ..." else ""))
        lines.add(Words.t("routes.way.sum", Words.marks(leg.cost), Words.marks(leg.pay), Words.per(leg.perHour)))
        return lines
    }

    /** No way pays: the book's pencilled ways with nothing written, and a word on what to do. */
    private fun none(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.5)
        val cx = 75
        // a road that goes off the page and comes to nothing, walked slowly
        var x = 24f
        var y = 96f
        val walked = ((c.t * 9.0) % 80.0).toFloat()
        for (i in 0 until 22) {
            val nx = x + 5f
            val ny = y - 1.5f + 2.5f * sin(i * 0.8f + 0.7f)
            if (i * 3.6f < walked) pen.dot(nx.roundToInt(), ny.roundToInt(), Ink.fade(Ink.PENCIL, 0.9f * k))
            x = nx
            y = ny
        }
        pen.centered(Words.t("routes.none.title"), cx, 108, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("routes.none.body"), 15, 122, 120, Ink.fade(Ink.SOFT, k), 10, 6, true)
    }

    /** No town to start from. */
    private fun nowhere(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.5)
        // a small compass rose slowly turning, as if looking for a way
        pen.save()
        pen.translate(75f, 88f)
        pen.rotate((sin(c.t * 0.9) * 0.6).toFloat())
        pen.translate(-12.5f, -12.5f)
        pen.sprite("map/rose", 0, 0, 25, 25, Ink.fade(Ink.WHITE, 0.8f * k))
        pen.restore()
        pen.centered(Words.t("routes.nowhere.title"), 75, 112, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("routes.nowhere.body"), 15, 126, 120, Ink.fade(Ink.SOFT, k), 10, 5, true)
    }

    // ---- locked until a Hawker

    /** The page, shut: the rows where the ways would be pencilled in faintly, a padlock over them that rattles now and again, and why. */
    private fun lockedLeft(c: Ctx) {
        val pen = c.pen
        for (i in 0 until 4) {
            val k = comes(c.since - 0.05 * i, 0.35)
            val y = 54 + i * 24
            pen.frame(13, y, 128, 21, Ink.fade(Ink.PENCIL, 0.4f * k))
            pen.rule(26, 70 + 8 * (i % 2), y + 7, Ink.fade(Ink.PENCIL, 0.4f * k), 70 + i)
            pen.rule(26, 96 + 6 * ((i + 1) % 3), y + 14, Ink.fade(Ink.PENCIL, 0.3f * k), 80 + i)
            pen.rule(104, 130, y + 7, Ink.fade(Ink.PENCIL, 0.35f * k), 90 + i)
        }
        val k = comes(c.since - 0.2, 0.4)
        pen.fill(47, 96, 107, 140, Ink.fade(0xFFF4EAD0.toInt(), 0.9f * k))
        pen.frame(47, 96, 60, 44, Ink.fade(Ink.SOFT, 0.5f * k))
        val phase = (c.t % 4.0)
        val rattle = if (phase < 0.5) (sin(phase * 36.0) * 0.16 * (1.0 - phase / 0.5)).toFloat() else 0f
        pen.padlock(77, 106, rattle * k)
        pen.centered(Words.t("routes.locked"), 77, 126, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("routes.locked.body"), 15, 146, 120, Ink.fade(Ink.SOFT, k), 10, 4, true)
        c.hot(13, 54, 128, 90, if (c.over(13, 54, 128, 90)) listOf(Words.t("routes.locked"), Words.t("routes.locked.tip")) else null)
    }

    /** What a Hawker asks, set out as the Standing page sets out the asks. */
    private fun lockedRight(c: Ctx) {
        val pen = c.pen
        val standing = c.book.view.standing
        pen.title(Words.t("routes.locked.title"), RX0, 8, 126, 23)
        val k = comes(c.since - 0.15, 0.4)
        pen.paragraph(Words.t("routes.locked.what"), RX0, 31, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 4, true)
        var y = 80
        pen.text(Words.t("ask.head", Words.rung(1)), RX0, y, Ink.fade(Ink.BLACK, k))
        y += 13
        if (standing.asks.isEmpty()) {
            pen.paragraph(Words.t("ask.none"), RX0, y, RX1 - RX0, Ink.fade(Ink.PENCIL, k), 10, 3, true)
        } else {
            for ((i, ask) in standing.asks.withIndex()) y += askRow(c, RX0, y, RX1 - RX0, ask, 0.3 + 0.1 * i, 3)
        }
    }

    // ---------------------------------------------------------------- the way picked

    override fun right(c: Ctx) {
        val r = c.book.view.routes
        if (r.locked) {
            lockedRight(c)
            return
        }
        val leg = r.legs.getOrNull(c.book.leg)
        if (r.from == TallyRoutes.NONE || leg == null) {
            emptyRight(c)
            return
        }
        detail(c, r, leg)
    }

    private fun emptyRight(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.5)
        pen.title(Words.t("routes.pick"), RX0, 8, 126, 24)
        pen.paragraph(Words.t("routes.pick.body"), RX0, 36, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 6, true)
    }

    private fun detail(c: Ctx, r: TallyRoutes, leg: TallyLeg) {
        val pen = c.pen
        val book = c.book
        val since = min(c.since, c.t - book.legAt)
        val news = max(0.0, book.ticks() - leg.news)
        val name = Words.town(leg.name)
        pen.title(if (pen.headingWidth(name) <= 108) name else pen.fit(name, 108), RX0, 8, 126, 25)
        // the ring of how old the news of the town is, drawing itself as the way is picked
        pen.ageRing(137f, 14f, 6f, news / 1000.0, false, Ink.BLACK, Beat.out(Beat.clamp(((since - 0.05) / 0.5).toFloat())), 26, false)
        pen.text(pen.fit(Words.t("routes.trip", Words.num(leg.distance), Words.hours(leg.hours)), 118), RX0, 29, Ink.BLACK)
        pen.text(pen.fit(newsLine(news), 118), RX0, 39, Ink.SOFT, false, true)
        pen.rule(RX0, RX1, 50, Ink.fade(Ink.SOFT, 0.45f), 27)
        c.hot(RX0, 27, 112, 22, if (c.over(RX0, 27, 112, 22)) listOf(name, Words.t("routes.way.trip", Words.num(leg.distance), Words.hours(leg.hours), Words.hours(TallyBook.VISIT.toFloat())), Words.t("routes.way.news", Words.ago(news))) else null)
        c.hot(129, 6, 16, 16, if (c.over(129, 6, 16, 16)) listOf(Words.t("word.legend"), Words.t("word.legend.tip")) else null)
        basket(c, leg, since)
        sketch(c, leg, since)
        sums(c, r, leg, since)
        bookButton(c, r, leg, since)
    }

    private fun newsLine(news: Double): String = when {
        news >= TallyBook.STRIKE -> Words.t("word.news.struck", Words.age(news))
        news < 500.0 -> Words.t("word.news.fresh")
        else -> Words.t("word.news.age", Words.age(news))
    }

    /** The basket: each line of it a picture of the good, how many, what it costs here and what it brings there, with a seal if it fills haul tickets. */
    private fun basket(c: Ctx, leg: TallyLeg, since: Double) {
        val pen = c.pen
        val rows = min(MAX_LINES, leg.lines.size)
        for (i in 0 until rows) {
            val line = leg.lines[i]
            val y = 54 + i * 17
            val k = comes(since - 0.06 - 0.06 * i, 0.3)
            if (k <= 0f) continue
            val hot = c.over(RX0, y, RX1 - RX0, 17)
            pen.save()
            pen.translate((1f - k) * 10f, 0f)
            if (hot) pen.fill(RX0, y, RX1, y + 17, 0x22FFF8E2)
            pen.good(line.good, RX0, y)
            pen.text(pen.fit(Words.goods(line.good, line.units), 78), RX0 + 19, y + 1, Ink.fade(Ink.BLACK, k))
            val cost = rolls(line.cost, since, 0.15 + 0.06 * i, 0.5)
            val pay = rolls(line.pay, since, 0.15 + 0.06 * i, 0.5)
            pen.text(Words.t("routes.line", Words.num(cost), Words.num(pay)), RX0 + 19, y + 9, Ink.fade(Ink.SOFT, k), false, true)
            val gain = line.pay - line.cost
            pen.right(if (gain >= 0) "+${Words.num(gain)}" else "-${Words.num(-gain)}", RX1 - (if (line.serves.isNotEmpty()) 16 else 2), y + 1, Ink.fade(if (gain >= 0) Ink.GREEN else Ink.RED, k))
            if (line.serves.isNotEmpty()) {
                val n = line.serves.size.toString()
                val sx = RX1 - 13
                pen.seal(0, sx, y + 2)
                pen.text(n, sx + 6 - pen.width(n) / 2 + 1, y + 3, 0xFF3A0A0A.toInt())
                pen.text(n, sx + 6 - pen.width(n) / 2, y + 2, 0xFFFFF0E0.toInt())
            }
            pen.restore()
            c.hot(RX0, y, RX1 - RX0, 17, if (hot) lineTip(line) else null)
        }
        if (leg.lines.size > rows) pen.text(Words.t("routes.more", leg.lines.size - rows), RX0, 54 + rows * 17 + 1, Ink.fade(Ink.SOFT, comes(since - 0.5, 0.3)), false, true)
    }

    private fun lineTip(line: TallyLine): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.goods(line.good, line.units))
        lines.add(Words.t("routes.line.cost", Words.marks(line.cost), Words.price(if (line.units > 0) line.cost.toFloat() / line.units else 0f)))
        lines.add(Words.t("routes.line.pay", Words.marks(line.pay)))
        if (line.serves.isNotEmpty()) lines.add(Words.t(if (line.serves.size == 1) "routes.line.serves.1" else "routes.line.serves", line.serves.size))
        lines.add(Words.t(if (line.slots == 1) "routes.line.slot" else "routes.line.slots", line.slots))
        return lines
    }

    /** The road to the town as a dotted line from one ring to the other, laid from the first as the way is picked, and a walker going along it and back, a longer way slower. */
    private fun sketch(c: Ctx, leg: TallyLeg, since: Double) {
        val pen = c.pen
        val x0 = RX0 + 8
        val x1 = RX1 - 8
        val y = 143
        val seed = (leg.capital xor (leg.capital ushr 32)).toInt()
        val laid = comes(since - 0.25, 0.6)
        val n = 36
        fun at(f: Float): Float = y + 5f + 3.4f * Beat.noise(f * 3.1f, seed) * sin(PI.toFloat() * f)
        for (i in 0..n) {
            val f = i / n.toFloat()
            if (f > laid) break
            pen.dot((x0 + (x1 - x0) * f).roundToInt(), at(f).roundToInt(), Ink.fade(Ink.SOFT, 0.85f))
        }
        pen.sprite("map/city", x0 - 5, y, 10, 10, Ink.fade(Ink.WHITE, comes(since - 0.1, 0.3)))
        pen.sprite("map/city", x1 - 4, y, 10, 10, Ink.fade(Ink.WHITE, comes(since - 0.4, 0.3)))
        // the walker: there, a stay at the stalls, and back
        val cycle = min(9.0, 3.2 + leg.hours * 0.7)
        val p = (c.t / cycle) % 1.0
        val f = when {
            p < 0.42 -> p / 0.42
            p < 0.5 -> 1.0
            p < 0.92 -> 1.0 - (p - 0.5) / 0.42
            else -> 0.0
        }.toFloat()
        if (laid >= 1f) {
            val wx = (x0 + (x1 - x0) * f).roundToInt()
            val wy = at(f).roundToInt()
            val bob = if (p in 0.42..0.5 || p >= 0.92) 0 else (sin(c.t * 9.0) * 1.0).roundToInt()
            pen.sprite("map/player", wx - 4, wy - 9 + bob, 9, 9)
            // at the far town the stay is marked: a ring goes out from it
            if (p in 0.42..0.5) {
                val q = ((p - 0.42) / 0.08).toFloat()
                pen.ring(x1 + 1f, y + 5f, 6f + 6f * Beat.out(q), Ink.fade(Ink.GOLD, 0.9f * (1f - q)), 1, 1f, 0f, 3, 0f)
            }
        }
        c.hot(RX0, y - 4, RX1 - RX0, 18, if (c.over(RX0, y - 4, RX1 - RX0, 18)) listOf(Words.t("routes.road.title"), Words.t("routes.road.tip", Words.hours(leg.hours - TallyBook.VISIT.toFloat()), Words.num(leg.distance), Words.hours(TallyBook.VISIT.toFloat()))) else null)
    }

    /** What the basket costs, brings and leaves, with the slots it takes. The figures count up as the way is picked. */
    private fun sums(c: Ctx, r: TallyRoutes, leg: TallyLeg, since: Double) {
        val pen = c.pen
        val k = comes(since - 0.45, 0.3)
        val cost = rolls(leg.cost, since, 0.4, 0.6)
        val pay = rolls(leg.pay, since, 0.4, 0.6)
        pen.text(pen.fit(Words.t("routes.sum", Words.num(cost), Words.num(pay)), RX1 - RX0), RX0, 158, Ink.fade(Ink.BLACK, k))
        val gain = leg.pay - leg.cost
        val rate = leg.perHour * Beat.out(Beat.clamp(((since - 0.4) / 0.6).toFloat()))
        pen.text(pen.fit(Words.t("routes.profit", Words.num(rolls(gain, since, 0.4, 0.6)), String.format(java.util.Locale.ROOT, "%.1f", rate)), RX1 - RX0), RX0, 167, Ink.fade(Ink.GREEN, k))
        val used = leg.lines.sumOf { it.slots }
        val tip = if (c.over(RX0, 156, RX1 - RX0, 20)) listOf(Words.t("routes.sums.title"), Words.t("routes.sums.tip"), Words.t("routes.slots.use", used, r.slots)) else null
        c.hot(RX0, 156, RX1 - RX0, 20, tip)
    }

    /** The button that books the tickets this basket fills: pencilled in until the haul tickets are in and the basket fills one, and, as the tickets are booked, a seal comes down on it. */
    private fun bookButton(c: Ctx, r: TallyRoutes, leg: TallyLeg, since: Double) {
        val pen = c.pen
        val book = c.book
        val serves = leg.lines.any { it.serves.isNotEmpty() }
        val ok = r.book && serves
        val x = RX0
        val y = 177
        val w = 108
        val h = 13
        val sinceBooked = c.t - book.bookedAt
        val stamped = sinceBooked in 0.0..1.9
        val lit = c.over(x, y, w, h)
        pen.button(x, y, w, h, Words.t(if (stamped) "routes.booked" else "routes.book"), ok, lit)
        val why = when {
            !r.book -> Words.t("routes.book.off")
            !serves -> Words.t("routes.book.none")
            else -> Words.t("routes.book.tip", Words.town(leg.name))
        }
        c.hot(x, y, w, h, if (lit) listOf(Words.t("routes.book.title"), why) else null) { if (ok) book.book(leg.capital) }
        if (stamped) {
            if (book.due("booked@${book.bookedAt}", book.bookedAt)) c.sound("trade.seal_press", 1f, 0.7f)
            pen.stamp(x + w + 10, y + 6, sinceBooked, 0)
        }
    }

    // ---------------------------------------------------------------- keys

    override fun key(book: TallyBook, code: Int): Boolean {
        if (book.view.routes.locked) return false
        return when (code) {
            Keys.DOWN -> step(book, 1)
            Keys.UP -> step(book, -1)
            else -> false
        }
    }

    override fun wheel(book: TallyBook, up: Boolean): Boolean = step(book, if (up) -1 else 1)

    private fun step(book: TallyBook, by: Int): Boolean {
        if (book.view.routes.locked || book.view.routes.legs.size < 2) return false
        book.pickLeg(book.leg + by)
        return true
    }

    private companion object {
        /** The right page's text column, and the most lines of a basket it lists. */
        const val RX0 = 17
        const val RX1 = 149
        const val MAX_LINES = 5
    }
}
