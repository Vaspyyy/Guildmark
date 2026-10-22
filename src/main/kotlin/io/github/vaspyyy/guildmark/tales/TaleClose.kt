package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Markets
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The close of a capital's market day, the one time the engine looks for or starts anything (living-world/stories/design.md
 * 2.2 and 2.3): the guard against closing a day twice, the days a switch slept (11.5), the open tale settled, or a new one
 * drawn. It is called once an hour of the market's own clock from `Markets.step` and acts only at the close (two in the morning);
 * between closes a tale does nothing but what a listener tells it.
 */
internal object TaleClose {
    /** Game ticks a close that waits for its capital's places is kept (ten minutes: a market day is twenty). */
    private const val WAIT_TICKS = 12000L

    /** A close that could not run yet because its capital's places are not known: the day, whether it was a replayed one, and until when it is kept. */
    private class Waiting(val day: Long, val replayed: Boolean, val until: Long)

    private val waiting = HashMap<Long, Waiting>()

    /** The day the tales started in the world are counted for, and how many started on it (9.1: one a day; memory only). */
    private var startedOn = Long.MIN_VALUE
    private var started = 0

    /** What each capital's last draw came to, in words, for `tale` and `tale day`. */
    private val drew = HashMap<Long, String>()

    /** Everything forgotten, as the server stops or tales go off. */
    fun forget() {
        waiting.clear()
        drew.clear()
        startedOn = Long.MIN_VALUE
        started = 0
    }

    /** How the last draw in [capital] came out, or null if none has been made since the server started. */
    fun lastDraw(capital: Long): String? = drew[capital]

    /** The tales started in the world on market [day]. */
    fun startsOn(day: Long): Int = if (startedOn == day) started else 0

    private fun noteStart(day: Long) {
        if (startedOn != day) {
            startedOn = day
            started = 0
        }
        started++
    }

    /** The days of closing prices [c]'s market keeps, which is the days the town has on record (eight at most). */
    fun daysOnRecord(c: Ctx): Int = c.entry.market.closes[0].size

    // ---- From Markets.step ----

    /** `Tales.day`: [entry]'s capital's close, once a day (the guard is the fortunes' own, `Fortunes.day`). */
    fun day(level: ServerLevel, entry: Markets.Entry) {
        // The switch is read at the top of every close; off, nothing is touched, `closed` least of all (11.5)
        Tales.refresh()
        if (!Tales.live) return
        val m = entry.market
        val c = Conditions(m.hour)
        if (c.clock != Economy.NEW_DAY) return
        TaleEngine.use(level)
        val data = TaleData.get(level)
        val town = data.town(m.capital)
        // On ahead of the clock (`market run`), each day the market closes is the tales' next, however often it is run on
        // from the same hour: those days are lived already, and the clock's closes wait for them to come round
        val day = if (m.hour > Markets.hourOf(level)) maxOf(c.day, town.closed + 1) else c.day
        if (day <= town.closed) return
        // A close of a catch-up (a night slept through, a long absence) is a day or more behind the clock: it settles a tale, never starts one
        val replayed = Markets.hourOf(level) - m.hour >= 24
        close(level, m.capital, day, replayed)
    }

    private fun close(level: ServerLevel, key: Long, day: Long, replayed: Boolean) {
        val c = TaleEngine.ctx(level, key, day)
        if (c == null) {
            // The capital's places are not known yet: the close is kept (one for a capital; a later one takes its place) and tried each second
            waiting[key] = Waiting(day, replayed, level.gameTime + WAIT_TICKS)
            return
        }
        waiting.remove(key)
        TaleEngine.guarded("the close of day $day in the capital at ${BlockPos.of(key).toShortString()}") { run(c, replayed) }
    }

    /** Each second: the closes that waited for their capital's places. */
    fun retry(level: ServerLevel) {
        if (waiting.isEmpty()) return
        val data = TaleData.get(level)
        for ((key, w) in waiting.entries.toList()) {
            if (level.gameTime > w.until) {
                waiting.remove(key)
                Guildmark.LOGGER.warn("Tales gave up waiting for the places of the capital at {} to close day {}", BlockPos.of(key).toShortString(), w.day)
                continue
            }
            if (w.day <= (data.find(key)?.closed ?: TaleData.NEVER)) {
                waiting.remove(key)
                continue
            }
            val c = TaleEngine.ctx(level, key, w.day) ?: continue
            waiting.remove(key)
            TaleEngine.guarded("the close of day ${w.day} in the capital at ${BlockPos.of(key).toShortString()}") { run(c, w.replayed) }
        }
    }

    // ---- The close itself ----

