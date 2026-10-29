package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.network.BoardSight
import io.github.vaspyyy.guildmark.network.BoardTown
import io.github.vaspyyy.guildmark.network.BoardView
import io.github.vaspyyy.guildmark.network.HaulBoardActPayload
import io.github.vaspyyy.guildmark.trade.HaulKind
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The client's side of the Haul Board's screen: the screen the server's page opens or brings up to date. */
object HaulBoardLink {
    /** The server sent [view]: [open] opens the screen (or, if it is open, brings it up to date); else it only updates the open one. */
    fun receive(view: BoardView, open: Boolean) {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.gui.screen() as? HaulBoardScreen
        if (screen != null) screen.update(view) else if (open) minecraft.gui.setScreen(HaulBoardScreen(view))
    }
}

/**
 * The Haul Board read close to (living-world/trader/design.md, Part 1): the board itself on the screen, in its dark oak frame with the town's name on its
 * header plank. On the left the slate, a chalked row for each town round about (its glass with the sand run down as far as the news is old, what it is
 * short of in red and has too much of in green, its worst road's pennant, the tickets it had out); on the right the rail, the chosen town's tickets each
 * with a Book button (this town's as they stand, another's as the board heard of them), and at the foot the board's sight of the town beside the one the
 * reader's Tally had. Rows are chalked in as the screen comes up, the tickets swing in on their pegs when a town is picked, and a ticket booked comes down
 * stamped in the reader's colour. It only draws what the server sends ([BoardView]) and asks it every two seconds for fresh news; Book asks the server,
 * which books it as the shop's counter would.
 */
class HaulBoardScreen(private var view: BoardView) : Screen(Component.translatable("block.guildmark.haul_board")) {
    private val opened = Util.getMillis()

    /** Seconds since the screen opened, as of this frame; and when the last page came. */
    private var t = 0.0
    private var viewAt = 0.0

    /** The town picked on the slate ([HERE] for this town's own tickets), since when, and how far the rail is scrolled. */
    private var pick = HERE
    private var pickAt = 0.0
    private var scroll = 0

    private var asked = 0.0
    private var pressed = -1
    private var pressedAt = -10.0

    /** When each row was last chalked anew (by the capital), and each of this town's tickets the reader came to hold (by number), for their stamp. */
    private val chalkedAt = HashMap<Long, Double>()
    private val stampedAt = HashMap<Int, Double>()
    private var mine: Map<Int, Int> = view.here.associate { it.haul.id to it.haul.mine }
    private var ticks: Map<Long, Long> = view.towns.associate { it.capital to it.board.tick }

    private class Zone(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val tip: List<String>?, val click: (() -> Unit)?)

    private val zones = ArrayList<Zone>()
    private var mx = FAR
    private var my = FAR
    private var left = 0
    private var top = 0
    private var scale = 1f

    init {
        if (view.here.isEmpty() && view.towns.isNotEmpty()) pick = view.towns.first().capital
    }

    /** The server sent a newer page: rows whose news moved are chalked anew, and a ticket the reader now holds is stamped in their colour. */
    fun update(next: BoardView) {
        t = elapsed()
        for (town in next.towns) if (ticks[town.capital] != town.board.tick) chalkedAt[town.capital] = t
        for (h in next.here) {
            if (h.haul.mine > 0 && (mine[h.haul.id] ?: 0) == 0) {
                stampedAt[h.haul.id] = t
                sound("trade.board_stamp", 1f, 0.8f)
            }
        }
        mine = next.here.associate { it.haul.id to it.haul.mine }
        ticks = next.towns.associate { it.capital to it.board.tick }
        if (pick != HERE && next.towns.none { it.capital == pick }) pick = HERE
        view = next
        viewAt = t
        pressed = -1
    }

    private fun elapsed(): Double = (Util.getMillis() - opened) / 1000.0

    /** The overworld's clock now, run on from the last page's. */
    private fun now(): Double = view.now + (t - viewAt) * 20.0

    override fun isPauseScreen(): Boolean = false

