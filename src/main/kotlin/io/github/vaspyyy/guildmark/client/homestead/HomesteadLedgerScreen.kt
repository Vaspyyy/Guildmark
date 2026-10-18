package io.github.vaspyyy.guildmark.client.homestead

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.homestead.HomesteadLedger
import io.github.vaspyyy.guildmark.homestead.HomesteadTiers
import io.github.vaspyyy.guildmark.homestead.LedgerActionPayload
import io.github.vaspyyy.guildmark.homestead.LedgerPayload
import io.github.vaspyyy.guildmark.homestead.PlotFrame
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.Direction
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A homestead's ledger, opened at its Charter Post by its owner or one of their party: the plot from above with the
 * tiers' bounds and whoever is at work on it, the tier and the one going up, how the build stands (its stage, the
 * crew, the daylight work left), and the materials: what the tier takes, what's still to come of it and what's in the
 * supply crate. The owner commissions the next tier here, or gives the charter up (twice asked). It keeps itself up to
 * date while it's open, and everything in it eases to its new value.
 */
class HomesteadLedgerScreen(private var data: LedgerPayload) : Screen(Component.translatable("gui.guildmark.homestead.ledger")) {
    val id: Int get() = data.id

    private var ticks = 0
    private var panelX = 0
    private var panelY = 0

    /** Shown values easing toward the ledger's: the build's progress, and each material's share done and in the crate. */
    private var progress = -1f
    private var progressO = 0f
    private val done = FloatArray(Stuff.PAID.size) { -1f }
    private val doneO = FloatArray(Stuff.PAID.size)
    /** When the stage last changed (its name crossfades), and the one before. */
    private var stageAt = -100
    private var stageBefore = -1

    private var map: DynamicTexture? = null
    private var mapStale = true

    private var commission: Ui.KitButton? = null
    private var relinquish: Ui.KitButton? = null
    private var round: Ui.KitButton? = null
    /** Until when a second press of the relinquish button gives the charter up. */
    private var confirmUntil = -1

    fun update(payload: LedgerPayload) {
        if (!payload.map.contentEquals(data.map)) mapStale = true
        if (payload.stage != data.stage) {
            stageBefore = data.stage
            stageAt = ticks
        }
        data = payload
        refresh()
    }

    // ---- Setup ----

    override fun init() {
        panelX = (width - PANEL_WIDTH) / 2
        panelY = max(4, (height - PANEL_HEIGHT) / 2)
        val footY = panelY + PANEL_HEIGHT - 27
        val center = panelX + PANEL_WIDTH / 2
        commission = addRenderableWidget(Ui.button(Component.empty(), 150) { send(HomesteadLedger.COMMISSION) }.also { it.setPosition(center - 154, footY) })
        relinquish = addRenderableWidget(Ui.button(Component.empty(), 96) { relinquish() }.also { it.setPosition(center + 2, footY) })
        addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.homestead.ledger.close"), 52) { onClose() }.also { it.setPosition(center + 102, footY) })
        round = addRenderableWidget(Ui.button(Component.translatable("gui.guildmark.homestead.ledger.round"), 96) { send(HomesteadLedger.BUILD_ROUND) }.also {
            it.setPosition(panelX + 12, panelY + 147)
        })
        refresh()
    }

    private fun refresh() {
        val next = HomesteadTiers.of(data.tier + 1)
        val building = data.building > data.tier
        commission?.let {
            it.message = when {
                building -> Component.translatable("gui.guildmark.homestead.ledger.raising_button", tierName(data.building))
                next == null -> Component.translatable("gui.guildmark.homestead.ledger.complete_button")
                else -> Component.translatable("gui.guildmark.homestead.ledger.commission", tierName(next.n), next.marks)
            }
            it.active = data.owner && !building && next != null && data.purse >= next.marks
            it.setTooltip(null)
        }
        relinquish?.let {
            val asking = ticks < confirmUntil
            it.message = Component.translatable(if (asking) "gui.guildmark.homestead.ledger.relinquish_sure" else "gui.guildmark.homestead.ledger.relinquish")
            it.active = data.owner
        }
        round?.visible = data.blocked != null
    }

