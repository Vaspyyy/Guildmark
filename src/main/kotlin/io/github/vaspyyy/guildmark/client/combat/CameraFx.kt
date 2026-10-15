package io.github.vaspyyy.guildmark.client.combat

import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.combat.Move
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.event.ViewportEvent
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The camera's share of combat feel: shakes on impact, a tilt through rolls, a small punch of field of view. */
object CameraFx {
    /** 0 to 1; the shake grows with its square, so small knocks stay small. */
    private var trauma = 0f
    /** Which way the last roll tilted the view: -1 left, 1 right. */
    private var rollSide = 0f
    private var punch = 0f
    private var punchPrev = 0f

    private fun enabled(): Boolean = GuildmarkClientConfig.SPEC.isLoaded && GuildmarkClientConfig.CAMERA_SHAKE.get()

    fun shake(amount: Float) {
        if (enabled()) trauma = min(1f, trauma + amount)
    }

    /** A quick widening of the view that eases back, for a sudden burst of speed. */
    fun punch(amount: Float) {
        punch = max(punch, amount)
    }

    /** A roll or backstep began, [relativeYaw] degrees from where the player faces. */
    fun onDodge(move: Move, relativeYaw: Float) {
        rollSide = if (move == Move.BACKSTEP) 0f else sin(Math.toRadians(Mth.wrapDegrees(relativeYaw).toDouble())).toFloat()
        punch = max(punch, 0.06f)
    }

    fun tick() {
        trauma = max(0f, trauma - 0.045f)
        punchPrev = punch
        punch = max(0f, punch - 0.012f)
    }

    fun onAngles(event: ViewportEvent.ComputeCameraAngles) {
        if (!CombatClient.active() || !enabled()) return
        val partial = event.partialTick
        val time = CombatClient.ticks + partial
        val shake = trauma * trauma
        if (shake > 0f) {
            event.yaw = event.yaw + shake * 2.2f * noise(time * 1.7f, 1.3f)
            event.pitch = event.pitch + shake * 2.2f * noise(time * 1.9f, 4.1f)
            event.roll = event.roll + shake * 3.5f * noise(time * 2.3f, 7.7f)
        }
        // In first person the body can't be seen rolling, so the view rolls a little with it
        if (Minecraft.getInstance().options.cameraType != CameraType.FIRST_PERSON) return
        val a = CombatClient.action ?: return
        if (a.done()) return
        val phase = (a.time(partial) / a.move.length).coerceIn(0f, 1f)
        val arc = sin(phase * PI).toFloat()
        when {
            a.move == Move.BACKSTEP -> event.pitch = event.pitch - arc * 4f
            a.move.isRoll -> {
                event.roll = event.roll - rollSide * arc * 12f
                event.pitch = event.pitch + arc * 7f
            }
            a.move == Move.STAGGER || a.move == Move.GUARD_BREAK -> event.pitch = event.pitch - arc * 5f
        }
    }

    fun onFov(event: ViewportEvent.ComputeFov) {
        if (!CombatClient.active()) return
        val p = Mth.lerp(event.partialTick, punchPrev, punch)
        if (p > 0f) event.fov = event.fov * (1f + p)
    }

    /** Smooth noise between -1 and 1 from a few out-of-step waves. */
    private fun noise(t: Float, seed: Float): Float =
        (sin(t + seed) * 0.6f + sin(t * 2.17f + seed * 1.9f) * 0.3f + sin(t * 4.31f + seed * 3.1f) * 0.1f)
}
