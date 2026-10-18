package io.github.vaspyyy.guildmark.client.homestead

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.homestead.CharterPostBlock
import io.github.vaspyyy.guildmark.homestead.CharterPostBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sin
import kotlin.math.sqrt

/** What a Charter Post's pennant and lantern need for a frame. */
class CharterPostRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.SOUTH
    var color = 0xFFFFFF
    /** Ticks, for the wave in the cloth; each post has its own start so two side by side don't flap as one. */
    var time = 0f
    /** How strong the wind is: a little more in the rain, a lot more in a storm. */
    var wind = 1f
    /** How dark it is: the lantern's glow shows at dusk and by night. */
    var dark = 0f
    var tier = 0
}

/**
 * The Charter Post's pennant, flying from the top of its pole in the owner's colour: a swallow-tailed cloth cut into a
 * strip of panels that ripple from the pole out to the tails, harder in wind and rain. Below, the lantern on its arm
 * glows softly after dark, flickering a little.
 */
class CharterPostRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CharterPostBlockEntity, CharterPostRenderState> {

    override fun createRenderState(): CharterPostRenderState = CharterPostRenderState()

    override fun extractRenderState(
        post: CharterPostBlockEntity, state: CharterPostRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(post, state, breakProgress)
        state.facing = post.blockState.getValueOrElse(CharterPostBlock.FACING, Direction.SOUTH)
        state.color = Dye.entries[post.color.coerceIn(0, Dye.entries.size - 1)].rgb
        state.tier = post.tier
        val level = post.level ?: return
        val start = ((state.blockPos.asLong() * -7046029254386353131L) ushr 44) % 1000L
        state.time = ((level.gameTime + start) % 24000L).toFloat() + partialTicks
        state.wind = 1f + level.getRainLevel(partialTicks) * 0.6f + level.getThunderLevel(partialTicks) * 0.8f
        state.dark = (level.skyDarken / 11f).coerceIn(0f, 1f)
    }

    override fun submit(state: CharterPostRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        // Drawn facing south (the road to +z) and turned to the post's facing
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, -state.facing.toYRot())
        val light = state.lightCoords
        val color = ARGB.color(255, state.color)
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(PENNANT)) { pose, buffer -> pennant(buffer, pose, state, color, light) }
        if (state.dark > 0.05f) {
            val flicker = 0.85f + 0.1f * sin(state.time * 0.71f) + 0.05f * sin(state.time * 2.3f + 1.7f)
            val alpha = (state.dark * 0.75f * flicker).coerceIn(0f, 1f)
            poseStack.pushPose()
            poseStack.translate(LANTERN_X, LANTERN_Y, 0f)
            poseStack.rotate(camera.orientation)
            collector.submitCustomGeometry(poseStack, RenderTypes.eyes(GLOW)) { pose, buffer ->
                val h = 0.55f * (0.92f + 0.08f * flicker)
                val c = ARGB.color(alpha, 0xFFD38A)
                vertex(buffer, pose, -h, -h, 0f, 0f, 1f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
                vertex(buffer, pose, h, -h, 0f, 1f, 1f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
                vertex(buffer, pose, h, h, 0f, 1f, 0f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
                vertex(buffer, pose, -h, h, 0f, 0f, 0f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
            }
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    /**
     * The cloth: [SEGMENTS] panels from the pole out, each two rows high. Along its length a wave runs out from the pole,
     * growing toward the tails, with a slower sway over it; the cloth narrows a little and droops toward its end.
     */
    private fun pennant(buffer: VertexConsumer, pose: PoseStack.Pose, state: CharterPostRenderState, color: Int, light: Int) {
        val t = state.time
        val wind = state.wind
        val xs = FloatArray(SEGMENTS + 1)
        val zs = FloatArray(SEGMENTS + 1)
        val tops = FloatArray(SEGMENTS + 1)
        val bottoms = FloatArray(SEGMENTS + 1)
        for (i in 0..SEGMENTS) {
            val u = i.toFloat() / SEGMENTS
            // Out from the pole (to -x), the wave across it (z) growing toward the end
            val wave = sin(u * 7.5f - t * 0.32f * wind) * 0.07f * u * wind + sin(u * 3f - t * 0.11f) * 0.03f * u
            xs[i] = -0.03f - u * LENGTH * (1f - 0.04f * wind * u)
            zs[i] = wave
            val droop = u * u * 0.08f / wind
            val height = CLOTH * (1f - 0.22f * u)
            tops[i] = TOP - droop - (CLOTH - height) * 0.15f
            bottoms[i] = tops[i] - height
        }
        for (i in 0 until SEGMENTS) {
            val u0 = i.toFloat() / SEGMENTS
            val u1 = (i + 1).toFloat() / SEGMENTS
            // The panel's facing, from the run of the cloth here, for its shading
            val dx = xs[i + 1] - xs[i]
            val dz = zs[i + 1] - zs[i]
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
            val nx = -dz / len
            val nz = dx / len
            val shade = 0.82f + 0.18f * (nx * 0.6f + nz * 0.8f).coerceIn(-1f, 1f)
            val c = ARGB.scaleRGB(color, shade.coerceIn(0.6f, 1f))
            vertex(buffer, pose, xs[i], bottoms[i], zs[i], u0, 1f, c, light, nx, 0f, nz)
            vertex(buffer, pose, xs[i + 1], bottoms[i + 1], zs[i + 1], u1, 1f, c, light, nx, 0f, nz)
            vertex(buffer, pose, xs[i + 1], tops[i + 1], zs[i + 1], u1, 0f, c, light, nx, 0f, nz)
            vertex(buffer, pose, xs[i], tops[i], zs[i], u0, 0f, c, light, nx, 0f, nz)
        }
    }

    private fun vertex(
        buffer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, u: Float, v: Float, color: Int, light: Int,
        nx: Float, ny: Float, nz: Float,
    ) {
        buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz)
    }

    override fun getRenderBoundingBox(post: CharterPostBlockEntity): AABB = AABB(post.blockPos).inflate(1.0, 1.0, 1.0)

    override fun getViewDistance(): Int = 96

    companion object {
        private val PENNANT = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/homestead/pennant.png")
        private val GLOW = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/homestead/lantern_glow.png")

        private const val SEGMENTS = 10
        /** The cloth's length out from the pole, its height at the pole and where its top edge is (blocks). */
        private const val LENGTH = 0.78f
        private const val CLOTH = 0.42f
        private const val TOP = 1.74f
        /** Where the lantern hangs, from the post's middle (art/scripts/homestead_art.py puts it there). */
        private const val LANTERN_X = 0.3125f
        private const val LANTERN_Y = 0.53f
    }
}
