package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.ComingsData
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The households a tale sends away, brings home and protects (living-world/stories/design.md 6.5 and 10.2(c)): `Ctx.leave`,
 * `Ctx.bringHome` and `Ctx.protect` come here, and so does the one question the comings ask of tales, whether a household may
 * not leave ([stays]). Everything that moves a household is the comings' own: this object picks the household, checks what a
 * tale must check first, and calls them. The reason a household sent away by a tale is recorded under ([reason]) is how the
 * comings' log, `/guildmark comings` and the engine tell it from a household that left in hard times.
 */
internal object TaleHouseholds {
    /** The reason a household sent away by [t] is recorded with: `tale <template> <id>`. */
    fun reason(t: Tale): String = "tale ${t.template} ${t.id}"

    /** The home [leave] is sending away just now, or -1: the one household [stays] lets through while it does, and no other. */
    private var sending = -1

    /** How many `tale` commands are driving a tale at this moment: a household is sent away whatever the town, as the comings' own command does. */
    private var commanded = 0

    /** Runs [body] as a `tale` command's doing: the floor of 6.5 is not kept for what it makes a tale do. */
    fun <T> byHand(body: () -> T): T {
        commanded++
        try {
            return body()
        } finally {
            commanded--
        }
    }

    // ---- What the comings ask ----

    /**
     * From `Comings.leavers`, for the household at [home] of [capital] with [members] living in it: whether it may not leave now,
     * because someone in it is in the cast of an open tale (a household in the middle of a story cannot leave from under it), or
     * because a tale has [protect]ed it and its days are not over. Never while [leave] is sending that very household.
     */
    fun stays(level: ServerLevel, capital: Long, home: Int, members: List<Resident>): Boolean {
        if (home == sending) return false
        val data = TaleData.get(level)
        return members.any { data.castIn(it.id) != null } || shielded(level, capital, home)
    }

    /** Whether a tale has protected the household at [home] of [capital], and its days are not over. */
    private fun shielded(level: ServerLevel, capital: Long, home: Int): Boolean {
        val town = TaleData.get(level).find(capital) ?: return false
        if (town.protect.isEmpty()) return false
        val today = TaleEngine.today(level, capital)
        return town.protect.any { it.home == home && it.until >= today }
    }

    // ---- A household goes ----

    /**
     * `Ctx.leave`: the household of lodging [home] goes away for [t], as `Comings.leave` sends one, but this one by home and not
     * newest first, and recorded under [reason] so the comings say "after the tale" and a template can tell it. It stays, and false
     * is answered with a `Why`, if the comings are off, a tale has protected it, it is not a citizens' household free of a trade, a
     * duty or a works, or the town is at or under the share of its people nobody leaves at (unless a `tale` command is driving the
     * tale). The `Why` is the template's own kind [why] when it gave one, else the engine's `stays`, `stays_kept` or `stays_off`;
     * its one argument is the household's family name.
     */
    fun leave(c: Ctx, t: Tale, home: Int, why: String?): Boolean {
        if (!Tales.live || t.finished || home < 0) return false
        val level = c.level
        val family = family(c, home) ?: return false
        if (!Comings.enabled()) return stayed(c, t, why ?: "stays_off", family)
        if (shielded(level, c.capital, home)) return stayed(c, t, why ?: "stays_kept", family)
        val today = Comings.today(level, c.capital) ?: return false
        val comings = Comings.ctx(level, c.here.places)
        val before = sending
        sending = home
        try {
            val pick = Comings.leavers(comings).firstOrNull { it.home.id == home } ?: return stayed(c, t, why ?: "stays_kept", family)
            if (commanded == 0 && Comings.living(comings) <= Comings.FLOOR * comings.planned) return stayed(c, t, why ?: "stays", family)
            val left = Comings.leave(comings, today, force = true, near = pick.home.box.center, why = reason(t))
                ?: return stayed(c, t, why ?: "stays_kept", family)
            if (left.home != home) Guildmark.LOGGER.warn("The {} tale #{} sent household #{} away, and it was another that went", t.template, t.id, home)
            return left.home == home
        } finally {
            sending = before
        }
    }

    /** The household stayed: [kind] is noted, with the [family], and the answer is no. */
    private fun stayed(c: Ctx, t: Tale, kind: String, family: String): Boolean {
        TaleSteps.why(c, t, kind, arrayOf(family))
        return false
    }

    /** The family name of whoever lives at [home] of [c]'s capital (a grown-up's first), or null if nobody does. */
    private fun family(c: Ctx, home: Int): String? {
        val people = Residents.get(c.level).of(c.here.capital.center).filter { it.alive && it.home == home }
        return (people.firstOrNull { it.role.adult } ?: people.firstOrNull())?.family
    }

    // ---- A household comes home ----

    /**
     * `Ctx.bringHome`: the household [away] comes home to [c]'s capital now, as the comings' own return plays it, by the gate
     * nearest [gate] (else whichever a player is near). False if the comings are off, the household is not away any more, or there
     * is no house for it.
     */
    fun bringHome(c: Ctx, t: Tale, away: ComingsData.Moved, gate: BlockPos?): Boolean {
        if (!Tales.live || t.finished || !Comings.enabled()) return false
        val level = c.level
        val comings = Comings.ctx(level, c.here.places)
        val live = comings.town.away.firstOrNull { it.id == away.id } ?: return false
        val today = Comings.today(level, c.capital) ?: return false
        return Comings.comeBack(comings, live, today, gate?.let { gateNear(comings.places, it) }, force = true) != null
    }

    /** The gate with a country road nearest [at], else null (the comings choose one). */
    private fun gateNear(places: CityPlaces, at: BlockPos): Dir? {
        val plan = places.census.plan
        return plan.outerGates
            .filter { g -> plan.outskirts.roads.any { it.side == g } }
            .minByOrNull { g -> front(places, g).distSqr(at) }
    }

    /** Just outside the gatehouse on [gate]'s side, on the road. */
    private fun front(places: CityPlaces, gate: Dir): BlockPos {
        val plan = places.census.plan
        val p = plan.sidePos(gate, 0, plan.outer + CityPlan.GATE_OUT + 1)
        return places.capital.world(p.x, plan.level(p.x, p.z), p.z)
    }

    // ---- A household stays ----

    /**
     * `Ctx.protect`: the household of lodging [home] cannot be sent away by the comings for [days] market days after today
     * (the longer of this and what it has already). A capital keeps [TaleLimits.PROTECT] of them; a new one past that drops the oldest.
     */
    fun protect(c: Ctx, t: Tale, home: Int, days: Int) {
        if (!Tales.live || t.finished || home < 0 || days <= 0) return
        val town = c.data.town(c.capital)
        val until = c.day + days
        val had = town.protect.firstOrNull { it.home == home }
        if (had != null) {
            if (had.until >= until) return
            town.protect.remove(had)
        }
        town.protect.add(TaleData.Protect(home, until))
        while (town.protect.size > TaleLimits.PROTECT) town.protect.removeAt(0)
        c.data.changed()
    }
}