    override fun isInGameUi(): Boolean = true

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun tick() {
        t = elapsed()
        if (t - asked >= ASK) {
            asked = t
            ClientPacketDistributor.sendToServer(HaulBoardActPayload(HaulBoardActPayload.REFRESH, 0))
        }
        // Walked off from the board: the screen is put away
        val player = Minecraft.getInstance().player ?: return
        val at = BlockPos.of(view.board)
        if (player.distanceToSqr(at.x + 0.5, at.y + 1.0, at.z + 0.5) > REACH * REACH) onClose()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val fadeIn = min(1f, (Util.getMillis() - opened) / 300f)
        graphics.fill(0, 0, width, height, ARGB.color(fadeIn * 0.62f, 0x06060B))
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        t = elapsed()
        val pen = GuiPen(graphics, font)
        scale = min(1f, min((width - 8) / W.toFloat(), (height - 8) / H.toFloat()))
        left = ((width - W * scale) / 2).roundToInt()
        top = ((height - H * scale) / 2).roundToInt()
        mx = ((mouseX - left) / scale).toInt()
        my = ((mouseY - top) / scale).toInt()
        zones.clear()
        // The board comes up, a little rise and a settling
        val up = Beat.back(Beat.clamp((t / OPEN).toFloat()))
        pen.save()
        pen.translate(left.toFloat(), top.toFloat() + (1f - up) * 10f)
        pen.scale(scale, scale)
        frame(pen)
        slate(pen)
        rail(pen)
        foot(pen)
        pen.restore()
        val tip = zones.lastOrNull { mx >= it.x0 && mx < it.x1 && my >= it.y0 && my < it.y1 && it.tip != null }?.tip
        tip?.let { drawTip(pen, it, mouseX, mouseY) }
    }

    // ---- The frame ----

    private fun frame(pen: Pen) {
        pen.fill(-4, -3, W + 4, H + 5, 0x58000000)
        pen.fill(0, 0, W, H, OAK_DARK)
        pen.fill(2, 2, W - 2, H - 2, OAK)
        // The grain of the oak, and the iron at its corners
        for (i in 0 until 9) pen.stroke(4f, 6f + i * 23f, W - 4f, 7f + i * 23f + (i % 3), OAK_GRAIN, 0.8f, 40 + i)
        for ((cx, cy) in listOf(4 to 4, W - 12 to 4, 4 to H - 12, W - 12 to H - 12)) {
            pen.fill(cx, cy, cx + 8, cy + 8, IRON)
            pen.dot(cx + 2, cy + 2, IRON_LIGHT)
            pen.dot(cx + 5, cy + 5, IRON_LIGHT)
        }
        // The header plank: the board's name gilt, the town's after it
        pen.fill(14, 4, W - 14, 20, PLANK)
        pen.fill(14, 4, W - 14, 5, PLANK_LIGHT)
        pen.fill(14, 19, W - 14, 20, OAK_DARK)
        val name = Words.tr("gui.guildmark.trade.board.title").uppercase(Locale.ROOT)
        pen.heading(name, 20, 7, GILT)
        val town = Words.town(view.town)
        pen.right(pen.fit(town, W - 60 - pen.headingWidth(name)), W - 20, 8, PARCHMENT)
    }

    // ---- The slate ----

    private fun slate(pen: Pen) {
        pen.fill(SX0 - 1, SY0 - 1, SX1 + 1, SY1 + 1, OAK_DARK)
        pen.fill(SX0, SY0, SX1, SY1, SLATE)
        // Old chalk wiped over, never quite gone
        for (i in 0 until 6) pen.stroke(SX0 + 6f + i * 29f, SY0 + 30f + (i * 37) % 120, SX0 + 26f + i * 29f, SY0 + 34f + (i * 37) % 120, SMEAR, 1.4f, 70 + i, 3)
        val title = Words.tr("gui.guildmark.trade.board.slate")
        val k = comes(t - 0.05, 0.4)
        pen.clip(SX0, SY0, SX0 + 6 + ((pen.width(title) + 4) * k).roundToInt(), SY0 + 14)
        pen.text(title, SX0 + 6, SY0 + 4, CHALK_HEAD)
        pen.unclip()
        pen.stroke(SX0 + 5f, SY0 + 13.5f, SX0 + 5f + (pen.width(title) + 2) * k, SY0 + 13.5f, Ink.fade(CHALK_HEAD, 0.5f), 0.4f, 9)
        if (!view.word || view.towns.isEmpty()) {
            val words = Words.tr(if (!view.word) "gui.guildmark.trade.board.slate.off" else "gui.guildmark.trade.board.slate.empty")
            pen.paragraph(words, SX0 + 8, SY0 + 30, SX1 - SX0 - 16, Ink.fade(CHALK, 0.7f * comes(t - 0.3)))
            return
        }
        for ((i, town) in view.towns.withIndex()) row(pen, town, i, SY0 + 18 + i * ROW_H)
    }

