package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.festival.FestivalClient
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Server tells a nearby client about a festival: where its middle is and how big its square, the part of the day it's
 * at, whether the band is playing (and how far into the set, so the client plays the music on the same bars the dance
 * is on) and where its centrepiece stands. [kind] -1 means it's over (or the player has gone out of earshot).
 */
data class FestivalPayload(
    val site: Long,
    val kind: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val square: Int,
    /** Ticks since the set being played began, or -1 between sets. */
    val setAge: Int,
    val phase: Int,
    /** The centrepiece's crown (packed), or Long.MIN_VALUE. */
    val crown: Long,
    /** The town's name. */
    val town: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FestivalPayload> = TYPE

    val gone: Boolean get() = kind < 0

    companion object {
        val TYPE: CustomPacketPayload.Type<FestivalPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "festival"))

        val STREAM_CODEC: StreamCodec<ByteBuf, FestivalPayload> = StreamCodec.composite(
            ByteBufCodecs.LONG, FestivalPayload::site,
            ByteBufCodecs.VAR_INT, FestivalPayload::kind,
            ByteBufCodecs.DOUBLE, FestivalPayload::x,
            ByteBufCodecs.DOUBLE, FestivalPayload::y,
            ByteBufCodecs.DOUBLE, FestivalPayload::z,
            ByteBufCodecs.VAR_INT, FestivalPayload::square,
            ByteBufCodecs.VAR_INT, FestivalPayload::setAge,
            ByteBufCodecs.VAR_INT, FestivalPayload::phase,
            ByteBufCodecs.LONG, FestivalPayload::crown,
            ByteBufCodecs.STRING_UTF8, FestivalPayload::town,
            ::FestivalPayload,
        )

        /** "It's over" for [site]. */
        fun gone(site: Long): FestivalPayload = FestivalPayload(site, -1, 0.0, 0.0, 0.0, 0, -1, 0, Long.MIN_VALUE, "")

        fun send(player: ServerPlayer, payload: FestivalPayload) = PacketDistributor.sendToPlayer(player, payload)

        fun handle(payload: FestivalPayload, context: IPayloadContext) {
            FestivalClient.receive(payload)
        }
    }
}
