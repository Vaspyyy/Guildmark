package io.github.vaspyyy.guildmark.client.homestead

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.GoodsList
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
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.Direction
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
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
 * tiers' bounds, the shipping crates and whoever is at work on it, and two pages, each with its ribbon to turn to it.
 * The build's page: the tier and the one going up, how the build stands (its stage, the crew, the daylight work left),
 * and the materials (what the tier takes, what's still to come of it and what's in the supply crate). The shipping
 * page: the Marks the carters have left in the post's purse, where the goods go and when the carter next comes, what's
 * in the crates, and the latest shipments. The owner commissions the next tier here, empties the purse, or gives the
 * charter up (twice asked). It keeps itself up to date while it's open, and everything in it eases to its new value.
 */
class HomesteadLedgerScreen(private var data: LedgerPayload) : Screen(Component.translatable("gui.guildmark.homestead.ledger")) {
    val id: Int get() = data.id

    private var ticks = 0
    private var panelX = 0
    private var panelY = 0

    /** The page open on the right ([BUILD] or [SHIPPING]), when it was turned to, and each ribbon's length as it eases. */
    private var page = lastPage
    private var pageAt = -100
    private val ribbon = floatArrayOf(-1f, -1f)
    private val ribbonO = FloatArray(2)

    /** The post's purse as shown, easing to the ledger's; Marks just come in, and coins thrown up as it's emptied. */
    private var purse = -1f
    private var purseO = 0f
    private var gained = 0
    private var gainedAt = -100
    private val coins = ArrayList<Coin>()

    /** A coin thrown up out of the purse as it's emptied: when, and which way. */
    private class Coin(val at: Int, val vx: Float, val vy: Float)

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
    private var collect: Ui.KitButton? = null
    /** Until when a second press of the relinquish button gives the charter up. */
    private var confirmUntil = -1

