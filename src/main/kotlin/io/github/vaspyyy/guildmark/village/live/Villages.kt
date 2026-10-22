package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Resident
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The villages the world has counted, each a [VillageRec] kept by the key of its bell (living-world/villages/design.md
 * section 4). The names and signatures here are the contract of design section 19.3, which every part of the villages
 * builds against; the first part to fill them in is V1 (the registry and the census), and V10 for [died] and [note].
 * Until then each answers with the empty answer, a null, an empty list or nothing done, so what asks never fails and
 * the game plays as it did. Only the list of those who close the day ([onDay], [closeDay]) is real already.
 */
object Villages {
    /** Those who keep a daily rhythm, in the order they were added (see [onDay]). */
    private val closers = ArrayList<DayClose>()

    /** At setup: the registry's listeners, which the first part to count villages adds. */
    fun register() {}

    /** A bell was seen within 64 blocks of a player: a record is made for it if there is none (called from `VillageBoards.checkNearbyVillages`). */
    fun sight(level: ServerLevel, bell: BlockPos) {}

    /** The record of the village whose bell is [key], or null. */
    fun byKey(level: ServerLevel, key: Long): VillageRec? = null

    /** The record whose box takes in [pos], or null; the loaded records only, and no chunk is loaded to find it. */
    fun at(level: ServerLevel, pos: BlockPos): VillageRec? = null

    /** The records whose bell is within [blocks] of [pos], the nearest first. */
    fun near(level: ServerLevel, pos: BlockPos, blocks: Int): List<VillageRec> = emptyList()

    /** The places of the village [key] as its census found them, or null until the census has run. */
    fun places(level: ServerLevel, key: Long): VillagePlaces? = null

    /** The records that are [Live.LIVE] or [Live.SLEEPING], the nearest a player first. */
    fun awake(level: ServerLevel): List<VillageRec> = emptyList()

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
