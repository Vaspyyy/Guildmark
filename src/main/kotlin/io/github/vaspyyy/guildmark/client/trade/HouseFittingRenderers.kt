package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.client.works.WorksPaint
import io.github.vaspyyy.guildmark.trade.CountingFrameBlockEntity
import io.github.vaspyyy.guildmark.trade.HouseBellBlockEntity
import io.github.vaspyyy.guildmark.trade.StrongboxBlockEntity
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

/** Draws [part] where [poseStack] stands, as art stream X3's meshes are drawn: turned over (x and y) into the model's own space. */
internal fun drawPart(poseStack: PoseStack, collector: SubmitNodeCollector, part: ModelPart, type: RenderType, light: Int) {
    poseStack.pushPose()
    poseStack.scale(-1f, -1f, 1f)
    collector.submitModelPart(part, poseStack, type, light, OverlayTexture.NO_OVERLAY, null)
    poseStack.popPose()
}

/** [draw] with [poseStack] moved to ([x], [y], [z]) in model pixels. */
internal inline fun atPx(poseStack: PoseStack, x: Float, y: Float, z: Float, draw: () -> Unit) {
    poseStack.pushPose()
    poseStack.translate(x * WorksPaint.PX, y * WorksPaint.PX, z * WorksPaint.PX)
    draw()
    poseStack.popPose()
}

// ---- The strongbox ----

/** What the strongbox needs drawn for one frame: its lid's turn and its hasp's against it. */
class StrongboxRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var lid = 0f
    var hasp = 0f
    var upper = 0
}

/**
 * The Counting House's strongbox (art stream X3's `strongbox`, its lid and hasp [StoreArt.strongboxMesh]): the lid lifts on its hinge in
 * [StoreArt.LID_OPEN_TICKS] (quick off the mark, a hair past its stop and back), stands open on the heaped coins, and falls in
 * [StoreArt.LID_SHUT_TICKS], striking the rim and springing a little; the hasp hangs plumb from its front and swings on after each move.
 * Drawn from the box's two times ([StrongboxBlockEntity.opened], [StrongboxBlockEntity.shuts]): a lid dropped before it was fully up falls
 * from wherever it had got to.
 */
class StrongboxRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<StrongboxBlockEntity, StrongboxRenderState> {
    private val lid: ModelPart = context.bakeLayer(TradeBlocksClient.STRONGBOX).getChild("lid")
    private val hasp: ModelPart = context.bakeLayer(TradeBlocksClient.STRONGBOX).getChild("hasp")

    override fun createRenderState(): StrongboxRenderState = StrongboxRenderState()

    override fun extractRenderState(
        be: StrongboxBlockEntity, state: StrongboxRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        state.upper = LightCoordsUtil.getLightCoords(level, be.blockPos.above())
        val now = level.gameTime
        val opened = be.opened
        val shuts = be.shuts
        val falling = if (opened != 0L && shuts > opened) WorksPaint.since(now, shuts, partialTicks) else -1f
        when {
            opened == 0L -> {
                state.lid = 0f
                state.hasp = 0f
            }
            falling >= 0f -> {
                // Let fall from wherever it had got to
                val from = StoreArt.lidAngle((shuts - opened).toFloat(), 0f, StoreArt.LID_OPEN)
                state.lid = StoreArt.lidAngle(falling, from, 0f)
                state.hasp = StoreArt.haspAngle(falling, from, 0f)
            }
            else -> {
                val a = WorksPaint.since(now, opened, partialTicks)
                state.lid = StoreArt.lidAngle(a, 0f, StoreArt.LID_OPEN)
                state.hasp = StoreArt.haspAngle(a, 0f, StoreArt.LID_OPEN)
            }
        }
    }

