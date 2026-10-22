package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Who may be cast in a tale (living-world/stories/design.md 1.2), the rules every template shares, written once. A seed picks
 * from [pool]; the engine picks a stand-in from it when a standing cast member dies or goes (5.6, [standIn]). A template with a
 * rule of its own (the informer's friend must be a guard) filters the pool further and never loosens it.
 */
object TaleCast {
    /**
     * Whether [r] may be cast now: alive and in the town (not away, not dead), grown up (or, with [child], a child), nobody's
     * faction person, with a home to point at, and in no open tale. [data] is the record the open tales are read from.
     */
    fun free(r: Resident, data: TaleData, child: Boolean = false): Boolean {
        if (r.id < 0 || !r.alive || r.left >= 0 || r.home < 0) return false
        if (r.role.adult == child) return false
        if (Faction.of(r.role) != null) return false
        return data.castIn(r.id) == null
    }

    /** The people of the capital whose heart is [center] who may be cast (see [free]), in id order. */
    fun pool(level: ServerLevel, center: BlockPos, child: Boolean = false): List<Resident> {
        val data = TaleData.get(level)
        return Residents.get(level).of(center).filter { free(it, data, child) }.sortedBy { it.id }
    }

    /**
     * Who stands in for the person in [old] (5.6): someone of the same trade who may be cast, who is not already in [t], from
     * the home nearest the old one (the lowest id on a tie); null if there is no one. The old person may be dead or away.
     */
    fun standIn(c: Ctx, t: Tale, old: Cast): Resident? {
        val was = Residents.get(c.level)[old.resident] ?: return null
        val data = c.data
        val from = c.here.places.lodgings[was.home]?.box?.center
        val taken = t.cast.mapTo(HashSet()) { it.resident }
        return pool(c.level, c.here.capital.center, !was.role.adult)
            .filter { it.role == was.role && it.id !in taken }
            .minWithOrNull(compareBy<Resident> { r -> from?.let { f -> c.here.places.lodgings[r.home]?.box?.center?.distSqr(f) } ?: Double.MAX_VALUE }.thenBy { it.id })
            ?.takeIf { data.castIn(it.id) == null }
    }
}
