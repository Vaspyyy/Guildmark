package io.github.vaspyyy.guildmark.client.island

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.island.Gull
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import kotlin.math.PI
import kotlin.math.sin

/**
 * A gull. The renderer turns the whole bird in flight, about the middle of its body: nose down in a glide and up in a
 * climb, banked over into its turns; and rocks it on the swell when it rides the water. [GullModel] does the rest, from
 * how the gull moves (eased on the entity from tick to tick) and the action it's in, on the action's own clock.
 */
class GullRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<Gull, GullRenderState, GullModel>(context, GullModel(context.bakeLayer(GullModel.LAYER)), 0.22f) {

    override fun createRenderState(): GullRenderState = GullRenderState()

    override fun getTextureLocation(state: GullRenderState): Identifier = TEXTURES[state.variant.coerceIn(0, TEXTURES.size - 1)]

    override fun extractRenderState(entity: Gull, state: GullRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        fun lerp(from: Float, to: Float) = Mth.lerp(partialTicks, from, to)
        state.time = entity.tickCount + partialTicks
        state.flying = lerp(entity.flyingO, entity.flying)
        state.spread = lerp(entity.spreadO, entity.spread)
        state.flap = lerp(entity.flapO, entity.flap)
        state.flapPhase = wrapped(partialTicks, entity.flapPhaseO, entity.flapPhase)
        state.bank = lerp(entity.bankO, entity.bank)
        state.hover = lerp(entity.hoverO, entity.hover)
        state.floating = lerp(entity.floatingO, entity.floating)
        // Where it looks: its head turned from its body, tipped up (the game's pitch is head down)
        state.headYaw = Mth.clamp(state.yRot, -100f, 100f)
        state.headPitch = Mth.clamp(-state.xRot, -40f, 50f)
        state.beak = lerp(entity.beakO, entity.beak)
        state.walk = lerp(entity.walkO, entity.walk)
        state.walkPhase = wrapped(partialTicks, entity.walkPhaseO, entity.walkPhase)
        state.action = entity.action
        state.actionTime = entity.actionTime(partialTicks)
        state.fish = entity.fish
        state.variant = entity.variant
        state.flightPitch = lerp(entity.pitchO, entity.pitch)
        // Riding the swell: a slow rise and fall, and a quicker small one
        state.bob = state.floating * (SWELL * sin(state.time * 0.11f + entity.id) + CHOP * sin(state.time * 0.29f + entity.id * 2f))
    }

    override fun setupRotations(state: GullRenderState, poseStack: PoseStack, bodyRot: Float, entityScale: Float) {
        super.setupRotations(state, poseStack, bodyRot, entityScale)
        poseStack.translate(0f, state.bob, 0f)
        // Pitched and banked about the middle of its body
        val pivot = GullModel.BODY_Y / 16f * entityScale * size(state)
        poseStack.translate(0f, pivot, 0f)
        poseStack.rotateDegrees(Axis.XP, state.flightPitch)
        poseStack.rotateDegrees(Axis.ZP, -state.bank * MAX_BANK)
        poseStack.translate(0f, -pivot, 0f)
    }

    // A young gull is a little smaller
    override fun scale(state: GullRenderState, poseStack: PoseStack) {
        val s = size(state)
        if (s != 1f) poseStack.scale(s, s, s)
    }

    // Its wings reach well past its box in flight
    override fun getBoundingBoxForCulling(entity: Gull, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(entity, partialTicks).inflate(0.9, 0.4, 0.9)

    companion object {
        private val TEXTURES = arrayOf(
            Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/island/gull.png"),
            Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/island/gull_blackback.png"),
            Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/island/gull_young.png"),
        )
        private const val TAU = (PI * 2.0).toFloat()
        /** Degrees it banks over in its hardest turn. */
        private const val MAX_BANK = 38f
        /** Blocks it rises and falls on the swell, and on the chop. */
        private const val SWELL = 0.035f
        private const val CHOP = 0.012f
        private const val YOUNG_SIZE = 0.9f

        private fun size(state: GullRenderState): Float = if (state.variant == Gull.YOUNG) YOUNG_SIZE else 1f

        /** Between last tick's and this tick's turn of a phase that wraps round at a full turn. */
        private fun wrapped(partial: Float, from: Float, to: Float): Float {
            val end = if (to < from) to + TAU else to
            return Mth.lerp(partial, from, end)
        }
    }
}
