package io.github.vaspyyy.guildmark.client

import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.UndeadHorseRenderer
import net.minecraft.client.renderer.entity.state.EquineRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.resources.model.EquipmentClientInfo
import net.minecraft.util.ARGB
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.item.ItemStack

/** The Dusk Bridle's steed: a skeleton horse drawn as a pale, see-through ghost, its saddle as much a ghost as it is. */
class SpectralSteedRenderer(context: EntityRendererProvider.Context) : UndeadHorseRenderer(
    context, EquipmentClientInfo.LayerType.SKELETON_HORSE_SADDLE, ModelLayers.SKELETON_HORSE_SADDLE,
    UndeadHorseRenderer.Type.SKELETON, UndeadHorseRenderer.Type.SKELETON_BABY,
) {
    override fun extractRenderState(entity: AbstractHorse, state: EquineRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.saddle = ItemStack.EMPTY
    }

    override fun getRenderType(state: EquineRenderState, isBodyVisible: Boolean, forceTransparent: Boolean, appearGlowing: Boolean): RenderType? =
        if (isBodyVisible) RenderTypes.entityTranslucent(getTextureLocation(state))
        else super.getRenderType(state, isBodyVisible, forceTransparent, appearGlowing)

    override fun getModelTint(state: EquineRenderState): Int = TINT

    private companion object {
        /** Pale blue, a little more than half there. */
        val TINT = ARGB.color(160, 190, 225, 255)
    }
}
