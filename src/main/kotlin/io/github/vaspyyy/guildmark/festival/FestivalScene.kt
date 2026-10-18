package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.townsfolk.Activity
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3

/** The parts of a festival's day, in local ticks from six in the morning. */
enum class FestivalPhase(val id: String, val from: Int) {
    /** The square is dressed: the centrepiece, the posts and their lanterns, the trim, piece by piece. */
    SETUP("setup", 0),
    /** The fair: the band plays sets, the ring dances, the day's contest is open. */
    FAIR("fair", 1000),
    /** Everyone gathers round the host for the prize-giving. */
    CEREMONY("ceremony", 11000),
    /** Dancing on into the evening, fireworks, and the Lanternrise's lanterns let go. */
    DUSK("dusk", 12000),
    /** Everyone goes home and the decorations come down. */
    TEARDOWN("teardown", 14000);

    val title: Component get() = Component.translatable("festival.guildmark.phase.$id")

    companion object {
        /** When the last decoration should be down. */
        const val OVER = 15000

        fun byId(id: String): FestivalPhase? = entries.firstOrNull { it.id == id }

        fun at(local: Int): FestivalPhase = entries.last { local >= it.from }
    }
}

/** What someone can be doing at a festival (see [FestivalScene.spotFor]). */
enum class FestivalPart {
    /** In the crowd round the dance: clapping along, cheering, raising a mug. */
    WATCH,
    /** Dancing (an outer ring for anyone the festival's own ring has no place for). */
    DANCE,
    /** Playing in the band. */
    PLAY,
    /** Hosting: calling the dances and the prizes. */
    HOST,
    /** Helping put the decorations up in the morning. */
    HELP,
}

/** Where to stand, facing which way, doing what. */
data class FestivalSpot(val pos: Vec3, val yaw: Float, val activity: Activity, val part: FestivalPart)

/**
 * A festival going on in a town, as anyone outside the festival code sees it: the Living World's own townsfolk can
 * ask where to stand and what to do ([spotFor]) without the festival having to direct them. Get one with
 * [Festivities.sceneAt]. Server side; a scene goes stale when its festival ends (check [live]).
 */
interface FestivalScene {
    val kind: FestivalKind
    /** The town's name. */
    val townName: String
    /** The middle of the festival (a capital's fountain, or the village's ring), at the height feet stand at. */
    val centre: Vec3
    val phase: FestivalPhase
    /** Whether the band is playing a set (and the ring dancing to it). */
    val playing: Boolean
    /** Which bar of the set it is (0 to 35), or -1 between sets. A bar is 40 ticks, starting on the beat. */
    val bar: Int
    /** Whether the festival is still on (false once it's over and gone). */
    val live: Boolean

    /**
     * Where the [index]th person taking [part] should be now and what they should be doing: a place in the crowd, a
     * step in the outer ring of the dance (moving round with the music while a set plays), a place in the band, the
     * host's stage, or the next decoration to be put up. Null when there's no such place right now (the festival is
     * over, say, or there's no room to dance). Asked once a tick per person it's cheap enough.
     */
    fun spotFor(index: Int, part: FestivalPart): FestivalSpot?
}
