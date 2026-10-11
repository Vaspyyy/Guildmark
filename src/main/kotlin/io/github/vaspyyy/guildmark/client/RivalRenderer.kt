package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.model.RivalModel
import io.github.vaspyyy.guildmark.client.model.RivalRenderState
import io.github.vaspyyy.guildmark.guild.RivalAdventurer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.resources.Identifier

/** Rival guild adventurers, skinned per guild; captains stand a little taller. */
class RivalRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<RivalAdventurer, RivalRenderState, RivalModel>(context, RivalModel(context.bakeLayer(RivalModel.LAYER)), 0.5f) {

    override fun createRenderState(): RivalRenderState = RivalRenderState()

    override fun extractRenderState(entity: RivalAdventurer, state: RivalRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.guild = entity.guildKey
        state.captain = entity.captain
    }

    override fun getTextureLocation(state: RivalRenderState): Identifier =
        Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/rival/${state.guild.ifEmpty { "iron_hounds" }}.png")

    override fun scale(state: RivalRenderState, poseStack: PoseStack) {
        if (state.captain) poseStack.scale(1.12f, 1.12f, 1.12f)
    }
}
