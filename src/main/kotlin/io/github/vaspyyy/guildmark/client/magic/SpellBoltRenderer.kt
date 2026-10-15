package io.github.vaspyyy.guildmark.client.magic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.magic.BoltKind
import io.github.vaspyyy.guildmark.magic.SpellBolt
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.item.ItemDisplayContext
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.sin

class SpellBoltRenderState : EntityRenderState() {
    val item = ItemStackRenderState()
    var kind = BoltKind.ARC_BOLT
    val velocity = Vector3f()
}

/**
 * A spell in flight: its glowing sprite, always facing the camera and lit as if by day. Each kind is drawn at
 * its own size; the orbs and the star turn slowly and pulse, the ember pulses upright like a flame, and the splinters
 * and the lance point the way they fly.
 */
class SpellBoltRenderer(context: EntityRendererProvider.Context) : EntityRenderer<SpellBolt, SpellBoltRenderState>(context) {
    private val itemModelResolver: ItemModelResolver = context.itemModelResolver

    override fun createRenderState(): SpellBoltRenderState = SpellBoltRenderState()

    override fun extractRenderState(entity: SpellBolt, state: SpellBoltRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.kind = entity.kind
        val motion = entity.deltaMovement
        state.velocity.set(motion.x.toFloat(), motion.y.toFloat(), motion.z.toFloat())
        itemModelResolver.updateForNonLiving(state.item, entity.item, ItemDisplayContext.GROUND, entity)
    }

    override fun submit(state: SpellBoltRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val kind = state.kind
        val t = state.ageInTicks
        val turns = kind == BoltKind.ARC_BOLT || kind == BoltKind.UNDERTOW || kind == BoltKind.STAR
        val pulse = if (turns || kind == BoltKind.WAYFLAME) 1f + 0.08f * sin(t * 0.6f) else 1f
        val scale = kind.scale * pulse
        poseStack.pushPose()
        poseStack.translate(0f, 0.2f, 0f)
        poseStack.scale(scale, scale, scale)
        poseStack.rotate(camera.orientation)
        if (turns) poseStack.rotateDegrees(Axis.ZP, t * (if (kind == BoltKind.UNDERTOW) -14f else 6f))
        else if (kind != BoltKind.WAYFLAME && state.velocity.lengthSquared() > 1e-6f) {
            // Turned so the sprite's diagonal (painted pointing up and right) lies along its flight as seen on screen
            val seen = camera.orientation.conjugate(Quaternionf()).transform(Vector3f(state.velocity))
            poseStack.rotateDegrees(Axis.ZP, Math.toDegrees(atan2(seen.y, seen.x).toDouble()).toFloat() - 45f)
        }
        state.item.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }
}
