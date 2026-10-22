package io.github.vaspyyy.guildmark.village.live

import net.minecraft.server.level.ServerLevel

/**
 * What the villages may spend this tick, so that dozens of them cost about what the vanilla ones did (living-world/villages/
 * design.md section 13.5): the hour steps the capitals' run leaves, the columns the census may read, whether one more
 * villager may be taken in, and whether a unit of work of so many microseconds fits. The numbers are worked out afresh at
 * the start of every server tick ([begin]), and the work that spends them says what it spent ([spent], [usedColumns],
 * [usedSteps]); work that doesn't fit waits for the next tick and nothing is dropped. Part V1 gives it enough for the census
 * and part V11 completes it.
 */
object VillageBudget {
    /** All the villages may spend in a tick: 1.5 milliseconds (design section 13.5, rule 2). */
    const val TICK_NANOS = 1_500_000L

    /** The census's share of it, which is what keeps the census under a millisecond a tick with room to spare for one unit going over. */
    const val CENSUS_NANOS = 700_000L

    /** The hour steps a run of the villages may take, after the capitals' (design section 4.5). */
    const val STEPS = 12

    /** The columns of the world the census may read in a tick. */
    const val COLUMNS = 1000

    /** Adoption (design section 13.2): two a second for a village, eight a second in all. */
    private const val ADOPT_WINDOW = 20L
    private const val ADOPT_VILLAGE = 2
    private const val ADOPT_ALL = 8

    private var nanos = TICK_NANOS
    private var steps = STEPS
    private var columns = COLUMNS

    private var window = Long.MIN_VALUE
    private var adopted = 0
    private val adoptedBy = HashMap<Long, Int>()

    /** How much the villages spent in the tick that is going, and the most they have in one since the world opened or [forget]. */
    private var tickSpent = 0L
    var worstTickNanos = 0L
        private set

    /** Units of work that were put off because they did not fit (since the world opened or [forget]). */
    var putOff = 0L
        private set

    /** A tick begins for the villages: the numbers it may spend are worked out afresh. */
    fun begin(level: ServerLevel) {
        if (tickSpent > worstTickNanos) worstTickNanos = tickSpent
        tickSpent = 0L
        nanos = TICK_NANOS
        steps = STEPS
        columns = COLUMNS
        val now = level.gameTime
        // A clock behind the window's (another world's) starts a new one
        if (now - window >= ADOPT_WINDOW || now < window) {
            window = now
            adopted = 0
            adoptedBy.clear()
        }
    }

    /** The hour steps left for the villages this run (at most 12, after the capitals'). */
    fun steps(): Int = steps

    /** [n] of those steps were used. */
    fun usedSteps(n: Int) {
        steps = maxOf(0, steps - n)
    }

    /** The columns of the world the census may still read this tick. */
    fun columns(): Int = columns

    /** [n] of those columns were read. */
    fun usedColumns(n: Int) {
        columns = maxOf(0, columns - n)
    }

    /**
     * Whether one more villager may be taken in, in the village [key], now: if it may, the ticket is taken (two a second for a
     * village, eight in all, so the cost of adoption stays a sliver of a tick however many villages there are).
     */
    fun adoptOk(key: Long): Boolean {
        if (adopted >= ADOPT_ALL || (adoptedBy[key] ?: 0) >= ADOPT_VILLAGE) {
            putOff++
            return false
        }
        adopted++
        adoptedBy[key] = (adoptedBy[key] ?: 0) + 1
        return true
    }

    /** Whether a unit of work of [micros] microseconds fits in what is left of this tick's share. */
    fun fits(micros: Int): Boolean {
        if (nanos >= micros * 1000L) return true
        putOff++
        return false
    }

    /** The nanoseconds the census may use of this tick: its share, or what is left if less. */
    fun censusNanos(): Long = minOf(CENSUS_NANOS, nanos)

    /** [used] nanoseconds were spent on the villages' work this tick. */
    fun spent(used: Long) {
        nanos = maxOf(0L, nanos - used)
        tickSpent += used
    }

    /** Forgets what was counted and spent (when the server stops). */
    fun forget() {
        nanos = TICK_NANOS
        steps = STEPS
        columns = COLUMNS
        window = Long.MIN_VALUE
        adopted = 0
        adoptedBy.clear()
        tickSpent = 0L
        worstTickNanos = 0L
        putOff = 0L
    }
}
