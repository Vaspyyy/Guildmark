package io.github.vaspyyy.guildmark.client.streets

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlock
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTableBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** What the alms table's pot and bowls need for one frame. */
class AlmsTableRenderState : BlockEntityRenderState() {
    var pot = false
    /** How full the pot is, 0 to 1, as it's seen (easing to the soup that's in it). */
    var fill = 0f
    var bowls = 0
    var front: Direction = Direction.NORTH
    /** Game time with the frame's fraction (wrapped every hour, for the simmer). */
    var time = 0f
    /** Ticks since the ladle last dipped, a gift was last tipped in, and the last bowl was set out. */
    var ladled = 1e9f
    var given = 1e9f
    var bowlAge = 1e9f
    val bowl = ItemStackRenderState()
}

/**
 * The alms table's pot and bowls (M6 Part D2, townsfolk/streets/AlmsTable), drawn over its middle block until Part
 * F's art takes their place: an iron pot whose soup stands as high as there's soup in it (rising as a gift is tipped
 * in, going down as it's ladled out), simmering with a bubble now and then, rippling where the ladle dips and
 * splashing as food goes in; and the bowls set out along the table's front either side of it, each set down as it
 * comes. The steam is the block's own (AlmsTableBlock.animateTick); the pot's simmer is heard near it ([StreetsClient]).
 */
class AlmsTableRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<AlmsTableBlockEntity, AlmsTableRenderState> {
    private val items: ItemModelResolver = context.itemModelResolver()

    override fun createRenderState(): AlmsTableRenderState = AlmsTableRenderState()

    override fun extractRenderState(
        be: AlmsTableBlockEntity, state: AlmsTableRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val now = level.gameTime
        state.pot = be.pot
        state.front = be.blockState.getValue(AlmsTableBlock.FACING)
        val shown = if (be.shown < 0f) be.soup.toFloat() else be.shown
        state.fill = (shown / be.cap.coerceAtLeast(1)).coerceIn(0f, 1f)
        state.bowls = be.bowls
        state.time = (now % HOUR).toFloat() + partialTicks
        state.ladled = if (be.ladled > 0L) (now - be.ladled).coerceIn(0L, 1_000_000L) + partialTicks else 1e9f
        state.given = if (be.given > 0L) (now - be.given).coerceIn(0L, 1_000_000L) + partialTicks else 1e9f
        state.bowlAge = (now - be.bowlAt).coerceIn(0L, 1_000_000L) + partialTicks
        if (be.bowls > 0) items.updateForTopItem(state.bowl, BOWL, ItemDisplayContext.FIXED, level, null, be.blockPos.hashCode())
    }

