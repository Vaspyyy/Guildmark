package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.vessel.Call
import io.github.vaspyyy.guildmark.cooking.vessel.OvenBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The Bake oven seen (living-world/cook/design.md 1.6): a domed oven with a door that swings, trays that slide out of it, and
 * a window that glows.
 *
 *  - the door is a piece on a hinge: it swings open (past its stop a hair, and back) while the cook's cursor is on it with
 *    food in hand, or a townsperson is at it, or the server says it is open (a click), and shuts when they look away; the
 *    trays slide out as it opens and in as it shuts;
 *  - shut, the door's window glows orange and deepens with the heat (the glowing door fades in over the dark one, and flares when
 *    the fat catches), and goes dark again as the door opens;
 *  - what is put in arcs in from the cook's side and lands on a tray (a puff of crumbs), browns with the dish-clock from pale to
 *    gold to dark, shows a golden edge for a TURN until it is turned, and is then flipped through a half turn in its turn;
 *  - a sixth item brings out the upper tray, which fades up on its rails;
 *  - what is lifted out rises toward the cook on the peel and goes; a burnt oven is blackened over.
 */
class OvenRenderer(context: BlockEntityRendererProvider.Context) : StationRenderer<OvenBlockEntity>(context) {

    override fun draw(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val p = state.partial
        val t = anim.time(p)
        val ph = anim.wander(p)
        val light = state.lightCoords

        poseStack.pushPose()
        Gfx.enter(poseStack, anim.facing)

        // The door: on its hinge, the glowing version over the dark one while it is hot and shut
        val d = anim.door.at(p).coerceIn(0f, 1.05f)
        poseStack.pushPose()
        Gfx.at(poseStack, CookGeo.HINGE_X, 0f, CookGeo.HINGE_Z)
        poseStack.rotateDegrees(Axis.YP, d * CookGeo.DOOR_MAX)
        Gfx.at(poseStack, -CookGeo.HINGE_X, 0f, -CookGeo.HINGE_Z)
        CookPieces.solid(collector, poseStack, CookPieces.OVEN_DOOR, light)
        val hot = anim.glow.at(p) * (1f - Gfx.smooth(d * 3f))
        if (hot > 0.02f) {
            Gfx.at(poseStack, 0f, 0f, -0.025f)
            CookPieces.faded(collector, poseStack, CookPieces.OVEN_DOOR_HOT, light, hot, 4)
        }
        poseStack.popPose()

        trays(state, anim, poseStack, collector, t, ph)

        val crust = anim.crust.at(p)
        if (crust > 0.02f) {
            if (crust > 0.98f) CookPieces.solid(collector, poseStack, CookPieces.OVEN_BURNT, light)
            else CookPieces.faded(collector, poseStack, CookPieces.OVEN_BURNT, light, crust, 3)
        }
        poseStack.popPose()
    }

