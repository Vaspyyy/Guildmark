package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.works.build.WorksYardBlockEntity
import io.github.vaspyyy.guildmark.economy.works.build.YardStuff
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
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/** What a yard heap needs drawn for one frame. */
class WorksYardRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    internal var heap: WorksArt.Heap? = null

    /** How many of its pieces show with what's there now, and showed before it last changed, and ticks since then. */
    var now = 0
    var was = 0
    var since = WorksPaint.NEVER
}

/**
 * A yard's heap of one material (living-world/works-art.md, "The yard"): as many of its pieces as the share of its bill
 * that's in the yard, from WorksArt.YARD's heaps on WorksMesh.yard's parts (timber stacked with stickers, stone
 * piled, glass crated in straw, iron bars, wool bales). As the yard fills the new pieces are set on one after another,
 * each dropped a hand's height with a little bounce; as it's used they go from the top, lifted away and gone.
 */
class WorksYardRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WorksYardBlockEntity, WorksYardRenderState> {
    private val parts: Map<String, ModelPart> = context.bakeLayer(LAYER).let { root -> PARTS.associateWith { root.getChild(it) } }

    override fun createRenderState(): WorksYardRenderState = WorksYardRenderState()

    override fun extractRenderState(
        be: WorksYardBlockEntity, state: WorksYardRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        state.facing = WorksPaint.facing(be.blockState)
        val heap = WorksArt.YARD[be.stuff.stuff.key]
        state.heap = heap
        if (heap == null) return
        state.now = WorksArt.shown(be.here, be.bill, heap.full)
        state.was = WorksArt.shown(be.was, be.bill, heap.full)
        state.since = WorksPaint.since(level.gameTime, be.since, partialTicks)
    }

    override fun submit(state: WorksYardRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val heap = state.heap ?: return
        if (state.now <= 0 && state.was <= 0) return
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        val steady = min(state.now, state.was)
        for (piece in heap.pieces) {
            val n = piece.n
            var lift = 0f
            var scale = 1f
            when {
                n <= steady -> {}
                n <= state.now -> {
                    // Set on in turn, the first of the new ones as the yard filled
                    val age = state.since - (n - state.was - 1) * WorksArt.DROP_TICKS
                    if (age < 0f) continue
                    lift = WorksArt.drop(age)
                }
                n <= state.was -> {
                    // Taken from the top down
                    val age = state.since - (state.was - n) * TAKE_GAP
                    if (age >= WorksArt.TAKE_TICKS) continue
                    if (age > 0f) {
                        lift = WorksArt.takeLift(age)
                        scale = WorksArt.takeScale(age)
                    }
                }
                else -> continue
            }
            val part = parts[piece.part] ?: continue
            poseStack.pushPose()
            poseStack.translate(piece.x * WorksPaint.PX, (piece.y + lift) * WorksPaint.PX, piece.z * WorksPaint.PX)
            poseStack.rotateDegrees(Axis.YP, piece.yaw)
            if (scale < 1f) poseStack.scale(scale, scale, scale)
            poseStack.scale(-1f, -1f, 1f)
            collector.submitModelPart(part, poseStack, CUTOUT, state.lightCoords, OverlayTexture.NO_OVERLAY, null)
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    // The wool's bales stand higher than a block
    override fun getRenderBoundingBox(be: WorksYardBlockEntity): AABB =
        if (be.stuff == YardStuff.WOOL) AABB(be.blockPos).expandTowards(0.0, 1.0, 0.0) else AABB(be.blockPos).expandTowards(0.0, 0.2, 0.0)

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "works_yard"), "main")
        private val PARTS = listOf("baulk", "sticker", "ashlar", "crate", "bar", "bale")
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(WorksArt.YARD_TEXTURE))

        /** Ticks between one piece taken and the next under it. */
        private const val TAKE_GAP = 3f
    }
}
