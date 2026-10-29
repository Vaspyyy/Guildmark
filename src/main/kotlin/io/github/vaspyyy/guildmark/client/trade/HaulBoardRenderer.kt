package io.github.vaspyyy.guildmark.client.trade

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.HaulBoardBlockEntity
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.client.BoardQuads
import io.github.vaspyyy.guildmark.client.MarketBoardArt as M
import io.github.vaspyyy.guildmark.client.trade.HaulBoardArt as A
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import io.github.vaspyyy.guildmark.trade.HaulKind
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.sprite.SpriteId
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.locale.Language
import net.minecraft.resources.Identifier
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import java.text.Normalizer
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Haul Board's own entry on the client (living-world/trader/design.md, Part 1; build part C): its renderer, registered here so the client's
 * start-up code stays as it was.
 */
@Mod(value = Guildmark.MOD_ID, dist = [Dist.CLIENT])
class HaulBoardClient(modBus: IEventBus) {
    init {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(ModBlockEntities.HAUL_BOARD.get(), ::HaulBoardRenderer)
        }
    }
}

/** What a Haul Board needs drawn this frame: its writing, pictures and tickets as flat quads, and the merchant's satchel on the shelf. */
class HaulBoardRenderState : BlockEntityRenderState() {
    /** The board's turn from facing north, in degrees (as its blockstate turns its cells). */
    var yRot = 0f
    var any = false

    /** Drawn one after another: the slate's chalk and pictures; the tickets' paper and the header's gilt; the tickets' ink and seals; the chalk stick. */
    val layers = Array(4) { BoardQuads() }

    val satchel = ItemStackRenderState()
    var satchelOn = false
    var satchelLift = 0f
    var satchelLight = 0

    fun clear() {
        any = false
        for (q in layers) q.clear()
        satchelOn = false
    }
}

/**
 * A Haul Board's face, drawn live by the numbers in [HaulBoardArt] (art/scripts/haul_board_art.py lays its previews out by the same ones) from X1's atlas,
 * with the market board's own chalk and ink lettering ([io.github.vaspyyy.guildmark.client.MarketBoardArt]).
 *
 * The header plank has the board's name in gilt, the slate "Word from the capitals" and a row for each town round about: its name, the chips of what it is
 * short of (red) and has too much of (green), an hourglass whose sand runs from the time its news is of (spent at three days, when the row is faded and
 * struck through), how old that is, a pennant for its worst road, the hall's wax with the number of tickets it had out, and a quill when the news came in
 * a report. The rail on the right has the town's own tickets on their pegs: the paper with the good's chip, how many, the pay, the shop, the days left in
 * notches along its foot, the relief cross or the feast festoon, the hall's wax stamp, and the booker's seal once someone has booked it.
 *
 * It is alive while the hall's merchant is at it (see [io.github.vaspyyy.guildmark.trade.HaulClerk]): a row they write is gone over a piece at a time,
 * the parts that changed rubbed out with the rag and the new ones chalked in order (the hourglass tipped first if the news is new, the name stroke by
 * stroke, each chip in three strokes, then the age, the pennant pinned out, the wax and its count), the chalk stick at work and a little dust falling;
 * what did not change stays as it was. A ticket they hang comes down onto its peg and swings there, and the stamp comes down on it with a thump, a puff
 * of dust and the wax squashed out and settling; a booked one is stamped again in the booker's colour; one taken down peels off its peg and drops.
 * The satchel stands on the shelf while they work. What was written while nobody was by shows whole, and a board looked away from for a couple of
 * seconds shows as it stands when looked at again: nothing is written before your eyes that was written before you looked. So the age chalked by a row
 * is the age when it was written or last looked at afresh; the glass's sand is the only thing that keeps running. The writing is lit by the light at the
 * board and fades out past [HaulBoardArt.FADE_NEAR] blocks; the tickets hang there for anyone to see.
 */
class HaulBoardRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<HaulBoardBlockEntity, HaulBoardRenderState> {
    private val itemModels = context.itemModelResolver()
    private val sprites = context.sprites()

    override fun createRenderState(): HaulBoardRenderState = HaulBoardRenderState()

    // The whole 3x2 board, wherever the anchor cell is
    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = 48

    override fun getRenderBoundingBox(board: HaulBoardBlockEntity): AABB {
        val p = board.blockPos
        // The crest stands over the cap's ridge
        return AABB(p.x - 1.0, p.y.toDouble(), p.z - 1.0, p.x + 2.0, p.y + 2.5, p.z + 2.0)
    }

    // ---------------------------------------------------------------- extraction

    override fun extractRenderState(
        board: HaulBoardBlockEntity, state: HaulBoardRenderState, partialTicks: Float, cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(board, state, breakProgress)
        state.clear()
        val level = board.level ?: return
        val blockState = board.blockState
        if (!blockState.hasProperty(NoticeBoardBlock.FACING)) return
        val facing = blockState.getValue(NoticeBoardBlock.FACING)
        state.yRot = yRot(facing)
        val pos = board.blockPos
        val centre = world(pos, state.yRot, 1.5f, 1.0f, -0.75f)
        val distance = cameraPosition.distanceTo(centre).toFloat()
        val fade = 1f - smooth((distance - A.FADE_NEAR) / (A.FADE_FAR - A.FADE_NEAR))

        val mem = memory.getOrPut(board) { Memory() }
        val now = level.gameTime + partialTicks.toDouble()
        // Not drawn for a while (looked away from, or out of range): whatever changed meanwhile is shown as it stands
        if (!mem.lastFrame.isNaN() && now - mem.lastFrame > UNSEEN) mem.fresh = true
        val frame = if (mem.lastFrame.isNaN()) 0f else (now - mem.lastFrame).toFloat().coerceIn(0f, 4f)
        mem.lastFrame = now
        cellLights(level, pos, facing)
        val atlas = sprites.get(ATLAS_ID)
        val letters = sprites.get(LETTERS_ID)
        lines.refresh()
        if (mem.words != lines.version) {
            // A new language: everything's written again in it, whole
            mem.words = lines.version
            mem.fresh = true
            for (s in mem.shown) s.forget()
        }

        if (fade > 0.002f) header(state, fade, letters)
        tickets(board, state, mem, level, pos, now, fade, atlas, letters)
        // Kept up to date even out of reading range, so nothing's rewritten before your eyes that was written before you came
        slate(board, state, mem, level, pos, now, frame, fade, atlas, letters)
        satchel(board, state, level, now)
        mem.fresh = false
        state.any = true
    }

    // ---------------------------------------------------------------- the header and the slate's title

    private fun header(state: HaulBoardRenderState, fade: Float, letters: TextureAtlasSprite) {
        val name = lines.header
        if (name.isBlank()) return
        val q = state.layers[1]
        val s = A.S_HEADER
        val w = width(CHALK, name) * s
        val squeeze = min(1f, HEADER_MAX_W / max(1e-4f, w))
        val x = 1.5f - w * squeeze / 2
        val base = A.HEADER_V0 + A.HEADER_BASE
        // Gilt, over its own dark edge
        flat.z = -A.HEADER_DEPTH + 0.0025f
        whole(q, CHALK, name, x + 0.6f * s, base + 0.6f * s, s, squeeze, tint(M.GILT_SHADOW, 0.85f * fade), letters)
        flat.z = -A.HEADER_DEPTH + 0.0033f
        whole(q, CHALK, name, x, base, s, squeeze, tint(M.GILT, fade), letters)
    }

    // ---------------------------------------------------------------- the slate

    private fun slate(
        board: HaulBoardBlockEntity, state: HaulBoardRenderState, mem: Memory, level: Level, pos: BlockPos,
        now: Double, frame: Float, fade: Float, atlas: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        val q = state.layers[0]
        val z = -A.SLATE_DEPTH + 0.0025f
        val left = A.SLATE_U0 + A.PAD_L
        flat.z = z
        val title = lines.title
        if (title.isNotBlank() && fade > 0.002f) {
            val room = A.SLATE_U1 - A.PAD_R - left
            val squeeze = min(1f, room / max(1e-4f, width(CHALK, title) * A.S_TITLE))
            whole(q, CHALK, title, left, A.PANEL_V0 + A.TITLE_BASE, A.S_TITLE, squeeze, tint(M.CHALK_HEAD, 0.95f * fade), letters)
        }
        val clock = level.overworldClockTime
        // The age chalked by each row is looked at again now and then, and changed only where nobody can read it
        val check = mem.fresh || fade <= 0.002f || now - mem.checked >= 20.0
        if (check) mem.checked = now
        var tip: Tip? = null
        for (i in 0 until HaulBoardBlockEntity.ROWS) {
            tip = row(board.rows.getOrNull(i), mem, mem.shown[i], i, now, clock, fade, check, q, z, atlas, letters) ?: tip
        }
        stick(board, state, mem, level, pos, tip, now, frame, fade, letters)
    }