    /** One town's row: its glass, its name and how old its news is, its chips, its road's pennant and its tickets' seal; picked with a ring of chalk. */
    private fun row(pen: Pen, town: BoardTown, i: Int, y: Int) {
        val x = SX0 + 4
        val w = SX1 - SX0 - 8
        val s = town.board
        val age = ageOf(s)
        val struck = age >= STRUCK
        val dim = if (struck) 0.45f else 1f
        val picked = pick == town.capital
        val over = !pressedOver() && mx >= x && mx < x + w && my >= y && my < y + ROW_H - 1
        // Chalked in, left to right, as the screen comes up (and again when its news moves)
        val since = t - (chalkedAt[town.capital] ?: (0.15 + i * 0.07))
        val reveal = Beat.smooth(Beat.clamp((since / 0.45).toFloat()))
        if (reveal <= 0f) return
        if (over && !picked) pen.fill(x, y, x + w, y + ROW_H - 1, 0x12FFFFFF)
        if (picked) {
            pen.fill(x, y, x + w, y + ROW_H - 1, 0x1CFFFFFF)
            pen.stroke(x + 0.5f, y + 0.5f, x + w - 0.5f, y + 0.5f, Ink.fade(CHALK, 0.6f), 0.5f, 11 + i)
            pen.stroke(x + 0.5f, y + ROW_H - 1.5f, x + w - 0.5f, y + ROW_H - 1.5f, Ink.fade(CHALK, 0.6f), 0.5f, 12 + i)
            val bob = (sin(t * 4.0) * 1.0).roundToInt()
            for (j in 0 until 3) pen.fill(x - 4 + bob + j, y + 7 + j, x - 3 + bob + j, y + 13 - j, CHALK)
        }
        pen.clip(x, y, x + (w * reveal).roundToInt() + 1, y + ROW_H)
        // The glass: the sand run down as far as the news is old, still running while there is any above
        val f = (age / STRUCK).toFloat().coerceIn(0f, 1f)
        pen.save()
        pen.translate(x + 2f, y + 1.5f)
        pen.scale(0.6f, 0.6f)
        pen.hourglass(0, 0, 1f - f, f, t + i * 0.37, stream = !struck)
        pen.restore()
        val glassTip = listOf(Words.t("word.glass"), news(s), Words.t("word.glass.tip", Words.left(STRUCK - age)))
        hot(x, y, 12, ROW_H - 1, glassTip, pickTown(town))
        // The name and the age under it
        val nameColour = Ink.fade(CHALK, dim)
        val name = pen.fit(Words.town(town.name), 72)
        pen.text(name, x + 14, y + 2, nameColour)
        if (struck) pen.stroke(x + 13f, y + 6f, x + 15f + pen.width(name), y + 5f, Ink.fade(CHALK, 0.8f), 0.6f, 20 + i)
        if (town.by.isNotEmpty()) pen.quill(x + 16 + pen.width(name), y + 1, Ink.fade(Ink.WHITE, dim))
        pen.text(Words.age(age), x + 14, y + 11, Ink.fade(CHALK_DIM, dim))
        val nameTip = ArrayList<String>()
        nameTip.add(Words.town(town.name))
        nameTip.add(Words.tr("gui.guildmark.trade.board.distance", Words.num(town.distance)))
        if (town.by.isNotEmpty()) nameTip.add(Words.tr("gui.guildmark.trade.board.by", town.by))
        hot(x + 12, y, 76, ROW_H - 1, nameTip, pickTown(town))
        // The chips: up to three of each
        for ((j, id) in s.shorts.take(3).withIndex()) chip(pen, id, true, x + 90 + j * 10, y + 5, dim, pickTown(town))
        for ((j, id) in s.gluts.take(3).withIndex()) chip(pen, id, false, x + 122 + j * 10, y + 5, dim, pickTown(town))
        if (s.shorts.isEmpty() && s.gluts.isEmpty()) pen.text("-", x + 116, y + 6, Ink.fade(CHALK_DIM, dim))
        // The road's pennant, flying
        if (s.road >= 0) {
            pen.pole(x + 154, y + 3, 14)
            pen.pennant(x + 156, y + 4, s.road, t + i * 0.5, calm = struck)
            hot(x + 152, y, 14, ROW_H - 1, listOf(Words.t("word.road.title", Words.town(town.name)), roadLine(s)), pickTown(town))
        }
        // How many tickets it had out, by the hall's seal
        if (town.heard.isNotEmpty()) {
            pen.sprite("trade/seal_tint", x + 168, y + 4, 9, 9, Ink.fade(HALL_WAX, dim))
            pen.text(town.heard.size.toString(), x + 179, y + 5, Ink.fade(CHALK, dim))
            hot(x + 166, y, 20, ROW_H - 1, listOf(Words.t("word.tickets", town.heard.size), Words.t("word.tickets.old")), pickTown(town))
        }
        pen.unclip()
        hot(x, y, w, ROW_H - 1, null, pickTown(town))
    }

