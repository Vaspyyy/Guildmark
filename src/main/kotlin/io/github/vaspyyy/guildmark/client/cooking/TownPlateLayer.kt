package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkModel
import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkRenderState
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dish on the table in front of a townsperson eating at the inn (living-world/cook/design.md 7.4): the served dish's own
 * item model, the one the player's dish of that kind and tier has, laid flat on the table's top where [TownTables] found room
 * for it, its top toward the far side of the table. [TownPlates.eat] decides who has a plate and fills it into the render
 * state; this only draws it, set down on the table (it grows out of it, a little above it, in the moments after a diner sits)
 * and taken up as they rise.
 *
 * The plate belongs to the world, not to the diner: it is placed from the table's own place (the spot is in world coordinates)
 * and drawn through the entity's pose, so it stays where it is whichever way the diner has turned, and it is not scaled or
 * leaned with them. The pose a layer is handed is the model's own space (pixels over sixteen, y down, the front toward -z, the
 * character's right toward -x), turned to the body's heading, flipped by (-1, -1, 1) and lowered by 1.501 (the vanilla living
 * renderer's chain); this undoes exactly that to place the plate by its world offset from the entity, and stands the item
 * up the right way as the goods layer does before laying it down.
 */
internal class TownPlateLayer(parent: RenderLayerParent<TownsfolkRenderState, TownsfolkModel>) :
    RenderLayer<TownsfolkRenderState, TownsfolkModel>(parent) {

    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: TownsfolkRenderState, yRot: Float, xRot: Float) {
        val plate = state.living.plate ?: return
        if (state.isInvisible) return
        val dish = plate.dish
        val grow = plate.grow(state.ageInTicks)
        if (grow <= 0.002f || !dish.ok || dish.render.isEmpty) return
        val spot = plate.spot
        // The renderer's own scale is undone: the plate is the size it is in the world
        val inv = if (state.scale > 0.01f) 1.0 / state.scale else 1.0
        // Half the plate's thickness at the size it has now, so its underside is just clear of the table whatever the model's thickness
        val half = dish.thick * spot.size / dish.extent * 0.5 * grow
        // Where its middle is, from the entity, in world axes; then turned by the body's heading into the model's axes (x and y flipped, lowered 1.501)
        val wx = (spot.x - state.x) * inv
        val wy = (spot.y + CLEAR + half + (1.0 - grow) * SETTLE - state.y) * inv
        val wz = (spot.z - state.z) * inv
        val heading = Math.toRadians(state.bodyRot - 180.0)
        val c = cos(heading)
        val s = sin(heading)
        val ux = wx * c + wz * s
        val uz = -wx * s + wz * c
        poseStack.pushPose()
        poseStack.translate(-ux, DROP - wy, uz)
        // Stood up the right way as the goods layer does (the flip of the pose undone), then laid on its back with its top toward the far side
        poseStack.rotate(Axis.ZP, PI.toFloat())
        poseStack.rotate(Axis.YP, (heading + atan2(spot.dx.toDouble(), spot.dz.toDouble())).toFloat())
        poseStack.rotate(Axis.XP, (PI / 2).toFloat())
        val scale = (spot.size * grow / dish.extent * inv).toFloat()
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-dish.mid.x, -dish.mid.y, -dish.mid.z)
        dish.render.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.popPose()
    }

    private companion object {
        /** How far above the table the underside of a plate floats, so it does not fight the table's top. */
        const val CLEAR = 0.012

        /** How far above its place a plate is as it begins to be set down, in blocks. */
        const val SETTLE = 0.07

        /** The renderer lowers the model by this (`LivingEntityRenderer`): the feet at 24 px come to the entity's feet. */
        const val DROP = 1.501
    }
}
