package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.island.IslandClient
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** One of an island's own sounds, as the client needs it: what makes it (SoundSpot's ordinal) and where. */
data class IslandSoundSpot(val spot: Int, val x: Int, val y: Int, val z: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, IslandSoundSpot> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IslandSoundSpot::spot,
            ByteBufCodecs.INT, IslandSoundSpot::x,
            ByteBufCodecs.VAR_INT, IslandSoundSpot::y,
            ByteBufCodecs.INT, IslandSoundSpot::z,
            ::IslandSoundSpot,
        )
    }
}

/**
 * Server tells the player they've come into an island's waters: what it is ([ref], see IslandMarks), its middle, its
 * sea, how far its land reaches, and where its own sounds come from; or, with [radius] 0, that they've left them.
 */
data class IslandPayload(
    val ref: Int,
    val cx: Int,
    val cz: Int,
    val sea: Int,
    val radius: Int,
    val spots: List<IslandSoundSpot>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<IslandPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<IslandPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "island"))

        val STREAM_CODEC: StreamCodec<ByteBuf, IslandPayload> = StreamCodec.composite(
            ByteBufCodecs.INT, IslandPayload::ref,
            ByteBufCodecs.INT, IslandPayload::cx,
            ByteBufCodecs.INT, IslandPayload::cz,
            ByteBufCodecs.VAR_INT, IslandPayload::sea,
            ByteBufCodecs.VAR_INT, IslandPayload::radius,
            IslandSoundSpot.STREAM_CODEC.apply(ByteBufCodecs.list()), IslandPayload::spots,
            ::IslandPayload,
        )

        val NONE = IslandPayload(0, 0, 0, 0, 0, listOf())

        fun handle(payload: IslandPayload, context: IPayloadContext) {
            IslandClient.receive(payload)
        }
    }
}
