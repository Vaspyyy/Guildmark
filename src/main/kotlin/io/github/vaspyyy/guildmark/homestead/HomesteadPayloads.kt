package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.homestead.HomesteadClient
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

private fun <T : CustomPacketPayload> type(name: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

private fun RegistryFriendlyByteBuf.writeInts(a: IntArray) {
    writeVarInt(a.size)
    for (v in a) writeVarInt(v)
}

private fun RegistryFriendlyByteBuf.readInts(): IntArray = IntArray(readVarInt().coerceIn(0, 4096)) { readVarInt() }

/**
 * Every homestead, as clients need it: to draw a plot's bounds when someone holds a charter or stands at a post, and
 * to put homesteads on the map. Sent to everyone whenever one is claimed, changes tier or is given up.
 */
data class HomesteadSyncPayload(val entries: List<Entry>) : CustomPacketPayload {
    /** One homestead: its post, which way its plot reaches (a horizontal direction's 2D id), its tiers and colour. */
    data class Entry(
        val id: Int, val owner: UUID, val ownerName: String, val post: BlockPos, val facing: Int, val tier: Int, val building: Int,
        val color: Int,
    )

    override fun type(): CustomPacketPayload.Type<HomesteadSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<HomesteadSyncPayload> = type("homestead_sync")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, HomesteadSyncPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.entries.size)
                for (e in p.entries) {
                    buf.writeVarInt(e.id)
                    UUIDUtil.STREAM_CODEC.encode(buf, e.owner)
                    buf.writeUtf(e.ownerName)
                    buf.writeBlockPos(e.post)
                    buf.writeByte(e.facing)
                    buf.writeByte(e.tier)
                    buf.writeByte(e.building)
                    buf.writeByte(e.color)
                }
            },
            { buf ->
                val n = buf.readVarInt().coerceIn(0, 4096)
                HomesteadSyncPayload(
                    List(n) {
                        Entry(
                            buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(), buf.readBlockPos(), buf.readByte().toInt(),
                            buf.readByte().toInt(), buf.readByte().toInt(), buf.readByte().toInt(),
                        )
                    },
                )
            },
        )

        fun handle(payload: HomesteadSyncPayload, context: IPayloadContext) = HomesteadClient.onSync(payload)
    }
}

/**
 * What a survey of a spot for a homestead found, for the player holding the charter: whether it will do, the reason
 * if it won't, and the places at fault to pick out on the ground. [post] and [facing] say which spot it was (none: a
 * claim just refused).
 */
data class ClaimVerdictPayload(
    val ok: Boolean, val reason: Component, val highlight: List<BlockPos>, val post: BlockPos?, val facing: Int,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClaimVerdictPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ClaimVerdictPayload> = type("homestead_verdict")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClaimVerdictPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeBoolean(p.ok)
                ComponentSerialization.STREAM_CODEC.encode(buf, p.reason)
                buf.writeVarInt(p.highlight.size)
                for (pos in p.highlight) buf.writeBlockPos(pos)
                buf.writeBoolean(p.post != null)
                p.post?.let(buf::writeBlockPos)
                buf.writeByte(p.facing)
            },
            { buf ->
                val ok = buf.readBoolean()
                val reason = ComponentSerialization.STREAM_CODEC.decode(buf)
                val highlight = List(buf.readVarInt().coerceIn(0, 256)) { buf.readBlockPos() }
                val post = if (buf.readBoolean()) buf.readBlockPos() else null
                ClaimVerdictPayload(ok, reason, highlight, post, buf.readByte().toInt())
            },
        )

        fun handle(payload: ClaimVerdictPayload, context: IPayloadContext) = HomesteadClient.onVerdict(payload)
    }
}

/** A player holding a charter asks whether the spot they're looking at would do, its plot reaching [facing]. */
data class ClaimCheckPayload(val post: BlockPos, val facing: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClaimCheckPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ClaimCheckPayload> = type("homestead_check")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClaimCheckPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeBlockPos(p.post)
                buf.writeByte(p.facing)
            },
            { buf -> ClaimCheckPayload(buf.readBlockPos(), buf.readByte().toInt()) },
        )

        fun handle(payload: ClaimCheckPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            HomesteadLedger.check(player, payload.post, payload.facing)
        }
    }
}

/**
 * A homestead's shipping as its ledger shows it (see [HomesteadShipping]): whether shipping is on, the Marks in the
 * post's purse, whether a capital's market is within the carters' reach and the capital's name ([place]; empty if it
 * couldn't be found), the ticks until the carter next comes ([next]; 0 while the morning's shipment is due), the carter
 * at the crates now ([carter], an entity id, or -1), where the shipping crates stand, what's in them (of each good the
 * market deals in, by [io.github.vaspyyy.guildmark.economy.Good] ordinal, and how many other things, which stay), and
 * the latest shipments as the ledger words them.
 */
