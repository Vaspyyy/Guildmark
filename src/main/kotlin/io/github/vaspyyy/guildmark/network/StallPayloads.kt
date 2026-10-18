package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.economy.StallClient
import io.github.vaspyyy.guildmark.economy.StallDesk
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

private fun <T : CustomPacketPayload> type(name: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

/**
 * One of a player's goods as their stall's screen shows it (see [StallDesk]): how many are on the [stall] and how many
 * they [carried], how many of those may go on it ([allowed]: not what they bought at this market this week, [bought],
 * and only while there's room), the lot it sells at to the town ([lotCount] for [lotMarks] Guild Marks, the market's
 * price less the hall's tenth; 0 for 0 while the market hasn't opened), and how many it has sold since the town's day
 * began ([today]).
 */
data class StallGood(
    val good: String,
    val stall: Int,
    val carried: Int,
    val allowed: Int,
    val bought: Int,
    val lotCount: Int,
    val lotMarks: Int,
    val today: Int,
) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeUtf(good)
        buf.writeVarInt(stall)
        buf.writeVarInt(carried)
        buf.writeVarInt(allowed)
        buf.writeVarInt(bought)
        buf.writeVarInt(lotCount)
        buf.writeVarInt(lotMarks)
        buf.writeVarInt(today)
    }

    companion object {
        fun read(buf: RegistryFriendlyByteBuf): StallGood = StallGood(
            buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
        )
    }
}

/**
 * What a market hall table's screen shows: the capital it's in ([place]), which of the hall's tables it is, whether
 * it's free, the player's own or someone else's ([state], one of [StallDesk]'s), who rents it and in what colour (a
 * Dye ordinal; the player's own while it's free), the market hours of rent [left], what a week costs this player
 * ([keep]) and their [rank] as a supplier there, why they can't rent it or pay for it now ([refusal], a lang key, or
 * ""), their Guild Marks, the whole Marks in the [till], what the hall's merchant keeps for them from a stall let go
 * ([kept] goods and [keptMarks]) and whether a merchant is about to hand it over ([merchant]), whether stalls are let
 * and sell at all ([open]), and the goods.
 */
data class StallView(
    val place: String,
    val table: Int,
    val state: Int,
    val renter: String,
    val color: Int,
    val left: Int,
    val keep: Int,
    val rank: Int,
    val refusal: String,
    val marks: Int,
    val till: Int,
    val kept: Int,
    val keptMarks: Int,
    val merchant: Boolean,
    val open: Boolean,
    val goods: List<StallGood>,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StallView> = StreamCodec.of(
            { buf, v ->
                buf.writeUtf(v.place)
                buf.writeVarInt(v.table)
                buf.writeVarInt(v.state)
                buf.writeUtf(v.renter)
                buf.writeVarInt(v.color)
                buf.writeVarInt(v.left)
                buf.writeVarInt(v.keep)
                buf.writeVarInt(v.rank)
                buf.writeUtf(v.refusal)
                buf.writeVarInt(v.marks)
                buf.writeVarInt(v.till)
                buf.writeVarInt(v.kept)
                buf.writeVarInt(v.keptMarks)
                buf.writeBoolean(v.merchant)
                buf.writeBoolean(v.open)
                buf.writeVarInt(v.goods.size)
                for (g in v.goods) g.write(buf)
            },
            { buf ->
                StallView(
                    buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readBoolean(), List(buf.readVarInt().coerceIn(0, 64)) { StallGood.read(buf) },
                )
            },
        )
    }
}

/** Server opens a market hall table's screen (see [StallDesk]). */
data class OpenStallPayload(val view: StallView) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenStallPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenStallPayload> = type("open_stall")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenStallPayload> = StallView.STREAM_CODEC.map(::OpenStallPayload, OpenStallPayload::view)

        fun handle(payload: OpenStallPayload, context: IPayloadContext) {
            StallClient.open(payload.view)
        }
    }
}

/**
 * Server sends an open table's screen how it stands now: after anything done at it or its market's hour, and when the
 * screen asks. [closed]: the player walked away or it can't be used any more, so the screen closes.
 */
data class StallUpdatePayload(val view: StallView, val closed: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StallUpdatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StallUpdatePayload> = type("stall_update")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StallUpdatePayload> = StreamCodec.of(
            { buf, p ->
                StallView.STREAM_CODEC.encode(buf, p.view)
                buf.writeBoolean(p.closed)
            },
            { buf -> StallUpdatePayload(StallView.STREAM_CODEC.decode(buf), buf.readBoolean()) },
        )

        fun handle(payload: StallUpdatePayload, context: IPayloadContext) {
            StallClient.update(payload.view, payload.closed)
        }
    }
}

/** Client asks for [action] (one of [StallDesk]'s) at the table whose screen it has open: with [count] of [good] (its id) to set out or take back. */
data class StallActionPayload(val action: Int, val good: String, val count: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StallActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StallActionPayload> = type("stall_action")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StallActionPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.action)
                buf.writeUtf(p.good)
                buf.writeVarInt(p.count)
            },
            { buf -> StallActionPayload(buf.readVarInt(), buf.readUtf(), buf.readVarInt()) },
        )

        fun handle(payload: StallActionPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            StallDesk.act(player, payload.action, payload.good, payload.count)
        }
    }
}

/** Client's table screen is still open and would like to see it as it stands (it asks every couple of seconds). */
object StallRefreshPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<StallRefreshPayload> = type("stall_refresh")

    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StallRefreshPayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<StallRefreshPayload> = TYPE

    fun handle(payload: StallRefreshPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        StallDesk.refresh(player)
    }
}

/** Client closed its table screen. */
object StallClosePayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<StallClosePayload> = type("stall_close")

    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StallClosePayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<StallClosePayload> = TYPE

    fun handle(payload: StallClosePayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        StallDesk.close(player)
    }
}

/** A let market hall table, for its pennant: where its display stands, its front (a horizontal direction's 2D id), and its renter's colour (a Dye ordinal) and name. */
data class StallPennant(val at: BlockPos, val front: Int, val color: Int, val name: String)

/** Every let market hall table there is, for the pennants over them (see client/economy/StallPennants); sent to everyone when one is let or let go. */
data class StallPennantsPayload(val pennants: List<StallPennant>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StallPennantsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StallPennantsPayload> = type("stall_pennants")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StallPennantsPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.pennants.size)
                for (s in p.pennants) {
                    buf.writeBlockPos(s.at)
                    buf.writeByte(s.front)
                    buf.writeByte(s.color)
                    buf.writeUtf(s.name)
                }
            },
            { buf -> StallPennantsPayload(List(buf.readVarInt().coerceIn(0, 4096)) { StallPennant(buf.readBlockPos(), buf.readByte().toInt(), buf.readByte().toInt(), buf.readUtf()) }) },
        )

        fun handle(payload: StallPennantsPayload, context: IPayloadContext) {
            StallClient.pennants(payload.pennants)
        }
    }
}
