package io.github.vaspyyy.guildmark.tales

/**
 * Every cap and every number of how tales are paced and kept, in one place (living-world/stories/design.md 9.1, 11.1 and
 * 11.2), so a tester finds them together and no part makes up a number of its own. Days are market days. Nothing reads
 * these yet: the parts that do (the engine, the telling, the memorials) read them from here, and a part that needs a new
 * number adds it here.
 */
object TaleLimits {
    // ---- How many, and how often (design 9.1) ----

    /** Open tales in one capital at once. */
    const val OPEN_PER_CAPITAL = 1

    /** Days of quiet after a tale ends, before another may start in that capital. */
    const val QUIET_DAYS = 8

    /** The daily draw before the pace: a capital draws `min(1, TALE_CHANCE x townTalePace)`. */
    const val TALE_CHANCE = 0.5

    /** A player must have been in a capital within this many days for a tale to start there. */
    const val SEEN_DAYS = 3

    /** A capital's first tale waits until this market day on record, since a tale reads what has happened. */
    const val FIRST_DAY = 6

    /** Tales that may start in the whole world in one market day. */
    const val STARTS_A_DAY = 1

    /** A template is not seeded in the same capital again for this many days. */
    const val AGAIN_DAYS = 40

    /** Open tales on one player's tracker. */
    const val ON_TRACKER = 2

    /** The days one step lasts at most, and the days a whole tale lasts at most. */
    const val STEP_DAYS = 5
    const val TALE_DAYS = 12

    /** The days a template's longest route may take (`tale check`), inside [TALE_DAYS]. */
    const val ROUTE_DAYS = 9

    /** Times a template may throw in a tale before the tale is ended at W (the strikes rule, 15.2). */
    const val STRIKES = 3

    /** "Not now" costs a player nothing: the days before the cast ask again, and how many more times they do. */
    const val ASK_AGAIN_DAYS = 2
    const val ASKS_MORE = 2

    // ---- What is kept, and how much (design 11.1 and 11.2) ----

    /** The version of the `tales` file. */
    const val VERSION = 1

    /** Tales kept for a capital, open and ended (the oldest ended goes first; an open one is never dropped). */
    const val KEPT_PER_CAPITAL = 24

    /** Days an ended tale is kept after it ended (its chronicle line stays for good). */
    const val KEPT_DAYS = 40

    /** The most a tale's cast, small numbers, short strings, reasons and players' parts hold, and a string's length. */
    const val CAST = 8
    const val NUMBERS = 8
    const val STRINGS = 4
    const val STRING_LETTERS = 40
    const val WHY = 8
    const val PARTS = 8

    /** The lines of a capital's chronicle, and of the template-to-day list that keeps the 40-day rule. */
    const val CHRONICLE = 12
    const val LAST = 12

    /** The traditions a capital keeps, and the festivals each is kept for before it is let go. */
    const val TRADITIONS = 6
    const val TRADITION_FESTIVALS = 10

    /** The caravans' strikes kept for a capital, and the days old one may be before it is dropped. */
    const val WAYLAID = 24
    const val WAYLAID_DAYS = 6

    /** The residents the works take first, and the households a tale protects from leaving, for a capital. */
    const val VOLUNTEERS = 6
    const val PROTECT = 12

    /** The stones and cairns of a capital (a seventh is not set), and the stalls sealed at once. */
    const val MEMORIALS = 6
    const val SEALED = 12

    /** Trait flips: at most this many a tale, one a resident in [FLIP_DAYS] days, and this many in the log. */
    const val FLIPS_A_TALE = 2
    const val FLIP_DAYS = 40
    const val FLIP_LOG = 64

    /** Lines of pay or refund waiting for one player who was away. */
    const val OWED_PER_PLAYER = 6

    // ---- The telling (design 11.2) ----

    /** A stage: the parts it plays, its ticks at most, and the seconds between one stage and the next in a capital. */
    const val STAGE_PARTS = 5
    const val STAGE_TICKS = 2400
    const val STAGE_GAP_SECONDS = 40

    /** Made figures at once for one tale, and how near a player must be to their mark for any to be made (blocks). */
    const val FIGURES = 6
    const val FIGURE_RANGE = 64

    /** Residents one tale holds at once. */
    const val HELD = 3

    /** Blocks a tale sets in a tick (only memorials, a posy and a lantern). */
    const val BLOCKS_A_TICK = 4

    // ---- What a tale pays (design 7.1) ----

    /** A size's base in reward points, the ending scaling it (A 1.0, B 0.9, C 0.6, W 0.25), rounded: one row a size, A B C W. */
    private val POINTS = arrayOf(
        intArrayOf(18, 16, 11, 5),
        intArrayOf(34, 31, 20, 9),
        intArrayOf(56, 50, 34, 14),
    )

    /** The reward points of a tale of [size] (1 to 3) ending at [ending] (a letter of `Ending.LETTERS`); 0 for anything else. */
    fun points(size: Int, ending: String): Int {
        val row = POINTS.getOrNull(size - 1) ?: return 0
        val i = Ending.LETTERS.indexOf(ending)
        return if (i < 0) 0 else row[i]
    }
}
