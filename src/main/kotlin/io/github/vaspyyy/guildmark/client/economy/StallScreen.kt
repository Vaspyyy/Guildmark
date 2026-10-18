package io.github.vaspyyy.guildmark.client.economy

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.StallDesk
import io.github.vaspyyy.guildmark.economy.SupplierLink
import io.github.vaspyyy.guildmark.network.StallActionPayload
import io.github.vaspyyy.guildmark.network.StallClosePayload
import io.github.vaspyyy.guildmark.network.StallGood
import io.github.vaspyyy.guildmark.network.StallPennant
import io.github.vaspyyy.guildmark.network.StallRefreshPayload
import io.github.vaspyyy.guildmark.network.StallView
import io.github.vaspyyy.guildmark.registry.ModItems
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
 * A market hall table's screen (see StallDesk), in the market screen's manner: a panel down the left under an awning
 * striped in the renter's colour, with their pennant swaying at its corner. A free table offers itself for a week's
 * keep; on the player's own stall each of their goods has its row, its icon with how many they carry, a bar of what's on
 * the table and what it sells to the town at, and buttons to set more out or take some back (a shift-click moves them
 * all). Below, the till, coins dropping into it as the stall sells and flying to the purse when it's emptied, and the
 * week: paid ahead, or given up (twice asked). Someone else's stall shows whose it is and what's on it. Everything
 * eases to its new value as the server says it's changed.
 */
class StallScreen(private var view: StallView) : Screen(Component.translatable("gui.guildmark.stall")) {
    /** Which table it shows: the capital's name and the table's place in the hall. */
    val place: String get() = view.place
    val table: Int get() = view.table

    /** One good's line, with what's shown of it eased toward the server's latest. */
    private inner class Row(var g: StallGood) {
        val good = Good.byId(g.good)
        val stack: ItemStack = good?.item?.let(::ItemStack) ?: ItemStack.EMPTY

        /** The bar of what's on the table as shown, last tick and now, and the level it's coming from, fading. */
        var bar = g.stall.toFloat()
        var barLast = bar
        var ghost = bar
        var ghostLast = bar

        /** When the count carried and the count on the table last changed, and each button was pressed. */
        var carriedAt = -100
        var stallAt = -100
        var pressedPut = -100
        var pressedTake = -100

        fun name(): Component = good?.let { Component.translatable(it.key) } ?: Component.literal(g.good)
        fun many(): Component = good?.let { Component.translatable(it.key + ".many") } ?: Component.literal(g.good)
    }

    /** A button at the foot of the panel: what it does, where it is, what it says, and whether it can be pressed. */
    private class Desk(val action: Int, val x: Int, val y: Int, val w: Int, val label: Component, val ok: Boolean, val tip: Component?)

    private var rows: List<Row> = view.goods.map { Row(it) }
    private var desk: List<Desk> = listOf()

    private var ticks = 0

    /** The server closed the screen: nothing to tell it on the way out. */
    private var shutHere = false

    /** The purse and the till as shown, last tick and now, and when each last changed. */
    private var purse = view.marks.toFloat()
    private var purseLast = purse
    private var purseAt = -100
    private var till = view.till.toFloat()
    private var tillLast = till
    private var tillAt = -100

    /** The last desk button pressed, and when; until when a second press of "give it up" does it. */
    private var pressedDesk = -1
    private var pressedDeskAt = -100
    private var confirmUntil = -1

    private class Floater(val text: Component, val x: Int, val y: Int, val color: Int, val born: Int)
    private class Coin(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val born: Int)

    private val floaters = ArrayList<Floater>()
    private val coins = ArrayList<Coin>()

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
    private var buttonWidth = 0

    private val innerX get() = panelX + PAD
    private val innerWidth get() = panelWidth - PAD * 2
    private val textX get() = innerX + 22

    private fun textWidth(): Int = innerWidth - 22 - buttonWidth - 6

