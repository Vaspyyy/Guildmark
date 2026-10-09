package io.github.vaspyyy.guildmark.block

import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.util.StringRepresentable

/** Which paper (if any) a board cell shows. Mirrors the cell's quest so the model can render it. */
enum class NoteKind(private val id: String) : StringRepresentable {
    NONE("none"),
    FETCH("fetch"),
    HUNT("hunt"),
    CLEAR("clear");

    override fun getSerializedName(): String = id

    companion object {
        fun of(type: QuestType?): NoteKind = when (type) {
            null -> NONE
            QuestType.FETCH -> FETCH
            QuestType.HUNT -> HUNT
            QuestType.CLEAR -> CLEAR
        }
    }
}
