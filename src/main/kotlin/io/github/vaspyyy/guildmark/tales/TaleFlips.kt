package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Trait
import net.minecraft.server.level.ServerLevel

/**
 * People change (living-world/stories/design.md 6.5), Part E: a tale flips a resident's ways, and every flip is logged so that it
 * can be seen and put back. [flip] is `Tales.flip`: it clears the opposite of the pair (EARLY/LATE, DEVOUT/DRINKER, CHATTY/SHY,
 * DILIGENT/IDLE, CHEERFUL/GRUMPY), sets the trait asked for, writes the old one into [TaleData.flips], and drops the body's cached
 * day so that its plan is drawn again from the new ways (`Days.life`); nothing more, for what a trait does is what the traits
 * already do. At most [TaleLimits.FLIPS_A_TALE] residents are flipped by one tale and one resident once in [TaleLimits.FLIP_DAYS]
 * days; a flip a limit stops adds a `Why` to the tale (`flip_many`, `flip_soon`) and answers false. [unflip] puts one back, by its
 * number in `tale flips`.
 */
object TaleFlips {
    /** The other of [trait]'s pair: the traits are declared in pairs, so it is the one beside it. */
    fun opposite(trait: Trait): Trait = Trait.entries[trait.ordinal xor 1]

    /** The trait called [name] (as the log keeps it, lower case), or null for "" and anything unknown. */
    private fun trait(name: String): Trait? = Trait.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * What `Tales.flip` does: [r], of [t]'s cast, takes up [to]. True if they now have it (a resident who already had it is left alone
     * and nothing is logged); false if they cannot be changed (not living), or a limit stops it and a `Why` says so.
     */
    fun flip(c: Ctx, t: Tale, r: Resident, to: Trait): Boolean {
        if (!Tales.READY || !r.alive) return false
        if (r.has(to)) return true
        val data = c.data
        if (data.flips.count { it.tale == t.id } >= TaleLimits.FLIPS_A_TALE) {
            c.why(t, "flip_many", r.name)
            return false
        }
        if (data.flips.any { it.resident == r.id && c.day - it.day < TaleLimits.FLIP_DAYS }) {
            c.why(t, "flip_soon", r.name)
            return false
        }
        val opposite = opposite(to)
        val cleared = if (r.has(opposite)) opposite.name.lowercase() else ""
        r.traits = (r.traits and opposite.bit.inv()) or to.bit
        data.flips.add(TaleData.Flip(r.id, cleared, to.name.lowercase(), c.day, t.id))
        while (data.flips.size > TaleLimits.FLIP_LOG) data.flips.removeAt(0)
        data.changed()
        redraw(c.level, r)
        return true
    }

    /** The result of [unflip]: whether it was found (and so taken off the list), and what to tell whoever asked. */
    class Undone(val ok: Boolean, val text: String)

    /**
     * Puts flip number [n] of `tale flips` (counted from 1, oldest first) back: the trait it set is cleared, and the one it cleared is
     * set again. A resident who is gone, or who no longer has the trait, is left as they are; the line is taken off the list in every
     * case it was found.
     */
    fun unflip(level: ServerLevel, n: Int): Undone {
        val data = TaleData.get(level)
        val flip = data.flips.getOrNull(n - 1) ?: return Undone(false, "There is no flip $n (the list holds ${data.flips.size}).")
        data.flips.removeAt(n - 1)
        data.changed()
        val r = Residents.get(level)[flip.resident]?.takeIf { it.alive } ?: return Undone(true, "Flip $n is off the list: that resident is gone.")
        val to = trait(flip.to)
        if (to == null || !r.has(to)) return Undone(true, "Flip $n is off the list: ${r.name} no longer has that way.")
        val back = trait(flip.from)
        r.traits = r.traits and to.bit.inv()
        if (back != null) r.traits = r.traits or back.bit
        redraw(level, r)
        return Undone(true, "${r.name} is no longer ${to.name.lowercase()}" + (back?.let { " and is ${it.name.lowercase()} again" } ?: "") + ".")
    }

    /** Saves the change to [r] and has their body, if it is about, draw its day again from the new ways. */
    private fun redraw(level: ServerLevel, r: Resident) {
        Residents.get(level).changed()
        Comings.body(level, r)?.let {
            it.life = null
            it.lifeLooked = 0
        }
    }
}
