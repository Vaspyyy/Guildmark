package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.economy.works.build.WorksAwningBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksAwningBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sin
import kotlin.math.sqrt

/** What a stall's awning needs drawn for one frame. */
class WorksAwningRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH

    /** How far it's drawn up and out, from 0 (let down over the counter) to 1 (spread over the customers). */
    var open = 0f
    var rgb = 0xA02828
    var time = 0f
    var wet = false
}

/**
 * A stall of one's own's awning in its patron's colour, striped with cream (the stall's own: no model of Part F's):
 * while it trades, spread out from the stall's back posts over its counter and its customers, its scalloped valance
 * fluttering; at night let down, its front hanging over the counter to shut the stall; and between the two, rolled
 * out or let down over a second and a half as its keeper works it (WorksAtWork.awning). The canvas lifts and ripples in
 * the wind, more in rain. Drawn over the stall's three blocks from the awning's own block over its middle.
 */
class WorksAwningRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<WorksAwningBlockEntity, WorksAwningRenderState> {
    override fun createRenderState(): WorksAwningRenderState = WorksAwningRenderState()

    override fun extractRenderState(
        be: WorksAwningBlockEntity, state: WorksAwningRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val block = be.blockState
        state.facing = WorksPaint.facing(block)
        val open = block.hasProperty(WorksAwningBlock.OPEN) && block.getValue(WorksAwningBlock.OPEN)
        val k = WorksPaint.smooth(WorksPaint.since(level.gameTime, be.moved, partialTicks) / MOVE)
        state.open = if (open) k else 1f - k
        state.rgb = Dye.entries[be.color.coerceIn(0, Dye.entries.size - 1)].rgb
        state.time = WorksPaint.time(level.gameTime, partialTicks)
        state.wet = level.isRainingAt(be.blockPos)
    }

    override fun submit(state: WorksAwningRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        poseStack.scale(WorksPaint.PX, WorksPaint.PX, WorksPaint.PX)
        val canvas = Canvas(state)
        collector.submitCustomGeometry(poseStack, CLOTH) { pose, buffer -> canvas.draw(buffer, pose) }
        poseStack.popPose()
    }

    /** The canvas at one frame: its rows from the back posts to the front edge, across its stripes. */
    private class Canvas(private val s: WorksAwningRenderState) {
        private val gust = WorksArt.gust(s.time) * (if (s.wet) 1.5f else 1f)
        private val xs = FloatArray(STRIPES + 1) { X0 + it * STRIPE }
        private val px = Array(ROWS + 1) { FloatArray(STRIPES + 1) }
        private val py = Array(ROWS + 1) { FloatArray(STRIPES + 1) }
        private val pz = Array(ROWS + 1) { FloatArray(STRIPES + 1) }

        init {
            for (r in 0..ROWS) {
                val f = r.toFloat() / ROWS
                // Spread: one slope from the back posts out over the customers
                val oz = WorksPaint.lerp(BACK_Z, OPEN_Z, f)
                val oy = WorksPaint.lerp(BACK_Y, OPEN_Y, f)
                // Let down: the roof's part over the stall, then hanging straight over the counter's front
                val sz: Float
                val sy: Float
                if (f <= ROOF) {
                    sz = WorksPaint.lerp(BACK_Z, FRONT_Z, f / ROOF)
                    sy = WorksPaint.lerp(BACK_Y, EAVE_Y, f / ROOF)
                } else {
                    sz = FRONT_Z - 0.6f
                    sy = WorksPaint.lerp(EAVE_Y, SHUT_Y, (f - ROOF) / (1f - ROOF))
                }
                for (c in 0..STRIPES) {
                    val x = xs[c]
                    // The spread canvas lifts and ripples, most at its free front; the hanging one sways out
                    val ripple = 0.55f * gust * f * sin(0.21f * s.time + 2.6f * f + 0.09f * x)
                    val hang = if (f > ROOF) -0.9f * gust * ((f - ROOF) / (1f - ROOF)) * (0.6f + 0.4f * sin(0.15f * s.time + 0.07f * x)) else 0f
                    px[r][c] = x
                    py[r][c] = WorksPaint.lerp(sy, oy + ripple, s.open)
                    pz[r][c] = WorksPaint.lerp(sz + hang, oz, s.open)
                }
            }
        }

        fun draw(buffer: VertexConsumer, pose: PoseStack.Pose) {
            val light = s.lightCoords
            for (c in 0 until STRIPES) {
                val color = if (c % 2 == 0) ARGB.opaque(s.rgb) else CREAM
                val under = ARGB.scaleRGB(color, 0.72f)
                for (r in 0 until ROWS) cell(buffer, pose, r, c, color, under, light)
                valance(buffer, pose, c, color, under, light)
            }
        }

