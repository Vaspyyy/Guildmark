package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyGood
import io.github.vaspyyy.guildmark.network.TallyNote
import io.github.vaspyyy.guildmark.network.TallyRoutes
import io.github.vaspyyy.guildmark.network.TallyTown
import io.github.vaspyyy.guildmark.trade.Tally
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Word (living-world/trader/design.md, Part 1): on the left a chart of the capitals the trader knows, each ringed by how old the news of it is, with its road's pennant
 * and a name; on the right the town picked, as its sight has it: the hourglass of the news, what it is short of and has too much of as chips, what is
 * afoot there, its road, the haul tickets out, and the button that files what the trader saw as a report at the board where they stand.
 */
class WordPage : TallyPage("word") {
    // ---------------------------------------------------------------- the chart

    override fun left(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        pen.title(Words.t("page.word"), 9, 8, 141, 1)
        chartGround(pen)
        if (view.towns.isEmpty()) {
            emptyChart(c)
            chartRules(pen)
            return
        }
        val stand = book.env.stand()
        val map = WordMap(view.towns, stand, intArrayOf(CX0 + 4, CY0 + 4, CX1 - 4, CY1 - 4))
        // The rose is faint ground in the corner the towns crowd least; the names are set where they run over nothing, the rose or no
        val rose = decor(map)
        map.place(
            listOf(book.town) + view.towns.map { it.capital },
            { t -> (if (t.capital == book.town) max(pen.width(Words.town(t.name)), pen.width(agePhrase(book, t))) else pen.width(Words.town(t.name))) + 2 },
            { t -> if (t.capital == book.town) LABEL_H else NAME_H },
        )

        // Which town the mouse is over, by its ring or its name
        var hover: WordMap.Spot? = null
        for (s in map.spots) if (hit(c, map, s)) hover = s
        graticule(pen, map)
        compass(pen, rose)
        scaleBar(c, map)
        if (hover != null && map.hasMe && hover.key != view.here) measure(c, map, hover)
        for (s in map.spots) road(c, s)
        for ((i, s) in map.spots.withIndex()) ring(c, s, i, s === hover)
        for (s in map.spots) icon(c, s, s === hover)
        for (s in map.spots) label(c, map, s, s === hover)
        you(c, map)
        legend(c, pen)
        // The picks and what the mouse says
        for (s in map.spots) mark(c, map, s)
        chartRules(pen)
    }

    private fun chartGround(pen: Pen) {
        pen.fill(CX0, CY0, CX1, CY1, 0x2AFFF8E2)
    }

    private fun chartRules(pen: Pen) {
        pen.stroke(CX0.toFloat(), CY0.toFloat(), CX1.toFloat(), CY0.toFloat(), Ink.SOFT, 0.45f, 11)
        pen.stroke(CX0.toFloat(), CY1.toFloat(), CX1.toFloat(), CY1.toFloat(), Ink.SOFT, 0.45f, 12)
        pen.stroke(CX0.toFloat(), CY0.toFloat(), CX0.toFloat(), CY1.toFloat(), Ink.SOFT, 0.45f, 13)
        pen.stroke(CX1.toFloat(), CY0.toFloat(), CX1.toFloat(), CY1.toFloat(), Ink.SOFT, 0.45f, 14)
        val k = Ink.fade(Ink.PENCIL, 0.8f)
        pen.stroke((CX0 + 3).toFloat(), (CY0 + 3).toFloat(), (CX1 - 3).toFloat(), (CY0 + 3).toFloat(), k, 0.3f, 15)
        pen.stroke((CX0 + 3).toFloat(), (CY1 - 3).toFloat(), (CX1 - 3).toFloat(), (CY1 - 3).toFloat(), k, 0.3f, 16)
        pen.stroke((CX0 + 3).toFloat(), (CY0 + 3).toFloat(), (CX0 + 3).toFloat(), (CY1 - 3).toFloat(), k, 0.3f, 17)
        pen.stroke((CX1 - 3).toFloat(), (CY0 + 3).toFloat(), (CX1 - 3).toFloat(), (CY1 - 3).toFloat(), k, 0.3f, 18)
    }

