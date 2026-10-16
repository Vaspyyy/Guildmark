package io.github.vaspyyy.guildmark.client.ship

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.ship.Cog
import io.github.vaspyyy.guildmark.ship.Sailing
import io.github.vaspyyy.guildmark.ship.Wind
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.gui.GuiLayer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sailing HUD, low on the right while you're aboard: a wind rose with the ship at its middle (her bow always
 * up), the wind's arrow streaming the way it blows and swelling with its gusts, a red wedge where the wind is too
 * far ahead to sail; beside it a gauge of how far the sail is let down, with a notch where it's been ordered to;
 * the anchor, lit when it's down; her name over it all, and under it her speed in knots (or why she isn't
 * making any: at anchor, head to wind, or held fast by something from the deep) and her hull.
 */
object ShipHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ship")

    private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "ship/$name")
    private val ROSE = sprite("rose")
    private val HULL = sprite("rose_hull")
    private val ARROW = sprite("wind_arrow")
    private val NOGO = sprite("nogo")
    private val SAIL_FRAME = sprite("sail_frame")
    private val SAIL_FILL = sprite("sail_fill")
    private val SAIL_MARK = sprite("sail_mark")
    private val ANCHOR_UP = sprite("anchor_up")
    private val ANCHOR_DOWN = sprite("anchor_down")
    private val BAR_FRAME = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/bar_frame")
    private val BAR_HULL = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/bar_health")
    private val BAR_TRAIL = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/bar_trail")

    // ---- Layout, in GUI pixels ----
    private const val ROSE_SIZE = 56
    private const val HULL_W = 16
    private const val HULL_H = 28
    private const val ARROW_W = 8
    private const val ARROW_H = 44
    private const val GAUGE_W = 8
    private const val GAUGE_H = 44
    /** The gauge's slot, rows from its top: the canvas fills it from the foot up, graduated in quarters. */
    private const val SLOT_HEAD = 3
    private const val SLOT_FOOT = 41
    private const val MARK_W = 10
    private const val MARK_H = 3
    private const val ANCHOR = 12
    private const val GAP = 5
    private const val MARGIN = 8
    /** Room under the rose for the speed and the hull bar. */
    private const val BELOW = 24

    private const val WARN = 0xE07A5A
    private const val GOLD = 0xE8C07A

    private var cogId = -1
    private var ticks = 0
    private var alpha = 0f
    private var alphaO = 0f
    /** Her speed through the water, smoothed for reading (blocks a tick; below 0 astern). */
    private var speed = 0f
    private var speedO = 0f
    /** The hull shown, and what was lost lingering behind it. */
    private var hull = -1f
    private var hullLost = -1f
    private var anchorLit = 0f
    private var anchorLitO = 0f

    fun reset() {
        cogId = -1
        alpha = 0f
        alphaO = 0f
        hull = -1f
        hullLost = -1f
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.isPaused) return
        ticks++
        alphaO = alpha
        speedO = speed
        anchorLitO = anchorLit
        val aboard = player.vehicle as? Cog
        if (aboard != null) cogId = aboard.id
        alpha += ((if (aboard != null) 1f else 0f) - alpha) * 0.2f
        val cog = minecraft.level?.getEntity(cogId) as? Cog ?: return
        // Made good over the ground along her heading
        val way = ((cog.x - cog.xo) * Cog.forwardX(cog.yRot) + (cog.z - cog.zo) * Cog.forwardZ(cog.yRot)).toFloat()
        speed += (way - speed) * 0.15f
        if (abs(speed) < 0.002f && abs(way) < 0.002f) speed = 0f
        anchorLit += ((if (cog.anchored) 1f else 0f) - anchorLit) * 0.15f
        val h = cog.hull / Cog.MAX_HULL
        if (hull < 0f) {
            hull = h
            hullLost = h
        }
        hull = h
        hullLost = if (hullLost < h) h else hullLost + (h - hullLost) * 0.06f
    }

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.gui.hud.isHidden || player.isSpectator) return
        val partial = delta.getGameTimeDeltaPartialTick(true)
        val a = Mth.lerp(partial, alphaO, alpha)
        if (a < 0.03f) return
        val level = minecraft.level ?: return
        val cog = level.getEntity(cogId) as? Cog ?: return
        val font = minecraft.font

        val right = graphics.guiWidth() - MARGIN
        val bottom = graphics.guiHeight() - MARGIN
        val x0 = right - GAUGE_W - GAP - ROSE_SIZE
        val y0 = bottom - BELOW - ROSE_SIZE
        val cx = x0 + ROSE_SIZE / 2f
        val cy = y0 + ROSE_SIZE / 2f
        val white = ARGB.white(a)

        // Where the wind comes from, against her bow (+ to starboard), and how hard
        val yaw = cog.getYRot(partial)
        val rel = Mth.wrapDegrees(Wind.from(level, partial) - yaw)
        val strength = Wind.strength(level, partial)
        val sail = Mth.lerp(partial, cog.sailO, cog.sail)

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ROSE, x0, y0, ROSE_SIZE, ROSE_SIZE, white)
        // The wedge she can't sail into, brighter while her sail is set and she's in it
        val inIt = sail > 0.1f && abs(rel) < Sailing.NO_GO
        val wedge = if (inIt) 0.75f + 0.25f * sin((ticks + partial) * 0.25f) else 0.55f
        turned(graphics, cx, cy, rel) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, NOGO, -ROSE_SIZE / 2, -ROSE_SIZE / 2, ROSE_SIZE, ROSE_SIZE, ARGB.white(a * wedge))
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HULL, (cx - HULL_W / 2f).roundToInt(), (cy - HULL_H / 2f).roundToInt(), HULL_W, HULL_H, white)
        // The wind's arrow, the way it blows: longer and brighter as it freshens and gusts
        val reach = 0.6f + 0.4f * strength
        graphics.pose().pushMatrix()
        graphics.pose().translate(cx, cy)
        graphics.pose().rotate((rel + 180f) * Mth.DEG_TO_RAD)
        graphics.pose().scale(1f, reach)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, -ARROW_W / 2, -ARROW_H / 2, ARROW_W, ARROW_H, ARGB.white(a * (0.6f + 0.4f * strength)))
        graphics.pose().popMatrix()

        // The sail let down, filling the gauge from its foot, and the notch where it's ordered
        val gx = x0 + ROSE_SIZE + GAP
        val gy = y0 + (ROSE_SIZE - GAUGE_H) / 2 - ANCHOR / 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SAIL_FRAME, gx, gy, GAUGE_W, GAUGE_H, white)
        val slot = SLOT_FOOT - SLOT_HEAD
        val filled = (slot * sail).roundToInt().coerceIn(0, slot)
        if (filled > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SAIL_FILL, GAUGE_W, GAUGE_H, 0, SLOT_FOOT - filled, gx, gy + SLOT_FOOT - filled, GAUGE_W, filled, white)
        }
        val markY = (gy + SLOT_FOOT - slot * cog.sailOrder - MARK_H / 2f).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SAIL_MARK, gx + GAUGE_W / 2 - MARK_W / 2, markY, MARK_W, MARK_H, white)
        // The anchor under it, glowing gold as it goes down
        val lit = Mth.lerp(partial, anchorLitO, anchorLit)
        val ax = gx + GAUGE_W / 2 - ANCHOR / 2
        val ay = gy + GAUGE_H + 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ANCHOR_UP, ax, ay, ANCHOR, ANCHOR, ARGB.white(a * (1f - lit)))
        if (lit > 0.01f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ANCHOR_DOWN, ax, ay, ANCHOR, ANCHOR, ARGB.white(a * lit))

        // Her name over the rose
        cog.customName?.let { name ->
            val shown = Ui.heading(name)
            graphics.text(font, shown, (cx - font.width(shown) / 2f).roundToInt(), y0 - 11 + Ui.HEADING_RISE, ARGB.color(a, GOLD), true)
        }
        // Under it, her speed, or why she's making none
        val way = Mth.lerp(partial, speedO, speed)
        val status: Component
        var color = 0xFFFFFF
        when {
            cog.held -> {
                status = Component.translatable("hud.guildmark.ship.held")
                color = WARN
            }
            cog.anchored -> {
                status = Component.translatable("hud.guildmark.ship.anchored")
                color = GOLD
            }
            inIt && abs(way) < 0.05f -> {
                status = Component.translatable("hud.guildmark.ship.head_to_wind")
                color = WARN
            }
            way < -0.01f -> status = Component.translatable("hud.guildmark.ship.astern")
            else -> status = Component.translatable("hud.guildmark.ship.knots", String.format(Locale.ROOT, "%.1f", Wind.knots(abs(way).toDouble())))
        }
        val sy = y0 + ROSE_SIZE + 3
        graphics.text(font, status, (cx - font.width(status) / 2f).roundToInt(), sy, ARGB.color(a, color), true)
        // Her hull, draining when she's holed
        val barY = sy + 11
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_FRAME, x0, barY, ROSE_SIZE, 9, white)
        val inner = ROSE_SIZE - 6
        val shownW = (inner * hull.coerceIn(0f, 1f)).roundToInt()
        val lostW = (inner * hullLost.coerceIn(0f, 1f)).roundToInt()
        if (lostW > shownW) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_TRAIL, x0 + 3, barY + 3, lostW, 3, white)
        if (shownW > 0) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_HULL, x0 + 3, barY + 3, shownW, 3, white)
    }

    /** Draws [draw] about ([cx], [cy]) turned [degrees] clockwise. */
    private inline fun turned(graphics: GuiGraphicsExtractor, cx: Float, cy: Float, degrees: Float, draw: () -> Unit) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(cx, cy)
        graphics.pose().rotate(degrees * Mth.DEG_TO_RAD)
        draw()
        graphics.pose().popMatrix()
    }
}
