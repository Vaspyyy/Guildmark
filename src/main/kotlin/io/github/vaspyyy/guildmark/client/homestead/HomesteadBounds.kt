package io.github.vaspyyy.guildmark.client.homestead

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.homestead.PlotFrame
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.tags.BlockTags
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BannerBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.WallBannerBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A homestead's bounds, drawn on the land: a ribbon of light in its owner's colour running from corner stake to corner
 * stake over the lie of the ground, a pale beam rising at each corner and motes drifting up along it, while the player
 * holds a charter or stands at the Charter Post. The curtain grows up out of the ground as it's shown and sinks back
 * as it goes, ripples as a light runs round it, and fades with distance.
 *
 * Holding a charter also shows the plot the charter would stake where it points: the cottage's plot and, dim and low,
 * the whole of the land it would take, gold while the server is asked, green when the land will do and red when it
 * won't, sliding along as the aim moves; and whatever is at fault (a lot too steep or wet, someone's work, another's
 * land) picked out on the ground.
 */
object HomesteadBounds {
    private val CURTAIN = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/homestead/boundary.png")
    private val MARK = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/homestead/mark.png")

    /** A loop of ground columns round a box, from corner to corner: its points, the ground at each, how far along. */
    private class Ring {
        var minX = 0
        var minZ = 0
        var maxX = -1
        var maxZ = -1
        var n = 0
        var xs = FloatArray(0)
        var zs = FloatArray(0)
        var ys = FloatArray(0)
        var along = FloatArray(0)
        var length = 1f
        val corners = IntArray(4)

        fun same(x0: Int, z0: Int, x1: Int, z1: Int): Boolean = x0 == minX && z0 == minZ && x1 == maxX && z1 == maxZ

        fun build(level: ClientLevel, x0: Int, z0: Int, x1: Int, z1: Int, fallback: Int) {
            minX = x0
            minZ = z0
            maxX = x1
            maxZ = z1
            val w = x1 - x0
            val d = z1 - z0
            n = 2 * (w + d)
            xs = FloatArray(n + 1)
            zs = FloatArray(n + 1)
            ys = FloatArray(n + 1)
            along = FloatArray(n + 1)
            var i = 0
            fun put(x: Int, z: Int) {
                xs[i] = x + 0.5f
                zs[i] = z + 0.5f
                ys[i] = ground(level, x, z, fallback).toFloat()
                i++
            }
            corners[0] = 0
            for (k in 0 until w) put(x0 + k, z0)
            corners[1] = i
            for (k in 0 until d) put(x1, z0 + k)
            corners[2] = i
            for (k in 0 until w) put(x1 - k, z1)
            corners[3] = i
            for (k in 0 until d) put(x0, z1 - k)
            xs[n] = xs[0]
            zs[n] = zs[0]
            ys[n] = ys[0]
            // A step up or down is eased over the columns beside it, so the ribbon runs over the land rather than up it
            val raw = ys.copyOf()
            for (k in 0 until n) ys[k] = (raw[Math.floorMod(k - 1, n)] + raw[k] * 2 + raw[(k + 1) % n]) / 4f
            ys[n] = ys[0]
            for (k in 1..n) along[k] = along[k - 1] + 1f
            length = max(1f, along[n])
        }
    }

    private class Shown(val key: Long) {
        val ring = Ring()
        var want = false
        var alpha = 0f
        var alphaO = 0f
        /** The colour it's heading for, and the colour it is (eased, so a verdict's change of colour washes over it). */
        var rgb = 0xFFFFFF
        var r = -1f
        var g = 0f
        var b = 0f
        var height = 1.6f
        /** The charter's whole land round a preview: low, dim and still. */
        var faint = false
        var posts = true
        var motes = true
        /** How fast its light runs round, and how strong. */
        var runner = 1f
        var surveyed = -1000L
        /** Where the outline was drawn from, sliding to where it is now (the aim moved). */
        var slideX = 0f
        var slideZ = 0f
        var slideXO = 0f
        var slideZO = 0f
        /** The post's ghost for a preview: where the Charter Post would stand. */
        var post: BlockPos? = null