    /** Row [i] of the slate: kept up with what the board has, the old one rubbed out and the new one chalked in where it changed; the chalk's tip, if at work. */
    private fun row(
        src: HaulBoardBlockEntity.Row?, mem: Memory, sh: Shown, i: Int, now: Double, clock: Long, fade: Float, check: Boolean,
        q: BoardQuads, z: Float, atlas: TextureAtlasSprite, letters: TextureAtlasSprite,
    ): Tip? {
        // A row padded in ahead of the merchant's chalk has nothing written in it yet
        val row = src?.takeUnless { it.name.isEmpty() && it.distance == 0 && it.chalked == HaulBoardBlockEntity.LONG_AGO }
        if (!sameSource(row, sh.src)) {
            val before = sh.look
            val seen = !mem.fresh
            sh.src = row
            if (row == null) {
                // Rubbed off: the slate wiped, or a town gone from it
                if (seen && before != null) sh.rubbing(before, present(before), now, cross = false) else sh.old = null
                sh.look = null
                sh.changed = 0
                sh.start = Double.NEGATIVE_INFINITY
            } else {
                val next = Look(i, row, clock)
                val chalking = row.chalked != HaulBoardBlockEntity.LONG_AGO && now - row.chalked < LIMIT + 40.0
                val changed = if (before == null || !seen) present(next) else diff(before, next)
                sh.look = next
                sh.changed = changed
                if (seen && before != null) {
                    sh.rubbing(before, diff(before, next) and present(before) and bit(GLASS).inv(), if (chalking) row.chalked.toDouble() else now, cross = !chalking)
                } else {
                    sh.old = null
                }
                sh.cross = !chalking && seen && before != null
                sh.tipFrom = if (chalking && seen && before != null && changed has GLASS) sand(before, clock) else Float.NaN
                sh.start = when {
                    chalking -> row.chalked.toDouble()
                    sh.cross -> now
                    else -> Double.NEGATIVE_INFINITY
                }
                if (chalking) schedule(sh, next)
            }
        } else if (check && row != null) {
            // Time going by: the age it shows and whether it's stale, changed only out of reading range or when looked at afresh
            val look = sh.look
            if (look != null && (fade <= 0.002f || mem.fresh) && (look.ageText != ageTextOf(row, clock) || look.stale != staleOf(row, clock))) {
                sh.look = Look(i, row, clock)
            }
        }

        // The old one under the rag
        sh.old?.let { old ->
            val a = (now - sh.oldAt).toFloat()
            val over = if (sh.oldCross) CROSS else M.RUB + M.SMEAR
            if (a > over) {
                sh.old = null
            } else if (fade > 0.002f) {
                for (e in ORDER) if (sh.oldParts has e) rubbed(q, old, e, a, sh.oldCross, now, clock, fade, z, atlas, letters)
            }
        }

        val look = sh.look ?: return null
        if (fade <= 0.002f) return null
        val t = (now - sh.start).toFloat()
        var tip: Tip? = null
        for (e in ORDER) {
            if (!has(look, e)) continue
            if (sh.cross || sh.start.isInfinite() || !(sh.changed has e)) {
                val alpha = if (sh.cross && sh.changed has e) (t / CROSS).coerceIn(0f, 1f) else 1f
                element(q, look, e, 1f, false, alpha, Float.POSITIVE_INFINITY, sh, now, clock, fade, z, atlas, letters)
                continue
            }
            val from = sh.from[e]
            val to = sh.to[e]
            val p = ((t - from) / max(1e-3f, to - from)).coerceIn(0f, 1f)
            if (t >= from && t < to) {
                tip = element(q, look, e, p, true, 1f, 0f, sh, now, clock, fade, z, atlas, letters) ?: tip
            } else {
                element(q, look, e, p, false, 1f, t - to, sh, now, clock, fade, z, atlas, letters)
            }
        }
        // Between one part and the next, the chalk lifted and on its way
        if (tip == null && !sh.start.isInfinite() && !sh.cross) tip = between(sh, look, t)
        return tip
    }

    /** Where the hand is between the parts of a row being written: on its way, lifted, from where the last one ended to where the next begins. */
    private fun between(sh: Shown, look: Look, t: Float): Tip? {
        var prev = -1
        for (e in ORDER) {
            if (!(sh.changed has e) || !has(look, e)) continue
            if (t < sh.from[e]) {
                if (prev < 0) return null
                val a = look.end(prev)
                val b = look.start(e)
                val g = ((t - sh.to[prev]) / max(1e-3f, sh.from[e] - sh.to[prev])).coerceIn(0f, 1f)
                return Tip(a[0] + (b[0] - a[0]) * g, a[1] + (b[1] - a[1]) * g, false)
            }
            prev = e
        }
        return null
    }

    /** When each changed part of [sh]'s row is written, after the rag: in the hand's order, each its own time, the whole squeezed into [LIMIT]. */
    private fun schedule(sh: Shown, look: Look) {
        val parts = ArrayList<Int>()
        val takes = ArrayList<Float>()
        for (e in ORDER) {
            if (!(sh.changed has e) || !has(look, e)) continue
            parts.add(e)
            takes.add(
                when (e) {
                    GLASS -> if (sh.tipFrom.isNaN()) 4f else TURN
                    NAME -> look.nameRow.plan.total.coerceIn(8f, 16f)
                    AGE -> look.ageRow.plan.total.coerceIn(4f, 10f)
                    COUNT -> (look.countRow?.plan?.total ?: 3f).coerceIn(3f, 6f)
                    PENNANT -> 6f
                    SEAL, QUILL -> 4f
                    STRIKE -> 8f
                    else -> CHIP_TAKES
                },
            )
        }
        val first = if (sh.old != null && sh.oldParts != 0) M.RUB * 0.6f else 0f
        val total = first + takes.sum() + GAP * max(0, parts.size - 1)
        val k = if (total > LIMIT) LIMIT / total else 1f
        var t = first * k
        for (n in parts.indices) {
            if (n > 0) t += GAP * k
            sh.from[parts[n]] = t
            t += takes[n] * k
            sh.to[parts[n]] = t
        }
    }

    /**
     * Part [e] of [look] drawn [p] of the way (1: whole), at [alpha]; [writing] while the chalk is on it ([since] ticks after it was done, for how fresh the
     * chalk is). Returns the chalk's tip while it's being written.
     */
    private fun element(
        q: BoardQuads, look: Look, e: Int, p: Float, writing: Boolean, alpha: Float, since: Float, sh: Shown?, now: Double, clock: Long,
        fade: Float, z: Float, atlas: TextureAtlasSprite, letters: TextureAtlasSprite,
    ): Tip? {
        if (p <= 0f && !writing && !(e == GLASS && sh != null && !sh.tipFrom.isNaN())) return null
        val a = alpha * fade * (if (look.stale && e != STRIKE) STALE_FADE else 1f)
        if (a < 0.004f) return null
        val top = look.top
        val left = A.SLATE_U0 + A.PAD_L
        val bright = if (since.isInfinite()) 0f else (1f - since / M.FRESH).coerceIn(0f, 1f)
        flat.z = z
        when (e) {
            NAME -> return chalk(q, look.nameRow, p, bright, a, letters)
            AGE -> return chalk(q, look.ageRow, p, bright, a, letters)
            COUNT -> return look.countRow?.let { chalk(q, it, p, bright, a, letters) }
            GLASS -> {
                val gu = left + A.HOURGLASS_X
                val gv = top + A.HOURGLASS_MID - A.HOURGLASS_H * A.TEXEL / 2
                val live = sand(look, clock)
                val tipFrom = sh?.tipFrom ?: Float.NaN
                val phase = (now / 3.0).toInt()
                val light = lightAt(gu, gv)
                if (!tipFrom.isNaN() && p < 1f) {
                    // Tipped end for end: its sand pours over to where the new news has it
                    val g = ease(p)
                    glass(q, atlas, gu, gv, tipFrom + ((1f - live) - tipFrom) * g, PI.toFloat() * g, phase, tint(0xFFFFFF, a), light, z)
                } else {
                    glass(q, atlas, gu, gv, live, 0f, phase, tint(0xFFFFFF, a * if (tipFrom.isNaN()) min(1f, p * 1.5f) else 1f), light, z)
                }
                return if (writing) Tip(gu + A.HOURGLASS_W * A.TEXEL / 2, gv - 0.01f, false) else null
            }
            PENNANT -> {
                val pu = left + A.PENNANT_X
                val pt = top + A.PENNANT_TOP
                val wave = (now * 0.314 + look.i * 2.2).toFloat()
                pennant(q, atlas, look.road, pu, pt, wave, p, tint(0xFFFFFF, a), lightAt(pu, pt), z)
                return if (writing) Tip(pu + (A.PENNANT_HOIST_X + 20f * p) * A.TEXEL, pt + 0.01f, false) else null
            }
            SEAL -> {
                val ss = A.SEAL_TEXELS * A.SEAL_TEXEL
                val su = left + A.SEAL_X
                val sv = top + A.SEAL_MID - ss / 2
                spin.set(su + ss / 2, sv + ss / 2, 0f, z + 0.0004f, 1f + 0.35f * (1f - ease(p)))
                pic(q, spin, atlas, A.SEAL_TINT, su, sv, su + ss, sv + ss, tint(HALL_WAX, a * min(1f, p * 2f)), lightAt(su, sv))
                return if (writing) Tip(su + ss / 2, sv, false) else null
            }
            QUILL -> {
                val s = 16 * A.TEXEL
                val qu = look.nameEnd + 0.012f
                val qv = top + A.NAME_BASE - s + 0.004f
                pic(q, flat, atlas, A.QUILL, qu, qv, qu + s, qv + s, tint(0xFFFFFF, a * p), lightAt(qu, qv))
                return if (writing) Tip(qu + s / 2, qv, false) else null
            }
            STRIKE -> {
                val y = top + A.STRUCK_Y
                val w = A.SLATE_U1 - A.PAD_R - left
                flat.z = z + 0.0006f
                struck(q, atlas, left, y, w, p, tint(0xFFFFFF, a * STRIKE_ALPHA), lightAt(left + w / 2, y))
                return if (writing) Tip(left + w * p, y + 0.012f, true) else null
            }
            else -> {
                // A chip, in three strokes round its ring
                val k = e - CHIP0
                val good = look.chips[k]
                if (good < 0) return null
                val cell = if (k < 3) A.CHIP_RED[good] else A.CHIP_GREEN[good]
                val cu = left + (if (k < 3) A.CHIP_RED_X else A.CHIP_GREEN_X) + (k % 3) * A.CHIP_PITCH
                val cv = top + A.CHIP_TOP
                val size = A.CHIP_BLOCKS
                val d = p * CHIP_TAKES
                var drawn = 1f
                var down = false
                for (n in 0 until 3) {
                    val s0 = n * (CHIP_STROKE + CHIP_GAP)
                    if (d < s0 + CHIP_STROKE) {
                        down = d >= s0
                        drawn = if (down) (n + ease((d - s0) / CHIP_STROKE)) / 3f else n / 3f
                        break
                    }
                }
                if (p >= 1f) drawn = 1f
                chip(q, atlas, cell, cu, cv, size, drawn, tint(0xFFFFFF, a), lightAt(cu + size / 2, cv + size / 2), z)
                if (!writing) return null
                val ang = drawn * 2f * PI.toFloat()
                return Tip(cu + size / 2 + sin(ang) * size * 0.42f, cv + size / 2 - cos(ang) * size * 0.42f, down)
            }
        }
    }

    /** Part [e] of an old row under the rag ([age] ticks in), or fading out as the new one fades in ([cross]). */
    private fun rubbed(
        q: BoardQuads, old: Look, e: Int, age: Float, cross: Boolean, now: Double, clock: Long, fade: Float, z: Float,
        atlas: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        if (cross) {
            element(q, old, e, 1f, false, 1f - (age / CROSS).coerceIn(0f, 1f), Float.POSITIVE_INFINITY, null, now, clock, fade, z, atlas, letters)
            return
        }
        val gone = (age / M.RUB).coerceIn(0f, 1f)
        if (gone < 1f) element(q, old, e, 1f, false, 1f - gone, Float.POSITIVE_INFINITY, null, now, clock, fade, z, atlas, letters)
        // A pale smear where it was, lingering a while
        val smear = min(1f, age / M.RUB) * (1f - ((age - M.RUB) / M.SMEAR).coerceIn(0f, 1f))
        if (smear <= 0.01f) return
        val box = old.box(e)
        flat.z = z - 0.0008f
        cellSprite(q, flat, letters, SMUDGE, box[0] - 0.006f, box[1] - 0.004f, box[2] + 0.008f, box[3] + 0.006f,
            tint(M.CHALK, 0.42f * smear * fade * if (old.stale) STALE_FADE else 1f), lightAt((box[0] + box[2]) / 2, box[3]))
        flat.z = z
    }

