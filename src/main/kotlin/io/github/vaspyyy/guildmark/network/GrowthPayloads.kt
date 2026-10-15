package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.progression.Levelling
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Optional

/** Client raises the stats in [stats] (ids, one point each, in order) at the Wardfire at [at]. */
data class GrowPayload(val at: Optional<BlockPos>, val stats: List<String>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<GrowPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GrowPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "grow"))

        /** Most raises one payload holds; send more in several. */
        const val MAX_STATS = 64

        val STREAM_CODEC: StreamCodec<ByteBuf, GrowPayload> = StreamCodec.composite(
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), GrowPayload::at,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_STATS)), GrowPayload::stats,
            ::GrowPayload,
        )

        fun handle(payload: GrowPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Levelling.grow(player, payload.at.orElse(null), payload.stats)
        }
    }
}

/** Client learns, forgets or starts over skill tree nodes; forgetting needs the Wardfire at [at]. */
data class TreePayload(val action: Int, val ids: List<String>, val at: Optional<BlockPos>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TreePayload> = TYPE

    companion object {
        const val LEARN = 0
        const val UNLEARN = 1
        const val RESET = 2
        /** Most nodes one payload holds; send more in several. */
        const val MAX_IDS = 64

        val TYPE: CustomPacketPayload.Type<TreePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "tree"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TreePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TreePayload::action,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_IDS)), TreePayload::ids,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), TreePayload::at,
            ::TreePayload,
        )

        fun handle(payload: TreePayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            val at = payload.at.orElse(null)
            when (payload.action) {
                LEARN -> Levelling.learn(player, payload.ids)
                UNLEARN -> payload.ids.firstOrNull()?.let { Levelling.unlearn(player, at, it) }
                RESET -> Levelling.reset(player, at)
            }
        }
    }
}
