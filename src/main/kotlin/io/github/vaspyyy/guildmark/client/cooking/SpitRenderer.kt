package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.vessel.Call
import io.github.vaspyyy.guildmark.cooking.vessel.SpitBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The Spit seen (living-world/cook/design.md 1.6): two iron uprights, a rod through up to six joints with a crank at one end, over
 * a fire pit and a drip tray.
 *
 *  - the rod rests in one of three notches and slides between them with the heat, a little past and back, as a rod dropped into a
 *    rest does; it turns about its own line, a quarter turn for each click of the crank, springing a hair past and settling, or
 *    slowly and steadily while a jack or a keeper turns it, and the crank handle turns with it;
 *  - the joints are skewered on the rod and turn with it, each standing on the rod as a pair of pictures at right angles, so a
 *    face shows from every way round, and the light plays over them as they come round; they brown with the dish-clock from
 *    pale to gold to dark, and warm toward gold while a TURN is asked for;
 *  - a joint put on arcs in from the cook's side and is skewered with a squash; a joint carved lifts off and goes;
 *  - fat gathers on the food, hangs, lets go and falls to the tray, where it splashes: a drip at a time, more often the hotter it
 *    is, and, while a TURN is asked for, all on one side, bigger and redder;
 *  - a burnt spit is blackened over.
 */
class SpitRenderer(context: BlockEntityRendererProvider.Context) : StationRenderer<SpitBlockEntity>(context) {

    override fun draw(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val p = state.partial
        val t = anim.time(p)
        val ph = anim.wander(p)
        val light = state.lightCoords

        poseStack.pushPose()
        Gfx.enter(poseStack, anim.facing)

        // The rod in its rest (1.6 px up at notch 0, level at 1, 1.6 down at 2), turning about its own line
        val heat = anim.heat.at(p).coerceIn(-0.3f, 2.3f)
        val dy = CookGeo.REST_DY[0] + (CookGeo.REST_DY[2] - CookGeo.REST_DY[0]) * (heat / 2f)
        val rodDeg = anim.rod.at(p)
        poseStack.pushPose()
        Gfx.at(poseStack, 0f, dy + CookGeo.AXIS_Y, CookGeo.AXIS_Z)
        poseStack.rotateDegrees(Axis.XP, rodDeg)
        Gfx.at(poseStack, 0f, -CookGeo.AXIS_Y, -CookGeo.AXIS_Z)
        CookPieces.solid(collector, poseStack, CookPieces.SPIT_ROD, light)
        CookPieces.solid(collector, poseStack, CookPieces.SPIT_CRANK, light)
        poseStack.popPose()

        joints(state, anim, poseStack, collector, t, ph, CookGeo.AXIS_Y + dy, Math.toRadians(rodDeg.toDouble()).toFloat())
        drips(state, anim, poseStack, collector, t)

        val crust = anim.crust.at(p)
        if (crust > 0.02f) {
            if (crust > 0.98f) CookPieces.solid(collector, poseStack, CookPieces.SPIT_BURNT, light)
            else CookPieces.faded(collector, poseStack, CookPieces.SPIT_BURNT, light, crust, 3)
        }
        poseStack.popPose()
    }

