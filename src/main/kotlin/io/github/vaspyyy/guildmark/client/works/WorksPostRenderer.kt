package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.economy.works.build.WorksPostBlockEntity
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
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** What a works post needs drawn for one frame. */
class WorksPostRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH

    /** The works' parchment's top left in post.png (texels), or null for a post with no works on it yet. */
    var paper: IntArray? = null
    var seals = 0
    var was = 0
    var stamped = false

    /** Ticks since the seals last changed, the stamp came down (less than 0: still to), it was pledged to and put up. */
    var sealed = WorksPaint.NEVER
    var stamp = WorksPaint.NEVER
    var pledged = WorksPaint.NEVER
    var raised = WorksPaint.NEVER

    var time = 0f
    var wet = false
    val coin = ItemStackRenderState()
}

/**
 * A works post's drawing, seals and stamp (living-world/works-art.md, "The post"): the works' parchment pinned to the
 * board, nailed at its top corners and lifting at its foot in the wind (half again in rain, and with a shiver as the
 * post is knocked in); a red seal on its cord for each tenth pledged, swinging, each new one dropping out of its slit
 * one after another with a jolt (those taken back drawn up into it); the stamp pressed in as the works is paid for;
 * and the coins of a pledge made at the post dropping into its box. The seals' sound is [WorksClient]'s.
 */
class WorksPostRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WorksPostBlockEntity, WorksPostRenderState> {
    private val items: ItemModelResolver = context.itemModelResolver()

    override fun createRenderState(): WorksPostRenderState = WorksPostRenderState()

    override fun extractRenderState(
        be: WorksPostBlockEntity, state: WorksPostRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val now = level.gameTime
        state.facing = WorksPaint.facing(be.blockState)
        state.paper = if (be.works < 0) null else WorksArt.PAPER_AT[be.kind] ?: WorksArt.PAPER_AT["oven"]
        state.seals = be.seals
        state.was = be.was
        state.stamped = be.stamped
        state.sealed = WorksPaint.since(now, be.sealedAt, partialTicks)
        state.stamp = if (be.stamped) WorksPaint.since(now, be.stampedAt, partialTicks) else WorksPaint.NEVER
        state.pledged = WorksPaint.since(now, be.pledgedAt, partialTicks)
        state.raised = WorksPaint.since(now, be.raised, partialTicks)
        state.time = WorksPaint.time(now, partialTicks)
        state.wet = level.isRainingAt(be.blockPos.above())
        if (state.pledged in 0f..COINS) items.updateForTopItem(state.coin, coin, ItemDisplayContext.FIXED, level, null, be.blockPos.hashCode())
        else state.coin.clear()
    }

    override fun submit(state: WorksPostRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val paper = state.paper ?: return
        poseStack.pushPose()
        WorksPaint.model(poseStack, state.facing)
        poseStack.scale(WorksPaint.PX, WorksPaint.PX, WorksPaint.PX)
        val sheet = Sheet(state.time, state.wet, settle(state.raised))
        val light = state.lightCoords
        collector.submitCustomGeometry(poseStack, CUTOUT) { pose, buffer ->
            parchment(buffer, pose, sheet, paper, light)
            seals(buffer, pose, sheet, state, light)
        }
        if (state.stamped && state.stamp >= 0f) {
            val pressed = WorksArt.stampIn(state.stamp)
            collector.submitCustomGeometry(poseStack, INK) { pose, buffer -> stamp(buffer, pose, sheet, pressed[0], pressed[1], light) }
        }
        if (state.pledged in 0f..COINS && !state.coin.isEmpty) coins(state, poseStack, collector)
        poseStack.popPose()
    }

    /** The parchment's surface at one frame: its wind, its rain, and its shiver after the post went in. */
    private class Sheet(val t: Float, val wet: Boolean, val settle: Float) {
        /** px its point ([u], [v]) stands off the board. */
        fun lift(u: Float, v: Float): Float = WorksArt.paperLift(u, v, t, wet) + settle * v * v

        fun x(u: Float): Float = WorksPaint.lerp(WorksArt.PAPER_X0, WorksArt.PAPER_X1, u)

        fun y(v: Float): Float = WorksPaint.lerp(WorksArt.PAPER_Y0, WorksArt.PAPER_Y1, v)

        fun z(u: Float, v: Float): Float = WorksArt.PAPER_Z - lift(u, v)
    }

    /** px the foot flaps out as the post is knocked in, settling. */
    private fun settle(age: Float): Float = if (age < 0f || age > SETTLE) 0f else max(0f, 1.4f * exp(-age / 9f) * sin(age * 0.8f + 0.6f))

