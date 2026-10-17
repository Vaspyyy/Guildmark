package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.util.StringRepresentable

/** Which paper (if any) a board cell shows. Mirrors the cell's quest so the model can render it. */
enum class NoteKind(private val id: String) : StringRepresentable {
    NONE("none"),
    FETCH("fetch"),
    HUNT("hunt"),
    CLEAR("clear"),
    DELIVER("deliver"),
    ESCORT("escort"),
    CHAMPION("champion"),
    LAIR("lair"),
    CHART("chart"),
    SALVAGE("salvage"),
    SLAY("slay"),
    WANTED("wanted");

    override fun getSerializedName(): String = id

    companion object {
        fun of(type: QuestType?): NoteKind = when (type) {
            null -> NONE
            QuestType.FETCH -> FETCH
            QuestType.HUNT -> HUNT
            QuestType.CLEAR -> CLEAR
            QuestType.DELIVER -> DELIVER
            QuestType.ESCORT -> ESCORT
            QuestType.CHAMPION -> CHAMPION
            QuestType.LAIR -> LAIR
            QuestType.CHART -> CHART
            QuestType.SALVAGE -> SALVAGE
            QuestType.SLAY -> SLAY
            QuestType.BOUNTY -> WANTED
            // A plea for someone taken: the same folded paper as asking someone to see a traveller home
            QuestType.RESCUE -> ESCORT
            // Never posted on a board; drawn like a champion's notice if one ever is
            QuestType.TRIAL -> CHAMPION
        }
    }
}
