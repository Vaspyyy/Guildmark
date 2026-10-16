package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.faction.SuspicionHud
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Client asks the faction leader [entityId] for work between chapters. */
data class FactionWorkPayload(val entityId: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FactionWorkPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<FactionWorkPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "faction_work"))

        val STREAM_CODEC: StreamCodec<ByteBuf, FactionWorkPayload> =
            ByteBufCodecs.VAR_INT.map(::FactionWorkPayload, FactionWorkPayload::entityId)

        fun handle(payload: FactionWorkPayload, context: IPayloadContext) {
            Factions.giveWork(context.player(), payload.entityId)
        }
    }
}

/**
 * Server tells a player on a heist how suspicious the watcher who's nearest to catching them is ([amount], out
 * of 100; 0 when nobody suspects a thing) and which one it is ([watcher]), for the eye on the HUD.
 */
data class SuspicionPayload(val amount: Int, val watcher: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SuspicionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SuspicionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "suspicion"))

        val STREAM_CODEC: StreamCodec<ByteBuf, SuspicionPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SuspicionPayload::amount,
            ByteBufCodecs.VAR_INT, SuspicionPayload::watcher,
            ::SuspicionPayload,
        )

        fun handle(payload: SuspicionPayload, context: IPayloadContext) {
            SuspicionHud.onSuspicion(payload)
        }
    }
}
