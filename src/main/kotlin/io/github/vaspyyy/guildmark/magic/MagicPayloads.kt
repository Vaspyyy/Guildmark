package io.github.vaspyyy.guildmark.magic

import io.github.vaspyyy.guildmark.Guildmark
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Optional

/** Client attunes [spells] (known spell ids, in slot order) at the Wardfire at [at]. */
data class AttunePayload(val at: Optional<BlockPos>, val spells: List<String>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<AttunePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<AttunePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "attune"))

        val STREAM_CODEC: StreamCodec<ByteBuf, AttunePayload> = StreamCodec.composite(
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), AttunePayload::at,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(Spellbook.MAX_SLOTS)), AttunePayload::spells,
            ::AttunePayload,
        )

        fun handle(payload: AttunePayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            Spellbook.attune(player, payload.at.orElse(null), payload.spells)
        }
    }
}

/** Without the action combat: client casts [spell] (its ordinal) at once. */
data class QuickCastPayload(val spell: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<QuickCastPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<QuickCastPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "quick_cast"))

        val STREAM_CODEC: StreamCodec<ByteBuf, QuickCastPayload> = ByteBufCodecs.VAR_INT.map(::QuickCastPayload, QuickCastPayload::spell)

        fun handle(payload: QuickCastPayload, context: IPayloadContext) {
            val player = context.player() as? ServerPlayer ?: return
            val spell = Spell.entries.getOrNull(payload.spell) ?: return
            Magic.quickCast(player, spell)
        }
    }
}
