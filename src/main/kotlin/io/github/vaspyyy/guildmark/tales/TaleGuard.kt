package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark

/**
 * The strikes rule (living-world/stories/design.md 15.2): every call into a template goes through [run], which catches what it
 * throws, logs it and counts it against the tale. A tale that has thrown [TaleLimits.STRIKES] times since its last close began is
 * ended at W through `finish` and, if even that fails, cleared with its escrow given back ([TaleFinish.broken]), so a template
 * with a mistake in it can hold a town for at most a day. The count is kept in memory only: a restart gives every tale a fresh one.
 */
internal object TaleGuard {
    private val strikes = HashMap<Int, Int>()

    /** [t]'s count begins again: a close of its capital begins, or the tale is over. */
    fun reset(t: Tale) {
        strikes.remove(t.id)
    }

    /** How many times [t] has thrown since its count began. */
    fun strikes(t: Tale): Int = strikes[t.id] ?: 0

    /** Everything forgotten, as the server stops. */
    fun forget() {
        strikes.clear()
    }

    /**
     * Runs [body], which is a call into [t]'s template (or into a helper it called), and answers what it answers; null (and a
     * strike on the tale, and a line in the log naming [what]) if it threw.
     */
    fun <R> run(c: Ctx, t: Tale, what: String, body: () -> R): R? {
        try {
            return body()
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The {} tale #{} threw in {}", t.template, t.id, what, e)
            strike(c, t)
            return null
        }
    }

    private fun strike(c: Ctx, t: Tale) {
        val n = strikes(t) + 1
        strikes[t.id] = n
        if (n >= TaleLimits.STRIKES && t.open) {
            strikes.remove(t.id)
            TaleFinish.broken(c, t)
        }
    }
}
