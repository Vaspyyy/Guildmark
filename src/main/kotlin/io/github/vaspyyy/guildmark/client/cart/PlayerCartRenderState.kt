package io.github.vaspyyy.guildmark.client.cart

/**
 * What the Chapman's Cart's renderer needs besides a handcart's: the pennant's colour and how it flutters, how far the canvas is folded back, how the
 * load rocks on rough ground, and how far ahead of the server's axle the cart is drawn while its owner pulls it (see [PlayerCartRenderer]).
 */
class PlayerCartRenderState : HandcartRenderState() {
    /** ARGB: the pennant's colour. */
    var colour = -1

    /** 0 the canvas folded back on the tail, 1 over the load; eased, and three steps within it. */
    var cover = 0f

    /** Radians the pennant has gone through in its flutter, and how far it swings. */
    var wind = 0f
    var flutter = 0f

    /** Degrees the load rocks sideways (roll) and fore and aft (pitch) about the middle of the bed. */
    var swayRoll = 0f
    var swayPitch = 0f

    /** Blocks the cart is drawn away from the server's axle: the owner's own client puts it where their hands say it is (see [PlayerCartRenderer.getRenderOffset]). */
    var aheadX = 0.0
    var aheadZ = 0.0
}