        fun place(level: ClientLevel, frame: PlotFrame, rect: Rect, now: Long, slide: Boolean, fallback: Int) {
            val box = frame.box(rect, 0, 0)
            placeBox(level, box.minX(), box.minZ(), box.maxX(), box.maxZ(), now, slide, fallback)
        }

        /** Round the columns [x0]..[x1], [z0]..[z1]; [slide] eases it over from where it was if it only moved. */
        fun placeBox(level: ClientLevel, x0: Int, z0: Int, x1: Int, z1: Int, now: Long, slide: Boolean, fallback: Int) {
            val same = ring.same(x0, z0, x1, z1)
            if (same && now - surveyed < RESURVEY) return
            if (!same && slide && ring.n > 0 && ring.maxX - ring.minX == x1 - x0 && ring.maxZ - ring.minZ == z1 - z0) {
                slideX += (ring.minX - x0).toFloat()
                slideZ += (ring.minZ - z0).toFloat()
                slideXO = slideX
                slideZO = slideZ
            } else if (!same && ring.n > 0) {
                // Turned round: it fades out and in again rather than jump
                alpha = min(alpha, 0.25f)
                alphaO = alpha
            }
            ring.build(level, x0, z0, x1, z1, fallback)
            surveyed = now
        }

        fun ease() {
            alphaO = alpha
            alpha = if (want) min(1f, alpha + 0.09f) else max(0f, alpha - 0.07f)
            slideXO = slideX
            slideZO = slideZ
            slideX *= 0.55f
            slideZ *= 0.55f
            if (abs(slideX) < 0.01f) slideX = 0f
            if (abs(slideZ) < 0.01f) slideZ = 0f
            val tr = (rgb shr 16 and 0xFF) / 255f
            val tg = (rgb shr 8 and 0xFF) / 255f
            val tb = (rgb and 0xFF) / 255f
            if (r < 0f) {
                r = tr
                g = tg
                b = tb
            } else {
                r += (tr - r) * 0.3f
                g += (tg - g) * 0.3f
                b += (tb - b) * 0.3f
            }
        }

