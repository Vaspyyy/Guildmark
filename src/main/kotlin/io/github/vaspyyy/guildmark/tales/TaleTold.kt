package io.github.vaspyyy.guildmark.tales

/**
 * Stage 5 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: what is told. [run] starts the ending's rumour
 * (6.2, through [Ctx.rumour]), queues the crier's cry and the board's slip for the next noon (`Tales.cries`), and adds the
 * guild's news for a size-3 tale that ended A or B. The chronicle line is written by stage 1 (the engine), not here. Runs
 * once for a tale; the engine guards it.
 */
object TaleTold {
    fun run(c: Ctx, t: Tale) {}
}
