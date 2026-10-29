package io.github.vaspyyy.guildmark.client.cart

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/**
 * The handcart (geometry and texture from art/scripts/handcart.py, see [HandcartMesh]): the bed, the shafts and
 * the axle hold still, the wheels turn. The renderer tips the whole cart forward or back, so each wheel is turned
 * back by as much again, to keep turning only by how far it rolled. A broken cart's near wheel stands off it.
 */
class HandcartModel(root: ModelPart) : EntityModel<HandcartRenderState>(root, RenderTypes::entityCutout) {
    private val wheelR = root.getChild("wheel_r")
    private val wheelL = root.getChild("wheel_l")

    override fun setupAnim(state: HandcartRenderState) {
        super.setupAnim(state)
        val pitch = Math.toRadians(state.pitch.toDouble()).toFloat()
        wheelR.xRot = state.spinR + pitch
        wheelL.xRot = state.spinL + pitch
        // The handcart tale's broken cart: the near wheel off its axle, stood on its rim on the ground and leant against the bed's side
        // ahead of the axle (the renderer slumps the bed onto that corner); mended, it swings up onto the axle as the bed rises
        val off = state.broken
        if (off > 0f) {
            val e = ease(off)
            wheelR.x = Mth.lerp(e, ON_X, OFF_X)
            wheelR.y = Mth.lerp(e, 0f, OFF_Y)
            wheelR.z = Mth.lerp(e, 0f, OFF_Z)
            wheelR.xRot = Mth.lerp(e, wheelR.xRot, OFF_TURN)
            wheelR.yRot = OFF_YAW * e
            wheelR.zRot = OFF_LEAN * e
        }
    }

    companion object {
        /** The cart itself. */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "handcart"), "main")

        /** What heaps on it, one part for each kind of piece. */
        val LOAD = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "handcart"), "load")

        // A broken cart, worked out for a cart let go on the flat (as the tale's camp is): the bed slumps onto the corner whose wheel is
        // off, rolled about the far wheel's foot until the bare axle end touches down, and rocks back off its shafts until the near
        // shaft's tip does, a little lifted; the off wheel then stands on its rim beside it, leant against the bed's side rail
        /** Degrees the bed rolls down onto the near corner. */
        internal const val SLUMP = 27.75f
        /** Degrees it rocks back off its shafts. */
        internal const val ROCK = 18.5f
        /** Blocks it is lifted, so the far wheel stays on the ground. */
        internal const val LIFT = 0.476 / 16
        /** Where it rolls about, in the cart's own space before it is flipped (blocks): the far wheel's foot. */
        internal const val PIVOT_X = -9.0 / 16
        internal const val PIVOT_Y = -10.0 / 16

        // The near wheel on its axle, and off (px and radians, model space, y down)
        private const val ON_X = -9f
        private const val OFF_X = -6.63f
        private const val OFF_Y = -10.8f
        private const val OFF_Z = -10.51f
        private const val OFF_TURN = 0.197f
        private const val OFF_YAW = -0.1f
        private const val OFF_LEAN = 0.747f

        /** Eases [t] (0 to 1) in and out. */
        internal fun ease(t: Float) = t * t * (3f - 2f * t)
    }
}