    private fun trays(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, ph: Float) {
        val p = state.partial
        val v = anim.view
        val light = state.lightCoords
        val slide = anim.tray.at(p) * CookGeo.TRAY_SLIDE
        val upper = anim.upper.at(p)

        // The trays themselves, sliding on their rails
        poseStack.pushPose()
        Gfx.at(poseStack, 0f, 0f, -slide)
        CookPieces.solid(collector, poseStack, CookPieces.OVEN_TRAY, light)
        poseStack.popPose()
        if (upper > 0.02f) {
            poseStack.pushPose()
            Gfx.at(poseStack, 0f, CookGeo.TRAY_LIFT, -slide)
            if (upper > 0.98f) CookPieces.solid(collector, poseStack, CookPieces.OVEN_TRAY, light)
            else CookPieces.faded(collector, poseStack, CookPieces.OVEN_TRAY, light, upper, 3)
            poseStack.popPose()
        }

        // What lies on them
        val n = min(v.items.size, 10)
        val leaving = anim.leaving
        if (n == 0 && leaving.isEmpty()) return
        val sprites = Sprites(n * 2 + leaving.size)
        val level = state.level
        val base = Gfx.browned(anim.brown.at(p))
        val energy = anim.energy.at(p)
        val turnWanted = anim.callNow == Call.TURN
        val progress = if (turnWanted) anim.callProgress(t) else 0f
        val flipSince = (t - anim.flipAt).toFloat()
        val fly = StationAnim.PLOP_FLY.toFloat()

        for (i in 0 until n) {
            val id = v.items[i]
            val flat = CookItems.flat(id, resolver, level) ?: continue
            val slot = CookGeo.TRAY_SLOTS[i % 5]
            val lift = if (i >= 5) CookGeo.TRAY_LIFT else 0f
            val seed = Mix.unit(id, i + 60)
            var x = slot[0]
            var z = slot[1] - slide
            var y = CookGeo.TRAY_ITEM_Y + lift + 0.12f
            var yaw = seed * 0.8f - 0.4f
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
                    // From the cook's side, through the door, onto the tray
                    val u = age / fly
                    val sx = 8f + plop.dx * 6f
                    val sz = 2f + plop.dz * 5f - slide
                    x = sx + (x - sx) * u
                    z = sz + (z - sz) * u
                    y += 4.5f * (1f - u) + 1.6f * sin(PI.toFloat() * u)
                    half = HALF * (1.25f - 0.25f * u) * min(1f, 0.4f + 2.5f * u)
                    yaw += u * 6f
                    tilt = 0.4f * (1f - u)
                } else {
                    val a = age - fly
                    y += 0.7f * exp(-a / 4f) * abs(sin(a * 0.8f))
                    tilt = 0.12f * exp(-a / 6f) * sin(a * 0.9f)
                }
            }
            var turned = false
            val u = (flipSince - StationAnim.FLIP_GAP * i) / StationAnim.FLIP_LEN
            if (u in 0f..1f) {
                tilt = PI.toFloat() * u
                y += 3.2f * sin(PI.toFloat() * u)
                turned = true
            }
            // A heat shimmer on the food while the oven is roaring: it breathes a hair
            y += 0.03f * sin(ph * 0.12f + seed * 6f) * energy
            val edge = when {
                turned -> 0f
                turnWanted -> 0.15f + 0.4f * progress
                flipSince in 0f..(StationAnim.FLIP_GAP * 10f + StationAnim.FLIP_LEN) && flipSince < StationAnim.FLIP_GAP * i -> 0.5f
                else -> 0f
            }
            if (edge > 0.01f) {
                val cut = 1f - edge
                sprites.add(flat, x, y, z, yaw, tilt, 0f, half, base, 0f, cut)
                val glow = 0.85f + 0.15f * sin(ph * 0.3f + i)
                sprites.add(flat, x, y + 0.01f, z, yaw, tilt, 0f, half, Gfx.argb(1f, glow, 0.78f * glow, 0.34f * glow), cut, 1f)
            } else {
                sprites.add(flat, x, y, z, yaw, tilt, 0f, half, base)
            }
        }

        // What the peel took: it rises toward the cook and goes
        for (l in leaving) {
            val flat = CookItems.flat(l.item, resolver, level) ?: continue
            val slot = CookGeo.TRAY_SLOTS[l.index % 5]
            val lift = if (l.index >= 5) CookGeo.TRAY_LIFT else 0f
            val u = ((t - l.at).toFloat() / StationAnim.LEAVE_TICKS).coerceIn(0f, 1f)
            sprites.add(
                flat, slot[0], CookGeo.TRAY_ITEM_Y + lift + 0.12f + 3.5f * sin(u * PI.toFloat() * 0.5f), slot[1] - slide - 4f * u, u * 2f, 0.3f * u, 0f,
                HALF * (1f - u * u), base,
            )
        }
        sprites.submit(state, collector, poseStack)
    }

    companion object {
        /** A food's picture is this far from its middle to each edge, px. */
        private const val HALF = 1.2f
    }
}
