package io.github.vaspyyy.guildmark.client.sea

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState

/**
 * What the Maelgrasp's head needs to be drawn. The renderer places it, turns it to face the ship it's attacking and
 * lowers it under the water as it sinks; [MaelgraspModel] works its eye, its beak, the short arms round its beak,
 * its siphon and the heave of its mantle.
 */
class MaelgraspRenderState : LivingEntityRenderState() {
    /** Ticks it has lived, with the partial tick. */
    var time = 0f

    /** 0 sunk out of sight below, 1 up out of the water beside the ship. */
    var rise = 0f

    /** Degrees its eye turns from straight ahead: to its right (above 0), and up (above 0). */
    var lookYaw = 0f
    var lookPitch = 0f

    /** 0 its pupil a narrow bar, 1 opened wide. */
    var pupil = 0.5f

    /** 0 its eye open, 1 its lids shut. */
    var lid = 0f

    /** 0 its beak shut, 1 gaping. */
    var beak = 0f

    /** 0 to 1: surging forward and down to bite. */
    var lunge = 0f

    /** 0 to 1: its siphon spewing ink. */
    var ink = 0f

    /** 0 to 1: its fury in the second half of the fight (flushed dark, its veins blazing). */
    var rage = 0f

    /** 0 to 1: dead and sinking, its eye clouding over. */
    var dying = 0f

    /** 0 to 1: how brightly the veins under its skin and the ring round its eye burn. */
    var glow = 0f
}
