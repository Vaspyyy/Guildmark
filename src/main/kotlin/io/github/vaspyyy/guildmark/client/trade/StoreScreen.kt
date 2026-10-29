package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.network.StoreActionPayload
import io.github.vaspyyy.guildmark.network.StoreClosePayload
import io.github.vaspyyy.guildmark.network.StoreGood
import io.github.vaspyyy.guildmark.network.StoreRefreshPayload
import io.github.vaspyyy.guildmark.network.StoreTown
import io.github.vaspyyy.guildmark.network.StoreView
import io.github.vaspyyy.guildmark.network.StoreWagon
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.trade.Ledger
import io.github.vaspyyy.guildmark.trade.StoreDesk
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.trade.carry.WagonBill
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
import net.minecraft.world.item.Items
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Carriers' Store's desk (see [StoreDesk]): a panel under the Store's name board, which hangs on two chains with the owner's pennant
 * flying from its pole and the hoist's rope beside it, its crate going up when goods are bonded and coming down when they are taken back,
 * as the hoist on the Store itself does. Five tabs, the gold rule under them sliding to the one chosen:
 *
 * - *Bonded goods*: a row for each good in the Store or in the pack, with what is in the Store (hauled and plain, a bar of each), the
 *   buttons to bond more and take some back (a shift-click: all), and each hauled parcel under it with the town it came from and its
 *   days, thirty notches running out; a parcel just bonded is pressed with the owner's seal.
 * - *Word*: the Store's town as it stands now and each town where the owner keeps a stall: what it is short of and has too much of, and
 *   its haul tickets.
 * - *Wagon*: the yard a wagon called to the Store comes to, with a pointer to it from where the player stands and faces.
 * - *Guards*: the guards hired, and their wage.
 * - *Account*: the clerk's wage, four weeks of days with the ones paid for lit, the button to pay a week (the coins fly to the days), and
 *   what the half rent saves on the tables rented.
 *
 * Everything eases to its new value as the server says it has changed.
 */
class StoreScreen(private var view: StoreView) : Screen(Component.translatable("gui.guildmark.trade.store")) {
    /** Which Store it shows: its town. */
    val place: String get() = view.place

    /** One good's row, with what's shown of it eased toward the server's latest. */
    private inner class Row(var g: StoreGood) {
        val good = Good.byId(g.good)
        val stack: ItemStack = good?.item?.let(::ItemStack) ?: ItemStack.EMPTY

        /** The bars of what's in the Store and of the hauled part of it, as shown last tick and now. */
        var bar = g.bonded.toFloat()
        var barLast = bar
        var named = hauledOf(g).toFloat()
        var namedLast = named

        /** When the count carried and the count in the Store last changed, each button was pressed, and a parcel was last sealed. */
        var carriedAt = -100
        var bondedAt = -100
        var pressedBond = -100
        var pressedTake = -100
        var sealedAt = -100

        fun name(): Component = good?.let { Component.translatable(it.key) } ?: Component.literal(g.good)
        fun many(): Component = good?.let { Component.translatable(it.key + ".many") } ?: Component.literal(g.good)

        /** The parcel lines it shows under it: three at most, and one more saying how many more there are. */
        fun lines(): Int = min(g.parcels.size, PARCELS_SHOWN) + if (g.parcels.size > PARCELS_SHOWN) 1 else 0

        fun height(): Int = ROW + lines() * LINE
    }

    private fun hauledOf(g: StoreGood): Int = g.parcels.sumOf { it.units }

    /** A button of the Account or a tab: what it does, where it is, what it says, whether it can be pressed and why not. */
    private class Desk(val action: Int, val x: Int, val y: Int, val w: Int, val label: Component, val ok: Boolean, val tip: Component?)

    private var rows: List<Row> = view.goods.map { Row(it) }
    private var desk: List<Desk> = listOf()
    private var ticks = 0

    /** The server closed the screen: nothing to tell it on the way out. */
    private var shutHere = false

    /** The tab shown, the rule under the tabs as it slides to it (last tick and now), and when the tab was last changed. */
    private var tab = lastTab
    private var rule = tab.toFloat()
    private var ruleLast = rule
    private var tabAt = 0

    /** The purse and the days paid, as shown last tick and now, and when each last changed. */
    private var purse = view.marks.toFloat()
    private var purseLast = purse
    private var purseAt = -100
    private var paid = view.paidDays.toFloat()
    private var paidLast = paid
    private var paidAt = -100

    /** When the hoist last moved a crate, and which way; the last desk button pressed, and when. */
    private var hoistAt = -1000
    private var hoistDown = false
    private var pressedDesk = -1
    private var pressedDeskAt = -100

    private class Floater(val text: Component, val x: Int, val y: Int, val color: Int, val born: Int)
    private class Coin(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val born: Int)

    /** A seal pressed on a row's first parcel: which row, and when; [sounded] once it has struck. */
    private class Stamp(val row: Row, val born: Int) {
        var sounded = false
    }

