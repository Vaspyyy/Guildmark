package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.platform.InputConstants
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Role
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.BillFoods
import io.github.vaspyyy.guildmark.cooking.dish.CookJournal
import io.github.vaspyyy.guildmark.cooking.dish.DishTooltip
import io.github.vaspyyy.guildmark.progression.Skill
import io.github.vaspyyy.guildmark.progression.Skills
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Cook's Book (living-world/cook/design.md 3.4): two parchment pages on the dark cover, as the Almanac's. On the left
 * the twelve kinds, the ones the cook cannot yet make greyed with the level they need, the best tier reached marked beside
 * each; on the right the page of the one chosen: its picture (wearing the seal of the best tier made), the station that
 * cooks it and how long it takes, the standard bill, what the cook has found out about this world's ideal ("less carrot,
 * more savour"), and the best batch made, with what went into it. Everything on it is read from the journal on the player
 * and from the tables of the kinds; the world's ideal is never here, only what the ladle and the keepers have told. The
 * pages turn with a slide, the ribbon sways, the stations burn and the Hallmarked seal glints in their own animation, the
 * chosen row glows, the foods of the best batch bob, and what is known is written out as the page opens.
 */
class CookBookScreen : Screen(Component.translatable("gui.guildmark.cook.book")) {
    companion object {
        fun open() {
            Minecraft.getInstance().gui.setScreen(CookBookScreen())
        }

        private const val LEFT_W = 146
        private const val RIGHT_W = 196
        private const val W = LEFT_W + RIGHT_W + 26
        private const val H = 228
        private const val PAGE_Y = 22
        private const val PAGE_H = 196
        private const val ROW = 14

        /** The width of one food's cell on a page (the item, and its count beside it), and how many go in a line. */
        private const val CELL = 29
        private const val PER_LINE = 6

        private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cook/$name")
        private val PAGE = sprite("book_page")
        private val RIBBON = sprite("book_ribbon")
        private val LOCK = sprite("book_lock")
        private val POT = sprite("hint_pot")
        private val ROLES = Role.entries.associateWith { sprite("role_${it.name.lowercase()}") }
        private val STATIONS = Station.entries.associateWith { sprite("station_${it.name.lowercase()}") }
        private val TIERS = Tier.entries.filter { it != Tier.SPOILT }.associateWith { sprite("tier_${it.name.lowercase()}") }

        /** The block names of the stations, as the language file has them. */
        private val STATION_KEYS = mapOf(
            Station.STEWPOT to "block.guildmark.stewpot",
            Station.HOB to "block.guildmark.hob",
            Station.OVEN to "block.guildmark.bake_oven",
            Station.SPIT to "block.guildmark.spit",
        )

        // Ink on parchment (no shadow, as a printed page)
        private const val INK = 0x3B2A17
        private const val INK_SOFT = 0x6A5236
        private const val INK_FAINT = 0x9A8560
        private const val INK_RED = 0x8C3426
        private const val PARCHMENT = 0xEAD9AC

        /** Which kind was open last, kept for the session. */
        private var lastKind = 0
    }

    private val kinds = DishKind.entries
    private var left = 0
    private var top = 0
    private var selected = lastKind.coerceIn(0, kinds.size - 1)
    private var hovered = -1
    private val opened = Util.getMillis()
    private var changed = opened

