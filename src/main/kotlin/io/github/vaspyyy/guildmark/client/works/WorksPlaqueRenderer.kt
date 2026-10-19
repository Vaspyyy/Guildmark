package io.github.vaspyyy.guildmark.client.works

import com.mojang.blaze3d.vertex.PoseStack
import io.github.vaspyyy.guildmark.economy.works.build.WorksPlaqueBlock
import io.github.vaspyyy.guildmark.economy.works.build.WorksPlaqueBlockEntity
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.min

/** What a plaque needs drawn for one frame: its three lines, once it's bare. */
class WorksPlaqueRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.SOUTH
    var covered = true
    var lines: List<FormattedCharSequence> = emptyList()
    var widths: IntArray = IntArray(0)

    /** Ticks since the cloth came off. */
    var unveiled = WorksPaint.NEVER
}

/**
 * The patron's plaque, lettered once its velvet is off (living-world/works-art.md, "The plaque"): above the name
 * "Raised by the gift of", the patron's name, and below it how many pledged with them (or, with no patron, the town's
 * name and the day it opened), each line centred, shrunk to fit and cast: dark letters with their lit edge a tenth of a
 * pixel up and to the left. As the cloth comes off, a gleam runs across the new bronze.
 */
class WorksPlaqueRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<WorksPlaqueBlockEntity, WorksPlaqueRenderState> {
    private val font: Font = context.font()

    override fun createRenderState(): WorksPlaqueRenderState = WorksPlaqueRenderState()

    override fun extractRenderState(
        be: WorksPlaqueBlockEntity, state: WorksPlaqueRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(be, state, breakProgress)
        val level = be.level ?: return
        val block = be.blockState
        state.facing = WorksPaint.facing(block)
        state.covered = !block.hasProperty(WorksPlaqueBlock.COVERED) || block.getValue(WorksPlaqueBlock.COVERED)
        state.unveiled = WorksPaint.since(level.gameTime, be.unveiled, partialTicks)
        if (state.covered) return
        val words = words(be)
        state.lines = words.lines
        state.widths = words.widths
    }

    /** The plaque's words, worked out again only when what it says (or the language) changes. */
    private class Words(val key: String, val lines: List<FormattedCharSequence>, val widths: IntArray)

    private fun words(be: WorksPlaqueBlockEntity): Words {
        val key = "${be.patron}|${be.others}|${be.place}|${be.day}|${Language.getInstance().hashCode()}"
        (be.words as? Words)?.let { if (it.key == key) return it }
        val gift = be.patron.isNotEmpty()
        val texts = listOf(
            Component.translatable(if (gift) "gui.guildmark.works.plaque.gift" else "gui.guildmark.works.plaque.town"),
            Component.literal(if (gift) be.patron else be.place),
            when {
                gift && be.others == 1 -> Component.translatable("gui.guildmark.works.plaque.one")
                gift && be.others > 1 -> Component.translatable("gui.guildmark.works.plaque.others", number(be.others))
                gift -> Component.translatable("gui.guildmark.works.plaque.when", be.place, be.day)
                else -> Component.translatable("gui.guildmark.works.plaque.on_day", be.day)
            },
        )
        val lines = texts.map { it.visualOrderText }
        return Words(key, lines, IntArray(lines.size) { font.width(lines[it]) }).also { be.words = it }
    }

    /** [n] in words as far as twelve, as the language has them. */
    private fun number(n: Int): Component = if (n in 2..12) Component.translatable("gui.guildmark.works.number.$n") else Component.literal(n.toString())

    override fun submit(state: WorksPlaqueRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (state.covered || state.lines.isEmpty()) return
        poseStack.pushPose()
        WorksPaint.southModel(poseStack, state.facing)
        poseStack.scale(WorksPaint.PX, WorksPaint.PX, WorksPaint.PX)
        val light = state.lightCoords
        for ((i, line) in state.lines.withIndex()) {
            val spec = WorksArt.PLAQUE_LINES.getOrNull(i) ?: break
            val width = state.widths[i].coerceAtLeast(1).toFloat()
            // px a font unit: its capitals (seven units) as tall as the line's, shrunk to fit its width
            val s = min(spec[1] / CAP, spec[2] / width)
            val top = spec[0] + CAP * s
            letter(poseStack, collector, line, width, s, top, WorksArt.PLAQUE_TEXT_Z - EDGE_BACK, -EDGE / s, -EDGE / s, EDGE_COLOR, light)
            letter(poseStack, collector, line, width, s, top, WorksArt.PLAQUE_TEXT_Z, 0f, 0f, INK_COLOR, light)
        }
        // The gleam across the new bronze as the cloth comes off
        val g = (state.unveiled - GLEAM_FROM) / GLEAM_TICKS
        if (g in 0f..1f) {
            val x = WorksPaint.lerp(GLEAM_X0, GLEAM_X1, g)
            val strength = 1f - abs(2f * g - 1f)
            val c = ARGB.color(255, (200 * strength).toInt(), (160 * strength).toInt(), (90 * strength).toInt())
            collector.order(1).submitCustomGeometry(poseStack, GLEAM) { pose, buffer ->
                val z = WorksArt.PLAQUE_TEXT_Z + 0.04f
                WorksPaint.vertex(buffer, pose, x - GLEAM_W, 3f, z, 0f, 1f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
                WorksPaint.vertex(buffer, pose, x + GLEAM_W, 3f, z, 1f, 1f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
                WorksPaint.vertex(buffer, pose, x + GLEAM_W + GLEAM_LEAN, 13f, z, 1f, 0f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
                WorksPaint.vertex(buffer, pose, x - GLEAM_W + GLEAM_LEAN, 13f, z, 0f, 0f, c, LightCoordsUtil.FULL_BRIGHT, 0f, 0f, 1f)
            }
        }
        poseStack.popPose()
    }

    private fun letter(
        poseStack: PoseStack, collector: SubmitNodeCollector, line: FormattedCharSequence, width: Float, s: Float, top: Float, z: Float,
        dx: Float, dy: Float, color: Int, light: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(8f, top, z)
        poseStack.scale(s, -s, s)
        collector.submitText(poseStack, -width / 2f + dx, dy, line, false, Font.DisplayMode.POLYGON_OFFSET, light, color, 0, 0)
        poseStack.popPose()
    }

    companion object {
        /** A capital's height in font units. */
        private const val CAP = 7f

        /** The lit edge: a tenth of a pixel up and to the left of each letter, just behind it. */
        private const val EDGE = 0.1f
        private const val EDGE_BACK = 0.006f
        private const val INK_COLOR = (0xFF shl 24) or WorksArt.PLAQUE_INK
        private const val EDGE_COLOR = (0xFF shl 24) or WorksArt.PLAQUE_EDGE

        /** The gleam: from when after the cloth's off and for how long it runs across, from where to where, and its half width and lean (px). */
        private const val GLEAM_FROM = 6f
        private const val GLEAM_TICKS = 26f
        private const val GLEAM_X0 = 1f
        private const val GLEAM_X1 = 15f
        private const val GLEAM_W = 1.2f
        private const val GLEAM_LEAN = 1.6f

        private val GLEAM: RenderType = RenderTypes.eyes(WorksPaint.texture(WorksArt.GLOW_TEXTURE))
    }
}
