package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.TaleBannerPayload

/**
 * `Tales.finish`, the one door every ending goes through (living-world/stories/design.md 6.7): a player's last choice, a
 * listener, the world's way at `by`, the hard stop, a command. Ten stages in a fixed order ([Finish]), each in a try/catch of its
 * own so that one that fails is logged and the rest still run, each marked in [Tale.done] once it has been tried. The record is
 * complete after stage 1; an ended tale whose mask is not full is finished again from what the record holds at its capital's next
 * close ([resume]), so a server that stopped in the middle cannot leave a stone unset or a reward unpaid. A stage that threw is
 * not tried a second time: running a pay twice is worse than running it never, and the log says what happened.
 */
internal object TaleFinish {
    /** The tales whose stages are running now, so that a stage which ends its own tale again does not start the pipeline again. */
    private val running = HashSet<Int>()

    /**
     * Ends [t] with [ending] ("A", "B", "C" or "W"; anything else is W) through all ten stages. A tale already ended is only
     * finished, not ended again, so ending a tale twice is harmless. A tale whose template this build does not know is ended
     * quietly instead ([quiet], 11.4).
     */
    fun finish(c: Ctx, t: Tale, ending: String) {
        val template = Templates.find(t.template)
        if (template == null) {
            if (t.open) quiet(c, t)
            return
        }
        if (!running.add(t.id)) return
        try {
            if (t.open) {
                mark(c, t, if (ending in Ending.LETTERS) ending else {
                    Guildmark.LOGGER.warn("The {} tale #{} was ended with '{}', which is none of A, B, C or W: W it is", t.template, t.id, ending)
                    Ending.W
                })
            }
            if (!t.open) stages(c, t, template)
        } finally {
            running.remove(t.id)
        }
    }

    /** Runs the stages of [t], ended earlier, that were not tried (a crash, a stop), at its capital's close. */
    fun resume(c: Ctx, t: Tale) {
        if (t.open || t.finished) return
        val template = Templates.find(t.template)
        if (template == null) {
            t.done = Finish.ALL
            c.data.changed()
            return
        }
        if (!running.add(t.id)) return
        try {
            stages(c, t, template)
        } finally {
            running.remove(t.id)
        }
    }

    // ---- The stages ----

    /** Stage 1, the tale itself: ENDED with its [ending], the last `Why`, and the chronicle line. */
    private fun mark(c: Ctx, t: Tale, ending: String) {
        t.phase = Tale.Phase.ENDED
        t.ending = ending
        t.ended = c.day
        t.addWhy(TaleKeys.closing(ending))
        t.done = t.done or Finish.MARK.bit
        c.data.changed()
        TaleEngine.reindex()
        try {
            val town = c.data.town(t.capital)
            val names = t.cast.map { it.name }.filter { it.isNotBlank() }.distinct().take(3)
            val lead = t.lead?.let { TaleEngine.playerName(c.level, it.player) }.orEmpty()
            town.chronicle.add(TaleData.Line(t.template, ending, c.day, names, lead))
            while (town.chronicle.size > TaleLimits.CHRONICLE) town.chronicle.removeAt(0)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The chronicle did not take the line of the {} tale #{}", t.template, t.id, e)
        }
    }

    /** Stages 2 to 10, each tried once. */
    private fun stages(c: Ctx, t: Tale, template: Template) {
        val ending = t.ending
        stage(c, t, Finish.END) { template.end(c, t, ending) }
        stage(c, t, Finish.TIMES) { TaleTimes.run(c, t) }
        stage(c, t, Finish.MEMORY) { TaleMemory.run(c, t) }
        stage(c, t, Finish.TOLD) { TaleTold.run(c, t) }
        stage(c, t, Finish.PAY) {
            try {
                TalePay.run(c, t)
            } finally {
                // What a player put in and has not had spent comes back at the world's way; at an ending of their own making it was spent
                if (ending == Ending.W) TalePay.refund(c, t) else spend(c, t)
            }
        }
        stage(c, t, Finish.SEEN) {
            // The scene and the banner do not wait on one another
            try {
                TaleLook.queueEnding(c, t)
            } catch (e: Exception) {
                Guildmark.LOGGER.warn("The ending scene of the {} tale #{} failed", t.template, t.id, e)
            }
            TaleBannerPayload.send(c, t)
        }
        stage(c, t, Finish.BLOCKS) { Memorials.place(c, t) }
        stage(c, t, Finish.FREE) { free(c, t) }
        stage(c, t, Finish.HEARD) { Tales.hear { it.ended(c.level, t) } }
    }

