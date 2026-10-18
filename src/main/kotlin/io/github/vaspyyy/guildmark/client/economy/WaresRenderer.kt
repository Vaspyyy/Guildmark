package io.github.vaspyyy.guildmark.client.economy

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Look
import io.github.vaspyyy.guildmark.city.plan.Wares
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.WaresBlock
import io.github.vaspyyy.guildmark.economy.WaresBlockEntity
import io.github.vaspyyy.guildmark.economy.WaresLayout
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.Util
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a market display needs for one frame: its goods' places, how many of each show, and how they're moving. */
class WaresRenderState : BlockEntityRenderState() {
    /** Its goods' places in its block, worked out once; null while they're worked out. */
    var drawn: WaresRenderer.Drawn? = null
    /** How many items of each good show. */
    var counts: IntArray = IntArray(0)
    /** Each good's items on the move (see [WaresBlockEntity.motion]). */
    var motion: Array<WaresBlockEntity.Motion?> = arrayOf()
    /** Ticks since each good's items set off. */
    var ages: FloatArray = FloatArray(0)
    /** Each good's item, ready to draw. */
    var models: Array<ItemStackRenderState> = arrayOf()
    /** How many of its items are drawn, by how far off it's seen: all near by, fewer and fewer out to [WaresRenderer.FAR]. */
    var keep = 1f
}

/**
 * A market display's goods: the game's own item models laid as [WaresLayout] sets them out, as many as the market
 * holds ([WaresBlockEntity.counts]), so a crate stands empty when the market has none, full to the rim at the town's
 * usual stock and heaped over it in a glut. When the counts change the new items settle in one after another, set
 * down from the keeper's side, and those bought lift out toward the buyers'; it all takes [WaresBlockEntity.LONGEST]
 * ticks at most. Fewer items are drawn the further off the display is seen, and none past [FAR] blocks.
 *
 * A display's places are worked out off the render thread the first time it's drawn and kept with it, each item's
 * pose as one matrix, so drawing one is a matrix and a model per item.
 */
class WaresRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WaresBlockEntity, WaresRenderState> {
    private val items: ItemModelResolver = context.itemModelResolver()

    /** A display's goods' places as they stand in its block, its front to [facing]: each good's lane, item and poses. */
    class Drawn(val wares: Wares, val facing: Direction, val lanes: List<WaresLayout.Lane>, val stacks: List<ItemStack>, val poses: List<Array<Matrix4f>>)

    /** A display's places being worked out. */
    private class Laying(val wares: Wares, val facing: Direction, val job: CompletableFuture<Drawn?>)

    override fun createRenderState(): WaresRenderState = WaresRenderState()

    override fun extractRenderState(
        display: WaresBlockEntity, state: WaresRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(display, state, breakProgress)
        val level = display.level ?: return
        val drawn = drawn(display) ?: return
        state.drawn = drawn
        state.keep = keep(sqrt(cameraPosition.distanceToSqr(Vec3.atCenterOf(display.blockPos))).toFloat())
        state.counts = display.counts
        val motion = display.motion
        state.motion = motion
        state.ages = FloatArray(motion.size) { i -> motion[i]?.let { (level.gameTime - it.since) + partialTicks } ?: 0f }
        val seed = display.blockPos.hashCode()
        state.models = Array(drawn.stacks.size) { i ->
            ItemStackRenderState().also { items.updateForTopItem(it, drawn.stacks[i], ItemDisplayContext.FIXED, level, null, seed + i) }
        }
    }

    /** [display]'s places as they stand now; null while they're worked out (it's set going if need be). */
    private fun drawn(display: WaresBlockEntity): Drawn? {
        val facing = display.blockState.getValue(WaresBlock.FACING)
        val wares = display.wares
        when (val kept = display.drawn) {
            is Drawn -> if (kept.wares == wares && kept.facing == facing) return kept
            is Laying -> if (kept.wares == wares && kept.facing == facing) {
                if (!kept.job.isDone) return null
                val done = kept.job.join() ?: return null
                display.drawn = done
                return done
            }
        }
        val look = display.look
        val seed = display.blockPos.asLong()
        val job = CompletableFuture.supplyAsync({ lay(look, wares, facing, seed) }, Util.backgroundExecutor()).exceptionally { e ->
            Guildmark.LOGGER.warn("Couldn't lay out the market display at {}", display.blockPos, e)
            null
        }
        display.drawn = Laying(wares, facing, job)
        return null
    }

