package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.TownZones
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.abs

/**
 * What keeps the registry of villages (living-world/villages/design.md section 4.5), each server tick and every 100 ticks:
 *
 * - at the start of every tick the budget is worked out afresh ([VillageBudget.begin]), and at the end the census of the village at
 *   the front of the queue gets the share of it that is its own, a little at a time, never loading a chunk;
 * - every 100 ticks (the player look) each record is seen to: LIVE with a player within 96 blocks, SLEEPING further off, PAUSED when
 *   more are awake than `maxLiveVillages` allows (the ones seen longest ago; a LIVE village is never paused), LOST when its bell is gone
 *   and no board stands, found again when the bell is put back, and dropped after 30 days lost. A LIVE village is counted again every
 *   6,000 ticks, and at once when it has no places kept (the first count, or after a restart);
 * - the town zone of each village follows its record, so that crowds skip it.
 *
 * With Living Villages off none of it runs: the zones are taken away once, and nothing else is read or written.
 */
internal object VillageTick {
    /** Ticks between looks, and the tick of that period the look is made on (off the multiples of 100 that other work is on). */
    private const val LOOK = 100
    private const val LOOK_AT = 41

    /** The least the census may be given in a tick, or the least columns, to be worth starting a unit of work. */
    private const val MIN_NANOS = 100_000L
    private const val MIN_COLUMNS = 32

    /** Ticks between counts of a LIVE village. */
    const val RECOUNT = 6000L

    /** How near a player a village is LIVE: blocks across the ground. */
    const val LIVE_NEAR = 96.0

    /** Days a lost village is kept. */
    const val LOST_DAYS = 30L

    /** A bell moved: a village within this many blocks whose bell is gone is lost to the new one. */
    private const val MOVED = 48

    /** A second bell this close to a village's own is another of its bells, not another village. */
    private const val SPARE = 16

    /** Who asked for a count, to be told when it is done. */
    private class Ask(val who: UUID?)

    private val queue = ArrayList<Long>()
    private val asks = HashMap<Long, Ask>()
    private var job: VillageCensus.Job? = null
    private var swept = false
    private var lastFailure = -1L

    /** Whether a bell stands: seen standing, seen gone, or not seen because its chunk is not loaded. */
    enum class Stands { YES, NO, UNKNOWN }

