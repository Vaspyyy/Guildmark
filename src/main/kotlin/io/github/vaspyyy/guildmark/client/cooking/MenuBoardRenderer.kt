package io.github.vaspyyy.guildmark.client.cooking

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.cooking.CookParticles
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.market.MenuBoardBlockEntity
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.level.Level
import java.util.WeakHashMap
import kotlin.math.min

/** One line on the slate: what it says, which dish its icon is, how long and wide it is, and, while it is being rewritten, what it said before. */
class MenuRow(
    val source: Component, val text: FormattedCharSequence, val kind: String, val total: Int, val width: Int, val icon: Int,
) {
    var old: FormattedCharSequence? = null
    var oldWidth = 0
    var oldIcon = -1

    /** When the rewriting began (game time); long ago for a line that was there at first sight. */
    var since = -1.0e9
}

/** What a board remembers from one frame to the next: the lines it was last given and how each is being written. */
class MenuSeen {
    val ticker = Ticker()
    var lines: List<Component>? = null
    var kinds: List<String>? = null
    var rows: List<MenuRow> = emptyList()
    var generation = -1
}

class MenuState : SceneState() {
    var seen: MenuSeen? = null
    var facing: Direction = Direction.NORTH

    /** Whether the camera is on the side of the slate that has the chalk. */
    var seeing = false
}

/**
 * The Menu board seen (living-world/cook/design.md 5.4, 11.1): the lines the server has chalked on its slate, with the small picture
 * of a dish beside a line that is about one, drawn from the block entity's own synced text so that what is on the slate is always what
 * the server has.
 *
 *  - the first line, when it has no dish, is the heading and is centred; the rest start at the left, after the picture;
 *  - a line is drawn as large as the slate's height allows, and no wider than the slate (a long one is written smaller);
 *  - when a line is rewritten the old one is wiped out and the new one is chalked in, a few letters a tick, with a puff of chalk dust
 *    at the point of the chalk; a board that comes into sight is already written;
 *  - the chalk is seen from the slate's side only and not from far off.
 */
class MenuBoardRenderer(context: BlockEntityRendererProvider.Context) : SceneRenderer<MenuBoardBlockEntity, MenuState>(context) {
    private val memory = WeakHashMap<MenuBoardBlockEntity, MenuSeen>()
    private val fitted = FloatArray(2)

    override fun createRenderState(): MenuState = MenuState()

    override fun extract(be: MenuBoardBlockEntity, state: MenuState) {
        val level = state.level ?: return
        val block = be.blockState
        val facing = if (block.hasProperty(StationProps.FACING)) block.getValue(StationProps.FACING) else Direction.NORTH
        state.facing = facing
        // The slate looks the way the block faces; its plane is a hair in front of the middle of the cell
        state.seeing = state.camX * facing.stepX + state.camZ * facing.stepZ > 0.05
        val seen = memory.getOrPut(be) { MenuSeen() }
        state.seen = seen
        if (state.distSq > FAR * FAR) return
        val now = level.gameTime
        val n = seen.ticker.due(now)
        val remade = seen.generation != CookItems.generation
        if (be.lines !== seen.lines || be.kinds !== seen.kinds || remade) {
            rebuild(be, seen, level, state.time, n < 0 || remade)
            seen.generation = CookItems.generation
        }
        if (n > 0 && state.near && state.seeing) dust(be, state, seen, now, facing)
    }

    private fun rebuild(be: MenuBoardBlockEntity, seen: MenuSeen, level: Level, now: Double, first: Boolean) {
        val lines = be.lines
        val kinds = be.kinds
        val before = seen.rows
        val rows = ArrayList<MenuRow>(lines.size)
        for (i in lines.indices) {
            val line = lines[i]
            val kind = kinds.getOrElse(i) { "" }
            val prev = before.getOrNull(i)
            if (prev != null && prev.source == line && prev.kind == kind) {
                rows.add(prev)
                continue
            }
            val text = line.visualOrderText
            val row = MenuRow(line, text, kind, length(text), font.width(text), iconOf(kind))
            if (!first) {
                row.since = now
                if (prev != null) {
                    row.old = prev.text
                    row.oldWidth = prev.width
                    row.oldIcon = prev.icon
                }
            }
            rows.add(row)
        }
        seen.rows = rows
        seen.lines = lines
        seen.kinds = kinds
    }