    override fun init() {
        left = (width - W) / 2
        top = (height - H) / 2
        Ui.open()
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    private fun choose(index: Int) {
        val next = index.coerceIn(0, kinds.size - 1)
        if (next == selected) return
        selected = next
        lastKind = next
        changed = Util.getMillis()
        Ui.page()
    }

    private fun listX(): Int = left + 8 + 10

    private fun listY(): Int = top + PAGE_Y + 11

    private fun rowAt(x: Double, y: Double): Int {
        val x0 = listX()
        val y0 = listY()
        if (x < x0 - 3 || x >= x0 + LEFT_W - 17 || y < y0 || y >= y0 + ROW * kinds.size) return -1
        return ((y - y0) / ROW).toInt()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val row = rowAt(event.x(), event.y())
            if (row >= 0) {
                choose(row)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        choose(selected + if (scrollY > 0) -1 else 1)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            InputConstants.KEY_DOWN -> choose(selected + 1)
            InputConstants.KEY_UP -> choose(selected - 1)
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val fadeIn = min(1f, (Util.getMillis() - opened) / 250f)
        graphics.fill(0, 0, width, height, ARGB.color(fadeIn * 0.72f, 0x06060B))
        Ui.panel(graphics, left, top, W, H)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val player = Minecraft.getInstance().player ?: return
        val journal = player.getData(CookRegistry.COOK_JOURNAL)
        val level = Skills.level(player, Skill.COOKING)
        val seconds = (Util.getMillis() % 600_000L) / 1000f
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.cook.book"), left + W / 2, top + 7)
        Ui.divider(graphics, left + 12, top + 18, W - 24)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PAGE, left + 8, top + PAGE_Y, LEFT_W, PAGE_H)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PAGE, left + 8 + LEFT_W + 10, top + PAGE_Y, RIGHT_W, PAGE_H)
        list(graphics, mouseX, mouseY, level, journal, seconds)
        page(graphics, mouseX, mouseY, level, journal, seconds)
        ribbon(graphics, seconds)
    }

    // ---- The kinds, down the left page ----

    /** The picture of [kind]: its dish, wearing the seal of the [best] tier made (-1 for none: as made). */
    private fun dish(kind: DishKind, best: Int): ItemStack {
        val stack = ItemStack(CookRegistry.DISHES.getValue(kind).get())
        if (best >= 0) stack.set(CookRegistry.DISH_TIER.get(), best)
        return stack
    }

    private fun kindName(kind: DishKind): Component = Component.translatable("item.guildmark.dish_${kind.id}")

