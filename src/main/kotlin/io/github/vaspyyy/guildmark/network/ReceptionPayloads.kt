package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.guild.NewsItem
import io.github.vaspyyy.guildmark.guild.Reception
import io.github.vaspyyy.guildmark.guild.Recruiting
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Server tells the client to open the guild hall desk of the receptionist [entityId]. */
data class OpenReceptionPayload(
    val entityId: Int,
    val news: List<NewsItem>,
    val hallContractReady: Boolean,
    val lairHuntReady: Boolean,
    val today: Long,
    /** The player's own guild: empty name if they haven't founded one. */
    val guildName: String,
    val guildColor: Int,
    val guildMembers: Int,
    val guildCap: Int,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenReceptionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenReceptionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_reception"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenReceptionPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenReceptionPayload::entityId,
            NewsItem.STREAM_CODEC.apply(ByteBufCodecs.list(16)), OpenReceptionPayload::news,
            ByteBufCodecs.BOOL, OpenReceptionPayload::hallContractReady,
            ByteBufCodecs.BOOL, OpenReceptionPayload::lairHuntReady,
            ByteBufCodecs.VAR_LONG, OpenReceptionPayload::today,
            ByteBufCodecs.STRING_UTF8, OpenReceptionPayload::guildName,
            ByteBufCodecs.VAR_INT, OpenReceptionPayload::guildColor,
            ByteBufCodecs.VAR_INT, OpenReceptionPayload::guildMembers,
            ByteBufCodecs.VAR_INT, OpenReceptionPayload::guildCap,
            ::OpenReceptionPayload,
        )

        fun handle(payload: OpenReceptionPayload, context: IPayloadContext) {
            ClientHooks.openReception(payload)
        }
    }
}

/** Client asks the receptionist [entityId] for something: a rank trial, today's hall contract or a lair hunt. */
data class ReceptionActionPayload(val entityId: Int, val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ReceptionActionPayload> = TYPE

    companion object {
        const val TRIAL = 0
        const val HALL_CONTRACT = 1
        const val LAIR_HUNT = 2

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

/** Client asks the receptionist [entityId] to found a guild called [name] under the dye colour [color]. */
data class FoundGuildPayload(val entityId: Int, val name: String, val color: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FoundGuildPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<FoundGuildPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "found_guild"))

        val STREAM_CODEC: StreamCodec<ByteBuf, FoundGuildPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FoundGuildPayload::entityId,
            ByteBufCodecs.stringUtf8(32), FoundGuildPayload::name,
            ByteBufCodecs.VAR_INT, FoundGuildPayload::color,
            ::FoundGuildPayload,
        )

        fun handle(payload: FoundGuildPayload, context: IPayloadContext) {
            Reception.handleFound(context.player(), payload.entityId, payload.name, payload.color)
        }
    }
}
