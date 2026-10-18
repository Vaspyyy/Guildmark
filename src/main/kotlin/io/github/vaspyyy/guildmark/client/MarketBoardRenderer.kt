package io.github.vaspyyy.guildmark.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity
import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.client.MarketBoardArt as A
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.MarketBoards
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import net.minecraft.client.gui.Font
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
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import java.text.Normalizer
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flat quads for one layer of a market board, in the board's own frame: x to the right as seen from the front, y up,
 * z out toward whoever reads it; one block to a unit, the origin at the board's top left on the cells' front.
 */
class BoardQuads {
    var size = 0
        private set
    private var data = FloatArray(20 * 128)
    private var colours = IntArray(128)
    private var lights = IntArray(128)
    private var put = 0

    fun clear() {
        size = 0
        put = 0
    }

    fun vertex(x: Float, y: Float, z: Float, u: Float, v: Float) {
        if (put + 5 > data.size) data = data.copyOf(data.size * 2)
        data[put++] = x
        data[put++] = y
        data[put++] = z
        data[put++] = u
        data[put++] = v
    }

    /** Closes the four vertices just given (top left, bottom left, bottom right, top right, as seen) as a quad. */
    fun close(argb: Int, light: Int) {
        if (size == colours.size) {
            colours = colours.copyOf(size * 2)
            lights = lights.copyOf(size * 2)
        }
        colours[size] = argb
        lights[size] = light
        size++
    }

    fun emit(pose: PoseStack.Pose, buffer: VertexConsumer) {
        var o = 0
        for (q in 0 until size) {
            val c = colours[q]
            val l = lights[q]
            repeat(4) {
                buffer.addVertex(pose, data[o], data[o + 1], data[o + 2]).setUv(data[o + 3], data[o + 4]).setColor(c).setLight(l)
                o += 5
            }
        }
    }
}

/**
 * What a market board needs drawn this frame: the writing, the slips and the orders' tickets as flat quads, the goods'
 * icons, the town's name.
 */
class MarketBoardRenderState : BlockEntityRenderState() {
    /** The board's turn from facing north, in degrees (as its blockstate turns its cells). */
    var yRot = 0f
    var any = false

    /**
     * Drawn one after another: the chalk on the slate and the slips' shadows; the slips and the tickets; their ink,
     * stamps and tacks; the chalk stick.
     */
    val layers = Array(4) { BoardQuads() }

    val icons = Array(MarketBoards.ORDER.size) { ItemStackRenderState() }
    val iconOn = BooleanArray(MarketBoards.ORDER.size)
    val iconU = FloatArray(MarketBoards.ORDER.size)
    val iconV = FloatArray(MarketBoards.ORDER.size)
    val iconSize = FloatArray(MarketBoards.ORDER.size)
    val iconLight = IntArray(MarketBoards.ORDER.size)

    /** The goods' icons on the tickets, a slot of the rail each: where in the board's frame, and turned as the ticket hangs. */
    val ticketIcons = Array(MarketBoardBlockEntity.TICKETS) { ItemStackRenderState() }
    val ticketIconOn = BooleanArray(MarketBoardBlockEntity.TICKETS)
    val ticketIconX = FloatArray(MarketBoardBlockEntity.TICKETS)
    val ticketIconY = FloatArray(MarketBoardBlockEntity.TICKETS)
    val ticketIconZ = FloatArray(MarketBoardBlockEntity.TICKETS)
    val ticketIconTurn = FloatArray(MarketBoardBlockEntity.TICKETS)
    val ticketIconSize = FloatArray(MarketBoardBlockEntity.TICKETS)
    val ticketIconLight = IntArray(MarketBoardBlockEntity.TICKETS)

    var town: FormattedCharSequence? = null
    var townShadow: FormattedCharSequence? = null
    var townU = 0f
    var townScale = 0f
    var townLight = 0
    var townAlpha = 1f

    fun clear() {
        any = false
        for (q in layers) q.clear()
        iconOn.fill(false)
        ticketIconOn.fill(false)
        town = null
        townShadow = null
    }
}

/**
 * A market board's face, written live: the town's name gilt on the header plank, when the prices are from chalked at
 * the head of the slate, and below it every good the market trades, each as its own icon with its lot in chalk ("6 for
 * 1" and the Guild Mark's coin), an arrow for which way its price is going, pale red where it's dear and pale green
 * where it's cheap, "none" struck through where there's none to be had. The news hangs beside it on slips of paper,
 * each kind on its own paper (a red seal on trouble, a green ribbon on good news), headlines inked in a quick hand.
 * The capital's orders hang under it all on manila tickets along the rail: "Wanted", the good, how many and for which
 * business, stamped "Taken" once someone has taken the order; each swings a little on its tack in the air.
 *
 * It's alive while the crier is at it: an old price is rubbed out with the rag, the new one comes up stroke by stroke
 * from left to right with the stick of chalk at work and a little dust falling, bright while it's fresh; a slip flies
 * up from the crier's hand, is tacked on and swings to rest (a ticket too), and one taken down falls away; a ticket
 * whose order is taken gets its stamp with a thump. Now and then a breeze lifts a slip's corner, never two at once,
 * or a ticket out from the rail. Lines written while nobody was by show whole. The writing is lit by the light at the
 * board (dark at night unless a lamp is near, like a sign's) and fades out past [MarketBoardArt.FADE_NEAR] blocks.
 * Over the ridge the medallion shows the town's times on a carved drum, and turns when they move (see [medallion]).
 *
 * The lettering is the board's own (see art/scripts/market_board_letters.py): every glyph of the chalk hand is cut into
 * pieces, each swept one way, so a line can be revealed exactly as the chalk's tip goes; everything is laid out by the
 * numbers in [MarketBoardArt], which the art script also lays its previews out by.
 */
class MarketBoardRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<MarketBoardBlockEntity, MarketBoardRenderState> {

    private val itemModels = context.itemModelResolver()
    private val sprites = context.sprites()
    private val font = context.font()

    override fun createRenderState(): MarketBoardRenderState = MarketBoardRenderState()

    // The whole 3x2 board, wherever the anchor cell is
    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = 48

    override fun getRenderBoundingBox(board: MarketBoardBlockEntity): AABB {
        val p = board.blockPos
        // The medallion stands over the board's top
        return AABB(p.x - 1.0, p.y.toDouble(), p.z - 1.0, p.x + 2.0, p.y + 2.75, p.z + 2.0)
    }

    // ---------------------------------------------------------------- extraction

    override fun extractRenderState(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, partialTicks: Float, cameraPosition: Vec3,
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
        val frame = if (mem.lastFrame.isNaN()) 0f else (now - mem.lastFrame).toFloat().coerceIn(0f, 4f)
        mem.lastFrame = now
        cellLights(level, pos, facing)
        val letters = sprites.get(LETTERS_ID)
        val paper = sprites.get(SLIPS_ID)
        words.refresh()
        if (mem.words != words.version) {
            // A new language: everything's written again in it, whole
            mem.words = words.version
            mem.fresh = true
            mem.lines.fill(null)
            mem.title = ""
        }

        // The slips and tickets hang there for anyone to see, the writing only near enough to read
        slips(board, state, mem, level, now, fade, paper, letters)
        tickets(board, state, mem, level, now, fade, paper, letters)
        if (fade > 0.002f) town(board, state, mem, now, fade)
        // Kept up to date even out of reading range, so nothing's rewritten before your eyes that was written before you came
        slate(board, state, mem, level, pos, now, frame, fade, letters)
        medallion(board, state, mem, level, pos, now, cameraPosition)
        mem.fresh = false
        state.any = true
    }

    // ---------------------------------------------------------------- the slate

