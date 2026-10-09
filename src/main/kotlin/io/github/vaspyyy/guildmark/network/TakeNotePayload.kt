package io.github.vaspyyy.guildmark.network

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
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

            board.updateNote(null)
            val contract = ItemStack(ModItems.CONTRACT.get())
            contract.set(ModDataComponents.QUEST_NOTE.get(), note)
            contract.set(DataComponents.LORE, ItemLore(listOf(note.title(), note.rewardLine())))
            player.inventory.placeItemBackInInventory(contract, Prediction.SERVER_ONLY)
            level.playSound(null, payload.pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0f, 1.0f)
        }
    }
}
