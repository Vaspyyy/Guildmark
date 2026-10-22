package io.github.vaspyyy.guildmark.tales

/**
 * Keeping the tales' record small and in step with the clock (living-world/stories/design.md 11.2 for the caps, 2.3 for a clock
 * set back, 11.5 for the days a switch slept): the trimming of every list, the going back of every day, and the thaw. All of it
 * is plain work on [TaleData] and touches no world, so it is the same whoever calls it and a test can run it on a made-up record.
 * Days are market days; [TaleData.NEVER] is no day and is never moved.
 */
object TaleKeep {
    private const val HALF = TaleData.NEVER / 2

    /** Whether [day] is a day at all (not [TaleData.NEVER]). */
    fun real(day: Long): Boolean = day > HALF

    /** [day] taken back [days], unless it is no day. */
    fun moved(day: Long, days: Long): Long = if (real(day)) day - days else day

    // ---- The caps (11.2) ----

    /**
     * Brings the record inside its caps at the close of market [day]: an ended tale whose finish is complete is dropped
     * [TaleLimits.KEPT_DAYS] days after it ended and, past [TaleLimits.KEPT_PER_CAPITAL] a capital, the oldest ended goes first (an
     * open tale, and an ended one still to be finished, is never dropped); the flips log, the sealed stalls and each town's lists
     * likewise. Marks the record changed only if something went. Gives whether it did.
     */
    fun trim(data: TaleData, day: Long): Boolean {
        var changed = false
        val old = data.all().filter { !it.open && it.finished && real(it.ended) && day - it.ended > TaleLimits.KEPT_DAYS }.map { it.id }
        for (id in old) data.remove(id)
        changed = old.isNotEmpty() || changed
        for (list in data.all().groupBy { it.capital }.values) {
            var over = list.size - TaleLimits.KEPT_PER_CAPITAL
            if (over <= 0) continue
            for (t in list.filter { !it.open && it.finished }.sortedWith(compareBy({ it.ended }, { it.id }))) {
                if (over <= 0) break
                data.remove(t.id)
                over--
                changed = true
            }
        }
        while (data.flips.size > TaleLimits.FLIP_LOG) {
            data.flips.removeAt(0)
            changed = true
        }
        val lapsed = data.sealed.filterValues { it < day }.keys.toList()
        for (k in lapsed) data.sealed.remove(k)
        changed = lapsed.isNotEmpty() || changed
        while (data.sealed.size > TaleLimits.SEALED) {
            data.sealed.remove(data.sealed.minByOrNull { it.value }!!.key)
            changed = true
        }
        for (town in data.towns().values) changed = trimTown(town, day) || changed
        if (changed) data.changed()
        return changed
    }

    /** One town's lists inside their caps at market [day]: strikes a few days old, volunteers and protections past their day, the chronicle's 12 lines, the 12 templates remembered, the traditions kept ten festivals. */
    fun trimTown(town: TaleData.Town, day: Long): Boolean {
        var changed = false
        changed = town.waylaid.removeAll { day - it.day > TaleLimits.WAYLAID_DAYS } || changed
        while (town.waylaid.size > TaleLimits.WAYLAID) {
            town.waylaid.removeAt(0)
            changed = true
        }
        changed = town.volunteers.removeAll { it.until < day } || changed
        while (town.volunteers.size > TaleLimits.VOLUNTEERS) {
            town.volunteers.removeAt(0)
            changed = true
        }
        changed = town.protect.removeAll { it.until < day } || changed
        while (town.protect.size > TaleLimits.PROTECT) {
            town.protect.removeAt(0)
            changed = true
        }
        while (town.chronicle.size > TaleLimits.CHRONICLE) {
            town.chronicle.removeAt(0)
            changed = true
        }
        while (town.last.size > TaleLimits.LAST) {
            town.last.remove(town.last.minByOrNull { it.value }!!.key)
            changed = true
        }
        changed = town.traditions.removeAll { it.kept > TaleLimits.TRADITION_FESTIVALS } || changed
        while (town.traditions.size > TaleLimits.TRADITIONS) {
            town.traditions.removeAt(0)
            changed = true
        }
        return changed
    }

    // ---- The clock set back (2.3) ----

    /**
     * The clock was set back [days] market days: every day the record keeps goes back with it, so a tale never ends the sooner
     * for it. That is each tale's days (the step's, its settling day, the seed day, the ending's, a part's last asking), each town's
     * quiet, seen, closed and last-ended days, its chronicle, strikes, volunteers and protections, the sealed stalls, the flips
     * and the memorials. A day that is none stays none.
     */
    fun back(data: TaleData, days: Long) {
        if (days <= 0L) return
        for (t in data.all()) {
            t.stepDay -= days
            t.by -= days
            t.d0 -= days
            t.ended = moved(t.ended, days)
            for (p in t.parts) p.lastAsk = moved(p.lastAsk, days)
        }
        for (town in data.towns().values) {
            town.quiet = moved(town.quiet, days)
            town.seen = moved(town.seen, days)
            town.closed = moved(town.closed, days)
            town.last.replaceAll { _, v -> moved(v, days) }
            town.chronicle.replaceAll { TaleData.Line(it.template, it.ending, it.day - days, it.names, it.lead) }
            town.waylaid.replaceAll { TaleData.Waylaid(it.caravan, it.band, it.road, it.day - days) }
            town.volunteers.replaceAll { TaleData.Volunteer(it.resident, it.until - days) }
            town.protect.replaceAll { TaleData.Protect(it.home, it.until - days) }
        }
        data.sealed.replaceAll { _, v -> v - days }
        data.flips.replaceAll { TaleData.Flip(it.resident, it.from, it.to, it.day - days, it.tale) }
        data.memorials.replaceAll {
            TaleData.Memorial(it.id, it.kind, it.pos, it.dimension, it.names, it.template, it.lead, it.day - days, it.capital, it.placed)
        }
        data.changed()
    }

    // ---- The days a switch slept (11.5) ----

    /**
     * The first close of [capital] after tales came back on, [shift] days later than the one before it: the open tale's days
     * (its step's, its settling day and its seed day, unless [thaws] says it keeps a date of the world's, as the lantern does)
     * and the town's quiet and last-ended days move on by [shift], so a tale is not ended by the days it slept. What is a date
     * in the world is left: a sealed stall, a protection, the volunteers, and the keep of an ended tale ran on.
     */
    fun thaw(data: TaleData, capital: Long, shift: Long, thaws: (Tale) -> Boolean) {
        if (shift <= 0L) return
        for (t in data.all()) {
            if (t.capital != capital || !t.open || !thaws(t)) continue
            t.stepDay += shift
            t.by += shift
            t.d0 += shift
        }
        val town = data.town(capital)
        if (real(town.quiet)) town.quiet += shift
        town.last.replaceAll { _, v -> if (real(v)) v + shift else v }
        data.changed()
    }
}
