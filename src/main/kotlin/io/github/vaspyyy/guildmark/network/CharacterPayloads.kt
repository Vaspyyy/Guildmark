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
data class OpenCharacterPayload(
    val entityId: Int,
    val character: String,
    val chapters: Int,
    val carrying: Boolean,
    /** The player just finished this character's story: they say goodbye instead. */
    val finale: Boolean = false,
    /** For a faction's leader, what else bears on the talk (see Factions.GATED and the rest). */
    val flags: Int = 0,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenCharacterPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenCharacterPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_character"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenCharacterPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenCharacterPayload::entityId,
            ByteBufCodecs.STRING_UTF8, OpenCharacterPayload::character,
            ByteBufCodecs.VAR_INT, OpenCharacterPayload::chapters,
            ByteBufCodecs.BOOL, OpenCharacterPayload::carrying,
            ByteBufCodecs.BOOL, OpenCharacterPayload::finale,
            ByteBufCodecs.VAR_INT, OpenCharacterPayload::flags,
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

/** Client closed the conversation with the named character [entityId]. */
data class EndTalkPayload(val entityId: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EndTalkPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EndTalkPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "end_talk"))

        val STREAM_CODEC: StreamCodec<ByteBuf, EndTalkPayload> =
            ByteBufCodecs.VAR_INT.map(::EndTalkPayload, EndTalkPayload::entityId)

        fun handle(payload: EndTalkPayload, context: IPayloadContext) {
            Characters.endTalk(context.player(), payload.entityId)
        }
    }
}