    // ---------------------------------------------------------------- the slate's pictures

    /**
     * The hourglass with its top left at ([gu], [gv]), its sand run [frac] of the way (0 all above, 1 all below), turned [angle] about its middle: the empty
     * glass, the sand of the upper bulb cut from the top down and of the lower from the bottom up, and the falling sand's frame [phase].
     */
    private fun glass(q: BoardQuads, atlas: TextureAtlasSprite, gu: Float, gv: Float, frac: Float, angle: Float, phase: Int, argb: Int, light: Int, z: Float) {
        val t = A.TEXEL
        val cu = gu + A.HOURGLASS_W * t / 2
        val cv = gv + A.HOURGLASS_H * t / 2
        fun part(c: IntArray, x0: Float, y0: Float, x1: Float, y1: Float, lift: Float) {
            if (x1 <= x0 || y1 <= y0) return
            spin.set(cu, cv, angle, z + lift, 1f)
            pic(q, spin, atlas, c, gu + x0 * t, gv + y0 * t, gu + x1 * t, gv + y1 * t, argb, light, x0, y0, x1, y1)
        }
        val w = A.HOURGLASS_W.toFloat()
        part(A.HOURGLASS_FRAME, 0f, 0f, w, A.HOURGLASS_H.toFloat(), 0f)
        val f = frac.coerceIn(0f, 1f)
        val neck = A.HOURGLASS_NECK_Y.toFloat()
        val foot = A.HOURGLASS_SAND_BOTTOM + 1f
        val cutUpper = A.HOURGLASS_SAND_TOP + (neck - A.HOURGLASS_SAND_TOP) * f
        val cutLower = foot - (foot - neck) * f
        part(A.HOURGLASS_SAND_UPPER, 0f, cutUpper, w, neck, 0.0004f)
        part(A.HOURGLASS_SAND_LOWER, 0f, cutLower, w, foot, 0.0004f)
        if (f > 0.002f && f < 0.998f) {
            val y0 = max(neck - 3f, cutUpper)
            val n = min(12f, cutLower - y0)
            if (n >= 0.5f) {
                val sx = A.HOURGLASS_STREAM_X.toFloat()
                spin.set(cu, cv, angle, z + 0.0007f, 1f)
                pic(q, spin, atlas, A.HOURGLASS_STREAM[Math.floorMod(phase, A.HOURGLASS_STREAM_FRAMES)], gu + sx * t, gv + y0 * t, gu + (sx + 2f) * t, gv + (y0 + n) * t,
                    argb, light, 0f, 0f, 2f, n)
            }
        }
    }

    /** A road's pennant on its pole at ([pu], [pt]), the cloth riding a wave ([wave] its phase) and pinned out [shown] of the way from the pole. */
    private fun pennant(q: BoardQuads, atlas: TextureAtlasSprite, road: Int, pu: Float, pt: Float, wave: Float, shown: Float, argb: Int, light: Int, z: Float) {
        if (road !in 0 until A.PENNANT.size) return
        val t = A.TEXEL
        flat.z = z
        val pole = A.PENNANT_POLE
        pic(q, flat, atlas, pole, pu, pt, pu + pole[2] * t, pt + pole[3] * t, argb, light)
        val cloth = A.PENNANT[road]
        val cw = cloth[2]
        val ch = cloth[3].toFloat()
        val out = (shown * cw).coerceIn(0f, cw.toFloat())
        flat.z = z + 0.0004f
        val columns = ceil(out).toInt()
        for (x in 0 until columns) {
            val xw = min(1f, out - x)
            val k = x / max(1f, cw - 1f)
            val dy = sin(wave - x * 0.55f) * 1.7f * k
            val u = pu + (A.PENNANT_HOIST_X + x) * t
            val v = pt + (A.PENNANT_HOIST_Y + dy) * t
            pic(q, flat, atlas, cloth, u, v, u + xw * t, v + ch * t, argb, light, x.toFloat(), 0f, x + xw, ch)
        }
        flat.z = z
    }

    /** The chip [c] with its top left at ([u0], [v0]), [size] square, drawn [frac] of the way round from the top, clockwise. */
    private fun chip(q: BoardQuads, atlas: TextureAtlasSprite, c: IntArray, u0: Float, v0: Float, size: Float, frac: Float, argb: Int, light: Int, z: Float) {
        if (frac <= 0f || argb ushr 24 == 0) return
        flat.z = z
        if (frac >= 0.999f) {
            pic(q, flat, atlas, c, u0, v0, u0 + size, v0 + size, argb, light)
            return
        }
        val h = size / 2
        val cu = u0 + h
        val cv = v0 + h
        fun su(u: Float) = atlas.getU((c[0] + (u - u0) / size * c[2]) / A.ATLAS_W)
        fun sv(v: Float) = atlas.getV((c[1] + (v - v0) / size * c[3]) / A.ATLAS_H)
        val end = frac * SECTORS
        var j = 0
        while (j < end) {
            val a0 = j * SECTOR
            val a1 = min(end, j + 1f) * SECTOR
            edge(cu, cv, h, a0, corner)
            val pu0 = corner[0]
            val pv0 = corner[1]
            edge(cu, cv, h, a1, corner)
            val pu1 = corner[0]
            val pv1 = corner[1]
            // The slice from the middle out to the ring's edge, wound as the quads are (the first two corners one point)
            q.vertex(cu, -cv, z, su(cu), sv(cv))
            q.vertex(cu, -cv, z, su(cu), sv(cv))
            q.vertex(pu1, -pv1, z, su(pu1), sv(pv1))
            q.vertex(pu0, -pv0, z, su(pu0), sv(pv0))
            q.close(argb, light)
            j++
        }
    }

    /** The point on the edge of the square of half-side [h] about ([cu], [cv]) at [angle] clockwise from straight up, into [out]. */
    private fun edge(cu: Float, cv: Float, h: Float, angle: Float, out: FloatArray) {
        val dx = sin(angle)
        val dy = -cos(angle)
        val k = h / max(abs(dx), abs(dy))
        out[0] = cu + dx * k
        out[1] = cv + dy * k
    }

    /** The stroke through a stale row, from [u0] along [y], [w] long: its two ends as they are, the middle stretched; swept [f] of the way along. */
    private fun struck(q: BoardQuads, atlas: TextureAtlasSprite, u0: Float, y: Float, w: Float, f: Float, argb: Int, light: Int) {
        if (f <= 0f) return
        val t = A.TEXEL
        val c = A.STRUCK
        val e = A.STRUCK_END.toFloat()
        val h = c[3].toFloat()
        val reach = u0 + w * f
        fun piece(x0: Float, x1: Float, from: Float, to: Float) {
            if (reach <= from) return
            val cut = min(1f, (reach - from) / max(1e-5f, to - from))
            pic(q, flat, atlas, c, from, y, from + (to - from) * cut, y + h * t, argb, light, x0, 0f, x0 + (x1 - x0) * cut, h)
        }
        piece(0f, e, u0, u0 + e * t)
        piece(e, c[2] - e, u0 + e * t, u0 + w - e * t)
        piece(c[2] - e, c[2].toFloat(), u0 + w - e * t, u0 + w)
    }

    // ---------------------------------------------------------------- chalk

    /** [row] chalked [f] of the way (whole at 1), fresh by [bright], at [alpha]; the chalk's tip while it's at it. */
    private fun chalk(q: BoardQuads, row: Row, f: Float, bright: Float, alpha: Float, letters: TextureAtlasSprite): Tip? {
        if (f >= 1f) {
            for (r in row.runs) whole(q, CHALK, r.text, r.x, r.base, r.scale, r.squeeze, fresh(r.argb, bright, alpha), letters)
            return null
        }
        if (f <= 0f) return null
        val plan = row.plan
        val t = f * plan.total
        var tip: Tip? = null
        for (k in plan.strokes.indices) {
            val st = plan.strokes[k]
            val argb = fresh(st.argb, 1f, alpha)
            if (t >= plan.ends[k]) {
                stroke(q, st, 1f, argb, letters)
                continue
            }
            if (t >= plan.starts[k]) {
                val g = (t - plan.starts[k]) / max(1e-4f, plan.ends[k] - plan.starts[k])
                stroke(q, st, g, argb, letters)
                tip = st.tipAt(g, down = true)
            } else {
                // Between strokes, the chalk lifted and on its way to this one
                val prev = if (k > 0) plan.strokes[k - 1] else null
                val from = prev?.tipAt(1f, down = false) ?: st.tipAt(0f, down = false)
                val to = st.tipAt(0f, down = false)
                val e0 = if (k > 0) plan.ends[k - 1] else 0f
                val g = ((t - e0) / max(1e-4f, plan.starts[k] - e0)).coerceIn(0f, 1f)
                tip = Tip(from.u + (to.u - from.u) * g, from.v + (to.v - from.v) * g, false)
            }
            break
        }
        return tip
    }

    /** One piece of a glyph drawn as far as [f] of the way along its stroke. */
    private fun stroke(q: BoardQuads, st: Stroke, f: Float, argb: Int, letters: TextureAtlasSprite) {
        val cell = st.cell ?: return
        if (f >= 1f) {
            cell(q, letters, cell, st.x, st.base, st.sx, st.sy, st.lift, argb, -1, 0, 0f)
            return
        }
        val p = st.pointAt(f)
        val at = if (st.axis == 0) p[0] + st.dir * TIP else p[1] + st.dir * TIP
        cell(q, letters, cell, st.x, st.base, st.sx, st.sy, st.lift, argb, st.axis, st.dir, at)
    }

    /** [text] in [hand], whole, on [flat], from [x] along the baseline [base]. */
    private fun whole(q: BoardQuads, hand: Hand, text: String, x: Float, base: Float, s: Float, squeeze: Float, argb: Int, letters: TextureAtlasSprite) {
        if (alpha(argb) < 0.004f) return
        var pen = x
        val sx = s * squeeze
        for (c in text) {
            val g = hand.glyph(c)
            if (g == null) {
                pen += 3f * sx
                continue
            }
            g.cell?.let { cell(q, letters, it, pen, base, sx, s, CAP, argb, -1, 0, 0f) }
            pen += g.advance * sx
        }
    }

