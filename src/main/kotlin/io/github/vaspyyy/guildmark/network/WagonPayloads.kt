package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.carry.LedShow
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

private fun <T : CustomPacketPayload> type(name: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name))

/**
 * Client used a trader's wagon (build part D3): its horse's head ([LedShow.HEAD], with an empty hand: take the lead or let go) or its tailboard
 * ([LedShow.TAIL]: open its 54 slots), the wagon's or the horse's entity [id]. The wagon and its horse are props nothing can hit or use, so
 * the client finds the part looked at and says so; the server checks it is in reach, whose it is and what may be done.
 */
data class WagonUsePayload(val id: Int, val part: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WagonUsePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WagonUsePayload> = type("wagon_use")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WagonUsePayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.id)
                buf.writeVarInt(p.part)
            },
            { buf -> WagonUsePayload(buf.readVarInt(), buf.readVarInt()) },
        )

        fun handle(payload: WagonUsePayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            LedShow.used(player, payload.id, payload.part)
        }
    }
}

/**
 * Server tells a client near a trader's wagon what its bodies don't carry (build part D3): that wagon [wagon] (with horse [horse], or -1) is
 * [owner]'s, painted [rgb]; who holds its lead ([holder], an entity id, or -1) and whether the rein is drawn [taut]; the game time its pennant
 * ran up ([raised]; -1 furled, as in the yard; 0 long since); how full it is ([laden], 0 to 1, for its creak). [alive] false: its bodies have gone.
 * Sent as any of it changes and every few seconds to whoever is near.
 */
data class WagonSyncPayload(
    val wagon: Int,
    val horse: Int,
    val owner: String,
    val rgb: Int,
    val holder: Int,
    val taut: Boolean,
    val raised: Long,
    val laden: Float,
    val alive: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WagonSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WagonSyncPayload> = type("wagon_sync")

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WagonSyncPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeVarInt(p.wagon)
                buf.writeVarInt(p.horse)
                buf.writeUtf(p.owner, 64)
                buf.writeInt(p.rgb)
                buf.writeVarInt(p.holder)
                buf.writeBoolean(p.taut)
                buf.writeLong(p.raised)
                buf.writeFloat(p.laden)
                buf.writeBoolean(p.alive)
            },
            { buf ->
                WagonSyncPayload(
                    buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readInt(), buf.readVarInt(), buf.readBoolean(), buf.readLong(),
                    buf.readFloat(), buf.readBoolean(),
                )
            },
        )

        /** What the client does with one (set by the client's own setup, client/trade/ReinRenderer.kt); nothing on a server. */
        @JvmStatic
        var received: ((WagonSyncPayload) -> Unit)? = null

        fun handle(payload: WagonSyncPayload, context: IPayloadContext) {
            received?.invoke(payload)
        }
    }
}