    private fun send(action: Int, confirm: Boolean = false) {
        ClientPacketDistributor.sendToServer(LedgerActionPayload(data.id, action, confirm))
    }

    private fun relinquish() {
        if (!data.owner) return
        if (ticks < confirmUntil) {
            send(HomesteadLedger.RELINQUISH, true)
            onClose()
            return
        }
        confirmUntil = ticks + CONFIRM_TICKS
        Ui.play(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 0.5f)
        refresh()
    }

    override fun added() {
        super.added()
        Ui.open()
    }

    override fun onClose() {
        Ui.close()
        super.onClose()
    }

    override fun removed() {
        map?.close()
        map = null
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    override fun isInGameUi(): Boolean = true

    override fun tick() {
        ticks++
        val player = minecraft.player
        // Walked off from the post: the ledger closes
        if (player == null || !player.isAlive || player.distanceToSqr(Vec3.atCenterOf(data.post)) > REACH * REACH) {
            Ui.close()
            minecraft.gui.setScreen(null)
            return
        }
        if (ticks % REFRESH_TICKS == 0) send(HomesteadLedger.REFRESH)
        if (confirmUntil in 1..ticks) {
            confirmUntil = -1
            refresh()
        }
        // The shown values ease toward the ledger's
        progressO = progress
        val target = if (data.total <= 0) 0f else data.laid.toFloat() / data.total
        progress = if (progress < 0f) target * 0.2f else progress + (target - progress) * 0.18f
        for (i in done.indices) {
            doneO[i] = done[i]
            val bill = data.bill.getOrElse(i) { 0 }
            val left = data.left.getOrElse(i) { 0 }
            val t = if (bill <= 0) 1f else ((bill - left).toFloat() / bill).coerceIn(0f, 1f)
            done[i] = if (done[i] < 0f) 0f else done[i] + (t - done[i]) * 0.2f
        }
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val k = min(1f, (ticks + a) / 5f)
        graphics.fill(0, 0, width, height, ARGB.color((150 * k).toInt(), 6, 4, 2))
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val time = ticks + a
        // The page settles in as it opens
        val open = min(1f, time / 6f)
        val lift = ((1f - open * open * (3 - 2 * open)) * 10f).roundToInt()
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(0f, lift.toFloat())
        Ui.panel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT)
        val center = panelX + PANEL_WIDTH / 2
        Ui.centeredHeading(graphics, font, Component.translatable("gui.guildmark.homestead.ledger.title", data.ownerName), center, panelY + 9, Ui.GOLD_BRIGHT)
        graphics.centeredText(font, subtitle(), center, panelY + 24, Ui.TEXT_SOFT)
        val purse = Component.translatable("gui.guildmark.homestead.ledger.purse", grouped(data.purse))
        graphics.text(font, purse, panelX + PANEL_WIDTH - 12 - font.width(purse), panelY + 9, Ui.GOLD, false)
        Ui.divider(graphics, panelX + 12, panelY + 36, PANEL_WIDTH - 24)

        drawMap(graphics, panelX + 12, panelY + 43, time, mouseX, mouseY - lift)
        drawBuild(graphics, panelX + MAP_SIZE + 24, panelY + 44, time)
        drawMaterials(graphics, panelX + MAP_SIZE + 24, panelY + 98, time, mouseX, mouseY - lift)
        if (!data.owner) {
            val note = Component.translatable("gui.guildmark.homestead.ledger.party", data.ownerName)
            graphics.centeredText(font, note, center, panelY + PANEL_HEIGHT - 38, Ui.TEXT_FAINT)
        }
        pose.popMatrix()
        // The buttons stay where they're clicked
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val asking = confirmUntil - time
        relinquish?.let { b ->
            if (asking > 0) {
                val w = (b.width * (asking / CONFIRM_TICKS)).roundToInt().coerceIn(0, b.width)
                graphics.fill(b.x, b.y + b.height, b.x + w, b.y + b.height + 2, ARGB.color(0.9f, Ui.RED))
            }
        }
        commission?.let { b ->
            if (b.isHovered && !b.active) whyNot()?.let { graphics.setTooltipForNextFrame(font, it, mouseX, mouseY) }
        }
    }

