package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlock
import io.github.vaspyyy.guildmark.cooking.dish.LarderBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap

/** One of the three places a larder shows a dish through its slats: the dish, its model, and how much of it is there (it grows in and shrinks out). */
class Shelf {
    var stack: ItemStack = ItemStack.EMPTY
    var model: ItemStackRenderState? = null
    val grow = Ease()
}

/** What a larder remembers from one frame to the next: its door, what is on its shelves and whether the door was open at the last tick. */
class LarderSeen {
    val ticker = Ticker()
    val door = Ease()
    var open = false
    val shelves = Array(LarderBlockEntity.SHOWN_MAX) { Shelf() }
    var generation = -1
}

class LarderState : SceneState() {
    var seen: LarderSeen? = null

    /** Whether the block says the door is open (then the shell has none and the renderer's is the only one). */
    var open = false
}

/**
 * The Larder seen (living-world/cook/design.md 1.1, 11.1): a slatted cupboard whose door swings and through whose slats the first
 * three kinds of dish it holds can be seen.
 *
 *  - the door is the piece M1 drew, on its hinge: when a player opens the chest it swings out (a spring that goes a hair past its stop
 *    and rocks back) and, when the last one has gone, swings to again, fading as it meets the shut door that the shell has by then;
 *  - what stands on the shelves is the dish as it looks today (the server sends the looks), small, upright, each shelf's on its own:
 *    a kind that comes pops in and a kind that goes shrinks out, and the dishes shift on their shelves as the door is flung;
 *  - opening it lets out a breath of cool air, three pale wisps.
 */
class LarderRenderer(context: BlockEntityRendererProvider.Context) : SceneRenderer<LarderBlockEntity, LarderState>(context) {
    private val memory = WeakHashMap<LarderBlockEntity, LarderSeen>()

    override fun createRenderState(): LarderState = LarderState()

    override fun extract(be: LarderBlockEntity, state: LarderState) {
        val level = state.level ?: return
        val block = be.blockState
        val open = block.hasProperty(LarderBlock.OPEN) && block.getValue(LarderBlock.OPEN)
        state.open = open
        val seen = memory.getOrPut(be) { LarderSeen() }
        state.seen = seen
        val n = seen.ticker.due(level.gameTime)
        val remade = seen.generation != CookItems.generation
        if (n == 0 && !remade) return
        val shown = be.shown
        if (n < 0 || remade) {
            // First sight (or the pictures were made again): everything is where it is
            seen.door.snap(if (open) 1f else 0f)
            seen.open = open
            for (i in seen.shelves.indices) {
                val shelf = seen.shelves[i]
                val want = shown.getOrNull(i) ?: ItemStack.EMPTY
                shelf.stack = want
                shelf.model = CookItems.stateOf(want, resolver, level, i)
                shelf.grow.snap(if (want.isEmpty) 0f else 1f)
            }
            seen.generation = CookItems.generation
            if (n <= 0) return
        }
        repeat(n.coerceAtLeast(0)) {
            seen.door.begin()
            seen.door.swing(if (open) 1f else 0f, 0.3f, 0.56f, 0f, 1.04f, 0.3f)
            for (i in seen.shelves.indices) {
                val shelf = seen.shelves[i]
                shelf.grow.begin()
                val want = shown.getOrNull(i) ?: ItemStack.EMPTY
                if (!ItemStack.matches(want, shelf.stack)) {
                    // What stood there shrinks out, and then the new one grows in
                    shelf.grow.ease(0f, 0.4f)
                    if (shelf.grow.cur < 0.05f) {
                        shelf.stack = want
                        shelf.model = CookItems.stateOf(want, resolver, level, i)
                        shelf.grow.snap(0f)
                    }
                } else if (shelf.stack.isEmpty) {
                    shelf.grow.set(0f)
                } else {
                    shelf.grow.spring(1f, 0.3f, 0.62f)
                }
            }
            if (open && !seen.open) breath(be, state)
            seen.open = open
        }
    }

    /** The door has just come open: three pale wisps of cool air from the gap, drifting out. */
    private fun breath(be: LarderBlockEntity, state: LarderState) {
        val pos = be.blockPos
        if (!state.near || !StationFx.inRange(pos)) return
        for (i in 0 until 3) {
            val x = 5.5f + 5f * Mix.unit(i, pos.hashCode())
            val y = 4f + 8f * Mix.unit(i + 3, pos.hashCode())
            CookParticleProviders.spawn(
                CookParticles.STEAM.get(), pos.x + x * Gfx.PX.toDouble(), pos.y + y * Gfx.PX.toDouble(), pos.z + 3.2 * Gfx.PX,
                0.0, 0.003, -0.012, 0.78f, 0.88f, 1f, 0.7f, 0.4f,
            )
        }
    }

    override fun draw(state: LarderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val seen = state.seen ?: return
        val p = state.partial

        // The door, on its hinge. Open, the shell has none; shut, it has one already, and a door still swinging shut fades out as it meets it
        val d = seen.door.at(p).coerceIn(0f, 1.06f)
        if (d > 0.004f) {
            poseStack.pushPose()
            Gfx.at(poseStack, CookGeo.LARDER_HINGE_X, 0f, CookGeo.LARDER_HINGE_Z)
            poseStack.rotateDegrees(Axis.YP, d * CookGeo.LARDER_MAX)
            Gfx.at(poseStack, -CookGeo.LARDER_HINGE_X, 0f, -CookGeo.LARDER_HINGE_Z)
            if (state.open) CookPieces.solid(collector, poseStack, CookPieces.LARDER_DOOR, state.light)
            else CookPieces.faded(collector, poseStack, CookPieces.LARDER_DOOR, state.light, Gfx.smooth(d * 4f), 1)
            poseStack.popPose()
        }

        // What stands on the shelves, seen through the slats; the dishes shift as the door is flung
        val flung = seen.door.speed()
        for (i in seen.shelves.indices) {
            val shelf = seen.shelves[i]
            val model = shelf.model ?: continue
            val g = shelf.grow.at(p)
            if (g < 0.02f) continue
            val spot = CookGeo.LARDER_DISHES[i]
            val shift = (flung * 40f * (if (i % 2 == 0) 1f else -1f)).coerceIn(-9f, 9f)
            poseStack.pushPose()
            Gfx.at(poseStack, spot[0], spot[1] + DISH * 0.5f * g, spot[2])
            poseStack.rotateDegrees(Axis.YP, shift)
            val s = DISH / 8f * g
            poseStack.scale(s, s, s)
            model.submit(poseStack, collector, state.light, OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }
    }

    companion object {
        /** A dish seen through the slats is this many px across: the shelves are 4.3 apart. */
        private const val DISH = 4f
    }
}
