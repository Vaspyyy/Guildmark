package io.github.vaspyyy.guildmark.client.cart

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cart.Handcart
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import org.joml.Quaternionf
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A handcart. Pulled, its shafts end in the puller's hands wherever this frame draws them (the cart turned toward
 * them and tipped up or down to their height); standing, it rests tipped forward onto its shafts, as the server
 * left it, and the shafts come up or go down between the two. It leans as its wheels stand, its wheels turn by how
 * far they rolled, and its load is heaped on the bed (each piece of HandcartMesh's, the goods showing in the open
 * ones, the hen in a household's coop looking about her), settling on as it's put there and lifting off as it's taken.
 */
open class HandcartRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Handcart, HandcartRenderState>(context) {
    private val model = HandcartModel(context.bakeLayer(HandcartModel.LAYER))
    private val load = context.bakeLayer(HandcartModel.LOAD)
    private val parts = HandcartMesh.PIECES.map { load.getChild(it.part) }
    private val items: ItemModelResolver = context.itemModelResolver

    // The hen's head, drawn by itself so that each cart's hen looks about on her own: out of a second baking of the
    // load, and left out of the coop drawn from the first
    private val henHead: ModelPart = context.bakeLayer(HandcartModel.LOAD).getChild(COOP).getChild(HEN_HEAD)
    private val turn = Quaternionf()

    init {
        shadowRadius = 0.75f
        load.getChild(COOP).getChild(HEN_HEAD).visible = false
    }

    override fun createRenderState(): HandcartRenderState = HandcartRenderState()

    override fun extractRenderState(cart: Handcart, state: HandcartRenderState, partialTicks: Float) {
        super.extractRenderState(cart, state, partialTicks)
        val look = CartClient.look(cart)
        val lift = CartClient.ease(Mth.lerp(partialTicks, look.liftO, look.lift))
        if (cart.held) {
            look.heldYaw = cart.getYRot(partialTicks)
            look.heldPitch = -cart.getXRot(partialTicks)
            // The shafts end in the puller's hands, wherever this frame draws them
            val puller = cart.level().getEntity(cart.pullerId)
            if (puller != null) {
                val partial = partialTicks.toDouble()
                val dx = Mth.lerp(partial, puller.xo, puller.x) - state.x
                val dz = Mth.lerp(partial, puller.zo, puller.z) - state.z
                val flat = sqrt(dx * dx + dz * dz)
                if (flat > 0.3) {
                    val hands = Mth.lerp(partial, puller.yo, puller.y) + Handcart.HAND_UP - (state.y + Handcart.AXLE_UP)
                    look.heldYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
                    look.heldPitch = (Math.toDegrees(atan2(hands, max(flat - Handcart.HAND_BACK, 0.1))) - Handcart.SHAFT_RISE).toFloat()
                }
            }
        } else {
            // Standing as the server left it, tipped onto its shafts
            look.restYaw = cart.getYRot(partialTicks)
            look.restPitch = -cart.getXRot(partialTicks)
        }
        state.yaw = look.restYaw + Mth.wrapDegrees(look.heldYaw - look.restYaw) * lift
        state.pitch = Mth.lerp(lift, look.restPitch, look.heldPitch)
        state.roll = Mth.lerp(partialTicks, look.rollO, look.roll)
        state.spinR = Mth.lerp(partialTicks, look.spinRO, look.spinR)
        state.spinL = Mth.lerp(partialTicks, look.spinLO, look.spinL)
        state.broken = off(cart, partialTicks)
        // The load: each piece on its place, resting on what's under it; settling on, or lifting off
        var n = 0
        for (s in 0 until Handcart.SLOTS) {
            val rest = Mth.lerp(partialTicks, look.restO[s], look.rest[s])
            val on = look.shown[s]
            if (!on.isEmpty) {
                val t = ((look.since[s] + partialTicks) / CartClient.SETTLE).coerceIn(0f, 1f)
                n = add(state, n, cart, s, on, rest + SETTLE_DROP * (1f - t) * (1f - t), 1f)
            }
            val off = look.leaving[s]
            if (!off.isEmpty) {
                val u = ((look.gone[s] + partialTicks) / CartClient.LIFT).coerceIn(0f, 1f)
                val shrink = 1f - CartClient.ease((u - 0.45f) / 0.55f)
                if (shrink > 0.02f) n = add(state, n, cart, s, off, rest + LIFT_RISE * (1f - (1f - u) * (1f - u)), shrink)
            }
        }
        state.pieces = n
        // The hen in a household's coop looks about her
        for (i in 0 until n) {
            if (state.kind[i] != CartClient.CAGE) continue
            HandcartMesh.henHead(cart.tickCount + partialTicks, cart.id, state.hen)
            break
        }
    }

    /** Adds a piece of [stack] on place [slot], [up] px off the floor at [scale]; the count drawn so far. */
    private fun add(state: HandcartRenderState, n: Int, cart: Handcart, slot: Int, stack: ItemStack, up: Float, scale: Float): Int {
        if (n >= HandcartRenderState.MAX) return n
        val place = HandcartMesh.SLOTS[slot]
        val kind = CartClient.piece(stack)
        state.kind[n] = kind
        state.px[n] = place.x
        state.py[n] = HandcartMesh.FLOOR_TOP - up
        state.pz[n] = place.z
        state.turn[n] = place.yaw
        state.size[n] = scale
        if (HandcartMesh.PIECES[kind].shows.isNotEmpty()) items.updateForNonLiving(state.goods[n], stack, ItemDisplayContext.FIXED, cart)
        return n + 1
    }

    override fun submit(state: HandcartRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        // A broken cart slumps onto the corner whose wheel is off (HandcartModel), and rises again as it's mended
        val slump = if (state.broken > 0f) HandcartModel.ease(state.broken) else 0f
        poseStack.translate(0.0, Handcart.AXLE_UP + HandcartModel.LIFT * slump, 0.0)
        poseStack.rotateDegrees(Axis.YP, 180f - state.yaw)
        poseStack.rotateDegrees(Axis.XP, state.pitch + HandcartModel.ROCK * slump)
        poseStack.rotateDegrees(Axis.ZP, state.roll)
        if (slump > 0f) {
            poseStack.translate(HandcartModel.PIVOT_X, HandcartModel.PIVOT_Y, 0.0)
            poseStack.rotateDegrees(Axis.ZP, -HandcartModel.SLUMP * slump)
            poseStack.translate(-HandcartModel.PIVOT_X, -HandcartModel.PIVOT_Y, 0.0)
        }
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModel(model, state, poseStack, texture(state), state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.pushPose()
        shake(state, poseStack)
        for (i in 0 until state.pieces) {
            val piece = HandcartMesh.PIECES[state.kind[i]]
            poseStack.pushPose()
            poseStack.translate(state.px[i] / 16f, state.py[i] / 16f, state.pz[i] / 16f)
            poseStack.rotateDegrees(Axis.YP, -state.turn[i])
            val scale = state.size[i]
            if (scale != 1f) poseStack.scale(scale, scale, scale)
            collector.submitModelPart(parts[state.kind[i]], poseStack, LOAD_TYPE, state.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, state.outlineColor)
            if (state.kind[i] == CartClient.CAGE) {
                // The hen's head on her neck, turned there as ModelPart turns a part about its pivot
                val neck = HandcartMesh.HEN_HEAD
                poseStack.pushPose()
                poseStack.translate(neck[0] / 16f, neck[1] / 16f, neck[2] / 16f)
                poseStack.rotate(turn.rotationZYX(state.hen[2], state.hen[1], state.hen[0]))
                poseStack.translate(-neck[0] / 16f, -neck[1] / 16f, -neck[2] / 16f)
                collector.submitModelPart(henHead, poseStack, LOAD_TYPE, state.lightCoords, OverlayTexture.NO_OVERLAY, null, -1, state.outlineColor)
                poseStack.popPose()
            }
            // What shows in an open one, lying on top, each a little turned and tipped
            if (!state.goods[i].isEmpty) {
                for ((j, show) in piece.shows.withIndex()) {
                    poseStack.pushPose()
                    poseStack.translate(show[0] / 16f, (-piece.height - 0.25f - 0.15f * j) / 16f, show[1] / 16f)
                    poseStack.rotateDegrees(Axis.YP, -show[2])
                    poseStack.rotateDegrees(Axis.XP, show[3])
                    // Flat, face up (y is down in here)
                    poseStack.rotateDegrees(Axis.XP, -90f)
                    poseStack.scale(piece.size, piece.size, piece.size)
                    state.goods[i].submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor)
                    poseStack.popPose()
                }
            }
            poseStack.popPose()
        }
        poseStack.popPose()
        dressed(state, poseStack, collector)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    /**
     * How far [cart]'s near wheel is off this frame (the handcart tale's broken cart): 1 off, 0 on, and between while it's set back
     * on (over [MEND] ticks, the bed settling as it goes). A cart first seen broken is drawn broken at once.
     */
    private fun off(cart: Handcart, partialTicks: Float): Float {
        val now = cart.tickCount + partialTicks
        val want = if (cart.broken) 1f else 0f
        val was = mending[cart]
        if (was == null) {
            if (want > 0f) mending[cart] = floatArrayOf(want, now)
            return want
        }
        val step = ((now - was[1]).coerceIn(0f, 5f)) / MEND
        was[0] = if (want > was[0]) minOf(want, was[0] + step) else maxOf(want, was[0] - step)
        was[1] = now
        if (was[0] <= 0f && want <= 0f) mending.remove(cart)
        return was[0]
    }

    /** The texture the cart itself (not its load) is drawn with: the carters' own; the Chapman's Cart has its livery (PlayerCartArt). */
    protected open fun texture(state: HandcartRenderState): Identifier = TEXTURE

    /** Moves the load before it is drawn, in the cart's own space (px, y down): nothing for a carter's cart; the Chapman's Cart's load rocks. */
    protected open fun shake(state: HandcartRenderState, poseStack: PoseStack) {}

    /** Draws what a cart has besides the bed and its load, in the cart's own space after the load: nothing for a carter's cart; the Chapman's Cart's pennant and canvas. */
    protected open fun dressed(state: HandcartRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {}

    // Its box is the bed's middle: the shafts reach well ahead of it, the tail a little behind
    override fun getBoundingBoxForCulling(cart: Handcart, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(cart, partialTicks).inflate(REACH, 0.5, REACH)

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/cart/handcart.png")
        private val LOAD_TYPE: RenderType = RenderTypes.entityCutout(TEXTURE)

        /** The hen's coop among the load's parts, and her head in it (HandcartMesh.load). */
        private val COOP = HandcartMesh.PIECES[CartClient.CAGE].part
        private const val HEN_HEAD = "cage_hen_head"

        /** Px a piece drops from as it's set down, and rises as it's lifted off. */
        private const val SETTLE_DROP = 5f
        private const val LIFT_RISE = 7f

        /** Blocks the shafts reach past its box. */
        private const val REACH = 1.2

        /** Ticks a broken cart's wheel takes going back on. */
        private const val MEND = 12f

        /** Each broken cart's wheel as last drawn: how far off, and when (its tick and the frame's part of one). */
        private val mending = java.util.WeakHashMap<Handcart, FloatArray>()
    }
}
