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
 * One haul ticket as a shop's market screen shows it to a player (living-world/trader/design.md, Part 2): number [id], the [good] (its id), the
 * [units] it asks in all and how many are [done] (in), how many the player has booked and still [mine] to bring, and how many nobody has booked
 * ([open]). [pay] is the Marks the units they hold (or, if they hold none, the ones they could take, or the ones left) pay them in all, with their
 * rung's perk. [kind] is the [io.github.vaspyyy.guildmark.trade.HaulKind]'s place in its list. [hours] are the market hours left until the ticket falls
 * due and [span] the hours it was posted for. [why] is the reason they cannot book it (a key under gui.guildmark.trade.haul.refused., "" if they can or
 * they have). Of the good they [carried] (in the pack and the carriers at their elbow), [deliverable] is how many the ticket will take from them now
 * (what the ledger says came a long way, up to what their booking still owes). [deposit] is what their booking holds, or what booking it would hold.
 * [who] names the others who hold a booking on it, and [seal] is the colour (0 to 5) of the seal of the first booker, the player's own first, or -1
 * if nobody has booked it.
 */
data class MarketHaul(
    val id: Int,
    val good: String,
    val units: Int,
    val done: Int,
    val mine: Int,
    val open: Int,
    val pay: Int,
    val kind: Int,
    val hours: Int,
    val span: Int,
    val why: String,
    val carried: Int,
    val deliverable: Int,
    val deposit: Int,
    val who: String,
    val seal: Int,
) {
    companion object {
        // Sixteen fields are more than StreamCodec.composite takes (fourteen), so they are written and read one after the other
        val STREAM_CODEC: StreamCodec<ByteBuf, MarketHaul> = StreamCodec.of<ByteBuf, MarketHaul>(
            { buf, h ->
                ByteBufCodecs.VAR_INT.encode(buf, h.id)
                ByteBufCodecs.STRING_UTF8.encode(buf, h.good)
                ByteBufCodecs.VAR_INT.encode(buf, h.units)
                ByteBufCodecs.VAR_INT.encode(buf, h.done)
                ByteBufCodecs.VAR_INT.encode(buf, h.mine)
                ByteBufCodecs.VAR_INT.encode(buf, h.open)
                ByteBufCodecs.VAR_INT.encode(buf, h.pay)
                ByteBufCodecs.VAR_INT.encode(buf, h.kind)
                ByteBufCodecs.VAR_INT.encode(buf, h.hours)
                ByteBufCodecs.VAR_INT.encode(buf, h.span)
                ByteBufCodecs.STRING_UTF8.encode(buf, h.why)
                ByteBufCodecs.VAR_INT.encode(buf, h.carried)
                ByteBufCodecs.VAR_INT.encode(buf, h.deliverable)
                ByteBufCodecs.VAR_INT.encode(buf, h.deposit)
                ByteBufCodecs.STRING_UTF8.encode(buf, h.who)
                ByteBufCodecs.VAR_INT.encode(buf, h.seal)
            },
            { buf ->
                MarketHaul(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                )
            },
        )
    }
}

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
