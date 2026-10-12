package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.ClientHooks
import io.github.vaspyyy.guildmark.guild.Holdings
import io.github.vaspyyy.guildmark.guild.RivalGuild
import io.github.vaspyyy.guildmark.guild.Rivals
import io.github.vaspyyy.guildmark.guild.TownControl
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Server tells the client to open a village's advances, with its name and who holds it. */
data class OpenAdvancesPayload(
    val anchor: BlockPos,
    val village: String,
    /** The holding guild's name, empty if no guild holds the village. */
    val holder: String,
    val holderColor: Int,
    val loyalty: Int,
    /** A TownControl.ClaimStatus ordinal for the player looking. */
    val claimStatus: Int,
    val claimFee: Int,
    val holdingCap: Int,
    /** A rival guild holds the village: the button challenges its captain instead of claiming. */
    val rivalHeld: Boolean,
    val advanceIndex: Int,
    /** How many villages a coach from here can reach along finished roads. */
    val coachStops: Int,
    /** The board is a capital's. */
    val capital: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenAdvancesPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenAdvancesPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "open_advances"))

        val STREAM_CODEC: StreamCodec<ByteBuf, OpenAdvancesPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenAdvancesPayload::anchor,
            ByteBufCodecs.STRING_UTF8, OpenAdvancesPayload::village,
            ByteBufCodecs.STRING_UTF8, OpenAdvancesPayload::holder,
            ByteBufCodecs.INT, OpenAdvancesPayload::holderColor,
            ByteBufCodecs.VAR_INT, OpenAdvancesPayload::loyalty,
            ByteBufCodecs.VAR_INT, OpenAdvancesPayload::claimStatus,
            ByteBufCodecs.VAR_INT, OpenAdvancesPayload::claimFee,
            ByteBufCodecs.VAR_INT, OpenAdvancesPayload::holdingCap,
            ByteBufCodecs.BOOL, OpenAdvancesPayload::rivalHeld,
            ByteBufCodecs.VAR_INT, OpenAdvancesPayload::advanceIndex,
            ByteBufCodecs.VAR_INT, OpenAdvancesPayload::coachStops,
            ByteBufCodecs.BOOL, OpenAdvancesPayload::capital,
            ::OpenAdvancesPayload,
        )

        fun handle(payload: OpenAdvancesPayload, context: IPayloadContext) {
            ClientHooks.openAdvances(payload)
        }
    }
}

/** Client asks to raise their guild's banner over the village whose board is at [anchor]. */
data class ClaimVillagePayload(val anchor: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClaimVillagePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ClaimVillagePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "claim_village"))

        val STREAM_CODEC: StreamCodec<ByteBuf, ClaimVillagePayload> =
            BlockPos.STREAM_CODEC.map(::ClaimVillagePayload, ClaimVillagePayload::anchor)

        fun handle(payload: ClaimVillagePayload, context: IPayloadContext) {
            val player = context.player()
            val level = player.level() as? ServerLevel ?: return
            if (!player.blockPosition().closerThan(payload.anchor, 12.0)) return
            val holding = Holdings.get(level).at(payload.anchor)
            if (holding != null && RivalGuild.byId(holding.guild) != null) Rivals.challenge(level, player, payload.anchor)
            else TownControl.claim(level, player, payload.anchor)
            TownControl.openAdvances(level, player as net.minecraft.server.level.ServerPlayer, payload.anchor)
        }
    }
}
