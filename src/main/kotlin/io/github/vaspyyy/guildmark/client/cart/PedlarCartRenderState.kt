package io.github.vaspyyy.guildmark.client.cart

/**
 * What the village pedlar's cart needs drawn besides a handcart's (Villages, V12a): whether this cart is a pedlar's at all, how each hanging
 * thing swings this frame, and how brightly the lantern burns.
 */
class PedlarCartRenderState : HandcartRenderState() {
    /** Whether it is a village pedlar's ([io.github.vaspyyy.guildmark.cart.Handcart.pedlar]): false, and it is drawn as the carters' handcart. */
    var pedlar = false

    /** Each of PedlarCartMesh.HANGING's swing this frame, degrees in the cart's own frame: fore (its foot toward the tail) and side (toward the middle). */
    val fore = FloatArray(PedlarCartMesh.HANGING.size)
    val side = FloatArray(PedlarCartMesh.HANGING.size)

    /** How brightly the lantern burns, 0 (out) to about 1, its flicker in it. */
    var lit = 0f
}
