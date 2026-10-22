package io.github.vaspyyy.guildmark.tales

/**
 * The households a tale sends away, brings home and protects (living-world/stories/design.md 6.5 and 10.2(c)). The reason a
 * household sent away by a tale is recorded under is how the comings' log, `/guildmark comings` and the engine tell it from a
 * household that left in hard times.
 */
internal object TaleHouseholds {
    /** The reason a household sent away by [t] is recorded with: `tale <template> <id>`. */
    fun reason(t: Tale): String = "tale ${t.template} ${t.id}"
}
