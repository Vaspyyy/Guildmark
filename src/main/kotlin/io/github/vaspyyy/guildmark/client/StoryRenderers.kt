package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.model.HollowKingModel
import io.github.vaspyyy.guildmark.client.model.StoryNpcModel
import io.github.vaspyyy.guildmark.client.model.StoryNpcRenderState
import io.github.vaspyyy.guildmark.story.HollowKing
import io.github.vaspyyy.guildmark.story.StoryNpc
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.EyesLayer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier

/** The Hollow King, drawn 1.6 times humanoid size, with his eyes, heart and crown glowing in the dark. */
class HollowKingRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<HollowKing, HumanoidRenderState, HollowKingModel>(context, HollowKingModel(context.bakeLayer(HollowKingModel.LAYER)), 1.2f) {

    init {
        addLayer(object : EyesLayer<HumanoidRenderState, HollowKingModel>(this) {
            override fun renderType(): RenderType = RenderTypes.eyes(GLOW)
        })
    }

    override fun createRenderState(): HumanoidRenderState = HumanoidRenderState()
    override fun getTextureLocation(state: HumanoidRenderState): Identifier = TEXTURE

    override fun scale(state: HumanoidRenderState, poseStack: PoseStack) {
        poseStack.scale(SCALE, SCALE, SCALE)
    }

    companion object {
        const val SCALE = 1.6f
        private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/hollow_king.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/hollow_king_glow.png")
    }
}

/** The named characters, each with their own skin; Maren is a ghost whose eyes glow. */
class StoryNpcRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<StoryNpc, StoryNpcRenderState, StoryNpcModel>(context, StoryNpcModel(context.bakeLayer(StoryNpcModel.LAYER)), 0.5f) {

    init {
        addLayer(GlowLayer(this))
    }

    override fun createRenderState(): StoryNpcRenderState = StoryNpcRenderState()

    override fun extractRenderState(entity: StoryNpc, state: StoryNpcRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.character = entity.character
    }

    override fun getTextureLocation(state: StoryNpcRenderState): Identifier = texture(state.character)

    /** Glowing eyes for characters that have a glow texture (just Maren for now). */
    private class GlowLayer(parent: RenderLayerParent<StoryNpcRenderState, StoryNpcModel>) : RenderLayer<StoryNpcRenderState, StoryNpcModel>(parent) {
        override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: StoryNpcRenderState, yRot: Float, xRot: Float) {
            if (state.character !in GLOWING) return
            collector.order(1).submitModel(parentModel, state, poseStack, RenderTypes.eyes(glow(state.character)), light, OverlayTexture.NO_OVERLAY, state.outlineColor)
        }
    }

    companion object {
        private val GLOWING = setOf("maren")
        private fun texture(id: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/character/${id.ifEmpty { "wren" }}.png")
        private fun glow(id: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/character/${id}_glow.png")
    }
}