    private val floaters = ArrayList<Floater>()
    private val coins = ArrayList<Coin>()
    private val stamps = ArrayList<Stamp>()

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
            ClientPacketDistributor.sendToServer(StoreClosePayload)
        }
    }

    // ---- From the server ----

    /** The Store as it stands now: everything that changed eases to its new place. */
    fun update(next: StoreView) {
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
        }
        if (next.paidDays > before.paidDays) {
            paidAt = ticks
            // The week's coins fly from the purse to the days they pay for
            if (gained < 0) repeat(min(6, -gained + 1)) { i ->
                coins.add(Coin(innerX + 8f, footerY() + 3f, stripX() + stripWidth() / 2f, stripY() + 6f, ticks + i * 2))
            }
        }
        var bonded = false
        var taken = false
        for (row in rows) {
            val g = next.goods.firstOrNull { it.good == row.g.good } ?: continue
            val old = before.goods.firstOrNull { it.good == g.good }
            row.g = g
            if (old == null) continue
            if (g.bonded != old.bonded) {
                row.bondedAt = ticks
                val n = g.bonded - old.bonded
                if (n > 0) bonded = true else taken = true
                rowTop(row)?.let { y -> floaters.add(Floater(Component.literal(if (n > 0) "+$n" else "$n"), textX + 4, y + 10, if (n > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, ticks)) }
            }
            // A parcel more in the Store is pressed with the owner's seal
            if (hauledOf(g) > hauledOf(old)) {
                row.sealedAt = ticks
                stamps.add(Stamp(row, ticks))
            }
            if (g.carried != old.carried) {
                row.carriedAt = ticks
                rowTop(row)?.let { y ->
                    val n = g.carried - old.carried
                    floaters.add(Floater(Component.literal(if (n > 0) "+$n" else "$n"), innerX + 4, y - 2, if (n > 0) Ui.GREEN else Ui.TEXT_SOFT, ticks))
                }
            }
        }
        if (bonded || taken) {
            hoistAt = ticks
            hoistDown = taken && !bonded
        }
        scroll = scroll.coerceIn(0f, maxScroll())
        layDesk()
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
        if (ticks % HEARTBEAT == 0) ClientPacketDistributor.sendToServer(StoreRefreshPayload)
        purseLast = purse
        purse += (view.marks - purse) * 0.25f
        if (abs(view.marks - purse) < 0.05f) purse = view.marks.toFloat()
        paidLast = paid
        paid += (view.paidDays - paid) * 0.18f
        if (abs(view.paidDays - paid) < 0.02f) paid = view.paidDays.toFloat()
        ruleLast = rule
        rule += (tab - rule) * 0.35f
        if (abs(tab - rule) < 0.01f) rule = tab.toFloat()
        for (row in rows) {
            row.barLast = row.bar
            row.bar += (row.g.bonded - row.bar) * 0.22f
            if (abs(row.g.bonded - row.bar) < 0.02f) row.bar = row.g.bonded.toFloat()
            row.namedLast = row.named
            val named = hauledOf(row.g).toFloat()
            row.named += (named - row.named) * 0.22f
            if (abs(named - row.named) < 0.02f) row.named = named
        }
        for (s in stamps) if (!s.sounded && ticks - s.born >= STAMP_STRIKE) {
            s.sounded = true
            Ui.play(TradeSounds.get("trade.seal_press"), 1f, 0.5f)
        }
        scrollLast = scrollShown
        scrollShown += (scroll - scrollShown) * 0.5f
        floaters.removeIf { ticks - it.born > FLOAT }
        coins.removeIf { ticks - it.born > FLIGHT }
        stamps.removeIf { ticks - it.born > STAMP }
    }

    // ---- Layout ----

    private fun contentHeight(): Int = when (tab) {
        BONDED -> rows.sumOf { it.height() + GAP }
        WORD -> view.towns.size * (TOWN + GAP)
        else -> 0
    }

    private fun maxScroll(): Float = max(0, contentHeight() - (listBottom - listTop)).toFloat()

    private fun footerY(): Int = panelY + panelHeight - FOOTER + 8

    /** The four weeks of days on the Account. */
    private fun stripX(): Int = innerX
    private fun stripY(): Int = listTop + 20
    private fun stripWidth(): Int = innerWidth

    private fun tabX(i: Int): Int = innerX + innerWidth * i / TABS
    private fun tabWidth(): Int = innerWidth / TABS

    /** Where [row]'s top is on screen now, or null if it's scrolled out of the list. */
    private fun rowTop(row: Row): Int? {
        var y = listTop - scrollShown.roundToInt()
        for (r in rows) {
            if (r === row) return if (y + r.height() <= listTop || y >= listBottom) null else y
            y += r.height() + GAP
        }
        return null
    }

    private fun buttonWidth(): Int = Mth.clamp(innerWidth / 4, 64, 84)

    /** The row and button ([Pair.second]: true for bonding) under the cursor. */
    private fun buttonAt(mx: Double, my: Double): Pair<Row, Boolean>? {
        if (tab != BONDED || my < listTop || my >= listBottom) return null
        val bx = innerX + innerWidth - buttonWidth()
        if (mx < bx || mx >= bx + buttonWidth()) return null
        var y = listTop - scrollShown.roundToInt()
        for (r in rows) {
            if (my >= y + 1 && my < y + 14) return r to true
            if (my >= y + 15 && my < y + 28) return r to false
            y += r.height() + GAP
        }
        return null
    }

    /** The row whose good is under the cursor. */
    private fun rowAt(mx: Double, my: Double): Row? {
        if (tab != BONDED || my < listTop || my >= listBottom || mx < innerX || mx >= innerX + innerWidth - buttonWidth() - 4) return null
        var y = listTop - scrollShown.roundToInt()
        for (r in rows) {
            if (my >= y && my < y + r.height()) return r
            y += r.height() + GAP
        }
        return null
    }

    private fun tabAt(mx: Double, my: Double): Int? {
        val y = panelY + TABS_TOP
        if (my < y || my >= y + TAB_H || mx < innerX || mx >= innerX + innerWidth) return null
        return ((mx - innerX) / (innerWidth.toDouble() / TABS)).toInt().coerceIn(0, TABS - 1)
    }

    /** The Account's button (a week of the clerk's wage), and the Wagon tab's ([layWagon]). */
    private fun layDesk() {
        val out = ArrayList<Desk>()
        if (tab == WAGON) layWagon(out)
        if (tab == ACCOUNT) {
            val why = view.payRefusal.takeIf { it.isNotEmpty() }?.let { Component.translatable(it, view.place, view.wage) }
            out.add(Desk(StoreDesk.PAY, innerX, stripY() + 22, min(innerWidth, 170), Component.translatable("gui.guildmark.trade.store.account.pay", marks(view.wage)), why == null, why))
        }
        desk = out
    }

    private fun deskAt(mx: Double, my: Double): Desk? = desk.firstOrNull { mx >= it.x && mx < it.x + it.w && my >= it.y && my < it.y + 13 }

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
                layDesk()
            }
            return true
        }
        deskAt(event.x(), event.y())?.let { d ->
            if (!d.ok) return true
            Ui.select()
            pressedDesk = d.action
            pressedDeskAt = ticks
            ClientPacketDistributor.sendToServer(StoreActionPayload(d.action, "", 0))
            return true
        }
        val (row, bonding) = buttonAt(event.x(), event.y()) ?: return super.mouseClicked(event, doubleClick)
        if (!(if (bonding) canBond(row.g) else canTake(row.g))) return true
        Ui.select()
        if (bonding) row.pressedBond = ticks else row.pressedTake = ticks
        ClientPacketDistributor.sendToServer(StoreActionPayload(if (bonding) StoreDesk.BOND else StoreDesk.TAKE, row.g.good, if (event.hasShiftDown()) view.most else StoreDesk.BATCH))
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0 || maxScroll() <= 0f) return false
        scroll = (scroll - scrollY.toFloat() * 22f).coerceIn(0f, maxScroll())
        return true
    }

    private fun canBond(g: StoreGood): Boolean = view.refusal.isEmpty() && g.room > 0 && g.carried > 0
    private fun canTake(g: StoreGood): Boolean = g.bonded > 0

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // The Store stays in view: a shade behind the panel only
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
        drawHoist(graphics, rise, t)
        drawSign(graphics, rise, t)
        drawTabs(graphics, rise, mouseX, mouseY, t, a)
        val k = rise * ease(Mth.clamp((t - tabAt) / 6f, 0f, 1f))
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)
        when (tab) {
            BONDED -> drawBonded(graphics, k, mouseX, mouseY, t, a)
            WORD -> drawWord(graphics, k, t, a)
            WAGON -> drawWagon(graphics, k, mouseX, mouseY, t, a)
            GUARDS -> drawGuards(graphics, k, t)
            else -> drawAccount(graphics, k, mouseX, mouseY, t, a)
        }
        pose.popMatrix()
        drawFooter(graphics, rise, t, a)
        pose.popMatrix()
        drawCoins(graphics, t)
        drawFloaters(graphics, t)
        if (rise >= 1f) {
            deskAt(mouseX.toDouble(), mouseY.toDouble())?.tip?.let { drawTip(graphics, listOf(it to Ui.TEXT), mouseX, mouseY) }
                ?: buttonAt(mouseX.toDouble(), mouseY.toDouble())?.let { (row, bonding) -> drawTip(graphics, buttonTip(row, bonding), mouseX, mouseY) }
                ?: rowAt(mouseX.toDouble(), mouseY.toDouble())?.let { drawTip(graphics, goodTip(it), mouseX, mouseY) }
        }
    }

    /** The owner's colour. */
    private fun tint(): Int = Dye.entries[view.color.coerceIn(0, Dye.entries.size - 1)].rgb

    /**
     * The Store's name board over the panel, hung on two chains and swinging a little as it settles after the screen opens, the owner's
     * name under the Store's; the owner's pennant flies from a pole at its right, rippling.
     */
    private fun drawSign(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val w = min(panelWidth - 90, max(150, font.width(Ui.heading(Component.translatable("gui.guildmark.trade.store.board"))) + 40))
        val cx = panelX + panelWidth / 2
        val top = panelY - SIGN_ROOM
        val pose = graphics.pose()
        // The chains, from the panel's top rail
        val swing = sin(t * 0.21f) * 0.05f * max(0f, 1f - t / 50f) + sin(t * 0.045f) * 0.006f
        pose.pushMatrix()
        pose.translate(cx.toFloat(), top.toFloat())
        pose.rotate(swing)
        val chain = ARGB.color(k, 0x4A4650)
        for (x in listOf(-w / 2 + 10, w / 2 - 10)) for (y in 0 until CHAIN step 2) graphics.fill(x, y, x + 1, y + 1, chain)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, -w / 2, CHAIN, w, SIGN_H, ARGB.white(k))
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.trade.store.board"), 0, CHAIN + 4, fade(Ui.GOLD, k))
        val owner = Component.translatable("gui.guildmark.trade.store.owner", view.owner, view.place)
        graphics.centeredText(font, owner, 0, CHAIN + 16, fade(Ui.TEXT_SOFT, k))
        pose.popMatrix()
        // The pole and its pennant, at the board's right
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
     * The hoist at the board's left: a beam out from the panel's corner with a rope and a crate on it, which rises up to the beam when goods
     * are bonded and comes down to the panel when they are taken back, easing in and out of the climb and swinging as it stops.
     */
    private fun drawHoist(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val bx = panelX + 18
        val beamY = panelY - SIGN_ROOM - 2
        val beam = ARGB.color(k, 0x5A3E26)
        graphics.fill(bx - 6, beamY, bx + 22, beamY + 3, beam)
        graphics.fill(bx - 6, beamY + 3, bx - 3, panelY, beam)
        graphics.fill(bx + 14, beamY + 3, bx + 16, beamY + 5, ARGB.color(k, 0x2A1A10))
        val (y, crate, sway) = hook(t - hoistAt, panelY - 12f, beamY + 7f)
        val rope = ARGB.color(k, 0xB49A6A)
        graphics.fill(bx + 15, beamY + 5, bx + 16, y.roundToInt(), rope)
        graphics.fill(bx + 14, y.roundToInt(), bx + 17, y.roundToInt() + 1, ARGB.color(k, 0x8A8690))
        if (crate <= 0f) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(bx + 15.5f + sway, y + 1f)
        pose.rotate(sway * 0.04f)
        // An item can't be faded: the crate grows as it is hooked on and shrinks as it is swung off
        val s = 0.6f * ease(crate)
        pose.scale(s, s)
        graphics.item(CRATE, -8, 0)
        pose.popMatrix()
    }

    /**
     * Where the hook is [since] ticks after the hoist last moved, between [low] (at rest, by the board) and [high] (the beam), whether a crate
     * hangs on it (0 to 1, as it is hooked on and swung off) and how far it swings. Bonding, a crate is hooked on below, climbs, is
     * swung into the loft at the top and the empty hook comes back down; taking back, the empty hook goes up, a crate is hooked on at the
     * top and lowered, and set down. The hook is never anywhere it could not have got to.
     */
    private fun hook(since: Float, low: Float, high: Float): Triple<Float, Float, Float> {
        fun e(p: Float): Float = Mth.clamp(p, 0f, 1f).let { it * it * (3f - 2f * it) }
        val swing = if (since in 0f..(HOIST * 2 + 16f)) sin(since * 0.35f) * 1.6f * max(0f, 1f - since / (HOIST * 2 + 16f)) else 0f
        if (since < 0f) return Triple(low, 0f, 0f)
        return if (!hoistDown) when {
            since < 4f -> Triple(low, since / 4f, swing)
            since < 4f + HOIST -> Triple(Mth.lerp(e((since - 4f) / HOIST), low, high), 1f, swing)
            since < 10f + HOIST -> Triple(high, 1f - (since - 4f - HOIST) / 6f, swing)
            since < 10f + HOIST * 2 -> Triple(Mth.lerp(e((since - 10f - HOIST) / HOIST), high, low), 0f, swing)
            else -> Triple(low, 0f, 0f)
        } else when {
            since < HOIST -> Triple(Mth.lerp(e(since / HOIST), low, high), 0f, swing)
            since < HOIST + 4f -> Triple(high, (since - HOIST) / 4f, swing)
            since < HOIST * 2 + 4f -> Triple(Mth.lerp(e((since - HOIST - 4f) / HOIST), high, low), 1f, swing)
            since < HOIST * 2 + 10f -> Triple(low, 1f - (since - HOIST * 2 - 4f) / 6f, swing)
            else -> Triple(low, 0f, 0f)
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
            // An owing wage marks the Account; goods the Store would take mark the Bonded tab
            val mark = when (i) {
                ACCOUNT -> view.paidDays <= 0 && view.open
                BONDED -> view.refusal.isEmpty() && view.goods.any { canBond(it) }
                else -> false
            }
            if (mark) {
                val pulse = 0.55f + 0.45f * sin(t * 0.15f)
                graphics.fill(x + w / 2 + font.width(label) / 2 + 2, y + 2, x + w / 2 + font.width(label) / 2 + 4, y + 4, ARGB.color(pulse * k, if (i == ACCOUNT) Ui.RED else Ui.GOLD))
            }
        }
        Ui.divider(graphics, innerX, y + TAB_H, innerWidth, gem = false)
        // The rule slides under the tab chosen
        val shown = Mth.lerp(a, ruleLast, rule)
        val rx = innerX + (innerWidth * shown / TABS).roundToInt()
        graphics.fill(rx + 6, y + TAB_H - 1, rx + tabWidth() - 6, y + TAB_H + 1, fade(Ui.GOLD, k))
    }

    // ---- Bonded goods ----

    private fun drawBonded(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        val units = view.goods.sumOf { it.bonded }
        val hauled = view.goods.sumOf { hauledOf(it) }
        val kinds = view.goods.count { it.bonded > 0 }
        val summary = if (units == 0) Component.translatable("gui.guildmark.trade.store.bonded.empty")
        else Component.translatable("gui.guildmark.trade.store.bonded.summary", units, kinds, view.kinds, hauled)
        graphics.text(font, summary, innerX + 2, y0, fade(Ui.TEXT, k), false)
        val why = view.refusal.takeIf { it.isNotEmpty() }
        graphics.text(font, if (why != null) Component.translatable(why, view.place, view.wage) else Component.translatable("gui.guildmark.trade.store.bonded.note"),
            innerX + 2, y0 + 11, fade(if (why != null) Ui.RED else Ui.TEXT_FAINT, k), false)
        if (rows.isEmpty()) {
            graphics.centeredText(font, Component.translatable("gui.guildmark.trade.store.bonded.nothing"), innerX + innerWidth / 2, listTop + 14, fade(Ui.TEXT_FAINT, k))
            return
        }
        val scrolled = Mth.lerp(a, scrollLast, scrollShown)
        graphics.enableScissor(innerX - 2, listTop, innerX + innerWidth + 2, listBottom)
        var y = listTop - scrolled.roundToInt()
        val shift = minecraft.hasShiftDown()
        for ((i, row) in rows.withIndex()) {
            val h = row.height()
            if (y + h > listTop && y < listBottom) {
                val rk = k * ease(Mth.clamp((t - tabAt - 2f - i * 1.5f) / 6f, 0f, 1f))
                if (rk > 0f) drawRow(graphics, row, y, rk, mouseX, mouseY, t, a, shift)
            }
            y += h + GAP
        }
        graphics.disableScissor()
        drawScrollbar(graphics, scrolled)
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

    private fun drawRow(graphics: GuiGraphicsExtractor, row: Row, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float, shift: Boolean) {
        val g = row.g
        val pose = graphics.pose()
        pose.pushMatrix()
        // A jolt as goods go in or come out
        val jolt = bump(t - row.bondedAt, 8f)
        pose.translate(0f, -jolt * 1.5f)
        val hovered = mouseY >= y && mouseY < y + row.height() && mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= listTop && mouseY < listBottom
        if (hovered) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, innerX - 2, y, innerWidth + 4, row.height(), ARGB.white(0.4f * k))

        // The good, with how many the player carries that the Store would take
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, innerX, y + 2, 18, 18, ARGB.white(k))
        if (k > 0.3f) {
            graphics.item(row.stack, innerX + 1, y + 3)
            if (g.carried > 0) {
                val pop = 1f + 0.5f * bump(t - row.carriedAt, 8f)
                val count = g.carried.toString()
                pose.pushMatrix()
                pose.translate(innerX + 18f, y + 20f)
                pose.scale(pop, pop)
                graphics.text(font, count, -font.width(count), -8, if (t - row.carriedAt < 10f) Ui.GOLD_BRIGHT else Ui.TEXT, true)
                pose.popMatrix()
            }
        }

        // Its name, and what's in the Store of it
        val width = innerWidth - 22 - buttonWidth() - 6
        graphics.text(font, row.name(), textX, y + 3, fade(Ui.TEXT, k), false)
        val inStore = Component.translatable("gui.guildmark.trade.store.bonded.in_store", g.bonded)
        val pop = 1f + 0.35f * bump(t - row.bondedAt, 10f)
        pose.pushMatrix()
        pose.translate((textX + width - font.width(inStore) * pop), y + 3f)
        pose.scale(pop, pop)
        graphics.text(font, inStore, 0, 0, fade(if (g.bonded == 0) Ui.TEXT_FAINT else if (bump(t - row.bondedAt, 14f) > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, k), false)
        pose.popMatrix()

        // The bar: the hauled goods in gold, the plain after them in a duller brown
        drawBar(graphics, row, textX, y + 15, width - 34, k, t, a)
        val plain = g.bonded - hauledOf(g)
        if (plain > 0) {
            val text = Component.translatable("gui.guildmark.trade.store.bonded.plain", plain)
            graphics.text(font, text, textX + width - font.width(text), y + 14, fade(Ui.TEXT_FAINT, k), false)
        }

        // The buttons
        val bx = innerX + innerWidth - buttonWidth()
        drawButton(graphics, row, true, bx, y + 1, k, mouseX, mouseY, t, shift)
        drawButton(graphics, row, false, bx, y + 15, k, mouseX, mouseY, t, shift)

        // Each hauled parcel: the town it came from and its days, thirty notches running out
        var ly = y + ROW - 2
        for ((i, p) in g.parcels.withIndex()) {
            if (i >= PARCELS_SHOWN) {
                graphics.text(font, Component.translatable("gui.guildmark.trade.store.bonded.more", g.parcels.size - PARCELS_SHOWN), textX + 8, ly, fade(Ui.TEXT_FAINT, k), false)
                break
            }
            drawParcel(graphics, row, i, p.units, p.days, p.from, textX, ly, width + buttonWidth() + 2, k, t)
            ly += LINE
        }
        pose.popMatrix()
    }

    /** One parcel's line: a seal, its units and town, and its days as notches (the last few blinking). */
    private fun drawParcel(graphics: GuiGraphicsExtractor, row: Row, i: Int, units: Int, days: Int, from: String, x: Int, y: Int, w: Int, k: Float, t: Float) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SEAL_TINT, x, y, 8, 8, ARGB.color(k, tint()))
        val text = Component.translatable("gui.guildmark.trade.store.bonded.parcel", units, from.ifEmpty { "?" })
        graphics.text(font, text, x + 11, y, fade(Ui.TEXT_SOFT, k), false)
        val notches = Ledger.HELD_DAYS.toInt()
        val nx = x + w - notches * 2 - 34
        for (d in 0 until notches) {
            val lit = d < days
            val last = lit && days <= 3 && d == days - 1
            val blink = if (last) 0.45f + 0.55f * abs(sin(t * 0.12f)) else 1f
            val color = if (lit) (if (days <= 3) Ui.RED else Ui.GOLD) else 0xFF3A342C.toInt()
            val h = if (d % 7 == 6) 7 else 5
            graphics.fill(nx + d * 2, y + 7 - h, nx + d * 2 + 1, y + 7, ARGB.multiplyAlpha(color, k * blink))
        }
        val left = Component.translatable(if (days == 1) "gui.guildmark.trade.store.bonded.day" else "gui.guildmark.trade.store.bonded.days", days)
        graphics.text(font, left, x + w - font.width(left), y, fade(if (days <= 3) Ui.RED else Ui.TEXT_FAINT, k), false)
        // A parcel just bonded is pressed with the owner's seal: it comes down large and strikes
        if (i == 0) for (s in stamps) if (s.row === row) {
            val age = t - s.born
            if (age < 0f || age > STAMP) continue
            val down = Mth.clamp(age / STAMP_STRIKE, 0f, 1f)
            val scale = 1f + 2.2f * (1f - down * down)
            val alpha = k * (if (age < STAMP_STRIKE) down else 1f - Mth.clamp((age - STAMP_STRIKE - 8f) / 10f, 0f, 1f))
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x + 4f, y + 4f)
            pose.scale(scale, scale)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SEAL_TINT, -4, -4, 8, 8, ARGB.color(alpha, tint()))
            pose.popMatrix()
            if (age >= STAMP_STRIKE && age < STAMP_STRIKE + 6f) {
                val ring = (age - STAMP_STRIKE) * 1.5f
                val ra = ARGB.color((1f - (age - STAMP_STRIKE) / 6f) * 0.6f * k, 0xF2E6B0)
                graphics.fill((x + 4 - 4 - ring).roundToInt(), y + 8, (x + 4 + 4 + ring).roundToInt(), y + 9, ra)
            }
        }
    }

    /** What's in the Store of a good against [BAR_UNITS]: its hauled part gold, its plain part brown, easing when it changes. */
    private fun drawBar(graphics: GuiGraphicsExtractor, row: Row, x: Int, y: Int, w: Int, k: Float, t: Float, a: Float) {
        val inner = w - 2
        fun at(v: Float): Int = x + 1 + (inner * Mth.clamp(v / BAR_UNITS, 0f, 1f)).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.INSET, x, y, w, 6, ARGB.white(k))
        val all = Mth.lerp(a, row.barLast, row.bar)
        val named = min(all, Mth.lerp(a, row.namedLast, row.named))
        val end = at(all)
        val gold = at(named)
        if (gold > x + 1) graphics.fillGradient(x + 1, y + 1, gold, y + 5, fade(0xFFE4BF72.toInt(), k), fade(0xFF96713A.toInt(), k))
        if (end > gold) graphics.fillGradient(gold, y + 1, end, y + 5, fade(0xFF8A7458.toInt(), k), fade(0xFF5A4A38.toInt(), k))
        val run = (t % 90f) / 30f
        if (run < 1f && end > x + 4) {
            val gx = x + 1 + ((end - x) * run).toInt()
            graphics.fill(gx, y + 1, min(end, gx + 3), y + 5, ARGB.color((60 * k).toInt(), 0xFFFFFF))
        }
    }

    /** A row's button: bond ([bonding]) or take back, lit under the cursor, pressed down a moment, dimmed when it can't. */
    private fun drawButton(graphics: GuiGraphicsExtractor, row: Row, bonding: Boolean, x: Int, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float, shift: Boolean) {
        val g = row.g
        val ok = if (bonding) canBond(g) else canTake(g)
        val w = buttonWidth()
        val lit = ok && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 13 && mouseY >= listTop && mouseY < listBottom
        val down = t - (if (bonding) row.pressedBond else row.pressedTake) < 3f
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(ok, lit), x, y + if (down) 1 else 0, w, 13, ARGB.white(k))
        val n = if (bonding) min(g.carried, g.room) else g.bonded
        val label = Component.translatable(
            if (bonding) (if (shift) "gui.guildmark.trade.store.bond_all" else "gui.guildmark.trade.store.bond") else (if (shift) "gui.guildmark.trade.store.take_all" else "gui.guildmark.trade.store.take"),
            if (shift) n else min(n, StoreDesk.BATCH),
        )
        graphics.centeredText(font, label, x + w / 2, y + 3 + if (down) 1 else 0, fade(if (!ok) Ui.TEXT_FAINT else if (lit) Ui.GOLD_BRIGHT else Ui.TEXT, k))
    }

    // ---- Word ----

    private fun drawWord(graphics: GuiGraphicsExtractor, k: Float, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.word.head"), innerX + 2, y0, fade(Ui.TEXT, k), false)
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.word.note"), innerX + 2, y0 + 11, fade(Ui.TEXT_FAINT, k), false)
        if (!view.wordOn || view.towns.isEmpty()) {
            val key = if (!view.wordOn) "gui.guildmark.trade.store.word.off" else "gui.guildmark.trade.store.word.none"
            graphics.centeredText(font, Component.translatable(key), innerX + innerWidth / 2, listTop + 14, fade(Ui.TEXT_FAINT, k))
            return
        }
        val pen = GuiPen(graphics, font)
        val scrolled = Mth.lerp(a, scrollLast, scrollShown)
        graphics.enableScissor(innerX - 2, listTop, innerX + innerWidth + 2, listBottom)
        var y = listTop - scrolled.roundToInt()
        for ((i, town) in view.towns.withIndex()) {
            if (y + TOWN > listTop && y < listBottom) {
                val rk = k * ease(Mth.clamp((t - tabAt - 2f - i * 2f) / 6f, 0f, 1f))
                if (rk > 0f) drawTown(graphics, pen, town, y, rk, t)
            }
            y += TOWN + GAP
        }
        graphics.disableScissor()
        drawScrollbar(graphics, scrolled)
    }

    /** One town's card: its name and how far, what it is short of (red rings) and has too much of (green), and its tickets. */
    private fun drawTown(graphics: GuiGraphicsExtractor, pen: Pen, town: StoreTown, y: Int, k: Float, t: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - k) * 8f, 0f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.INSET, innerX, y, innerWidth, TOWN, ARGB.white(k * 0.8f))
        val tag = Component.translatable(if (town.own) "gui.guildmark.trade.store.word.here" else "gui.guildmark.trade.store.word.stall")
        graphics.text(font, town.name, innerX + 6, y + 4, fade(if (town.own) Ui.GOLD_BRIGHT else Ui.GOLD, k), false)
        val sub = if (town.own) tag else Component.translatable("gui.guildmark.trade.store.word.away", tag, town.distance)
        graphics.text(font, sub, innerX + innerWidth - 6 - font.width(sub), y + 4, fade(Ui.TEXT_FAINT, k), false)
        // What it wants and what it has too much of, the chips bobbing a little in turn
        var x = innerX + 6
        val cy = y + 15
        if (town.shorts.isEmpty() && town.gluts.isEmpty()) {
            graphics.text(font, Component.translatable("gui.guildmark.trade.store.word.calm"), x, cy + 4, fade(Ui.TEXT_SOFT, k), false)
        } else {
            for ((list, short) in listOf(town.shorts to true, town.gluts to false)) {
                if (list.isEmpty()) continue
                val label = Component.translatable(if (short) "gui.guildmark.trade.store.word.short" else "gui.guildmark.trade.store.word.glut")
                graphics.text(font, label, x, cy + 4, fade(if (short) Ui.RED else Ui.GREEN, k), false)
                x += font.width(label) + 3
                for ((j, id) in list.withIndex()) {
                    val bob = (sin(t * 0.08f - j * 0.9f) * 0.8f).roundToInt()
                    if (hasChip(id)) pen.chip(id, short, x, cy + bob, ARGB.white(k)) else pen.good(id, x, cy + bob)
                    x += 17
                }
                x += 6
            }
        }
        // Its tickets
        val tickets = when (town.tickets) {
            0 -> Component.translatable("gui.guildmark.trade.store.word.no_tickets")
            1 -> Component.translatable("gui.guildmark.trade.store.word.ticket")
            else -> Component.translatable("gui.guildmark.trade.store.word.tickets", town.tickets)
        }
        val best = Good.byId(town.bestGood)
        val line = if (best != null && town.bestUnits > 0)
            Component.translatable("gui.guildmark.trade.store.word.best", tickets, town.bestUnits, Component.translatable(best.key + ".many"), marks(town.bestPay))
        else tickets
        graphics.text(font, line, innerX + 6, y + TOWN - 11, fade(Ui.TEXT_SOFT, k), false)
        pose.popMatrix()
    }

    // ---- Wagon ----

    /** The room the Wagon tab's words have beside the pointer. */
    private fun wagonTextWidth(): Int = innerWidth - 44

    /** What the Wagon tab says above its buttons, each line with its colour: the wagon's state, its horse and its hold, or the Bill's offer. */
    private fun wagonLines(): List<Pair<Component, Int>> {
        val w = view.wagon ?: return listOf()
        val out = ArrayList<Pair<Component, Int>>()
        if (w.state == StoreWagon.NONE) {
            out.add(Component.translatable("gui.guildmark.trade.store.wagon.state.none") to Ui.TEXT)
            out.add(Component.translatable("gui.guildmark.trade.store.wagon.offer", WAGON_SLOTS) to Ui.TEXT_SOFT)
            if (!w.factor) out.add(Component.translatable("gui.guildmark.trade.store.wagon.factor_only") to Ui.RED)
            return out
        }
        val state = when (w.state) {
            StoreWagon.COMING -> if (w.place.isEmpty()) Component.translatable("gui.guildmark.trade.store.wagon.state.coming_any", duration(w.dueSecs))
            else Component.translatable("gui.guildmark.trade.store.wagon.state.coming", w.place, duration(w.dueSecs))
            StoreWagon.HERE -> Component.translatable("gui.guildmark.trade.store.wagon.state.here")
            StoreWagon.YARD -> if (w.place.isEmpty()) Component.translatable("gui.guildmark.trade.store.wagon.state.yard_any")
            else Component.translatable("gui.guildmark.trade.store.wagon.state.yard", w.place)
            StoreWagon.PARKED -> if (w.far >= 0) Component.translatable("gui.guildmark.trade.store.wagon.state.parked", w.x, w.y, w.z)
            else Component.translatable("gui.guildmark.trade.store.wagon.state.away")
            else -> Component.translatable("gui.guildmark.trade.store.wagon.state.led")
        }
        out.add(state to if (w.state == StoreWagon.HERE) Ui.GREEN else Ui.GOLD)
        out.add(
            if (w.horse) Component.translatable("gui.guildmark.trade.store.wagon.horse", w.horseName) to Ui.TEXT_SOFT
            else Component.translatable("gui.guildmark.trade.store.wagon.no_horse") to Ui.RED,
        )
        out.add(Component.translatable("gui.guildmark.trade.store.wagon.hold", w.used, WAGON_SLOTS) to Ui.TEXT_SOFT)
        return out
    }

    /** How long until the wagon is in its yard. */
    private fun duration(secs: Int): Component =
        if (secs >= 60) Component.translatable("gui.guildmark.trade.store.wagon.minutes", (secs + 30) / 60) else Component.translatable("gui.guildmark.trade.store.wagon.moment")

    /** Where the Wagon tab's buttons start: under its words, and under the pointer. */
    private fun wagonButtonsY(): Int {
        var y = listTop + 4
        for ((text, _) in wagonLines()) y += font.split(text, wagonTextWidth()).size * 10
        return max(y + 4, listTop + 42)
    }

    /** The Wagon tab's buttons: the Bill with no wagon; else a horse (with none), the wagon called here, a new copy of the Bill. */
    private fun layWagon(out: MutableList<Desk>) {
        val w = view.wagon ?: return
        val y = wagonButtonsY()
        val purse = view.marks
        val off = Component.translatable("gui.guildmark.trade.store.wagon.why.off")
        if (w.state == StoreWagon.NONE) {
            val why = when {
                !w.on -> off
                !w.factor -> Component.translatable("gui.guildmark.trade.store.wagon.why.factor")
                purse < w.billPrice -> Component.translatable("gui.guildmark.trade.store.wagon.why.short", w.billPrice)
                else -> null
            }
            val label = Component.translatable("gui.guildmark.trade.store.wagon.buy_bill", marks(w.billPrice))
            out.add(Desk(StoreDesk.WAGON_BILL, innerX, y, min(innerWidth, 200), label, why == null, why ?: Component.translatable("gui.guildmark.trade.store.wagon.tip.bill")))
            return
        }
        val half = (innerWidth - 4) / 2
        var n = 0
        fun put(action: Int, label: Component, why: Component?, tip: Component) {
            out.add(Desk(action, innerX + (n % 2) * (half + 4), y + (n / 2) * 16, half, label, why == null, why ?: tip))
            n++
        }
        if (!w.horse) {
            val why = when {
                !w.on -> off
                purse < w.horsePrice -> Component.translatable("gui.guildmark.trade.store.wagon.why.short", w.horsePrice)
                else -> null
            }
            put(StoreDesk.WAGON_HORSE, Component.translatable("gui.guildmark.trade.store.wagon.buy_horse", marks(w.horsePrice)), why, Component.translatable("gui.guildmark.trade.store.wagon.tip.horse"))
        }
        put(
            StoreDesk.WAGON_RECALL,
            if (w.fee == 0) Component.translatable("gui.guildmark.trade.store.wagon.recall_free") else Component.translatable("gui.guildmark.trade.store.wagon.recall", marks(w.fee)),
            if (w.state == StoreWagon.HERE) Component.translatable("gui.guildmark.trade.store.wagon.why.here") else null,
            Component.translatable("gui.guildmark.trade.store.wagon.tip.recall", marks(WagonBill.RECALL_FEE)),
        )
        put(
            StoreDesk.WAGON_COPY, Component.translatable("gui.guildmark.trade.store.wagon.copy"),
            if (w.hasBill) Component.translatable("gui.guildmark.trade.store.wagon.why.has_bill") else null, Component.translatable("gui.guildmark.trade.store.wagon.tip.copy"),
        )
    }

    /**
     * The Carrier's Wagon (build part D3): where it is, its horse and its hold, the Bill and the horse for sale and the wagon called to this yard;
     * a pointer turning to the wagon (or, while it is in a yard, to this one) from where the player stands and faces; the yard under them.
     */
    private fun drawWagon(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.wagon.head"), innerX + 2, y0, fade(Ui.TEXT, k), false)
        if (!view.wagonOn) {
            graphics.text(font, Component.translatable("gui.guildmark.trade.store.wagon.off"), innerX + 2, y0 + 11, fade(Ui.TEXT_FAINT, k), false)
        }
        val w = view.wagon
        val yard = view.yard
        val player = minecraft.player ?: return
        // The wagon's state, its horse and its hold, each line easing in after the last
        var y = listTop + 4
        for ((i, line) in wagonLines().withIndex()) {
            val lk = k * ease(Mth.clamp((t - tabAt - 2f - i * 2f) / 6f, 0f, 1f))
            for (part in font.split(line.first, wagonTextWidth())) {
                graphics.text(font, part, innerX + 2, y, fade(line.second, lk), false)
                y += 10
            }
        }
        // The pointer: the bearing to the wagon out on the road in this land, else to the yard, against where the player looks
        val out = w != null && (w.state == StoreWagon.PARKED || w.state == StoreWagon.LED) && w.far >= 0
        val tx = if (out && w != null) w.x + 0.5 else yard?.let { it.x + 0.5 }
        val tz = if (out && w != null) w.z + 0.5 else yard?.let { it.z + 0.5 }
        if (tx != null && tz != null) {
            val px = Mth.lerp(a.toDouble(), player.xo, player.x)
            val pz = Mth.lerp(a.toDouble(), player.zo, player.z)
            val dx = tx - px
            val dz = tz - pz
            val far = hypot(dx, dz).roundToInt()
            val cx = innerX + innerWidth - 22
            val cy = listTop + 16
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SOCKET, cx - 14, cy - 14, 28, 28, ARGB.white(k))
            val bearing = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val turn = Math.toRadians((bearing - Mth.lerp(a, player.yRotO, player.yRot)).toDouble()).toFloat()
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(cx.toFloat(), cy.toFloat())
            pose.rotate(turn)
            for (i in 0 until 9) {
                val half = (i / 2).coerceAtMost(3)
                graphics.fill(-half, -9 + i, half + 1, -8 + i, fade(if (far < 4) Ui.GREEN else Ui.GOLD_BRIGHT, k))
            }
            graphics.fill(0, 0, 1, 7, fade(Ui.GOLD, k))
            pose.popMatrix()
            graphics.centeredText(font, Component.translatable("gui.guildmark.trade.store.wagon.blocks", far), cx, cy + 16, fade(Ui.TEXT_FAINT, k))
        }
        // Its buttons
        drawDesk(graphics, k, mouseX, mouseY, t)
        y = (desk.maxOfOrNull { it.y + 13 } ?: wagonButtonsY()) + 8
        // The yard a wagon called here comes to
        Ui.divider(graphics, innerX, y - 3, innerWidth, gem = false)
        y += 4
        if (yard == null) {
            graphics.text(font, Component.translatable("gui.guildmark.trade.store.wagon.none"), innerX + 2, y, fade(Ui.TEXT_FAINT, k), false)
            y += 12
        } else {
            for (part in font.split(Component.translatable("gui.guildmark.trade.store.wagon.yard"), innerWidth - 4)) {
                graphics.text(font, part, innerX + 2, y, fade(Ui.TEXT_SOFT, k), false)
                y += 10
            }
            val far = hypot(yard.x + 0.5 - player.x, yard.z + 0.5 - player.z).roundToInt()
            graphics.text(font, Component.translatable("gui.guildmark.trade.store.wagon.at", yard.x, yard.y, yard.z, far), innerX + 2, y, fade(Ui.TEXT_SOFT, k), false)
            y += 12
        }
        if (w != null && w.state != StoreWagon.NONE) {
            for (part in font.split(Component.translatable("gui.guildmark.trade.store.wagon.note"), innerWidth - 4)) {
                if (y + 10 > listBottom) break
                graphics.text(font, part, innerX + 2, y, fade(Ui.TEXT_FAINT, k), false)
                y += 10
            }
        }
    }

    /** The buttons laid on this tab ([desk]), lit under the cursor and pressed down a moment when clicked. */
    private fun drawDesk(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float) {
        for (d in desk) {
            val lit = d.ok && mouseX >= d.x && mouseX < d.x + d.w && mouseY >= d.y && mouseY < d.y + 13
            val down = pressedDesk == d.action && t - pressedDeskAt < 3f
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(d.ok, lit), d.x, d.y + if (down) 1 else 0, d.w, 13, ARGB.white(k))
            graphics.centeredText(font, d.label, d.x + d.w / 2, d.y + 3 + if (down) 1 else 0, fade(if (!d.ok) Ui.TEXT_FAINT else if (lit) Ui.GOLD_BRIGHT else Ui.TEXT, k))
        }
    }

    // ---- Guards ----

    private fun drawGuards(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.guards.head"), innerX + 2, y0, fade(Ui.TEXT, k), false)
        if (!view.guardsOn) graphics.text(font, Component.translatable("gui.guildmark.trade.store.guards.off"), innerX + 2, y0 + 11, fade(Ui.TEXT_FAINT, k), false)
        var y = listTop + 4
        val hired = if (view.guards == 0) Component.translatable("gui.guildmark.trade.store.guards.none")
        else Component.translatable("gui.guildmark.trade.store.guards.hired", view.guards)
        graphics.text(font, hired, innerX + 2, y, fade(Ui.TEXT_SOFT, k), false)
        // A shield for each, set down one after another
        for (i in 0 until min(view.guards, 8)) {
            val s = ease(Mth.clamp((t - tabAt - 4f - i * 3f) / 6f, 0f, 1f))
            if (s <= 0f) continue
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(innerX + 2f + i * 20f + 8f, y + 14f + 8f - (1f - s) * 6f)
            pose.scale(s, s)
            graphics.item(SHIELD, -8, -8)
            pose.popMatrix()
        }
        y += if (view.guards > 0) 36 else 14
        val wage = Component.translatable("gui.guildmark.trade.store.guards.wage", tenths(view.guardWage))
        graphics.text(font, wage, innerX + 2, y, fade(Ui.TEXT_FAINT, k), false)
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.guards.later"), innerX + 2, y + 12, fade(Ui.TEXT_FAINT, k), false)
    }

    // ---- Account ----

    private fun drawAccount(graphics: GuiGraphicsExtractor, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val y0 = panelY + TABS_TOP + TAB_H + 6
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.account.wage", marks(view.wage)), innerX + 2, y0, fade(Ui.TEXT, k), false)
        val days = view.paidDays
        val state = when {
            !view.open -> Component.translatable("gui.guildmark.trade.store.account.off")
            days > 1 -> Component.translatable("gui.guildmark.trade.store.account.paid", days)
            days == 1 -> Component.translatable("gui.guildmark.trade.store.account.paid_today")
            days == 0 -> Component.translatable("gui.guildmark.trade.store.account.owing_today")
            else -> Component.translatable("gui.guildmark.trade.store.account.owing", -days + 1)
        }
        val glow = bump(t - paidAt, 20f)
        graphics.text(font, state, innerX + 2, y0 + 11, fade(if (days <= 0 && view.open) Ui.RED else if (glow > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, k), false)

        // Four weeks of days, today first: the days paid for lit, filling as a week is paid, today ringed (struck through if owing)
        val sx = stripX()
        val sy = stripY()
        graphics.text(font, Component.translatable("gui.guildmark.trade.store.account.weeks"), sx, sy - 10, fade(Ui.TEXT_FAINT, k), false)
        val cells = view.ahead
        val cw = (stripWidth() - (cells / 7 - 1) * 4) / cells
        val shown = Mth.lerp(a, paidLast, paid)
        for (d in 0 until cells) {
            val x = sx + d * cw + (d / 7) * 4
            val fill = Mth.clamp(shown - d, 0f, 1f)
            graphics.fill(x, sy, x + cw - 1, sy + 12, fade(0xFF2A2620.toInt(), k))
            if (fill > 0f) {
                val top = sy + 12 - (12 * fill).roundToInt()
                graphics.fillGradient(x, top, x + cw - 1, sy + 12, fade(0xFFE4BF72.toInt(), k), fade(0xFF96713A.toInt(), k))
            }
            if (d == 0) {
                graphics.outline(x - 1, sy - 1, cw + 1, 14, fade(if (days <= 0 && view.open) Ui.RED else Ui.GOLD_BRIGHT, k))
                if (days <= 0 && view.open) graphics.fill(x, sy + 5, x + cw - 1, sy + 7, fade(Ui.RED, k * (0.6f + 0.4f * sin(t * 0.15f))))
            }
        }
        drawDesk(graphics, k, mouseX, mouseY, t)

        // The half rent: what the tables rented cost a week with the Store, and what they would without
        var y = stripY() + 46
        Ui.divider(graphics, innerX, y - 6, innerWidth, gem = false)
        val rent = when {
            !view.half -> Component.translatable("gui.guildmark.trade.store.account.half_off")
            view.tables == 0 -> Component.translatable("gui.guildmark.trade.store.account.no_tables")
            view.tables == 1 -> Component.translatable("gui.guildmark.trade.store.account.table", view.keepNow, view.keepFull)
            else -> Component.translatable("gui.guildmark.trade.store.account.tables", view.tables, view.keepNow, view.keepFull)
        }
        for (line in font.split(rent, innerWidth - 4)) {
            graphics.text(font, line, innerX + 2, y, fade(if (view.half && view.tables > 0) Ui.GREEN else Ui.TEXT_SOFT, k), false)
            y += 10
        }
    }

    // ---- The foot ----

    /** The purse, counting up or down to what the player has now, and the clerk: come or not. */
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
        val clerk = Component.translatable(if (view.clerk) "gui.guildmark.trade.store.clerk.here" else "gui.guildmark.trade.store.clerk.away")
        val hint = if (tab == BONDED) Component.translatable("gui.guildmark.trade.store.hint") else clerk
        graphics.text(font, hint, innerX + innerWidth - font.width(hint), y, fade(if (tab == BONDED && minecraft.hasShiftDown()) Ui.GOLD else Ui.TEXT_FAINT, k), false)
    }

    private fun drawCoins(graphics: GuiGraphicsExtractor, t: Float) {
        for (c in coins) {
            val p = (t - c.born) / FLIGHT
            if (p < 0f || p > 1f) continue
            val e = ease(p)
            val x = Mth.lerp(e, c.x0, c.x1)
            val y = Mth.lerp(e, c.y0, c.y1) - sin(p * Math.PI.toFloat()) * 18f
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

    private fun buttonTip(row: Row, bonding: Boolean): List<Pair<Component, Int>> {
        val g = row.g
        val lines = ArrayList<Pair<Component, Int>>()
        if (bonding) {
            val n = min(min(g.carried, g.room), StoreDesk.BATCH)
            when {
                view.refusal.isNotEmpty() -> lines.add(Component.translatable(view.refusal, view.place, view.wage) to Ui.TEXT)
                g.carried == 0 -> lines.add(Component.translatable("gui.guildmark.trade.store.tip.none", row.many()) to Ui.TEXT)
                g.room == 0 -> lines.add(Component.translatable(if (g.bonded > 0) "gui.guildmark.trade.store.tip.full" else "gui.guildmark.trade.store.tip.kinds", row.many(), view.kinds) to Ui.TEXT)
                else -> {
                    lines.add(Component.translatable("gui.guildmark.trade.store.tip.bond", n, row.many()) to Ui.TEXT)
                    if (g.hauled > 0) lines.add(Component.translatable("gui.guildmark.trade.store.tip.bond_hauled", min(n, g.hauled)) to Ui.GOLD)
                }
            }
        } else {
            lines.add(Component.translatable(if (g.bonded > 0) "gui.guildmark.trade.store.tip.take" else "gui.guildmark.trade.store.tip.nothing", min(g.bonded, StoreDesk.BATCH), row.many()) to Ui.TEXT)
        }
        lines.add(Component.translatable("gui.guildmark.trade.store.tip.shift") to Ui.TEXT_FAINT)
        return lines
    }

    /** What the Store and the pack hold of [row]'s good, for the tooltip over its name. */
    private fun goodTip(row: Row): List<Pair<Component, Int>> {
        val g = row.g
        val lines = ArrayList<Pair<Component, Int>>()
        lines.add(row.name() to Ui.GOLD)
        lines.add(Component.translatable("gui.guildmark.trade.store.tip.in_store", g.bonded, hauledOf(g)) to Ui.TEXT)
        lines.add(Component.translatable("gui.guildmark.trade.store.tip.carried", g.carried, g.hauled) to Ui.TEXT_SOFT)
        if (g.parcels.isNotEmpty()) lines.add(Component.translatable("gui.guildmark.trade.store.tip.days") to Ui.TEXT_FAINT)
        return lines
    }

    private fun marks(n: Int): Component = Component.translatable(if (n == 1) "gui.guildmark.market.marks.one" else "gui.guildmark.market.marks", n)

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

        val COIN = sprite("compass/lost_marks")
        val PENNANT = sprite("stall/pennant")
        val PENNANT_PAINT = sprite("stall/pennant_paint")
        val SEAL_TINT = sprite(HaulBoardArt.SEAL_TINT_SPRITE)
        val MARK: ItemStack by lazy { ItemStack(ModItems.GUILD_MARK.get()) }
        val CRATE: ItemStack by lazy { ItemStack(Items.BARREL) }
        val SHIELD: ItemStack by lazy { ItemStack(Items.SHIELD) }

        /** The tabs, in order, and their names. */
        const val BONDED = 0
        const val WORD = 1
        const val WAGON = 2
        const val GUARDS = 3
        const val ACCOUNT = 4
        const val TABS = 5

        /** The slots a Carrier's Wagon holds. */
        const val WAGON_SLOTS = 54
        val TAB_KEYS = arrayOf(
            "gui.guildmark.trade.store.tab.bonded", "gui.guildmark.trade.store.tab.word", "gui.guildmark.trade.store.tab.wagon",
            "gui.guildmark.trade.store.tab.guards", "gui.guildmark.trade.store.tab.account",
        )

        /** The tab shown when a desk was last open, to open on again (not kept past the game's closing). */
        var lastTab = BONDED

        const val PAD = 12

        /** Room over the panel for the name board: its chains and the board itself; the board's height. */
        const val SIGN_ROOM = 44
        const val CHAIN = 10
        const val SIGN_H = 28
        const val PENNANT_W = 11
        const val PENNANT_H = 16

        /** The tabs' row (from the panel's top) and height, the lines over the list, and the foot. */
        const val TABS_TOP = 8
        const val TAB_H = 14
        const val HEADLINE = 28
        const val FOOTER = 24

        /** A good's row, each parcel line under it and the most shown; a town's card; the gap between. */
        const val ROW = 30
        const val LINE = 10
        const val PARCELS_SHOWN = 3
        const val TOWN = 46
        const val GAP = 3
        const val TIP_WIDTH = 200

        /** Units of a good in the Store its bar shows full (four stacks). */
        const val BAR_UNITS = 256f

        /** The ticks the hoist's hook takes to climb or come down. */
        const val HOIST = 22f

        /** The ticks a seal takes to come down and strike, and to be gone. */
        const val STAMP_STRIKE = 7
        const val STAMP = 30

        const val HEARTBEAT = 40
        const val FLIGHT = 14f
        const val FLOAT = 24f
    }
}

/** The client's half of the Carriers' Store's desk: its screen as the server opens and updates it. */
object StoreClient {
    /** The server opened [view]'s desk. */
    fun open(view: StoreView) {
        val minecraft = Minecraft.getInstance()
        val open = minecraft.gui.screen() as? StoreScreen
        // A held use key can send the same click twice: keep the screen that's up
        if (open != null && open.place == view.place) {
            open.update(view)
            return
        }
        minecraft.gui.setScreen(StoreScreen(view))
    }

    /** The Store as it stands now, for the desk that's open if it's [view]'s; or, [closed], word that it's closed. */
    fun update(view: StoreView, closed: Boolean) {
        val screen = Minecraft.getInstance().gui.screen() as? StoreScreen ?: return
        if (screen.place != view.place) return
        if (closed) screen.shut() else screen.update(view)
    }
}
