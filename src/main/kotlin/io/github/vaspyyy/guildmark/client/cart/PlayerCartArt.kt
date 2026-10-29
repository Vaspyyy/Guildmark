package io.github.vaspyyy.guildmark.client.cart

import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.resources.Identifier
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.trade.PlayerCartMesh

/**
 * Where the Chapman's Cart gets its dressing (living-world/trader/design.md, Part 3; build parts D2 and X2): the pole with the pennant on it and the
 * canvas over the load, drawn on top of the carters' handcart (the cart itself is the handcart's own mesh and texture).
 *
 * **What the renderer reads of a dressing's mesh.** One layer, in the handcart's model space (pixels, y down, the origin the middle of the axle, the
 * shafts toward -z, the tail toward +z). Every part is a direct child of the root, optional, and found by its name:
 *
 * - `pole`: the short pole on the left rear post, still.
 * - `flag_0` to `flag_N`: the pennant, in the order from the pole outward. It is tinted with the cart's saved colour, so it is painted white (or grey,
 *   for the shading). Each segment is modelled where it hangs and is turned about the vertical axis at its [Table.ripples] hinge (x, z), the next
 *   segment riding on the one before: the pennant ripples, more the faster the cart goes.
 * - `canvas_0` to `canvas_N`: the canvas over the load, in strips from the tail forward, modelled flat where they lie when the canvas is over (the top
 *   face at [Table.canvasTop]). Strip k folds back onto the strip before it about its [Table.folds] hinge (y, z), the strips after it riding on it: the
 *   canvas folds back onto the tail strip one strip at a time, the front one first. It is lifted as a whole to clear the load.
 *
 * Nothing else is read: a part that is missing is not drawn.
 */
internal object PlayerCartArt {
    /** A point a part turns about: two of (x, y, z) in px, as the part's kind says (see [Table]). */
    class Hinge(val a: Float, val b: Float)

    /** One dressing: its mesh and texture, the tints of what is not painted for the cart's colour, and where its parts turn. */
    class Table(
        val mesh: () -> LayerDefinition,
        val texture: Identifier,
        /** ARGB tint of the pole and of the canvas (-1: painted, no tint). */
        val poleTint: Int,
        val canvasTint: Int,
        /** The canvas's top face (px, y down) as the mesh has it, and the hinge (y, z) of each strip from the second on. */
        val canvasTop: Float,
        val folds: List<Hinge>,
        /** The pennant's hinges (x, z) from the pole outward, one for each segment. */
        val ripples: List<Hinge>,
        /** The texture the cart itself is drawn with (its livery, on the handcart's own UVs), or null for the carters' own. */
        val body: Identifier? = null,
        /** The canvas's wet look, drawn over each strip translucent with the alpha of how wet it is, or null for none. */
        val wet: Identifier? = null,
        /** Px the canvas's top face lies over the highest point under it. */
        val lift: Float = 2.4f,
        /** Each of HandcartMesh.PIECES' highest point over its foot (px; its handle, its knot, the goods shown on it), or null for its stacking height. */
        val tops: FloatArray? = null,
        /**
         * The canvas's front edge when it is over (px): with it a piece lifts the canvas only once the canvas is laid over that piece's back edge,
         * [reach] px toward the tail from its place (folded, the canvas covers the tail strip, back from [folds]' first hinge); null: every piece does.
         */
        val front: Float? = null,
        val reach: Float = 0f,
    )

    /**
     * THE ONE CALL (stream X2): the dressing drawn from X2's [PlayerCartMesh] (the handcart's model in the Chapman's livery, the pennant on its pole and
     * the canvas as a second part that folds in three steps, its wet look for rain), or null for the stand-in below: the carters' handcart with a pole, a
     * pennant of white wool tinted with the cart's colour and a canvas of four plain strips.
     */
    fun table(): Table? = x2

