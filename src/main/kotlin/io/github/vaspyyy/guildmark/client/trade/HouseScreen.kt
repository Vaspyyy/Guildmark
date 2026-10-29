package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.network.HouseActionPayload
import io.github.vaspyyy.guildmark.network.HouseBookEntry
import io.github.vaspyyy.guildmark.network.HouseCarterLine
import io.github.vaspyyy.guildmark.network.HouseClosePayload
import io.github.vaspyyy.guildmark.network.HouseRefreshPayload
import io.github.vaspyyy.guildmark.network.HouseView
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.trade.HouseDesk
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A trading house's desk (see [HouseDesk]): a panel under the Counting House's name board, hung on two chains with the owner's pennant
 * flying from its pole and, at its left, a counting frame whose beads slide to the purse as it changes. Three tabs, the gold rule under them
 * sliding to the one chosen:
 *
 * - *Carters*: a row for each carter with their wagon (its canvas in the House's colour, its wheels turning on the road), what they are
 *   doing and when it is done, the extra guards their wagon takes (- and +) and the button to let them go (or keep them, once let go while
 *   out); the button to hire another, and why the idle ones wait if they do.
 * - *Book*: the last trips home, newest first: who went where with what, and what each made after its costs and the wright's bill.
 * - *Purse*: what it holds, the Marks put in or the profit taken out (the float stays while the House works), and the totals since it opened.
 *
 * Everything eases to its new value as the server says it has changed.
 */
class HouseScreen(private var view: HouseView) : Screen(Component.translatable("gui.guildmark.trade.house")) {
    /** Which House it shows: its town. */
    val place: String get() = view.place

    /** A button: what it asks for (about carter [carter], with [count]), where it is, what it says, whether it can be pressed and why not. */
    private class Desk(val action: Int, val carter: Int, val count: Int, val x: Int, val y: Int, val w: Int, val label: Component, val ok: Boolean, val tip: Component?)

    private class Floater(val text: Component, val x: Int, val y: Int, val color: Int, val born: Int)

    private var ticks = 0

    /** The server closed the screen: nothing to tell it on the way out. */
    private var shutHere = false

    /** The tab shown, the rule under the tabs as it slides to it (last tick and now), and when the tab was last changed. */
    private var tab = lastTab
    private var rule = tab.toFloat()
    private var ruleLast = rule
    private var tabAt = 0

    /** The House's purse and the player's own Marks, as shown last tick and now, and when each last changed. */
    private var purse = view.purse.toFloat()
    private var purseLast = purse
    private var purseAt = -100
    private var marks = view.marks.toFloat()
    private var marksLast = marks
    private var marksAt = -100

    /** The counting frame's rows (units first), each sliding to its digit of the purse, as shown last tick and now. */
    private val beads = FloatArray(FRAME_ROWS) { digit(view.purse, it).toFloat() }
    private val beadsLast = beads.copyOf()

    /** When each carter's line last changed (by id), the last button pressed, and when. */
    private val changedAt = HashMap<Int, Int>()
    private var pressed: Desk? = null
    private var pressedAt = -100

    private val floaters = ArrayList<Floater>()

    // The list's scroll, as asked for and as shown
    private var scroll = 0f
    private var scrollShown = 0f
    private var scrollLast = 0f

    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var listTop = 0
    private var listBottom = 0

    private val innerX get() = panelX + PAD
    private val innerWidth get() = panelWidth - PAD * 2
    private val textX get() = innerX + 22

    override fun init() {
        panelWidth = min(width - 20, Mth.clamp(width - 40, 280, 340))
        panelX = (width - panelWidth) / 2
        panelHeight = min(height - SIGN_ROOM - 12, 262)
        panelY = max(SIGN_ROOM + 6, (height - panelHeight) / 2 + SIGN_ROOM / 2)
        panelHeight = min(panelHeight, height - panelY - 6)
        listTop = panelY + TABS_TOP + TAB_H + HEADLINE
        listBottom = panelY + panelHeight - FOOTER - 4
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    override fun added() {
        Ui.open()
    }

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun removed() {
        if (!shutHere) {
            shutHere = true
            ClientPacketDistributor.sendToServer(HouseClosePayload)
        }
    }

    // ---- From the server ----

    /** The House as it stands now: everything that changed eases to its new place. */
    fun update(next: HouseView) {
        val before = view
        view = next
        val put = next.purse - before.purse
        if (put != 0) {
            purseAt = ticks
            floaters.add(Floater(Component.literal(if (put > 0) "+$put" else "$put"), innerX + innerWidth - 40, footerY() - 4, if (put > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, ticks))
            Ui.play(TradeSounds.get("trade.beads"), 1.1f, 0.35f)
        }
        val gained = next.marks - before.marks
        if (gained != 0) {
            marksAt = ticks
            floaters.add(Floater(Component.literal(if (gained > 0) "+$gained" else "$gained"), innerX + 18, footerY() - 4, if (gained > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, ticks))
        }
        val old = before.carters.associateBy { it.id }
        for (c in next.carters) {
            val o = old[c.id]
            if (o == null || o.state != c.state || o.guards != c.guards || o.leaving != c.leaving) changedAt[c.id] = ticks
        }
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    /** The screen is closed on the server's side. */
    fun shut() {
        shutHere = true
        onClose()
    }

    // ---- Every tick ----

    override fun tick() {
        ticks++
        if (minecraft.player == null) {
            onClose()
            return
        }
        if (ticks % HEARTBEAT == 0) ClientPacketDistributor.sendToServer(HouseRefreshPayload)
        purseLast = purse
        purse += (view.purse - purse) * 0.2f
        if (abs(view.purse - purse) < 0.05f) purse = view.purse.toFloat()
        marksLast = marks
        marks += (view.marks - marks) * 0.25f
        if (abs(view.marks - marks) < 0.05f) marks = view.marks.toFloat()
        // The beads slide a row at a time, the units first
        for (r in 0 until FRAME_ROWS) {
            beadsLast[r] = beads[r]
            val want = digit(view.purse, r).toFloat()
            if (r > 0 && abs(beads[r - 1] - digit(view.purse, r - 1)) > 0.4f) continue
            beads[r] += (want - beads[r]) * 0.3f
            if (abs(want - beads[r]) < 0.02f) beads[r] = want
        }
        ruleLast = rule
        rule += (tab - rule) * 0.35f
        if (abs(tab - rule) < 0.01f) rule = tab.toFloat()
        scrollLast = scrollShown
        scrollShown += (scroll - scrollShown) * 0.5f
        floaters.removeIf { ticks - it.born > FLOAT }
    }

    // ---- Layout ----

    private fun contentHeight(): Int = when (tab) {
        CARTERS -> view.carters.size * (ROW + GAP)
        BOOK -> view.book.size * (BOOK_ROW + GAP)
        else -> 0
    }

    private fun maxScroll(): Float = max(0, contentHeight() - (listBottom - listTop)).toFloat()

    private fun footerY(): Int = panelY + panelHeight - FOOTER + 8

    private fun tabX(i: Int): Int = innerX + innerWidth * i / TABS
    private fun tabWidth(): Int = innerWidth / TABS

    private fun tabAt(mx: Double, my: Double): Int? {
        val y = panelY + TABS_TOP
        if (my < y || my >= y + TAB_H || mx < innerX || mx >= innerX + innerWidth) return null
        return ((mx - innerX) / (innerWidth.toDouble() / TABS)).toInt().coerceIn(0, TABS - 1)
    }

    /** Where carter row [i]'s top is on screen now. */
    private fun rowY(i: Int): Int = listTop - scrollShown.roundToInt() + i * (ROW + GAP)

    /** The buttons on the tab shown, where they are now (a carter's move with the list's scroll). */
    private fun desks(): List<Desk> {
        val out = ArrayList<Desk>()
        val shift = minecraft.hasShiftDown()
        when (tab) {
            CARTERS -> {
                val why = view.hire.takeIf { it.isNotEmpty() }?.let { Component.translatable("gui.guildmark.trade.house.refused.$it", view.most, view.float) }
                val w = min(110, innerWidth / 3)
                out.add(
                    Desk(HouseDesk.HIRE, -1, 0, innerX + innerWidth - w, panelY + TABS_TOP + TAB_H + 4, w, Component.translatable("gui.guildmark.trade.house.hire"), why == null,
                        why ?: Component.translatable("gui.guildmark.trade.house.tip.hire", view.wage)),
                )
                for ((i, c) in view.carters.withIndex()) {
                    val y = rowY(i) + 7
                    if (y < listTop || y + 13 > listBottom) continue
                    val right = innerX + innerWidth
                    val go = right - LET_GO
                    val guards = Component.translatable("gui.guildmark.trade.house.tip.guards", 1 + c.guards, tenths(view.guardWage))
                    out.add(Desk(HouseDesk.GUARDS, c.id, c.guards - 1, go - 6 - 44, y, 12, Component.literal("-"), view.open && c.guards > 0, guards))
                    out.add(Desk(HouseDesk.GUARDS, c.id, c.guards + 1, go - 6 - 12, y, 12, Component.literal("+"), view.open && c.guards < view.guardsMost, guards))
                    out.add(
                        Desk(HouseDesk.DISMISS, c.id, 0, go, y, LET_GO, Component.translatable(if (c.leaving) "gui.guildmark.trade.house.keep" else "gui.guildmark.trade.house.dismiss"), true,
                            Component.translatable(if (c.leaving) "gui.guildmark.trade.house.tip.keep" else "gui.guildmark.trade.house.tip.dismiss", c.name)),
                    )
                }
            }
            PURSE -> {
                val y = listTop + 34
                val w = (innerWidth - 8) / 3
                val closed = !view.open || view.hire == "closed"
                val off = if (closed) Component.translatable(if (!view.open) "gui.guildmark.trade.house.refused.off" else "gui.guildmark.trade.house.refused.closed") else null
                for ((j, n) in listOf(10, 50).withIndex()) {
                    val all = shift && j == 1
                    val count = if (all) view.marks else n
                    val ok = off == null && view.marks >= (if (all) 1 else n)
                    val tip = off ?: if (view.marks < n && !all) Component.translatable("gui.guildmark.trade.house.tip.short", view.marks) else Component.translatable("gui.guildmark.trade.house.tip.put")
                    val label = if (all) Component.translatable("gui.guildmark.trade.house.purse.put_all", view.marks) else Component.translatable("gui.guildmark.trade.house.purse.put", n)
                    out.add(Desk(HouseDesk.PUT, -1, count, innerX + j * (w + 4), y, w, label, ok, tip))
                }
                val take = view.takeable
                out.add(
                    Desk(HouseDesk.TAKE, -1, take, innerX + 2 * (w + 4), y, w, Component.translatable("gui.guildmark.trade.house.purse.take", take), take > 0,
                        if (take > 0) Component.translatable("gui.guildmark.trade.house.tip.take", take) else Component.translatable("gui.guildmark.trade.house.tip.nothing", view.float)),
                )
            }
        }
        return out
    }

    private fun deskAt(mx: Double, my: Double): Desk? = desks().firstOrNull { mx >= it.x && mx < it.x + it.w && my >= it.y && my < it.y + 13 }

    /** The carter whose row is under the cursor (not over its buttons). */
    private fun carterAt(mx: Double, my: Double): HouseCarterLine? {
        if (tab != CARTERS || my < listTop || my >= listBottom || mx < innerX || mx >= innerX + innerWidth - LET_GO - 56) return null
        for ((i, c) in view.carters.withIndex()) {
            val y = rowY(i)
            if (my >= y && my < y + ROW) return c
        }
        return null
    }

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        tabAt(event.x(), event.y())?.let { t ->
            if (t != tab) {
                Ui.page()
                tab = t
                lastTab = t
                tabAt = ticks
                scroll = 0f
                scrollShown = 0f
                scrollLast = 0f
            }
            return true
        }
        val d = deskAt(event.x(), event.y()) ?: return super.mouseClicked(event, doubleClick)
        if (!d.ok) return true
        Ui.select()
        pressed = d
        pressedAt = ticks
        ClientPacketDistributor.sendToServer(HouseActionPayload(d.action, d.carter, d.count))
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0 || maxScroll() <= 0f) return false
        scroll = (scroll - scrollY.toFloat() * 22f).coerceIn(0f, maxScroll())
        return true
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // The House stays in view: a shade behind the panel only
        val k = min(1f, (ticks + a) / 8f)
        graphics.fill(0, 0, width, height, ARGB.color((110 * k).toInt(), 0x0A0806))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val t = ticks + a
        val rise = ease(min(1f, t / 8f))
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(0f, (1f - rise) * 14f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, panelX, panelY, panelWidth, panelHeight, ARGB.white(rise))
        drawFrame(graphics, rise, a)
        drawSign(graphics, rise, t)
        drawTabs(graphics, rise, mouseX, mouseY, t, a)
        val k = rise * ease(Mth.clamp((t - tabAt) / 6f, 0f, 1f))
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)
        when (tab) {
            CARTERS -> drawCarters(graphics, k, mouseX, mouseY, t, a)
            BOOK -> drawBook(graphics, k, t, a)
            else -> drawPurse(graphics, k, mouseX, mouseY, t, a)
        }
        pose.popMatrix()
        drawFooter(graphics, rise, t, a)
        pose.popMatrix()
        drawFloaters(graphics, t)
        if (rise >= 1f) {
            deskAt(mouseX.toDouble(), mouseY.toDouble())?.tip?.let { drawTip(graphics, listOf(it to Ui.TEXT), mouseX, mouseY) }
                ?: carterAt(mouseX.toDouble(), mouseY.toDouble())?.let { drawTip(graphics, carterTip(it), mouseX, mouseY) }
        }
    }

    /** The owner's colour. */
    private fun tint(): Int = Dye.entries[view.color.coerceIn(0, Dye.entries.size - 1)].rgb

    /**
     * The Counting House's name board over the panel, hung on two chains and swinging a little as it settles after the screen opens, the
     * owner's name and town under it; the owner's pennant flies from a pole at its right, rippling.
     */
    private fun drawSign(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val w = min(panelWidth - 110, max(150, font.width(Ui.heading(Component.translatable("gui.guildmark.trade.house.board"))) + 40))
        val cx = panelX + panelWidth / 2
        val top = panelY - SIGN_ROOM
        val pose = graphics.pose()
        val swing = sin(t * 0.21f) * 0.05f * max(0f, 1f - t / 50f) + sin(t * 0.045f) * 0.006f
        pose.pushMatrix()
        pose.translate(cx.toFloat(), top.toFloat())
        pose.rotate(swing)
        val chain = ARGB.color(k, 0x4A4650)
        for (x in listOf(-w / 2 + 10, w / 2 - 10)) for (y in 0 until CHAIN step 2) graphics.fill(x, y, x + 1, y + 1, chain)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, -w / 2, CHAIN, w, SIGN_H, ARGB.white(k))
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.trade.house.board"), 0, CHAIN + 4, fade(Ui.GOLD, k))
        val owner = Component.translatable("gui.guildmark.trade.house.owner", view.owner, view.place)
        graphics.centeredText(font, owner, 0, CHAIN + 16, fade(Ui.TEXT_SOFT, k))
        pose.popMatrix()
        val px = cx + w / 2 + 14
        val pole = ARGB.color(k, 0x5A3E26)
        graphics.fill(px, top - 2, px + 2, panelY, pole)
        graphics.fill(px - 1, top - 3, px + 3, top - 1, ARGB.color(k, 0xC9A45C))
        pose.pushMatrix()
        pose.translate(px + 2f, top.toFloat())
        pose.rotate(-1.5708f + sin(t * 0.09f) * 0.07f + sin(t * 0.23f + 1.1f) * 0.03f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PENNANT, -PENNANT_W / 2, 0, PENNANT_W, PENNANT_H, ARGB.white(k))
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PENNANT_PAINT, -PENNANT_W / 2, 0, PENNANT_W, PENNANT_H, ARGB.color(k, tint()))
        pose.popMatrix()
    }

    /**
     * The counting frame at the board's left: a rod for each of the purse's places, the thousands at the top, with nine beads on each; as
     * many beads as the digit sit at the left, sliding over a row at a time as the purse changes.
     */
    private fun drawFrame(graphics: GuiGraphicsExtractor, k: Float, a: Float) {
        val x = panelX + 8
        val top = panelY - SIGN_ROOM + 2
        val w = FRAME_W
        val h = FRAME_ROWS * 7 + 6
        val wood = ARGB.color(k, 0x5A3E26)
        graphics.fill(x, top, x + 2, top + h + 4, wood)
        graphics.fill(x + w - 2, top, x + w, top + h + 4, wood)
        graphics.fill(x, top, x + w, top + 2, wood)
        graphics.fill(x, top + h, x + w, top + h + 2, wood)
        val rod = ARGB.color(k, 0x8A8690)
        val bead = ARGB.color(k, 0xD9B46A)
        val beadDark = ARGB.color(k, 0x96713A)
        val inner = w - 4
        for (r in 0 until FRAME_ROWS) {
            val ry = top + 4 + (FRAME_ROWS - 1 - r) * 7
            graphics.fill(x + 2, ry + 1, x + w - 2, ry + 2, rod)
            val shown = Mth.lerp(a, beadsLast[r], beads[r])
            for (i in 0 until 9) {
                // Bead i is at the left once the row's digit is past it, at the right before, sliding between
                val p = Mth.clamp(shown - i, 0f, 1f)
                val left = x + 2 + i * 3
                val right = x + 2 + inner - (9 - i) * 3
                val bx = Mth.lerp(ease(p), right.toFloat(), left.toFloat()).roundToInt()
                graphics.fill(bx, ry, bx + 3, ry + 3, if (p > 0.5f) bead else beadDark)
            }
        }
    }

    private fun drawTabs(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val y = panelY + TABS_TOP
        val hovered = tabAt(mouseX.toDouble(), mouseY.toDouble())
        for (i in 0 until TABS) {
            val x = tabX(i)
            val w = tabWidth()
            val on = i == tab
            if (hovered == i && !on) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x, y - 1, w, TAB_H, ARGB.white(0.5f * k))
            val label = Component.translatable(TAB_KEYS[i])
            graphics.centeredText(font, label, x + w / 2, y + 3, fade(if (on) Ui.GOLD_BRIGHT else if (hovered == i) Ui.TEXT else Ui.TEXT_SOFT, k))
            // Carters waiting for their wage mark the Carters tab, a purse too short for a wagon the Purse
            val mark = when (i) {
                CARTERS -> view.carters.any { it.state == HouseCarterLine.UNPAID }
                PURSE -> view.idle == "float"
                else -> false
            }
            if (mark) {
                val pulse = 0.55f + 0.45f * sin(t * 0.15f)
                graphics.fill(x + w / 2 + font.width(label) / 2 + 2, y + 2, x + w / 2 + font.width(label) / 2 + 4, y + 4, ARGB.color(pulse * k, Ui.RED))
            }
        }
        Ui.divider(graphics, innerX, y + TAB_H, innerWidth, gem = false)
        val shown = Mth.lerp(a, ruleLast, rule)
        val rx = innerX + (innerWidth * shown / TABS).roundToInt()
        graphics.fill(rx + 6, y + TAB_H - 1, rx + tabWidth() - 6, y + TAB_H + 1, fade(Ui.GOLD, k))
    }

    // ---- Carters ----

    /** Why the House's carters do not go out now, if they don't: off, its owner away, or the reason the idle ones wait. */
    private fun note(): Pair<Component, Int> = when {
        !view.open -> Component.translatable("gui.guildmark.trade.house.off") to Ui.RED
        view.away -> Component.translatable("gui.guildmark.trade.house.away") to Ui.RED
        view.idle.isNotEmpty() -> Component.translatable("gui.guildmark.trade.house.idle.${view.idle}") to (if (view.idle == "float") Ui.RED else Ui.TEXT_SOFT)
        else -> Component.translatable("gui.guildmark.trade.house.carters.note", view.wage, tenths(view.guardWage)) to Ui.TEXT_FAINT
    }

    private fun drawCarters(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        val out = view.carters.count { it.state == HouseCarterLine.LOADING || it.state == HouseCarterLine.OUT || it.state == HouseCarterLine.BACK }
        val head = Component.translatable("gui.guildmark.trade.house.carters.head", view.carters.size, view.most, out)
        graphics.text(font, head, innerX + 2, y0, fade(Ui.TEXT, k), false)
        val (line, colour) = note()
        val split = font.split(line, innerWidth - 4)
        for ((i, part) in split.take(2).withIndex()) graphics.text(font, part, innerX + 2, y0 + 11 + i * 9, fade(colour, k), false)
        if (view.carters.isEmpty()) {
            for ((i, part) in font.split(Component.translatable("gui.guildmark.trade.house.carters.none"), innerWidth - 20).withIndex()) {
                graphics.centeredText(font, part, innerX + innerWidth / 2, listTop + 14 + i * 10, fade(Ui.TEXT_FAINT, k))
            }
        } else {
            val scrolled = Mth.lerp(a, scrollLast, scrollShown)
            graphics.enableScissor(innerX - 2, listTop, innerX + innerWidth + 2, listBottom)
            var y = listTop - scrolled.roundToInt()
            for ((i, c) in view.carters.withIndex()) {
                if (y + ROW > listTop && y < listBottom) {
                    val rk = k * ease(Mth.clamp((t - tabAt - 2f - i * 1.5f) / 6f, 0f, 1f))
                    if (rk > 0f) drawCarter(graphics, c, y, rk, mouseX, mouseY, t)
                }
                y += ROW + GAP
            }
            graphics.disableScissor()
            drawScrollbar(graphics, scrolled)
        }
        drawDesks(graphics, k, mouseX, mouseY, t)
    }

    private fun drawCarter(graphics: GuiGraphicsExtractor, c: HouseCarterLine, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        val jolt = bump(t - (changedAt[c.id] ?: -100), 8f)
        pose.translate(0f, -jolt * 1.5f)
        val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= listTop && mouseY < listBottom
        if (hovered) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, innerX - 2, y, innerWidth + 4, ROW, ARGB.white(0.4f * k))
        drawWagon(graphics, c, innerX, y + 3, k, t)
        val nameColour = if (c.state == HouseCarterLine.UNPAID) Ui.RED else if (bump(t - (changedAt[c.id] ?: -100), 14f) > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT
        graphics.text(font, c.name, textX, y + 4, fade(nameColour, k), false)
        if (c.robbed) {
            val tag = Component.translatable("gui.guildmark.trade.house.robbed")
            graphics.text(font, tag, textX + font.width(c.name) + 6, y + 4, fade(Ui.RED, k * 0.85f), false)
        }
        var state = stateText(c)
        if (c.leaving) state = state.copy().append(Component.translatable("gui.guildmark.trade.house.state.leaving"))
        val room = innerX + innerWidth - LET_GO - 56 - textX
        val shown = font.split(state, room).firstOrNull()
        if (shown != null) graphics.text(font, shown, textX, y + 15, fade(if (c.state == HouseCarterLine.UNPAID || c.leaving) Ui.RED else Ui.TEXT_SOFT, k), false)
        // The guards their wagon takes, between the - and +
        val go = innerX + innerWidth - LET_GO
        val guards = "${1 + c.guards}"
        graphics.centeredText(font, Component.literal(guards), go - 6 - 25, y + 10, fade(if (c.guards > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, k))
        pose.popMatrix()
    }

    /** What carter [c] is doing, and when it is done. */
    private fun stateText(c: HouseCarterLine): Component {
        val where = c.where.ifEmpty { "?" }
        return when (c.state) {
            HouseCarterLine.LOADING -> Component.translatable("gui.guildmark.trade.house.state.loading", where)
            HouseCarterLine.OUT -> Component.translatable("gui.guildmark.trade.house.state.out", where, hours(c.eta))
            HouseCarterLine.BACK -> Component.translatable("gui.guildmark.trade.house.state.back", where, hours(c.eta))
            HouseCarterLine.UNPAID -> Component.translatable("gui.guildmark.trade.house.state.unpaid", c.unpaid)
            else -> Component.translatable("gui.guildmark.trade.house.state.idle")
        }
    }

    /**
     * Carter [c]'s wagon in a slot: its bed, and its canvas in the House's colour once it is loading; on the road it bobs and its wheels
     * turn, facing the town it is bound for on the way out and home on the way back.
     */
    private fun drawWagon(graphics: GuiGraphicsExtractor, c: HouseCarterLine, x: Int, y: Int, k: Float, t: Float) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, x, y, 18, 18, ARGB.white(k))
        val rolling = c.state == HouseCarterLine.OUT || c.state == HouseCarterLine.BACK
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + 9f, y + 9f + if (rolling) sin(t * 0.7f + c.id) * 0.5f else 0f)
        if (c.state == HouseCarterLine.BACK) pose.scale(-1f, 1f)
        val dim = if (c.state == HouseCarterLine.UNPAID) 0.5f else 1f
        val bed = ARGB.color(k * dim, 0x6B4A2E)
        val dark = ARGB.color(k * dim, 0x2A1A10)
        if (c.state != HouseCarterLine.IDLE && c.state != HouseCarterLine.UNPAID) {
            val canvas = ARGB.color(k, tint())
            graphics.fill(-5, -5, 5, -4, canvas)
            graphics.fill(-6, -4, 6, 0, canvas)
            graphics.fill(-6, -4, -5, 0, ARGB.color(k, 0xE8DEC6))
        }
        graphics.fill(-7, 0, 7, 3, bed)
        graphics.fill(7, 1, 9, 2, bed)
        // The wheels, a spoke turning on the road
        val turn = if (rolling) ((t * 0.5f).toInt() % 4) else 0
        for (wx in listOf(-5, 3)) {
            graphics.fill(wx, 3, wx + 3, 6, dark)
            val (sx, sy) = SPOKES[turn]
            graphics.fill(wx + sx, 3 + sy, wx + sx + 1, 4 + sy, ARGB.color(k * dim, 0xB49A6A))
        }
        pose.popMatrix()
    }

    private fun carterTip(c: HouseCarterLine): List<Pair<Component, Int>> {
        val lines = ArrayList<Pair<Component, Int>>()
        lines.add(Component.literal(c.name) to Ui.GOLD)
        lines.add(stateText(c) to Ui.TEXT)
        lines.add(Component.translatable("gui.guildmark.trade.house.tip.carter", c.trips, marksText(c.earned)) to Ui.TEXT_SOFT)
        if (c.robbed) lines.add(Component.translatable("gui.guildmark.trade.house.tip.robbed") to Ui.RED)
        if (c.unpaid > 0) lines.add(Component.translatable("gui.guildmark.trade.house.tip.unpaid", c.unpaid, view.wage) to Ui.RED)
        return lines
    }

    // ---- Book ----

    private fun drawBook(graphics: GuiGraphicsExtractor, k: Float, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        graphics.text(font, Component.translatable("gui.guildmark.trade.house.book.head"), innerX + 2, y0, fade(Ui.TEXT, k), false)
        graphics.text(font, Component.translatable("gui.guildmark.trade.house.book.note"), innerX + 2, y0 + 11, fade(Ui.TEXT_FAINT, k), false)
        if (view.book.isEmpty()) {
            graphics.centeredText(font, Component.translatable("gui.guildmark.trade.house.book.empty"), innerX + innerWidth / 2, listTop + 14, fade(Ui.TEXT_FAINT, k))
            return
        }
        val scrolled = Mth.lerp(a, scrollLast, scrollShown)
        graphics.enableScissor(innerX - 2, listTop, innerX + innerWidth + 2, listBottom)
        var y = listTop - scrolled.roundToInt()
        for ((i, b) in view.book.withIndex()) {
            if (y + BOOK_ROW > listTop && y < listBottom) {
                val rk = k * ease(Mth.clamp((t - tabAt - 2f - i * 1.2f) / 6f, 0f, 1f))
                if (rk > 0f) drawEntry(graphics, b, y, rk)
            }
            y += BOOK_ROW + GAP
        }
        graphics.disableScissor()
        drawScrollbar(graphics, scrolled)
    }

    /** One trip home: who went where and when, its goods (small), what it made in green or red, and a waylaid trip's loss. */
    private fun drawEntry(graphics: GuiGraphicsExtractor, b: HouseBookEntry, y: Int, k: Float) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.INSET, innerX, y, innerWidth, BOOK_ROW, ARGB.white(k * 0.8f))
        val title = Component.translatable("gui.guildmark.trade.house.book.line", b.carter, b.town.ifEmpty { "?" })
        graphics.text(font, title, innerX + 5, y + 3, fade(if (b.waylaid) Ui.RED else Ui.GOLD, k), false)
        val ago = Component.translatable(if (b.ago >= 48) "gui.guildmark.trade.house.book.ago_days" else "gui.guildmark.trade.house.book.ago_hours", if (b.ago >= 48) b.ago / 24 else b.ago)
        val net = b.net
        val made = Component.literal(if (net > 0) "+$net" else "$net")
        graphics.text(font, made, innerX + innerWidth - 5 - font.width(made), y + 3, fade(if (net > 0) Ui.GREEN else if (net < 0) Ui.RED else Ui.TEXT_SOFT, k), false)
        graphics.text(font, ago, innerX + innerWidth - 12 - font.width(made) - font.width(ago), y + 3, fade(Ui.TEXT_FAINT, k), false)
        // The goods: a small icon and count each, then the loss if it was waylaid
        var x = innerX + 5
        val gy = y + 13
        val pose = graphics.pose()
        for ((id, n) in b.goods.take(5)) {
            val stack = Good.byId(id)?.item?.let(::ItemStack) ?: continue
            if (k > 0.3f) {
                pose.pushMatrix()
                pose.translate(x.toFloat(), gy.toFloat())
                pose.scale(0.5f, 0.5f)
                graphics.item(stack, 0, 0)
                pose.popMatrix()
            }
            graphics.text(font, "$n", x + 10, gy, fade(Ui.TEXT_SOFT, k), false)
            x += 14 + font.width("$n")
        }
        if (b.waylaid) {
            val loss = Component.translatable("gui.guildmark.trade.house.book.waylaid", b.lost, b.repair)
            graphics.text(font, loss, max(x + 4, innerX + innerWidth - 5 - font.width(loss)), gy, fade(Ui.RED, k), false)
        } else {
            val got = Component.translatable("gui.guildmark.trade.house.book.got", b.got, b.cost)
            graphics.text(font, got, max(x + 4, innerX + innerWidth - 5 - font.width(got)), gy, fade(Ui.TEXT_FAINT, k), false)
        }
    }

    // ---- Purse ----

    private fun drawPurse(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        val shown = Mth.lerp(a, purseLast, purse).roundToInt()
        val glow = bump(t - purseAt, 20f)
        graphics.text(font, Component.translatable("gui.guildmark.trade.house.purse.holds", marksText(shown)), innerX + 2, y0, fade(if (glow > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT, k), false)
        val float = if (!view.open || view.hire == "closed") Component.translatable("gui.guildmark.trade.house.purse.all")
        else Component.translatable("gui.guildmark.trade.house.purse.float", marksText(view.float), marksText(view.takeable))
        var y = y0 + 11
        for (part in font.split(float, innerWidth - 4).take(2)) {
            graphics.text(font, part, innerX + 2, y, fade(Ui.TEXT_FAINT, k), false)
            y += 9
        }
        // What the wages draw a day, and the float note when the purse is short of a wagon
        val day = view.carters.size * view.wage + view.carters.sumOf { it.guards } * view.guardWage / 10
        val draw = Component.translatable("gui.guildmark.trade.house.purse.daily", marksText(day))
        graphics.text(font, draw, innerX + 2, listTop + 12, fade(if (view.idle == "float") Ui.RED else Ui.TEXT_SOFT, k), false)
        drawDesks(graphics, k, mouseX, mouseY, t)
        // Since it opened
        y = listTop + 58
        Ui.divider(graphics, innerX, y - 6, innerWidth, gem = false)
        Ui.headingAt(graphics, font, Component.translatable("gui.guildmark.trade.house.purse.since", tenths(view.days)), innerX + 2, y, fade(Ui.GOLD, k))
        y += 14
        val lines = listOf(
            Component.translatable("gui.guildmark.trade.house.purse.trips", view.trips, view.waylaid) to Ui.TEXT_SOFT,
            Component.translatable("gui.guildmark.trade.house.purse.wages", marksText(view.wages)) to Ui.TEXT_SOFT,
            Component.translatable("gui.guildmark.trade.house.purse.net", signed(view.net), perDay(view.net, view.days)) to (if (view.net > 0) Ui.GREEN else if (view.net < 0) Ui.RED else Ui.TEXT_SOFT),
        )
        for ((line, colour) in lines) {
            graphics.text(font, line, innerX + 2, y, fade(colour, k), false)
            y += 11
        }
    }

    /** [net] Marks over [days] tenths of a day, a day, to a tenth. */
    private fun perDay(net: Int, days: Int): String {
        if (days <= 0) return "0"
        val tenth = (net * 100.0 / days).roundToInt()
        return (if (tenth < 0) "-" else "") + tenths(abs(tenth))
    }

    private fun signed(n: Int): String = if (n > 0) "+$n" else "$n"

    // ---- Shared ----

    /** The buttons laid on this tab, lit under the cursor and pressed down a moment when clicked. */
    private fun drawDesks(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float) {
        val last = pressed
        for (d in desks()) {
            val lit = d.ok && mouseX >= d.x && mouseX < d.x + d.w && mouseY >= d.y && mouseY < d.y + 13
            val down = last != null && last.action == d.action && last.carter == d.carter && last.count == d.count && t - pressedAt < 3f
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(d.ok, lit), d.x, d.y + if (down) 1 else 0, d.w, 13, ARGB.white(k))
            graphics.centeredText(font, d.label, d.x + d.w / 2, d.y + 3 + if (down) 1 else 0, fade(if (!d.ok) Ui.TEXT_FAINT else if (lit) Ui.GOLD_BRIGHT else Ui.TEXT, k))
        }
    }

    private fun drawScrollbar(graphics: GuiGraphicsExtractor, scrolled: Float) {
        val most = maxScroll()
        if (most <= 0f) return
        val span = listBottom - listTop
        val thumb = max(12, span * span / (span + most.toInt()))
        val top = listTop + ((span - thumb) * (scrolled / most)).roundToInt()
        graphics.fill(panelX + panelWidth - 6, listTop, panelX + panelWidth - 5, listBottom, 0x40D9B46A)
        graphics.fill(panelX + panelWidth - 7, top, panelX + panelWidth - 4, top + thumb, Ui.GOLD)
    }

    /** The player's own Marks at the left, counting to what they have now; the House's purse at the right. */
    private fun drawFooter(graphics: GuiGraphicsExtractor, k: Float, t: Float, a: Float) {
        Ui.divider(graphics, innerX, panelY + panelHeight - FOOTER - 2, innerWidth)
        val y = footerY()
        graphics.item(MARK, innerX, y - 4)
        val own = Mth.lerp(a, marksLast, marks).roundToInt()
        val glow = bump(t - marksAt, 14f)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(innerX + 20f, y + 4f)
        val pop = 1f + 0.15f * glow
        pose.scale(pop, pop)
        graphics.text(font, marksText(own), 0, -4, fade(if (glow > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT, k), true)
        pose.popMatrix()
        val shown = Mth.lerp(a, purseLast, purse).roundToInt()
        val pglow = bump(t - purseAt, 14f)
        val text = Component.translatable("gui.guildmark.trade.house.footer.purse", marksText(shown))
        pose.pushMatrix()
        pose.translate((innerX + innerWidth - font.width(text) * (1f + 0.15f * pglow)), y + 4f)
        pose.scale(1f + 0.15f * pglow, 1f + 0.15f * pglow)
        graphics.text(font, text, 0, -4, fade(if (pglow > 0.05f) Ui.GOLD_BRIGHT else if (view.idle == "float") Ui.RED else Ui.TEXT_SOFT, k), true)
        pose.popMatrix()
    }

    private fun drawFloaters(graphics: GuiGraphicsExtractor, t: Float) {
        for (f in floaters) {
            val p = (t - f.born) / FLOAT
            if (p < 0f || p > 1f) continue
            val alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
            graphics.text(font, f.text, f.x, (f.y - ease(p) * 10f).roundToInt(), ARGB.color(alpha, f.color), true)
        }
    }

    private fun marksText(n: Int): Component = Component.translatable(if (n == 1) "gui.guildmark.market.marks.one" else "gui.guildmark.market.marks", n)

    /** [n] tenths of an hour as hours. */
    private fun hours(n: Int): Component = Component.translatable("gui.guildmark.trade.house.hours", tenths(n))

    private fun tenths(n: Int): String = if (n % 10 == 0) "${n / 10}" else "${n / 10}.${n % 10}"

    private fun drawTip(graphics: GuiGraphicsExtractor, lines: List<Pair<Component, Int>>, mouseX: Int, mouseY: Int) {
        val wrapped = lines.flatMap { (text, color) -> font.split(text, TIP_WIDTH).map { it to color } }
        if (wrapped.isEmpty()) return
        val w = wrapped.maxOf { font.width(it.first) } + 16
        val h = wrapped.size * 10 + 10
        var x = mouseX + 10
        if (x + w > width - 4) x = max(4, mouseX - 10 - w)
        val y = (mouseY - 6).coerceIn(4, max(4, height - h - 4))
        graphics.nextStratum()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        for ((i, line) in wrapped.withIndex()) graphics.text(font, line.first, x + 8, y + 6 + i * 10, line.second, false)
    }

    private fun ease(k: Float): Float = 1f - (1f - k) * (1f - k) * (1f - k)

    /** 1 just after something happened, falling to 0 over [length] ticks. */
    private fun bump(since: Float, length: Float): Float = if (since < 0f || since >= length) 0f else (1f - since / length).let { it * it }

    private fun fade(color: Int, k: Float): Int = ARGB.multiplyAlpha(color, k)

    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    private companion object {
        fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

        val PENNANT = sprite("stall/pennant")
        val PENNANT_PAINT = sprite("stall/pennant_paint")
        val MARK: ItemStack by lazy { ItemStack(ModItems.GUILD_MARK.get()) }

        /** The spoke's pixel in a 3x3 wheel, a quarter turn at a time. */
        val SPOKES = arrayOf(1 to 0, 2 to 1, 1 to 2, 0 to 1)

        /** The digit of [n] (no less than 0) in place [r], the units 0. */
        fun digit(n: Int, r: Int): Int {
            var v = max(0, n)
            repeat(r) { v /= 10 }
            return v % 10
        }

        /** The tabs, in order, and their names. */
        const val CARTERS = 0
        const val BOOK = 1
        const val PURSE = 2
        const val TABS = 3
        val TAB_KEYS = arrayOf("gui.guildmark.trade.house.tab.carters", "gui.guildmark.trade.house.tab.book", "gui.guildmark.trade.house.tab.purse")

        /** The tab shown when a desk was last open, to open on again (not kept past the game's closing). */
        var lastTab = CARTERS

        const val PAD = 12

        /** Room over the panel for the name board: its chains and the board itself; the board's height; the counting frame's rods and width. */
        const val SIGN_ROOM = 44
        const val CHAIN = 10
        const val SIGN_H = 28
        const val PENNANT_W = 11
        const val PENNANT_H = 16
        const val FRAME_ROWS = 4
        const val FRAME_W = 40

        /** The tabs' row (from the panel's top) and height, the lines over the list, and the foot. */
        const val TABS_TOP = 8
        const val TAB_H = 14
        const val HEADLINE = 36
        const val FOOTER = 24

        /** A carter's row, a book line, the gap between; the let-go button's width. */
        const val ROW = 26
        const val BOOK_ROW = 24
        const val GAP = 3
        const val LET_GO = 50
        const val TIP_WIDTH = 200

        const val HEARTBEAT = 40
        const val FLOAT = 24f
    }
}

/** The client's half of the trading house's desk: its screen as the server opens and updates it. */
object HouseClient {
    /** The server opened [view]'s desk. */
    fun open(view: HouseView) {
        val minecraft = Minecraft.getInstance()
        val open = minecraft.gui.screen() as? HouseScreen
        // A held use key can send the same click twice: keep the screen that's up
        if (open != null && open.place == view.place) {
            open.update(view)
            return
        }
        minecraft.gui.setScreen(HouseScreen(view))
    }

    /** The House as it stands now, for the desk that's open if it's [view]'s; or, [closed], word that it's closed. */
    fun update(view: HouseView, closed: Boolean) {
        val screen = Minecraft.getInstance().gui.screen() as? HouseScreen ?: return
        if (screen.place != view.place) return
        if (closed) screen.shut() else screen.update(view)
    }
}
