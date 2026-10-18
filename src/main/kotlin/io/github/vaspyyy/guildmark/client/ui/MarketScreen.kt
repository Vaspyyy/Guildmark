package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Lots
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Stalls
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.network.MarketClosePayload
import io.github.vaspyyy.guildmark.network.MarketGood
import io.github.vaspyyy.guildmark.network.MarketOrder
import io.github.vaspyyy.guildmark.network.MarketOrderPayload
import io.github.vaspyyy.guildmark.network.MarketRefreshPayload
import io.github.vaspyyy.guildmark.network.MarketTradePayload
import io.github.vaspyyy.guildmark.network.MarketView
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Trading at one of a capital's shops, face to face with its keeper (see Stalls). The camera settles over the
 * player's shoulder as it does for a conversation, and a panel down the left, under an awning striped in the shop's
 * colour, lists its goods: each one's icon (with how many the player carries), the market's stock as a bar against
 * what the town likes to keep, which way the price is going, the lot to buy and the lot the market buys, and why
 * the price is well off its usual when it is. The keeper's words type out under the shop's name. Every deal comes
 * back with fresh quotes, so the bars slide, the lots change and the purse counts up or down as the player watches;
 * a shift-click deals [Stalls.MANY] lots at once. The screen closes if the keeper walks off or the player does.
 *
 * With supplier orders on, the player's standing at the capital shows under the shop's name (its emblem popping when
 * it rises), and the business's orders head the list, each on its slip: what's wanted, how much of it is in and by
 * when, with buttons to take it, deliver what's carried of it (the Marks flying to the purse), give it up (a second
 * press to be sure), or answer the offer of a standing order. The inn's screen has nothing but its orders.
 */
class MarketScreen(private var view: MarketView) : Screen(Component.translatable("gui.guildmark.market")) {
    /** The shop shown (its id). */
    val shop: String get() = view.shop

    /** Who keeps it: an entity id, or -1 for the testing screen. */
    val keeper: Int get() = view.keeper

    private val kind = Shop.byId(view.shop)

    /** One good's line, with what's shown of it eased toward the latest quote. */
    private inner class Row(var g: MarketGood) {
        val good = Good.byId(g.good)
        val stack: ItemStack = good?.item?.let(::ItemStack) ?: ItemStack.EMPTY

        /** The stock bar's level as shown, last tick and now, and the level it's coming from, fading. */
        var bar = g.stock.toFloat()
        var barLast = bar
        var ghost = bar
        var ghostLast = bar

        /** When the trend, each lot and the count carried last changed, and each button was pressed. */
        var trendAt = -100
        var buyAt = -100
        var sellAt = -100
        var carriedAt = -100
        var pressedBuy = -100
        var pressedSell = -100

        /** Why the price is where it is, cut to the row's width, and the row's height with it. */
        var reason: List<FormattedCharSequence> = listOf()
        val height: Int get() = ROW + reason.size * LINE

        fun wrap() {
            reason = if (g.why.isEmpty()) listOf() else font.split(Component.translatable(g.why, name()), textWidth()).take(2)
        }

        fun name(): Component = good?.let { Component.translatable(it.key) } ?: Component.literal(g.good)
        fun many(): Component = good?.let { Component.translatable(it.key + ".many") } ?: Component.literal(g.good)
    }

    private var rows: List<Row> = view.goods.map { Row(it) }

    /** One of the business's orders, with what's shown of it eased toward the latest word. */
    private inner class Ticket(var o: MarketOrder) {
        val good = Good.byId(o.good)
        val stack: ItemStack = good?.item?.let(::ItemStack) ?: ItemStack.EMPTY

        /** How much of it is in as shown, last tick and now. */
        var filled = o.delivered.toFloat()
        var filledLast = filled

        /** When it came onto the screen, when it last changed, and when its top and bottom buttons were pressed. */
        var cameAt = -100
        var changedAt = -100
        var pressedTop = -100
        var pressedBottom = -100

        /** How many are still to come in, and how many of those it would take of what the player carries. */
        val left: Int get() = (o.amount - o.delivered).coerceAtLeast(0)
        val taking: Int get() = min(o.deliverable, left)

        /** The most it pays in all (a standing order's, each week). */
        val total: Int get() = floor(o.each * o.amount + 1e-6).toInt()

        fun many(): Component = good?.let { Component.translatable(it.key + ".many") } ?: Component.literal(o.good)
    }

    private var tickets: List<Ticket> = view.orders.map { Ticket(it) }

    /** The order whose Give up was pressed once, and when: a second press soon after gives it up. */
    private var unsure = -1
    private var unsureAt = -100

    /** When the player's standing here last rose, and where it's shown (for its tooltip). */
    private var rankAt = -100
    private var rankLeft = 0
    private var rankRight = 0

    private var ticks = 0

    /** The server closed the trade: nothing to tell it on the way out. */
    private var shutHere = false

    /** Ticks the keeper has been gone, dead or out of reach (the server, which says why, mostly closes it first). */
    private var lostFor = 0

    /** The purse as shown, last tick and now, and when it last changed. */
    private var purse = view.marks.toFloat()
    private var purseLast = purse
    private var purseAt = -100

    /** Where the last button pressed is, so the coins know where to fly, and when it was pressed. */
    private var pressedX = 0f
    private var pressedY = 0f
    private var pressedAt = -100

    private class Floater(val text: Component, val x: Int, val y: Int, val color: Int, val born: Int)
    private class Coin(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val born: Int)

    private val floaters = ArrayList<Floater>()
    private val coins = ArrayList<Coin>()

    // The keeper's words: the line last seen on them, cut to the panel's width, and how much of it has typed out
    private var heard = ""
    private var words: List<FormattedCharSequence> = listOf()
    private var wordsText: List<String> = listOf()
    private var typed = 0f
    private var typeWait = 0

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

    /** Room for the name, the bar and the reason, left of the buttons. */
    private fun textWidth(): Int = innerWidth - 22 - buttonWidth - 6

