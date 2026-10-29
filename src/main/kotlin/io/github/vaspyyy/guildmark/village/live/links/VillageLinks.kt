package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.VillageMarkets
import io.github.vaspyyy.guildmark.road.Road
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.live.Link
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillageData
import io.github.vaspyyy.guildmark.village.live.VillagePerf
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which capitals' wagon roads end at which village (living-world/villages/design.md section 9.1). A capital's wagons go by
 * each road of the network that touches its heart (`CaravanRoutes.roads`), and each road's leg starts at the road's far end
 * (`Road.otherEnd`, the leg's `far`). The village whose bell lies within [NEAR] blocks of that far end (the capitals' own
 * `ROAD_END`) is linked to the capital by that road: its record's `links` hold the capital's heart, the road's id and the
 * leg's length. A village may have several links (two capitals, or two roads from one); a road whose far end is another
 * capital's heart is the capitals' own and links nobody; an unlinked village is served by the pedlar alone.
 *
 * The links are worked out again once a day, at once when the roads change (a road laid or finished, a leg worked out for
 * the first time: looked at every [LOOK] ticks by a few numbers, never by a scan of the world), and when a village is made,
 * lost, found again or dropped. Only what is kept in memory is read: no chunk is loaded and no leg is asked for, so a link
 * waits for the capital's own wagons to have worked their leg out once. A road whose leg is not worked out yet keeps the
 * link it had, so a restart does not cut a village off until the legs come back.
 *
 * With the switch off ([enabled] false: Village Wagons, or what it needs) nothing is worked out or written, and [linked]
 * answers none, so the village is served by the pedlar alone and the wagons run as they always have; the links last kept
 * are left on the records for when it is on again.
 */
object VillageLinks {
    /** A village's bell this near a road's far end (blocks, across) is the village at the end of that road. */
    const val NEAR = 64

    /** Ticks between looks at whether the roads changed, and the tick of that period the look is made on. */
    private const val LOOK = 200
    private const val LOOK_AT = 67

    /** The roads and legs as last looked at (a few numbers that change whenever a link could), and the day last refreshed. */
    private var mark = 0L
    private var day = Long.MIN_VALUE

    /** A village was made, lost, found again or dropped since the last look. */
    @Volatile
    private var stale = true

    /** Whether the villages and the capitals' wagons are one: Village Wagons on, with Capital Caravans and Village Markets. */
    fun enabled(): Boolean = VillageMarkets.enabled() && Caravans.enabled() && flag { GuildmarkConfig.VILLAGE_WAGONS.get() }

    private inline fun flag(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }

    /** At setup (from `Villages.register`): the look (and the scenes at the road ends), the registry's listener, the wagons' listener and the commands. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { VillagePerf.timed(VillagePerf.Piece.LINKS) { tick(it.server.overworld()) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
        Villages.listen(object : Villages.Listener {
            override fun made(level: ServerLevel, rec: VillageRec) {
                stale = true
            }

            override fun lost(level: ServerLevel, rec: VillageRec) {
                stale = true
            }

            override fun restored(level: ServerLevel, rec: VillageRec) {
                stale = true
            }

            override fun dropped(level: ServerLevel, rec: VillageRec) {
                stale = true
            }
        })
        Caravans.listen(VillageWagons.Heard)
        VillageLinkCommand.init()
    }

    private fun forget() {
        mark = 0L
        day = Long.MIN_VALUE
        stale = true
        VillageWagons.forget()
        SendOff.forget()
        Greeters.forget()
    }

    private fun tick(level: ServerLevel) {
        // The scenes at the road ends under way (nothing to do while there are none)
        SendOff.tick(level)
        if (level.server.tickCount % LOOK != LOOK_AT) return
        if (!enabled()) return
        val today = Days.dayOf(level.overworldClockTime)
        val now = mark(level)
        if (stale || today != day || now != mark) {
            stale = false
            day = today
            mark = now
            refresh(level)
        }
        VillageWagons.look(level)
        VillageNews.look(level)
        SendOff.look(level)
    }

    /** A few numbers that change whenever a link could: each capital, each road into it, whether it is finished and whether its leg is worked out. */
    private fun mark(level: ServerLevel): Long {
        var h = 17L
        for (entry in Markets.get(level).all()) {
            val capital = entry.market.capital
            h = h * 31 + capital
            for (road in CaravanRoutes.roads(level, capital)) {
                h = h * 31 + road.id
                h = h * 31 + (if (road.finished) 1 else 0) + (if (CaravanRoutes.cached(capital, road.id) != null) 2 else 0)
            }
        }
        return h
    }

    /**
     * Works the links out again: for each capital with a market and each road into it (each of its legs), the village at the far
     * end (once a day, when the roads change, and from `village links refresh`). Each record's links are written only when they
     * change.
     */
    fun refresh(level: ServerLevel) {
        if (!enabled()) return
        val data = VillageData.get(level)
        val capitals = Markets.get(level).all().map { it.market.capital }
        val found = HashMap<Long, MutableList<Link>>()
        for (capital in capitals) {
            val heart = BlockPos.of(capital)
            for (road in CaravanRoutes.roads(level, capital)) {
                val far = road.otherEnd(heart)
                // A road from one capital to another is the capitals' own
                if (capitals.any { it != capital && flat(BlockPos.of(it), far) <= NEAR }) continue
                val rec = Villages.near(level, far, NEAR).firstOrNull() ?: continue
                found.getOrPut(rec.key) { ArrayList() }.add(Link(capital, road.id, length(capital, road, rec)))
            }
        }
        var changed = false
        for (rec in data.records.values) {
            val want = if (rec.state == Live.LOST) emptyList() else found[rec.key].orEmpty().sortedWith(compareBy({ it.capital }, { it.road }))
            if (rec.links == want) continue
            rec.links.clear()
            rec.links.addAll(want)
            changed = true
        }
        if (changed) data.setDirty()
    }

    /** The length of the wagons' leg along [road] into [capital] (blocks): the leg's own once worked out, else the one kept, else the road's. */
    private fun length(capital: Long, road: Road, rec: VillageRec): Int {
        CaravanRoutes.cached(capital, road.id)?.let { return it.length.roundToInt() }
        rec.links.firstOrNull { it.capital == capital && it.road == road.id && it.length > 0 }?.let { return it.length }
        var n = 0.0
        for (i in 1 until road.points.size) n += sqrt(road.points[i].distSqr(road.points[i - 1]))
        return n.roundToInt()
    }

    private fun flat(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    /** The links that end at the village [key]: none while the switch is off, or for a record that is not here or is lost. */
    fun linked(level: ServerLevel, key: Long): List<Link> {
        if (!enabled()) return emptyList()
        val rec = Villages.byKey(level, key) ?: return emptyList()
        if (rec.state == Live.LOST) return emptyList()
        return rec.links.toList()
    }

    /** The links of [key] the wagons really run on now: the capital's market is open and its leg by that road is worked out. */
    fun running(level: ServerLevel, key: Long): List<Link> {
        val markets = Markets.get(level)
        return linked(level, key).filter { markets[BlockPos.of(it.capital)] != null && it.road >= 0 && CaravanRoutes.cached(it.capital, it.road) != null }
    }

    /** The village linked to [capital] by [road], if one is. */
    fun villageOf(level: ServerLevel, capital: Long, road: Int): VillageRec? {
        if (!enabled()) return null
        return VillageData.get(level).records.values.firstOrNull { rec -> rec.state != Live.LOST && rec.links.any { it.capital == capital && it.road == road } }
    }
}
