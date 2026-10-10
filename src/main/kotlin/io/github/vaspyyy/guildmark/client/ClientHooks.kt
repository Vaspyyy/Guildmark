package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.network.OpenReceptionPayload
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/** Client-only entry points. Only call these when the level is client side. */
object ClientHooks {
    /** A note still pinned to the board cell at [boardPos]. */
    fun openNote(note: QuestNote, boardPos: BlockPos) {
        Minecraft.getInstance().gui.setScreen(NoteScreen(note, boardPos, null))
    }

    /** A taken contract, with its progress if it has any. */
    fun openContract(note: QuestNote, state: ContractState?) {
        Minecraft.getInstance().gui.setScreen(NoteScreen(note, null, state))
    }

    /** The guild hall desk. */
    fun openReception(payload: OpenReceptionPayload) {
        Minecraft.getInstance().gui.setScreen(ReceptionScreen(payload))
    }

    /** A conversation with a named character. */
    fun openCharacter(payload: OpenCharacterPayload) {
        Minecraft.getInstance().gui.setScreen(CharacterScreen(payload))
    }

    /** Naming a new guild at the hall desk. */
    fun openFoundGuild(entityId: Int) {
        Minecraft.getInstance().gui.setScreen(FoundGuildScreen(entityId))
    }

    /** The village advances of the board whose anchor cell is [anchor]. */
    fun openAdvances(anchor: BlockPos) {
        Minecraft.getInstance().gui.setScreen(AdvanceScreen(anchor))
    }
}