    /** One lettering cell placed with its glyph origin at ([x], [base]) on [flat], clipped along [axis] at [at] (font px) if given. */
    private fun cell(
        q: BoardQuads, sprite: TextureAtlasSprite, c: Cell, x: Float, base: Float, sx: Float, sy: Float, lift: Float, argb: Int,
        axis: Int, dir: Int, at: Float,
    ) {
        if (alpha(argb) < 0.004f) return
        var gx0 = c.left
        var gx1 = c.left + c.width
        var gy0 = c.top
        var gy1 = c.top + c.height
        var tx0 = c.x.toFloat()
        var tx1 = (c.x + c.w).toFloat()
        var ty0 = c.y.toFloat()
        var ty1 = (c.y + c.h).toFloat()
        if (axis == 0) {
            if (dir > 0) {
                if (at <= gx0) return
                if (at < gx1) {
                    tx1 = tx0 + (tx1 - tx0) * (at - gx0) / (gx1 - gx0)
                    gx1 = at
                }
            } else {
                if (at >= gx1) return
                if (at > gx0) {
                    tx0 = tx1 - (tx1 - tx0) * (gx1 - at) / (gx1 - gx0)
                    gx0 = at
                }
            }
        } else if (axis == 1) {
            if (dir > 0) {
                if (at <= gy0) return
                if (at < gy1) {
                    ty1 = ty0 + (ty1 - ty0) * (at - gy0) / (gy1 - gy0)
                    gy1 = at
                }
            } else {
                if (at >= gy1) return
                if (at > gy0) {
                    ty0 = ty1 - (ty1 - ty0) * (gy1 - at) / (gy1 - gy0)
                    gy0 = at
                }
            }
        }
        val u0 = x + gx0 * sx
        val u1 = x + gx1 * sx
        val v0 = base + (gy0 - lift) * sy
        val v1 = base + (gy1 - lift) * sy
        quad(
            q, flat, u0, v0, u1, v1,
            sprite.getU(tx0 / M.LETTERS_W), sprite.getV(ty0 / M.LETTERS_H), sprite.getU(tx1 / M.LETTERS_W), sprite.getV(ty1 / M.LETTERS_H),
            argb, lightAt((u0 + u1) * 0.5f, (v0 + v1) * 0.5f),
        )
    }

    /** A whole lettering-atlas cell stretched over the face rectangle [u0]..[u1], [v0]..[v1]. */
    private fun cellSprite(q: BoardQuads, surface: Surface, sprite: TextureAtlasSprite, c: Cell, u0: Float, v0: Float, u1: Float, v1: Float, argb: Int, light: Int) {
        quad(
            q, surface, u0, v0, u1, v1,
            sprite.getU(c.x.toFloat() / M.LETTERS_W), sprite.getV(c.y.toFloat() / M.LETTERS_H),
            sprite.getU((c.x + c.w).toFloat() / M.LETTERS_W), sprite.getV((c.y + c.h).toFloat() / M.LETTERS_H),
            argb, light,
        )
    }

    /** Texels [tx0]..[tx1] across and [ty0]..[ty1] down of the atlas cell [c] (the whole of it if not given), laid on [s] over [u0]..[u1], [v0]..[v1]. */
    private fun pic(
        q: BoardQuads, s: Surface, atlas: TextureAtlasSprite, c: IntArray, u0: Float, v0: Float, u1: Float, v1: Float, argb: Int, light: Int,
        tx0: Float = 0f, ty0: Float = 0f, tx1: Float = c[2].toFloat(), ty1: Float = c[3].toFloat(),
    ) {
        quad(
            q, s, u0, v0, u1, v1,
            atlas.getU((c[0] + tx0) / A.ATLAS_W), atlas.getV((c[1] + ty0) / A.ATLAS_H), atlas.getU((c[0] + tx1) / A.ATLAS_W), atlas.getV((c[1] + ty1) / A.ATLAS_H),
            argb, light,
        )
    }

    // ---------------------------------------------------------------- the chalk stick and its dust

    private fun stick(
        board: HaulBoardBlockEntity, state: HaulBoardRenderState, mem: Memory, level: Level, pos: BlockPos, tip: Tip?,
        now: Double, frame: Float, fade: Float, letters: TextureAtlasSprite,
    ) {
        // Where the chalk is: at the tip while a row's being written, resting after the row the merchant is on, else put down on the ledge
        var target: Tip? = tip
        if (target == null && board.writing >= 0) {
            mem.shown.getOrNull(board.writing)?.look?.let { look ->
                val end = look.end(AGE)
                val bob = sin((now % 1000.0 * 0.21).toFloat()) * 0.004f
                target = Tip(end[0] + 0.025f, end[1] - 0.01f + bob, false)
            }
        }
        val k = 1f - exp(-frame * 0.55f)
        val t = target
        if (t != null) {
            if (mem.stickAlpha <= 0.01f || mem.stickU.isNaN()) {
                // Taken up from the ledge
                mem.stickU = t.u + 0.08f
                mem.stickV = A.LEDGE_V
                mem.stickLift = 0.02f
            }
            if (tip != null) {
                mem.stickU = t.u
                mem.stickV = t.v
            } else {
                mem.stickU += (t.u - mem.stickU) * k
                mem.stickV += (t.v - mem.stickV) * k
            }
            mem.stickLift += ((if (t.down) 0f else 0.012f) - mem.stickLift) * (1f - exp(-frame * 1.4f))
            mem.stickAlpha = min(1f, mem.stickAlpha + frame / 4f)
        } else {
            mem.stickAlpha = max(0f, mem.stickAlpha - frame / 8f)
            if (!mem.stickU.isNaN()) {
                mem.stickV += (A.LEDGE_V - 0.02f - mem.stickV) * k
                mem.stickLift += (0.03f - mem.stickLift) * k
            }
        }
        if (mem.stickAlpha * fade <= 0.01f || mem.stickU.isNaN()) return

        // The stick leans up and to the right from its tip, out from the slate
        val u = mem.stickU
        val v = mem.stickV
        val z = -A.SLATE_DEPTH + 0.004f + mem.stickLift
        val len = STICK.height * M.S_STICK
        val half = STICK.width * M.S_STICK * 0.42f
        val du = 0.34f * len
        val dv = -0.86f * len
        val dz = 0.38f * len
        val wu = 0.93f * half
        val wv = 0.37f * half
        val argb = scaleAlpha(0xFFFFFFFF.toInt(), mem.stickAlpha * fade)
        val light = lightAt(u, v)
        val q = state.layers[3]
        val su0 = letters.getU(STICK.x.toFloat() / M.LETTERS_W)
        val su1 = letters.getU((STICK.x + STICK.w).toFloat() / M.LETTERS_W)
        val sv0 = letters.getV(STICK.y.toFloat() / M.LETTERS_H)
        val sv1 = letters.getV((STICK.y + STICK.h).toFloat() / M.LETTERS_H)
        q.vertex(u - wu + du, -(v - wv + dv), z + dz, su0, sv0)
        q.vertex(u - wu, -(v - wv), z, su0, sv1)
        q.vertex(u + wu, -(v + wv), z, su1, sv1)
        q.vertex(u + wu + du, -(v + wv + dv), z + dz, su1, sv0)
        q.close(argb, light)

        // Dust from the tip as it writes
        if (tip != null && tip.down && fade > 0.3f && now - mem.lastDust > 2.5) {
            mem.lastDust = now
            val w = world(pos, state.yRot, tip.u, tip.v, z + 0.01f)
            val r = level.random
            level.addParticle(DUST, w.x, w.y - 0.01, w.z, (r.nextDouble() - 0.5) * 0.004, -0.004, (r.nextDouble() - 0.5) * 0.004)
        }
    }

    // ---------------------------------------------------------------- the rail's tickets

    private fun tickets(
        board: HaulBoardBlockEntity, state: HaulBoardRenderState, mem: Memory, level: Level, pos: BlockPos, now: Double, fade: Float,
        atlas: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        // Each ticket as it was last seen: one gone from the rail peels off its peg and drops
        val here = HashSet<Int>()
        for (t in board.tickets) here.add(t.id)
        val gone = mem.hung.entries.iterator()
        while (gone.hasNext()) {
            val (id, h) = gone.next()
            if (id !in here) {
                if (!mem.fresh) mem.dropped.add(Dropped(h.t, now))
                gone.remove()
            }
        }
        for (t in board.tickets) {
            val h = mem.hung[t.id]
            if (h == null) {
                // A ticket found hung while the board is watched comes up gently; one hung by the merchant is drawn as they hang it
                val shownAt = if (!mem.fresh && t.pinned == HaulBoardBlockEntity.LONG_AGO) now else Double.NEGATIVE_INFINITY
                mem.hung[t.id] = Hung(t, shownAt).also {
                    if (mem.fresh || level.gameTime - t.stamped > PUFF_WINDOW) it.puffedStamp = t.stamped
                    if (mem.fresh || level.gameTime - t.sealed > PUFF_WINDOW) it.puffedSeal = t.sealed
                }
            } else {
                if (h.t.seal >= 0 && t.seal < 0 && !mem.fresh) {
                    h.unsealed = h.t.seal
                    h.unsealAt = now
                }
                h.t = t
            }
        }
        mem.dropped.removeAll { now - it.at > PEEL + DROP }

        val wind = 1f + level.getRainLevel(0f) * 0.7f + level.getThunderLevel(0f) * 0.8f
        for (h in mem.hung.values) ticket(state, h.t, h, Double.NaN, level, pos, now, wind, fade, atlas, letters)
        for (d in mem.dropped) ticket(state, d.t, null, d.at, level, pos, now, wind, fade, atlas, letters)
    }

