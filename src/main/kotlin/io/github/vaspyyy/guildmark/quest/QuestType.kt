package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.village.StandingTier

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

enum class QuestType(private val id: String) : StringRepresentable {
    FETCH("fetch"),
    HUNT("hunt"),
    CLEAR("clear"),

    /** Carry a sealed letter to another village's board. */
    DELIVER("deliver"),

    /** Walk a traveller to another village's board. */
    ESCORT("escort"),

    /** Slay one named, well-armed mob spawned when the contract is taken. */
    CHAMPION("champion"),

    /** Clear a monster lair built out in the wild when the contract is taken, by killing its master. */
    LAIR("lair"),

    /**
     * A faction's own kind of job, set up when it's taken (see faction/Trials): its kind is the target's path,
     * guildmark:<kind>, and its progress is counted by that kind's own rules. Never posted on a board.
     */
    TRIAL("trial");

    /** One-off jobs: a single letter, traveller, champion or lair rather than a count. */
    val isSingle: Boolean get() = this == DELIVER || this == ESCORT || this == CHAMPION || this == LAIR

    /** Villages only trust these jobs to people they know. */
    val minStanding: StandingTier get() = if (this == CHAMPION || this == LAIR) StandingTier.KNOWN else StandingTier.STRANGER

    /** Jobs that travel a road to another village; locked until the village has a Trade Road. */
    val needsRoad: Boolean get() = this == DELIVER || this == ESCORT

    /** Jobs counted by kills, which show "Progress: x / y". */
    val showsProgress: Boolean get() = this == HUNT || this == CLEAR || this == CHAMPION || this == TRIAL

    override fun getSerializedName(): String = id

    companion object {
        val CODEC: Codec<QuestType> = StringRepresentable.fromEnum(QuestType::values)
    }
}
