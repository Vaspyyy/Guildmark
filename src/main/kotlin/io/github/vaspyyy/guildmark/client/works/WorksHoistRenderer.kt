package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.works.build.WorksHoistBlockEntity
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/** What a hoist needs drawn for one frame. */
class WorksHoistRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var drop = 4

    /** Ticks into its lift; [WorksPaint.NEVER] while nothing's being lifted. */
    var elapsed = WorksPaint.NEVER

    /** What's on the rope (empty: its own baulk in slings), and the light down by the ground and about the hook. */
    val item = ItemStackRenderState()
    var groundLight = 0
    var hookLight = 0
}

/**
 * The granary's hoist at work (living-world/works-art.md, "The hoist"), on WorksMesh.hoist's parts: the sheave turning
 * as the rope runs over it, the two falls from it (the load's, its lengths seen running up, and the hauled one, running
 * down to the hauler's hands while HOIST plays, or to its coil on the ground), the hook, and what's lifted hung under
 * it: hauled up heave by heave as the clip hauls (WorksArt.hookAt over B's lift), held at the top while it's taken in,
 * and the empty hook let back down.
 */
class WorksHoistRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WorksHoistBlockEntity, WorksHoistRenderState> {
    private val root: ModelPart = context.bakeLayer(LAYER)
    private val sheave = root.getChild("sheave")
    private val wrap = root.getChild("wrap")
    private val rope = root.getChild("rope")
    private val hook = root.getChild("hook")
    private val load = root.getChild("load")
    private val coil = root.getChild("coil")
    private val items: ItemModelResolver = context.itemModelResolver()

    /** The lifted things' stacks by item id, made once each. */
    private val stacks = HashMap<String, ItemStack>()

    override fun createRenderState(): WorksHoistRenderState = WorksHoistRenderState()

    override fun extractRenderState(
        be: WorksHoistBlockEntity, state: WorksHoistRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val facing = WorksPaint.facing(be.blockState)
        state.facing = facing
        state.drop = be.drop.coerceIn(1, 24)
        state.elapsed = if (be.ticks > 0) WorksPaint.since(level.gameTime, be.from, partialTicks) else WorksPaint.NEVER
        // A lift run backwards (the hook drawn up empty, the load let down): its clock turned round, and over once the load is down
        if (be.down && state.elapsed < WorksPaint.NEVER / 2f) {
            val travel = 16f * be.drop.coerceIn(1, 24) + WorksArt.HOOK_TOP - WorksArt.HOOK_REST
            val whole = WorksArt.ticksFor(travel) + WorksArt.HOIST_LAND + travel / WorksArt.HOIST_LOWER
            state.elapsed = if (state.elapsed > whole) WorksPaint.NEVER else (whole - state.elapsed).coerceAtLeast(0f)
        }
        val stack = if (be.load.isEmpty()) ItemStack.EMPTY else stacks.getOrPut(be.load) { stackOf(be.load) }
        if (stack.isEmpty) state.item.clear()
        else items.updateForTopItem(state.item, stack, ItemDisplayContext.FIXED, level, null, be.blockPos.hashCode())
        val foot = be.blockPos.relative(facing).below(state.drop)
        state.groundLight = LightCoordsUtil.getLightCoords(level, foot)
        val eye = eye(state)
        val up = (eye / 16f).toInt().coerceIn(-state.drop, 0)
        state.hookLight = if (up <= -state.drop) state.groundLight else LightCoordsUtil.getLightCoords(level, BlockPos(foot.x, be.blockPos.y + up, foot.z))
    }

    private fun stackOf(id: String): ItemStack {
        val key = Identifier.tryParse(id) ?: return ItemStack.EMPTY
        return BuiltInRegistries.ITEM.getOptional(key).map { ItemStack(it) }.orElse(ItemStack.EMPTY)
    }

    /** px of rope hauled so far: the hook's height over its rest. */
    private fun run(s: WorksHoistRenderState): Float = if (s.elapsed in 0f..WorksPaint.NEVER / 2f) WorksArt.hookAt(s.elapsed, travel(s)) else 0f

    private fun travel(s: WorksHoistRenderState): Float = 16f * s.drop + WorksArt.HOOK_TOP - WorksArt.HOOK_REST

    /** The hook's eye's height (px over the hoist's floor). */
    private fun eye(s: WorksHoistRenderState): Float = -16f * s.drop + WorksArt.HOOK_REST + run(s)

