package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.trade.StoreClient
import io.github.vaspyyy.guildmark.trade.StoreDesk
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

private fun <T : CustomPacketPayload> type(name: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

private fun RegistryFriendlyByteBuf.strings(list: List<String>) {
    writeVarInt(list.size)
    for (s in list) writeUtf(s)
}

private fun RegistryFriendlyByteBuf.strings(): List<String> = List(readVarInt().coerceIn(0, 64)) { readUtf() }

/** One hauled parcel in a Store as its desk shows it: [units] bought at [from], keeping the name [days] more days (counting today). */
data class StoreParcelLine(val units: Int, val days: Int, val from: String)

/**
 * One good at a Store's desk (see [StoreDesk]): how many the Store holds ([bonded]) and how many of those are which hauled [parcels] (the
 * rest plain), how many the owner [carried] that it would take, how many of what they carry the ledger counts as hauled ([hauled]), and
 * how many more it has [room] for.
 */
data class StoreGood(val good: String, val bonded: Int, val carried: Int, val hauled: Int, val room: Int, val parcels: List<StoreParcelLine>) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeUtf(good)
        buf.writeVarInt(bonded)
        buf.writeVarInt(carried)
        buf.writeVarInt(hauled)
        buf.writeVarInt(room)
        buf.writeVarInt(parcels.size)
        for (p in parcels) {
            buf.writeVarInt(p.units)
            buf.writeVarInt(p.days)
            buf.writeUtf(p.from)
        }
    }

    companion object {
        fun read(buf: RegistryFriendlyByteBuf): StoreGood = StoreGood(
            buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            List(buf.readVarInt().coerceIn(0, 64)) { StoreParcelLine(buf.readVarInt(), buf.readVarInt(), buf.readUtf()) },
        )
    }
}

/**
 * A town whose word the Store's desk has live: [name], [distance] blocks from the Store, the Store's own town ([own]) or one where the owner
 * keeps a stall; what it is short of and has too much of now ([shorts], [gluts], good ids, the most wanting first), its haul tickets
 * ([tickets]) and the best of them ([bestGood] "" for none, [bestUnits] for [bestPay] Marks).
 */
data class StoreTown(
    val name: String, val distance: Int, val own: Boolean, val shorts: List<String>, val gluts: List<String>,
    val tickets: Int, val bestGood: String, val bestUnits: Int, val bestPay: Int,
) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeUtf(name)
        buf.writeVarInt(distance)
        buf.writeBoolean(own)
        buf.strings(shorts)
        buf.strings(gluts)
        buf.writeVarInt(tickets)
        buf.writeUtf(bestGood)
        buf.writeVarInt(bestUnits)
        buf.writeVarInt(bestPay)
    }

    companion object {
        fun read(buf: RegistryFriendlyByteBuf): StoreTown = StoreTown(
            buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.strings(), buf.strings(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
        )
    }
}

/**
 * What a Carriers' Store's desk shows (see [StoreDesk]): the town it stands in ([place]), its owner's name and colour (a Dye ordinal),
 * whether the Stores are switched on ([open]), the owner's Guild Marks, and why nothing can be bonded now ([refusal], a lang key, or "").
 * The clerk's [wage] a week, the days it is paid ahead counting today ([paidDays], 0 or less: owing), the most days it can be paid [ahead],
 * why a week can't be paid now ([payRefusal] or ""), and whether the clerk has come ([clerk]). The goods, and the most kinds and units a
 * Store holds. The live word ([wordOn]: the Word switched on, and the [towns]). The wagon yard ([wagonOn], [yard]: where a wagon called
 * comes to, or null), the guards hired ([guardsOn], [guards]) at [guardWage] tenths of a Mark a day. The tables the owner rents
 * ([tables]), what a week of them costs now ([keepNow]) and would without the Store ([keepFull]), and whether the half rent is on ([half]).
 */
