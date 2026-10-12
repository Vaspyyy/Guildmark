package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.item.SpectralSteed
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.UndeadHorseRenderer
import net.minecraft.client.renderer.entity.state.EquineRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.resources.model.EquipmentClientInfo
import net.minecraft.util.ARGB

/** The Dusk Bridle's steed: a skeleton horse drawn as a pale, see-through ghost. */
class SpectralSteedRenderer(context: EntityRendererProvider.Context) : UndeadHorseRenderer(
    context, EquipmentClientInfo.LayerType.SKELETON_HORSE_SADDLE, ModelLayers.SKELETON_HORSE_SADDLE,
    UndeadHorseRenderer.Type.SKELETON, UndeadHorseRenderer.Type.SKELETON_BABY,
) {
    override fun getRenderType(state: EquineRenderState, isBodyVisible: Boolean, forceTransparent: Boolean, appearGlowing: Boolean): RenderType? =
        if (isBodyVisible) RenderTypes.entityTranslucent(getTextureLocation(state))
        else super.getRenderType(state, isBodyVisible, forceTransparent, appearGlowing)

    override fun getModelTint(state: EquineRenderState): Int = TINT

    private companion object {
        /** Pale blue, a little more than half there. */
        val TINT = ARGB.color(160, 190, 225, 255)
    }
}

/** On a spectral steed, which has no saddlebags of its own, the inventory key opens your own inventory. */
object SpectralSteedControls {
    /** Before the game handles its keys this tick. */
    fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (player.vehicle !is SpectralSteed || minecraft.gui.screen() != null) return
        while (minecraft.options.keyInventory.consumeClick()) minecraft.gui.setScreen(InventoryScreen(player))
    }
}
