package io.github.vaspyyy.guildmark.client

import io.github.vaspyyy.guildmark.client.atlas.AtlasClient
import io.github.vaspyyy.guildmark.client.ui.DialogueScreen
import io.github.vaspyyy.guildmark.network.AtlasPayload
import io.github.vaspyyy.guildmark.network.PartyPositionsPayload
import io.github.vaspyyy.guildmark.network.OpenCharacterPayload
import io.github.vaspyyy.guildmark.network.OpenReceptionPayload
import io.github.vaspyyy.guildmark.quest.ContractState
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.network.OpenAdvancesPayload
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
        val minecraft = Minecraft.getInstance()
        // A held use key can send the same click twice: keep the talk that's already going
        if ((minecraft.gui.screen() as? DialogueScreen)?.entityId == payload.entityId) return
        minecraft.gui.setScreen(DialogueScreen(payload))
    }

    /** Naming a new guild at the hall desk. */
    fun openFoundGuild(entityId: Int) {
        Minecraft.getInstance().gui.setScreen(FoundGuildScreen(entityId))
    }

    /** The places the player has found, for the compass and the map. */
    fun atlas(payload: AtlasPayload) {
        AtlasClient.receive(payload)
    }

    /** Where the player's party mates are. */
    fun partyPositions(payload: PartyPositionsPayload) {
        AtlasClient.receiveParty(payload)
    }

    /** A village's advances and who holds it, as the server sent them. */
    fun openAdvances(info: OpenAdvancesPayload) {
        Minecraft.getInstance().gui.setScreen(AdvanceScreen(info))
    }
}
