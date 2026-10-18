package io.github.vaspyyy.guildmark.client.festival

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalOrnamentBlockEntity
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.OrnamentData
import io.github.vaspyyy.guildmark.festival.OrnamentKind
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a festival ornament needs for one frame. */
class FestivalOrnamentRenderState : BlockEntityRenderState() {
    var data: OrnamentData = OrnamentData(OrnamentKind.POST, FestivalKind.RIBBONWAKE)
    /** Game time with the frame's fraction, in ticks. */
    var time = 0.0
    /** Something of its own, so no two ornaments stir together. */
    var seed = 0.0
    /** How dark the sky is: 0 by day, 1 at night. */
    var dark = 0f
    /** Its festival's evening, and whether the band is playing. */
    var dusk = false
    var playing = false
    /** The ribbon pole: where the dancers' hands holding its ribbons are (from the block's corner), and whose. */
    var hands = 0
    val hand = DoubleArray(MAX_HANDS * 3)
    val holder = IntArray(MAX_HANDS)
    /** The light along its string of pennants and its garland, a few samples each. */
    val stringLight = IntArray(SAMPLES)
    val garlandLight = IntArray(SAMPLES)

    companion object {
        const val MAX_HANDS = 12
        const val SAMPLES = 4
    }
}

/**
 * The festival's ornaments, drawn and animated here (the block has no model): every part of them stirs in a breeze
 * that comes and goes, each in its own time.
 *
 * - A post: a lantern standing on top (glass and iron, or the Lanternrise's paper ones, with the riddle's slip tied on),
 *   a banner hanging on its inner side that ripples, a string of pennants up to the centrepiece that sags and flutters
 *   (the Lanternrise strings little paper lanterns instead), and a garland on to the next post (blossom, marigolds,
 *   autumn leaves, or holly with twinkling lights).
 * - The centrepiece's crown: the Ribbonwake's flower crown with a ribbon down to each dancer's hand, following them
 *   round as they weave (and hanging down the pole between dances); the Sunhearth's sun wheel, turning, its streamers
 *   going round with it, burning at dusk; the Sheafmoot's crown of sheaves with its band and streamers; the
 *   Lanternrise's star over a ring of hanging lanterns.
 * - A hunt ribbon: a bow on a little stake, its tails streaming, with now and then a glint to catch the eye.
 */
class FestivalOrnamentRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<FestivalOrnamentBlockEntity, FestivalOrnamentRenderState> {

    override fun createRenderState(): FestivalOrnamentRenderState = FestivalOrnamentRenderState()

    override fun extractRenderState(
        be: FestivalOrnamentBlockEntity, state: FestivalOrnamentRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val data = be.data
        state.data = data
        val level = be.level ?: return
        val pos = state.blockPos
        state.time = level.gameTime + partialTicks.toDouble()
        state.seed = ((pos.asLong() * -7046029254386353131L) ushr 44).toDouble() / 4096.0 * 2 * PI
        state.dark = (level.skyDarken / 11f).coerceIn(0f, 1f)
        val site = FestivalClient.nearest(pos.x + 0.5, pos.z + 0.5, 24.0)
        state.dusk = site?.phase == FestivalPhase.DUSK
        state.playing = (site?.setAge() ?: -1L) >= 0
        sample(level, pos, data.string, -0.02, state.stringLight)
        sample(level, pos, data.garland, -0.02, state.garlandLight)
        state.hands = 0
        if (data.kind == OrnamentKind.CROWN && data.festival == FestivalKind.RIBBONWAKE && state.playing && data.ring > 0f) hands(level, pos, data, partialTicks, state)
    }

    /** The light at a few points along a line from the block's middle by [to], for what hangs along it. */
    private fun sample(level: Level, pos: BlockPos, to: Vec3?, dy: Double, out: IntArray) {
        if (to == null) return
        for (i in out.indices) {
            val t = (i + 0.5) / out.size
            out[i] = LightCoordsUtil.getLightCoords(level, BlockPos.containing(pos.x + 0.5 + to.x * t, pos.y + 0.5 + dy + to.y * t - 0.3, pos.z + 0.5 + to.z * t))
        }
    }

    /** Where the hands of those dancing round the pole are, each holding the end of a ribbon. */
    private fun hands(level: Level, pos: BlockPos, data: OrnamentData, partialTicks: Float, state: FestivalOrnamentRenderState) {
        val cx = pos.x + 0.5
        val cz = pos.z + 0.5
        val floor = pos.y + 0.5 - data.drop
        val r = data.ring.toDouble()
        val box = AABB(cx - r - 2, floor - 1.5, cz - r - 2, cx + r + 2, floor + 3.0, cz + r + 2)
        for (p in level.getEntitiesOfClass(Townsperson::class.java, box)) {
            if (state.hands >= FestivalOrnamentRenderState.MAX_HANDS) break
            val at = p.getPosition(partialTicks)
            val d = hypot(at.x - cx, at.z - cz)
            if (abs(d - r) > 1.3) continue
            val act = p.shownActivity
            val yaw = Mth.rotLerp(partialTicks, p.yBodyRotO, p.yBodyRot) * (PI / 180.0)
            val fx = -sin(yaw)
            val fz = cos(yaw)
            val rx = -cos(yaw)
            val rz = -sin(yaw)
            val side = when (act) {
                Activity.RING_STEP -> 1.0
                Activity.RING_STEP_L -> -1.0
                // The hand on the pole's side
                else -> if ((at.x + rx - cx).let { it * it } + (at.z + rz - cz).let { it * it } < (at.x - rx - cx).let { it * it } + (at.z - rz - cz).let { it * it }) 1.0 else -1.0
            }
            val up = when (act) {
                Activity.RING_STEP, Activity.RING_STEP_L -> 1.45
                Activity.RING_IN -> 1.55
                Activity.TWIRL -> 1.7
                Activity.HURRAH -> 1.85
                Activity.BOW -> 0.95
                else -> 1.25
            }
            val k = state.hands * 3
            state.hand[k] = at.x + rx * 0.36 * side + fx * 0.12 - pos.x
            state.hand[k + 1] = at.y + up - pos.y
            state.hand[k + 2] = at.z + rz * 0.36 * side + fz * 0.12 - pos.z
            state.holder[state.hands] = p.id
            state.hands++
        }
    }