    private fun chip(pen: Pen, id: String, short: Boolean, x: Int, y: Int, dim: Float, click: () -> Unit) {
        if (!hasChip(id)) return
        pen.save()
        pen.translate(x.toFloat(), y.toFloat())
        pen.scale(CHIP / 16f, CHIP / 16f)
        pen.chip(id, short, 0, 0, Ink.fade(Ink.WHITE, dim))
        pen.restore()
        hot(x, y, CHIP, CHIP, listOf(Words.t(if (short) "word.short" else "word.glut", Words.good(id))), click)
    }

    private fun pickTown(town: BoardTown): () -> Unit = {
        if (pick != town.capital) {
            pick = town.capital
            pickAt = t
            scroll = 0
            sound("trade.tally_page", 1.1f, 0.5f)
        }
    }

    // ---- The rail ----

    private fun rail(pen: Pen) {
        pen.fill(RX0 - 1, RY0 - 1, RX1 + 1, RY1 + 1, OAK_DARK)
        pen.fill(RX0, RY0, RX1, RY1, PANEL)
        for (i in 0 until 5) pen.stroke(RX0 + 2f, RY0 + 12f + i * 34f, RX1 - 2f, RY0 + 13f + i * 34f, PANEL_GRAIN, 0.7f, 90 + i)
        val town = view.towns.firstOrNull { it.capital == pick }
        // The heading: this town's tickets, or another's as the board heard them, with a way back to this town's
        val heading = if (town == null) Words.tr("gui.guildmark.trade.board.here.title", Words.town(view.town))
        else Words.tr("gui.guildmark.trade.board.heard.title", Words.town(town.name))
        val hereW = if (town != null) pen.width(Words.tr("gui.guildmark.trade.board.here")) + 10 else 0
        pen.text(pen.fit(heading, RX1 - RX0 - 12 - hereW), RX0 + 6, RY0 + 4, PARCHMENT)
        if (town != null) {
            val label = Words.tr("gui.guildmark.trade.board.here")
            val bx = RX1 - hereW - 4
            val lit = mx >= bx && mx < bx + hereW && my >= RY0 + 2 && my < RY0 + 14
            pen.button(bx, RY0 + 2, hereW, 12, label, true, lit)
            hot(bx, RY0 + 2, hereW, 12, listOf(Words.tr("gui.guildmark.trade.board.here.tip", Words.town(view.town)))) {
                pick = HERE
                pickAt = t
                scroll = 0
                sound("trade.tally_page", 1.1f, 0.5f)
            }
        }
        // The rail the tickets hang from
        pen.fill(RX0 + 3, RY0 + 17, RX1 - 3, RY0 + 19, IRON)
        pen.fill(RX0 + 3, RY0 + 17, RX1 - 3, RY0 + 18, IRON_LIGHT)
        val lines = if (town == null) hereSlips() else heardSlips(town)
        val y0 = RY0 + 22
        val room = (FOOT - y0) / SLIP_H
        scroll = scroll.coerceIn(0, max(0, lines.size - room))
        if (lines.isEmpty()) {
            val words = when {
                !view.hauls -> Words.tr("gui.guildmark.trade.board.hauls.off")
                town == null -> Words.tr("gui.guildmark.trade.board.here.none")
                else -> Words.tr("gui.guildmark.trade.board.heard.none", Words.town(town.name))
            }
            pen.paragraph(words, RX0 + 8, y0 + 8, RX1 - RX0 - 16, Ink.fade(PARCHMENT, 0.85f * comes(t - pickAt - 0.1)))
            return
        }
        for (k in 0 until min(room, lines.size - scroll)) slip(pen, lines[scroll + k], k, RX0 + 4, y0 + k * SLIP_H)
        if (lines.size > room) {
            // How far down the list the rail is
            val track = FOOT - y0 - 4
            val knob = max(8, track * room / lines.size)
            val at = y0 + (track - knob) * scroll / max(1, lines.size - room)
            pen.fill(RX1 - 3, y0, RX1 - 2, y0 + track, 0x30000000)
            pen.fill(RX1 - 3, at, RX1 - 2, at + knob, PARCHMENT)
        }
    }