    fun register() {
        // First of all, so that what the villages may spend this tick is known to whatever comes after
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre::class.java) {
            if (Villages.enabled()) VillageBudget.begin(it.server.overworld())
        }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { VillagePerf.timed(VillagePerf.Piece.REGISTRY) { post(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> forget() }
    }

    private fun post(server: MinecraftServer) {
        val level = server.overworld()
        if (!Villages.enabled()) {
            if (!swept) sweep(level)
            return
        }
        swept = false
        try {
            if (server.tickCount % LOOK == LOOK_AT) look(level)
            census(level)
            VillageShow.tick(server, level)
        } catch (e: RuntimeException) {
            failed(level, e)
        }
    }

    /**
     * Something in the villages' own work threw: the count in hand is given up, so that one village it can't read costs a count and
     * not the server, and it is logged (the first time, then at most once in [RECOUNT] ticks, so a fault that repeats can't fill the log).
     */
    private fun failed(level: ServerLevel, e: RuntimeException) {
        job = null
        val now = level.gameTime
        if (lastFailure < 0L || now < lastFailure || now - lastFailure >= RECOUNT) {
            lastFailure = now
            Guildmark.LOGGER.error("The work of the villages failed; the count in hand is given up (this is told once in {} ticks)", RECOUNT, e)
        }
    }

    /** The switch is off: the zones are taken away once, what was waiting is dropped, and the records are left as they are. */
    private fun sweep(level: ServerLevel) {
        swept = true
        queue.clear()
        asks.clear()
        job = null
        Villages.forgetAll()
        VillageShow.clear()
        val zones = TownZones.get(level)
        if (zones.zones.removeIf { it.village }) zones.setDirty()
    }

    /** Everything held in memory is forgotten, as the server stops. */
    private fun forget() {
        queue.clear()
        asks.clear()
        job = null
        swept = false
        lastFailure = -1L
        Villages.forgetAll()
        VillageShow.clear()
        VillageBudget.forget()
        VillageCensus.Perf.forget()
        PoiKinds.forget()
    }

    // ---- The look ----

    private fun cap(): Int = try {
        GuildmarkConfig.MAX_LIVE_VILLAGES.get()
    } catch (e: IllegalStateException) {
        48
    }

    /** Whether a player is within [LIVE_NEAR] blocks of the bell [key], across the ground. */
    private fun near(players: List<ServerPlayer>, key: Long): Boolean {
        for (p in players) if (Villages.flat(p.x, p.z, key) <= LIVE_NEAR * LIVE_NEAR) return true
        return false
    }

    /** The squared distance from the bell [key] to the nearest of [players], or the largest there is with none. */
    fun nearest(players: List<ServerPlayer>, key: Long): Double =
        players.minOfOrNull { Villages.flat(it.x, it.z, key) } ?: Double.MAX_VALUE

    /**
     * Who is awake. A record with a player [near] it is LIVE and seen at [now]; the rest, the ones seen most lately first (then by
     * key), are SLEEPING while fewer than [cap] villages are awake and PAUSED past that ([paused] is told of each). A LIVE village is
     * never paused, however many there are; a LOST one is left as it is. Answers whether any record's state changed.
     */
    fun settle(records: Collection<VillageRec>, now: Long, cap: Int, near: (VillageRec) -> Boolean, paused: (VillageRec) -> Unit): Boolean {
        var live = 0
        var changed = false
        val rest = ArrayList<VillageRec>()
        for (rec in records) {
            if (rec.state == Live.LOST) continue
            if (near(rec)) {
                rec.seen = now
                live++
                if (rec.state != Live.LIVE) {
                    rec.state = Live.LIVE
                    changed = true
                }
            } else {
                rest.add(rec)
            }
        }
        val room = (cap - live).coerceAtLeast(0)
        rest.sortWith(compareByDescending<VillageRec> { it.seen }.thenBy { it.key })
        for ((i, rec) in rest.withIndex()) {
            val want = if (i < room) Live.SLEEPING else Live.PAUSED
            if (rec.state != want) {
                rec.state = want
                changed = true
            }
            if (want == Live.PAUSED) paused(rec)
        }
        return changed
    }

    private fun look(level: ServerLevel) {
        val data = VillageData.get(level)
        val zones = TownZones.get(level)
        if (data.records.isEmpty() && zones.zones.none { it.village }) return
        val now = level.gameTime
        val day = VillageBoards.day(level)
        val t = LevelTerrain(level)
        var dirty = false

        // Bells gone, put back, and long gone
        for (rec in data.records.values.toList()) {
            if (rec.state == Live.LOST) {
                when {
                    bellOf(t, rec) == Stands.YES -> {
                        restore(level, data, rec)
                        dirty = true
                    }
                    rec.lostDay < 0L -> {
                        rec.lostDay = day
                        dirty = true
                    }
                    day - rec.lostDay >= LOST_DAYS -> {
                        drop(level, data, rec)
                        dirty = true
                    }
                }
            } else if (bellOf(t, rec) == Stands.NO && boardOf(t, rec) == Stands.NO) {
                lose(level, rec, day)
                dirty = true
            }
        }

        // Who is near: LIVE; the rest follow the cap, the ones seen last paused first
        val players = level.players()
        if (settle(data.records.values, now, cap(), { near(players, it.key) }, { Villages.forget(it.key) })) dirty = true

        // The zones follow the records
        val have = HashSet<Long>()
        for (z in zones.zones) if (z.village) have.add(z.center.asLong())
        val wanted = HashSet<Long>()
        for (rec in data.records.values) if (rec.state != Live.LOST) wanted.add(rec.key)
        if (have.any { it !in wanted }) {
            zones.zones.removeIf { it.village && it.center.asLong() !in wanted }
            zones.setDirty()
        }
        for (rec in data.records.values) if (rec.state != Live.LOST && rec.key !in have) zone(level, rec)

        // A LIVE village is counted again now and then, and at once if nothing is kept of it
        for (rec in data.records.values) {
            if (rec.state != Live.LIVE) continue
            val stale = rec.counted < 0L || now < rec.counted || now - rec.counted >= RECOUNT
            if (stale || Villages.places(level, rec.key) == null) queue(rec.key, false)
        }
        if (dirty) data.setDirty()
    }

    // ---- Bells and boards ----

    /** Whether the bell of [rec] stands: its block is looked at only if its chunk is loaded. */
    fun bellOf(t: Terrain, rec: VillageRec): Stands {
        val s = t.state(BlockPos.getX(rec.key), BlockPos.getY(rec.key), BlockPos.getZ(rec.key)) ?: return Stands.UNKNOWN
        return if (s.`is`(Blocks.BELL)) Stands.YES else Stands.NO
    }

    /** Whether the board of [rec] stands (a village with no board known has none). */
    fun boardOf(t: Terrain, rec: VillageRec): Stands {
        if (rec.board < 0L) return Stands.NO
        if (!t.loaded(BlockPos.getX(rec.board) shr 4, BlockPos.getZ(rec.board) shr 4)) return Stands.UNKNOWN
        return if (t.board(rec.board) != null) Stands.YES else Stands.NO
    }

    // ---- Records made, lost, found again, dropped ----

    /** A bell was seen for the first time: its record is made, its zone stands, and its census comes first in the queue. */
    fun make(level: ServerLevel, data: VillageData, bell: BlockPos) {
        val key = bell.asLong()
        val day = VillageBoards.day(level)
        val rec = VillageRec(key, name = VillageNames.of(bell), found = day, state = Live.LIVE, seen = level.gameTime)
        data.records[key] = rec
        // A bell that was moved: a village near it that has lost its bell is lost to this one
        val t = LevelTerrain(level)
        for (old in data.records.values.toList()) {
            if (old === rec || old.state == Live.LOST) continue
            if (Villages.flat(bell.x.toDouble() + 0.5, bell.z.toDouble() + 0.5, old.key) > MOVED.toDouble() * MOVED) continue
            if (bellOf(t, old) == Stands.NO) lose(level, old, day)
        }
        data.setDirty()
        zone(level, rec)
        queue(key, true)
        Guildmark.LOGGER.info("A village was found, its bell at {} ({})", bell.toShortString(), rec.name)
        Villages.tell("found") { it.made(level, rec) }
    }

    /** Whether a bell at [bell] is another of a village's own: one of a village whose bell stands, within [SPARE] blocks. */
    fun spare(level: ServerLevel, data: VillageData, bell: BlockPos): Boolean {
        var t: LevelTerrain? = null
        for (rec in data.records.values) {
            if (rec.state == Live.LOST) continue
            val dx = abs(BlockPos.getX(rec.key) - bell.x)
            val dy = abs(BlockPos.getY(rec.key) - bell.y)
            val dz = abs(BlockPos.getZ(rec.key) - bell.z)
            if (dx > SPARE || dy > SPARE || dz > SPARE) continue
            if (t == null) t = LevelTerrain(level)
            if (bellOf(t, rec) != Stands.NO) return true
        }
        return false
    }

    /** A bell the registry knows was seen standing: a lost village is found again; one never counted is counted. */
    fun sighted(level: ServerLevel, rec: VillageRec) {
        if (rec.state == Live.LOST) restore(level, VillageData.get(level), rec)
        else if (rec.counted < 0L && rec.state != Live.PAUSED) queue(rec.key, false)
    }

    private fun lose(level: ServerLevel, rec: VillageRec, day: Long) {
        rec.state = Live.LOST
        rec.lostDay = day
        Villages.forget(rec.key)
        unqueue(rec.key)
        removeZone(level, rec.key)
        VillageData.get(level).setDirty()
        Guildmark.LOGGER.info("The village at {} ({}) is lost: its bell is gone and no board stands. It is kept {} days", BlockPos.of(rec.key).toShortString(), rec.name, LOST_DAYS)
        Villages.tell("lost") { it.lost(level, rec) }
    }

    private fun restore(level: ServerLevel, data: VillageData, rec: VillageRec) {
        rec.state = Live.SLEEPING
        rec.lostDay = -1L
        data.setDirty()
        zone(level, rec)
        queue(rec.key, true)
        Guildmark.LOGGER.info("The village at {} ({}) is found again: its bell stands", BlockPos.of(rec.key).toShortString(), rec.name)
        Villages.tell("found again") { it.restored(level, rec) }
    }

    private fun drop(level: ServerLevel, data: VillageData, rec: VillageRec) {
        Guildmark.LOGGER.info("The village at {} ({}) is dropped", BlockPos.of(rec.key).toShortString(), rec.name)
        Villages.tell("dropped") { it.dropped(level, rec) }
        data.records.remove(rec.key)
        Villages.forget(rec.key)
        unqueue(rec.key)
        removeZone(level, rec.key)
        data.setDirty()
    }

    /** `village clear`: the record is forgotten at once (it is made afresh the next time its bell is seen). */
    fun clear(level: ServerLevel, rec: VillageRec) {
        drop(level, VillageData.get(level), rec)
    }

    // ---- Zones ----

    /** The town zone of [rec] is made, or put right if its radius changed; none if the village stands in a capital. */
    fun zone(level: ServerLevel, rec: VillageRec) {
        val overworld = level.server.overworld()
        val zones = TownZones.get(overworld)
        val bell = BlockPos.of(rec.key)
        val radius = zoneRadius(overworld, bell, rec.radius)
        val old = zones.zones.firstOrNull { it.village && it.center == bell }
        when {
            radius <= 0 -> if (old != null) {
                zones.zones.remove(old)
                zones.setDirty()
            }
            old == null || old.radius != radius -> zones.add(bell, radius, true)
        }
    }

    private fun removeZone(level: ServerLevel, key: Long) {
        val zones = TownZones.get(level.server.overworld())
        if (zones.zones.removeIf { it.village && it.center.asLong() == key }) zones.setDirty()
    }

    /**
     * The radius a village's zone may have: its own, or smaller where its square would take in a capital's ground (a zone is
     * looked at before a capital in `Towns.at`, so it must not cover any of one), or none if its bell is in a capital. A capital's
     * ground is looked for only in chunks that are loaded.
     */
    private fun zoneRadius(level: ServerLevel, bell: BlockPos, radius: Int): Int {
        if (Furnish.capitalAt(level, bell) != null) return 0
        var r = radius
        while (r >= 8) {
            if (!reaches(level, bell, r)) return r
            r -= 4
        }
        return 0
    }

    /** Whether any of the eight points round the middle of a square of radius [r] about [bell] is in a capital's ground. */
    private fun reaches(level: ServerLevel, bell: BlockPos, r: Int): Boolean {
        for (dx in intArrayOf(-r, 0, r)) {
            for (dz in intArrayOf(-r, 0, r)) {
                if (dx == 0 && dz == 0) continue
                if (Furnish.capitalAt(level, BlockPos(bell.x + dx, bell.y, bell.z + dz)) != null) return true
            }
        }
        return false
    }

    // ---- The census ----

    /** Counts the village [key] ahead of the others; [who] is told when it is done. */
    fun ask(key: Long, who: UUID?) {
        asks[key] = Ask(who)
        // One in hand for it is begun again: the world has changed since it began
        if (job?.key == key) job = null
        queue(key, true)
    }

    private fun queue(key: Long, front: Boolean) {
        if (job?.key == key || key in queue) {
            if (front && key in queue) {
                queue.remove(key)
                queue.add(0, key)
            }
            return
        }
        if (front) queue.add(0, key) else queue.add(key)
    }

    private fun unqueue(key: Long) {
        queue.remove(key)
        asks.remove(key)
        if (job?.key == key) job = null
    }

    /** The count of the village nearest a player (the one asked for first), skipping any that is gone, lost, paused or not loaded. */
    private fun start(level: ServerLevel): VillageCensus.Job? {
        val data = VillageData.get(level)
        val players = level.players()
        var best = -1L
        var bestD = Double.MAX_VALUE
        var found = false
        val dead = ArrayList<Long>()
        for (key in queue) {
            val rec = data.records[key]
            val asked = asks.containsKey(key)
            if (rec == null || rec.state == Live.LOST || (rec.state == Live.PAUSED && !asked)) {
                dead.add(key)
                continue
            }
            if (level.chunkSource.getChunkNow(BlockPos.getX(key) shr 4, BlockPos.getZ(key) shr 4) == null) {
                dead.add(key)
                continue
            }
            val d = if (asked) -1.0 else nearest(players, key)
            if (!found || d < bestD) {
                found = true
                best = key
                bestD = d
            }
        }
        for (key in dead) {
            queue.remove(key)
            asks.remove(key)
        }
        if (!found) return null
        queue.remove(best)
        val ask = asks.remove(best)
        return VillageCensus.Job(best, BlockPos.of(best), Villages.places(level, best)?.found, ask?.who, level.gameTime)
    }

    /** One tick of the count in hand, within what the budget leaves of the census's share. */
    private fun census(level: ServerLevel) {
        var j = job
        if (j == null) {
            if (queue.isEmpty()) return
            j = start(level) ?: return
            job = j
        }
        val columns = VillageBudget.columns()
        val nanos = VillageBudget.censusNanos()
        if (columns < MIN_COLUMNS || nanos < MIN_NANOS) return
        val begun = System.nanoTime()
        val used = j.step(LevelTerrain(level), begun + nanos, columns)
        VillageBudget.usedColumns(used)
        if (j.done) {
            job = null
            finish(level, j)
        }
        val spent = System.nanoTime() - begun
        VillageBudget.spent(spent)
        VillageCensus.Perf.tick(spent)
    }

    /** What a count found and changed: [before] is the count it follows (none for the first this session), [diff] what it did to the record's homes. */
    class Counted(val rec: VillageRec, val before: Found?, val found: Found, val diff: VillageCensus.Diff)

    /** A count that runs to its end in this tick, whatever it costs (`village census now`); what it did, or null if the village is gone. */
    fun countNow(level: ServerLevel, rec: VillageRec): Counted? {
        unqueue(rec.key)
        // One count at a time (they share a flood and a scanner): the one in hand starts again afterwards
        val running = job
        job = null
        if (running != null) queue(running.key, true)
        val j = VillageCensus.Job(rec.key, BlockPos.of(rec.key), Villages.places(level, rec.key)?.found, null, level.gameTime)
        val t = LevelTerrain(level)
        while (!j.done) j.step(t, Long.MAX_VALUE, Int.MAX_VALUE)
        return finish(level, j)
    }

    /** A count is done: its homes are put into the record, its places kept, its zone put right, and whoever asked is told. */
    private fun finish(level: ServerLevel, job: VillageCensus.Job): Counted? {
        val found = job.found ?: return null
        val data = VillageData.get(level)
        val rec = data.records[job.key] ?: return null
        if (rec.state == Live.LOST) return null
        val before = Villages.places(level, job.key)?.found
        val bell = BlockPos.of(job.key)
        // A village is named for its board, as the sieges and the festivals know it: once there is a board, the name from the bell gives way
        val anchor = found.board
        val rename = rec.name.isEmpty() || (anchor != null && rec.board < 0L && rec.name == VillageNames.of(bell))
        val (ids, diff) = VillageCensus.merge(rec, found, LevelTerrain(level))
        if (rename) rec.name = VillageNames.of(anchor ?: bell)
        val places = VillagePlaces(bell, rec.name, found, ids, rec.radius)
        if (rec.state != Live.PAUSED) Villages.keep(rec.key, places)
        data.setDirty()
        zone(level, rec)
        if (before == null) {
            Guildmark.LOGGER.info("Counted the village at {} ({}): {}", bell.toShortString(), rec.name, VillageCensus.changes(null, found))
        }
        val counted = Counted(rec, before, found, diff)
        Villages.tell("counted") { it.counted(level, rec, places) }
        job.notify?.let { VillageInfoCommand.told(level, it, counted) }
        return counted
    }
}