    // The parchment: a grid of quads, each corner stood off the board as the wind has it
    private fun parchment(buffer: VertexConsumer, pose: PoseStack.Pose, s: Sheet, at: IntArray, light: Int) {
        val u0 = at[0].toFloat()
        val v0 = at[1].toFloat()
        for (i in 0 until GRID) for (j in 0 until GRID) {
            val a = i.toFloat() / GRID
            val b = (i + 1).toFloat() / GRID
            val c = j.toFloat() / GRID
            val d = (j + 1).toFloat() / GRID
            corner(buffer, pose, s, a, c, u0, v0, light)
            corner(buffer, pose, s, a, d, u0, v0, light)
            corner(buffer, pose, s, b, d, u0, v0, light)
            corner(buffer, pose, s, b, c, u0, v0, light)
        }
    }

    private fun corner(buffer: VertexConsumer, pose: PoseStack.Pose, s: Sheet, u: Float, v: Float, u0: Float, v0: Float, light: Int) {
        WorksPaint.vertex(
            buffer, pose, s.x(u), s.y(v), s.z(u, v),
            (u0 + u * WorksArt.PAPER_W) / WorksArt.POST_TEX_W, (v0 + v * WorksArt.PAPER_H) / WorksArt.POST_TEX_H,
            -1, light, 0f, 0f, -1f,
        )
    }

    // The seals: each on its cord from its slit, swinging; the new ones dropping out in turn, those taken back drawn up
    private fun seals(buffer: VertexConsumer, pose: PoseStack.Pose, s: Sheet, state: WorksPostRenderState, light: Int) {
        val shown = max(state.seals, state.was)
        for (k in 0 until shown.coerceAtMost(WorksArt.SEALS)) {
            val cord = WorksArt.SEAL_CORD[k % 2]
            val tall = cord + WorksArt.SEAL_W
            var hidden = 0f
            var extra = 0f
            if (k >= state.seals) {
                // Taken back: drawn up into the slit, and gone
                val u = (state.sealed / WorksArt.SEAL_DROP).coerceIn(0f, 1f)
                if (u >= 1f) continue
                hidden = tall * u * u
            } else if (k >= state.was) {
                val drop = WorksArt.sealDrop(state.sealed - (k - state.was) * WorksPostBlockEntity.SEAL_GAP, cord)
                hidden = drop[0]
                extra = drop[1]
            }
            val swing = WorksArt.sealSwing(k, s.t, s.wet)
            seal(buffer, pose, s, k, tall, hidden, swing[0] + extra, swing[1], light)
        }
    }

    private fun seal(buffer: VertexConsumer, pose: PoseStack.Pose, s: Sheet, k: Int, tall: Float, hidden: Float, side: Float, out: Float, light: Int) {
        val u = WorksArt.SEAL_U[k]
        val v = WorksArt.SEAL_V
        val ax = s.x(u)
        val ay = s.y(v)
        val az = s.z(u, v) - 0.03f
        val shown = tall - hidden
        if (shown <= 0.01f) return
        val sprite = WorksArt.SEAL_SPRITE[k % 2]
        val half = WorksArt.SEAL_W / 2f
        // Its texels: the visible part only, from as far down its cord as still shows
        val tu0 = sprite[0].toFloat() / WorksArt.POST_TEX_W
        val tu1 = (sprite[0] + WorksArt.SEAL_W * WorksArt.PAPER_TPX) / WorksArt.POST_TEX_W
        val tv0 = (sprite[1] + hidden * WorksArt.PAPER_TPX) / WorksArt.POST_TEX_H
        val tv1 = (sprite[1] + tall * WorksArt.PAPER_TPX) / WorksArt.POST_TEX_H
        // Swung about the cord's top: across the board (clockwise as seen), then out from it
        val a = Math.toRadians(side.toDouble()).toFloat()
        val b = Math.toRadians(out.toDouble()).toFloat()
        val ca = cos(a)
        val sa = sin(a)
        val cb = cos(b)
        val sb = sin(b)
        val ny = sb
        val nz = -cb
        fun put(x: Float, y: Float, tu: Float, tv: Float) {
            val rx = x * ca - y * sa
            val ry = x * sa + y * ca
            WorksPaint.vertex(buffer, pose, ax + rx, ay + ry * cb, az + ry * sb, tu, tv, -1, light, 0f, ny, nz)
        }
        // Its left as seen (the post faces -z) is to +x
        put(half, 0f, tu0, tv0)
        put(half, -shown, tu0, tv1)
        put(-half, -shown, tu1, tv1)
        put(-half, 0f, tu1, tv0)
    }

