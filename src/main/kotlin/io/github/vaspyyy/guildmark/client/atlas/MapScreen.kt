package io.github.vaspyyy.guildmark.client.atlas

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.client.ContractTracker
import io.github.vaspyyy.guildmark.client.combat.CombatClient
import io.github.vaspyyy.guildmark.client.wardfire.TravelFade
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.AtlasMark
import io.github.vaspyyy.guildmark.network.CoachStop
import io.github.vaspyyy.guildmark.network.CoachStopsPayload
import io.github.vaspyyy.guildmark.network.CoachStopsRequestPayload
import io.github.vaspyyy.guildmark.network.RideCoachPayload
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.village.StandingTier
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The world map (M): the land you've seen, painted as watercolour on parchment, with the places in your
 * Atlas, the roads between them, your contracts, your party, your own pins and where you last fell.
 * Drag to move about, scroll to zoom, right-click to pin a spot. Standing in a village with a coach,
 * the places its roads lead are marked with a wheel: click one to ride there. Click a Wardfire you've
 * kindled to travel to it.
 */
class MapScreen : Screen(Component.translatable("gui.guildmark.map")) {
    private var centerX = 0.0
    private var centerZ = 0.0
    private var placed = false
    private var zoom = lastZoom
    private var scale = ZOOMS[lastZoom]
    /** While a zoom eases in, the world spot held under the cursor: world x and z, then screen x and y. */
    private var anchor: DoubleArray? = null
    /** Gliding back to the player: where to come to rest. */
    private var glide: DoubleArray? = null
    private var lastFrame = 0L

    // The map's window, inside the frame
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private val midX get() = (left + right) / 2.0
    private val midY get() = (top + bottom) / 2.0

    private var pressed = false
    private var dragged = 0.0
    private var spots: List<Spot> = listOf()
    private var hovered: Spot? = null
    private var legend = legendShown

    /** The pin being named, and whether it was only just placed (so Esc takes it away again). */
    private var naming: MapData.Pin? = null
    private var placing = false
    private var nameBox: EditBox? = null
    /** The coach stop or Wardfire being asked about. */
    private var riding: Spot? = null
    private val rideButtons = ArrayList<Ui.KitButton>()

    /** Something marked on the map, where it is on screen this frame, and what hovering it tells. */
    private class Spot(
        val x: Double,
        val z: Double,
        val icon: Identifier,
        val name: Component,
        val lines: List<Component>,
        val priority: Int,
        val tint: Int = -1,
        val label: Component? = null,
        /** The zoom from which its name is written on the map. */
        val labelFrom: Double = 99.0,
        /** Its name in the carved capitals, as towns are lettered. */
        val carved: Boolean = false,
        val coach: CoachStop? = null,
        /** A Wardfire to travel to: where it stands. */
        val wardfire: BlockPos? = null,
        val pin: MapData.Pin? = null,
        /** The colour of the guild holding it, for the wash of its lands (0 if nobody does). */
        val territory: Int = 0,
        /** Where the icon's middle sits above the spot: pins stand on their point. */
        val lift: Int = 0,
    ) {
        var sx = 0
        var sy = 0
    }

    override fun init() {
        left = SIDE + BORDER
        top = TOP + BORDER
        right = width - SIDE - BORDER
        bottom = height - BAR - BORDER
        if (!placed) {
            minecraft.player?.let {
                centerX = it.x
                centerZ = it.z
            }
            placed = true
        }
        // A resize clears the widgets: put back whatever was open
        naming?.let { openNameBox(it, placing, nameBox?.value ?: "") }
        riding?.let { openRide(it) }
    }

    override fun added() {
        super.added()
        stops = listOf()
        ClientPacketDistributor.sendToServer(CoachStopsRequestPayload)
        Ui.page()
    }

    override fun removed() {
        lastZoom = zoom
        legendShown = legend
        MapData.closed()
        super.removed()
    }