    private fun subtitle(): Component {
        val tier = tierName(data.tier)
        return when {
            data.building > data.tier -> Component.translatable("gui.guildmark.homestead.ledger.raising", tier, tierName(data.building))
            HomesteadTiers.of(data.tier + 1) == null -> Component.translatable("gui.guildmark.homestead.ledger.complete", tier)
            data.tier == 0 -> Component.translatable("gui.guildmark.homestead.ledger.staked")
            else -> Component.translatable("gui.guildmark.homestead.ledger.standing", tier)
        }
    }

    /** Why the commission button can't be pressed, for its tooltip. */
    private fun whyNot(): Component? {
        val next = HomesteadTiers.of(data.tier + 1) ?: return null
        return when {
            !data.owner -> Component.translatable("gui.guildmark.homestead.ledger.why.owner", data.ownerName)
            data.building > data.tier -> Component.translatable("gui.guildmark.homestead.ledger.why.building")
            data.purse < next.marks -> Component.translatable("gui.guildmark.homestead.ledger.why.marks", next.marks - data.purse)
            else -> null
        }
    }

    // ---- The plot from above ----

    private fun mapTexture(): DynamicTexture {
        val existing = map
        val r = PlotShape.RESERVED
        val texture = existing ?: DynamicTexture({ "Guildmark homestead ledger map" }, NativeImage(r.width, r.depth, true)).also { map = it }
        if (mapStale || existing == null) {
            mapStale = false
            val image = texture.pixels ?: return texture
            for (z in r.z0..r.z1) for (x in r.x0..r.x1) {
                val packed = data.map.getOrElse((z - r.z0) * r.width + (x - r.x0)) { 0 }.toInt() and 0xFF
                val color = if (packed shr 2 == 0) PAPER else MapColor.getColorFromPackedId(packed)
                // Seen from above with the road at the foot of the page: the plan's x runs right to left
                image.setPixel(r.x1 - x, r.z1 - z, ARGB.srgbLerp(0.18f, color or (0xFF shl 24), PAPER))
            }
            texture.upload()
        }
        return texture
    }

