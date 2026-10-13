package io.github.vaspyyy.guildmark.region

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.region.RegionClient
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.io.ByteArrayOutputStream

/** A region as the client needs it: for the map, the title card and the music. */
data class RegionEntry(val id: Long, val kind: String, val name: String, val labelX: Int, val labelZ: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, RegionEntry> = StreamCodec.composite(
            ByteBufCodecs.LONG, RegionEntry::id,
            ByteBufCodecs.STRING_UTF8, RegionEntry::kind,
            ByteBufCodecs.STRING_UTF8, RegionEntry::name,
            ByteBufCodecs.VAR_INT, RegionEntry::labelX,
            ByteBufCodecs.VAR_INT, RegionEntry::labelZ,
            ::RegionEntry,
        )

        fun of(info: RegionInfo) = RegionEntry(info.id, info.kind.id, info.name, info.labelX, info.labelZ)
    }
}

/**
 * Server sends one cell's regions (see CellPlan) for the map: its lattice of region indexes, run-length
 * packed, and the regions themselves. Sent for the cells around the player and those they've been near.
 */
data class RegionCellPayload(
    val cell: Long,
    val i0: Int,
    val j0: Int,
    val width: Int,
    val height: Int,
    val runs: ByteArray,
    val regions: List<RegionEntry>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RegionCellPayload> = TYPE

    /** The lattice unpacked: a region index per point, row by row, -1 where it's another cell's. */
    fun grid(): ByteArray {
        val out = ByteArray(width * height) { -1 }
        var p = 0
        var i = 0
        while (i + 1 < runs.size && p < out.size) {
            val value = runs[i]
            val count = runs[i + 1].toInt() and 0xFF
            for (n in 0 until count) if (p < out.size) out[p++] = value
            i += 2
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is RegionCellPayload && other.cell == cell && other.runs.contentEquals(runs)
    override fun hashCode(): Int = cell.hashCode()

    companion object {
        val TYPE: CustomPacketPayload.Type<RegionCellPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "region_cell"))

        val STREAM_CODEC: StreamCodec<ByteBuf, RegionCellPayload> = StreamCodec.composite(
            ByteBufCodecs.LONG, RegionCellPayload::cell,
            ByteBufCodecs.VAR_INT, RegionCellPayload::i0,
            ByteBufCodecs.VAR_INT, RegionCellPayload::j0,
            ByteBufCodecs.VAR_INT, RegionCellPayload::width,
            ByteBufCodecs.VAR_INT, RegionCellPayload::height,
            ByteBufCodecs.BYTE_ARRAY, RegionCellPayload::runs,
            RegionEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), RegionCellPayload::regions,
            ::RegionCellPayload,
        )

        /** [plan] packed for sending: the lattice as (value, count) pairs, counts up to 255. */
        fun of(plan: CellPlan): RegionCellPayload {
            val out = ByteArrayOutputStream()
            var i = 0
            val grid = plan.grid
            while (i < grid.size) {
                val value = grid[i]
                var count = 1
                while (i + count < grid.size && grid[i + count] == value && count < 255) count++
                out.write(value.toInt())
                out.write(count)
                i += count
            }
            return RegionCellPayload(plan.cell, plan.i0, plan.j0, plan.width, plan.height, out.toByteArray(), plan.regions.map(RegionEntry::of))
        }

        fun handle(payload: RegionCellPayload, context: IPayloadContext) {
            RegionClient.receiveCell(payload)
        }
    }
}

/**
 * Server tells the player they've come into a region ([first] if they never had before), or with [id] 0
 * that they're in none (out of the overworld, or with the regions turned off).
 */
data class RegionEnteredPayload(val id: Long, val kind: String, val name: String, val first: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RegionEnteredPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RegionEnteredPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "region_entered"))

        val STREAM_CODEC: StreamCodec<ByteBuf, RegionEnteredPayload> = StreamCodec.composite(
            ByteBufCodecs.LONG, RegionEnteredPayload::id,
            ByteBufCodecs.STRING_UTF8, RegionEnteredPayload::kind,
            ByteBufCodecs.STRING_UTF8, RegionEnteredPayload::name,
            ByteBufCodecs.BOOL, RegionEnteredPayload::first,
            ::RegionEnteredPayload,
        )

        val NONE = RegionEnteredPayload(0L, "", "", false)

        fun handle(payload: RegionEnteredPayload, context: IPayloadContext) {
            RegionClient.receiveEntered(payload)
        }
    }
}