data class StoreView(
    val place: String,
    val owner: String,
    val color: Int,
    val open: Boolean,
    val marks: Int,
    val refusal: String,
    val wage: Int,
    val paidDays: Int,
    val ahead: Int,
    val payRefusal: String,
    val clerk: Boolean,
    val goods: List<StoreGood>,
    val kinds: Int,
    val most: Int,
    val wordOn: Boolean,
    val towns: List<StoreTown>,
    val wagonOn: Boolean,
    val yard: BlockPos?,
    val guardsOn: Boolean,
    val guards: Int,
    val guardWage: Int,
    val tables: Int,
    val keepNow: Int,
    val keepFull: Int,
    val half: Boolean,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreView> = StreamCodec.of(
            { buf, v ->
                buf.writeUtf(v.place)
                buf.writeUtf(v.owner)
                buf.writeVarInt(v.color)
                buf.writeBoolean(v.open)
                buf.writeVarInt(v.marks)
                buf.writeUtf(v.refusal)
                buf.writeVarInt(v.wage)
                buf.writeVarInt(v.paidDays)
                buf.writeVarInt(v.ahead)
                buf.writeUtf(v.payRefusal)
                buf.writeBoolean(v.clerk)
                buf.writeVarInt(v.goods.size)
                for (g in v.goods) g.write(buf)
                buf.writeVarInt(v.kinds)
                buf.writeVarInt(v.most)
                buf.writeBoolean(v.wordOn)
                buf.writeVarInt(v.towns.size)
                for (t in v.towns) t.write(buf)
                buf.writeBoolean(v.wagonOn)
                buf.writeBoolean(v.yard != null)
                v.yard?.let { buf.writeBlockPos(it) }
                buf.writeBoolean(v.guardsOn)
                buf.writeVarInt(v.guards)
                buf.writeVarInt(v.guardWage)
                buf.writeVarInt(v.tables)
                buf.writeVarInt(v.keepNow)
                buf.writeVarInt(v.keepFull)
                buf.writeBoolean(v.half)
            },
            { buf ->
                StoreView(
                    buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readUtf(), buf.readBoolean(), List(buf.readVarInt().coerceIn(0, 64)) { StoreGood.read(buf) }, buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean(), List(buf.readVarInt().coerceIn(0, 32)) { StoreTown.read(buf) }, buf.readBoolean(),
                    if (buf.readBoolean()) buf.readBlockPos() else null, buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                )
            },
        )
    }
}

/** Server opens a Carriers' Store's desk (see [StoreDesk]). */
data class OpenStorePayload(val view: StoreView) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenStorePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenStorePayload> = type("open_store")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenStorePayload> = StoreView.STREAM_CODEC.map(::OpenStorePayload, OpenStorePayload::view)

        fun handle(payload: OpenStorePayload, context: IPayloadContext) {
            StoreClient.open(payload.view)
        }
    }
}

/** Server sends an open desk how the Store stands now: after anything done at it, and when the screen asks. [closed]: the player walked away, so the screen closes. */
data class StoreUpdatePayload(val view: StoreView, val closed: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StoreUpdatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StoreUpdatePayload> = type("store_update")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreUpdatePayload> = StreamCodec.of(
            { buf, p ->
                StoreView.STREAM_CODEC.encode(buf, p.view)
                buf.writeBoolean(p.closed)
            },
            { buf -> StoreUpdatePayload(StoreView.STREAM_CODEC.decode(buf), buf.readBoolean()) },
        )

        fun handle(payload: StoreUpdatePayload, context: IPayloadContext) {
            StoreClient.update(payload.view, payload.closed)
        }
    }
}

/** Client asks for [action] (one of [StoreDesk]'s) at the desk it has open: with [count] of [good] (its id) to bond or take back. */
data class StoreActionPayload(val action: Int, val good: String, val count: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StoreActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StoreActionPayload> = type("store_action")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreActionPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.action)
                buf.writeUtf(p.good)
                buf.writeVarInt(p.count)
            },
            { buf -> StoreActionPayload(buf.readVarInt(), buf.readUtf(), buf.readVarInt()) },
        )

        fun handle(payload: StoreActionPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            StoreDesk.act(player, payload.action, payload.good, payload.count)
        }
    }
}

/** Client's desk is still open and would like to see the Store as it stands (it asks every couple of seconds). */
object StoreRefreshPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<StoreRefreshPayload> = type("store_refresh")

    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreRefreshPayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<StoreRefreshPayload> = TYPE

    fun handle(payload: StoreRefreshPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        StoreDesk.refresh(player)
    }
}

/** Client closed its desk. */
object StoreClosePayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<StoreClosePayload> = type("store_close")

    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreClosePayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<StoreClosePayload> = TYPE

    fun handle(payload: StoreClosePayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        StoreDesk.close(player)
    }
}
