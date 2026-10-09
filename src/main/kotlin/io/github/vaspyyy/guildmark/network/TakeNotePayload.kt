package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.quest.Contracts
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Client asks to tear the note off the board cell at [pos]. */
data class TakeNotePayload(val pos: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TakeNotePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TakeNotePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "take_note"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TakeNotePayload> =
            BlockPos.STREAM_CODEC.map(::TakeNotePayload, TakeNotePayload::pos)

        fun handle(payload: TakeNotePayload, context: IPayloadContext) {
            val player = context.player()
            val level = player.level()
            if (!player.isWithinBlockInteractionRange(payload.pos, 1.0)) return
            val board = level.getBlockEntity(payload.pos) as? QuestBoardBlockEntity ?: return
            val note = board.note ?: return

            val serverLevel = level as? ServerLevel ?: return
            val facing = level.getBlockState(payload.pos).getValue(QuestBoardBlock.FACING)
            val contract = Contracts.take(note, serverLevel, payload.pos, facing, player) ?: return
            board.updateNote(null)
            player.inventory.placeItemBackInInventory(contract, Prediction.SERVER_ONLY)
            level.playSound(null, payload.pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0f, 1.0f)
        }
    }
}
