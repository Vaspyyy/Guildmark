package io.github.vaspyyy.guildmark.client.cart

import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.item.ItemStackRenderState

/**
 * What a handcart's renderer needs to draw it: how it's turned, tipped and leaning, how far its wheels have
 * turned, and the pieces of its load (on the bed, settling onto it or being lifted off) with what shows in them.
 */
class HandcartRenderState : EntityRenderState() {
    /** Degrees: which way it heads (Minecraft yaw: 0 faces south, 90 west). */
    var yaw = 0f

    /** Degrees its nose is up: down onto its shafts standing, up or down to its puller's hands pulled. */
    var pitch = 0f

    /** Degrees it leans, its right side up. */
    var roll = 0f

    /** Radians the right wheel has turned, rolling forward. */
    var spinR = 0f

    /** Radians the left wheel has turned. */
    var spinL = 0f

    /** How many pieces of the load are drawn. */
    var pieces = 0

    /** Each piece's kind, of HandcartMesh.PIECES. */
    val kind = IntArray(MAX)

    /** Where each piece stands (px, model space: y down). */
    val px = FloatArray(MAX)
    val py = FloatArray(MAX)
    val pz = FloatArray(MAX)

    /** Degrees each piece is turned. */
    val turn = FloatArray(MAX)

    /** How big each piece is drawn (1: its size; less as it's lifted off). */
    val size = FloatArray(MAX)

    /** What shows in each open piece (the carrots in a crate, the loaves in a basket). */
    val goods = Array(MAX) { ItemStackRenderState() }

    companion object {
        /** Pieces at most: one on each place on the bed, and one being lifted off each. */
        const val MAX = 16
    }
}