    /** A ticket as the rail shows it, whichever kind it is. */
    private class Slip(
        val id: Int, val good: String, val units: Int, val pay: Int, val shop: String, val kind: Int, val dueTicks: Double, val seal: Int,
        val mine: Int, val why: String, val deposit: Int, val others: String, val heard: Boolean, val hours: Int,
    )

    private fun hereSlips(): List<Slip> = if (!view.hauls) listOf() else view.here.map { h ->
        val m = h.haul
        val units = if (m.mine > 0) m.mine else if (m.open > 0) m.open else m.units - m.done
        Slip(m.id, m.good, units, m.pay, h.shop, m.kind, m.hours * 1000.0, m.seal, m.mine, m.why, m.deposit, m.who, false, m.hours)
    }

    private fun heardSlips(town: BoardTown): List<Slip> = if (!view.hauls) listOf() else town.heard.map { h ->
        val kind = HaulKind.entries.indexOfFirst { it.id == h.kind }.coerceAtLeast(0)
        val left = h.due - view.now
        Slip(h.id, h.good, h.units, h.pay, h.shop, kind, left.toDouble(), -1, 0, "", 0, "", true, (left / 1000L).toInt())
    }

    /** One ticket on its peg: it swings in when the town is picked, and it is stamped in the reader's colour once they hold it. */
    private fun slip(pen: Pen, s: Slip, k: Int, x: Int, y: Int) {
        val w = RX1 - RX0 - 10
        val h = SLIP_H - 3
        val since = t - pickAt - 0.08 - k * 0.06
        if (since <= 0.0) return
        val e = Beat.out(Beat.clamp((since / 0.3).toFloat()))
        val swing = Beat.swing(Beat.clamp((since / 1.4).toFloat()), 2.5f, 4f) * 0.06f * (if (k % 2 == 0) 1f else -1f)
        val kindArt = when (HaulKind.entries.getOrNull(s.kind)) {
            HaulKind.RELIEF -> 1
            HaulKind.FEAST -> 2
            else -> 0
        }
        val buttonX = x + w - 38
        val buttonY = y + 7
        val overButton = mx >= buttonX && mx < buttonX + 34 && my >= buttonY && my < buttonY + 12
        val over = mx >= x && mx < x + w && my >= y && my < y + h
        pen.save()
        // Hung from its peg at the middle of its top, swinging to rest
        pen.translate(x + w / 2f, y - 2f - (1f - e) * 10f)
        pen.rotate(swing * (1f - e * 0.5f))
        pen.translate(-(x + w / 2f), -y.toFloat())
        val lift = if (over && !overButton) 1 else 0
        pen.slip(x, y - lift, w, h, kindArt, Ink.fade(Ink.WHITE, e))
        pen.fill(x + w / 2 - 1, y - 3, x + w / 2 + 1, y + 1, IRON)
        val a = e
        pen.goodAt(s.good, x + 5, y + 5 - lift)
        val count = Words.goods(s.good, s.units)
        pen.text(pen.fit(count, w - 90), x + 25, y + 4 - lift, Ink.fade(Ink.BLACK, a))
        val under = if (s.mine > 0) Words.tr("gui.guildmark.trade.board.yours", Words.num(s.mine)) else Words.shop(s.shop)
        pen.text(pen.fit(under, w - 92), x + 25, y + 14 - lift, Ink.fade(if (s.mine > 0) Ink.GREEN else Ink.SOFT, a), false, true)
        val payX = buttonX - 8
        pen.marksRight(s.pay, payX, y + 4 - lift, Ink.fade(Ink.BLACK, a), a)
        val days = daysLeft(s.dueTicks)
        pen.notches(payX, y + 14 - lift, days, t, a, urgent = days <= 1)
        // The booker's seal (the reader's, stamped on as they took it); the hall's mark on one heard of
        val stamped = stampedAt[s.id]
        val sealX = payX - 34
        if (stamped != null && t - stamped < 1.3) pen.stamp(sealX + 6, y + 10, t - stamped, view.seal, hold = 0.4)
        if (s.seal >= 0 && (stamped == null || t - stamped > 0.3)) pen.seal(s.seal, sealX, y + 4 - lift, Ink.fade(Ink.WHITE, a))
        else if (s.seal < 0 && !s.heard) pen.sprite("trade/seal_tint", sealX + 1, y + 5 - lift, 10, 10, Ink.fade(HALL_WAX, 0.55f * a))
        // Book
        val on = view.hauls && s.mine == 0 && s.why.isEmpty() && pressed != s.id
        val label = Words.tr(
            when {
                s.mine > 0 -> "gui.guildmark.trade.board.booked"
                s.why == "taken" -> "gui.guildmark.trade.board.taken"
                else -> "gui.guildmark.trade.board.book"
            },
        )
        pen.button(buttonX, buttonY - lift, 34, 12, label, on, overButton, down = pressed == s.id)
        pen.restore()
        // What the ticket says, in words
        val tip = ArrayList<String>()
        tip.add(Words.goods(s.good, s.units) + " - " + Words.shop(s.shop))
        tip.add(Words.kind(HaulKind.entries.getOrNull(s.kind)?.id ?: "parcel") + ", " + Words.marks(s.pay))
        tip.add(Words.tr("gui.guildmark.trade.board.due", Words.left(s.dueTicks)))
        if (s.others.isNotEmpty()) tip.add(Words.tr("gui.guildmark.trade.board.others", s.others))
        hot(x, y, w - 40, h, tip, null)
        val bookTip = when {
            !view.hauls -> listOf(Words.tr("gui.guildmark.trade.board.hauls.off"))
            s.mine > 0 -> listOf(Words.tr("gui.guildmark.trade.board.yours", Words.num(s.mine)))
            s.why.isNotEmpty() -> listOf(Words.tr("gui.guildmark.trade.haul.refused.${s.why}"))
            s.heard -> listOf(Words.tr("gui.guildmark.trade.board.book"), Words.tr("gui.guildmark.trade.board.book.heard.tip"))
            else -> listOf(Words.tr("gui.guildmark.trade.board.book"), Words.tr("gui.guildmark.trade.board.book.tip", Words.marks(s.deposit), Words.left(s.dueTicks)))
        }
        hot(buttonX, buttonY, 34, 12, bookTip, if (on) ({ book(s.id) }) else null)
    }

