package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.ambience.CityDistricts
import io.github.vaspyyy.guildmark.ambience.District
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.village.live.days.VillageHands
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tells each player's village sounds (client/ambience/VillageAmbience) where they stand, as `city.CapitalVisits` tells the
 * city's (living-world/villages/design.md 17.3): in a village's ground or not, and whether it is the village's market, with
 * capital = false and [District.VILLAGE] or [District.VILLAGE_MARKET]. Each player is looked at twice a second and told only
 * when that changes; the village they stand in is looked for (in the records in memory, so no chunk is loaded) only every two
 * seconds, and once they are in one it is the same village until they are well out of its ground, so a step over its edge
 * does not turn the sounds on and off.
 *
 * The market is the village's own market day ([VillageHands.market]: one in seven, the rest day keeping it) from eight in the
 * morning, when its stalls open, to four in the afternoon, when they close and the pedlar packs up. Nothing is told in a world
 * with Living Villages off (a player who was in a village is told they have left it), and a player who leaves a village for a
 * capital's streets is left to the capital's own word (CapitalVisits), which this never overrides.
 *
 * It is also what wakes a village a player is in at first light: its rooster crows once ([VillageSounds.ROOSTER], 16.1 row 1),
 * from one of its barns (or its farthest house, or the edge of its ground), at a minute between five and a quarter to six drawn
 * for each village and day, and not again that day; a village with no player in it at that time is not heard.
 */
object VillageVisits {
    /** Ticks between looks at where each player is. */
    private const val CHECK = 10
    /** Ticks between looking for a village round a player who isn't in one's ground. */
    private const val SEARCH = 40
    /** Blocks past a village's ground that a player who was in it still counts as being in. */
    private const val STAY = 6
    /** The clock hours (by the 24-hour clock) a market day's market is open: from eight in the morning until four. */
    private const val OPENS = 8
    private const val CLOSES = 16
    /** The clock time (ticks into the day, which starts at six in the morning) of five o'clock, from which a rooster crows. */
    private const val CROW_FROM = 23000L
    /** The span (ticks, three quarters of an hour by the clock) each village's minute to crow is drawn from. */
    private const val CROW_SPAN = 750L
    /** How long after its minute the rooster still crows for a player who has only just come (a quarter of an hour by the clock). */
    private const val CROW_LATE = 250L

    private class Visit {
        /** The key of the village whose ground the player was last in; none when they were not in one. */
        var village: Long? = null
        var searched = Long.MIN_VALUE / 2
        /** Whether the player's side has been told anything yet in this world (a first look is always told, so nothing stale is left there). */
        var spoken = false
        /** What it was last told of a village: [District.VILLAGE] or [District.VILLAGE_MARKET], null when it was told it is in none. */
        var told: District? = null
    }

    private val visits = HashMap<UUID, Visit>()

