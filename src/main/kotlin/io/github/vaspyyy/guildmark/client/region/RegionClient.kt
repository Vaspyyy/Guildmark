package io.github.vaspyyy.guildmark.client.region

import io.github.vaspyyy.guildmark.region.RegionCellPayload
import io.github.vaspyyy.guildmark.region.RegionData
import io.github.vaspyyy.guildmark.region.RegionEnteredPayload
import io.github.vaspyyy.guildmark.region.RegionEntry
import io.github.vaspyyy.guildmark.region.RegionKind
import io.github.vaspyyy.guildmark.region.RegionMap
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.SelectMusicEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge

/**
 * The client's half of the regions: the region the player is in, as the server last said, and the regions
 * of the cells the server has sent, for the map. The title card, the air and the music hang off this.
 */
object RegionClient {
    /** One cell's regions, as sent (see CellPlan). */
    class Plan(val cell: Long, val i0: Int, val j0: Int, val width: Int, val height: Int, val grid: ByteArray, val regions: List<RegionEntry>)

    /** The region the player is in. */
    class Current(val id: Long, val kind: RegionKind, val name: String)

    private val plans = HashMap<Long, Plan>()
    var current: Current? = null
        private set

    /** The found list as last read, and the same as a set. */
    private var foundList: List<Long>? = null
    private var foundSet: Set<Long> = setOf()

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { _ ->
            RegionTitle.tick()
            RegionAtmosphere.tick()
            RegionMusic.tick()
        }
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFogColor::class.java, RegionAtmosphere::onFogColor)
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog::class.java, RegionAtmosphere::onRenderFog)
        // After the boss music has had its say (it cancels the event), and only if nobody else changed the choice
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, true, SelectMusicEvent::class.java, RegionMusic::onSelectMusic)
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> reset() }
        modBus.addListener(RegisterGuiLayersEvent::class.java) { event ->
            event.registerBelow(VanillaGuiLayers.TITLE, RegionTitle.ID, RegionTitle)
        }
        RegionParticles.register(modBus)
    }

    fun receiveCell(payload: RegionCellPayload) {
        plans[payload.cell] = Plan(payload.cell, payload.i0, payload.j0, payload.width, payload.height, payload.grid(), payload.regions)
        RegionMapLayer.changed()
    }

    fun receiveEntered(payload: RegionEnteredPayload) {
        val kind = RegionKind.byId(payload.kind)
        val before = current
        if (payload.id == 0L || kind == null) {
            current = null
            RegionMusic.entered(before?.kind, null)
            return
        }
        current = Current(payload.id, kind, payload.name)
        RegionMusic.entered(before?.kind, kind)
        RegionTitle.announce(payload.id, payload.name, kind, payload.first)
    }

    /** The region at lattice point ([i], [j]) (see RegionMap.STEP), if the server has sent its cell. */
    fun regionAt(i: Int, j: Int): RegionEntry? {
        for (plan in plans.values) {
            val x = i - plan.i0
            val z = j - plan.j0
            if (x < 0 || z < 0 || x >= plan.width || z >= plan.height) continue
            val index = plan.grid[z * plan.width + x].toInt()
            if (index >= 0) return plan.regions.getOrNull(index)
        }
        return null
    }

    /** The surface region at world spot ([x], [z]), if known. */
    fun regionAtBlock(x: Int, z: Int): RegionEntry? = regionAt(Math.floorDiv(x, RegionMap.STEP), Math.floorDiv(z, RegionMap.STEP))

    fun plans(): Collection<Plan> = plans.values

    /** Whether the player has been in region [id]. */
    fun found(id: Long): Boolean {
        val list = Minecraft.getInstance().player?.getData(RegionData.FOUND) ?: return false
        if (list !== foundList) {
            foundList = list
            foundSet = list.toHashSet()
        }
        return id in foundSet
    }

    private fun reset() {
        plans.clear()
        current = null
        foundList = null
        foundSet = setOf()
        RegionTitle.reset()
        RegionMusic.reset()
        RegionAtmosphere.reset()
    }
}
