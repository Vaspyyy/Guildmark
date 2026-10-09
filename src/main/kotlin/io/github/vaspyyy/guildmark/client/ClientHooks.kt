package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.quest.QuestNote
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/** Client-only entry points. Only call these when the level is client side. */
object ClientHooks {
    /** [boardPos] is the board cell the note is pinned to, or null when reading a taken contract. */
    fun openNote(note: QuestNote, boardPos: BlockPos?) {
        Minecraft.getInstance().gui.setScreen(NoteScreen(note, boardPos))
    }
}