    /** The registry id of the dish item for the kind `kind` (`pie`, `stew`), or -1 for none. */
    private fun iconOf(kind: String): Int {
        if (kind.isEmpty()) return -1
        val dish = DishKind.byId(kind) ?: return -1
        val item = CookRegistry.DISHES[dish]?.get() ?: return -1
        return BuiltInRegistries.ITEM.getId(item)
    }

    private fun length(seq: FormattedCharSequence): Int {
        var n = 0
        seq.accept { _, _, _ ->
            n++
            true
        }
        return n
    }

    /** The first [count] letters of [seq], each in its own style. */
    private fun prefix(seq: FormattedCharSequence, count: Int): FormattedCharSequence {
        if (count <= 0) return FormattedCharSequence.EMPTY
        val parts = ArrayList<FormattedCharSequence>(count)
        var n = 0
        seq.accept { _, style, cp ->
            if (n >= count) {
                false
            } else {
                parts.add(FormattedCharSequence.codepoint(cp, style))
                n++
                true
            }
        }
        return FormattedCharSequence.composite(parts)
    }

    /** A puff of chalk dust at the point of the chalk of the line being written (one writer at a time, a puff every few ticks). */
    private fun dust(be: MenuBoardBlockEntity, state: MenuState, seen: MenuSeen, now: Long, facing: Direction) {
        if (now % 4L != 0L) return
        val rows = seen.rows
        val pitch = pitch(rows.size)
        for (i in rows.indices) {
            val row = rows[i]
            val age = (now - row.since).toFloat()
            val wipe = if (row.old != null) WIPE else 0f
            val typed = (age - wipe) * TYPE
            if (typed < 0f || typed >= row.total) continue
            fit(pitch, row.width, row.icon >= 0, i == 0 && row.kind.isEmpty() && rows.size > 1)
            val px = fitted[0] + row.width * fitted[1] * (typed / row.total)
            val at = CookGeo.world(be.blockPos, facing, CookGeo.SLATE[2] - px, CookGeo.SLATE[3] - (MY + (i + 0.5f) * pitch), CookGeo.SLATE_Z - 0.4f)
            if (StationFx.inRange(be.blockPos)) {
                CookParticleProviders.spawn(CookParticles.STEAM.get(), at.x, at.y, at.z, 0.0, 0.002, -0.003, 1f, 1f, 0.95f, 0.3f, 0.3f)
            }
            return
        }
    }

    override fun draw(state: MenuState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val seen = state.seen ?: return
        val rows = seen.rows
        if (rows.isEmpty() || !state.seeing || state.distSq > FAR * FAR) return
        val n = rows.size
        val pitch = pitch(n)
        val icons = state.distSq < ICON_NEAR * ICON_NEAR
        poseStack.pushPose()
        Gfx.enter(poseStack, state.facing)
        // The slate's top left as its reader sees it, a hair in front of its face; x runs to the reader's right, y up, z toward the reader
        Gfx.at(poseStack, CookGeo.SLATE[2], CookGeo.SLATE[3], CookGeo.SLATE_Z - 0.05f)
        poseStack.rotateDegrees(Axis.YP, 180f)
        for (i in 0 until n) {
            val row = rows[i]
            val age = (state.time - row.since).toFloat()
            val old = row.old
            val heading = i == 0 && row.kind.isEmpty() && n > 1
            val yc = MY + (i + 0.5f) * pitch
            if (old != null && age < WIPE) {
                // The old line is wiped
                chalk(state, poseStack, collector, old, row.oldWidth, row.oldIcon >= 0, heading, pitch, yc, 1f - age / WIPE)
                if (icons && row.oldIcon >= 0) icon(state, poseStack, collector, row.oldIcon, pitch, yc)
            } else {
                val typed = ((age - (if (old != null) WIPE else 0f)) * TYPE).toInt()
                val seq = if (typed >= row.total) row.text else prefix(row.text, typed)
                chalk(state, poseStack, collector, seq, row.width, row.icon >= 0, heading, pitch, yc, 1f)
                if (icons && row.icon >= 0 && typed > 0) icon(state, poseStack, collector, row.icon, pitch, yc)
            }
        }
        poseStack.popPose()
    }