    override fun submit(state: WaresRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val drawn = state.drawn ?: return
        val fx = drawn.facing.stepX.toFloat()
        val fz = drawn.facing.stepZ.toFloat()
        for ((i, lane) in drawn.lanes.withIndex()) {
            val model = state.models.getOrNull(i)?.takeIf { !it.isEmpty } ?: continue
            val poses = drawn.poses[i]
            val count = state.counts.getOrElse(i) { lane.full }.coerceIn(0, lane.max)
            val m = state.motion.getOrNull(i)
            val age = state.ages.getOrElse(i) { 0f }
            val moving = m != null && age < length(m)
            val from = if (moving) m!!.from.coerceIn(0, lane.max) else count
            val low = min(from, count)
            val high = max(from, count)
            val gap = gap(high - low)
            for (j in 0 until high) {
                val slot = lane.slots[j]
                if (slot.rank >= state.keep) continue
                var ox = 0f
                var oy = 0f
                var oz = 0f
                var size = 1f
                if (j >= low) {
                    // On the move: the new ones in order, from the bottom of the heap up; those bought from the top down
                    val arriving = count > from
                    val k = if (arriving) j - from else from - 1 - j
                    val p = ((age - k * gap) / SETTLE).coerceIn(0f, 1f)
                    if (arriving) {
                        if (p <= 0f) continue
                        val rest = (1f - p) * (1f - p)
                        ox = -fx * BACK * rest
                        oy = LIFT * rest
                        oz = -fz * BACK * rest
                        size = 0.6f + 0.4f * min(1f, p * 2.5f)
                    } else {
                        if (p >= 1f) continue
                        val gone = p * p
                        ox = fx * BACK * gone
                        oy = LIFT * gone
                        oz = fz * BACK * gone
                        size = 1f - 0.7f * gone
                    }
                } else if (low >= lane.buried[j]) {
                    // Out of sight under the rest
                    continue
                }
                poseStack.pushPose()
                if (oy != 0f) poseStack.translate(ox, oy, oz)
                poseStack.mulPose(poses[j])
                if (size != 1f) poseStack.scale(size, size, size)
                model.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
                poseStack.popPose()
            }
        }
    }

    override fun getRenderBoundingBox(display: WaresBlockEntity): AABB = AABB(display.blockPos).expandTowards(0.0, HEAPED, 0.0)

    override fun getViewDistance(): Int = FAR

    companion object {
        /** How far off (blocks) a display's goods are drawn, and how near all of them are. */
        const val FAR = 32
        private const val NEAR = 12f

        /** The share of a display's items still drawn at [FAR]. */
        private const val THINNEST = 0.3f

        /** How far over its block a glut may heap (blocks). */
        private const val HEAPED = 0.6

        /** Ticks an item takes to settle in or lift out, and the most between one and the next. */
        private const val SETTLE = 10f
        private const val MOST_GAP = 2.5f

        /** How high over its place an item comes in from or goes up to, and how far from behind or toward the front (blocks). */
        private const val LIFT = 0.3f
        private const val BACK = 0.2f

        /** The share of a display's items drawn seen from [distance] blocks off. */
        fun keep(distance: Float): Float =
            if (distance <= NEAR) 1f else max(THINNEST, 1f - (distance - NEAR) / (FAR - NEAR) * (1f - THINNEST))

        /** Ticks between one item setting off and the next, for [n] of them, so all are done within [WaresBlockEntity.LONGEST]. */
        private fun gap(n: Int): Float = min(MOST_GAP, (WaresBlockEntity.LONGEST - SETTLE) / max(1, n - 1))

        /** Ticks until a good's items in [m] are all there or gone. */
        private fun length(m: WaresBlockEntity.Motion): Float {
            val n = kotlin.math.abs(m.to - m.from)
            return SETTLE + gap(n) * (n - 1).coerceAtLeast(0)
        }

        /** The places of a display of [look] showing [wares], its front to [facing], for the display whose position gives [seed]. */
        private fun lay(look: Look, wares: Wares, facing: Direction, seed: Long): Drawn {
            val lanes = WaresLayout.of(look, wares.goods, seed)
            val stacks = lanes.map { lane -> Good.byId(lane.good)?.item?.let(::ItemStack) ?: ItemStack.EMPTY }
            // The layout's frame has the front to the north: turned about the block's middle to face the right way
            val turn = Matrix4f().translation(0.5f, 0f, 0.5f).rotateY(rad(180f - facing.toYRot())).translate(-0.5f, 0f, -0.5f)
            val poses = lanes.map { lane -> Array(lane.slots.size) { pose(turn, lane.slots[it]) } }
            return Drawn(wares, facing, lanes, stacks, poses)
        }

        /** One item's pose in its block: in place, turned and tipped, laid as its pose says, spun in its plane and sized. */
        private fun pose(turn: Matrix4f, s: WaresLayout.Slot): Matrix4f {
            val m = Matrix4f(turn).translate(s.x, s.y, s.z).rotateY(rad(s.yaw)).rotateX(rad(s.pitch)).rotateZ(rad(s.roll))
            if (s.pose == WaresLayout.Pose.FLAT) m.rotateX(rad(90f))
            return m.rotateZ(rad(s.spin)).scale(s.sx, s.sy, s.sz)
        }

        private fun rad(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()
    }
}