        private fun cell(buffer: VertexConsumer, pose: PoseStack.Pose, r: Int, c: Int, top: Int, under: Int, light: Int) {
            val ax = px[r][c]; val ay = py[r][c]; val az = pz[r][c]
            val bx = px[r + 1][c]; val by = py[r + 1][c]; val bz = pz[r + 1][c]
            val cx = px[r + 1][c + 1]; val cy = py[r + 1][c + 1]; val cz = pz[r + 1][c + 1]
            val dx = px[r][c + 1]; val dy = py[r][c + 1]; val dz = pz[r][c + 1]
            // Its normal, from its diagonals
            var nx = (by - dy) * (cz - az) - (bz - dz) * (cy - ay)
            var ny = (bz - dz) * (cx - ax) - (bx - dx) * (cz - az)
            var nz = (bx - dx) * (cy - ay) - (by - dy) * (cx - ax)
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
            nx /= len; ny /= len; nz /= len
            if (ny < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val v0 = r.toFloat() / ROWS
            val v1 = (r + 1).toFloat() / ROWS
            // Its top, and its underside a shade darker
            WorksPaint.vertex(buffer, pose, ax, ay, az, 0f, v0, top, light, nx, ny, nz)
            WorksPaint.vertex(buffer, pose, bx, by, bz, 0f, v1, top, light, nx, ny, nz)
            WorksPaint.vertex(buffer, pose, cx, cy, cz, 1f, v1, top, light, nx, ny, nz)
            WorksPaint.vertex(buffer, pose, dx, dy, dz, 1f, v0, top, light, nx, ny, nz)
            val ox = nx * UNDER
            val oy = ny * UNDER
            val oz = nz * UNDER
            WorksPaint.vertex(buffer, pose, dx - ox, dy - oy, dz - oz, 1f, v0, under, light, -nx, -ny, -nz)
            WorksPaint.vertex(buffer, pose, cx - ox, cy - oy, cz - oz, 1f, v1, under, light, -nx, -ny, -nz)
            WorksPaint.vertex(buffer, pose, bx - ox, by - oy, bz - oz, 0f, v1, under, light, -nx, -ny, -nz)
            WorksPaint.vertex(buffer, pose, ax - ox, ay - oy, az - oz, 0f, v0, under, light, -nx, -ny, -nz)
        }

        // The valance along the front edge: a scallop to each stripe, fluttering out; tucked away as it's let down
        private fun valance(buffer: VertexConsumer, pose: PoseStack.Pose, c: Int, top: Int, under: Int, light: Int) {
            val deep = VALANCE * s.open
            if (deep < 0.2f) return
            val x0 = px[ROWS][c]; val x1 = px[ROWS][c + 1]
            val y0 = py[ROWS][c]; val y1 = py[ROWS][c + 1]
            val z0 = pz[ROWS][c]; val z1 = pz[ROWS][c + 1]
            val flap = -1.4f * gust * (0.5f + 0.5f * sin(0.33f * s.time + 0.2f * x0))
            val xm = (x0 + x1) / 2f
            val ym = (y0 + y1) / 2f
            val zm = (z0 + z1) / 2f
            // Two quads: each half of the scallop, deepest at the stripe's middle
            for (half in 0..1) {
                val ax = if (half == 0) x0 else xm
                val bx = if (half == 0) xm else x1
                val ay = if (half == 0) y0 else ym
                val by = if (half == 0) ym else y1
                val az = if (half == 0) z0 else zm
                val bz = if (half == 0) zm else z1
                val da = deep * (if (half == 0) EDGE_DEEP else 1f)
                val db = deep * (if (half == 0) 1f else EDGE_DEEP)
                val ua = half * 0.5f
                val ub = ua + 0.5f
                WorksPaint.vertex(buffer, pose, ax, ay, az, ua, 0f, top, light, 0f, 0f, -1f)
                WorksPaint.vertex(buffer, pose, ax, ay - da, az + flap * da / VALANCE, ua, 1f, top, light, 0f, 0f, -1f)
                WorksPaint.vertex(buffer, pose, bx, by - db, bz + flap * db / VALANCE, ub, 1f, top, light, 0f, 0f, -1f)
                WorksPaint.vertex(buffer, pose, bx, by, bz, ub, 0f, top, light, 0f, 0f, -1f)
                WorksPaint.vertex(buffer, pose, bx, by, bz + 0.01f, ub, 0f, under, light, 0f, 0f, 1f)
                WorksPaint.vertex(buffer, pose, bx, by - db, bz + 0.01f + flap * db / VALANCE, ub, 1f, under, light, 0f, 0f, 1f)
                WorksPaint.vertex(buffer, pose, ax, ay - da, az + 0.01f + flap * da / VALANCE, ua, 1f, under, light, 0f, 0f, 1f)
                WorksPaint.vertex(buffer, pose, ax, ay, az + 0.01f, ua, 0f, under, light, 0f, 0f, 1f)
            }
        }
    }

    // Over the stall's three blocks either way of its middle, and down over the counter
    override fun getRenderBoundingBox(be: WorksAwningBlockEntity): AABB = AABB(be.blockPos).inflate(1.3, 0.0, 1.3).expandTowards(0.0, -2.3, 0.0).expandTowards(0.0, 0.6, 0.0)

    override fun shouldRenderOffScreen(): Boolean = true

    companion object {
        private val CLOTH: RenderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/block/white_wool.png"))
        private const val CREAM = 0xFFF2EBDC.toInt()

        /** Ticks it takes to be drawn up or let down. */
        private const val MOVE = 30f

        /** Its stripes (px: from where across, each how wide, how many), and its rows from back to front. */
        private const val X0 = -14f
        private const val STRIPE = 4f
        private const val STRIPES = 11
        private const val ROWS = 10

        /**
         * Its shape in the awning block's space (px, the stall's front to -z): its back edge on the back posts, its
         * front spread out over the customers or let down: the roof's share of it, the front posts' top (the eave)
         * and the counter's top it hangs to.
         */
        private const val BACK_Z = 26f
        private const val BACK_Y = 6f
        private const val OPEN_Z = -16f
        private const val OPEN_Y = 0.5f
        private const val FRONT_Z = -8f
        private const val EAVE_Y = 2.5f
        private const val SHUT_Y = -32f
        private const val ROOF = 0.55f

        /** How far under its top the canvas's underside is drawn (px). */
        private const val UNDER = 0.02f

        /** The valance: how deep (px), and how deep at a scallop's ends for its middle. */
        private const val VALANCE = 3.5f
        private const val EDGE_DEEP = 0.45f
    }
}
