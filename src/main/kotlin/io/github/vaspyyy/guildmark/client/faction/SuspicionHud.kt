package io.github.vaspyyy.guildmark.client.faction

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.faction.SuspicionPayload
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.gui.GuiLayer
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sin

/**
 * The eye over the crosshair on a heist: it opens as the watcher nearest to catching you grows suspicious, its white
 * rising with how sure they are, gold to amber to red, its pupil turned toward them (wandering when nobody's looking
 * your way). Past half it pulses; when they raise the alarm it flares and shakes. It closes and fades as they forget
 * you, and when the heist is over.
 */
object SuspicionHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "suspicion")
    private val EYE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/suspicion_eye")
    private val FILL = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/suspicion_fill")
    private val PUPIL = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/suspicion_pupil")
    private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hud/suspicion_glow")

    private const val W = 20
    private const val H = 12
    /** How far above the crosshair the eye sits. */
    private const val RISE = 26
    private const val CALM = 0xE8DCC0
    private const val WARY = 0xE8A040
    private const val SURE = 0xD83A2A
    /** Ticks the eye flares and shakes when the alarm is raised. */
    private const val ALARM_TICKS = 30

    /** What the server last said: how suspicious (0 to 100), and who. */
    private var amount = 0
    private var watcher = -1
    private var ticks = 0
    private var alarm = 0

    // Eased toward what the server said, with last tick's values to draw between
    private var shown = 0f
    private var shownO = 0f
    private var open = 0f
    private var openO = 0f
    private var alpha = 0f
    private var alphaO = 0f
    private var pupil = 0f
    private var pupilO = 0f

    fun onSuspicion(payload: SuspicionPayload) {
        if (payload.amount >= 100 && amount < 100) alarm = ALARM_TICKS
        amount = payload.amount.coerceIn(0, 100)
        watcher = payload.watcher
    }

    fun reset() {
        amount = 0
        watcher = -1
        alarm = 0
        shown = 0f
        shownO = 0f
        open = 0f
        openO = 0f
        alpha = 0f
        alphaO = 0f
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.isPaused) return
        ticks++
        shownO = shown
        openO = open
        alphaO = alpha
        pupilO = pupil
        if (alarm > 0) alarm--
        val showing = amount > 0 || alarm > 0
        shown += (amount - shown) * 0.25f
        // Half-closed at the first doubt, wide open by the time they're sure something's wrong
        val wantOpen = if (!showing) 0.1f else 0.3f + 0.7f * (amount / 50f).coerceAtMost(1f)
        open += (wantOpen - open) * 0.2f
        val wantAlpha = if (showing) 1f else 0f
        alpha += (wantAlpha - alpha) * if (wantAlpha > alpha) 0.35f else 0.1f
        // The pupil turns toward whoever it is, left or right of where you're looking; nobody, and it searches
        val who = minecraft.level?.getEntity(watcher)
        val want = if (who != null) {
            val yaw = Math.toDegrees(atan2(-(who.x - player.x), who.z - player.z)).toFloat()
            (Mth.wrapDegrees(yaw - player.yRot) / 60f).coerceIn(-1f, 1f)
        } else sin(ticks * 0.07f) * 0.6f
        pupil += (want - pupil) * 0.3f
    }

    private fun color(amount: Float): Int =
        if (amount < 50f) ARGB.srgbLerp(amount / 50f, CALM, WARY) else ARGB.srgbLerp((amount - 50f) / 50f, WARY, SURE)

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden || minecraft.player == null) return
        val partial = delta.getGameTimeDeltaPartialTick(true)
        val a = Mth.lerp(partial, alphaO, alpha)
        if (a < 0.02f) return
        val level = Mth.lerp(partial, shownO, shown).coerceIn(0f, 100f)
        val lids = Mth.lerp(partial, openO, open).coerceIn(0.05f, 1f)
        val look = Mth.lerp(partial, pupilO, pupil)
        val tint = color(level) and 0xFFFFFF
        val cx = graphics.guiWidth() / 2
        val cy = graphics.guiHeight() / 2 - RISE
        var x = cx - W / 2
        val y = cy - H / 2
        // The alarm shakes it, dying away
        if (alarm > 0) x += ((ticks * 7 % 5) - 2) * alarm / ALARM_TICKS

        val pose = graphics.pose()
        pose.pushMatrix()
        // The lids part as it opens: the eye drawn squeezed toward its middle line
        pose.translate(cx.toFloat(), cy.toFloat())
        pose.scale(1f, lids)
        pose.translate(-cx.toFloat(), -cy.toFloat())

        // A halo: pulsing once they're wary, flaring with the alarm
        val flare = alarm / ALARM_TICKS.toFloat()
        val halo = when {
            alarm > 0 -> 0.5f + 0.5f * flare
            level >= 50f -> 0.25f + 0.2f * sin(ticks * 0.3f + partial * 0.3f) * ((level - 50f) / 50f + 0.3f)
            else -> 0f
        }
        if (halo > 0.02f) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, x - 4, y - 4, W + 8, H + 8, ARGB.color(a * halo, tint))
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, EYE, x, y, W, H, ARGB.color(a, 0xFFFFFF))

        // The white rising, a row at a time, the row it's partway through drawn faint
        val rows = H * level / 100f
        val full = floor(rows).toInt()
        val part = rows - full
        if (full > 0) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FILL, W, H, 0, H - full, x, y + H - full, W, full, ARGB.color(a, tint))
        if (part > 0.05f && full < H) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FILL, W, H, 0, H - full - 1, x, y + H - full - 1, W, 1, ARGB.color(a * part, tint))
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PUPIL, x + W / 2 - 2 + Math.round(look * 5f), y + H / 2 - 4, 4, 8, ARGB.color(a, 0xFFFFFF))
        pose.popMatrix()
    }
}
