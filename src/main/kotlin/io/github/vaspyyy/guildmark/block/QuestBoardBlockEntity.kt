package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/** Holds the note pinned to one board cell. Synced to clients so clicking can open it without a round trip. */
class QuestBoardBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.QUEST_BOARD.get(), pos, state) {

    var note: QuestNote? = null
        private set

    /** Day index (see [VillageBoards.day]) the current note was pinned; unclaimed notes come down the next day. */
    var postedDay: Long = 0
        private set

    /** Anchor cell only: how many village advances are built, and progress toward the next. */
    var advanceIndex: Int = 0
        private set
    var advancePoints: Int = 0
        private set

    fun setAdvanceProgress(index: Int, points: Int) {
        advanceIndex = index
        advancePoints = points
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS) }
    }

    /** Server side: pin or remove a note, update the cell's paper model and sync to clients. */
    fun updateNote(newNote: QuestNote?) {
        note = newNote
        level?.let { postedDay = VillageBoards.day(it) }
        setChanged()
        val level = level ?: return
        val state = level.getBlockState(blockPos)
        if (state.block is QuestBoardBlock) {
            val newState = state.setValue(QuestBoardBlock.NOTE, NoteKind.of(newNote?.type))
            level.setBlock(blockPos, newState, Block.UPDATE_ALL)
            level.sendBlockUpdated(blockPos, state, newState, Block.UPDATE_ALL)
        }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.storeNullable("note", QuestNote.CODEC, note)
        output.putLong("posted_day", postedDay)
        output.putInt("advance_index", advanceIndex)
        output.putInt("advance_points", advancePoints)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        note = input.read("note", QuestNote.CODEC).orElse(null)
        postedDay = input.getLongOr("posted_day", 0L)
        advanceIndex = input.getIntOr("advance_index", 0)
        advancePoints = input.getIntOr("advance_points", 0)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)
}
