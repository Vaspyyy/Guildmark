package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Live
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.server.level.ServerLevel

/**
 * A village's people as their bodies know them (living-world/villages/design.md 5.2 to 5.4): the [Life] of a resident whose
 * town is a village, on the village's own places (its homes, wells, benches, fields, work blocks and green), found where
 * [io.github.vaspyyy.guildmark.townsfolk.Days.life] finds a capital's person's and failing to find a capital for them.
 *
 * The days are the capital's own days ([io.github.vaspyyy.guildmark.townsfolk.Days.plan]) shaped to the village ([VillageDays]),
 * and what they do in each stretch is [Acts](io.github.vaspyyy.guildmark.townsfolk.Acts) with the village's own turns ([VillageActs]).
 * What a village still lacks of a capital's plan is made good here, once per body: a resident with no home, bed or work block of
 * the village's (a body set down in its zone and taken in by the adoption pass, say) is given a free home and bed that stand, and
 * the work block of their trade, so nobody stands on the green all day for want of a door to go home to.
 *
 * Off with Living Villages or the folk switch ([enabled]): no life, and the body goes by the old timetable, as it did.
 */
object VillageLife {
    /** Whether a village's people live days: Living Villages on, and the folk switch. */
    fun enabled(): Boolean = Villages.enabled() && try {
        GuildmarkConfig.VILLAGE_FOLK.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** Whether [life] is a village's. */
    fun village(life: Life): Boolean = life.places.kind == TownKind.VILLAGE

    /**
     * Whether [life] (a village's) is out of date: the village has been counted again since, and its places are new ones. Never for
     * a capital's life, and not while the village has no places kept (paused, or not yet counted since the game began): the life
     * they have goes on.
     */
    fun stale(p: Townsperson, life: Life): Boolean {
        if (!village(life)) return false
        val level = p.level() as? ServerLevel ?: return false
        val now = Villages.places(level, life.resident.capital.asLong()) ?: return false
        return now !== life.places
    }

    /** The life of village resident [r], whose body is [p]; null if the village has no places yet or is not one to live in. */
    fun of(p: Townsperson, level: ServerLevel, r: Resident): Life? {
        if (!enabled()) return null
        val key = r.capital.asLong()
        val rec = Villages.byKey(level, key) ?: return null
        if (rec.state == Live.LOST || !rec.folk) return null
        val places = Villages.places(level, key) ?: return null
        settle(level, p, r, places)
        val home = places.lodgings[r.home]?.takeIf { it.beds.isNotEmpty() }
        val life = Life(r, places, home)
        life.nightWatch = p.nightWatch
        workplace(p, r, places)
        // Home is the house they live in now, not where they happened to be set down
        home?.front?.let { p.home = it.inside }
        return life
    }

    // --- A home, a bed and a work block ---

    /**
     * [r]'s home, if the one they had no longer stands (or they never had one) a home of the village's with a free bed, the
     * nearest to [p] first; and their bed in it, if the one they had is no longer a bed of it. A resident the village has no bed
     * for is left homeless; their days see to that ([VillageActs]).
     */
    private fun settle(level: ServerLevel, p: Townsperson, r: Resident, places: VillagePlaces) {
        val data = Residents.get(level)
        val mates = data.byCentre(r.capital).filter { it.alive && it.id != r.id }
        val own = places.lodgings[r.home]?.takeIf { it.beds.isNotEmpty() }
        if (own != null) {
            if (r.bed == null || r.bed !in own.beds) {
                val taken = mates.filter { it.home == own.id }.mapNotNull { it.bed }.toSet()
                val bed = own.beds.firstOrNull { it !in taken }
                if (bed != null) {
                    r.bed = bed
                    data.changed()
                }
            }
            return
        }
        val used = mates.groupBy { it.home }
        val free = places.lodgings.values.filter { it.beds.isNotEmpty() && (used[it.id]?.size ?: 0) < it.beds.size }
        val pick = free.minByOrNull { it.box.center.distSqr(p.blockPosition()) }
        if (pick == null) {
            if (r.home != -1 || r.bed != null) {
                r.home = -1
                r.bed = null
                data.changed()
            }
            return
        }
        val taken = mates.filter { it.home == pick.id }.mapNotNull { it.bed }.toSet()
        r.home = pick.id
        r.bed = pick.beds.firstOrNull { it !in taken }
        data.changed()
    }

    /** The job blocks each trade works at. */
    private val BLOCKS: Map<TownRole, List<M>> = mapOf(
        TownRole.FARMER to listOf(M.COMPOSTER),
        TownRole.SMITH to listOf(M.ANVIL, M.GRINDSTONE, M.SMITHING_TABLE, M.BLAST_FURNACE),
        TownRole.BAKER to listOf(M.SMOKER, M.FURNACE),
        TownRole.PRIEST to listOf(M.LECTERN, M.BREWING_STAND),
        TownRole.FISHER to listOf(M.BARREL),
        TownRole.CITIZEN to listOf(M.LOOM, M.FLETCHING_TABLE, M.CARTOGRAPHY_TABLE, M.STONECUTTER, M.CRAFTING_TABLE),
    )

    /**
     * The block [p] works at, if they have none that is one of the village's job blocks: the nearest of their trade's blocks to
     * their home (or to where they stand), spread over the nearest few by who they are so a village's two smiths don't share one.
     */
    private fun workplace(p: Townsperson, r: Resident, places: VillagePlaces) {
        val kinds = BLOCKS[r.role] ?: return
        val mine = p.work
        if (mine != null && places.jobs.any { it.pos == mine && it.m in kinds }) return
        val from = places.lodgings[r.home]?.box?.center ?: p.blockPosition()
        val near = places.jobs.filter { it.m in kinds }.sortedBy { it.pos.distSqr(from) }.take(3)
        if (near.isEmpty()) return
        p.work = near[Math.floorMod(r.id, near.size)].pos
    }
}
