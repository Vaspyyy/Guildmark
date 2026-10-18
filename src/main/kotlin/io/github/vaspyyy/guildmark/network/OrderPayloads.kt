package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ContractTracker
import io.github.vaspyyy.guildmark.economy.Stalls
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * One of a business's orders as its market screen shows it to a player: the [good] (its id) and how many of it are
 * wanted ([amount]; for a standing order, this week's), how many have come in ([delivered]), the Marks a unit pays this
 * player ([each]), whole days left to bring them ([days], 0 on the last; for one not yet taken, the days they'd have if
 * they took it now), whole days left to take it or answer the offer ([open], for one not yet taken), where it stands for
 * them ([state], one of [OPEN] and the rest), which week of a standing order this is ([week], 0 for an ordinary order),
 * how many of the good they carry ([carried]) and how many of those it will take ([deliverable]: none that came off
 * this market's own stalls lately), and who holds it if someone else does ([who]).
 */
data class MarketOrder(
    val id: Int,
    val good: String,
    val amount: Int,
    val delivered: Int,
    val each: Float,
    val days: Int,
    val open: Int,
    val state: Int,
    val week: Int,
    val carried: Int,
    val deliverable: Int,
    val who: String,
) {
    companion object {
        /** Posted for anyone to take. */
        const val OPEN = 0

        /** Taken by this player. */
        const val YOURS = 1

        /** Taken by another supplier. */
        const val PROMISED = 2

        /** Filled by this player, and offered to them again every week. */
        const val OFFERED = 3

        /** A standing order of this player's. */
        const val STANDING = 4

        val STREAM_CODEC: StreamCodec<ByteBuf, MarketOrder> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MarketOrder::id,
            ByteBufCodecs.STRING_UTF8, MarketOrder::good,
            ByteBufCodecs.VAR_INT, MarketOrder::amount,
            ByteBufCodecs.VAR_INT, MarketOrder::delivered,
            ByteBufCodecs.FLOAT, MarketOrder::each,
            ByteBufCodecs.VAR_INT, MarketOrder::days,
            ByteBufCodecs.VAR_INT, MarketOrder::open,
            ByteBufCodecs.VAR_INT, MarketOrder::state,
            ByteBufCodecs.VAR_INT, MarketOrder::week,
            ByteBufCodecs.VAR_INT, MarketOrder::carried,
            ByteBufCodecs.VAR_INT, MarketOrder::deliverable,
            ByteBufCodecs.STRING_UTF8, MarketOrder::who,
            ::MarketOrder,
        )
    }
}

/**
 * Client asks to do [action] (one of [TAKE] and the rest) with the order [order] at the business whose screen it has
 * open (see [Stalls.order]).
 */
data class MarketOrderPayload(val order: Int, val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MarketOrderPayload> = TYPE

    companion object {
        /** Take an open order; deliver what's carried of its good; give a held one up; accept or decline a standing order. */
        const val TAKE = 0
        const val DELIVER = 1
        const val GIVE_UP = 2
        const val ACCEPT = 3
        const val DECLINE = 4

        val TYPE: CustomPacketPayload.Type<MarketOrderPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_order"))

        val STREAM_CODEC: StreamCodec<ByteBuf, MarketOrderPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MarketOrderPayload::order,
            ByteBufCodecs.VAR_INT, MarketOrderPayload::action,
            ::MarketOrderPayload,
        )

        fun handle(payload: MarketOrderPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Stalls.order(player, payload.order, payload.action)
        }
    }
}

/**
 * One of a player's orders, for the contract tracker: the [good] (its id), how many are wanted ([amount]) and have
 * come in ([delivered]), the business ([shop], its id) and the capital ([place]) that want it, the market hour it's
 * due by ([due]), where the business is ([pos]), and which week of a standing order it is ([week], 0 for an
 * ordinary one).
 */
data class TrackedOrder(
    val id: Int,
    val good: String,
    val amount: Int,
    val delivered: Int,
    val shop: String,
    val place: String,
    val due: Long,
    val pos: BlockPos,
    val week: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, TrackedOrder> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrackedOrder::id,
            ByteBufCodecs.STRING_UTF8, TrackedOrder::good,
            ByteBufCodecs.VAR_INT, TrackedOrder::amount,
            ByteBufCodecs.VAR_INT, TrackedOrder::delivered,
            ByteBufCodecs.STRING_UTF8, TrackedOrder::shop,
            ByteBufCodecs.STRING_UTF8, TrackedOrder::place,
            ByteBufCodecs.VAR_LONG, TrackedOrder::due,
            BlockPos.STREAM_CODEC, TrackedOrder::pos,
            ByteBufCodecs.VAR_INT, TrackedOrder::week,
            ::TrackedOrder,
        )
    }
}

/** Server sends a player every order they hold, at every capital, for the contract tracker: on joining and whenever one changes. */
data class SupplierOrdersPayload(val orders: List<TrackedOrder>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SupplierOrdersPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SupplierOrdersPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "supplier_orders"))

        val STREAM_CODEC: StreamCodec<ByteBuf, SupplierOrdersPayload> =
            TrackedOrder.STREAM_CODEC.apply(ByteBufCodecs.list()).map(::SupplierOrdersPayload, SupplierOrdersPayload::orders)

        fun handle(payload: SupplierOrdersPayload, context: IPayloadContext) {
            ContractTracker.orders = payload.orders
        }
    }
}