    override fun submit(state: StrongboxRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = maxOf(state.lightCoords, state.upper)
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        // The lid on its hinge pin, and the hasp hung from its front in the lid's own frame
        atPx(poseStack, StoreArt.LID_X, StoreArt.LID_Y, StoreArt.LID_Z) {
            poseStack.rotateDegrees(Axis.XP, state.lid)
            drawPart(poseStack, collector, lid, CUTOUT, light)
            atPx(poseStack, StoreArt.HASP_X, StoreArt.HASP_Y, StoreArt.HASP_Z) {
                poseStack.rotateDegrees(Axis.XP, state.hasp)
                drawPart(poseStack, collector, hasp, CUTOUT, light)
            }
        }
        poseStack.popPose()
    }

    // The lid stands up over the box as it opens
    override fun getRenderBoundingBox(be: StrongboxBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, 0.6, 0.0).inflate(0.1)

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(StoreArt.STRONGBOX_TEXTURE))
    }
}

// ---- The counting frame ----

/** What the counting frame needs drawn for one frame: each bead's x along its rod. */
class CountingFrameRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    val x = FloatArray(CountingFrameBlockEntity.ROWS * CountingFrameBlockEntity.BEADS)
}

/**
 * The Counting House's counting frame (art stream X3's `counting_frame`, its beads [StoreArt.beadsMesh]): six rods of nine beads, each
 * row showing a figure of the sum by how many of its beads are pushed to the counted side ([StoreArt.beadRestX]; row 0 the lowest, the
 * units). When the sum changes the rows that change go one after another from the lowest ([StoreArt.BEAD_ROW_GAP] apart), and in each
 * the beads run along one after another ([StoreArt.beadX]: away fast, a hair of rebound against the stack). Drawn from the frame's rows
 * before and after and when they were set moving, so every client sees the same push and none ever shows a bead mid-rod at rest.
 */
class CountingFrameRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<CountingFrameBlockEntity, CountingFrameRenderState> {
    private val beads: Array<Array<ModelPart>> = context.bakeLayer(TradeBlocksClient.BEADS).let { root ->
        Array(StoreArt.BEAD_ROWS) { r -> Array(StoreArt.BEAD_COLS) { i -> root.getChild("bead_${r}_$i") } }
    }

    override fun createRenderState(): CountingFrameRenderState = CountingFrameRenderState()

    override fun extractRenderState(
        be: CountingFrameBlockEntity, state: CountingFrameRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        val rows = be.rows
        val was = be.was
        val age = WorksPaint.since(level.gameTime, be.moved, partialTicks)
        val starts = if (be.moved == 0L) emptyMap() else CountingFrameBlockEntity.starts(was, rows).toMap()
        val cols = StoreArt.BEAD_COLS
        for (r in 0 until StoreArt.BEAD_ROWS) {
            val to = rows.getOrElse(r) { 0 }
            val from = was.getOrElse(r) { to }
            val start = starts[r]
            for (i in 0 until cols) {
                state.x[r * cols + i] = when {
                    start == null || from == to -> StoreArt.beadRestX(i, i < to)
                    // Counting up: the beads from..to-1 go over, the lowest-numbered first; counting down, back, the highest first
                    to > from && i in from until to -> StoreArt.beadX(i, true, age - start, i - from)
                    to < from && i in to until from -> StoreArt.beadX(i, false, age - start, from - 1 - i)
                    else -> StoreArt.beadRestX(i, i < to)
                }
            }
        }
    }

    override fun submit(state: CountingFrameRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        val cols = StoreArt.BEAD_COLS
        for (r in 0 until StoreArt.BEAD_ROWS) {
            val y = StoreArt.beadY(r)
            for (i in 0 until cols) atPx(poseStack, state.x[r * cols + i], y, StoreArt.BEAD_Z) { drawPart(poseStack, collector, beads[r][i], CUTOUT, state.lightCoords) }
        }
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(be: CountingFrameBlockEntity): AABB = AABB(be.blockPos).inflate(0.05)

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(StoreArt.BEADS_TEXTURE))
    }
}

// ---- The bell ----

