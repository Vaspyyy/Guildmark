package io.github.vaspyyy.guildmark.client.sea

import io.github.vaspyyy.guildmark.sea.Tentacle
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState

/**
 * What one of the Maelgrasp's arms needs to be drawn: its joints, worked out by [Tentacle] from what its entity
 * carries, in the arm's own frame (blocks; the origin where it breaks the surface, y up, z the way it faces, x to
 * its right). The renderer only places and turns the arm as a whole; [MaelgraspArmModel] lays its segments along
 * the joints.
 */
class MaelgraspArmRenderState : LivingEntityRenderState() {
    /** (x, y, z) of each of its joints, root first. */
    val joints = FloatArray((Tentacle.SEGMENTS + 1) * 3)

    /** How many of its segments are still on it, root first: fewer once it's been hacked through. */
    var cut = Tentacle.SEGMENTS

    /**
     * The first segment drawn, root first: 0 for a whole arm. Above 0 it's the length hacked off it, drawn on its own
     * as it falls away (from [start] to the tip, its root end the same raw stump as [cut]'s, facing back along it).
     */
    var start = 0

    /** Ticks it has lived, with the partial tick. */
    var time = 0f

    /** 0 to 1: its suckers clenched tight round whatever it's holding. */
    var grip = 0f

    /** 0 to 1: how brightly the veins under its skin burn. */
    var glow = 0f

    /** 0 to 1: the Maelgrasp's fury in the second half of the fight (its arms flush darker and their veins blaze). */
    var rage = 0f

    /**
     * The length hacked off it, falling into the sea and sinking (in the same frame, already fallen and turned over),
     * drawn as a second arm from its own state; null while it's whole. Only the renderer reads it.
     */
    var piece: MaelgraspArmRenderState? = null
}
