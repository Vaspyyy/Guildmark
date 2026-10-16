package io.github.vaspyyy.guildmark.client.island

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.island.LighthouseLampBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a lighthouse lamp's beams need for one frame. */
class LighthouseLampRenderState : BlockEntityRenderState() {
    /** How bright it burns: 0 out, 1 full. */
    var glow = 0f
    /** Where it is in its turn, in ticks from facing east. */
    var turn = 0f
    /** How hard it's raining: the beams show stronger in wet air. */
    var rain = 0f
    /** How dark the sky is: 0 by day, 1 at night. A beam is a pale thing in daylight. */
    var dark = 0f
}

/**
 * A lit lighthouse lamp's two beams, turning together once every [PERIOD] ticks and reaching [LENGTH] blocks out over
 * the sea. Each beam is a widening cone of soft light drawn as crossed planes; a plane seen edge on fades out, so from
 * any side the beam reads as one shaft of light, and a second layer of haze drifts slowly outward along it. When a beam
 * swings round to face you the lens flares, and from far out the lamp still shows as a point of light. All the lamps
 * of a world turn in step with its clock, so everyone at sea sees the same sweep.
 */
class LighthouseLampRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<LighthouseLampBlockEntity, LighthouseLampRenderState> {

    override fun createRenderState(): LighthouseLampRenderState = LighthouseLampRenderState()

    override fun extractRenderState(
        lamp: LighthouseLampBlockEntity, state: LighthouseLampRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(lamp, state, breakProgress)
        state.glow = lamp.glowO + (lamp.glow - lamp.glowO) * partialTicks
        val level = lamp.level ?: return
        // Each lamp starts its turn somewhere of its own, so two lighthouses in sight of each other don't sweep as one
        val start = ((state.blockPos.asLong() * -7046029254386353131L) ushr 40) % PERIOD
        state.turn = ((level.gameTime + start) % PERIOD).toFloat() + partialTicks
        state.rain = level.getRainLevel(partialTicks)
        state.dark = (level.skyDarken / 11f).coerceIn(0f, 1f)
    }

    override fun submit(state: LighthouseLampRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (state.glow < 0.003f) return
        // Where the eye is, from the lens
        val ex = (camera.pos.x - state.blockPos.x - 0.5).toFloat()
        val ey = (camera.pos.y - state.blockPos.y - 0.5).toFloat()
        val ez = (camera.pos.z - state.blockPos.z - 0.5).toFloat()
        val eyeDistance = max(0.001f, sqrt(ex * ex + ey * ey + ez * ez))
        val strength = state.glow * (0.35f + 0.65f * state.dark) * (1f + 0.6f * state.rain)
        val angle = state.turn / PERIOD * 2f * PI.toFloat()
        val beams = Array(2) { k ->
            val a = angle + k * PI.toFloat()
            val x = cos(a)
            val z = sin(a)
            val n = sqrt(x * x + DIP * DIP + z * z)
            floatArrayOf(x / n, -DIP / n, z / n)
        }

        poseStack.pushPose()
        poseStack.translate(0.5f, 0.5f, 0.5f)
        collector.submitCustomGeometry(poseStack, RenderTypes.eyes(BEAM)) { pose, buffer ->
            for (d in beams) cone(buffer, pose, d, ex, ey, ez, strength * BEAM_ALPHA, SHAFT_PLANES, BEAM_SEGMENTS, haze = false, state.turn)
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.eyes(HAZE)) { pose, buffer ->
            for (d in beams) cone(buffer, pose, d, ex, ey, ez, strength * HAZE_ALPHA, HAZE_PLANES, BEAM_SEGMENTS, haze = true, state.turn)
        }

        // The lens: a steady glow, flaring as a beam comes round to face the eye, and kept big enough to show far off
        var facing = 0f
        for (d in beams) facing = max(facing, (d[0] * ex + d[1] * ey + d[2] * ez) / eyeDistance)
        val glare = max(0f, facing).pow(10)
        val far = 1f + eyeDistance / 40f
        val size = (0.9f + 4.5f * glare) * far * (0.6f + 0.4f * state.glow)
        val alpha = min(1f, strength * (0.45f + 0.55f * glare))
        poseStack.rotate(camera.orientation)
        collector.submitCustomGeometry(poseStack, RenderTypes.eyes(FLARE)) { pose, buffer ->
            val h = size / 2f
            val color = ARGB.color(alpha, 0xFFFFFF)
            vertex(buffer, pose, -h, -h, 0f, 0f, 1f, color)
            vertex(buffer, pose, h, -h, 0f, 1f, 1f, color)
            vertex(buffer, pose, h, h, 0f, 1f, 0f, color)
            vertex(buffer, pose, -h, h, 0f, 0f, 0f, color)
        }
        poseStack.popPose()
    }

