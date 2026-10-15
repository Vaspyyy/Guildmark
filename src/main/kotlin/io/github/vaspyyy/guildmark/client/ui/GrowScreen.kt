package io.github.vaspyyy.guildmark.client.ui

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.GrowPayload
import io.github.vaspyyy.guildmark.progression.CharacterSheet
import io.github.vaspyyy.guildmark.progression.Effect
import io.github.vaspyyy.guildmark.progression.GuildProgress
import io.github.vaspyyy.guildmark.progression.Stat
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.sounds.SoundManager
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Locale
import java.util.Optional
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Growing stronger, at a Wardfire (or anywhere in a world without them): banked guild XP buys a level
 * at a time, and every level raises one stat by a point. Raises owed from levels gained before come
 * free. Plus and minus line the raises up, as the server will take them; Confirm sends them.
 */
class GrowScreen(private val restAt: BlockPos?, private val back: Screen?) : Screen(Component.translatable("gui.guildmark.grow")) {
    /** Stat ids lined up to be raised, in the order picked. */
    private val queued = ArrayList<String>()
    /** Raises sent and not yet seen back, shown as made until the sheet changes. */
    private var sent: List<String> = listOf()
    private var sentSheet: CharacterSheet? = null
    private var sentAt = 0
    private var ticks = 0

    private var panelX = 0
    private var panelY = 0
    private val plus = HashMap<Stat, StepButton>()
    private val minus = HashMap<Stat, StepButton>()
    private var confirm: Ui.KitButton? = null

    /** What lining up some raises comes to: the level, banked XP and stats after them. */
    private class Outcome(val progress: GuildProgress, val sheet: CharacterSheet, val made: Int, val spent: Int, val free: Int)

    /** Raises [raises] on top of [progress] and [sheet] as [io.github.vaspyyy.guildmark.progression.Levelling.grow] does. */
    private fun simulate(progress: GuildProgress, sheet: CharacterSheet, raises: List<String>): Outcome {
        var level = progress.level
        var xp = progress.xp
        var s = sheet
        var made = 0
        var spent = 0
        var free = 0
        for (id in raises) {
            val stat = Stat.byId(id) ?: continue
            val value = s.stat(stat)
            if (value >= Stat.MAX) continue
            if (s.owed(level) <= 0) {
                if (level >= GuildProgress.MAX_LEVEL) break
                val cost = GuildProgress.xpToNext(level)
                if (xp < cost) break
                level++
                xp -= cost
                spent += cost
            } else {
                free++
            }
            s = s.withStat(stat, value + 1)
            made++
        }
        return Outcome(progress.copy(level = level, xp = xp), s, made, spent, free)
    }

    /** Where things stand before the queue: the synced data, with anything just sent made already. */
    private fun base(): Outcome? {
        val player = minecraft.player ?: return null
        val progress = player.getData(ModAttachments.GUILD_PROGRESS)
        val sheet = player.getData(ModAttachments.SHEET)
        if (sent.isNotEmpty() && (sheet !== sentSheet || ticks - sentAt > SENT_TICKS)) sent = listOf()
        return simulate(progress, sheet, sent)
    }

    private fun outcome(): Pair<Outcome, Outcome>? {
        val base = base() ?: return null
        return base to simulate(base.progress, base.sheet, queued)
    }

    private fun canRaise(base: Outcome, after: Outcome, stat: Stat): Boolean =
        simulate(base.progress, base.sheet, queued + stat.id).made > after.made

    // ---- Setup ----

