package io.github.vaspyyy.guildmark.client.cart

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier

/**
 * The handcart (geometry and texture from art/scripts/handcart.py, see [HandcartMesh]): the bed, the shafts and
 * the axle hold still, the wheels turn. The renderer tips the whole cart forward or back, so each wheel is turned
 * back by as much again, to keep turning only by how far it rolled.
 */
class HandcartModel(root: ModelPart) : EntityModel<HandcartRenderState>(root, RenderTypes::entityCutout) {
    private val wheelR = root.getChild("wheel_r")
    private val wheelL = root.getChild("wheel_l")

    override fun setupAnim(state: HandcartRenderState) {
        super.setupAnim(state)
        val pitch = Math.toRadians(state.pitch.toDouble()).toFloat()
        wheelR.xRot = state.spinR + pitch
        wheelL.xRot = state.spinL + pitch
    }

    companion object {
        /** The cart itself. */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "handcart"), "main")

        /** What heaps on it, one part for each kind of piece. */
        val LOAD = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "handcart"), "load")
    }
}