class ShippingView(
    val on: Boolean,
    val purse: Int,
    val reach: Boolean,
    val place: String,
    val next: Int,
    val carter: Int,
    val crates: List<BlockPos>,
    val goods: IntArray,
    val others: Int,
    val notes: List<Component>,
) {
    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeBoolean(on)
        buf.writeVarInt(purse)
        buf.writeBoolean(reach)
        buf.writeUtf(place)
        buf.writeVarInt(next)
        buf.writeVarInt(carter + 1)
        val shown = crates.take(MAX_CRATES)
        buf.writeVarInt(shown.size)
        for (pos in shown) buf.writeBlockPos(pos)
        buf.writeInts(goods)
        buf.writeVarInt(others)
        val told = notes.take(MAX_NOTES)
        buf.writeVarInt(told.size)
        for (note in told) ComponentSerialization.STREAM_CODEC.encode(buf, note)
    }

    companion object {
        /** The most crates and shipments a ledger shows. */
        private const val MAX_CRATES = 64
        private const val MAX_NOTES = 16

        fun read(buf: RegistryFriendlyByteBuf): ShippingView = ShippingView(
            buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(), buf.readVarInt(), buf.readVarInt() - 1,
            List(buf.readVarInt().coerceIn(0, MAX_CRATES)) { buf.readBlockPos() }, buf.readInts(), buf.readVarInt(),
            List(buf.readVarInt().coerceIn(0, MAX_NOTES)) { ComponentSerialization.STREAM_CODEC.decode(buf) },
        )
    }
}

/**
 * The homestead's ledger, for its owner or one of their party at the post: the plot as it stands from above ([map]:
 * a packed map colour per column of the charter's land), the tier and the one after it, what the build going up (or the
 * next tier) costs and what's still to come of it, what the supply crate holds, the crew, and its [shipping].
 */
data class LedgerPayload(
    val id: Int,
    val ownerName: String,
    /** Whether whoever it's for is the owner (who alone may commission a tier or give the charter up). */
    val owner: Boolean,
    val tier: Int,
    /** The tier going up, or the homestead's own when nothing is. */
    val building: Int,
    val stage: Int,
    val laid: Int,
    val total: Int,
    /** The Marks the next tier costs, and the Marks the reader has. */
    val price: Int,
    val purse: Int,
    val crew: Int,
    val working: Int,
    /** Daylight ticks of work left at the crew's pace (or the whole of the next tier's). */
    val eta: Int,
    val bill: IntArray,
    val left: IntArray,
    val crate: IntArray,
    /** What the crate has run out of (a [io.github.vaspyyy.guildmark.homestead.plan.Stuff] ordinal), or -1. */
    val short: Int,
    val blocked: BlockPos?,
    val facing: Int,
    val post: BlockPos,
    val map: ByteArray,
    val shipping: ShippingView,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<LedgerPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LedgerPayload> = type("homestead_ledger")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, LedgerPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.id)
                buf.writeUtf(p.ownerName)
                buf.writeBoolean(p.owner)
                buf.writeVarInt(p.tier)
                buf.writeVarInt(p.building)
                buf.writeVarInt(p.stage)
                buf.writeVarInt(p.laid)
                buf.writeVarInt(p.total)
                buf.writeVarInt(p.price)
                buf.writeVarInt(p.purse)
                buf.writeVarInt(p.crew)
                buf.writeVarInt(p.working)
                buf.writeVarInt(p.eta)
                buf.writeInts(p.bill)
                buf.writeInts(p.left)
                buf.writeInts(p.crate)
                buf.writeVarInt(p.short + 1)
                buf.writeBoolean(p.blocked != null)
                p.blocked?.let(buf::writeBlockPos)
                buf.writeByte(p.facing)
                buf.writeBlockPos(p.post)
                buf.writeByteArray(p.map)
                p.shipping.write(buf)
            },
            { buf ->
                LedgerPayload(
                    buf.readVarInt(), buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readInts(), buf.readInts(), buf.readInts(), buf.readVarInt() - 1,
                    if (buf.readBoolean()) buf.readBlockPos() else null, buf.readByte().toInt(), buf.readBlockPos(), buf.readByteArray(8192),
                    ShippingView.read(buf),
                )
            },
        )

        fun handle(payload: LedgerPayload, context: IPayloadContext) = HomesteadClient.onLedger(payload)
    }
}

/** A button pressed in the ledger: [action] one of [HomesteadLedger]'s, on homestead [id]; [confirm] for giving it up. */
data class LedgerActionPayload(val id: Int, val action: Int, val confirm: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<LedgerActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LedgerActionPayload> = type("homestead_action")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, LedgerActionPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.id)
                buf.writeVarInt(p.action)
                buf.writeBoolean(p.confirm)
            },
            { buf -> LedgerActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean()) },
        )

        fun handle(payload: LedgerActionPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            HomesteadLedger.act(player, payload.id, payload.action, payload.confirm)
        }
    }
}