        val color: Int get() = ARGB.colorFromFloat(1f, r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f)) and 0xFFFFFF
    }

    private val shown = LinkedHashMap<Long, Shown>()

    /** Places picked out on the ground (a refusal's), and how strongly. */
    private var marks: List<BlockPos> = listOf()
    private var marksAlpha = 0f
    private var marksAlphaO = 0f
    private var marksRed = true

    private const val PREVIEW = -1L
    private const val PREVIEW_LAND = -2L
    private const val LOT = -3L

    private const val PENDING = 0xF2D58C
    private const val OK = 0x7CF08A
    private const val NO = 0xFF5A46

    /** Ticks before a shown outline looks at the ground under it again. */
    private const val RESURVEY = 60L
    /** Blocks from a post within which its bounds show. */
    private const val AT_POST = 6.5
    /** Blocks within which a charter in hand shows everyone's bounds. */
    private const val CHARTER_SIGHT = 160.0
    /** The curtain is whole within this many blocks of the eye, and gone this many further out. */
    private const val CLEAR = 56f
    private const val FADE = 28f

    /** Every homestead's bounds should be looked at afresh (one was claimed, changed tier or given up). */
    fun changed() {
        for (s in shown.values) s.surveyed = -1000L
    }

    fun reset() {
        shown.clear()
        marks = listOf()
        marksAlpha = 0f
        marksAlphaO = 0f
    }

    /** A homestead's colour as light: its dye's own, lifted so even a black one glows. */
    fun glow(color: Int): Int {
        val rgb = Dye.entries[color.coerceIn(0, Dye.entries.size - 1)].rgb
        return ARGB.srgbLerp(0.32f, rgb or (0xFF shl 24), -1) and 0xFFFFFF
    }

    // ---- Each tick: what to show ----

    fun tick(minecraft: Minecraft, level: ClientLevel, player: Player) {
        val now = HomesteadClient.ticks
        for (s in shown.values) s.want = false
        val overworld = level.dimension() == Level.OVERWORLD
        val holding = HomesteadClient.holdsCharter(player)

        if (overworld) {
            for (e in HomesteadClient.homesteads) {
                val dx = player.x - (e.post.x + 0.5)
                val dz = player.z - (e.post.z + 0.5)
                val d2 = dx * dx + dz * dz
                val atPost = d2 < AT_POST * AT_POST && abs(player.y - e.post.y) < 6
                val key = e.id.toLong()
                if (!(atPost || holding && d2 < CHARTER_SIGHT * CHARTER_SIGHT) && key !in shown) continue
                val s = shown.getOrPut(key) { Shown(key) }
                s.want = atPost || holding && d2 < CHARTER_SIGHT * CHARTER_SIGHT
                s.rgb = glow(e.color)
                s.height = 1.7f
                s.runner = 1f
                s.place(level, PlotFrame(e.post, Direction.from2DDataValue(e.facing)), PlotShape.plot(max(e.tier, e.building)), now, false, e.post.y)
            }
        }

        // The charter's own plot where it points
        val target = HomesteadClient.target
        if (overworld && target != null) {
            val facing = HomesteadClient.targetFacing
            val frame = PlotFrame(target, facing)
            val verdict = HomesteadClient.verdictFor(target, facing)
            val color = when {
                verdict == null -> PENDING
                verdict.ok -> OK
                else -> NO
            }
            val plot = shown.getOrPut(PREVIEW) { Shown(PREVIEW) }
            plot.want = true
            plot.rgb = color
            plot.height = 1.4f
            plot.runner = if (verdict == null) 2.2f else 1f
            plot.motes = verdict != null
            plot.post = target
            plot.place(level, frame, PlotShape.plot(1), now, true, target.y)
            val land = shown.getOrPut(PREVIEW_LAND) { Shown(PREVIEW_LAND) }
            land.want = true
            land.rgb = color
            land.height = 0.5f
            land.faint = true
            land.posts = false
            land.motes = false
            land.place(level, frame, PlotShape.RESERVED, now, true, target.y)
            // A lot at fault is outlined on its own
            val corners = verdict?.takeIf { !it.ok }?.highlight
            val lot = corners?.let(::lotOf)
            if (lot != null) {
                val s = shown.getOrPut(LOT) { Shown(LOT) }
                s.want = true
                s.rgb = NO
                s.height = 1.1f
                s.runner = 2.6f
                s.posts = true
                s.placeBox(level, lot.x0, lot.z0, lot.x1, lot.z1, now, false, target.y)
            }
            marks = if (verdict != null && !verdict.ok && lot == null) verdict.highlight else listOf()
            marksRed = true
        } else {
            val refusal = HomesteadClient.refusal
            marks = refusal?.highlight ?: listOf()
            marksRed = refusal?.ok != true
        }

        for (s in shown.values) s.ease()
        shown.values.removeIf { !it.want && it.alpha <= 0f }
        marksAlphaO = marksAlpha
        marksAlpha = if (marks.isNotEmpty()) min(1f, marksAlpha + 0.1f) else max(0f, marksAlpha - 0.1f)

        // Motes along the ribbons, and the pale lights going up at the corners
        for (s in shown.values) if (s.motes && s.alpha > 0.4f) motes(level, player, s, now)
    }

    /** The world columns a lot's four corners (as a survey picks them out) span, or null if they're not a lot's. */
    private fun lotOf(corners: List<BlockPos>): Rect? {
        if (corners.size != 4) return null
        val xs = corners.map { it.x }
        val zs = corners.map { it.z }
        if (xs.distinct().size != 2 || zs.distinct().size != 2) return null
        return Rect(xs.min(), zs.min(), xs.max(), zs.max())
    }

    private fun motes(level: ClientLevel, player: Player, s: Shown, now: Long) {
        val ring = s.ring
        if (ring.n == 0) return
        val random = level.random
        repeat(if (s.key >= 0) 2 else 1) {
            val k = random.nextInt(ring.n)
            val f = random.nextFloat()
            val x = ring.xs[k] + (ring.xs[k + 1] - ring.xs[k]) * f + s.slideX
            val z = ring.zs[k] + (ring.zs[k + 1] - ring.zs[k]) * f + s.slideZ
            val y = ring.ys[k] + (ring.ys[k + 1] - ring.ys[k]) * f
            val dx = x - player.x
            val dz = z - player.z
            if (dx * dx + dz * dz > 40.0 * 40.0) return@repeat
            level.addParticle(DustParticleOptions(s.color, 0.8f + random.nextFloat() * 0.5f), x.toDouble(), y + 0.15 + random.nextFloat() * s.height * 0.8, z.toDouble(), 0.0, 0.02, 0.0)
        }
        if (s.posts && now % 7L == Math.floorMod(s.key, 7L)) {
            for (c in ring.corners) {
                val x = ring.xs[c] + s.slideX
                val z = ring.zs[c] + s.slideZ
                val dx = x - player.x
                val dz = z - player.z
                if (dx * dx + dz * dz > 48.0 * 48.0) continue
                level.addParticle(ParticleTypes.END_ROD, x.toDouble(), ring.ys[c] + 0.4, z.toDouble(), 0.0, 0.04 + random.nextFloat() * 0.03, 0.0)
            }
        }
    }

    /**
     * The ground at column [x], [z] as the client sees it: the top of whatever stops a step, down past leaves, fences,
     * walls and banners (the corner stakes among them) to the land or the water.
     */
    private fun ground(level: ClientLevel, x: Int, z: Int, fallback: Int): Int {
        if (!level.hasChunk(x shr 4, z shr 4)) return fallback
        val cursor = BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z)
        var n = 0
        while (n++ < 16 && cursor.y > level.minY) {
            val state = level.getBlockState(cursor)
            if (!state.fluidState.isEmpty) break
            val skip = state.isAir || state.`is`(BlockTags.LEAVES) || state.block is FenceBlock || state.block is FenceGateBlock ||
                state.block is WallBlock || state.block is BannerBlock || state.block is WallBannerBlock
            if (!skip) break
            cursor.move(Direction.DOWN)
        }
        return cursor.y + 1
    }

    // ---- Drawing ----

    fun submit(event: SubmitCustomGeometryEvent) {
        if (shown.isEmpty() && marksAlpha <= 0f && marksAlphaO <= 0f) return
        val state = event.levelRenderState
        val camera = state.cameraRenderState.pos
        val partial = state.worldPartialTicks
        val time = HomesteadClient.ticks + partial
        // Drawn from a whole block near the eye, so far-off coordinates keep their precision
        val ox = floor(camera.x)
        val oy = floor(camera.y)
        val oz = floor(camera.z)
        val ex = (camera.x - ox).toFloat()
        val ez = (camera.z - oz).toFloat()
        val pose = event.poseStack
        pose.pushPose()
        pose.translate(ox - camera.x, oy - camera.y, oz - camera.z)
        val outlines = shown.values.toList()
        event.submitNodeCollector.submitCustomGeometry(pose, RenderTypes.eyes(CURTAIN)) { p, buffer ->
            for (s in outlines) {
                val a = s.alphaO + (s.alpha - s.alphaO) * partial
                if (a <= 0.01f || s.ring.n == 0) continue
                val sx = s.slideXO + (s.slideX - s.slideXO) * partial
                val sz = s.slideZO + (s.slideZ - s.slideZO) * partial
                curtain(buffer, p, s, a, time, (ox).toFloat(), oy.toFloat(), oz.toFloat(), sx, sz, ex, ez)
                if (s.posts) for (c in s.ring.corners) beam(buffer, p, s.ring.xs[c] + sx - ox.toFloat(), s.ring.ys[c] - oy.toFloat(), s.ring.zs[c] + sz - oz.toFloat(), s.color, a, s.height * 2.2f, time + c, ex, ez)
                s.post?.let { at ->
                    if (s.key == PREVIEW) beam(buffer, p, at.x + 0.5f + sx - ox.toFloat(), at.y - oy.toFloat(), at.z + 0.5f + sz - oz.toFloat(), s.color, a, 2.6f, time * 1.7f, ex, ez)
                }
            }
        }
        val ma = marksAlphaO + (marksAlpha - marksAlphaO) * partial
        if (ma > 0.01f && marks.isNotEmpty()) {
            val shownMarks = marks
            val color = if (marksRed) NO else OK
            event.submitNodeCollector.submitCustomGeometry(pose, RenderTypes.eyes(MARK)) { p, buffer ->
                val pulse = 0.55f + 0.45f * sin(time * 0.25f)
                for ((i, at) in shownMarks.withIndex()) {
                    val x = (at.x - ox).toFloat()
                    val y = (at.y - oy).toFloat()
                    val z = (at.z - oz).toFloat()
                    if (x * x + z * z > 72f * 72f) continue
                    // Each lights up a beat after the one before, so a row of them reads as a line
                    val beat = 0.75f + 0.25f * sin(time * 0.25f - i * 0.6f)
                    box(buffer, p, x, y, z, ARGB.color((ma * pulse * beat * 0.9f).coerceIn(0f, 1f), color))
                }
            }
        }
        pose.popPose()
    }

    /** The ribbon round [s]: a curtain of light on the ground, rippling, with a brighter light running round it. */
    private fun curtain(
        buffer: VertexConsumer, pose: PoseStack.Pose, s: Shown, a: Float, time: Float, ox: Float, oy: Float, oz: Float,
        sx: Float, sz: Float, ex: Float, ez: Float,
    ) {
        val ring = s.ring
        // It grows up out of the ground as it comes and sinks as it goes
        val rise = a * a * (3 - 2 * a)
        val h = s.height * (if (s.faint) 1f else rise)
        val base = if (s.faint) 0.28f else 0.62f
        val color = s.color
        val run = time * 0.32f * s.runner
        for (k in 0 until ring.n) {
            val x0 = ring.xs[k] + sx - ox
            val z0 = ring.zs[k] + sz - oz
            val x1 = ring.xs[k + 1] + sx - ox
            val z1 = ring.zs[k + 1] + sz - oz
            val y0 = ring.ys[k] - oy + 0.04f
            val y1 = ring.ys[k + 1] - oy + 0.04f
            val c0 = ARGB.color((a * base * light(ring.along[k], ring.length, time, run, s) * fade(x0 - ex, z0 - ez)).coerceIn(0f, 1f), color)
            val c1 = ARGB.color((a * base * light(ring.along[k + 1], ring.length, time, run, s) * fade(x1 - ex, z1 - ez)).coerceIn(0f, 1f), color)
            if (ARGB.alpha(c0) == 0 && ARGB.alpha(c1) == 0) continue
            // Both faces: the pipeline culls the back of a quad
            vertex(buffer, pose, x0, y0, z0, 0f, 1f, c0)
            vertex(buffer, pose, x1, y1, z1, 1f, 1f, c1)
            vertex(buffer, pose, x1, y1 + h, z1, 1f, 0f, c1)
            vertex(buffer, pose, x0, y0 + h, z0, 0f, 0f, c0)
            vertex(buffer, pose, x0, y0 + h, z0, 0f, 0f, c0)
            vertex(buffer, pose, x1, y1 + h, z1, 1f, 0f, c1)
            vertex(buffer, pose, x1, y1, z1, 1f, 1f, c1)
            vertex(buffer, pose, x0, y0, z0, 0f, 1f, c0)
        }
    }

    /** How bright the ribbon is [along] blocks round it: a slow ripple, and the light that runs round. */
    private fun light(along: Float, length: Float, time: Float, run: Float, s: Shown): Float {
        if (s.faint) return 0.75f + 0.25f * sin(along * 0.4f - time * 0.05f)
        val ripple = 0.62f + 0.38f * sin(along * 0.55f - time * 0.09f)
        var d = (along - run) % length
        if (d < 0) d += length
        d = min(d, length - d)
        val runner = exp(-d * d / 10f) * 0.9f
        // A second, fainter light the other way round, so a still plot is never quite still
        var e = (along + run * 0.6f) % length
        if (e < 0) e += length
        e = min(e, length - e)
        return min(1.6f, ripple + runner + exp(-e * e / 16f) * 0.35f)
    }

    /** The curtain's strength [dx], [dz] from the eye: whole near by, fading far off, and thin where you stand in it. */
    private fun fade(dx: Float, dz: Float): Float {
        val d = sqrt(dx * dx + dz * dz)
        val far = ((CLEAR + FADE - d) / FADE).coerceIn(0f, 1f)
        val near = ((d - 0.5f) / 1.5f).coerceIn(0.15f, 1f)
        return far * far * (3 - 2 * far) * near
    }

    /** A pale beam going up from a corner: two planes crossed, turning slowly, so it reads round from any side. */
    private fun beam(buffer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, rgb: Int, a: Float, height: Float, time: Float, ex: Float, ez: Float) {
        val strength = a * fade(x - ex, z - ez) * (0.75f + 0.25f * sin(time * 0.13f))
        if (strength <= 0.01f) return
        val color = ARGB.color(strength.coerceIn(0f, 1f), ARGB.srgbLerp(0.45f, rgb or (0xFF shl 24), -1) and 0xFFFFFF)
        val top = y + height * (a * a * (3 - 2 * a))
        val w = 0.16f
        val turn = time * 0.02f
        for (k in 0 until 2) {
            val ang = turn + k * (Math.PI.toFloat() / 2f)
            val cx = kotlin.math.cos(ang) * w
            val cz = sin(ang) * w
            vertex(buffer, pose, x - cx, y, z - cz, 0f, 1f, color)
            vertex(buffer, pose, x + cx, y, z + cz, 1f, 1f, color)
            vertex(buffer, pose, x + cx, top, z + cz, 1f, 0f, color)
            vertex(buffer, pose, x - cx, top, z - cz, 0f, 0f, color)
            vertex(buffer, pose, x - cx, top, z - cz, 0f, 0f, color)
            vertex(buffer, pose, x + cx, top, z + cz, 1f, 0f, color)
            vertex(buffer, pose, x + cx, y, z + cz, 1f, 1f, color)
            vertex(buffer, pose, x - cx, y, z - cz, 0f, 1f, color)
        }
    }

    /** A glowing frame round the block at [x], [y], [z]: its six faces, a hair outside it, seen from either side. */
    private fun box(buffer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, color: Int) {
        val e = 0.03f
        val x0 = x - e
        val y0 = y - e
        val z0 = z - e
        val x1 = x + 1 + e
        val y1 = y + 1 + e
        val z1 = z + 1 + e
        face(buffer, pose, color, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0)
        face(buffer, pose, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        face(buffer, pose, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0)
        face(buffer, pose, color, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0)
        face(buffer, pose, color, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1)
        face(buffer, pose, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
    }

    private fun face(
        buffer: VertexConsumer, pose: PoseStack.Pose, color: Int,
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
    ) {
        vertex(buffer, pose, ax, ay, az, 0f, 1f, color)
        vertex(buffer, pose, bx, by, bz, 1f, 1f, color)
        vertex(buffer, pose, cx, cy, cz, 1f, 0f, color)
        vertex(buffer, pose, dx, dy, dz, 0f, 0f, color)
        vertex(buffer, pose, dx, dy, dz, 0f, 0f, color)
        vertex(buffer, pose, cx, cy, cz, 1f, 0f, color)
        vertex(buffer, pose, bx, by, bz, 1f, 1f, color)
        vertex(buffer, pose, ax, ay, az, 0f, 1f, color)
    }

    private fun vertex(buffer: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, u: Float, v: Float, color: Int) {
        buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
    }
}