    /** Lives market day [Ctx.day] in [c]'s capital. */
    private fun run(c: Ctx, replayed: Boolean) {
        val data = c.data
        val key = c.capital
        val town = data.town(key)
        val day = c.day
        // Coming on again (11.5): the days a switch slept, or that the clock leapt, are not lived; an open tale is not ended by them.
        // The last day lived is the last close, or the day an open tale was seeded if that is later (a tale started by hand before any close)
        val lived = maxOf(town.closed, data.openIn(key)?.d0 ?: TaleData.NEVER)
        if (TaleKeep.real(lived) && day - lived > 1) {
            val shift = day - lived - 1
            Guildmark.LOGGER.info("The tales of the capital at {} come on after {} market days that were not lived", BlockPos.of(key).toShortString(), shift)
            TaleKeep.thaw(data, key, shift) { t -> Templates.find(t.template)?.thaws ?: true }
        }
        town.closed = day
        data.changed()
        TaleKeep.trim(data, day)
        TaleEngine.reindex()
        // An ended tale whose finish was cut short is finished again from what the record holds (6.7)
        for (t in data.all().filter { it.capital == key && !it.open && !it.finished }.sortedBy { it.id }) {
            TaleEngine.guarded("finishing the ${t.template} tale #${t.id}") { TaleFinish.resume(c, t) }
        }
        val open = data.openIn(key)
        if (open != null) {
            drew[key] = "the ${open.template} tale #${open.id} is open, so there was no draw"
            settle(c, open)
        } else {
            draw(c, town, replayed)
        }
    }

    /**
     * Step 2 of 2.2, a capital with an open tale: who has gone, the hard stop, the day told to what listens, and the step settled
     * if its day has come (the first close whose new day is later than `by`) or cannot be done (5.7). At most one step lapses at a close.
     */
    private fun settle(c: Ctx, t: Tale) {
        val template = Templates.find(t.template)
        if (template == null) {
            TaleFinish.quiet(c, t)
            return
        }
        TaleGuard.reset(t)
        TaleGone.sweep(c, t, template)
        if (!t.open) return
        if (TaleRules.overdue(c.day, t.d0)) {
            t.addWhy("late")
            c.data.changed()
            TaleFinish.finish(c, t, Ending.W)
            return
        }
        val step = template.steps.getOrNull(t.step)
        if (step == null) {
            // A step this build's table does not have (a later build changed it): there is nothing to be done at it
            t.addWhy("skipped")
            c.data.changed()
            TaleFinish.finish(c, t, Ending.W)
            return
        }
        if (TaleEvents.listens(step, Event.Kind.DAY)) TaleSteps.hear(c, t, template, Event.Day(c.capital, c.day))
        if (!t.open) return
        if (!TaleSteps.possible(t, template)) {
            TaleSteps.skip(c, t)
        } else if (TaleRules.due(c.day, t.by)) {
            TaleSteps.lapse(c, t)
        }
    }

    /** Steps 3 to 7 of 2.2, a capital with no open tale: the rules, the draw, and the templates in the day's order. */
    private fun draw(c: Ctx, town: TaleData.Town, replayed: Boolean) {
        val key = c.capital
        val stop = TaleRules.stopped(c.day, town.quiet, town.seen, daysOnRecord(c), startsOn(c.day), replayed)
        if (stop != null) {
            drew[key] = "no draw: $stop"
            return
        }
        val pace = Tales.pace()
        if (!TaleRules.hit(key, c.day, pace)) {
            drew[key] = "the draw missed (the chance is ${Math.round(TaleRules.chance(pace) * 100)} in 100)"
            return
        }
        for (id in TaleRules.order(key, c.day, Templates.IDS)) {
            val template = Templates.byId(id) ?: continue
            if (TaleRules.again(c.day, town.last[id] ?: TaleData.NEVER)) continue
            if (!TaleSeeding.needs(template)) continue
            val made = TaleSeeding.attempt(c, template, forced = false).tale ?: continue
            noteStart(c.day)
            drew[key] = "the draw hit and ${template.id} found its trouble (tale #${made.id})"
            return
        }
        drew[key] = "the draw hit and no template found its trouble"
    }

    // ---- For the commands ----

    /**
     * `tale day`: the capital [key] lives its next market day now, a real close and not a replayed one, and the answer says what
     * came of it. Null if the capital's places are not known yet.
     */
    fun live(level: ServerLevel, key: Long): String? {
        val day = TaleEngine.today(level, key) + 1
        val c = TaleEngine.ctx(level, key, day) ?: return null
        val before = describe(c.data.openIn(key))
        try {
            run(c, replayed = false)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The close of day {} run by hand failed", day, e)
            return "day $day failed: ${e.javaClass.simpleName} (see the log)"
        }
        return "day $day closed: $before -> ${describe(c.data.openIn(key))}; ${drew[key] ?: "no draw"}"
    }

    private fun describe(t: Tale?): String = if (t == null) "no tale" else "${t.template} #${t.id} at step ${t.step}"
}
