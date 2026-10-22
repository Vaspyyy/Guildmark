package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.vessel.CookTool
import io.github.vaspyyy.guildmark.cooking.vessel.PrepBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationView
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.registries.BuiltInRegistries
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the items of a Prep Board's heap lie (living-world/cook/design.md 1.1, 11.1): a heap of up to twelve pictures in the box the
 * art leaves for it, five on the board, four on those and three on top, each turned and tipped its own way and darker the lower it lies.
 * A place is worked from the item's index and its kind alone, so it is the same from one frame to the next and from one client to the
 * next, and an item laid or chopped moves no other.
 */
object Heap {
    private val FIRST = intArrayOf(0, 5, 9)
    private val COUNT = intArrayOf(5, 4, 3)
    private val RADIUS = floatArrayOf(2.5f, 1.45f, 0.55f)
    private val LIFT = floatArrayOf(0.42f, 1.5f, 2.55f)
    private val TILT = floatArrayOf(0.3f, 0.5f, 0.65f)

    /** How much light each layer keeps (the lower the darker, a heap shades itself). */
    val SHADE = floatArrayOf(0.8f, 0.9f, 1f)

    /** A picture's half-size, px. */
    const val HALF = 1.55f

    fun layer(index: Int): Int = if (index < 5) 0 else if (index < 9) 1 else 2

    /** Writes into [out] the item's x, y, z (px, its middle), its yaw and its tilt about x and about z (radians). */
    fun place(index: Int, id: Int, out: FloatArray) {
        val i = index.coerceIn(0, 11)
        val layer = layer(i)
        val slot = i - FIRST[layer]
        val angle = Gfx.TAU * slot / COUNT[layer] + layer * 0.9f + Mix.unit(id, i + 3) * 0.5f
        val radius = RADIUS[layer] * (0.85f + 0.3f * Mix.unit(id, i * 5 + 1))
        out[0] = 8f + cos(angle) * radius
        out[1] = CookGeo.HEAP[1] + LIFT[layer]
        out[2] = 8.4f + sin(angle) * radius
        out[3] = Mix.unit(id, i * 3 + 5) * Gfx.TAU
        out[4] = (Mix.unit(id, i * 7 + 9) - 0.5f) * 2f * TILT[layer]
        out[5] = (Mix.unit(id, i * 11 + 13) - 0.5f) * 2f * TILT[layer]
    }
}

/**
 * The Prep Board seen (living-world/cook/design.md 1.1, 11.1): a chopping board with a heap on it and the Cook's Knife that chops it.
 *
 *  - what is laid on the board arcs in from the side of the cook who laid it, lands on the heap with a bounce and tips as it settles;
 *  - a chop brings the knife down on the piece on top (from the cook's side, so it faces whoever is chopping): it comes down
 *    faster and faster, bites, and rises over the next piece; the piece goes at the bite, with a few crumbs, hopping off toward the
 *    cook and shrinking away, and the pieces that are left jump a hair and settle;
 *  - taking the heap back sends every piece to the cook at once; an empty board draws nothing (the knife that lies on the counter is the shell's).
 */
class PrepBoardRenderer(context: BlockEntityRendererProvider.Context) : StationRenderer<PrepBoardBlockEntity>(context) {
    private val knifeId: Int = BuiltInRegistries.ITEM.getId(CookRegistry.TOOLS.getValue(CookTool.KNIFE).get())
    private val place = FloatArray(6)
    private val next = FloatArray(6)

    override fun draw(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val p = state.partial
        val t = anim.time(p)
        val n = min(anim.view.items.size, StationView.MAX_ITEMS)
        if (n > 0 || anim.leaving.isNotEmpty()) heap(state, anim, poseStack, collector, t, n)
        knife(state, anim, poseStack, collector, t, p)
    }

