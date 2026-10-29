package io.github.vaspyyy.guildmark.village.live

/**
 * What the villages' work costs, piece by piece, since the world opened or `village perf reset` (living-world/villages/design.md
 * section 13.6): each loop that works for the villages is run through [timed] (one line where it is registered), and the market's
 * hour steps through [hour], so that the time each took, in all and in its worst tick, is the time the server spent on it. The
 * time is charged to the tick's budget too ([VillageBudget.spent]), once: a loop that charges its own units (the census, the
 * adoption) is charged only what it did not, so the 1.5 ms that [VillageBudget] hands out is the villages' whole work.
 *
 * Kept in memory only, and forgotten when the server stops; the command that reads it is `village perf` ([VillageSafetyCommand]).
 */
object VillagePerf {
    /** The loops that work for the villages, in the order `village perf` lists them, with what design section 13.2 allows each. */
    enum class Piece(val words: String, val allows: String) {
        REGISTRY("the registry: the look at the players and the census", "the census 0.7 ms of a tick (under 1 ms), the look a few us"),
        HOURS("the market hours", "0.1 to 0.25 ms a step, what the capitals leave of 48 steps a run and 12 at most"),
        MARKETS("the markets opened and counted again", "once every 100 ticks for the villages awake"),
        ADOPT("the folk taken in and given back", "two a second for a village, eight in all: 0.05 ms a tick"),
        WARES("their old wares", "nothing while no screen is open"),
        LAMPS("the lamps' pass", "0.05 to 0.3 ms a village every 20 ticks"),
        DRESSING("the bunting", "a look every few seconds at a live village"),
        BUILDS("the building crews", "a site a second"),
        GROWTH("births and newcomers", "a few looks a minute"),
        LINKS("the wagons, the news and the send-offs", "a look every few seconds"),
        BELLMAN("the bellman's word", "a look every few seconds before noon"),
        PEDLARS("the pedlars", "a look a second, 4 alive at most"),
        TROUBLE("shelter, the watch, raids, the dead, the mend, the golems", "a few tenths of a ms when a scene runs"),
        VISITS("where each player is, for the village sounds", "a few us a player"),
    }

    private val PIECES = Piece.entries

    /** Each piece: nanoseconds in all, the times it ran, the ticks it ran in, its worst tick, and what it has spent this tick. */
    private val nanos = LongArray(PIECES.size)
    private val calls = LongArray(PIECES.size)
    private val ticks = LongArray(PIECES.size)
    private val worst = LongArray(PIECES.size)
    private val now = LongArray(PIECES.size)

    /** All of them together: the ticks with any work, the worst tick, the ticks over the 1.5 ms budget, and this tick's. */
    private var busyTicks = 0L
    private var worstTick = 0L
    private var overTicks = 0L
    private var allNow = 0L
    private var allNanos = 0L

    /** The ticks counted since the start (each [roll]), and when that was. */
    private var counted = 0L
    private var sinceNanos = System.nanoTime()
    private var sinceTick = -1L

    /** The hour steps: how many, in all and their worst, the last; the runs that stepped, the steps of the last, and the most in one. */
    var steps = 0L
        private set
    private var stepNanos = 0L
    private var lastStep = 0L
    private var worstStep = 0L
    private var runs = 0L
    private var lastRun = 0
    private var mostRun = 0
    private var runSteps = 0

    /** Runs [body], one of [piece]'s, and keeps what it took (and charges the budget what the body did not charge itself). */
    inline fun <T> timed(piece: Piece, body: () -> T): T {
        val charged = VillageBudget.spentThisTick()
        val begun = System.nanoTime()
        try {
            return body()
        } finally {
            took(piece, System.nanoTime() - begun, VillageBudget.spentThisTick() - charged)
        }
    }

    /** One of the market's hour steps, [body], timed and charged to the budget. */
    inline fun <T> hour(body: () -> T): T {
        val begun = System.nanoTime()
        try {
            return body()
        } finally {
            stepped(System.nanoTime() - begun)
        }
    }

    /** [piece] took [used] nanoseconds, [charged] of which its own units charged the budget already. */
    fun took(piece: Piece, used: Long, charged: Long) {
        val i = piece.ordinal
        nanos[i] += used
        calls[i]++
        now[i] += used
        allNow += used
        allNanos += used
        if (used > charged) VillageBudget.spent(used - charged)
    }

    /** A step of the market's hour took [used] nanoseconds (the budget is charged here; the [Piece.HOURS] wrapper sees it charged). */
    fun stepped(used: Long) {
        steps++
        stepNanos += used
        lastStep = used
        if (used > worstStep) worstStep = used
        runSteps++
        VillageBudget.spent(used)
    }

    /** A tick ends for the villages (called as the next begins, from [VillageBudget.begin]): its numbers go into the tallies. */
    fun roll(gameTime: Long) {
        if (sinceTick < 0L) sinceTick = gameTime
        counted++
        for (i in now.indices) {
            val t = now[i]
            if (t == 0L) continue
            ticks[i]++
            if (t > worst[i]) worst[i] = t
            now[i] = 0L
        }
        if (allNow > 0L) {
            busyTicks++
            if (allNow > worstTick) worstTick = allNow
            if (allNow > VillageBudget.TICK_NANOS) overTicks++
            allNow = 0L
        }
        if (runSteps > 0) {
            runs++
            lastRun = runSteps
            if (runSteps > mostRun) mostRun = runSteps
            runSteps = 0
        }
    }

    /** Forgets every tally and starts afresh (`village perf reset`, and the server stopping). */
    fun reset() {
        nanos.fill(0L)
        calls.fill(0L)
        ticks.fill(0L)
        worst.fill(0L)
        now.fill(0L)
        busyTicks = 0L
        worstTick = 0L
        overTicks = 0L
        allNow = 0L
        allNanos = 0L
        counted = 0L
        sinceNanos = System.nanoTime()
        sinceTick = -1L
        steps = 0L
        stepNanos = 0L
        lastStep = 0L
        worstStep = 0L
        runs = 0L
        lastRun = 0
        mostRun = 0
        runSteps = 0
    }

    // ---- What it reads ----

    /** One piece's tally: nanoseconds in all, times it ran, ticks it ran in, its worst tick. */
    class Line(val piece: Piece, val nanos: Long, val calls: Long, val ticks: Long, val worst: Long)

    fun lines(): List<Line> = PIECES.map { Line(it, nanos[it.ordinal], calls[it.ordinal], ticks[it.ordinal], worst[it.ordinal]) }

    /** The whole: ticks counted, ticks with village work, the worst tick, the ticks over the budget, nanoseconds in all. */
    class Whole(val ticks: Long, val busy: Long, val worst: Long, val over: Long, val nanos: Long, val seconds: Double, val since: Long)

    fun whole(): Whole = Whole(counted, busyTicks, worstTick, overTicks, allNanos, (System.nanoTime() - sinceNanos) / 1e9, sinceTick)

    /** The hour steps: in all, nanoseconds, the last, the worst; the runs, the last run's steps and the most in one. */
    class Steps(val steps: Long, val nanos: Long, val last: Long, val worst: Long, val runs: Long, val lastRun: Int, val mostRun: Int)

    fun hours(): Steps = Steps(steps, stepNanos, lastStep, worstStep, runs, lastRun, mostRun)
}
