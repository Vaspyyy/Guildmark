package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the renderers of the stations share: the four textures of M1's renderer set and the render types that draw them, a way into
 * a cell turned for the way a station faces, and the colour helpers. Everything is in pixels of the cell (16 to a block, x east,
 * y up, z south, for a station facing north) until it reaches a vertex.
 */
object Gfx {
    const val PX = 1f / 16f
    const val TAU = (2.0 * PI).toFloat()

    private fun texture(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/cook/$name.png")

    val SURFACE_ID: Identifier = texture("surface")
    val FILM_ID: Identifier = texture("film")
    val CRUST_ID: Identifier = texture("crust")
    val RING_ID: Identifier = texture("ring")

    /** Where a pure white texel of `surface.png` is (the middle of texel 17, 10 of 32, which all four of its neighbours share): geometry drawn from it is flat colour. */
    const val WHITE_U = 0.546875f
    const val WHITE_V = 0.328125f

    /** Translucent, lit: the liquid's surface, and every flat shape drawn in a single colour from the white texel. */
    val SURFACE: RenderType by lazy { RenderTypes.entityTranslucent(SURFACE_ID) }

    /** The grey film of a SKIM and the burnt crust. */
    val FILM: RenderType by lazy { RenderTypes.entityTranslucent(FILM_ID) }
    val CRUST: RenderType by lazy { RenderTypes.entityTranslucent(CRUST_ID) }

    /** The ready ring: added to what is behind it, whatever the light (it shimmers). */
    val RING: RenderType by lazy { RenderTypes.eyes(RING_ID) }

    private val cutouts = HashMap<Identifier, RenderType>()

    /** Cutout sprites of [atlas] (an item's picture, a chunk or a piece lying in a pan). */
    fun cutout(atlas: Identifier): RenderType = cutouts.getOrPut(atlas) { RenderTypes.entityCutout(atlas) }

    /** Into the cell turned for a station that faces [facing] (north is the models' own): about the cell's middle, as the blockstates turn their models. */
    fun enter(poseStack: PoseStack, facing: Direction) {
        if (facing == Direction.NORTH) return
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, 180f - facing.toYRot())
        poseStack.translate(-0.5f, 0f, -0.5f)
    }

    /** Moves to the cell pixel ([x], [y], [z]). */
    fun at(poseStack: PoseStack, x: Float, y: Float, z: Float) {
        poseStack.translate(x * PX, y * PX, z * PX)
    }

    // ---- Colour ----

    fun c8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    fun argb(a: Float, r: Float, g: Float, b: Float): Int = (c8(a) shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)