    private fun heap(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, n: Int) {
        val v = anim.view
        val level = state.level
        val sprites = Sprites(n + anim.leaving.size)
        val fly = StationAnim.PLOP_FLY.toFloat()
        // The knife's blow runs through what is left: each piece jumps a hair and settles, the upper ones more
        val sinceBite = (t - anim.chopAt).toFloat() - StationAnim.CHOP_BITE
        val jolt = if (sinceBite in 0f..14f) exp(-sinceBite / 3f) else 0f

        for (i in 0 until n) {
            val id = v.items[i]
            val flat = CookItems.flat(id, resolver, level) ?: continue
            Heap.place(i, id, place)
            var x = place[0]
            var y = place[1]
            var z = place[2]
            var yaw = place[3]
            var tiltX = place[4]
            var tiltZ = place[5]
            var half = Heap.HALF
            val layer = Heap.layer(i)
            if (jolt > 0.01f) y += 0.16f * jolt * (0.5f + layer) * abs(sin(sinceBite * 1.9f + i))
            var plop: Plop? = null
            for (q in anim.plops) {
                val best = plop
                if (q.index == i && (best == null || q.at > best.at)) plop = q
            }
            if (plop != null) {
                val age = (t - plop.at).toFloat()
                if (age < 0f) continue
                if (age < fly) {
                    // From the cook's side, over the board's edge, onto the heap
                    val u = age / fly
                    val sx = 8f + plop.dx * 9f
                    val sz = 8.4f + plop.dz * 9f
                    x = sx + (x - sx) * u
                    z = sz + (z - sz) * u
                    y += 6f * (1f - u) + 2.4f * sin(PI.toFloat() * u)
                    half *= (1.25f - 0.25f * u) * min(1f, 0.4f + 2.5f * u)
                    yaw += u * 6f
                    tiltX += 0.5f * (1f - u)
                } else {
                    // It lands with a bounce or two and rocks as it settles
                    val a = age - fly
                    y += 0.9f * exp(-a / 4f) * abs(sin(a * 0.8f))
                    tiltX += 0.12f * exp(-a / 6f) * sin(a * 0.9f)
                    tiltZ += 0.1f * exp(-a / 6f) * cos(a * 0.8f)
                }
            }
            val s = Heap.SHADE[layer]
            sprites.add(flat, x, y, z, yaw, tiltX, tiltZ, half, Gfx.argb(1f, s, s, s))
        }

        // What is going: a chopped piece waits for the knife and then hops off toward the cook; a heap taken back goes at once
        for (l in anim.leaving) {
            val flat = CookItems.flat(l.item, resolver, level) ?: continue
            Heap.place(l.index, l.item, place)
            val s = Heap.SHADE[Heap.layer(l.index)]
            val age = (t - l.at).toFloat()
            val wait = if (l.at == anim.chopAt) StationAnim.CHOP_BITE else 0f
            if (age < wait) {
                sprites.add(flat, place[0], place[1], place[2], place[3], place[4], place[5], Heap.HALF, Gfx.argb(1f, s, s, s))
                continue
            }
            val u = ((age - wait) / StationAnim.CHOP_FLIGHT).coerceIn(0f, 1f)
            val none = l.x == 0f && l.z == 0f
            val dx = l.x
            val dz = if (none) -1f else l.z
            sprites.add(
                flat, place[0] + dx * 10f * u, place[1] + 4.5f * sin(PI.toFloat() * u) + 2f * u, place[2] + dz * 10f * u,
                place[3] + u * 5f, place[4] * (1f - u) + 0.6f * u, place[5] * (1f - u), Heap.HALF * (1f - u * u) * (1f + 0.25f * sin(PI.toFloat() * u)),
                Gfx.argb(1f, s, s, s),
            )
        }
        sprites.submit(state, collector, poseStack)
    }

    /**
     * The Cook's Knife at work, held over the heap by the cook's hand: pivoting about its grip, it comes down faster and faster to bite
     * the piece on top at [StationAnim.CHOP_BITE] ticks into the stroke, gives a hair, and rises over the next piece in the time that is
     * left of the stroke (a chop a stroke long and strokes follow at the chop's beat, so it never jumps). It is brought in when the
     * chopping begins and goes when it stops, and it faces the side the cook chopped from.
     */
    private fun knife(state: StationState, anim: StationAnim, poseStack: PoseStack, collector: SubmitNodeCollector, t: Double, p: Float) {
        val presence = Gfx.smooth(anim.swirl.at(p) * 1.5f)
        if (presence < 0.02f) return
        val item = CookItems.state(knifeId, resolver, state.level) ?: return
        val bite = StationAnim.CHOP_BITE
        val stroke = StationAnim.CHOP_STROKE
        val age = (t - anim.chopAt).toFloat().coerceIn(0f, stroke)
        val rise = if (age > bite + 1f) Gfx.smooth((age - bite - 1f) / (stroke - bite - 1f)) else 0f
        val down = when {
            age < bite -> {
                val u = age / bite
                u * u
            }
            age < bite + 1f -> 1f - 0.12f * sin(PI.toFloat() * (age - bite))
            else -> 1f - rise
        }

        // Over the piece it bites, and, as it rises, over the one it bites next (or the middle of the board when there is none)
        Heap.place(anim.chopIndex, anim.chopItem, place)
        val n = min(anim.view.items.size, StationView.MAX_ITEMS)
        if (n > 0) Heap.place(n - 1, anim.view.items[n - 1], next) else {
            next[0] = 8f
            next[1] = CookGeo.HEAP[1]
            next[2] = 8.4f
        }
        val x = Gfx.mix(place[0], next[0], rise)
        val z = Gfx.mix(place[2], next[2], rise)
        val tipY = Gfx.mix(place[1], next[1], rise) + 3.6f * (1f - down) + 3f * (1f - presence) - 0.15f * down

        val none = anim.chopDx == 0f && anim.chopDz == 0f
        val dx = anim.chopDx
        val dz = if (none) -1f else anim.chopDz
        val yaw =Math.toDegrees(atan2((-dx).toDouble(), (-dz).toDouble())).toFloat()

        // The blade swings from a slant to nearly upright as it comes down; the hand is where that puts the grip, the tip on the piece
        val f = KNIFE_SCALE * (0.4f + 0.6f * presence)
        val phi = Math.toRadians(-25.0 - 40.0 * down)
        val len = KNIFE_LEN * f
        val grip = KNIFE_GRIP * f
        poseStack.pushPose()
        Gfx.at(poseStack, x, tipY, z)
        poseStack.rotateDegrees(Axis.YP, yaw)
        Gfx.at(poseStack, (len * cos(phi)).toFloat(), (-len * sin(phi)).toFloat(), 0f)
        poseStack.rotateDegrees(Axis.ZP, (45.0 - Math.toDegrees(phi)).toFloat())
        Gfx.at(poseStack, -grip, grip, 0f)
        poseStack.scale(f, f, f)
        item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
    }

    companion object {
        /** The knife's picture is this many times what an item frame shows, and from its grip to its tip it is [KNIFE_LEN] px of that (grip to the picture's middle [KNIFE_GRIP]), by the sprite's own layout. */
        private const val KNIFE_SCALE = 0.75f
        private const val KNIFE_LEN = 7.07f
        private const val KNIFE_GRIP = 2.25f
    }
}