    private fun book(id: Int) {
        pressed = id
        pressedAt = t
        Ui.select()
        ClientPacketDistributor.sendToServer(HaulBoardActPayload(HaulBoardActPayload.BOOK, id))
    }

    /** Whether a press is still waiting for its answer (the slate does not light up under it meanwhile). */
    private fun pressedOver(): Boolean = pressed >= 0 && t - pressedAt < 2.0

    // ---- The foot: the board's sight beside the Tally's ----

    private fun foot(pen: Pen) {
        val y = FOOT + 2
        pen.fill(RX0 + 3, y - 2, RX1 - 3, y - 1, OAK_DARK)
        val k = comes(t - 0.3)
        val town = view.towns.firstOrNull { it.capital == pick }
        if (town == null) {
            val words = when {
                !view.tally -> Words.tr("gui.guildmark.trade.board.foot.notally")
                view.copied == 1 -> Words.tr("gui.guildmark.trade.board.foot.copied.one")
                view.copied > 1 -> Words.tr("gui.guildmark.trade.board.foot.copied", view.copied)
                else -> Words.tr("gui.guildmark.trade.board.foot.kept")
            }
            pen.paragraph(words, RX0 + 6, y + 3, RX1 - RX0 - 30, Ink.fade(PARCHMENT, 0.9f * k), 10, 2, italic = true)
            pen.seal(view.seal, RX1 - 18, y + 6, Ink.fade(Ink.WHITE, k))
            hot(RX1 - 20, y + 4, 16, 16, listOf(Words.tr("gui.guildmark.trade.board.seal"), Words.tr("gui.guildmark.trade.board.seal.tip")), null)
            return
        }
        sight(pen, Words.tr("gui.guildmark.trade.board.foot.board"), town.board, RX0 + 6, y + 2, k)
        val tally = town.tally
        if (!view.tally) {
            pen.text(pen.fit(Words.tr("gui.guildmark.trade.board.foot.notally"), RX1 - RX0 - 12), RX0 + 6, y + 15, Ink.fade(PARCHMENT_DIM, k), false, true)
        } else if (tally == null) {
            pen.text(Words.tr("gui.guildmark.trade.board.foot.tally") + ": " + Words.tr("gui.guildmark.trade.board.foot.none"), RX0 + 6, y + 15, Ink.fade(PARCHMENT_DIM, k), false, true)
        } else {
            sight(pen, Words.tr("gui.guildmark.trade.board.foot.tally"), tally, RX0 + 6, y + 15, k)
        }
    }