    private fun drawMap(graphics: GuiGraphicsExtractor, x: Int, y: Int, time: Float, mouseX: Int, mouseY: Int) {
        Ui.inset(graphics, x - 2, y - 2, MAP_SIZE + 4, MAP_SIZE + 4)
        val r = PlotShape.RESERVED
        val scale = MAP_SIZE / r.width
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        graphics.blit(mapTexture().textureView, sampler, x, y, x + r.width * scale, y + r.depth * scale, 0f, 1f, 0f, 1f)
        fun px(planX: Int): Int = x + (r.x1 - planX) * scale
        fun py(planZ: Int): Int = y + (r.z1 - planZ) * scale
        fun rect(rect: io.github.vaspyyy.guildmark.city.plan.Rect, color: Int) {
            val x0 = px(rect.x1)
            val x1 = px(rect.x0) + scale
            val y0 = py(rect.z1)
            val y1 = py(rect.z0) + scale
            graphics.outline(x0, y0, x1 - x0, y1 - y0, color)
        }
        // The tier it stands at, and the one going up pulsing round it
        rect(PlotShape.plot(max(1, data.tier)), ARGB.color(0.95f, Ui.GOLD))
        if (data.building > data.tier && PlotShape.plot(data.building) != PlotShape.plot(max(1, data.tier))) {
            val pulse = 0.45f + 0.4f * sin(time * 0.2f)
            rect(PlotShape.plot(data.building), ARGB.color(pulse, Ui.GOLD_BRIGHT))
        }
        // The post and the supply crate at the gate
        val glint = 0.7f + 0.3f * sin(time * 0.3f)
        graphics.fill(px(PlotShape.POST_X), py(0), px(PlotShape.POST_X) + scale, py(0) + scale, ARGB.color(glint, Ui.GOLD_BRIGHT))
        graphics.fill(px(PlotShape.CRATE_X), py(0), px(PlotShape.CRATE_X) + scale, py(0) + scale, ARGB.color(1f, 0x8A5A2E))
        val frame = PlotFrame(data.post, Direction.from2DDataValue(data.facing))
        // The crew at work, and whoever is reading this
        val level = minecraft.level
        if (level != null) {
            val box = frame.box(r, data.post.y - 24, data.post.y + 40)
            val area = AABB(box.minX().toDouble(), box.minY().toDouble(), box.minZ().toDouble(), box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0)
            for (p in level.getEntitiesOfClass(Townsperson::class.java, area)) dot(graphics, frame, p.x, p.z, x, y, scale, 0xFF8FCB7A.toInt(), 1)
            minecraft.player?.let { dot(graphics, frame, it.x, it.z, x, y, scale, if ((time / 8).toInt() % 2 == 0) -1 else 0xFFD9B46A.toInt(), 1) }
        }
        // Whatever holds the build up, blinking
        data.blocked?.let { at ->
            if ((time / 6).toInt() % 2 == 0) {
                val bx = px(frame.localX(at.x, at.z))
                val by = py(frame.localZ(at.x, at.z))
                graphics.fill(bx - 1, by - 1, bx + scale + 1, by + scale + 1, ARGB.color(1f, Ui.RED))
            }
        }
        if (mouseX >= x && mouseX < x + MAP_SIZE && mouseY >= y && mouseY < y + MAP_SIZE) {
            graphics.setTooltipForNextFrame(font, Component.translatable("gui.guildmark.homestead.ledger.map_tip"), mouseX, mouseY)
        }
        // Under the map, the plot's size at this tier
        val plot = PlotShape.plot(max(data.tier, max(1, data.building)))
        graphics.centeredText(font, Component.translatable("gui.guildmark.homestead.ledger.plot", plot.width, plot.depth), x + MAP_SIZE / 2, y + MAP_SIZE + 5, Ui.TEXT_FAINT)
    }

    private fun dot(graphics: GuiGraphicsExtractor, frame: PlotFrame, wx: Double, wz: Double, x: Int, y: Int, scale: Int, color: Int, size: Int) {
        val r = PlotShape.RESERVED
        val lx = frame.localX(kotlin.math.floor(wx).toInt(), kotlin.math.floor(wz).toInt())
        val lz = frame.localZ(kotlin.math.floor(wx).toInt(), kotlin.math.floor(wz).toInt())
        if (!r.contains(lx, lz)) return
        val px = x + (r.x1 - lx) * scale + scale / 2
        val py = y + (r.z1 - lz) * scale + scale / 2
        graphics.fill(px - size, py - size, px + size + 1, py + size + 1, color)
    }

    // ---- The build ----

    private fun drawBuild(graphics: GuiGraphicsExtractor, x: Int, y: Int, time: Float) {
        val w = PANEL_WIDTH - MAP_SIZE - 36
        val building = data.building > data.tier
        val next = HomesteadTiers.of(data.tier + 1)
        // The stage, crossfading when it changes
        val stage = Stage.entries.getOrNull(data.stage)
        val since = min(1f, (time - stageAt) / 10f)
        val label = Component.translatable("gui.guildmark.homestead.ledger.stage")
        graphics.text(font, label, x, y, Ui.TEXT_FAINT, false)
        val lx = x + font.width(label) + 4
        if (since < 1f) Stage.entries.getOrNull(stageBefore)?.let {
            graphics.text(font, Component.translatable("gui.guildmark.homestead.stage.${it.key}"), lx, y - (since * 4).roundToInt(), ARGB.color(1f - since, Ui.TEXT), false)
        }
        val stageText = when {
            stage != null && building -> Component.translatable("gui.guildmark.homestead.stage.${stage.key}")
            next == null -> Component.translatable("gui.guildmark.homestead.ledger.stage_done")
            else -> Component.translatable("gui.guildmark.homestead.ledger.stage_none")
        }
        graphics.text(font, stageText, lx, y + ((1f - since) * 4).roundToInt(), ARGB.color(since, Ui.TEXT), false)
        val shown = progressO + (progress - progressO) * minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)
        if (building) {
            val percent = "${(shown.coerceIn(0f, 1f) * 100).roundToInt()}%"
            graphics.text(font, percent, x + w - font.width(percent), y, Ui.GOLD, false)
        }
        bar(graphics, x, y + 11, w, 7, if (building) shown else if (next == null) 1f else 0f, Ui.GOLD, time)

