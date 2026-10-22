package io.github.vaspyyy.guildmark.cooking

/**
 * What Hearthcraft costs, counted, for `/guildmark cook perf` (living-world/cook/design.md 9.5, 12.3): each part's
 * `register()` adds one readout, a label and a function that makes its line (a count of vessels ticking, scenes
 * running, a last-hour time), and nothing else reads them. A readout is read only when someone asks, so a part keeps
 * its counts as plain fields and pays nothing for it the rest of the time. A label given twice replaces the first.
 */
object CookStats {
    private val readouts = LinkedHashMap<String, () -> String>()

    /** Adds [line] under [label]: it makes the part's line for `cook perf`, and is asked only then. */
    @Synchronized
    fun readout(label: String, line: () -> String) {
        readouts[label] = line
    }

    /** Every readout's line now, "label: line", in the order they were added; one that fails says so and the rest still show. */
    @Synchronized
    fun lines(): List<String> = readouts.map { (label, line) ->
        val text = try {
            line()
        } catch (e: RuntimeException) {
            "unavailable (" + e.javaClass.simpleName + ")"
        }
        "$label: $text"
    }
}