    // The stamp: pressed into the parchment's surface (a small grid following it), struck big and faint, settling
    private fun stamp(buffer: VertexConsumer, pose: PoseStack.Pose, s: Sheet, scale: Float, ink: Float, light: Int) {
        if (ink <= 0.01f) return
        val size = WorksArt.STAMP_SIZE * scale
        val turn = Math.toRadians(WorksArt.STAMP_TURN.toDouble()).toFloat()
        val ct = cos(turn)
        val st = sin(turn)
        val cx = s.x(WorksArt.STAMP_U)
        val cy = s.y(WorksArt.STAMP_V)
        val color = ARGB.color((ink * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        fun put(sa: Float, sb: Float) {
            // Its own (across as seen, up), turned clockwise as seen; then back to the parchment's u and v for the lift
            val dx = -sa * size
            val dy = sb * size
            val x = cx + dx * ct - dy * st
            val y = cy + dx * st + dy * ct
            val u = (WorksArt.PAPER_X0 - x) / (WorksArt.PAPER_X0 - WorksArt.PAPER_X1)
            val v = (WorksArt.PAPER_Y0 - y) / (WorksArt.PAPER_Y0 - WorksArt.PAPER_Y1)
            val z = WorksArt.PAPER_Z - s.lift(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) - 0.015f
            val tu = (WorksArt.STAMP_U0 + (sa + 0.5f) * WorksArt.STAMP_TEX) / WorksArt.POST_TEX_W
            val tv = (WorksArt.STAMP_V0 + (0.5f - sb) * WorksArt.STAMP_TEX) / WorksArt.POST_TEX_H
            WorksPaint.vertex(buffer, pose, x, y, z, tu, tv, color, light, 0f, 0f, -1f)
        }
        for (i in 0 until STAMP_GRID) for (j in 0 until STAMP_GRID) {
            val a0 = i.toFloat() / STAMP_GRID - 0.5f
            val a1 = (i + 1).toFloat() / STAMP_GRID - 0.5f
            val b0 = 0.5f - j.toFloat() / STAMP_GRID
            val b1 = 0.5f - (j + 1).toFloat() / STAMP_GRID
            put(a0, b0)
            put(a0, b1)
            put(a1, b1)
            put(a1, b0)
        }
    }

    // A pledge's coins into the box: three marks dropped in one after another through its lid, turning as they fall
    private fun coins(state: WorksPostRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        for (i in 0 until 3) {
            val age = state.pledged - i * COIN_GAP
            if (age < 0f || age >= COIN_FALL) continue
            val f = age / COIN_FALL
            poseStack.pushPose()
            poseStack.translate(BOX_X + (i - 1) * 0.8f, BOX_LID + COIN_FROM * (1f - f * f), BOX_Z)
            poseStack.rotateDegrees(Axis.YP, age * 47f + i * 70f)
            poseStack.scale(COIN_SIZE, COIN_SIZE, COIN_SIZE)
            state.coin.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }
    }

    // Its board stands a block above the post's own
    override fun getRenderBoundingBox(be: WorksPostBlockEntity): AABB = AABB(be.blockPos).expandTowards(0.0, 1.0, 0.0).inflate(0.1)

    companion object {
        private val TEXTURE = WorksPaint.texture(WorksArt.POST_TEXTURE)
        private val CUTOUT: RenderType = RenderTypes.entityCutout(TEXTURE)
        private val INK: RenderType = RenderTypes.entityTranslucent(TEXTURE)

        private val coin: ItemStack by lazy { ItemStack(ModItems.GUILD_MARK.get()) }

        /** The parchment's grid, the stamp's. */
        private const val GRID = 8
        private const val STAMP_GRID = 4

        /** How long the parchment shivers after the post goes in. */
        private const val SETTLE = 40f

        /** The pledge box's lid (px: its slot's middle, the lid's top), how high a coin drops from, and its size (px). */
        private const val BOX_X = 8f
        private const val BOX_Z = 8f
        private const val BOX_LID = 9.6f
        private const val COIN_FROM = 6f
        private const val COIN_SIZE = 3.6f

        /** Ticks a coin takes to fall, between one and the next, and the whole drop. */
        private const val COIN_FALL = 6f
        private const val COIN_GAP = 4f
        private const val COINS = 16f
    }
}
