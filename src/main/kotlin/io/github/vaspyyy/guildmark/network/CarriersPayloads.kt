package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.trade.CarriersClient
import io.github.vaspyyy.guildmark.trade.carry.CarryShop
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * What the Carriers section of the market hall's screen (living-world/trader/design.md, Part 3; build part D1) sends between the
 * screen and the server. The section has its own pair so that `MarketView` stays the Hauls' alone: the client asks for the list when
 * the screen opens, the server answers with what the counter sells this player and what stands in the way of each, and a press on a
 * row is a request to buy it, which the server decides in full. They are registered by [CarryShop.register].
 */

/** One thing the counter sells as this player sees it: its [id] (its words are `ware.guildmark.<id>`), its [price] in Marks, and [gate], the lang key of what stands in the way or "" if nothing does. */
data class CarriersRow(val id: String, val price: Int, val gate: String) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, CarriersRow> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CarriersRow::id,
            ByteBufCodecs.VAR_INT, CarriersRow::price,
            ByteBufCodecs.STRING_UTF8, CarriersRow::gate,
            ::CarriersRow,
        )
    }
}

/**
 * Server to client: the counter's list in its order. With an answer to a press, [bought] is the row that was bought ("" if none was)
 * and [refused] the lang key of why a press was turned down ("" if it was not), both for the row the press was on ([row]).
 */
data class CarriersViewPayload(val rows: List<CarriersRow>, val row: String = "", val bought: Boolean = false, val refused: String = "") : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CarriersViewPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CarriersViewPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "carriers_view"))

        val STREAM_CODEC: StreamCodec<ByteBuf, CarriersViewPayload> = StreamCodec.composite(
            CarriersRow.STREAM_CODEC.apply(ByteBufCodecs.list()), CarriersViewPayload::rows,
            ByteBufCodecs.STRING_UTF8, CarriersViewPayload::row,
            ByteBufCodecs.BOOL, CarriersViewPayload::bought,
            ByteBufCodecs.STRING_UTF8, CarriersViewPayload::refused,
            ::CarriersViewPayload,
        )

        fun handle(payload: CarriersViewPayload, context: IPayloadContext) {
            CarriersClient.receive(payload)
        }
    }
}

/** Client to server: the screen is open at the hall's counter and would like the list. */
object CarriersQueryPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<CarriersQueryPayload> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "carriers_query"))

    val STREAM_CODEC: StreamCodec<ByteBuf, CarriersQueryPayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<CarriersQueryPayload> = TYPE

    fun handle(payload: CarriersQueryPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        CarryShop.query(player)
    }
}

/** Client to server: buy the row [id] at the counter the player is standing at. */
data class CarriersBuyPayload(val id: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CarriersBuyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CarriersBuyPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "carriers_buy"))

        val STREAM_CODEC: StreamCodec<ByteBuf, CarriersBuyPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CarriersBuyPayload::id,
            ::CarriersBuyPayload,
        )

        fun handle(payload: CarriersBuyPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            CarryShop.buy(player, payload.id)
        }
    }
}
