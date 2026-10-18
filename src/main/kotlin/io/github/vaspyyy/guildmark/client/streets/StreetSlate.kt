package io.github.vaspyyy.guildmark.client.streets

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.locale.Language
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The shut stall's slate (townsfolk/streets/Awnings, M6 Part D2), drawn with the market display it leans on
 * (client/economy/WaresRenderer, from [io.github.vaspyyy.guildmark.economy.WaresBlockEntity.slate]): a little slate in
 * a wooden frame, lifted over the goods from the keeper's side and leant on the display's front, rocking as it settles,
 * where "Nothing today" is chalked on it a letter at a time; taken away again, it goes back over the way it came. Made
 * of the festivals' ornaments' atlas (its iron for the slate, its wood for the frame): no art of its own.
 */
object StreetSlate {
    private val ATLAS = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/entity/festival/ornaments.png")
    private const val SIZE = 256f

    /** The atlas's tiles: the iron (a dark speckled grey, the slate) and the wood (the frame). */
    private const val IRON_U = 128f
    private const val WOOD_U = 112f

    /**
     * Draws the slate on a display whose front is to [facing], lit [light]: [up] while it's there, [age] ticks since it
     * was set down (huge for "there already"); else [gone] ticks since it was taken away.
     */
    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, font: Font, facing: Direction, light: Int, up: Boolean, age: Float, gone: Float) {
        // How far it's come from the keeper's side (0) to leaning in its place (1), and its size as it goes
        val u: Float
        var size = 1f
        if (up) {
            u = ease(age / PLACE)
        } else {
            if (gone >= AWAY) return
            u = 1f - ease(gone / AWAY)
            size = 1f - smooth((gone - AWAY * 0.5f) / (AWAY * 0.5f))
            if (size <= 0.01f) return
        }
        val y = START_Y * (1 - u) + ARC * sin(PI.toFloat() * u)
        val z = START_Z + (REST_Z - START_Z) * u
        var lean = START_LEAN + (REST_LEAN - START_LEAN) * u
        // It rocks a moment as it comes to rest against the display
        if (up && age > PLACE && age < PLACE + 20f) lean += ROCK * exp(-(age - PLACE) / 2.5f) * sin((age - PLACE) * 1.6f)
        poseStack.pushPose()
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, -facing.toYRot())
        poseStack.translate(0f, y, z)
        poseStack.rotateDegrees(Axis.XP, lean)
        if (size != 1f) poseStack.scale(size, size, size)
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(ATLAS)) { pose, buffer -> board(pose, buffer, light) }
        // The chalk, a letter at a time once it's set down
        val text = text()
        val written = if (!up) text.letters else ((age - WRITE_FROM) / PER_LETTER).toInt().coerceIn(0, text.letters)
        if (written > 0) chalk(poseStack, collector, font, text, written, light)
        poseStack.popPose()
    }

    // ---- The board ----

    /** The board in its own frame: its foot's middle at the origin, its face to +z, its top to +y. */
    private fun board(pose: PoseStack.Pose, buffer: VertexConsumer, light: Int) {
        val w = FACE_W / 2
        // The slate, a hair behind the frame's front
        quad(pose, buffer, -w, BAR, w, BAR + FACE_H, 0f, IRON_U, 1f, SLATE, light)
        // The frame round it
        val x0 = -w - BAR
        val x1 = w + BAR
        val y1 = BAR * 2 + FACE_H
        box(pose, buffer, x0, 0f, -DEPTH, x1, BAR, PROUD, light)
        box(pose, buffer, x0, y1 - BAR, -DEPTH, x1, y1, PROUD, light)
        box(pose, buffer, x0, BAR, -DEPTH, -w, y1 - BAR, PROUD, light)
        box(pose, buffer, w, BAR, -DEPTH, x1, y1 - BAR, PROUD, light)
        // Its back, boarded
        quad(pose, buffer, -w, BAR, w, y1 - BAR, -DEPTH + 0.004f, WOOD_U, -1f, WOOD, light)
    }

    /** A box of the frame's wood, every side, seen from either side. */
    private fun box(pose: PoseStack.Pose, buffer: VertexConsumer, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, light: Int) {
        quad(pose, buffer, x0, y0, x1, y1, z1, WOOD_U, 1f, WOOD, light)
        quad(pose, buffer, x0, y0, x1, y1, z0, WOOD_U, -1f, WOOD, light)
        side(pose, buffer, x0, y0, z0, y1, z1, -1f, light)
        side(pose, buffer, x1, y0, z0, y1, z1, 1f, light)
        flat(pose, buffer, x0, y0, z0, x1, z1, -1f, light)
        flat(pose, buffer, x0, y1, z0, x1, z1, 1f, light)
    }

    /** A face across x and y at [z] facing [nz] (+1 the front), textured from the tile at [tu], seen from either side. */
    private fun quad(pose: PoseStack.Pose, buffer: VertexConsumer, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, tu: Float, nz: Float, color: Int, light: Int) {
        val u0 = tu / SIZE
        val u1 = (tu + min(16f, (x1 - x0) * 16f)) / SIZE
        val v1 = min(16f, (y1 - y0) * 16f) / SIZE
        both(pose, buffer, floatArrayOf(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z), floatArrayOf(u0, v1, u1, v1, u1, 0f, u0, 0f), 0f, 0f, nz, color, light)
    }

    /** A face across z and y at [x] (the frame's ends). */
    private fun side(pose: PoseStack.Pose, buffer: VertexConsumer, x: Float, y0: Float, z0: Float, y1: Float, z1: Float, nx: Float, light: Int) {
        val u1 = (WOOD_U + min(16f, (z1 - z0) * 16f)) / SIZE
        val v1 = min(16f, (y1 - y0) * 16f) / SIZE
        both(pose, buffer, floatArrayOf(x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0), floatArrayOf(WOOD_U / SIZE, v1, u1, v1, u1, 0f, WOOD_U / SIZE, 0f), nx, 0f, 0f, WOOD, light)
    }

    /** A face across x and z at [y] (the frame's top and bottom). */
    private fun flat(pose: PoseStack.Pose, buffer: VertexConsumer, x0: Float, y: Float, z0: Float, x1: Float, z1: Float, ny: Float, light: Int) {
        val u1 = (WOOD_U + min(16f, (x1 - x0) * 16f)) / SIZE
        val v1 = min(16f, (z1 - z0) * 16f) / SIZE
        both(pose, buffer, floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1), floatArrayOf(WOOD_U / SIZE, 0f, u1, 0f, u1, v1, WOOD_U / SIZE, v1), 0f, ny, 0f, WOOD, light)
    }

    /** Four corners (x, y, z each) with their texture, drawn both ways round so either side shows. */
    private fun both(pose: PoseStack.Pose, buffer: VertexConsumer, p: FloatArray, uv: FloatArray, nx: Float, ny: Float, nz: Float, color: Int, light: Int) {
        for (k in 0 until 4) vertex(pose, buffer, p, uv, k, nx, ny, nz, color, light)
        for (k in 3 downTo 0) vertex(pose, buffer, p, uv, k, -nx, -ny, -nz, color, light)
    }

    private fun vertex(pose: PoseStack.Pose, buffer: VertexConsumer, p: FloatArray, uv: FloatArray, k: Int, nx: Float, ny: Float, nz: Float, color: Int, light: Int) {
        buffer.addVertex(pose, p[k * 3], p[k * 3 + 1], p[k * 3 + 2]).setColor(color).setUv(uv[k * 2], uv[k * 2 + 1])
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz)
    }

    // ---- The chalk ----

    /** "Nothing today" as the language has it, in its lines (two, as even as its words allow), and how many letters. */
    private class Text(val whole: String, val lines: List<String>) {
        val letters = lines.sumOf { it.length }
    }

    private var text: Text? = null

    private fun text(): Text {
        val whole = Language.getInstance().getOrDefault(KEY, "Nothing today")
        text?.let { if (it.whole == whole) return it }
        // Two lines as even as the words allow (one if it's one word)
        val words = whole.split(' ').filter { it.isNotEmpty() }
        var lines = listOf(whole)
        var best = Int.MAX_VALUE
        for (i in 1 until words.size) {
            val a = words.subList(0, i).joinToString(" ")
            val b = words.subList(i, words.size).joinToString(" ")
            val longest = maxOf(a.length, b.length)
            if (longest < best) {
                best = longest
                lines = listOf(a, b)
            }
        }
        return Text(whole, lines).also { text = it }
    }

    /** The first [written] letters of [text] chalked on the slate, each line centred as it'll be when it's done. */
    private fun chalk(poseStack: PoseStack, collector: SubmitNodeCollector, font: Font, text: Text, written: Int, light: Int) {
        val widest = text.lines.maxOf { font.width(it) }.coerceAtLeast(1)
        val scale = min(FACE_W * 0.84f / widest, FACE_H * 0.8f / (text.lines.size * LINE))
        poseStack.pushPose()
        poseStack.translate(0f, BAR + FACE_H / 2, 0.003f)
        poseStack.scale(scale, -scale, scale)
        var left = written
        val top = -text.lines.size * LINE / 2f + 1f
        for ((i, line) in text.lines.withIndex()) {
            if (left <= 0) break
            val shown = line.substring(0, min(line.length, left))
            left -= line.length
            val x = -font.width(line) / 2f
            val y = top + i * LINE
            val letters = FormattedCharSequence.forward(shown, Style.EMPTY)
            collector.submitText(poseStack, x, y, letters, false, Font.DisplayMode.POLYGON_OFFSET, light, CHALK, 0, 0)
            // A fainter stroke a hair off: chalk, not ink
            collector.submitText(poseStack, x + 0.4f, y + 0.3f, letters, false, Font.DisplayMode.POLYGON_OFFSET, light, CHALK_DUST, 0, 0)
        }
        poseStack.popPose()
    }

    private fun ease(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }

    private fun smooth(x: Float): Float = ease(x)

    /** The words chalked on it. */
    const val KEY = "streets.guildmark.slate"

    /** Ticks it takes to be set down, and to be taken away. */
    private const val PLACE = 14f
    private const val AWAY = 14f

    /** Ticks after it's set down that the chalk begins, and between one letter and the next. */
    private const val WRITE_FROM = PLACE + 6f
    private const val PER_LETTER = 2.5f

    /**
     * Where it comes from (behind the goods, on the keeper's side, tipped toward them) and how high it's lifted over
     * them, and where it rests: its foot on the counter's front edge, leant back a little on the display. In the
     * display's frame: its middle, its front to +z. And how far it rocks as it settles (degrees).
     */
    private const val START_Y = 0.22f
    private const val START_Z = -0.5f
    private const val START_LEAN = 25f
    private const val ARC = 0.38f
    private const val REST_Z = 0.49f
    private const val REST_LEAN = -6f
    private const val ROCK = 4f

    /** The slate's size, its frame's bars, how far the frame stands proud of it and how deep the board is (blocks). */
    private const val FACE_W = 0.44f
    private const val FACE_H = 0.3f
    private const val BAR = 0.04f
    private const val PROUD = 0.008f
    private const val DEPTH = 0.025f

    /** The chalk's lines' height (font pixels), and its colour and dust. */
    private const val LINE = 10f
    private const val CHALK = 0xF2EEEDE6.toInt()
    private const val CHALK_DUST = 0x55EEEDE6

    /** The board's tints: the slate a little blue, the wood as it is. */
    private const val SLATE = 0xFFC8D0DA.toInt()
    private const val WOOD = -1
}