    /** The row height, px: as large as the slate allows, no more than [MAX_PITCH]. */
    private fun pitch(rows: Int): Float = min(MAX_PITCH, (CookGeo.SLATE[3] - CookGeo.SLATE[1] - 2f * MY) / rows.coerceAtLeast(1))

    /** Works into [fitted] the left edge (px from the slate's left) and the size (px a font unit) of a line [width] font units long. */
    private fun fit(pitch: Float, width: Int, icon: Boolean, heading: Boolean) {
        val iconW = if (icon) pitch * 1.15f else 0f
        var k = pitch * 0.8f / 8f * (if (heading) 1.12f else 1f)
        val avail = (CookGeo.SLATE[2] - CookGeo.SLATE[0]) - 2f * MX - iconW
        if (width * k > avail && width > 0) k = avail / width
        fitted[0] = if (heading) ((CookGeo.SLATE[2] - CookGeo.SLATE[0]) - width * k) / 2f else MX + iconW
        fitted[1] = k
    }

    private fun chalk(
        state: MenuState, poseStack: PoseStack, collector: SubmitNodeCollector, seq: FormattedCharSequence, width: Int, icon: Boolean,
        heading: Boolean, pitch: Float, yc: Float, alpha: Float,
    ) {
        if (alpha < 0.02f) return
        fit(pitch, width, icon, heading)
        val k = fitted[1]
        poseStack.pushPose()
        poseStack.translate(fitted[0] * Gfx.PX, -yc * Gfx.PX, 0f)
        poseStack.scale(k * Gfx.PX, -k * Gfx.PX, k * Gfx.PX)
        val argb = (Gfx.c8(alpha) shl 24) or CHALK
        collector.submitText(poseStack, 0f, -4f, seq, false, Font.DisplayMode.POLYGON_OFFSET, state.light, argb, 0, 0)
        poseStack.popPose()
    }

    /** A dish's picture, flat on the slate beside a line. */
    private fun icon(state: MenuState, poseStack: PoseStack, collector: SubmitNodeCollector, id: Int, pitch: Float, yc: Float) {
        val model = CookItems.state(id, resolver, state.level) ?: return
        val size = pitch * 1.05f
        poseStack.pushPose()
        poseStack.translate((MX + size / 2f) * Gfx.PX, -yc * Gfx.PX, 0.02f * Gfx.PX)
        poseStack.rotateDegrees(Axis.YP, 180f)
        val s = size / 8f
        poseStack.scale(s, s, s * 0.04f)
        model.submit(poseStack, collector, state.light, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
    }

    companion object {
        /** Margins of the chalking area, px, and the tallest a row may be. */
        private const val MX = 0.6f
        private const val MY = 0.35f
        private const val MAX_PITCH = 1.5f

        /** Ticks an old line takes to wipe out, and the letters a tick the new one is chalked at. */
        private const val WIPE = 6f
        private const val TYPE = 2.2f

        /** The chalk is not drawn beyond this many blocks, nor the pictures beyond [ICON_NEAR]. */
        private const val FAR = 20.0
        private const val ICON_NEAR = 12.0

        /** The colour of chalk (the line's own style may colour it). */
        private const val CHALK = 0xF2EEE0
    }
}
