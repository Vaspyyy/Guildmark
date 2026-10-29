package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.client.works.WorksPaint
import io.github.vaspyyy.guildmark.trade.StoreScaleBlockEntity
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
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** What the clerk's scale needs drawn for one frame. */
class StoreScaleRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH

    /** The beam's tilt (degrees, positive dipping the goods end) and the pans' sway about their hooks (side to side, to and fro). */
    var tilt = 0f
    var sway = 0f
    var swing = 0f

    /** Whether the sack is on the goods pan, and how many weights are on the other. */
    var sack = false
    var weights = 0

    /** The light about the beam and the pans (the block over the bench, which they stand up into). */
    var upper = 0
}

/**
 * The Carriers' Store's scale (art stream X3's `store_scale`, its beam and pans [StoreArt.scaleMesh]), weighing as the clerk sets a sack on
 * it: the beam dips to the goods, three weights go on the other pan one after another and with each the beam swings up past its mark and
 * settles nearer level ([StoreArt.settle]), the pans swaying on their chains ([StoreArt.panSway]); level with the third, it is read. When
 * the clerk takes the sack off the beam drops to the weights, and as they come off it settles level again. At rest it is never quite
 * still ([StoreArt.idleTilt]). Every pose follows from the block's two times ([StoreScaleBlockEntity.weighed], [StoreScaleBlockEntity.cleared])
 * and the clock, so nothing snaps from one pose to the next and every client sees the same weighing.
 */
class StoreScaleRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<StoreScaleBlockEntity, StoreScaleRenderState> {
    private val beam: ModelPart = context.bakeLayer(TradeBlocksClient.SCALE).getChild("beam")

    // A pan's things are its own children, so each way it can be loaded is a copy of its own, baked once: the goods pan bare and
    // with the sack on, the weights pan with none to three of its weights
    private val goods: Array<ModelPart> = Array(2) { k ->
        context.bakeLayer(TradeBlocksClient.SCALE).getChild("pan_goods").also { it.getChild("sack").visible = k == 1 }
    }
    private val weighted: Array<ModelPart> = Array(WEIGHTS + 1) { n ->
        context.bakeLayer(TradeBlocksClient.SCALE).getChild("pan_weights").also { pan -> for (i in 0 until WEIGHTS) pan.getChild("weight_$i").visible = i < n }
    }

    override fun createRenderState(): StoreScaleRenderState = StoreScaleRenderState()

    override fun extractRenderState(
        be: StoreScaleBlockEntity, state: StoreScaleRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        state.upper = LightCoordsUtil.getLightCoords(level, be.blockPos.above())
        val time = WorksPaint.time(level.gameTime, partialTicks)
        val a = WorksPaint.since(level.gameTime, be.weighed, partialTicks)
        val pose = weighing(a, if (be.cleared > be.weighed) (be.cleared - be.weighed).toFloat() else -1f)
        state.tilt = (pose.tilt + StoreArt.idleTilt(time + be.blockPos.hashCode() % 200)).coerceIn(-StoreArt.BEAM_MAX, StoreArt.BEAM_MAX)
        state.sway = StoreArt.panSway(pose.since, 0f)
        state.swing = StoreArt.panSway(pose.since, SWING_PHASE)
        state.sack = pose.sack
        state.weights = pose.weights
    }

    override fun submit(state: StoreScaleRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = maxOf(state.lightCoords, state.upper)
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        // The beam on its pivot, turned about z (positive dips the west, goods end)
        at(poseStack, StoreArt.SCALE_PIVOT_X, StoreArt.SCALE_PIVOT_Y, StoreArt.SCALE_PIVOT_Z) {
            poseStack.rotateDegrees(Axis.ZP, state.tilt)
            part(poseStack, collector, beam, light)
        }
        // Each pan from its hook at the beam's end, hanging plumb whatever the beam does, swaying on its chains
        pan(poseStack, collector, goods[if (state.sack) 1 else 0], StoreArt.GOODS, state, light)
        pan(poseStack, collector, weighted[state.weights.coerceIn(0, WEIGHTS)], StoreArt.WEIGHTS, state, light)
        poseStack.popPose()
    }

