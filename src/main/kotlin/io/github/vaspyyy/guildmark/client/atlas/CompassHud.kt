package io.github.vaspyyy.guildmark.client.atlas

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.atlas.AtlasPlace
import io.github.vaspyyy.guildmark.client.ContractTracker
import io.github.vaspyyy.guildmark.client.GuildmarkClientConfig
import io.github.vaspyyy.guildmark.client.combat.Cutscenes
import io.github.vaspyyy.guildmark.client.ui.Ui
import io.github.vaspyyy.guildmark.island.IslandMarks
import io.github.vaspyyy.guildmark.network.AtlasMark
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent
import net.neoforged.neoforge.client.gui.GuiLayer
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The compass strip across the top of the screen: the points of the compass turning as you look about,
 * the places you've found nearby, where your contracts and the main story lead, your party, the pins
 * you've put on the map, and where you last fell. Whatever sits under the gold notch is named below the strip, with how far it is.
 */
object CompassHud : GuiLayer {
    val ID: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "compass")

    private val sprites = HashMap<String, Identifier>()
    private fun sprite(name: String): Identifier = sprites.getOrPut(name) { Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "compass/$name") }
    private val STRIP = sprite("strip")
    private val NOTCH = sprite("notch")
    private val ICON_VILLAGE = sprite("village")
    private val ICON_TOWN = sprite("town")
    private val ICON_HALL = sprite("hall")
    private val ICON_LAIR = sprite("lair")
    private val ICON_LAIR_CLEARED = sprite("lair_cleared")
    private val ICON_BOSS = sprite("boss")
    private val ICON_VAULT = sprite("vault")
    private val ICON_CONTRACT = sprite("contract")
    private val ICON_PARTY = sprite("party")
    private val ICON_DEATH = sprite("death")
    private val ICON_PIN = sprite("pin")
    private val ICON_WARDFIRE = sprite("wardfire")
    private val ICON_LOST_MARKS = sprite("lost_marks")
    private val ICON_TROLL = sprite("troll_camp")
    private val ICON_TROLL_SLAIN = sprite("troll_camp_slain")
    private val ICON_OUTLAW = sprite("outlaw_camp")
    private val ICON_OUTLAW_SLAIN = sprite("outlaw_camp_slain")
    private val ICON_HOMESTEAD = sprite("homestead")
    private val ICON_HOMESTEAD_YOURS = sprite("homestead_yours")
    private val ICON_SHIP = sprite("ship")

    private const val TOP = 3
    private const val HEIGHT = 13
    private const val MAX_WIDTH = 182
    /** Room kept clear at each side for the tracker and the combat bars. */
    private const val SIDE_ROOM = 160
    /** Degrees of the horizon the strip shows. */
    private const val SPAN = 150f
    /** A marker this close to the notch (in degrees) is the one named. */
    private const val FOCUS = 5f
    /** How far down the vanilla boss bars move to make room. */
    private const val BOSS_SHIFT = 26

    private val CARDINALS = arrayOf("N", "E", "S", "W")
    private val INTERCARDINALS = arrayOf("NE", "SE", "SW", "NW")

    private class Marker(val at: Vec3, val icon: Identifier, val label: Component, val priority: Int, val hideWithin: Double, val tint: Int = 0xFFFFFF)

    fun enabled(): Boolean = !GuildmarkClientConfig.SPEC.isLoaded || GuildmarkClientConfig.COMPASS.get()

    /** Whether the strip is up this frame. */
    fun shown(): Boolean {
        val minecraft = Minecraft.getInstance()
        return enabled() && minecraft.player != null && !minecraft.gui.hud.isHidden &&
            !minecraft.debugOverlay.showDebugScreen() && !Cutscenes.holdsCamera()
    }

    // ---- Making room for the vanilla boss bars ----

    private var shifted = false

    fun onLayerPre(event: RenderGuiLayerEvent.Pre) {
        if (event.name != VanillaGuiLayers.BOSS_OVERLAY || !shown()) return
        event.guiGraphics.pose().pushMatrix()
        event.guiGraphics.pose().translate(0f, BOSS_SHIFT.toFloat())
        shifted = true
    }

    fun onLayerPost(event: RenderGuiLayerEvent.Post) {
        if (event.name != VanillaGuiLayers.BOSS_OVERLAY || !shifted) return
        event.guiGraphics.pose().popMatrix()
        shifted = false
    }

    // ---- Drawing ----

    override fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        if (!shown()) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val font = minecraft.font
        val width = (graphics.guiWidth() - 2 * SIDE_ROOM).coerceIn(96, MAX_WIDTH) / 2 * 2
        val center = graphics.guiWidth() / 2
        val half = width / 2
        // 0 is north, 90 east: the camera's yaw is 0 facing south
        val heading = Mth.wrapDegrees(minecraft.gameRenderer.mainCamera().yRot() + 180f)
        val eye = minecraft.gameRenderer.mainCamera().position()

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, STRIP, center - half, TOP, width, HEIGHT)
        fun x(relative: Float): Int = center + (relative * width / SPAN).roundToInt()
        /** Faded toward the strip's ends. */
        fun fade(relative: Float): Float {
            val t = abs(relative) / (SPAN / 2)
            return (1f - t * t * t * t).coerceIn(0f, 1f)
        }

        // The points of the compass, with a tick every 15 degrees between them
        for (step in 0 until 24) {
            val degrees = step * 15
            val relative = Mth.wrapDegrees(degrees - heading)
            if (abs(relative) > SPAN / 2 - 2) continue
            val px = x(relative)
            val alpha = fade(relative)
            if (alpha < 0.05f) continue
            when {
                degrees % 90 == 0 -> {
                    val letter = Ui.heading(Component.literal(CARDINALS[degrees / 90]))
                    val color = if (degrees == 0) Ui.GOLD_BRIGHT else Ui.TEXT
                    graphics.text(font, letter, px - font.width(letter) / 2 + 1, TOP + 2 + Ui.HEADING_RISE, ARGB.color(alpha, color), true)
                }
                degrees % 45 == 0 -> {
                    val text = INTERCARDINALS[degrees / 90]
                    graphics.text(font, text, px - font.width(text) / 2 + 1, TOP + 3, ARGB.color(alpha * 0.85f, Ui.TEXT_SOFT), true)
                }
                else -> graphics.fill(px, TOP + HEIGHT - 4, px + 1, TOP + HEIGHT - 2, ARGB.color(alpha * 0.8f, Ui.TEXT_FAINT))
            }
        }

        // Markers, the most important drawn last so they sit on top
        var focus: Marker? = null
        var focusOff = 0f
        for (marker in markers(player).sortedBy { it.priority }) {
            val dx = marker.at.x - eye.x
            val dz = marker.at.z - eye.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < marker.hideWithin) continue
            val bearing = Math.toDegrees(atan2(dx, -dz)).toFloat()
            val relative = Mth.wrapDegrees(bearing - heading)
            if (abs(relative) > SPAN / 2 - 3) continue
            val alpha = fade(relative)
            if (alpha < 0.05f) continue
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, marker.icon, x(relative) - 3, TOP + 3, 7, 7, ARGB.color(alpha, marker.tint))
            val off = abs(relative)
            val current = focus
            if (off <= FOCUS && (current == null || marker.priority > current.priority || marker.priority == current.priority && off < focusOff)) {
                focus = marker
                focusOff = off
            }
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, NOTCH, center - 3, TOP - 2, 7, 5)

        val named = focus ?: return
        val distance = named.at.subtract(player.position()).horizontalDistance()
        val far = if (distance >= 1000) "%.1fkm".format(distance / 1000) else "${distance.toInt()}m"
        val label = font.substrByWidth(named.label, width - font.width(far) - 8)
        val labelWidth = font.width(label)
        val total = labelWidth + 6 + font.width(far)
        val left = center - total / 2
        graphics.text(font, Language.getInstance().getVisualOrder(label), left, TOP + HEIGHT + 3, Ui.TEXT, true)
        graphics.text(font, far, left + labelWidth + 6, TOP + HEIGHT + 3, Ui.GOLD, true)
    }

    /** Everything the strip could show, before the ones out of view are left off. */
    private fun markers(player: Player): List<Marker> {
        val out = ArrayList<Marker>()
        val here = player.position()
        for (mark in AtlasClient.marks()) {
            val at = Vec3(mark.pos.x + 0.5, mark.pos.y.toDouble(), mark.pos.z + 0.5)
            val range = when {
                mark.kind == AtlasPlace.THRONE -> 2000.0
                // Home is always worth knowing the way to
                mark.kind == AtlasPlace.HOMESTEAD && mark.has(AtlasMark.YOURS) -> 2000.0
                // Islands are what you steer by at sea
                mark.kind == AtlasPlace.ISLAND -> 900.0
                mark.kind == AtlasPlace.VILLAGE -> 640.0
                mark.kind == AtlasPlace.WARDFIRE -> 512.0
                mark.has(AtlasMark.CLEARED) -> 192.0
                else -> 384.0
            }
            if (at.subtract(here).horizontalDistance() > range) continue
            val icon = when {
                mark.kind == AtlasPlace.ISLAND -> sprite(IslandMarks.icon(mark.standing))
                mark.kind == AtlasPlace.WARDFIRE -> ICON_WARDFIRE
                mark.kind == AtlasPlace.VILLAGE && mark.has(AtlasMark.YOURS) -> ICON_HALL
                mark.kind == AtlasPlace.VILLAGE -> if (mark.size > 5) ICON_TOWN else ICON_VILLAGE
                mark.kind == AtlasPlace.THRONE || mark.has(AtlasMark.VAULT) -> ICON_VAULT
                mark.kind == AtlasPlace.FIELD_BOSS -> if (mark.has(AtlasMark.CLEARED)) ICON_TROLL_SLAIN else ICON_TROLL
                mark.kind == AtlasPlace.OUTLAW_CAMP -> if (mark.has(AtlasMark.CLEARED)) ICON_OUTLAW_SLAIN else ICON_OUTLAW
                mark.kind == AtlasPlace.HOMESTEAD -> if (mark.has(AtlasMark.YOURS)) ICON_HOMESTEAD_YOURS else ICON_HOMESTEAD
                mark.has(AtlasMark.CLEARED) -> ICON_LAIR_CLEARED
                mark.has(AtlasMark.AWAKE) -> ICON_BOSS
                else -> ICON_LAIR
            }
            val hide = when (mark.kind) {
                AtlasPlace.VILLAGE -> 40.0
                // Once you're on it, its middle is no way to point
                AtlasPlace.ISLAND -> mark.size.toDouble()
                AtlasPlace.THRONE -> 24.0
                AtlasPlace.WARDFIRE -> 6.0
                else -> 12.0
            }
            out.add(Marker(at, icon, AtlasClient.name(mark), if (mark.kind == AtlasPlace.VILLAGE) 0 else 1, hide))
        }
        for (pin in MapData.pins) {
            out.add(Marker(Vec3(pin.x + 0.5, here.y, pin.z + 0.5), ICON_PIN, Component.literal(pin.name), 2, 4.0, pin.color))
        }
        for (mate in AtlasClient.partyMates(player)) {
            out.add(Marker(mate.second, ICON_PARTY, Component.literal(AtlasClient.mateName(mate.first)), 3, 4.0))
        }
        // Lost Marks stand in for where you fell: they're what's worth going back for
        val lost = AtlasClient.lostMarks(player)
        if (lost != null) {
            out.add(Marker(Vec3.atCenterOf(lost.first), ICON_LOST_MARKS, Component.translatable("atlas.guildmark.lost_marks", lost.second), 3, 2.0))
        } else {
            AtlasClient.deathPoint(player)?.let {
                out.add(Marker(Vec3.atCenterOf(it), ICON_DEATH, Component.translatable("atlas.guildmark.death"), 3, 4.0))
            }
        }
        // Your ship, where you stepped off her
        io.github.vaspyyy.guildmark.client.ship.ShipClient.berth(player)?.let { (at, name) -> out.add(Marker(at, ICON_SHIP, name, 3, 6.0)) }
        for (waypoint in ContractTracker.waypoints(player)) {
            val icon = when (waypoint.kind) {
                ContractTracker.STORY -> ICON_VAULT
                ContractTracker.CHALLENGE -> ICON_BOSS
                else -> ICON_CONTRACT
            }
            out.add(Marker(Vec3.atCenterOf(waypoint.pos), icon, waypoint.title, 4, 6.0))
        }
        return out
    }
}
