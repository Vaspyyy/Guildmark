package io.github.vaspyyy.guildmark.tales

/**
 * Stage 4 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: memory. [run] gives the favours (6.6: `TALE_HELPED`
 * to each essential cast member alive at the end and their household, for each player whose `did` is set, on A and B;
 * `TALE_HEARD` to the residents who hear the rumour), through [Ctx.favour]. Runs once for a tale; the engine guards it.
 */
object TaleMemory {
    fun run(c: Ctx, t: Tale) {}
}
