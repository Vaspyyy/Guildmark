package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.cooking.vessel.StationBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * What a station's renderer takes from the world in the frame's extraction and draws from in its submission: the station's live
 * [StationAnim], how far between two ticks the frame is, how far the camera is (which decides how much detail is drawn), and the
 * time that drawing has cost so far for `cook perf`. A new one is made for every station every frame, so what a lambda that
 * writes geometry later in the frame holds of it cannot change under it.
 */
class StationState : BlockEntityRenderState() {
    var anim: StationAnim? = null
    var level: Level? = null
    var partial = 0f

    /** The camera's distance from the block's middle, squared, blocks. */
    var distSq = 0.0

    /** The nanoseconds this frame's extraction took (the drawing adds its own). */
    var nanos = 0L

    /** Whether the camera is near enough for the fine details (bubbles, ripples, glints, the second soot layer). */
    val close: Boolean get() = distSq < CLOSE * CLOSE

    /** Whether it is near enough for anything but the bare shapes (the soot, the chunks' tilt, the drift of a glint). */
    val near: Boolean get() = distSq < NEAR * NEAR

    /** Takes [be]'s anim and the frame's numbers. */
    fun fill(be: StationBlockEntity, partial: Float, camera: Vec3) {
        val t0 = System.nanoTime()
        this.partial = partial
        level = be.level
        val dx = camera.x - (be.blockPos.x + 0.5)
        val dy = camera.y - (be.blockPos.y + 0.5)
        val dz = camera.z - (be.blockPos.z + 0.5)
        distSq = dx * dx + dy * dy + dz * dz
        anim = StationFx.of(be)
        nanos = System.nanoTime() - t0
    }

    /**
     * Writes a layer of flat shapes: [draw] is given a [Pen] when the game comes to draw the layer [order] (layers go in order, the
     * lowest first, so what lies on a surface is drawn after the surface), in the pose [poseStack] has now. What it costs is counted.
     */
    fun layer(collector: SubmitNodeCollector, order: Int, poseStack: PoseStack, type: RenderType, draw: (Pen) -> Unit) {
        val light = lightCoords
        collector.order(order).submitCustomGeometry(poseStack, type) { pose, buffer ->
            val t0 = System.nanoTime()
            draw(Pen(buffer, pose, light))
            StationFx.spent(System.nanoTime() - t0)
        }
    }

    companion object {
        const val CLOSE = 13.0
        const val NEAR = 28.0
    }
}
