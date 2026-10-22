package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.dish.Freshness
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** What a plate remembers from one frame to the next: the dish it held, how it looks, when it was set down, and the one that has just gone. */
class PlateSeen {
    val ticker = Ticker()

    /** The dish as the block holds it (what a change is told by), the stage it was at, and the model of it as it looks. */
    var raw: ItemStack = ItemStack.EMPTY
    var stage = Stage.FRESH
    var model: ItemStackRenderState? = null
    var generation = -1

    /** Whether the dish was cooked today and is fresh (it steams). */
    var hot = false

    /** When it was set down (game time), long ago for one that was there at first sight. */
    var since = -1.0e9

    /** The dish that was taken, as it looked, when it went and which way (toward whoever took it). */
    var gone: ItemStackRenderState? = null
    var goneAt = -1.0e9
    var goneDx = 0f
    var goneDz = 0f
}

class PlateState : SceneState() {
    var seen: PlateSeen? = null

    /** The dish's turn on the plate, degrees: a plate's dish is not set down square. */
    var yaw = 0f
}

/**
 * The Plate seen (living-world/cook/design.md 3.5, 11.1): one dish laid flat on the plate as the game draws an item, dressed as it looks
 * today (fresh with its steam, stale, gone off with flies; the item's own model carries all that) and animated as it comes and goes:
 *
 *  - a dish set down falls from a hand's height, lands with a bounce or two and rocks as it settles;
 *  - a dish taken or eaten lifts off the plate toward whoever took it, turning, and shrinks away; a plate that is dressed for the town
 *    shows what it is given the same way;
 *  - a dish cooked today steams: a curl now and then;
 *  - a dish gone off draws flies, three specks that circle it.
 */
class PlateRenderer(context: BlockEntityRendererProvider.Context) : SceneRenderer<PlateBlockEntity, PlateState>(context) {
    private val memory = WeakHashMap<PlateBlockEntity, PlateSeen>()

    override fun createRenderState(): PlateState = PlateState()

    override fun extract(be: PlateBlockEntity, state: PlateState) {
        val level = state.level ?: return
        val seen = memory.getOrPut(be) { PlateSeen() }
        state.seen = seen
        val pos = be.blockPos
        state.yaw = (Mix.unit(pos.x * 31 + pos.z, pos.y + 7) - 0.5f) * 30f
        val now = level.gameTime
        val n = seen.ticker.due(now)
        if (n == 0 && seen.generation == CookItems.generation) return

        val raw = be.dish
        val first = n < 0
        val changed = !ItemStack.matches(raw, seen.raw)
        val stage = if (raw.isEmpty) Stage.FRESH else Freshness.stageOf(level, raw)
        if (changed || stage != seen.stage || seen.generation != CookItems.generation) {
            if (changed && !first) {
                // The dish that was there goes, toward whoever has it, and the one that comes is set down
                val old = seen.model
                if (!seen.raw.isEmpty && old != null) {
                    val dir = StationFx.towardPlayer(pos)
                    seen.gone = old
                    seen.goneAt = now.toDouble()
                    seen.goneDx = dir[0]
                    seen.goneDz = dir[1]
                }
                seen.since = if (raw.isEmpty) -1.0e9 else now.toDouble()
            } else if (first) {
                seen.since = -1.0e9
                seen.gone = null
            }
            seen.raw = raw
            seen.stage = stage
            seen.model = if (raw.isEmpty) null else CookItems.stateOf(be.look(level), resolver, level, pos.hashCode())
            seen.generation = CookItems.generation
        }
        if (seen.gone != null && state.time - seen.goneAt > GONE_TICKS + 2) seen.gone = null

        // A dish cooked today steams, a wisp now and then
        if (n != 0) {
            val data = if (raw.isEmpty) null else raw.get(CookRegistry.DISH.get())
            seen.hot = data != null && data.day < DishData.UNDATED && stage == Stage.FRESH && Freshness.age(Freshness.today(level), data) == 0L
            if (seen.hot && state.near && Mix.unit(now.toInt(), pos.hashCode()) < 0.05f && StationFx.inRange(pos)) {
                CookParticleProviders.spawn(
                    CookParticles.STEAM.get(), pos.x + (8f + (Mix.unit(now.toInt(), 5) - 0.5f) * 4f) * Gfx.PX.toDouble(), pos.y + 3.2 * Gfx.PX,
                    pos.z + (8f + (Mix.unit(now.toInt(), 6) - 0.5f) * 4f) * Gfx.PX.toDouble(), 0.0, 0.011, 0.0, 1f, 1f, 1f, 0.6f, 0.55f,
                )
            }
        }
    }