/** What the bell needs drawn for one frame: the bell's swing, its clapper's, and where the rope hangs from and how it swings. */
class HouseBellRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var swing = 0f
    var clapper = 0f
    var ropeY = 0f
    var ropeZ = 0f
    var ropeSway = 0f
    var lower = 0
}

/**
 * The Counting House's yard bell (art stream X3's `house_bell`, its bell, clapper and rope [HouseArt.bellMesh]): pulled, it swings the
 * other way first, builds over ten ticks and dies away ([HouseArt.bellSwing]); the clapper swings a little later and further, stopped by
 * the bell's wall on each side ([HouseArt.clapperRel]); the rope rides up and back on the wheel's rim as the bell turns and swings on after
 * the pull ([HouseArt.ropeSway]). At rest it is never quite still in the wind ([HouseArt.idleSwing]). Drawn from when it was rung
 * ([HouseBellBlockEntity.rung]); the ring's sound is the server's, played once at the pull.
 */
class HouseBellRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<HouseBellBlockEntity, HouseBellRenderState> {
    private val bell: ModelPart = context.bakeLayer(TradeBlocksClient.HOUSE_BELL).getChild("bell")
    private val clapper: ModelPart = context.bakeLayer(TradeBlocksClient.HOUSE_BELL).getChild("clapper")
    private val rope: ModelPart = context.bakeLayer(TradeBlocksClient.HOUSE_BELL).getChild("rope")

    override fun createRenderState(): HouseBellRenderState = HouseBellRenderState()

    override fun extractRenderState(
        be: HouseBellBlockEntity, state: HouseBellRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        state.lower = LightCoordsUtil.getLightCoords(level, be.blockPos.below())
        val age = WorksPaint.since(level.gameTime, be.rung, partialTicks)
        val time = WorksPaint.time(level.gameTime, partialTicks) + Math.floorMod(be.blockPos.asLong(), 997L).toFloat()
        // The wind's stir eases out under the pull and back in as the ring dies
        val calm = if (age >= 0f && age < HouseArt.RING_TICKS) maxOf(1f - WorksPaint.smooth(age / STIR_OUT), 1f - HouseArt.ringFade(age)) else 1f
        state.swing = HouseArt.bellSwing(age) + HouseArt.idleSwing(time) * calm
        state.clapper = state.swing + HouseArt.clapperRel(age)
        state.ropeY = HouseArt.ropeY(state.swing)
        state.ropeZ = HouseArt.ropeZ(state.swing)
        state.ropeSway = HouseArt.ropeSway(age)
    }

    override fun submit(state: HouseBellRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = maxOf(state.lightCoords, state.lower)
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        atPx(poseStack, HouseArt.BELL_X, HouseArt.BELL_Y, HouseArt.BELL_Z) {
            poseStack.pushPose()
            poseStack.rotateDegrees(Axis.XP, state.swing)
            drawPart(poseStack, collector, bell, CUTOUT, light)
            poseStack.popPose()
            poseStack.rotateDegrees(Axis.XP, state.clapper)
            drawPart(poseStack, collector, clapper, CUTOUT, light)
        }
        // The rope from the wheel's front rim, carried up and back as the bell turns
        atPx(poseStack, HouseArt.BELL_X + HouseArt.ROPE_X, HouseArt.BELL_Y + state.ropeY, HouseArt.BELL_Z - HouseArt.WHEEL_RAD + state.ropeZ) {
            poseStack.rotateDegrees(Axis.XP, state.ropeSway)
            drawPart(poseStack, collector, rope, CUTOUT, light)
        }
        poseStack.popPose()
    }

    // The bell swings out of its block and its rope hangs toward the block below
    override fun getRenderBoundingBox(be: HouseBellBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, -1.0, 0.0).inflate(0.3)

    companion object {
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(HouseArt.BELL_TEXTURE))

        /** Ticks over which the wind's stir gives way to the pull. */
        private const val STIR_OUT = 8f
    }
}
