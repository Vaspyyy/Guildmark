package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Resident
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.UUID
import kotlin.math.abs

/**
 * The villages the world has counted, each a [VillageRec] kept by the key of its bell (living-world/villages/design.md
 * section 4). The names and signatures here are the contract of design section 19.3, which every part of the villages
 * builds against. Part V1 fills in the registry: a record is made the first time a player comes within 64 blocks of a bell
 * ([sight]), its census runs a little each tick ([VillageTick]) and its state follows the players ([Live]). [died] and [note]
 * are V10's, and answer nothing until it fills them.
 *
 * Everything here is the overworld's, as the markets and the folk are; a bell in another dimension is not counted. With Living
 * Villages off ([enabled]) nothing is counted, nothing is written and no zone stands: the records are kept as they were for when
 * it is on again.
 */
object Villages {
    /** Those who keep a daily rhythm, in the order they were added (see [onDay]). */
    private val closers = ArrayList<DayClose>()

    /** Those told what happens to the villages of the registry (see [listen]). */
    private val listeners = ArrayList<Listener>()

    /** What each awake village's last census found, as the places its people read; not saved, since a census makes it afresh. */
    private val kept = HashMap<Long, VillagePlaces>()

    /** Bells that stand inside a capital, which are not villages (not looked at again). */
    private val capitals = HashSet<Long>()