    override fun init() {
        panelWidth = min(width - 20, Mth.clamp(width / 2 - 12, 236, 300))
        panelX = 10
        panelY = AWNING_HEIGHT + 4
        panelHeight = height - panelY - 10
        buttonWidth = Mth.clamp(innerWidth - 120, 84, 118)
        val header = if (view.keeper >= 0) HEADER else HEADER_PLAIN
        listTop = panelY + header
        listBottom = panelY + panelHeight - FOOTER
        rows.forEach { it.wrap() }
        heard = ""
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    override fun added() {
        speaker()?.let { Cutscenes.talk(it, this) }
    }

    override fun removed() {
        if (!shutHere) {
            shutHere = true
            ClientPacketDistributor.sendToServer(MarketClosePayload)
        }
    }

    /** The keeper, while the client knows them. */
    private fun speaker(): Townsperson? = if (view.keeper < 0) null else minecraft.level?.getEntity(view.keeper) as? Townsperson

    // ---- From the server ----

    /** Fresh quotes and counts: everything that changed eases to its new place. */
    fun update(next: MarketView) {
        val before = view
        view = next
        if (next.goods.map { it.good } != rows.map { it.g.good }) {
            rows = next.goods.map { Row(it) }
            rows.forEach { it.wrap() }
        }
        val gained = next.marks - before.marks
        if (gained != 0) {
            purseAt = ticks
            floaters.add(Floater(Component.literal(if (gained > 0) "+$gained" else "$gained"), innerX + 18, footerY() - 4, if (gained > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, ticks))
            flyCoins(gained)
        }
        for (row in rows) {
            val g = next.goods.firstOrNull { it.good == row.g.good } ?: continue
            val old = row.g
            row.g = g
            // The bar slides from where it was shown, the change between glowing as it fades
            if (g.stock < old.stock) row.ghost = max(row.ghost, row.bar) else if (g.stock > old.stock) row.ghost = min(row.ghost, row.bar)
            if (g.trend != old.trend) row.trendAt = ticks
            if (g.buyCount != old.buyCount || g.buyMarks != old.buyMarks) row.buyAt = ticks
            if (g.sellCount != old.sellCount || g.sellMarks != old.sellMarks) row.sellAt = ticks
            if (g.carried != old.carried) {
                row.carriedAt = ticks
                val y = rowTop(row)
                if (y != null) {
                    val n = g.carried - old.carried
                    floaters.add(Floater(Component.literal(if (n > 0) "+$n" else "$n"), innerX + 4, y - 2, if (n > 0) Ui.GREEN else Ui.TEXT_SOFT, ticks))
                }
            }
            if (g.why != old.why) row.wrap()
        }
        updateOrders(before, next)
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    /** The orders and the standing as the server has them now: a new order slides in, a change glows, one filled says so as it goes. */
    private fun updateOrders(before: MarketView, next: MarketView) {
        if (before.standing >= 0 && next.standing > before.standing) {
            rankAt = ticks
            Ui.play(ModSounds.UI_DISCOVER.get(), 1.1f, 0.7f)
        }
        val known = tickets.associateBy { it.o.id }
        fun filled(y: Int) = floaters.add(Floater(Component.translatable("gui.guildmark.market.order.filled"), textX, y + 4, Ui.GREEN, ticks))
        // Filled by the delivery just made, and gone
        for (ticket in tickets) {
            if (next.orders.any { it.id == ticket.o.id }) continue
            val y = ticketTop(ticket) ?: continue
            if (ticket.o.state == MarketOrder.YOURS && ticks - pressedAt < 40) filled(y)
        }
        tickets = next.orders.map { o ->
            val ticket = known[o.id] ?: return@map Ticket(o).also { it.cameAt = ticks }
            val old = ticket.o
            val y = ticketTop(ticket)
            ticket.o = o
            if (o.state != old.state || o.delivered != old.delivered || o.week != old.week) ticket.changedAt = ticks
            // Filled and offered again, or a standing order's week met
            val met = old.state == MarketOrder.YOURS && o.state == MarketOrder.OFFERED ||
                old.state == MarketOrder.STANDING && o.week == old.week && old.delivered < old.amount && o.delivered >= o.amount
            if (met && y != null) filled(y)
            // Taken, or a standing order agreed: sealed
            if (old.state == MarketOrder.OPEN && o.state == MarketOrder.YOURS || old.state == MarketOrder.OFFERED && o.state == MarketOrder.STANDING) {
                Ui.play(ModSounds.UI_SEAL.get(), 1f, 0.6f)
            }
            ticket
        }
    }

    /** The trade is over on the server's side. */
    fun shut() {
        shutHere = true
        onClose()
    }

    /** A few coins arc from the purse to the button just pressed when [gained] is spent, or from it to the purse when it's earned. */
    private fun flyCoins(gained: Int) {
        if (ticks - pressedAt >= 40) return
        val px = innerX + 8f
        val py = footerY() + 3f
        repeat(min(6, abs(gained) + 1)) { i ->
            val c = if (gained < 0) Coin(px, py, pressedX, pressedY, ticks + i * 2) else Coin(pressedX, pressedY, px, py, ticks + i * 2)
            coins.add(c)
        }
    }

    // ---- Every tick ----

    override fun tick() {
        ticks++
        val player = minecraft.player
        val speaker = speaker()
        val lost = view.keeper >= 0 && (speaker == null || !speaker.isAlive || player != null && speaker.distanceToSqr(player) > GONE * GONE)
        lostFor = if (lost) lostFor + 1 else 0
        if (player == null || lostFor > LOST) {
            onClose()
            return
        }
        if (ticks % HEARTBEAT == 0) ClientPacketDistributor.sendToServer(MarketRefreshPayload)
        purseLast = purse
        purse += (view.marks - purse) * 0.25f
        if (abs(view.marks - purse) < 0.05f) purse = view.marks.toFloat()
        for (row in rows) {
            row.barLast = row.bar
            row.bar += (row.g.stock - row.bar) * 0.22f
            if (abs(row.g.stock - row.bar) < 0.02f) row.bar = row.g.stock.toFloat()
            row.ghostLast = row.ghost
            row.ghost += (row.bar - row.ghost) * 0.08f
        }
        for (ticket in tickets) {
            ticket.filledLast = ticket.filled
            ticket.filled += (ticket.o.delivered - ticket.filled) * 0.22f
            if (abs(ticket.o.delivered - ticket.filled) < 0.02f) ticket.filled = ticket.o.delivered.toFloat()
        }
        if (unsure >= 0 && ticks - unsureAt > SURE) unsure = -1
        scrollLast = scrollShown
        scrollShown += (scroll - scrollShown) * 0.5f
        floaters.removeIf { ticks - it.born > FLOAT }
        coins.removeIf { ticks - it.born > FLIGHT }
        listen(speaker)
    }

    /** Types out whatever the keeper is saying, a few letters a tick with the tick of a pen. */
    private fun listen(speaker: Townsperson?) {
        val said = speaker?.speech ?: ""
        // Once they fall quiet the last words stay up, and the next line types out even if it's the same again
        if (said.isEmpty()) {
            heard = ""
        } else if (said != heard) {
            heard = said
            words = font.split(Speech.text(said), innerWidth - 4).take(2)
            wordsText = words.map { line -> StringBuilder().also { b -> line.accept { _, _, c -> b.appendCodePoint(c); true } }.toString() }
            typed = 0f
            typeWait = 2
        }
        val total = wordsText.sumOf { it.length }
        if (typed >= total) return
        if (typeWait > 0) {
            typeWait--
            return
        }
        val from = typed.toInt()
        typed = min(total.toFloat(), typed + TYPING)
        val pitch = speaker?.let { 0.92f + Math.floorMod(it.uuid.leastSignificantBits, 17L) / 100f } ?: 1f
        for (i in from until typed.toInt()) if (i % 3 == 0) Ui.play(ModSounds.UI_TEXT.get(), pitch + (Math.random().toFloat() - 0.5f) * 0.08f, 0.18f)
    }

    // ---- Layout ----

    /** Whether the business's orders show: it has some out, or nothing else to show (the inn). */
    private fun ordersShown(): Boolean = view.standing >= 0 && (tickets.isNotEmpty() || rows.isEmpty())

    /** How far down the list the goods start: under the orders (a line saying there are none, if there aren't), with a heading over each. */
    private fun waresAt(): Int {
        if (!ordersShown()) return 0
        return SECTION + max(1, tickets.size) * (ORDER_ROW + GAP) + if (rows.isEmpty()) 0 else SECTION
    }

    private fun maxScroll(): Float = max(0, waresAt() + rows.sumOf { it.height + GAP } - (listBottom - listTop)).toFloat()

    private fun footerY(): Int = panelY + panelHeight - FOOTER + 10

    /** Where [row]'s top is on screen now, or null if it's scrolled out of the list. */
    private fun rowTop(row: Row): Int? {
        var y = listTop - scrollShown.roundToInt() + waresAt()
        for (r in rows) {
            if (r === row) return if (y + r.height <= listTop || y >= listBottom) null else y
            y += r.height + GAP
        }
        return null
    }

    /** The row and button ([Pair.second]: true for buy) under the cursor. */
    private fun buttonAt(mx: Double, my: Double): Pair<Row, Boolean>? {
        if (my < listTop || my >= listBottom) return null
        val bx = innerX + innerWidth - buttonWidth
        if (mx < bx || mx >= bx + buttonWidth) return null
        var y = listTop - scrollShown.roundToInt() + waresAt()
        for (r in rows) {
            if (my >= y + 1 && my < y + 14) return r to true
            if (my >= y + 15 && my < y + 28) return r to false
            y += r.height + GAP
        }
        return null
    }

    /** The row whose good (its icon, name or bar) is under the cursor. */
    private fun rowAt(mx: Double, my: Double): Row? {
        if (my < listTop || my >= listBottom || mx < innerX || mx >= innerX + innerWidth - buttonWidth - 4) return null
        var y = listTop - scrollShown.roundToInt() + waresAt()
        for (r in rows) {
            if (my >= y && my < y + r.height) return r
            y += r.height + GAP
        }
        return null
    }

    /** Where [ticket]'s top is on screen now, or null if it's scrolled out of the list. */
    private fun ticketTop(ticket: Ticket): Int? {
        val i = tickets.indexOf(ticket)
        if (i < 0 || !ordersShown()) return null
        val y = listTop - scrollShown.roundToInt() + SECTION + i * (ORDER_ROW + GAP)
        return if (y + ORDER_ROW <= listTop || y >= listBottom) null else y
    }

    /** The buttons [ticket]'s order has, each an action (see [MarketOrderPayload]) and how far down its row it sits. */
    private fun actions(ticket: Ticket): List<Pair<Int, Int>> = when (ticket.o.state) {
        MarketOrder.OPEN -> listOf(MarketOrderPayload.TAKE to ONE_BUTTON)
        MarketOrder.YOURS, MarketOrder.STANDING -> listOf(MarketOrderPayload.DELIVER to TOP_BUTTON, MarketOrderPayload.GIVE_UP to BOTTOM_BUTTON)
        MarketOrder.OFFERED -> listOf(MarketOrderPayload.ACCEPT to TOP_BUTTON, MarketOrderPayload.DECLINE to BOTTOM_BUTTON)
        else -> listOf()
    }

    /** Whether [action]'s button is the lower of an order's two. */
    private fun lower(action: Int): Boolean = action == MarketOrderPayload.GIVE_UP || action == MarketOrderPayload.DECLINE

    /** An order's button under the cursor: the order, the action, and the button's top on screen. */
    private class OrderButton(val ticket: Ticket, val action: Int, val y: Int)

    private fun orderButtonAt(mx: Double, my: Double): OrderButton? {
        if (my < listTop || my >= listBottom) return null
        val bx = innerX + innerWidth - buttonWidth
        if (mx < bx || mx >= bx + buttonWidth) return null
        for (ticket in tickets) {
            val y = ticketTop(ticket) ?: continue
            for ((action, dy) in actions(ticket)) if (my >= y + dy && my < y + dy + 13) return OrderButton(ticket, action, y + dy)
        }
        return null
    }

    /** The order whose slip, or what it says, is under the cursor. */
    private fun ticketAt(mx: Double, my: Double): Ticket? {
        if (my < listTop || my >= listBottom || mx < innerX || mx >= innerX + innerWidth - buttonWidth - 4) return null
        for (ticket in tickets) {
            val y = ticketTop(ticket) ?: continue
            if (my >= y && my < y + ORDER_ROW) return ticket
        }
        return null
    }

    /** Whether the player's standing is under the cursor. */
    private fun overRank(mx: Double, my: Double): Boolean =
        view.standing >= 0 && rankRight > rankLeft && mx >= rankLeft && mx < rankRight && my >= panelY + 22 && my < panelY + 35

    // ---- What can be done ----

    private fun canBuy(g: MarketGood): Boolean = g.buyCount > 0 && view.marks >= g.buyMarks
    private fun canSell(g: MarketGood): Boolean = g.sellCount > 0 && g.carried >= g.sellCount

    private fun lot(count: Int, marks: Int): Component =
        Component.translatable(if (marks == 1) "gui.guildmark.market.lot.one" else "gui.guildmark.market.lot", count, marks)

    private fun marks(n: Int): Component = Component.translatable(if (n == 1) "gui.guildmark.market.marks.one" else "gui.guildmark.market.marks", n)

    /** Why the buy or sell button is as it is, for its tooltip. */
    private fun tip(row: Row, buying: Boolean): List<Pair<Component, Int>> {
        val g = row.g
        val lines = ArrayList<Pair<Component, Int>>()
        if (buying) when {
            g.buyCount == 0 -> lines.add(Component.translatable("gui.guildmark.market.tip.sold_out", row.many()) to Ui.TEXT)
            view.marks < g.buyMarks -> lines.add(Component.translatable("gui.guildmark.market.tip.poor", marks(g.buyMarks), marks(view.marks)) to Ui.TEXT)
            else -> lines.add(Component.translatable("gui.guildmark.market.tip.buy", g.buyCount, row.many(), marks(g.buyMarks)) to Ui.TEXT)
        } else when {
            g.sellCount == 0 -> lines.add(Component.translatable("gui.guildmark.market.tip.full", row.many()) to Ui.TEXT)
            g.carried < g.sellCount -> lines.add(Component.translatable("gui.guildmark.market.tip.lacking", g.sellCount, g.carried) to Ui.TEXT)
            else -> lines.add(Component.translatable("gui.guildmark.market.tip.sell", g.sellCount, row.many(), marks(g.sellMarks)) to Ui.TEXT)
        }
        if (if (buying) canBuy(g) else canSell(g)) lines.add(Component.translatable("gui.guildmark.market.tip.shift", Stalls.MANY) to Ui.TEXT_FAINT)
        return lines
    }

    /** What the market holds of [row]'s good, and how its price stands, for the tooltip over its name. */
    private fun goodTip(row: Row): List<Pair<Component, Int>> {
        val g = row.g
        val lines = ArrayList<Pair<Component, Int>>()
        lines.add(row.name() to Ui.GOLD)
        lines.add(Component.translatable("gui.guildmark.market.tip.stock", g.stock, g.keep) to Ui.TEXT)
        if (g.sellCount > 0) lines.add(Component.translatable("gui.guildmark.market.tip.wants", g.wants) to Ui.TEXT_SOFT)
        val percent = ((g.off - 1f) * 100f).roundToInt()
        when {
            percent >= 10 -> lines.add(Component.translatable("gui.guildmark.market.tip.dearer", percent) to Ui.GOLD)
            percent <= -10 -> lines.add(Component.translatable("gui.guildmark.market.tip.cheaper", -percent) to Ui.BLUE)
            else -> lines.add(Component.translatable("gui.guildmark.market.tip.usual") to Ui.TEXT_SOFT)
        }
        lines.add(Component.translatable("gui.guildmark.market.tip.carried", g.carried) to Ui.TEXT_SOFT)
        return lines
    }

    /** Whether [ticket]'s button for [action] does anything now, as far as the screen knows (the server has the last word). */
    private fun can(ticket: Ticket, action: Int): Boolean = when (action) {
        MarketOrderPayload.TAKE, MarketOrderPayload.ACCEPT -> view.held < view.most
        MarketOrderPayload.DELIVER -> ticket.taking > 0
        else -> true
    }

    /** "within 3 days, before the rest day", from the days left. */
    private fun due(days: Int): Component = when (days) {
        0 -> Component.translatable("supplier.guildmark.due.tonight")
        1 -> Component.translatable("supplier.guildmark.due.tomorrow")
        else -> Component.translatable("supplier.guildmark.due", days)
    }

    /** "for 2 more days", from the days an order not yet taken stays open. */
    private fun open(days: Int): Component = when (days) {
        0 -> Component.translatable("gui.guildmark.market.order.open.tonight")
        1 -> Component.translatable("gui.guildmark.market.order.open.tomorrow")
        else -> Component.translatable("gui.guildmark.market.order.open.days", days)
    }

    /** What [ticket]'s order is and where it stands, for the tooltip over its slip. */
    private fun ticketTip(ticket: Ticket): List<Pair<Component, Int>> {
        val o = ticket.o
        val lines = ArrayList<Pair<Component, Int>>()
        val shopName = kind?.let { Component.translatable(it.key) } ?: Component.literal(view.shop)
        lines.add(Component.translatable("gui.guildmark.tracker.order", o.amount, ticket.many(), shopName) to Ui.GOLD)
        if (o.state == MarketOrder.PROMISED) {
            lines.add(Component.translatable("gui.guildmark.market.order.tip.promised", o.who) to Ui.TEXT_SOFT)
            return lines
        }
        if (o.state == MarketOrder.STANDING) lines.add(Component.translatable("gui.guildmark.market.order.tip.standing", Orders.WEEKS, o.week) to Ui.TEXT_SOFT)
        if (o.state == MarketOrder.OFFERED) lines.add(Component.translatable("gui.guildmark.market.order.tip.offered", Orders.WEEKS) to Ui.TEXT_SOFT)
        lines.add(Component.translatable("gui.guildmark.market.order.tip.pays", String.format(Locale.ROOT, "%.2f", o.each), marks(ticket.total)) to Ui.TEXT)
        when (o.state) {
            MarketOrder.OPEN -> {
                lines.add(Component.translatable("gui.guildmark.market.order.tip.if_taken", due(o.days)) to Ui.TEXT_SOFT)
                lines.add(Component.translatable("gui.guildmark.market.order.tip.open", open(o.open)) to Ui.TEXT_FAINT)
            }
            MarketOrder.OFFERED -> lines.add(Component.translatable("gui.guildmark.market.order.tip.answer", open(o.open)) to Ui.TEXT_FAINT)
            else -> {
                lines.add(Component.translatable("gui.guildmark.market.order.tip.in", o.delivered, o.amount, ticket.left) to Ui.TEXT_SOFT)
                if (ticket.left > 0) lines.add(Component.translatable("gui.guildmark.market.order.tip.due", due(o.days)) to if (o.days <= 1) Ui.RED else Ui.TEXT_SOFT)
            }
        }
        lines.add(Component.translatable("gui.guildmark.market.tip.carried", o.carried) to Ui.TEXT_SOFT)
        if (o.carried > o.deliverable) lines.add(Component.translatable("gui.guildmark.market.order.tip.own", o.carried - o.deliverable) to Ui.GOLD)
        return lines
    }

    /** What [ticket]'s button for [action] does, or why it can't, for its tooltip. */
    private fun orderTip(ticket: Ticket, action: Int): List<Pair<Component, Int>> {
        val o = ticket.o
        val line = when (action) {
            MarketOrderPayload.TAKE ->
                if (can(ticket, action)) Component.translatable("gui.guildmark.market.order.tip.take", marks(ticket.total))
                else Component.translatable("gui.guildmark.market.order.tip.full", view.most)
            MarketOrderPayload.ACCEPT ->
                if (can(ticket, action)) Component.translatable("gui.guildmark.market.order.tip.accept", o.amount, ticket.many(), Orders.WEEKS, marks(ticket.total))
                else Component.translatable("gui.guildmark.market.order.tip.full", view.most)
            MarketOrderPayload.DELIVER -> when {
                ticket.left == 0 -> Component.translatable("gui.guildmark.market.order.tip.met")
                ticket.taking > 0 -> Component.translatable("gui.guildmark.market.order.tip.deliver", ticket.taking, ticket.many())
                o.carried > 0 -> Component.translatable("gui.guildmark.market.order.tip.all_own", ticket.many())
                else -> Component.translatable("gui.guildmark.market.order.tip.none", ticket.many())
            }
            MarketOrderPayload.GIVE_UP ->
                if (unsure == o.id) Component.translatable("gui.guildmark.market.order.tip.sure")
                else Component.translatable("gui.guildmark.market.order.tip.give_up")
            else -> Component.translatable("gui.guildmark.market.order.tip.decline")
        }
        return listOf(line to Ui.TEXT)
    }

    /** The player's standing here: what it is, what the next takes, what it does for them. */
    private fun rankTip(): List<Pair<Component, Int>> {
        val rank = SupplierRank.entries.getOrNull(view.standing) ?: return listOf()
        val lines = ArrayList<Pair<Component, Int>>()
        lines.add(rank.title(view.place) to Ui.GOLD)
        val next = rank.next
        lines.add(
            (if (next == null) Component.translatable("gui.guildmark.market.rank.top", view.points)
            else Component.translatable("gui.guildmark.market.rank.points", view.points, next.title(view.place), view.next)) to Ui.TEXT,
        )
        lines.add(Component.translatable("gui.guildmark.market.rank.share", ((Lots.SELL_SHARE + rank.bonus) * 100).roundToInt()) to Ui.TEXT_SOFT)
        lines.add(Component.translatable("gui.guildmark.market.rank.orders", view.held, view.most) to Ui.TEXT_SOFT)
        lines.add(Component.translatable("gui.guildmark.market.rank.how") to Ui.TEXT_FAINT)
        return lines
    }

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            orderButtonAt(event.x(), event.y())?.let { return order(it) }
            val (row, buying) = buttonAt(event.x(), event.y()) ?: return super.mouseClicked(event, doubleClick)
            val ok = if (buying) canBuy(row.g) else canSell(row.g)
            if (!ok) return true
            Ui.select()
            if (buying) row.pressedBuy = ticks else row.pressedSell = ticks
            pressedX = innerX + innerWidth - buttonWidth / 2f
            pressedY = (rowTop(row) ?: listTop) + if (buying) 7f else 21f
            pressedAt = ticks
            ClientPacketDistributor.sendToServer(MarketTradePayload(row.g.good, buying, if (event.hasShiftDown()) Stalls.MANY else 1))
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    /** An order's [button] pressed: asked of the server, but giving an order up wants a second press. */
    private fun order(button: OrderButton): Boolean {
        val ticket = button.ticket
        val action = button.action
        if (!can(ticket, action)) return true
        Ui.select()
        if (action == MarketOrderPayload.GIVE_UP && unsure != ticket.o.id) {
            unsure = ticket.o.id
            unsureAt = ticks
            return true
        }
        unsure = -1
        if (lower(action)) ticket.pressedBottom = ticks else ticket.pressedTop = ticks
        pressedX = innerX + innerWidth - buttonWidth / 2f
        pressedY = button.y + 6f
        pressedAt = ticks
        ClientPacketDistributor.sendToServer(MarketOrderPayload(ticket.o.id, action))
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0 || maxScroll() <= 0f) return false
        scroll = (scroll - scrollY.toFloat() * 22f).coerceIn(0f, maxScroll())
        return true
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // The world stays in view: a shade behind the panel only, fading off to the right
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
        drawHeader(graphics, rise, t)
        drawRows(graphics, mouseX, mouseY, t, a)
        drawFooter(graphics, rise, t, a)
        pose.popMatrix()
        drawCoins(graphics, t)
        drawFloaters(graphics, t)
        if (rise >= 1f) {
            val mx = mouseX.toDouble()
            val my = mouseY.toDouble()
            val lines = orderButtonAt(mx, my)?.let { orderTip(it.ticket, it.action) }
                ?: ticketAt(mx, my)?.let { ticketTip(it) }
                ?: buttonAt(mx, my)?.let { (row, buying) -> tip(row, buying) }
                ?: rowAt(mx, my)?.let { goodTip(it) }
                ?: if (overRank(mx, my)) rankTip() else null
            lines?.let { drawTip(graphics, it, mouseX, mouseY) }
        }
    }

    /** The striped awning over the panel, in the shop's colour, hanging a little lower in the middle of each stripe. */
    private fun drawAwning(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val x0 = panelX - 2
        val w = panelWidth + 4
        val y = panelY - AWNING_HEIGHT + 3
        val cloth = ARGB.white(k)
        val paint = ARGB.color(k, tint())
        var x = 0
        while (x < w) {
            val n = min(TILE, w - x)
            // The cloth hangs still; its hem stirs in a breeze that runs along the awning
            val sway = if (sin(t * 0.08f - x * 0.06f) > 0.9f) 1 else 0
            for (sprite in listOf(AWNING, AWNING_PAINT)) {
                val color = if (sprite == AWNING) cloth else paint
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, TILE, AWNING_HEIGHT, 0, 0, x0 + x, y, n, HEM, color)
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, TILE, AWNING_HEIGHT, 0, HEM - 1, x0 + x, y + HEM - 1 + sway, n, AWNING_HEIGHT - HEM + 1, color)
            }
            x += TILE
        }
        // One rail the whole way along (each tile's own would show a joint), and a pole at either end
        for ((i, c) in RAIL.withIndex()) graphics.fill(x0, y + i, x0 + w, y + i + 1, ARGB.color(k, c))
        val pole = ARGB.color(k, 0x26242A)
        graphics.fill(x0 - 1, y, x0 + 2, y + AWNING_HEIGHT + 2, pole)
        graphics.fill(x0 + w - 2, y, x0 + w + 1, y + AWNING_HEIGHT + 2, pole)
    }