    private fun joints(
        state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, ph: Float, axisY: Float, rodRad: Float,
    ) {
        val v = anim.view
        val p = state.partial
        val n = min(v.items.size, 6)
        val leaving = anim.leaving
        if (n == 0 && leaving.isEmpty()) return
        val level = state.level
        val spokes = Spokes(n)
        val loose = Sprites(n + leaving.size)

        // Brown with the clock, warm while a TURN is asked for
        var base = Gfx.browned(anim.brown.at(p))
        if (anim.callNow == Call.TURN) {
            val warm = (0.12f + 0.3f * anim.callProgress(t)) * (0.7f + 0.3f * sin(ph * 0.25f))
            base = Gfx.lerp(base, Gfx.argb(1f, 1f, 0.82f, 0.38f), warm)
        }
        val fly = StationAnim.PLOP_FLY.toFloat()

        for (i in 0 until n) {
            val id = v.items[i]
            val flat = CookItems.flat(id, resolver, level) ?: continue
            val x = CookGeo.JOINT_X[i]
            var size = 1f
            var plop: Plop? = null
            for (q in anim.plops) {
                val best = plop
                if (q.index == i && (best == null || q.at > best.at)) plop = q
            }
            if (plop != null) {
                val age = (t - plop.at).toFloat()
                if (age < 0f) continue
                if (age < fly) {
                    // In the air from the cook's side, tumbling, to the rod
                    val u = age / fly
                    val sx = 8f + plop.dx * 6.5f
                    val sz = CookGeo.AXIS_Z + plop.dz * 6.5f
                    val sy = axisY + 5f
                    loose.add(
                        flat, sx + (x - sx) * u, sy + (axisY - sy) * u + 2.4f * sin(PI.toFloat() * u), sz + (CookGeo.AXIS_Z - sz) * u,
                        u * 7f, 0.5f * (1f - u), 0f, HR * (1.2f - 0.2f * u) * min(1f, 0.4f + 2.5f * u), base,
                    )
                    continue
                }
                // Skewered: a squash that settles
                size = 1f + 0.3f * exp(-(age - fly) / 4f)
            }
            spokes.add(flat, x, axisY, CookGeo.AXIS_Z, HX * size, HR * size, rodRad + i * 0.9f, base)
        }

        // A joint carved: it lifts off the rod toward the cook and goes
        for (l in leaving) {
            val flat = CookItems.flat(l.item, resolver, level) ?: continue
            val u = ((t - l.at).toFloat() / StationAnim.LEAVE_TICKS).coerceIn(0f, 1f)
            loose.add(
                flat, CookGeo.JOINT_X[l.index.coerceIn(0, 5)], axisY + 3.2f * sin(u * PI.toFloat() * 0.5f), CookGeo.AXIS_Z - 3.5f * u,
                u * 2f, 0.6f * u, 0f, HR * (1f - u * u), base,
            )
        }
        spokes.submit(state, collector, poseStack)
        loose.submit(state, collector, poseStack)
    }

    /** Fat: a drop gathers on the food, hangs, lets go, falls faster and faster and splashes on the tray. */
    private fun drips(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double) {
        val v = anim.view
        val p = state.partial
        if (v.items.isEmpty() || !v.lit || !state.near) return
        if (anim.energy.at(p) < 0.2f) return
        val turn = anim.callNow == Call.TURN
        val k0 = Math.floorDiv(t.toLong() - StationAnim.DRIP_FALL, StationAnim.DRIP_EVERY)
        state.layer(collector, 2, poseStack, Gfx.SURFACE) { pen ->
            for (k in (k0 - 1)..(k0 + 1)) {
                if (!anim.dripSlot(k)) continue
                val land = anim.dripLanding(k)
                val place = anim.dripPlace(k)
                val x = place[0]
                val z = place[1]
                val dt = (t - (land - StationAnim.DRIP_FALL)).toFloat()
                val big = if (turn) 1.5f else 1f
                val colour = if (turn) Gfx.argb(0.95f, 1f, 0.58f, 0.16f) else Gfx.argb(0.92f, 1f, 0.76f, 0.3f)
                if (dt < 0f && dt > -HANG) {
                    // Gathering on the food
                    val g = (dt + HANG) / HANG
                    val s = (0.1f + 0.22f * g) * big
                    pen.blob(x, CookGeo.DRIP_FROM - 0.25f * g, z, s, s, 0f, colour)
                } else if (dt in 0f..StationAnim.DRIP_FALL.toFloat()) {
                    // Falling, faster and faster
                    val u = dt / StationAnim.DRIP_FALL
                    val y = CookGeo.DRIP_FROM - (CookGeo.DRIP_FROM - CookGeo.DRIP_TO) * u * u
                    pen.line(x, y, y + 0.35f + 1.2f * u, z, 0.1f * big, colour)
                } else {
                    // Splashing in the tray
                    val since = (t - land).toFloat()
                    if (since in 0f..SPLASH) {
                        val u = since / SPLASH
                        val r = 0.2f + 0.9f * u
                        pen.ring(x, CookGeo.DRIP_TO + 0.08f, z, r - 0.1f, r + 0.1f, 0f, Gfx.TAU, 8, Gfx.argb(0.7f * (1f - u), 1f, 0.8f, 0.4f))
                        if (u < 0.35f) pen.blob(x, CookGeo.DRIP_TO + 0.1f, z, 0.35f * big, 0.35f * big, 0f, Gfx.argb(0.8f * (1f - u / 0.35f), 1f, 0.9f, 0.6f))
                    }
                }
            }
        }
    }

    companion object {
        /** A joint on the rod: this far from its middle along the rod and this far out from it each way, px. */
        private const val HX = 1.15f
        private const val HR = 1.7f

        /** Ticks a drop takes to gather before it falls, and a splash lasts. */
        private const val HANG = 9f
        private const val SPLASH = 8f
    }
}
