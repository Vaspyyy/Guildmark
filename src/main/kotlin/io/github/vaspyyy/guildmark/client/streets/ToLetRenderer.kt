package io.github.vaspyyy.guildmark.client.streets

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksArt
import io.github.vaspyyy.guildmark.townsfolk.streets.ToLetBlock
import io.github.vaspyyy.guildmark.townsfolk.streets.ToLetBlockEntity
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** What a "To let" board needs for one frame. */
class ToLetRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    /** Degrees it's swung, its foot toward the street. */
    var swing = 0f
}

/**
 * A "To let" board (M6 Part D2, townsfolk/streets/ToLetBlock), from Part F's art (client/works): its block's model is
 * the post and bracket (works/to_let_post), and this hangs WorksMesh.toLet's board from the bracket's hooks, swinging
 * on them by WorksArt.toLetSwing (each board in its own time, twice as far in rain). Its creak is [StreetsClient]'s.
 */
class ToLetRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<ToLetBlockEntity, ToLetRenderState> {
    private val board: ModelPart = context.bakeLayer(LAYER).getChild("board")

    override fun createRenderState(): ToLetRenderState = ToLetRenderState()

    override fun extractRenderState(
        be: ToLetBlockEntity, state: ToLetRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = be.blockState.getValue(ToLetBlock.FACING)
        state.swing = swing(level, be.blockPos, be.wet, partialTicks)
    }

    override fun submit(state: ToLetRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        // The post's model space, turned about the block's middle as its blockstate turns it, to the line through the hooks
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, 180f - state.facing.toYRot())
        poseStack.translate(-0.5f, 0f, -0.5f)
        poseStack.translate(WorksArt.TO_LET_X / 16f, WorksArt.TO_LET_Y / 16f, WorksArt.TO_LET_Z / 16f)
        poseStack.scale(-1f, -1f, 1f)
        // Swung on them (the board's xRot, given to the pose so each board keeps its own)
        poseStack.rotate(Axis.XP, -state.swing * Mth.DEG_TO_RAD)
        collector.submitModelPart(board, poseStack, TYPE, state.lightCoords, OverlayTexture.NO_OVERLAY, null)
        poseStack.popPose()
    }

    // The board hangs above the block's top (its hooks at 20 px)
    override fun getRenderBoundingBox(be: ToLetBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, 0.3, 0.0)

    companion object {
        /** WorksMesh.toLet's layer (registered by [StreetsClient]). */
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "to_let_board"), "main")

        private val TYPE: RenderType = RenderTypes.entityCutout(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, WorksArt.TO_LET_TEXTURE))

        /** Ticks the swing's clock wraps at: 10000 pi, near enough whole turns of both its swings that the wrap doesn't show. */
        private const val WRAP = 31416L

        /** Degrees the board at [pos] has swung (its foot toward the street) [partialTicks] into this tick, [wet] (0 to 1) as the rain has it there. */
        fun swing(level: Level, pos: BlockPos, wet: Float, partialTicks: Float): Float {
            val t = (level.gameTime % WRAP).toFloat() + partialTicks
            return WorksArt.toLetSwing(t, phase(pos), false) * (1f + wet.coerceIn(0f, 1f))
        }

        /** The board at [pos]'s own time in the swing, so neighbours don't swing together. */
        private fun phase(pos: BlockPos): Float = (Mth.getSeed(pos) and 0xFFFFL).toFloat() / 65536f * Mth.TWO_PI
    }
}