    private fun slate(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, level: Level, pos: BlockPos,
        now: Double, frame: Float, fade: Float, letters: TextureAtlasSprite,
    ) {
        val chalk = state.layers[0]
        val z = -A.SLATE_DEPTH + 0.0025f
        flat.z = z
        val left = A.SLATE_U0 + A.PAD_L
        val top = A.PANEL_V0

        // The title: when these prices are from; rewritten when that changes ("this morning" becomes "at noon")
        val today = Conditions(Math.floorDiv(level.overworldClockTime, 1000L)).day
        val title = when {
            board.day < 0 -> ""
            board.day == today -> words.text(if (board.noon) "board.guildmark.market.noon" else "board.guildmark.market.morning")
            board.day == today - 1 -> words.text(if (board.noon) "board.guildmark.market.yesterday_noon" else "board.guildmark.market.yesterday_morning")
            else -> words.text("board.guildmark.market.old")
        }.replaceFirstChar { it.titlecase(Locale.ROOT) }
        if (title != mem.title) {
            // Rewritten before your eyes only while the crier is at it; a day turning over, or a board found
            // written up, just reads differently the next time you look
            val seen = !mem.fresh && board.begun != MarketBoardBlockEntity.LONG_AGO && level.gameTime - board.begun < TITLE_FRESH
            if (seen && mem.title.isNotEmpty()) {
                mem.oldTitle = mem.titleRow
                mem.oldTitleAt = now
            }
            mem.title = title
            mem.titleAt = if (seen) now else Double.NEGATIVE_INFINITY
            mem.titleRow = if (title.isEmpty()) null else Row().also {
                val base = top + A.TITLE_BASE
                it.run(title, left, base, A.S_TITLE, tint(A.CHALK))
                val w = width(CHALK, title) * A.S_TITLE
                it.bar(SWASH, left - 0.004f, left + w + 0.01f, base + 2.2f * A.S_TITLE, A.S_TITLE * 0.85f, tint(A.CHALK, 0.55f))
            }
        }
        var tip: Tip? = null
        mem.oldTitle?.let { old ->
            val a = (now - mem.oldTitleAt).toFloat()
            if (a > A.RUB + A.SMEAR) mem.oldTitle = null else rub(chalk, old, a, z, fade, letters)
        }
        mem.titleRow?.let { row ->
            val start = mem.titleAt + if (mem.oldTitle != null) A.RUB * 0.6 else 0.0
            tip = draw(chalk, row, (now - start).toFloat(), fade, letters) ?: tip
        }

        // The key to the coin, small, at the foot of the first column (on the last row's line)
        val legend = A.COIN + " " + words.text("item.guildmark.guild_mark")
        whole(chalk, CHALK, legend, left, top + A.LEGEND_BASE, A.S_LEGEND, 1f, tint(A.CHALK, 0.62f * fade), letters)

        // The prices, section by section down three columns
        val colTop = FloatArray(3) { top + A.ROWS_TOP }
        var index = 0
        for ((s, key) in SECTION_KEYS.withIndex()) {
            val column = A.SECTION_COLUMNS[s]
            val cx = left + column * A.COL_W
            var y = colTop[column]
            val heading = words.text(key).trimEnd(':', ' ')
            val hb = y + A.HEAD_BASE
            // A long heading is chalked smaller, to keep out of the next column
            val hs = min(A.S_HEAD, (A.COL_W - 0.05f) / max(1f, width(CHALK, heading)))
            whole(chalk, CHALK, heading, cx, hb, hs, 1f, tint(A.CHALK_HEAD, 0.95f * fade), letters)
            val hw = width(CHALK, heading) * hs
            bar(chalk, UNDERLINE, cx - 0.003f, cx + hw + 0.006f, hb + 2.1f * A.S_HEAD, A.S_HEAD * 0.7f, tint(A.CHALK_HEAD, 0.5f * fade), letters, 1f)
            y += A.HEAD_PITCH
            repeat(A.SECTION_SIZES[s]) {
                val mid = y + A.ITEM_PITCH * 0.5f
                tip = row(board, state, mem, index, cx, mid, now, fade, letters, chalk) ?: tip
                index++
                y += A.ITEM_PITCH
            }
            colTop[column] = y + A.SECTION_GAP
        }

        stick(board, state, mem, level, pos, tip, now, frame, fade, letters)
    }

    /** One good's row: its icon, and its line as the board has it (rubbing the old one out, chalking the new one in). */
    private fun row(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, i: Int, cx: Float, mid: Float,
        now: Double, fade: Float, letters: TextureAtlasSprite, chalk: BoardQuads,
    ): Tip? {
        val good = MarketBoards.ORDER[i]
        val stack = STACKS[i]
        if (!stack.isEmpty && fade > 0.05f) {
            val level = board.level
            itemModels.updateForTopItem(state.icons[i], stack, ItemDisplayContext.FIXED, level, null, i)
            state.iconOn[i] = true
            state.iconU[i] = cx + A.ICON_X * A.S_PRICE
            state.iconV[i] = mid
            state.iconSize[i] = A.ICON_SIZE * A.S_PRICE * (if (stack.item is BlockItem) 1.6f else 1f)
            state.iconLight[i] = lightAt(state.iconU[i], mid)
        }
        // A line padded in ahead of the crier's chalk is for another good: nothing's written there yet
        val line = board.lines.getOrNull(i)?.takeIf { it.good == good }
        if (!identical(line, mem.lines[i])) {
            val before = mem.rows[i]
            if (!mem.fresh && before != null && line != null && line.chalked != MarketBoardBlockEntity.LONG_AGO) {
                mem.old[i] = before
                mem.oldAt[i] = line.chalked.toDouble()
            } else {
                mem.old[i] = null
            }
            mem.lines[i] = line
            mem.rows[i] = line?.let { layout(it, cx, mid) }
        }
        val z = flat.z
        mem.old[i]?.let { old ->
            val a = (now - mem.oldAt[i]).toFloat()
            if (a > A.RUB + A.SMEAR) mem.old[i] = null else rub(chalk, old, a, z, fade, letters)
        }
        val row = mem.rows[i] ?: return null
        if (line == null || line.chalked == MarketBoardBlockEntity.LONG_AGO) {
            draw(chalk, row, Float.POSITIVE_INFINITY, fade, letters)
            return null
        }
        val start = line.chalked + if (mem.old[i] != null) A.RUB * 0.6 else 0.0
        return draw(chalk, row, (now - start).toFloat(), fade, letters)
    }

    /** Lays out [line]'s lot in its row: count, the small "for", the Marks and the coin, the arrow; or "none", struck. */
    private fun layout(line: MarketBoardBlockEntity.Line, cx: Float, mid: Float): Row {
        val row = Row()
        val s = A.S_PRICE
        val base = mid + A.TEXT_DROP * s
        val colour = when (line.off) {
            1 -> A.CHALK_DEAR
            -1 -> A.CHALK_CHEAP
            else -> A.CHALK
        }
        val argb = tint(colour)
        if (line.count <= 0) {
            val none = words.none
            val x = cx + A.NONE_X * s
            row.run(none, x, base, s, tint(colour, 0.8f))
            val w = width(CHALK, none) * s
            row.bar(STRIKE, x - 0.9f * s, x + w + 1.2f * s, base - 2.6f * s, s * 0.85f, argb)
            return row
        }
        // In font px at S_PRICE from the column's left: the count ends at COUNT_RIGHT (a long one pushes the rest
        // along, clear of the icon), then the small "for", the Marks, the coin and the arrow; all squeezed together
        // if they'd run into the next column
        val count = line.count.toString()
        val marks = line.marks.toString()
        val middle = words.lotMiddle
        val first = A.ICON_X + A.ICON_SIZE * 0.5f + 1f
        val start: Float
        var forX = 0f
        var marksX = 0f
        val coinX: Float
        if (middle == null) {
            // A language whose lot isn't "count words price": its own words, then the coin
            start = first + 0.2f
            coinX = start + width(CHALK, words.lot(line.count, line.marks)) + A.COIN_GAP
        } else {
            val natural = A.COUNT_RIGHT - width(CHALK, count)
            start = max(first, natural)
            forX = A.FOR_X + start - natural
            marksX = forX + width(CHALK, middle) * A.S_FOR / s + A.FOR_GAP
            coinX = marksX + width(CHALK, marks) + A.COIN_GAP
        }
        val arrow = when (line.trend) {
            1 -> A.UP.toString()
            -1 -> A.DOWN.toString()
            else -> null
        }
        val arrowX = coinX + width(CHALK, A.COIN.toString()) + A.ARROW_GAP
        val end = if (arrow == null) arrowX - A.ARROW_GAP else arrowX + width(CHALK, arrow)
        val room = A.COL_W / s - 1.5f
        val k = if (end > room) (room - start) / (end - start) else 1f
        fun at(x: Float) = cx + (start + (x - start) * k) * s
        if (middle == null) {
            row.run(words.lot(line.count, line.marks), at(start), base, s, argb, k)
        } else {
            row.run(count, at(start), base, s, argb, k)
            row.run(middle, at(forX), base, A.S_FOR, tint(colour, 0.82f), k)
            row.run(marks, at(marksX), base, s, argb, k)
        }
        row.run(A.COIN.toString(), at(coinX), base, s, argb, k)
        if (arrow != null) row.run(arrow, at(arrowX), base, s, argb, k)
        return row
    }

    // ---------------------------------------------------------------- chalk: whole, rubbed out, coming up

