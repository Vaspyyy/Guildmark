package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.EnemyAnimation
import io.github.vaspyyy.guildmark.client.model.EnemyRenderState
import io.github.vaspyyy.guildmark.client.model.FallenWardenModel
import io.github.vaspyyy.guildmark.client.model.HollowSoldierModel
import io.github.vaspyyy.guildmark.enemy.FallenWarden
import io.github.vaspyyy.guildmark.enemy.HollowSoldier
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.HumanoidArm

/** Glowing eyes (and more) from a texture that can change with the creature's state. */
class EnemyGlowLayer<M : HumanoidModel<EnemyRenderState>>(
    parent: RenderLayerParent<EnemyRenderState, M>,
    private val texture: (EnemyRenderState) -> Identifier,
) : RenderLayer<EnemyRenderState, M>(parent) {
    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: EnemyRenderState, yRot: Float, xRot: Float) {
        collector.order(1).submitModel(parentModel, state, poseStack, RenderTypes.eyes(texture(state)), light, OverlayTexture.NO_OVERLAY, state.outlineColor)
    }
}

/** The Hollow Soldier, its eyes glowing under the helm, its shield up while it guards. */
class HollowSoldierRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<HollowSoldier, EnemyRenderState, HollowSoldierModel>(context, HollowSoldierModel(context.bakeLayer(HollowSoldierModel.LAYER)), 0.5f) {

    init {
        addLayer(EnemyGlowLayer(this) { GLOW })
    }

    override fun createRenderState(): EnemyRenderState = EnemyRenderState()
    override fun getTextureLocation(state: EnemyRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: HollowSoldier, state: EnemyRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.guarding = entity.guarding
        if (entity.guarding) {
            if (entity.mainArm == HumanoidArm.RIGHT) state.leftArmPose = HumanoidModel.ArmPose.BLOCK
            else state.rightArmPose = HumanoidModel.ArmPose.BLOCK
        }
        EnemyAnimation.extract(entity, state, partialTicks)
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/hollow_soldier.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/hollow_soldier_glow.png")
    }
}

/**
 * Halvard, half again a man's size. His eyes glow from the start; once the crown's fire takes him, the
 * cracks in his plate, his sigil and his blade's edges burn too. He kneels at his post until woken, and
 * kneels again to die rather than falling over.
 */
class FallenWardenRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<FallenWarden, EnemyRenderState, FallenWardenModel>(context, FallenWardenModel(context.bakeLayer(FallenWardenModel.LAYER)), 1.1f) {

    init {
        addLayer(EnemyGlowLayer(this) { if (it.phaseTwo) GLOW_2 else GLOW })
    }

    override fun createRenderState(): EnemyRenderState = EnemyRenderState()
    override fun getTextureLocation(state: EnemyRenderState): Identifier = TEXTURE

    override fun extractRenderState(entity: FallenWarden, state: EnemyRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.phaseTwo = entity.phaseTwo
        state.hasRedOverlay = entity.hurtTime > 0 && entity.deathTime <= 0
        EnemyAnimation.extract(entity, state, partialTicks)
        when {
            entity.deathTime > 0 -> EnemyAnimation.hold(state, "fallen_warden/death", entity.deathTime + partialTicks)
            entity.dormant -> EnemyAnimation.hold(state, "fallen_warden/dormant", 0f)
        }
    }

    override fun getFlipDegrees(): Float = 0f

    override fun scale(state: EnemyRenderState, poseStack: PoseStack) {
        poseStack.scale(SCALE, SCALE, SCALE)
    }

    companion object {
        const val SCALE = 1.5f
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/fallen_warden.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/fallen_warden_glow.png")
        private val GLOW_2 = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/fallen_warden_glow2.png")
    }
}
