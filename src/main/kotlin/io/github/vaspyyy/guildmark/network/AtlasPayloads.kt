package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/** One place on the compass and the map, as the client draws it (see Atlas). */
data class AtlasMark(
    val kind: Int,
    val pos: BlockPos,
    /** A lair's or the throne's name key, or an island's name. Villages are named from where their board stands (VillageNames). */
    val label: String,
    /** How many advances a village has built, a lair's rank, or how far an island's land reaches (blocks). */
    val size: Int,
    val flags: Int,
    /** The guild holding the village and its colour, or "" and 0. */
    val holder: String,
    val color: Int,
    /** The player's standing tier with the village; for an island, what sort it is (IslandMarks.ref). */
    val standing: Int,
    /** The named characters who live there. */
    val people: List<String>,
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        const val HALL = 1
        const val HELD = 2
        const val YOURS = 4
        const val CLEARED = 8
        const val AWAKE = 16
        const val VAULT = 32
        const val ROAD = 64
        /** The Wardfire the player last rested at, where they'll wake. */
        const val RESTED = 128
        /** A capital city, walled and built from the start. */
        const val CAPITAL = 256

        val STREAM_CODEC: StreamCodec<ByteBuf, AtlasMark> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AtlasMark::kind,
            BlockPos.STREAM_CODEC, AtlasMark::pos,
            ByteBufCodecs.STRING_UTF8, AtlasMark::label,
            ByteBufCodecs.VAR_INT, AtlasMark::size,
            ByteBufCodecs.VAR_INT, AtlasMark::flags,
            ByteBufCodecs.STRING_UTF8, AtlasMark::holder,
            ByteBufCodecs.INT, AtlasMark::color,
            ByteBufCodecs.VAR_INT, AtlasMark::standing,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), AtlasMark::people,
            ::AtlasMark,
        )
    }
}

/** A road on the map: its bends as x, z pairs, and whether it's paved all the way yet. */
data class AtlasRoad(val points: List<Int>, val finished: Boolean) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, AtlasRoad> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), AtlasRoad::points,
            ByteBufCodecs.BOOL, AtlasRoad::finished,
            ::AtlasRoad,
        )
    }
}

/**
 * Server sends the places this player has found in the dimension they're in, the roads between them,
 * and any found just now (for the "discovered" banner).
 */
data class AtlasPayload(
    val dimension: ResourceKey<Level>,
    val marks: List<AtlasMark>,
    val roads: List<AtlasRoad>,
    val discovered: List<BlockPos>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<AtlasPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<AtlasPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "atlas"))

        val STREAM_CODEC: StreamCodec<ByteBuf, AtlasPayload> = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), AtlasPayload::dimension,
            AtlasMark.STREAM_CODEC.apply(ByteBufCodecs.list()), AtlasPayload::marks,
            AtlasRoad.STREAM_CODEC.apply(ByteBufCodecs.list()), AtlasPayload::roads,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), AtlasPayload::discovered,
            ::AtlasPayload,
        )

        fun handle(payload: AtlasPayload, context: IPayloadContext) {
            ClientHooks.atlas(payload)
        }
    }
}

/** Where one party mate stands. */
data class PartyMark(val id: UUID, val x: Int, val z: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, PartyMark> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PartyMark::id,
            ByteBufCodecs.VAR_INT, PartyMark::x,
            ByteBufCodecs.VAR_INT, PartyMark::z,
            ::PartyMark,
        )
    }
}

/** Server sends where the player's party mates are, those in the same dimension, every second. */
data class PartyPositionsPayload(val mates: List<PartyMark>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PartyPositionsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PartyPositionsPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "party_positions"))

        val STREAM_CODEC: StreamCodec<ByteBuf, PartyPositionsPayload> =
            PartyMark.STREAM_CODEC.apply(ByteBufCodecs.list()).map(::PartyPositionsPayload, PartyPositionsPayload::mates)

        fun handle(payload: PartyPositionsPayload, context: IPayloadContext) {
            ClientHooks.partyPositions(payload)
        }
    }
}