    override fun submit(state: WorksHoistRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val travel = travel(state)
        val lifting = state.elapsed in 0f..WorksPaint.NEVER / 2f
        val run = run(state)
        val hauling = lifting && state.elapsed < WorksArt.ticksFor(travel)
        val loaded = lifting && WorksArt.loaded(state.elapsed, travel)
        val ground = -16f * state.drop
        val eye = eye(state)
        val light = state.lightCoords
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        // The sheave turning with the rope, the rope over it still
        at(poseStack, WorksArt.SHEAVE_X, WorksArt.SHEAVE_Y, WorksArt.SHEAVE_Z) {
            poseStack.rotateDegrees(Axis.ZP, Math.toDegrees((-run / WorksArt.SHEAVE_R).toDouble()).toFloat())
            part(poseStack, collector, sheave, light)
        }
        at(poseStack, WorksArt.SHEAVE_X, WorksArt.SHEAVE_Y, WorksArt.SHEAVE_Z) { part(poseStack, collector, wrap, light) }
        // The load's fall, its lengths running up as it's hauled; the hauled fall's running down
        val cut = run % ROPE
        fall(poseStack, collector, WorksArt.LOAD_X, eye, ROPE - cut, ground, state)
        val end = if (hauling) ground + WorksArt.HAUL_TOP else ground + COIL_TOP
        fall(poseStack, collector, WorksArt.HAUL_X, end, if (cut < 0.01f) ROPE else cut, ground, state)
        if (!hauling) at(poseStack, WorksArt.COIL_X, ground, WorksArt.COIL_Z) { part(poseStack, collector, coil, state.groundLight) }
        // The hook at the end of the load's fall, and what hangs from it
        at(poseStack, WorksArt.LOAD_X, eye, WorksArt.SHEAVE_Z) { part(poseStack, collector, hook, state.hookLight) }
        if (loaded) {
            if (state.item.isEmpty) {
                at(poseStack, WorksArt.LOAD_X, eye - WorksArt.HOOK_LOAD, WorksArt.SHEAVE_Z) { part(poseStack, collector, load, state.hookLight) }
            } else {
                poseStack.pushPose()
                poseStack.translate(WorksArt.LOAD_X * WorksPaint.PX, (eye - WorksArt.HOOK_LOAD - ITEM_DOWN) * WorksPaint.PX, WorksArt.SHEAVE_Z * WorksPaint.PX)
                poseStack.rotateDegrees(Axis.YP, 8f * kotlin.math.sin(state.elapsed * 0.11f))
                poseStack.scale(ITEM_SIZE, ITEM_SIZE, ITEM_SIZE)
                state.item.submit(poseStack, collector, state.hookLight, OverlayTexture.NO_OVERLAY, 0)
                poseStack.popPose()
            }
        }
        poseStack.popPose()
    }

    /** A fall of rope hanging at x [x] (px) from the sheave down to [bottom], in a block's lengths, the top one [first] long. */
    private fun fall(poseStack: PoseStack, collector: SubmitNodeCollector, x: Float, bottom: Float, first: Float, ground: Float, s: WorksHoistRenderState) {
        var y = WorksArt.SHEAVE_Y
        var len = first.coerceIn(0.05f, ROPE)
        var n = 0
        while (y > bottom + 0.01f && n++ < MOST) {
            val l = min(len, y - bottom)
            val light = if (y - l < ground + LOW) s.groundLight else s.lightCoords
            poseStack.pushPose()
            poseStack.translate(x * WorksPaint.PX, y * WorksPaint.PX, WorksArt.SHEAVE_Z * WorksPaint.PX)
            poseStack.scale(1f, l / ROPE, 1f)
            poseStack.scale(-1f, -1f, 1f)
            collector.submitModelPart(rope, poseStack, CUTOUT, light, OverlayTexture.NO_OVERLAY, null)
            poseStack.popPose()
            y -= l
            len = ROPE
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

    // Down to the ground under its falls, and out to the jib's end
    override fun getRenderBoundingBox(be: WorksHoistBlockEntity): AABB {
        val f = WorksPaint.facing(be.blockState)
        return AABB(be.blockPos).expandTowards(f.stepX * 1.1, 0.0, f.stepZ * 1.1).expandTowards(0.0, -be.drop.coerceIn(1, 24) - 0.1, 0.0)
            .expandTowards(0.0, 0.7, 0.0).inflate(0.15)
    }

    // Its rope hangs where its block isn't
    override fun shouldRenderOffScreen(): Boolean = true

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "works_hoist"), "main")
        private val CUTOUT: RenderType = RenderTypes.entityCutout(WorksPaint.texture(WorksArt.HOIST_TEXTURE))

        /** A length of rope (px); the most lengths a fall has; how near the ground the lower lengths take its light. */
        private const val ROPE = 16f
        private const val MOST = 40
        private const val LOW = 24f

        /** The coil's top, where the slack fall ends on it (px over the ground). */
        private const val COIL_TOP = 1.6f

        /** A lifted thing: how far under the slings' ring it hangs (px), and how big it's drawn (blocks). */
        private const val ITEM_DOWN = 3.5f
        private const val ITEM_SIZE = 0.55f
    }
}