    private fun stage(c: Ctx, t: Tale, which: Finish, body: () -> Unit) {
        if (t.done and which.bit != 0) return
        try {
            body()
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Stage {} of ending the {} tale #{} failed", which, t.template, t.id, e)
        }
        t.done = t.done or which.bit
        c.data.changed()
    }

    /** What the players put in is spent: it was the price of what they did. */
    private fun spend(c: Ctx, t: Tale) {
        var any = false
        for (p in t.parts) if (p.escrow != 0) {
            p.escrow = 0
            any = true
        }
        if (any) c.data.changed()
    }

    /** Stage 9: the cast and the held residents go back to their days, the capital is quiet, and the template is remembered. */
    private fun free(c: Ctx, t: Tale) {
        for (cast in t.cast.toList()) {
            if (!cast.held) continue
            try {
                Tales.release(c.level, t, cast.slot)
            } catch (e: Exception) {
                Guildmark.LOGGER.warn("{} of the {} tale #{} was not let go to their day", cast.name, t.template, t.id, e)
            }
            cast.held = false
        }
        val town = c.data.town(t.capital)
        town.quiet = maxOf(town.quiet, t.ended + TaleLimits.QUIET_DAYS)
        town.last[t.template] = t.ended
        c.data.changed()
    }

    // ---- The other ways a tale stops ----

    /**
     * A tale of a template this build does not know (a later build rolled back, 11.4) is ended quietly: its escrow is given
     * back, the held let go, and it is taken out of the record. No pay, no consequence, no chronicle line.
     */
    fun quiet(c: Ctx, t: Tale) {
        Guildmark.LOGGER.info("The tale #{} is ended quietly: this build has no '{}' tale", t.id, t.template)
        clear(c, t, quiet = false)
    }

    /**
     * Takes [t] out of the record with no consequence (`tale clear`, a broken tale): no chronicle line, no pay, its escrow given
     * back in full, the held residents let go. With [quiet] the capital is quiet as for an ending and remembers the template, so
     * a tale that broke is not drawn again at once.
     */
    fun clear(c: Ctx, t: Tale, quiet: Boolean) {
        try {
            TalePay.refund(c, t)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The escrow of the {} tale #{} was not given back", t.template, t.id, e)
        }
        for (cast in t.cast.toList()) {
            if (!cast.held) continue
            try {
                Tales.release(c.level, t, cast.slot)
            } catch (e: Exception) {
                Guildmark.LOGGER.warn("{} of the {} tale #{} was not let go to their day", cast.name, t.template, t.id, e)
            }
            cast.held = false
        }
        if (quiet) {
            val town = c.data.town(t.capital)
            town.quiet = maxOf(town.quiet, c.day + TaleLimits.QUIET_DAYS)
            town.last[t.template] = c.day
        }
        c.data.remove(t.id)
        TaleGuard.reset(t)
        TaleEngine.reindex()
        c.data.changed()
    }

    /** The strikes rule (15.2): [t] threw too often; it is ended at W, and if even that cannot be done it is cleared. */
    fun broken(c: Ctx, t: Tale) {
        Guildmark.LOGGER.warn("The {} tale #{} has thrown {} times: ending it at W", t.template, t.id, TaleLimits.STRIKES)
        t.addWhy("broke")
        var ended = false
        try {
            finish(c, t, Ending.W)
            ended = !t.open
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Ending the {} tale #{} at W failed as well: clearing it", t.template, t.id, e)
        }
        if (!ended) clear(c, t, quiet = true)
    }
}