    fun update(payload: LedgerPayload) {
        if (!payload.map.contentEquals(data.map)) mapStale = true
        if (payload.stage != data.stage) {
            stageBefore = data.stage
            stageAt = ticks
        }
        val was = data.shipping.purse
        val now = payload.shipping.purse
        if (now < was) {
            // Emptied: the coins fly, a few more the more there were
            repeat(min(10, 3 + (was - now) / 8)) { i ->
                coins.add(Coin(ticks + i / 2, (i % 5 - 2) * 0.55f + ((i * 37) % 7 - 3) * 0.06f, -2.2f - (i * 13 % 5) * 0.25f))
            }
        } else if (now > was) {
            gained = now - was
            gainedAt = ticks
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
        collect = addRenderableWidget(Ui.button(Component.empty(), 150) { send(HomesteadLedger.COLLECT) }.also { it.setPosition(center - 154, footY) })
        refresh()
    }

    private fun refresh() {
        val shipping = page == SHIPPING
        commission?.visible = !shipping
        relinquish?.visible = !shipping
        collect?.let {
            val marks = data.shipping.purse
            it.visible = shipping
            it.message = if (marks > 0) Component.translatable("gui.guildmark.homestead.ship.collect", grouped(marks)) else Component.translatable("gui.guildmark.homestead.ship.collect_none")
            it.active = data.owner && marks > 0
        }
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
        round?.visible = !shipping && data.blocked != null
    }

    /** Turns to page [to] (the ribbon's click). */
    private fun turn(to: Int) {
        if (to == page) return
        page = to
        lastPage = to
        pageAt = ticks
        confirmUntil = -1
        Ui.page()
        refresh()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (i in 0 until 2) {
                if (onRibbon(i, event.x(), event.y())) {
                    turn(i)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
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
        // The open page's ribbon hangs longer; the purse counts its way to what's in it
        for (i in ribbon.indices) {
            ribbonO[i] = ribbon[i]
            val want = if (i == page) RIBBON_OPEN else RIBBON_SHUT
            ribbon[i] = if (ribbon[i] < 0f) want else ribbon[i] + (want - ribbon[i]) * 0.35f
        }
        purseO = purse
        val marks = data.shipping.purse.toFloat()
        purse = if (purse < 0f) marks else if (abs(marks - purse) < 0.5f) marks else purse + (marks - purse) * 0.25f
        coins.removeIf { ticks - it.at > COIN_TICKS }
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
        // The page turned to slides in from the right
        val turned = min(1f, (time - pageAt) / 6f)
        val slide = ((1f - turned) * (1f - turned) * 12f).roundToInt()
        pose.pushMatrix()
        pose.translate(slide.toFloat(), 0f)
        if (page == SHIPPING) {
            drawShipping(graphics, panelX + MAP_SIZE + 24, panelY + 44, time, mouseX - slide, mouseY - lift)
        } else {
            drawBuild(graphics, panelX + MAP_SIZE + 24, panelY + 44, time)
            drawMaterials(graphics, panelX + MAP_SIZE + 24, panelY + 98, time, mouseX - slide, mouseY - lift)
        }
        pose.popMatrix()
        if (!data.owner) {
            val note = Component.translatable(if (page == SHIPPING) "gui.guildmark.homestead.ship.party" else "gui.guildmark.homestead.ledger.party", data.ownerName)
            graphics.centeredText(font, note, center, panelY + PANEL_HEIGHT - 38, Ui.TEXT_FAINT)
        }
        pose.popMatrix()
        // The buttons and the ribbons stay where they're clicked
        super.extractRenderState(graphics, mouseX, mouseY, a)
        drawRibbons(graphics, mouseX, mouseY, a, time)
        drawCoins(graphics, time)
        val asking = confirmUntil - time
        relinquish?.let { b ->
            if (asking > 0 && b.visible) {
                val w = (b.width * (asking / CONFIRM_TICKS)).roundToInt().coerceIn(0, b.width)
                graphics.fill(b.x, b.y + b.height, b.x + w, b.y + b.height + 2, ARGB.color(0.9f, Ui.RED))
            }
        }
        commission?.let { b ->
            if (b.visible && b.isHovered && !b.active) whyNot()?.let { graphics.setTooltipForNextFrame(font, it, mouseX, mouseY) }
        }
        collect?.let { b ->
            if (b.visible && b.isHovered && !b.active) {
                val why = if (!data.owner) Component.translatable("gui.guildmark.homestead.ship.why.owner", data.ownerName) else Component.translatable("gui.guildmark.homestead.ship.why.empty")
                graphics.setTooltipForNextFrame(font, font.split(why, TIP_WIDTH), mouseX, mouseY)
            }
        }
        for (i in 0 until 2) {
            if (onRibbon(i, mouseX.toDouble(), mouseY.toDouble())) {
                graphics.setTooltipForNextFrame(font, Component.translatable(if (i == SHIPPING) "gui.guildmark.homestead.ship.ribbon" else "gui.guildmark.homestead.ledger.ribbon"), mouseX, mouseY)
            }
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
        // The shipping crates, glinting while the shipping page is open
        for (crate in data.shipping.crates) {
            val lx = frame.localX(crate.x, crate.z)
            val lz = frame.localZ(crate.x, crate.z)
            if (!r.contains(lx, lz)) continue
            val cx = px(lx)
            val cy = py(lz)
            if (page == SHIPPING) {
                val pulse = 0.35f + 0.35f * sin(time * 0.22f + lx * 0.7f)
                graphics.fill(cx - 1, cy - 1, cx + scale + 1, cy + scale + 1, ARGB.color(pulse, Ui.GOLD_BRIGHT))
            }
            graphics.fill(cx, cy, cx + scale, cy + scale, ARGB.color(1f, CRATE))
        }
        // The crew at work, a carter come for the crates, and whoever is reading this
        val level = minecraft.level
        if (level != null) {
            val box = frame.box(r, data.post.y - 24, data.post.y + 40)
            val area = AABB(box.minX().toDouble(), box.minY().toDouble(), box.minZ().toDouble(), box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0)
            for (p in level.getEntitiesOfClass(Townsperson::class.java, area)) dot(graphics, frame, p.x, p.z, x, y, scale, 0xFF8FCB7A.toInt(), 1)
            if (data.shipping.carter >= 0) level.getEntity(data.shipping.carter)?.let {
                dot(graphics, frame, it.x, it.z, x, y, scale, if ((time / 10).toInt() % 2 == 0) CARTER else ARGB.scaleRGB(CARTER, 0.7f), 1)
            }
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
            val tip = if (page == SHIPPING) "gui.guildmark.homestead.ship.map_tip" else "gui.guildmark.homestead.ledger.map_tip"
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable(tip), TIP_WIDTH), mouseX, mouseY)
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

    // ---- Shipping ----

    /** The shipping page: the post's purse, where the goods go and when, what's in the crates, and the latest shipments. */
    private fun drawShipping(graphics: GuiGraphicsExtractor, x: Int, y: Int, time: Float, mouseX: Int, mouseY: Int) {
        val w = PANEL_WIDTH - MAP_SIZE - 36
        val s = data.shipping
        // Where the goods go
        val title = Component.translatable("gui.guildmark.homestead.ship.title")
        graphics.text(font, title, x, y, Ui.GOLD, false)
        val to = when {
            !s.on -> Component.translatable("gui.guildmark.homestead.ship.to_off")
            !s.reach -> Component.translatable("gui.guildmark.homestead.ship.to_none")
            else -> Component.translatable("gui.guildmark.homestead.ship.to", place(s.place))
        }
        val toText = fit(to, w - font.width(title) - 8)
        graphics.text(font, toText, x + w - font.width(toText), y, if (s.on && s.reach) Ui.TEXT_SOFT else Ui.TEXT_FAINT, false)

        // The post's purse, counting its way to what's in it, a coin glinting by it while there's something
        val shown = if (purse < 0f) s.purse else Mth.lerp(minecraft.deltaTracker.getGameTimeDeltaPartialTick(false), purseO, purse).roundToInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, COIN, x, y + 12, 7, 7, ARGB.white(if (s.purse > 0) 0.75f + 0.25f * sin(time * 0.3f) else 0.4f))
        val purseText = Component.translatable("gui.guildmark.homestead.ship.purse", grouped(shown))
        graphics.text(font, purseText, x + 10, y + 12, if (s.purse > 0) Ui.GOLD_BRIGHT else Ui.TEXT_SOFT, false)
        val since = time - gainedAt
        if (gained > 0 && since < GAIN_TICKS) {
            // Marks just come in rise off it
            val k = since / GAIN_TICKS
            graphics.text(font, "+${grouped(gained)}", x + 14 + font.width(purseText), y + 12 - (k * 8).roundToInt(), ARGB.color(1f - k * k, Ui.GREEN), true)
        }

        // When the carter comes
        val hours = (s.next + 999) / 1000
        val (line, color) = when {
            !s.on -> Component.translatable("gui.guildmark.homestead.ship.when_off") to Ui.TEXT_FAINT
            !s.reach -> Component.translatable("gui.guildmark.homestead.ship.when_none") to Ui.TEXT_FAINT
            s.carter >= 0 -> Component.translatable("gui.guildmark.homestead.ship.carter_here") to Ui.GREEN
            s.next <= 0 -> Component.translatable("gui.guildmark.homestead.ship.due") to Ui.TEXT_SOFT
            hours <= 1 -> Component.translatable("gui.guildmark.homestead.ship.next_soon") to Ui.TEXT_SOFT
            else -> Component.translatable("gui.guildmark.homestead.ship.next", hours) to Ui.TEXT_SOFT
        }
        graphics.text(font, fit(line, w), x, y + 23, color, false)
        if (s.on && !s.reach && mouseX >= x && mouseX < x + w && mouseY >= y + 22 && mouseY < y + 32) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("gui.guildmark.homestead.ship.why.reach"), TIP_WIDTH), mouseX, mouseY)
        }
        Ui.divider(graphics, x, y + 35, w, gem = false)

        // What's in the crates: each good the market deals in, with how many (bobbing while the carter sorts them)
        graphics.text(font, Component.translatable("gui.guildmark.homestead.ship.in_crates"), x, y + 41, Ui.GOLD, false)
        val count = Component.translatable(if (s.crates.size == 1) "gui.guildmark.homestead.ship.crate" else "gui.guildmark.homestead.ship.crates", s.crates.size)
        graphics.text(font, count, x + w - font.width(count), y + 41, Ui.TEXT_FAINT, false)
        val goods = Good.entries.filter { s.goods.getOrElse(it.ordinal) { 0 } > 0 }
        val iy = y + 52
        when {
            s.crates.isEmpty() -> graphics.text(font, fit(Component.translatable("gui.guildmark.homestead.ship.no_crates"), w), x, iy + 4, Ui.TEXT_FAINT, false)
            goods.isEmpty() -> graphics.text(font, fit(Component.translatable("gui.guildmark.homestead.ship.empty"), w), x, iy + 4, Ui.TEXT_FAINT, false)
            else -> for ((i, good) in goods.take(w / ICON_STEP).withIndex()) {
                val gx = x + i * ICON_STEP
                val n = s.goods[good.ordinal]
                val bob = if (s.carter >= 0) (sin(time * 0.4f + i * 1.3f) * 1.2f).roundToInt() else 0
                good.item?.let { graphics.item(ItemStack(it), gx, iy + bob) }
                val shownCount = if (n >= 10_000) "${n / 1000}k" else n.toString()
                graphics.text(font, shownCount, gx + 17 - font.width(shownCount), iy + 9 + bob, Ui.TEXT, true)
                if (mouseX >= gx && mouseX < gx + 16 && mouseY >= iy && mouseY < iy + 16) {
                    graphics.setComponentTooltipForNextFrame(font, listOf(GoodsList.one(good, n), Component.translatable("gui.guildmark.homestead.ship.tip.goes")), mouseX, mouseY)
                }
            }
        }
        if (s.others > 0) {
            graphics.text(font, fit(Component.translatable("gui.guildmark.homestead.ship.others", grouped(s.others)), w), x, y + 71, Ui.TEXT_FAINT, false)
        }
        Ui.divider(graphics, x, y + 83, w, gem = false)

        // The latest shipments, newest first
        graphics.text(font, Component.translatable("gui.guildmark.homestead.ship.last"), x, y + 89, Ui.GOLD, false)
        val bottom = y + if (data.owner) NOTES_END else NOTES_END - 10
        var ly = y + 100
        if (s.notes.isEmpty()) graphics.text(font, fit(Component.translatable("gui.guildmark.homestead.ship.none_yet"), w), x, ly, Ui.TEXT_FAINT, false)
        for ((i, note) in s.notes.withIndex()) {
            for (part in font.split(note, w)) {
                if (ly + 9 > bottom) return
                graphics.text(font, part, x, ly, if (i == 0) Ui.TEXT_SOFT else Ui.TEXT_FAINT, false)
                ly += 10
            }
            ly += 2
        }
    }

