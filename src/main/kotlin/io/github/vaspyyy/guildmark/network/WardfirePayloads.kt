package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.wardfire.Wardfires
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Server tells the client the player has sat down at the Wardfire at [pos]: open its menu. */
data class OpenWardfirePayload(val pos: BlockPos, val flags: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenWardfirePayload> = TYPE

    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        /** Nobody else is about, so the player can wait for morning or nightfall. */
        const val CAN_WAIT = 1

        val TYPE: CustomPacketPayload.Type<OpenWardfirePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_wardfire"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenWardfirePayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenWardfirePayload::pos,
            ByteBufCodecs.VAR_INT, OpenWardfirePayload::flags,
            ::OpenWardfirePayload,
        )

        fun handle(payload: OpenWardfirePayload, context: IPayloadContext) {
            ClientHooks.openWardfire(payload)
        }
    }
}

/** Client picked [action] in the menu of the Wardfire at [pos]. */
data class WardfireActionPayload(val pos: BlockPos, val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WardfireActionPayload> = TYPE

    companion object {
        /** Wait at the fire for morning (by night) or nightfall (by day). */
        const val WAIT = 0

        val TYPE: CustomPacketPayload.Type<WardfireActionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "wardfire_action"))

        val STREAM_CODEC: StreamCodec<ByteBuf, WardfireActionPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WardfireActionPayload::pos,
            ByteBufCodecs.VAR_INT, WardfireActionPayload::action,
            ::WardfireActionPayload,
        )

        fun handle(payload: WardfireActionPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Wardfires.onAction(player, payload.pos, payload.action)
        }
    }
}

/** Client asks to travel to the kindled Wardfire at [to], picked on the map. */
data class WardfireTravelPayload(val to: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WardfireTravelPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WardfireTravelPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "wardfire_travel"))

        val STREAM_CODEC: StreamCodec<ByteBuf, WardfireTravelPayload> =
            BlockPos.STREAM_CODEC.map(::WardfireTravelPayload, WardfireTravelPayload::to)

        fun handle(payload: WardfireTravelPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Wardfires.travel(player, payload.to)
        }
    }
}

/** Server answers a travel request: [arrived] or not, so the client's fade can lift. */
data class WardfireTravelledPayload(val arrived: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WardfireTravelledPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WardfireTravelledPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "wardfire_travelled"))

        val STREAM_CODEC: StreamCodec<ByteBuf, WardfireTravelledPayload> =
            ByteBufCodecs.BOOL.map(::WardfireTravelledPayload, WardfireTravelledPayload::arrived)

        fun handle(payload: WardfireTravelledPayload, context: IPayloadContext) {
            ClientHooks.travelled(payload)
        }
    }
}