    private fun list(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, level: Int, journal: CookJournal, seconds: Float) {
        val x0 = listX()
        val w = LEFT_W - 20
        val hot = rowAt(mouseX.toDouble(), mouseY.toDouble())
        if (hot != hovered) {
            hovered = hot
            if (hot >= 0) Ui.hover()
        }
        var y = listY()
        for ((i, kind) in kinds.withIndex()) {
            val locked = level < kind.level
            val page = journal.pageOrNull(kind)
            if (i == selected) {
                graphics.fill(x0 - 3, y - 1, x0 + w + 3, y + ROW - 1, ARGB.color(0.30f + 0.08f * sin(seconds * 3f), 0x8A6A2A))
                graphics.fill(x0 - 3, y - 1, x0 - 1, y + ROW - 1, ARGB.color(0.9f, 0xB58A32))
            } else if (i == hot) {
                graphics.fill(x0 - 3, y - 1, x0 + w + 3, y + ROW - 1, ARGB.color(0.16f, 0x8A6A2A))
            }
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x0.toFloat(), y.toFloat())
            pose.scale(0.75f, 0.75f)
            graphics.item(dish(kind, page?.best ?: -1), 0, 0)
            pose.popMatrix()
            if (locked) graphics.fill(x0, y, x0 + 12, y + 12, ARGB.color(0.6f, PARCHMENT))
            // A locked kind wears a lock and the level it needs; one that has been made wears the seal of its best tier
            val number = kind.level.toString()
            val name = font.substrByWidth(kindName(kind), w - 30 - if (locked) font.width(number) else 0).string
            graphics.text(font, name, x0 + 16, y + 2, ARGB.color(1f, if (locked) INK_FAINT else INK), false)
            if (locked) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LOCK, x0 + w - font.width(number) - 12, y + 1, 10, 10)
                graphics.text(font, number, x0 + w - font.width(number), y + 2, ARGB.color(1f, INK_RED), false)
                if (i == hot) tip(graphics, Component.translatable("gui.guildmark.cook.book.level", kind.level), mouseX, mouseY)
            } else if (page != null && page.best >= 1) {
                TIERS[Tier.of(page.best)]?.let { graphics.blitSprite(RenderPipelines.GUI_TEXTURED, it, x0 + w - 12, y, 12, 12) }
            }
            y += ROW
        }
    }

    // ---- The page of the chosen kind ----

    private fun page(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, level: Int, journal: CookJournal, seconds: Float) {
        val kind = kinds[selected]
        val locked = level < kind.level
        val entry = journal.pageOrNull(kind)
        val turn = min(1f, (Util.getMillis() - changed) / 220f).let { it * it * (3f - 2f * it) }
        val dim = if (locked) 0.55f else 1f
        val x0 = left + 8 + LEFT_W + 10 + 10
        val w = RIGHT_W - 20
        val y0 = top + PAGE_Y + 10
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((1f - turn) * 10f, 0f)

        // The dish, big, with the station that cooks it beside
        pose.pushMatrix()
        pose.translate(x0.toFloat(), (y0 + 1).toFloat())
        pose.scale(2f, 2f)
        graphics.item(dish(kind, entry?.best ?: -1), 0, 0)
        pose.popMatrix()
        if (locked) {
            graphics.fill(x0, y0 + 1, x0 + 32, y0 + 33, ARGB.color(0.55f, PARCHMENT))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LOCK, x0 + 4, y0 + 5, 24, 24)
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, STATIONS.getValue(kind.station), x0 + w - 24, y0 + 2, 24, 24, ARGB.color(dim * turn, 0xFFFFFF))
        graphics.text(font, kindName(kind).copy().withStyle(ChatFormatting.BOLD), x0 + 38, y0 + 2, ARGB.color(turn, if (locked) INK_FAINT else INK), false)
        val where = Component.translatable("gui.guildmark.cook.book.where", Component.translatable(STATION_KEYS.getValue(kind.station)), kind.level)
        graphics.text(font, where, x0 + 38, y0 + 14, ARGB.color(turn, if (locked) INK_RED else INK_SOFT), false)
        val makes = Component.translatable("gui.guildmark.cook.book.makes", time(kind.time), kind.portions)
        graphics.text(font, makes, x0 + 38, y0 + 25, ARGB.color(turn * dim, INK_SOFT), false)
        graphics.fill(x0, y0 + 36, x0 + w, y0 + 37, ARGB.color(0.35f * turn, INK_SOFT))

        // The standard bill, the first guess
        label(graphics, x0, y0 + 40, Component.translatable("gui.guildmark.cook.book.bill"), turn)
        val bill = kind.bill.entries.map { (food, count) -> BillFoods.stack(food, count) }.filter { !it.isEmpty }
        foods(graphics, x0, y0 + 50, bill, turn * dim, mouseX, mouseY, seconds, false)

        // What the cook has found out of this world
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, POT, x0, y0 + 70, 12, 12, ARGB.color(turn, 0xFFFFFF))
        val know = knowLine(kind, entry)
        val head = Component.translatable("gui.guildmark.cook.book.know")
        graphics.text(font, head, x0 + 15, y0 + 72, ARGB.color(turn, INK_FAINT), false)
        val known = Role.entries.filter { (entry?.direction?.get(it.ordinal) ?: 0) != 0 }
        for ((n, role) in known.withIndex()) {
            val rx = x0 + w - 11 * (known.size - n)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ROLES.getValue(role), rx, y0 + 71, 10, 10, ARGB.color(turn, 0xFFFFFF))
            if (mouseX in rx until rx + 10 && mouseY in y0 + 71 until y0 + 81) {
                tip(graphics, roleWord(role), mouseX, mouseY)
            }
        }
        if (mouseX in x0 until x0 + w - 11 * known.size && mouseY in y0 + 70 until y0 + 82) {
            tip(graphics, Component.translatable("gui.guildmark.cook.book.know.tip"), mouseX, mouseY)
        }
        // written out as the page opens, line by line as it will stand
        var reveal = ((Util.getMillis() - changed - 150L) / 11L).toInt().coerceAtLeast(0)
        var y = y0 + 84
        for (line in lines(know, w).take(3)) {
            val shown = line.take(reveal)
            reveal -= line.length
            if (shown.isNotEmpty()) graphics.text(font, shown, x0, y, ARGB.color(turn, if (entry == null || known.isEmpty()) INK_FAINT else INK), false)
            y += font.lineHeight
        }

        // The best batch, and what went into it
        val made = entry?.made ?: 0
        val madeText = when (made) {
            0 -> Component.translatable("gui.guildmark.cook.book.made.none")
            1 -> Component.translatable("gui.guildmark.cook.book.made.one")
            else -> Component.translatable("gui.guildmark.cook.book.made", made)
        }
        val best = Component.translatable("gui.guildmark.cook.book.best")
        graphics.text(font, best, x0, y0 + 118, ARGB.color(turn, INK_FAINT), false)
        graphics.text(font, madeText, x0 + font.width(best) + 6, y0 + 118, ARGB.color(turn, INK_SOFT), false)
        val tier = entry?.best?.takeIf { it >= 1 }?.let { Tier.of(it) }
        if (tier != null) {
            val sx = x0 + w - 16
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TIERS.getValue(tier), sx, y0 + 113, 16, 16, ARGB.color(turn, 0xFFFFFF))
            if (mouseX in sx until sx + 16 && mouseY in y0 + 113 until y0 + 129) tip(graphics, DishTooltip.tierWord(tier), mouseX, mouseY)
        }
        val mix = mixStacks(entry)
        if (mix.isEmpty()) {
            graphics.text(font, Component.translatable("gui.guildmark.cook.book.best.none"), x0, y0 + 132, ARGB.color(turn, INK_FAINT), false)
        } else {
            foods(graphics, x0, y0 + 130, mix, turn, mouseX, mouseY, seconds, true)
        }
        pose.popMatrix()
    }

    /** A tooltip of [text] beside the cursor, wrapped to a readable width. */
    private fun tip(graphics: GuiGraphicsExtractor, text: Component, mouseX: Int, mouseY: Int) {
        graphics.setTooltipForNextFrame(font, font.split(text, 180), mouseX, mouseY)
    }

    /** The label of a block of the page: small and faint. */
    private fun label(graphics: GuiGraphicsExtractor, x: Int, y: Int, text: Component, alpha: Float) {
        graphics.text(font, text, x, y, ARGB.color(alpha, INK_FAINT), false)
    }

    /** "2:00": [seconds] of cooking, as the clock of the vessel reads them. */
    private fun time(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /**
     * A block of foods, [PER_LINE] to a line: each its item and, beside it, how many ("x3"); under the cursor, its name and
     * the count. With [bob] each rises and falls a little, out of step with its neighbours.
     */
    private fun foods(
        graphics: GuiGraphicsExtractor, x0: Int, y0: Int, stacks: List<ItemStack>, alpha: Float,
        mouseX: Int, mouseY: Int, seconds: Float, bob: Boolean,
    ) {
        for ((i, stack) in stacks.withIndex()) {
            val cx = x0 + (i % PER_LINE) * CELL
            val cy = y0 + (i / PER_LINE) * 18
            val lift = if (bob) (sin(seconds * 2f + i * 0.9f) * 0.8f).roundToInt() else 0
            graphics.item(stack, cx, cy + lift)
            if (alpha < 0.99f) graphics.fill(cx, cy, cx + 16, cy + 16, ARGB.color((1f - alpha) * 0.9f, PARCHMENT))
            graphics.text(font, "×${stack.count}", cx + 17, cy + 5, ARGB.color(alpha, INK_SOFT), false)
            if (mouseX in cx until cx + CELL - 1 && mouseY in cy until cy + 16) {
                tip(graphics, Component.translatable("gui.guildmark.cook.book.food.tip", stack.count, stack.hoverName), mouseX, mouseY)
            }
        }
    }

    /** The foods of the best batch of [entry], as stacks: the items the journal names, counted (what the game no longer has is left out). */
    private fun mixStacks(entry: CookJournal.Page?): List<ItemStack> {
        if (entry == null) return emptyList()
        val out = ArrayList<ItemStack>()
        for (i in 0 until min(entry.mix.size, entry.names.size)) {
            val id = Identifier.tryParse(entry.names[i]) ?: continue
            val item = BuiltInRegistries.ITEM.getOptional(id).orElse(null) ?: continue
            out.add(ItemStack(item, entry.mix[i].coerceIn(1, 99)))
        }
        return out
    }

    // ---- What is known ----

    /** The word the Book uses for [food] (the standard bill's names: "carrot", "beef"). */
    private fun foodWord(food: String): Component = Component.translatable("gui.guildmark.cook.book.food.$food")

    private fun roleWord(role: Role): Component = Component.translatable("message.guildmark.cook.role.${role.name.lowercase()}")

    /** A number of items to the nearest half, with no ".0": 2, 1.5. */
    private fun halves(tenths: Int): String {
        val n = (tenths / 5.0).roundToInt() / 2.0
        return if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
    }

    /**
     * "In this world you know: less carrot, more savour." from what [entry] holds: for a role found too high in the standard
     * bill, less of the food of the bill that carries most of it; for one found too low, more of the role itself. With the
     * amount (learned from COOKING 25), "about 2 less carrot", or "a little less carrot" under three quarters of an item.
     */
    private fun knowLine(kind: DishKind, entry: CookJournal.Page?): Component {
        val parts = ArrayList<Component>()
        if (entry != null) {
            for (role in Role.entries) {
                val direction = entry.direction[role.ordinal]
                if (direction == 0) continue
                val over = direction > 0
                val word = if (over) BillFoods.heaviest(kind, role)?.let { foodWord(it) } ?: roleWord(role) else roleWord(role)
                val tenths = entry.amount[role.ordinal]
                val key = if (over) "gui.guildmark.cook.book.know.less" else "gui.guildmark.cook.book.know.more"
                parts.add(
                    when {
                        tenths <= 0 -> Component.translatable(key, word)
                        tenths < 8 -> Component.translatable(key + ".little", word)
                        else -> Component.translatable(key + ".about", halves(tenths), word)
                    },
                )
            }
        }
        if (parts.isEmpty()) return Component.translatable("gui.guildmark.cook.book.know.none")
        val joined = Component.empty()
        for ((i, part) in parts.withIndex()) {
            if (i > 0) joined.append(", ")
            joined.append(part)
        }
        return Component.translatable("gui.guildmark.cook.book.know.line", joined)
    }

    /** [text] as lines of at most [width], as plain strings (so they can be written out a few letters at a time). */
    private fun lines(text: Component, width: Int): List<String> = font.split(text, width).map { sequence ->
        val line = StringBuilder()
        sequence.accept { _, _, codePoint ->
            line.appendCodePoint(codePoint)
            true
        }
        line.toString()
    }

    // ---- The ribbon ----

    /** The bookmark at the spine, swaying a little on its top edge. */
    private fun ribbon(graphics: GuiGraphicsExtractor, seconds: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((left + 8 + LEFT_W + 5).toFloat(), (top + PAGE_Y - 5).toFloat())
        pose.rotate(sin(seconds * 1.1f) * 0.035f)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, RIBBON, -10, 0, 20, 64)
        pose.popMatrix()
    }
}