    override fun submit(state: AlmsTableRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val front = state.front
        val fx = front.stepX.toFloat()
        val fz = front.stepZ.toFloat()
        if (state.pot) {
            val mesh = Mesh()
            pot(mesh, state, fx, fz)
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(ATLAS)) { pose, buffer -> mesh.emit(buffer, pose) }
        }
        // The bowls along the front, the keeper's right first, the newest set down onto the table
        if (state.bowls > 0 && !state.bowl.isEmpty) {
            val along = AlmsTableBlock.along(front)
            for (i in 0 until state.bowls) {
                val side = if (i % 2 == 0) 1 else -1
                val step = OUT + SPACING * (i / 2)
                val drop = if (i == state.bowls - 1 && state.bowlAge < SET) (1 - state.bowlAge / SET).let { it * it } * 0.3f else 0f
                poseStack.pushPose()
                poseStack.translate(0.5f + along.stepX * side * step + fx * FORWARD, TOP + BOWL_UP + drop, 0.5f + along.stepZ * side * step + fz * FORWARD)
                poseStack.rotateDegrees(Axis.YP, -front.toYRot() + ((i * 37) % 21 - 10).toFloat())
                poseStack.scale(BOWL_SIZE, BOWL_SIZE, BOWL_SIZE)
                state.bowl.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
                poseStack.popPose()
            }
        }
    }

    /** The pot on the table's middle, its soup, the simmer's bubbles, the ladle's ripple and a gift's splash. */
    private fun pot(m: Mesh, s: AlmsTableRenderState, fx: Float, fz: Float) {
        val light = s.lightCoords
        // The body, fuller about its middle, and its lip
        for (k in 0 until SIDES) {
            m.band(RING[0], RING[1], k, IRON_TINT, light)
            m.band(RING[1], RING[2], k, IRON_TINT, light)
            m.lip(k, light)
        }
        // Its floor inside, darker
        m.disc(CX, FLOOR, CZ, R_FLOOR, PLAIN_IRON, ARGB.scaleRGB(IRON_TINT, 0.65f), light)
        if (s.fill <= 0.002f) return
        val t = s.time
        // The soup: as high as the pot's fill, rocking a little as it simmers
        val y = FLOOR + 0.01f + (RIM - 0.03f - FLOOR) * s.fill + 0.004f * sin(t * 0.35f) * min(1f, s.fill * 4)
        val r = radiusAt(y) - 0.012f
        m.disc(CX, y, CZ, r, SOUP_UV, SOUP, light)
        // A bubble now and then, swelling and bursting
        for (b in 0 until 3) {
            val cycle = t * 0.045f + b * 0.33f
            val n = floor(cycle).toInt()
            val phase = cycle - n
            if (phase > 0.85f) continue
            val a = hash(n * 3 + b) * 2 * PI.toFloat()
            val d = hash(n * 7 + b * 5 + 1) * r * 0.7f
            m.disc(CX + cos(a) * d, y + 0.003f, CZ + sin(a) * d, 0.012f + 0.022f * phase, SOUP_UV, BUBBLE, light, sides = 6)
        }
        // Where the ladle dips, at the keeper's side of it
        if (s.ladled < RIPPLE) {
            val k = s.ladled / RIPPLE
            m.ring(CX - fx * 0.08f, y + 0.004f, CZ - fz * 0.08f, min(r * 0.85f, 0.02f + 0.1f * k), 0.018f * (1 - k), BUBBLE, light)
        }
        // A gift tipped in: a ring out to the sides and a few drops thrown up
        if (s.given < SPLASH) {
            val k = s.given / SPLASH
            m.ring(CX, y + 0.005f, CZ, min(r * 0.92f, 0.03f + 0.22f * k), 0.026f * (1 - k), BUBBLE, light)
            val age = s.given
            for (j in 0 until 6) {
                val a = j * PI.toFloat() / 3 + 0.4f
                val d = 0.04f + 0.0065f * age
                val h = 0.03f * age - 0.0026f * age * age
                if (h <= 0f || d > r) continue
                m.disc(CX + cos(a) * d, y + h, CZ + sin(a) * d, 0.014f, SOUP_UV, SOUP, light, sides = 4)
            }
        }
    }

    override fun getRenderBoundingBox(be: AlmsTableBlockEntity): AABB = AABB(be.blockPos).inflate(1.2, 0.0, 1.2).expandTowards(0.0, 0.6, 0.0)

    /** Quads waiting to be drawn (position, texture, colour, light), each drawn both ways round so either side shows. */
    private class Mesh {
        private var data = FloatArray(7 * 128)
        private var count = 0

        fun vertex(x: Float, y: Float, z: Float, u: Float, v: Float, color: Int, light: Int) {
            if ((count + 1) * 7 > data.size) data = data.copyOf(data.size * 2)
            val i = count * 7
            data[i] = x
            data[i + 1] = y
            data[i + 2] = z
            data[i + 3] = u
            data[i + 4] = v
            data[i + 5] = Float.fromBits(color)
            data[i + 6] = Float.fromBits(light)
            count++
        }

        /** Side [k] of the pot's body between two of its rings (each a height and a radius). */
        fun band(lo: FloatArray, hi: FloatArray, k: Int, color: Int, light: Int) {
            val a0 = angle(k)
            val a1 = angle(k + 1)
            val u0 = IRON_UV.u0
            val u1 = IRON_UV.u1
            vertex(CX + cos(a0) * lo[1], lo[0], CZ + sin(a0) * lo[1], u0, IRON_UV.v1, color, light)
            vertex(CX + cos(a1) * lo[1], lo[0], CZ + sin(a1) * lo[1], u1, IRON_UV.v1, color, light)
            vertex(CX + cos(a1) * hi[1], hi[0], CZ + sin(a1) * hi[1], u1, IRON_UV.v0, color, light)
            vertex(CX + cos(a0) * hi[1], hi[0], CZ + sin(a0) * hi[1], u0, IRON_UV.v0, color, light)
        }

        /** Side [k] of the pot's lip: its flat top and its outer edge. */
        fun lip(k: Int, light: Int) {
            val a0 = angle(k)
            val a1 = angle(k + 1)
            val inner = RING[2][1]
            val outer = inner + LIP
            val c = ARGB.scaleRGB(IRON_TINT, 1.15f)
            vertex(CX + cos(a0) * inner, RIM, CZ + sin(a0) * inner, IRON_UV.u0, IRON_UV.v0, c, light)
            vertex(CX + cos(a1) * inner, RIM, CZ + sin(a1) * inner, IRON_UV.u1, IRON_UV.v0, c, light)
            vertex(CX + cos(a1) * outer, RIM, CZ + sin(a1) * outer, IRON_UV.u1, IRON_UV.v(0.2f), c, light)
            vertex(CX + cos(a0) * outer, RIM, CZ + sin(a0) * outer, IRON_UV.u0, IRON_UV.v(0.2f), c, light)
            vertex(CX + cos(a0) * outer, RIM, CZ + sin(a0) * outer, IRON_UV.u0, IRON_UV.v0, IRON_TINT, light)
            vertex(CX + cos(a1) * outer, RIM, CZ + sin(a1) * outer, IRON_UV.u1, IRON_UV.v0, IRON_TINT, light)
            vertex(CX + cos(a1) * outer, RIM - LIP, CZ + sin(a1) * outer, IRON_UV.u1, IRON_UV.v(0.2f), IRON_TINT, light)
            vertex(CX + cos(a0) * outer, RIM - LIP, CZ + sin(a0) * outer, IRON_UV.u0, IRON_UV.v(0.2f), IRON_TINT, light)
        }

        /** A flat disc of [sides] at height [y], from its middle out (each side a quad with its two inner corners at the middle). */
        fun disc(x: Float, y: Float, z: Float, r: Float, uv: Tile, color: Int, light: Int, sides: Int = SIDES) {
            for (k in 0 until sides) {
                val a0 = k * 2 * PI.toFloat() / sides
                val a1 = (k + 1) * 2 * PI.toFloat() / sides
                vertex(x, y, z, uv.u(0.5f), uv.v(0.5f), color, light)
                vertex(x, y, z, uv.u(0.5f), uv.v(0.5f), color, light)
                vertex(x + cos(a1) * r, y, z + sin(a1) * r, uv.u(0.5f + 0.5f * cos(a1)), uv.v(0.5f + 0.5f * sin(a1)), color, light)
                vertex(x + cos(a0) * r, y, z + sin(a0) * r, uv.u(0.5f + 0.5f * cos(a0)), uv.v(0.5f + 0.5f * sin(a0)), color, light)
            }
        }

        /** A flat ring [width] wide at radius [r] (a ripple). */
        fun ring(x: Float, y: Float, z: Float, r: Float, width: Float, color: Int, light: Int) {
            if (width <= 0.001f) return
            val inner = (r - width / 2).coerceAtLeast(0f)
            val outer = r + width / 2
            for (k in 0 until 12) {
                val a0 = k * PI.toFloat() / 6
                val a1 = (k + 1) * PI.toFloat() / 6
                vertex(x + cos(a0) * inner, y, z + sin(a0) * inner, SOUP_UV.u0, SOUP_UV.v0, color, light)
                vertex(x + cos(a1) * inner, y, z + sin(a1) * inner, SOUP_UV.u1, SOUP_UV.v0, color, light)
                vertex(x + cos(a1) * outer, y, z + sin(a1) * outer, SOUP_UV.u1, SOUP_UV.v1, color, light)
                vertex(x + cos(a0) * outer, y, z + sin(a0) * outer, SOUP_UV.u0, SOUP_UV.v1, color, light)
            }
        }

        fun emit(buffer: VertexConsumer, pose: PoseStack.Pose) {
            var q = 0
            while (q + 3 < count) {
                for (k in 0 until 4) put(buffer, pose, q + k)
                for (k in 3 downTo 0) put(buffer, pose, q + k)
                q += 4
            }
        }

        private fun put(buffer: VertexConsumer, pose: PoseStack.Pose, n: Int) {
            val i = n * 7
            buffer.addVertex(pose, data[i], data[i + 1], data[i + 2]).setColor(data[i + 5].toRawBits()).setUv(data[i + 3], data[i + 4])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(data[i + 6].toRawBits()).setNormal(pose, 0f, 1f, 0f)
        }
    }

    /** A rectangle of the festivals' ornaments' atlas, in its units. */
    private class Tile(x0: Float, y0: Float, x1: Float, y1: Float) {
        val u0 = x0 / SIZE
        val v0 = y0 / SIZE
        val u1 = x1 / SIZE
        val v1 = y1 / SIZE
        fun u(t: Float): Float = u0 + (u1 - u0) * t
        fun v(t: Float): Float = v0 + (v1 - v0) * t
    }

    companion object {
        private val ATLAS = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/festival/ornaments.png")
        private const val SIZE = 256f
        private val IRON_UV = Tile(128f, 0f, 144f, 16f)
        private val PLAIN_IRON = Tile(130f, 2f, 142f, 14f)
        /** The cloth's plain weave, tinted for the soup. */
        private val SOUP_UV = Tile(5f, 3f, 11f, 6f)

        private val BOWL = ItemStack(Items.BOWL)

        /** The pot: its middle, the table top it stands on, its floor inside and its rim (blocks, in its block). */
        private const val CX = 0.5f
        private const val CZ = 0.5f
        private val TOP = AlmsTableBlock.TOP.toFloat()
        private val RIM = AlmsTableBlock.POT_RIM.toFloat()
        private val FLOOR = TOP + 0.03f
        private const val R_FLOOR = 0.2f
        private const val LIP = 0.028f
        private const val SIDES = 10

        /** The pot's body's rings, bottom to top: each its height and radius. */
        private val RING = arrayOf(floatArrayOf(TOP + 0.004f, 0.2f), floatArrayOf(TOP + 0.17f, 0.25f), floatArrayOf(RIM, 0.235f))

        /** The pot's colours, and the soup's and its bubbles'. */
        private const val IRON_TINT = -1
        private const val SOUP = 0xFFC07A3A.toInt()
        private const val BUBBLE = 0xFFE0A866.toInt()

        /** Ticks the ladle's ripple and a gift's splash last, and a bowl takes to be set down. */
        private const val RIPPLE = 22f
        private const val SPLASH = 26f
        private const val SET = 6f

        /** The bowls: how far out from the middle the first stands, how far apart, how far to the front, how high and how big. */
        private const val OUT = 0.62f
        private const val SPACING = 0.3f
        private const val FORWARD = 0.2f
        private const val BOWL_UP = 0.16f
        private const val BOWL_SIZE = 0.42f

        private const val HOUR = 72000L

        private fun angle(k: Int): Float = (k % SIDES) * 2 * PI.toFloat() / SIDES

        /** The pot's inside's radius at height [y]. */
        private fun radiusAt(y: Float): Float {
            if (y <= RING[1][0]) {
                val f = ((y - RING[0][0]) / (RING[1][0] - RING[0][0])).coerceIn(0f, 1f)
                return RING[0][1] + (RING[1][1] - RING[0][1]) * f
            }
            val f = ((y - RING[1][0]) / (RING[2][0] - RING[1][0])).coerceIn(0f, 1f)
            return RING[1][1] + (RING[2][1] - RING[1][1]) * f
        }

        /** A number from 0 to 1 fixed by [n]. */
        private fun hash(n: Int): Float {
            var h = n * -0x61c88647
            h = h xor (h ushr 15)
            h *= 0x2c1b3c6d
            h = h xor (h ushr 12)
            return (h ushr 8) / 16777216f
        }
    }
}
