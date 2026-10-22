package io.github.vaspyyy.guildmark.cooking

/**
 * `/guildmark cook perf` (living-world/cook/design.md 9.5, 12.2): prints the lines [CookStats] holds, one for each part
 * that registered a readout, and nothing else. Part M4's, the last: it adds the command and what only it can count (vessels
 * ticking, scenes running, particles alive, payloads in the last minute). Until then there is no such command.
 */
object PerfCommand {
    /** Part M4's registration: the `perf` branch under `cook` (stub: nothing). */
    fun register() {}
}
