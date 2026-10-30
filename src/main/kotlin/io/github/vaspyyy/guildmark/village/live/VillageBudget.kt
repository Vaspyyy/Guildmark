package io.github.vaspyyy.guildmark.village.live

import net.minecraft.server.level.ServerLevel

/**
 * What the villages may spend this tick, so that dozens of them cost about what the vanilla ones did (living-world/villages/
 * design.md section 13.5): the hour steps the capitals' run leaves, the columns the census may read, whether one more
 * villager may be taken in, and whether a unit of work of so many microseconds fits. The numbers are worked out afresh at
 * the start of every server tick ([begin]), and the work that spends them says what it spent ([spent], [usedColumns],
 * [usedSteps]); work that doesn't fit waits for the next tick and nothing is dropped. Part V1 gave it enough for the census
 * and part V11 completed it: every loop that works for the villages takes from it in one line, the census (V1's columns and
 * share), the adoption (V2's tickets and its slow units), the market's hour (V4's steps, each one only if a step still fits), the
 * loops over every awake village that once did them all in one tick ([within]: the bellman's morning, the trouble's look, the
 * growth's own close) and the rest through [VillagePerf.timed], which charges each loop what it took, so that what is left is what
 * the tick has left.
 *
 * What it put off and its worst tick are shown by `village perf` with the rest of [VillagePerf]'s figures, and are forgotten with
 * them by `village perf reset` ([clearReadout]) and when the server stops ([forget]).
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
    const val ADOPT_WINDOW = 20L
    const val ADOPT_VILLAGE = 2
    const val ADOPT_ALL = 8

    /**
     * What one of the market's hour steps is reckoned to cost before it runs (design section 13.2: 0.1 to 0.25 ms, the most of
     * that): a step is taken only while this much of the tick is left, so a run of twelve stays inside the 1.5 ms and the steps
     * a slow tick could not take are the next run's.
     */
    const val STEP_MICROS = 250

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

    /** Adoption tickets taken (each the start of one swap; since the world opened, [clearReadout] or [forget]). */
    var tickets = 0L
        private set

    /** Hour steps not taken because the tick had no room left for one (they wait for the next run). */
    var stepsPutOff = 0L
        private set

    /** A tick begins for the villages: the numbers it may spend are worked out afresh. */
    fun begin(level: ServerLevel) {
        VillagePerf.roll(level.gameTime)
        if (tickSpent > worstTickNanos) worstTickNanos = tickSpent
        tickSpent = 0L
        nanos = TICK_NANOS
        steps = STEPS
        columns = COLUMNS
        val now = level.gameTime
        // The first tick starts the first window (`now - window` from the unset one would overflow, and no window would ever start), and
        // a clock behind the window's (another world's) starts a new one
        if (window == Long.MIN_VALUE || now - window >= ADOPT_WINDOW || now < window) {
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

    /**
     * Whether one more hour step may be taken now: a step left of the twelve and room in the tick for one ([STEP_MICROS]). The
     * first step of a tick always has the room (the market's run comes before any other village work in the tick), so a run
     * always gets somewhere.
     */
    fun stepFits(): Boolean {
        if (steps <= 0) return false
        if (nanos >= STEP_MICROS * 1000L) return true
        stepsPutOff++
        putOff++
        return false
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
        tickets++
        adoptedBy[key] = (adoptedBy[key] ?: 0) + 1
        return true
    }

    /** Whether a unit of work of [micros] microseconds fits in what is left of this tick's share. */
    fun fits(micros: Int): Boolean {
        if (nanos >= micros * 1000L) return true
        putOff++
        return false
    }

    /**
     * The villages of the loop [loop] that takes its work from the budget, one village a unit of about [micros] microseconds: of
     * [recs] (in the loop's own order, the nearest a player first where it has one), the first, and then the rest on from where the
     * loop's last run stopped and round, while a unit still fits in what is left of this tick less what the loop has spent since it
     * began. Two are always given, the first and the next in turn, so the nearest is looked at every run and the rest go round
     * however full the ticks are; those not given are the next run's (each run cut short is counted in [putOff]). A loop that runs
     * every few seconds over every awake village writes `for (rec in VillageBudget.within("trouble", Villages.awake(level), 100))`
     * and costs no more than the tick has left; with room for all of them it is the loop it was.
     */
    fun within(loop: String, recs: List<VillageRec>, micros: Int): Sequence<VillageRec> = sequence {
        val n = recs.size
        if (n == 0) return@sequence
        val begun = System.nanoTime()
        val after = stopped.remove(loop)?.let { key -> recs.indexOfFirst { it.key == key } + 1 } ?: 0
        val start = if (after in 2 until n) after else 1
        for (j in 0 until n) {
            val i = if (j == 0) 0 else 1 + (start - 1 + j - 1) % (n - 1)
            if (j >= 2 && nanos - (System.nanoTime() - begun) < micros * 1000L) {
                stopped[loop] = recs[1 + (start - 1 + j - 2) % (n - 1)].key
                putOff++
                return@sequence
            }
            yield(recs[i])
        }
    }

    /** Where each loop of [within] last stopped short, by its name: the key of the last village it was given. */
    private val stopped = HashMap<String, Long>()

    /** The nanoseconds the census may use of this tick: its share, or what is left if less. */
    fun censusNanos(): Long = minOf(CENSUS_NANOS, nanos)

    /** [used] nanoseconds were spent on the villages' work this tick. */
    fun spent(used: Long) {
        nanos = maxOf(0L, nanos - used)
        tickSpent += used
    }

    /** What has been charged this tick so far (for [VillagePerf.timed], which charges a loop only what it did not charge itself). */
    fun spentThisTick(): Long = tickSpent

    /** The nanoseconds left this tick. */
    fun left(): Long = nanos

    /** Forgets what is shown (`village perf reset`): the worst tick, the units put off and the tickets; the tick's own numbers stay. */
    fun clearReadout() {
        worstTickNanos = 0L
        putOff = 0L
        tickets = 0L
        stepsPutOff = 0L
    }

    /** Forgets what was counted and spent (when the server stops). */
    fun forget() {
        nanos = TICK_NANOS
        steps = STEPS
        columns = COLUMNS
        window = Long.MIN_VALUE
        adopted = 0
        adoptedBy.clear()
        stopped.clear()
        tickSpent = 0L
        clearReadout()
        VillagePerf.reset()
    }
}
