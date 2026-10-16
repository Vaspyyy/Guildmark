package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.Guildmark
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/** An answer the player gave their companion [entityId] in the talk that changes something: [order] is one of [JOIN] and the rest. */
data class CompanionOrderPayload(val entityId: Int, val order: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CompanionOrderPayload> = TYPE

    companion object {
        /** "Travel with me." */
        const val JOIN = 0
        /** "Follow me." (from waiting) */
        const val FOLLOW = 1
        /** "Wait here." */
        const val WAIT = 2
        /** "Go your own way for now." */
        const val PART = 3
        /** "Travel with me again." (after parting) */
        const val REJOIN = 4
        /** "Come back with me." (after they left in anger, and have cooled) */
        const val RETURN = 5
        /** "Take this." (what's in the player's hand) */
        const val GIFT = 6

        val TYPE: CustomPacketPayload.Type<CompanionOrderPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "companion_order"))

        val STREAM_CODEC: StreamCodec<ByteBuf, CompanionOrderPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CompanionOrderPayload::entityId,
            ByteBufCodecs.VAR_INT, CompanionOrderPayload::order,
            ::CompanionOrderPayload,
        )

        fun handle(payload: CompanionOrderPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Companions.order(player, payload.entityId, payload.order)
        }
    }
}
