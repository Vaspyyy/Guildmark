package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.network.CarriersBuyPayload
import io.github.vaspyyy.guildmark.network.CarriersQueryPayload
import io.github.vaspyyy.guildmark.network.CarriersRow
import io.github.vaspyyy.guildmark.network.CarriersViewPayload
import io.github.vaspyyy.guildmark.network.MarketView
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.trade.Wares
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.max
import kotlin.math.sin

/**
 * The client's side of the Carriers section: the list the server last sent, and how the last press on a row went. The section is a
 * [MarketPanel], so the market screen asks it how tall it is, draws it and passes it the mouse; what it knows of the server comes
 * only in a [CarriersViewPayload], which this keeps.
 */
object CarriersClient {
    /** The server's answer to a press on [row]: it was [bought], or turned down for [refused] (a lang key). [at] is when the section first drew it, in the screen's ticks. */
    class Answer(val row: String, val bought: Boolean, val refused: String) {
        var at = Float.NaN
    }

    /** The list as the server last sent it, or null before it has (the screen is drawn from the wares registered here until then, with every row waiting). */
    var rows: List<CarriersRow>? = null
        private set

    /** The last press's answer, while it is still on show. */
    var answer: Answer? = null
        private set

    /** A new screen at the hall's counter: what was kept of the last one goes, and the list is asked for. */
    fun opened() {
        rows = null
        answer = null
        ask()
    }

    /** Asks the server for the list (it answers at most every half second). */
    fun ask() {
        if (Minecraft.getInstance().connection != null) ClientPacketDistributor.sendToServer(CarriersQueryPayload)
    }

    /** Presses the row [id]: the server decides it and answers. */
    fun buy(id: String) {
        if (Minecraft.getInstance().connection != null) ClientPacketDistributor.sendToServer(CarriersBuyPayload(id))
    }

    fun receive(payload: CarriersViewPayload) {
        rows = payload.rows
        if (payload.row.isNotEmpty()) answer = Answer(payload.row, payload.bought, payload.refused)
    }
}

/**
 * The Carriers section at the market hall's counter (living-world/trader/design.md, Part 3; build part D1): what the hall sells a
 * trader to carry their goods in, a row each from the hall's [Wares]. A row is the ware's icon, its name and one line, and a button
 * with its price; it is greyed, with the reason under its name, while something stands in its way (the rung not reached, a Warrant not
 * held, carrying switched off), and its button is dimmed while the purse is short. A press asks the server to buy it, and the
 * merchant does it in front of the player (the counter scenes, `trade/carry/CounterScenes`); what the screen shows is the row
 * flashing, the price floating up from its button, or a line in red under the name saying why not.
 *
 * It is shown on the hall's screen only. Until the server has answered the rows are drawn from the wares registered here, waiting, so
 * the list does not jump when the answer comes.
 */
object CarriersPanel : MarketPanel {
    private const val HEADING = 16
    private const val ROW = 30
    private const val GAP = 3
    private const val BUTTON = 13

    /** Ticks between two askings for the list while the section is on show, and how long a refusal stays under a row's name. */
    private const val REFRESH = 100
    private const val NOTE = 80f

    /** Wares whose icon is not the item of the same name. */
    private val ICONS: Map<String, ItemStack> by lazy {
        mapOf("pack_donkey" to ItemStack(Items.LEAD)) + (1..3).associate { "hired_guard_$it" to ItemStack(Items.IRON_SPEAR) }
    }

    private val icons = HashMap<String, ItemStack>()
    private val mark by lazy { ItemStack(ModItems.GUILD_MARK.get()) }

    override fun shownAt(view: MarketView): Boolean {
        if (view.shop != Shop.HALL.id) return false
        CarriersClient.opened()
        return true
    }

    private fun list(): List<CarriersRow> = CarriersClient.rows ?: Wares.all().map { CarriersRow(it.id, it.price, "") }

    override fun height(view: MarketView): Int {
        val n = list().size
        return if (n == 0) 0 else HEADING + n * (ROW + GAP)
    }

    // ---- Drawing ----

    override fun render(graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, y: Int, mouseX: Int, mouseY: Int, t: Float) {
        if (frame.ticks > 0 && frame.ticks % REFRESH == 0) CarriersClient.ask()
        CarriersClient.answer?.let { if (it.at.isNaN()) it.at = t }
        val head = ease(Mth.clamp((t - 2f) / 6f, 0f, 1f))
        Ui.headingAt(graphics, frame.font, Component.translatable("gui.guildmark.trade.carriers"), frame.x + 2, y + 2, fade(Ui.GOLD, head))
        graphics.fill(frame.x, y + HEADING - 3, frame.x + frame.width, y + HEADING - 2, fade(0x40D9B46A, head))
        for ((i, row) in list().withIndex()) drawRow(graphics, frame, row, i, y + HEADING + i * (ROW + GAP), mouseX, mouseY, t)
    }