    /** One sight on a line: whose it is, how old, and its chips; the tip says how it was come by. */
    private fun sight(pen: Pen, label: String, s: BoardSight, x: Int, y: Int, k: Float) {
        val age = ageOf(s)
        val head = "$label: " + Words.age(age)
        val hw = pen.width(head)
        pen.text(pen.fit(head, 86), x, y, Ink.fade(if (age >= STRUCK) PARCHMENT_DIM else PARCHMENT, k))
        var cx = x + min(hw, 86) + 4
        for (id in s.shorts.take(3)) if (hasChip(id)) { chipAt(pen, id, true, cx, y - 1, k); cx += 10 }
        cx += 2
        for (id in s.gluts.take(3)) if (hasChip(id)) { chipAt(pen, id, false, cx, y - 1, k); cx += 10 }
        val from = Words.tr("gui.guildmark.trade.board.from.${s.from.coerceIn(0, 3)}")
        hot(x, y - 2, RX1 - RX0 - 12, 12, listOf(label + ", " + from, news(s), shortLine(s)), null)
    }

    private fun chipAt(pen: Pen, id: String, short: Boolean, x: Int, y: Int, k: Float) {
        pen.save()
        pen.translate(x.toFloat(), y.toFloat())
        pen.scale(CHIP / 16f, CHIP / 16f)
        pen.chip(id, short, 0, 0, Ink.fade(Ink.WHITE, k))
        pen.restore()
    }

    // ---- Words ----

    /** How old a sight's news is now, in ticks. */
    private fun ageOf(s: BoardSight): Double = if (s.age >= 0) s.age.toDouble() else max(0.0, now() - s.tick)

    private fun news(s: BoardSight): String {
        val age = ageOf(s)
        return if (age >= STRUCK) Words.t("word.news.struck", Words.age(age)) else Words.t("word.news.age", Words.age(age))
    }

    private fun shortLine(s: BoardSight): String = when {
        s.shorts.isNotEmpty() -> Words.t("word.short", s.shorts.take(3).joinToString(", ") { Words.good(it) })
        s.gluts.isNotEmpty() -> Words.t("word.glut", s.gluts.take(3).joinToString(", ") { Words.good(it) })
        else -> Words.t("word.fair")
    }

    private fun roadLine(s: BoardSight): String {
        val stage = Words.t("road.stage.${s.road.coerceIn(0, 3)}")
        return if (s.side in SIDES) Words.t("word.road", Words.t("road.side.${s.side}"), stage) else Words.t("word.road.any", stage)
    }

    // ---- The mouse ----

