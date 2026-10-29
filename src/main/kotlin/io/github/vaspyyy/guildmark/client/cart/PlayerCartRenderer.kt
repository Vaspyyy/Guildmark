package io.github.vaspyyy.guildmark.client.cart

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cart.Handcart
import io.github.vaspyyy.guildmark.cart.PlayerCart
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Chapman's Cart: the carters' handcart (its model, its wheels turning by how far they rolled, its load heaped on the bed and its shafts coming up into
 * the puller's hands, all [HandcartRenderer]'s) with what is a trader's own on it:
 * - **A pennant** in the colour the cart was bought in, on a short pole at the rear, rippling more the faster it goes ([PlayerCartArt]).
 * - **The canvas**, in four strips, over the load: it folds back onto the tail one strip at a time (the front one first) and unfolds the other way, lifted
 *   to clear what is under it; beaded and darkened as rain falls on it, drying off after ([PlayerCartRenderState.wet]).
 * - **The cart in its livery** where the dressing has one ([PlayerCartArt.Table.body]).
 * - **The load rocks** on rough ground and as the cart turns or starts, and the cart **tips onto its shafts** with a small bounce when it is set down
 *   (both worked out by [PlayerCartClient] each tick; the cart's own lean is the handcart's).
 * - **The owner's own client puts the cart where their hands say** ([getRenderOffset]): the server places the cart, and the picture of it arrives a
 *   little late, so the cart pulled by the player at the keyboard is drawn with the shafts' ends in their hands now, the axle that distance behind
 *   them along the line to where the server has it. With nobody pulling, or someone else, it is drawn where the server has it.
 */
class PlayerCartRenderer(context: EntityRendererProvider.Context) : HandcartRenderer(context) {
    private val table = PlayerCartArt.active()
    private val dressing: ModelPart = context.bakeLayer(PlayerCartArt.LAYER)
    private val type: RenderType = RenderTypes.entityCutout(table.texture)
    private val wetType: RenderType? = table.wet?.let { RenderTypes.entityTranslucent(it) }
    private val pole: ModelPart? = part("pole")
    private val flag: List<ModelPart> = (0 until table.ripples.size).mapNotNull { part("flag_$it") }
    private val canvas: List<ModelPart> = (0..table.folds.size).mapNotNull { part("canvas_$it") }

    /** A part of the dressing, or null if its mesh has none of that name. */
    private fun part(name: String): ModelPart? = if (dressing.hasChild(name)) dressing.getChild(name) else null

    override fun createRenderState(): HandcartRenderState = PlayerCartRenderState()

    override fun texture(state: HandcartRenderState): Identifier = table.body ?: super.texture(state)

    override fun extractRenderState(cart: Handcart, state: HandcartRenderState, partialTicks: Float) {
        super.extractRenderState(cart, state, partialTicks)
        val s = state as? PlayerCartRenderState ?: return
        val mine = cart as? PlayerCart ?: return
        val feel = PlayerCartClient.feel(mine)
        val look = CartClient.look(mine)
        s.colour = 0xFF000000.toInt() or mine.colour
        s.cover = Mth.lerp(partialTicks, feel.coverO, feel.cover)
        s.wind = Mth.lerp(partialTicks, feel.windO, feel.wind)
        s.flutter = feel.flutter
        s.wet = Mth.lerp(partialTicks, feel.wetO, feel.wet)
        s.swayRoll = Mth.lerp(partialTicks, feel.swayRO, feel.swayR)
        s.swayPitch = Mth.lerp(partialTicks, feel.swayPO, feel.swayP)
        val lift = CartClient.ease(Mth.lerp(partialTicks, look.liftO, look.lift))
        s.aheadX = 0.0
        s.aheadZ = 0.0
        val me = Minecraft.getInstance().player
        if (me != null && mine.held && mine.pullerId == me.id && lift > 0f) {
            // Where the owner's hands are this frame; the axle is the shafts' length behind them, along the line to where the server has it
            val partial = partialTicks.toDouble()
            val hx = Mth.lerp(partial, me.xo, me.x)
            val hz = Mth.lerp(partial, me.zo, me.z)
            val dx = state.x - hx
            val dz = state.z - hz
            val flat = hypot(dx, dz)
            if (flat > NEAR) {
                var ox = hx + dx / flat * Handcart.GRIP_FLAT - state.x
                var oz = hz + dz / flat * Handcart.GRIP_FLAT - state.z
                val far = hypot(ox, oz)
                if (far > MOST) {
                    ox *= MOST / far
                    oz *= MOST / far
                }
                s.aheadX = ox * lift
                s.aheadZ = oz * lift
                // The shafts then reach their hands level: tipped as they are over the length they have
                val hands = Mth.lerp(partial, me.yo, me.y) + Handcart.HAND_UP - (state.y + Handcart.AXLE_UP)
                val held = (Math.toDegrees(Math.atan2(hands, max(hypot(dx + ox, dz + oz) - Handcart.HAND_BACK, 0.1))) - Handcart.SHAFT_RISE).toFloat()
                look.heldPitch = held
                s.pitch = Mth.lerp(lift, look.restPitch, held)
            }
        }
        s.pitch += Mth.lerp(partialTicks, feel.bounceO, feel.bounce)
    }

    override fun getRenderOffset(state: HandcartRenderState): Vec3 {
        val s = state as? PlayerCartRenderState ?: return Vec3.ZERO
        return if (s.aheadX == 0.0 && s.aheadZ == 0.0) Vec3.ZERO else Vec3(s.aheadX, 0.0, s.aheadZ)
    }