    private fun drawRow(graphics: GuiGraphicsExtractor, frame: MarketPanel.Frame, row: CarriersRow, i: Int, y: Int, mouseX: Int, mouseY: Int, t: Float) {
        val k = ease(Mth.clamp((t - 3f - i * 1.5f) / 6f, 0f, 1f))
        if (k <= 0f || y + ROW <= frame.top || y >= frame.bottom) return
        val font = frame.font
        val waiting = CarriersClient.rows == null
        val gated = row.gate.isNotEmpty()
        val short = frame.view.marks < row.price
        val ok = !waiting && !gated && !short
        val answer = CarriersClient.answer?.takeIf { it.row == row.id && !it.at.isNaN() }
        val since = if (answer == null) Float.MAX_VALUE else t - answer.at
        val flash = if (answer?.bought == true) bump(since, 22f) else 0f
        val refused = answer?.refused?.isNotEmpty() == true && since < NOTE
        val x = frame.x
        val bx = x + frame.width - frame.buttonWidth
        val by = y + (ROW - BUTTON) / 2
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - k) * 10f, 0f)

        val over = mouseY >= max(y, frame.top) && mouseY < minOf(y + ROW, frame.bottom) && mouseX >= x && mouseX < x + frame.width
        if (over && !waiting && !gated) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x - 2, y, frame.width + 4, ROW, ARGB.white(0.5f * k))
        if (flash > 0f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.CHOICE_GLOW, x - 2, y, frame.width + 4, ROW, ARGB.white(flash * k))

        // The ware: its icon in a slot, hopping when it has just been bought
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.SLOT, x, y + 2, 18, 18, ARGB.white(k))
        if (k > 0.3f) {
            val hop = if (flash > 0f) -Math.round(sin((1f - flash) * Math.PI.toFloat()) * 3f) else 0
            graphics.item(iconOf(row.id), x + 1, y + 3 + hop)
            if (gated || waiting) graphics.fill(x + 1, y + 3, x + 17, y + 19, 0x99101014.toInt())
        }

        // Its name, and under it what it is, or what stands in its way
        val name = Component.translatable("ware.guildmark.${row.id}")
        graphics.text(font, name, frame.textX, y + 3, fade(if (gated || waiting) Ui.TEXT_FAINT else Ui.TEXT, k), false)
        val note: Pair<Component, Int> = when {
            refused -> Component.translatable(answer!!.refused) to Ui.RED
            gated -> Component.translatable(row.gate) to Ui.TEXT_FAINT
            else -> lineOf(row.id) to Ui.TEXT_SOFT
        }
        val first = font.split(note.first, max(1, frame.textWidth)).firstOrNull()
        if (first != null) graphics.text(font, first, frame.textX, y + 15, fade(note.second, k), false)

        // The button: the price, lit under the cursor, dipping when pressed, shaking when turned down
        val lit = ok && mouseX >= bx && mouseX < bx + frame.buttonWidth && mouseY >= by && mouseY < by + BUTTON && mouseY >= frame.top && mouseY < frame.bottom
        val shake = if (refused && since < 8f) Math.round(sin(since * 2.4f) * (1f - since / 8f) * 2.5f) else 0
        val down = answer != null && since < 3f
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.BUTTON.get(ok, lit), bx + shake, by + if (down) 1 else 0, frame.buttonWidth, BUTTON, ARGB.white(k))
        val ty = by + 3 + if (down) 1 else 0
        // A ware that costs nothing (the Warrant's free copy) is taken, not bought, and shows no price and no Mark
        val free = row.price <= 0
        val verb = Component.translatable(if (free) "gui.guildmark.trade.carriers.take" else "gui.guildmark.trade.carriers.buy")
        graphics.text(font, verb, bx + 5 + shake, ty, fade(if (ok) (if (lit) Ui.GOLD_BRIGHT else Ui.GOLD) else Ui.TEXT_FAINT, k), false)
        val right = bx + frame.buttonWidth - 5 + shake
        if (free) {
            val none = Component.translatable("gui.guildmark.trade.carriers.free")
            graphics.text(font, none, right - font.width(none), ty, fade(if (gated || waiting) Ui.TEXT_FAINT else if (lit) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, k), false)
        } else {
            val price = row.price.toString()
            val priceColor = when {
                gated || waiting -> Ui.TEXT_FAINT
                short -> Ui.RED
                lit -> Ui.GOLD_BRIGHT
                else -> Ui.TEXT
            }
            graphics.text(font, price, right - 9 - font.width(price), ty, fade(priceColor, k), false)
            pose.pushMatrix()
            pose.translate((right - 8).toFloat(), (ty - 1).toFloat())
            pose.scale(0.5f, 0.5f)
            if (k > 0.3f) graphics.item(mark, 0, 0)
            pose.popMatrix()
        }

        // The price rising from the button when it is paid
        if (!free && answer?.bought == true && since in 0f..24f) {
            val rise = ease(since / 24f)
            val a = if (since < 16f) 1f else (24f - since) / 8f
            graphics.text(font, "-${row.price}", bx - font.width("-${row.price}") - 4, (by - rise * 9f).toInt() + 3, ARGB.color(a.coerceIn(0f, 1f), Ui.GOLD_BRIGHT), true)
        }
        pose.popMatrix()
    }

    /**
     * The icon of the ware [id]: its own item if there is one, else the item of the ware it is another copy of (`carriers_warrant_again`
     * shows the Warrant), else a stand-in.
     */
    private fun iconOf(id: String): ItemStack = icons.getOrPut(id) {
        ICONS[id] ?: itemNamed(id) ?: itemNamed(id.removeSuffix("_again")) ?: ItemStack(Items.PAPER)
    }

    private fun itemNamed(id: String): ItemStack? =
        BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, id)).map { ItemStack(it) }.orElse(null)

    /** The one line under a ware's name: its own, or the start of its tooltip if it has none. */
    private fun lineOf(id: String): Component {
        val own = "ware.guildmark.$id.line"
        return Component.translatable(if (Language.getInstance().has(own)) own else "ware.guildmark.$id.tip")
    }

    // ---- Input ----

    override fun click(frame: MarketPanel.Frame, y: Int, mouseX: Double, mouseY: Double, shift: Boolean): Boolean {
        val (row, index) = rowAt(frame, y, mouseY) ?: return false
        val bx = frame.x + frame.width - frame.buttonWidth
        val by = y + HEADING + index * (ROW + GAP) + (ROW - BUTTON) / 2
        if (mouseX < bx || mouseX >= bx + frame.buttonWidth || mouseY < by || mouseY >= by + BUTTON) return false
        if (CarriersClient.rows == null || row.gate.isNotEmpty() || frame.view.marks < row.price) return true
        Ui.select()
        CarriersClient.buy(row.id)
        return true
    }

    override fun tooltip(frame: MarketPanel.Frame, y: Int, mouseX: Double, mouseY: Double): List<Pair<Component, Int>>? {
        val (row, _) = rowAt(frame, y, mouseY) ?: return null
        if (mouseX < frame.x || mouseX >= frame.x + frame.width) return null
        val lines = ArrayList<Pair<Component, Int>>()
        lines.add(Component.translatable("ware.guildmark.${row.id}") to Ui.GOLD)
        lines.add(Component.translatable("ware.guildmark.${row.id}.tip") to Ui.TEXT)
        lines.add(
            (if (row.price <= 0) Component.translatable("gui.guildmark.trade.carriers.nothing") else Component.translatable("gui.guildmark.trade.carriers.price", row.price)) to Ui.TEXT_SOFT,
        )
        when {
            row.gate.isNotEmpty() -> lines.add(Component.translatable(row.gate) to Ui.RED)
            frame.view.marks < row.price -> lines.add(Component.translatable("gui.guildmark.trade.carriers.short") to Ui.RED)
            CarriersClient.rows != null -> lines.add(Component.translatable(if (row.price <= 0) "gui.guildmark.trade.carriers.click_free" else "gui.guildmark.trade.carriers.click") to Ui.TEXT_FAINT)
        }
        return lines
    }

    /** The row whose place on the screen [mouseY] is in, and its number, for a section whose top is [y]. */
    private fun rowAt(frame: MarketPanel.Frame, y: Int, mouseY: Double): Pair<CarriersRow, Int>? {
        if (mouseY < frame.top || mouseY >= frame.bottom) return null
        val rows = list()
        val index = Math.floorDiv((mouseY - y - HEADING).toInt(), ROW + GAP)
        if (mouseY - y - HEADING < 0.0 || index !in rows.indices) return null
        if ((mouseY - y - HEADING).toInt() - index * (ROW + GAP) >= ROW) return null
        return rows[index] to index
    }

    private fun ease(k: Float): Float = 1f - (1f - k) * (1f - k) * (1f - k)

    /** 1 just after something happened, falling to 0 over [length] ticks. */
    private fun bump(since: Float, length: Float): Float = if (since < 0f || since >= length) 0f else (1f - since / length).let { it * it }

    private fun fade(color: Int, k: Float): Int = ARGB.multiplyAlpha(color, k)
}