    /** The day (Days.dayOf) each village's rooster last crowed on, by the village's key. */
    private val crowed = HashMap<Long, Long>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event -> (event.entity as? ServerPlayer)?.let(::tick) }
        // A new world on the player's side, or a new player entity, starts from nothing told
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { visits.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { visits.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { visits.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            visits.clear()
            crowed.clear()
        }
    }

    private fun tick(player: ServerPlayer) {
        if (player.tickCount % CHECK != 6) return
        val level = player.level()
        // A player nothing has been told of costs nothing where villages are not counted
        val visit = visits[player.uuid] ?: if (on(level)) Visit().also { visits[player.uuid] = it } else return
        try {
            look(level, player, visit)
        } catch (e: RuntimeException) {
            // A bad look costs this player's village sounds for now, not the tick
            visit.village = null
        }
    }

    /** Whether villages are counted where [level] is: Living Villages on, and the overworld. */
    private fun on(level: ServerLevel): Boolean = Villages.enabled() && level.dimension() == Level.OVERWORLD

    private fun look(level: ServerLevel, player: ServerPlayer, visit: Visit) {
        val pos = player.blockPosition()
        val counted = on(level)
        var rec = if (counted) visit.village?.let { Villages.byKey(level, it) }?.takeIf { holds(it, pos, STAY) } else null
        if (rec == null && counted && level.gameTime - visit.searched >= SEARCH) {
            visit.searched = level.gameTime
            rec = Villages.at(level, pos)
        }
        visit.village = rec?.key
        // A village this player has been told they are in (not a capital's streets) wakes to its rooster
        if (rec != null && visit.told != null) crow(level, rec)
        val now = if (rec == null) null else if (market(level, rec)) District.VILLAGE_MARKET else District.VILLAGE
        if (visit.spoken && now == visit.told) return
        if (now == null) {
            // Out of a village, or just arrived: unless it is a capital's streets, which the capital has told them (a word from here would undo it)
            visit.told = null
            visit.spoken = true
            if (!inCapital(level, pos)) CityDistricts.tell(player, false, District.NONE)
            return
        }
        // A village that overlaps a capital's town is the capital's there: its streets are not the country
        if (visit.told == null && inCapital(level, pos)) {
            visit.village = null
            return
        }
        visit.told = now
        visit.spoken = true
        CityDistricts.tell(player, false, now)
    }

    /** Whether [pos] is in [rec]'s ground (the square of its radius round the bell), [extra] blocks past it. */
    private fun holds(rec: VillageRec, pos: BlockPos, extra: Int): Boolean {
        if (rec.state == Live.LOST) return false
        return abs(pos.x - BlockPos.getX(rec.key)) <= rec.radius + extra && abs(pos.z - BlockPos.getZ(rec.key)) <= rec.radius + extra
    }

    /** Whether [rec]'s market is open now: its market day, between the stalls' opening and their closing. */
    private fun market(level: ServerLevel, rec: VillageRec): Boolean {
        val time = level.overworldClockTime
        val hour = (Math.floorMod(time, 24000L) / 1000L + 6L).toInt() % 24
        return hour in OPENS until CLOSES && VillageHands.market(level, BlockPos.of(rec.key), Days.dayOf(time))
    }

    /** [rec]'s rooster crows, if this is its minute at first light and it has not crowed today. */
    private fun crow(level: ServerLevel, rec: VillageRec) {
        val time = level.overworldClockTime
        val day = Days.dayOf(time)
        if (crowed[rec.key] == day) return
        val at = CROW_FROM + Math.floorMod(draw(rec.key, day), CROW_SPAN)
        val clock = Math.floorMod(time, 24000L)
        if (clock < at || clock >= at + CROW_LATE) return
        crowed[rec.key] = day
        VillageSounds.play(level, coop(level, rec, day), VillageSounds.ROOSTER)
    }

    /**
     * Where [rec]'s rooster crows from: one of its barns (the hens are kept by the barn), a different one each day; failing a barn,
     * the house farthest from its bell; failing a counted house, the edge of its ground. Read from what is kept in memory: nothing
     * is loaded to find it, and a sound needs no loaded chunk to be heard.
     */
    private fun coop(level: ServerLevel, rec: VillageRec, day: Long): Vec3 {
        val bell = BlockPos.of(rec.key)
        val lodgings = Villages.places(level, rec.key)?.lodgings?.values.orEmpty()
        val barns = lodgings.filter { it.kind == "BARN" }.sortedBy { it.id }
        val lodging = if (barns.isNotEmpty()) {
            barns[Math.floorMod(day, barns.size.toLong()).toInt()]
        } else {
            lodgings.maxByOrNull { val c = it.box.center; c.distSqr(bell) }
        }
        if (lodging != null) {
            val c = lodging.box.center
            return Vec3(c.x + 0.5, lodging.y + 1.0, c.z + 0.5)
        }
        val a = Math.floorMod(draw(rec.key, day + 1), 360L) * Math.PI / 180.0
        val r = rec.radius * 0.8
        return Vec3(bell.x + 0.5 + cos(a) * r, bell.y + 1.0, bell.z + 0.5 + sin(a) * r)
    }

    /** A number drawn from a village and a day, the same each time it is drawn for them. */
    private fun draw(key: Long, day: Long): Long {
        var h = key * -7046029254386353131L + day * 0x2545F4914F6CDD1DL
        h = h xor (h ushr 31)
        h *= -4658895280553007687L
        return h xor (h ushr 29)
    }

    private fun inCapital(level: ServerLevel, pos: BlockPos): Boolean {
        if (!Capitals.enabled()) return false
        val capital = Capitals.covering(level, pos) ?: return false
        return capital.inTown(pos.x, pos.z)
    }
}