        // The crew, or what holds them up
        val (line, color) = when {
            !building && next == null -> Component.translatable("gui.guildmark.homestead.ledger.nothing_more") to Ui.TEXT_SOFT
            !building -> Component.translatable("gui.guildmark.homestead.ledger.next", tierName(next!!.n), grouped(next.marks)) to Ui.TEXT_SOFT
            data.blocked != null -> Component.translatable("gui.guildmark.homestead.ledger.blocked") to Ui.RED
            data.short >= 0 -> Component.translatable("gui.guildmark.homestead.ledger.short", stuffName(data.short)) to Ui.RED
            data.working > 0 -> Component.translatable("gui.guildmark.homestead.ledger.crew", data.working, data.crew) to Ui.GREEN
            else -> Component.translatable("gui.guildmark.homestead.ledger.crew_away", data.crew) to Ui.TEXT_SOFT
        }
        graphics.text(font, fit(line, w), x, y + 23, color, false)
        // The daylight work left, or what the next tier would take
        val eta = data.eta
        val hours = eta / 1000.0
        val days = eta / HomesteadTiers.WORKDAY.toDouble()
        val time2 = when {
            !building && next == null -> null
            building && eta <= 300 -> Component.translatable("gui.guildmark.homestead.ledger.eta_soon")
            hours < 11.5 -> Component.translatable(if (building) "gui.guildmark.homestead.ledger.eta_hours" else "gui.guildmark.homestead.ledger.takes_hours", max(1, hours.roundToInt()))
            else -> Component.translatable(if (building) "gui.guildmark.homestead.ledger.eta_days" else "gui.guildmark.homestead.ledger.takes_days", half(days))
        }
        time2?.let { graphics.text(font, fit(it, w), x, y + 34, Ui.TEXT_FAINT, false) }
        Ui.divider(graphics, x, y + 47, w, gem = false)
    }

    // ---- Materials ----

    private fun drawMaterials(graphics: GuiGraphicsExtractor, x: Int, y: Int, time: Float, mouseX: Int, mouseY: Int) {
        val w = PANEL_WIDTH - MAP_SIZE - 36
        val building = data.building > data.tier
        val next = HomesteadTiers.of(data.tier + 1)
        if (!building && next == null) return
        graphics.text(font, Component.translatable("gui.guildmark.homestead.ledger.materials"), x, y, Ui.GOLD, false)
        val head = Component.translatable("gui.guildmark.homestead.ledger.in_crate")
        graphics.text(font, head, x + w - font.width(head), y, Ui.TEXT_FAINT, false)
        val partial = minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)
        for ((i, stuff) in Stuff.PAID.withIndex()) {
            val ry = y + 11 + i * ROW
            val bill = data.bill.getOrElse(i) { 0 }
            val left = data.left.getOrElse(i) { 0 }
            val crate = data.crate.getOrElse(i) { 0 }
            if (i % 2 == 1) graphics.fill(x - 3, ry - 1, x + w + 3, ry + ROW - 1, STRIPE)
            graphics.item(ICONS[i], x, ry - 1)
            graphics.text(font, stuffName(i), x + 19, ry + 3, Ui.TEXT, false)
            val f = (doneO[i] + (done[i] - doneO[i]) * partial).coerceIn(0f, 1f)
            val barX = x + 64
            val barW = w - 64 - 58
            val short = building && data.short == i
            bar(graphics, barX, ry + 4, barW, 5, f, if (short) Ui.RED else Ui.GREEN, time + i * 7)
            val count = "${grouped(bill - left)}/${grouped(bill)}"
            graphics.text(font, count, barX + barW + 4, ry + 3, Ui.TEXT_SOFT, false)
            // What's in the crate: enough for the rest (green), some (gold), none while some's still to come (red)
            val crateColor = when {
                left <= 0 -> Ui.TEXT_FAINT
                crate >= left -> Ui.GREEN
                crate > 0 -> Ui.GOLD
                else -> if ((time / 10).toInt() % 2 == 0 || !short) Ui.RED else Ui.TEXT_FAINT
            }
            val c = grouped(crate)
            graphics.text(font, c, x + w - font.width(c), ry + 3, crateColor, false)
            if (mouseX >= x && mouseX < x + w && mouseY >= ry - 1 && mouseY < ry + ROW - 1) {
                val tip = listOf(
                    Component.translatable("gui.guildmark.homestead.stuff.${stuff.key}"),
                    Component.translatable("gui.guildmark.homestead.ledger.tip.${stuff.key}"),
                    Component.translatable("gui.guildmark.homestead.ledger.tip.counts", grouped(bill), grouped(left), grouped(crate)),
                )
                graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY)
            }
        }
    }

    /** A bar [f] full, with a slow gleam running along the filled part. */
    private fun bar(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, f: Float, color: Int, time: Float) {
        graphics.fill(x, y, x + w, y + h, 0xC0100E12.toInt())
        graphics.outline(x - 1, y - 1, w + 2, h + 2, 0x60D9B46A)
        val filled = (w * f.coerceIn(0f, 1f)).roundToInt()
        if (filled <= 0) return
        graphics.fillGradient(x, y, x + filled, y + h, ARGB.color(1f, ARGB.srgbLerp(0.25f, color, -1)), ARGB.color(1f, ARGB.scaleRGB(color, 0.62f)))
        // The gleam
        val period = w + 24
        val at = ((time * 1.6f) % period).roundToInt() - 12
        val g0 = max(x, x + at)
        val g1 = min(x + filled, x + at + 10)
        if (g1 > g0) graphics.fill(g0, y, g1, y + h, 0x30FFFFFF)
    }

    // ---- Words ----

    private fun tierName(n: Int): Component = Component.translatable("gui.guildmark.homestead.tier.${HomesteadTiers.key(n)}")

    private fun stuffName(i: Int): Component = Component.translatable("gui.guildmark.homestead.stuff.${Stuff.PAID.getOrElse(i) { Stuff.TIMBER }.key}")

    private fun grouped(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    /** [text] cut to [width] pixels. */
    private fun fit(text: Component, width: Int): FormattedCharSequence = Language.getInstance().getVisualOrder(font.substrByWidth(text, width))

    /** Days to the nearest half: "1", "1.5", "2". */
    private fun half(days: Double): String {
        val h = max(0.5, (days * 2).roundToInt() / 2.0)
        return if (abs(h - h.toInt()) < 0.01) h.toInt().toString() else String.format(Locale.ROOT, "%.1f", h)
    }

    companion object {
        private const val PANEL_WIDTH = 318
        private const val PANEL_HEIGHT = 226
        /** The map: the charter's whole land, two pixels a column. */
        private const val MAP_SIZE = 98
        private const val ROW = 17
        private const val REACH = 10.0
        private const val REFRESH_TICKS = 40
        private const val CONFIRM_TICKS = 80
        private val STRIPE = 0x18D9B46A
        /** Bare parchment where the map knows nothing. */
        private val PAPER = 0xFFD8C9A0.toInt()

        private val ICONS = listOf(
            ItemStack(Items.OAK_PLANKS), ItemStack(Items.COBBLESTONE), ItemStack(Items.GLASS), ItemStack(Items.IRON_INGOT),
            ItemStack(Items.WOOL.pick(DyeColor.WHITE)),
        )
    }
}
