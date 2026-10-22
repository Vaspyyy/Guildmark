package io.github.vaspyyy.guildmark.tales

/**
 * Who a tale pays, and what the ending is worth to each (living-world/stories/design.md 7.2, as 8.0 corrects it), apart from the
 * paying: only arithmetic over the record's parts, so it can be tried with no game running ([TalePay] pays what it answers).
 *
 * A part is paid if it did a real thing in the tale ([holds]: a `did` bit, or the engine's DONE); standing about is never paid.
 * The player whose deed settled the ending ([Part.DECIDED]) is paid the ending's full points, any other holder half (the whole number
 * below), up to [PAYEES] players; the points are [TaleLimits.points] of the template's size, so a size-2 tale that ended A is
 * worth 34 to its decider and 17 to a second player. When no one is marked as having decided, a single holder is paid in full and
 * several are all paid half, so two players together can never be paid more than the tale is worth.
 */
object TaleShares {
    /** The most players one tale pays (7.2). */
    const val PAYEES = 4

    /** A part and the points the ending is worth to it, before what the tale's own contract notes have paid. */
    class Share(val part: Part, val points: Int) {
        /** What is still to pay: the share less [Part.paid], never below nothing (a note that paid more is not taken back). */
        val owing: Int get() = (points - part.paid).coerceAtLeast(0)
    }

    /** Whether [p] did a real thing in the tale (a `did` bit, or the engine's DONE) and so is paid. Presence alone is never paid. */
    fun holds(p: Part): Boolean = p.did != 0 || p.status == Part.Status.DONE

    /**
     * Who is paid what for a tale of [size] that ended [ending], from its [parts]: the deciding player first, then the others who
     * did a real step in the order the tale met them, at most [PAYEES]. Empty for an ending the table has no points for, or when
     * nobody did anything.
     */
    fun shares(size: Int, ending: String, parts: List<Part>): List<Share> {
        val full = TaleLimits.points(size, ending)
        if (full <= 0) return listOf()
        val holders = parts.filter { holds(it) }
        if (holders.isEmpty()) return listOf()
        val decider = holders.firstOrNull { it.did(Part.DECIDED) }
        val ordered = if (decider != null) listOf(decider) + holders.filter { it !== decider } else holders
        return ordered.take(PAYEES).mapIndexed { i, part ->
            val whole = if (decider != null) i == 0 else holders.size == 1
            Share(part, if (whole) full else full / 2)
        }
    }
}
