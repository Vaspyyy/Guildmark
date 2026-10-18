package io.github.vaspyyy.guildmark.client.townsfolk

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The goods a townsperson has in hand (Townsperson.carried), drawn with the item's own model where their clip puts
 * them ([LivingSchedule.GOODS]): held out between both hands to be handed over or taken, lifted off the ground and
 * set back down, a sack's worth in the fist, a crop in the basket on the hip, a carrot tossed into it. Each is
 * sized so its largest side spans the span's size, and kept upright and facing the way the body does.
 */
internal class LivingGoodsLayer(parent: RenderLayerParent<TownsfolkRenderState, TownsfolkModel>) :
    RenderLayer<TownsfolkRenderState, TownsfolkModel>(parent) {
    private val at = Vector3f()
    private val to = Vector3f()
    private val forward = Vector3f()
    private val turn = Quaternionf()
    private val step = Quaternionf()

    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: TownsfolkRenderState, yRot: Float, xRot: Float) {
        val living = state.living
        val span = living.span ?: return
        if (state.isInvisible || living.goods.isEmpty) return
        val model = parentModel
        when (span.how) {
            LivingSchedule.How.HANDS -> {
                // Between the two fists, and on from there the way the body faces
                point(model, model.rightArm, -1f, 10f, 0f, at)
                point(model, model.leftArm, 1f, 10f, 0f, to)
                at.add(to).mul(0.5f)
                facing(model, turn)
                at.add(forward.mul(span.x))
            }
            LivingSchedule.How.PART -> {
                val part = part(model, span.part) ?: return
                point(model, part, span.x, span.y, span.z, at)
                val root = model.root()
                turn.rotationZYX(root.zRot, root.yRot, root.xRot).mul(step.rotationZYX(part.zRot, part.yRot, part.xRot))
            }
            LivingSchedule.How.GROUND -> {
                at.set(span.x, span.y, span.z)
                turn.identity()
            }
            LivingSchedule.How.TOSS -> {
                // Thrown on an arc to where it lands, turning over once on the way
                val part = part(model, span.toPart) ?: return
                val u = ((living.time - span.from) / (span.to - span.from)).coerceIn(0f, 1f)
                point(model, part, span.tx, span.ty, span.tz, to)
                at.set(span.x, span.y, span.z).lerp(to, u)
                at.y -= 4f * span.arc * u * (1f - u)
                facing(model, turn)
                turn.rotateX(-u * PI.toFloat())
            }
        }
        poseStack.pushPose()
        poseStack.translate(at.x / 16f, at.y / 16f, at.z / 16f)
        poseStack.rotate(turn)
        // The model's y runs down: turned over, the item stands the right way up
        poseStack.rotate(Axis.ZP, PI.toFloat())
        val scale = span.size / 16f / living.extent
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-living.middle.x, -living.middle.y, -living.middle.z)
        living.goods.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.popPose()
    }

    private fun part(model: TownsfolkModel, name: String): ModelPart? = when (name) {
        "right_arm" -> model.rightArm
        "left_arm" -> model.leftArm
        "body" -> model.body
        "head" -> model.head
        else -> null
    }

    /** The point ([x], [y], [z]) of [part]'s own space, in the model's (px), as the model is posed now. */
    private fun point(model: TownsfolkModel, part: ModelPart, x: Float, y: Float, z: Float, out: Vector3f): Vector3f {
        val root = model.root()
        out.set(x, y, z)
        step.rotationZYX(part.zRot, part.yRot, part.xRot).transform(out).add(part.x, part.y, part.z)
        step.rotationZYX(root.zRot, root.yRot, root.xRot).transform(out).add(root.x, root.y, root.z)
        return out
    }

    /** [out] turned about the upright to face the way the body does (and [forward] that way, level). */
    private fun facing(model: TownsfolkModel, out: Quaternionf) {
        val root = model.root()
        val body = model.body
        forward.set(0f, 0f, -1f)
        step.rotationZYX(body.zRot, body.yRot, body.xRot).transform(forward)
        step.rotationZYX(root.zRot, root.yRot, root.xRot).transform(forward)
        forward.y = 0f
        val length = sqrt(forward.x * forward.x + forward.z * forward.z)
        if (length < 1e-4f) forward.set(0f, 0f, -1f) else forward.div(length)
        out.rotationY(atan2(-forward.x, -forward.z))
    }
}
