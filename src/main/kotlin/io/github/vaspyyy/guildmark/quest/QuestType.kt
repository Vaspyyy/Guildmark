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

    /** Sail out and sight islands you've never seen. */
    CHART("chart"),

    /**
     * Sail to an island of one kind (the target's path: wreck, cove or shrine), picked when the contract is taken,
     * and get into the chest at its heart: the captain's chest, the smugglers' strongbox, the shrine's altar chest.
     */
    SALVAGE("salvage"),

    /**
     * Slay a field boss that keeps a place of its own (the target: guildmark:hill_troll or guildmark:dragon), the
     * nearest to the board when the contract is taken, in its camp or on its roost.
     */
    SLAY("slay"),

    /**
     * Bring down an outlaw band's captain (the target is always guildmark:outlaw): the band the note names, posted while
     * it's camped within reach of the board, or for a note naming no one, the nearest such band when it's taken.
     */
    BOUNTY("bounty"),

    /**
     * A faction's own kind of job, set up when it's taken (see faction/Trials): its kind is the target's path,
     * guildmark:<kind>, and its progress is counted by that kind's own rules. Never posted on a board.
     */
    TRIAL("trial");

    /** One-off jobs: a single letter, traveller, champion, lair, island, beast or outlaw rather than a count. */
    val isSingle: Boolean get() = this == DELIVER || this == ESCORT || this == CHAMPION || this == LAIR || this == SALVAGE || this == SLAY || this == BOUNTY

    /** Villages only trust these jobs to people they know. */
    val minStanding: StandingTier get() = if (this == CHAMPION || this == LAIR || this == SLAY || this == BOUNTY) StandingTier.KNOWN else StandingTier.STRANGER

    /** Jobs that travel a road to another village; locked until the village has a Trade Road. */
    val needsRoad: Boolean get() = this == DELIVER || this == ESCORT

    /** Jobs out at sea, only posted where the sea is near. */
    val needsSea: Boolean get() = this == CHART || this == SALVAGE

    /** Jobs counted by kills, which show "Progress: x / y". */
    val showsProgress: Boolean get() = this == HUNT || this == CLEAR || this == CHAMPION || this == TRIAL || this == CHART

    override fun getSerializedName(): String = id

    companion object {
        val CODEC: Codec<QuestType> = StringRepresentable.fromEnum(QuestType::values)
    }
}
