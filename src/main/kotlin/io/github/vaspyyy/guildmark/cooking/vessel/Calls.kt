package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Station
import net.minecraft.util.RandomSource
import java.util.UUID

/** What a vessel asks of its cook (living-world/cook/design.md 1.4, Appendix C.2): sent in the view as the ordinal plus one. Append only. */
enum class Call {
    STICK,
    BOIL_OVER,
    SKIM,
    FLARE,
    TURN,
    SEASON,
    ;

    /** What a [StationView.call] carries for this call: its ordinal plus one (0 means none). */
    val wire: Int get() = ordinal + 1

    companion object {
        /** The call a [StationView.call] value names, or null for 0 and anything unknown. */
        fun of(wire: Int): Call? = entries.getOrNull(wire - 1)
    }
}

/**
 * The calls of a vessel (living-world/cook/design.md 1.4): how long to the first and between two, which call comes at
 * which station, how much an answer earns, and the two tables of who is busy answering and who has just tasted. The
 * numbers are the first lines of the file, my estimates of what feels right and the first things to turn.
 */
object Calls {
    // ---- The numbers (1.4) ----

    /** Real seconds of a lit, fuelled fire into COOKING that the first call comes. */
    const val FIRST_SECONDS = 10.0

    /** Real seconds between two calls on the low, mid and high notch, before the jitter below. */
    val GAP_SECONDS: DoubleArray = doubleArrayOf(36.0, 25.0, 17.0)

    /** Each gap is this much of its table value at the least... */
    const val JITTER_LOW = 0.8

    /** ...and this much at the most. */
    const val JITTER_HIGH = 1.2

    /** A call answered within this many ticks of its cue earns full credit (4 s). */
    const val FULL_TICKS = 80L

    /** A call answered within this many ticks of its cue earns half (7.5 s); after it, nothing, and the missed effect. */
    const val LATE_TICKS = 150L

    /** How far from a vessel a player is called, blocks (1.2: it ticks only with a player this near). */
    const val NEAR_BLOCKS = 16.0

    /** A SEASON call may come once the cook is this far through its time (the last third, 1.4). */
    const val SEASON_FROM = 2.0 / 3.0

    /** Until Part C's signs are on the screen, a call is also said in the action bar of the player it goes to. */
    const val SAY_CALLS = true

    /** Ticks a player waits between two tastes of one vessel (6 s, 2.6). */
    const val TASTE_TICKS = 120L

    /** How much more than a fifth of a pot must be meat, fish or bone for a SKIM to be asked (1.4). */
    const val SKIM_SHARE = 0.2

    // ---- What a call earns ----

    /** The credit an answer [elapsed] ticks after the cue earns: 1 within [FULL_TICKS], a half within [LATE_TICKS], else 0. */
    fun credit(elapsed: Long): Double = when {
        elapsed <= FULL_TICKS -> 1.0
        elapsed <= LATE_TICKS -> 0.5
        else -> 0.0
    }

    /** The real seconds from one call to the next at the notch [notch], with its jitter. */
    fun gap(notch: Int, random: RandomSource): Double =
        GAP_SECONDS[notch.coerceIn(0, 2)] * (JITTER_LOW + random.nextDouble() * (JITTER_HIGH - JITTER_LOW))

    // ---- Which call comes (1.4) ----

    /**
     * The call a vessel of [station] cooking [kind] (null: Gruel) at heat [notch] asks for now, or null if none fits.
     * [progress] is the dish-clock over the cooking time, [meaty] whether meat, fish or bone is over a fifth of the mix,
     * [seasoned] whether a SEASON call has come in this batch, and [last] the call before (it is half as likely again).
     * The pot is mostly STICK, then BOIL_OVER, SKIM and, once in the last third, SEASON; the hob STICK and TURN with a
     * FLARE now and then; the oven TURN, FLARE and, for pie, tart and fish, once SEASON; the spit TURN and FLARE.
     * BOIL_OVER and FLARE are never asked at the lowest notch, where they could not be answered.
     */
    fun pick(station: Station, kind: DishKind?, notch: Int, progress: Double, meaty: Boolean, seasoned: Boolean, last: Call?, random: RandomSource): Call? {
        val w = DoubleArray(Call.entries.size)
        val canSeason = !seasoned && kind != null && progress >= SEASON_FROM
        when (station) {
            Station.STEWPOT -> {
                w[Call.STICK.ordinal] = 5.0
                w[Call.BOIL_OVER.ordinal] = when (notch) { 0 -> 0.0; 1 -> 2.0; else -> 4.0 }
                w[Call.SKIM.ordinal] = if (meaty) 2.0 else 0.0
                if (canSeason) w[Call.SEASON.ordinal] = 40.0
            }
            Station.HOB -> {
                w[Call.STICK.ordinal] = 5.0
                w[Call.TURN.ordinal] = 4.0
                w[Call.FLARE.ordinal] = if (notch == 0) 0.0 else 1.0
            }
            Station.OVEN -> {
                w[Call.TURN.ordinal] = 5.0
                w[Call.FLARE.ordinal] = if (notch == 0) 0.0 else 2.0
                if (canSeason && (kind == DishKind.PIE || kind == DishKind.TART || kind == DishKind.FISH)) w[Call.SEASON.ordinal] = 40.0
            }
            Station.SPIT -> {
                w[Call.TURN.ordinal] = 5.0
                w[Call.FLARE.ordinal] = if (notch == 0) 0.0 else 2.0
            }
        }
        if (last != null) w[last.ordinal] *= 0.5
        var total = 0.0
        for (x in w) total += x
        if (total <= 0.0) return null
        var roll = random.nextDouble() * total
        for (i in w.indices) {
            roll -= w[i]
            if (roll < 0.0 && w[i] > 0.0) return Call.entries[i]
        }
        for (i in w.indices.reversed()) if (w[i] > 0.0) return Call.entries[i]
        return null
    }

    // ---- Who is busy answering (one call due to a player at a time, 1.4) ----

    private val busy = HashMap<UUID, Long>()

    /** [who] now has a call to answer, until game time [until]; no other vessel calls them before then. */
    fun hold(who: UUID, until: Long) {
        busy[who] = until
    }

    /** [who] has answered, or the call has lapsed. */
    fun release(who: UUID) {
        busy.remove(who)
    }

    /** Whether [who] still has a call to answer at game time [now]. A stamp that has run out, or lies later than now, holds nobody. */
    fun isBusy(who: UUID, now: Long): Boolean {
        val until = busy[who] ?: return false
        if (until <= now || until - LATE_TICKS > now) {
            busy.remove(who)
            return false
        }
        return true
    }

    // ---- Who has just tasted (a 6 s cool-down for each vessel and player, 2.6) ----

    private data class Taste(val dimension: Int, val pos: Long, val who: UUID)

    private val tasted = HashMap<Taste, Long>()

    /** Whether [who] may taste the vessel at [pos] in [dimension] now; if so, the cool-down starts. A stamp later than [now] holds nothing back. */
    fun tryTaste(dimension: Int, pos: Long, who: UUID, now: Long): Boolean {
        val key = Taste(dimension, pos, who)
        val last = tasted[key]
        if (last != null && last <= now && now - last < TASTE_TICKS) return false
        if (tasted.size > 512) tasted.entries.removeIf { it.value > now || now - it.value >= TASTE_TICKS }
        tasted[key] = now
        return true
    }

    init {
        Cooking.onForget {
            busy.clear()
            tasted.clear()
        }
    }
}