    override fun submit(state: FestivalOrnamentRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val eye = V(camera.pos.x - state.blockPos.x, camera.pos.y - state.blockPos.y, camera.pos.z - state.blockPos.z)
        val solid = Geo()
        val glow = Geo()
        val draw = Draw(state, eye, solid, glow)
        when (state.data.kind) {
            OrnamentKind.POST, OrnamentKind.RIDDLE -> draw.post()
            OrnamentKind.CROWN -> draw.crown()
            OrnamentKind.HUNT_RIBBON -> draw.huntRibbon()
        }
        if (solid.count > 0) collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(ATLAS)) { pose, buffer -> solid.emit(buffer, pose, false) }
        if (glow.count > 0) collector.submitCustomGeometry(poseStack, RenderTypes.eyes(ATLAS)) { pose, buffer -> glow.emit(buffer, pose, true) }
    }

    override fun getRenderBoundingBox(be: FestivalOrnamentBlockEntity): AABB {
        val data = be.data
        var box = AABB(be.blockPos).inflate(0.5)
        data.string?.let { box = box.expandTowards(it) }
        data.garland?.let { box = box.expandTowards(it).expandTowards(0.0, -1.5, 0.0) }
        if (data.banner) box = box.expandTowards(0.0, -1.8, 0.0)
        if (data.kind == OrnamentKind.CROWN) {
            val r = max(data.ring.toDouble(), WHEEL_RADIUS) + 2.0
            box = box.inflate(r, 0.0, r).expandTowards(0.0, -data.drop.toDouble() - 1.0, 0.0).expandTowards(0.0, 2.5, 0.0)
        }
        return box
    }

    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = 128

    // =============================================================================================== drawing

    /** A point or a direction, in blocks from the block's corner. */
    private data class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = V(x * k, y * k, z * k)
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun length() = sqrt(x * x + y * y + z * z)
        fun unit(): V {
            val l = length()
            return if (l < 1e-9) V(0.0, 1.0, 0.0) else V(x / l, y / l, z / l)
        }
        fun flat(): V = V(x, 0.0, z)
    }

    /** Vertices waiting to be drawn: position, texture, colour and light. */
    private class Geo {
        private var data = FloatArray(7 * 256)
        var count = 0
            private set

        fun vertex(p: V, u: Float, v: Float, color: Int, light: Int) {
            if ((count + 1) * 7 > data.size) data = data.copyOf(data.size * 2)
            val i = count * 7
            data[i] = p.x.toFloat()
            data[i + 1] = p.y.toFloat()
            data[i + 2] = p.z.toFloat()
            data[i + 3] = u
            data[i + 4] = v
            data[i + 5] = Float.fromBits(color)
            data[i + 6] = Float.fromBits(light)
            count++
        }

        /** Every four vertices are a quad; [both] draws each from behind as well (for a pass that culls). */
        fun emit(buffer: VertexConsumer, pose: PoseStack.Pose, both: Boolean) {
            var q = 0
            while (q + 3 < count) {
                for (k in 0 until 4) put(buffer, pose, q + k)
                if (both) for (k in 3 downTo 0) put(buffer, pose, q + k)
                q += 4
            }
        }

        private fun put(buffer: VertexConsumer, pose: PoseStack.Pose, n: Int) {
            val i = n * 7
            buffer.addVertex(pose, data[i], data[i + 1], data[i + 2]).setColor(data[i + 5].toRawBits()).setUv(data[i + 3], data[i + 4])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(data[i + 6].toRawBits()).setNormal(pose, 0f, 1f, 0f)
        }
    }

    /** A rectangle of the atlas, in pixels. */
    private class Uv(x0: Int, y0: Int, x1: Int, y1: Int) {
        val u0 = x0 / SIZE
        val v0 = y0 / SIZE
        val u1 = x1 / SIZE
        val v1 = y1 / SIZE
        fun u(t: Double): Float = (u0 + (u1 - u0) * t).toFloat()
        fun v(t: Double): Float = (v0 + (v1 - v0) * t).toFloat()
    }

    /** One frame's drawing of one ornament. */
    private class Draw(val s: FestivalOrnamentRenderState, val eye: V, val solid: Geo, val glow: Geo) {
        val data = s.data
        val kind = data.festival
        val t = s.time
        val light = s.lightCoords
        /** The breeze: comes and goes, the same for every ornament (each stirs in it in its own time). */
        val wind = 0.55 + 0.3 * sin(t * 0.011) + 0.15 * sin(t * 0.037 + 1.3)
        val windDir = V(cos(0.6 + 0.4 * sin(t * 0.0021)), 0.0, sin(0.6 + 0.4 * sin(t * 0.0021)))
        /** How strongly the lanterns' glow shows: faint by day, full at night. */
        val glowing = 0.25f + 0.75f * s.dark

        // ---- Primitives ----

        fun quad(g: Geo, a: V, b: V, c: V, d: V, uv: Uv, color: Int, l: Int) {
            g.vertex(a, uv.u0, uv.v0, color, l)
            g.vertex(b, uv.u1, uv.v0, color, l)
            g.vertex(c, uv.u1, uv.v1, color, l)
            g.vertex(d, uv.u0, uv.v1, color, l)
        }

        fun tri(g: Geo, a: V, b: V, tip: V, uv: Uv, color: Int, l: Int) {
            g.vertex(a, uv.u0, uv.v0, color, l)
            g.vertex(b, uv.u1, uv.v0, color, l)
            g.vertex(tip, uv.u(0.5), uv.v1, color, l)
            g.vertex(tip, uv.u(0.5), uv.v1, color, l)
        }

        /** An upright box, every side of it (and its top and bottom) from [side]. */
        fun box(g: Geo, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, side: Uv, color: Int, l: Int, top: Uv = side) {
            val a = V(x0, y0, z0)
            val b = V(x1, y0, z0)
            val c = V(x1, y0, z1)
            val d = V(x0, y0, z1)
            val e = V(x0, y1, z0)
            val f = V(x1, y1, z0)
            val gg = V(x1, y1, z1)
            val h = V(x0, y1, z1)
            quad(g, e, f, b, a, side, color, l)
            quad(g, f, gg, c, b, side, color, l)
            quad(g, gg, h, d, c, side, color, l)
            quad(g, h, e, a, d, side, color, l)
            quad(g, h, gg, f, e, top, color, l)
            quad(g, a, b, c, d, top, color, l)
        }

        /** A square of [size] facing the eye, turned [roll] in its plane. */
        fun billboard(g: Geo, at: V, size: Double, uv: Uv, color: Int, l: Int, roll: Double = 0.0) {
            val toEye = (eye - at).unit()
            var right = V(0.0, 1.0, 0.0).cross(toEye)
            if (right.length() < 1e-4) right = V(1.0, 0.0, 0.0)
            right = right.unit()
            val up = toEye.cross(right).unit()
            val h = size / 2
            val c = cos(roll)
            val sn = sin(roll)
            val r = right * c + up * sn
            val u = up * c - right * sn
            quad(g, at - r * h + u * h, at + r * h + u * h, at + r * h - u * h, at - r * h - u * h, uv, color, l)
        }

        /** Two upright cards crossing at [at] (bottom middle), turned [angle], [w] wide and [h] tall. */
        fun crossed(g: Geo, at: V, w: Double, h: Double, angle: Double, uv: Uv, color: Int, l: Int, lean: V = V(0.0, 0.0, 0.0)) {
            for (k in 0 until 2) {
                val a = angle + k * PI / 2
                val d = V(cos(a) * w / 2, 0.0, sin(a) * w / 2)
                val top = at + V(0.0, h, 0.0) + lean
                quad(g, top - d, top + d, at + d, at - d, uv, color, l)
            }
        }

        /** A flat strip along [points], [width] across, turned to face the eye, its texture running along it. */
        fun strip(g: Geo, points: List<V>, width: Double, uv: Uv, color: Int, lights: IntArray?, l: Int, repeat: Double = 1.0) {
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val dir = p1 - p0
                val len = dir.length()
                val mid = (p0 + p1) * 0.5
                var w = dir.cross(eye - mid)
                if (w.length() < 1e-6) w = V(1.0, 0.0, 0.0)
                w = w.unit() * (width / 2)
                // Each length starts the texture afresh (an atlas can't repeat): the cord and ribbon run the same all along
                val u0 = uv.u0
                val u1 = uv.u(min(1.0, len / repeat))
                val lit = lights?.get(min(lights.size - 1, (i * lights.size) / max(1, points.size - 1))) ?: l
                g.vertex(p0 - w, u0, uv.v0, color, lit)
                g.vertex(p1 - w, u1, uv.v0, color, lit)
                g.vertex(p1 + w, u1, uv.v1, color, lit)
                g.vertex(p0 + w, u0, uv.v1, color, lit)
            }
        }

        /** A line from [a] to [b] sagging [sag] in its middle and blown a little sideways by the breeze. */
        fun hang(a: V, b: V, sag: Double, sway: Double, phase: Double): (Double) -> V {
            val dir = (b - a).flat()
            val across = if (dir.length() < 1e-6) V(1.0, 0.0, 0.0) else V(-dir.z, 0.0, dir.x).unit()
            val push = (across.x * windDir.x + across.z * windDir.z).let { if (it >= 0) 1.0 else -1.0 }
            val blow = sway * (0.6 * wind + 0.4 * sin(t * 0.05 + phase)) * push
            return { f ->
                val bend = 4 * f * (1 - f)
                a + (b - a) * f - V(0.0, sag * bend, 0.0) + across * (blow * bend)
            }
        }

        fun lightAt(lights: IntArray, f: Double): Int = lights[min(lights.size - 1, (f * lights.size).toInt().coerceAtLeast(0))]

        // ---- A lantern ----

        /**
         * A lantern standing with its base at [base]: an iron foot and cap, glass (or paper) lit from within, a
         * flame flickering in it and a glow round it that shows more as it gets dark.
         */
        fun lantern(base: V, size: Double, tint: Int, paper: Boolean, l: Int) {
            val w = 0.14 * size
            val bottom = base.y + 0.03 * size
            val top = base.y + 0.42 * size
            val x = base.x
            val z = base.z
            // Foot and cap
            box(solid, x - w - 0.02 * size, base.y, z - w - 0.02 * size, x + w + 0.02 * size, bottom, z + w + 0.02 * size, IRON, -1, l)
            if (paper) {
                // A round paper lantern: eight ribbed panels, fat in the middle
                val n = 8
                for (k in 0 until n) {
                    val a0 = k * 2 * PI / n
                    val a1 = (k + 1) * 2 * PI / n
                    val rows = 3
                    for (r in 0 until rows) {
                        val f0 = r.toDouble() / rows
                        val f1 = (r + 1).toDouble() / rows
                        val r0 = w * (0.75 + 0.55 * sin(PI * f0))
                        val r1 = w * (0.75 + 0.55 * sin(PI * f1))
                        val y0 = bottom + (top - bottom) * f0
                        val y1 = bottom + (top - bottom) * f1
                        val p00 = V(x + cos(a0) * r0, y0, z + sin(a0) * r0)
                        val p10 = V(x + cos(a1) * r0, y0, z + sin(a1) * r0)
                        val p01 = V(x + cos(a0) * r1, y1, z + sin(a0) * r1)
                        val p11 = V(x + cos(a1) * r1, y1, z + sin(a1) * r1)
                        solid.vertex(p01, PAPER.u0, PAPER.v(1 - f1), tint, LightCoordsUtil.FULL_BRIGHT)
                        solid.vertex(p11, PAPER.u1, PAPER.v(1 - f1), tint, LightCoordsUtil.FULL_BRIGHT)
                        solid.vertex(p10, PAPER.u1, PAPER.v(1 - f0), tint, LightCoordsUtil.FULL_BRIGHT)
                        solid.vertex(p00, PAPER.u0, PAPER.v(1 - f0), tint, LightCoordsUtil.FULL_BRIGHT)
                    }
                }
            } else {
                box(solid, x - w, bottom, z - w, x + w, top, z + w, GLASS, tint, LightCoordsUtil.FULL_BRIGHT, top = IRON)
            }
            // The cap: a low pyramid, and the ring to hang it by
            val capW = w + 0.03 * size
            val peak = V(x, top + 0.12 * size, z)
            val c = listOf(V(x - capW, top, z - capW), V(x + capW, top, z - capW), V(x + capW, top, z + capW), V(x - capW, top, z + capW))
            for (k in 0 until 4) tri(solid, c[(k + 1) % 4], c[k], peak, IRON, -1, l)
            crossed(solid, peak, 0.09 * size, 0.08 * size, PI / 4, IRON, -1, l)
            // The flame and the glow
            val mid = V(x, (bottom + top) / 2, z)
            val flicker = 0.85 + 0.15 * sin(t * 0.9 + s.seed) * sin(t * 1.37 + s.seed * 2)
            billboard(glow, mid + V(0.0, -0.02 * size, 0.0), 0.16 * size * flicker, FLAME, ARGB.color((0.95f * flicker).toFloat().coerceIn(0f, 1f), 0xFFE6B0), LightCoordsUtil.FULL_BRIGHT)
            val halo = (glowing * (0.55 + 0.1 * flicker)).toFloat()
            billboard(glow, mid, 1.15 * size, HALO, ARGB.color(halo.coerceIn(0f, 1f), tint), LightCoordsUtil.FULL_BRIGHT)
        }

        // ---- A post ----

        fun post() {
            val riddle = data.kind == OrnamentKind.RIDDLE
            val paper = kind == FestivalKind.LANTERNRISE
            val anchor = V(0.5, -0.04, 0.5)
            // Which way is in (toward the centrepiece), from the string, or else the garland
            val inward = data.string?.let { V(it.x, 0.0, it.z).unit() }
                ?: data.garland?.let { V(-it.z, 0.0, it.x).unit() } ?: V(1.0, 0.0, 0.0)
            val tint = (if (paper) kind.color(data.index * 2) else LANTERN_TINT[kind.ordinal]).opaque()
            lantern(V(0.5, 0.0, 0.5), if (riddle) 1.25 else 1.0, tint, paper, light)
            if (riddle) riddleSlip(inward)
            data.string?.let { pennants(anchor, V(0.5, 0.1, 0.5) + V(it.x, it.y, it.z)) }
            data.garland?.let { garland(anchor, anchor + V(it.x, it.y, it.z)) }
            if (data.banner) banner(inward)
        }

        /** The riddle's paper slip, tied to the lantern's foot, turning in the breeze. */
        fun riddleSlip(inward: V) {
            val side = V(-inward.z, 0.0, inward.x)
            val tie = V(0.5, 0.02, 0.5) + side * 0.2
            val swing = 0.35 * sin(t * 0.08 + s.seed) + 0.25 * wind
            val turn = 0.6 * sin(t * 0.031 + s.seed)
            val face = (inward * cos(turn) + side * sin(turn)).unit()
            val across = V(-face.z, 0.0, face.x) * 0.085
            val down = (V(0.0, -1.0, 0.0) * cos(swing) + face * sin(swing)).unit()
            val top = tie + down * 0.05
            val bottom = top + down * 0.24
            strip(solid, listOf(tie, top), 0.012, ROPE, 0xD8C8A0.opaque(), null, light)
            quad(solid, top - across, top + across, bottom + across, bottom - across, TAG, -1, light)
        }

        /** The string of pennants (or, at the Lanternrise, of little lanterns) up to the centrepiece. */
        fun pennants(a: V, b: V) {
            val len = (b - a).length()
            val line = hang(a, b, 0.05 * len + 0.1, 0.12, s.seed)
            val n = max(4, ceil(len / 0.5).toInt())
            val points = (0..n).map { line(it.toDouble() / n) }
            strip(solid, points, 0.022, ROPE, 0xC8B89A.opaque(), s.stringLight, light, repeat = 0.5)
            if (kind == FestivalKind.LANTERNRISE) {
                // Little paper lanterns hung along it, each on its own short drop, swinging
                val count = max(2, (len / 1.3).toInt())
                for (k in 0 until count) {
                    val f = (k + 0.5) / count
                    val top = line(f)
                    val swing = 0.25 * sin(t * 0.07 + k * 1.3 + s.seed) * wind
                    val hangAt = top + V(sin(swing) * 0.1, -0.16, cos(swing) * 0.04)
                    strip(solid, listOf(top, hangAt), 0.012, ROPE, 0x403020.opaque(), null, lightAt(s.stringLight, f))
                    smallLantern(hangAt, 0.42, kind.color(k + data.index).opaque())
                }
                return
            }
            // Pennants: triangles hung from the line, every one fluttering on its own
            val count = max(3, (len / 0.52).toInt())
            for (k in 0 until count) {
                val f0 = (k + 0.12) / count
                val f1 = (k + 0.88) / count
                val p0 = line(f0)
                val p1 = line(f1)
                val tangent = (p1 - p0).unit()
                val across = tangent.cross(V(0.0, 1.0, 0.0)).let { if (it.length() < 1e-6) V(1.0, 0.0, 0.0) else it.unit() }
                val flutter = (0.22 + 0.25 * wind) * sin(t * 0.21 + k * 0.83 + s.seed) + 0.25 * wind *
                    (across.x * windDir.x + across.z * windDir.z)
                val down = V(0.0, -1.0, 0.0) * cos(flutter) + across * sin(flutter)
                val tip = (p0 + p1) * 0.5 + down * 0.3
                val color = PENNANT_COLORS[kind.ordinal].let { it[Math.floorMod(k + data.index, it.size)] }
                tri(solid, p0, p1, tip, CLOTH, color.opaque(), lightAt(s.stringLight, (f0 + f1) / 2))
            }
        }

        /** A small paper lantern hanging from [top], glowing. */
        fun smallLantern(top: V, size: Double, tint: Int) {
            val h = 0.3 * size
            val w = 0.12 * size
            val mid = top + V(0.0, -h / 2, 0.0)
            for (k in 0 until 6) {
                val a0 = k * PI / 3
                val a1 = (k + 1) * PI / 3
                val bulge = w * 1.25
                val pTop0 = top + V(cos(a0) * w, 0.0, sin(a0) * w)
                val pTop1 = top + V(cos(a1) * w, 0.0, sin(a1) * w)
                val pMid0 = mid + V(cos(a0) * bulge, 0.0, sin(a0) * bulge)
                val pMid1 = mid + V(cos(a1) * bulge, 0.0, sin(a1) * bulge)
                val pBot0 = top + V(cos(a0) * w, -h, sin(a0) * w)
                val pBot1 = top + V(cos(a1) * w, -h, sin(a1) * w)
                solid.vertex(pTop0, PAPER.u0, PAPER.v0, tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pTop1, PAPER.u1, PAPER.v0, tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pMid1, PAPER.u1, PAPER.v(0.5), tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pMid0, PAPER.u0, PAPER.v(0.5), tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pMid0, PAPER.u0, PAPER.v(0.5), tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pMid1, PAPER.u1, PAPER.v(0.5), tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pBot1, PAPER.u1, PAPER.v1, tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
                solid.vertex(pBot0, PAPER.u0, PAPER.v1, tint.opaque(), LightCoordsUtil.FULL_BRIGHT)
            }
            val flicker = 0.85 + 0.15 * sin(t * 1.1 + top.x * 7 + top.z * 5)
            billboard(glow, mid, 0.85 * size, HALO, ARGB.color((glowing * 0.6 * flicker).toFloat().coerceIn(0f, 1f), tint), LightCoordsUtil.FULL_BRIGHT)
        }

        /** The garland on to the next post: a heavier rope dressed in the festival's flowers or leaves. */
        fun garland(a: V, b: V) {
            val len = (b - a).length()
            val line = hang(a, b, 0.12 * len + 0.2, 0.06, s.seed + 2.0)
            val n = max(6, ceil(len / 0.35).toInt())
            val points = (0..n).map { line(it.toDouble() / n) }
            strip(solid, points, 0.07, ROPE, GARLAND_ROPE[kind.ordinal].opaque(), s.garlandLight, light, repeat = 0.4)
            val sprites = GARLAND[kind.ordinal]
            val count = max(4, (len / 0.24).toInt())
            for (k in 0 until count) {
                val f = (k + 0.5) / count
                val at = line(f) + V(0.0, -0.1, 0.0)
                val pick = sprites[Math.floorMod(k * 7 + data.index * 3, sprites.size)]
                val size = 0.22 + 0.05 * sin(k * 2.1 + s.seed)
                val stir = 0.08 * sin(t * 0.06 + k * 0.7 + s.seed) * wind
                crossed(solid, at, size, size, k * 1.7 + s.seed, pick, -1, lightAt(s.garlandLight, f), V(stir, 0.0, stir * 0.5))
            }
            if (kind == FestivalKind.LANTERNRISE) {
                // Little lights twinkling along the evergreen
                val lights = max(3, (len / 0.6).toInt())
                for (k in 0 until lights) {
                    val f = (k + 0.3) / lights
                    val twinkle = 0.55 + 0.45 * sin(t * 0.13 + k * 2.4 + s.seed)
                    val color = intArrayOf(0xFFE6A0, 0xFF8070, 0xA0E0FF, 0xC8FFB0)[Math.floorMod(k + data.index, 4)]
                    billboard(glow, line(f) + V(0.0, -0.06, 0.0), 0.16, HALO, ARGB.color((twinkle * (0.4f + 0.6f * s.dark)).toFloat().coerceIn(0f, 1f), color), LightCoordsUtil.FULL_BRIGHT)
                }
            }
        }

        /** The post's banner, hung on its inner side from a short rod, rippling. */
        fun banner(inward: V) {
            val side = V(-inward.z, 0.0, inward.x)
            val front = inward * 0.21
            val topY = -0.14
            val length = 1.45
            val half = 0.3
            // The rod
            box(solid, 0.5 + front.x - 0.02 - abs(side.x) * (half + 0.06), topY - 0.02, 0.5 + front.z - 0.02 - abs(side.z) * (half + 0.06),
                0.5 + front.x + 0.02 + abs(side.x) * (half + 0.06), topY + 0.02, 0.5 + front.z + 0.02 + abs(side.z) * (half + 0.06), WOOD, -1, light)
            val uv = BANNERS[kind.ordinal]
            val rows = 6
            var prev: Pair<V, V>? = null
            for (r in 0..rows) {
                val f = r.toDouble() / rows
                val wave = (0.05 + 0.06 * wind) * sin(t * 0.13 + f * 3.2 + s.seed) * f + 0.1 * wind * f * f
                val y = topY - length * f
                val mid = V(0.5, y, 0.5) + front + inward * wave
                val pair = (mid - side * half) to (mid + side * half)
                prev?.let { (l0, r0) ->
                    val f0 = (r - 1).toDouble() / rows
                    val lit = light
                    solid.vertex(l0, uv.u0, uv.v(f0), -1, lit)
                    solid.vertex(r0, uv.u1, uv.v(f0), -1, lit)
                    solid.vertex(pair.second, uv.u1, uv.v(f), -1, lit)
                    solid.vertex(pair.first, uv.u0, uv.v(f), -1, lit)
                }
                prev = pair
            }
        }

        // ---- The centrepiece ----

        fun crown() {
            when (kind) {
                FestivalKind.RIBBONWAKE -> ribbonCrown()
                FestivalKind.SUNHEARTH -> sunWheel()
                FestivalKind.SHEAFMOOT -> sheafCrown()
                FestivalKind.LANTERNRISE -> star()
            }
        }

        /** The ribbon pole's crown of blossom, and its ribbons: to the dancers' hands while they dance, down the pole while they don't. */
        fun ribbonCrown() {
            val centre = V(0.5, 0.0, 0.5)
            // A gilded knob on top, and the crown of flowers
            box(solid, 0.4, 0.0, 0.4, 0.6, 0.18, 0.6, GOLD, -1, light)
            billboard(solid, V(0.5, 0.3, 0.5), 0.22, GOLD_BALL, -1, light)
            for (k in 0 until 12) {
                val a = k * PI / 6 + 0.2
                val at = centre + V(cos(a) * 0.3, 0.02, sin(a) * 0.3)
                crossed(solid, at, 0.24, 0.24, a, GARLAND[0][k % 2], -1, light)
            }
            val ribbons = max(8, s.hands)
            if (s.hands > 0) {
                for (h in 0 until s.hands) {
                    val hand = V(s.hand[h * 3], s.hand[h * 3 + 1], s.hand[h * 3 + 2])
                    val dir = (hand - centre).flat().unit()
                    val from = centre + dir * 0.28 + V(0.0, 0.08, 0.0)
                    val len = (hand - from).length()
                    val sag = 0.04 * len
                    val n = 12
                    val points = (0..n).map { k ->
                        val f = k.toDouble() / n
                        from + (hand - from) * f - V(0.0, sag * 4 * f * (1 - f), 0.0)
                    }
                    val color = RIBBON_COLORS[Math.floorMod(s.holder[h], RIBBON_COLORS.size)]
                    strip(solid, points, 0.075, RIBBON, color.opaque(), null, light)
                }
                return
            }
            // Between dances the ribbons hang down the pole, stirring
            val drop = min(data.drop - 1.2, 7.5).coerceAtLeast(1.0)
            for (k in 0 until ribbons) {
                val a = k * 2 * PI / ribbons
                val out = V(cos(a), 0.0, sin(a))
                val tangent = V(-sin(a), 0.0, cos(a))
                val from = centre + out * 0.26 + V(0.0, 0.06, 0.0)
                val n = 12
                val points = (0..n).map { i ->
                    val f = i.toDouble() / n
                    val flutter = (0.05 + 0.12 * wind) * sin(t * 0.09 + k * 1.1 + f * 4.0 + s.seed) * f
                    from + V(0.0, -drop * f, 0.0) + out * (0.06 + 0.1 * f * f) + tangent * flutter
                }
                strip(solid, points, 0.075, RIBBON, RIBBON_COLORS[k % RIBBON_COLORS.size].opaque(), null, light)
            }
        }

        /** The sun wheel: upright on the pole, spinning in its plane while that plane slowly comes round. */
        fun sunWheel() {
            val time = t
            val (spin, face) = wheelAngles(time)
            val c = V(0.5, 0.5 + WHEEL_LIFT, 0.5)
            val h = V(cos(face), 0.0, sin(face))
            val up = V(0.0, 1.0, 0.0)
            val burning = s.dusk
            val lit = if (burning) LightCoordsUtil.FULL_BRIGHT else light
            // The axle up from the pole to the hub
            box(solid, 0.45, 0.0, 0.45, 0.55, c.y, 0.55, WOOD, -1, light)
            // The wheel: one card both ways, its picture turned by the spin
            val r = WHEEL_RADIUS * sqrt(2.0)
            val corner = { k: Int -> c + (h * cos(spin + PI / 4 + k * PI / 2) + up * sin(spin + PI / 4 + k * PI / 2)) * r }
            solid.vertex(corner(1), WHEEL.u0, WHEEL.v0, -1, lit)
            solid.vertex(corner(0), WHEEL.u1, WHEEL.v0, -1, lit)
            solid.vertex(corner(3), WHEEL.u1, WHEEL.v1, -1, lit)
            solid.vertex(corner(2), WHEEL.u0, WHEEL.v1, -1, lit)
            box(solid, c.x - 0.09, c.y - 0.09, c.z - 0.09, c.x + 0.09, c.y + 0.09, c.z + 0.09, GOLD, -1, lit)
            // Its glow: warm by day, a blaze at dusk
            val blaze = if (burning) 0.7 + 0.2 * sin(time * 0.4) else 0.18 + 0.2 * s.dark
            billboard(glow, c, WHEEL_RADIUS * 3.4, HALO, ARGB.color(blaze.toFloat().coerceIn(0f, 1f), 0xFFB040), LightCoordsUtil.FULL_BRIGHT)
            // Streamers from the rim, going round with it
            for (k in 0 until 8) {
                val a = spin + k * PI / 4
                val from = c + (h * cos(a) + up * sin(a)) * (WHEEL_RADIUS * 0.96)
                val n = 6
                val points = (0..n).map { i ->
                    val f = i.toDouble() / n
                    val flutter = (0.08 + 0.15 * wind) * sin(time * 0.15 + k + f * 3.0) * f
                    from + V(0.0, -0.95 * f, 0.0) + windDir * (0.25 * wind * f) + V(-h.z, 0.0, h.x) * flutter
                }
                strip(solid, points, 0.06, RIBBON, intArrayOf(0xD9432F, 0xF5B342, 0xFFF1C9, 0xF08A3A)[k % 4].opaque(), null, light)
            }
        }

        /** The harvest crown: sheaves stood round the pole's top, a red band about them and streamers hanging. */
        fun sheafCrown() {
            val sway = 0.03 * sin(t * 0.05 + s.seed) * wind
            for (k in 0 until 6) {
                val a = k * PI / 6
                val lean = V(cos(a) * sway, 0.0, sin(a) * sway) + windDir * (0.04 * wind)
                crossedOne(V(0.5, 0.0, 0.5), 0.95, 1.25, a, lean)
            }
            // The band
            val n = 14
            val ring = (0..n).map { k ->
                val a = k * 2 * PI / n
                V(0.5 + cos(a) * 0.3, 0.32, 0.5 + sin(a) * 0.3)
            }
            for (k in 0 until n) {
                val p0 = ring[k]
                val p1 = ring[k + 1]
                quad(solid, p0 + V(0.0, 0.05, 0.0), p1 + V(0.0, 0.05, 0.0), p1 - V(0.0, 0.05, 0.0), p0 - V(0.0, 0.05, 0.0), RIBBON, 0x8C2D3A.opaque(), light)
            }
            for (k in 0 until 4) {
                val a = k * PI / 2 + 0.4
                val from = V(0.5 + cos(a) * 0.31, 0.3, 0.5 + sin(a) * 0.31)
                val m = 8
                val points = (0..m).map { i ->
                    val f = i.toDouble() / m
                    val flutter = (0.05 + 0.12 * wind) * sin(t * 0.12 + k * 1.7 + f * 3.5) * f
                    from + V(cos(a) * 0.15 * f, -1.2 * f, sin(a) * 0.15 * f) + V(-sin(a), 0.0, cos(a)) * flutter
                }
                strip(solid, points, 0.06, RIBBON, intArrayOf(0xB5532A, 0xD9A23A, 0x8C2D3A, 0x6F8F3A)[k].opaque(), null, light)
            }
        }

        /** One sheaf card ([crossed] makes a pair). */
        fun crossedOne(at: V, w: Double, h: Double, angle: Double, lean: V) {
            val d = V(cos(angle) * w / 2, 0.0, sin(angle) * w / 2)
            val top = at + V(0.0, h, 0.0) + lean
            quad(solid, top - d, top + d, at + d, at - d, SHEAF, -1, light)
        }

        /** The Lanternrise's star over its ring of lanterns hung from four short arms. */
        fun star() {
            val c = V(0.5, 0.95, 0.5)
            box(solid, 0.44, 0.0, 0.44, 0.56, 0.55, 0.56, GOLD, -1, light)
            val roll = 0.18 * sin(t * 0.02 + s.seed)
            val pulse = 0.85 + 0.15 * sin(t * 0.07)
            billboard(glow, c, 0.95, STAR, ARGB.color(1f, 0xFFF4C8), LightCoordsUtil.FULL_BRIGHT, roll)
            billboard(glow, c, 3.2 * pulse, HALO, ARGB.color(((0.35f + 0.45f * s.dark) * pulse).toFloat().coerceIn(0f, 1f), 0xFFE6A0), LightCoordsUtil.FULL_BRIGHT)
            for (k in 0 until 4) {
                val a = k * PI / 2 + PI / 4
                val out = V(cos(a), 0.0, sin(a))
                val armEnd = V(0.5, 0.18, 0.5) + out * 0.62
                strip(solid, listOf(V(0.5, 0.2, 0.5), armEnd), 0.05, WOOD, -1, null, light)
                val swing = 0.15 * sin(t * 0.06 + k * 1.9) * wind
                val top = armEnd + V(sin(swing) * 0.08, -0.22, cos(swing) * 0.05)
                strip(solid, listOf(armEnd, top), 0.012, ROPE, 0x403020.opaque(), null, light)
                smallLantern(top, 0.75, kind.color(k * 2).opaque())
            }
        }

        // ---- A hunt ribbon ----

        fun huntRibbon() {
            box(solid, 0.47, 0.0, 0.47, 0.53, 0.56, 0.53, WOOD, -1, light)
            val color = RIBBON_COLORS[Math.floorMod(data.index * 3, RIBBON_COLORS.size)].opaque()
            val bow = V(0.5, 0.58, 0.5)
            val turn = 0.3 * sin(t * 0.03 + s.seed)
            crossed(solid, bow - V(0.0, 0.17, 0.0), 0.36, 0.34, s.seed + turn, BOW, color, light)
            for (k in 0 until 2) {
                val a = s.seed + k * PI + 0.4
                val out = V(cos(a), 0.0, sin(a))
                val n = 6
                val points = (0..n).map { i ->
                    val f = i.toDouble() / n
                    val flutter = (0.05 + 0.1 * wind) * sin(t * 0.17 + k * 2.0 + f * 3.0 + s.seed) * f
                    bow + out * (0.05 + 0.12 * f) + V(0.0, -0.42 * f, 0.0) + windDir * (0.12 * wind * f) + V(-out.z, 0.0, out.x) * flutter
                }
                strip(solid, points, 0.05, RIBBON, color, null, light)
            }
            // Now and then a glint, to catch the eye of anyone looking
            val glint = max(0.0, sin(t * 0.045 + s.seed)).pow(14)
            if (glint > 0.02) billboard(glow, bow + V(0.08, 0.06, 0.0), 0.4 * glint + 0.1, SPARK, ARGB.color(glint.toFloat().coerceIn(0f, 1f), 0xFFFFFF), LightCoordsUtil.FULL_BRIGHT, t * 0.05)
        }
    }

    companion object {
        private val ATLAS = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/festival/ornaments.png")
        private const val SIZE = 256f

        private val CLOTH = Uv(0, 0, 16, 16)
        private val ROPE = Uv(16, 0, 32, 16)
        private val RIBBON = Uv(32, 0, 48, 16)
        private val HALO = Uv(48, 0, 64, 16)
        private val FLAME = Uv(64, 0, 80, 16)
        private val TAG = Uv(80, 0, 96, 16)
        private val BOW = Uv(96, 0, 112, 16)
        private val WOOD = Uv(112, 0, 128, 16)
        private val IRON = Uv(128, 0, 144, 16)
        private val GOLD = Uv(144, 0, 160, 16)
        private val SPARK = Uv(160, 0, 176, 16)
        private val GOLD_BALL = Uv(176, 0, 192, 16)
        private val GLASS = Uv(0, 16, 16, 40)
        private val PAPER = Uv(16, 16, 32, 40)
        private val BANNERS = Array(4) { Uv(32 + 32 * it, 16, 64 + 32 * it, 80) }
        /** The garlands' flowers and leaves (blossom, marigold, maple, holly, leaf, wheat, berries, pine). */
        private val SPRITES = Array(8) { Uv(16 * it, 80, 16 * it + 16, 96) }
        private val GARLAND = arrayOf(
            arrayOf(SPRITES[0], SPRITES[4], SPRITES[0], SPRITES[4], SPRITES[6]),
            arrayOf(SPRITES[1], SPRITES[4], SPRITES[1], SPRITES[5]),
            arrayOf(SPRITES[2], SPRITES[5], SPRITES[2], SPRITES[6], SPRITES[4]),
            arrayOf(SPRITES[7], SPRITES[3], SPRITES[7], SPRITES[6]),
        )
        private val WHEEL = Uv(0, 96, 64, 160)
        private val STAR = Uv(64, 96, 96, 128)
        private val SHEAF = Uv(96, 96, 128, 160)

        /** The lanterns' glass, the garlands' rope, the pennants' cloth and the ribbons, by festival. */
        private val LANTERN_TINT = intArrayOf(0xFFE4F0, 0xFFE2A0, 0xFFD2A0, 0xFFF0C8)
        private val GARLAND_ROPE = intArrayOf(0x6E8F48, 0xC9A04A, 0x8A6A3A, 0x3F6A44)
        private val PENNANT_COLORS = arrayOf(
            intArrayOf(0xF2A6C8, 0xF6E27A, 0x9BD36A, 0x8EC9F0, 0xC4A3E8, 0xF8F4EC),
            intArrayOf(0xF5B342, 0xD9432F, 0xF08A3A, 0xFFF1C9, 0x6FB7E8),
            intArrayOf(0xB5532A, 0xD9A23A, 0x8C2D3A, 0xC98B2E, 0x6F8F3A),
            intArrayOf(0xF4F1EA, 0xE8C26A, 0xB32D35, 0x2F6B45),
        )
        private val RIBBON_COLORS = intArrayOf(0xF2A6C8, 0xF6E27A, 0x9BD36A, 0x8EC9F0, 0xC4A3E8, 0xF8F4EC, 0xF08A9A, 0x7FD6C2)

        /** The sun wheel's size and how high its hub stands above the crown's middle. */
        const val WHEEL_RADIUS = 1.1
        const val WHEEL_LIFT = 0.8

        /** The sun wheel's spin in its own plane and the turn of that plane about the pole, at [time] ticks. */
        fun wheelAngles(time: Double): Pair<Double, Double> =
            ((time % 400.0) / 400.0 * 2 * PI) to ((time % 1600.0) / 1600.0 * 2 * PI)

        private fun Int.opaque(): Int = this or (0xFF shl 24)
    }
}
