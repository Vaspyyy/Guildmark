package io.github.vaspyyy.guildmark.client.sea

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.sea.Gnashfin
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import kotlin.math.sin

/**
 * A Gnashfin. The renderer turns the whole fish: nose up or down the way it's going, banked a little into its turns,
 * thrown over onto its side floundering on a deck, and rolling belly up as it dies; [GnashfinModel] works its body,
 * tail, fins and jaw. The lights along its flanks and the lure under its chin burn on a glow layer, brighter by night.
 */
class GnashfinRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<Gnashfin, GnashfinRenderState, GnashfinModel>(context, GnashfinModel(context.bakeLayer(GnashfinModel.LAYER)), 0.45f) {

    init {
        addLayer(object : RenderLayer<GnashfinRenderState, GnashfinModel>(this) {
            override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: GnashfinRenderState, yRot: Float, xRot: Float) {
                if (state.glow < 0.01f) return
                collector.order(1).submitModel(
                    parentModel, state, poseStack, RenderTypes.eyes(GLOW), light, OverlayTexture.NO_OVERLAY, ARGB.white(state.glow), null, state.outlineColor,
                )
            }
        })
    }

    override fun createRenderState(): GnashfinRenderState = GnashfinRenderState()
    override fun getTextureLocation(state: GnashfinRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: Gnashfin, state: GnashfinRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        // It turns as a fish does, all of it at once: no body lagging behind its head
        state.bodyRot = entity.getYRot(partialTicks)
        state.yRot = 0f
        state.time = entity.tickCount + partialTicks
        state.swim = Mth.lerp(partialTicks, entity.swimO, entity.swim)
        state.bend = Mth.lerp(partialTicks, entity.bendO, entity.bend)
        state.jaw = Mth.lerp(partialTicks, entity.jawO, entity.jaw)
        state.leap = entity.leap(partialTicks)
        state.stranded = Mth.lerp(partialTicks, entity.strandedO, entity.stranded)
        state.gnaw = Mth.lerp(partialTicks, entity.gnawO, entity.gnaw)
        val dying = if (entity.deathTime > 0) ((entity.deathTime + partialTicks) / DIE_ROLL).coerceIn(0f, 1f) else 0f
        state.glow = Mth.lerp(partialTicks, entity.glowO, entity.glow) * (1f - dying)
        // Nose up is its own pitch reversed (the game's pitch is nose down)
        state.pitch = -entity.getXRot(partialTicks) * (1f - dying)
        // Banked into its turns; over on its side on a deck, bucking as it flops; belly up as it dies
        val flop = sin(state.time * 0.9f + entity.id) * 14f * state.stranded
        val roll = state.bend * BANK * (1f - state.stranded) + (STRANDED_ROLL + flop) * state.stranded
        state.roll = Mth.lerp(ease(dying), roll, 180f)
        // It's red where it's struck, not all the while it dies
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0
    }

    // Turned about the middle of its body, where the model's origin is
    override fun setupRotations(state: GnashfinRenderState, poseStack: PoseStack, bodyRot: Float, entityScale: Float) {
        super.setupRotations(state, poseStack, bodyRot, entityScale)
        poseStack.translate(0f, LIFT, 0f)
        poseStack.rotateDegrees(Axis.XP, state.pitch)
        poseStack.rotateDegrees(Axis.ZP, -state.roll)
    }

    // Its origin is its spine, not a pair of feet: undo the lift every model gets to stand its feet on the ground
    override fun scale(state: GnashfinRenderState, poseStack: PoseStack) {
        poseStack.translate(0f, MODEL_LIFT, 0f)
    }

    // It rolls belly up as it dies, rather than toppling over
    override fun getFlipDegrees(): Float = 0f

    // Its jaw and tail reach past its box, and its dorsal fin above it
    override fun getBoundingBoxForCulling(entity: Gnashfin, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(entity, partialTicks).inflate(1.2, 0.8, 1.2)

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sea/gnashfin.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/sea/gnashfin_glow.png")

        /** Blocks above the bottom of its box its spine runs (the model's origin). */
        private const val LIFT = 0.2f
        /** What LivingEntityRenderer lifts every model by, so its feet (24 px down) stand on the ground. */
        private const val MODEL_LIFT = 1.501f
        /** Degrees it banks into its hardest turn, and lies over on a deck. */
        private const val BANK = 18f
        private const val STRANDED_ROLL = 82f
        /** Ticks it takes to roll belly up as it dies. */
        private const val DIE_ROLL = 16f

        private fun ease(u: Float): Float = u * u * (3f - 2f * u)
    }
}
