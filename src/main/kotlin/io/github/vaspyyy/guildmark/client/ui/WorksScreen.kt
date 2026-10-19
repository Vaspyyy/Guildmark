package io.github.vaspyyy.guildmark.client.ui

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksArt
import io.github.vaspyyy.guildmark.client.works.WorksClient
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.economy.works.build.WorksBlocks
import io.github.vaspyyy.guildmark.economy.works.build.WorksLedger
import io.github.vaspyyy.guildmark.homestead.HomesteadTiers
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.network.WorksActionPayload
import io.github.vaspyyy.guildmark.network.WorksDrawingPayload
import io.github.vaspyyy.guildmark.network.WorksLedgerPayload
import io.github.vaspyyy.guildmark.network.WorksLine
import io.github.vaspyyy.guildmark.network.WorksShipLine
import io.github.vaspyyy.guildmark.registry.ModSounds
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.Direction
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A capital's works screen (living-world/design-influence.md sections 2 to 4, and open question 10), opened at a
 * works' post, crate or yard, from the market board's WORKS slip, or by its master builder. Down its left, every works
 * the town offers the reader, building and paid-for first, and those it doesn't want yet, greyed; on its right, four
 * pages, each with its tab:
 *
 *  - **The works:** its drawing, its front as an elevation inked in block by block as the crew lays it (scaffolding up
 *    and down, the bough on the ridge), with the crew where they stand on it; its stages along a track; its state,
 *    stage and progress; its master builder and crew, today's labourers by name; what holds it up, or the daylight
 *    work left; and its materials, used, in the yard and on the wagons, with the wagons on the road under the drawing.
 *  - **Pledges:** its price and its parts, the Marks pledged toward it and the reader's share, and every pledger, its
 *    patron crowned; with Pledge (1, 10, 50, the rest) and Take back below.
 *  - **Fortunes:** the town's last fourteen days' score with the times' bands shaded, the days its works eased
 *    something marked, and today's reasons with their points and the days they have left.
 *  - **Regard:** how many of the town's residents count the reader a friend, an acquaintance, or are wary or worse,
 *    the rest who know of them and the strangers, and for what.
 *
 * Everything comes from the server and keeps itself up to date while it's open: whoever else pledges, the crew's every
 * block. Every number eases to its new value, and the page settles in as it opens. It closes once the reader walks off
 * from where they opened it.
 */
class WorksScreen(private var data: WorksLedgerPayload) : Screen(Component.translatable("gui.guildmark.works.screen")) {
    val capital: Long get() = data.capital

    private var ticks = 0
    private var panelX = 0
    private var panelY = 0

    /** The works shown (its id), or a not-yet ([NOT_YET] less its index), and when it was picked. */
    private var selected = data.selected
    private var selectedAt = -100

    /** The page open (a works wanted opens at its pledges), when it was turned to, and the tabs' underline as it eases across. */
    private var page = if (data.works.firstOrNull { it.id == data.selected }?.state == WorksState.WANTED.ordinal) PLEDGES else WORKS
    private var pageAt = -100
    private var underline = -1f
    private var underlineO = 0f

    /** Scrolled lists: the works down the left, the pledgers, the reasons. */
    private var listScroll = 0
    private var pledgeScroll = 0
    private var reasonScroll = 0

    /** What came of the reader's last ask, and since when it shows. */
    private var notice: Component = Component.empty()
    private var noticeTone = WorksLedger.NONE
    private var noticeAt = -1000
    private var serial = data.serial

    /** Shown values easing to the screen's own, by what they show. */
    private class Ease {
        var now = 0f
        var before = 0f
        var seen = false

        fun step(target: Float, rate: Float) {
            before = now
            now = if (!seen) 0f else if (abs(target - now) < 0.0005f) target else now + (target - now) * rate
            seen = true
        }

        fun at(a: Float): Float = before + (now - before) * a
    }

    private val eases = HashMap<String, Ease>()

    private fun ease(key: String): Ease = eases.getOrPut(key) { Ease() }

    /** Coins thrown into the pledge box, and when; Marks others just pledged, rising off the bar. */
    private class Coin(val at: Int, val dx: Float, val spin: Float)

    private val coins = ArrayList<Coin>()

    private class Rise(val at: Int, val text: String)

    private val rises = ArrayList<Rise>()

    /** The drawing's cells as last drawn (the entry showing in each, or -1), and when each last changed: what's newly laid pops in. */
    private var drawnFor = -1
    private var drawnHash = 0
    private var shownIn = IntArray(0)
    private var poppedAt = IntArray(0)

    /** The stage last seen of each works, the one before, and when it moved on, so its name crossfades. */
    private class Moved(val now: Int, val was: Int, val at: Int)

    private val stageSeen = HashMap<Int, Moved>()

    /** Until when a second press of Take back takes it back. */
    private var confirmUntil = -1

    private var pledgeButtons = listOf<Ui.KitButton>()
    private var withdraw: Ui.KitButton? = null

    fun update(payload: WorksLedgerPayload) {
        val before = data
        data = payload
        if (payload.serial != serial) {
            serial = payload.serial
            if (payload.tone != WorksLedger.NONE) heard(payload.tone, payload.notice)
        }
        // Others' pledges rise off the bar
        val was = before.works.firstOrNull { it.id == selected }
        val now = payload.works.firstOrNull { it.id == selected }
        if (was != null && now != null && now.pledged > was.pledged) {
            for (p in now.pledges) {
                if (p.mine) continue
                val had = was.pledges.firstOrNull { it.name == p.name }?.marks ?: 0
                if (p.marks > had) rises.add(Rise(ticks, "+${p.marks - had} ${p.name}"))
            }
        }
        if (selected >= 0 && payload.works.none { it.id == selected }) selected = payload.works.firstOrNull()?.id ?: if (payload.notYet.isNotEmpty()) NOT_YET else -1
        refresh()
    }