    /** Draws [row] as it stands [age] ticks after its chalking started: whole, or up to the chalk's tip (returned). */
    private fun draw(q: BoardQuads, row: Row, age: Float, fade: Float, letters: TextureAtlasSprite): Tip? {
        val plan = row.plan
        val duration = plan.total.coerceIn(A.DRAW_MIN, A.DRAW_MAX)
        // Fresh chalk is brighter, settling over a few seconds
        val bright = if (age.isInfinite()) 0f else (1f - (age - duration) / A.FRESH).coerceIn(0f, 1f)
        if (age >= duration) {
            for (r in row.runs) whole(q, CHALK, r.text, r.x, r.base, r.scale, r.squeeze, fresh(r.argb, bright, fade), letters)
            for (b in row.bars) bar(q, b.cell, b.x0, b.x1, b.y, b.scale, fresh(b.argb, bright, fade), letters, 1f)
            return null
        }
        if (age <= 0f) return null
        val t = age * plan.total / duration
        var tip: Tip? = null
        for (k in plan.strokes.indices) {
            val st = plan.strokes[k]
            val argb = fresh(st.argb, 1f, fade)
            if (t >= plan.ends[k]) {
                stroke(q, st, 1f, argb, letters)
                continue
            }
            if (t >= plan.starts[k]) {
                val f = (t - plan.starts[k]) / max(1e-4f, plan.ends[k] - plan.starts[k])
                stroke(q, st, f, argb, letters)
                tip = st.tipAt(f, down = true)
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

    /** The old line under the rag: fading as it's wiped, leaving a pale smear that lingers a while. */
    private fun rub(q: BoardQuads, row: Row, age: Float, z: Float, fade: Float, letters: TextureAtlasSprite) {
        val gone = (age / A.RUB).coerceIn(0f, 1f)
        if (gone < 1f) {
            for (r in row.runs) whole(q, CHALK, r.text, r.x, r.base, r.scale, r.squeeze, scaleAlpha(r.argb, (1f - gone) * fade), letters)
            for (b in row.bars) bar(q, b.cell, b.x0, b.x1, b.y, b.scale, scaleAlpha(b.argb, (1f - gone) * fade), letters, 1f)
        }
        val smear = min(1f, age / A.RUB) * (1f - ((age - A.RUB) / A.SMEAR).coerceIn(0f, 1f))
        if (smear > 0.01f && row.runs.isNotEmpty()) {
            val x0 = row.runs.minOf { it.x } - 0.01f
            val x1 = row.runs.maxOf { it.x + width(CHALK, it.text) * it.scale * it.squeeze } + 0.012f
            val base = row.runs[0].base
            val s = row.runs[0].scale
            flat.z = z - 0.0008f
            sprite(q, flat, letters, SMUDGE, x0, base - 7.6f * s, x1, base + 1.6f * s, tint(A.CHALK, 0.42f * smear * fade), lightAt((x0 + x1) / 2, base))
            flat.z = z
        }
    }

    /** One piece of a glyph (or a bar) drawn as far as [f] of the way along its stroke. */
    private fun stroke(q: BoardQuads, st: Stroke, f: Float, argb: Int, letters: TextureAtlasSprite) {
        val cell = st.cell ?: return
        if (f >= 1f) {
            cell(q, letters, cell, st.x, st.base, st.sx, st.sy, st.lift, argb, -1, 0, 0f)
            return
        }
        // Reveal up to just past the tip along the way the piece is swept
        val p = st.pointAt(f)
        val at = if (st.axis == 0) p[0] + st.dir * TIP else p[1] + st.dir * TIP
        cell(q, letters, cell, st.x, st.base, st.sx, st.sy, st.lift, argb, st.axis, st.dir, at)
    }

    /** [text] in [hand], whole, from [x] along the baseline [base]. */
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

    /** A chalk bar (an underline, the stroke through "none") from [x0] to [x1] along [y]. */
    private fun bar(q: BoardQuads, c: Cell, x0: Float, x1: Float, y: Float, s: Float, argb: Int, letters: TextureAtlasSprite, f: Float) {
        if (alpha(argb) < 0.004f || f <= 0f || x1 <= x0) return
        val xe = x0 + (x1 - x0) * f
        sprite(q, flat, letters, c, x0, y + c.top * s, xe, y + (c.top + c.height) * s, argb, lightAt((x0 + xe) / 2, y), uFraction = f)
    }

    /** One atlas cell placed with its glyph origin at ([x], [base]), clipped along [axis] at [at] (font px) if given. */
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
            sprite.getU(tx0 / A.LETTERS_W), sprite.getV(ty0 / A.LETTERS_H), sprite.getU(tx1 / A.LETTERS_W), sprite.getV(ty1 / A.LETTERS_H),
            argb, lightAt((u0 + u1) * 0.5f, (v0 + v1) * 0.5f),
        )
    }

    /** A whole atlas cell stretched over the face rectangle [u0]..[u1], [v0]..[v1] (the first [uFraction] of it across). */
    private fun sprite(
        q: BoardQuads, surface: Surface, sprite: TextureAtlasSprite, c: Cell, u0: Float, v0: Float, u1: Float, v1: Float,
        argb: Int, light: Int, uFraction: Float = 1f,
    ) {
        quad(
            q, surface, u0, v0, u1, v1,
            sprite.getU(c.x.toFloat() / A.LETTERS_W), sprite.getV(c.y.toFloat() / A.LETTERS_H),
            sprite.getU((c.x + c.w * uFraction) / A.LETTERS_W), sprite.getV((c.y + c.h).toFloat() / A.LETTERS_H),
            argb, light,
        )
    }

    // ---------------------------------------------------------------- the chalk stick and its dust

    private fun stick(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, level: Level, pos: BlockPos, tip: Tip?,
        now: Double, frame: Float, fade: Float, letters: TextureAtlasSprite,
    ) {
        // Where the chalk is: at the tip while a line's coming up, resting after the line the crier is on, else put down
        var target: Tip? = tip
        if (target == null && board.writing >= 0) {
            mem.rows.getOrNull(board.writing)?.let { row ->
                val last = row.runs.lastOrNull()
                if (last != null) {
                    val bob = sin((now % 1000.0 * 0.21).toFloat()) * 0.004f
                    target = Tip(last.x + width(CHALK, last.text) * last.scale * last.squeeze + 0.025f, last.base - 0.02f + bob, false)
                }
            }
        }
        val show = target != null
        val k = 1f - exp(-frame * 0.55f)
        if (show) {
            val t = target!!
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
        val len = STICK.height * A.S_STICK
        val half = STICK.width * A.S_STICK * 0.42f
        val du = 0.34f * len
        val dv = -0.86f * len
        val dz = 0.38f * len
        val wu = 0.93f * half
        val wv = 0.37f * half
        val argb = scaleAlpha(0xFFFFFFFF.toInt(), mem.stickAlpha * fade)
        val light = lightAt(u, v)
        val q = state.layers[3]
        val su0 = letters.getU(STICK.x.toFloat() / A.LETTERS_W)
        val su1 = letters.getU((STICK.x + STICK.w).toFloat() / A.LETTERS_W)
        val sv0 = letters.getV(STICK.y.toFloat() / A.LETTERS_H)
        val sv1 = letters.getV((STICK.y + STICK.h).toFloat() / A.LETTERS_H)
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

    // ---------------------------------------------------------------- the town's name

    private fun town(board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, now: Double, fade: Float) {
        val name = board.town.string.uppercase(Locale.ROOT)
        if (name.isBlank()) return
        if (name != mem.townName) {
            mem.townName = name
            val style = Style.EMPTY.withFont(HEADING)
            mem.townWidth = font.width(FormattedCharSequence.forward(name, style)).toFloat()
            mem.townCentres = FloatArray(name.length).also {
                var x = 0f
                for (i in name.indices) {
                    val w = font.width(FormattedCharSequence.forward(name[i].toString(), style)).toFloat()
                    it[i] = x + w / 2
                    x += w
                }
            }
        }
        val width = mem.townWidth
        val scale = min(A.S_TOWN, A.TOWN_MAX_W / max(1f, width))
        // Now and then the light runs along the gilt
        val cycle = ((now + (board.blockPos.asLong() and 255L)) % GLINT_EVERY).toFloat()
        val sweep = -10f + (width + 20f) * (cycle / GLINT_TAKES)
        val styles = Array(name.length) { i ->
            val g = if (cycle < GLINT_TAKES) max(0f, 1f - abs(mem.townCentres[i] - sweep) / 7f) else 0f
            Style.EMPTY.withFont(HEADING).withColor(mixRgb(A.GILT, A.GILT_GLINT, g * 0.85f))
        }
        state.town = FormattedCharSequence { sink ->
            var ok = true
            for (i in name.indices) {
                if (!sink.accept(i, styles[i], name[i].code)) {
                    ok = false
                    break
                }
            }
            ok
        }
        state.townShadow = FormattedCharSequence.forward(name, Style.EMPTY.withFont(HEADING))
        state.townScale = scale
        state.townU = A.TOWN_U - width * scale / 2
        state.townLight = lightAt(A.TOWN_U, (A.HEADER_V0 + A.HEADER_V1) / 2)
        state.townAlpha = fade
    }

    // ---------------------------------------------------------------- slips

    private fun slips(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, level: Level, now: Double, fade: Float,
        paper: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        // Each slip keeps the slot it was pinned in; one taken down falls away from its own
        val keys = board.slips.map { key(it) }
        for (s in 0 until SLOTS) {
            val seen = mem.slots[s] ?: continue
            if (seen.key !in keys) {
                if (!mem.fresh) mem.gone.add(Gone(seen.slip, s, now))
                mem.slots[s] = null
            }
        }
        for ((i, slip) in board.slips.withIndex()) {
            val k = keys[i]
            val at = mem.slots.indexOfFirst { it?.key == k }
            if (at >= 0) {
                mem.slots[at]!!.slip = slip
                continue
            }
            val free = mem.slots.indexOfFirst { it == null }
            if (free >= 0) mem.slots[free] = Seen(slip, k)
        }
        mem.gone.removeAll { now - it.at > A.FALL }

        val wind = 1f + level.getRainLevel(0f) * 0.7f + level.getThunderLevel(0f) * 0.8f
        for (s in 0 until SLOTS) {
            val seen = mem.slots[s] ?: continue
            val age = if (seen.slip.pinned == MarketBoardBlockEntity.LONG_AGO) Float.POSITIVE_INFINITY else (now - seen.slip.pinned).toFloat()
            slip(state, seen.slip, seen.key, s, age, -1f, now, wind, fade, paper, letters)
        }
        for (g in mem.gone) slip(state, g.slip, key(g.slip), g.slot, Float.POSITIVE_INFINITY, (now - g.at).toFloat(), now, wind, fade, paper, letters)
    }

    /** One slip in [slot]: flying up and settling ([age] since pinned), stirring in the [wind], or falling ([falling] since taken down). */
    private fun slip(
        state: MarketBoardRenderState, slip: MarketBoardBlockEntity.Slip, key: String, slot: Int, age: Float, falling: Float,
        now: Double, wind: Float, fade: Float, paper: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        val h = key.hashCode().toLong() * -7046029254386353131L
        fun rnd(bits: Int): Float = ((h ushr bits) and 1023L).toFloat() / 1023f
        val kind = A.PAPER_KINDS.indexOf(slip.kind).let { if (it < 0) 3 else it }
        val p = page
        p.w = A.SLIP_W
        p.h = A.SLIP_H
        p.pin = A.PIN_DOWN
        p.tilt = 0f
        p.slotU = A.NEWS_U0 + (A.NEWS_U1 - A.NEWS_U0 - A.SLIP_W) / 2 + (rnd(3) - 0.5f) * 0.012f
        p.slotV = A.PANEL_V0 + A.SLOT_TOP + slot * A.SLOT_PITCH + (rnd(13) - 0.5f) * 0.008f
        p.z = -A.SLATE_DEPTH + 0.006f + slot * 0.0015f
        p.turn = (rnd(23) - 0.5f) * 0.09f
        p.du = 0f
        p.dv = 0f
        p.dz = 0f
        p.lift = 0f
        p.ripple = 0f
        p.corner = if (rnd(33) < 0.5f) -1 else 1
        p.time = (now % 100000.0 + rnd(43) * 4000.0).toFloat()
        var alpha = 1f
        var tack = 1f
        var pressed = 1f
        if (falling >= 0f) {
            // Taken down: the tack out, the paper dropping away and gone
            val g = falling
            p.dv = g * g * 0.0011f + g * 0.004f
            p.dz = 0.03f + g * 0.006f
            p.turn += g * 0.035f * p.corner
            p.lift = 0.6f
            alpha = (1f - g / A.FALL).coerceIn(0f, 1f)
            tack = 0f
        } else if (age < A.LAND) {
            // In flight from the crier's hand, flapping, the tack not in yet
            val e = 1f - (1f - (age / A.LAND).coerceIn(0f, 1f)).pow(3)
            p.du = (1f - e) * 0.16f
            p.dv = (1f - e) * 0.42f
            p.dz = (1f - e) * 0.3f + sin(e * PI.toFloat()) * 0.04f
            p.turn += (1f - e) * -0.55f
            p.ripple = (1f - e) * 1.4f
            p.lift = (1f - e) * 0.5f * (0.5f + 0.5f * sin(age * 1.7f))
            alpha = min(1f, age / 2.5f)
            tack = 0f
        } else if (age < A.FLUTTER) {
            // Tacked on: a swing on the tack, dying away
            val b = age - A.LAND
            p.turn += 0.11f * exp(-b / 6f) * sin(b * 0.85f)
            p.lift = 0.45f * exp(-b / 5f) * (0.5f + 0.5f * cos(b * 0.9f))
            pressed = 1f + 0.5f * exp(-b / 2.5f)
        }
        if (falling < 0f && age >= A.LAND) {
            // The breeze: now and then a corner lifts and settles, each slip in its own time
            val t = p.time
            val gust = sin(t * 0.019f) * 0.6f + sin(t * 0.047f + 1.7f) * 0.4f
            val lifted = ((gust - 0.55f) / 0.45f).coerceIn(0f, 1f) * min(1.6f, wind)
            p.lift = max(p.lift, lifted)
            p.ripple = max(p.ripple, lifted * 0.5f)
        }
        if (alpha <= 0.01f) return

        val light = lightAt(p.slotU + A.SLIP_W / 2, p.slotV + A.SLIP_H / 2)
        // Its shadow on the planks, further off the more it's lifted
        val away = min(1f, p.dz / 0.3f)
        val sh = 0.014f + 0.03f * p.lift * LIFT * 20f + 0.12f * away
        shade.z = p.z - 0.003f
        val shadowAlpha = 0.5f * alpha * (1f - away * 0.8f)
        quad(
            state.layers[0], shade,
            p.slotU + p.du * 0.5f + sh * 0.6f - 0.01f, p.slotV + p.dv * 0.5f + sh - 0.008f,
            p.slotU + p.du * 0.5f + sh * 0.6f + A.SLIP_W + 0.01f, p.slotV + p.dv * 0.5f + sh + A.SLIP_H + 0.012f,
            paper.getU(A.SLIP_SHADOW[0].toFloat() / A.SLIPS_W), paper.getV(A.SLIP_SHADOW[1].toFloat() / A.SLIPS_H),
            paper.getU((A.SLIP_SHADOW[0] + A.SLIP_SHADOW[2]).toFloat() / A.SLIPS_W), paper.getV((A.SLIP_SHADOW[1] + A.SLIP_SHADOW[3]).toFloat() / A.SLIPS_H),
            scaleAlpha(0xFFFFFFFF.toInt(), shadowAlpha), light,
        )

        // The paper, as a little mesh so it can bend
        val box = A.PAPERS[kind]
        val q = state.layers[1]
        for (j in 0 until MESH_Y) for (i in 0 until MESH_X) {
            val x0 = A.SLIP_W * i / MESH_X
            val x1 = A.SLIP_W * (i + 1) / MESH_X
            val y0 = A.SLIP_H * j / MESH_Y
            val y1 = A.SLIP_H * (j + 1) / MESH_Y
            // Where it's lifted toward the eye it turns from the light a little
            val slope = (p.liftAt(x0, y1) - p.liftAt(x0, y0)) / (y1 - y0)
            val tone = (1f - slope * 2.2f).coerceIn(0.72f, 1f)
            val c = (tone * 255).toInt()
            quad(
                q, p, x0, y0, x1, y1,
                paper.getU((box[0] + box[2] * x0 / A.SLIP_W) / A.SLIPS_W), paper.getV((box[1] + box[3] * y0 / A.SLIP_H) / A.SLIPS_H),
                paper.getU((box[0] + box[2] * x1 / A.SLIP_W) / A.SLIPS_W), paper.getV((box[1] + box[3] * y1 / A.SLIP_H) / A.SLIPS_H),
                ((alpha * 255).toInt() shl 24) or (c shl 16) or (c shl 8) or c, light,
            )
        }

        // The writing, near enough to read, inked on and bending with the paper
        val ink = state.layers[2]
        if (fade > 0.002f) {
            p.inkLift = 0.0012f
            inkOn(ink, p, slip, kind, scaleAlpha(0xFF000000.toInt() or (if (kind == 0) A.INK_TROUBLE else A.INK), 0.93f * alpha * fade),
                scaleAlpha(0xFF000000.toInt() or A.INK, 0.9f * alpha * fade), light, letters)
            p.inkLift = 0f
        }

        // The tack
        if (tack > 0f) tackOn(ink, p, A.SLIP_W / 2, A.PIN_DOWN, A.TACK_SIZE * pressed, alpha, light, paper)
    }

    /** A tack through page [p] at ([u], [v]), [size] across, with its shadow on the paper. */
    private fun tackOn(q: BoardQuads, p: Page, u: Float, v: Float, size: Float, alpha: Float, light: Int, paper: TextureAtlasSprite) {
        p.inkLift = 0.0035f
        val shadow = A.TACK_SHADOW
        quad(
            q, p, u - size * 0.35f, v - size * 0.3f, u + size * 0.85f, v + size * 0.8f,
            paper.getU(shadow[0].toFloat() / A.SLIPS_W), paper.getV(shadow[1].toFloat() / A.SLIPS_H),
            paper.getU((shadow[0] + shadow[2]).toFloat() / A.SLIPS_W), paper.getV((shadow[1] + shadow[3]).toFloat() / A.SLIPS_H),
            scaleAlpha(0xFFFFFFFF.toInt(), alpha), light,
        )
        p.inkLift = 0.005f
        val t = A.TACK
        quad(
            q, p, u - size / 2, v - size / 2, u + size / 2, v + size / 2,
            paper.getU(t[0].toFloat() / A.SLIPS_W), paper.getV(t[1].toFloat() / A.SLIPS_H),
            paper.getU((t[0] + t[2]).toFloat() / A.SLIPS_W), paper.getV((t[1] + t[3]).toFloat() / A.SLIPS_H),
            scaleAlpha(0xFFFFFFFF.toInt(), alpha), light,
        )
        p.inkLift = 0f
    }

    /** A slip's headline and words, kept off its seal, ribbon or stamp. */
    private fun inkOn(q: BoardQuads, p: Page, slip: MarketBoardBlockEntity.Slip, kind: Int, headArgb: Int, textArgb: Int, light: Int, letters: TextureAtlasSprite) {
        val keep = A.PAPER_KEEP[kind]
        val kx0 = keep[0] / 64f * A.SLIP_W
        val ky0 = keep[1] / 32f * A.SLIP_H
        val kx1 = keep[2] / 64f * A.SLIP_W
        val ky1 = keep[3] / 32f * A.SLIP_H
        val keepRight = kx0 > A.SLIP_W / 2
        fun room(top: Float, bottom: Float): FloatArray {
            var l = A.SLIP_MARGIN
            var r = A.SLIP_W - A.SLIP_MARGIN
            if (bottom > ky0 && top < ky1) {
                if (keepRight) r = min(r, kx0 - 0.008f) else l = max(l, kx1 + 0.008f)
            }
            return floatArrayOf(l, r)
        }

        // The headline: on one line if it'll go, smaller or on two if not, centred
        val head = slip.headline.string
        var hs = A.S_INK
        var lines = listOf(head)
        val top = A.SLIP_HEAD_TOP
        var r = room(top, top + 9f * hs)
        if (width(INK, head) * hs > r[1] - r[0]) {
            val smaller = (r[1] - r[0]) / (width(INK, head) * hs)
            if (smaller >= 0.8f) {
                hs *= smaller
            } else {
                lines = wrap(INK, head, (r[1] - r[0]) / hs, 2)
                val widest = lines.maxOf { width(INK, it) } * hs
                if (widest > r[1] - r[0]) hs *= (r[1] - r[0]) / widest
            }
        }
        var base = top + 7f * hs
        for (line in lines) {
            r = room(base - 7f * hs, base + 2.5f * hs)
            val w = width(INK, line) * hs
            ink(q, p, line, (r[0] + r[1]) / 2 - w / 2, base, hs, headArgb, light, letters)
            base += A.HEAD_LINE * hs
        }
        base -= A.HEAD_LINE * hs

        // The words under it, as many lines as the slip holds
        val ts = A.S_INK_TEXT
        var y = base + 3.4f * hs + 7f * ts
        var rest = slip.text.string.trim()
        while (rest.isNotEmpty() && y + 2.4f * ts <= A.SLIP_H - 0.02f) {
            r = room(y - 7f * ts, y + 2.4f * ts)
            val space = (r[1] - r[0]) / ts
            val last = y + A.TEXT_LINE * ts + 2.4f * ts > A.SLIP_H - 0.02f
            val taken = wrap(INK, rest, space, 1).firstOrNull() ?: break
            rest = rest.substring(min(rest.length, taken.length)).trimStart()
            var shown = taken
            if (last && rest.isNotEmpty()) {
                while (shown.isNotEmpty() && width(INK, "$shown…") > space) shown = shown.dropLast(1)
                shown = shown.trimEnd() + "…"
            }
            ink(q, p, shown, r[0], y, ts, textArgb, light, letters)
            y += A.TEXT_LINE * ts
        }
    }

    /** [text] inked on [p] (a page, or a stamp on one) from [x] along [base], each glyph bending with the paper. */
    private fun ink(q: BoardQuads, p: Surface, text: String, x: Float, base: Float, s: Float, argb: Int, light: Int, sprite: TextureAtlasSprite) {
        var pen = x
        for (c in text) {
            val g = INK.glyph(c)
            if (g == null) {
                pen += 3f * s
                continue
            }
            g.cell?.let { cell ->
                quad(
                    q, p, pen + cell.left * s, base + (cell.top - CAP) * s, pen + (cell.left + cell.width) * s, base + (cell.top + cell.height - CAP) * s,
                    sprite.getU(cell.x.toFloat() / A.LETTERS_W), sprite.getV(cell.y.toFloat() / A.LETTERS_H),
                    sprite.getU((cell.x + cell.w).toFloat() / A.LETTERS_W), sprite.getV((cell.y + cell.h).toFloat() / A.LETTERS_H),
                    argb, light,
                )
            }
            pen += g.advance * s
        }
    }

    // ---------------------------------------------------------------- the orders' tickets

    private fun tickets(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, level: Level, now: Double, fade: Float,
        paper: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        // Each hangs in its own slot: one whose order is gone falls from it, one whose order is taken gets its stamp
        for (s in 0 until TICKET_SLOTS) {
            val hung = board.tickets.firstOrNull { it.slot == s }
            val seen = mem.tickets[s]
            if (seen != null && seen.order != hung?.order) {
                if (!mem.fresh) mem.fallen.add(Fallen(seen, now))
                mem.tickets[s] = null
            }
            if (hung != null) {
                val before = mem.tickets[s]
                if (hung.taken && before?.taken != true) mem.stamped[s] = if (mem.fresh || before == null) Double.NEGATIVE_INFINITY else now
                mem.tickets[s] = hung
            }
        }
        mem.fallen.removeAll { now - it.at > A.FALL }

        val wind = 1f + level.getRainLevel(0f) * 0.7f + level.getThunderLevel(0f) * 0.8f
        for (s in 0 until TICKET_SLOTS) {
            val hung = mem.tickets[s] ?: continue
            val age = if (hung.pinned == MarketBoardBlockEntity.LONG_AGO) Float.POSITIVE_INFINITY else (now - hung.pinned).toFloat()
            ticket(state, hung, age, -1f, (now - mem.stamped[s]).toFloat(), s, level, now, wind, fade, paper, letters)
        }
        for (f in mem.fallen) {
            // Its icon goes down with it, unless a new ticket has its slot already
            val icon = if (mem.tickets[f.ticket.slot] == null) f.ticket.slot else -1
            ticket(state, f.ticket, Float.POSITIVE_INFINITY, (now - f.at).toFloat(), Float.POSITIVE_INFINITY, icon, level, now, wind, fade, paper, letters)
        }
    }

    /**
     * One order's ticket on the rail: flying up from the crier's hand and swinging on its tack ([age] since it was
     * hung), swaying in the [wind] and now and then lifted out by it, or falling ([falling] since it came down); its
     * stamp put on [stamped] ticks ago if its order's taken. Its good's icon goes in slot [icon] of the icons (-1: none).
     */
    private fun ticket(
        state: MarketBoardRenderState, ticket: MarketBoardBlockEntity.Ticket, age: Float, falling: Float, stamped: Float, icon: Int,
        level: Level, now: Double, wind: Float, fade: Float, paper: TextureAtlasSprite, letters: TextureAtlasSprite,
    ) {
        val h = "ticket|${ticket.order}".hashCode().toLong() * -7046029254386353131L
        fun rnd(bits: Int): Float = ((h ushr bits) and 1023L).toFloat() / 1023f
        val p = page
        p.w = A.TICKET_W
        p.h = A.TICKET_H
        p.pin = A.TICKET_PIN
        p.slotU = A.TICKET_U0 + ticket.slot * A.TICKET_PITCH + (rnd(3) - 0.5f) * 0.01f
        p.slotV = A.TICKET_TOP + (rnd(13) - 0.5f) * 0.006f
        p.z = -A.TICKET_DEPTH + 0.004f + ticket.slot * 0.0005f
        p.turn = (rnd(23) - 0.5f) * 0.07f
        p.du = 0f
        p.dv = 0f
        p.dz = 0f
        p.lift = 0f
        p.ripple = 0f
        p.tilt = 0f
        p.corner = if (rnd(33) < 0.5f) -1 else 1
        p.time = (now % 100000.0 + rnd(43) * 4000.0).toFloat()
        var alpha = 1f
        var tack = 1f
        var pressed = 1f
        if (falling >= 0f) {
            // Taken down: the tack out, the card dropping to the ground and blown off it
            val g = falling
            p.dv = min(g * g * 0.0011f + g * 0.003f, 2f - (p.slotV + A.TICKET_H) - 0.004f)
            p.dz = 0.02f + g * 0.01f
            p.turn += g * 0.03f * p.corner
            alpha = (1f - g / A.FALL).coerceIn(0f, 1f)
            tack = 0f
        } else if (age < A.LAND) {
            // In flight from the crier's hand, down from about where they stand
            val e = 1f - (1f - (age / A.LAND).coerceIn(0f, 1f)).pow(3)
            p.du = (1f - e) * (1.5f - (p.slotU + A.TICKET_W / 2)) * 0.5f
            p.dv = (1f - e) * -0.55f
            p.dz = (1f - e) * 0.35f + sin(e * PI.toFloat()) * 0.04f
            p.turn += (1f - e) * 0.5f * p.corner
            alpha = min(1f, age / 2.5f)
            tack = 0f
        } else if (age < A.FLUTTER) {
            // Tacked on, it swings on the tack, hanging free, and settles
            val b = age - A.LAND
            p.turn += 0.16f * exp(-b / 7f) * sin(b * 0.8f)
            pressed = 1f + 0.5f * exp(-b / 2.5f)
        }
        if (falling < 0f && age >= A.LAND) {
            // The air sways it on its tack, and now and then a breeze lifts it out from the rail
            val t = p.time
            p.turn += A.SWAY * min(1f, wind * 0.6f) * (0.6f * sin(t * 0.031f) + 0.4f * sin(t * 0.077f + 1.3f))
            val gust = sin(t * 0.019f) * 0.6f + sin(t * 0.047f + 1.7f) * 0.4f
            p.tilt = ((gust - 0.55f) / 0.45f).coerceIn(0f, 1f) * min(1.6f, wind) * 0.18f
        }
        if (alpha <= 0.01f) return

        val light = lightAt(p.slotU + A.TICKET_W / 2, p.slotV + A.TICKET_H / 2)
        // The card, turned from the light as it's lifted out; its back for anyone behind the board
        val c = (255 * (1f - 0.3f * min(1f, p.tilt * 4f))).toInt()
        val argb = ((alpha * 255).toInt() shl 24) or (c shl 16) or (c shl 8) or c
        val front = A.TICKET
        quad(
            state.layers[1], p, 0f, 0f, A.TICKET_W, A.TICKET_H,
            paper.getU(front[0].toFloat() / A.SLIPS_W), paper.getV(front[1].toFloat() / A.SLIPS_H),
            paper.getU((front[0] + front[2]).toFloat() / A.SLIPS_W), paper.getV((front[1] + front[3]).toFloat() / A.SLIPS_H),
            argb, light,
        )
        val back = A.TICKET_BACK
        p.inkLift = -0.004f
        quad(
            state.layers[1], p, A.TICKET_W, 0f, 0f, A.TICKET_H,
            paper.getU(back[0].toFloat() / A.SLIPS_W), paper.getV(back[1].toFloat() / A.SLIPS_H),
            paper.getU((back[0] + back[2]).toFloat() / A.SLIPS_W), paper.getV((back[1] + back[3]).toFloat() / A.SLIPS_H),
            argb, light,
        )
        p.inkLift = 0f

        // The good's icon, near enough to make out
        val k = MarketBoards.ORDER.indexOf(ticket.good)
        val stack = if (k >= 0) STACKS[k] else ItemStack.EMPTY
        if (icon >= 0 && !stack.isEmpty && fade > 0.05f && alpha > 0.5f) {
            p.inkLift = 0.003f
            p.map(A.TICKET_ICON_U, A.TICKET_ICON_V, corner)
            p.inkLift = 0f
            itemModels.updateForTopItem(state.ticketIcons[icon], stack, ItemDisplayContext.FIXED, level, null, MarketBoards.ORDER.size + icon)
            state.ticketIconOn[icon] = true
            state.ticketIconX[icon] = corner[0]
            state.ticketIconY[icon] = corner[1]
            state.ticketIconZ[icon] = corner[2]
            state.ticketIconTurn[icon] = p.turn
            state.ticketIconSize[icon] = A.TICKET_ICON * (if (stack.item is BlockItem) 1.6f else 1f)
            state.ticketIconLight[icon] = light
        }

        // What it says, near enough to read; the stamp over the count once it's taken, thumped on
        val q = state.layers[2]
        if (fade > 0.002f) {
            p.inkLift = 0.0012f
            val room = A.TICKET_W - 2 * A.TICKET_MARGIN
            val head = words.text("board.guildmark.ticket.wanted").uppercase(Locale.ROOT)
            val hs = min(A.S_TICKET_HEAD, room / max(1f, width(INK, head)))
            ink(q, p, head, A.TICKET_W / 2 - width(INK, head) * hs / 2, A.TICKET_HEAD_BASE, hs, scaleAlpha(0xFF000000.toInt() or A.INK_WANTED, 0.93f * alpha * fade), light, letters)
            if (!ticket.taken) {
                val count = ticket.count.toString()
                val cs = min(A.S_TICKET_COUNT, (A.TICKET_W - A.TICKET_MARGIN - A.TICKET_COUNT_U) / max(1f, width(INK, count)))
                ink(q, p, count, A.TICKET_COUNT_U, A.TICKET_COUNT_BASE, cs, scaleAlpha(0xFF000000.toInt() or A.INK, 0.92f * alpha * fade), light, letters)
            }
            val shop = ticket.shop.string
            if (shop.isNotBlank()) {
                // On two lines if it won't go on one, and smaller if it still won't
                var ss = A.S_TICKET_SHOP
                val lines = if (width(INK, shop) * ss <= room) listOf(shop) else wrap(INK, shop, room / ss, 2)
                val widest = lines.maxOf { width(INK, it) } * ss
                if (widest > room) ss *= room / widest
                var base = A.TICKET_SHOP_BASE - (lines.size - 1) * A.TEXT_LINE * ss
                for (line in lines) {
                    ink(q, p, line, A.TICKET_W / 2 - width(INK, line) * ss / 2, base, ss, scaleAlpha(0xFF000000.toInt() or A.INK, 0.88f * alpha * fade), light, letters)
                    base += A.TEXT_LINE * ss
                }
            }
            if (ticket.taken) {
                val a = (stamped / 2f).coerceIn(0f, 1f)
                val st = turned
                st.u = A.STAMP_U
                st.v = A.STAMP_V
                st.turn = A.STAMP_TURN
                st.scale = 1f + 0.6f * max(0f, 1f - stamped / 6f).pow(2)
                val sw = A.STAMP_W
                val sh = sw * A.STAMP[3] / A.STAMP[2]
                val frame = A.STAMP
                // Over the good's icon
                p.inkLift = 0.0055f
                quad(
                    q, st, -sw / 2, -sh / 2, sw / 2, sh / 2,
                    paper.getU(frame[0].toFloat() / A.SLIPS_W), paper.getV(frame[1].toFloat() / A.SLIPS_H),
                    paper.getU((frame[0] + frame[2]).toFloat() / A.SLIPS_W), paper.getV((frame[1] + frame[3]).toFloat() / A.SLIPS_H),
                    scaleAlpha(0xFFFFFFFF.toInt(), a * alpha * fade), light,
                )
                p.inkLift = 0.006f
                val word = words.text("board.guildmark.ticket.taken").uppercase(Locale.ROOT)
                val ws = min(A.S_STAMP, sw * 0.72f / max(1f, width(INK, word)))
                ink(q, st, word, -width(INK, word) * ws / 2, 3.5f * ws, ws, scaleAlpha(0xFF000000.toInt() or A.INK_STAMP, 0.95f * a * alpha * fade), light, letters)
            }
            p.inkLift = 0f
        }

        if (tack > 0f) tackOn(q, p, A.TICKET_W / 2, A.TICKET_PIN, A.TACK_SIZE * pressed, alpha, light, paper)
    }

    // ---------------------------------------------------------------- the medallion

    /**
     * The medallion at the board's head: a drum of five carved faces, one for each of the town's times, on an iron axle
     * between two posts over the ridge (the posts and their plinth are the top middle cell's model). It shows the face
     * of the times [board] holds. When they move ([MarketBoardBlockEntity.turned] says when) it turns to the new face as
     * a heavy drum would, rolling up for better times and down for worse, swinging a little past onto its catch and
     * back; a little dust comes out of its bearings as it starts and off its faces at the catch. At rest it stirs on its
     * axle, more in rain and storm. First seen, it shows as it stands, and so it does if it turned while nobody was
     * looking (longer ago than a turn takes); a change that comes while it's turning turns it on from wherever it is.
     */
    private fun medallion(
        board: MarketBoardBlockEntity, state: MarketBoardRenderState, mem: Memory, level: Level, pos: BlockPos,
        now: Double, eye: Vec3,
    ) {
        val target = A.MEDALLION_TIMES.indexOf(board.times).let { if (it < 0) STEADY_FACE else it }
        val seen = !mem.medShown.isNaN()
        if (!seen || target != mem.medTo) {
            val missed = board.turned == MarketBoardBlockEntity.LONG_AGO ||
                now - board.turned > A.medallionStill(abs(target - mem.medShown)) + 20.0
            if (!seen || missed) {
                mem.medFrom = target.toFloat()
                mem.medTo = target
                mem.medStart = Double.NEGATIVE_INFINITY
                mem.medKnocked = true
            } else {
                mem.medFrom = mem.medShown
                mem.medTo = target
                mem.medStart = now
                mem.medKnocked = false
                medDust(level, pos, state.yRot, 3, false)
            }
        }
        val faces = abs(mem.medTo - mem.medFrom)
        val turning = faces > 1e-4f
        val age = (now - mem.medStart).toFloat()
        val at = if (turning) mem.medFrom + (mem.medTo - mem.medFrom) * A.medallionTurn(age, faces) else mem.medTo.toFloat()
        mem.medShown = at
        if (!mem.medKnocked && turning && age >= medPeak(faces)) {
            mem.medKnocked = true
            medDust(level, pos, state.yRot, 2, true)
        }
        // At rest it stirs on its axle in the wind, coming to it slowly once a turn is over
        val settled = if (turning) ((age - A.medallionStill(faces)) / 30f).coerceIn(0f, 1f) else 1f
        val wind = 1f + level.getRainLevel(0f) * 0.7f + level.getThunderLevel(0f) * 0.8f
        val t = (now % 100000.0).toFloat() + (pos.hashCode() and 1023)
        val phi = at * STEP + A.MED_ROCK * wind * settled * (0.6f * sin(t * 0.071f) + 0.4f * sin(t * 0.163f + 1.7f))

        val sprite = sprites.get(MEDALLION_ID)
        val q = state.layers[1]
        val light = LightCoordsUtil.max(lightAt(1.5f, 0.5f), LightCoordsUtil.getLightCoords(level, pos.above(2)))
        // The eye in the board's frame: the faces turned from it aren't drawn
        local(pos, state.yRot, eye, corner)
        val ex = corner[0]
        val ey = corner[1]
        val ez = corner[2]
        val y0 = -A.MED_V
        val z0 = -A.MED_DEPTH
        val r = A.MED_R
        for (k in A.MEDALLION_FACES.indices) {
            // Face k is turned (phi - k fifths) from facing out, up being positive; its edges a tenth of a turn either side
            val a = phi - k * STEP
            val ny = sin(a)
            val nz = cos(a)
            if ((ey - y0 - A.MED_APOTHEM * ny) * ny + (ez - z0 - A.MED_APOTHEM * nz) * nz <= 0f) continue
            val ty = y0 + r * sin(a + STEP / 2)
            val tz = z0 + r * cos(a + STEP / 2)
            val by = y0 + r * sin(a - STEP / 2)
            val bz = z0 + r * cos(a - STEP / 2)
            val cell = A.MEDALLION_FACES[k]
            val s0 = sprite.getU(cell[0].toFloat() / A.MEDALLION_W)
            val s1 = sprite.getU((cell[0] + cell[2]).toFloat() / A.MEDALLION_W)
            val t0 = sprite.getV(cell[1].toFloat() / A.MEDALLION_H)
            val t1 = sprite.getV((cell[1] + cell[3]).toFloat() / A.MEDALLION_H)
            q.vertex(A.MED_U0, ty, tz, s0, t0)
            q.vertex(A.MED_U0, by, bz, s0, t1)
            q.vertex(A.MED_U1, by, bz, s1, t1)
            q.vertex(A.MED_U1, ty, tz, s1, t0)
            // Shaded as a block's faces are: full on top, less to the front, least underneath
            q.close(grey(if (ny >= 0f) 0.8f + 0.2f * ny else 0.8f + 0.3f * ny), light)
        }
        // Its ends, each pentagon turned with the drum (the left end's is seen from its own side, a tenth of a turn on)
        val cap = A.MEDALLION_CAP
        val s0 = sprite.getU(cap[0].toFloat() / A.MEDALLION_W)
        val s1 = sprite.getU((cap[0] + cap[2]).toFloat() / A.MEDALLION_W)
        val t0 = sprite.getV(cap[1].toFloat() / A.MEDALLION_H)
        val t1 = sprite.getV((cap[1] + cap[3]).toFloat() / A.MEDALLION_H)
        if (ex > A.MED_U1) drumEnd(q, A.MED_U1, y0, z0, -sin(phi), -cos(phi), cos(phi), -sin(phi), s0, t0, s1, t1, light)
        if (ex < A.MED_U0) {
            val p = phi + STEP / 2
            drumEnd(q, A.MED_U0, y0, z0, sin(p), cos(p), cos(p), -sin(p), s0, t0, s1, t1, light)
        }
    }

    /** An end of the medallion at [x]: its square about the axle ([y], [z]), ([ry], [rz]) its right and ([qy], [qz]) its up as seen from outside. */
    private fun drumEnd(
        q: BoardQuads, x: Float, y: Float, z: Float, ry: Float, rz: Float, qy: Float, qz: Float,
        s0: Float, t0: Float, s1: Float, t1: Float, light: Int,
    ) {
        val h = A.MED_CAP_HALF
        q.vertex(x, y + (qy - ry) * h, z + (qz - rz) * h, s0, t0)
        q.vertex(x, y + (-qy - ry) * h, z + (-qz - rz) * h, s0, t1)
        q.vertex(x, y + (ry - qy) * h, z + (rz - qz) * h, s1, t1)
        q.vertex(x, y + (ry + qy) * h, z + (rz + qz) * h, s1, t0)
        q.close(grey(0.6f), light)
    }

    /** A little wood dust at the medallion: out of its bearings as it starts to turn, or [shaken] off its underside at the catch. */
    private fun medDust(level: Level, pos: BlockPos, yRot: Float, n: Int, shaken: Boolean) {
        val r = level.random
        for (side in 0..1) {
            repeat(n) {
                val w = if (shaken) {
                    world(pos, yRot, A.MED_U0 + (A.MED_U1 - A.MED_U0) * r.nextFloat(), A.MED_V + A.MED_R * 0.9f, -A.MED_DEPTH + (r.nextFloat() - 0.5f) * 0.3f)
                } else {
                    world(pos, yRot, if (side == 0) A.MED_U0 - 0.01f else A.MED_U1 + 0.01f, A.MED_V, -A.MED_DEPTH)
                }
                level.addParticle(WOOD_DUST, w.x, w.y, w.z, (r.nextDouble() - 0.5) * 0.01, -0.006 - r.nextDouble() * 0.006, (r.nextDouble() - 0.5) * 0.01)
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun submit(state: MarketBoardRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
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

        // The goods' icons, flat on the slate
        val z = -A.SLATE_DEPTH + 0.004f
        for (i in state.icons.indices) {
            if (!state.iconOn[i]) continue
            poseStack.pushPose()
            poseStack.translate(state.iconU[i], -state.iconV[i], z)
            poseStack.rotateDegrees(Axis.YP, 180f)
            val s = state.iconSize[i]
            poseStack.scale(s, s, s * 0.04f)
            state.icons[i].submit(poseStack, collector, state.iconLight[i], OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }

        // The goods' icons on the tickets, turned as each ticket hangs
        for (i in state.ticketIcons.indices) {
            if (!state.ticketIconOn[i]) continue
            poseStack.pushPose()
            poseStack.translate(state.ticketIconX[i], state.ticketIconY[i], state.ticketIconZ[i])
            poseStack.rotateDegrees(Axis.ZP, -Math.toDegrees(state.ticketIconTurn[i].toDouble()).toFloat())
            poseStack.rotateDegrees(Axis.YP, 180f)
            val s = state.ticketIconSize[i]
            poseStack.scale(s, s, s * 0.04f)
            state.ticketIcons[i].submit(poseStack, collector, state.ticketIconLight[i], OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }

        // The town's name, gilt, sunk a little into the plank
        val town = state.town
        val shadow = state.townShadow
        if (town != null && shadow != null && state.townAlpha > 0.004f) {
            poseStack.pushPose()
            poseStack.translate(state.townU, -(A.HEADER_V0 + A.HEADER_V1) / 2, -A.HEADER_DEPTH + 0.003f)
            poseStack.scale(state.townScale, -state.townScale, state.townScale)
            val a = (state.townAlpha * 255).toInt().coerceIn(4, 255) shl 24
            collector.submitText(poseStack, 0.6f, -2.5f + 0.6f, shadow, false, Font.DisplayMode.POLYGON_OFFSET, state.townLight, a or A.GILT_SHADOW, 0, 0)
            poseStack.translate(0f, 0f, 0.0008f / state.townScale)
            collector.submitText(poseStack, 0f, -2.5f, town, false, Font.DisplayMode.POLYGON_OFFSET, state.townLight, a or 0xFFFFFF, 0, 0)
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    // ---------------------------------------------------------------- helpers

    private val flat = Flat()
    private val shade = Flat()
    private val page = Page()
    private val turned = Turned(page)
    private val corner = FloatArray(3)
    private val lights = IntArray(6)

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
        private val LETTERS_ID = SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, A.LETTERS))
        private val SLIPS_ID = SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, A.SLIPS))
        private val MEDALLION_ID = SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, A.MEDALLION))
        private val HEADING = FontDescription.Resource(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "heading"))
        private val type: RenderType by lazy { RenderTypes.textPolygonOffset(TextureAtlas.LOCATION_BLOCKS) }
        private val DUST = DustParticleOptions(0xF2EFE6, 0.45f)
        private val WOOD_DUST = DustParticleOptions(0x8C6E4E, 0.5f)

        /** A fifth of a turn: from one of the medallion's faces to the next. */
        private val STEP = (2 * PI / 5).toFloat()
        /** The face shown for times the medallion doesn't know (steady's). */
        private const val STEADY_FACE = 2

        private val SECTION_KEYS = MarketBoards.SECTIONS.map { it.first }
        private val STACKS: Array<ItemStack> by lazy { Array(MarketBoards.ORDER.size) { i -> MarketBoards.ORDER[i].item?.let { ItemStack(it) } ?: ItemStack.EMPTY } }

        private const val SLOTS = MarketBoardBlockEntity.SLIPS
        private const val TICKET_SLOTS = MarketBoardBlockEntity.TICKETS

        /** Ticks after the crier begins writing a board up during which a change of its title is chalked in for all to see. */
        private const val TITLE_FRESH = 200L
        private const val MESH_X = 6
        private const val MESH_Y = 3
        private const val CAP = 7f
        /** How far ahead of the chalk's middle a piece shows as it's drawn: the stick's own breadth (font px). */
        private const val TIP = 0.95f
        /** How far a breeze lifts a slip's free corner, at most (blocks). */
        private const val LIFT = 0.035f
        private const val GLINT_EVERY = 240.0
        private const val GLINT_TAKES = 34f

        private val CHALK: Hand by lazy { Hand(A.CHALK_GLYPHS, 1.0f) }
        private val INK: Hand by lazy { Hand(A.INK_GLYPHS, 0.85f) }
        private val UNDERLINE by lazy { Cell.of(A.UNDERLINE) }
        private val STRIKE by lazy { Cell.of(A.STRIKE) }
        private val SWASH by lazy { Cell.of(A.SWASH) }
        private val SMUDGE by lazy { Cell.of(A.SMUDGE) }
        private val STICK by lazy { Cell.of(A.STICK) }

        private val memory = WeakHashMap<MarketBoardBlockEntity, Memory>()
        private val words = Words()

        fun register(modBus: IEventBus) {
            modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
                event.registerBlockEntityRenderer(ModBlockEntities.MARKET_BOARD.get(), ::MarketBoardRenderer)
            }
        }

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

        /** Where a point of the world is in the board's frame (x right as seen, y up from the top, z out from the front): [world] undone, into [out]. */
        private fun local(pos: BlockPos, yRot: Float, p: Vec3, out: FloatArray) {
            val a = Math.toRadians(-yRot.toDouble())
            val c = cos(a)
            val s = sin(a)
            val dx = p.x - pos.x - 0.5
            val dz = p.z - pos.z - 0.5
            out[0] = (1.5 - (dx * c - dz * s)).toFloat()
            out[1] = (p.y - pos.y - 2.0).toFloat()
            out[2] = (-(dx * s + dz * c) - 0.5).toFloat()
        }

        /** The ticks from the start of a turn of [faces] faces to its first swing past the new face, onto the catch. */
        private fun medPeak(faces: Float): Float =
            PI.toFloat() / (A.MED_OMEGA / sqrt(max(1f, faces)) * sqrt(1f - A.MED_ZETA * A.MED_ZETA))

        /** Opaque white dimmed to [k], to shade a face by. */
        private fun grey(k: Float): Int {
            val c = (k * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }

        private fun key(slip: MarketBoardBlockEntity.Slip): String = "${slip.pinned}|${slip.kind}|${slip.headline.string}"

        private fun identical(a: MarketBoardBlockEntity.Line?, b: MarketBoardBlockEntity.Line?): Boolean =
            if (a == null || b == null) a === b
            else a.good == b.good && a.count == b.count && a.marks == b.marks && a.trend == b.trend && a.off == b.off && a.chalked == b.chalked

        private fun width(hand: Hand, text: String): Float {
            var w = 0f
            for (c in text) w += hand.glyph(c)?.advance ?: 3f
            return max(0f, w - hand.gap)
        }

        /** [text] broken at spaces into lines no wider than [room] font px, at most [most] of them (the last may run over). */
        private fun wrap(hand: Hand, text: String, room: Float, most: Int): List<String> {
            val out = ArrayList<String>()
            var line = ""
            for (word in text.split(' ')) {
                val tried = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(hand, tried) > room) {
                    out.add(line)
                    if (out.size == most) return out
                    line = word
                } else {
                    line = tried
                }
            }
            if (line.isNotEmpty() && out.size < most) out.add(line)
            return out
        }

        private fun smooth(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        private fun alpha(argb: Int): Float = (argb ushr 24) / 255f

        private fun tint(rgb: Int, a: Float = 0.94f): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

        private fun scaleAlpha(argb: Int, k: Float): Int = ((alpha(argb) * k.coerceIn(0f, 1f) * 255).toInt() shl 24) or (argb and 0xFFFFFF)

        /** Chalk's colour while fresh: whiter and stronger, settling to its own. */
        private fun fresh(argb: Int, k: Float, fade: Float): Int {
            val rgb = mixRgb(argb and 0xFFFFFF, A.CHALK_FRESH, 0.6f * k)
            return ((min(1f, alpha(argb) + 0.06f * k) * fade * 255).toInt() shl 24) or rgb
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

    // ---------------------------------------------------------------- the lettering

    /** A cell of the lettering atlas: texels [x], [y], [w], [h], with its top left at ([left], [top]) font px of its glyph. */
    private class Cell(val x: Int, val y: Int, val w: Int, val h: Int, val left: Float, val top: Float) {
        val width: Float get() = w / A.TEXELS.toFloat()
        val height: Float get() = h / A.TEXELS.toFloat()

        companion object {
            fun of(a: IntArray) = Cell(a[0], a[1], a[2], a[3], a[4] / 1000f, a[5] / 1000f)
        }
    }

    /** One piece of a chalk glyph: what of it shows ([cell]), the way it's swept, and the chalk's path over it (font px). */
    private class Piece(val cell: Cell?, val axis: Int, val dir: Int, val path: FloatArray) {
        val length: Float = run {
            var l = 0f
            for (i in 2 until path.size step 2) l += hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1])
            l
        }
    }

    private class Glyph(val advance: Float, val cell: Cell?, val pieces: Array<Piece>)

    /** A hand of lettering read from [MarketBoardArt]'s tables; letters it hasn't got are written as their plain forms. */
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

    // ---------------------------------------------------------------- a row of chalk, and its chalking

    /** Text in the chalk hand along a baseline, [squeeze]d across if it had to be. */
    private class Run(val text: String, val x: Float, val base: Float, val scale: Float, val argb: Int, val squeeze: Float)

    /** A chalked bar from [x0] to [x1] along [y]. */
    private class Bar(val cell: Cell, val x0: Float, val x1: Float, val y: Float, val scale: Float, val argb: Int)

    /** Where the chalk's tip is (face blocks), and whether it's on the slate or lifted between strokes. */
    private class Tip(val u: Float, val v: Float, val down: Boolean)

    /** One stroke of the chalk: a glyph's piece (or a bar) placed on the face, glyph font px scaled by [sx] across and [sy] down. */
    private class Stroke(
        val cell: Cell?, val axis: Int, val dir: Int, val path: FloatArray, val x: Float, val base: Float, val sx: Float, val sy: Float,
        val lift: Float, val argb: Int,
    ) {
        /** Its length on the face, in font px of the prices. */
        val length: Float = run {
            var l = 0f
            for (i in 2 until path.size step 2) l += hypot((path[i] - path[i - 2]) * sx, (path[i + 1] - path[i - 1]) * sy)
            l / A.S_PRICE
        }
        private val point = FloatArray(2)

        /** The point of its path [f] of the way along (glyph font px). */
        fun pointAt(f: Float): FloatArray {
            if (path.size < 4) {
                point[0] = path.getOrElse(0) { 0f }
                point[1] = path.getOrElse(1) { 0f }
                return point
            }
            val want = f.coerceIn(0f, 1f) * length * A.S_PRICE
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

    /** When each stroke of a row starts and ends, in ticks at the chalk's own pace (scaled to the row's time on the board). */
    private class Plan(val strokes: List<Stroke>) {
        val starts = FloatArray(strokes.size)
        val ends = FloatArray(strokes.size)
        val total: Float

        init {
            var t = 0f
            var last: Tip? = null
            for ((k, s) in strokes.withIndex()) {
                val from = s.tipAt(0f, false)
                last?.let { t += hypot(from.u - it.u, from.v - it.v) / (A.TRAVEL_SPEED * A.S_PRICE) }
                starts[k] = t
                t += s.length / A.DRAW_SPEED
                ends[k] = t
                last = s.tipAt(1f, false)
            }
            total = max(t, 1f)
        }
    }

    /** What's chalked in a row (a good's lot, or the title), whole and as strokes. */
    private class Row {
        val runs = ArrayList<Run>()
        val bars = ArrayList<Bar>()

        fun run(text: String, x: Float, base: Float, scale: Float, argb: Int, squeeze: Float = 1f) {
            runs.add(Run(text, x, base, scale, argb, squeeze))
        }

        fun bar(cell: Cell, x0: Float, x1: Float, y: Float, scale: Float, argb: Int) {
            bars.add(Bar(cell, x0, x1, y, scale, argb))
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
            for (b in bars) {
                // A bar is one stroke along its length, its own cell stretched to fit
                val sx = (b.x1 - b.x0) / b.cell.width
                val path = floatArrayOf(b.cell.left + 0.8f, 0f, b.cell.left + b.cell.width - 0.8f, 0f)
                strokes.add(Stroke(b.cell, 0, 1, path, b.x0 - b.cell.left * sx, b.y, sx, b.scale, 0f, b.argb))
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

    /**
     * A slip's paper (or an order's ticket), in its own blocks from its top left: [w] by [h], tacked [pin] down from
     * the middle of its top, turned on its tack, lifted and rippled where the air takes it; a ticket hanging free
     * below the tack is tilted out from the board by [tilt] (blocks out a block down).
     */
    private class Page : Surface {
        var w = A.SLIP_W
        var h = A.SLIP_H
        var pin = A.PIN_DOWN
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

        /** How far a point of the paper stands off the board (blocks). */
        fun liftAt(x: Float, y: Float): Float {
            val fy = (y / h).coerceIn(0f, 1f)
            val fx = (if (corner > 0) x / w else 1f - x / w).coerceIn(0f, 1f)
            val corner = fy.pow(1.7f) * fx.pow(1.3f)
            val wave = sin(time * 0.9f + x * 26f) * fy * 0.12f
            return LIFT * (lift * corner + ripple * wave * (0.4f + fy))
        }

        override fun map(u: Float, v: Float, out: FloatArray) {
            val px = u - w / 2
            val py = v - pin
            val c = cos(turn)
            val s = sin(turn)
            out[0] = slotU + w / 2 + px * c - py * s + du
            out[1] = -(slotV + pin + px * s + py * c + dv)
            out[2] = z + dz + liftAt(u, v) + max(0f, py) * tilt + inkLift
        }
    }

    /** Something turned and scaled about a point of a [page] (a stamp on a ticket), in its own blocks from that point. */
    private class Turned(val page: Page) : Surface {
        var u = 0f
        var v = 0f
        var turn = 0f
        var scale = 1f

        override fun map(u: Float, v: Float, out: FloatArray) {
            val c = cos(turn)
            val s = sin(turn)
            val x = u * scale
            val y = v * scale
            page.map(this.u + x * c - y * s, this.v + x * s + y * c, out)
        }
    }

    // ---------------------------------------------------------------- what a viewer's client remembers of a board

    private class Seen(var slip: MarketBoardBlockEntity.Slip, val key: String)

    private class Gone(val slip: MarketBoardBlockEntity.Slip, val slot: Int, val at: Double)

    /** A ticket taken down at [at], falling from its slot. */
    private class Fallen(val ticket: MarketBoardBlockEntity.Ticket, val at: Double)

    private class Memory {
        /** Nothing seen yet: everything there is shown as it stands, with nothing being rubbed out. */
        var fresh = true
        var lastFrame = Double.NaN
        val lines = arrayOfNulls<MarketBoardBlockEntity.Line>(MarketBoards.ORDER.size)
        val rows = arrayOfNulls<Row>(MarketBoards.ORDER.size)
        val old = arrayOfNulls<Row>(MarketBoards.ORDER.size)
        val oldAt = DoubleArray(MarketBoards.ORDER.size)
        var title = ""
        var titleRow: Row? = null
        var titleAt = Double.NEGATIVE_INFINITY
        var oldTitle: Row? = null
        var oldTitleAt = 0.0
        var stickU = Float.NaN
        var stickV = 0f
        var stickLift = 0f
        var stickAlpha = 0f
        var lastDust = 0.0
        val slots = arrayOfNulls<Seen>(SLOTS)
        val gone = ArrayList<Gone>()

        /** The ticket seen in each slot of the rail, when each was seen stamped (-infinity: stamped before it was seen), those falling. */
        val tickets = arrayOfNulls<MarketBoardBlockEntity.Ticket>(TICKET_SLOTS)
        val stamped = DoubleArray(TICKET_SLOTS) { Double.NEGATIVE_INFINITY }
        val fallen = ArrayList<Fallen>()
        var townName = ""
        var townWidth = 0f
        var townCentres = FloatArray(0)
        var words = -1

        /**
         * The medallion: where it was last drawn (in faces from hard times', part way through a turn; NaN until it's
         * first drawn), the turn it's in (from where, to which face, begun when), whether that turn has reached its catch.
         */
        var medShown = Float.NaN
        var medFrom = 0f
        var medTo = 0
        var medStart = Double.NEGATIVE_INFINITY
        var medKnocked = true
    }

    /** The board's words in the player's language, looked up again when it changes. */
    private class Words {
        private var language: Language? = null
        private val cache = HashMap<String, String>()
        var none = "none"
            private set
        var lotMiddle: String? = "for"
            private set
        var version = 0
            private set

        fun refresh() {
            val now = Language.getInstance()
            if (now === language) return
            language = now
            version++
            cache.clear()
            none = text("board.guildmark.market.none")
            val pattern = now.getOrDefault("board.guildmark.market.lot")
            lotMiddle = Regex("^%(?:1\\$)?s(.*?)%(?:2\\$)?s$").matchEntire(pattern)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun text(key: String): String = cache.getOrPut(key) { Component.translatable(key).string }

        fun lot(count: Int, marks: Int): String = Component.translatable("board.guildmark.market.lot", count, marks).string
    }
}
