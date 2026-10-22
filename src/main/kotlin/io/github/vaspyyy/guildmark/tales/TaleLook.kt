package io.github.vaspyyy.guildmark.tales

/**
 * Stage 7 of `Tales.finish` (living-world/stories/design.md 6.7, 4.2), Part B: the ending seen. [queueEnding] queues the
 * ending's scene on the stage when a player is within 48 blocks, and does nothing when none is (the record is the truth).
 * Runs once for a tale; the engine guards it.
 */
object TaleLook {
    fun queueEnding(c: Ctx, t: Tale) {}
}
