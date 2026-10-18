// Written by art/scripts/road_clips.py (write_crew_moves) when art/scripts/living_anims.py runs: run that rather
// than editing this by hand.
package io.github.vaspyyy.guildmark.client.caravan

/**
 * What the caravan crew's clips (art/scripts/road_clips.py; LivingSchedule has their props and moments) are made to,
 * for the game to place people by and to time the wagon and the horse to them. Distances are px (16 to a block) in
 * the clip's own frame as it starts: right, up and ahead of the townsperson's facing then; turns are degrees to their
 * right (clockwise seen from above).
 */
object CrewMoves {
    /** How far the seats' tops are over the road: the bench's (jump_down and climb_up are made for it) and the tailboard's. */
    const val BENCH_DROP = 27.5f
    const val TAIL_DROP = 19f

    /**
     * jump_down, played on a seat on the bench (from drive's frame): ends standing on the road [JUMP_DOWN_RIGHT] px to
     * the right of the seat and [JUMP_DOWN_AHEAD] ahead, [BENCH_DROP] down, turned [JUMP_DOWN_TURN] (facing out from
     * the wagon's right side): put them there after it, unturned by the clip. The reins leave the hands at
     * [JUMP_DOWN_REINS] (show the horse's "reins_tied" then). From the tailboard, raise the body by BENCH_DROP -
     * TAIL_DROP over the ticks it is in the air ([JUMP_DOWN_AIR_FROM] to [JUMP_DOWN_LAND]), so it lands on the road.
     */
    const val JUMP_DOWN_RIGHT = 14f
    const val JUMP_DOWN_AHEAD = 1f
    const val JUMP_DOWN_TURN = 90f
    const val JUMP_DOWN_REINS = 1.5f
    const val JUMP_DOWN_AIR_FROM = 5f
    const val JUMP_DOWN_LAND = 10f

    /**
     * climb_up: starts standing on the road [CLIMB_UP_RIGHT] px to the right of the bench seat it ends on and
     * [CLIMB_UP_BEHIND] behind it (beside the front wheel), [BENCH_DROP] down, turned [CLIMB_UP_TURN] (facing the
     * box's side), and ends sitting on the seat in drive's first frame, the reins in hand from [CLIMB_UP_REINS] (hide
     * "reins_tied" then). From the road to the tailboard, lower the body by BENCH_DROP - TAIL_DROP between
     * [CLIMB_UP_HAUL_FROM] and [CLIMB_UP_HAUL_TO].
     */
    const val CLIMB_UP_RIGHT = 14.2f
    const val CLIMB_UP_BEHIND = 8.8f
    const val CLIMB_UP_TURN = -90f
    const val CLIMB_UP_REINS = 17f
    const val CLIMB_UP_HAUL_FROM = 3f
    const val CLIMB_UP_HAUL_TO = 10f

    /**
     * drive_brake: the brake lever's lean (WagonMesh.BRAKE_REST to BRAKE_ON, degrees) every half tick of the clip, as
     * the driver's hand hauls it on; it stays on after.
     */
    val BRAKE_LEAN = floatArrayOf(
        -16f, -16f, -16f, -16f, -16f, -16f, -16f, -16f, -16f, -16f,
        -16f, -16f, -16f, -16f, -16f, -16f, -16f, -15.252f, -13.185f, -10.062f,
        -6.148f, -1.706f, 3f, 7.706f, 12.148f, 16.062f, 19.185f, 21.252f, 22f, 22f,
        22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f,
        22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f,
        22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f, 22f,
        22f,
    )

    /** drive_urge: the click of the tongue and the flick of the reins (the horse leans into the collar after it). */
    const val URGE_CLICK = 3f
    const val URGE_FLICK = 5f

    /**
     * cut_loose is played standing by the horse's right shoulder facing it: at ([CUT_LOOSE_X], [CUT_LOOSE_Z]) in the
     * horse's model space (DraughtHorseMesh's: its origin under the middle of its body, facing -z), facing +x. The
     * right trace parts at [CUT_LOOSE_CUT] just behind its hame hook (show "trace_r_cut" and "trace_l_cut", lay the
     * wagon's traces on the road), the knife goes away at [CUT_LOOSE_SHEATHE], the slap on the haunch at
     * [CUT_LOOSE_SLAP] sends the horse off. It ends [CUT_LOOSE_LEFT] px to the left of where it started and
     * [CUT_LOOSE_AHEAD] ahead (by the haunch), facing the same way.
     */
    const val CUT_LOOSE_X = -20.5f
    const val CUT_LOOSE_Z = -14f
    const val CUT_LOOSE_CUT = 20f
    const val CUT_LOOSE_SHEATHE = 24.5f
    const val CUT_LOOSE_SLAP = 39f
    const val CUT_LOOSE_LEFT = 24.6f
    const val CUT_LOOSE_AHEAD = -1f

    /**
     * hand_down is played standing on the wagon's floor at WagonMesh.STAND facing out; the one taking the load stands
     * on the road [HAND_DOWN_OUT] px out from there and [HAND_DOWN_DROP] down, facing back at the wagon, playing
     * receive from the same tick. The goods come off the load into the hands at [HAND_DOWN_LIFT] and go to the taker
     * at [HAND_DOWN_HANDOFF].
     */
    const val HAND_DOWN_OUT = 24f
    const val HAND_DOWN_DROP = 19f
    const val HAND_DOWN_LIFT = 7f
    const val HAND_DOWN_HANDOFF = 16f

    /**
     * cower (a loop) is played crouched by one of the wagon's back wheels (WagonMesh.REAR_RADIUS), outside it and
     * facing it square: the wheel's middle plane [COWER_WHEEL_AHEAD] px ahead, its hub [COWER_HUB_LEFT] px to the left,
     * so the peek from [COWER_PEEK_FROM] to [COWER_PEEK_TO] looks out round the wheel's right edge, the right hand on
     * the rim; the flinch at [COWER_FLINCH].
     */
    const val COWER_WHEEL_AHEAD = 15f
    const val COWER_HUB_LEFT = 6f
    const val COWER_PEEK_FROM = 17f
    const val COWER_PEEK_TO = 28f
    const val COWER_FLINCH = 30.5f
}