    override fun init() {
        panelWidth = min(width - 20, Mth.clamp(width / 2 - 12, 236, 300))
        panelX = 10
        panelY = AWNING_HEIGHT + 4
        panelHeight = height - panelY - 10
        buttonWidth = Mth.clamp(innerWidth - 130, 76, 104)
        listTop = panelY + HEADER
        listBottom = panelY + panelHeight - FOOTER - DESK
        scroll = scroll.coerceIn(0f, maxScroll())
        layDesk()
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
            ClientPacketDistributor.sendToServer(StallClosePayload)
        }
    }

    // ---- From the server ----

    /** The table as it stands now: everything that changed eases to its new place. */
    fun update(next: StallView) {
        val before = view
        view = next
        if (next.goods.map { it.good } != rows.map { it.g.good }) {
            val old = rows.associateBy { it.g.good }
            rows = next.goods.map { g -> old[g.good]?.also { it.g = g } ?: Row(g) }
        }
        val gained = next.marks - before.marks
        if (gained != 0) {
            purseAt = ticks
            floaters.add(Floater(Component.literal(if (gained > 0) "+$gained" else "$gained"), innerX + 18, footerY() - 4, if (gained > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, ticks))
            // Coins from the till to the purse when it's emptied; from the purse to the table when the week is paid
            if (gained > 0 && next.till < before.till) flyCoins(tillX() + 12f, deskY() + 6f, innerX + 8f, footerY() + 3f, gained)
            if (gained < 0) flyCoins(innerX + 8f, footerY() + 3f, innerX + innerWidth / 2f, deskY() + 28f, -gained)
        }
        if (next.till > before.till && next.state == StallDesk.YOURS && before.state == StallDesk.YOURS) {
            // The stall sold something: a coin drops into the till
            tillAt = ticks
            coins.add(Coin(tillX() + 12f, deskY() - 16f, tillX() + 12f, deskY() + 6f, ticks))
            Ui.play(StallDesk.TILL, 1.2f, 0.35f)
        }
        for (row in rows) {
            val g = next.goods.firstOrNull { it.good == row.g.good } ?: continue
            row.g = g
            val old = before.goods.firstOrNull { it.good == g.good } ?: continue
            if (g.stall < old.stall) row.ghost = max(row.ghost, row.bar) else if (g.stall > old.stall) row.ghost = min(row.ghost, row.bar)
            if (g.stall != old.stall) row.stallAt = ticks
            if (g.carried != old.carried) {
                row.carriedAt = ticks
                rowTop(row)?.let { y ->
                    val n = g.carried - old.carried
                    floaters.add(Floater(Component.literal(if (n > 0) "+$n" else "$n"), innerX + 4, y - 2, if (n > 0) Ui.GREEN else Ui.TEXT_SOFT, ticks))
                }
            }
        }
        if (next.state != before.state) confirmUntil = -1
        scroll = scroll.coerceIn(0f, maxScroll())
        layDesk()
    }

    /** The screen is closed on the server's side. */
    fun shut() {
        shutHere = true
        onClose()
    }

    private fun flyCoins(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
        repeat(min(6, n + 1)) { i -> coins.add(Coin(x0, y0, x1, y1, ticks + i * 2)) }
    }

    // ---- Every tick ----

    override fun tick() {
        ticks++
        if (minecraft.player == null) {
            onClose()
            return
        }
        if (ticks % HEARTBEAT == 0) ClientPacketDistributor.sendToServer(StallRefreshPayload)
        purseLast = purse
        purse += (view.marks - purse) * 0.25f
        if (abs(view.marks - purse) < 0.05f) purse = view.marks.toFloat()
        tillLast = till
        till += (view.till - till) * 0.25f
        if (abs(view.till - till) < 0.05f) till = view.till.toFloat()
        for (row in rows) {
            row.barLast = row.bar
            row.bar += (row.g.stall - row.bar) * 0.22f
            if (abs(row.g.stall - row.bar) < 0.02f) row.bar = row.g.stall.toFloat()
            row.ghostLast = row.ghost
            row.ghost += (row.bar - row.ghost) * 0.08f
        }
        scrollLast = scrollShown
        scrollShown += (scroll - scrollShown) * 0.5f
        floaters.removeIf { ticks - it.born > FLOAT }
        coins.removeIf { ticks - it.born > FLIGHT }
        if (confirmUntil in 1..ticks) {
            confirmUntil = -1
            layDesk()
        }
    }

    // ---- Layout ----

    private fun maxScroll(): Float = max(0, rows.size * (ROW + GAP) - (listBottom - listTop)).toFloat()

    private fun deskY(): Int = listBottom + 8

    private fun footerY(): Int = panelY + panelHeight - FOOTER + 10

    private fun tillX(): Int = innerX

    /** Where [row]'s top is on screen now, or null if it's scrolled out of the list. */
    private fun rowTop(row: Row): Int? {
        var y = listTop - scrollShown.roundToInt()
        for (r in rows) {
            if (r === row) return if (y + ROW <= listTop || y >= listBottom) null else y
            y += ROW + GAP
        }
        return null
    }

    /** The row and button ([Pair.second]: true for setting out) under the cursor. */
    private fun buttonAt(mx: Double, my: Double): Pair<Row, Boolean>? {
        if (view.state != StallDesk.YOURS || my < listTop || my >= listBottom) return null
        val bx = innerX + innerWidth - buttonWidth
        if (mx < bx || mx >= bx + buttonWidth) return null
        var y = listTop - scrollShown.roundToInt()
        for (r in rows) {
            if (my >= y + 1 && my < y + 14) return r to true
            if (my >= y + 15 && my < y + 28) return r to false
            y += ROW + GAP
        }
        return null
    }

    /** The row whose good is under the cursor. */
    private fun rowAt(mx: Double, my: Double): Row? {
        val right = if (view.state == StallDesk.YOURS) innerX + innerWidth - buttonWidth - 4 else innerX + innerWidth
        if (my < listTop || my >= listBottom || mx < innerX || mx >= right) return null
        var y = listTop - scrollShown.roundToInt()
        for (r in rows) {
            if (my >= y && my < y + ROW) return r
            y += ROW + GAP
        }
        return null
    }

    /** The desk's buttons for the table as it stands: rent it, or the till and the week. */
    private fun layDesk() {
        val y = deskY()
        val out = ArrayList<Desk>()
        val why = view.refusal.takeIf { it.isNotEmpty() }?.let { Component.translatable(it, view.place, rankName(SupplierLink.TRUSTED)) }
        when (view.state) {
            StallDesk.FREE -> out.add(Desk(StallDesk.RENT, innerX, y + 22, innerWidth, Component.translatable("gui.guildmark.stall.rent", marks(view.keep)), why == null, why))
            StallDesk.YOURS -> {
                val half = (innerWidth - 4) / 2
                val asking = ticks < confirmUntil
                out.add(Desk(StallDesk.COLLECT, innerX + innerWidth - 70, y, 70, Component.translatable("gui.guildmark.stall.collect"), view.till > 0, null))
                out.add(Desk(StallDesk.PAY, innerX, y + 22, half, Component.translatable("gui.guildmark.stall.pay", marks(view.keep)), why == null, why))
                out.add(
                    Desk(
                        StallDesk.GIVE_UP, innerX + innerWidth - half, y + 22, half,
                        Component.translatable(if (asking) "gui.guildmark.stall.give_up_sure" else "gui.guildmark.stall.give_up"), true,
                        Component.translatable("gui.guildmark.stall.tip.give_up"),
                    ),
                )
            }
        }
        if ((view.kept > 0 || view.keptMarks > 0) && !view.merchant && view.state != StallDesk.YOURS) {
            out.add(Desk(StallDesk.COLLECT_KEPT, innerX + innerWidth - 70, y, 70, Component.translatable("gui.guildmark.stall.take_kept"), true, null))
        }
        desk = out
    }

    private fun deskAt(mx: Double, my: Double): Desk? = desk.firstOrNull { mx >= it.x && mx < it.x + it.w && my >= it.y && my < it.y + 13 }

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        deskAt(event.x(), event.y())?.let { d ->
            if (!d.ok) return true
            if (d.action == StallDesk.GIVE_UP && ticks >= confirmUntil) {
                confirmUntil = ticks + CONFIRM
                Ui.play(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 0.5f)
                layDesk()
                return true
            }
            Ui.select()
            pressedDesk = d.action
            pressedDeskAt = ticks
            confirmUntil = -1
            ClientPacketDistributor.sendToServer(StallActionPayload(d.action, "", 0))
            return true
        }
        val (row, putting) = buttonAt(event.x(), event.y()) ?: return super.mouseClicked(event, doubleClick)
        if (!(if (putting) canPut(row.g) else canTake(row.g))) return true
        Ui.select()
        if (putting) row.pressedPut = ticks else row.pressedTake = ticks
        ClientPacketDistributor.sendToServer(StallActionPayload(if (putting) StallDesk.PUT else StallDesk.TAKE, row.g.good, if (event.hasShiftDown()) StallDesk.MOST else BATCH))
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0 || maxScroll() <= 0f) return false
        scroll = (scroll - scrollY.toFloat() * 22f).coerceIn(0f, maxScroll())
        return true
    }

    private fun canPut(g: StallGood): Boolean = view.open && g.allowed > 0
    private fun canTake(g: StallGood): Boolean = g.stall > 0

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // The hall stays in view: a shade behind the panel only, fading off to the right
        val k = min(1f, (ticks + a) / 8f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SHADE, 0, 0, panelX + panelWidth + 90, height, ARGB.white(k))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val t = ticks + a
        val rise = ease(min(1f, t / 8f))
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(-(1f - rise) * 24f, 0f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL, panelX, panelY, panelWidth, panelHeight, ARGB.white(rise))
        drawAwning(graphics, rise, t)
        drawPennant(graphics, rise, t)
        drawHeader(graphics, rise, mouseX, mouseY)
        drawRows(graphics, mouseX, mouseY, t, a)
        drawDesk(graphics, rise, mouseX, mouseY, t, a)
        drawFooter(graphics, rise, t, a)
        pose.popMatrix()
        drawCoins(graphics, t)
        drawFloaters(graphics, t)
        if (rise >= 1f) {
            deskAt(mouseX.toDouble(), mouseY.toDouble())?.tip?.let { drawTip(graphics, listOf(it to Ui.TEXT), mouseX, mouseY) }
                ?: buttonAt(mouseX.toDouble(), mouseY.toDouble())?.let { (row, putting) -> drawTip(graphics, buttonTip(row, putting), mouseX, mouseY) }
                ?: rowAt(mouseX.toDouble(), mouseY.toDouble())?.let { drawTip(graphics, goodTip(it), mouseX, mouseY) }
        }
    }

    /** The stall's colour: its renter's, or the player's own over a free table. */
    private fun tint(): Int = Dye.entries[view.color.coerceIn(0, Dye.entries.size - 1)].rgb

    /** The striped awning over the panel, in the stall's colour, its hem stirring in a breeze that runs along it. */
    private fun drawAwning(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val x0 = panelX - 2
        val w = panelWidth + 4
        val y = panelY - AWNING_HEIGHT + 3
        val cloth = ARGB.white(k)
        val paint = ARGB.color(k, if (view.state == StallDesk.FREE) HALL else tint())
        var x = 0
        while (x < w) {
            val n = min(TILE, w - x)
            val sway = if (sin(t * 0.08f - x * 0.06f) > 0.9f) 1 else 0
            for (sprite in listOf(AWNING, AWNING_PAINT)) {
                val color = if (sprite == AWNING) cloth else paint
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, TILE, AWNING_HEIGHT, 0, 0, x0 + x, y, n, HEM, color)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, TILE, AWNING_HEIGHT, 0, HEM - 1, x0 + x, y + HEM - 1 + sway, n, AWNING_HEIGHT - HEM + 1, color)
            }
            x += TILE
        }
        for ((i, c) in RAIL.withIndex()) graphics.fill(x0, y + i, x0 + w, y + i + 1, ARGB.color(k, c))
        val pole = ARGB.color(k, 0x26242A)
        graphics.fill(x0 - 1, y, x0 + 2, y + AWNING_HEIGHT + 2, pole)
        graphics.fill(x0 + w - 2, y, x0 + w + 1, y + AWNING_HEIGHT + 2, pole)
    }

    /** The renter's pennant, hung from the awning's rail at the panel's corner, swinging gently; a bare hook over a free table. */
    private fun drawPennant(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        if (view.state == StallDesk.FREE) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(panelX + panelWidth - 18f, panelY - AWNING_HEIGHT + 4f)
        pose.rotate(sin(t * 0.07f) * 0.08f + sin(t * 0.19f + 1.3f) * 0.03f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PENNANT, -PENNANT_W / 2, 0, PENNANT_W, PENNANT_H, ARGB.white(k))
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PENNANT_PAINT, -PENNANT_W / 2, 0, PENNANT_W, PENNANT_H, ARGB.color(k, tint()))
        pose.popMatrix()
    }

    private fun drawHeader(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int) {
        val title = when (view.state) {
            StallDesk.YOURS -> Component.translatable("gui.guildmark.stall.yours")
            StallDesk.TAKEN -> Component.translatable("gui.guildmark.stall.theirs", view.renter)
            else -> Component.translatable("gui.guildmark.stall.free")
        }
        Ui.headingAt(graphics, font, title, innerX, panelY + 9, fade(Ui.GOLD, k))
        val place = Component.translatable("gui.guildmark.stall.place", view.place, view.table + 1)
        graphics.text(font, place, innerX + innerWidth - 20 - font.width(place), panelY + 11, fade(Ui.TEXT_FAINT, k), false)
        val status = when (view.state) {
            StallDesk.YOURS -> Component.translatable("gui.guildmark.stall.paid_for", time(view.left))
            StallDesk.TAKEN -> Component.translatable("gui.guildmark.stall.rented_for", time(view.left))
            else -> Component.translatable("gui.guildmark.stall.let_by_week", marks(view.keep))
        }
        graphics.text(font, status, innerX + 2, panelY + 25, fade(if (view.state == StallDesk.YOURS && view.left <= LOW) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, k), false)
        // What the hall's merchant keeps for them, or where they stand as a supplier here
        val kept = view.kept > 0 || view.keptMarks > 0
        val line = when {
            !view.open -> Component.translatable("gui.guildmark.stall.closed") to Ui.RED
            kept -> Component.translatable(if (view.merchant) "gui.guildmark.stall.kept_ask" else "gui.guildmark.stall.kept", view.kept, marks(view.keptMarks)) to Ui.GOLD
            view.state == StallDesk.TAKEN -> Component.translatable("gui.guildmark.stall.theirs_note") to Ui.TEXT_FAINT
            else -> Component.translatable("gui.guildmark.stall.your_rank", rankName(view.rank)) to Ui.TEXT_FAINT
        }
        graphics.text(font, line.first, innerX + 2, panelY + 37, fade(line.second, k), false)
        Ui.divider(graphics, innerX, listTop - 5, innerWidth)
    }

    private fun drawRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        if (rows.isEmpty()) {
            val empty = Component.translatable(if (view.state == StallDesk.TAKEN) "gui.guildmark.stall.bare" else "gui.guildmark.stall.nothing")
            graphics.centeredText(font, empty, innerX + innerWidth / 2, listTop + 12, Ui.TEXT_FAINT)
            return
        }
        val scrolled = Mth.lerp(a, scrollLast, scrollShown)
        graphics.enableScissor(innerX - 2, listTop, innerX + innerWidth + 2, listBottom)
        var y = listTop - scrolled.roundToInt()
        val shift = minecraft.hasShiftDown()
        for ((i, row) in rows.withIndex()) {
            if (y + ROW > listTop && y < listBottom) {
                val k = ease(Mth.clamp((t - 3f - i * 1.5f) / 6f, 0f, 1f))
                if (k > 0f) drawRow(graphics, row, y, k, mouseX, mouseY, t, a, shift)
            }
            y += ROW + GAP
        }
        graphics.disableScissor()
        val most = maxScroll()
        if (most > 0f) {
            val span = listBottom - listTop
            val thumb = max(12, span * span / (span + most.toInt()))
            val top = listTop + ((span - thumb) * (scrolled / most)).roundToInt()
            graphics.fill(panelX + panelWidth - 6, listTop, panelX + panelWidth - 5, listBottom, 0x40D9B46A)
            graphics.fill(panelX + panelWidth - 7, top, panelX + panelWidth - 4, top + thumb, Ui.GOLD)
        }
    }

    private fun drawRow(graphics: GuiGraphicsExtractor, row: Row, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float, shift: Boolean) {
        val g = row.g
        val yours = view.state == StallDesk.YOURS
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)
        val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= listTop && mouseY < listBottom
        if (hovered) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, innerX - 2, y, innerWidth + 4, ROW, ARGB.white(0.5f * k))

        // The good, with how many the player carries
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, innerX, y + 2, 18, 18, ARGB.white(k))
        if (k > 0.3f) {
            graphics.item(row.stack, innerX + 1, y + 3)
            if (g.carried > 0 && view.state != StallDesk.TAKEN) {
                val pop = 1f + 0.5f * bump(t - row.carriedAt, 8f)
                val count = g.carried.toString()
                pose.pushMatrix()
                pose.translate(innerX + 18f, y + 20f)
                pose.scale(pop, pop)
                graphics.text(font, count, -font.width(count), -8, if (t - row.carriedAt < 10f) Ui.GOLD_BRIGHT else Ui.TEXT, true)
                pose.popMatrix()
            }
        }

        // Its name, and what it sells to the town at
        val width = if (yours) textWidth() else innerWidth - 22
        graphics.text(font, row.name(), textX, y + 3, fade(Ui.TEXT, k), false)
        if (g.lotCount > 0) {
            val lot = Component.translatable("gui.guildmark.stall.sells_at", g.lotCount, marks(g.lotMarks))
            graphics.text(font, lot, textX + width - font.width(lot), y + 3, fade(Ui.TEXT_FAINT, k), false)
        }

        // What's on the table, and how many it's sold today
        val barWidth = width - 30
        drawBar(graphics, row, textX, y + 15, barWidth, k, t, a)
        val pop = 1f + 0.4f * bump(t - row.stallAt, 10f)
        val on = g.stall.toString()
        pose.pushMatrix()
        pose.translate(textX + barWidth + 4f, y + 14f)
        pose.scale(pop, pop)
        graphics.text(font, on, 0, 0, fade(if (g.stall == 0) Ui.TEXT_FAINT else Ui.TEXT_SOFT, k), false)
        pose.popMatrix()
        if (g.today > 0) {
            val sold = Component.translatable("gui.guildmark.stall.sold_today_short", g.today)
            graphics.text(font, sold, textX + width - font.width(sold), y + 22, fade(Ui.GREEN, k), false)
        }

        if (yours) {
            val bx = innerX + innerWidth - buttonWidth
            drawButton(graphics, row, true, bx, y + 1, k, mouseX, mouseY, t, shift)
            drawButton(graphics, row, false, bx, y + 15, k, mouseX, mouseY, t, shift)
        }
        pose.popMatrix()
    }

    /** What's on the table of a good, against a well-stocked table of it: easing when it changes, the change glowing as it fades. */
    private fun drawBar(graphics: GuiGraphicsExtractor, row: Row, x: Int, y: Int, w: Int, k: Float, t: Float, a: Float) {
        val inner = w - 2
        fun at(v: Float): Int = x + 1 + (inner * Mth.clamp(v / BAR_UNITS, 0f, 1f)).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.INSET, x, y, w, 6, ARGB.white(k))
        val shown = Mth.lerp(a, row.barLast, row.bar)
        val ghost = Mth.lerp(a, row.ghostLast, row.ghost)
        val end = at(shown)
        if (end > x + 1) graphics.fillGradient(x + 1, y + 1, end, y + 5, fade(0xFFE4BF72.toInt(), k), fade(0xFF96713A.toInt(), k))
        val was = at(ghost)
        if (abs(was - end) >= 1) {
            val glow = Mth.clamp(abs(ghost - shown) / 4f, 0f, 1f)
            if (was > end) graphics.fill(end, y + 1, was, y + 5, ARGB.color((150 * glow * k).toInt(), 0x9FD288))
            else graphics.fill(was, y + 1, end, y + 5, ARGB.color((170 * glow * k).toInt(), 0xF2E6B0))
        }
        val run = (t % 90f) / 30f
        if (run < 1f && end > x + 4) {
            val gx = x + 1 + ((end - x) * run).toInt()
            graphics.fill(gx, y + 1, min(end, gx + 3), y + 5, ARGB.color((60 * k).toInt(), 0xFFFFFF))
        }
    }

    /** A row's button: set out ([putting]) or take back, lit under the cursor, pressed down a moment, dimmed when it can't. */
    private fun drawButton(graphics: GuiGraphicsExtractor, row: Row, putting: Boolean, x: Int, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float, shift: Boolean) {
        val g = row.g
        val ok = if (putting) canPut(g) else canTake(g)
        val lit = ok && mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 13 && mouseY >= listTop && mouseY < listBottom
        val down = t - (if (putting) row.pressedPut else row.pressedTake) < 3f
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(ok, lit), x, y + if (down) 1 else 0, buttonWidth, 13, ARGB.white(k))
        val n = if (putting) g.allowed else g.stall
        val label = Component.translatable(
            if (putting) (if (shift) "gui.guildmark.stall.put_all" else "gui.guildmark.stall.put") else (if (shift) "gui.guildmark.stall.take_all" else "gui.guildmark.stall.take"),
            if (shift) n else min(n, BATCH),
        )
        graphics.centeredText(font, label, x + buttonWidth / 2, y + 3 + if (down) 1 else 0, fade(if (!ok) Ui.TEXT_FAINT else if (lit) Ui.GOLD_BRIGHT else Ui.TEXT, k))
    }

    /** The till and the week's buttons, or the free table's rent. */
    private fun drawDesk(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        Ui.divider(graphics, innerX, listBottom + 3, innerWidth, gem = false)
        val y = deskY()
        if (view.state == StallDesk.YOURS) {
            // The till: its coins heaped as it fills, a glint running over them, a jolt when one drops in
            val shown = Mth.lerp(a, tillLast, till)
            val jolt = bump(t - tillAt, 8f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TILL, tillX(), y - 2 - (jolt * 1.5f).roundToInt(), TILL_W, TILL_H, ARGB.white(k))
            val heap = when {
                shown >= 20f -> TILL_COINS_HIGH
                shown >= 4f -> TILL_COINS
                shown >= 0.5f -> TILL_COINS_LOW
                else -> null
            }
            heap?.let { graphics.blitSprite(RenderPipelines.GUI_TEXTURED, it, tillX(), y - 2 - (jolt * 1.5f).roundToInt(), TILL_W, TILL_H, ARGB.white(k)) }
            if (heap != null && (t % 70f) < 8f) graphics.fill(tillX() + 6 + ((t % 70f) * 1.5f).toInt(), y + 1, tillX() + 8 + ((t % 70f) * 1.5f).toInt(), y + 2, ARGB.color((90 * k).toInt(), 0xFFFFFF))
            val text = if (view.till > 0) Component.translatable("gui.guildmark.stall.till", marks(shown.roundToInt())) else Component.translatable("gui.guildmark.stall.till_empty")
            graphics.text(font, text, tillX() + TILL_W + 6, y + 3, fade(if (bump(t - tillAt, 14f) > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT, k), false)
        } else if (view.state == StallDesk.TAKEN) {
            graphics.centeredText(font, Component.translatable("gui.guildmark.stall.rented_by", view.renter), innerX + innerWidth / 2, y + 25, fade(Ui.TEXT_SOFT, k))
        }
        for (d in desk) {
            val lit = d.ok && mouseX >= d.x && mouseX < d.x + d.w && mouseY >= d.y && mouseY < d.y + 13
            val down = pressedDesk == d.action && t - pressedDeskAt < 3f
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(d.ok, lit), d.x, d.y + if (down) 1 else 0, d.w, 13, ARGB.white(k))
            val asking = d.action == StallDesk.GIVE_UP && ticks < confirmUntil
            val color = when {
                !d.ok -> Ui.TEXT_FAINT
                asking -> Ui.RED
                lit -> Ui.GOLD_BRIGHT
                else -> Ui.TEXT
            }
            graphics.centeredText(font, d.label, d.x + d.w / 2, d.y + 3 + if (down) 1 else 0, fade(color, k))
            if (asking) {
                val w = (d.w * ((confirmUntil - t) / CONFIRM)).roundToInt().coerceIn(0, d.w)
                graphics.fill(d.x, d.y + 13, d.x + w, d.y + 15, ARGB.color(0.9f * k, Ui.RED))
            }
        }
    }

    /** The purse: the player's Guild Marks, counting up or down to what they have now, and the hint for shift-clicking. */
    private fun drawFooter(graphics: GuiGraphicsExtractor, k: Float, t: Float, a: Float) {
        Ui.divider(graphics, innerX, panelY + panelHeight - FOOTER - 2, innerWidth)
        val y = footerY()
        graphics.item(MARK, innerX, y - 4)
        val shown = Mth.lerp(a, purseLast, purse).roundToInt()
        val glow = bump(t - purseAt, 14f)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(innerX + 20f, y + 4f)
        val pop = 1f + 0.15f * glow
        pose.scale(pop, pop)
        graphics.text(font, marks(shown), 0, -4, fade(if (glow > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT, k), true)
        pose.popMatrix()
        if (view.state == StallDesk.YOURS) {
            val hint = Component.translatable("gui.guildmark.stall.hint")
            graphics.text(font, hint, innerX + innerWidth - font.width(hint), y, fade(if (minecraft.hasShiftDown()) Ui.GOLD else Ui.TEXT_FAINT, k), false)
        }
    }

    private fun drawCoins(graphics: GuiGraphicsExtractor, t: Float) {
        for (c in coins) {
            val p = (t - c.born) / FLIGHT
            if (p < 0f || p > 1f) continue
            val e = ease(p)
            val x = Mth.lerp(e, c.x0, c.x1)
            val y = Mth.lerp(e, c.y0, c.y1) - sin(p * Math.PI.toFloat()) * (if (c.x0 == c.x1) 0f else 18f)
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x, y)
            val s = 1f - 0.25f * p
            pose.scale(s, s)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, COIN, -3, -3, 7, 7, ARGB.white(min(1f, (1f - p) * 4f)))
            pose.popMatrix()
        }
    }

    private fun drawFloaters(graphics: GuiGraphicsExtractor, t: Float) {
        for (f in floaters) {
            val p = (t - f.born) / FLOAT
            if (p < 0f || p > 1f) continue
            val alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
            graphics.text(font, f.text, f.x, (f.y - ease(p) * 10f).roundToInt(), ARGB.color(alpha, f.color), true)
        }
    }

    // ---- Words ----

    private fun buttonTip(row: Row, putting: Boolean): List<Pair<Component, Int>> {
        val g = row.g
        val lines = ArrayList<Pair<Component, Int>>()
        if (putting) when {
            !view.open -> lines.add(Component.translatable("gui.guildmark.stall.closed") to Ui.TEXT)
            g.allowed > 0 -> lines.add(Component.translatable("gui.guildmark.stall.tip.put", min(g.allowed, BATCH), row.many()) to Ui.TEXT)
            g.carried == 0 -> lines.add(Component.translatable("gui.guildmark.stall.tip.none", row.many()) to Ui.TEXT)
            g.bought > 0 -> lines.add(Component.translatable("gui.guildmark.stall.tip.bought", g.bought, view.place) to Ui.TEXT)
            else -> lines.add(Component.translatable("gui.guildmark.stall.tip.full", row.many()) to Ui.TEXT)
        } else lines.add(Component.translatable(if (g.stall > 0) "gui.guildmark.stall.tip.take" else "gui.guildmark.stall.tip.nothing", min(g.stall, BATCH), row.many()) to Ui.TEXT)
        lines.add(Component.translatable("gui.guildmark.stall.tip.shift") to Ui.TEXT_FAINT)
        return lines
    }

    /** What's on the table of [row]'s good and how it sells, for the tooltip over its name. */
    private fun goodTip(row: Row): List<Pair<Component, Int>> {
        val g = row.g
        val lines = ArrayList<Pair<Component, Int>>()
        lines.add(row.name() to Ui.GOLD)
        lines.add(Component.translatable("gui.guildmark.stall.tip.on_table", g.stall) to Ui.TEXT)
        if (g.lotCount > 0) lines.add(Component.translatable("gui.guildmark.stall.tip.sells", g.lotCount, row.many(), marks(g.lotMarks)) to Ui.TEXT_SOFT)
        lines.add(Component.translatable("gui.guildmark.stall.tip.today", g.today) to Ui.TEXT_SOFT)
        if (view.state != StallDesk.TAKEN) lines.add(Component.translatable("gui.guildmark.stall.tip.carried", g.carried) to Ui.TEXT_SOFT)
        if (g.bought > 0) lines.add(Component.translatable("gui.guildmark.stall.tip.bought", g.bought, view.place) to Ui.GOLD)
        return lines
    }

    private fun marks(n: Int): Component = Component.translatable(if (n == 1) "gui.guildmark.market.marks.one" else "gui.guildmark.market.marks", n)

    /** A rank's name as a supplier of this capital. */
    private fun rankName(rank: Int): Component = StallDesk.rankName(rank, view.place)

    /** [hours] of the market's clock: "5 days, 3 hours", "1 day", "4 hours", "less than an hour". */
    private fun time(hours: Int): Component {
        val days = hours / 24
        val left = hours % 24
        val d = Component.translatable(if (days == 1) "gui.guildmark.stall.time.day" else "gui.guildmark.stall.time.days", days)
        val h = Component.translatable(if (left == 1) "gui.guildmark.stall.time.hour" else "gui.guildmark.stall.time.hours", left)
        return when {
            days > 0 && left > 0 -> Component.translatable("gui.guildmark.stall.time.both", d, h)
            days > 0 -> d
            left > 0 -> h
            else -> Component.translatable("gui.guildmark.stall.time.none")
        }
    }

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

        val AWNING = sprite("market/awning")
        val AWNING_PAINT = sprite("market/awning_paint")
        val SHADE = sprite("market/shade")
        val COIN = sprite("compass/lost_marks")
        val PENNANT = sprite("stall/pennant")
        val PENNANT_PAINT = sprite("stall/pennant_paint")
        val TILL = sprite("stall/till")
        val TILL_COINS_LOW = sprite("stall/till_coins_low")
        val TILL_COINS = sprite("stall/till_coins")
        val TILL_COINS_HIGH = sprite("stall/till_coins_high")
        val MARK: ItemStack by lazy { ItemStack(ModItems.GUILD_MARK.get()) }

        /** A free table's awning: the market hall's blue. */
        const val HALL = 0x3A5D9A

        const val PAD = 12
        const val TILE = 16
        const val AWNING_HEIGHT = 14
        const val HEM = 10
        val RAIL = intArrayOf(0x0C0B0E, 0x54525C, 0x3A3840, 0x26242A)
        /** The pennant's and the till's sprites (art/scripts/stall_art.py). */
        const val PENNANT_W = 11
        const val PENNANT_H = 16
        const val TILL_W = 22
        const val TILL_H = 14
        /** The panel's top as far as the list, the desk under the list, and the purse at the foot. */
        const val HEADER = 52
        const val DESK = 46
        const val FOOTER = 24
        const val ROW = 30
        const val GAP = 3
        const val TIP_WIDTH = 190
        /** Units of a good on the table its bar shows full (two neat lanes of the display). */
        const val BAR_UNITS = 64f
        /** How many a click sets out or takes back (a shift-click: all of them). */
        const val BATCH = 16
        /** Hours of rent left under which the time paid for shows bright. */
        const val LOW = 24
        const val HEARTBEAT = 40
        const val FLIGHT = 14f
        const val FLOAT = 24f
        /** Ticks the second press of "give it up" may come in. */
        const val CONFIRM = 80
    }
}

/** The client's half of players' own stalls: a market hall table's screen as the server opens and updates it, and the pennants over the let tables. */
object StallClient {
    /** Starts the pennants (see [StallPennants]). */
    fun register() = StallPennants.register()

    /** The server opened [view]'s table. */
    fun open(view: StallView) {
        val minecraft = Minecraft.getInstance()
        val open = minecraft.gui.screen() as? StallScreen
        // A held use key can send the same click twice: keep the screen that's up
        if (open != null && open.place == view.place && open.table == view.table) {
            open.update(view)
            return
        }
        minecraft.gui.setScreen(StallScreen(view))
    }

    /** The table as it stands now, for the screen that's open if it's [view]'s; or, [closed], word that it's closed. */
    fun update(view: StallView, closed: Boolean) {
        val screen = Minecraft.getInstance().gui.screen() as? StallScreen ?: return
        if (screen.place != view.place || screen.table != view.table) return
        if (closed) screen.shut() else screen.update(view)
    }

    /** Every let table there is now, for the pennants. */
    fun pennants(pennants: List<StallPennant>) = StallPennants.set(pennants)
}
