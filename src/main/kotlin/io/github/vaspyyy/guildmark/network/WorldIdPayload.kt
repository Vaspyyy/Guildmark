package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** The world's id (see WorldId), sent on login so the client knows which of its maps to open. */
data class WorldIdPayload(val id: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WorldIdPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WorldIdPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "world_id"))

        val STREAM_CODEC: StreamCodec<ByteBuf, WorldIdPayload> = ByteBufCodecs.STRING_UTF8.map(::WorldIdPayload, WorldIdPayload::id)

        fun handle(payload: WorldIdPayload, context: IPayloadContext) {
            ClientHooks.worldId(payload)
        }
    }
}
