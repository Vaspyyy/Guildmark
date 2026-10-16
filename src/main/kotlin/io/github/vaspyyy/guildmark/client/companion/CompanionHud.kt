package io.github.vaspyyy.guildmark.client.companion

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.companion.CompanionEntity
import io.github.vaspyyy.guildmark.companion.Companions
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The companion travelling with you, low on the left above the quick slots: their name, and their health draining and
 * filling as a bar (it pulses red when they're down, with how to help them up). What they say comes up as a line over
 * the hotbar, their name first, and fades once they've said it.
 */
object CompanionHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "companion")

    private const val MARGIN = 6
    /** Above the flask and spell slots, and the spell's pips and name over those. */
    private const val RISE = 58
    private const val BAR_W = 64
    private const val BAR_H = 3
    private const val LINE_WIDTH = 280
    private const val LINE_ROWS = 3
    /** Ticks a line stays up for each letter in it, and at least. */
    private const val TICKS_PER_LETTER = 1.3f
    private const val LINE_MIN = 60
    private const val FADE = 10f
    private const val HEALTH = 0xFFB8413A.toInt()
    private const val HEALTH_LOST = 0xFFE8C07A.toInt()
    private const val DOWN = 0xFFE05A4A.toInt()

    private var companionId = -1
    private var ticks = 0
    /** The health shown, drawn after the real one so a hit drains visibly. */
    private var shownHealth = -1f
    private var lostHealth = -1f
    private var alpha = 0f
    private var alphaO = 0f

    private var line: Component? = null
    private var lineName: Component = Component.empty()
    private var lineAt = 0
    private var lineFor = 0

    fun reset() {
        companionId = -1
        shownHealth = -1f
        lostHealth = -1f
        alpha = 0f
        alphaO = 0f
        line = null
    }

    /** [c] said [text]: it comes up over the hotbar. */
    fun say(c: CompanionEntity, text: Component) {
        line = text
        lineName = c.kind.title
        lineAt = ticks
        lineFor = max(LINE_MIN, (text.string.length * TICKS_PER_LETTER).toInt())
    }

    /** The player's own companion travelling with them, as their client knows it. */
    private fun find(): CompanionEntity? {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        val kind = Companions.state(player).activeKind ?: return null
        (level.getEntity(companionId) as? CompanionEntity)?.takeIf { it.isAlive && it.kind == kind && it.ownerId == player.uuid }?.let { return it }
        val found = level.getEntitiesOfClass(CompanionEntity::class.java, player.boundingBox.inflate(64.0)) { it.kind == kind && it.ownerId == player.uuid }.firstOrNull()
        companionId = found?.id ?: -1
        return found
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.player == null || minecraft.isPaused) return
        ticks++
        alphaO = alpha
        val c = if (ticks % 10 == 0 || companionId >= 0) find() else null
        val want = if (c != null) 1f else 0f
        alpha += (want - alpha) * 0.2f
        if (c == null) return
        val health = (c.health / c.maxHealth).coerceIn(0f, 1f)
        if (shownHealth < 0f) {
            shownHealth = health
            lostHealth = health
        }
        shownHealth = health
        // What was lost lingers a moment, then drains away
        lostHealth = if (lostHealth < health) health else lostHealth + (health - lostHealth) * 0.08f
    }

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.gui.hud.isHidden || player.isSpectator) return
        val partial = delta.getGameTimeDeltaPartialTick(true)
        val font = minecraft.font
        drawLine(graphics, partial)
        val a = Mth.lerp(partial, alphaO, alpha)
        if (a < 0.03f) return
        val c = minecraft.level?.getEntity(companionId) as? CompanionEntity
        val x = MARGIN
        val y = graphics.guiHeight() - RISE
        val name = c?.kind?.title ?: return
        graphics.text(font, name, x, y, ARGB.color(a, -1), true)
        val barY = y + 11
        graphics.fill(x - 1, barY - 1, x + BAR_W + 1, barY + BAR_H + 1, ARGB.color(a * 0.75f, 0x14100C))
        if (c.isDowned) {
            // Down: the bar empty and pulsing, and what to do
            val pulse = 0.55f + 0.45f * sin((ticks + partial) * 0.25f)
            graphics.fill(x, barY, x + BAR_W, barY + BAR_H, ARGB.color(a * pulse, DOWN and 0xFFFFFF))
            graphics.text(font, Component.translatable("gui.guildmark.companion.downed"), x, barY + 6, ARGB.color(a * pulse, DOWN and 0xFFFFFF), true)
            return
        }
        val lost = (lostHealth * BAR_W).toInt()
        val left = (shownHealth * BAR_W).toInt()
        if (lost > left) graphics.fill(x + left, barY, x + lost, barY + BAR_H, ARGB.color(a, HEALTH_LOST and 0xFFFFFF))
        graphics.fill(x, barY, x + left, barY + BAR_H, ARGB.color(a, HEALTH and 0xFFFFFF))
        // Far behind, or out of sight: their name dims
        if (c.distanceToSqr(player) > 24.0 * 24.0) graphics.text(font, Component.translatable("gui.guildmark.companion.far"), x, barY + 6, ARGB.color(a * 0.7f, Ui.TEXT_FAINT and 0xFFFFFF), true)
    }

    /** What they're saying, over the hotbar: up to a few rows, fading in and out. */
    private fun drawLine(graphics: GuiGraphicsExtractor, partial: Float) {
        val text = line ?: return
        val age = ticks - lineAt + partial
        if (age > lineFor + FADE) {
            line = null
            return
        }
        val k = min(age / 4f, 1f) * ((lineFor + FADE - age) / FADE).coerceIn(0f, 1f)
        if (k <= 0.02f) return
        val font = Minecraft.getInstance().font
        val width = min(LINE_WIDTH, graphics.guiWidth() - 40)
        val said = Component.empty().append(lineName).append(Component.literal(": ").withColor(Ui.TEXT_SOFT)).append(text.copy().withColor(Ui.TEXT))
        val rows = font.splitter.splitLines(said, width, Style.EMPTY).take(LINE_ROWS)
        val rowH = 10
        val bottom = graphics.guiHeight() - 72
        val top = bottom - rows.size * rowH
        val widest = rows.maxOf { font.width(it) }
        val cx = graphics.guiWidth() / 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, cx - widest / 2 - 6, top - 4, widest + 12, rows.size * rowH + 6, ARGB.white(k * 0.85f))
        for ((i, row) in rows.withIndex()) {
            graphics.text(font, net.minecraft.locale.Language.getInstance().getVisualOrder(row), cx - font.width(row) / 2, top + i * rowH, ARGB.color(k, -1), false)
        }
    }
}
