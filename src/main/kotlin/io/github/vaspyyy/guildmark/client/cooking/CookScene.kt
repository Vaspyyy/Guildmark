package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * What the renderers of the three blocks that are not stations (the Plate, the Larder and the Menu board) take from the world in
 * extraction and draw from in submission: the level, the frame's partial tick and the game time with it, where the camera is (which
 * decides how much is drawn), the light to draw by, and the time the extraction took for `cook perf`. A new one is made for every
 * block every frame.
 */
open class SceneState : BlockEntityRenderState() {
    var level: Level? = null
    var partial = 0f

    /** The game time with the partial tick, in ticks: a clock that moves with the frames and never jumps at a tick. */
    var time = 0.0

    /** The camera, relative to the block's middle (blocks), and its distance from it, squared. */
    var camX = 0.0
    var camY = 0.0
    var camZ = 0.0
    var distSq = 0.0

    var nanos = 0L

    /** The light to draw by. */
    var light = 0

    val close: Boolean get() = distSq < StationState.CLOSE * StationState.CLOSE
    val near: Boolean get() = distSq < StationState.NEAR * StationState.NEAR

    fun fill(be: BlockEntity, partial: Float, camera: Vec3, scratch: BlockPos.MutableBlockPos) {
        val t0 = System.nanoTime()
        val world = be.level
        this.partial = partial
        level = world
        time = (world?.gameTime ?: 0L) + partial.toDouble()
        camX = camera.x - (be.blockPos.x + 0.5)
        camY = camera.y - (be.blockPos.y + 0.5)
        camZ = camera.z - (be.blockPos.z + 0.5)
        distSq = camX * camX + camY * camY + camZ * camZ
        light = lightAround(world, be.blockPos, lightCoords, scratch)
        nanos = System.nanoTime() - t0
    }

    /** Writes a layer of flat shapes, as [StationState.layer] does. */
    fun layer(collector: SubmitNodeCollector, order: Int, poseStack: PoseStack, type: RenderType, draw: (Pen) -> Unit) {
        val l = light
        collector.order(order).submitCustomGeometry(poseStack, type) { pose, buffer ->
            val t0 = System.nanoTime()
            draw(Pen(buffer, pose, l))
            StationFx.spent(System.nanoTime() - t0)
        }
    }

    companion object {
        /**
         * The light of the block's own cell, or, where that is dark because the block fills it (a cupboard holds no light of its own),
         * the brightest of the cells touching it.
         */
        fun lightAround(level: Level?, pos: BlockPos, own: Int, scratch: BlockPos.MutableBlockPos): Int {
            level ?: return own
            var best = own
            for (d in Direction.entries) {
                scratch.setWithOffset(pos, d)
                best = LightCoordsUtil.max(best, LightCoordsUtil.getLightCoords(level, scratch))
            }
            return best
        }
    }
}

/**
 * The shared body of the renderers of the Plate, the Larder and the Menu board: the extraction, the box to cull by (a larder's door
 * swings past its cell), the cost counted for `cook perf`. A subclass reads the block in [extract] and draws in [draw].
 */
abstract class SceneRenderer<BE : BlockEntity, S : SceneState>(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<BE, S> {
    protected val resolver: ItemModelResolver = context.itemModelResolver()
    protected val font: Font = context.font()
    private val scratch = BlockPos.MutableBlockPos()

    override fun getRenderBoundingBox(be: BE): AABB {
        val p = be.blockPos
        return AABB(p.x - 1.0, p.y.toDouble(), p.z - 1.0, p.x + 2.0, p.y + 2.0, p.z + 2.0)
    }

    override fun extractRenderState(be: BE, state: S, partialTicks: Float, cameraPosition: Vec3, breakProgress: ModelFeatureRenderer.CrumblingOverlay?) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        state.fill(be, partialTicks, cameraPosition, scratch)
        val t0 = System.nanoTime()
        extract(be, state)
        state.nanos += System.nanoTime() - t0
    }

    override fun submit(state: S, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val t0 = System.nanoTime()
        draw(state, poseStack, collector)
        StationFx.drew(state.nanos + System.nanoTime() - t0)
    }

    /** Takes from [be] what [draw] will draw (and keeps what it must from one frame to the next), in the frame's extraction. */
    protected abstract fun extract(be: BE, state: S)

    protected abstract fun draw(state: S, poseStack: PoseStack, collector: SubmitNodeCollector)
}

/**
 * The ticks of a block that has no [StationAnim]: what a renderer eases a tick at a time ([Ease]) is stepped from the game time of the
 * frames that are drawn, once for each tick that has passed since the last look, so a block out of sight costs nothing and a block
 * come into sight starts where it is.
 */
class Ticker {
    private var last = Long.MIN_VALUE

    /** How many ticks to step to reach [now]: 0 for none (the same tick as the last look), that many, or -1 to start over where it is (a first sight, a long gap, time gone back). */
    fun due(now: Long): Int {
        val gap = if (last == Long.MIN_VALUE) -1L else now - last
        last = now
        return when {
            gap == 0L -> 0
            gap < 0L || gap > SNAP -> -1
            else -> gap.toInt()
        }
    }

    companion object {
        /** After this many ticks unlooked-at a block starts over. */
        const val SNAP = 40L
    }
}
