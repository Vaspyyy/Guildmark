package io.github.vaspyyy.guildmark.tales

/**
 * Stage 8 of `Tales.finish` (living-world/stories/design.md 6.7, 6.4), Part F: props and blocks. [place] finds the site for
 * each memorial the tale asked for through [Ctx.memorial] and has not yet had placed (the safe-site check and its spiral
 * search), queues its blocks, posy and lantern on the street's job queue, and marks it placed. With `townTaleMemorials` off
 * it places nothing and the memorial stays recorded. Runs once for a tale; the engine guards it.
 */
object Memorials {
    fun place(c: Ctx, t: Tale) {}
}
