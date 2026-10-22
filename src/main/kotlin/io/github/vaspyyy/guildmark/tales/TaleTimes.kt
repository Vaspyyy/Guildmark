package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import java.util.UUID

/**
 * Stage 3 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: the times. [run] leaves the town's fortunes the
 * `tale` reason an ending earns (6.1), through `Fortunes.note`, which counts it at the next closes like any noted reason and
 * caps all the tales together at two either way (`FortuneReasons`), so a tale is a nudge and never a lever:
 *
 * | Ending | Points | Days |
 * |---|---|---|
 * | A | +1, and +2 for a size-3 tale | 5 |
 * | B | +1 | 5 |
 * | C | nothing | |
 * | W | -1, only for a tale whose world's way hurts others (`Template.hurts`) | 3 |
 *
 * The reason's words are the sentence of the ending (`TaleWords`): `<template>.<word>` and the names it is said with. Runs
 * once for a tale (its bit in [Tale.done]); the engine guards it. With the fortunes off `Fortunes.note` returns at once.
 */
object TaleTimes {
    /** The points an ending of a tale of [size] leaves the times, [hurts] being whether its template is one whose world's way hurts others. */
    fun points(size: Int, hurts: Boolean, ending: String): Int = when (ending) {
        Ending.A -> if (size >= 3) 2 else 1
        Ending.B -> 1
        Ending.W -> if (hurts) -1 else 0
        else -> 0
    }

    /** How many market days an ending's note lasts: five, but three for the world's way. */
    fun days(ending: String): Int = if (ending == Ending.W) 3 else 5

    fun run(c: Ctx, t: Tale) {
        val points = points(TaleWords.size(t), TaleWords.hurts(t), t.ending)
        if (points == 0) return
        note(c, t, points, days(t.ending), t.lead?.player)
    }

    /** What `Ctx.note` does: the reason `tale` of [points] for [days] days at [t]'s capital, about [about], said with the ending's words. */
    fun note(c: Ctx, t: Tale, points: Int, days: Int, about: UUID?) {
        if (!Tales.READY || points == 0 || days <= 0) return
        Fortunes.note(c.level, t.capital, FortuneReasons.TALE, points, days, TaleWords.args(c.level, t), about)
    }
}
