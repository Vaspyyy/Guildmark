package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.ModelDebugName
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.event.ModelEvent
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey

/**
 * The moving pieces of the stations (living-world/cook/design.md 11.1): each is a block model of its own, named
 * `cook_<station>_<piece>` and authored facing north, that the shell leaves out and a renderer draws where it belongs.
 * Their pivots and ranges are the contract at the head of `art/scripts/cook_art.py` ([CookGeo] has the numbers).
 *
 * The game bakes them as standalone models ([register] asks it to) and a piece is drawn one of two ways: whole, as a cutout
 * block model ([solid]), or faded, as translucent quads with a vertex alpha ([faded]), for a soot that is coming or going, the
 * glow of an oven's door, a burnt look setting in. A piece the game could not bake draws nothing.
 */
object CookPieces {
    class Piece(val name: String) {
        /** Where the model is (`guildmark:block/<name>`). */
        val id: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "block/$name")

        /** What the game keeps its baked model under. */
        val key: StandaloneModelKey<BlockStateModelPart> = StandaloneModelKey(ModelDebugName { "guildmark:$name" })

        private var held: BlockStateModelPart? = null
        private var single: List<BlockStateModelPart> = emptyList()

        /** The baked model, or null if the game has none (the list of one is kept so a frame makes no garbage). */
        fun part(): BlockStateModelPart? {
            val baked: BlockStateModelPart? = try {
                Minecraft.getInstance().modelManager.getStandaloneModel(key)
            } catch (e: RuntimeException) {
                null
            }
            if (baked == null) return null
            if (baked !== held) {
                held = baked
                single = listOf(baked)
            }
            return baked
        }

        fun list(): List<BlockStateModelPart> = single
    }

    // The stewpot
    val POT = Piece("cook_stewpot_pot")
    val SOOT_1 = Piece("cook_stewpot_soot_1")
    val SOOT_2 = Piece("cook_stewpot_soot_2")
    val SOOT_3 = Piece("cook_stewpot_soot_3")
    val CRANE = Piece("cook_stewpot_crane")

    // The hob
    val PAN = Piece("cook_hob_pan")
    val HOB_BURNT = Piece("cook_hob_burnt")

    // The bake oven
    val OVEN_DOOR = Piece("cook_oven_door")
    val OVEN_DOOR_HOT = Piece("cook_oven_door_hot")
    val OVEN_TRAY = Piece("cook_oven_tray")
    val OVEN_BURNT = Piece("cook_oven_burnt")

    // The spit
    val SPIT_ROD = Piece("cook_spit_rod")
    val SPIT_CRANK = Piece("cook_spit_crank")
    val SPIT_BURNT = Piece("cook_spit_burnt")

    // The larder
    val LARDER_DOOR = Piece("cook_larder_door")

    val SOOT: Array<Piece> = arrayOf(SOOT_1, SOOT_2, SOOT_3)

    private val ALL: List<Piece> = listOf(
        POT, SOOT_1, SOOT_2, SOOT_3, CRANE, PAN, HOB_BURNT, OVEN_DOOR, OVEN_DOOR_HOT, OVEN_TRAY, OVEN_BURNT, SPIT_ROD, SPIT_CRANK, SPIT_BURNT, LARDER_DOOR,
    )

    /** Asks the game to bake each piece as a standalone model (on the mod bus, at model registration). */
    fun register(event: ModelEvent.RegisterStandalone) {
        for (piece in ALL) event.register(piece.key, SimpleUnbakedStandaloneModel.simpleModelWrapper(piece.id))
    }

    /** The faces to draw: the unculled quads and each side's (a piece has no neighbour to cull against). */
    private val FACES: Array<Direction?> = arrayOf(null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)

    /** Draws [piece] whole, in the pose [poseStack] has now, lit by [light]. */
    fun solid(collector: SubmitNodeCollector, poseStack: PoseStack, piece: Piece, light: Int) {
        if (piece.part() == null) return
        collector.submitBlockModel(poseStack, Sheets.cutoutBlockItemSheet(), piece.list(), EMPTY_TINTS, light, OverlayTexture.NO_OVERLAY, 0)
    }

    /**
     * Draws [piece] [alpha] solid (0 to 1) in the pose [poseStack] has now: as translucent quads, a layer [order] of the frame's
     * translucent geometry, so a faded piece lies over what was drawn before it.
     */
    fun faded(collector: SubmitNodeCollector, poseStack: PoseStack, piece: Piece, light: Int, alpha: Float, order: Int = 1) {
        if (alpha <= 0.004f) return
        val part = piece.part() ?: return
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        collector.order(order).submitCustomGeometry(poseStack, Sheets.translucentBlockItemSheet()) { pose, buffer ->
            val quad = QuadInstance()
            quad.setColor((a shl 24) or 0xFFFFFF)
            quad.setLightCoords(light)
            quad.setOverlayCoords(OverlayTexture.NO_OVERLAY)
            for (face in FACES) {
                for (q in part.getQuads(face)) buffer.putBakedQuad(pose, q, quad)
            }
        }
    }

    private val EMPTY_TINTS = IntArray(0)
}
