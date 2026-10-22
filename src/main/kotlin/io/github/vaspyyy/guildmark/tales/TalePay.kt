package io.github.vaspyyy.guildmark.tales

/**
 * Stage 6 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: pay. [run] pays each part with a `did` bit through
 * [Ctx.pay] (7): online players at once, the others into [TaleData.owed], and hands the keepsake with the pay (7.6). Runs once
 * for a tale; the engine guards it.
 */
object TalePay {
    fun run(c: Ctx, t: Tale) {}

    /**
     * Part E (7.5 rule 3): gives every part's [Part.escrow] of [t] back in full (to the player if they are on, else as an
     * `owed` line) and sets it to 0. The engine calls it where a step lapses unmet, at an ending W, and where it clears a broken tale.
     */
    fun refund(c: Ctx, t: Tale) {}
}
