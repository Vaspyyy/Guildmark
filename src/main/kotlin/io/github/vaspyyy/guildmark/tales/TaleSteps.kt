package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.party.Parties
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The step machine (living-world/stories/design.md 1.3, 1.4 and 5.7): a tale is at one step of its template's table; a step is
 * entered ([go]), met by what the template hears, or settled by the world's way ([lapse]) at the close whose new day is later than
 * its `by`. This is where a step is met (what the players put into it is spent) and where it lapses unmet (it is given back), so
 * that no step can leave a player's Marks held. Everything that calls the template goes through [TaleGuard].
 */
internal object TaleSteps {
    /** How many steps may enter one another in one call: a template whose step moves straight on is followed this far, no further. */
    private const val DEPTH = 16

    private var depth = 0

    /** Adds a reason to [t] until its finish is complete (`Ctx.why`): while it is open, and while its ending is being carried out, when the template's own ending has its say. */
    fun why(c: Ctx, t: Tale, kind: String, args: Array<out String>) {
        if (t.finished) return
        t.addWhy(Why(kind, args.toList()))
        c.data.changed()
    }

    /**
     * Moves [t] on to step [step] (`Ctx.go`): the step before was met, so what the players put into it is spent; the step's days
     * are set from the template's table; the template's `enter` is called; and whoever follows tales hears of the turn.
     * A step beyond the table is none: the template ends the tale itself, and a call that asks for one is logged and ignored.
     */
    fun go(c: Ctx, t: Tale, step: Int) {
        if (!t.open) return
        val template = Templates.find(t.template) ?: return
        val to = template.steps.getOrNull(step)
        if (to == null) {
            Guildmark.LOGGER.warn("The {} tale #{} asked for step {}, which it does not have (it has {})", t.template, t.id, step, template.steps.size)
            return
        }
        if (depth >= DEPTH) {
            Guildmark.LOGGER.warn("The {} tale #{} moved on {} steps in one call: stopping at step {}", t.template, t.id, DEPTH, t.step)
            return
        }
        for (p in t.parts) p.escrow = 0
        t.step = step
        t.stepDay = c.day
        t.by = TaleRules.settleDay(t.d0, c.day, to.by)
        c.data.changed()
        depth++
        try {
            TaleGuard.run(c, t, "enter step $step") { template.enter(c, t) }
        } finally {
            depth--
        }
        if (t.open) Tales.hear { it.stepped(c.level, t) }
    }

    /**
     * The world's way for the step [t] is at (1.4), as the close does it and as `tale step` does it now: the Marks players put
     * in are given back in full (7.5), and the template's `settle` does what the town does without a hero. The template moves the
     * tale on or ends it; one that does neither is asked again at the next close, until the hard stop.
     */
    fun lapse(c: Ctx, t: Tale) {
        if (!t.open) return
        val template = Templates.find(t.template) ?: return
        try {
            TalePay.refund(c, t)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The escrow of the {} tale #{} was not given back", t.template, t.id, e)
        }
        TaleGuard.run(c, t, "settle step ${t.step}") { template.settle(c, t) }
    }

    /** A step the world cannot satisfy is let go (5.7): the reason is noted, and the world's way follows. */
    fun skip(c: Ctx, t: Tale) {
        if (!t.open) return
        why(c, t, "skipped", arrayOf())
        lapse(c, t)
    }

    /** Whether the step [t] is at can be done now: its own `needs` (5.7). A step whose `needs` throws can be done. */
    fun possible(t: Tale, template: Template): Boolean {
        val step = template.steps.getOrNull(t.step) ?: return true
        return try {
            step.needs()
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The {} tale's step {} could not say whether it can be done", t.template, t.step, e)
            true
        }
    }

    /** Tells [t]'s template of [e], guarded. */
    fun hear(c: Ctx, t: Tale, template: Template, e: Event) {
        if (!t.open) return
        TaleGuard.run(c, t, "heard ${e.kind}") { template.heard(c, t, e) }
    }

    // ---- A player takes it up ----

    /**
     * [player] said yes to [t] (`Tales.join`, 1.3 and 3.1): their part goes IN, and so do their party mates' within 32 blocks, who
     * are told in their chat (4.7); the listeners hear of each (the begin banner and its chime); and an ASK step ends. False if they
     * cannot take it up: tales are off, it is over, they already have the most a tracker holds, or the tale has no room for a
     * ninth part. Saying yes twice is saying it once.
     */
    fun join(level: ServerLevel, t: Tale, player: ServerPlayer): Boolean {
        if (!Tales.live || !t.open) return false
        val template = Templates.find(t.template) ?: return false
        val data = TaleData.get(level)
        val mine = t.part(player.uuid)
        if (mine != null && (mine.status == Part.Status.IN || mine.status == Part.Status.DONE)) return true
        if (!takeUp(data, t, player)) return false
        data.changed()
        Tales.hear { it.joined(level, t, player) }
        val title = Component.translatable(TaleKeys.title(t.template))
        for (mate in Parties.nearbyMates(player, MATES)) {
            if (!takeUp(data, t, mate)) continue
            mate.sendSystemMessage(Component.translatable(TaleKeys.PARTY, player.name, title))
            Tales.hear { it.joined(level, t, mate) }
        }
        if (template.steps.getOrNull(t.step)?.verb == Verb.ASK) {
            val step = t.step
            TaleEngine.withCtx(level, t.capital, "the end of the asking of the ${t.template} tale #${t.id}") { c ->
                if (t.open && t.step == step) c.next(t)
            }
        }
        return true
    }

    /** Puts [player]'s part IN, unless they already hold the most a tracker takes or the tale has no room for them. */
    private fun takeUp(data: TaleData, t: Tale, player: ServerPlayer): Boolean {
        val part = t.partFor(player.uuid) ?: return false
        if (part.status == Part.Status.IN || part.status == Part.Status.DONE) return true
        if (data.joinedBy(player.uuid).size >= TaleLimits.ON_TRACKER) return false
        part.status = Part.Status.IN
        return true
    }

    /** How near a party mate must be to be given the part too (4.7). */
    private const val MATES = 32.0
}
