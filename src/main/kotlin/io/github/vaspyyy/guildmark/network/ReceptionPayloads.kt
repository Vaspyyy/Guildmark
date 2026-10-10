package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.guild.NewsItem
import io.github.vaspyyy.guildmark.guild.Reception
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Server tells the client to open the guild hall desk of the receptionist [entityId]. */
data class OpenReceptionPayload(val entityId: Int, val news: List<NewsItem>, val hallContractReady: Boolean, val today: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenReceptionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenReceptionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_reception"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenReceptionPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenReceptionPayload::entityId,
            NewsItem.STREAM_CODEC.apply(ByteBufCodecs.list(16)), OpenReceptionPayload::news,
            ByteBufCodecs.BOOL, OpenReceptionPayload::hallContractReady,
            ByteBufCodecs.VAR_LONG, OpenReceptionPayload::today,
            ::OpenReceptionPayload,
        )

        fun handle(payload: OpenReceptionPayload, context: IPayloadContext) {
            ClientHooks.openReception(payload)
        }
    }
}

/** Client asks the receptionist [entityId] for something: a rank trial or today's hall contract. */
data class ReceptionActionPayload(val entityId: Int, val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ReceptionActionPayload> = TYPE

    companion object {
        const val TRIAL = 0
        const val HALL_CONTRACT = 1

        val TYPE: CustomPacketPayload.Type<ReceptionActionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "reception_action"))

        val STREAM_CODEC: StreamCodec<ByteBuf, ReceptionActionPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ReceptionActionPayload::entityId,
            ByteBufCodecs.VAR_INT, ReceptionActionPayload::action,
            ::ReceptionActionPayload,
        )

        fun handle(payload: ReceptionActionPayload, context: IPayloadContext) {
            Reception.handleAction(context.player(), payload.entityId, payload.action)
        }
    }
}
