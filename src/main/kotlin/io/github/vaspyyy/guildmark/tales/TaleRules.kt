package io.github.vaspyyy.guildmark.tales

import java.util.Random

/**
 * The rules of how a capital's days run (living-world/stories/design.md 2.2, 9.1 and 1.3), as plain functions of numbers, so
 * the engine, `tale` and `tale seeds` all say the same thing and the arithmetic of a boundary (the last quiet day, the third
 * day since a player was seen) is written once. Nothing here touches the world. Days are market days.
 */
object TaleRules {
    private const val DRAW = 0x5851F42D4C957F2DL
    private const val ORDER = 0x14057B7EF767814FL

    /** A seed for [capital], market [day] and [salt]: well spread, and the same for the same three, so a day run twice picks the same (1.2, 9.4). */
    fun mix(capital: Long, day: Long, salt: Long): Long {
        var z = capital * -0x61C8864680B583EBL + day * 0x632BE59BD9B4E019L + salt
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        return z xor (z ushr 31)
    }

    /** The daily draw at [pace] (townTalePace): `min(1, TALE_CHANCE x pace)`, and never below 0. */
    fun chance(pace: Double): Double = (TaleLimits.TALE_CHANCE * pace).coerceIn(0.0, 1.0)

    /** Whether [capital]'s draw on market [day] hits at [pace]. The same answer every time for the same three. */
    fun hit(capital: Long, day: Long, pace: Double): Boolean {
        val p = chance(pace)
        if (p <= 0.0) return false
        return Random(mix(capital, day, DRAW)).nextDouble() < p
    }

    /** [ids] in the day's order: a seeded shuffle of [capital] and market [day], so no template always goes first and a repeat of the day gives the same order (2.1). */
    fun order(capital: Long, day: Long, ids: List<String>): List<String> {
        val out = ids.toMutableList()
        val random = Random(mix(capital, day, ORDER))
        for (i in out.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = out[i]
            out[i] = out[j]
            out[j] = t
        }
        return out
    }

    /**
     * The first rule of 9.1 that stops a new tale in a capital at the close of market [day], in words, or null if none does
     * (the draw is still to come). [quiet] is the last quiet day, [seen] the last day a player was there, [closes] the days of
     * closing prices the market keeps (a town's days on record, 8 at most), [started] the tales already begun in the world
     * today, and [replayed] whether this close is one of a catch-up (2.2 step 5).
     */
    fun stopped(day: Long, quiet: Long, seen: Long, closes: Int, started: Int, replayed: Boolean): String? = when {
        replayed -> "this close is a replayed one: a replayed day settles a tale but never starts one"
        closes < TaleLimits.FIRST_DAY -> "the town has $closes market days on record, and a first tale waits for ${TaleLimits.FIRST_DAY}"
        day <= quiet -> "quiet through day $quiet"
        seen <= TaleData.NEVER / 2 -> "no player has been here yet"
        day - seen > TaleLimits.SEEN_DAYS -> "no player has been here since day $seen, and one must have been within ${TaleLimits.SEEN_DAYS} days"
        started >= TaleLimits.STARTS_A_DAY -> "a tale has started in the world today already"
        else -> null
    }

    /** Whether a template that last ended in a capital on [last] may not be seeded there on [day] (the forty-day rule, 9.1). */
    fun again(day: Long, last: Long): Boolean = last > TaleData.NEVER / 2 && day - last < TaleLimits.AGAIN_DAYS

    /**
     * What is wrong with a template's step days, or null (design 1.3 and `tale check`): [bys] are the steps' `by`, the market
     * days after the seed day on which the world settles each. A step lasts at most [TaleLimits.STEP_DAYS] days from the day it
     * can begin (the previous step's `by` at the latest, the seed day for the first), and no step falls after
     * [TaleLimits.ROUTE_DAYS], so a tale is over inside its twelve.
     */
    fun timing(bys: List<Int>): String? {
        if (bys.isEmpty()) return "no steps"
        var from = 0
        for ((i, by) in bys.withIndex()) {
            if (by < 0) return "step $i falls before the seed day (by $by)"
            if (by - from > TaleLimits.STEP_DAYS) return "step $i lasts ${by - from} days (at most ${TaleLimits.STEP_DAYS})"
            from = maxOf(from, by)
        }
        val last = bys.max()
        return if (last > TaleLimits.ROUTE_DAYS) "the longest route is $last days (at most ${TaleLimits.ROUTE_DAYS})" else null
    }

    /**
     * When the world settles a step that begins on [stepDay] in a tale seeded on [d0], with the template's own [by] for it:
     * `d0 + by`, but no later than [TaleLimits.STEP_DAYS] after the step begins, nor than [TaleLimits.TALE_DAYS] after the seed.
     */
    fun settleDay(d0: Long, stepDay: Long, by: Int): Long =
        minOf(d0 + by, stepDay + TaleLimits.STEP_DAYS, d0 + TaleLimits.TALE_DAYS)

    /** Whether the close of market [day] settles a step whose world's-way day is [by]: the first close whose new day is later (1.4). */
    fun due(day: Long, by: Long): Boolean = day > by

    /** Whether a tale seeded on [d0] is past its hard stop at the close of [day] (1.3, 15.2). */
    fun overdue(day: Long, d0: Long): Boolean = day - d0 > TaleLimits.TALE_DAYS
}
