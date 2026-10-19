package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.works.build.WorksFacingBlock
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the works' renderers share: the way into their models' space as Part F drew them (living-world/works-art.md,
 * "Spaces and signs": x east, y up, z south, facing north, a renderer turning about the block's middle), a quad's
 * vertex, and the frame's time.
 */
internal object WorksPaint {
    /** A pixel, in blocks. */
    const val PX = 1f / 16f

    /** An age that never was: long past everything that eases. */
    const val NEVER = 1e9f

    /** Game time is wrapped this often for the frame's clock, so a float keeps it exact. */
    private const val WRAP = 96_000L

    fun texture(path: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, path)

    /** The way [state] faces (north for anything that doesn't). */
    fun facing(state: BlockState): Direction =
        if (state.hasProperty(WorksFacingBlock.FACING)) state.getValue(WorksFacingBlock.FACING) else Direction.NORTH

    /** From the block's corner into the space of a model authored facing north, turned to [facing]; in blocks. */
    fun model(poseStack: PoseStack, facing: Direction) {
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, 180f - facing.toYRot())
        poseStack.translate(-0.5f, 0f, -0.5f)
    }

    /** A point of a model authored facing north ([x], [y], [z]: blocks from [pos]'s corner) in the world, turned to [facing] as [model] turns it. */
    fun world(pos: BlockPos, facing: Direction, x: Float, y: Float, z: Float): Vec3 {
        val a = Math.toRadians(180.0 - facing.toYRot())
        val dx = x - 0.5
        val dz = z - 0.5
        val c = cos(a)
        val s = sin(a)
        return Vec3(pos.x + 0.5 + dx * c + dz * s, pos.y + y.toDouble(), pos.z + 0.5 - dx * s + dz * c)
    }

    /** The same for a model authored facing south (the plaque). */
    fun southModel(poseStack: PoseStack, facing: Direction) {
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, -facing.toYRot())
        poseStack.translate(-0.5f, 0f, -0.5f)
    }

    /** One vertex, in the pose's units. */
    fun vertex(buffer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, u: Float, v: Float, color: Int, light: Int, nx: Float, ny: Float, nz: Float) {
        buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz)
    }

    /** The frame's clock in ticks: game time wrapped, with the partial tick. */
    fun time(gameTime: Long, partial: Float): Float = (gameTime % WRAP).toFloat() + partial

    /** Ticks since game time [then], with the partial tick (less than 0 while it's still to come); [NEVER] if it never was. */
    fun since(now: Long, then: Long, partial: Float): Float =
        if (then == 0L) NEVER else (now - then).coerceIn(-100_000L, 1_000_000L).toFloat() + partial

    fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
