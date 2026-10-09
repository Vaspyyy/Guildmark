package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.Perk
import io.github.vaspyyy.guildmark.progression.Progression
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Client asks to put a perk point into [perkId]. */
data class SpendPerkPayload(val perkId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SpendPerkPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SpendPerkPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "spend_perk"))

        val STREAM_CODEC: StreamCodec<ByteBuf, SpendPerkPayload> =
            ByteBufCodecs.STRING_UTF8.map(::SpendPerkPayload, SpendPerkPayload::perkId)

        fun handle(payload: SpendPerkPayload, context: IPayloadContext) {
            val perk = Perk.byId(payload.perkId) ?: return
            Progression.spend(context.player(), perk)
        }
    }
}
