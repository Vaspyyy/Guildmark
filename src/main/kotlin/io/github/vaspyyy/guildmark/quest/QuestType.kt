package io.github.vaspyyy.guildmark.quest

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

enum class QuestType(private val id: String) : StringRepresentable {
    FETCH("fetch"),
    HUNT("hunt"),
    CLEAR("clear");

    override fun getSerializedName(): String = id

    companion object {
        val CODEC: Codec<QuestType> = StringRepresentable.fromEnum(QuestType::values)
    }
}
