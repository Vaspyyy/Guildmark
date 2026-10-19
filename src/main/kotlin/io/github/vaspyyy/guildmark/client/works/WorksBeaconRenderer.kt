package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.economy.works.build.BeaconFire
import io.github.vaspyyy.guildmark.economy.works.build.WorksBeaconBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksBeaconBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.exp

/** What a beacon's glow needs for one frame. */
class WorksBeaconRenderState : BlockEntityRenderState() {
    var fire = BeaconFire.OUT
    var time = 0f

    /** Ticks since it last flared up, and since it was lit. */
    var flared = WorksPaint.NEVER
    var lit = WorksPaint.NEVER
}

/**
 * The tower's beacon's glow (living-world/works-art.md, "The beacon"): while it burns, a soft light about its flames
 * facing whoever looks, flickering; bigger and higher while it flares, with a burst of it as it flares up, and swelling
 * from nothing as it's lit. Its flames are its model's (animated), its sparks and smoke its block's, its roar
 * [WorksClient]'s loop.
 */
class WorksBeaconRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<WorksBeaconBlockEntity, WorksBeaconRenderState> {
    override fun createRenderState(): WorksBeaconRenderState = WorksBeaconRenderState()

    override fun extractRenderState(
        be: WorksBeaconBlockEntity, state: WorksBeaconRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val block = be.blockState
        state.fire = if (block.hasProperty(WorksBeaconBlock.FIRE)) block.getValue(WorksBeaconBlock.FIRE) else BeaconFire.OUT
        state.time = WorksPaint.time(level.gameTime, partialTicks)
        state.flared = WorksPaint.since(level.gameTime, be.flared, partialTicks)
        state.lit = WorksPaint.since(level.gameTime, be.lit, partialTicks)
    }

    override fun submit(state: WorksBeaconRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (state.fire == BeaconFire.OUT) return
        val flare = state.fire == BeaconFire.FLARE
        val glow = if (flare) WorksArt.GLOW_FLARE else WorksArt.GLOW_LIT
        var width = glow[3] * WorksArt.glowPulse(state.time)
        // A burst as it flares up; and as it's lit, the glow swelling with the fire
        if (state.flared in 0f..BURST) width *= 1f + 0.55f * exp(-state.flared / 9f)
        if (state.lit in 0f..CATCH) width *= WorksPaint.smooth(state.lit / CATCH)
        if (width <= 0.2f) return
        val strength = if (flare) 1f else 0.78f
        val c = ARGB.color(255, (255 * strength).toInt(), (205 * strength).toInt(), (140 * strength).toInt())
        poseStack.pushPose()
        poseStack.translate(glow[0] * WorksPaint.PX, glow[1] * WorksPaint.PX, glow[2] * WorksPaint.PX)
        poseStack.rotate(camera.orientation)
        val h = width * WorksPaint.PX / 2f
        collector.order(1).submitCustomGeometry(poseStack, GLOW) { pose, buffer -> quad(buffer, pose, h, c) }
        poseStack.popPose()
    }

    /** A square [h] each way of its middle, facing the camera, both ways round. */
    private fun quad(buffer: VertexConsumer, pose: PoseStack.Pose, h: Float, c: Int) {
        val l = LightCoordsUtil.FULL_BRIGHT
        WorksPaint.vertex(buffer, pose, -h, -h, 0f, 0f, 1f, c, l, 0f, 0f, 1f)
        WorksPaint.vertex(buffer, pose, h, -h, 0f, 1f, 1f, c, l, 0f, 0f, 1f)
        WorksPaint.vertex(buffer, pose, h, h, 0f, 1f, 0f, c, l, 0f, 0f, 1f)
        WorksPaint.vertex(buffer, pose, -h, h, 0f, 0f, 0f, c, l, 0f, 0f, 1f)
        WorksPaint.vertex(buffer, pose, -h, h, 0f, 0f, 0f, c, l, 0f, 0f, -1f)
        WorksPaint.vertex(buffer, pose, h, h, 0f, 1f, 0f, c, l, 0f, 0f, -1f)
        WorksPaint.vertex(buffer, pose, h, -h, 0f, 1f, 1f, c, l, 0f, 0f, -1f)
        WorksPaint.vertex(buffer, pose, -h, -h, 0f, 0f, 1f, c, l, 0f, 0f, -1f)
    }

    // The flaring glow reaches nearly three blocks up
    override fun getRenderBoundingBox(be: WorksBeaconBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, 2.2, 0.0).inflate(1.0)

    override fun shouldRenderOffScreen(): Boolean = true

    companion object {
        private val GLOW: RenderType = RenderTypes.eyes(WorksPaint.texture(WorksArt.GLOW_TEXTURE))

        /** Ticks a flare's burst takes to die down, and the glow to come up with the fire as it's lit. */
        private const val BURST = 50f
        private const val CATCH = 24f
    }
}