    override fun init() {
        panelX = (width - PANEL_WIDTH) / 2
        panelY = max(4, (height - PANEL_HEIGHT) / 2)
        plus.clear()
        minus.clear()
        val right = panelX + PANEL_WIDTH - PAD
        for ((i, stat) in Stat.entries.withIndex()) {
            val y = panelY + ROWS_TOP + i * ROW + (ROW - STEP) / 2
            minus[stat] = addRenderableWidget(StepButton(right - STEP * 2 - 4, y, MINUS) { lower(stat) })
            plus[stat] = addRenderableWidget(StepButton(right - STEP, y, PLUS) { raise(stat) })
        }
        val footY = panelY + PANEL_HEIGHT - 28
        val center = panelX + PANEL_WIDTH / 2
        confirm = addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.grow.confirm"), 96) { confirm() }.also {
            it.setPosition(center - 100, footY)
        })
        addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.grow.back"), 96) { onClose() }.also {
            it.setPosition(center + 4, footY)
        })
        refresh()
    }

    private fun refresh() {
        val (base, after) = outcome() ?: return
        for (stat in Stat.entries) {
            plus[stat]?.active = canRaise(base, after, stat)
            minus[stat]?.active = stat.id in queued
        }
        confirm?.active = queued.isNotEmpty()
    }

    private fun raise(stat: Stat) {
        val (base, after) = outcome() ?: return
        if (!canRaise(base, after, stat)) return
        queued.add(stat.id)
        refresh()
    }

    private fun lower(stat: Stat) {
        val i = queued.lastIndexOf(stat.id)
        if (i < 0) return
        queued.removeAt(i)
        refresh()
    }

    private fun confirm() {
        val player = minecraft.player ?: return
        if (queued.isEmpty()) return
        for (part in queued.chunked(GrowPayload.MAX_STATS)) {
            ClientPacketDistributor.sendToServer(GrowPayload(Optional.ofNullable(restAt), part))
        }
        sent = sent + queued
        sentSheet = player.getData(ModAttachments.SHEET)
        sentAt = ticks
        queued.clear()
        refresh()
    }

    override fun added() {
        super.added()
        Ui.open()
    }

    override fun onClose() {
        Ui.close()
        val to = back
        if (to != null && minecraft.player?.isAlive == true) minecraft.gui.setScreen(to) else super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    /** Subtitles are drawn over this screen, not hidden under its shade. */
    override fun isInGameUi(): Boolean = true

    override fun tick() {
        ticks++
        val player = minecraft.player
        // Struck, or carried off from the fire: the rest is over
        if (player == null || !player.isAlive || restAt != null && (player.hurtTime > 0 || player.distanceToSqr(Vec3.atCenterOf(restAt)) > REACH * REACH)) {
            Ui.close()
            minecraft.gui.setScreen(null)
            return
        }
        refresh()
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val k = min(1f, (ticks + a) / 5f)
        graphics.fill(0, 0, width, height, ARGB.color((150 * k).toInt(), 6, 4, 2))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val (base, after) = outcome() ?: return
        Ui.panel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT)
        val center = panelX + PANEL_WIDTH / 2
        val x = panelX + PAD
        val w = PANEL_WIDTH - PAD * 2
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.grow"), center, panelY + 9, Ui.GOLD_BRIGHT)

        // The level, and the guild XP it takes
        val from = base.progress
        val to = after.progress
        val level = if (to.level > from.level) Component.translatable("gui.guildmark.grow.level.to", from.level, Component.literal(to.level.toString()).withColor(Ui.GREEN and 0xFFFFFF))
        else Component.translatable("gui.guildmark.grow.level", from.level)
        graphics.centeredText(font, level, center, panelY + 23, Ui.TEXT)
        val xp = if (after.spent > 0) Component.translatable("gui.guildmark.grow.xp.to", grouped(from.xp), grouped(to.xp))
        else Component.translatable("gui.guildmark.grow.xp", grouped(from.xp))
        graphics.centeredText(font, xp, center, panelY + 34, Ui.GOLD)
        val owed = base.sheet.owed(from.level) - after.free
        val note = when {
            owed > 0 -> Component.translatable("gui.guildmark.grow.free", owed)
            to.isMaxLevel() -> Component.translatable("gui.guildmark.grow.max")
            to.xp >= to.xpToNext() -> Component.translatable("gui.guildmark.grow.next", grouped(to.xpToNext()))
            else -> Component.translatable("gui.guildmark.grow.short", grouped(to.xpToNext() - to.xp), to.level + 1)
        }
        graphics.centeredText(font, font.plainSubstrByWidth(note.string, w), center, panelY + 45, if (owed > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT)
        Ui.divider(graphics, x, panelY + 56, w)

        var tip: List<Pair<Component, Int>>? = null
        var tipY = 0
        for ((i, stat) in Stat.entries.withIndex()) {
            val y = panelY + ROWS_TOP + i * ROW
            val now = base.sheet.stat(stat)
            val then = after.sheet.stat(stat)
            val textY = y + (ROW - 8) / 2
            if (i % 2 == 1) graphics.fill(x - 4, y, x + w + 4, y + ROW, STRIPE)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tree/stat/${stat.id}"), x, y + (ROW - ICON) / 2, ICON, ICON)
            graphics.text(font, stat.title, x + ICON + 4, textY, Ui.TEXT, false)
            val valueRight = x + w - STEP * 2 - 30
            val nowText = now.toString()
            graphics.text(font, nowText, valueRight - 18 - font.width(nowText), textY, stat.color, false)
            if (then != now) {
                graphics.text(font, ARROW, valueRight - 13, textY, Ui.TEXT_FAINT, false)
                val thenText = then.toString()
                graphics.text(font, thenText, valueRight + 8 - font.width(thenText), textY, Ui.GREEN, false)
            }
            if (mouseX >= x - 4 && mouseX < valueRight + 10 && mouseY >= y && mouseY < y + ROW) {
                tip = statTip(stat, now, then)
                tipY = y
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, a)
        tip?.let { drawTip(graphics, it, tipY) }
    }

    /** What [stat] gives at [then], with how much the raises add. */
    private fun statTip(stat: Stat, now: Int, then: Int): List<Pair<Component, Int>> {
        val lines = arrayListOf<Pair<Component, Int>>(stat.title to stat.color, stat.description to Ui.TEXT_SOFT)
        val before = stat.effectsAt(now)
        val effects = stat.effectsAt(then)
        if (then > Stat.BASE) {
            lines.add(Component.translatable("gui.guildmark.ledger.stat.gives", then) to Ui.TEXT_FAINT)
            for ((i, effect) in effects.withIndex()) {
                val delta = effect.amount - before[i].amount
                val line = effect.line().copy()
                if (abs(delta) > 1e-9) line.append(Component.literal(" (" + Effect.format(delta, effect.bonus.shown) + ")").withColor(Ui.GOLD and 0xFFFFFF))
                lines.add(line to if (effect.helps) Ui.GREEN else Ui.RED)
            }
        } else {
            // Nothing yet: what the first point would bring
            lines.add(Component.translatable("gui.guildmark.grow.first") to Ui.TEXT_FAINT)
            for (effect in stat.effectsAt(Stat.BASE + 1)) lines.add(effect.line() to Ui.GREEN)
        }
        val next = then + 1
        if (then < Stat.MAX) {
            val share = when {
                next > Stat.HARD -> "gui.guildmark.grow.quarter"
                next > Stat.SOFT -> "gui.guildmark.grow.half"
                else -> null
            }
            share?.let { lines.add(Component.translatable(it) to Ui.TEXT_FAINT) }
        } else {
            lines.add(Component.translatable("gui.guildmark.grow.capped", Stat.MAX) to Ui.TEXT_FAINT)
        }
        return lines
    }

    /** The lines in a small dark panel beside the stats, level with the row at [rowY], so the numbers stay in view. */
    private fun drawTip(graphics: GuiGraphicsExtractor, lines: List<Pair<Component, Int>>, rowY: Int) {
        val wrapped = lines.flatMap { (text, color) -> font.split(text, TIP_WIDTH).map { it to color } }
        if (wrapped.isEmpty()) return
        val w = wrapped.maxOf { font.width(it.first) } + 16
        val h = wrapped.size * 10 + 10
        var x = panelX + PANEL_WIDTH + 4
        if (x + w > width - 4) x = max(4, panelX - 4 - w)
        val y = (rowY - 4).coerceIn(4, max(4, height - h - 4))
        graphics.nextStratum()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        for ((i, line) in wrapped.withIndex()) graphics.text(font, line.first, x + 8, y + 6 + i * 10, line.second, false)
    }

    private fun grouped(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    /** A small square plus or minus. */
    private class StepButton(x: Int, y: Int, private val sprites: WidgetSprites, onPress: OnPress) :
        Button(x, y, STEP, STEP, Component.empty(), onPress, CreateNarration { it.get() }) {

        override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprites.get(active, isHoveredOrFocused), x, y, width, height)
        }

        override fun playDownSound(manager: SoundManager) = Ui.click(manager)
    }

    companion object {
        private fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tree/$name")

        private val PLUS = WidgetSprites(sprite("plus"), sprite("plus_disabled"), sprite("plus_highlighted"))
        private val MINUS = WidgetSprites(sprite("minus"), sprite("minus_disabled"), sprite("minus_highlighted"))
        private const val ARROW = "→"

        private const val PANEL_WIDTH = 252
        private const val PANEL_HEIGHT = 232
        private const val PAD = 16
        private const val ROWS_TOP = 62
        private const val ROW = 19
        /** The plus and minus buttons, and the stat icons, at their art's size. */
        private const val STEP = 11
        private const val ICON = 16
        private const val TIP_WIDTH = 184
        private const val REACH = 6.0
        private const val SENT_TICKS = 40
        private val STRIPE = 0x18D9B46A
    }
}