    override fun onClose() {
        Ui.page()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false


    /** Subtitles are drawn over this screen, not hidden under its shade. */

    override fun isInGameUi(): Boolean = true

    // ---- Where things are ----

    private fun screenX(x: Double): Double = midX + (x - centerX) * scale
    private fun screenY(z: Double): Double = midY + (z - centerZ) * scale
    private fun worldX(x: Double): Double = centerX + (x - midX) / scale
    private fun worldZ(y: Double): Double = centerZ + (y - midY) / scale

    private fun inMap(x: Double, y: Double): Boolean = x >= left && x < right && y >= top && y < bottom

    private fun flat(x0: Double, z0: Double, x1: Double, z1: Double): Double = hypot(x1 - x0, z1 - z0)

    private fun far(distance: Double): String = if (distance >= 1000) "%.1f km".format(distance / 1000) else "${distance.toInt()} m"

    /** The marked spot under the cursor: the nearest, the more important if two are as near. */
    private fun spotAt(x: Int, y: Int): Spot? = spots
        .filter { abs(it.sx - x) <= 6 && abs(it.sy + it.lift - y) <= 6 }
        .minWithOrNull(compareBy<Spot> { abs(it.sx - x) + abs(it.sy + it.lift - y) }.thenByDescending { it.priority })

    // ---- Moving about ----

    private fun animate() {
        val now = Util.getMillis()
        val dt = if (lastFrame == 0L) 0.0 else (now - lastFrame).coerceIn(0L, 100L) / 1000.0
        lastFrame = now
        if (naming == null && riding == null) {
            var dx = 0
            var dz = 0
            if (held(InputConstants.KEY_A) || held(InputConstants.KEY_LEFT)) dx--
            if (held(InputConstants.KEY_D) || held(InputConstants.KEY_RIGHT)) dx++
            if (held(InputConstants.KEY_W) || held(InputConstants.KEY_UP)) dz--
            if (held(InputConstants.KEY_S) || held(InputConstants.KEY_DOWN)) dz++
            if (dx != 0 || dz != 0) {
                centerX += dx * PAN * dt / scale
                centerZ += dz * PAN * dt / scale
                glide = null
            }
        }
        // Zooming eases between the steps, in even ratios
        val target = ZOOMS[zoom]
        if (scale != target) {
            scale = exp(ln(scale) + (ln(target) - ln(scale)) * (1 - exp(-dt * 14)))
            if (abs(scale / target - 1) < 0.003) scale = target
        }
        anchor?.let {
            centerX = it[0] - (it[2] - midX) / scale
            centerZ = it[1] - (it[3] - midY) / scale
            if (scale == target) anchor = null
        }
        glide?.let {
            val k = 1 - exp(-dt * 9)
            centerX += (it[0] - centerX) * k
            centerZ += (it[1] - centerZ) * k
            if (abs(it[0] - centerX) * scale < 0.5 && abs(it[1] - centerZ) * scale < 0.5) glide = null
        }
    }

    private fun held(key: Int): Boolean = InputConstants.isKeyDown(key)

    private fun zoomAt(steps: Int, x: Double, y: Double) {
        val next = (zoom + steps).coerceIn(0, ZOOMS.size - 1)
        if (next == zoom) return
        zoom = next
        anchor = doubleArrayOf(worldX(x), worldZ(y), x, y)
        glide = null
    }

    private fun home() {
        val player = minecraft.player ?: return
        glide = doubleArrayOf(player.x, player.z)
        anchor = null
    }

    // ---- Input ----

    override fun keyPressed(event: KeyEvent): Boolean {
        if (naming != null) {
            if (event.isEscape()) finishNaming(false)
            else if (event.isConfirmation()) finishNaming(true)
            else return super.keyPressed(event)
            return true
        }
        if (riding != null) {
            if (!event.isEscape()) return super.keyPressed(event)
            closeRide()
            return true
        }
        if (KEY.matches(event)) {
            onClose()
            return true
        }
        when (event.key()) {
            InputConstants.KEY_SPACE -> home()
            InputConstants.KEY_L -> {
                legend = !legend
                Ui.page()
            }
            InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> zoomAt(1, midX, midY)
            InputConstants.KEY_MINUS, KEY_NUMPAD_MINUS -> zoomAt(-1, midX, midY)
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (riding != null) return true
        if (naming != null) {
            // Clicking away from the name keeps it
            finishNaming(true)
            return true
        }
        if (!inMap(event.x(), event.y())) return false
        when (event.button()) {
            InputConstants.MOUSE_BUTTON_LEFT -> {
                pressed = true
                dragged = 0.0
                glide = null
            }
            InputConstants.MOUSE_BUTTON_RIGHT -> rightClick(event.x(), event.y())
            else -> return false
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (!pressed) return super.mouseDragged(event, dx, dy)
        centerX -= dx / scale
        centerZ -= dy / scale
        dragged += abs(dx) + abs(dy)
        anchor = null
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (!pressed || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseReleased(event)
        pressed = false
        // A click rather than a drag: act on what's under it
        if (dragged < 4) {
            val spot = spotAt(event.x().roundToInt(), event.y().roundToInt())
            val pin = spot?.pin
            when {
                spot?.coach != null -> openRide(spot)
                spot?.wardfire != null -> openRide(spot)
                pin != null -> openNameBox(pin, false, pin.name)
            }
        }
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (riding != null || scrollY == 0.0) return true
        val steps = if (scrollY > 0) 1 else -1
        if (inMap(x, y)) zoomAt(steps, x, y) else zoomAt(steps, midX, midY)
        return true
    }

    /** Right-click: take away the pin under the cursor, or put a new one there and name it. */
    private fun rightClick(x: Double, y: Double) {
        val pin = spotAt(x.roundToInt(), y.roundToInt())?.pin
        if (pin != null) {
            MapData.removePin(pin)
            Ui.close()
            return
        }
        val name = Component.translatable("gui.guildmark.map.pin.default", MapData.pins.size + 1).string
        val made = MapData.addPin(floor(worldX(x)).toInt(), floor(worldZ(y)).toInt(), name) ?: return
        Ui.select()
        openNameBox(made, true, "")
    }

    // ---- Naming a pin ----

    private fun openNameBox(pin: MapData.Pin, placing: Boolean, typed: String) {
        nameBox?.let { removeWidget(it) }
        naming = pin
        this.placing = placing
        val box = EditBox(font, 0, 0, NAME_WIDTH, 14, Component.translatable("gui.guildmark.map.pin.name"))
        box.setMaxLength(32)
        box.value = typed
        box.setHint(Component.translatable("gui.guildmark.map.pin.hint"))
        placeNameBox(box, pin)
        addRenderableWidget(box)
        setFocused(box)
        nameBox = box
    }

    /** Just under its pin, kept inside the map. */
    private fun placeNameBox(box: EditBox, pin: MapData.Pin) {
        val x = screenX(pin.x + 0.5).roundToInt()
        val y = screenY(pin.z + 0.5).roundToInt()
        box.setPosition((x - NAME_WIDTH / 2).coerceIn(left + 4, max(left + 4, right - NAME_WIDTH - 4)), (y + 6).coerceIn(top + 4, max(top + 4, bottom - 18)))
    }

    /** Done naming: [keep] what was typed, or (for a pin only just placed) take the pin away. */
    private fun finishNaming(keep: Boolean) {
        val pin = naming ?: return
        val typed = nameBox?.value?.trim().orEmpty()
        if (keep && typed.isNotEmpty()) MapData.renamePin(pin, typed)
        else if (!keep && placing) MapData.removePin(pin)
        nameBox?.let { removeWidget(it) }
        nameBox = null
        naming = null
        placing = false
        setFocused(null)
    }

    // ---- Taking a coach ----

    private fun openRide(spot: Spot) {
        val player = minecraft.player ?: return
        val fire = spot.wardfire
        val stop = spot.coach
        if (fire == null && stop == null) return
        closeRide()
        riding = spot
        val (x, y, w, h) = ridePanel()
        val go = if (fire != null) {
            Ui.button(Component.translatable("gui.guildmark.map.travel.go"), 80) {
                TravelFade.begin(fire)
                onClose()
            }.also { it.active = travelBlock(fire, player) == null }
        } else {
            Ui.button(Component.translatable("gui.guildmark.map.ride.go"), 80) {
                ClientPacketDistributor.sendToServer(RideCoachPayload(stop!!.x, stop.z))
                onClose()
            }.also { it.active = TownControl.marks(player) >= stop!!.fare }
        }
        go.setPosition(x + w / 2 - 84, y + h - 30)
        val stay = Ui.button(Component.translatable("gui.guildmark.map.ride.stay"), 80) { closeRide() }
        stay.setPosition(x + w / 2 + 4, y + h - 30)
        rideButtons.add(addRenderableWidget(go))
        rideButtons.add(addRenderableWidget(stay))
        Ui.select()
    }

    private fun closeRide() {
        for (button in rideButtons) removeWidget(button)
        rideButtons.clear()
        riding = null
    }

    private fun ridePanel(): IntArray {
        val w = RIDE_WIDTH
        val h = RIDE_HEIGHT
        return intArrayOf(width / 2 - w / 2, height / 2 - h / 2, w, h)
    }

    // ---- What's on the map ----

    private fun gather(player: Player): List<Spot> {
        val out = ArrayList<Spot>()
        val coaches = stops.toMutableList()
        for (mark in AtlasClient.marks()) {
            val x = mark.pos.x + 0.5
            val z = mark.pos.z + 0.5
            val name = AtlasClient.name(mark)
            val lines = ArrayList<Component>()
            if (mark.kind == AtlasPlace.VILLAGE) {
                val yours = mark.has(AtlasMark.YOURS)
                val icon = when {
                    yours -> HALL
                    mark.size > 7 -> CITY
                    mark.size > 5 -> TOWN
                    else -> VILLAGE
                }
                lines.add(VillageNames.size(mark.size))
                if (mark.holder.isNotEmpty()) {
                    val holder = Component.literal(mark.holder).withColor(mark.color and 0xFFFFFF)
                    lines.add(Component.translatable(if (yours) "gui.guildmark.map.tip.yours" else "gui.guildmark.map.tip.held", holder))
                }
                StandingTier.entries.getOrNull(mark.standing)?.let { lines.add(Component.translatable("gui.guildmark.map.tip.standing", it.title)) }
                if (mark.has(AtlasMark.HALL)) lines.add(Component.translatable("gui.guildmark.map.tip.hall"))
                val people = mark.people.mapNotNull { StoryCharacter.byId(it)?.title }
                if (people.isNotEmpty()) lines.add(Component.translatable("gui.guildmark.map.tip.people", ComponentUtils.formatList(people) { it }))
                // A coach from here stops at the village it's found nearest the road's end
                val coach = coaches.filter { flat(it.x + 0.5, it.z + 0.5, x, z) < 64 }.minByOrNull { flat(it.x + 0.5, it.z + 0.5, x, z) }
                coach?.let { coaches.remove(it) }
                if (coach == null && mark.has(AtlasMark.ROAD)) lines.add(Component.translatable("gui.guildmark.map.tip.road"))
                val major = yours || mark.size > 5
                out.add(Spot(
                    x, z, icon, name, lines, if (major) 2 else 1, label = name, labelFrom = if (major) 0.0 else 0.5, carved = true,
                    coach = coach, territory = if (mark.has(AtlasMark.HELD)) mark.color else 0,
                ))
            } else if (mark.kind == AtlasPlace.WARDFIRE) {
                lines.add(AtlasClient.kindName(mark))
                if (mark.has(AtlasMark.RESTED)) lines.add(Component.translatable("gui.guildmark.map.tip.rested"))
                out.add(Spot(x, z, WARDFIRE, name, lines, 2, label = name, labelFrom = 1.0, wardfire = mark.pos))
            } else {
                val throne = mark.kind == AtlasPlace.THRONE
                val icon = when {
                    throne || mark.has(AtlasMark.VAULT) -> VAULT
                    mark.has(AtlasMark.CLEARED) -> LAIR_CLEARED
                    else -> LAIR
                }
                lines.add(AtlasClient.kindName(mark))
                if (mark.has(AtlasMark.CLEARED)) lines.add(Component.translatable(if (throne) "gui.guildmark.map.tip.throne_fallen" else "gui.guildmark.map.tip.cleared"))
                else if (mark.has(AtlasMark.AWAKE)) lines.add(Component.translatable("gui.guildmark.map.tip.awake"))
                out.add(Spot(x, z, icon, name, lines, if (throne) 2 else 1, label = name, labelFrom = if (throne) 0.5 else 2.0))
            }
        }
        // Road ends at villages not in your Atlas (named if someone else has found them)
        for (stop in coaches) {
            val name = if (stop.village.isNotEmpty()) Component.literal(stop.village) else Component.translatable("gui.guildmark.map.road_end")
            out.add(Spot(stop.x + 0.5, stop.z + 0.5, COACH, name, listOf(), 2, coach = stop))
        }
        for (waypoint in ContractTracker.waypoints(player)) {
            val kind = when (waypoint.kind) {
                ContractTracker.STORY -> "story"
                ContractTracker.CHALLENGE -> "challenge"
                else -> "contract"
            }
            out.add(Spot(waypoint.pos.x + 0.5, waypoint.pos.z + 0.5, CONTRACT, waypoint.title, listOf(Component.translatable("gui.guildmark.map.tip.$kind")), 3))
        }
        for (pin in MapData.pins) {
            val name = Component.literal(pin.name)
            out.add(Spot(
                pin.x + 0.5, pin.z + 0.5, PIN, name, listOf(Component.translatable("gui.guildmark.map.tip.pin")), 3,
                tint = pin.color, label = name, labelFrom = 0.5, pin = pin, lift = -4,
            ))
        }
        for ((id, at) in AtlasClient.partyMates(player)) {
            out.add(Spot(at.x, at.z, PARTY, Component.literal(AtlasClient.mateName(id)), listOf(Component.translatable("gui.guildmark.map.tip.party")), 4))
        }
        val lost = AtlasClient.lostMarks(player)
        if (lost != null) {
            val (at, amount) = lost
            out.add(Spot(at.x + 0.5, at.z + 0.5, LOST_MARKS, Component.translatable("atlas.guildmark.lost_marks", amount),
                listOf(Component.translatable("gui.guildmark.map.tip.lost_marks")), 4))
        } else {
            AtlasClient.deathPoint(player)?.let {
                out.add(Spot(it.x + 0.5, it.z + 0.5, DEATH, Component.translatable("atlas.guildmark.death"), listOf(), 4))
            }
        }
        return out
    }

    // ---- Drawing ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        graphics.fill(0, 0, width, height, BACKDROP)
        minecraft.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val player = minecraft.player ?: return
        animate()
        spots = gather(player)
        for (spot in spots) {
            spot.sx = screenX(spot.x).coerceIn(-1e6, 1e6).roundToInt()
            spot.sy = screenY(spot.z).coerceIn(-1e6, 1e6).roundToInt()
        }
        hovered = if (riding == null && naming == null && !pressed && inMap(mouseX.toDouble(), mouseY.toDouble())) spotAt(mouseX, mouseY) else null

        Ui.panel(graphics, SIDE, TOP, width - 2 * SIDE, height - TOP - BAR)
        graphics.enableScissor(left, top, right, bottom)
        drawPaper(graphics)
        val ceiling = minecraft.level?.dimensionType()?.hasCeiling() == true
        if (!ceiling) drawLand(graphics)
        drawSpots(graphics, player, a)
        drawRose(graphics)
        drawScale(graphics)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, VIGNETTE, left, top, right - left, bottom - top)
        if (ceiling) note(graphics, Component.translatable("gui.guildmark.map.ceiling"), midY.toInt() - 4)
        else if (stops.isNotEmpty() && riding == null) note(graphics, Component.translatable("gui.guildmark.map.coach_hint"), top + 12)
        if (legend) drawLegend(graphics)
        graphics.disableScissor()
        drawTitle(graphics)
        drawBar(graphics, mouseX, mouseY)

        graphics.nextStratum()
        hovered?.let { drawTooltip(graphics, it, player, mouseX, mouseY) }
        riding?.let { drawRide(graphics, it, player) }
        val pin = naming
        val box = nameBox
        if (pin != null && box != null) placeNameBox(box, pin)
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    /** The parchment, moving with the map as it's dragged. */
    private fun drawPaper(graphics: GuiGraphicsExtractor) {
        val u = Math.floorMod((left - midX + centerX * scale).roundToLong(), PAPER_SIZE.toLong()).toFloat()
        val v = Math.floorMod((top - midY + centerZ * scale).roundToLong(), PAPER_SIZE.toLong()).toFloat()
        graphics.blit(RenderPipelines.GUI_TEXTURED, PARCHMENT, left, top, u, v, right - left, bottom - top, PAPER_SIZE, PAPER_SIZE)
    }

    private fun Double.roundToLong(): Long = Math.round(this)

    /** The land painted so far, the roads inked on it, and the wash of guild lands over that. */
    private fun drawLand(graphics: GuiGraphicsExtractor) {
        MapData.beginFrame()
        val zoomedOut = scale < 0.5 - 1e-6
        val tx0 = Math.floorDiv(floor(worldX(left.toDouble())).toInt(), MapData.TILE_BLOCKS)
        val tx1 = Math.floorDiv(floor(worldX(right.toDouble())).toInt(), MapData.TILE_BLOCKS)
        val tz0 = Math.floorDiv(floor(worldZ(top.toDouble())).toInt(), MapData.TILE_BLOCKS)
        val tz1 = Math.floorDiv(floor(worldZ(bottom.toDouble())).toInt(), MapData.TILE_BLOCKS)
        // Drawn in world blocks from a nearby corner, so far-off coordinates keep their precision
        val originX = tx0 * MapData.TILE_BLOCKS
        val originZ = tz0 * MapData.TILE_BLOCKS
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(screenX(originX.toDouble()).toFloat(), screenY(originZ.toDouble()).toFloat())
        pose.scale(scale.toFloat(), scale.toFloat())
        // Close up the painting is smoothed, like a wash; further out each sample stays crisp
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(if (scale >= 2.0) FilterMode.LINEAR else FilterMode.NEAREST)
        val budget = intArrayOf(TILE_BUDGET)
        val middleX = Math.floorDiv(floor(centerX).toInt(), MapData.TILE_BLOCKS)
        val middleZ = Math.floorDiv(floor(centerZ).toInt(), MapData.TILE_BLOCKS)
        val order = ArrayList<IntArray>()
        for (tx in tx0..tx1) for (tz in tz0..tz1) order.add(intArrayOf(tx, tz))
        // The middle of the view is painted first
        order.sortBy { abs(it[0] - middleX) + abs(it[1] - middleZ) }
        for ((tx, tz) in order) {
            val texture = MapData.tile(tx, tz, zoomedOut, budget) ?: continue
            val x = tx * MapData.TILE_BLOCKS - originX
            val z = tz * MapData.TILE_BLOCKS - originZ
            graphics.blit(texture.textureView, sampler, x, z, x + MapData.TILE_BLOCKS, z + MapData.TILE_BLOCKS, 0f, 1f, 0f, 1f)
        }
        for (spot in spots) {
            if (spot.territory == 0) continue
            val x = (spot.x - originX).toInt()
            val z = (spot.z - originZ).toInt()
            graphics.blit(RenderPipelines.GUI_TEXTURED, TERRITORY, x - LANDS, z - LANDS, 0f, 0f, LANDS * 2, LANDS * 2, 64, 64, 64, 64, ARGB.color(0.45f, spot.territory))
        }
        pose.popMatrix()
    }

    private fun showing(x: Int, y: Int, margin: Int): Boolean = x >= left - margin && x < right + margin && y >= top - margin && y < bottom + margin

    private fun drawSpots(graphics: GuiGraphicsExtractor, player: Player, a: Float) {
        val shown = spots.filter { showing(it.sx, it.sy, 12) }.sortedBy { it.priority }

        // Names first, the most important first, leaving out any that would cross one already written
        // or the map's own furniture; the icons then go over them
        val taken = ArrayList<IntArray>()
        taken.add(intArrayOf(right - 40, top + 2, right, top + 42))
        taken.add(intArrayOf(left, bottom - 22, left + 150, bottom))
        if (legend) taken.add(legendBox())
        if (stops.isNotEmpty()) taken.add(intArrayOf(left, top + 6, right, top + 26))
        var lit: Pair<Spot, IntArray>? = null
        for (spot in shown.sortedByDescending { it.priority }) {
            if (spot.label == null || spot.pin != null && spot.pin === naming) continue
            val box = labelBox(spot)
            if (spot === hovered) {
                lit = spot to box
                continue
            }
            if (scale < spot.labelFrom - 1e-6 || taken.any { box[0] < it[2] && it[0] < box[2] && box[1] < it[3] && it[1] < box[3] }) continue
            taken.add(box)
            drawLabel(graphics, spot, box, false)
        }
        for (spot in shown) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, spot.icon, spot.sx - 5, spot.sy + spot.lift - 5, ICON, ICON, spot.tint)
            if (spot.coach != null && spot.icon != COACH) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, COACH_BADGE, spot.sx + 4, spot.sy - 11, 7, 7)
        }
        lit?.let { drawLabel(graphics, it.first, it.second, true) }

        // Where you are, pointing the way you face
        val position = player.getPosition(a)
        val heading = Mth.wrapDegrees(player.getViewYRot(a) + 180f)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(screenX(position.x).toFloat(), screenY(position.z).toFloat())
        pose.rotate(Math.toRadians(heading.toDouble()).toFloat())
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PLAYER, -5, -5, ICON, ICON)
        pose.popMatrix()
    }

    private fun labelText(spot: Spot): Component {
        val label = spot.label ?: spot.name
        return if (spot.carved) Ui.heading(label) else label
    }

    /** A spot's name, centred under its icon, as a box from its corner to the far one. */
    private fun labelBox(spot: Spot): IntArray {
        val w = font.width(labelText(spot))
        val x = spot.sx - w / 2
        val y = spot.sy + spot.lift + 8
        return intArrayOf(x - 2, y - 1, x + w + 2, y + 10)
    }

    private fun drawLabel(graphics: GuiGraphicsExtractor, spot: Spot, box: IntArray, lit: Boolean) {
        graphics.fill(box[0], box[1], box[2], box[3], if (lit) LABEL_LIT else LABEL)
        graphics.text(font, labelText(spot), box[0] + 2, box[1] + 1 + if (spot.carved) Ui.HEADING_RISE - 1 else 0, if (lit) INK_RED else INK, false)
    }

    /** The compass rose in the top corner. */
    private fun drawRose(graphics: GuiGraphicsExtractor) {
        val x = right - 36
        val y = top + 14
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ROSE, x, y, 25, 25)
        val north = Ui.heading(Component.literal("N"))
        graphics.text(font, north, x + 13 - font.width(north) / 2, y - 10 + Ui.HEADING_RISE, INK, false)
    }

    /** A scale bar in the bottom corner: a round distance, in two halves. */
    private fun drawScale(graphics: GuiGraphicsExtractor) {
        val blocks = SCALES.lastOrNull { it * scale <= 96 } ?: SCALES.first()
        val length = (blocks * scale).roundToInt()
        val x = left + 12
        val y = bottom - 14
        graphics.fill(x - 1, y - 1, x + length + 1, y + 4, INK)
        graphics.fill(x, y, x + length / 2, y + 3, INK_SOFT)
        graphics.fill(x + length / 2, y, x + length, y + 3, PAPER_LIGHT)
        val text = if (blocks >= 1000) "${blocks / 1000} km" else "$blocks m"
        graphics.text(font, text, x + length + 5, y - 2, INK, false)
    }

    /** A line of ink on a strip of paper, centred across the map. */
    private fun note(graphics: GuiGraphicsExtractor, text: Component, y: Int) {
        val shown = font.substrByWidth(text, right - left - 40)
        val w = font.width(shown)
        val x = (left + right) / 2 - w / 2
        graphics.fill(x - 6, y - 4, x + w + 6, y + 12, NOTE)
        graphics.outline(x - 6, y - 4, w + 12, 16, INK_SOFT)
        graphics.text(font, Language.getInstance().getVisualOrder(shown), x, y, INK, false)
    }

    private fun legendColumn(): Int = LEGEND.maxOf { font.width(Component.translatable("gui.guildmark.map.legend." + it.second)) } + 18

    private fun legendBox(): IntArray {
        val rows = (LEGEND.size + 1) / 2
        val w = legendColumn() * 2 + 14
        val h = rows * 13 + 25
        return intArrayOf(right - w - 8, bottom - h - 8, right - 8, bottom - 8)
    }

    private fun drawLegend(graphics: GuiGraphicsExtractor) {
        val rows = (LEGEND.size + 1) / 2
        val column = legendColumn()
        val (x, y, x1, y1) = legendBox()
        val w = x1 - x
        val h = y1 - y
        graphics.fill(x, y, x1, y1, NOTE)
        graphics.outline(x, y, w, h, INK_SOFT)
        graphics.outline(x + 2, y + 2, w - 4, h - 4, LEGEND_RULE)
        val title = Ui.heading("gui.guildmark.map.legend")
        graphics.text(font, title, x + w / 2 - font.width(title) / 2, y + 7 + Ui.HEADING_RISE - 1, INK, false)
        for ((i, entry) in LEGEND.withIndex()) {
            val cx = x + 7 + (i / rows) * column
            val cy = y + 21 + (i % rows) * 13
            val tint = if (entry.first == PIN) MapData.PIN_COLORS[0] else -1
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, entry.first, cx, cy, ICON, ICON, tint)
            graphics.text(font, Component.translatable("gui.guildmark.map.legend." + entry.second), cx + 15, cy + 2, INK, false)
        }
    }

    /** The dimension's name on a plate across the frame's top edge. */
    private fun drawTitle(graphics: GuiGraphicsExtractor) {
        val id = minecraft.level?.dimension()?.identifier()
        val name = if (id == null) title else Component.translatableWithFallback(
            "gui.guildmark.map.dimension.${id.namespace}.${id.path}", id.path.replace('_', ' ').replaceFirstChar { it.uppercase() },
        )
        val shown = Ui.heading(name)
        val w = font.width(shown) + 24
        val x = width / 2 - w / 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.NAME_PLATE, x, TOP - 7, w, 15)
        graphics.text(font, shown, x + 12, TOP - 4 + Ui.HEADING_RISE, Ui.GOLD_BRIGHT, true)
    }

    /** Under the frame: how to work the map, and where the cursor is. */
    private fun drawBar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val y = height - BAR + 4
        var room = width - 2 * SIDE - 8
        if (inMap(mouseX.toDouble(), mouseY.toDouble())) {
            val at = Component.translatable("gui.guildmark.map.cursor", floor(worldX(mouseX.toDouble())).toInt(), floor(worldZ(mouseY.toDouble())).toInt())
            val w = font.width(at)
            graphics.text(font, at, width - SIDE - 4 - w, y, Ui.TEXT_SOFT, false)
            room -= w + 12
        }
        // As many of the hints as fit whole
        var hints = ""
        for (key in HINTS) {
            val next = if (hints.isEmpty()) Component.translatable(key).string else hints + "  ·  " + Component.translatable(key).string
            if (font.width(next) > room) break
            hints = next
        }
        graphics.text(font, hints, SIDE + 4, y, Ui.TEXT_FAINT, false)
    }

    private fun drawTooltip(graphics: GuiGraphicsExtractor, spot: Spot, player: Player, mouseX: Int, mouseY: Int) {
        val rows = ArrayList<Pair<Component, Int>>()
        for (line in spot.lines) rows.add(line to Ui.TEXT_SOFT)
        rows.add(Component.translatable("gui.guildmark.map.tip.distance", far(flat(spot.x, spot.z, player.x, player.z))) to Ui.TEXT_FAINT)
        spot.coach?.let { rows.add(Component.translatable("gui.guildmark.map.tip.coach", it.fare) to Ui.GREEN) }
        if (spot.wardfire != null) rows.add(Component.translatable("gui.guildmark.map.tip.travel") to Ui.GREEN)
        val title = Ui.heading(spot.name)
        val w = max(font.width(title), rows.maxOf { font.width(it.first) }) + 20
        val h = 27 + rows.size * 10
        var x = mouseX + 12
        if (x + w > width - 4) x = mouseX - 12 - w
        val y = (mouseY - 10).coerceIn(4, max(4, height - h - 4))
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Ui.PANEL_PLAIN, x, y, w, h)
        graphics.text(font, title, x + 10, y + 7 + Ui.HEADING_RISE, Ui.GOLD_BRIGHT, true)
        Ui.divider(graphics, x + 6, y + 19, w - 12, false)
        for ((i, row) in rows.withIndex()) graphics.text(font, row.first, x + 10, y + 24 + i * 10, row.second, false)
    }

    /** Why travelling to the Wardfire at [fire] can't happen right now, as far as the client can tell, or null. */
    private fun travelBlock(fire: BlockPos, player: Player): Component? = when {
        TravelFade.busy -> Component.translatable("gui.guildmark.map.travel.busy")
        flat(fire.x + 0.5, fire.z + 0.5, player.x, player.z) < HERE -> Component.translatable("gui.guildmark.map.travel.here")
        CombatClient.active() && CombatClient.inCombat() -> Component.translatable("gui.guildmark.map.travel.foes")
        else -> null
    }

    private fun drawRide(graphics: GuiGraphicsExtractor, spot: Spot, player: Player) {
        val (x, y, w, h) = ridePanel()
        val fire = spot.wardfire
        val stop = spot.coach
        val lines = if (fire != null) {
            val block = travelBlock(fire, player)
            listOf(
                Component.translatable("gui.guildmark.map.ride.to", spot.name) to Ui.TEXT,
                (block ?: Component.translatable("gui.guildmark.map.travel.note")) to if (block != null) Ui.RED else Ui.TEXT_SOFT,
            )
        } else if (stop != null) {
            val marks = TownControl.marks(player)
            listOf(
                Component.translatable("gui.guildmark.map.ride.to", spot.name) to Ui.TEXT,
                Component.translatable("gui.guildmark.map.ride.fare", stop.fare) to Ui.GOLD,
                Component.translatable("gui.guildmark.map.ride.purse", marks) to if (marks >= stop.fare) Ui.TEXT_SOFT else Ui.RED,
            )
        } else return
        graphics.fill(0, 0, width, height, SHADE)
        Ui.panel(graphics, x, y, w, h)
        val heading = if (fire != null) "gui.guildmark.map.travel.title" else "gui.guildmark.map.ride.title"
        Ui.centeredHeading(graphics, font, Component.translatable(heading), x + w / 2, y + 11)
        Ui.divider(graphics, x + 16, y + 25, w - 32)
        for ((i, line) in lines.withIndex()) {
            val shown = font.substrByWidth(line.first, w - 24)
            graphics.text(font, Language.getInstance().getVisualOrder(shown), x + w / 2 - font.width(shown) / 2, y + 33 + i * 11, line.second, false)
        }
    }

    companion object {
        val KEY = KeyMapping("key.guildmark.map", InputConstants.KEY_M, KeyMapping.Category(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "main")))

        /** GUI pixels per block at each zoom step. */
        private val ZOOMS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)
        private var lastZoom = 2
        private var legendShown = false
        /** Where the coaches from the village the player stands in go, as the server last said. */
        private var stops: List<CoachStop> = listOf()

        fun receiveStops(payload: CoachStopsPayload) {
            stops = payload.stops
        }

        private fun sprite(name: String) = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "map/$name")
        private val VILLAGE = sprite("village")
        private val TOWN = sprite("town")
        private val CITY = sprite("city")
        private val HALL = sprite("hall")
        private val LAIR = sprite("lair")
        private val LAIR_CLEARED = sprite("lair_cleared")
        private val VAULT = sprite("vault")
        private val COACH = sprite("coach")
        private val CONTRACT = sprite("contract")
        private val PARTY = sprite("party")
        private val DEATH = sprite("death")
        private val PLAYER = sprite("player")
        private val PIN = sprite("pin")
        private val WARDFIRE = sprite("wardfire")
        private val LOST_MARKS = sprite("lost_marks")
        private val ROSE = sprite("rose")
        private val VIGNETTE = sprite("frame")
        private val COACH_BADGE = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "compass/coach")
        private val PARCHMENT = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/map/parchment.png")
        private val TERRITORY = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "textures/gui/map/territory.png")

        private val LEGEND = listOf(
            VILLAGE to "village", TOWN to "town", CITY to "city", HALL to "hall", LAIR to "lair", LAIR_CLEARED to "lair_cleared",
            VAULT to "vault", WARDFIRE to "wardfire", COACH to "coach", CONTRACT to "objective", PIN to "pin", PARTY to "party",
            DEATH to "death", LOST_MARKS to "lost_marks", PLAYER to "you",
        )
        private val SCALES = intArrayOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000)
        private val HINTS = listOf("drag", "zoom", "pin", "home", "legend").map { "gui.guildmark.map.hint.$it" }

        private const val SIDE = 8
        private const val TOP = 14
        private const val BAR = 16
        /** The frame's iron border, inside which the map is drawn. */
        private const val BORDER = 5
        private const val ICON = 11
        private const val PAPER_SIZE = 256
        /** How far round a held village its guild's colour is washed, in blocks. */
        private const val LANDS = 64
        /** Tiles painted per frame at most. */
        private const val TILE_BUDGET = 2
        /** Keyboard panning, in GUI pixels a second. */
        private const val PAN = 420.0
        private const val NAME_WIDTH = 110
        private const val RIDE_WIDTH = 210
        private const val RIDE_HEIGHT = 100
        /** Closer than this to a Wardfire (in blocks) and you're already there. */
        private const val HERE = 12.0
        private const val KEY_NUMPAD_MINUS = 86

        private val BACKDROP = 0xEC0C0B0E.toInt()
        private val SHADE = 0x90000000.toInt()
        private val INK = 0xFF3A2614.toInt()
        private val INK_SOFT = 0xFF6A4C2E.toInt()
        private val INK_RED = 0xFF8C2219.toInt()
        private val PAPER_LIGHT = 0xFFF4EAD0.toInt()
        private val LABEL = 0x8CEFE3C2.toInt()
        private val LABEL_LIT = 0xD8F4EAD0.toInt()
        private val NOTE = 0xEEEADBB4.toInt()
        private val LEGEND_RULE = 0x806A4C2E.toInt()
    }
}
