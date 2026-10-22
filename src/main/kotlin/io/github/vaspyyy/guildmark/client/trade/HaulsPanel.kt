package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.network.MarketHaul
import io.github.vaspyyy.guildmark.network.MarketHaulPayload
import io.github.vaspyyy.guildmark.network.MarketView
import io.github.vaspyyy.guildmark.trade.HaulKind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Hauls section of the market screen (living-world/trader/design.md, Part 2), under the Orders: the haul tickets out at this shop, each on its
 * paper (plain for a parcel or a consignment, red-edged for relief, green-edged with a festoon over it for a festival), with the good, what is asked,
 * what it pays, a bar of how much is in (green), how much is booked by the player (amber) and by others (grey), and the notches of the days it has
 * left, one bitten out as each goes. A booking wears the booker's seal, pressed on with a thump as it is made. The buttons are Take (a deposit
 * is held), Deliver (the keeper counts what the player carries that came a long way, as the Orders' Deliver does) and Give up (a second press to be sure).
 * A ticket filled by the player's delivery peels off and drops; one that goes any other way (filled by another, lapsed, withdrawn) fades, struck out.
 *
 * Everything shown is what the server sent in [MarketView.hauls]; the server has the last word on every button. The section is as tall as its tickets
 * and not there at all with none, so a shop with no tickets looks as it did. It keeps what it needs to animate between the views it is given, and starts
 * afresh when a new screen opens.
 */
internal object HaulsPanel : MarketPanel {
    // ---- What it keeps from one view to the next ----

    /** One ticket as shown: what the server last said of it, when it came on and last changed, and the fill easing toward what is in. */
    private class Row(var h: MarketHaul, val cameAt: Long) {
        var changedAt = -1000L
        var sealAt = -1000L
        var pressedTop = -1000L
        var pressedBottom = -1000L
        var shown = h.done.toFloat()
        var shownAt = 0f
    }

    /** A ticket that has gone from the view, at row [place] of the list, at game tick [born]: [filled] if the player's own delivery filled it. */
    private class Ghost(val h: MarketHaul, val place: Int, val born: Long, val filled: Boolean)

    private var shop = ""
    private var synced: MarketView? = null
    private var lastGame = Long.MIN_VALUE / 2
    private var rows: List<Row> = listOf()
    private val ghosts = ArrayList<Ghost>()

    /** When the player last pressed Deliver on each ticket, to tell a ticket their delivery filled from one that went some other way. */
    private val delivering = HashMap<Int, Long>()

    /** The ticket whose Give up was pressed once, and when: a second press soon after gives it up. */
    private var unsure = -1
    private var unsureAt = -1000L

    private fun game(): Long = Minecraft.getInstance().level?.gameTime ?: 0L

    /** Takes in the view the screen has now (once for each): new tickets come on, changes glow, a seal is pressed, a ticket that went falls. */
    private fun sync(view: MarketView) {
        val game = game()
        val fresh = synced == null || view.shop != shop || game - lastGame > STALE
        lastGame = game
        if (view === synced) return
        synced = view
        if (fresh) {
            shop = view.shop
            rows = view.hauls.mapIndexed { i, h -> Row(h, game + 3 + i * 2L) }
            ghosts.clear()
            delivering.clear()
            unsure = -1
            return
        }
        val known = rows.associateBy { it.h.id }
        for ((i, row) in rows.withIndex()) {
            if (view.hauls.any { it.id == row.h.id }) continue
            ghosts.add(Ghost(row.h, i, game, game - (delivering[row.h.id] ?: -1000L) < FILLING))
        }
        ghosts.removeIf { game - it.born >= GHOST }
        rows = view.hauls.map { h ->
            val row = known[h.id] ?: return@map Row(h, game)
            val old = row.h
            row.h = h
            if (h.done != old.done || h.mine != old.mine || h.open != old.open || h.units != old.units) row.changedAt = game
            if (h.seal >= 0 && h.seal != old.seal) row.sealAt = game
            row
        }
    }

    // ---- Size ----

    override fun height(view: MarketView): Int {
        sync(view)
        val falling = ghosts.any { game() - it.born < GHOST }
        val n = if (view.hauls.isEmpty() && falling) 1 else view.hauls.size
        return if (n == 0) 0 else SECTION + n * (ROW + GAP)
    }

    private fun rowTop(y: Int, i: Int): Int = y + SECTION + i * (ROW + GAP)

    /** The paper's width: all but the buttons' column. */
    private fun paperWidth(frame: MarketPanel.Frame): Int = frame.width - frame.buttonWidth - 3

    // ---- Drawing ----

    override fun render(graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, y: Int, mouseX: Int, mouseY: Int, t: Float) {
        sync(frame.view)
        val game = game()
        val a = (t - frame.ticks).coerceIn(0f, 1f)
        if (unsure >= 0 && game - unsureAt > SURE) unsure = -1
        Ui.headingAt(graphics, frame.font, Component.translatable("gui.guildmark.trade.haul.hauls"), frame.x + 2, y + 2, Ui.GOLD)
        graphics.fill(frame.x, y + SECTION - 3, frame.x + frame.width, y + SECTION - 2, 0x40D9B46A)
        for ((i, row) in rows.withIndex()) {
            val ry = rowTop(y, i)
            if (ry + ROW <= frame.top || ry >= frame.bottom) continue
            drawRow(graphics, frame, row, ry, game, a, mouseX, mouseY)
        }
        for (ghost in ghosts) {
            val ry = rowTop(y, ghost.place)
            if (ry + ROW + 80 <= frame.top || ry >= frame.bottom) continue
            drawGhost(graphics, frame, ghost, ry, game, a)
        }
    }

    /** How many ticks ago [stamp] was, to the partial tick [a]. */
    private fun age(stamp: Long, game: Long, a: Float): Float = (game - stamp) + a

    private fun ease(k: Float): Float = 1f - (1f - k) * (1f - k) * (1f - k)

    /** 1 just after something happened, falling to 0 over [length] ticks. */
    private fun bump(since: Float, length: Float): Float = if (since < 0f || since >= length) 0f else (1f - since / length).let { it * it }

    private fun fade(color: Int, k: Float): Int = ARGB.multiplyAlpha(color, k)

    private fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    private fun marks(n: Int): Component = Component.translatable(if (n == 1) "gui.guildmark.market.marks.one" else "gui.guildmark.market.marks", n)

    /** "in 30 hours", "in 3 days" from two days on, or "within the hour" for the last (as Hauls.due says it in the chat). */
    private fun due(hours: Int): Component = when {
        hours <= 1 -> Component.translatable("message.guildmark.trade.haul.due.soon")
        hours < 48 -> Component.translatable("message.guildmark.trade.haul.due.hours", hours)
        else -> Component.translatable("message.guildmark.trade.haul.due.days", (hours + 23) / 24)
    }

    private fun kindOf(h: MarketHaul): HaulKind = HaulKind.entries.getOrNull(h.kind) ?: HaulKind.PARCEL

    private fun paperOf(h: MarketHaul): Identifier = when (kindOf(h)) {
        HaulKind.RELIEF -> PAPER_RELIEF
        HaulKind.FEAST -> PAPER_FEAST
        else -> PAPER
    }

    /** [text] on one line no wider than [w], cut at a word if it is longer. */
    private fun line(graphics: GuiGraphicsExtractor, font: Font, text: Component, x: Int, y: Int, w: Int, color: Int) {
        val first = font.split(text, max(1, w)).firstOrNull() ?: return
        graphics.text(font, first, x, y, color, false)
    }

    private fun drawRow(graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, row: Row, y: Int, game: Long, a: Float, mouseX: Int, mouseY: Int) {
        val h = row.h
        val font = frame.font
        val x = frame.x
        val pw = paperWidth(frame)
        val k = ease(Mth.clamp(age(row.cameAt, game, a) / ARRIVE, 0f, 1f))
        if (k <= 0f) return
        val time = (game % 100000L) + a
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)
        val inList = mouseY >= frame.top && mouseY < frame.bottom
        val hovered = inList && mouseY >= y && mouseY < y + ROW && mouseX >= x && mouseX < x + frame.width
        if (hovered) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x - 2, y, frame.width + 4, ROW, ARGB.white(0.5f * k))
        val glow = bump(age(row.changedAt, game, a), 16f)
        if (glow > 0f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x - 2, y, frame.width + 4, ROW, ARGB.white(glow * k))

        // The paper, dimmed if the player cannot have it; a relief ticket's cross and a festival's festoon are pinned on
        val mine = h.mine > 0
        val locked = !mine && h.why.isNotEmpty()
        val tint = if (locked) ARGB.color(k * 0.82f, 0xC9BDA9) else ARGB.white(k)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, paperOf(h), x, y + 1, pw, ROW - 2, tint)
        when (kindOf(h)) {
            HaulKind.RELIEF -> {
                // The cross beats now and then, as a town in want would
                val beat = max(0f, sin(time * 0.21f + h.id)).let { it * it * it * it }
                val s = 1f + 0.12f * beat
                pose.pushMatrix()
                pose.translate(x + 3f, y + 4f)
                pose.scale(s, s)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CROSS, -CROSS_PX / 2, -CROSS_PX / 2, CROSS_PX, CROSS_PX, ARGB.white(k))
                pose.popMatrix()
            }
            HaulKind.FEAST -> {
                val sway = sin(time * 0.07f + h.id) * 1.2f
                pose.pushMatrix()
                pose.translate(sway, 0f)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FESTOON, x + pw / 2 - FESTOON_W / 2, y - 3, FESTOON_W, FESTOON_H, ARGB.white(k))
                pose.popMatrix()
            }
            else -> {}
        }

        // The good, what is asked of it and what it pays; the booker's seal in the paper's corner
        val x0 = x + INSET
        val x1 = x + pw - INSET
        val stack = Good.byId(h.good)?.item?.let(::ItemStack) ?: ItemStack.EMPTY
        if (k > 0.3f) graphics.item(stack, x0, y + 11)
        val sealX = x1 - SEAL_PX
        val sealed = h.seal >= 0 && h.seal < SEALS.size
        val payText = marks(h.pay)
        val payX = (if (sealed) sealX - 3 else x1) - font.width(payText)
        val what = Component.translatable("supplier.guildmark.what", h.units, goodName(h))
        line(graphics, font, what, x0 + 20, y + 7, payX - (x0 + 20) - 4, fade(if (locked) INK_SOFT else INK, k))
        graphics.text(font, payText, payX, y + 7, fade(if (locked) INK_FAINT else INK_GREEN, k), false)
        if (sealed) {
            val thump = bump(age(row.sealAt, game, a), 12f)
            val s = 1f + 0.8f * thump
            pose.pushMatrix()
            pose.translate(sealX + SEAL_PX / 2f, y + 3f + SEAL_PX / 2f)
            pose.scale(s, s)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SEALS[h.seal], -SEAL_PX / 2, -SEAL_PX / 2, SEAL_PX, SEAL_PX, ARGB.white(k))
            pose.popMatrix()
        }

        // How much is in, how much is booked: a bar, with the count at its right
        val count = Component.translatable("gui.guildmark.market.order.count", h.done, h.units)
        val barX = x0 + 20
        val barW = x1 - barX - font.width(count) - 4
        if (barW > 12) drawBar(graphics, row, barX, y + 18, barW, k, time, game, a)
        graphics.text(font, count, x1 - font.width(count), y + 17, fade(INK_SOFT, k), false)

        // Where it stands, and the days it has left as notches along the foot
        val notches = min(NOTCH_MAX, max(1, (h.span + 23) / 24))
        val daysLeft = min(notches, (h.hours + 23) / 24)
        val spent = notches - daysLeft
        val notchesW = notches * (NOTCH_W + 1)
        val (text, ink) = status(h)
        line(graphics, font, text, barX, y + 27, x1 - barX - notchesW - 4, fade(ink, k))
        for (i in 0 until notches) {
            val nx = x1 - notchesW + i * (NOTCH_W + 1) + 1
            val ny = y + ROW - 1 - NOTCH_H
            if (i < spent) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, NOTCH_SPENT, nx, ny, NOTCH_W, NOTCH_H, ARGB.white(k))
            } else {
                // The day running out glows red when it is the last
                if (i == spent && h.hours <= 24) {
                    val urgent = 0.35f + 0.25f * sin(time * 0.3f)
                    graphics.fill(nx - 1, ny - 1, nx + NOTCH_W + 1, ny + NOTCH_H, ARGB.color(urgent * k, 0xD0573F))
                }
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, NOTCH, nx, ny, NOTCH_W, NOTCH_H, ARGB.white(k))
            }
        }

        // The buttons, or what stands in their place
        val bx = x + frame.width - frame.buttonWidth
        for ((action, dy) in buttons(h)) drawButton(graphics, frame, row, action, bx, y + dy, k, game, a, time, mouseX, mouseY)
        if (!mine && h.why == "taken") graphics.centeredText(font, Component.translatable("gui.guildmark.market.order.taken"), bx + frame.buttonWidth / 2, y + 15, fade(Ui.TEXT_FAINT, k))
        pose.popMatrix()
    }

    private fun goodName(h: MarketHaul): Component = Good.byId(h.good)?.let { Component.translatable(it.key + ".many") } ?: Component.literal(h.good)

    /** The short word on a ticket's paper of where it stands for the player, and its ink. */
    private fun status(h: MarketHaul): Pair<Component, Int> = when {
        h.mine > 0 -> Component.translatable("gui.guildmark.trade.haul.row.yours", h.mine, due(h.hours)) to (if (h.hours <= 24) INK_RED else INK)
        h.why == "taken" ->
            (if (h.who.isEmpty()) Component.translatable("gui.guildmark.trade.haul.row.taken") else Component.translatable("gui.guildmark.trade.haul.row.booked", h.who)) to INK_SOFT
        h.why.isNotEmpty() -> Component.translatable("gui.guildmark.trade.haul.row.${h.why}") to INK_FAINT
        else -> Component.translatable("gui.guildmark.trade.haul.row.open", h.open, due(h.hours)) to INK
    }

    /** The bar: what is in (green, easing up as it lands), then what the player has booked (amber, with a glint running along it), then what others have (grey). */
    private fun drawBar(graphics: GuiGraphicsExtractor, row: Row, x: Int, y: Int, w: Int, k: Float, time: Float, game: Long, a: Float) {
        val h = row.h
        // The fill eases toward what is in, whatever the frame rate
        val dt = max(0f, time - row.shownAt)
        row.shownAt = time
        row.shown += (h.done - row.shown) * (1f - exp(-0.25f * dt))
        if (abs(h.done - row.shown) < 0.02f) row.shown = h.done.toFloat()
        val units = max(1, h.units).toFloat()
        val inner = w - 2
        fun at(v: Float): Int = x + 1 + (inner * Mth.clamp(v / units, 0f, 1f)).roundToInt()
        graphics.fill(x, y, x + w, y + 6, fade(INK_SOFT, k))
        graphics.fill(x + 1, y + 1, x + w - 1, y + 5, fade(TRACK, k))
        val doneEnd = at(row.shown)
        // What has landed but is not yet shown in is still shown as booked, so the bar slides and never jumps
        val mineEnd = at(row.shown + h.mine + (h.done - row.shown))
        val others = max(0, h.units - h.done - h.mine - h.open)
        val othersEnd = at(row.shown + h.mine + (h.done - row.shown) + others)
        if (doneEnd > x + 1) graphics.fillGradient(x + 1, y + 1, doneEnd, y + 5, fade(0xFF86B558.toInt(), k), fade(0xFF4C7C30.toInt(), k))
        if (mineEnd > doneEnd) {
            graphics.fillGradient(doneEnd, y + 1, mineEnd, y + 5, fade(0xFFE9BA52.toInt(), k), fade(0xFFB27C26.toInt(), k))
            val run = (time % 80f) / 40f
            if (run < 1f && mineEnd - doneEnd > 4) {
                val gx = doneEnd + ((mineEnd - doneEnd) * run).toInt()
                graphics.fill(gx, y + 1, min(mineEnd, gx + 3), y + 5, ARGB.color((90 * k).toInt(), 0xFFFFFF))
            }
        }
        if (othersEnd > mineEnd) graphics.fillGradient(mineEnd, y + 1, othersEnd, y + 5, fade(0xFF98A4B8.toInt(), k), fade(0xFF6A768C.toInt(), k))
        // A delivery landing flashes the part that came in
        val flash = bump(age(row.changedAt, game, a), 20f)
        if (flash > 0f && doneEnd > x + 1) graphics.fill(x + 1, y + 1, doneEnd, y + 5, ARGB.color((110 * flash * k).toInt(), 0xFFFFFF))
    }

    // ---- Buttons ----

    /** The buttons a ticket has, each an action (see [MarketHaulPayload]) and how far down its row it sits. */
    private fun buttons(h: MarketHaul): List<Pair<Int, Int>> = when {
        h.mine > 0 -> listOf(MarketHaulPayload.DELIVER to TOP_BUTTON, MarketHaulPayload.GIVE_UP to BOTTOM_BUTTON)
        h.why == "taken" -> listOf()
        else -> listOf(MarketHaulPayload.TAKE to ONE_BUTTON)
    }

    /** Whether the button for [action] does anything now, as far as the screen knows (the server has the last word). */
    private fun can(h: MarketHaul, action: Int): Boolean = when (action) {
        MarketHaulPayload.TAKE -> h.why.isEmpty() && h.open > 0
        MarketHaulPayload.DELIVER -> h.deliverable > 0
        else -> true
    }

    private fun lower(action: Int): Boolean = action == MarketHaulPayload.GIVE_UP

    private fun drawButton(
        graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, row: Row, action: Int, x: Int, y: Int, k: Float, game: Long, a: Float, time: Float, mouseX: Int, mouseY: Int,
    ) {
        val h = row.h
        val font = frame.font
        val w = frame.buttonWidth
        val ok = can(h, action)
        val lit = ok && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 13 && mouseY >= frame.top && mouseY < frame.bottom
        val down = age(if (lower(action)) row.pressedBottom else row.pressedTop, game, a) < 3f
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(ok, lit), x, y + if (down) 1 else 0, w, 13, ARGB.white(k))
        // Goods in hand that the ticket will take: the Deliver button glows to say so
        if (action == MarketHaulPayload.DELIVER && ok) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x + 1, y + 1, w - 2, 11, ARGB.white((0.22f + 0.14f * sin(time * 0.16f)) * k))
        }
        val sure = action == MarketHaulPayload.GIVE_UP && unsure == h.id
        if (sure) graphics.fill(x + 1, y + 1, x + w - 1, y + 12, ARGB.color(((0.3f + 0.15f * sin(time * 0.4f)) * k).coerceIn(0f, 1f), 0xD0573F))
        val ty = y + 3 + if (down) 1 else 0
        val verb = Component.translatable(
            when (action) {
                MarketHaulPayload.TAKE -> "gui.guildmark.market.order.take"
                MarketHaulPayload.DELIVER -> "gui.guildmark.market.order.deliver"
                else -> if (sure) "gui.guildmark.market.order.sure" else "gui.guildmark.market.order.give_up"
            },
        )
        val value = when (action) {
            MarketHaulPayload.TAKE -> marks(h.pay)
            MarketHaulPayload.DELIVER -> if (ok) Component.translatable("gui.guildmark.market.order.many", h.deliverable) else null
            else -> null
        }
        val verbColor = when {
            !ok -> Ui.TEXT_FAINT
            sure -> Ui.GOLD_BRIGHT
            lower(action) -> Ui.TEXT_SOFT
            else -> Ui.GOLD
        }
        if (value != null && font.width(verb) + 6 + font.width(value) <= w - 8) {
            graphics.text(font, verb, x + 5, ty, fade(verbColor, k), false)
            graphics.text(font, value, x + w - 5 - font.width(value), ty, fade(if (lit) Ui.GOLD_BRIGHT else if (ok) Ui.TEXT else Ui.TEXT_FAINT, k), false)
        } else {
            graphics.centeredText(font, verb, x + w / 2, ty, fade(verbColor, k))
        }
    }

    // ---- A ticket that has gone ----

    private fun drawGhost(graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, ghost: Ghost, y: Int, game: Long, a: Float) {
        val age = age(ghost.born, game, a)
        if (age < 0f || age >= GHOST) return
        val h = ghost.h
        val font = frame.font
        val pw = paperWidth(frame)
        val p = age / GHOST
        val pose = graphics.pose()
        pose.pushMatrix()
        val k: Float
        if (ghost.filled) {
            // Peeled off the board at one corner, then let drop, turning as it goes
            val fall = max(0f, age - 3f)
            pose.translate(frame.x + fall * 1.5f, y + 1f + fall * fall * 0.5f)
            pose.rotate(min(age, 3f) / 3f * 0.05f + fall * 0.04f)
            k = if (p < 0.5f) 1f else (1f - p) * 2f
        } else {
            pose.translate(frame.x.toFloat(), y + 1f)
            k = 1f - p * p
        }
        val tint = if (ghost.filled) ARGB.white(k) else ARGB.color(k * 0.85f, 0xC9BDA9)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, paperOf(h), 0, 0, pw, ROW - 2, tint)
        val stack = Good.byId(h.good)?.item?.let(::ItemStack) ?: ItemStack.EMPTY
        graphics.item(stack, INSET, 10)
        val what = Component.translatable("supplier.guildmark.what", h.units, goodName(h))
        line(graphics, font, what, INSET + 20, 6, pw - INSET * 2 - 20, fade(if (ghost.filled) INK else INK_SOFT, k))
        if (ghost.filled) {
            line(graphics, font, Component.translatable("gui.guildmark.market.order.filled"), INSET + 20, 17, pw - INSET * 2 - 20, fade(INK_GREEN, k))
        } else {
            // Struck out with a stroke of chalk, drawn across a little at a time
            val across = Mth.clamp(age / 5f, 0f, 1f)
            graphics.fill(INSET, 16, INSET + ((pw - INSET * 2) * across).toInt(), 18, fade(0xFFB5402C.toInt(), k))
        }
        pose.popMatrix()
    }

    // ---- Input ----

    /** The row index and the ticket under [mouseY], the section starting at [y]. */
    private fun rowAt(y: Int, mouseY: Double): Int? {
        for (i in rows.indices) {
            val top = rowTop(y, i)
            if (mouseY >= top && mouseY < top + ROW) return i
        }
        return null
    }

    override fun click(frame: MarketPanel.Frame, y: Int, mouseX: Double, mouseY: Double, shift: Boolean): Boolean {
        sync(frame.view)
        val i = rowAt(y, mouseY) ?: return false
        val row = rows[i]
        val h = row.h
        val top = rowTop(y, i)
        val bx = frame.x + frame.width - frame.buttonWidth
        if (mouseX < bx || mouseX >= bx + frame.buttonWidth) return false
        for ((action, dy) in buttons(h)) {
            if (mouseY < top + dy || mouseY >= top + dy + 13) continue
            if (!can(h, action)) return true
            Ui.select()
            val game = game()
            if (action == MarketHaulPayload.GIVE_UP && unsure != h.id) {
                unsure = h.id
                unsureAt = game
                return true
            }
            unsure = -1
            if (lower(action)) row.pressedBottom = game else row.pressedTop = game
            if (action == MarketHaulPayload.DELIVER) delivering[h.id] = game
            ClientPacketDistributor.sendToServer(MarketHaulPayload(h.id, action))
            return true
        }
        return false
    }

    // ---- Tooltips ----

    override fun tooltip(frame: MarketPanel.Frame, y: Int, mouseX: Double, mouseY: Double): List<Pair<Component, Int>>? {
        sync(frame.view)
        if (unsure >= 0 && game() - unsureAt > SURE) unsure = -1
        val i = rowAt(y, mouseY) ?: return null
        val h = rows[i].h
        val top = rowTop(y, i)
        val bx = frame.x + frame.width - frame.buttonWidth
        if (mouseX >= bx && mouseX < bx + frame.buttonWidth) {
            for ((action, dy) in buttons(h)) if (mouseY >= top + dy && mouseY < top + dy + 13) return buttonTip(h, action)
            return null
        }
        if (mouseX < frame.x || mouseX >= frame.x + paperWidth(frame)) return null
        return ticketTip(frame, h)
    }

    /** What a ticket is and where it stands, for the tooltip over its paper. */
    private fun ticketTip(frame: MarketPanel.Frame, h: MarketHaul): List<Pair<Component, Int>> {
        val lines = ArrayList<Pair<Component, Int>>()
        val shopName = Shop.byId(frame.view.shop)?.let { Component.translatable(it.key) } ?: Component.literal(frame.view.shop)
        lines.add(Component.translatable("gui.guildmark.trade.haul.tip.title", h.units, goodName(h), shopName) to Ui.GOLD)
        lines.add(Component.translatable("gui.guildmark.trade.haul.tip.kind.${kindOf(h).id}") to Ui.TEXT_SOFT)
        val booked = h.units - h.done - h.open
        lines.add(Component.translatable("gui.guildmark.trade.haul.tip.in", h.done, h.units, booked, h.open) to Ui.TEXT)
        when {
            h.mine > 0 -> lines.add(Component.translatable("gui.guildmark.trade.haul.tip.pays.mine", h.mine, marks(h.pay)) to Ui.TEXT)
            h.open > 0 -> lines.add(Component.translatable("gui.guildmark.trade.haul.tip.pays.open", h.open, marks(h.pay)) to Ui.TEXT)
            else -> lines.add(Component.translatable("gui.guildmark.trade.haul.tip.pays.rest", marks(h.pay)) to Ui.TEXT)
        }
        lines.add(Component.translatable("gui.guildmark.trade.haul.tip.due", due(h.hours)) to if (h.hours <= 24) Ui.RED else Ui.TEXT_SOFT)
        if (h.mine > 0) {
            lines.add(Component.translatable("gui.guildmark.trade.haul.tip.deposit.held", marks(h.deposit)) to Ui.TEXT_SOFT)
            lines.add(Component.translatable("gui.guildmark.market.tip.carried", h.carried) to Ui.TEXT_SOFT)
            if (h.carried > h.deliverable) lines.add(Component.translatable("gui.guildmark.trade.haul.tip.near", h.carried - h.deliverable) to Ui.GOLD)
        } else if (h.open > 0 && h.why.isEmpty()) {
            lines.add(Component.translatable("gui.guildmark.trade.haul.tip.deposit.would", marks(h.deposit)) to Ui.TEXT_SOFT)
        }
        if (h.who.isNotEmpty()) lines.add(Component.translatable("gui.guildmark.trade.haul.tip.who", h.who) to Ui.TEXT_FAINT)
        if (h.mine == 0 && h.why.isNotEmpty() && h.why != "taken") lines.add(Component.translatable("gui.guildmark.trade.haul.refused.${h.why}") to Ui.RED)
        return lines
    }

    /** What a button does, or why it cannot, for its tooltip. */
    private fun buttonTip(h: MarketHaul, action: Int): List<Pair<Component, Int>> {
        val line = when (action) {
            MarketHaulPayload.TAKE ->
                if (can(h, action)) Component.translatable("gui.guildmark.trade.haul.tip.take", h.open, marks(h.deposit), due(h.hours))
                else Component.translatable("gui.guildmark.trade.haul.refused.${h.why.ifEmpty { "taken" }}")
            MarketHaulPayload.DELIVER -> when {
                h.deliverable > 0 -> Component.translatable("gui.guildmark.trade.haul.tip.deliver", h.deliverable, goodName(h))
                h.carried > 0 -> Component.translatable("gui.guildmark.trade.haul.refused.not_hauled")
                else -> Component.translatable("gui.guildmark.trade.haul.refused.none")
            }
            else ->
                if (unsure == h.id) Component.translatable("gui.guildmark.market.order.tip.sure")
                else Component.translatable("gui.guildmark.trade.haul.tip.give_up", marks(h.deposit))
        }
        return listOf(line to Ui.TEXT)
    }

    // ---- Numbers ----

    private const val SECTION = 16
    private const val ROW = 38
    private const val GAP = 3
    private const val INSET = 6
    private const val TOP_BUTTON = 6
    private const val BOTTOM_BUTTON = 21
    private const val ONE_BUTTON = 13

    /** Ticks a ticket takes to come on, a gone one to fall, a first press of Give up to wait for its second, a delivery to count as what filled a ticket, and a view to go stale. */
    private const val ARRIVE = 8f
    private const val GHOST = 18
    private const val SURE = 60L
    private const val FILLING = 40L
    private const val STALE = 60L

    // The art's sizes, in screen px (see HaulBoardArt)
    private const val SEAL_PX = HaulBoardArt.SEAL_PX
    private const val CROSS_PX = HaulBoardArt.RELIEF_CROSS_PX
    private const val FESTOON_W = HaulBoardArt.FESTOON_PX_W
    private const val FESTOON_H = HaulBoardArt.FESTOON_PX_H
    private const val NOTCH_W = HaulBoardArt.NOTCH_PX_W
    private const val NOTCH_H = HaulBoardArt.NOTCH_PX_H
    private const val NOTCH_MAX = HaulBoardArt.NOTCH_MAX

    // The ink on the paper, and the bar's track
    private val INK = 0xFF3A2916.toInt()
    private val INK_SOFT = 0xFF6E5A3C.toInt()
    private val INK_FAINT = 0xFF9B8A6B.toInt()
    private val INK_RED = 0xFF9C3524.toInt()
    private val INK_GREEN = 0xFF3C6E2B.toInt()
    private val TRACK = 0xFFCDBB92.toInt()

    private val PAPER = sprite(HaulBoardArt.TICKET_ROW)
    private val PAPER_RELIEF = sprite(HaulBoardArt.TICKET_ROW_RELIEF)
    private val PAPER_FEAST = sprite(HaulBoardArt.TICKET_ROW_FEAST)
    private val CROSS = sprite(HaulBoardArt.RELIEF_CROSS_SPRITE)
    private val FESTOON = sprite(HaulBoardArt.FESTOON_SPRITE)
    private val NOTCH = sprite(HaulBoardArt.NOTCH_SPRITE)
    private val NOTCH_SPENT = sprite(HaulBoardArt.NOTCH_SPENT_SPRITE)
    private val SEALS = HaulBoardArt.SEAL_SPRITES.map { sprite(it) }
}
