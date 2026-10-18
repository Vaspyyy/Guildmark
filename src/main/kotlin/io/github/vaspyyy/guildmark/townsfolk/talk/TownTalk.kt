package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.townsfolk.Resident
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * What the capitals' people remember of the players and what goes round among them (living-world/design-voices.md
 * sections 3 and 4): each resident's acquaintance with each player, the rumours a capital holds, who has heard which
 * and in which version, and what befell each resident. The talk, the topic sources and the crier read and add to it
 * only through what follows.
 *
 * As it stands this is the interface only, fixed so that the parts can be built at once: nobody remembers anything
 * yet, no rumour starts, and everyone is a stranger.
 */
object TownTalk {
    /** How well [r] knows [player] (a stranger for anyone with no resident record). */
    fun familiarity(level: ServerLevel, r: Resident?, player: ServerPlayer): Familiarity = Familiarity.STRANGER

    /** What [r] remembers of [player], worked out to now, or null if they've never met nor seen them. */
    fun knows(level: ServerLevel, r: Resident, player: UUID): Acquaintance? = null

    /** [r] and [player] have talked or dealt today. */
    fun met(level: ServerLevel, r: Resident, player: ServerPlayer) {}

    /** [player] told [r] their name. */
    fun named(level: ServerLevel, r: Resident, player: ServerPlayer) {}

    /** [player] dealt at [r]'s stall for [marks] Marks; [short]: in what the town was short of, a favour too. */
    fun traded(level: ServerLevel, r: Resident, player: ServerPlayer, marks: Int, short: Boolean) {}

    /** [player] did [r] a good turn. */
    fun favour(level: ServerLevel, r: Resident, player: UUID, favour: Favour) {}

    /** [player] wronged [r]. */
    fun slight(level: ServerLevel, r: Resident, player: UUID, slight: Slight) {}

    /**
     * Something happened at [at]: the residents within [radius] who could see it know it as a rumour (of [kind], about
     * player [about] and resident [resident], with its facts [args] as Speech arguments), which is returned; null if
     * nobody saw it or it heated an old rumour of the same thing.
     */
    fun witness(
        level: ServerLevel, at: BlockPos, kind: String, about: UUID?, resident: Int, args: List<String>,
        heat: Float, fade: Fade, scope: Scope, tone: Int, radius: Double = 16.0,
    ): Rumour? = null

    /** A rumour a system starts at [capital], known first by [knowers] (resident ids). */
    fun start(
        level: ServerLevel, capital: BlockPos, kind: String, about: UUID?, resident: Int, args: List<String>,
        heat: Float, fade: Fade, scope: Scope, tone: Int, knowers: Collection<Int>,
    ): Rumour = Rumour(-1, kind, about, resident, args, level.gameTime / 1000L, heat, fade, scope, tone, null)

    /** What [r] has heard, hottest first. */
    fun known(level: ServerLevel, r: Resident): List<Known> = listOf()

    /** [from] told [to] rumour [rumour] in a chat seen on screen. */
    fun tell(level: ServerLevel, from: Resident, to: Resident, rumour: Int) {}

    /** [from] (null: the crier, or someone with no record) told [player] rumour [rumour]. */
    fun told(level: ServerLevel, from: Resident?, player: ServerPlayer, rumour: Int) {}

    /** The rumours [player] has heard at [capital]: each rumour's id to the version they heard. */
    fun heard(level: ServerLevel, capital: BlockPos, player: UUID): Map<Int, Int> = mapOf()

    /** What has befallen [r] lately, newest first. */
    fun felt(level: ServerLevel, r: Resident): List<Felt> = listOf()

    /** The capital's hottest rumour fit for the crier and the board, if one is. */
    fun talkOfTheTown(level: ServerLevel, capital: BlockPos): Rumour? = null
}

/**
 * What a resident remembers of one player (section 3), worked out to the moment it was asked for: [met] the day they
 * first talked or dealt (-1: only seen); whether they know the player's name ([named]); the last day they talked or dealt
 * ([seen]) and on how many [days]; their [trades] at the resident's stall and the Marks [spent] there; the [favour] and
 * [slights] felt now, faded; the slight that weighs most now ([worst]); the deeds they saw the player do ([witnessed],
 * newest first); and the [opinion] worked out from it all, from -100 to 100.
 */