    /** Whether Living Villages is on; on until the settings are loaded. */
    fun enabled(): Boolean = try {
        GuildmarkConfig.LIVING_VILLAGES.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** At setup: the clock's listener, the tick that keeps the registry, and the commands that read it. */
    fun register() {
        Comings.listen(object : Comings.Listener {
            override fun back(level: ServerLevel, days: Long) = takeBack(level, days)
        })
        VillageTick.register()
        VillageInfoCommand.init()
        io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt.register()
        io.github.vaspyyy.guildmark.village.live.days.VillageSceneCommand.init()
        VillageLamps.init()
    }

    /**
     * A bell was seen within 64 blocks of a player: a record is made for it if there is none (called from
     * `VillageBoards.checkNearbyVillages`), and counted from the next tick on. A bell that stands in a capital is not a
     * village's, and nor is a second bell within 16 blocks of a village's own. A lost village whose bell is seen is found again.
     */
    fun sight(level: ServerLevel, bell: BlockPos) {
        if (!enabled() || level.dimension() != Level.OVERWORLD) return
        try {
            seen(level, bell)
        } catch (e: RuntimeException) {
            // A bell that can't be taken in costs that bell, not the walk that found it
            Guildmark.LOGGER.warn("A bell at {} could not be taken into the registry of villages", bell.toShortString(), e)
        }
    }

    private fun seen(level: ServerLevel, bell: BlockPos) {
        val data = VillageData.get(level)
        val key = bell.asLong()
        val rec = data.records[key]
        if (rec != null) {
            VillageTick.sighted(level, rec)
            return
        }
        if (key in capitals) return
        if (Furnish.capitalAt(level, bell) != null) {
            if (capitals.size > 512) capitals.clear()
            capitals.add(key)
            return
        }
        if (VillageTick.spare(level, data, bell)) return
        VillageTick.make(level, data, bell)
    }

    /** The record of the village whose bell is [key], or null. A lost village's too, until it is dropped. */
    fun byKey(level: ServerLevel, key: Long): VillageRec? = VillageData.get(level).records[key]

    /** Every record, lost ones too, in the order of their keys. */
    fun all(level: ServerLevel): List<VillageRec> = VillageData.get(level).records.values.sortedBy { it.key }

    /**
     * The record whose box takes in [pos] (the square of its radius round the bell), or null; the nearest bell if two do. A lost
     * village is not here. Reads the records in memory, so no chunk is loaded to find it.
     */
    fun at(level: ServerLevel, pos: BlockPos): VillageRec? {
        var best: VillageRec? = null
        var bestD = Double.MAX_VALUE
        for (rec in VillageData.get(level).records.values) {
            if (rec.state == Live.LOST) continue
            val dx = abs(pos.x - BlockPos.getX(rec.key))
            val dz = abs(pos.z - BlockPos.getZ(rec.key))
            if (dx > rec.radius || dz > rec.radius) continue
            val d = dx.toDouble() * dx + dz.toDouble() * dz
            if (d < bestD) {
                bestD = d
                best = rec
            }
        }
        return best
    }

    /** The records whose bell is within [blocks] of [pos] (across, not up and down), the nearest first; a lost village is not here. */
    fun near(level: ServerLevel, pos: BlockPos, blocks: Int): List<VillageRec> {
        val limit = blocks.toDouble() * blocks
        return VillageData.get(level).records.values
            .filter { it.state != Live.LOST }
            .map { it to flat(pos.x, pos.z, it.key) }
            .filter { it.second <= limit }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * The places of the village [key] as its census found them, or null until the census has run (and for a village that is
     * paused, lost, or from an earlier session whose census has not come round again: nothing is kept for those).
     */
    @Suppress("UNUSED_PARAMETER")
    fun places(level: ServerLevel, key: Long): VillagePlaces? = kept[key]

    /** The records that are [Live.LIVE] or [Live.SLEEPING], the nearest a player first (those with no player to measure from, by their keys). */
    fun awake(level: ServerLevel): List<VillageRec> {
        val players = level.players()
        return VillageData.get(level).records.values
            .filter { it.state == Live.LIVE || it.state == Live.SLEEPING }
            .sortedWith(compareBy<VillageRec> { rec ->
                players.minOfOrNull { flat(it.x, it.z, rec.key) } ?: Double.MAX_VALUE
            }.thenBy { it.key })
    }

    /**
     * Counts the village [key] again, ahead of the others waiting (a project built, a mend finished, the command): the new count
     * is told to [notify], if given. Does nothing for a village that is not here or is lost.
     */
    fun recount(level: ServerLevel, key: Long, notify: UUID? = null) {
        if (!enabled()) return
        val rec = byKey(level, key) ?: return
        if (rec.state == Live.LOST) return
        VillageTick.ask(key, notify)
    }

    /** Adds [listener] (once, at setup) to those told what happens to the villages of the registry. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Something told what happens to the villages of the registry; each call is guarded, so one that fails costs the others nothing. */
    interface Listener {
        /** A village was found: its record is made, and its first census is about to run. */
        fun made(level: ServerLevel, rec: VillageRec) {}

        /** A census finished: the record's homes and the village's [places] are new. */
        fun counted(level: ServerLevel, rec: VillageRec, places: VillagePlaces) {}

        /** The village's bell is gone and no board stands: it is [Live.LOST] from now, and dropped after 30 days. */
        fun lost(level: ServerLevel, rec: VillageRec) {}

        /** A lost village's bell stands again. */
        fun restored(level: ServerLevel, rec: VillageRec) {}

        /** The record is being dropped: 30 days lost, or `village clear`. It is already out of the registry's reach to ask by [rec]'s key afterwards. */
        fun dropped(level: ServerLevel, rec: VillageRec) {}
    }

    internal fun tell(what: String, call: (Listener) -> Unit) {
        for (l in listeners.toList()) {
            try {
                call(l)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Something told that a village was {} failed", what, e)
            }
        }
    }

    internal fun keep(key: Long, places: VillagePlaces) {
        kept[key] = places
    }

    internal fun forget(key: Long) {
        kept.remove(key)
    }

    /** Everything kept in memory is forgotten, as the server stops. */
    internal fun forgetAll() {
        kept.clear()
        capitals.clear()
    }

    /** The clock was set back [days] days: every date the records keep goes back with it, none below zero. */
    private fun takeBack(level: ServerLevel, days: Long) {
        val data = VillageData.get(level)
        if (data.records.isEmpty()) return
        for (rec in data.records.values) rec.back(days)
        data.setDirty()
    }

    /** The squared distance across the ground from ([x], [z]) to the bell [key]. */
    internal fun flat(x: Int, z: Int, key: Long): Double = flat(x.toDouble(), z.toDouble(), key)

    internal fun flat(x: Double, z: Double, key: Long): Double {
        val dx = x - (BlockPos.getX(key) + 0.5)
        val dz = z - (BlockPos.getZ(key) + 0.5)
        return dx * dx + dz * dz
    }

    /**
     * Adds [listener] (once, at setup) to those called at each village's close of the day, at two in the morning, in the
     * order they were added: the bellman's week and the pinned notes, the pedlar's roster, the build crew's morning, the
     * damage list, the golem top-up and the dead's candles, births and newcomers.
     */
    fun onDay(listener: DayClose) {
        if (listener !in closers) closers += listener
    }

    /**
     * Calls those who close the day for [rec]'s [day]: one that fails is logged, and the rest still run. The market's step
     * calls it after the village's fortunes close, so a day is closed once.
     */
    fun closeDay(level: ServerLevel, rec: VillageRec, day: Long) {
        for (closer in closers.toList()) {
            try {
                closer.close(level, rec, day)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Something closing the day in the village at {} failed", BlockPos.of(rec.key).toShortString(), e)
            }
        }
    }

    /**
     * [r] died, or was put to rest with no body (from `Residents.pass`, the record half of a death): if they lived in a
     * village, they go in its dead list, with the [cause] if it is known ("" if it isn't). Does nothing for a capital's
     * people.
     */
    fun died(level: ServerLevel, r: Resident, cause: String) {}

    /** A line in the chronicle of the village [key]: [kind] (built, siege, raid, rescued, born, arrived, died, mended) and what it was about; anyone may call. */
    fun note(level: ServerLevel, key: Long, kind: String, arg: String = "") {}
}

/** Something that keeps a daily rhythm in a village: called at its close of the day (see [Villages.onDay]). */
fun interface DayClose {
    fun close(level: ServerLevel, rec: VillageRec, day: Long)
}
