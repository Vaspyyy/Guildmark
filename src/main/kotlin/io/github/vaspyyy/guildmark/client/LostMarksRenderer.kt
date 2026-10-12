package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.death.LostMarks
import io.github.vaspyyy.guildmark.registry.ModItems
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
import net.minecraft.world.item.ItemStack
import kotlin.math.sin

class LostMarksRenderState : EntityRenderState() {
    val coin = ItemStackRenderState()
    var coins = 1
    var phase = 0f
}

/** Lost Guild Marks: a few coins turning slowly in the air where their owner fell, glowing in any light. */
class LostMarksRenderer(context: EntityRendererProvider.Context) : EntityRenderer<LostMarks, LostMarksRenderState>(context) {
    private val itemModelResolver: ItemModelResolver = context.itemModelResolver
    private val coin: ItemStack by lazy { ItemStack(ModItems.GUILD_MARK.get()) }

    init {
        shadowRadius = 0.2f
        shadowStrength = 0.4f
    }

    override fun createRenderState(): LostMarksRenderState = LostMarksRenderState()

    override fun extractRenderState(entity: LostMarks, state: LostMarksRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        itemModelResolver.updateForNonLiving(state.coin, coin, ItemDisplayContext.GROUND, entity)
        val amount = entity.amount
        state.coins = when {
            amount < 10 -> 1
            amount < 50 -> 2
            amount < 200 -> 3
            else -> 4
        }
        state.phase = (entity.id % 360).toFloat()
    }

    override fun submit(state: LostMarksRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (!state.coin.isEmpty) {
            val age = state.ageInTicks + state.phase
            poseStack.pushPose()
            poseStack.translate(0f, 0.15f + sin(age / 14f) * 0.06f, 0f)
            for (i in 0 until state.coins) {
                poseStack.pushPose()
                // The coins circle each other, each turning on its own as it goes
                poseStack.rotateDegrees(Axis.YP, age * 2f + i * 360f / state.coins)
                if (state.coins > 1) poseStack.translate(0.16f, sin(age / 9f + i * 2.1f) * 0.05f, 0f)
                poseStack.rotateDegrees(Axis.YP, age * 5f)
                state.coin.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor)
                poseStack.popPose()
            }
            poseStack.popPose()
        }
        super.submit(state, poseStack, collector, camera)
    }
}