    /** The load rocks about the middle of the bed. */
    override fun shake(state: HandcartRenderState, poseStack: PoseStack) {
        rock(state as? PlayerCartRenderState ?: return, poseStack)
    }

    private fun rock(s: PlayerCartRenderState, poseStack: PoseStack) {
        if (s.swayRoll == 0f && s.swayPitch == 0f) return
        val floor = HandcartMesh.FLOOR_TOP / 16f
        poseStack.translate(0f, floor, 0f)
        poseStack.rotateDegrees(Axis.ZP, s.swayRoll)
        poseStack.rotateDegrees(Axis.XP, s.swayPitch)
        poseStack.translate(0f, -floor, 0f)
    }

    /** The pole, the pennant and the canvas, in the cart's own space (px, y down), after the load. */
    override fun dressed(state: HandcartRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val s = state as? PlayerCartRenderState ?: return
        val light = state.lightCoords
        val outline = state.outlineColor
        pole?.let { collector.submitModelPart(it, poseStack, type, light, OverlayTexture.NO_OVERLAY, null, table.poleTint, outline) }
        // The pennant streams out behind the pole, each segment turning on the one before
        var pushed = 0
        for ((i, seg) in flag.withIndex()) {
            val hinge = table.ripples[i]
            val turn = Math.toDegrees((s.flutter * sin(s.wind - i * RIPPLE_LAG) + SLACK * i).toDouble()).toFloat()
            poseStack.pushPose()
            pushed++
            poseStack.translate(hinge.a / 16f, 0f, hinge.b / 16f)
            poseStack.rotateDegrees(Axis.YP, turn)
            poseStack.translate(-hinge.a / 16f, 0f, -hinge.b / 16f)
            collector.submitModelPart(seg, poseStack, type, light, OverlayTexture.NO_OVERLAY, null, s.colour, outline)
        }
        repeat(pushed) { poseStack.popPose() }
        // The canvas: lifted to clear the load, rocking with it, and folded back strip by strip, the front one first
        if (canvas.isEmpty()) return
        poseStack.pushPose()
        poseStack.translate(0f, (clearance(s) - table.canvasTop) / 16f, 0f)
        rock(s, poseStack)
        pushed = 0
        for ((k, strip) in canvas.withIndex()) {
            if (k > 0) {
                val hinge = table.folds[k - 1]
                // Strip 1 comes unfolded first as the canvas is pulled over, strip 2 next, the front strip last; folding back is the other way about
                val folded = 1f - CartClient.ease((s.cover * (canvas.size - 1) - (k - 1)).coerceIn(0f, 1f))
                poseStack.pushPose()
                pushed++
                poseStack.translate(0f, hinge.a / 16f, hinge.b / 16f)
                poseStack.rotateDegrees(Axis.XP, -180f * folded)
                poseStack.translate(0f, -hinge.a / 16f, -hinge.b / 16f)
            }
            collector.submitModelPart(strip, poseStack, type, light, OverlayTexture.NO_OVERLAY, null, table.canvasTint, outline)
            // The wet look over it, as wet as it is, drawn after it on the same faces
            if (wetType != null && s.wet > WET_SHOWN) {
                collector.order(1).submitModelPart(strip, poseStack, wetType, light, OverlayTexture.NO_OVERLAY, null, ARGB.white(s.wet), 0)
            }
        }
        repeat(pushed) { poseStack.popPose() }
        poseStack.popPose()
    }

    /**
     * Where the canvas's top face lies (px, y down): [PlayerCartArt.Table.lift] over the highest point of the load under it, or over the posts if there
     * is little or none. With the canvas's front edge known ([PlayerCartArt.Table.front]) a piece counts as the canvas comes over it: fully once the
     * folded canvas lies over its back edge, otherwise from [LEAD] of the cover before the canvas reaches it, eased over at least [RAMP_MIN], so the folded
     * canvas rests on what is at the tail and is up before the strip that lands over the heap comes down. Without it every piece counts.
     */
    private fun clearance(s: PlayerCartRenderState): Float {
        var top = table.canvasTop
        val front = table.front
        val tail = table.folds.firstOrNull()?.b
        for (i in 0 until s.pieces) {
            val kind = s.kind[i]
            val height = (table.tops?.getOrNull(kind) ?: HandcartMesh.PIECES[kind].height) * s.size[i]
            val need = s.py[i] - height - table.lift
            var w = 1f
            if (front != null && tail != null && tail > front) {
                val back = s.pz[i] + table.reach
                if (back < tail) {
                    val at = (tail - back) / (tail - front)
                    val lo = max(0f, at - LEAD)
                    val hi = max(at, lo + RAMP_MIN)
                    w = CartClient.ease((s.cover - lo) / (hi - lo))
                }
            }
            top = min(top, Mth.lerp(w, table.canvasTop, need))
        }
        return top
    }

    companion object {
        /** Blocks away from the server's axle the owner's hands are taken to say it is, at most; and how close to the hands the server's axle may be before it is believed. */
        private const val MOST = 1.4
        private const val NEAR = 0.35

        /** Radians each pennant segment lags the one before in the ripple, and the slack it hangs with. */
        private const val RIPPLE_LAG = 1.1f
        private const val SLACK = 0.05f

        /** How much of the cover before the canvas reaches a piece it begins to lift for it, and the least of the cover it takes to (see [clearance]). */
        private const val LEAD = 1f / 6f
        private const val RAMP_MIN = 0.1f

        /** How wet the canvas must be for its wet look to be drawn at all. */
        private const val WET_SHOWN = 0.004f
    }
}