    private fun pan(poseStack: PoseStack, collector: SubmitNodeCollector, pan: ModelPart, side: Int, state: StoreScaleRenderState, light: Int) {
        at(poseStack, StoreArt.hookX(side, state.tilt), StoreArt.hookY(side, state.tilt), StoreArt.SCALE_PIVOT_Z) {
            poseStack.rotateDegrees(Axis.ZP, state.sway * side)
            poseStack.rotateDegrees(Axis.XP, state.swing)
            part(poseStack, collector, pan, light)
        }
    }

    private inline fun at(poseStack: PoseStack, x: Float, y: Float, z: Float, draw: () -> Unit) {
        poseStack.pushPose()
        poseStack.translate(x * WorksPaint.PX, y * WorksPaint.PX, z * WorksPaint.PX)
        draw()
        poseStack.popPose()
    }

    private fun part(poseStack: PoseStack, collector: SubmitNodeCollector, part: ModelPart, light: Int) {
        poseStack.pushPose()
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModelPart(part, poseStack, CUTOUT, light, OverlayTexture.NO_OVERLAY, null)
        poseStack.popPose()
    }

    // The scale stands two blocks high, and its pans hang out over the bench's ends
    override fun getRenderBoundingBox(be: StoreScaleBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, 1.0, 0.0).inflate(0.25)

    /** Where a weighing has the beam: its tilt, ticks since what last moved it ([since], for the pans' sway), and what is on the pans. */
    class Pose(val tilt: Float, val since: Float, val sack: Boolean, val weights: Int)

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(StoreArt.SCALE_TEXTURE))

        /** The weights the clerk sets on, one after another; and the phase of the pans' to and fro against their side to side. */
        const val WEIGHTS = 3
        private const val SWING_PHASE = 1.6f

        /** Ticks after the sack is set on that each weight goes on, and the tilt each leaves the beam at as it settles (the last, level). */
        private val WEIGHT_AT = floatArrayOf(30f, 50f, 70f)
        private val TILTS = floatArrayOf(12f, 7f, 2.5f, 0f)

        /** The tilt with the sack off and the weights still on; ticks after that until they come off; the soonest the sack comes off. */
        private const val WEIGHTS_DOWN = -9f
        private const val WEIGHTS_OFF = 20f
        private const val READ_BY = 80f

        /** Ticks after which a sack never taken off (a weighing broken off) comes off by itself. */
        private const val ABANDONED = 400f

        private val REST = Pose(0f, WorksPaint.NEVER, false, 0)

        /**
         * The beam [a] ticks after a sack was set on the goods pan ([WorksPaint.NEVER]: never), its sack taken off [off] ticks after it was set
         * on (less than 0: not yet). Each change (the sack on, a weight on, the sack off, the weights off) swings the beam from wherever the
         * last left it toward its own tilt, so a change before the last has settled carries on smoothly from where the beam was.
         */
        fun weighing(a: Float, off: Float): Pose {
            if (a < 0f || a > WorksPaint.NEVER / 2f) return REST
            val sackOff = (if (off < 0f) ABANDONED else off).coerceAtLeast(READ_BY)
            val weightsOff = sackOff + WEIGHTS_OFF
            if (a > weightsOff + StoreArt.SETTLE_TICKS) return REST
            val times = floatArrayOf(0f, WEIGHT_AT[0], WEIGHT_AT[1], WEIGHT_AT[2], sackOff, weightsOff)
            val tilts = floatArrayOf(TILTS[0], TILTS[1], TILTS[2], TILTS[3], WEIGHTS_DOWN, 0f)
            var from = 0f
            var to = 0f
            var start = 0f
            var k = -1
            for (i in times.indices) {
                if (a < times[i]) break
                from = if (k < 0) 0f else StoreArt.settle(times[i] - start, from, to)
                to = tilts[i]
                start = times[i]
                k = i
            }
            val weights = if (a >= weightsOff) 0 else WEIGHT_AT.count { a >= it }
            return Pose(StoreArt.settle(a - start, from, to), a - start, a < sackOff, weights)
        }
    }
}
