package io.github.vaspyyy.guildmark.client.mill

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.mill.MillBlockEntity
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import kotlin.math.PI

/**
 * A windmill's sails' timber frame ([MillMesh.sails]), turned as far as [MillRenderState.turn] says, against the clock
 * seen from in front, with a broken sail's outer half hanging from where it broke. Drawn in its city's wood.
 */
class SailsModel(root: ModelPart) : Model<MillRenderState>(root, RenderTypes::entityCutout) {
    private val rig = SailRig(root)

    override fun setupAnim(state: MillRenderState) {
        resetPose()
        rig.pose(state)
    }

    companion object {
        /** Where the frame's mesh is registered. */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "windmill_sails"), "main")
    }
}

/**
 * A windmill's sails' cloth ([MillMesh.cloth]), on the same rotor, arms and joints as [SailsModel] and posed the same:
 * spread on the bars while the sails turn, drawn in to the whips and rolled up there as they stand
 * ([MillRenderState.furl]), and torn on a broken sail. The canvas is white: the renderer tints it with the cloth's dye.
 */
class ClothModel(root: ModelPart) : Model<MillRenderState>(root, RenderTypes::entityCutout) {
    private val rig = SailRig(root)
    private val find = root.createPartLookup()
    private val sails = parts("sail")
    private val sailTips = parts("sail_tip")
    private val torn = parts("torn")
    private val tornTips = parts("torn_tip")
    private val rolls = parts("roll")
    private val rollTips = parts("roll_tip")

    private fun parts(name: String): Array<ModelPart> = Array(4) { find.apply("$name$it")!! }

    override fun setupAnim(state: MillRenderState) {
        resetPose()
        rig.pose(state)
        // Furled, the canvas is drawn in toward the whip (its joint is at the whip's edge) and bundled in a roll along it
        val spread = 1f - FURLED * state.furl
        val rolled = state.furl > 0.02f
        for (k in 0..3) {
            val whole = k != state.broken
            sails[k].visible = whole
            sailTips[k].visible = whole
            sails[k].xScale = spread
            sailTips[k].xScale = spread
            torn[k].visible = !whole
            tornTips[k].visible = !whole
            rolls[k].visible = whole && rolled
            rollTips[k].visible = whole && rolled
            rolls[k].xScale = state.furl
            rollTips[k].xScale = state.furl
        }
    }

    companion object {
        /** Where the cloth's mesh is registered. */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "windmill_cloth"), "main")

        // How much of the canvas's width goes into the roll when it's furled
        private const val FURLED = 0.94f
    }
}

/** A watermill's wheel ([MillMesh.wheel]), turned on its axle as far as [MillRenderState.turn] says. */
class WheelModel(root: ModelPart) : Model<MillRenderState>(root, RenderTypes::entityCutout) {
    private val wheel = root.getChild("wheel")

    override fun setupAnim(state: MillRenderState) {
        resetPose()
        wheel.zRot = state.turn
    }

    companion object {
        /** Where the wheel's mesh is registered. */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "water_wheel"), "main")
    }
}

/** The rotor, the four arms and their outer halves that both of the sails' models have, and how they're posed. */
private class SailRig(root: ModelPart) {
    private val find = root.createPartLookup()
    private val rotor = find.apply("rotor")!!
    private val arms = Array(4) { find.apply("arm$it")!! }
    private val tips = Array(4) { find.apply("tip$it")!! }

    fun pose(state: MillRenderState) {
        // The sails turn against the clock seen from in front, which is the model's own turn backwards
        rotor.zRot = -state.turn
        for (k in 0..3) {
            arms[k].zRot = k * MillBlockEntity.QUARTER
            // A broken sail's outer half hangs straight down from where it broke, swinging a little
            tips[k].zRot = if (k == state.broken) PI.toFloat() + state.swing - rotor.zRot - arms[k].zRot else 0f
        }
    }
}
