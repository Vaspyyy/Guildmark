package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.resources.Identifier

/**
 * Where the Carrier's Wagon gets its dressing (living-world/trader/design.md, Part 3; build parts D3 and X2): the name boards, the guards' seats and
 * the owner's pennant, drawn on top of the caravans' own wagon (the wagon itself is the caravans' mesh and texture, its own pennant hidden).
 *
 * **What the renderer reads of a dressing's mesh.** One layer, in the caravans' wagon's model space (WagonMesh's: pixels, y down, the origin the back
 * axle's ground, its front toward -z, its right toward -x). Every part is a direct child of the root, optional, and found by its name:
 *
 * - `board_r`, `board_l`: the name boards on the bed's right and left sides, tinted [Table.boardTint]; the owner's name is written on each at
 *   [Table.names].
 * - `seat_bench`: the guard's cushion on the bench's right (where a guard sits, `Wagon.DRIVER`), and `seat_tail`: the pad on the tailboard, modelled
 *   where it lies with the tailboard let down and drawn only while it is down; both tinted [Table.seatTint].
 * - `flag_0` to `flag_N`: the pennant, from the staff outward, tinted with the owner's colour (so painted white, or grey for the shading), modelled
 *   flying from the staff's top. Segment k turns about the vertical at its [Table.ripples] hinge (x, z), the next riding on it; the whole is
 *   [Table.staffRun] px lower while it is furled and runs up the staff as the wagon is rolled out.
 *
 * Nothing else is read: a part that is missing is not drawn.
 */
internal object CarrierWagonArt {
    /** A point a part turns about: (x, z) in px. */
    class Hinge(val a: Float, val b: Float)

    /** Where a name is written: the middle of a board's outer face (px), its side (-1 the right, 1 the left), and the room for it (blocks). */
    class Name(val x: Float, val y: Float, val z: Float, val side: Float, val wide: Float, val high: Float)

    /** One dressing: its mesh and texture, the tints of what is not the owner's colour, where the names go, the pennant's hinges and its run. */
    class Table(
        val mesh: () -> LayerDefinition,
        val texture: Identifier,
        /** ARGB tint of the boards and the seats (-1: painted, no tint). */
        val boardTint: Int,
        val seatTint: Int,
        val names: List<Name>,
        val ripples: List<Hinge>,
        val staffRun: Float,
    )

    /**
     * THE ONE CALL (stream X2): the dressing drawn from X2's wagon table (the name boards, the seats and the colour), or null while that table has not
     * landed on main. With null the stand-in below is drawn: plain planks for the boards, a cushion and a pad, a pennant of white wool tinted with the
     * owner's colour. When it lands this is the one line to change, to `Table(<X2's mesh>, <its texture>, -1, -1, <its names>, <its hinges>, <its run>)`.
     */
    fun table(): Table? = null

    /** The dressing in use: X2's, or the stand-in. */
    fun active(): Table = table() ?: STAND_IN

    val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "carrier_wagon"), "dressing")

    // ---- The stand-in (until X2's art): vanilla's white wool, tinted ----

    private val WOOL = Identifier.withDefaultNamespace("textures/block/white_wool.png")

    /** The boards: out from the middle past the stakes, their top and height, their front and length (px), between the second and third stakes. */
    private const val BOARD_OUT = 12.45f
    private const val BOARD_TOP = -25.6f
    private const val BOARD_HIGH = 5f
    private const val BOARD_FRONT = -18.2f
    private const val BOARD_LONG = 10.8f
    private const val BOARD_THICK = 0.5f

    /** The pennant: the staff's top (where the caravans' own flies from), each segment's length and height. */
    private const val FLAG_Y = -60f
    private const val FLAG_Z = -38.5f
    private const val FLAG_LONG = 4.8f

    private val STAND_IN = Table(
        mesh = ::standIn,
        texture = WOOL,
        boardTint = 0xFFB08A5E.toInt(),
        seatTint = 0xFF7A3B2E.toInt(),
        names = listOf(
            Name(-(BOARD_OUT + BOARD_THICK), BOARD_TOP + BOARD_HIGH / 2f, BOARD_FRONT + BOARD_LONG / 2f, -1f, 0.6f, 0.22f),
            Name(BOARD_OUT + BOARD_THICK, BOARD_TOP + BOARD_HIGH / 2f, BOARD_FRONT + BOARD_LONG / 2f, 1f, 0.6f, 0.22f),
        ),
        ripples = listOf(Hinge(0f, FLAG_Z), Hinge(0f, FLAG_Z + FLAG_LONG), Hinge(0f, FLAG_Z + 2 * FLAG_LONG)),
        staffRun = 26f,
    )

    private fun standIn(): LayerDefinition {
        val mesh = MeshDefinition()
        val root = mesh.root
        // The name boards, a plank on each side between two stakes
        root.addOrReplaceChild(
            "board_r",
            CubeListBuilder.create().texOffs(0, 0).addBox(-(BOARD_OUT + BOARD_THICK), BOARD_TOP, BOARD_FRONT, BOARD_THICK, BOARD_HIGH, BOARD_LONG),
            PartPose.ZERO,
        )
        root.addOrReplaceChild(
            "board_l",
            CubeListBuilder.create().texOffs(0, 0).addBox(BOARD_OUT, BOARD_TOP, BOARD_FRONT, BOARD_THICK, BOARD_HIGH, BOARD_LONG),
            PartPose.ZERO,
        )
        // The guard's cushion on the bench's right (its seat's top is at y = -27.5), and the pad on the let-down tailboard (its top at y = -19)
        root.addOrReplaceChild("seat_bench", CubeListBuilder.create().texOffs(0, 0).addBox(-10.5f, -28.1f, -48.5f, 8f, 0.7f, 5.5f), PartPose.ZERO)
        root.addOrReplaceChild("seat_tail", CubeListBuilder.create().texOffs(0, 0).addBox(-5f, -19.6f, 11f, 10f, 0.6f, 4.5f), PartPose.ZERO)
        // The pennant: three segments narrowing to the tip, flying back from the staff's top
        root.addOrReplaceChild("flag_0", CubeListBuilder.create().texOffs(0, 0).addBox(-0.2f, FLAG_Y - 2.1f, FLAG_Z, 0.4f, 4.2f, FLAG_LONG), PartPose.ZERO)
        root.addOrReplaceChild("flag_1", CubeListBuilder.create().texOffs(0, 0).addBox(-0.2f, FLAG_Y - 1.8f, FLAG_Z + FLAG_LONG, 0.4f, 3.6f, FLAG_LONG), PartPose.ZERO)
        root.addOrReplaceChild("flag_2", CubeListBuilder.create().texOffs(0, 0).addBox(-0.2f, FLAG_Y - 1.5f, FLAG_Z + 2 * FLAG_LONG, 0.4f, 3f, FLAG_LONG), PartPose.ZERO)
        return LayerDefinition.create(mesh, 64, 64)
    }
}
