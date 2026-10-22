package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.Hauls
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * What the haul tickets (living-world/trader/design.md, Part 2) send between the market screen and the server. Each other part of the
 * trader's life has payloads of its own, in its own file, registered from its own `register(modBus)`; these are part A's, registered
 * by [Hauls.register].
 *
 * The client asks to do [action] (one of [TAKE] and the rest) with the ticket numbered [haul] at the shop whose screen it has open;
 * the server decides everything and answers with a fresh view of the screen, as it does for an order (see MarketOrderPayload).
 */
data class MarketHaulPayload(val haul: Int, val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MarketHaulPayload> = TYPE

    companion object {
        /** Book an open ticket; deliver what is carried of its good; give a booked one up (a booking is never lost to anything but the player's choice or the due hour). */
        const val TAKE = 0
        const val DELIVER = 1
        const val GIVE_UP = 2

        val TYPE: CustomPacketPayload.Type<MarketHaulPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_haul"))

        val STREAM_CODEC: StreamCodec<ByteBuf, MarketHaulPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MarketHaulPayload::haul,
            ByteBufCodecs.VAR_INT, MarketHaulPayload::action,
            ::MarketHaulPayload,
        )

        fun handle(payload: MarketHaulPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Hauls.act(player, payload.haul, payload.action)
        }
    }
}