    /** [rgb] (0xRRGGBB) with the alpha [a], each channel multiplied by [k]. */
    fun rgb(rgb: Int, a: Float, k: Float = 1f): Int =
        argb(a, (rgb shr 16 and 0xFF) / 255f * k, (rgb shr 8 and 0xFF) / 255f * k, (rgb and 0xFF) / 255f * k)

    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** The colour [t] of the way (0 to 1) from the packed colour [a] to [b], each channel (alpha too) on its own. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = (t.coerceIn(0f, 1f) * 256f).toInt()
        val ar = (a ushr 24 and 0xFF) + (((b ushr 24 and 0xFF) - (a ushr 24 and 0xFF)) * u shr 8)
        val rr = (a shr 16 and 0xFF) + (((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * u shr 8)
        val gr = (a shr 8 and 0xFF) + (((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * u shr 8)
        val br = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * u shr 8)
        return (ar shl 24) or (rr shl 16) or (gr shl 8) or br
    }

    fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * The tint of food browned [b] (0 pale and raw, about 0.45 golden, 0.9 a deep brown, 1 black), in one of four steps that blend
     * into one another, for an item's picture to be drawn in: the colours multiply the picture's own.
     */
    fun browned(b: Float): Int {
        val x = b.coerceIn(0f, 1f)
        return when {
            x < 0.45f -> {
                val k = x / 0.45f
                argb(1f, mix(1f, 1f, k), mix(0.97f, 0.82f, k), mix(0.94f, 0.55f, k))
            }
            x < 0.9f -> {
                val k = (x - 0.45f) / 0.45f
                argb(1f, mix(1f, 0.62f, k), mix(0.82f, 0.36f, k), mix(0.55f, 0.18f, k))
            }
            else -> {
                val k = (x - 0.9f) / 0.1f
                argb(1f, mix(0.62f, 0.14f, k), mix(0.36f, 0.11f, k), mix(0.18f, 0.09f, k))
            }
        }
    }

    /** [t] in the span [lo] to [hi] as 0 to 1, clamped. */
    fun span(t: Float, lo: Float, hi: Float): Float = if (hi == lo) (if (t >= hi) 1f else 0f) else ((t - lo) / (hi - lo)).coerceIn(0f, 1f)
}

/**
 * Flat shapes into a [VertexConsumer] for one submission of custom geometry: the textured square, the single-colour disc, ring and
 * line, and the tilted sprite of an item. Positions are in pixels of the cell (the pose the geometry was submitted in is the
 * cell's, so a pixel is [Gfx.PX] of a block); every vertex carries all the elements the entity formats need.
 *
 * [rot] and [yaw] turn clockwise as seen from above (from east toward south), the way a stirred liquid turns.
 */
class Pen(private val buffer: VertexConsumer, private val pose: PoseStack.Pose, var light: Int) {
    var quads = 0
        private set

    fun vertex(x: Float, y: Float, z: Float, u: Float, v: Float, argb: Int, nx: Float, ny: Float, nz: Float) {
        buffer.addVertex(pose, x * Gfx.PX, y * Gfx.PX, z * Gfx.PX).setColor(argb).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz)
    }

    /** A square lying flat at height [y], centred ([cx], [cz]), [hx] and [hz] from the middle to each edge, turned [rot], showing the picture [u0] to [u1], [v0] to [v1]. */
    fun flat(cx: Float, y: Float, cz: Float, hx: Float, hz: Float, rot: Float, u0: Float, v0: Float, u1: Float, v1: Float, argb: Int) {
        val c = cos(rot)
        val s = sin(rot)
        corner(cx, y, cz, -hx, -hz, c, s, u0, v0, argb)
        corner(cx, y, cz, -hx, hz, c, s, u0, v1, argb)
        corner(cx, y, cz, hx, hz, c, s, u1, v1, argb)
        corner(cx, y, cz, hx, -hz, c, s, u1, v0, argb)
        quads++
    }

    private fun corner(cx: Float, y: Float, cz: Float, dx: Float, dz: Float, c: Float, s: Float, u: Float, v: Float, argb: Int) {
        vertex(cx + dx * c - dz * s, y, cz + dx * s + dz * c, u, v, argb, 0f, 1f, 0f)
    }

    /** A flat quad in one colour (from the white texel). */
    fun flatColour(cx: Float, y: Float, cz: Float, hx: Float, hz: Float, rot: Float, argb: Int) {
        flat(cx, y, cz, hx, hz, rot, Gfx.WHITE_U, Gfx.WHITE_V, Gfx.WHITE_U, Gfx.WHITE_V, argb)
    }

    /** A round soft-edged blob: the surface texture's whole disc, in a colour, on one quad (a bubble, a lump of foam, a glow). */
    fun blob(cx: Float, y: Float, cz: Float, hx: Float, hz: Float, rot: Float, argb: Int) {
        flat(cx, y, cz, hx, hz, rot, 0f, 0f, 1f, 1f, argb)
    }

    /** A flat ellipse of [segs] sides (an even number) in one colour, [rx] by [rz] from the middle, turned [rot]. Made of kites: two sides to a quad. */
    fun disc(cx: Float, y: Float, cz: Float, rx: Float, rz: Float, rot: Float, segs: Int, argb: Int) {
        val c = cos(rot)
        val s = sin(rot)
        var i = 0
        while (i < segs) {
            val a0 = Gfx.TAU * i / segs
            val a1 = Gfx.TAU * (i + 1) / segs
            val a2 = Gfx.TAU * (i + 2) / segs
            vertex(cx, y, cz, Gfx.WHITE_U, Gfx.WHITE_V, argb, 0f, 1f, 0f)
            rim(cx, y, cz, rx, rz, c, s, a0, argb)
            rim(cx, y, cz, rx, rz, c, s, a1, argb)
            rim(cx, y, cz, rx, rz, c, s, a2, argb)
            quads++
            i += 2
        }
    }

    private fun rim(cx: Float, y: Float, cz: Float, rx: Float, rz: Float, c: Float, s: Float, a: Float, argb: Int) {
        val dx = cos(a) * rx
        val dz = sin(a) * rz
        vertex(cx + dx * c - dz * s, y, cz + dx * s + dz * c, Gfx.WHITE_U, Gfx.WHITE_V, argb, 0f, 1f, 0f)
    }

    /** A flat ring from radius [r0] to [r1], from angle [a0] to [a1] (radians, clockwise from east), of [segs] pieces, in one colour; [argb1] is the colour at the end if given (a fade along it). */
    fun ring(cx: Float, y: Float, cz: Float, r0: Float, r1: Float, a0: Float, a1: Float, segs: Int, argb: Int, argb1: Int = argb) {
        for (i in 0 until segs) {
            val t0 = i / segs.toFloat()
            val t1 = (i + 1) / segs.toFloat()
            val p = a0 + (a1 - a0) * t0
            val q = a0 + (a1 - a0) * t1
            val c0 = if (argb1 == argb) argb else lerpArgb(argb, argb1, t0)
            val c1 = if (argb1 == argb) argb else lerpArgb(argb, argb1, t1)
            vertex(cx + cos(p) * r0, y, cz + sin(p) * r0, Gfx.WHITE_U, Gfx.WHITE_V, c0, 0f, 1f, 0f)
            vertex(cx + cos(p) * r1, y, cz + sin(p) * r1, Gfx.WHITE_U, Gfx.WHITE_V, c0, 0f, 1f, 0f)
            vertex(cx + cos(q) * r1, y, cz + sin(q) * r1, Gfx.WHITE_U, Gfx.WHITE_V, c1, 0f, 1f, 0f)
            vertex(cx + cos(q) * r0, y, cz + sin(q) * r0, Gfx.WHITE_U, Gfx.WHITE_V, c1, 0f, 1f, 0f)
            quads++
        }
    }

    private fun lerpArgb(a: Int, b: Int, t: Float): Int = Gfx.lerp(a, b, t)

    /** A thin upright line from ([x], [y0], [z]) up to [y1], [w] wide, as two crossed quads so it shows from every side, in one colour (the colour at the top is [argb1]). */
    fun line(x: Float, y0: Float, y1: Float, z: Float, w: Float, argb: Int, argb1: Int = argb) {
        vertex(x - w, y0, z, Gfx.WHITE_U, Gfx.WHITE_V, argb, 0f, 0f, 1f)
        vertex(x - w, y1, z, Gfx.WHITE_U, Gfx.WHITE_V, argb1, 0f, 0f, 1f)
        vertex(x + w, y1, z, Gfx.WHITE_U, Gfx.WHITE_V, argb1, 0f, 0f, 1f)
        vertex(x + w, y0, z, Gfx.WHITE_U, Gfx.WHITE_V, argb, 0f, 0f, 1f)
        vertex(x, y0, z - w, Gfx.WHITE_U, Gfx.WHITE_V, argb, 1f, 0f, 0f)
        vertex(x, y1, z - w, Gfx.WHITE_U, Gfx.WHITE_V, argb1, 1f, 0f, 0f)
        vertex(x, y1, z + w, Gfx.WHITE_U, Gfx.WHITE_V, argb1, 1f, 0f, 0f)
        vertex(x, y0, z + w, Gfx.WHITE_U, Gfx.WHITE_V, argb, 1f, 0f, 0f)
        quads += 2
    }

    /**
     * An item's picture on a square [hx] by [hz] from the middle, its middle at ([cx], [cy], [cz]): turned [yaw] about the vertical,
     * and tipped [tiltX] and [tiltZ] (radians) first, so a chunk in a pot leans as it bobs. [flat] is the picture's place in its atlas.
     * Only the part of the picture from [from] to [to] (0 to 1, along x) is drawn, so one side of a piece can be coloured on its own.
     */
    fun sprite(
        cx: Float, cy: Float, cz: Float, hx: Float, hz: Float, yaw: Float, tiltX: Float, tiltZ: Float, flat: CookItems.Flat, argb: Int,
        from: Float = 0f, to: Float = 1f,
    ) {
        val cy1 = cos(yaw)
        val sy1 = sin(yaw)
        val ct = cos(tiltX)
        val st = sin(tiltX)
        val cb = cos(tiltZ)
        val sb = sin(tiltZ)
        // The normal: up, tipped about x, then about z, then turned
        val nx0 = -ct * sb
        val ny0 = ct * cb
        val nz0 = st
        val nx = nx0 * cy1 - nz0 * sy1
        val nz = nx0 * sy1 + nz0 * cy1
        val l0 = -hx + 2f * hx * from
        val l1 = -hx + 2f * hx * to
        val ua = flat.u0 + (flat.u1 - flat.u0) * from
        val ub = flat.u0 + (flat.u1 - flat.u0) * to
        tilted(cx, cy, cz, l0, -hz, cy1, sy1, ct, st, cb, sb, ua, flat.v0, argb, nx, ny0, nz)
        tilted(cx, cy, cz, l0, hz, cy1, sy1, ct, st, cb, sb, ua, flat.v1, argb, nx, ny0, nz)
        tilted(cx, cy, cz, l1, hz, cy1, sy1, ct, st, cb, sb, ub, flat.v1, argb, nx, ny0, nz)
        tilted(cx, cy, cz, l1, -hz, cy1, sy1, ct, st, cb, sb, ub, flat.v0, argb, nx, ny0, nz)
        quads++
    }

    /**
     * An item's picture standing on an axis that runs along x through ([cx], [cy], [cz]) (a joint skewered on a rod): [hx] each way
     * along the axis and [hr] each way out from it, in the direction [angle] (radians; 0 is up and it turns toward +z). Seen from
     * both sides, so a pair at right angles shows a face from every way round.
     */
    fun spoke(cx: Float, cy: Float, cz: Float, hx: Float, hr: Float, angle: Float, flat: CookItems.Flat, argb: Int) {
        val ry = cos(angle)
        val rz = sin(angle)
        val ny = -rz
        val nz = ry
        vertex(cx - hx, cy + ry * hr, cz + rz * hr, flat.u0, flat.v0, argb, 0f, ny, nz)
        vertex(cx - hx, cy - ry * hr, cz - rz * hr, flat.u0, flat.v1, argb, 0f, ny, nz)
        vertex(cx + hx, cy - ry * hr, cz - rz * hr, flat.u1, flat.v1, argb, 0f, ny, nz)
        vertex(cx + hx, cy + ry * hr, cz + rz * hr, flat.u1, flat.v0, argb, 0f, ny, nz)
        quads++
    }

    private fun tilted(
        cx: Float, cy: Float, cz: Float, lx: Float, lz: Float, cyaw: Float, syaw: Float, ct: Float, st: Float, cb: Float, sb: Float,
        u: Float, v: Float, argb: Int, nx: Float, ny: Float, nz: Float,
    ) {
        // The corner (lx, 0, lz) tipped about x, then about z, then turned about the vertical
        val y1 = -lz * st
        val z1 = lz * ct
        val x2 = lx * cb - y1 * sb
        val y2 = lx * sb + y1 * cb
        vertex(cx + x2 * cyaw - z1 * syaw, cy + y2, cz + x2 * syaw + z1 * cyaw, u, v, argb, nx, ny, nz)
    }
}