    private val x2: Table by lazy {
        val texture = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, PlayerCartMesh.TEXTURE)
        Table(
            PlayerCartMesh::dressing, texture, -1, -1, PlayerCartMesh.CANVAS_TOP, hinges(PlayerCartMesh.FOLDS), hinges(PlayerCartMesh.RIPPLES),
            body = texture,
            wet = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, PlayerCartMesh.WET),
            lift = PlayerCartMesh.CANVAS_LIFT,
            tops = PlayerCartMesh.TOPS,
            front = PlayerCartMesh.CANVAS_FRONT,
            reach = PlayerCartMesh.PIECE_REACH,
        )
    }

    /** (a, b) pairs as hinges. */
    private fun hinges(pairs: FloatArray): List<Hinge> = (0 until pairs.size / 2).map { Hinge(pairs[2 * it], pairs[2 * it + 1]) }

    /** The dressing in use: X2's, or the stand-in. */
    fun active(): Table = table() ?: STAND_IN

    val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "player_cart"), "dressing")

    // ---- The stand-in (until X2's art): vanilla's white wool for cloth, tinted ----

    private val WOOL = Identifier.withDefaultNamespace("textures/block/white_wool.png")

    /** The stand-in canvas: four strips, each [LEN] px long and [THICK] thick, the top face at [TOP] (px, y down) when it lies over the bed. */
    private const val TOP = -10.4f
    private const val THICK = 0.5f
    private const val LEN = 6.2f
    private const val TAIL = 4.3f
    private const val POLE_X = 7.1f
    private const val FLAG_Z = 9.6f

    private val STAND_IN = Table(
        mesh = ::standIn,
        texture = WOOL,
        poleTint = 0xFF5B3E22.toInt(),
        canvasTint = 0xFFD2C49A.toInt(),
        canvasTop = TOP,
        // Strip 1 folds onto strip 0 about its top face, strip 2 about the bottom face of strip 1, strip 3 about the top of strip 2: folded, the four
        // lie one on another in the order 0, 1, 2, 3 (worked out on paper, and the hinges' arithmetic run, not seen in the game)
        folds = listOf(
            Hinge(TOP, TAIL),
            Hinge(TOP + THICK, TAIL - LEN),
            Hinge(TOP, TAIL - 2 * LEN),
        ),
        ripples = listOf(Hinge(POLE_X, FLAG_Z), Hinge(POLE_X, FLAG_Z + 3f), Hinge(POLE_X, FLAG_Z + 6f)),
    )

    private fun standIn(): LayerDefinition {
        val mesh = MeshDefinition()
        val root = mesh.root
        // The pole on the left rear post, standing on its top (the post's top face is at y = -8.75)
        root.addOrReplaceChild("pole", CubeListBuilder.create().texOffs(0, 0).addBox(POLE_X - 0.5f, -24f, FLAG_Z - 1f, 1f, 15.3f, 1f), PartPose.ZERO)
        // The pennant: three segments narrowing to the tip
        root.addOrReplaceChild("flag_0", CubeListBuilder.create().texOffs(0, 0).addBox(POLE_X - 0.2f, -23.6f, FLAG_Z, 0.4f, 5f, 3f), PartPose.ZERO)
        root.addOrReplaceChild("flag_1", CubeListBuilder.create().texOffs(0, 0).addBox(POLE_X - 0.2f, -23.6f, FLAG_Z + 3f, 0.4f, 3.4f, 3f), PartPose.ZERO)
        root.addOrReplaceChild("flag_2", CubeListBuilder.create().texOffs(0, 0).addBox(POLE_X - 0.2f, -23.6f, FLAG_Z + 6f, 0.4f, 1.8f, 3f), PartPose.ZERO)
        // The canvas: from the tail forward
        for (k in 0..3) {
            root.addOrReplaceChild(
                "canvas_$k",
                CubeListBuilder.create().texOffs(0, 0).addBox(-6.3f, TOP, TAIL - LEN * k, 12.6f, THICK, LEN),
                PartPose.ZERO,
            )
        }
        return LayerDefinition.create(mesh, 64, 64)
    }
}