class Acquaintance(
    val player: UUID,
    val met: Int,
    val named: Boolean,
    val seen: Int,
    val days: Int,
    val trades: Int,
    val spent: Int,
    val favour: Double,
    val slights: Double,
    val worst: Slight?,
    val witnessed: List<Deed>,
    val opinion: Int,
)

/** A deed a resident saw a player do: its rumour kind ("deed.dragon"), the day, and whether it was for good or ill. */
class Deed(val kind: String, val day: Int, val tone: Int)

/** A good turn a player did a resident, and how much it counts (section 3; half-life 10 days). */
enum class Favour(val amount: Double) {
    /** Sold the town what it was short of, at their stall. */
    SOLD_SHORT(0.5),
    /** Escorted the wagon in (M4): the gate guards and keepers. */
    ESCORTED(0.5),
    /** Filled their order (M3). */
    ORDER_FILLED(1.0),
    /** Brought back salvage from their wagon (M4). */
    SALVAGE(1.0),
    /** Freed them, or brought them home. */
    FREED(3.0),
    /** Saved them from a monster. */
    SAVED(3.0),
}

/** A wrong a player did a resident: how much it counts, and its half-life in days (section 3). */
enum class Slight(val amount: Double, val halfLife: Int) {
    /** Walked off mid-talk (once a day). */
    WALKED_OFF(0.2, 5),
    /** Let their order lapse (M3). */
    ORDER_LAPSED(0.5, 5),
    /** Kept salvage off their wagon (M4). */
    SALVAGE_KEPT(1.0, 5),
    /** Caught lifting their purse. */
    THEFT(2.0, 15),
    /** Struck them. */
    STRUCK(3.0, 15),
    /** Struck one of their household. */
    STRUCK_KIN(1.5, 15),
    /** Killed someone in front of them. */
    KILLING_SEEN(2.0, 15),
    /** Killed one of their household. */
    KILLED_KIN(8.0, 60),
}

/** How fast a rumour cools: its heat is multiplied by [daily] each dawn. */
enum class Fade(val daily: Float) {
    /** Small talk. */
    SMALL(0.7f),
    /** Big news. */
    BIG(0.85f),
    /** Lore: a dragon slain, the Hollow King fallen, talked of for a season. */
    LORE(0.95f),
}

/** How far a rumour may go: its own capital, or the others too. */
enum class Scope { TOWN, REGION }

/** Where a resident heard a rumour, which the lead-in says ("Word in the tavern is..."). */
enum class Venue { SEEN, HOUSEHOLD, VISIT, TAVERN, WELL, MARKET, WORK, TEMPLE, STROLL, STREET, CRIER, AFAR }

/**
 * Something said about the town (section 4): [id] within its capital; its [kind] ("deed.dragon", "slight.murder",
 * "death", "market.event.blight", "road.waylaid", ...); the player it's [about] and the [resident] it's about (or -1);
 * its facts as Speech arguments ([args]: the good, the band's name, the place, a name as "=Tam Miller"); the hour it was
 * [born]; how much people want to tell it ([heat], 0 to 1), how it fades and how far it goes; whether it's bad or good
 * news ([tone]); and where it happened ([origin]), for "at the ford".
 */
class Rumour(
    val id: Int,
    val kind: String,
    val about: UUID?,
    val resident: Int,
    val args: List<String>,
    val born: Long,
    var heat: Float,
    val fade: Fade,
    val scope: Scope,
    val tone: Int,
    val origin: BlockPos?,
)

/**
 * How a resident came by a rumour: which ([rumour]), the [hour], [from] whom (a resident; -1 the crier; -2 from afar),
 * where ([venue]), which version they had (warp: 0 as it happened, 1 grown in the telling, 2 muddled, 3 a tall tale)
 * and how many they've [told].
 */
class Heard(val rumour: Int, val hour: Long, val from: Int, val venue: Venue, val warp: Int, var told: Int)

/** A rumour a resident knows, as they heard it. */
class Known(val rumour: Rumour, val heard: Heard)

/** What befell a resident lately, and how many days it stays with them. */
enum class Experience(val days: Int) { HUNGRY(7), FRIGHTENED(7), ROBBED(15), SAVED(15), BEREAVED(30) }

/** Something that befell a resident on [day]; [about] is the resident it concerned (the one who died), or -1. */
class Felt(val experience: Experience, val day: Int, val about: Int = -1)
