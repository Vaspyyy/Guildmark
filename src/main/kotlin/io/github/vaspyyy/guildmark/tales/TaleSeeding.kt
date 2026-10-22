package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Residents

/**
 * Making a tale (living-world/stories/design.md 2.2 step 7): a template's `seed` reads the records and answers a plan; this
 * checks the plan against the rules every template shares (1.2, 11.2), makes the tale from it, holds the people it says to hold,
 * enters its first step and tells whatever follows tales. The draw and its rules are the close's ([TaleClose]); this is what
 * both the draw and `tale start` do once a template is chosen.
 */
internal object TaleSeeding {
    /** What a try to seed came to: the [tale], or [why] it did not (as `tale seeds` and `tale start` say it), and what a forced seed [madeUp]. */
    class Attempt(val tale: Tale?, val why: String, val madeUp: String = "")

    /** Whether the systems [template] stands on are on; false, and logged, if it cannot say. */
    fun needs(template: Template): Boolean = try {
        template.needs()
    } catch (e: Exception) {
        Guildmark.LOGGER.warn("The {} template could not say whether the systems it stands on are on", template.id, e)
        false
    }

    /**
     * Seeds [template] in [c]'s capital now. [forced] is `tale start`: the template makes up the window its trigger asks for and
     * says so. Nothing is made if the capital has an open tale, the template finds nothing, or its plan breaks a rule.
     */
    fun attempt(c: Ctx, template: Template, forced: Boolean): Attempt {
        if (!Tales.live) return Attempt(null, "tales are off")
        c.data.openIn(c.capital)?.let { return Attempt(null, "the capital has an open tale already: the ${it.template} tale #${it.id}") }
        val seeding = Seeding(c.level, c.here, c.entry, c.day, forced, template.id)
        val plan = try {
            template.seed(seeding)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The {} template threw while it looked for its trouble", template.id, e)
            return Attempt(null, "it threw while it looked (see the log)")
        } ?: return Attempt(null, seeding.found.ifEmpty { "it found nothing" })
        val problem = problem(c, template, plan)
        if (problem != null) {
            Guildmark.LOGGER.warn("The {} template's plan was not taken: {}", template.id, problem)
            return Attempt(null, "its plan was not taken: $problem")
        }
        val t = make(c, template, plan)
        for (member in plan.cast) if (member.held) hold(c, t, member.slot)
        TaleGuard.run(c, t, "enter step ${t.step}") { template.enter(c, t) }
        if (t.open) {
            Tales.hear { it.started(c.level, t) }
            if (!TaleSteps.possible(t, template)) TaleSteps.skip(c, t)
        }
        return Attempt(t, "", plan.madeUp)
    }

    /** What is wrong with [plan], or null: the rules every template shares. */
    private fun problem(c: Ctx, template: Template, plan: Planned): String? {
        val data = c.data
        val residents = Residents.get(c.level)
        if (plan.cast.isEmpty()) return "no one is cast"
        if (plan.cast.size > TaleLimits.CAST) return "${plan.cast.size} are cast, at most ${TaleLimits.CAST}"
        if (plan.cast.any { it.slot.isBlank() } || plan.cast.map { it.slot }.toSet().size != plan.cast.size) return "a slot has no name or is named twice"
        if (plan.cast.count { it.held } > TaleLimits.HELD) return "more than ${TaleLimits.HELD} would be held"
        val named = HashSet<Int>()
        for (member in plan.cast) {
            if (member.resident < 0) continue
            val r = residents[member.resident] ?: return "${member.slot} is resident ${member.resident}, who is not on record"
            if (!r.alive || r.left >= 0) return "${r.name} (${member.slot}) is not in the town"
            if (!named.add(member.resident)) return "${r.name} is cast twice"
            if (data.castIn(member.resident) != null) return "${r.name} (${member.slot}) is in another tale"
        }
        if (plan.step !in template.steps.indices) return "it begins at step ${plan.step}, which it does not have"
        if (plan.n.size > TaleLimits.NUMBERS) return "${plan.n.size} numbers, at most ${TaleLimits.NUMBERS}"
        if (plan.s.size > TaleLimits.STRINGS) return "${plan.s.size} strings, at most ${TaleLimits.STRINGS}"
        if (plan.why.kind.isBlank()) return "its first reason has no kind"
        return null
    }

    /** The tale [plan] describes, added to the record: not yet held, its first step not yet entered. */
    private fun make(c: Ctx, template: Template, plan: Planned): Tale {
        val data = c.data
        val residents = Residents.get(c.level)
        val t = Tale(
            id = data.newId(),
            template = template.id,
            capital = c.capital,
            step = plan.step,
            stepDay = c.day,
            by = TaleRules.settleDay(c.day, c.day, template.steps[plan.step].by),
            d0 = c.day,
        )
        for (member in plan.cast) {
            val name = member.name.ifBlank { if (member.resident >= 0) residents[member.resident]?.name.orEmpty() else "" }
            t.addCast(Cast(member.slot, member.resident, name))
        }
        plan.n.forEachIndexed { i, v -> t.n[i] = v }
        plan.s.forEachIndexed { i, v -> t.setS(i, v) }
        t.addWhy(plan.why)
        data.add(t)
        TaleEngine.reindex()
        return t
    }

    /** Holds the person in [slot] (4.4); a hold that cannot be made leaves the tale running in the record. */
    private fun hold(c: Ctx, t: Tale, slot: String) {
        try {
            Tales.hold(c.level, t, slot)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The {} tale #{} could not hold {}", t.template, t.id, slot, e)
        }
    }
}
