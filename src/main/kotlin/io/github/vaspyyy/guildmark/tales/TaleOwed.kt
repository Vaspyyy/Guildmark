package io.github.vaspyyy.guildmark.tales

/**
 * The lines of [TaleData.owed] (living-world/stories/design.md 7.4 and 7.5): pay earned, and Marks given back, for a player who was
 * not on when a tale ended. Only the keeping of the list, apart from the paying ([TalePay] pays what is on it), so it can be tried
 * with no game running. A line has [TaleData.Owed.points] of pay or [TaleData.Owed.marks] to give back, never both.
 */
object TaleOwed {
    /** Whether [l] gives Marks back and not pay. */
    fun refunds(l: TaleData.Owed): Boolean = l.marks > 0

    /**
     * Adds [line] to [data]'s lines for its player, within [TaleLimits.OWED_PER_PLAYER]: a refund for a tale already on a line is added
     * to it, and when the player has that many waiting the two oldest of the same sort are made one (their points or Marks added
     * together, the later line's tale, template and ending kept), so no pay is lost and the list is bounded.
     */
    fun add(data: TaleData, line: TaleData.Owed) {
        val owed = data.owed
        if (refunds(line)) {
            val same = owed.indexOfFirst { it.player == line.player && it.tale == line.tale && refunds(it) }
            if (same >= 0) {
                val old = owed[same]
                owed[same] = TaleData.Owed(old.player, old.tale, old.template, 0, old.ending, old.marks + line.marks)
                data.changed()
                return
            }
        }
        val mine = owed.filter { it.player == line.player }
        if (mine.size >= TaleLimits.OWED_PER_PLAYER) {
            for (sort in listOf(refunds(line), !refunds(line))) {
                val pair = mine.filter { refunds(it) == sort }
                if (pair.size < 2) continue
                val a = pair[0]
                val b = pair[1]
                owed[owed.indexOf(b)] = TaleData.Owed(b.player, b.tale, b.template, a.points + b.points, b.ending, a.marks + b.marks)
                owed.remove(a)
                break
            }
        }
        owed.add(line)
        data.changed()
    }
}
