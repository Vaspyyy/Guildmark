package io.github.vaspyyy.guildmark.client.economy

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.network.StallPennant
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The pennants over players' own stalls: a cloth in the renter's colour on a little rod over each let market hall
 * table, hung on two cords from the ceiling, its face to the buyers. It drops and unrolls as the table is let, with a
 * bounce at the end of its fall, and rolls up again as it's let go; in between it sways in the hall's air, swings and
 * turns when someone walks close past it, and a ripple runs down it. Drawn in the world from the list of let tables
 * the server sends (see StallTables), so it needs no block of its own and nothing in the hall changes.
 */
object StallPennants {
    private val TEXTURE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/market/stall_pennant.png")
    private val UNFURL = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "stall.pennant"))

    /** A pennant as it hangs: its table, how far it's unrolled, its swing and turn, and where it hangs from. */
    private class Hung(var p: StallPennant) {
        var want = true

        /** How far it's let down, 0 rolled up to 1 hanging full length; last tick and now. */
        var out = 0f
        var outO = 0f

        /** Its swing toward the buyers (radians) and how fast it's swinging; the same for its turn about the cords. */
        var swing = 0f
        var swingO = 0f
        var spin = 0f
        var twist = 0f
        var twistO = 0f
        var twistSpin = 0f

        /** How far along the table from the display's middle it hangs (to the middle of the table), and the rod's height. */
        var side = 0f
        var rod = p.at.y + ROD_HIGH

        /** What's over it, the underside's height; none found near enough above. */
        var ceiling: Double? = null
        var surveyed = -1000L

        /** Each pennant's own start, so two side by side don't sway as one. */
        val seed = ((p.at.asLong() * -7046029254386353131L) ushr 44) % 1000L
    }

    private val hung = LinkedHashMap<BlockPos, Hung>()

    /** Whether the first list since joining has come (those pennants are already up: they don't drop in). */
    private var heard = false
    private var ticks = 0L

    /** Starts the pennants with the client's ticks and its drawing of the world, and forgets them on leaving. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ -> tick() }
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent::class.java) { event: SubmitCustomGeometryEvent -> submit(event) }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ ->
            hung.clear()
            heard = false
        }
    }

    /** The let tables are now [pennants]: new ones drop in, gone ones roll up, and a table let to someone else drops in afresh. */
    fun set(pennants: List<StallPennant>) {
        val level = Minecraft.getInstance().level
        val fresh = pennants.associateBy { it.at }
        for (h in hung.values) {
            val p = fresh[h.p.at]
            if (p == null) {
                if (h.want) flap(level, h, 1.2f)
                h.want = false
                continue
            }
            if (!h.want || p.name != h.p.name) {
                h.out = 0f
                h.outO = 0f
                flap(level, h, 1f)
            }
            h.want = true
            h.p = p
        }
        for (p in pennants) if (p.at !in hung) {
            val h = Hung(p)
            if (heard) flap(level, h, 1f) else {
                h.out = 1f
                h.outO = 1f
            }
            hung[p.at] = h
        }
        heard = true
    }

    /** The cloth's sound as it unrolls or rolls up, to a player near enough to hear it. */
    private fun flap(level: ClientLevel?, h: Hung, pitch: Float) {
        val player = Minecraft.getInstance().player ?: return
        if (level == null || player.distanceToSqr(h.p.at.x + 0.5, h.rod, h.p.at.z + 0.5) > HEAR * HEAR) return
        level.playLocalSound(h.p.at.x + 0.5, h.rod, h.p.at.z + 0.5, UNFURL, SoundSource.BLOCKS, 0.55f, pitch * (0.95f + level.random.nextFloat() * 0.1f), false)
    }

    // ---- Each tick ----

    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        if (minecraft.isPaused || hung.isEmpty()) return
        ticks++
        val overworld = level.dimension() == Level.OVERWORLD
        for (h in hung.values) {
            h.outO = h.out
            h.out = if (h.want) minOf(1f, h.out + 1f / DROP) else maxOf(0f, h.out - 1f / ROLL)
            h.swingO = h.swing
            h.twistO = h.twist
            val at = h.p.at
            if (!overworld || player.distanceToSqr(at.x + 0.5, at.y + 0.5, at.z + 0.5) > SEEN * SEEN) continue
            if (ticks - h.surveyed >= SURVEY) survey(level, h)
            air(level, h)
        }
        hung.values.removeIf { !it.want && it.out <= 0f }
    }

    /** Where [h] hangs: over the middle of its table (the display and the slab beside it), on cords from what's above. */
    private fun survey(level: ClientLevel, h: Hung) {
        val at = h.p.at
        if (!level.hasChunk(at.x shr 4, at.z shr 4)) return
        h.surveyed = ticks
        val front = Direction.from2DDataValue(h.p.front)
        val table = level.getBlockState(at.below()).block
        val sides = listOf(front.clockWise, front.counterClockWise)
        val beside = sides.firstOrNull { level.getBlockState(at.below().relative(it)).block == table && level.getBlockState(at.relative(it)).block is LanternBlock }
            ?: sides.firstOrNull { level.getBlockState(at.below().relative(it)).block == table }
        h.side = when (beside) {
            null -> 0f
            front.clockWise -> 0.5f
            else -> -0.5f
        }
        h.ceiling = null
        val cursor = BlockPos.MutableBlockPos(at.x, at.y + 2, at.z)
        while (cursor.y <= at.y + REACH_UP) {
            val state = level.getBlockState(cursor)
            if (!state.isAir) {
                val shape = state.getShape(level, cursor)
                h.ceiling = cursor.y + if (shape.isEmpty) 0.0 else shape.min(Direction.Axis.Y)
                break
            }
            cursor.move(Direction.UP)
        }
        h.rod = (h.ceiling?.let { it - 0.14 } ?: (at.y + ROD_HIGH)).coerceIn(at.y + ROD_LOW, at.y + ROD_HIGH)
    }

    /** [h]'s swing in the hall's air: a slow sway, pushed along by anyone walking close past it, and settling. */
    private fun air(level: ClientLevel, h: Hung) {
        val t = ticks + h.seed.toFloat()
        val front = Direction.from2DDataValue(h.p.front)
        val along = front.clockWise
        val x = h.p.at.x + 0.5 + along.stepX * h.side
        val z = h.p.at.z + 0.5 + along.stepZ * h.side
        val sway = 0.05f * sin(t * 0.021f) + 0.025f * sin(t * 0.057f + 1.3f)
        h.spin += (sway - h.swing) * 0.02f
        h.twistSpin += (0.04f * sin(t * 0.017f + 0.7f) - h.twist) * 0.015f
        val box = AABB(x - PUSH, h.rod - 2.6, z - PUSH, x + PUSH, h.rod + 0.4, z + PUSH)
        for (e in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
            val dx = (e.x - e.xo).toFloat()
            val dz = (e.z - e.zo).toFloat()
            if (dx * dx + dz * dz < 0.0004f) continue
            h.spin += (dx * front.stepX + dz * front.stepZ) * 0.05f
            h.twistSpin += (dx * along.stepX + dz * along.stepZ) * 0.03f
        }
        h.spin *= 0.95f
        h.twistSpin *= 0.93f
        h.swing = (h.swing + h.spin).coerceIn(-0.7f, 0.7f)
        h.twist = (h.twist + h.twistSpin).coerceIn(-0.5f, 0.5f)
    }

    // ---- Drawing ----

    /** One pennant to draw this frame: where it hangs, how, and the light on it. */
    private class Frame(val h: Hung, val out: Float, val swing: Float, val twist: Float, val light: Int, val rgb: Int)

    private fun submit(event: SubmitCustomGeometryEvent) {
        if (hung.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension() != Level.OVERWORLD) return
        val state = event.levelRenderState
        val camera = state.cameraRenderState.pos
        val partial = state.worldPartialTicks
        val frames = ArrayList<Frame>()
        for (h in hung.values) {
            if (h.surveyed < 0) continue
            val dx = h.p.at.x + 0.5 - camera.x
            val dz = h.p.at.z + 0.5 - camera.z
            if (dx * dx + dz * dz > SEEN * SEEN) continue
            val out = h.outO + (h.out - h.outO) * partial
            if (out <= 0.01f) continue
            val light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(h.p.at.x + 0.5, h.rod - 0.4, h.p.at.z + 0.5))
            val rgb = Dye.entries[h.p.color.coerceIn(0, Dye.entries.size - 1)].rgb
            frames.add(Frame(h, out, h.swingO + (h.swing - h.swingO) * partial, h.twistO + (h.twist - h.twistO) * partial, light, rgb))
        }
        if (frames.isEmpty()) return
        val time = ticks + partial
        // Drawn from a whole block near the eye, so far-off coordinates keep their precision
        val ox = floor(camera.x)
        val oy = floor(camera.y)
        val oz = floor(camera.z)
        val pose = event.poseStack
        pose.pushPose()
        pose.translate(ox - camera.x, oy - camera.y, oz - camera.z)
        event.submitNodeCollector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE)) { p, buffer ->
            for (f in frames) pennant(buffer, p, f, time, ox, oy, oz)
        }
        pose.popPose()
    }

    /** A point of a pennant, from its rod's middle: [a] along the rod, [y] up, [f] out toward the buyers. */
    private class Place(val x: Float, val y: Float, val z: Float, val ax: Float, val az: Float, val fx: Float, val fz: Float)

    /** [f]'s pennant: its cords, its rod, the cloth hanging from it and, while it isn't all the way down, the roll at its foot. */
    private fun pennant(buffer: VertexConsumer, pose: PoseStack.Pose, f: Frame, time: Float, ox: Double, oy: Double, oz: Double) {
        val h = f.h
        val front = Direction.from2DDataValue(h.p.front)
        val along = front.clockWise
        val at = Place(
            (h.p.at.x + 0.5 + along.stepX * h.side - ox).toFloat(), (h.rod - oy).toFloat(), (h.p.at.z + 0.5 + along.stepZ * h.side - oz).toFloat(),
            along.stepX.toFloat(), along.stepZ.toFloat(), front.stepX.toFloat(), front.stepZ.toFloat(),
        )
        val light = f.light

        // The cords, up to whatever it hangs from
        val up = h.ceiling?.let { (it - h.rod).toFloat() }?.coerceIn(0.05f, REACH_UP.toFloat()) ?: CORD_FREE
        for (side in floatArrayOf(-CORD_AT, CORD_AT)) cord(buffer, pose, at, side, ROD_THICK, up, light)

        // The rod: a thin bar the width of the cloth and a little more
        bar(buffer, pose, at, -ROD_HALF, ROD_HALF, 0f, 0f, ROD_THICK * 2f, ROD_U0, ROD_U1, ROD_V0, ROD_V1, WHITE, light)

        // The cloth: rows down from the rod, swinging as one with a ripple running down it and a turn about the cords
        val o = if (h.want) drop(f.out) else smooth(f.out)
        val length = CLOTH_LENGTH * o
        val shown = minOf(1f, o)
        val color = ARGB.color(255, f.rgb)
        var lastY = 0f
        var lastF = 0f
        var lastTwist = 0f
        var lastV = CLOTH_V0
        for (i in 0..ROWS) {
            val k = i.toFloat() / ROWS
            val down = k * length
            val ripple = sin(k * 5.5f - time * 0.16f + h.seed) * 0.022f * k * (0.6f + minOf(1f, abs(f.swing) * 6f))
            val y = -ROD_THICK - cos(f.swing) * down
            val fo = sin(f.swing) * down + ripple
            val tw = f.twist * k * CLOTH_HALF
            val v = CLOTH_V0 + (CLOTH_V1 - CLOTH_V0) * k * shown
            if (i > 0) {
                // A row leaning out toward the buyers catches a little more light than one leaning back
                val lean = (fo - lastF) / (lastY - y).coerceAtLeast(1e-3f)
                val c = ARGB.scaleRGB(color, 0.88f + 0.12f * lean.coerceIn(-1f, 1f))
                put(buffer, pose, at, -CLOTH_HALF, lastY, lastF + lastTwist, CLOTH_U0, lastV, c, light)
                put(buffer, pose, at, CLOTH_HALF, lastY, lastF - lastTwist, CLOTH_U1, lastV, c, light)
                put(buffer, pose, at, CLOTH_HALF, y, fo - tw, CLOTH_U1, v, c, light)
                put(buffer, pose, at, -CLOTH_HALF, y, fo + tw, CLOTH_U0, v, c, light)
            }
            lastY = y
            lastF = fo
            lastTwist = tw
            lastV = v
        }

        // The rest of the cloth, still rolled up at its foot
        if (o < 0.98f) {
            val r = ROLL_MIN + ROLL_MORE * (1f - o).coerceIn(0f, 1f)
            bar(buffer, pose, at, -CLOTH_HALF, CLOTH_HALF, lastY - r * 0.5f, lastF, r, ROLL_U0, ROLL_U1, ROLL_V0, ROLL_V1, color, light)
        }
    }

    /** One of the cords, at [a] along the rod, from the rod's top up [up] blocks: two strips crossed, so it reads from any side. */
    private fun cord(buffer: VertexConsumer, pose: PoseStack.Pose, at: Place, a: Float, from: Float, up: Float, light: Int) {
        var y = from
        val top = from + up
        while (y < top - 1e-3f) {
            val y1 = minOf(top, y + 1f)
            val v1 = CORD_V0 + (CORD_V1 - CORD_V0) * (y1 - y)
            put(buffer, pose, at, a - CORD_HALF, y, 0f, CORD_U0, v1, WHITE, light)
            put(buffer, pose, at, a + CORD_HALF, y, 0f, CORD_U1, v1, WHITE, light)
            put(buffer, pose, at, a + CORD_HALF, y1, 0f, CORD_U1, CORD_V0, WHITE, light)
            put(buffer, pose, at, a - CORD_HALF, y1, 0f, CORD_U0, CORD_V0, WHITE, light)
            put(buffer, pose, at, a, y, -CORD_HALF, CORD_U0, v1, WHITE, light)
            put(buffer, pose, at, a, y, CORD_HALF, CORD_U1, v1, WHITE, light)
            put(buffer, pose, at, a, y1, CORD_HALF, CORD_U1, CORD_V0, WHITE, light)
            put(buffer, pose, at, a, y1, -CORD_HALF, CORD_U0, CORD_V0, WHITE, light)
            y = y1
        }
    }

    /** A bar from [a0] to [a1] along the rod's line, [size] thick, its middle [y] high and [f] out toward the buyers: its four long faces. */
    private fun bar(
        buffer: VertexConsumer, pose: PoseStack.Pose, at: Place, a0: Float, a1: Float, y: Float, f: Float, size: Float,
        u0: Float, u1: Float, v0: Float, v1: Float, color: Int, light: Int,
    ) {
        val s = size * 0.5f
        val corners = arrayOf(floatArrayOf(s, s), floatArrayOf(s, -s), floatArrayOf(-s, -s), floatArrayOf(-s, s), floatArrayOf(s, s))
        for (i in 0 until 4) {
            val (y0, f0) = corners[i].let { it[0] to it[1] }
            val (y1, f1) = corners[i + 1].let { it[0] to it[1] }
            val c = ARGB.scaleRGB(color, if (i == 0) 1f else if (i == 2) 0.7f else 0.85f)
            put(buffer, pose, at, a0, y + y0, f + f0, u0, v0, c, light)
            put(buffer, pose, at, a1, y + y0, f + f0, u1, v0, c, light)
            put(buffer, pose, at, a1, y + y1, f + f1, u1, v1, c, light)
            put(buffer, pose, at, a0, y + y1, f + f1, u0, v1, c, light)
        }
    }

    /** A vertex at [a], [y], [f] from the rod's middle (see [Place]), lit as though facing the buyers and a little up. */
    private fun put(buffer: VertexConsumer, pose: PoseStack.Pose, at: Place, a: Float, y: Float, f: Float, u: Float, v: Float, color: Int, light: Int) {
        buffer.addVertex(pose, at.x + at.ax * a + at.fx * f, at.y + y, at.z + at.az * a + at.fz * f)
            .setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, at.fx * 0.958f, 0.287f, at.fz * 0.958f)
    }

    /** How far down a dropping pennant is at [k] of its fall: past its length a moment at the end, and back. */
    private fun drop(k: Float): Float {
        val c = 1.6f
        val m = k - 1f
        return 1f + (c + 1f) * m * m * m + c * m * m
    }

    private fun smooth(k: Float): Float = k * k * (3f - 2f * k)

    /** The cords' and the rod's own colours, untinted. */
    private const val WHITE = -1

    /** Blocks within which pennants are drawn and sway, and a player hears them drop. */
    private const val SEEN = 64.0
    private const val HEAR = 24.0

    /** Ticks between looks at what a pennant hangs from, ticks it takes to drop, and to roll up. */
    private const val SURVEY = 100L
    private const val DROP = 18f
    private const val ROLL = 14f

    /** How near a pennant (across) someone walking past pushes it. */
    private const val PUSH = 1.4

    /** How far over the display its rod may hang (under a low ceiling, as low as [ROD_LOW]), and how far up a ceiling is looked for. */
    private const val ROD_HIGH = 2.3
    private const val ROD_LOW = 1.75
    private const val REACH_UP = 8

    /** The pennant's parts, in blocks: the rod's half-length and thickness, the cloth's half-width and length, the cords. */
    private const val ROD_HALF = 0.34f
    private const val ROD_THICK = 0.022f
    private const val CLOTH_HALF = 0.28f
    private const val CLOTH_LENGTH = 0.84f
    private const val CORD_AT = 0.27f
    private const val CORD_HALF = 0.016f
    private const val CORD_FREE = 1.2f
    private const val ROLL_MIN = 0.03f
    private const val ROLL_MORE = 0.05f
    private const val ROWS = 10

    /** Where each part is on the texture (art/scripts/stall_art.py): cloth, rod, cord and the roll, as shares of the 64 by 64 sheet. */
    private const val CLOTH_U0 = 0f
    private const val CLOTH_U1 = 0.5f
    private const val CLOTH_V0 = 0f
    private const val CLOTH_V1 = 0.75f
    private const val ROD_U0 = 0.5f
    private const val ROD_U1 = 1f
    private const val ROD_V0 = 0f
    private const val ROD_V1 = 0.0625f
    private const val CORD_U0 = 0.5f
    private const val CORD_U1 = 0.5625f
    private const val CORD_V0 = 0.125f
    private const val CORD_V1 = 0.625f
    private const val ROLL_U0 = 0f
    private const val ROLL_U1 = 0.5f
    private const val ROLL_V0 = 0.8125f
    private const val ROLL_V1 = 0.9375f
}
