package io.github.vaspyyy.guildmark.client.streets

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.works.WorksArt
import io.github.vaspyyy.guildmark.client.works.WorksMesh
import net.minecraft.client.gui.Font
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
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
 * (client/economy/WaresRenderer, from [io.github.vaspyyy.guildmark.economy.WaresBlockEntity.slate]): Part F's slate in
 * its plain pine frame (client/works: WorksMesh.slate, rubbed clean but for the ghosts of old chalk), lifted over the
 * goods from the keeper's side and leant on the display's front, rocking as it settles, where "Nothing today" is
 * chalked on it a letter at a time; taken away again, it goes back over the way it came.
 */
object StreetSlate {
    /** The slate, baked from its mesh the first time it's drawn. */
    private val slate: ModelPart by lazy { WorksMesh.slate().bakeRoot().getChild("slate") }

    private val TYPE: RenderType = RenderTypes.entityCutout(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, WorksArt.SLATE_TEXTURE))

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
        // The slate in its frame: its foot's middle here, its face (the mesh's -z) turned to +z, out over the street
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, 180f)
        poseStack.scale(-1f, -1f, 1f)
        collector.submitModelPart(slate, poseStack, TYPE, light, OverlayTexture.NO_OVERLAY, null)
        poseStack.popPose()
        // The chalk, a letter at a time once it's set down
        val text = text()
        val written = if (!up) text.letters else ((age - WRITE_FROM) / PER_LETTER).toInt().coerceIn(0, text.letters)
        if (written > 0) chalk(poseStack, collector, font, text, written, light)
        poseStack.popPose()
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

    /**
     * The first [written] letters of [text] chalked on the slate, each line centred as it'll be when it's done: in the
     * room inside its frame (WorksArt.SLATE_CHALK), its middle half the slate's height up, on its face.
     */
    private fun chalk(poseStack: PoseStack, collector: SubmitNodeCollector, font: Font, text: Text, written: Int, light: Int) {
        val widest = text.lines.maxOf { font.width(it) }.coerceAtLeast(1)
        val scale = min(CHALK_W * 0.9f / widest, CHALK_H * 0.82f / (text.lines.size * LINE))
        poseStack.pushPose()
        poseStack.translate(0f, WorksArt.SLATE_SIZE[1] / 2f / 16f, WorksArt.SLATE_SIZE[2] / 2f / 16f + 0.001f)
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

    /** The chalk's room on the slate (blocks: WorksArt.SLATE_CHALK's px), its lines' height (font pixels), and its colour and dust. */
    private val CHALK_W = WorksArt.SLATE_CHALK[0] / 16f
    private val CHALK_H = WorksArt.SLATE_CHALK[1] / 16f
    private const val LINE = 10f
    private const val CHALK = 0xF2EEEDE6.toInt()
    private const val CHALK_DUST = 0x55EEEDE6
}