    /** What came of the reader's ask: its words for a while, and its sound. */
    private fun heard(tone: Int, words: Component) {
        notice = words
        noticeTone = tone
        noticeAt = ticks
        when (tone) {
            WorksLedger.PLEDGED, WorksLedger.PAID -> {
                Ui.play(WorksBlocks.PLEDGE.get(), 1f, 0.7f)
                repeat(5) { i -> coins.add(Coin(ticks + i * 2, (i - 2) * 1.6f, (i % 2 * 2 - 1) * 0.3f)) }
                if (tone == WorksLedger.PAID) Ui.play(ModSounds.UI_SEAL.get(), 1f, 0.7f)
            }
            WorksLedger.PLEDGED_AT_POST -> repeat(5) { i -> coins.add(Coin(ticks + i * 2, (i - 2) * 1.6f, (i % 2 * 2 - 1) * 0.3f)) }
            WorksLedger.WITHDRAWN -> Ui.play(ModSounds.MARKS_RECOVER.get(), 1f, 0.6f)
            WorksLedger.REFUSED -> Ui.play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 0.5f)
        }
    }

    /** A drawing has come for works [id]. */
    fun drew(id: Int) {
        if (id == selected) drawnFor = -1
    }

    // ---- Setup ----

    override fun init() {
        panelX = (width - PANEL_W) / 2
        panelY = max(4, (height - PANEL_H) / 2)
        val footY = panelY + PANEL_H - 25
        val cx = panelX + CONTENT_X
        var x = cx
        pledgeButtons = AMOUNTS.map { amount ->
            val w = if (amount == WorksLedger.REST) 72 else 26
            val b = addRenderableWidget(Ui.button(Component.empty(), w, 18) { pledge(amount) }.also { it.setPosition(x, footY) })
            x += w + 4
            b
        }
        withdraw = addRenderableWidget(Ui.button(Component.empty(), cx + CONTENT_W - x, 18) { withdraw() }.also { it.setPosition(x, footY) })
        addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.works.close"), LIST_W, 18) { onClose() }.also { it.setPosition(panelX + LIST_X, footY) })
        if (selected == -1 && data.works.isEmpty() && data.notYet.isNotEmpty()) selected = NOT_YET
        refresh()
    }

    private fun line(): WorksLine? = data.works.firstOrNull { it.id == selected }

    private fun refresh() {
        val l = line()
        val pledging = page == PLEDGES
        val wanted = l != null && l.state == WorksState.WANTED.ordinal
        val rest = if (l == null) 0 else (l.price - l.pledged).coerceAtLeast(0)
        for ((i, b) in pledgeButtons.withIndex()) {
            val amount = AMOUNTS[i]
            b.visible = pledging
            val n = if (amount == WorksLedger.REST) rest else min(amount, rest)
            val whole = Component.translatable("gui.guildmark.works.pledge.rest", grouped(rest))
            b.message = when {
                amount != WorksLedger.REST -> Component.literal("+$amount")
                font.width(whole) <= b.width - 8 -> whole
                else -> Component.translatable("gui.guildmark.works.pledge.rest_short", grouped(rest))
            }
            b.active = wanted && n > 0 && data.purse >= n && data.on
        }
        withdraw?.let {
            val asking = ticks < confirmUntil
            it.visible = pledging
            val mine = l?.mine ?: 0
            it.message = Component.translatable(if (asking) "gui.guildmark.works.withdraw.sure" else "gui.guildmark.works.withdraw", grouped(mine))
            it.active = wanted && mine > 0 && data.on
        }
    }

    private fun send(action: Int, id: Int = selected, arg: Int = 0) {
        ClientPacketDistributor.sendToServer(WorksActionPayload(id, action, arg))
    }

    private fun pledge(amount: Int) {
        val l = line() ?: return
        send(WorksLedger.PLEDGE, l.id, amount)
    }

    private fun withdraw() {
        val l = line() ?: return
        if (ticks < confirmUntil) {
            confirmUntil = -1
            send(WorksLedger.WITHDRAW, l.id)
            refresh()
            return
        }
        confirmUntil = ticks + CONFIRM_TICKS
        Ui.play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.4f)
        refresh()
    }

    private fun select(to: Int) {
        if (to == selected) return
        selected = to
        selectedAt = ticks
        drawnFor = -1
        pledgeScroll = 0
        confirmUntil = -1
        Ui.select()
        if (to >= 0) send(WorksLedger.SELECT, to)
        refresh()
    }

    private fun turn(to: Int) {
        if (to == page) return
        page = to
        pageAt = ticks
        confirmUntil = -1
        // The chart draws itself in again, and the regard counts up again
        if (to == FORTUNES) eases.remove("chart")
        if (to == REGARD) eases.keys.removeIf { it.startsWith("r") }
        Ui.page()
        send(WorksLedger.PAGE, selected.coerceAtLeast(0), to)
        refresh()
    }

    override fun added() {
        super.added()
        Ui.open()
        send(WorksLedger.PAGE, selected.coerceAtLeast(0), page)
    }

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun removed() {
        send(WorksLedger.CLOSE, selected.coerceAtLeast(0))
        super.removed()
    }

    /** The server closed it (walked off, the works switched off). */
    fun closeNow() {
        Ui.close()
        minecraft.gui.setScreen(null)
    }

    override fun isPauseScreen(): Boolean = false

    override fun isInGameUi(): Boolean = true

    override fun tick() {
        ticks++
        val player = minecraft.player
        if (player == null || !player.isAlive || player.position().distanceToSqr(Vec3.atCenterOf(data.at)) > REACH * REACH) {
            closeNow()
            return
        }
        if (confirmUntil in 1..ticks) {
            confirmUntil = -1
            refresh()
        }
        // The tabs' underline glides to the open page
        underlineO = underline
        underline = if (underline < 0f) page.toFloat() else underline + (page - underline) * 0.35f
        // The shown values ease toward the screen's
        val l = line()
        if (l != null) {
            ease("p${l.id}").step(if (l.total <= 0) (if (l.state == WorksState.DONE.ordinal) 1f else 0f) else l.laid.toFloat() / l.total, 0.15f)
            ease("f${l.id}").step(if (l.price <= 0) 1f else (l.pledged.toFloat() / l.price).coerceIn(0f, 1f), 0.18f)
            ease("y${l.id}").step(if (l.price <= 0) 0f else (l.mine.toFloat() / l.price).coerceIn(0f, 1f), 0.18f)
            for (i in l.bill.indices) {
                val bill = l.bill[i].toFloat().coerceAtLeast(1f)
                ease("u${l.id}.$i").step((l.used.getOrElse(i) { 0 } / bill).coerceIn(0f, 1f), 0.2f)
                ease("h${l.id}.$i").step(((l.used.getOrElse(i) { 0 } + l.here.getOrElse(i) { 0 }) / bill).coerceIn(0f, 1f), 0.2f)
                ease("c${l.id}.$i").step(((l.used.getOrElse(i) { 0 } + l.here.getOrElse(i) { 0 } + l.coming.getOrElse(i) { 0 }) / bill).coerceIn(0f, 1f), 0.2f)
            }
            for (s in l.ships) ease("w${s.caravan}").step(s.along, 0.1f)
            val seen = stageSeen[l.id]
            if (seen == null) stageSeen[l.id] = Moved(l.stage, l.stage, -100) else if (seen.now != l.stage) stageSeen[l.id] = Moved(l.stage, seen.now, ticks)
        }
        val r = data.regard
        if (r.counts.isNotEmpty()) for (k in r.counts.indices) ease("r$k").step(r.counts[k].toFloat(), 0.12f)
        ease("chart").step(1f, 0.08f)
        coins.removeIf { ticks - it.at > COIN_TICKS }
        rises.removeIf { ticks - it.at > RISE_TICKS }
    }

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val mx = event.x()
            val my = event.y()
            for (i in 0 until PAGES) {
                if (onTab(i, mx, my)) {
                    turn(i)
                    return true
                }
            }
            entryAt(mx, my)?.let {
                select(it)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val step = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (step == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val lx = panelX + LIST_X
        if (mouseX >= lx && mouseX < lx + LIST_W && mouseY >= panelY + CONTENT_Y && mouseY < panelY + CONTENT_Y + CONTENT_H) {
            listScroll = (listScroll + step).coerceIn(0, max(0, entries().size - LIST_ROWS))
            return true
        }
        when (page) {
            PLEDGES -> pledgeScroll = (pledgeScroll + step).coerceIn(0, max(0, (line()?.pledges?.size ?: 0) - PLEDGE_ROWS))
            FORTUNES -> reasonScroll = (reasonScroll + step).coerceIn(0, max(0, data.fortune.reasons.size - REASON_ROWS))
        }
        return true
    }

    // ---- The list ----

    /** The list's entries in order: works' ids, then the not-yets as [NOT_YET] less their index. */
    private fun entries(): List<Int> = data.works.map { it.id } + data.notYet.indices.map { NOT_YET - it }

    private fun entryAt(mx: Double, my: Double): Int? {
        val x = panelX + LIST_X
        if (mx < x || mx >= x + LIST_W) return null
        val all = entries()
        for ((k, e) in all.drop(listScroll).take(LIST_ROWS).withIndex()) {
            val y = entryY(k + listScroll, all)
            if (my >= y && my < y + ROW_H) return e
        }
        return null
    }

    /** Where entry [k] of [all] sits: the not-yets below a label of their own. */
    private fun entryY(k: Int, all: List<Int>): Int {
        val firstNot = all.indexOfFirst { it <= NOT_YET }
        val gap = if (firstNot in 1..k) LABEL_H else if (firstNot == 0) LABEL_H else 0
        return panelY + CONTENT_Y + (k - listScroll) * ROW_H + gap
    }

    private fun drawList(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, time: Float) {
        val x = panelX + LIST_X
        val top = panelY + CONTENT_Y
        val all = entries()
        if (all.isEmpty()) {
            g.textWithWordWrap(font, Component.translatable("gui.guildmark.works.none"), x + 2, top + 4, LIST_W - 4, Ui.TEXT_FAINT, false)
            return
        }
        g.enableScissor(x - 2, top - 2, x + LIST_W + 2, top + CONTENT_H)
        val firstNot = all.indexOfFirst { it <= NOT_YET }
        for ((k, e) in all.withIndex()) {
            if (k < listScroll || k >= listScroll + LIST_ROWS + 1) continue
            val y = entryY(k, all)
            if (k == firstNot) {
                g.text(font, Component.translatable("gui.guildmark.works.not_yet"), x + 2, y - LABEL_H + 1, Ui.TEXT_FAINT, false)
            }
            val sel = e == selected
            val hover = !sel && mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + ROW_H
            if (sel) {
                Ui.inset(g, x - 1, y - 1, LIST_W + 2, ROW_H + 1)
                val glow = 0.75f + 0.25f * sin(time * 0.15f)
                g.fill(x - 1, y, x + 1, y + ROW_H - 1, ARGB.color(glow, Ui.GOLD_BRIGHT))
            } else if (hover) {
                g.fill(x, y, x + LIST_W, y + ROW_H - 1, 0x22D9B46A)
            }
            if (e >= 0) {
                val l = data.works.firstOrNull { it.id == e } ?: continue
                val kind = kindOf(l.kind)
                g.item(ICONS[kind.ordinal], x + 3, y + 3)
                g.text(font, fit(name(kind, l.road), LIST_W - 24), x + 22, y + 2, if (sel) Ui.GOLD_BRIGHT else Ui.TEXT, false)
                val (chip, color) = chip(l)
                g.text(font, fit(chip, LIST_W - 24), x + 22, y + 12, color, false)
                // A works going up: a sliver of progress under it
                if (l.state == WorksState.BUILDING.ordinal && l.total > 0) {
                    val f = (l.laid.toFloat() / l.total).coerceIn(0f, 1f)
                    g.fill(x + 22, y + ROW_H - 3, x + 22 + ((LIST_W - 26) * f).roundToInt(), y + ROW_H - 2, ARGB.color(0.8f, Ui.GOLD))
                }
            } else {
                val n = data.notYet.getOrNull(NOT_YET - e) ?: continue
                val kind = kindOf(n.kind)
                g.item(ICONS[kind.ordinal], x + 3, y + 3)
                // Greyed: a veil over its icon
                g.fill(x + 3, y + 3, x + 19, y + 19, 0x99161219.toInt())
                g.text(font, fit(name(kind, n.road), LIST_W - 24), x + 22, y + 2, if (sel) Ui.TEXT_SOFT else Ui.TEXT_FAINT, false)
                g.text(font, fit(Component.translatable("gui.guildmark.works.chip.not_yet"), LIST_W - 24), x + 22, y + 12, Ui.TEXT_FAINT, false)
            }
        }
        g.disableScissor()
        if (all.size > LIST_ROWS) {
            // Where the list is scrolled to
            val h = CONTENT_H - 4
            val bar = max(8, h * LIST_ROWS / all.size)
            val at = (h - bar) * listScroll / max(1, all.size - LIST_ROWS)
            g.fill(x + LIST_W + 1, top + at, x + LIST_W + 2, top + at + bar, ARGB.color(0.6f, Ui.GOLD))
        }
    }

    /** A works' state in a few words for the list, and its colour. */
    private fun chip(l: WorksLine): Pair<Component, Int> = when (WorksState.entries.getOrNull(l.state)) {
        WorksState.WANTED -> Component.translatable("gui.guildmark.works.chip.wanted", grouped(l.pledged), grouped(l.price)) to Ui.GOLD
        WorksState.FUNDED -> Component.translatable("gui.guildmark.works.chip.funded") to Ui.TEXT_SOFT
        WorksState.BUILDING -> Component.translatable("gui.guildmark.works.chip.building", percent(l.laid, l.total)) to Ui.GREEN
        WorksState.DONE -> Component.translatable("gui.guildmark.works.chip.done") to Ui.TEXT_SOFT
        null -> Component.empty() to Ui.TEXT_FAINT
    }

    // ---- Drawing ----

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val k = min(1f, (ticks + a) / 5f)
        g.fill(0, 0, width, height, ARGB.color((150 * k).toInt(), 6, 4, 2))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val time = ticks + a
        // The panel settles in as it opens
        val open = min(1f, time / 6f)
        val lift = ((1f - open * open * (3 - 2 * open)) * 10f).roundToInt()
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(0f, lift.toFloat())
        val my = mouseY - lift
        Ui.panel(g, panelX, panelY, PANEL_W, PANEL_H)
        val center = panelX + PANEL_W / 2
        val town = if (data.town.isEmpty()) Component.translatable("gui.guildmark.works.the_town") else Component.literal(data.town)
        Ui.centeredHeading(g, font, Component.translatable("gui.guildmark.works.title", town), center, panelY + 8, Ui.GOLD_BRIGHT)
        subtitle()?.let { g.centeredText(font, it, center, panelY + 22, Ui.TEXT_SOFT) }
        val purse = Component.translatable("gui.guildmark.works.purse", grouped(data.purse))
        g.text(font, purse, panelX + PANEL_W - 12 - font.width(purse), panelY + 8, Ui.GOLD, false)
        Ui.divider(g, panelX + 10, panelY + 33, PANEL_W - 20)
        drawList(g, mouseX, my, time)
        drawTabs(g, mouseX, my, a, time)
        // The page slides in as it's turned to, or as another works is picked
        val turned = min(1f, (time - max(pageAt, selectedAt)) / 6f)
        val slide = ((1f - turned) * (1f - turned) * 12f).roundToInt()
        pose.pushMatrix()
        pose.translate(slide.toFloat(), 0f)
        val cx = panelX + CONTENT_X
        val cy = panelY + CONTENT_Y
        val mx = mouseX - slide
        when (page) {
            WORKS -> drawWorks(g, cx, cy, time, a, mx, my)
            PLEDGES -> drawPledges(g, cx, cy, time, a, mx, my)
            FORTUNES -> drawFortunes(g, cx, cy, time, a, mx, my)
            REGARD -> drawRegard(g, cx, cy, time, a, mx, my)
        }
        pose.popMatrix()
        drawNotice(g, time)
        pose.popMatrix()
        super.extractRenderState(g, mouseX, mouseY, a)
        val asking = confirmUntil - time
        withdraw?.let { b ->
            if (asking > 0 && b.visible) {
                val w = (b.width * (asking / CONFIRM_TICKS)).roundToInt().coerceIn(0, b.width)
                g.fill(b.x, b.y + b.height, b.x + w, b.y + b.height + 2, ARGB.color(0.9f, Ui.RED))
            }
        }
        for ((i, b) in pledgeButtons.withIndex()) {
            if (b.visible && b.isHovered && !b.active) whyNot(AMOUNTS[i])?.let { g.setTooltipForNextFrame(font, font.split(it, TIP_W), mouseX, mouseY) }
        }
        withdraw?.let { b ->
            if (b.visible && b.isHovered) {
                val tip = if (b.active) Component.translatable("gui.guildmark.works.withdraw.tip") else whyNotBack()
                tip?.let { g.setTooltipForNextFrame(font, font.split(it, TIP_W), mouseX, mouseY) }
            }
        }
    }

    /** Under the title: the times, and the market day. */
    private fun subtitle(): Component? {
        val f = data.fortune
        if (!data.on) return Component.translatable("gui.guildmark.works.off")
        if (!f.on || f.today < 0) return null
        return Component.translatable("gui.guildmark.works.subtitle", Component.translatable("board.guildmark.slip.fortune.${times(f.times)}"), f.today)
    }

    /** Why pledging [amount] can't be pressed, for its tooltip. */
    private fun whyNot(amount: Int): Component? {
        val l = line() ?: return Component.translatable("gui.guildmark.works.why.pick")
        if (!data.on) return Component.translatable("gui.guildmark.works.off")
        if (l.state != WorksState.WANTED.ordinal) return Component.translatable("gui.guildmark.works.why.paid")
        val rest = (l.price - l.pledged).coerceAtLeast(0)
        val n = if (amount == WorksLedger.REST) rest else min(amount, rest)
        if (data.purse < n) return Component.translatable("gui.guildmark.works.why.purse", n)
        return null
    }

    private fun whyNotBack(): Component? {
        val l = line() ?: return null
        return when {
            l.state != WorksState.WANTED.ordinal -> Component.translatable("gui.guildmark.works.why.spent")
            l.mine <= 0 -> Component.translatable("gui.guildmark.works.why.nothing")
            else -> null
        }
    }

    // ---- The tabs ----

    private fun tabX(i: Int): Int = panelX + CONTENT_X + i * (CONTENT_W / PAGES)

    private fun onTab(i: Int, mx: Double, my: Double): Boolean {
        val x = tabX(i)
        val y = panelY + TAB_Y
        return mx >= x && mx < x + CONTENT_W / PAGES && my >= y - 2 && my < y + 11
    }

    private fun drawTabs(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float, time: Float) {
        val w = CONTENT_W / PAGES
        val y = panelY + TAB_Y
        for (i in 0 until PAGES) {
            val x = tabX(i)
            val label = Component.translatable(TABS[i])
            val hover = onTab(i, mouseX.toDouble(), mouseY.toDouble())
            val color = when {
                i == page -> Ui.GOLD_BRIGHT
                hover -> Ui.TEXT
                else -> Ui.TEXT_FAINT
            }
            g.centeredText(font, label, x + w / 2, y, color)
            // Something new on a shut page: a pledge to make, the times moved
            if (i == PLEDGES && i != page && line()?.state == WorksState.WANTED.ordinal) {
                g.fill(x + w / 2 + font.width(label) / 2 + 3, y + 3, x + w / 2 + font.width(label) / 2 + 5, y + 5, ARGB.color(0.6f + 0.4f * sin(time * 0.2f), Ui.GOLD))
            }
        }
        val at = Mth.lerp(a, underlineO, underline)
        val ux = (panelX + CONTENT_X + at * w + w * 0.18f).roundToInt()
        g.fill(ux, y + 10, ux + (w * 0.64f).roundToInt(), y + 11, Ui.GOLD)
        g.fill(panelX + CONTENT_X, y + 11, panelX + CONTENT_X + CONTENT_W, y + 12, 0x40D9B46A)
    }

    // ---- The works page ----

    private fun drawWorks(g: GuiGraphicsExtractor, x: Int, y: Int, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        val l = line()
        if (l == null) {
            val n = data.notYet.getOrNull(NOT_YET - selected)
            if (n == null) {
                g.textWithWordWrap(font, Component.translatable("gui.guildmark.works.none"), x, y + 4, CONTENT_W, Ui.TEXT_FAINT, false)
                return
            }
            val kind = kindOf(n.kind)
            sketch(g, x, y, kind, time, faded = true)
            val rx = x + DRAW_W + 8
            val rw = CONTENT_W - DRAW_W - 8
            g.text(font, fit(name(kind, n.road), rw), rx, y, Ui.GOLD, false)
            g.text(font, Component.translatable("gui.guildmark.works.state.not_yet"), rx, y + 11, Ui.TEXT_FAINT, false)
            g.textWithWordWrap(font, n.text, rx, y + 26, rw, Ui.TEXT_SOFT, false)
            return
        }
        val kind = kindOf(l.kind)
        val drawing = WorksClient.drawings[l.id]?.takeIf { it.hash == l.drawing && l.drawing != 0 }
        if (drawing != null) elevation(g, x, y, l, drawing, time, mouseX, mouseY) else sketch(g, x, y, kind, time, faded = false)
        stages(g, x, y + DRAW_H + 4, l, time, mouseX, mouseY)
        road(g, x, y + DRAW_H + 16, l, time, a, mouseX, mouseY)

        val rx = x + DRAW_W + 8
        val rw = CONTENT_W - DRAW_W - 8
        val state = WorksState.entries.getOrNull(l.state) ?: WorksState.WANTED
        g.text(font, fit(name(kind, l.road), rw), rx, y, Ui.GOLD, false)
        // Its stage, the one it left fading out as it moves on, and how far along it is
        val moved = stageSeen[l.id]
        val since = if (moved == null || moved.at < 0) 1f else min(1f, (time - moved.at) / 10f)
        val sy = y + 11
        if (since < 1f && moved != null) fadedText(g, fit(stageName(state, moved.was), rw - 28), rx, sy - (since * 4).roundToInt(), Ui.TEXT, 1f - since)
        fadedText(g, fit(stageName(state, l.stage), rw - 28), rx, sy + ((1f - since) * 4).roundToInt(), Ui.TEXT, since)
        val shown = ease("p${l.id}").at(a)
        if (state != WorksState.WANTED) {
            val pc = "${(shown.coerceIn(0f, 1f) * 100).roundToInt()}%"
            g.text(font, pc, rx + rw - font.width(pc), sy, Ui.GOLD, false)
        }
        bar(g, rx, y + 22, rw, 5, if (state == WorksState.WANTED) ease("f${l.id}").at(a) else shown, if (state == WorksState.WANTED) Ui.GOLD else Ui.GREEN, time)

        var ly = y + 31
        when (state) {
            WorksState.WANTED -> {
                // What made it wanted, and what's to say of its site
                ly += 10 * wrap(g, Component.translatable("gui.guildmark.works.wanted_for", l.why), rx, ly, rw, Ui.TEXT_SOFT, 3) + 2
                if (l.status.string.isNotEmpty()) wrap(g, l.status, rx, ly, rw, Ui.TEXT_FAINT, 2)
            }
            WorksState.DONE -> {
                // Standing: since when, what it does now, and when it last eased something
                if (l.status.string.isNotEmpty()) {
                    g.text(font, fit(l.status, rw), rx, ly, Ui.TEXT_SOFT, false)
                    ly += 12
                }
                if (l.doing.string.isNotEmpty()) ly += 10 * wrap(g, l.doing, rx, ly, rw, Ui.TEXT, 4) + 4
                eased(g, l, rx, ly, rw)
                return
            }
            else -> {
                // The crew: its master and where he is, the labourers at it, today's from the town by name
                val master = if (l.master.string.isEmpty()) Component.translatable("gui.guildmark.works.master.someone") else l.master
                val (said, saidColor) = when (l.masterAt) {
                    1 -> Component.translatable("gui.guildmark.works.master.here", master) to Ui.GREEN
                    2 -> Component.translatable("gui.guildmark.works.master.away", master) to Ui.TEXT_SOFT
                    else -> Component.translatable("gui.guildmark.works.master.coming", master) to Ui.TEXT_FAINT
                }
                g.text(font, fit(said, rw), rx, ly, saidColor, false)
                tipIf(g, said, rx, ly, rw, mouseX, mouseY)
                ly += 10
                val crew = if (l.working > 0) Component.translatable("gui.guildmark.works.crew.working", l.working, l.crew)
                else Component.translatable("gui.guildmark.works.crew.away", l.crew)
                g.text(font, fit(crew, rw), rx, ly, if (l.working > 0) Ui.GREEN else Ui.TEXT_SOFT, false)
                ly += 10
                val names = when {
                    kind == WorksKind.TOWER -> Component.translatable("gui.guildmark.works.crew.strangers_all")
                    l.names.isEmpty() -> Component.translatable("gui.guildmark.works.crew.none_today")
                    l.strangers == 1 -> Component.translatable("gui.guildmark.works.crew.names_stranger", names(l.names))
                    l.strangers > 1 -> Component.translatable("gui.guildmark.works.crew.names_strangers", names(l.names), l.strangers)
                    else -> Component.translatable("gui.guildmark.works.crew.names", names(l.names))
                }
                g.text(font, fit(names, rw), rx, ly, Ui.TEXT_FAINT, false)
                tipIf(g, names, rx, ly, rw, mouseX, mouseY)
                ly += 11
                // What holds it up (red while it's run out of something), what's to say of its site, or the work left
                val (hold, holdColor) = when {
                    l.waiting.string.isNotEmpty() -> l.waiting to (if (!l.alarm) Ui.TEXT_SOFT else if ((time / 10).toInt() % 2 == 0) Ui.RED else ARGB.scaleRGB(Ui.RED, 0.8f))
                    l.status.string.isNotEmpty() -> l.status to Ui.TEXT_SOFT
                    l.eta >= 0 -> eta(l.eta) to Ui.TEXT_SOFT
                    else -> Component.empty() to Ui.TEXT_FAINT
                }
                if (hold.string.isNotEmpty()) wrap(g, hold, rx, ly, rw, holdColor, 2)
            }
        }
        materials(g, rx, y + 90, rw, l, time, a, mouseX, mouseY)
    }

    /** The whole of [text] as a tooltip while the mouse is over its line at ([x], [y]), if it didn't fit in [w]. */
    private fun tipIf(g: GuiGraphicsExtractor, text: Component, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        if (font.width(text) <= w || mouseX < x || mouseX >= x + w || mouseY < y - 1 || mouseY >= y + 9) return
        g.setTooltipForNextFrame(font, font.split(text, TIP_W), mouseX, mouseY)
    }

    /** [text] at [alpha] of [color], left out while it's all but clear (the font draws a near-clear colour solid). */
    private fun fadedText(g: GuiGraphicsExtractor, text: FormattedCharSequence, x: Int, y: Int, color: Int, alpha: Float, shadow: Boolean = false) {
        if (alpha < 0.05f) return
        g.text(font, text, x, y, ARGB.color(alpha.coerceAtMost(1f), color), shadow)
    }

    /** [text] wrapped to [w] from ([x], [y]), at most [most] lines: how many it took. */
    private fun wrap(g: GuiGraphicsExtractor, text: Component, x: Int, y: Int, w: Int, color: Int, most: Int): Int {
        val lines = font.split(text, w)
        for ((i, part) in lines.take(most).withIndex()) g.text(font, part, x, y + i * 10, color, false)
        return min(lines.size, most)
    }

    /** A standing works' last helps: the mill kept turning, bread the old ovens couldn't have baked, a band kept off. */
    private fun eased(g: GuiGraphicsExtractor, l: WorksLine, x: Int, y: Int, w: Int) {
        var ly = y
        for ((what, day) in l.eased.sortedByDescending { it.second }.take(2)) {
            g.text(font, fit(Component.translatable("gui.guildmark.works.last_eased.$what", day), w), x, ly, Ui.GOLD, false)
            ly += 10
        }
    }

    /** The daylight work left, in words. */
    private fun eta(ticks: Int): Component {
        val hours = ticks / 1000.0
        val days = ticks / HomesteadTiers.WORKDAY.toDouble()
        return when {
            ticks <= 300 -> Component.translatable("gui.guildmark.works.eta.soon")
            hours < 1.5 -> Component.translatable("gui.guildmark.works.eta.hour")
            hours < 11.5 -> Component.translatable("gui.guildmark.works.eta.hours", hours.roundToInt())
            half(days) == "1" -> Component.translatable("gui.guildmark.works.eta.day")
            else -> Component.translatable("gui.guildmark.works.eta.days", half(days))
        }
    }

    // ---- The drawing ----

    /** The post's own parchment drawing of [kind] (art/: post.png), for a works with no site yet or not wanted yet. */
    private fun sketch(g: GuiGraphicsExtractor, x: Int, y: Int, kind: WorksKind, time: Float, faded: Boolean) {
        Ui.inset(g, x - 2, y - 2, DRAW_W + 4, DRAW_H + 4)
        g.fill(x, y, x + DRAW_W, y + DRAW_H, PAPER)
        val at = WorksArt.PAPER_AT[kind.id] ?: return
        val w = WorksArt.PAPER_W * 2
        val h = WorksArt.PAPER_H * 2
        val px = x + (DRAW_W - w) / 2
        val py = y + (DRAW_H - h) / 2
        // Lifting a little in the wind, as on the post
        val bob = (sin(time * 0.05f) * 0.8f).roundToInt()
        g.blit(RenderPipelines.GUI_TEXTURED, POST, px, py + bob, at[0].toFloat(), at[1].toFloat(), w, h, WorksArt.PAPER_W, WorksArt.PAPER_H, WorksArt.POST_TEX_W, WorksArt.POST_TEX_H, ARGB.white(if (faded) 0.45f else 1f))
    }

    /**
     * [l]'s front, as [d] draws it: each cell its front-most block that's laid, in its colour; a scaffold pole's
     * lattice before it; the slim posts of stakes and the bough; what's still to lay pencilled in by its outline; and
     * what was just laid popping in. Over it, the crew and whoever else is on it, where they stand.
     */
    private fun elevation(g: GuiGraphicsExtractor, x: Int, y: Int, l: WorksLine, d: WorksDrawingPayload, time: Float, mouseX: Int, mouseY: Int) {
        Ui.inset(g, x - 2, y - 2, DRAW_W + 4, DRAW_H + 4)
        g.fill(x, y, x + DRAW_W, y + DRAW_H, PAPER)
        val cells = d.width * d.height
        if (cells <= 0) return
        val cs = max(1, min((DRAW_W - 8) / max(1, d.width), (DRAW_H - 10) / max(1, d.height)))
        val gw = cs * d.width
        val gh = cs * d.height
        val ox = x + (DRAW_W - gw) / 2
        val base = y + DRAW_H - 5 - ((DRAW_H - 10 - gh) / 2).coerceAtLeast(0)
        // The drafting grid, every four cells
        for (c in 0..d.width step 4) g.fill(ox + c * cs, base - gh, ox + c * cs + 1, base, GRID)
        for (r in 0..d.height step 4) g.fill(ox, base - r * cs, ox + gw, base - r * cs + 1, GRID)
        if (drawnFor != l.id || drawnHash != d.hash || shownIn.size != cells) {
            drawnFor = l.id
            drawnHash = d.hash
            shownIn = IntArray(cells) { -2 }
            poppedAt = IntArray(cells) { -1000 }
        }
        // Each cell's front-most entry laid now, and whether a scaffold's lattice or a post stands before it
        val solid = IntArray(cells) { -1 }
        val lattice = BooleanArray(cells)
        val pole = IntArray(cells) { -1 }
        val planned = IntArray(cells) { -1 }
        for (c in 0 until cells) {
            for (k in d.starts[c] until d.starts[c + 1]) {
                val style = d.colors[k] ushr 24
                if (style == WorksLedger.SOLID && planned[c] < 0) planned[c] = k
                val up = l.isDone(d.steps[k]) && (d.gones[k] < 0 || !l.isDone(d.gones[k]))
                if (!up || solid[c] >= 0) continue
                when (style) {
                    WorksLedger.SCAFFOLD -> lattice[c] = true
                    WorksLedger.POLE -> if (pole[c] < 0) pole[c] = k
                    else -> solid[c] = k
                }
            }
            val was = shownIn[c]
            if (was != -2 && was != solid[c] && solid[c] >= 0) poppedAt[c] = ticks
            shownIn[c] = solid[c]
        }
        for (c in 0 until cells) {
            val col = c % d.width
            val row = c / d.width
            val cx = ox + col * cs
            val cy = base - (row + 1) * cs
            val k = solid[c]
            if (k >= 0) {
                val rgb = d.colors[k] and 0xFFFFFF
                val age = (time - poppedAt[c]) / POP_TICKS
                if (age < 1f) {
                    // Newly laid: it drops in a little and flashes
                    val e = age.coerceIn(0f, 1f)
                    val inset = ((1f - e) * cs * 0.3f).roundToInt()
                    val drop = ((1f - e) * (1f - e) * cs * 0.8f).roundToInt()
                    g.fill(cx + inset, cy - drop + inset, cx + cs - inset, cy + cs - drop - inset, ARGB.opaque(rgb))
                    g.fill(cx + inset, cy - drop + inset, cx + cs - inset, cy + cs - drop - inset, ARGB.color(0.6f * (1f - e), -1))
                } else {
                    g.fill(cx, cy, cx + cs, cy + cs, ARGB.opaque(rgb))
                    if (cs >= 4) {
                        // Lit along its top where nothing's laid above it, shaded along its foot
                        val above = if (row + 1 < d.height) solid[c + d.width] else -1
                        if (above < 0) g.fill(cx, cy, cx + cs, cy + 1, ARGB.color(0.35f, -1))
                        g.fill(cx, cy + cs - 1, cx + cs, cy + cs, ARGB.color(0.18f, 0))
                    }
                }
            } else if (planned[c] >= 0) {
                // Still to lay: pencilled in
                val rgb = d.colors[planned[c]] and 0xFFFFFF
                g.fill(cx, cy, cx + cs, cy + cs, ARGB.color(0.13f, rgb))
                if (cs >= 3) {
                    fun empty(cc: Int, ok: Boolean): Boolean = !ok || planned[cc] < 0
                    if (empty(c + d.width, row + 1 < d.height)) g.fill(cx, cy, cx + cs, cy + 1, INK)
                    if (empty(c - d.width, row > 0)) g.fill(cx, cy + cs - 1, cx + cs, cy + cs, INK)
                    if (empty(c - 1, col > 0)) g.fill(cx, cy, cx + 1, cy + cs, INK)
                    if (empty(c + 1, col + 1 < d.width)) g.fill(cx + cs - 1, cy, cx + cs, cy + cs, INK)
                }
            }
            if (pole[c] >= 0 && k < 0) {
                val rgb = d.colors[pole[c]] and 0xFFFFFF
                val pw = max(1, cs / 4)
                g.fill(cx + (cs - pw) / 2, cy, cx + (cs + pw) / 2 + 1, cy + cs, ARGB.opaque(rgb))
            }
            if (lattice[c]) {
                g.outline(cx, cy, cs, cs, SCAFFOLD_COLOR)
                if (cs >= 5) g.fill(cx, cy + cs / 2, cx + cs, cy + cs / 2 + 1, SCAFFOLD_COLOR)
            }
        }
        // The ground it stands on
        val gy = base - d.ground * cs
        g.fill(x + 2, gy, x + DRAW_W - 2, gy + 1, GROUND)
        g.fillGradient(x + 2, gy + 1, x + DRAW_W - 2, min(y + DRAW_H, gy + 4), ARGB.color(0.35f, GROUND), 0)
        crew(g, x, y, d, ox, base, cs, time)
        if (mouseX >= x && mouseX < x + DRAW_W && mouseY >= y && mouseY < y + DRAW_H) {
            val tip = listOf(
                Component.translatable("gui.guildmark.works.drawing"),
                Component.translatable("gui.guildmark.works.drawing.laid", grouped(l.laid), grouped(l.total)),
            )
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY)
        }
    }

    /**
     * The crew and anyone else at the works, on its drawing where they stand: its master in gold, its builders green,
     * the reader blinking, other players white.
     */
    private fun crew(g: GuiGraphicsExtractor, x: Int, y: Int, d: WorksDrawingPayload, ox: Int, base: Int, cs: Int, time: Float) {
        val level = minecraft.level ?: return
        val front = Direction.from2DDataValue(d.front)
        val x0 = d.x1 - d.width + 1
        val corners = listOf(world(d, front, x0 - 3.0, d.z0 - 6.0), world(d, front, d.x1 + 3.0, d.z1 + 3.0))
        val box = AABB(
            min(corners[0].x, corners[1].x), d.bottom - 2.0, min(corners[0].z, corners[1].z),
            max(corners[0].x, corners[1].x) + 1.0, d.bottom + d.height + 4.0, max(corners[0].z, corners[1].z) + 1.0,
        )
        g.enableScissor(x, y, x + DRAW_W, y + DRAW_H)
        fun figure(ex: Double, ey: Double, ez: Double, color: Int) {
            val (lx, lz) = local(d, front, ex, ez)
            if (lz < d.z0 - 6.0 || lz > d.z1 + 3.0) return
            val sx = ox + ((d.x1 - lx) + 0.5) * cs
            val feet = base - (ey - d.bottom) * cs
            val h = max(3.0, cs * 1.8)
            val w = max(1.0, cs * 0.6)
            val left = (sx - w / 2).roundToInt()
            val top = (feet - h).roundToInt()
            g.fill(left, top, left + w.roundToInt().coerceAtLeast(1), feet.roundToInt(), color)
            // A head a shade lighter
            val head = max(1, (h * 0.3).roundToInt())
            g.fill(left, top, left + w.roundToInt().coerceAtLeast(1), top + head, ARGB.color(1f, ARGB.srgbLerp(0.35f, color, -1)))
        }
        for (p in level.getEntitiesOfClass(Townsperson::class.java, box)) {
            val color = if (WorksClient.master(p)) Ui.GOLD_BRIGHT else 0xFF6FA35A.toInt()
            figure(p.x, p.y, p.z, color)
        }
        for (p in level.getEntitiesOfClass(Player::class.java, box)) {
            val me = p === minecraft.player
            val color = if (me) (if ((time / 8).toInt() % 2 == 0) -1 else Ui.GOLD) else 0xFFE8E8E8.toInt()
            figure(p.x, p.y, p.z, color)
        }
        g.disableScissor()
    }

    /** The drawing's (fractional) x, z of the world's [wx], [wz]: as WorksFrame.local, between the blocks too. */
    private fun local(d: WorksDrawingPayload, front: Direction, wx: Double, wz: Double): Pair<Double, Double> {
        val ax = wx - d.origin.x - 0.5
        val az = wz - d.origin.z - 0.5
        return when (front) {
            Direction.EAST -> az to -ax
            Direction.SOUTH -> -ax to -az
            Direction.WEST -> -az to ax
            else -> ax to az
        }
    }

    /** The world's x, z (a block's middle) of the drawing's [lx], [lz]. */
    private fun world(d: WorksDrawingPayload, front: Direction, lx: Double, lz: Double): Vec3 {
        val (dx, dz) = when (front) {
            Direction.EAST -> -lz to lx
            Direction.SOUTH -> -lx to -lz
            Direction.WEST -> lz to -lx
            else -> lx to lz
        }
        return Vec3(d.origin.x + 0.5 + dx, 0.0, d.origin.z + 0.5 + dz)
    }

    /** The works' stages along a track under its drawing: done gold, the one under way filling and pulsing, those to come faint. */
    private fun stages(g: GuiGraphicsExtractor, x: Int, y: Int, l: WorksLine, time: Float, mouseX: Int, mouseY: Int) {
        val used = Stage.entries.filter { s -> l.starts.size > s.ordinal + 1 && l.starts[s.ordinal] < l.starts[s.ordinal + 1] }
        if (used.isEmpty()) return
        val step = DRAW_W.toFloat() / used.size
        g.fill(x + (step / 2).roundToInt(), y + 3, x + DRAW_W - (step / 2).roundToInt(), y + 4, 0x50D9B46A)
        val done = l.state == WorksState.DONE.ordinal
        for ((i, s) in used.withIndex()) {
            val cx = x + (step * (i + 0.5f)).roundToInt()
            val from = l.starts[s.ordinal]
            val to = l.starts[s.ordinal + 1]
            val laid = if (done) to - from else (from until to).count { l.isDone(it) }
            val f = if (to <= from) 1f else laid.toFloat() / (to - from)
            val current = !done && l.stage == s.ordinal
            val color = when {
                f >= 1f -> Ui.GOLD
                current -> ARGB.color(0.6f + 0.4f * sin(time * 0.25f), Ui.GOLD_BRIGHT)
                f > 0f -> ARGB.color(0.8f, Ui.GOLD)
                else -> 0x60B4A88E
            }
            if (f >= 1f || current || f > 0f) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, cx - 2, y + 1, 5, 5, color)
            } else {
                g.fill(cx - 1, y + 2, cx + 2, y + 5, color)
            }
            if (current && f > 0f && f < 1f) {
                // Its share laid, filling under it
                g.fill(cx - 3, y + 7, cx - 3 + (7 * f).roundToInt(), y + 8, Ui.GREEN)
            }
            if (mouseX >= cx - 4 && mouseX <= cx + 4 && mouseY >= y && mouseY < y + 9) {
                val kind = kindOf(l.kind)
                val tip = mutableListOf<Component>(Component.translatable("gui.guildmark.works.stage.${s.key}"))
                val what = Component.translatableWithFallback("gui.guildmark.works.stage_of.${kind.id}.${s.key}", "")
                if (what.string.isNotEmpty()) tip += what
                tip += Component.translatable("gui.guildmark.works.stage.laid", laid, to - from)
                g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY)
            }
        }
    }

    /** The wagons on their way in with its materials, on a strip of road to the town's gate, each where it is now. */
    private fun road(g: GuiGraphicsExtractor, x: Int, y: Int, l: WorksLine, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        val state = WorksState.entries.getOrNull(l.state)
        if (state != WorksState.FUNDED && state != WorksState.BUILDING) return
        val w = DRAW_W
        g.fill(x, y + 9, x + w - 6, y + 10, ROAD)
        // The gate at the road's end
        g.fill(x + w - 6, y + 4, x + w - 5, y + 11, Ui.TEXT_FAINT)
        g.fill(x + w - 2, y + 4, x + w - 1, y + 11, Ui.TEXT_FAINT)
        g.fill(x + w - 6, y + 4, x + w - 1, y + 5, Ui.TEXT_FAINT)
        if (l.ships.isEmpty()) {
            val none = Component.translatable(if (l.coming.sum() > 0 || l.bill.sum() > l.here.sum() + l.used.sum()) "gui.guildmark.works.ships.none" else "gui.guildmark.works.ships.all_in")
            g.text(font, fit(none, w - 8), x, y + 13, Ui.TEXT_FAINT, false)
            return
        }
        for ((i, s) in l.ships.withIndex()) {
            val along = ease("w${s.caravan}").at(a)
            val wx = x + ((w - 14) * along).roundToInt()
            val bob = if (s.phase == WorksShipLine.ROLLING) (sin(time * 0.6f + i) * 0.6f).roundToInt() else 0
            val wy = y + 4 + bob - i % 2
            val body = when (s.phase) {
                WorksShipLine.STRUCK -> if ((time / 5).toInt() % 2 == 0) Ui.RED else ARGB.scaleRGB(Ui.RED, 0.6f)
                WorksShipLine.WAITING -> Ui.TEXT_FAINT
                else -> WAGON
            }
            // The wagon: its bed, its load, its wheels
            g.fill(wx, wy + 1, wx + 7, wy + 4, body)
            g.fill(wx + 1, wy - 1, wx + 6, wy + 1, ARGB.color(1f, 0xC9B38A))
            g.fill(wx + 1, wy + 4, wx + 2, wy + 5, 0xFF2A2018.toInt())
            g.fill(wx + 5, wy + 4, wx + 6, wy + 5, 0xFF2A2018.toInt())
            if (mouseX >= wx - 1 && mouseX <= wx + 8 && mouseY >= wy - 2 && mouseY <= wy + 6) {
                g.setComponentTooltipForNextFrame(font, ship(s), mouseX, mouseY)
            }
        }
        val first = l.ships.first()
        val due = Component.translatable("gui.guildmark.works.ships.next", first.road, clock(first.clock))
        g.text(font, fit(due, w), x, y + 13, if (first.phase == WorksShipLine.STRUCK) Ui.RED else Ui.TEXT_FAINT, false)
    }

    /** A wagon's tooltip: its road, how it fares, what it brings and when. */
    private fun ship(s: WorksShipLine): List<Component> {
        val out = mutableListOf<Component>(Component.translatable("gui.guildmark.works.ship.title", s.road))
        out += Component.translatable(
            when (s.phase) {
                WorksShipLine.STRUCK -> "gui.guildmark.works.ship.struck"
                WorksShipLine.WAITING -> "gui.guildmark.works.ship.waiting"
                else -> "gui.guildmark.works.ship.rolling"
            },
        )
        for ((i, n) in s.stuff.withIndex()) if (n > 0) out += Component.translatable("gui.guildmark.works.ship.carries", grouped(n), stuffName(i))
        val hours = (s.ticks + 999) / 1000
        out += when {
            s.ticks <= 0 -> Component.translatable("gui.guildmark.works.ship.due_now", clock(s.clock))
            hours <= 1 -> Component.translatable("gui.guildmark.works.ship.due_hour", clock(s.clock))
            else -> Component.translatable("gui.guildmark.works.ship.due", clock(s.clock), hours)
        }
        return out
    }

    /** Its materials: what's used (green), in the yard (gold) and on the wagons (pale), of its bill, a row each. */
    private fun materials(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, l: WorksLine, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        val rows = l.bill.indices.filter { l.bill[it] > 0 }
        if (rows.isEmpty()) return
        Ui.divider(g, x, y - 5, w, gem = false)
        g.text(font, Component.translatable("gui.guildmark.works.materials"), x, y, Ui.GOLD, false)
        val head = Component.translatable("gui.guildmark.works.materials.head")
        g.text(font, fit(head, w - 50), x + w - min(font.width(head), w - 50), y, Ui.TEXT_FAINT, false)
        for ((k, i) in rows.withIndex()) {
            val ry = y + 11 + k * MAT_ROW
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate(x.toFloat(), (ry - 1).toFloat())
            pose.scale(0.5f, 0.5f)
            g.item(STUFF_ICONS[i.coerceIn(0, STUFF_ICONS.size - 1)], 0, 0)
            pose.popMatrix()
            val bx = x + 11
            val bw = w - 11 - 44
            g.fill(bx, ry + 1, bx + bw, ry + 6, 0xC0100E12.toInt())
            val used = (bw * ease("u${l.id}.$i").at(a)).roundToInt()
            val here = (bw * ease("h${l.id}.$i").at(a)).roundToInt()
            val coming = (bw * ease("c${l.id}.$i").at(a)).roundToInt()
            if (coming > here) {
                // On the wagons: hatched
                for (px in bx + here until bx + coming) if ((px + (time / 4).toInt()) % 3 == 0) g.fill(px, ry + 1, px + 1, ry + 6, 0x70D9C9A0)
            }
            if (here > used) g.fill(bx + used, ry + 1, bx + here, ry + 6, ARGB.color(0.95f, Ui.GOLD))
            if (used > 0) g.fill(bx, ry + 1, bx + used, ry + 6, ARGB.color(0.95f, Ui.GREEN))
            val nUsed = l.used.getOrElse(i) { 0 }
            val nHere = l.here.getOrElse(i) { 0 }
            val nComing = l.coming.getOrElse(i) { 0 }
            // Run out: none of it in the yard, and the work wanting more of it
            val short = l.alarm && nHere <= 0 && nUsed < l.bill[i]
            val count = "${grouped(nUsed + nHere)}/${grouped(l.bill[i])}"
            g.text(font, count, x + w - font.width(count), ry, if (short && (time / 10).toInt() % 2 == 0) Ui.RED else Ui.TEXT_SOFT, false)
            if (mouseX >= x && mouseX < x + w && mouseY >= ry - 1 && mouseY < ry + MAT_ROW - 1) {
                val tip = mutableListOf<Component>(
                    stuffName(i),
                    Component.translatable("gui.guildmark.works.materials.tip", grouped(l.bill[i]), grouped(nUsed), grouped(nHere), grouped(nComing)),
                )
                val left = (l.bill[i] - nUsed - nHere - nComing).coerceAtLeast(0)
                if (left > 0) tip += Component.translatable("gui.guildmark.works.materials.to_send", grouped(left))
                for (s in l.ships) if (s.stuff.getOrElse(i) { 0 } > 0) tip += Component.translatable("gui.guildmark.works.materials.on_wagon", grouped(s.stuff[i]), s.road, clock(s.clock))
                if (l.state == WorksState.FUNDED.ordinal || l.state == WorksState.BUILDING.ordinal) tip += Component.translatable("gui.guildmark.works.materials.bring")
                g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY)
            }
        }
    }

    // ---- The pledges page ----

    private fun drawPledges(g: GuiGraphicsExtractor, x: Int, y: Int, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        val l = line()
        if (l == null) {
            g.textWithWordWrap(font, Component.translatable("gui.guildmark.works.pledges.not_yet"), x, y + 4, CONTENT_W, Ui.TEXT_FAINT, false)
            return
        }
        val w = CONTENT_W
        val state = WorksState.entries.getOrNull(l.state) ?: WorksState.WANTED
        // Its price, and its parts
        g.text(font, Component.translatable("gui.guildmark.works.price", grouped(l.price)), x, y, Ui.GOLD, false)
        val togo = (l.price - l.pledged).coerceAtLeast(0)
        val right = if (state == WorksState.WANTED) Component.translatable("gui.guildmark.works.to_go", grouped(togo)) else Component.translatable("gui.guildmark.works.paid_for")
        g.text(font, right, x + w - font.width(right), y, if (state == WorksState.WANTED) Ui.TEXT_SOFT else Ui.GREEN, false)
        val total = l.parts.sumOf { it.second }.coerceAtLeast(1)
        var px = x
        for ((k, part) in l.parts.withIndex()) {
            val pw = if (k == l.parts.size - 1) x + w - px else (w * part.second / total)
            val color = PART_COLORS[k % PART_COLORS.size]
            g.fill(px, y + 12, px + pw - 1, y + 17, color)
            if (mouseX >= px && mouseX < px + pw && mouseY >= y + 11 && mouseY < y + 18) {
                g.setComponentTooltipForNextFrame(
                    font, listOf(Component.translatable("gui.guildmark.works.part.${part.first}"), Component.translatable("gui.guildmark.works.part.marks", grouped(part.second)), Component.translatable("gui.guildmark.works.part.${part.first}.tip")),
                    mouseX, mouseY,
                )
            }
            px += pw
        }
        // The parts' key, with their Marks if there's room
        val whole = l.parts.map { Component.translatable("gui.guildmark.works.part.short", Component.translatable("gui.guildmark.works.part.${it.first}"), grouped(it.second)) }
        val labels: List<Component> = if (whole.sumOf { font.width(it) + 15 } <= w) whole else l.parts.map { Component.translatable("gui.guildmark.works.part.${it.first}") }
        var kx = x
        for ((k, label) in labels.withIndex()) {
            g.fill(kx, y + 22, kx + 5, y + 27, PART_COLORS[k % PART_COLORS.size])
            g.text(font, label, kx + 7, y + 21, Ui.TEXT_FAINT, false)
            kx += 7 + font.width(label) + 8
        }

        // The Marks pledged, the reader's share bright in it, and the box they drop into
        val by = y + 36
        val bw = w - 14
        val f = ease("f${l.id}").at(a)
        val mine = ease("y${l.id}").at(a)
        g.fill(x, by, x + bw, by + 9, 0xC0100E12.toInt())
        g.outline(x - 1, by - 1, bw + 2, 11, 0x60D9B46A)
        val filled = (bw * f).roundToInt()
        if (filled > 0) {
            g.fillGradient(x, by, x + filled, by + 9, ARGB.color(1f, ARGB.srgbLerp(0.25f, Ui.GOLD, -1)), ARGB.color(1f, ARGB.scaleRGB(Ui.GOLD, 0.62f)))
            val m = (bw * mine).roundToInt().coerceAtMost(filled)
            if (m > 0) g.fill(x + filled - m, by, x + filled, by + 9, ARGB.color(0.55f, Ui.GOLD_BRIGHT))
            val period = bw + 24
            val at = ((time * 1.6f) % period).roundToInt() - 12
            val g0 = max(x, x + at)
            val g1 = min(x + filled, x + at + 10)
            if (g1 > g0) g.fill(g0, by, g1, by + 9, 0x30FFFFFF)
        }
        // The box at the bar's end, and the coins dropping into it
        val boxX = x + bw + 3
        g.fill(boxX, by + 2, boxX + 10, by + 9, 0xFF5A3A22.toInt())
        g.fill(boxX, by + 2, boxX + 10, by + 3, 0xFF8A6A42.toInt())
        for (c in coins) {
            val t = time - c.at
            if (t < 0f) continue
            val k = (t / COIN_TICKS).coerceIn(0f, 1f)
            val cx = boxX + 5f + c.dx * (1f - k)
            val cy = by - 22f + 24f * k * k
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate(cx, cy)
            pose.rotate(c.spin * t)
            g.blitSprite(RenderPipelines.GUI_TEXTURED, COIN, -3, -3, 7, 7, ARGB.white(if (k > 0.85f) (1f - k) / 0.15f else 1f))
            pose.popMatrix()
        }
        for (r in rises) {
            val k = (time - r.at) / RISE_TICKS
            if (k !in 0f..1f) continue
            fadedText(g, Component.literal(r.text).visualOrderText, x + filled - font.width(r.text) / 2, by - 4 - (k * 10).roundToInt(), Ui.GREEN, 1f - k * k, true)
        }
        if (state != WorksState.WANTED) {
            // Paid for: the stamp across it, pressed in as it's paid
            val since = if (noticeTone == WorksLedger.PAID) (time - noticeAt) / 8f else 1f
            val k = since.coerceIn(0f, 1f)
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate((x + bw / 2).toFloat(), (by + 5).toFloat())
            pose.rotate(-0.12f)
            val s = 1f + (1f - k) * 0.8f
            pose.scale(s, s)
            val stamp = Ui.heading(Component.translatable("gui.guildmark.works.stamp"))
            fadedText(g, stamp.visualOrderText, -font.width(stamp) / 2, -4, Ui.RED, 0.85f * k)
            pose.popMatrix()
        }
        val pledged = if (l.pledges.size == 1) Component.translatable("gui.guildmark.works.pledged.one", grouped(l.pledged))
        else Component.translatable("gui.guildmark.works.pledged", grouped(l.pledged), l.pledges.size)
        g.text(font, pledged, x, by + 13, Ui.TEXT_SOFT, false)
        if (l.mine > 0) {
            val yours = Component.translatable("gui.guildmark.works.yours", grouped(l.mine))
            g.text(font, yours, x + w - font.width(yours), by + 13, Ui.GOLD_BRIGHT, false)
        }

        // Every pledger, the patron crowned and the reader's own bright
        val ly = by + 26
        Ui.divider(g, x, ly - 3, w, gem = false)
        if (l.pledges.isEmpty()) {
            g.textWithWordWrap(font, Component.translatable(if (kindOf(l.kind) == WorksKind.STALL) "gui.guildmark.works.pledges.none_stall" else "gui.guildmark.works.pledges.none"), x, ly + 3, w, Ui.TEXT_FAINT, false)
            return
        }
        val shown = l.pledges.withIndex().drop(pledgeScroll).take(PLEDGE_ROWS)
        for ((k, iv) in shown.withIndex()) {
            val (i, p) = iv
            val ry = ly + 2 + k * 10
            if (k % 2 == 1) g.fill(x - 2, ry - 1, x + w + 2, ry + 9, 0x14D9B46A)
            val patron = i == l.patron
            if (patron) g.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, x, ry + 1, 5, 5, ARGB.color(0.7f + 0.3f * sin(time * 0.2f), Ui.GOLD_BRIGHT))
            val name = if (patron) Component.translatable("gui.guildmark.works.patron", p.name) else Component.literal(p.name)
            g.text(font, fit(name, w - 90), x + 8, ry, if (p.mine) Ui.GOLD_BRIGHT else Ui.TEXT, false)
            val marks = Component.translatable("gui.guildmark.works.marks", grouped(p.marks))
            g.text(font, marks, x + w - 44 - font.width(marks), ry, Ui.GOLD, false)
            val day = if (p.day >= 0) Component.translatable("gui.guildmark.works.day", p.day) else Component.empty()
            g.text(font, day, x + w - font.width(day), ry, Ui.TEXT_FAINT, false)
        }
        if (l.pledges.size > PLEDGE_ROWS) {
            val more = Component.translatable("gui.guildmark.works.scroll", pledgeScroll + 1, min(l.pledges.size, pledgeScroll + PLEDGE_ROWS), l.pledges.size)
            g.text(font, more, x + w - font.width(more), ly + 2 + PLEDGE_ROWS * 10, Ui.TEXT_FAINT, false)
        }
    }

    // ---- The fortunes page ----

    private fun drawFortunes(g: GuiGraphicsExtractor, x: Int, y: Int, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        val f = data.fortune
        val w = CONTENT_W
        if (!f.on) {
            g.textWithWordWrap(font, Component.translatable("gui.guildmark.works.fortune.off"), x, y + 4, w, Ui.TEXT_FAINT, false)
            return
        }
        val now = Component.translatable("board.guildmark.slip.fortune.${times(f.times)}")
        g.text(font, now, x, y, bandColor(f.times), false)
        val score = Component.translatable("gui.guildmark.works.fortune.score", signed(f.score))
        g.text(font, score, x + font.width(now) + 6, y, Ui.TEXT_SOFT, false)
        if (f.since >= 0) {
            val since = Component.translatable("gui.guildmark.works.fortune.since", Component.translatable("board.guildmark.slip.fortune.${times(f.from)}"), f.since)
            g.text(font, fit(since, w - font.width(now) - font.width(score) - 14), x + w - min(font.width(since), w - font.width(now) - font.width(score) - 14), y, Ui.TEXT_FAINT, false)
        }
        chart(g, x, y + 12, w, CHART_H, f, time, a, mouseX, mouseY)
        // Today's reasons, biggest first
        val ry0 = y + 16 + CHART_H
        g.text(font, Component.translatable("gui.guildmark.works.fortune.reasons"), x, ry0, Ui.GOLD, false)
        if (f.reasons.isEmpty()) {
            g.text(font, Component.translatable("gui.guildmark.works.fortune.no_reasons"), x, ry0 + 11, Ui.TEXT_FAINT, false)
            return
        }
        for ((k, r) in f.reasons.drop(reasonScroll).take(REASON_ROWS).withIndex()) {
            val ry = ry0 + 11 + k * 10
            val pts = signed(r.points)
            g.text(font, pts, x + 14 - font.width(pts), ry, if (r.points > 0) Ui.GREEN else if (r.points < 0) Ui.RED else Ui.TEXT_FAINT, false)
            val left = when {
                r.left < 0 -> Component.translatable("gui.guildmark.works.fortune.while")
                r.left == 0 -> Component.translatable("gui.guildmark.works.fortune.today")
                r.left == 1 -> Component.translatable("gui.guildmark.works.fortune.one_day")
                else -> Component.translatable("gui.guildmark.works.fortune.days", r.left)
            }
            val lw = font.width(left)
            if (r.eased) g.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, x + 17, ry + 1, 5, 5, Ui.GOLD_BRIGHT)
            val tx = x + 18 + if (r.eased) 7 else 0
            g.text(font, fit(r.text, w - (tx - x) - lw - 6), tx, ry, if (r.eased) Ui.GOLD else Ui.TEXT, false)
            g.text(font, left, x + w - lw, ry, Ui.TEXT_FAINT, false)
            if (mouseX >= x && mouseX < x + w && mouseY >= ry - 1 && mouseY < ry + 9) {
                g.setTooltipForNextFrame(font, font.split(Component.empty().append(r.label).append(": ").append(r.text), TIP_W), mouseX, mouseY)
            }
        }
        if (f.reasons.size > REASON_ROWS) {
            val more = Component.translatable("gui.guildmark.works.scroll", reasonScroll + 1, min(f.reasons.size, reasonScroll + REASON_ROWS), f.reasons.size)
            g.text(font, more, x + w - font.width(more), ry0, Ui.TEXT_FAINT, false)
        }
    }

    /**
     * The last days' score, oldest at the left: the times' bands shaded behind it (the score each band covers), the
     * line drawn in as the page opens, each day's point in its times' colour, today's pulsing, and a mark over each day
     * the town's works eased something.
     */
    private fun chart(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, f: io.github.vaspyyy.guildmark.network.WorksFortuneView, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        fun sy(score: Float): Float = y + (HIGHEST - score.coerceIn(LOWEST, HIGHEST)) / (HIGHEST - LOWEST) * h
        // The bands, each from half a point beyond its edge
        for ((i, band) in BANDS.withIndex()) {
            val top = sy(band.second + 0.5f).coerceAtLeast(y.toFloat()).roundToInt()
            val bottom = sy(band.first - 0.5f).coerceAtMost((y + h).toFloat()).roundToInt()
            val color = bandColor(i)
            g.fill(x, top, x + w, bottom, ARGB.color(if (i == f.times) 0.22f else 0.1f, color))
            val label = Component.translatable("board.guildmark.slip.fortune.${times(i)}")
            if (bottom - top >= 9) g.text(font, label, x + w - 2 - font.width(label), top + (bottom - top - 8) / 2, ARGB.color(0.5f, color), false)
        }
        g.outline(x - 1, y - 1, w + 2, h + 2, 0x40D9B46A)
        // Steady's line
        g.fill(x, sy(0f).roundToInt(), x + w, sy(0f).roundToInt() + 1, 0x30E8DEC6)
        val span = WorksChart.DAYS - 1
        val first = f.today - span
        val days = f.days.filter { it.day in first..f.today }
        if (days.isEmpty()) {
            g.centeredText(font, Component.translatable("gui.guildmark.works.fortune.no_days"), x + w / 2, y + h / 2 - 4, Ui.TEXT_FAINT)
            return
        }
        fun dx(day: Long): Float = x + 6f + (day - first).toFloat() / span * (w - 60f)
        val reveal = ease("chart").at(a)
        val pose = g.pose()
        val last = x + 6f + reveal * (w - 60f)
        // The line, a segment at a time, drawn in from the left
        for (k in 1 until days.size) {
            val p0 = days[k - 1]
            val p1 = days[k]
            val x0 = dx(p0.day)
            val x1 = dx(p1.day)
            if (x0 > last) break
            val y0 = sy(p0.score.toFloat())
            val y1 = sy(p1.score.toFloat())
            val t = ((last - x0) / max(0.01f, x1 - x0)).coerceIn(0f, 1f)
            val ex = x0 + (x1 - x0) * t
            val ey = y0 + (y1 - y0) * t
            val len = sqrt((ex - x0) * (ex - x0) + (ey - y0) * (ey - y0))
            if (len < 0.1f) continue
            pose.pushMatrix()
            pose.translate(x0, y0)
            pose.rotate(atan2(ey - y0, ex - x0))
            g.fill(0, -1, len.roundToInt().coerceAtLeast(1), 1, Ui.GOLD)
            pose.popMatrix()
        }
        // Each day's point, today's pulsing; and its tooltip
        for (d in days) {
            val px = dx(d.day)
            if (px > last + 1f) continue
            val py = sy(d.score.toFloat())
            val today = d.day == f.today
            val size = if (today) 5 + (sin(time * 0.25f) * 1.2f).roundToInt().coerceAtLeast(0) else 5
            g.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND, (px - size / 2f).roundToInt(), (py - size / 2f).roundToInt(), size, size, ARGB.color(1f, bandColor(d.times)))
            if (abs(mouseX - px) <= 3 && abs(mouseY - py) <= 4) {
                g.setComponentTooltipForNextFrame(
                    font,
                    listOf(
                        Component.translatable("gui.guildmark.works.fortune.day", d.day),
                        Component.translatable("gui.guildmark.works.fortune.day_score", signed(d.score), Component.translatable("board.guildmark.slip.fortune.${times(d.times)}")),
                    ),
                    mouseX, mouseY,
                )
            }
        }
        // The days the works eased something: a mark over the day
        for (e in f.eased) {
            if (e.day < first || e.day > f.today) continue
            val px = dx(e.day).roundToInt()
            if (px > last + 1f) continue
            for (yy in y until y + h step 3) g.fill(px, yy, px + 1, yy + 1, 0x60F2D58C)
            g.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.DIAMOND_LARGE, px - 3, y - 3, 7, 7, ARGB.color(0.8f + 0.2f * sin(time * 0.2f + px), Ui.GOLD_BRIGHT))
            if (abs(mouseX - px) <= 3 && mouseY >= y - 4 && mouseY <= y + 4) {
                g.setComponentTooltipForNextFrame(font, listOf(Component.translatable("gui.guildmark.works.fortune.day", e.day), e.text), mouseX, mouseY)
            }
        }
    }

    // ---- The regard page ----

    private fun drawRegard(g: GuiGraphicsExtractor, x: Int, y: Int, time: Float, a: Float, mouseX: Int, mouseY: Int) {
        val r = data.regard
        val w = CONTENT_W
        val town = if (data.town.isEmpty()) Component.translatable("gui.guildmark.works.the_town") else Component.literal(data.town)
        val st = if (r.standing.string.isNotEmpty()) Component.translatable("gui.guildmark.works.regard.standing", r.standing) else null
        val room = w - (st?.let { font.width(it) + 6 } ?: 0)
        g.text(font, fit(Component.translatable("gui.guildmark.works.regard.title", town), room), x, y, Ui.GOLD, false)
        st?.let { g.text(font, it, x + w - font.width(it), y, Ui.TEXT_SOFT, false) }
        if (!r.on) {
            g.textWithWordWrap(font, Component.translatable("gui.guildmark.works.regard.off"), x, y + 16, w, Ui.TEXT_FAINT, false)
            return
        }
        if (r.counts.isEmpty()) {
            val dots = ".".repeat(1 + (ticks / 8) % 3)
            g.text(font, Component.translatable("gui.guildmark.works.regard.asking").copy().append(dots), x, y + 16, Ui.TEXT_FAINT, false)
            return
        }
        // Everyone in town, by how well they know the reader, in one strip
        val order = REGARD_ORDER
        val shown = FloatArray(order.size) { ease("r${order[it]}").at(a).coerceAtLeast(0f) }
        val total = max(1f, shown.sum())
        var sx = x.toFloat()
        val sy = y + 13
        for ((k, f) in order.withIndex()) {
            val sw = shown[k] / total * w
            if (sw >= 0.5f) {
                g.fill(sx.roundToInt(), sy, (sx + sw).roundToInt(), sy + 8, ARGB.color(1f, REGARD_COLORS[f]))
                if (mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + 8) {
                    g.setTooltipForNextFrame(font, Component.translatable("gui.guildmark.works.regard.strip.$f", r.counts.getOrElse(f) { 0 }), mouseX, mouseY)
                }
            }
            sx += sw
        }
        g.outline(x - 1, sy - 1, w + 2, 10, 0x40D9B46A)
        // The three that count: friends, acquaintances, the wary and worse, counting up
        val friends = shown[0]
        val acquainted = shown[1]
        val wary = shown[order.indexOf(WARY)] + shown[order.indexOf(HOSTILE)]
        val cw = w / 3
        big(g, x, sy + 14, cw, friends, Component.translatable("gui.guildmark.works.regard.friends"), REGARD_COLORS[FRIEND])
        big(g, x + cw, sy + 14, cw, acquainted, Component.translatable("gui.guildmark.works.regard.acquainted"), REGARD_COLORS[ACQUAINTED])
        big(g, x + cw * 2, sy + 14, cw, wary, Component.translatable("gui.guildmark.works.regard.wary"), REGARD_COLORS[WARY])
        val known = r.counts.getOrElse(MET) { 0 } + r.counts.getOrElse(HEARD_OF) { 0 }
        val rest = Component.translatable("gui.guildmark.works.regard.rest", known, r.counts.getOrElse(STRANGER) { 0 })
        g.text(font, fit(rest, w), x, sy + 40, Ui.TEXT_FAINT, false)
        // For what
        var ly = sy + 54
        g.text(font, Component.translatable("gui.guildmark.works.regard.warms"), x, ly, Ui.GREEN, false)
        ly += 10
        if (r.warm.isEmpty()) {
            g.text(font, Component.translatable("gui.guildmark.works.regard.warms_none"), x + 8, ly, Ui.TEXT_FAINT, false)
            ly += 10
        }
        for (c in r.warm.take(4)) {
            reason(g, x, ly, w, c.n, c.text, Ui.GREEN)
            ly += 10
        }
        ly += 3
        g.text(font, Component.translatable("gui.guildmark.works.regard.cools"), x, ly, Ui.RED, false)
        ly += 10
        if (r.cool.isEmpty()) {
            g.text(font, Component.translatable("gui.guildmark.works.regard.cools_none"), x + 8, ly, Ui.TEXT_FAINT, false)
        }
        for (c in r.cool.take(max(1, (y + CONTENT_H - ly) / 10))) {
            reason(g, x, ly, w, c.n, c.text, Ui.RED)
            ly += 10
        }
    }

    /** A count in the heading's capitals over its label, centred in [w]. */
    private fun big(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, n: Float, label: Component, color: Int) {
        val shown = Ui.heading(Component.literal(n.roundToInt().toString()))
        g.text(font, shown, x + (w - font.width(shown)) / 2, y + Ui.HEADING_RISE, ARGB.color(1f, color), true)
        g.centeredText(font, label, x + w / 2, y + 12, Ui.TEXT_SOFT)
    }

    private fun reason(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, n: Int, text: Component, color: Int) {
        val count = n.toString()
        g.text(font, count, x + 18 - font.width(count), y, color, false)
        g.text(font, fit(text, w - 24), x + 24, y, Ui.TEXT, false)
    }

    // ---- The notice ----

    /** What came of the reader's last ask, over the foot of the page a while, fading. */
    private fun drawNotice(g: GuiGraphicsExtractor, time: Float) {
        val age = time - noticeAt
        if (age > NOTICE_TICKS || notice.string.isEmpty()) return
        val k = when {
            age < 4f -> age / 4f
            age > NOTICE_TICKS - 20 -> (NOTICE_TICKS - age) / 20f
            else -> 1f
        }.coerceIn(0f, 1f)
        val color = when (noticeTone) {
            WorksLedger.REFUSED -> Ui.RED
            WorksLedger.WITHDRAWN -> Ui.TEXT_SOFT
            else -> Ui.GOLD_BRIGHT
        }
        val y = panelY + PANEL_H - 38
        val text = fit(notice, CONTENT_W - 8)
        val tw = font.width(text)
        val cx = panelX + CONTENT_X + CONTENT_W / 2
        g.fill(cx - tw / 2 - 4, y - 2, cx + tw / 2 + 4, y + 10, ARGB.color(0.75f * k, 0x0C0A08))
        fadedText(g, text, cx - tw / 2, y, color, k)
    }

    // ---- Words ----

    private fun kindOf(ordinal: Int): WorksKind = WorksKind.entries.getOrElse(ordinal) { WorksKind.OVEN }

    /** A works' name, with its road (a tower's). */
    private fun name(kind: WorksKind, road: Component): Component =
        if (road.string.isNotEmpty()) Component.translatable("gui.guildmark.works.name.road", Component.translatable(kind.key), road) else Component.translatable(kind.key)

    /** What a works is at, by [stage] (a Stage ordinal; -1 before its first step). */
    private fun stageName(state: WorksState, stage: Int): Component = when {
        state == WorksState.WANTED -> Component.translatable("gui.guildmark.works.state.wanted")
        state == WorksState.DONE -> Component.translatable("gui.guildmark.works.state.done")
        stage < 0 -> Component.translatable(if (state == WorksState.FUNDED) "gui.guildmark.works.state.funded" else "gui.guildmark.works.state.begun")
        else -> Component.translatable("gui.guildmark.works.stage.${Stage.entries[stage.coerceIn(0, Stage.entries.size - 1)].key}")
    }

    private fun names(names: List<String>): Component = Component.literal(names.joinToString(", "))

    private fun stuffName(i: Int): Component = Component.translatable("gui.guildmark.homestead.stuff.${Stuff.PAID.getOrElse(i) { Stuff.TIMBER }.key}")

    private fun times(ordinal: Int): String = TIMES.getOrElse(ordinal) { "steady" }

    private fun bandColor(times: Int): Int = BAND_COLORS.getOrElse(times) { BAND_COLORS[2] }

    private fun signed(n: Int): String = if (n > 0) "+$n" else n.toString()

    private fun percent(laid: Int, total: Int): String = if (total <= 0) "0" else (laid * 100 / total).toString()

    private fun clock(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)

    private fun grouped(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    private fun fit(text: Component, width: Int): FormattedCharSequence = Language.getInstance().getVisualOrder(font.substrByWidth(text, max(0, width)))

    private fun half(days: Double): String {
        val h = max(0.5, (days * 2).roundToInt() / 2.0)
        return if (abs(h - h.toInt()) < 0.01) h.toInt().toString() else String.format(Locale.ROOT, "%.1f", h)
    }

    /** A bar [f] full, with a slow gleam along the filled part. */
    private fun bar(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, f: Float, color: Int, time: Float) {
        g.fill(x, y, x + w, y + h, 0xC0100E12.toInt())
        g.outline(x - 1, y - 1, w + 2, h + 2, 0x60D9B46A)
        val filled = (w * f.coerceIn(0f, 1f)).roundToInt()
        if (filled <= 0) return
        g.fillGradient(x, y, x + filled, y + h, ARGB.color(1f, ARGB.srgbLerp(0.25f, color, -1)), ARGB.color(1f, ARGB.scaleRGB(color, 0.62f)))
        val period = w + 24
        val at = ((time * 1.6f) % period).roundToInt() - 12
        val g0 = max(x, x + at)
        val g1 = min(x + filled, x + at + 10)
        if (g1 > g0) g.fill(g0, y, g1, y + h, 0x30FFFFFF)
    }

    companion object {
        private const val PANEL_W = 380
        private const val PANEL_H = 244
        private const val LIST_X = 10
        private const val LIST_W = 96
        private const val CONTENT_X = 114
        private const val CONTENT_W = 256
        private const val CONTENT_Y = 56
        private const val CONTENT_H = 156
        private const val TAB_Y = 40
        private const val ROW_H = 22
        private const val LABEL_H = 10
        private const val LIST_ROWS = 6
        private const val PLEDGE_ROWS = 8
        private const val REASON_ROWS = 6
        private const val MAT_ROW = 11
        private const val DRAW_W = 110
        private const val DRAW_H = 104
        private const val CHART_H = 60
        private const val TIP_W = 220
        private const val REACH = 24.0
        private const val CONFIRM_TICKS = 80
        private const val COIN_TICKS = 16
        private const val RISE_TICKS = 40
        private const val POP_TICKS = 8f
        private const val NOTICE_TICKS = 90

        /** The list's not-yets, as [NOT_YET] less their index. */
        private const val NOT_YET = -100

        /** The pages and their tabs. */
        private const val WORKS = WorksLedger.WORKS_PAGE
        private const val PLEDGES = WorksLedger.PLEDGES_PAGE
        private const val FORTUNES = WorksLedger.FORTUNES_PAGE
        private const val REGARD = WorksLedger.REGARD_PAGE
        private const val PAGES = 4
        private val TABS = listOf("gui.guildmark.works.tab.works", "gui.guildmark.works.tab.pledges", "gui.guildmark.works.tab.fortunes", "gui.guildmark.works.tab.regard")

        /** The pledge buttons' amounts. */
        private val AMOUNTS = listOf(1, 10, 50, WorksLedger.REST)

        /** The score's bounds and the times' bands (Fortunes.called): each one's lowest and highest score. */
        private const val LOWEST = -10f
        private const val HIGHEST = 10f
        private val BANDS = listOf(-10 to -6, -5 to -2, -1 to 2, 3 to 5, 6 to 10)
        private val TIMES = listOf("hard", "lean", "steady", "good", "thriving")
        private val BAND_COLORS = intArrayOf(0xFFB0503C.toInt(), 0xFFC98F45.toInt(), 0xFFB4A88E.toInt(), 0xFF8FCB7A.toInt(), 0xFFF2D58C.toInt())

        /** Familiarity's ordinals, and the strip's order and colours. */
        private const val STRANGER = 0
        private const val HEARD_OF = 1
        private const val MET = 2
        private const val ACQUAINTED = 3
        private const val FRIEND = 4
        private const val WARY = 5
        private const val HOSTILE = 6
        private val REGARD_ORDER = intArrayOf(FRIEND, ACQUAINTED, MET, HEARD_OF, STRANGER, WARY, HOSTILE)
        private val REGARD_COLORS = intArrayOf(
            0xFF3A3530.toInt(), 0xFF6F7F9A.toInt(), 0xFF8EA6C8.toInt(), 0xFF8FCB7A.toInt(), 0xFFF2D58C.toInt(), 0xFFE0913A.toInt(), 0xFFD0573F.toInt(),
        )

        /** The price's parts' colours: the wages, the materials, a granary's filling, the pitch. */
        private val PART_COLORS = intArrayOf(0xFFD9B46A.toInt(), 0xFF9A7A52.toInt(), 0xFFC9B060.toInt(), 0xFF86A9E6.toInt())

        /** The drawing's paper, its grid, its pencil, the ground, the scaffolding; the road and its wagons. */
        private val PAPER = 0xFFE6D9B8.toInt()
        private const val GRID = 0x1A5A4A32
        private const val INK = 0x8C4A3C2A.toInt()
        private val GROUND = 0xFF6B5034.toInt()
        private val SCAFFOLD_COLOR = 0xD0C8A060.toInt()
        private val ROAD = 0xFF6B5A44.toInt()
        private val WAGON = 0xFF8A5A2E.toInt()

        /** Each kind's icon in the list: the oven's bread, the granary's wheat, the great granary's bales, the tower's glass, a stall's barrel. */
        private val ICONS = listOf(
            ItemStack(Items.BREAD), ItemStack(Items.WHEAT), ItemStack(Items.HAY_BLOCK), ItemStack(Items.SPYGLASS), ItemStack(Items.BARREL),
        )

        private val STUFF_ICONS = listOf(
            ItemStack(Items.OAK_PLANKS), ItemStack(Items.COBBLESTONE), ItemStack(Items.GLASS), ItemStack(Items.IRON_INGOT),
            ItemStack(Items.WOOL.pick(DyeColor.WHITE)),
        )

        private fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

        private val COIN = sprite("compass/lost_marks")
        private val POST = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, WorksArt.POST_TEXTURE)
    }
}

/** The chart's span: the fortunes' days (Fortunes.DAYS). */
private object WorksChart {
    const val DAYS = 14
}