    /**
     * One ticket on its peg: coming down onto it and swinging there ([HaulBoardBlockEntity.Ticket.pinned]), stamped with the hall's wax (thumped, a puff
     * of dust, the wax squashed out and settling), stamped again in its booker's colour, stirring in the [wind]; or peeling off and dropping (since [dropAt]).
     */
    private fun ticket(
        state: HaulBoardRenderState, t: HaulBoardBlockEntity.Ticket, h: Hung?, dropAt: Double, level: Level, pos: BlockPos, now: Double, wind: Float,
        fade: Float, atlas: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        val hash = "haul|${t.id}".hashCode().toLong() * -7046029254386353131L
        fun rnd(bits: Int): Float = ((hash ushr bits) and 1023L).toFloat() / 1023f
        val tw = A.TICKET_BLOCKS
        val th = tw * A.TICKET_H / A.TICKET_W
        val tex = tw / A.TICKET_W
        val slot = t.slot.coerceIn(0, HaulBoardBlockEntity.HOOKS - 1)
        val hookU = A.HOOK_U0 + (slot % 4) * A.HOOK_PITCH
        val hookV = if (slot < 4) A.HOOK_V0 else A.HOOK_V1
        val p = page
        p.w = tw
        p.h = th
        p.pin = th * A.TICKET_EYELET_Y / A.TICKET_H
        p.slotU = hookU - tw * A.TICKET_EYELET_X / A.TICKET_W
        p.slotV = hookV - p.pin
        p.z = -A.TICKET_DEPTH + 0.002f + slot * 0.0004f
        p.turn = (rnd(23) - 0.5f) * 0.05f
        p.du = 0f
        p.dv = 0f
        p.dz = 0f
        p.lift = 0f
        p.ripple = 0f
        p.tilt = 0f
        p.corner = if (rnd(33) < 0.5f) -1 else 1
        p.time = (now % 100000.0 + rnd(43) * 4000.0).toFloat()
        p.inkLift = 0f
        val side = p.corner.toFloat()
        var alpha = 1f
        val dropping = !dropAt.isNaN()
        if (dropping) {
            val df = (now - dropAt).toFloat()
            if (df < PEEL) {
                // Its corner comes off the peg
                val x = ease(df / PEEL)
                p.turn += 0.52f * x * side
                p.dv = -0.012f * x
                p.dz = 0.01f * x
            } else {
                // And it drops, turning, out over the shelf, and is gone
                val d = (df - PEEL) / DROP
                if (d > 0.8f) return
                p.turn += (0.52f + 2.6f * d) * side
                p.dv = -0.012f + 2.0f * d * d
                p.dz = 0.012f + 0.45f * d
                p.du = 0.05f * d * side
                alpha = 1f - ease((d - 0.35f) / 0.45f)
            }
        } else {
            val hung = if (t.pinned == HaulBoardBlockEntity.LONG_AGO) Float.POSITIVE_INFINITY else (now - t.pinned).toFloat()
            if (hung < HANG_IN) {
                // Brought down onto its peg from the merchant's hand
                val e = ease(hung / HANG_IN)
                p.dv = -0.30f * (1f - e)
                p.dz = 0.18f * (1f - e)
                alpha = ease(max(0f, hung) / 3f)
            }
            if (hung < M.FLUTTER * 2) {
                // Swinging on the peg, and settling
                val b = max(0f, hung)
                p.turn += 0.38f * exp(-b / 6f) * cos(2f * PI.toFloat() * b / 9f) * side
            }
            if (h != null && !h.shownAt.isInfinite()) alpha *= ((now - h.shownAt).toFloat() / FADE_IN).coerceIn(0f, 1f)
            // The thump of a stamp
            for (at in longArrayOf(t.stamped, t.sealed)) {
                if (!pressed(at)) continue
                val ds = (now - at).toFloat()
                if (ds in 0f..3f) {
                    p.dv += 0.012f * (1f - ds / 3f)
                    p.dz -= 0.004f * (1f - ds / 3f)
                }
            }
            if (hung >= HANG_IN) {
                // The air sways it on its peg, and now and then a breeze lifts it out from the rail
                val tt = p.time
                p.turn += M.SWAY * min(1f, wind * 0.6f) * (0.6f * sin(tt * 0.031f) + 0.4f * sin(tt * 0.077f + 1.3f))
                val gust = sin(tt * 0.019f) * 0.6f + sin(tt * 0.047f + 1.7f) * 0.4f
                p.tilt = ((gust - 0.55f) / 0.45f).coerceIn(0f, 1f) * min(1.6f, wind) * 0.16f
            }
        }
        if (alpha <= 0.01f) return

        val light = lightAt(hookU, hookV + th / 2)
        // The paper, turned from the light as it's lifted out; its plain back for anyone behind it
        val c = (255 * (1f - 0.3f * min(1f, p.tilt * 4f))).toInt()
        val paper = ((alpha * 255).toInt() shl 24) or (c shl 16) or (c shl 8) or c
        pic(state.layers[1], p, atlas, A.TICKET, 0f, 0f, tw, th, paper, light)
        p.inkLift = -0.002f
        pic(state.layers[1], p, atlas, A.TICKET_BACK, tw, 0f, 0f, th, paper, light)
        p.inkLift = 0f

        val q = state.layers[2]
        val inked = alpha * fade
        fun box(b: IntArray): FloatArray = floatArrayOf(b[0] * tex, b[1] * tex, (b[0] + b[2]) * tex, (b[1] + b[3]) * tex)
        // The marks on the paper: the feast's festoon or the relief cross, the good's chip, the days in notches
        p.inkLift = 0.0008f
        when (t.kind) {
            HaulKind.FEAST -> {
                val b = A.TICKET_FESTOON
                val fh = b[2] * A.FESTOON[3].toFloat() / A.FESTOON[2]
                pic(q, p, atlas, A.FESTOON, b[0] * tex, b[1] * tex, (b[0] + b[2]) * tex, (b[1] + fh) * tex, tint(0xFFFFFF, alpha), light)
            }
            HaulKind.RELIEF -> {
                val b = box(A.TICKET_CROSS)
                pic(q, p, atlas, A.RELIEF_CROSS, b[0], b[1], b[2], b[3], tint(0xFFFFFF, alpha), light)
            }
            else -> {}
        }
        if (inked > 0.004f) {
            val g = A.GOODS.indexOf(t.good.id)
            if (g >= 0) {
                val b = box(A.TICKET_CHIP)
                pic(q, p, atlas, A.CHIP_RED[g], b[0], b[1], b[2], b[3], tint(0xFFFFFF, inked), light)
            }
            val span = t.span.coerceIn(1, A.NOTCH_MAX)
            for (k in 0 until span) {
                val nu = (A.NOTCH_X + k * A.NOTCH_PITCH) * tex
                val nv = A.NOTCH_Y * tex
                pic(q, p, atlas, if (k < t.days) A.NOTCH else A.NOTCH_SPENT, nu, nv, nu + A.NOTCH_W * tex, nv + A.NOTCH_H * tex, tint(0xFFFFFF, inked), light)
            }

            // What it says: how many, the pay and the coin, the shop
            p.inkLift = 0.0012f
            val cb = A.TICKET_COUNT
            val count = t.count.toString()
            val cs = 1.12f * tex
            val cw = width(INK, count) * cs
            val cq = min(1f, cb[2] * tex / max(1e-4f, cw))
            ink(q, p, count, (cb[0] + cb[2]) * tex - cw * cq, (cb[1] + cb[3] * 0.92f) * tex, cs, cq, tint(M.INK, 0.94f * inked), light, letters)
            val pb = A.TICKET_PAY
            val pay = t.pay.toString()
            val ps = 1.0f * tex
            val pw = width(INK, pay) * ps
            val coinW = width(INK, M.COIN.toString()) * ps
            val pq = min(1f, pb[2] * tex / max(1e-4f, pw + 0.5f * tex + coinW))
            val payBase = (pb[1] + pb[3] * 0.9f) * tex
            ink(q, p, pay, pb[0] * tex, payBase, ps, pq, tint(M.INK_STAMP, 0.95f * inked), light, letters)
            ink(q, p, M.COIN.toString(), pb[0] * tex + (pw + 0.5f * tex) * pq, payBase, ps, pq, tint(M.INK_STAMP, 0.95f * inked), light, letters)
            val sb = A.TICKET_SHOP
            val shop = Words.shop(t.shop.id)
            val ss = 0.72f * tex
            val sq = min(1f, sb[2] * tex / max(1e-4f, width(INK, shop) * ss))
            ink(q, p, shop, sb[0] * tex, (sb[1] + sb[3]) * tex, ss, sq, tint(M.INK, 0.9f * inked), light, letters)
        }

        // The hall's wax, once the stamp has come down; the booker's seal pressed over it in their colour
        val seal = box(A.TICKET_SEAL)
        val mu = (seal[0] + seal[2]) / 2
        val mv = (seal[1] + seal[3]) / 2
        val half = (seal[2] - seal[0]) / 2
        if (pressed(t.stamped) && now >= t.stamped) {
            val ds = (now - t.stamped).toFloat()
            p.inkLift = 0.0025f
            turned.set(mu, mv, -0.08f, squash(ds))
            pic(q, turned, atlas, A.SEAL_TINT, -half, -half, half, half, tint(HALL_WAX, alpha), light)
            if (h != null && h.puffedStamp != t.stamped && ds < PUFF_WINDOW) {
                h.puffedStamp = t.stamped
                puff(level, pos, state.yRot, p, mu, mv, STAMP_DUST)
            }
        }
        val booker = t.seal.takeIf { it in 0 until A.SEAL.size }
        if (booker != null && (t.sealed == HaulBoardBlockEntity.LONG_AGO || now >= t.sealed)) {
            val dq = if (t.sealed == HaulBoardBlockEntity.LONG_AGO) Float.POSITIVE_INFINITY else (now - t.sealed).toFloat()
            p.inkLift = 0.0035f
            turned.set(mu + 2.5f * tex, mv + 2.5f * tex, -0.2f, squash(dq))
            pic(q, turned, atlas, A.SEAL[booker], -half, -half, half, half, tint(0xFFFFFF, alpha), light)
            if (h != null && h.puffedSeal != t.sealed && dq < PUFF_WINDOW) {
                h.puffedSeal = t.sealed
                puff(level, pos, state.yRot, p, mu, mv, STAMP_DUST)
            }
        } else if (h != null && h.unsealed >= 0 && !h.unsealAt.isInfinite()) {
            // A booking given up: its seal comes away
            val a = 1f - ((now - h.unsealAt).toFloat() / UNSEAL).coerceIn(0f, 1f)
            if (a <= 0f) {
                h.unsealed = -1
            } else {
                p.inkLift = 0.0035f
                turned.set(mu + 2.5f * tex, mv + 2.5f * tex, -0.2f, 1f + 0.15f * (1f - a))
                pic(q, turned, atlas, A.SEAL[h.unsealed.coerceIn(0, A.SEAL.size - 1)], -half, -half, half, half, tint(0xFFFFFF, alpha * a), light)
            }
        }
        p.inkLift = 0f
    }

    /** A puff of dust off the paper at ([u], [v]) of page [p]. */
    private fun puff(level: Level, pos: BlockPos, yRot: Float, p: Page, u: Float, v: Float, dust: DustParticleOptions) {
        p.map(u, v, corner)
        val w = world(pos, yRot, corner[0], -corner[1], corner[2] + 0.02f)
        val r = level.random
        repeat(5) {
            val a = r.nextDouble() * 2 * PI
            level.addParticle(dust, w.x, w.y, w.z, cos(a) * 0.012, sin(a) * 0.01 + 0.004, cos(a + 1.1) * 0.012)
        }
    }