    private fun hot(x: Int, y: Int, w: Int, h: Int, tip: List<String>?, click: (() -> Unit)?) {
        zones.add(Zone(x, y, x + w, y + h, tip, click))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == 0) {
            val x = ((floor(event.x()) - left) / scale).toInt()
            val y = ((floor(event.y()) - top) / scale).toInt()
            val zone = zones.lastOrNull { it.click != null && x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1 }
            if (zone != null) {
                zone.click!!.invoke()
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val lx = ((x - left) / scale).toInt()
        if (scrollY != 0.0 && lx >= RX0) {
            scroll += if (scrollY > 0.0) -1 else 1
            return true
        }
        return super.mouseScrolled(x, y, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (minecraft.options.keyInventory.matches(event)) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    private fun drawTip(pen: Pen, tip: List<String>, mouseX: Int, mouseY: Int) {
        val lines = ArrayList<String>()
        val heads = ArrayList<Boolean>()
        for ((i, line) in tip.withIndex()) for (part in pen.wrap(line, min(TIP_W, width - 24))) {
            lines.add(part)
            heads.add(i == 0)
        }
        if (lines.isEmpty()) return
        var tw = 0
        for (line in lines) tw = max(tw, pen.width(line))
        val bw = tw + 12
        val bh = lines.size * 10 + 6
        var x = mouseX + 10
        var y = mouseY + 10
        if (x + bw > width - 2) x = mouseX - bw - 6
        if (y + bh > height - 2) y = mouseY - bh - 6
        x = max(2, x)
        y = max(2, y)
        pen.fill(x + 1, y + bh, x + bw + 1, y + bh + 1, 0x50000000)
        pen.fill(x, y, x + bw, y + bh, Ink.NOTE)
        pen.frame(x, y, bw, bh, Ink.SOFT)
        for ((i, line) in lines.withIndex()) pen.text(line, x + 6, y + 4 + i * 10, if (heads[i]) Ink.BLACK else Ink.SOFT)
    }

    private fun sound(name: String, pitch: Float, volume: Float) {
        if (!menuSounds()) return
        Ui.play(TradeSounds.get(name), pitch, volume)
    }

    private companion object {
        /** The board's size on the screen (it is drawn smaller on a screen too small for it), and how long it takes to come up. */
        const val W = 384
        const val H = 214
        const val OPEN = 0.35

        /** The slate's box, a row's height; the rail's box, a ticket's height, and the line its foot begins at. */
        const val SX0 = 8
        const val SY0 = 24
        const val SX1 = 196
        const val SY1 = H - 8
        const val ROW_H = 21
        const val RX0 = 202
        const val RY0 = 24
        const val RX1 = W - 8
        const val RY1 = H - 8
        const val SLIP_H = 27
        const val FOOT = RY1 - 30

        /** A chip's size on the slate, in screen px. */
        const val CHIP = 10

        /** The news that is three days old is struck through (ticks). */
        const val STRUCK = 72_000.0

        const val HERE = Long.MIN_VALUE
        const val ASK = 2.0
        const val REACH = 9.0
        const val TIP_W = 200
        const val FAR = -10000

        val SIDES = setOf("n", "e", "s", "w")

        val OAK_DARK = 0xFF24160C.toInt()
        val OAK = 0xFF3E2817.toInt()
        val OAK_GRAIN = 0x2A140A04
        val PLANK = 0xFF54391F.toInt()
        val PLANK_LIGHT = 0xFF6E4D2C.toInt()
        val IRON = 0xFF2C2C30.toInt()
        val IRON_LIGHT = 0xFF5E5E66.toInt()
        val GILT = 0xFFE2BC6C.toInt()
        val PARCHMENT = 0xFFEFE3C4.toInt()
        val PARCHMENT_DIM = 0xFFB7A98A.toInt()
        val SLATE = 0xFF2A3034.toInt()
        val SMEAR = 0x10ECE7DC
        val CHALK = 0xFFECE7DC.toInt()
        val CHALK_DIM = 0xFFAFAAA0.toInt()
        val CHALK_HEAD = 0xFFE8D79E.toInt()
        val PANEL = 0xFF5A3E26.toInt()
        val PANEL_GRAIN = 0x22100804
        val HALL_WAX = 0xFF9C6A2A.toInt()

        /** Whether the screens make their own sounds (Guildmark Settings > Interface), as the kit's `Ui` decides it. */
        fun menuSounds(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.MENU_SOUNDS.get()
    }
}