    /**
     * The chart with no town on it: three towns not written in yet, pencilled as rings with a question in each and a road between them that a small figure walks to and
     * fro, stirring each ring as it passes; and a word on how a town gets into the book.
     */
    private fun emptyChart(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.1, 0.5)
        val ring = 7f
        val px = floatArrayOf(36f, 76f, 114f)
        val py = floatArrayOf(78f, 56f, 86f)
        val seg = FloatArray(2) { hypot(px[it + 1] - px[it], py[it + 1] - py[it]) }
        val total = seg[0] + seg[1]
        for (i in 0 until 2) {
            val ux = (px[i + 1] - px[i]) / seg[i]
            val uy = (py[i + 1] - py[i]) / seg[i]
            val reach = comes(c.since - 0.3 - 0.25 * i, 0.5)
            val end = ring + 2 + (seg[i] - 2 * (ring + 2)) * reach
            pen.dotted(px[i] + ux * (ring + 2), py[i] + uy * (ring + 2), px[i] + ux * end, py[i] + uy * end, Ink.fade(Ink.PENCIL, 0.9f * k), 3f)
        }
        // where the walker is: along the road from the first ring to the last and back
        val u = ((c.t * 13.0) % (2.0 * total)).toFloat()
        var rest = if (u < total) u else 2f * total - u
        var wx = px[0]
        var wy = py[0]
        for (i in 0 until 2) {
            if (rest <= seg[i]) {
                wx = px[i] + (px[i + 1] - px[i]) * rest / seg[i]
                wy = py[i] + (py[i + 1] - py[i]) * rest / seg[i]
                break
            }
            rest -= seg[i]
            wx = px[i + 1]
            wy = py[i + 1]
        }
        for (i in 0 until 3) {
            val sweep = comes(c.since - 0.15 - 0.2 * i, 0.5)
            val near = hypot(wx - px[i], wy - py[i]) < 5f
            pen.ring(px[i], py[i], ring + (if (near) 1f else 0f), Ink.fade(Ink.PENCIL, k), 1, sweep, 0.35f, 5 + i, 0.3f)
            pen.centered("?", px[i].roundToInt(), py[i].roundToInt() - 3, Ink.fade(Ink.PENCIL, k * sweep))
        }
        val bob = (sin(c.t * 7.0) * 1.0).roundToInt()
        pen.sprite("map/player", wx.roundToInt() - 5, wy.roundToInt() - 9 + bob, 11, 11, Ink.fade(Ink.WHITE, k))
        pen.centered(Words.t("word.empty.title"), (CX0 + CX1) / 2, 104, Ink.fade(Ink.BLACK, k))
        pen.paragraph(Words.t("word.empty.body"), CX0 + 10, 118, CX1 - CX0 - 20, Ink.fade(Ink.SOFT, k), 10, 5, true)
        if (!c.book.view.word) pen.paragraph(Words.t("word.off"), CX0 + 10, 154, CX1 - CX0 - 20, Ink.fade(Ink.RED, k), 10, 2, true)
    }

    private fun hit(c: Ctx, map: WordMap, s: WordMap.Spot): Boolean {
        val x = s.x.roundToInt()
        val y = s.y.roundToInt()
        if (c.over(x - WordMap.RING - 1, y - WordMap.RING - 1, 2 * WordMap.RING + 3, 2 * WordMap.RING + 3)) return true
        val l = map.label(s.key) ?: return false
        return c.over(l[0], l[1], l[2], l[3])
    }

    // ---- the ground of the chart: a graticule, the rose, a scale

    private fun graticule(pen: Pen, map: WordMap) {
        val step = GRID_STEPS.firstOrNull { it / map.perPx >= 30f } ?: GRID_STEPS.last()
        val k = Ink.fade(Ink.PENCIL, 0.3f)
        val x0 = (CX0 + 4).toFloat()
        val x1 = (CX1 - 4).toFloat()
        val y0 = (CY0 + 4).toFloat()
        val y1 = (CY1 - 4).toFloat()
        // The world's x at the chart's left and right, to find the lines that fall between
        val wxl = (x0 - map.px(0.0)) * map.perPx
        val wxr = (x1 - map.px(0.0)) * map.perPx
        var wx = ceil(wxl / step).toInt() * step
        while (wx <= wxr) {
            val x = map.px(wx.toDouble())
            pen.dotted(x, y0, x, y1, k, 4f)
            wx += step
        }
        val wzt = (y0 - map.py(0.0)) * map.perPx
        val wzb = (y1 - map.py(0.0)) * map.perPx
        var wz = ceil(wzt / step).toInt() * step
        while (wz <= wzb) {
            val y = map.py(wz.toDouble())
            pen.dotted(x0, y, x1, y, k, 4f)
            wz += step
        }
    }

    /** The box the rose is drawn in: a corner of the chart, the one the towns crowd least (the lower left if they are tied). */
    private fun decor(map: WordMap): IntArray {
        var best = intArrayOf()
        var bestCost = Int.MAX_VALUE
        for (corner in 0 until 4) {
            val rx = if (corner % 2 == 1) CX1 - 31 else CX0 + 6
            val ry = if (corner < 2) CY1 - 31 else CY0 + 6
            val rose = intArrayOf(rx, ry, 25, 25)
            var cost = 0
            for (s in map.spots) {
                val ring = intArrayOf(s.x.roundToInt() - WordMap.RING - 1, s.y.roundToInt() - WordMap.RING - 1, 2 * WordMap.RING + 3, 2 * WordMap.RING + 3)
                cost += WordMap.overlap(rose, ring)
            }
            if (map.hasMe) cost += WordMap.overlap(rose, intArrayOf(map.meX.roundToInt() - 6, map.meY.roundToInt() - 8, 12, 12))
            if (cost < bestCost) {
                bestCost = cost
                best = rose
            }
        }
        return best
    }

    private fun compass(pen: Pen, box: IntArray) {
        pen.sprite("map/rose", box[0], box[1], 25, 25, Ink.fade(Ink.WHITE, 0.5f))
    }

    /** The scale, in the page's head to the right of its title where no town is ever: a bar as long as the blocks it names, drawn out from its left end, and the name over it. */
    private fun scaleBar(c: Ctx, map: WordMap) {
        val pen = c.pen
        val k = comes(c.since - 0.3, 0.5)
        val blocks = SCALES.lastOrNull { it / map.perPx <= 58f } ?: SCALES.first()
        val w = (blocks / map.perPx).roundToInt()
        val x = CX1 - w
        val y = 19
        val len = (w * Beat.out(Beat.clamp(((c.since - 0.35) / 0.5).toFloat()))).roundToInt()
        pen.right(Words.t("word.scale", Words.num(blocks)), CX1, 7, Ink.fade(Ink.PENCIL, k), false, true)
        pen.stroke(x, y, x + len, y, Ink.fade(Ink.SOFT, k), 0.3f, 21)
        pen.stroke(x, y - 2, x, y + 2, Ink.fade(Ink.SOFT, k), 0f, 22)
        if (len >= w) {
            pen.stroke(x + w, y - 2, x + w, y + 2, Ink.fade(Ink.SOFT, k), 0f, 23)
            pen.stroke(x + w / 2, y - 1, x + w / 2, y + 1, Ink.fade(Ink.SOFT, k), 0f, 24)
        }
        c.hot(x - 2, 5, w + 4, 18, if (c.over(x - 2, 5, w + 4, 18)) listOf(Words.t("word.scale.title"), Words.t("word.scale.tip", Words.num(TallyBook.WALK.toInt()))) else null)
    }

    // ---- roads, rings and names

    private fun stageColor(stage: Int): Int = when (stage) {
        0 -> Ink.GREEN
        1 -> Ink.AMBER
        2 -> Ink.RED
        else -> Ink.BLACK
    }

    private fun road(c: Ctx, s: WordMap.Spot) {
        val stub = s.stub ?: return
        val pen = c.pen
        val x = s.x.roundToInt()
        val y = s.y.roundToInt()
        val k = comes(c.since - 0.5, 0.4)
        if (k <= 0f) return
        val color = Ink.fade(stageColor(s.town.road), 0.85f * k)
        if (stub.roadX0 != stub.roadX1 || stub.roadY0 != stub.roadY1) {
            pen.dotted((x + stub.roadX0).toFloat(), (y + stub.roadY0).toFloat(), (x + stub.roadX1).toFloat(), (y + stub.roadY1).toFloat(), color, 2f)
        }
        pen.save()
        pen.translate(0f, (1f - k) * 3f)
        pen.pole(x + stub.poleX, y + stub.poleY, WordMap.POLE)
        pen.pennant(x + stub.flagX, y + stub.flagY, s.town.road, c.t, false, stub.flip)
        pen.restore()
        val tip = if (c.over(x + stub.bx, y + stub.by, WordMap.FLAG_W + 2, WordMap.POLE)) roadTip(s.town) else null
        c.hot(x + stub.bx, y + stub.by, WordMap.FLAG_W + 2, WordMap.POLE, tip) { c.book.pick(s.key) }
    }

    private fun ring(c: Ctx, s: WordMap.Spot, index: Int, hover: Boolean) {
        val pen = c.pen
        val book = c.book
        val town = s.town
        val hours = book.age(town) / 1000.0
        val live = town.live
        val seed = (s.key xor (s.key ushr 32)).toInt()
        val start = max(book.pageAt, book.shown[s.key] ?: 0.0)
        val delay = if (start <= book.pageAt + 1e-6) 0.15 + 0.07 * index else 0.0
        var sweep = Beat.out(Beat.clamp(((c.t - start - delay) / 0.6).toFloat()))
        if (sweep <= 0f) return
        val pulse = book.pulsed[s.key]?.let { c.t - it }
        if (pulse != null && pulse in 0.0..0.7) sweep = min(sweep, Beat.out((pulse / 0.7).toFloat()))
        val cx = s.x
        val cy = s.y
        val breathe = if (live) 0.55f * sin((c.t * 2.2).toFloat()) else 0f
        val color = if (live) Ink.GREEN else Ink.BLACK
        val tip = ageInk(hours, live)
        val r = WordMap.RING + breathe
        // the pen that writes it, and the ring
        val end = pen.ring(cx, cy, r, Ink.fade(color, tip.alpha), if (hover) tip.width + 1 else tip.width, sweep, tip.dashes, seed, 0.35f)
        if (sweep < 1f) {
            val ex = end[0].roundToInt()
            val ey = end[1].roundToInt()
            pen.fill(ex - 1, ey - 1, ex + 2, ey + 2, Ink.fade(Ink.RED, 0.9f))
            pen.fill(ex, ey, ex + 1, ey + 1, Ink.fade(Ink.WHITE, 0.8f))
        }
        // the ring of a live town sends a ripple out, slowly, now and again
        if (live) {
            val ph = ((c.t + (seed and 7) * 0.3) % 2.8 / 2.8).toFloat()
            if (ph < 0.9f && sweep >= 1f) pen.ring(cx, cy, WordMap.RING + 2f + 7f * Beat.out(ph), Ink.fade(Ink.GREEN, 0.5f * (1f - ph / 0.9f)), 1, 1f, 0f, seed + 9, 0f)
        }
        // a fresher sight: a wave goes out of the ring
        if (pulse != null && pulse in 0.0..1.4) {
            val p = (pulse / 1.4).toFloat()
            val a = (1f - p) * (1f - p)
            pen.ring(cx, cy, WordMap.RING + 2f + 15f * Beat.out(p), Ink.fade(Ink.GOLD, 0.95f * a), 2, 1f, 0f, seed + 3, 0.2f)
            pen.ring(cx, cy, WordMap.RING + 1f + 9f * Beat.out(min(1f, p * 1.3f)), Ink.fade(color, 0.6f * a), 1, 1f, 0f, seed + 5, 0f)
            if (pulse < 0.35) pen.sparkle(s.x.roundToInt(), s.y.roundToInt() - WordMap.RING - 3, 2, Ink.fade(Ink.GOLD, 1f - (pulse / 0.35).toFloat()))
        }
        if (tip.struck) {
            val a = comes(c.t - start - delay - 0.5, 0.3)
            val ex = WordMap.RING + 3f
            pen.stroke(cx - ex, cy + ex * 0.55f, cx - ex + 2 * ex * a, cy + ex * 0.55f - 2 * ex * 0.55f * a, Ink.fade(Ink.RED, 0.85f), 0.5f, seed + 1, 1)
        }
    }

    private fun icon(c: Ctx, s: WordMap.Spot, hover: Boolean) {
        val k = comes(c.t - max(c.book.pageAt, c.book.shown[s.key] ?: 0.0) - 0.05, 0.3)
        if (k <= 0f) return
        val hours = c.book.age(s.town) / 1000.0
        val struck = hours >= 72.0
        val grow = if (hover) 1.1f else 1f
        val size = (11 * (0.5f + 0.5f * k) * grow).roundToInt().coerceAtLeast(1)
        val tint = if (struck) Ink.fade(Ink.WHITE, 0.55f) else Ink.WHITE
        c.pen.sprite("map/city", s.x.roundToInt() - size / 2, s.y.roundToInt() - size / 2, size, size, tint)
    }

    private fun label(c: Ctx, map: WordMap, s: WordMap.Spot, hover: Boolean) {
        val l = map.label(s.key) ?: return
        val pen = c.pen
        val k = comes(c.t - max(c.book.pageAt, c.book.shown[s.key] ?: 0.0) - 0.3, 0.4)
        if (k <= 0f) return
        val town = s.town
        val book = c.book
        val hours = book.age(town) / 1000.0
        val struck = hours >= 72.0
        val name = Words.town(town.name)
        val picked = s.key == book.town
        val ink = Ink.fade(if (picked) Ink.RED else if (struck) Ink.PENCIL else Ink.BLACK, k)
        val x = l[0] + (l[2] - pen.width(name)) / 2
        pen.halo(name, x, l[1], ink)
        if (struck) pen.stroke(x - 1, l[1] + 4, x + pen.width(name) + 1, l[1] + 3, Ink.fade(Ink.RED, 0.8f * k), 0.4f, 3)
        // How old the news is is told in words under the name of the town picked (the ring tells it for the rest, and the tip says it for the one under the mouse)
        if (picked) {
            val age = agePhrase(book, town)
            pen.halo(age, l[0] + (l[2] - pen.width(age)) / 2, l[1] + 11, Ink.fade(if (town.live) Ink.GREEN else Ink.SOFT, k), true)
        }
        val tip = if (hover) townTip(c, town) else null
        c.hot(l[0] - 1, l[1] - 1, l[2] + 2, l[3] + 2, tip) { book.pick(s.key) }
        val rx = s.x.roundToInt() - WordMap.RING - 1
        val ry = s.y.roundToInt() - WordMap.RING - 1
        c.hot(rx, ry, 2 * WordMap.RING + 3, 2 * WordMap.RING + 3, tip) { book.pick(s.key) }
    }

    /** The picked town is circled again in red; the one under the mouse has its road measured: a dotted line from the trader, and the hours it is. */
    private fun mark(c: Ctx, map: WordMap, s: WordMap.Spot) {
        if (s.key != c.book.town) return
        val book = c.book
        val since = min(c.since, c.t - book.pickedAt)
        val a = Beat.out(Beat.clamp(((since - 0.1) / 0.45).toFloat()))
        if (a <= 0f) return
        val seed = (s.key.toInt() * 7) + 3
        c.pen.ring(s.x, s.y, WordMap.RING + 2.6f + 0.4f * sin((c.t * 2.0).toFloat()), Ink.fade(Ink.RED, 0.9f), 1, a, 0f, seed, 0.5f)
        val l = map.label(s.key) ?: return
        val name = Words.town(s.town.name)
        val x = l[0] + (l[2] - c.pen.width(name)) / 2
        c.pen.stroke((x - 1).toFloat(), (l[1] + 9).toFloat(), x - 1 + (c.pen.width(name) + 2) * a, (l[1] + 9).toFloat(), Ink.fade(Ink.RED, 0.85f), 0.4f, seed + 1)
    }

    private fun measure(c: Ctx, map: WordMap, s: WordMap.Spot) {
        val pen = c.pen
        val x0 = map.meX
        val y0 = map.meY
        val x1 = s.x
        val y1 = s.y
        val d = hypot(x1 - x0, y1 - y0)
        if (d < WordMap.RING + 6) return
        val ux = (x1 - x0) / d
        val uy = (y1 - y0) / d
        val ex = x1 - ux * (WordMap.RING + 2)
        val ey = y1 - uy * (WordMap.RING + 2)
        pen.dotted(x0, y0, ex, ey, Ink.fade(Ink.RED, 0.8f), 3f, ((c.t * 9.0) % 3.0).toFloat())
        val blocks = hypot(Pos.x(s.key) - (c.book.env.stand()?.x ?: Pos.x(s.key).toDouble()), Pos.z(s.key) - (c.book.env.stand()?.z ?: Pos.z(s.key).toDouble()))
        val text = Words.hours((blocks / TallyBook.WALK).toFloat())
        val mx = ((x0 + ex) / 2).roundToInt() - pen.width(text) / 2
        val my = ((y0 + ey) / 2).roundToInt() - 4
        pen.halo(text, mx, my, Ink.RED, true)
    }

    private fun you(c: Ctx, map: WordMap) {
        if (!map.hasMe) return
        val pen = c.pen
        val k = comes(c.since - 0.6, 0.4)
        if (k <= 0f) return
        val bob = (sin(c.t * 2.6) * 1.0).roundToInt()
        var x = map.meX.roundToInt()
        var y = map.meY.roundToInt()
        val here = map.spot(c.book.view.here)
        // Standing in a town's heart: set the marker beside the town's, so it does not hide it
        if (map.off == 0 && here != null && hypot(map.meX - here.x, map.meY - here.y) < 9f) {
            x = here.x.roundToInt() - 8
            y = here.y.roundToInt() - 9
        }
        pen.sprite("map/player", x - 5, y - 8 + bob - ((1f - k) * 6f).roundToInt(), 11, 11, Ink.fade(Ink.WHITE, k))
        if (map.off != 0) {
            val a = when (map.off) { 1 -> -PI / 2; 2 -> 0.0; 3 -> PI / 2; else -> PI }
            pen.dot((x + Math.cos(a) * 8).roundToInt(), (y - 3 + Math.sin(a) * 8).roundToInt(), Ink.RED)
        }
        val ptip = if (c.over(x - 6, y - 9, 12, 12)) listOf(Words.t("word.you"), Words.t("word.you.tip")) else null
        c.hot(x - 6, y - 9, 12, 12, ptip)
    }

    private fun legend(c: Ctx, pen: Pen) {
        val y = CY1 + 5
        if (!c.book.view.word) {
            pen.text(pen.fit(Words.t("word.off"), CX1 - CX0), CX0, y, Ink.RED, false, true)
            return
        }
        val k = comes(c.since - 0.2, 0.5)
        val samples = listOf(0.0 to true, 6.0 to false, 24.0 to false, 72.0 to false)
        var x = CX0 + 2
        for ((hours, live) in samples) {
            val label = if (live) Words.t("age.now") else Words.age(hours * 1000.0)
            pen.ageRing((x + 3).toFloat(), (y + 4).toFloat(), 3.2f, hours, live, if (live) Ink.GREEN else Ink.BLACK, 1f, 5, true)
            if (hours >= 72.0) pen.stroke(x - 1, y + 7, x + 7, y + 1, Ink.fade(Ink.RED, 0.8f), 0f, 2)
            pen.text(label, x + 9, y, Ink.fade(Ink.SOFT, k), false, true)
            x += 11 + pen.width(label) + 6
        }
        c.hot(CX0, y - 2, CX1 - CX0, 12, listOf(Words.t("word.legend"), Words.t("word.legend.tip")))
    }

    // ---- the words of the chart

    /** "14 h" for old news, "now" for live news, set small under a town's name. */
    private fun agePhrase(book: TallyBook, town: TallyTown): String = if (town.live) Words.t("age.now") else Words.age(book.age(town))

    private fun roadTip(town: TallyTown): List<String> =
        listOf(Words.t("word.road.title", Words.town(town.name)), roadLine(town))

    /** "The road to the east is troubled", worded for the road's side and stage. */
    private fun roadLine(town: TallyTown): String {
        val stage = Words.t("road.stage.${town.road.coerceIn(0, 3)}")
        return if (town.side in SIDES) Words.t("word.road", Words.t("road.side.${town.side}"), stage) else Words.t("word.road.any", stage)
    }

    private fun townTip(c: Ctx, town: TallyTown): List<String> {
        val book = c.book
        val lines = ArrayList<String>()
        lines.add(Words.town(town.name))
        lines.add(newsLine(book, town))
        val shorts = town.goods.filter { it.band <= 1 }
        val gluts = town.goods.filter { it.band == 4 }
        if (shorts.isNotEmpty()) lines.add(Words.t("word.short", list(shorts)))
        if (gluts.isNotEmpty()) lines.add(Words.t("word.glut", list(gluts)))
        if (shorts.isEmpty() && gluts.isEmpty()) lines.add(Words.t("word.fair"))
        val stand = book.env.stand()
        if (stand != null && town.capital != book.view.here) {
            val blocks = hypot(Pos.x(town.capital) - stand.x, Pos.z(town.capital) - stand.z)
            lines.add(Words.t("word.walk", Words.hours((blocks / TallyBook.WALK).toFloat()), Words.num(blocks.roundToInt())))
        }
        return lines
    }

    private fun list(goods: List<TallyGood>): String {
        val names = goods.take(3).joinToString(", ") { Words.good(it.good) }
        return if (goods.size > 3) Words.t("word.more", names, goods.size - 3) else names
    }

    private fun newsLine(book: TallyBook, town: TallyTown): String {
        val age = book.age(town)
        return when {
            town.live -> Words.t(if (town.capital == book.view.here) "word.news.here" else "word.news.live")
            age >= TallyBook.STRIKE -> Words.t("word.news.struck", Words.age(age))
            age < 500.0 -> Words.t("word.news.fresh")
            else -> Words.t("word.news.age", Words.age(age))
        }
    }

    // ---------------------------------------------------------------- the town picked

    override fun right(c: Ctx) {
        val pen = c.pen
        val book = c.book
        val town = book.selected
        if (town == null) {
            emptyTown(c)
            return
        }
        val since = min(c.since, c.t - book.pickedAt)
        val hours = book.age(town) / 1000.0
        val struck = hours >= 72.0
        // the name, and the hourglass beside it
        val name = Words.town(town.name)
        val shownName = if (pen.headingWidth(name) <= 108) name else pen.fit(name, 108)
        pen.title(shownName, RX0, 8, 126, 2)
        if (struck) pen.stroke(RX0, 13, RX0 + min(108, pen.headingWidth(shownName)), 12, Ink.fade(Ink.RED, 0.8f), 0.5f, 4, 1)
        hourglass(c, town, 133, 6)
        pen.text(pen.fit(newsLine(book, town), 112), RX0, 29, if (town.live) Ink.GREEN else if (struck) Ink.RED else Ink.BLACK)
        pen.text(pen.fit(sourceLine(town), RX1 - RX0), RX0, 39, Ink.SOFT, false, true)
        c.hot(RX0, 38, RX1 - RX0, 10, sourceTip(book, town))
        pen.rule(RX0, RX1, 50, Ink.fade(Ink.SOFT, 0.45f), 5)
        goods(c, town, since)
        notes(c, town, since)
        roadRow(c, town, since)
        reportRow(c, town)
    }

    private fun emptyTown(c: Ctx) {
        val pen = c.pen
        val k = comes(c.since - 0.2, 0.5)
        pen.title(Words.t("word.none"), RX0, 8, 126, 2)
        pen.paragraph(Words.t("word.none.body"), RX0, 36, RX1 - RX0, Ink.fade(Ink.SOFT, k), 10, 8, true)
        // an hourglass that has no sand yet, turning slowly over
        val a = (c.t * 0.5).toFloat()
        pen.save()
        pen.translate(RX0 + 66f, 112f)
        pen.rotate(a)
        pen.translate(-8f, -14f)
        pen.hourglass(0, 0, 0f, 0f, c.t, false)
        pen.restore()
    }

    /** How the trader got the sight, as the page has room to say it (the news line above already tells how old the news is). */
    private fun sourceLine(town: TallyTown): String = Words.t("word.from.${town.from.coerceIn(0, 3)}")

    /** What the mouse over that line adds: how long ago the trader got it. Nothing for a town kept live (the trader has it this moment). */
    private fun sourceTip(book: TallyBook, town: TallyTown): List<String>? {
        if (town.from == 2 || (town.live && town.capital == book.view.here)) return null
        return listOf(Words.t("word.from", sourceLine(town), Words.ago(max(0.0, book.ticks() - town.copied))))
    }

    /**
     * The hourglass of the news: the sand of three days, run as far as the sight is old. It turns over when another town is picked and when a fresher sight comes, the sand
     * running to its new level as it goes round.
     */
    private fun hourglass(c: Ctx, town: TallyTown, x: Int, y: Int) {
        val pen = c.pen
        val book = c.book
        val f = book.sand(town).toFloat()
        val turn = ((c.t - book.glassAt) / TURN).toFloat()
        val tip = if (c.over(x - 2, y - 2, 20, 32)) listOf(Words.t("word.glass"), Words.t("word.glass.tip", Words.age(max(0.0, 3 * TallyBook.DAY - book.age(town))))) else null
        c.hot(x - 2, y - 2, 20, 32, tip)
        if (turn in 0f..1f) {
            val p = Beat.smooth(turn)
            val from = book.glassFrom.toFloat()
            val upper = Beat.lerp(1f - from, f, p)
            val lower = Beat.lerp(from, 1f - f, p)
            pen.save()
            pen.translate(x + 8f, y + 14f)
            pen.rotate(PI.toFloat() * p)
            pen.translate(-8f, -14f)
            pen.hourglass(0, 0, upper, lower, c.t, false)
            pen.restore()
        } else {
            pen.hourglass(x, y, 1f - f, f, c.t)
        }
    }

    // ---- the goods

    private fun goods(c: Ctx, town: TallyTown, since: Double) {
        val pen = c.pen
        val order = town.goods.withIndex().sortedWith(compareBy({ rank(it.value.band) }, { it.index })).map { it.value }
        for ((i, g) in order.withIndex()) {
            val x = RX0 + (i % COLS) * CELL_W
            val y = GY + (i / COLS) * CELL_H
            val k = comes(since - 0.06 - i * 0.012, 0.28)
            if (k <= 0f) continue
            val chip = (g.band <= 1 || g.band == 4) && hasChip(g.good)
            val hot = c.over(x, y, CELL_W, CELL_H)
            if (hot) pen.fill(x, y, x + CELL_W - 1, y + CELL_H - 2, 0x22FFF8E2)
            // the picture comes up from small; the plain goods are set a little smaller than the chips, which are the news
            val s = (0.55f + 0.45f * k) * (if (chip) 1f else 0.78f) * (if (hot) 1.08f else 1f)
            pen.save()
            pen.translate(x + CELL_W / 2f, y + 9f)
            pen.scale(s, s)
            pen.translate(-8f, -8f)
            if (chip) pen.chip(g.good, g.band <= 1, 0, 0) else pen.good(g.good, 0, 0)
            pen.restore()
            val fill = Beat.out(Beat.clamp(((since - 0.2 - i * 0.012) / 0.45).toFloat()))
            val frac = if (g.target > 0) (g.stock / (2.0 * g.target)).toFloat().coerceIn(0f, 1f) else 0f
            miniGauge(pen, x + 2, y + 18, CELL_W - 5, frac * fill, bandColor(g.band))
            val tip = if (hot) goodTip(c, g, town) else null
            c.hot(x, y, CELL_W, CELL_H, tip)
        }
    }

    private fun rank(band: Int): Int = when (band) {
        0 -> 0
        1 -> 1
        4 -> 2
        3 -> 3
        else -> 4
    }

    private fun bandColor(band: Int): Int = when (band) {
        0 -> 0xFF7A1C14.toInt()
        1 -> 0xFFB5412B.toInt()
        3 -> 0xFF4F8A3A.toInt()
        4 -> Ink.GREEN
        else -> Ink.SOFT
    }

    /** A gauge 3 pixels deep with a tick at the middle: the stock of a good against the target the town keeps. */
    private fun miniGauge(pen: Pen, x: Int, y: Int, w: Int, frac: Float, color: Int) {
        pen.fill(x, y, x + w, y + 3, 0x33201008)
        val full = (w * frac).roundToInt()
        if (full > 0) pen.fill(x, y, x + full, y + 3, color)
        pen.fill(x + w / 2, y - 1, x + w / 2 + 1, y + 4, Ink.fade(Ink.BLACK, 0.7f))
    }

    private fun goodTip(c: Ctx, g: TallyGood, town: TallyTown): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.good(g.good))
        lines.add(Words.t("word.cell.stock", Words.num(g.stock), Words.num(g.target)))
        lines.add(Words.t("word.band.${g.band.coerceIn(0, 4)}"))
        lines.add(Words.t("word.cell.price", priceWord(g.price)))
        val asks = town.tickets.filter { it.good == g.good }
        if (asks.isNotEmpty()) lines.add(Words.t("word.cell.ticket", asks.sumOf { it.units }, Words.shop(asks.first().shop)))
        return lines
    }

    /** What a price against the usual is called: "a third dearer", "the usual", "half the usual". */
    private fun priceWord(ratio: Float): String = Words.t("word.price", String.format(java.util.Locale.ROOT, "%.2f", ratio))

    // ---- what is afoot, the road, the report

    private fun notes(c: Ctx, town: TallyTown, since: Double) {
        val pen = c.pen
        val book = c.book
        val k = comes(since - 0.35, 0.3)
        if (town.notes.isEmpty()) {
            pen.text(pen.fit(Words.t("word.notes.none"), 108), RX0, NY + 4, Ink.fade(Ink.PENCIL, k), false, true)
        }
        for ((i, note) in town.notes.take(6).withIndex()) {
            val x = RX0 + i * 18
            val done = note.until < book.ticks()
            val tint = if (done) Ink.fade(Ink.WHITE, 0.5f) else Ink.WHITE
            val kk = comes(since - 0.35 - i * 0.06, 0.3)
            pen.save()
            pen.translate(x + 8f, NY + 8f)
            pen.scale(0.6f + 0.4f * kk, 0.6f + 0.4f * kk)
            pen.translate(-8f, -8f)
            if (!pen.badge(note.kind, 0, 0, tint)) feast(pen, c.t)
            pen.restore()
            val tip = if (c.over(x, NY, 17, 16)) noteTip(book, note) else null
            c.hot(x, NY, 17, 16, tip)
        }
        // the seal of the haul tickets that are out there
        if (town.tickets.isNotEmpty()) {
            val sx = RX1 - 15
            val kk = Beat.back(Beat.clamp(((since - 0.5) / 0.35).toFloat()))
            val sealed = (12 * kk).roundToInt().coerceAtLeast(1)
            pen.seal(0, sx + 6 - sealed / 2, NY + 8 - sealed / 2)
            val n = town.tickets.size.toString()
            pen.text(n, sx + 6 - pen.width(n) / 2 + 1, NY + 4, 0xFF3A0A0A.toInt())
            pen.text(n, sx + 6 - pen.width(n) / 2, NY + 3, 0xFFFFF0E0.toInt())
            val tip = if (c.over(sx - 1, NY - 1, 15, 15)) ticketsTip(book, town) else null
            c.hot(sx - 1, NY - 1, 15, 15, tip)
        }
    }

    /** The feast, which has no badge of its own: a string of little flags. */
    private fun feast(pen: Pen, t: Double) {
        pen.stroke(1, 4, 15, 4, Ink.SOFT, 0.3f, 7)
        val colors = intArrayOf(Ink.RED, Ink.GOLD, Ink.BLUE, Ink.GREEN)
        for (i in 0 until 4) {
            val x = 2 + i * 3 + (if (i > 1) 1 else 0)
            val sway = (sin(t * 3.0 + i) * 1.0).roundToInt()
            pen.fill(x, 5, x + 3, 6 + 2 + (sway.coerceAtLeast(0)), colors[i])
            pen.fill(x + 1, 8, x + 2, 9 + 2 + sway.coerceAtLeast(0), colors[i])
        }
    }

    private fun noteTip(book: TallyBook, note: TallyNote): List<String> {
        val name = Words.t("word.note.${note.kind}")
        val head = if (note.good.isEmpty()) name else Words.t("word.note.on", name, Words.good(note.good))
        val left = note.until - book.ticks()
        return listOf(head, if (left > 0) Words.t("word.note.ends", Words.left(left)) else Words.t("word.note.over"))
    }

    private fun ticketsTip(book: TallyBook, town: TallyTown): List<String> {
        val lines = ArrayList<String>()
        lines.add(Words.t("word.tickets", town.tickets.size))
        for (t in town.tickets.take(4)) {
            val left = t.due - book.ticks()
            lines.add(Words.t("word.ticket", Words.goods(t.good, t.units), Words.shop(t.shop), Words.marks(t.pay), if (left > 0) Words.left(left) else Words.t("age.gone")))
        }
        if (town.tickets.size > 4) lines.add(Words.t("word.tickets.more", town.tickets.size - 4))
        lines.add(Words.t("word.tickets.old"))
        return lines
    }

    private fun roadRow(c: Ctx, town: TallyTown, since: Double) {
        val pen = c.pen
        val k = comes(since - 0.45, 0.3)
        if (town.road < 0) {
            pen.text(pen.fit(Words.t("word.road.none"), RX1 - RX0), RX0, RY + 1, Ink.fade(Ink.PENCIL, k), false, true)
            return
        }
        pen.pole(RX0, RY - 2, WordMap.POLE + 2)
        pen.pennant(RX0 + 2, RY - 2, town.road, c.t)
        pen.text(pen.fit(roadLine(town), RX1 - RX0 - 18), RX0 + 16, RY + 1, Ink.fade(stageColor(town.road), k))
        val tip = if (c.over(RX0, RY - 3, RX1 - RX0, 12)) listOf(Words.t("word.road.title", Words.town(town.name)), Words.t("word.road.tip")) else null
        c.hot(RX0, RY - 3, RX1 - RX0, 12, tip)
    }

    private fun reportRow(c: Ctx, town: TallyTown) {
        val pen = c.pen
        val book = c.book
        val view = book.view
        val ok = town.report && view.reports > 0
        val since = c.t - book.filedAt
        val stamping = book.filedTown == town.capital && since in 0.0..1.8
        if (ok || stamping) {
            val lit = c.over(RX0, PY, 74, 13)
            pen.button(RX0, PY, 74, 13, Words.t("word.report"), ok, lit)
            val tip = if (lit) listOf(Words.t("word.report.title"), Words.t("word.report.tip", Tally.STANDING.roundToInt(), Tally.XP.roundToInt(), view.reports)) else null
            c.hot(RX0, PY, 74, 13, tip) { if (ok) book.report(town.capital) }
            // the quills left today
            for (i in 0 until Tally.REPORTS) {
                val used = i >= view.reports
                pen.quill(RX0 + 82 + i * 10, PY + 2, if (used) Ink.fade(Ink.WHITE, 0.25f) else Ink.WHITE)
            }
        } else {
            // Why there is no button: a hand's note in pencil, two lines at most, kept clear of the corner that turns the page
            pen.paragraph(reportWhy(book, town), RX0, PY - 1, 112, Ink.PENCIL, 10, 2, true)
        }
        // the seal comes down on the slip: it is large at first, and settles, with a ring of dust
        if (stamping) pen.stamp(RX0 + 37, PY + 6, since, 1)
    }

    private fun reportWhy(book: TallyBook, town: TallyTown): String {
        val view = book.view
        return when {
            !view.word -> Words.t("word.report.off")
            view.here == TallyRoutes.NONE -> Words.t("word.report.nowhere")
            town.capital == view.here -> Words.t("word.report.here")
            view.reports <= 0 -> Words.t("word.report.tired", Tally.REPORTS)
            town.board < 0 -> Words.t("word.report.norow")
            town.tick - town.board < 1000L -> Words.t("word.report.same")
            else -> Words.t("word.report.stale", Words.age((town.tick - town.board).toDouble()), Words.age(Tally.FRESHER.toDouble()))
        }
    }

    private companion object {
        /** The chart's box on the left page, and the right page's text column. */
        const val CX0 = 9
        const val CY0 = 33
        const val CX1 = 141
        const val CY1 = 166
        const val RX0 = 17
        const val RX1 = 149

        /** The goods' grid on the right page: six to a row, and its top; then the rows of what is afoot, the road and the report. */
        const val COLS = 6
        const val CELL_W = 22
        const val CELL_H = 22
        const val GY = 54
        const val NY = 143
        const val RY = 161
        const val PY = 174

        /** The height of a town's name on the chart, and of the name and the age under it for the town picked. */
        const val NAME_H = 9
        const val LABEL_H = 19

        /** Seconds the hourglass takes to turn over. */
        const val TURN = 0.7

        val SIDES = setOf("n", "e", "s", "w")
        val GRID_STEPS = intArrayOf(100, 250, 500, 1000, 2500, 5000)
        val SCALES = intArrayOf(50, 100, 250, 500, 1000, 2500, 5000)
    }
}
