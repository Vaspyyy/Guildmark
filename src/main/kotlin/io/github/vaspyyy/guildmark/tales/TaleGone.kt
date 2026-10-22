package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.server.level.ServerLevel

/**
 * When the cast die or go (living-world/stories/design.md 5.6): the engine's own handling, the same for all twelve, and then the
 * template's path. A cast member who dies or whose household leaves is noted in the tale's reasons; a standing slot gets a stand-in
 * from the same trade nearest the old home; an essential one sends the tale down the template's `gone` path; and a tale whose
 * essential people have all gone ends at W. The same is worked out again from the records at every close ([sweep]), so a death that
 * happened while tales were off, or that no hook reported, is not missed.
 */
internal object TaleGone {
    // ---- From the hooks ----

    /** From `Tales.died`: [r] died or was put to rest. If an open tale cast them it copes (now, or as soon as the capital's places are known). */
    fun died(level: ServerLevel, r: Resident) {
        val t = TaleData.get(level).castIn(r.id) ?: return
        TaleEngine.withCtx(level, t.capital, "the death of ${r.name} in the ${t.template} tale #${t.id}") { c ->
            if (!t.open) return@withCtx
            val template = Templates.find(t.template) ?: return@withCtx
            val cast = t.cast.firstOrNull { it.resident == r.id && it.gone.isEmpty() } ?: return@withCtx
            gone(c, t, template, cast, Gone.DIED)
        }
    }

    /** From the comings' `left`: household [h] went from the town. The cast of the open tale who lived in it have left, unless the tale itself sent them. */
    fun left(level: ServerLevel, h: Household) {
        val t = TaleData.get(level).openIn(h.capital) ?: return
        val slots = t.cast.filter { it.resident in h.members && it.gone.isEmpty() }.map { it.slot }
        if (slots.isEmpty() || sent(level, t, h.capital, h.id)) return
        TaleEngine.withCtx(level, t.capital, "the leaving of a household from the ${t.template} tale #${t.id}") { c ->
            val template = Templates.find(t.template) ?: return@withCtx
            for (slot in slots) {
                if (!t.open) break
                val cast = t.cast(slot)?.takeIf { it.gone.isEmpty() } ?: continue
                gone(c, t, template, cast, Gone.LEFT)
            }
        }
    }

    /** Whether household [id] of [capital] was sent away by tale [t] itself (`Tales.leave`), which is the template's doing and not a loss. */
    fun sent(level: ServerLevel, t: Tale, capital: Long, id: Int): Boolean =
        ComingsData.get(level).find(capital)?.away?.firstOrNull { it.id == id }?.why == TaleHouseholds.reason(t)

    // ---- From the records, at the close ----

    /** Notes the cast of [t] who have died or gone, from the records (a death while tales were off, a hook that did not come), and ends the tale if no one it needs is left. */
    fun sweep(c: Ctx, t: Tale, template: Template) {
        val residents = Residents.get(c.level)
        for (slot in t.cast.map { it.slot }) {
            if (!t.open) return
            val cast = t.cast(slot) ?: continue
            if (cast.resident < 0 || cast.gone.isNotEmpty()) continue
            val r = residents[cast.resident]
            val how = when {
                r == null -> Gone.DIED
                r.alive -> null
                r.died >= 0 -> Gone.DIED
                r.left >= 0 -> if (sentBy(c, t, r)) null else Gone.LEFT
                else -> null
            } ?: continue
            gone(c, t, template, cast, how)
        }
        if (t.open && none(t, template)) finishNone(c, t)
    }

    /** Whether [r], away, is one of a household [t] itself sent away. */
    private fun sentBy(c: Ctx, t: Tale, r: Resident): Boolean {
        val away = ComingsData.get(c.level).find(c.capital)?.away ?: return false
        return away.any { r.id in it.members && it.why == TaleHouseholds.reason(t) }
    }

    // ---- The engine's own handling ----

    /**
     * [cast] of [t] went, [how]: a reason is noted; a held body is let go; a standing slot is filled again from the same trade
     * nearest the old home, and the template is told ([Template.stoodIn]); any other slot is gone, the template's `gone` path runs,
     * and if no essential slot is left the tale ends at W.
     */
    fun gone(c: Ctx, t: Tale, template: Template, cast: Cast, how: Gone) {
        if (!t.open || cast.gone.isNotEmpty()) return
        val name = cast.name
        t.addWhy(how.id, name)
        c.data.changed()
        val wasHeld = cast.held
        if (wasHeld) letGo(c, t, cast)
        if (cast.slot in template.standing) {
            val next = TaleCast.standIn(c, t, cast)
            if (next != null) {
                cast.resident = next.id
                cast.name = next.name
                t.addWhy("stood_in", next.name, name)
                c.data.changed()
                TaleEngine.reindex()
                TaleGuard.run(c, t, "the stand-in for ${cast.slot}") { template.stoodIn(c, t, cast.slot, wasHeld) }
                return
            }
        }
        cast.gone = how.id
        c.data.changed()
        TaleGuard.run(c, t, "the going of ${cast.slot}") { template.gone(c, t, cast.slot, how) }
        if (t.open && none(t, template)) finishNone(c, t)
    }

    /** Whether every person [t] needs has gone: the essential slots held by residents (the standing ones count only if there are no others). */
    private fun none(t: Tale, template: Template): Boolean {
        val real = t.cast.filter { it.resident >= 0 }
        if (real.isEmpty()) return false
        val essential = real.filter { it.slot !in template.standing }.ifEmpty { real }
        return essential.all { it.gone.isNotEmpty() }
    }

    private fun finishNone(c: Ctx, t: Tale) {
        t.addWhy("none_left")
        c.data.changed()
        TaleFinish.finish(c, t, Ending.W)
    }

    private fun letGo(c: Ctx, t: Tale, cast: Cast) {
        try {
            Tales.release(c.level, t, cast.slot)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("{} of the {} tale #{} was not let go to their day", cast.name, t.template, t.id, e)
        }
        cast.held = false
    }
}
