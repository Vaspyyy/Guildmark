package io.github.vaspyyy.guildmark.client.cart

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcart
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.Mth
import java.util.WeakHashMap
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Every handcart, drawn as [HandcartRenderer] draws it; and a village pedlar's ([Handcart.pedlar]; Villages, V12a, living-world/villages
 * design 17.2) with what is the pedlar's own on it (PedlarCartMesh, art/scripts/pedlar_cart.py):
 * - **The cart in the pedlar's paint**: blue boards lined in ochre with painted roses, ochre wheels.
 * - **A striped canvas hood** on four posts over the back half of the bed, the rest of it drawn back and gathered at the middle; still.
 * - **A pot, a kettle and a pan on a rail** along the left side, and **a lantern** off the front left post: each swings on its hook, a
 *   pendulum about it in the cart's own frame, worked out tick by tick for each cart ([Swing]): pulled toward plumb wherever the cart is
 *   tipped or leaning (parked on its shafts they hang well off the cart's upright), pushed back as it starts, forward as it stops and out
 *   as it turns, jolted by the road as it rolls; knocking against the canvas, the rail and one another and swinging back, never into them
 *   (the limits are PedlarCartMesh's, which the script proves).
 * - **The lantern lit after dark**, its panes and flame drawn full bright and flickering, catching over a second and a half at dusk.
 * A cart that is not a pedlar's is drawn exactly as HandcartRenderer draws it.
 */
class PedlarCartRenderer(context: EntityRendererProvider.Context) : HandcartRenderer(context) {
    private val dressing: ModelPart = context.bakeLayer(LAYER)
    private val hood: ModelPart = dressing.getChild("hood")
    private val hanging: List<ModelPart> = PedlarCartMesh.HANGING.map { dressing.getChild(it) }
    private val flame: ModelPart = dressing.getChild("lantern_light")

    override fun createRenderState(): HandcartRenderState = PedlarCartRenderState()

    override fun texture(state: HandcartRenderState): Identifier =
        if ((state as? PedlarCartRenderState)?.pedlar == true) TEXTURE else super.texture(state)

    override fun extractRenderState(cart: Handcart, state: HandcartRenderState, partialTicks: Float) {
        super.extractRenderState(cart, state, partialTicks)
        val s = state as? PedlarCartRenderState ?: return
        s.pedlar = cart.pedlar
        if (!s.pedlar) {
            swings.remove(cart)
            return
        }
        // Tipped and leaning as the cart is drawn (a broken one slumped onto its corner: HandcartRenderer.submit)
        val slump = if (state.broken > 0f) HandcartModel.ease(state.broken) else 0f
        val swing = swings.getOrPut(cart) { Swing(cart.id) }
        swing.advance(cart, state.yaw, state.pitch + HandcartModel.ROCK * slump, state.roll - HandcartModel.SLUMP * slump)
        for (i in s.fore.indices) {
            s.fore[i] = Mth.lerp(partialTicks, swing.foreO[i], swing.fore[i])
            s.side[i] = Mth.lerp(partialTicks, swing.sideO[i], swing.side[i])
        }
        val lit = Mth.lerp(partialTicks, swing.litO, swing.lit)
        val t = cart.tickCount + partialTicks
        val k = cart.id * 1.37f
        s.lit = if (lit <= 0f) 0f else lit * (0.88f + 0.07f * sin(t * 0.83f + k) + 0.05f * sin(t * 2.17f + 2f * k))
    }

    /** The hood, the pots and the lantern (lit after dark), in the cart's own space (px, y down), after the load. */
    override fun dressed(state: HandcartRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val s = state as? PedlarCartRenderState ?: return
        if (!s.pedlar) return
        val light = state.lightCoords
        val outline = state.outlineColor
        collector.submitModelPart(hood, poseStack, TYPE, light, OverlayTexture.NO_OVERLAY, null, -1, outline)
        val hooks = PedlarCartMesh.HOOKS
        for ((i, part) in hanging.withIndex()) {
            // Turned about its hook (ModelPart sets itself at its own pivot, so the turn is made about the hook first)
            val x = hooks[3 * i] / 16f
            val y = hooks[3 * i + 1] / 16f
            val z = hooks[3 * i + 2] / 16f
            poseStack.pushPose()
            poseStack.translate(x, y, z)
            poseStack.rotateDegrees(Axis.XP, s.fore[i])
            poseStack.rotateDegrees(Axis.ZP, s.side[i])
            poseStack.translate(-x, -y, -z)
            collector.submitModelPart(part, poseStack, TYPE, light, OverlayTexture.NO_OVERLAY, null, -1, outline)
            if (i == LANTERN && s.lit > LIT_SHOWS) {
                val b = (255f * min(1f, s.lit)).toInt().coerceIn(0, 255)
                collector.submitModelPart(flame, poseStack, TYPE, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, null, ARGB.color(255, b, b, b), outline)
            }
            poseStack.popPose()
        }
    }

    /**
     * One pedlar's cart's pots and lantern, swinging a tick at a time (art/scripts/pedlar_cart.py's `Swing`: the same numbers, the same
     * order), and its lantern catching and going out.
     */
    private class Swing(private val id: Int) {
        private val n = PedlarCartMesh.HANGING.size
        val fore = FloatArray(n)
        val side = FloatArray(n)
        val foreO = FloatArray(n)
        val sideO = FloatArray(n)
        private val vFore = FloatArray(n)
        private val vSide = FloatArray(n)
        var lit = 0f
        var litO = 0f
        private var tick = Int.MIN_VALUE
        private var x = 0.0
        private var z = 0.0
        private var velX = 0.0
        private var velZ = 0.0

        fun advance(cart: Handcart, yaw: Float, tip: Float, lean: Float) {
            val now = cart.tickCount
            val eqFore = plumbFore(tip, lean)
            val eqSide = plumbSide(tip, lean)
            val pending = now - tick
            val dx = if (tick == Int.MIN_VALUE) 0.0 else (cart.x - x) / pending.coerceAtLeast(1)
            val dz = if (tick == Int.MIN_VALUE) 0.0 else (cart.z - z) / pending.coerceAtLeast(1)
            if (tick == Int.MIN_VALUE || pending < 0 || pending > STALE || hypot(dx, dz) > JUMP) {
                // First seen, not seen for a while, or set down somewhere else: hanging still, straight down
                fore.fill(eqFore)
                foreO.fill(eqFore)
                side.fill(eqSide)
                sideO.fill(eqSide)
                vFore.fill(0f)
                vSide.fill(0f)
                velX = 0.0
                velZ = 0.0
                lit = if (night(cart)) 1f else 0f
                litO = lit
                tick = now
                x = cart.x
                z = cart.z
                return
            }
            val r = Math.toRadians(yaw.toDouble())
            while (tick < now) {
                tick++
                step(dx, dz, r, eqFore, eqSide)
                litO = lit
                lit = if (night(cart)) min(1f, lit + 1f / CATCH) else maxOf(0f, lit - 1f / CATCH * 3f)
            }
            x = cart.x
            z = cart.z
        }

        private fun step(dx: Double, dz: Double, yaw: Double, eqFore: Float, eqSide: Float) {
            System.arraycopy(fore, 0, foreO, 0, n)
            System.arraycopy(side, 0, sideO, 0, n)
            // The cart's acceleration this tick (its pace eased a little: the server's steps are not smooth), toward its front and its left
            val nx = velX + (dx - velX) * EASE
            val nz = velZ + (dz - velZ) * EASE
            val ax = nx - velX
            val az = nz - velZ
            velX = nx
            velZ = nz
            val ahead = (-sin(yaw) * ax + cos(yaw) * az).toFloat()
            val left = (cos(yaw) * ax + sin(yaw) * az).toFloat()
            val speed = hypot(velX, velZ).toFloat()
            for (i in 0 until n) {
                val w = 2f * Math.PI.toFloat() / PedlarCartMesh.PERIOD[i]
                val w2 = w * w
                vFore[i] += -w2 * (fore[i] - eqFore) + push(w2, ahead) - PedlarCartMesh.DAMP * vFore[i] + kick(speed, i, 0)
                fore[i] += vFore[i]
                vSide[i] += -w2 * (side[i] - eqSide) + push(w2, left) - PedlarCartMesh.DAMP * vSide[i] + kick(speed, i, 1)
                side[i] += vSide[i]
            }
            // What they knock against: each other along the rail, then the canvas, the frame and the rail
            repeat(2) {
                for (i in PedlarCartMesh.CLASH.indices) {
                    val over = fore[i] - fore[i + 1] - PedlarCartMesh.CLASH[i]
                    if (over > 0f) {
                        fore[i] -= over / 2f
                        fore[i + 1] += over / 2f
                        val rel = vFore[i] - vFore[i + 1]
                        if (rel > 0f) {
                            val mid = (vFore[i] + vFore[i + 1]) / 2f
                            vFore[i] = mid - PedlarCartMesh.BOUNCE * rel / 2f
                            vFore[i + 1] = mid + PedlarCartMesh.BOUNCE * rel / 2f
                        }
                    }
                }
                for (i in 0 until n) {
                    val most = PedlarCartMesh.SWING_MAX
                    limitFore(i, maxOf(-PedlarCartMesh.FORE[i], eqFore - most), minOf(PedlarCartMesh.FORE[i], eqFore + most))
                    limitSide(i, maxOf(-PedlarCartMesh.OUT_MAX, eqSide - most), minOf(PedlarCartMesh.SWING_IN[i], eqSide + most))
                }
            }
        }

        private fun push(w2: Float, acc: Float): Float =
            w2 * PedlarCartMesh.PUSH * Math.toDegrees((acc.coerceIn(-PedlarCartMesh.ACC_MAX, PedlarCartMesh.ACC_MAX) / PedlarCartMesh.G).toDouble()).toFloat()

        private fun kick(speed: Float, i: Int, axis: Int): Float {
            if (speed <= 0.005f || jolt(tick + id * 7919, i, axis) >= PedlarCartMesh.JOLT_P) return 0f
            return PedlarCartMesh.JOLT * speed * (2f * jolt(tick + id * 7919, i, axis + 2) - 1f)
        }

        private fun limitFore(i: Int, lo: Float, hi: Float) {
            if (fore[i] > hi) {
                fore[i] = hi
                if (vFore[i] > 0f) vFore[i] *= -PedlarCartMesh.BOUNCE
            } else if (fore[i] < lo) {
                fore[i] = lo
                if (vFore[i] < 0f) vFore[i] *= -PedlarCartMesh.BOUNCE
            }
        }

        private fun limitSide(i: Int, lo: Float, hi: Float) {
            if (side[i] > hi) {
                side[i] = hi
                if (vSide[i] > 0f) vSide[i] *= -PedlarCartMesh.BOUNCE
            } else if (side[i] < lo) {
                side[i] = lo
                if (vSide[i] < 0f) vSide[i] *= -PedlarCartMesh.BOUNCE
            }
        }
    }

    companion object {
        /** The dressing's layer (PedlarCartMesh.dressing): the hood, the pots, the lantern and its light. */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "handcart_pedlar"), "dressing")

        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, PedlarCartMesh.TEXTURE)
        private val TYPE: RenderType = RenderTypes.entityCutout(TEXTURE)

        /** Which of PedlarCartMesh.HANGING is the lantern: the last. */
        private val LANTERN = PedlarCartMesh.HANGING.indexOf("lantern")

        /** Each pedlar's cart this client draws, and how its pots and lantern hang. */
        private val swings = WeakHashMap<Handcart, Swing>()

        /** Ticks unseen after which a cart's pots are taken to hang still again; blocks a tick that is a cart set down elsewhere, not rolled. */
        private const val STALE = 40
        private const val JUMP = 1.5

        /** How much of the change in the cart's pace a tick is taken (its steps eased). */
        private const val EASE = 0.35

        /** Ticks the lantern takes to catch at dusk (and a third of that to go out at dawn); how lit it must be to show. */
        private const val CATCH = 30f
        private const val LIT_SHOWS = 0.12f

        /** Degrees the pot swings fore and aft to hang straight down: the cart tipped [tip] (nose up) and leaning [lean] (its right side up). */
        fun plumbFore(tip: Float, lean: Float): Float {
            val t = Math.toRadians(tip.toDouble())
            val l = Math.toRadians(lean.toDouble())
            return Math.toDegrees(atan2(sin(t), cos(t) * cos(l))).toFloat()
        }

        /** Degrees it swings inward (toward the cart's middle) to hang straight down. */
        fun plumbSide(tip: Float, lean: Float): Float {
            val t = Math.toRadians(tip.toDouble())
            val l = Math.toRadians(lean.toDouble())
            return Math.toDegrees(asin((-cos(t) * sin(l)).coerceIn(-1.0, 1.0))).toFloat()
        }

        /** A number in [0, 1) for this tick, thing and axis (pedlar_cart.py's `jolt_hash`). */
        fun jolt(tick: Int, i: Int, k: Int): Float {
            var x = (tick * 73856093) xor ((i + 1) * 19349663) xor ((k + 1) * 83492791)
            x = x xor (x ushr 13)
            x *= 0x5BD1E995
            x = x xor (x ushr 15)
            return (x and 0xFFFFFF) / 16777216f
        }

        /** Whether it is dark enough for the pedlar's lantern: each cart's lit a little apart from the next, from dusk to dawn. */
        private fun night(cart: Handcart): Boolean {
            val level = cart.level() as? ClientLevel ?: return false
            val time = Math.floorMod(level.overworldClockTime, 24000L)
            val dusk = 12400L + Math.floorMod(cart.id * 97, 400)
            return time in dusk..23300L
        }
    }
}