    // ---------------------------------------------------------------- the satchel

    /** The merchant's satchel on the shelf while they work: set down with a little drop. */
    private fun satchel(board: HaulBoardBlockEntity, state: HaulBoardRenderState, level: Level, now: Double) {
        if (!board.satchel) return
        itemModels.updateForTopItem(state.satchel, SATCHEL, ItemDisplayContext.FIXED, level, null, 0)
        state.satchelOn = true
        val since = if (board.satchelAt == HaulBoardBlockEntity.LONG_AGO) Float.POSITIVE_INFINITY else (now - board.satchelAt).toFloat()
        state.satchelLift = if (since < 6f) {
            val g = (since / 6f).coerceIn(0f, 1f)
            0.06f * (1f - g * g) + 0.008f * sin(g * PI.toFloat())
        } else {
            0f
        }
        state.satchelLight = lightAt(A.SATCHEL_U, A.SATCHEL_V - 0.1f)
    }

    // ---------------------------------------------------------------- submitting

    override fun submit(state: HaulBoardRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (!state.any) return
        poseStack.pushPose()
        // Into the board's frame: turned as its cells are, then x right, y up and z out from the front's top left
        poseStack.translate(0.5f, 0f, 0.5f)
        poseStack.rotateDegrees(Axis.YP, -state.yRot)
        poseStack.translate(-0.5f, 0f, -0.5f)
        poseStack.translate(2f, 2f, 0f)
        poseStack.rotateDegrees(Axis.YP, 180f)

        for ((k, quads) in state.layers.withIndex()) {
            if (quads.size == 0) continue
            collector.order(k).submitCustomGeometry(poseStack, type) { pose, buffer -> quads.emit(pose, buffer) }
        }

        if (state.satchelOn) {
            poseStack.pushPose()
            poseStack.translate(A.SATCHEL_U, -(A.SATCHEL_V - SATCHEL_SIZE * 0.47f) + state.satchelLift, -A.SATCHEL_DEPTH)
            poseStack.rotateDegrees(Axis.YP, 180f)
            poseStack.scale(SATCHEL_SIZE, SATCHEL_SIZE, SATCHEL_SIZE)
            state.satchel.submit(poseStack, collector, state.satchelLight, OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    // ---------------------------------------------------------------- helpers

    private val flat = Flat()
    private val spin = Spin()
    private val page = Page()
    private val turned = Turned(page)
    private val corner = FloatArray(3)
    private val lights = IntArray(6)

    /** [text] inked on [s] (a ticket, or a stamp on one) from [x] along [base], squeezed across by [squeeze]. */
    private fun ink(q: BoardQuads, s: Surface, text: String, x: Float, base: Float, size: Float, squeeze: Float, argb: Int, light: Int, sprite: TextureAtlasSprite) {
        if (alpha(argb) < 0.004f) return
        var pen = x
        val sx = size * squeeze
        for (c in text) {
            val g = INK.glyph(c)
            if (g == null) {
                pen += 3f * sx
                continue
            }
            g.cell?.let { cell ->
                quad(
                    q, s, pen + cell.left * sx, base + (cell.top - CAP) * size, pen + (cell.left + cell.width) * sx, base + (cell.top + cell.height - CAP) * size,
                    sprite.getU(cell.x.toFloat() / M.LETTERS_W), sprite.getV(cell.y.toFloat() / M.LETTERS_H),
                    sprite.getU((cell.x + cell.w).toFloat() / M.LETTERS_W), sprite.getV((cell.y + cell.h).toFloat() / M.LETTERS_H),
                    argb, light,
                )
            }
            pen += g.advance * sx
        }
    }

    private fun quad(
        q: BoardQuads, s: Surface, u0: Float, v0: Float, u1: Float, v1: Float, s0: Float, t0: Float, s1: Float, t1: Float,
        argb: Int, light: Int,
    ) {
        if (argb ushr 24 == 0) return
        s.map(u0, v0, corner)
        q.vertex(corner[0], corner[1], corner[2], s0, t0)
        s.map(u0, v1, corner)
        q.vertex(corner[0], corner[1], corner[2], s0, t1)
        s.map(u1, v1, corner)
        q.vertex(corner[0], corner[1], corner[2], s1, t1)
        s.map(u1, v0, corner)
        q.vertex(corner[0], corner[1], corner[2], s1, t0)
        q.close(argb, light)
    }

    /** The light at each of the six cells (or in front of it, if brighter), row by row from the top left. */
    private fun cellLights(level: Level, anchor: BlockPos, facing: Direction) {
        for (part in BoardPart.entries) {
            val cell = NoticeBoardBlock.cellPos(anchor, facing, part)
            val l = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, cell), LightCoordsUtil.getLightCoords(level, cell.relative(facing)))
            lights[(1 - part.row) * 3 + part.col + 1] = l
        }
    }

    /** The light at a point of the face, blended between the cells' so a lamp at one end lights that end. */
    private fun lightAt(u: Float, v: Float): Int {
        val fu = (u - 0.5f).coerceIn(0f, 2f)
        val i = min(1, fu.toInt())
        val tu = fu - i
        val tv = (v - 0.5f).coerceIn(0f, 1f)
        val w0 = (1 - tu) * (1 - tv)
        val w1 = tu * (1 - tv)
        val w2 = (1 - tu) * tv
        val w3 = tu * tv
        val a = lights[i]
        val b = lights[i + 1]
        val c = lights[3 + i]
        val d = lights[4 + i]
        val block = (a and 0xFFFF) * w0 + (b and 0xFFFF) * w1 + (c and 0xFFFF) * w2 + (d and 0xFFFF) * w3
        val sky = (a ushr 16) * w0 + (b ushr 16) * w1 + (c ushr 16) * w2 + (d ushr 16) * w3
        return (block + 0.5f).toInt() or ((sky + 0.5f).toInt() shl 16)
    }

    companion object {
        private val LETTERS_ID = SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, M.LETTERS))
        private val ATLAS_ID = SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, A.ATLAS))
        private val type: RenderType by lazy { RenderTypes.textPolygonOffset(TextureAtlas.LOCATION_BLOCKS) }
        private val DUST = DustParticleOptions(0xF2EFE6, 0.45f)
        private val STAMP_DUST = DustParticleOptions(0xD9CDB2, 0.6f)
        private val SATCHEL: ItemStack by lazy { ItemStack(Items.BUNDLE) }

        /** The hall's wax, as the board's screen has it. */
        private const val HALL_WAX = 0x9C6A2A

        /** Ticks without being drawn after which the board is taken to be looked at afresh. */
        private const val UNSEEN = 40.0

        /** The most ticks the chalking of one row takes (the merchant gives a row [HaulBoardBlockEntity.ROW_TICKS]). */
        private const val LIMIT = 54f

        /** Ticks: the glass tipped end for end, the chalk's way from one part to the next, a chip's three strokes and the gaps between them. */
        private const val TURN = 12f
        private const val GAP = 1.5f
        private const val CHIP_STROKE = 3f
        private const val CHIP_GAP = 1f
        private const val CHIP_TAKES = 3 * CHIP_STROKE + 2 * CHIP_GAP

        /** Ticks a row found changed while watched takes to fade over to the new one. */
        private const val CROSS = 20f

        /** Game ticks to an hour of the Word's clock, and a row's news stale at three days: faded and struck through. */
        private const val STALE_TICKS = 72_000L
        private const val STALE_FADE = 0.42f
        private const val STRIKE_ALPHA = 0.85f

        /** Ticks: a ticket brought down onto its peg, found hung and fading up, peeling off its peg and dropping, a booker's seal coming away. */
        private const val HANG_IN = 8f
        private const val FADE_IN = 10f
        private const val PEEL = 8f
        private const val DROP = 20f
        private const val UNSEAL = 10f

        /** Game ticks after a stamp within which a viewer still sees its dust go up. */
        private const val PUFF_WINDOW = 4L

        private const val HEADER_MAX_W = 2.2f
        private const val SATCHEL_SIZE = 0.3f

        private const val CAP = 7f
        private const val TIP = 0.95f
        private const val SECTORS = 24
        private val SECTOR = (2 * PI / SECTORS).toFloat()

        /** The parts of a row, in the order the merchant's hand goes over them. */
        private const val GLASS = 0
        private const val NAME = 1
        private const val QUILL = 2
        private const val CHIP0 = 3
        private const val AGE = 9
        private const val PENNANT = 10
        private const val SEAL = 11
        private const val COUNT = 12
        private const val STRIKE = 13
        private const val PARTS = 14
        private val ORDER = IntArray(PARTS) { it }

        private val CHALK: Hand by lazy { Hand(M.CHALK_GLYPHS, 1.0f) }
        private val INK: Hand by lazy { Hand(M.INK_GLYPHS, 0.85f) }
        private val SMUDGE by lazy { Cell.of(M.SMUDGE) }
        private val STICK by lazy { Cell.of(M.STICK) }

        private val memory = WeakHashMap<HaulBoardBlockEntity, Memory>()
        private val lines = Lines()

        private fun yRot(facing: Direction): Float = when (facing) {
            Direction.EAST -> 90f
            Direction.SOUTH -> 180f
            Direction.WEST -> 270f
            else -> 0f
        }

        /** Where a point of the face ([u] right, [v] down from the top left, [d] out from the cells' front) is in the world. */
        private fun world(pos: BlockPos, yRot: Float, u: Float, v: Float, d: Float): Vec3 {
            val lx = 2.0 - u - 0.5
            val lz = -d - 0.5
            val a = Math.toRadians(-yRot.toDouble())
            val c = cos(a)
            val s = sin(a)
            return Vec3(pos.x + 0.5 + lx * c + lz * s, pos.y + 2.0 - v, pos.z + 0.5 - lx * s + lz * c)
        }

        private fun bit(e: Int): Int = 1 shl e

        private infix fun Int.has(e: Int): Boolean = this and (1 shl e) != 0

        /** Whether the board's row [a] is the one [b] was (both none, or the same writing chalked at the same time). */
        private fun sameSource(a: HaulBoardBlockEntity.Row?, b: HaulBoardBlockEntity.Row?): Boolean =
            if (a == null || b == null) a === b else a.same(b) && a.chalked == b.chalked

        /** How old [row]'s news is by the clock [clock], in game ticks. */
        private fun ageOf(row: HaulBoardBlockEntity.Row, clock: Long): Long = if (row.age >= 0) row.age else max(0L, clock - row.tick)

        private fun ageTextOf(row: HaulBoardBlockEntity.Row, clock: Long): String = Words.age(ageOf(row, clock).toDouble())

        private fun staleOf(row: HaulBoardBlockEntity.Row, clock: Long): Boolean = ageOf(row, clock) >= STALE_TICKS

        /** How far [look]'s glass has run by the clock [clock] (0 fresh, 1 spent). */
        private fun sand(look: Look, clock: Long): Float {
            val age = if (look.age >= 0) look.age else max(0L, clock - look.tick)
            return (age.toDouble() / STALE_TICKS).toFloat().coerceIn(0f, 1f)
        }

        /** Whether [look] has part [e] at all. */
        private fun has(look: Look, e: Int): Boolean = when (e) {
            GLASS, NAME, AGE -> true
            QUILL -> look.quill
            PENNANT -> look.road in 0 until A.PENNANT.size
            SEAL, COUNT -> look.count > 0
            STRIKE -> look.stale
            else -> look.chips[e - CHIP0] >= 0
        }

        private fun present(look: Look): Int {
            var out = 0
            for (e in ORDER) if (has(look, e)) out = out or bit(e)
            return out
        }

        /** The parts that are not the same in [a] and [b]. */
        private fun diff(a: Look, b: Look): Int {
            var out = 0
            for (e in ORDER) {
                val differs = when (e) {
                    GLASS -> a.tick != b.tick || a.age != b.age
                    NAME -> a.name != b.name
                    QUILL -> a.quill != b.quill
                    AGE -> a.ageText != b.ageText
                    PENNANT -> a.road != b.road
                    SEAL -> (a.count > 0) != (b.count > 0)
                    COUNT -> a.count != b.count
                    STRIKE -> a.stale != b.stale
                    else -> a.chips[e - CHIP0] != b.chips[e - CHIP0]
                }
                if (differs) out = out or bit(e)
            }
            return out
        }

        /** Whether a stamp or a seal is down: not [HaulBoardBlockEntity.UNSTAMPED]. */
        private fun pressed(at: Long): Boolean = at != HaulBoardBlockEntity.UNSTAMPED

        /** A seal's size this long after it was pressed: squashed out and settling. */
        private fun squash(d: Float): Float = if (d in 0f..12f) 1f + 0.55f * exp(-d / 2.5f) * cos(PI.toFloat() * d / 2.5f) else 1f

        private fun width(hand: Hand, text: String): Float {
            var w = 0f
            for (c in text) w += hand.glyph(c)?.advance ?: 3f
            return max(0f, w - hand.gap)
        }

        private fun smooth(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        private fun ease(x: Float): Float = smooth(x)

        private fun alpha(argb: Int): Float = (argb ushr 24) / 255f

        private fun tint(rgb: Int, a: Float = 0.94f): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

        private fun scaleAlpha(argb: Int, k: Float): Int = ((alpha(argb) * k.coerceIn(0f, 1f) * 255).toInt() shl 24) or (argb and 0xFFFFFF)

        /** Chalk's colour while fresh: whiter and stronger, settling to its own; at [k] of the alpha it was given. */
        private fun fresh(argb: Int, k: Float, alpha: Float): Int {
            val rgb = mixRgb(argb and 0xFFFFFF, M.CHALK_FRESH, 0.6f * k)
            return ((min(1f, alpha(argb) + 0.06f * k) * alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or rgb
        }

        private fun mixRgb(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 255
                val y = (b shr shift) and 255
                return (x + (y - x) * k).toInt().coerceIn(0, 255) shl shift
            }
            return ch(16) or ch(8) or ch(0)
        }
    }

    // ---------------------------------------------------------------- a row as the slate shows it

    /**
     * Row [i] of the slate as it is drawn from the board's [row] by the clock [clock]: the town's name, the chips' goods (three red, three green, by
     * their place in [HaulBoardArt.GOODS]; -1 for none), when its news is of, the age chalked by it and whether it is stale, its worst road, how many
     * tickets it had out, and whether a report brought it; with its name, age and count laid out in chalk.
     */
    private class Look(val i: Int, row: HaulBoardBlockEntity.Row, clock: Long) {
        val top = A.PANEL_V0 + A.ROWS_TOP + i * A.ROW_PITCH
        val name = Words.town(row.name)
        val chips = IntArray(6) { k ->
            val goods = if (k < 3) row.shorts else row.gluts
            goods.getOrNull(k % 3)?.let { A.GOODS.indexOf(it.id) } ?: -1
        }
        val tick = row.tick
        val age = row.age
        val ageText = ageTextOf(row, clock)
        val stale = staleOf(row, clock)
        val road = row.road
        val count = row.tickets
        val quill = row.by.isNotEmpty()

        val nameRow = Row()
        val ageRow = Row()
        val countRow: Row?
        val nameEnd: Float

        init {
            val left = A.SLATE_U0 + A.PAD_L
            val chalk = tint(M.CHALK)
            val room = A.HOURGLASS_X - A.NAME_X - 0.025f - if (quill) 0.07f else 0f
            val nw = width(CHALK, name) * A.S_NAME
            val ns = min(1f, room / max(1e-4f, nw))
            nameRow.run(name, left + A.NAME_X, top + A.NAME_BASE, A.S_NAME, chalk, ns)
            nameEnd = left + A.NAME_X + nw * ns
            val aw = width(CHALK, ageText) * A.S_AGE
            val ar = A.PENNANT_X - A.AGE_X - 0.012f
            ageRow.run(ageText, left + A.AGE_X, top + A.AGE_BASE, A.S_AGE, chalk, min(1f, ar / max(1e-4f, aw)))
            countRow = if (count > 0) Row().also { it.run(count.toString(), left + A.COUNT_X, top + A.COUNT_BASE, A.S_COUNT, chalk) } else null
        }

        /** The face rectangle part [e] takes: u0, v0, u1, v1. */
        fun box(e: Int): FloatArray {
            val left = A.SLATE_U0 + A.PAD_L
            return when (e) {
                NAME -> floatArrayOf(left + A.NAME_X, top + A.NAME_BASE - 7f * A.S_NAME, nameEnd, top + A.NAME_BASE + 1.5f * A.S_NAME)
                AGE -> {
                    val r = ageRow.runs[0]
                    floatArrayOf(r.x, r.base - 7f * r.scale, r.x + width(CHALK, r.text) * r.scale * r.squeeze, r.base + 1.5f * r.scale)
                }
                COUNT -> {
                    val r = countRow?.runs?.get(0)
                    if (r == null) floatArrayOf(left + A.COUNT_X, top, left + A.COUNT_X, top)
                    else floatArrayOf(r.x, r.base - 7f * r.scale, r.x + width(CHALK, r.text) * r.scale, r.base + 1.5f * r.scale)
                }
                GLASS -> floatArrayOf(left + A.HOURGLASS_X, top + A.HOURGLASS_MID - A.HOURGLASS_H * A.TEXEL / 2, left + A.HOURGLASS_X + A.HOURGLASS_W * A.TEXEL,
                    top + A.HOURGLASS_MID + A.HOURGLASS_H * A.TEXEL / 2)
                PENNANT -> floatArrayOf(left + A.PENNANT_X, top + A.PENNANT_TOP, left + A.PENNANT_X + 22f * A.TEXEL, top + A.PENNANT_TOP + 32f * A.TEXEL)
                SEAL -> {
                    val ss = A.SEAL_TEXELS * A.SEAL_TEXEL
                    floatArrayOf(left + A.SEAL_X, top + A.SEAL_MID - ss / 2, left + A.SEAL_X + ss, top + A.SEAL_MID + ss / 2)
                }
                QUILL -> {
                    val s = 16 * A.TEXEL
                    floatArrayOf(nameEnd + 0.012f, top + A.NAME_BASE - s + 0.004f, nameEnd + 0.012f + s, top + A.NAME_BASE + 0.004f)
                }
                STRIKE -> floatArrayOf(left, top + A.STRUCK_Y, A.SLATE_U1 - A.PAD_R, top + A.STRUCK_Y + 8f * A.TEXEL)
                else -> {
                    val k = e - CHIP0
                    val cu = left + (if (k < 3) A.CHIP_RED_X else A.CHIP_GREEN_X) + (k % 3) * A.CHIP_PITCH
                    floatArrayOf(cu, top + A.CHIP_TOP, cu + A.CHIP_BLOCKS, top + A.CHIP_TOP + A.CHIP_BLOCKS)
                }
            }
        }

        /** Where the hand starts on part [e], and where it leaves it (face blocks). */
        fun start(e: Int): FloatArray = when (e) {
            NAME, AGE, COUNT -> (if (e == NAME) nameRow else if (e == AGE) ageRow else countRow)?.plan?.strokes?.firstOrNull()?.tipAt(0f, false)
                ?.let { floatArrayOf(it.u, it.v) } ?: mid(e)
            else -> mid(e)
        }

        fun end(e: Int): FloatArray = when (e) {
            NAME, AGE, COUNT -> (if (e == NAME) nameRow else if (e == AGE) ageRow else countRow)?.plan?.strokes?.lastOrNull()?.tipAt(1f, false)
                ?.let { floatArrayOf(it.u, it.v) } ?: mid(e)
            else -> mid(e)
        }

        private fun mid(e: Int): FloatArray {
            val b = box(e)
            return floatArrayOf((b[0] + b[2]) / 2, b[1])
        }
    }

    /** What a viewer's client keeps of one row of the slate: what the board had, how it is drawn, and how it is being written over. */
    private class Shown {
        var src: HaulBoardBlockEntity.Row? = null
        var look: Look? = null

        /** When its writing began (game ticks; -infinity: shown whole), the parts being written and when each is, and whether it's fading over instead. */
        var start = Double.NEGATIVE_INFINITY
        var changed = 0
        val from = FloatArray(PARTS)
        val to = FloatArray(PARTS)
        var cross = false

        /** How far the glass had run before it was tipped for new news (NaN: not tipped). */
        var tipFrom = Float.NaN

        /** The old row's parts going under the rag (or fading out), and since when. */
        var old: Look? = null
        var oldParts = 0
        var oldAt = 0.0
        var oldCross = false

        fun rubbing(look: Look, parts: Int, at: Double, cross: Boolean) {
            old = if (parts != 0) look else null
            oldParts = parts
            oldAt = at
            oldCross = cross
        }

        fun forget() {
            src = null
            look = null
            old = null
            start = Double.NEGATIVE_INFINITY
            changed = 0
        }
    }

    // ---------------------------------------------------------------- the lettering (the market board's hands)

    /** A cell of the lettering atlas: texels [x], [y], [w], [h], with its top left at ([left], [top]) font px of its glyph. */
    private class Cell(val x: Int, val y: Int, val w: Int, val h: Int, val left: Float, val top: Float) {
        val width: Float get() = w / M.TEXELS.toFloat()
        val height: Float get() = h / M.TEXELS.toFloat()

        companion object {
            fun of(a: IntArray) = Cell(a[0], a[1], a[2], a[3], a[4] / 1000f, a[5] / 1000f)
        }
    }

    /** One piece of a chalk glyph: what of it shows ([cell]), the way it's swept, and the chalk's path over it (font px). */
    private class Piece(val cell: Cell?, val axis: Int, val dir: Int, val path: FloatArray)

    private class Glyph(val advance: Float, val cell: Cell?, val pieces: Array<Piece>)

    /** A hand of lettering read from [io.github.vaspyyy.guildmark.client.MarketBoardArt]'s tables; letters it hasn't got are written as their plain forms. */
    private class Hand(table: String, val gap: Float) {
        private val glyphs = HashMap<Char, Glyph>()

        init {
            val n = table.split(',').map { it.trim().toInt() }
            var i = 0
            fun cell(): Cell {
                val c = Cell(n[i], n[i + 1], n[i + 2], n[i + 3], n[i + 4] / 1000f, n[i + 5] / 1000f)
                i += 6
                return c
            }
            while (i < n.size) {
                val ch = n[i++].toChar()
                val advance = n[i++] / 100f
                val whole = if (n[i++] == 1) cell() else null
                val pieces = Array(n[i++]) {
                    val c = if (n[i++] == 1) cell() else null
                    val axis = n[i++]
                    val dir = n[i++]
                    val points = n[i++]
                    val path = FloatArray(points * 2) { k -> n[i + k] / 100f }
                    i += points * 2
                    Piece(c, axis, dir, path)
                }
                glyphs[ch] = Glyph(advance, whole, pieces)
            }
        }

        fun glyph(c: Char): Glyph? = glyphs[c] ?: glyphs[plain(c)]

        private fun plain(c: Char): Char = when (c) {
            '‘', '’', '‛' -> '\''
            '“', '”' -> '"'
            else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: '?'
        }
    }

    /** Text in the chalk hand along a baseline, [squeeze]d across if it had to be. */
    private class Run(val text: String, val x: Float, val base: Float, val scale: Float, val argb: Int, val squeeze: Float)

    /** Where the chalk's tip is (face blocks), and whether it's on the slate or lifted between strokes. */
    private class Tip(val u: Float, val v: Float, val down: Boolean)

    /** One stroke of the chalk: a glyph's piece placed on the face, glyph font px scaled by [sx] across and [sy] down. */
    private class Stroke(
        val cell: Cell?, val axis: Int, val dir: Int, val path: FloatArray, val x: Float, val base: Float, val sx: Float, val sy: Float,
        val lift: Float, val argb: Int,
    ) {
        /** Its length on the face, in font px of the market board's prices (the chalk's pace is the market board's). */
        val length: Float = run {
            var l = 0f
            for (i in 2 until path.size step 2) l += hypot((path[i] - path[i - 2]) * sx, (path[i + 1] - path[i - 1]) * sy)
            l / M.S_PRICE
        }
        private val point = FloatArray(2)

        /** The point of its path [f] of the way along (glyph font px). */
        fun pointAt(f: Float): FloatArray {
            if (path.size < 4) {
                point[0] = path.getOrElse(0) { 0f }
                point[1] = path.getOrElse(1) { 0f }
                return point
            }
            val want = f.coerceIn(0f, 1f) * length * M.S_PRICE
            var run = 0f
            for (i in 2 until path.size step 2) {
                val seg = hypot((path[i] - path[i - 2]) * sx, (path[i + 1] - path[i - 1]) * sy)
                if (run + seg >= want || i == path.size - 2) {
                    val g = if (seg > 0f) ((want - run) / seg).coerceIn(0f, 1f) else 1f
                    point[0] = path[i - 2] + (path[i] - path[i - 2]) * g
                    point[1] = path[i - 1] + (path[i + 1] - path[i - 1]) * g
                    return point
                }
                run += seg
            }
            return point
        }

        fun tipAt(f: Float, down: Boolean): Tip {
            val p = pointAt(f)
            return Tip(x + p[0] * sx, base + (p[1] - lift) * sy, down)
        }
    }

    /** When each stroke of a row starts and ends, in ticks at the chalk's own pace. */
    private class Plan(val strokes: List<Stroke>) {
        val starts = FloatArray(strokes.size)
        val ends = FloatArray(strokes.size)
        val total: Float

        init {
            var t = 0f
            var last: Tip? = null
            for ((k, s) in strokes.withIndex()) {
                val from = s.tipAt(0f, false)
                last?.let { t += hypot(from.u - it.u, from.v - it.v) / (M.TRAVEL_SPEED * M.S_PRICE) }
                starts[k] = t
                t += s.length / M.DRAW_SPEED
                ends[k] = t
                last = s.tipAt(1f, false)
            }
            total = max(t, 1f)
        }
    }

    /** What's chalked in one piece of a row (a name, an age, a count), whole and as strokes. */
    private class Row {
        val runs = ArrayList<Run>()

        fun run(text: String, x: Float, base: Float, scale: Float, argb: Int, squeeze: Float = 1f) {
            runs.add(Run(text, x, base, scale, argb, squeeze))
        }

        val plan: Plan by lazy {
            val strokes = ArrayList<Stroke>()
            for (r in runs) {
                var pen = r.x
                val sx = r.scale * r.squeeze
                for (c in r.text) {
                    val g = CHALK.glyph(c)
                    if (g == null) {
                        pen += 3f * sx
                        continue
                    }
                    for (piece in g.pieces) strokes.add(Stroke(piece.cell, piece.axis, piece.dir, piece.path, pen, r.base, sx, r.scale, CAP, r.argb))
                    pen += g.advance * sx
                }
            }
            Plan(strokes)
        }
    }

    // ---------------------------------------------------------------- surfaces the quads are laid on

    private interface Surface {
        fun map(u: Float, v: Float, out: FloatArray)
    }

    /** The face itself, at a depth: the slate, the header plank. */
    private class Flat : Surface {
        var z = 0f

        override fun map(u: Float, v: Float, out: FloatArray) {
            out[0] = u
            out[1] = -v
            out[2] = z
        }
    }

    /** The face at a depth, turned by [turn] and scaled by [scale] about ([cu], [cv]): the hourglass tipped, a seal pressed. */
    private class Spin : Surface {
        var cu = 0f
        var cv = 0f
        var turn = 0f
        var z = 0f
        var scale = 1f

        fun set(cu: Float, cv: Float, turn: Float, z: Float, scale: Float) {
            this.cu = cu
            this.cv = cv
            this.turn = turn
            this.z = z
            this.scale = scale
        }

        override fun map(u: Float, v: Float, out: FloatArray) {
            val c = cos(turn)
            val s = sin(turn)
            val x = (u - cu) * scale
            val y = (v - cv) * scale
            out[0] = cu + x * c - y * s
            out[1] = -(cv + x * s + y * c)
            out[2] = z
        }
    }

    /**
     * A ticket's paper, in its own blocks from its top left: [w] by [h], hung from its eyelet [pin] down from the middle of its top, turned on its peg and
     * tilted out from the board by [tilt] (blocks out a block down) where the air takes it.
     */
    private class Page : Surface {
        var w = 0.25f
        var h = 0.28f
        var pin = 0.03f
        var tilt = 0f
        var slotU = 0f
        var slotV = 0f
        var z = 0f
        var turn = 0f
        var du = 0f
        var dv = 0f
        var dz = 0f
        var lift = 0f
        var ripple = 0f
        var corner = 1
        var time = 0f
        var inkLift = 0f

        override fun map(u: Float, v: Float, out: FloatArray) {
            val px = u - w / 2
            val py = v - pin
            val c = cos(turn)
            val s = sin(turn)
            out[0] = slotU + w / 2 + px * c - py * s + du
            out[1] = -(slotV + pin + px * s + py * c + dv)
            out[2] = z + dz + max(0f, py) * tilt + inkLift
        }
    }

    /** Something turned and scaled about a point of a [page] (a seal on a ticket), in its own blocks from that point. */
    private class Turned(val page: Page) : Surface {
        var u = 0f
        var v = 0f
        var turn = 0f
        var scale = 1f

        fun set(u: Float, v: Float, turn: Float, scale: Float) {
            this.u = u
            this.v = v
            this.turn = turn
            this.scale = scale
        }

        override fun map(u: Float, v: Float, out: FloatArray) {
            val c = cos(turn)
            val s = sin(turn)
            val x = u * scale
            val y = v * scale
            page.map(this.u + x * c - y * s, this.v + x * s + y * c, out)
        }
    }

    // ---------------------------------------------------------------- what a viewer's client remembers of a board

    /** A ticket on the rail as last seen: since when it's been coming up (found hung while watched), the stamp and seal whose dust has gone up, a seal coming away. */
    private class Hung(var t: HaulBoardBlockEntity.Ticket, val shownAt: Double) {
        var puffedStamp = Long.MIN_VALUE
        var puffedSeal = Long.MIN_VALUE
        var unsealed = -1
        var unsealAt = Double.NEGATIVE_INFINITY
    }

    /** A ticket taken down at [at], peeling off its peg and dropping. */
    private class Dropped(val t: HaulBoardBlockEntity.Ticket, val at: Double)

    private class Memory {
        /** Nothing seen yet (or not for a while): everything there is shown as it stands, with nothing being rubbed out. */
        var fresh = true
        var lastFrame = Double.NaN
        var checked = Double.NEGATIVE_INFINITY
        val shown = Array(HaulBoardBlockEntity.ROWS) { Shown() }
        val hung = HashMap<Int, Hung>()
        val dropped = ArrayList<Dropped>()
        var stickU = Float.NaN
        var stickV = 0f
        var stickLift = 0f
        var stickAlpha = 0f
        var lastDust = 0.0
        var words = -1
    }

    /** The board's own words in the player's language, looked up again when it changes. */
    private class Lines {
        private var language: Language? = null
        var version = 0
            private set
        var title = ""
            private set
        var header = ""
            private set

        fun refresh() {
            val now = Language.getInstance()
            if (now === language) return
            language = now
            version++
            title = Words.tr("gui.guildmark.trade.board.slate")
            header = Words.tr("gui.guildmark.trade.board.title").uppercase(Locale.ROOT)
        }
    }
}
