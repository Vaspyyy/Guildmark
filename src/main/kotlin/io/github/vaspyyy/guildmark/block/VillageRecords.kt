package io.github.vaspyyy.guildmark.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * What a village's quest board knew, kept when the board comes down (broken, blown up or built over): its standing
 * with every player, its advances, the road it's owed and its sieges. The next board put up in reach of where it stood,
 * or of the village's bell, takes it all back (see VillageBoards.setUp), so breaking or moving the board costs the
 * village nothing. The board itself still keeps all this while it stands; only boards that came down are listed here.
 */
class VillageRecords(val records: MutableList<VillageRecord> = mutableListOf()) : SavedData() {
    /** Keeps [record]; an older one kept for the same spot gives way to it. */
    fun keep(record: VillageRecord) {
        records.removeIf { it.pos == record.pos }
        records.add(record)
        setDirty()
    }

    /** Takes back the record nearest [anchor] of those in reach of it or of the village's [bell], if there is one. */
    fun take(anchor: BlockPos, bell: BlockPos?): VillageRecord? {
        val reach = REACH * REACH
        val record = records
            .filter { it.pos.distSqr(anchor) <= reach || bell != null && it.pos.distSqr(bell) <= reach }
            .minByOrNull { it.pos.distSqr(anchor) } ?: return null
        records.remove(record)
        setDirty()
        return record
    }

    companion object {
        /** As far as a board's village reaches: the same 48 blocks a village looks for its board in. */
        private const val REACH = 48.0

        private val CODEC: Codec<VillageRecords> = RecordCodecBuilder.create { i ->
            i.group(VillageRecord.CODEC.listOf().optionalFieldOf("records", listOf()).forGetter { it.records })
                .apply(i) { VillageRecords(it.toMutableList()) }
        }
        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "village_records"), Supplier { VillageRecords() }, CODEC)

        /** The records of [level]'s own boards. */
        fun get(level: ServerLevel): VillageRecords = level.dataStorage.computeIfAbsent(TYPE)
    }
}

/** A village as its board knew it when the board at [pos] came down on [day] (see QuestBoardBlockEntity). */
data class VillageRecord(
    val pos: BlockPos,
    val day: Long,
    val advanceIndex: Int,
    val advanceVersion: Int,
    val advancePoints: Int,
    val roadOwed: Boolean,
    val roadLooked: Long,
    val siegeLost: Long,
    val siegeWon: Long,
    val capital: Boolean,
    val standing: Map<UUID, Int>,
) {
    companion object {
        val CODEC: Codec<VillageRecord> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(VillageRecord::pos),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(VillageRecord::day),
                Codec.INT.optionalFieldOf("advance_index", 0).forGetter(VillageRecord::advanceIndex),
                Codec.INT.optionalFieldOf("advance_version", 1).forGetter(VillageRecord::advanceVersion),
                Codec.INT.optionalFieldOf("advance_points", 0).forGetter(VillageRecord::advancePoints),
                Codec.BOOL.optionalFieldOf("road_owed", false).forGetter(VillageRecord::roadOwed),
                Codec.LONG.optionalFieldOf("road_looked", -1L).forGetter(VillageRecord::roadLooked),
                Codec.LONG.optionalFieldOf("siege_lost", -100L).forGetter(VillageRecord::siegeLost),
                Codec.LONG.optionalFieldOf("siege_won", -100L).forGetter(VillageRecord::siegeWon),
                Codec.BOOL.optionalFieldOf("capital", false).forGetter(VillageRecord::capital),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("standing", mapOf()).forGetter(VillageRecord::standing),
            ).apply(i, ::VillageRecord)
        }
    }
}