    // ---- The ribbons ----

    private fun ribbonX(i: Int): Int = panelX + RIBBON_X + i * RIBBON_STEP

    /** Whether ([mx], [my]) is on ribbon [i], as far as it hangs. */
    private fun onRibbon(i: Int, mx: Double, my: Double): Boolean {
        val x = ribbonX(i)
        val y = panelY + RIBBON_TOP
        return mx >= x && mx < x + RIBBON_W && my >= y && my < y + max(ribbon[i], RIBBON_SHUT) + 2
    }

    /** The pages' ribbons at the top of the ledger, in the homestead's colour: the open page's hangs longer, the other a little lower when pointed at. */
    private fun drawRibbons(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float, time: Float) {
        val color = HomesteadClient.homesteads.firstOrNull { it.id == data.id }?.color ?: 14
        val tint = Dye.entries[color.coerceIn(0, Dye.entries.size - 1)].rgb
        for (i in 0 until 2) {
            val x = ribbonX(i)
            val y = panelY + RIBBON_TOP
            val open = i == page
            val pointed = !open && onRibbon(i, mouseX.toDouble(), mouseY.toDouble())
            val length = (Mth.lerp(a, ribbonO[i], ribbon[i]) + if (pointed) 2f else 0f).roundToInt().coerceIn(SIGN + 8, RIBBON_H)
            val v = RIBBON_H - length
            val paint = ARGB.color(1f, if (open) tint else ARGB.scaleRGB(tint, 0.68f))
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, RIBBON_PAINT, RIBBON_W, RIBBON_H, 0, v, x, y, RIBBON_W, length, paint)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, RIBBON, RIBBON_W, RIBBON_H, 0, v, x, y, RIBBON_W, length, -1)
            // In the shadow of the pages it comes out from between
            graphics.fillGradient(x + 1, y, x + RIBBON_W - 1, y + 3, ARGB.color(0.55f, 0), 0)
            // The page's sign just above the notch
            val sign = if (i == SHIPPING) SIGN_SHIPPING else SIGN_BUILD
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sign, x + (RIBBON_W - SIGN) / 2, y + length - SIGN - 6, SIGN, SIGN, ARGB.white(if (open) 1f else 0.7f))
            // Marks waiting in the purse: a coin glints at the shut shipping ribbon's tail
            if (i == SHIPPING && !open && data.shipping.purse > 0) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, COIN, x + (RIBBON_W - 7) / 2, y + length + 1, 7, 7, ARGB.white(0.55f + 0.45f * sin(time * 0.25f)))
            }
        }
    }

    /** Coins thrown up out of the purse as it's emptied, tumbling and fading. */
    private fun drawCoins(graphics: GuiGraphicsExtractor, time: Float) {
        if (coins.isEmpty() || page != SHIPPING) return
        val x0 = panelX + MAP_SIZE + 24 + 3.5f
        val y0 = panelY + 44 + 15.5f
        val pose = graphics.pose()
        for (c in coins) {
            val t = time - c.at
            if (t < 0f) continue
            val k = (t / COIN_TICKS).coerceIn(0f, 1f)
            pose.pushMatrix()
            pose.translate(x0 + c.vx * t * 1.5f, y0 + c.vy * t * 1.5f + 0.12f * t * t)
            pose.rotate(c.vx * t * 0.2f)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, COIN, -3, -3, 7, 7, ARGB.white(1f - k * k))
            pose.popMatrix()
        }
    }

    /** A capital's name for the page, or "the capital" if it couldn't be found. */
    private fun place(name: String): Component =
        if (name.isEmpty()) Component.translatable("gui.guildmark.homestead.ship.the_capital") else Component.literal(name)

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

        /** The pages, and the one the last ledger was left open at (the next opens there). */
        private const val BUILD = 0
        private const val SHIPPING = 1
        private var lastPage = BUILD

        /** The ribbons (art/scripts/stall_art.py): their size, where they hang, and how far shut and open. */
        private const val RIBBON_W = 11
        private const val RIBBON_H = 30
        private const val RIBBON_X = 14
        private const val RIBBON_STEP = 15
        private const val RIBBON_TOP = 3
        private const val RIBBON_SHUT = 18f
        private const val RIBBON_OPEN = 25f
        private const val SIGN = 7
        /** The shipping page: a good's icon and count, how far down the shipments may run, and how wide a tooltip runs. */
        private const val ICON_STEP = 20
        private const val NOTES_END = 150
        private const val TIP_WIDTH = 200
        /** How long Marks just come in rise, and how long a thrown coin flies. */
        private const val GAIN_TICKS = 30f
        private const val COIN_TICKS = 24
        private const val CRATE = 0xB07A3E
        private val CARTER = 0xFFE8913A.toInt()

        private fun sprite(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

        private val COIN = sprite("compass/lost_marks")
        private val RIBBON = sprite("homestead/ribbon")
        private val RIBBON_PAINT = sprite("homestead/ribbon_paint")
        private val SIGN_BUILD = sprite("homestead/sign_build")
        private val SIGN_SHIPPING = sprite("homestead/sign_shipping")
    }
}