    /**
     * One beam along [d] as [planes] crossed planes, each in [segments] lengths so that a length seen edge on, or one
     * the eye is standing in, can fade on its own. The haze layer's texture tiles along the beam and drifts outward.
     */
    private fun cone(
        buffer: VertexConsumer, pose: PoseStack.Pose, d: FloatArray, ex: Float, ey: Float, ez: Float, alpha: Float,
        planes: Int, segments: Int, haze: Boolean, turn: Float,
    ) {
        if (alpha <= 0.002f) return
        // Two directions square to the beam: level across it, and up from it
        var sx = -d[2]
        var sz = d[0]
        val sl = sqrt(sx * sx + sz * sz)
        sx /= sl
        sz /= sl
        val ux = d[1] * sz
        val uy = d[2] * sx - d[0] * sz
        val uz = -d[1] * sx
        val drift = turn / PERIOD * HAZE_REPEATS
        for (p in 0 until planes) {
            val phi = p * PI.toFloat() / planes
            // This plane's width runs along w, and it faces along n
            val wx = cos(phi) * sx + sin(phi) * ux
            val wy = sin(phi) * uy
            val wz = cos(phi) * sz + sin(phi) * uz
            val nx = d[1] * wz - d[2] * wy
            val ny = d[2] * wx - d[0] * wz
            val nz = d[0] * wy - d[1] * wx
            for (s in 0 until segments) {
                val t0 = s.toFloat() / segments
                val t1 = (s + 1).toFloat() / segments
                // How squarely this length of the plane faces the eye, and how far it is from it
                val tm = (t0 + t1) / 2f * LENGTH
                val mx = d[0] * tm - ex
                val my = d[1] * tm - ey
                val mz = d[2] * tm - ez
                val md = max(0.001f, sqrt(mx * mx + my * my + mz * mz))
                val face = (abs(nx * mx + ny * my + nz * mz) / md).pow(0.7f)
                val near = smooth((md - NEAR_FADE) / NEAR_FADE)
                val a0 = alpha * face * near * (if (haze) (1f - t0).pow(1.3f) else 1f)
                val a1 = alpha * face * near * (if (haze) (1f - t1).pow(1.3f) else 1f)
                if (a0 <= 0.002f && a1 <= 0.002f) continue
                val r0 = ROOT + (SPREAD - ROOT) * t0
                val r1 = ROOT + (SPREAD - ROOT) * t1
                val l0 = t0 * LENGTH
                val l1 = t1 * LENGTH
                val v0 = if (haze) l0 / HAZE_TILE - drift else t0
                val v1 = if (haze) l1 / HAZE_TILE - drift else t1
                val c0 = ARGB.color(min(1f, a0), 0xFFFFFF)
                val c1 = ARGB.color(min(1f, a1), 0xFFFFFF)
                val ax = d[0] * l0 - wx * r0
                val ay = d[1] * l0 - wy * r0
                val az = d[2] * l0 - wz * r0
                val bx = d[0] * l0 + wx * r0
                val by = d[1] * l0 + wy * r0
                val bz = d[2] * l0 + wz * r0
                val cx = d[0] * l1 + wx * r1
                val cy = d[1] * l1 + wy * r1
                val cz = d[2] * l1 + wz * r1
                val qx = d[0] * l1 - wx * r1
                val qy = d[1] * l1 - wy * r1
                val qz = d[2] * l1 - wz * r1
                // Both faces: the pipeline culls back faces
                vertex(buffer, pose, ax, ay, az, 0f, v0, c0)
                vertex(buffer, pose, bx, by, bz, 1f, v0, c0)
                vertex(buffer, pose, cx, cy, cz, 1f, v1, c1)
                vertex(buffer, pose, qx, qy, qz, 0f, v1, c1)
                vertex(buffer, pose, qx, qy, qz, 0f, v1, c1)
                vertex(buffer, pose, cx, cy, cz, 1f, v1, c1)
                vertex(buffer, pose, bx, by, bz, 1f, v0, c0)
                vertex(buffer, pose, ax, ay, az, 0f, v0, c0)
            }
        }
    }

    private fun vertex(buffer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, u: Float, v: Float, color: Int) {
        buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
    }

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    override fun getRenderBoundingBox(lamp: LighthouseLampBlockEntity): AABB = AABB(lamp.blockPos).inflate(LENGTH.toDouble() + SPREAD)

    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = 256

    companion object {
        private val BEAM = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/island/lighthouse_beam.png")
        private val HAZE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/island/lighthouse_beam_tile.png")
        private val FLARE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/island/lamp_flare.png")

        /** Ticks for one whole turn of the lamp: a beam passes any one point every half of it. */
        const val PERIOD = 240L
        /** How far the beams reach, and how wide they are at the lens and at their ends (half widths). */
        private const val LENGTH = 56f
        private const val ROOT = 0.4f
        private const val SPREAD = 4.5f
        /** How far the beams dip below level, so that far out they come down toward the sea. */
        private const val DIP = 0.035f
        private const val SHAFT_PLANES = 4
        private const val HAZE_PLANES = 2
        private const val BEAM_SEGMENTS = 6
        private const val BEAM_ALPHA = 0.3f
        private const val HAZE_ALPHA = 0.16f
        /** Blocks of beam per repeat of the haze, and how many repeats it drifts outward in one turn (a whole number, so it never jumps). */
        private const val HAZE_TILE = 9f
        private const val HAZE_REPEATS = 2f
        /** Within this of the eye a length of beam fades away, so standing in one doesn't white out the view. */
        private const val NEAR_FADE = 2.5f
    }
}
