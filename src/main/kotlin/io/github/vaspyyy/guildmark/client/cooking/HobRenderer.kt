package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.vessel.Call
import io.github.vaspyyy.guildmark.cooking.vessel.HobBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The Hob seen (living-world/cook/design.md 1.6): an iron pan over a firebox with up to six pieces laid flat in it.
 *
 *  - each piece arcs in from the side of the cook who put it there, lands with a bounce and a jolt of the pan, and sizzles;
 *  - it browns with the dish-clock, pale, then gold at the ripe moment, a deep brown past the window and black when it burns
 *    (the view does not say what the piece is turning into, so it is the same picture, browned, and not a cross-fade into the dish);
 *  - a TURN shows a golden edge on one side of each piece, which grows until it is answered; answered, each piece is flipped
 *    through a half turn in its turn, three ticks apart, and a speck of fat leaps where it lands;
 *  - a film of oil lies on the pan, marbled, glinting, and it spits tiny bubbles round the pieces while it is hot;
 *  - the pan shivers a little when a piece lands or is flipped;
 *  - what is lifted out sinks no more than it rises toward the cook and goes, and a burnt hob is blackened over.
 */
class HobRenderer(context: BlockEntityRendererProvider.Context) : StationRenderer<HobBlockEntity>(context) {

    override fun draw(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val p = state.partial
        val t = anim.time(p)
        val ph = anim.wander(p)
        val light = state.lightCoords

        poseStack.pushPose()
        val shake = shake(anim, t)
        if (shake != 0f) {
            Gfx.at(poseStack, 8f, 10f, 8f)
            poseStack.rotateDegrees(Axis.XP, shake)
            poseStack.rotateDegrees(Axis.ZP, shake * 0.6f)
            Gfx.at(poseStack, -8f, -10f, -8f)
        }
        CookPieces.solid(collector, poseStack, CookPieces.PAN, light)
        pieces(state, anim, poseStack, collector, t, ph)
        oil(state, anim, poseStack, collector, t, ph)
        poseStack.popPose()

        burnt(anim, p, poseStack, collector, light)
    }

    /** The pan's shiver in degrees: a damped ring from each landing and each flip. */
    private fun shake(anim: StationAnim, t: Double): Float {
        var s = 0f
        for (q in anim.plops) {
            val a = (t - q.at - StationAnim.PLOP_FLY).toFloat()
            if (a in 0f..30f) s += 1.5f * exp(-a / 6f) * sin(a * 0.9f)
        }
        val since = (t - anim.flipAt).toFloat()
        if (since in 0f..70f) {
            val n = min(anim.view.items.size, 6)
            for (i in 0 until n) {
                val a = since - StationAnim.FLIP_GAP * i - StationAnim.FLIP_LEN
                if (a in 0f..28f) s += 1.1f * exp(-a / 5f) * sin(a * 1.1f)
            }
        }
        return s.coerceIn(-3.5f, 3.5f)
    }

    private fun pieces(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, ph: Float) {
        val v = anim.view
        val p = state.partial
        val n = min(v.items.size, 6)
        val leaving = anim.leaving
        if (n == 0 && leaving.isEmpty()) return
        val sprites = Sprites(n * 2 + leaving.size)
        val level = state.level
        val brown = anim.brown.at(p)
        val energy = anim.energy.at(p)
        val base = Gfx.browned(brown)
        val turnWanted = anim.callNow == Call.TURN
        val progress = if (turnWanted) anim.callProgress(t) else 0f
        val flipSince = (t - anim.flipAt).toFloat()
        val fly = StationAnim.PLOP_FLY.toFloat()

        for (i in 0 until n) {
            val id = v.items[i]
            val flat = CookItems.flat(id, resolver, level) ?: continue
            val slot = CookGeo.PAN_SLOTS[i]
            val seed = Mix.unit(id, i + 40)
            var x = slot[0] + 0.1f * sin(ph * 0.05f + seed * 9f) * (0.3f + energy)
            var z = slot[1] + 0.1f * sin(ph * 0.04f + seed * 7f) * (0.3f + energy)
            var y = CookGeo.PAN_Y + 0.14f
            var yaw = seed * Gfx.TAU
            var tilt = 0f
            var half = HALF
            var plop: Plop? = null
            for (q in anim.plops) {
                val best = plop
                if (q.index == i && (best == null || q.at > best.at)) plop = q
            }
            if (plop != null) {
                val age = (t - plop.at).toFloat()
                if (age < 0f) continue
                if (age < fly) {
                    // In the air from over the pan's rim on the cook's side
                    val u = age / fly
                    val sx = 8f + plop.dx * 7.5f
                    val sz = 8f + plop.dz * 7.5f
                    x = sx + (x - sx) * u
                    z = sz + (z - sz) * u
                    y = (CookGeo.PAN_Y + 6.2f) + (y - CookGeo.PAN_Y - 6.2f) * u + 2.6f * sin(PI.toFloat() * u)
                    half = HALF * (1.3f - 0.3f * u) * min(1f, 0.4f + 2.5f * u)
                    yaw += u * 8f
                    tilt = 0.5f * (1f - u)
                } else {
                    // It lands with a bounce or two
                    val a = age - fly
                    y += 1.1f * exp(-a / 4.5f) * kotlin.math.abs(sin(a * 0.8f))
                    tilt = 0.18f * exp(-a / 6f) * sin(a * 0.9f)
                }
            }
            // A flip: a half turn over the piece's own middle, up and down
            var turned = false
            val u = (flipSince - StationAnim.FLIP_GAP * i) / StationAnim.FLIP_LEN
            if (u in 0f..1f) {
                tilt = PI.toFloat() * u
                y += 3.6f * sin(PI.toFloat() * u)
                turned = true
            }
            // Pale to gold to dark; the golden edge of a TURN grows along one side until it is answered (and stays until the piece goes over)
            val edge = when {
                turned -> 0f
                turnWanted -> 0.15f + 0.4f * progress
                flipSince in 0f..(StationAnim.FLIP_GAP * 6f + StationAnim.FLIP_LEN) && flipSince < StationAnim.FLIP_GAP * i -> 0.5f
                else -> 0f
            }
            if (edge > 0.01f) {
                val cut = 1f - edge
                sprites.add(flat, x, y, z, yaw, tilt, 0f, half, base, 0f, cut)
                val glow = 0.85f + 0.15f * sin(ph * 0.3f + i)
                sprites.add(flat, x, y + 0.01f, z, yaw, tilt, 0f, half, Gfx.argb(1f, 1f * glow, 0.78f * glow, 0.34f * glow), cut, 1f)
            } else {
                sprites.add(flat, x, y, z, yaw, tilt, 0f, half, base)
            }
        }

        // What was lifted out rises toward the cook and goes
        for (l in leaving) {
            val flat = CookItems.flat(l.item, resolver, level) ?: continue
            val slot = CookGeo.PAN_SLOTS[l.index.coerceIn(0, 5)]
            val u = ((t - l.at).toFloat() / StationAnim.LEAVE_TICKS).coerceIn(0f, 1f)
            sprites.add(
                flat, slot[0], CookGeo.PAN_Y + 0.14f + 4.5f * sin(u * PI.toFloat() * 0.5f), slot[1] - 2.5f * u, u * 3f, 0.4f * u, 0f,
                HALF * (1f - u * u), base,
            )
        }
        sprites.submit(state, collector, poseStack)
    }

