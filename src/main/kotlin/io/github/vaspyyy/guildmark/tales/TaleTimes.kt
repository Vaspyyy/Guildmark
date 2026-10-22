package io.github.vaspyyy.guildmark.tales

/**
 * Stage 3 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: the times. [run] leaves the town's fortunes the
 * `tale` reason an ending earns (6.1: A +1, +2 for a size-3 tale, B +1, C none, W -1 for the five tales whose world's way
 * hurts others), through [Ctx.note]. Runs once for a tale (its bit in [Tale.done]); the engine guards it.
 */
object TaleTimes {
    fun run(c: Ctx, t: Tale) {}
}
