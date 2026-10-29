package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.trade.HouseClient
import io.github.vaspyyy.guildmark.trade.HouseDesk
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

private fun <T : CustomPacketPayload> type(name: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

/**
 * One carter at a trading house's desk (see [HouseDesk]): carter [id], [name], with the face [look]; what they are doing ([state], one of
 * [IDLE] to [UNPAID]) and where ([where]: the town they are bound for or coming from, "" at the yard), in how many tenths of an hour that is
 * done ([eta]); the [trips] they have made and what they have [earned] the House in all, the extra [guards] their wagon takes, the days
 * they wait for their wage ([unpaid]), whether their last trip was waylaid ([robbed]) and whether they are let go and leave once back ([leaving]).
 */
data class HouseCarterLine(
    val id: Int, val name: String, val look: Int, val state: Int, val where: String, val eta: Int,
    val trips: Int, val earned: Int, val guards: Int, val unpaid: Int, val robbed: Boolean, val leaving: Boolean,
) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeVarInt(id)
        buf.writeUtf(name)
        buf.writeVarInt(look)
        buf.writeVarInt(state)
        buf.writeUtf(where)
        buf.writeVarInt(eta)
        buf.writeVarInt(trips)
        buf.writeVarInt(earned + 1_000_000)
        buf.writeVarInt(guards)
        buf.writeVarInt(unpaid)
        buf.writeBoolean(robbed)
        buf.writeBoolean(leaving)
    }

    companion object {
        const val IDLE = 0
        const val LOADING = 1
        const val OUT = 2
        const val BACK = 3
        const val UNPAID = 4

        fun read(buf: RegistryFriendlyByteBuf): HouseCarterLine = HouseCarterLine(
            buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt() - 1_000_000, buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
        )
    }
}

/**
 * One line of the House book: a trip settled [ago] hours before now, by [carter] to [town], carrying [goods] (good id and units), its goods
 * and deposits costing [cost] and bringing back [got]; [waylaid], with [lost] units gone and the wright's [repair].
 */
data class HouseBookEntry(
    val ago: Int, val carter: String, val town: String, val goods: List<Pair<String, Int>>, val cost: Int, val got: Int,
    val waylaid: Boolean, val lost: Int, val repair: Int,
) {
    val net: Int get() = got - cost - repair

    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeVarInt(ago.coerceAtLeast(0))
        buf.writeUtf(carter)
        buf.writeUtf(town)
        buf.writeVarInt(goods.size)
        for ((g, n) in goods) {
            buf.writeUtf(g)
            buf.writeVarInt(n)
        }
        buf.writeVarInt(cost)
        buf.writeVarInt(got)
        buf.writeBoolean(waylaid)
        buf.writeVarInt(lost)
        buf.writeVarInt(repair)
    }

    companion object {
        fun read(buf: RegistryFriendlyByteBuf): HouseBookEntry = HouseBookEntry(
            buf.readVarInt(), buf.readUtf(), buf.readUtf(), List(buf.readVarInt().coerceIn(0, 64)) { buf.readUtf() to buf.readVarInt() },
            buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
        )
    }
}

/**
 * What a trading house's desk shows (see [HouseDesk]): its owner's name ([owner]) and the town it stands in ([place]), their colour (a Dye
 * ordinal); whether the House is switched on ([open]), its owner away too long for it to work ([away]) and why an idle carter is not sent
 * ([idle], a key under `gui.guildmark.trade.house.idle.`, "" if none). The [purse], the owner's own Guild Marks ([marks]), the [float] that
 * stays in the purse and what may be taken out now ([takeable]). The carters ([carters]), the most it may keep ([most]) and why another
 * cannot be hired now ([hire], a key under `gui.guildmark.trade.house.refused.`, or ""); a carter's [wage] a day and an extra guard's in tenths
 * of a Mark ([guardWage]); the extra guards a wagon may take at most ([guardsMost]). The book (the last trips, newest first) and its totals since
 * the House opened: [trips], [waylaid], the [net] after every wage over [days] tenths of a day, and the wages paid ([wages]).
 */
