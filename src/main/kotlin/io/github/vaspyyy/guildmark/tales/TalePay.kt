package io.github.vaspyyy.guildmark.tales

/**
 * Stage 6 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: pay. [run] pays each part with a `did` bit through
 * [Ctx.pay] (7): online players at once, the others into [TaleData.owed], and hands the keepsake with the pay (7.6). Runs once
 * for a tale; the engine guards it.
 */
object TalePay {
    fun run(c: Ctx, t: Tale) {}
}
