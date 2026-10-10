package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.story.Characters
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Server tells the client to open a conversation with the named character [entityId]. */
data class OpenCharacterPayload(val entityId: Int, val character: String, val chapters: Int, val carrying: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenCharacterPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenCharacterPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_character"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenCharacterPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenCharacterPayload::entityId,
            ByteBufCodecs.STRING_UTF8, OpenCharacterPayload::character,
            ByteBufCodecs.VAR_INT, OpenCharacterPayload::chapters,
            ByteBufCodecs.BOOL, OpenCharacterPayload::carrying,
            ::OpenCharacterPayload,
        )

        fun handle(payload: OpenCharacterPayload, context: IPayloadContext) {
            ClientHooks.openCharacter(payload)
        }
    }
}

/** Client accepts the next chapter of the named character [entityId]'s story. */
data class AcceptChapterPayload(val entityId: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<AcceptChapterPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<AcceptChapterPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "accept_chapter"))

        val STREAM_CODEC: StreamCodec<ByteBuf, AcceptChapterPayload> =
            ByteBufCodecs.VAR_INT.map(::AcceptChapterPayload, AcceptChapterPayload::entityId)

        fun handle(payload: AcceptChapterPayload, context: IPayloadContext) {
            Characters.accept(context.player(), payload.entityId)
        }
    }
}
