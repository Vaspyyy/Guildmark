package io.github.vaspyyy.guildmark.client.island

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState

/**
 * What a gull's model needs to pose it. The renderer pitches and rolls the whole bird in flight, about the middle of
 * its body ([GullModel.BODY_Y] over its feet); [GullModel] works its wings, tail, neck, head, beak and legs from the rest.
 */
class GullRenderState : LivingEntityRenderState() {
    /** Ticks it has lived, with the partial tick: its idle stirring keeps time by it. */
    var time = 0f

    /** 0 on the ground or the water, 1 in the air: its legs tucked up under its tail and its body held level. */
    var flying = 0f

    /** 0 its wings folded at its sides, 1 spread full. */
    var spread = 0f

    /** Radians through its wingbeat, one beat every 2 pi (kept running by the entity, so the beat never jumps). */
    var flapPhase = 0f

    /** 0 gliding on still wings, 1 beating them as hard as it can (taking off, climbing). */
    var flap = 0f

    /** -1 to 1: banked into a turn, above 0 rolled to its own right. The model sweeps the outer wing up a little. */
    var bank = 0f

    /** 0 to 1: hanging still on the wind (behind a ship, over a cliff): tail fanned, wingtips working, feet let down. */
    var hover = 0f

    /** 0 to 1: sitting on the water, riding low, legs hidden. */
    var floating = 0f

    /** Degrees the renderer tips the whole bird nose down in flight (below 0 nose up, climbing or flaring). */
    var flightPitch = 0f

    /** Blocks it's lifted on the swell while it rides the water. */
    var bob = 0f

    /** Degrees its head is turned to its right and tipped up, from its body. */
    var headYaw = 0f
    var headPitch = 0f

    /** 0 beak shut, 1 gaping. */
    var beak = 0f

    /** 0 to 1: walking, and radians through its stride (one step of each foot every 2 pi). */
    var walk = 0f
    var walkPhase = 0f

    /** What else it's doing (see [GullModel.ACTION_TICKS] for how long each takes), and ticks into it. */
    var action = NONE
    var actionTime = 0f

    /** A fish held crosswise in its beak, after a dive. */
    var fish = false

    /** Which bird it is: 0 a herring gull, 1 a black-backed gull, 2 a young one (mottled brown). */
    var variant = 0

    companion object {
        const val NONE = 0
        /** Running its beak through the feathers of its back and under a wing. */
        const val PREEN = 1
        /** The long call: head thrown down, then up and back, beak wide, calling over and over. */
        const val CALL = 2
        /** Pecking at something at its feet. */
        const val PECK = 3
        /** Shaking out and settling its feathers. */
        const val RUFFLE = 4
        /** Squaring up to another gull: wings half open and held up, neck stretched, beak jabbing. */
        const val THREAT = 5
        /** Crouching and springing up into the air, the first great downbeat. */
        const val TAKEOFF = 6
        /** Flaring to land: body up, wings cupped and beating back, feet thrown forward. */
        const val LAND = 7
        /** Folding its wings and plunging head first into the sea, then bobbing back up. */
        const val DIVE = 8
    }
}