data class HouseView(
    val owner: String,
    val place: String,
    val color: Int,
    val open: Boolean,
    val away: Boolean,
    val idle: String,
    val purse: Int,
    val marks: Int,
    val float: Int,
    val takeable: Int,
    val carters: List<HouseCarterLine>,
    val most: Int,
    val hire: String,
    val wage: Int,
    val guardWage: Int,
    val guardsMost: Int,
    val book: List<HouseBookEntry>,
    val trips: Int,
    val waylaid: Int,
    val net: Int,
    val days: Int,
    val wages: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HouseView> = StreamCodec.of(
            { buf, v ->
                buf.writeUtf(v.owner)
                buf.writeUtf(v.place)
                buf.writeVarInt(v.color)
                buf.writeBoolean(v.open)
                buf.writeBoolean(v.away)
                buf.writeUtf(v.idle)
                buf.writeVarInt(v.purse + 1_000_000)
                buf.writeVarInt(v.marks)
                buf.writeVarInt(v.float)
                buf.writeVarInt(v.takeable)
                buf.writeVarInt(v.carters.size)
                for (c in v.carters) c.write(buf)
                buf.writeVarInt(v.most)
                buf.writeUtf(v.hire)
                buf.writeVarInt(v.wage)
                buf.writeVarInt(v.guardWage)
                buf.writeVarInt(v.guardsMost)
                buf.writeVarInt(v.book.size)
                for (b in v.book) b.write(buf)
                buf.writeVarInt(v.trips)
                buf.writeVarInt(v.waylaid)
                buf.writeVarInt(v.net + 10_000_000)
                buf.writeVarInt(v.days)
                buf.writeVarInt(v.wages)
            },
            { buf ->
                HouseView(
                    buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(), buf.readVarInt() - 1_000_000,
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), List(buf.readVarInt().coerceIn(0, 16)) { HouseCarterLine.read(buf) },
                    buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), List(buf.readVarInt().coerceIn(0, 32)) { HouseBookEntry.read(buf) },
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 10_000_000, buf.readVarInt(), buf.readVarInt(),
                )
            },
        )
    }
}

/** Server opens a trading house's desk (see [HouseDesk]). */
data class OpenHousePayload(val view: HouseView) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenHousePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenHousePayload> = type("open_house")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenHousePayload> = HouseView.STREAM_CODEC.map(::OpenHousePayload, OpenHousePayload::view)

        fun handle(payload: OpenHousePayload, context: IPayloadContext) {
            HouseClient.open(payload.view)
        }
    }
}

/** Server sends an open desk how the House stands now: after anything done at it, and when the screen asks. [closed]: the player walked away, so the screen closes. */
data class HouseUpdatePayload(val view: HouseView, val closed: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HouseUpdatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<HouseUpdatePayload> = type("house_update")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HouseUpdatePayload> = StreamCodec.of(
            { buf, p ->
                HouseView.STREAM_CODEC.encode(buf, p.view)
                buf.writeBoolean(p.closed)
            },
            { buf -> HouseUpdatePayload(HouseView.STREAM_CODEC.decode(buf), buf.readBoolean()) },
        )

        fun handle(payload: HouseUpdatePayload, context: IPayloadContext) {
            HouseClient.update(payload.view, payload.closed)
        }
    }
}

/** Client asks for [action] (one of [HouseDesk]'s) at the desk it has open: about carter [carter], with [count] (guards, or Marks). */
data class HouseActionPayload(val action: Int, val carter: Int, val count: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HouseActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<HouseActionPayload> = type("house_action")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HouseActionPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.action)
                buf.writeVarInt(p.carter)
                buf.writeVarInt(p.count)
            },
            { buf -> HouseActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) },
        )

        fun handle(payload: HouseActionPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            HouseDesk.act(player, payload.action, payload.carter, payload.count)
        }
    }
}

/** Client's desk is still open and would like to see the House as it stands (it asks every couple of seconds). */
object HouseRefreshPayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<HouseRefreshPayload> = type("house_refresh")

    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HouseRefreshPayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<HouseRefreshPayload> = TYPE

    fun handle(payload: HouseRefreshPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        HouseDesk.refresh(player)
    }
}

/** Client closed its desk. */
object HouseClosePayload : CustomPacketPayload {
    val TYPE: CustomPacketPayload.Type<HouseClosePayload> = type("house_close")

    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HouseClosePayload> = StreamCodec.unit(this)

    override fun type(): CustomPacketPayload.Type<HouseClosePayload> = TYPE

    fun handle(payload: HouseClosePayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        HouseDesk.close(player)
    }
}
