package io.github.vaspyyy.guildmark.client.sea

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState

/**
 * What a Gnashfin's model needs to pose it. The renderer turns the whole fish by its heading, [pitch] and [roll]
 * (about the middle of its body); [GnashfinModel] works its body, tail, fins and jaw from the rest.
 */
class GnashfinRenderState : LivingEntityRenderState() {
    /** Ticks it has lived, with the partial tick: its swimming keeps time by it. */
    var time = 0f

    /** 0 to 1: how hard it's swimming (its tail beats wider and faster). */
    var swim = 0f

    /** -1 to 1: how its body curves as it turns; above 0 its nose swings to its own right. */
    var bend = 0f

    /** 0 shut, 1 gaping. */
    var jaw = 0f

    /** -1 when it isn't leaping; otherwise 0 to 1 through a leap out of the water and back in. */
    var leap = -1f

    /** 0 to 1: floundering on a deck it was knocked down onto. */
    var stranded = 0f

    /** 0 to 1: its teeth in a ship's hull, worrying at the planks. */
    var gnaw = 0f

    /** 0 to 1: how brightly the lights along its flanks and the lure under its chin burn (at night, and deep). */
    var glow = 0f

    /** Degrees nose up, and rolled over to its right. */
    var pitch = 0f
    var roll = 0f
}