    /** The pan's film of oil, marbled and glinting, and the specks it spits round the pieces while it is hot. */
    private fun oil(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, ph: Float) {
        val p = state.partial
        val energy = anim.energy.at(p)
        val n = min(anim.view.items.size, 6)
        if (energy < 0.1f || (n == 0 && energy < 0.3f) || !state.near) return
        val heat = Gfx.smooth((energy - 0.1f) / 0.5f)
        val crust = anim.crust.at(p)
        val dark = 1f - 0.6f * crust
        val y = CookGeo.PAN_Y + 0.05f
        val spin = ph * 0.004f
        val close = state.close
        state.layer(collector, 2, poseStack, Gfx.SURFACE) { pen ->
            pen.blob(8f, y, 8f, 4.7f, 4.7f, spin, Gfx.argb(0.3f * heat, 0.86f * dark, 0.62f * dark, 0.24f * dark))
            if (close) {
                // A glint that drifts over the oil
                val a = ph * 0.017f
                pen.flatColour(8f + kotlin.math.cos(a) * 2.6f, y + 0.03f, 8f + sin(a * 1.3f) * 2.2f, 0.4f, 0.4f, 0.785f, Gfx.argb(0.5f * heat, 1f, 0.96f, 0.8f))
                if (n > 0) specks(pen, anim, ph, y + 0.07f, n, heat)
            }
        }
    }

    /** Small bright specks of fat that rise round the pieces and break, each with its own rhythm. */
    private fun specks(pen: Pen, anim: StationAnim, ph: Float, y: Float, n: Int, heat: Float) {
        val count = Math.round(7 * heat)
        for (i in 0 until count) {
            val period = (8f + 6f * Mix.unit(i, 51)) / (0.5f + heat)
            val clock = ph / period + Mix.unit(i, 52)
            val cycle = floor(clock).toInt()
            val u = clock - cycle
            val slot = CookGeo.PAN_SLOTS[(Mix.unit(i, cycle * 2 + 53) * 5.99f).toInt().coerceIn(0, 5)]
            val a = Mix.unit(i, cycle * 2 + 54) * Gfx.TAU
            val x = slot[0] + kotlin.math.cos(a) * 1.5f
            val z = slot[1] + sin(a) * 1.4f
            val s = 0.14f + 0.1f * Mix.unit(i, cycle * 2 + 55)
            val grow = if (u < 0.7f) Gfx.smooth(u / 0.7f) else 1f + (u - 0.7f) * 2f
            val alpha = if (u < 0.7f) 0.75f else 0.75f * (1f - (u - 0.7f) / 0.3f)
            pen.blob(x, y, z, s * grow, s * grow, 0f, Gfx.argb(alpha * heat, 1f, 0.92f, 0.62f))
        }
    }

    private fun burnt(anim: StationAnim, p: Float, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int) {
        val crust = anim.crust.at(p)
        if (crust < 0.02f) return
        if (crust > 0.98f) CookPieces.solid(collector, poseStack, CookPieces.HOB_BURNT, light)
        else CookPieces.faded(collector, poseStack, CookPieces.HOB_BURNT, light, crust, 3)
    }

    companion object {
        /** A piece's picture is this far from its middle to each edge, px (the pan's slots are 3 px apart). */
        private const val HALF = 1.3f
    }
}