    /** The shop's colour: its awning's stripes. */
    private fun tint(): Int = when (kind) {
        Shop.PRODUCE -> 0x6E9A3E
        Shop.FISH_AND_FRUIT -> 0x3F7FA6
        Shop.BUTCHER -> 0xA8402F
        Shop.CLOTH -> 0x7B55A3
        Shop.FLETCHER -> 0x8C6B3A
        Shop.CHANDLER -> 0xC6912F
        Shop.BAKERY -> 0xCB7A33
        Shop.SMITHY -> 0x5E6470
        Shop.QUAY -> 0x2F8E8A
        Shop.INN -> 0x8A5A2B
        Shop.HALL, null -> 0x3A5D9A
    }

    private fun drawHeader(graphics: GuiGraphicsExtractor, k: Float, t: Float) {
        val name = kind?.let { Component.translatable(it.key) } ?: Component.literal(view.shop)
        Ui.headingAt(graphics, font, name, innerX, panelY + 9, fade(Ui.GOLD, k))
        if (view.place.isNotEmpty()) {
            val place = Component.literal(view.place)
            graphics.text(font, place, innerX + innerWidth - font.width(place), panelY + 11, fade(Ui.TEXT_FAINT, k), false)
        }
        rankLeft = 0
        rankRight = 0
        if (view.keeper < 0) {
            val nobody = Component.translatable("gui.guildmark.market.nobody")
            graphics.text(font, nobody, innerX, panelY + 24, fade(Ui.TEXT_FAINT, k), false)
            drawRank(graphics, innerX + font.width(nobody) + 8, k, t)
            Ui.divider(graphics, innerX, listTop - 5, innerWidth)
            return
        }
        // A keeper the client has lost sight of: the screen is about to close
        val speaker = speaker()
        if (speaker == null) {
            Ui.divider(graphics, innerX, listTop - 5, innerWidth)
            return
        }
        // Who keeps it, on a plate, and what they're saying under it; the player's standing here at the right, and the keeper's trade between if there's room
        val plate = Ui.heading(speaker.customName ?: speaker.name)
        val plateWidth = font.width(plate) + 16
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.NAME_PLATE, innerX - 2, panelY + 22, plateWidth, 13, ARGB.white(k))
        graphics.text(font, plate, innerX + 6, panelY + 25 + Ui.HEADING_RISE - 1, fade(Ui.GOLD_BRIGHT, k), true)
        val rankFrom = drawRank(graphics, innerX + plateWidth + 4, k, t)
        val role = Component.translatable("entity.guildmark.townsperson.${speaker.role.id}")
        if (innerX + plateWidth + 6 + font.width(role) <= rankFrom - 6) {
            graphics.text(font, role, innerX + plateWidth + 4, panelY + 25, fade(Ui.TEXT_FAINT, k), false)
        }
        var left = typed.toInt()
        val fresh = speaker.speech.isNotEmpty()
        for ((i, line) in words.withIndex()) {
            if (left <= 0) break
            val text = wordsText[i]
            val shown = if (left >= text.length) text else text.substring(0, left)
            graphics.text(font, shown, innerX + 2, panelY + 39 + i * LINE, fade(if (fresh) Ui.TEXT else Ui.TEXT_SOFT, k), false)
            left -= text.length
        }
        Ui.divider(graphics, innerX, listTop - 5, innerWidth)
    }

    /**
     * The player's standing here, right-aligned on the line under the shop's name and no further left than [from]: its
     * emblem and its name (only the emblem, if the name won't fit), popping and glowing when it rises. Gives where it
     * starts, or the panel's right edge if there's nothing to show.
     */
    private fun drawRank(graphics: GuiGraphicsExtractor, from: Int, k: Float, t: Float): Int {
        val right = innerX + innerWidth
        val rank = SupplierRank.entries.getOrNull(view.standing) ?: return right
        val title = rank.title(view.place)
        val named = right - (RANK_SIZE + 3 + font.width(title)) >= from
        val left = if (named) right - RANK_SIZE - 3 - font.width(title) else right - RANK_SIZE
        if (left < from) return right
        rankLeft = left
        rankRight = right
        val y = panelY + 24
        val glow = bump(t - rankAt, 30f)
        if (glow > 0f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, left - 3, y - 2, right - left + 6, RANK_SIZE + 4, ARGB.white(glow * k))
        val pop = 1f + 0.8f * bump(t - rankAt, 14f)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(left + RANK_SIZE / 2f, y + RANK_SIZE / 2f)
        pose.scale(pop, pop)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, RANKS[rank.ordinal], -RANK_SIZE / 2, -RANK_SIZE / 2, RANK_SIZE, RANK_SIZE, ARGB.white(k))
        pose.popMatrix()
        if (named) graphics.text(font, title, left + RANK_SIZE + 3, panelY + 25, fade(if (glow > 0.05f) Ui.GOLD_BRIGHT else rankInk(rank), k), false)
        return left
    }

    /** A rank's name is the brighter the higher it stands. */
    private fun rankInk(rank: SupplierRank): Int = when (rank) {
        SupplierRank.STRANGER -> Ui.TEXT_FAINT
        SupplierRank.GROWER -> Ui.TEXT_SOFT
        SupplierRank.KNOWN -> Ui.TEXT
        SupplierRank.TRUSTED -> Ui.GOLD
        SupplierRank.PURVEYOR -> Ui.GOLD_BRIGHT
    }

    private fun drawRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val scrolled = Mth.lerp(a, scrollLast, scrollShown)
        graphics.enableScissor(innerX - 2, listTop, innerX + innerWidth + 2, listBottom)
        var y = listTop - scrolled.roundToInt()
        val shift = minecraft.hasShiftDown()
        // Each row comes in a moment after the one above
        var shown = 0
        fun arrive(): Float = ease(Mth.clamp((t - 3f - shown++ * 1.5f) / 6f, 0f, 1f))
        if (ordersShown()) {
            val held = if (view.most > 0) Component.translatable("gui.guildmark.market.orders.held", view.held, view.most) else null
            drawSection(graphics, Component.translatable("gui.guildmark.market.orders"), held, y, arrive())
            y += SECTION
            if (tickets.isEmpty()) {
                val k = arrive()
                if (y + ORDER_ROW > listTop && y < listBottom) graphics.text(font, Component.translatable("gui.guildmark.market.orders.none"), textX, y + 12, fade(Ui.TEXT_SOFT, k), false)
                y += ORDER_ROW + GAP
            }
            for (ticket in tickets) {
                // A new one slides in from where it was posted
                val k = min(arrive(), ease(Mth.clamp((t - ticket.cameAt) / 8f, 0f, 1f)))
                if (y + ORDER_ROW > listTop && y < listBottom && k > 0f) drawTicket(graphics, ticket, y, k, mouseX, mouseY, t, a)
                y += ORDER_ROW + GAP
            }
            if (rows.isNotEmpty()) {
                drawSection(graphics, Component.translatable("gui.guildmark.market.wares"), null, y, arrive())
                y += SECTION
            }
        }
        for (row in rows) {
            val h = row.height
            val k = arrive()
            if (y + h > listTop && y < listBottom && k > 0f) drawRow(graphics, row, y, k, mouseX, mouseY, t, a, shift)
            y += h + GAP
        }
        graphics.disableScissor()
        // A thin gold thumb at the list's edge when it scrolls
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
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)
        val hovered = mouseY >= y && mouseY < y + row.height && mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= listTop && mouseY < listBottom
        if (hovered) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, innerX - 2, y, innerWidth + 4, row.height, ARGB.white(0.5f * k))

        // The good, with how many the player carries
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, innerX, y + 2, 18, 18, ARGB.white(k))
        if (k > 0.3f) {
            graphics.item(row.stack, innerX + 1, y + 3)
            if (g.stock == 0) graphics.fill(innerX + 1, y + 3, innerX + 17, y + 19, 0x99101014.toInt())
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

        // Its name and which way its price is going
        graphics.text(font, row.name(), textX, y + 3, fade(if (g.stock == 0) Ui.TEXT_SOFT else Ui.TEXT, k), false)
        val barWidth = textWidth() - 24
        drawTrend(graphics, row, textX + barWidth + 15, y + 3, t)

        // The stock against what the town likes to keep, and what's left
        drawBar(graphics, row, textX, y + 15, barWidth, k, t, a)
        val left = if (g.stock == 0) Component.translatable("gui.guildmark.market.none") else Component.literal(g.stock.toString())
        graphics.text(font, left, textX + barWidth + 4, y + 14, fade(if (g.stock == 0) Ui.RED else Ui.TEXT_SOFT, k), false)

        // The lots
        val bx = innerX + innerWidth - buttonWidth
        drawButton(graphics, row, true, bx, y + 1, k, mouseX, mouseY, t, shift)
        drawButton(graphics, row, false, bx, y + 15, k, mouseX, mouseY, t, shift)

        // Why the price is where it is
        for ((i, line) in row.reason.withIndex()) graphics.text(font, line, textX, y + ROW + i * LINE - 1, fade(if (g.dear) Ui.GOLD else Ui.BLUE, k), false)
        pose.popMatrix()
    }

    /** A heading across the list, with [note] at its right if there is one. */
    private fun drawSection(graphics: GuiGraphicsExtractor, title: Component, note: Component?, y: Int, k: Float) {
        if (y + SECTION <= listTop || y >= listBottom || k <= 0f) return
        Ui.headingAt(graphics, font, title, innerX + 2, y + 2, fade(Ui.GOLD, k))
        note?.let { graphics.text(font, it, innerX + innerWidth - font.width(it), y + 4, fade(Ui.TEXT_FAINT, k), false) }
        graphics.fill(innerX, y + SECTION - 3, innerX + innerWidth, y + SECTION - 2, fade(0x40D9B46A, k))
    }

    /** One order on its pinned slip: what's wanted, how it stands for the player, and its buttons. */
    private fun drawTicket(graphics: GuiGraphicsExtractor, ticket: Ticket, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float, a: Float) {
        val o = ticket.o
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)
        val hovered = mouseY >= y && mouseY < y + ORDER_ROW && mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= listTop && mouseY < listBottom
        if (hovered) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, innerX - 2, y, innerWidth + 4, ORDER_ROW, ARGB.white(0.5f * k))
        // A change glows as it fades; an offer glows gently while it waits for an answer
        val glow = max(bump(t - ticket.changedAt, 16f), if (o.state == MarketOrder.OFFERED) 0.22f + 0.12f * sin(t * 0.12f) else 0f)
        if (glow > 0f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, innerX - 2, y, innerWidth + 4, ORDER_ROW, ARGB.white(glow * k))

        // The slip with the good on it: sealed for a standing order, stamped once someone else has it
        val promised = o.state == MarketOrder.PROMISED
        val standing = o.state == MarketOrder.STANDING || o.state == MarketOrder.OFFERED
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLIP, innerX, y + 4, SLIP_WIDTH, SLIP_HEIGHT, ARGB.white(k))
        if (k > 0.3f) {
            graphics.item(ticket.stack, innerX + 1, y + 9)
            if (promised) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, STAMP, innerX, y + 12, SLIP_WIDTH, 10, ARGB.white(k))
            if (standing) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SEAL, innerX + 11, y + 21, 8, 8, ARGB.white(k))
        }

        // What's wanted, and a standing order's weeks
        val x = textX
        val w = textWidth()
        val pips = if (standing) Orders.WEEKS * 5 + 2 else 0
        line(graphics, Component.translatable("supplier.guildmark.what", o.amount, ticket.many()), x, y + 3, w - pips, fade(if (promised) Ui.TEXT_SOFT else Ui.TEXT, k))
        if (standing) drawWeeks(graphics, if (o.state == MarketOrder.OFFERED) 0 else o.week, x + w, y + 3, k)

        // How it stands
        when (o.state) {
            MarketOrder.YOURS, MarketOrder.STANDING -> {
                val count = Component.translatable("gui.guildmark.market.order.count", o.delivered, o.amount)
                val barWidth = w - font.width(count) - 5
                drawFill(graphics, ticket, x, y + 15, barWidth, k, t, a)
                graphics.text(font, count, x + barWidth + 4, y + 14, fade(Ui.TEXT_SOFT, k), false)
                if (ticket.left == 0) line(graphics, Component.translatable("gui.guildmark.market.order.met"), x, y + 25, w, fade(Ui.GREEN, k))
                else line(graphics, shortDue(o.days), x, y + 25, w, fade(if (o.days <= 1) Ui.RED else Ui.TEXT_SOFT, k))
            }
            MarketOrder.OPEN -> {
                line(graphics, within(o.days, "gui.guildmark.market.order.to_bring"), x, y + 14, w, fade(Ui.TEXT_SOFT, k))
                line(graphics, within(o.open, "gui.guildmark.market.order.take_within"), x, y + 25, w, fade(Ui.TEXT_FAINT, k))
            }
            MarketOrder.OFFERED -> {
                line(graphics, Component.translatable("gui.guildmark.market.order.weekly", Orders.WEEKS), x, y + 14, w, fade(Ui.TEXT_SOFT, k))
                line(graphics, within(o.open, "gui.guildmark.market.order.answer_within"), x, y + 25, w, fade(Ui.TEXT_FAINT, k))
            }
            else -> line(graphics, Component.translatable("gui.guildmark.market.order.promised", o.who), x, y + 14, w, fade(Ui.TEXT_FAINT, k))
        }

        // What can be done with it
        val bx = innerX + innerWidth - buttonWidth
        for ((action, dy) in actions(ticket)) drawOrderButton(graphics, ticket, action, bx, y + dy, k, mouseX, mouseY, t)
        if (promised) graphics.centeredText(font, Component.translatable("gui.guildmark.market.order.taken"), bx + buttonWidth / 2, y + 13, fade(Ui.TEXT_FAINT, k))
        pose.popMatrix()
    }

    /** [text] on one line no wider than [w], cut at a word if it's longer. */
    private fun line(graphics: GuiGraphicsExtractor, text: Component, x: Int, y: Int, w: Int, color: Int) {
        val first = font.split(text, max(1, w)).firstOrNull() ?: return
        graphics.text(font, first, x, y, color, false)
    }

    /** "Due in 3 days", for an order's own line. */
    private fun shortDue(days: Int): Component = when (days) {
        0 -> Component.translatable("gui.guildmark.market.order.due.tonight")
        1 -> Component.translatable("gui.guildmark.market.order.due.tomorrow")
        else -> Component.translatable("gui.guildmark.market.order.due", days)
    }

    /** "2 days to take it" (under [key]; a day, or today, said as such). */
    private fun within(days: Int, key: String): Component = when (days) {
        0 -> Component.translatable("$key.today")
        1 -> Component.translatable("$key.one")
        else -> Component.translatable(key, days)
    }

    /** A standing order's weeks as pips ending at [right]: those gone by, this one the brightest, those to come faint (all of them, for one offered). */
    private fun drawWeeks(graphics: GuiGraphicsExtractor, week: Int, right: Int, y: Int, k: Float) {
        for (i in 1..Orders.WEEKS) {
            val px = right - (Orders.WEEKS - i + 1) * 5 + 1
            val color = when {
                i < week -> Ui.GOLD
                i == week -> Ui.GOLD_BRIGHT
                else -> Ui.TEXT_FAINT
            }
            graphics.fill(px, y + 2, px + 3, y + 5, fade(color, k))
        }
    }

    /** How much of an order is in: easing up as it comes, the bar flashing as a delivery lands. */
    private fun drawFill(graphics: GuiGraphicsExtractor, ticket: Ticket, x: Int, y: Int, w: Int, k: Float, t: Float, a: Float) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.INSET, x, y, w, 6, ARGB.white(k))
        val shown = Mth.lerp(a, ticket.filledLast, ticket.filled)
        val end = x + 1 + ((w - 2) * Mth.clamp(shown / max(1, ticket.o.amount), 0f, 1f)).roundToInt()
        if (end <= x + 1) return
        graphics.fillGradient(x + 1, y + 1, end, y + 5, fade(0xFFF2D58C.toInt(), k), fade(0xFFA07A34.toInt(), k))
        val flash = bump(t - ticket.changedAt, 20f)
        if (flash > 0f) graphics.fill(x + 1, y + 1, end, y + 5, ARGB.color((90 * flash * k).toInt(), 0xFFFFFF))
    }

    /** One of an order's buttons: lit under the cursor, dipping when pressed, dimmed when it can't be done, and red while it waits to be sure. */
    private fun drawOrderButton(graphics: GuiGraphicsExtractor, ticket: Ticket, action: Int, x: Int, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float) {
        val ok = can(ticket, action)
        val lit = ok && mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 13 && mouseY >= listTop && mouseY < listBottom
        val down = t - (if (lower(action)) ticket.pressedBottom else ticket.pressedTop) < 3f
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(ok, lit), x, y + if (down) 1 else 0, buttonWidth, 13, ARGB.white(k))
        val sure = action == MarketOrderPayload.GIVE_UP && unsure == ticket.o.id
        if (sure) graphics.fill(x + 1, y + 1, x + buttonWidth - 1, y + 12, ARGB.color(((0.3f + 0.15f * sin(t * 0.4f)) * k).coerceIn(0f, 1f), 0xD0573F))
        val ty = y + 3 + if (down) 1 else 0
        val verb = Component.translatable(
            when (action) {
                MarketOrderPayload.TAKE -> "gui.guildmark.market.order.take"
                MarketOrderPayload.DELIVER -> if (ticket.left == 0) "gui.guildmark.market.order.met.short" else "gui.guildmark.market.order.deliver"
                MarketOrderPayload.GIVE_UP -> if (sure) "gui.guildmark.market.order.sure" else "gui.guildmark.market.order.give_up"
                MarketOrderPayload.ACCEPT -> "gui.guildmark.market.order.accept"
                else -> "gui.guildmark.market.order.decline"
            },
        )
        val value = when (action) {
            MarketOrderPayload.TAKE, MarketOrderPayload.ACCEPT -> marks(ticket.total)
            MarketOrderPayload.DELIVER -> if (ok) Component.translatable("gui.guildmark.market.order.many", ticket.taking) else null
            else -> null
        }
        val verbColor = when {
            !ok -> Ui.TEXT_FAINT
            sure -> Ui.GOLD_BRIGHT
            lower(action) -> Ui.TEXT_SOFT
            else -> Ui.GOLD
        }
        if (value != null && font.width(verb) + 6 + font.width(value) <= buttonWidth - 8) {
            graphics.text(font, verb, x + 5, ty, fade(verbColor, k), false)
            graphics.text(font, value, x + buttonWidth - 5 - font.width(value), ty, fade(if (lit) Ui.GOLD_BRIGHT else if (ok) Ui.TEXT else Ui.TEXT_FAINT, k), false)
        } else {
            graphics.centeredText(font, verb, x + buttonWidth / 2, ty, fade(verbColor, k))
        }
    }

    /** The stock bar: what's there (easing to it when it changes, the change glowing as it fades), and a notch at what the town keeps. */
    private fun drawBar(graphics: GuiGraphicsExtractor, row: Row, x: Int, y: Int, w: Int, k: Float, t: Float, a: Float) {
        val g = row.g
        val most = max(1f, g.keep * Lots.ROOM.toFloat())
        val inner = w - 2
        fun at(v: Float): Int = x + 1 + (inner * Mth.clamp(v / most, 0f, 1f)).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.INSET, x, y, w, 6, ARGB.white(k))
        val shown = Mth.lerp(a, row.barLast, row.bar)
        val ghost = Mth.lerp(a, row.ghostLast, row.ghost)
        val end = at(shown)
        val full = g.stock / max(1f, g.keep.toFloat())
        val (top, bottom) = when {
            full < 0.35f -> 0xFFD0684C.toInt() to 0xFF7E2E22.toInt()
            full < 0.8f -> 0xFFE4BF72.toInt() to 0xFF96713A.toInt()
            else -> 0xFF9FD288.toInt() to 0xFF4C8842.toInt()
        }
        if (end > x + 1) graphics.fillGradient(x + 1, y + 1, end, y + 5, fade(top, k), fade(bottom, k))
        // What was taken, fading out; or what came in, glowing
        val was = at(ghost)
        if (abs(was - end) >= 1) {
            val glow = Mth.clamp(abs(ghost - shown) / max(1f, most * 0.05f), 0f, 1f)
            if (was > end) graphics.fill(end, y + 1, was, y + 5, ARGB.color((150 * glow * k).toInt(), 0xD0573F))
            else graphics.fill(was, y + 1, end, y + 5, ARGB.color((170 * glow * k).toInt(), 0xF2E6B0))
        }
        // A glint that runs along the bar now and then
        val run = (t % 90f) / 30f
        if (run < 1f && end > x + 4) {
            val gx = x + 1 + ((end - x) * run).toInt()
            graphics.fill(gx, y + 1, min(end, gx + 3), y + 5, ARGB.color((60 * k).toInt(), 0xFFFFFF))
        }
        // What the town likes to keep
        val notch = at(g.keep.toFloat())
        graphics.fill(notch, y - 1, notch + 1, y + 7, fade(Ui.GOLD_BRIGHT, k))
    }

    /** Which way the price is going: up (dearer, red), down (cheaper, green), or steady, bobbing gently; it pops when it turns. */
    private fun drawTrend(graphics: GuiGraphicsExtractor, row: Row, x: Int, y: Int, t: Float) {
        val sprite = when (row.g.trend) {
            1 -> UP
            -1 -> DOWN
            else -> STEADY
        }
        val bob = if (row.g.trend == 0) 0f else sin(t * 0.16f + row.hashCode() % 7) * 0.9f * -row.g.trend
        val pop = 1f + 0.7f * bump(t - row.trendAt, 10f)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + 3.5f, y + 3.5f + bob)
        pose.scale(pop, pop)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, -3, -3, 7, 7)
        pose.popMatrix()
    }

    /** A lot's button: lit under the cursor, flashing when pressed or when the lot changes, dimmed when it can't be had. */
    private fun drawButton(graphics: GuiGraphicsExtractor, row: Row, buying: Boolean, x: Int, y: Int, k: Float, mouseX: Int, mouseY: Int, t: Float, shift: Boolean) {
        val g = row.g
        val count = if (buying) g.buyCount else g.sellCount
        val marks = if (buying) g.buyMarks else g.sellMarks
        val ok = if (buying) canBuy(g) else canSell(g)
        val lit = ok && mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 13 && mouseY >= listTop && mouseY < listBottom
        val pressedAt = if (buying) row.pressedBuy else row.pressedSell
        val down = t - pressedAt < 3f
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(ok, lit), x, y + if (down) 1 else 0, buttonWidth, 13, ARGB.white(k))
        val changed = bump(t - if (buying) row.buyAt else row.sellAt, 12f)
        if (changed > 0f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x + 1, y + 1, buttonWidth - 2, 11, ARGB.white(changed * k))
        val ty = y + 3 + if (down) 1 else 0
        if (count == 0) {
            val none = Component.translatable(if (buying) "gui.guildmark.market.sold_out" else "gui.guildmark.market.full")
            graphics.centeredText(font, none, x + buttonWidth / 2, ty, fade(Ui.TEXT_FAINT, k))
            return
        }
        val verb = Component.translatable(
            when {
                shift && ok -> if (buying) "gui.guildmark.market.buy.many" else "gui.guildmark.market.sell.many"
                buying -> "gui.guildmark.market.buy"
                else -> "gui.guildmark.market.sell"
            },
            Stalls.MANY,
        )
        var shown = lot(count, marks)
        if (font.width(verb) + 6 + font.width(shown) > buttonWidth - 8) shown = Component.translatable("gui.guildmark.market.lot.short", count, marks)
        val withVerb = font.width(verb) + 6 + font.width(shown) <= buttonWidth - 8
        val lotColor = when {
            !ok -> Ui.TEXT_FAINT
            lit -> Ui.GOLD_BRIGHT
            else -> Ui.TEXT
        }
        if (withVerb) {
            graphics.text(font, verb, x + 5, ty, fade(if (ok) (if (shift) Ui.GOLD_BRIGHT else Ui.GOLD) else Ui.TEXT_FAINT, k), false)
            graphics.text(font, shown, x + buttonWidth - 5 - font.width(shown), ty, fade(lotColor, k), false)
        } else graphics.centeredText(font, shown, x + buttonWidth / 2, ty, fade(lotColor, k))
    }

    /** The purse: the player's Guild Marks, counting up or down to what they have now, and the hint for shift-clicking. */
    private fun drawFooter(graphics: GuiGraphicsExtractor, k: Float, t: Float, a: Float) {
        Ui.divider(graphics, innerX, listBottom + 3, innerWidth)
        val y = footerY()
        graphics.item(MARK, innerX, y - 4)
        val shown = Mth.lerp(a, purseLast, purse).roundToInt()
        val glow = bump(t - purseAt, 14f)
        val text = marks(shown)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(innerX + 20f, y + 4f)
        val pop = 1f + 0.15f * glow
        pose.scale(pop, pop)
        graphics.text(font, text, 0, -4, fade(if (glow > 0.05f) Ui.GOLD_BRIGHT else Ui.TEXT, k), true)
        pose.popMatrix()
        // Shift-clicking is for the goods; the inn has none
        if (rows.isEmpty()) return
        val shift = minecraft.hasShiftDown()
        val hint = Component.translatable("gui.guildmark.market.hint", Stalls.MANY)
        graphics.text(font, hint, innerX + innerWidth - font.width(hint), y, fade(if (shift) Ui.GOLD else Ui.TEXT_FAINT, k), false)
    }

    /** The coins in flight between the purse and the stall, each on a little arc. */
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

    /** Counts that changed, rising and fading where they changed. */
    private fun drawFloaters(graphics: GuiGraphicsExtractor, t: Float) {
        for (f in floaters) {
            val p = (t - f.born) / FLOAT
            if (p < 0f || p > 1f) continue
            val alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
            graphics.text(font, f.text, f.x, (f.y - ease(p) * 10f).roundToInt(), ARGB.color(alpha, f.color), true)
        }
    }

    /** What a button or a good means, in a small dark panel by the cursor. */
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
        val UP = sprite("market/up")
        val DOWN = sprite("market/down")
        val STEADY = sprite("market/steady")
        val SHADE = sprite("market/shade")
        val COIN = sprite("compass/lost_marks")
        val MARK: ItemStack by lazy { ItemStack(ModItems.GUILD_MARK.get()) }

        /** An order's slip, the wax seal of a standing one, the stamp on one promised to someone else, and each rank's emblem (as orders_art.py draws them). */
        val SLIP = sprite("market/order_slip")
        val SEAL = sprite("market/order_seal")
        val STAMP = sprite("market/order_taken")
        val RANKS = SupplierRank.entries.map { sprite("market/rank_${it.id}") }
        const val SLIP_WIDTH = 18
        const val SLIP_HEIGHT = 24
        const val RANK_SIZE = 9

        const val PAD = 12
        /** The awning's tile, how far down it its hem starts, and its rail's rows (as ui_art.py draws them). */
        const val TILE = 16
        const val AWNING_HEIGHT = 14
        const val HEM = 10
        val RAIL = intArrayOf(0x0C0B0E, 0x54525C, 0x3A3840, 0x26242A)
        /** The panel's top as far as the list, with the keeper's name and words in it, and without. */
        const val HEADER = 66
        const val HEADER_PLAIN = 40
        const val FOOTER = 26
        /** A row's height before its reason, the height of a line of it, and the gap between rows. */
        const val ROW = 30
        const val LINE = 10
        const val GAP = 3
        /** An order's row, how far down it its buttons sit (one alone, or two), and a heading over the orders or the goods. */
        const val ORDER_ROW = 34
        const val ONE_BUTTON = 10
        const val TOP_BUTTON = 3
        const val BOTTOM_BUTTON = 18
        const val SECTION = 16
        /** Ticks a first press of Give up waits for the second. */
        const val SURE = 60
        const val TIP_WIDTH = 190
        /** Letters of the keeper's words shown each tick. */
        const val TYPING = 1.8f
        /** Ticks between the screen's word to the server that it's still open. */
        const val HEARTBEAT = 40
        /** Ticks a coin flies, and a changed count floats. */
        const val FLIGHT = 14f
        const val FLOAT = 24f
        /** How far off the keeper may be before the screen gives up on them (the server closes it a little nearer). */
        const val GONE = Stalls.REACH + 3.0

        /** Ticks the screen waits for the server's word once the keeper's gone before closing by itself. */
        const val LOST = 20
    }
}

/** The client's side of the market screens (see [MarketScreen]): opening one, and keeping the open one up to date. */
object MarketClient {
    /** The server opened [view]'s screen. */
    fun open(view: MarketView) {
        val minecraft = Minecraft.getInstance()
        val open = minecraft.gui.screen() as? MarketScreen
        // A held use key can send the same click twice: keep the screen that's up
        if (open != null && open.keeper == view.keeper && open.shop == view.shop) {
            open.update(view)
            return
        }
        minecraft.gui.setScreen(MarketScreen(view))
    }

    /** Fresh quotes for the screen that's open, if it's [view]'s; or, [closed], word that its trade is over. */
    fun update(view: MarketView, closed: Boolean) {
        val screen = Minecraft.getInstance().gui.screen() as? MarketScreen ?: return
        if (screen.shop != view.shop || screen.keeper != view.keeper) return
        if (closed) screen.shut() else screen.update(view)
    }
}
