package io.github.vaspyyy.guildmark.client.cooking

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Where things are on the stations, in pixels of the block's cell (16 to a block, x east, y up, z south) for a station
 * facing north: the numbers of the contract at the head of `art/scripts/cook_art.py` (Part M1's), which the pieces and the
 * click regions are drawn against. A renderer divides by 16 and turns the cell about its middle for a station that faces another way.
 */
object CookGeo {
    const val PX = 1f / 16f

    // ---- The Stewpot ----

    /** The pot hangs at (8, 12.9, 8) and slides down a pixel for each notch of heat (the lower the pot, the hotter). */
    const val HANG_Y = 12.9f
    const val NOTCH_STEP = 1.0f

    /** The pot's rim, outside, at notch 0, and its inner floor. */
    const val POT_TOP = 10.0f
    const val POT_FLOOR = 6.25f

    /** The surface: a quad at 6.7 (a ladleful) to 9.4 (full) at notch 0, 2.9 px from the middle to each edge. */
    const val SURFACE_LOW = 6.7f
    const val SURFACE_HIGH = 9.4f
    const val SURFACE_R = 2.9f

    /** The inside of the rim, where foam climbs to and a ring gathers. */
    const val RIM_R = 3.3f

    /** Ingredients bob within this far of (8, 8). */
    const val CHUNK_R = 2.4f

    /** The hearth's crane turns about a vertical line at (13.2, 2.8), 0 to 60 degrees; the pot, its chain and its soot turn with it. */
    const val CRANE_X = 13.2f
    const val CRANE_Z = 2.8f
    const val CRANE_MAX = 60f

    // ---- The Hob ----

    /** The pan's floor, and where its six pieces lie (x, z). */
    const val PAN_Y = 10.5f
    val PAN_SLOTS: Array<FloatArray> = arrayOf(
        floatArrayOf(5.0f, 6.1f), floatArrayOf(8.0f, 6.1f), floatArrayOf(11.0f, 6.1f),
        floatArrayOf(5.0f, 9.9f), floatArrayOf(8.0f, 9.9f), floatArrayOf(11.0f, 9.9f),
    )

    /** The flue's top. */
    val FLUE = floatArrayOf(13.4f, 15.4f, 13.4f)

    // ---- The Bake oven ----

    /** The door's hinge (x, z) and how far it swings (outward, toward -z). */
    const val HINGE_X = 4.8f
    const val HINGE_Z = 1.0f
    const val DOOR_MAX = 100f

    /** The tray's rails at the lower and the upper level, how far up the upper one is, and how far a tray slides out (toward the door). */
    const val TRAY_LIFT = 2.2f
    const val TRAY_SLIDE = 6.0f

    /** Where an item lies on a tray (x, z), and the height it lies at on the lower tray (the upper is [TRAY_LIFT] more). */
    const val TRAY_ITEM_Y = 8.9f
    val TRAY_SLOTS: Array<FloatArray> = arrayOf(
        floatArrayOf(6.5f, 6.9f), floatArrayOf(8.0f, 6.9f), floatArrayOf(9.5f, 6.9f), floatArrayOf(7.25f, 4.5f), floatArrayOf(8.75f, 4.5f),
    )

    /** The chimney's top, and the door's window (x0, y0, x1, y1) on the plane z 1.0. */
    val CHIMNEY = floatArrayOf(11.7f, 16.0f, 11.8f)
    val WINDOW = floatArrayOf(5.6f, 9.6f, 10.4f, 12.4f)

    // ---- The Spit ----

    /** The rod turns about the line y 9.1, z 8 along x; it sits +1.6, 0 or -1.6 px for notch 0, 1, 2. */
    const val AXIS_Y = 9.1f
    const val AXIS_Z = 8.0f
    val REST_DY = floatArrayOf(1.6f, 0f, -1.6f)

    /** The six joints along the rod (x). */
    val JOINT_X = floatArrayOf(3.0f, 4.82f, 6.64f, 8.46f, 10.28f, 12.1f)

    /** Fat drips fall from y 7.8 to the tray's rim at 1.9 (x 3.2 to 12, z 6.4 to 9.6); a flare leaps from the coal beds at y 2. */
    const val DRIP_FROM = 7.8f
    const val DRIP_TO = 1.9f

    // ---- The Prep Board, the Plate, the Larder, the Menu board ----

    /** The heap's box on the board (x0, y0, z0, x1, y1, z1). */
    val HEAP = floatArrayOf(4.2f, 10.2f, 4.6f, 11.8f, 14.4f, 12.2f)

    /** The dish on a plate lies flat at (8, 1.55, 8). */
    val PLATE = floatArrayOf(8.0f, 1.55f, 8.0f)

    /** The larder door's hinge (x, z), and how far it opens. */
    const val LARDER_HINGE_X = 2.0f
    const val LARDER_HINGE_Z = 3.0f
    const val LARDER_MAX = 100f

    /** The three dishes seen through the slats: the middle of each (x, y, z). */
    val LARDER_DISHES: Array<FloatArray> = arrayOf(floatArrayOf(5.6f, 2.7f, 8f), floatArrayOf(8f, 7.0f, 8f), floatArrayOf(10.4f, 11.3f, 8f))

    /** The chalking area (x0, y0, x1, y1) of the slate on the post, on the plane z 7.05 (it looks toward -z). */
    val SLATE = floatArrayOf(1.9f, 6.7f, 14.1f, 14.3f)
    const val SLATE_Z = 7.05f

    // ---- Turning a cell's own pixels into the world ----

    /**
     * The world point of the cell pixel ([px], [py], [pz]) of the block at [pos], for a model facing north turned for
     * [facing] about the cell's middle as the blockstates turn it (east 90, south 180, west 270 degrees clockwise from above).
     */
    fun world(pos: BlockPos, facing: Direction, px: Float, py: Float, pz: Float): Vec3 {
        val dx = px - 8f
        val dz = pz - 8f
        val x: Float
        val z: Float
        when (facing) {
            Direction.EAST -> { x = -dz; z = dx }
            Direction.SOUTH -> { x = -dx; z = -dz }
            Direction.WEST -> { x = dz; z = -dx }
            else -> { x = dx; z = dz }
        }
        return Vec3(pos.x + (8f + x) * PX.toDouble(), pos.y + py * PX.toDouble(), pos.z + (8f + z) * PX.toDouble())
    }

    /** A direction in the cell's own frame (a velocity in blocks a tick) turned the same way. */
    fun turn(facing: Direction, dx: Float, dz: Float): FloatArray = when (facing) {
        Direction.EAST -> floatArrayOf(-dz, dx)
        Direction.SOUTH -> floatArrayOf(-dx, -dz)
        Direction.WEST -> floatArrayOf(dz, -dx)
        else -> floatArrayOf(dx, dz)
    }
}