    override fun draw(state: PlateState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val seen = state.seen ?: return
        val spot = CookGeo.PLATE
        val base = spot[1] + LIE

        // The dish on the plate, set down
        val model = seen.model
        if (model != null) {
            val age = (state.time - seen.since).toFloat()
            var y = base
            var tiltX = 0f
            var tiltZ = 0f
            var scale = 1f
            if (age < FALL + 24f) {
                if (age < FALL) {
                    val u = (age / FALL).coerceIn(0f, 1f)
                    y += 3.6f * (1f - u * u)
                    tiltX = 9f * (1f - u)
                    scale = 1f + 0.12f * (1f - u)
                } else {
                    val a = age - FALL
                    y += 0.9f * exp(-a / 2.6f) * abs(sin(a * 1.25f))
                    tiltX = 5f * exp(-a / 4f) * sin(a * 1.6f)
                    tiltZ = 4f * exp(-a / 4f) * cos(a * 1.3f)
                }
            }
            lay(state, poseStack, collector, model, spot[0], y, spot[2], state.yaw, tiltX, tiltZ, scale)
        }

        // The dish that was taken, lifting off toward whoever has it
        val gone = seen.gone
        if (gone != null) {
            val u = ((state.time - seen.goneAt).toFloat() / GONE_TICKS).coerceIn(0f, 1f)
            if (u < 1f) {
                val e = Gfx.smooth(u)
                val none = seen.goneDx == 0f && seen.goneDz == 0f
                val dx = if (none) 0f else seen.goneDx
                val dz = if (none) 0f else seen.goneDz
                lay(
                    state, poseStack, collector, gone, spot[0] + dx * 4.5f * e, base + 5.5f * e, spot[2] + dz * 4.5f * e, state.yaw + 200f * e,
                    14f * e, 0f, 1f - u * u,
                )
            }
        }

        if (seen.stage == Stage.GONE && seen.model != null && state.close) flies(state, poseStack, collector)
    }

    /** Lays [model] face up with its middle at ([x], [y], [z]) px, turned [yaw] degrees about the vertical and tipped, [scale] times its size (an item frame's picture is 8 px across). */
    private fun lay(
        state: PlateState, poseStack: PoseStack, collector: SubmitNodeCollector, model: ItemStackRenderState,
        x: Float, y: Float, z: Float, yaw: Float, tiltX: Float, tiltZ: Float, scale: Float,
    ) {
        if (scale < 0.02f) return
        poseStack.pushPose()
        Gfx.at(poseStack, x, y, z)
        poseStack.rotateDegrees(Axis.YP, yaw)
        if (tiltX != 0f) poseStack.rotateDegrees(Axis.XP, tiltX)
        if (tiltZ != 0f) poseStack.rotateDegrees(Axis.ZP, tiltZ)
        // An item frame's picture faces -z; turned a quarter about x it faces up
        poseStack.rotateDegrees(Axis.XP, 90f)
        poseStack.scale(scale, scale, scale)
        model.submit(poseStack, collector, state.light, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
    }

    /** Three flies circle a dish that has gone off, each on its own wandering path. */
    private fun flies(state: PlateState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val ph = (state.time % 100_000.0).toFloat()
        state.layer(collector, 2, poseStack, Gfx.SURFACE) { pen ->
            for (i in 0 until 3) {
                val a = ph * (0.31f + 0.07f * i) + i * 2.1f
                val x = 8f + 3.1f * sin(a) * cos(ph * 0.071f + i)
                val z = 8f + 2.9f * sin(a * 1.17f + 1f)
                val y = CookGeo.PLATE[1] + 2.8f + 0.9f * sin(ph * 0.53f + i * 1.7f)
                pen.line(x, y, y + 0.28f, z, 0.1f, Gfx.argb(0.9f, 0.08f, 0.07f, 0.06f))
            }
        }
    }

    companion object {
        /** A dish lies this far above the plate's floor (the picture is a slab half a pixel thick), px. */
        private const val LIE = 0.3f

        /** Ticks a dish takes to fall on to the plate, and to lift away from it. */
        private const val FALL = 3.5f
        private const val GONE_TICKS = 9f
    }
}
