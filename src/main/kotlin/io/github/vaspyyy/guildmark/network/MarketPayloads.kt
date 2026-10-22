package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ui.MarketClient
import io.github.vaspyyy.guildmark.economy.Stalls
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * One of a shop's goods as the market screen shows it: whole units in the market's [stock] against what the town
 * likes to [keep], the lot a player can buy ([buyCount] for [buyMarks] Guild Marks, 0 for 0 when there's none to
 * sell) and the lot the market buys ([sellCount] for [sellMarks], 0 for 0 when it wants no more), how many more it
 * [wants], which way the price is going ([trend], 1 up, -1 down), how far it is off its usual ([off], the price
 * over the usual), why if that's far enough off to say ([why], a lang key that takes the good's name, "" if not;
 * [dear] which way), and how many of it the player [carried].
 *
 * The haul tickets (living-world/trader/design.md, Part 2) add the dealer's lot: of the goods the player carries that came a long way
 * ([hauledLeft] of them, whether or not a lot is offered), the lot the market buys at a dealer's share ([hauledCount] for [hauledMarks], 0 for 0
 * when there is none, or when it would be the ordinary lot).
 */
data class MarketGood(
    val good: String,
    val stock: Int,
    val keep: Int,
    val buyCount: Int,
    val buyMarks: Int,
    val sellCount: Int,
    val sellMarks: Int,
    val wants: Int,
    val trend: Int,
    val off: Float,
    val why: String,
    val dear: Boolean,
    val carried: Int,
    val hauledCount: Int = 0,
    val hauledMarks: Int = 0,
    val hauledLeft: Int = 0,
) {
    companion object {
        // Sixteen fields are more than StreamCodec.composite takes (fourteen), so they are written and read one after the other
        val STREAM_CODEC: StreamCodec<ByteBuf, MarketGood> = StreamCodec.of<ByteBuf, MarketGood>(
            { buf, g ->
                ByteBufCodecs.STRING_UTF8.encode(buf, g.good)
                ByteBufCodecs.VAR_INT.encode(buf, g.stock)
                ByteBufCodecs.VAR_INT.encode(buf, g.keep)
                ByteBufCodecs.VAR_INT.encode(buf, g.buyCount)
                ByteBufCodecs.VAR_INT.encode(buf, g.buyMarks)
                ByteBufCodecs.VAR_INT.encode(buf, g.sellCount)
                ByteBufCodecs.VAR_INT.encode(buf, g.sellMarks)
                ByteBufCodecs.VAR_INT.encode(buf, g.wants)
                ByteBufCodecs.VAR_INT.encode(buf, g.trend)
                ByteBufCodecs.FLOAT.encode(buf, g.off)
                ByteBufCodecs.STRING_UTF8.encode(buf, g.why)
                ByteBufCodecs.BOOL.encode(buf, g.dear)
                ByteBufCodecs.VAR_INT.encode(buf, g.carried)
                ByteBufCodecs.VAR_INT.encode(buf, g.hauledCount)
                ByteBufCodecs.VAR_INT.encode(buf, g.hauledMarks)
                ByteBufCodecs.VAR_INT.encode(buf, g.hauledLeft)
            },
            { buf ->
                MarketGood(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                )
            },
        )
    }
}

/**
 * What a market screen shows: the [shop] (its id), who keeps it ([keeper], an entity id, or -1 for the testing
 * screen, which nobody keeps), the capital it's in ([place]), the player's Guild Marks, and the shop's goods; the
 * player's supplier standing there ([standing], the rank 0 to 4, or -1 with standing switched off), their [points]
 * and the points the next rank takes ([next], -1 at the top), how many orders they hold everywhere ([held]) and may
 * hold ([most]), and the business's orders as they stand for the player, and the haul tickets out at the business ([hauls]).
 */
data class MarketView(
    val shop: String,
    val keeper: Int,
    val place: String,
    val marks: Int,
    val goods: List<MarketGood>,
    val standing: Int = -1,
    val points: Int = 0,
    val next: Int = -1,
    val held: Int = 0,
    val most: Int = 0,
    val orders: List<MarketOrder> = listOf(),
    val hauls: List<MarketHaul> = listOf(),
) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, MarketView> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MarketView::shop,
            ByteBufCodecs.VAR_INT, MarketView::keeper,
            ByteBufCodecs.STRING_UTF8, MarketView::place,
            ByteBufCodecs.VAR_INT, MarketView::marks,
            MarketGood.STREAM_CODEC.apply(ByteBufCodecs.list()), MarketView::goods,
            ByteBufCodecs.VAR_INT, MarketView::standing,
            ByteBufCodecs.VAR_INT, MarketView::points,
            ByteBufCodecs.VAR_INT, MarketView::next,
            ByteBufCodecs.VAR_INT, MarketView::held,
            ByteBufCodecs.VAR_INT, MarketView::most,
            MarketOrder.STREAM_CODEC.apply(ByteBufCodecs.list()), MarketView::orders,
            MarketHaul.STREAM_CODEC.apply(ByteBufCodecs.list()), MarketView::hauls,
            ::MarketView,
        )
    }
}

/** Server opens a shop's market screen (see [Stalls]). */
data class OpenMarketPayload(val view: MarketView) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenMarketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenMarketPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_market"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenMarketPayload> = MarketView.STREAM_CODEC.map(::OpenMarketPayload, OpenMarketPayload::view)

        fun handle(payload: OpenMarketPayload, context: IPayloadContext) {
            MarketClient.open(payload.view)
        }
    }
}

/**
 * Server sends an open market screen fresh quotes and counts: after any deal at the capital's market, and when the
 * screen asks. [closed]: the trade is over (the keeper went off, the player walked away), so the screen closes.
 */
data class MarketUpdatePayload(val view: MarketView, val closed: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MarketUpdatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MarketUpdatePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_update"))

        val STREAM_CODEC: StreamCodec<ByteBuf, MarketUpdatePayload> = StreamCodec.composite(
            MarketView.STREAM_CODEC, MarketUpdatePayload::view,
            ByteBufCodecs.BOOL, MarketUpdatePayload::closed,
            ::MarketUpdatePayload,
        )

        fun handle(payload: MarketUpdatePayload, context: IPayloadContext) {
            MarketClient.update(payload.view, payload.closed)
        }
    }
}

/** Client buys ([buy]) or sells [lots] lots of [good] (its id) at the shop whose screen it has open. */
data class MarketTradePayload(val good: String, val buy: Boolean, val lots: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MarketTradePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MarketTradePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_trade"))

        val STREAM_CODEC: StreamCodec<ByteBuf, MarketTradePayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MarketTradePayload::good,
            ByteBufCodecs.BOOL, MarketTradePayload::buy,
            ByteBufCodecs.VAR_INT, MarketTradePayload::lots,
            ::MarketTradePayload,
        )

        fun handle(payload: MarketTradePayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Stalls.trade(player, payload.good, payload.buy, payload.lots)
        }
    }
}

/** Client's market screen is still open and would like fresh quotes (it asks every couple of seconds). */
object MarketRefreshPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<MarketRefreshPayload> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_refresh"))

    val STREAM_CODEC: StreamCodec<ByteBuf, MarketRefreshPayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<MarketRefreshPayload> = TYPE

    fun handle(payload: MarketRefreshPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        Stalls.refresh(player)
    }
}

/** Client closed its market screen. */
object MarketClosePayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<MarketClosePayload> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "market_close"))

    val STREAM_CODEC: StreamCodec<ByteBuf, MarketClosePayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<MarketClosePayload> = TYPE

    fun handle(payload: MarketClosePayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        Stalls.close(player)
    }
}
