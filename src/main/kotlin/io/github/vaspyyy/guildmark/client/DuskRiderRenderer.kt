package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.Clips
import io.github.vaspyyy.guildmark.client.model.DuskRiderModel
import io.github.vaspyyy.guildmark.client.model.DuskRiderRenderState
import io.github.vaspyyy.guildmark.combat.EnemyAttack
import io.github.vaspyyy.guildmark.enemy.DuskRider
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB

/**
 * The Dusk Rider, a little over a horse's size. Its visor and lantern glow from the start; once it's ignited, so
 * does the cold fire in its steed's mane, tail and hooves. At dawn it fades away.
 */
class DuskRiderRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<DuskRider, DuskRiderRenderState, DuskRiderModel>(context, DuskRiderModel(context.bakeLayer(DuskRiderModel.LAYER)), 1.2f) {

    init {
        addLayer(object : RenderLayer<DuskRiderRenderState, DuskRiderModel>(this) {
            override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: DuskRiderRenderState, yRot: Float, xRot: Float) {
                if (state.fade <= 0.02f) return
                // The glow blends by its alpha, so it fades with the rest of it
                collector.order(1).submitModel(
                    parentModel, state, poseStack, RenderTypes.eyes(if (state.ignited) GLOW_2 else GLOW), light, OverlayTexture.NO_OVERLAY,
                    ARGB.white(state.fade), null, state.outlineColor,
                )
            }
        })
    }

    override fun createRenderState(): DuskRiderRenderState = DuskRiderRenderState()
    override fun getTextureLocation(state: DuskRiderRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: DuskRider, state: DuskRiderRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.stride = Mth.lerp(partialTicks, entity.strideO, entity.stride)
        state.gaitSpeed = Mth.lerp(partialTicks, entity.gaitSpeedO, entity.gaitSpeed)
        state.ignited = entity.ignited
        state.fade = entity.fade(partialTicks)
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0
        state.dying = if (entity.deathTime > 0) entity.deathTime + partialTicks else -1f
        // The attack it's seen doing, over the one it cut short as that fades
        state.cut = null
        state.attack = null
        entity.cutShortAttack(partialTicks)?.let { (attack, time, fade) ->
            val clip = Clips.get(clipName(attack)) ?: return@let
            state.cut = clip
            state.cutTime = time
            state.cutWeight = clip.weight(time) * fade
        }
        entity.shownAttack(partialTicks)?.let { (attack, time) ->
            val clip = Clips.get(clipName(attack)) ?: return@let
            state.attack = clip
            state.attackTime = time
            state.attackWeight = clip.weight(time)
        }
    }

    private fun clipName(attack: EnemyAttack): String = clipNames.getOrPut(attack) { DuskRider.ANIMATIONS + "/" + attack.anim }

    // Its halberd reaches well past its body: 6 blocks up as it rears, 3 out to the side as it rides past
    override fun getBoundingBoxForCulling(entity: DuskRider, partialTicks: Float): AABB =
        super.getBoundingBoxForCulling(entity, partialTicks).inflate(2.3, 0.0, 2.3).expandTowards(0.0, 2.5, 0.0)

    override fun getRenderType(state: DuskRiderRenderState, isBodyVisible: Boolean, forceTransparent: Boolean, appearGlowing: Boolean): RenderType? =
        if (isBodyVisible && state.fade < 1f) RenderTypes.entityTranslucent(getTextureLocation(state))
        else super.getRenderType(state, isBodyVisible, forceTransparent, appearGlowing)

    override fun getModelTint(state: DuskRiderRenderState): Int = if (state.fade < 1f) ARGB.white(state.fade) else -1

    override fun getShadowRadius(state: DuskRiderRenderState): Float = super.getShadowRadius(state) * state.fade

    override fun getFlipDegrees(): Float = 0f

    override fun scale(state: DuskRiderRenderState, poseStack: PoseStack) {
        poseStack.scale(SCALE, SCALE, SCALE)
    }

    companion object {
        const val SCALE = 1.15f
        private val clipNames = HashMap<EnemyAttack, String>()
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dusk_rider.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dusk_rider_glow.png")
        private val GLOW_2 = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/dusk_rider_glow2.png")
    }
}
