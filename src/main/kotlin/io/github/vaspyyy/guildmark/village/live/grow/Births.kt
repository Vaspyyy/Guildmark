package io.github.vaspyyy.guildmark.village.live.grow

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * A village's children (living-world/villages/design.md 5.6): born by the record at the close, carried out to be seen the next
 * morning, a toddler about the place from their third day, and grown up on their eighth.
 *
 * - **Born** ([born]): the home [GrowthRules] picked has a child of its two grown-ups' family, a name of the land's, and the free
 *   bed; a baby for [GrowthRules.BABY_DAYS] days with no body of its own (a parent carries it), the village's chronicle and the
 *   street's talk told of it (`Villages.note` "born", `Witness.born`).
 * - **Shown** ([step]): the next morning (seven to noon, and on the baby's other mornings if nobody saw it the first), with a
 *   player within [SHOW_NEAR] blocks of the door and a parent about and free, a parent carries it out into the lane
 *   ([GrowScenes.Birth]). Once.
 * - **A toddler**: on its third day its body is made at home out of sight, and it plays in the lanes as the village's
 *   children do.
 * - **Grown up** ([grow]): on its eighth day in the village, as soon as nobody can see them, the child is the same person under
 *   the same id with a trade (the one the village lacks most, else a citizen's), a look of their own of it, and a body made anew
 *   at home out of sight ([Residents.grownUp]); the next morning a neighbour near a player remarks on it ([GrowScenes.Remark]).
 */
internal object Births {
    /** How near the door a player must be for a birth to be shown, and a grown child for a neighbour to remark on them. */
    private const val SHOW_NEAR = 48.0
    private const val REMARK_NEAR = 24.0

    /** The morning's hours (ticks since six) a birth is shown in, and a grown child remarked on. */
    private val SHOWN = 1000L..6000L
    private val REMARKED = 1000L..9000L

    /** How near their home a parent must be to fetch the baby. */
    private const val PARENT_NEAR = 24.0

    // ---- The record ----

    /** A child born on [today] (a board day) in [rec]'s home [homeId] by the record, or null if it has no two grown-ups and a bed free. */
    fun born(level: ServerLevel, rec: VillageRec, g: Grow, homeId: Int, today: Long): Resident? {
        val residents = Residents.get(level)
        val bell = BlockPos.of(rec.key)
        val living = residents.byCentre(bell).filter { it.alive }
        val parents = living.filter { it.home == homeId && it.role.adult }.sortedBy { it.id }
        if (parents.size < 2) return null
        val home = rec.homes.firstOrNull { it.id == homeId && !it.gone } ?: return null
        val bed = freeBed(level, home.beds.map { BlockPos.of(it) }, living) ?: return null
        val id = residents.newId()
        val seed = seed(rec.key, id, today)
        val look = Math.floorMod(seed ushr 3, maxOf(1, TownRole.CHILD.common).toLong()).toInt()
        val woman = Names.woman(TownRole.CHILD, look, seed)
        val given = Names.given(VillageGrowth.style(level, rec), woman, seed)
        val family = parents.first().family
        val r = Resident(id, null, bell, -1, given, family, TownRole.CHILD, look, homeId, bed, false, Residents.traits(seed), true, -1L, today)
        residents.add(r)
        g.babies += Baby(id, today, homeId)
        g.make += Make(id, today + GrowthRules.BABY_DAYS)
        Villages.note(level, rec.key, "born", r.name)
        Witness.born(level, r)
        g.said(today, "$given was born to the $family household (home $homeId): ${if (woman) "a girl" else "a boy"}")
        GrowthData.get(level).changed()
        return r
    }

    /** The seed a village's child is drawn by (their look, whether a girl, their name), and drawn again by as they grow up. */
    private fun seed(key: Long, id: Int, since: Long): Long = GrowthRules.mix(key, id * 31L + since)

    /** The first of [beds] none of [living] sleeps in (held for the townsfolk if its land is loaded and it can be), or null. */
    fun freeBed(level: ServerLevel, beds: List<BlockPos>, living: List<Resident>): BlockPos? {
        val taken = living.mapNotNullTo(HashSet()) { it.bed }
        val free = beds.filter { it !in taken }
        return free.firstOrNull { VillageGrowth.holdable(level, it) } ?: free.firstOrNull()
    }

    /**
     * A child born now in [rec] whatever the odds and the times (`village grow birth`): in the home with two grown-ups and a free
     * bed that has waited longest for one, and never past the beds or the cap. The child, or why there is none.
     */
    fun force(level: ServerLevel, rec: VillageRec, g: Grow): Pair<Resident?, String> {
        val today = VillageBoards.day(level)
        val p = VillageGrowth.picture(level, rec, g, today)
        val beds = p.homes.sumOf { it.beds }
        val cap = minOf(beds, VillageAdopt.maxFolk())
        val people = p.living.size + maxOf(0, p.vanilla) + (g.state.coming?.size ?: 0)
        if (people >= cap) return null to "no room: $people people of $cap at most (the beds or maxVillageFolk)"
        val homes = p.homes.filter { h ->
            val here = p.living.filter { it.home == h.id }
            here.count { it.adult } >= 2 && here.size < h.beds
        }
        val home = homes.minByOrNull { h -> p.living.filter { it.home == h.id }.maxOf { it.since } } ?: return null to "no home has two grown-ups and a free bed"
        val r = born(level, rec, g, home.id, today) ?: return null to "home ${home.id} could not have a child (no bed free)"
        g.state.born = today
        GrowthData.get(level).changed()
        return r to "home ${home.id}"
    }

    // ---- The world ----

    /** Each look: the babies shown, the children grown up when nobody can see them, and a word on those grown up. */
    fun step(level: ServerLevel, rec: VillageRec, places: VillagePlaces, g: Grow) {
        val today = VillageBoards.day(level)
        val clock = Math.floorMod(level.overworldClockTime, 24000L)
        val data = GrowthData.get(level)
        val residents = Residents.get(level)
        val grows = VillageGrowth.grows(rec)

        // The babies: shown the morning after, and off the list once they are toddlers
        val babies = g.babies.iterator()
        while (babies.hasNext()) {
            val b = babies.next()
            val r = residents[b.id]
            if (r == null || !r.alive || today - b.born >= GrowthRules.BABY_DAYS) {
                babies.remove()
                data.changed()
                continue
            }
            if (!grows || b.shown || today <= b.born || clock !in SHOWN || GrowScenes.running(rec.key)) continue
            show(level, rec, places, g, b, r)
        }

        // The children of age, grown up as soon as nobody can see them
        if (grows && g.aging.isNotEmpty() && VillageAdopt.swapping(rec.key) == 0) {
            for (id in g.aging.toList()) {
                val r = residents[id]
                if (r == null || !r.alive || r.role != TownRole.CHILD || r.capital.asLong() != rec.key) {
                    g.aging.remove(id)
                    data.changed()
                    continue
                }
                if (GrowScenes.holds(id)) continue
                val body = r.uuid?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive }
                if (body != null && (GrowScenes.has(body) || !Comings.unseen(level, body.blockPosition()))) continue
                grow(level, rec, places, g, r, body, today, clock)
                g.aging.remove(id)
                data.changed()
            }
        }

        // A word on those grown up, the morning after
        if (g.grown.isNotEmpty()) {
            if (g.grown.removeIf { it.said || today > it.day + 1 }) data.changed()
            if (grows && clock in REMARKED && !GrowScenes.running(rec.key)) {
                for (gr in g.grown.toList()) {
                    if (today < gr.day) continue
                    if (remark(level, rec, gr)) break
                }
            }
        }
    }

    /** [b] carried out into the lane by a parent of theirs, if one is about and free and a player is near the door. */
    private fun show(level: ServerLevel, rec: VillageRec, places: VillagePlaces, g: Grow, b: Baby, r: Resident) {
        val home = places.lodgings[r.home] ?: return
        val door = home.front ?: return
        if (level.getNearestPlayer(door.pos.x + 0.5, door.pos.y + 0.5, door.pos.z + 0.5, SHOW_NEAR, false) == null) return
        if (!io.github.vaspyyy.guildmark.townsfolk.Furnish.loaded(level, home)) return
        val residents = Residents.get(level)
        val household = residents.byCentre(BlockPos.of(rec.key)).filter { it.alive && it.home == r.home }
        val centre = Vec3.atCenterOf(home.box.center)
        val parents = household.filter { it.role.adult }
            .mapNotNull { p -> (p.uuid?.let { level.getEntity(it) } as? Townsperson)?.let { it to p } }
            .filter { (p, _) -> p.isAlive && p.director == null && !p.isSleeping && p.tale < 0 && p.distanceToSqr(centre) < PARENT_NEAR * PARENT_NEAR }
            // The mother carries the baby out, if she is about
            .sortedBy { (p, rp) -> if (Names.woman(rp.role, rp.look, rp.id.toLong())) 0 else 1 + p.distanceToSqr(centre).toInt() }
        val (mother, mr) = parents.firstOrNull() ?: return
        val father = parents.getOrNull(1)
        val bed = r.bed ?: home.beds.firstOrNull() ?: door.inside
        val cot = cot(level, home, bed) ?: door.inside
        GrowScenes.Birth(level, rec.key, g, b, cot, bed, door, household.mapTo(HashSet()) { it.id }).begin(mother, mr.id, father?.first, father?.second?.id ?: -1)
        g.said(VillageBoards.day(level), "${r.given} was carried out for the lane to see")
    }

    /** Where to stand by [bed] in [home] to take the baby up from it. */
    private fun cot(level: ServerLevel, home: CityPlaces.Lodging, bed: BlockPos): BlockPos? {
        for (d in Direction.Plane.HORIZONTAL) {
            val at = GrowScenes.stand(level, bed.relative(d)) ?: continue
            if (home.contains(at) && at != bed) return at
        }
        return null
    }

    /**
     * [r], a child of age, grown up now: the trade [rec] lacks most (else a citizen's), a look of it of their own voice, their old
     * body ([body], if it is about, nobody seeing it) gone and a new one to be made at home out of sight; a word on it tomorrow.
     */
    fun grow(level: ServerLevel, rec: VillageRec, places: VillagePlaces?, g: Grow, r: Resident, body: Townsperson?, today: Long, clock: Long): Resident {
        val residents = Residents.get(level)
        val role = if (places != null) VillageGrowth.lacking(level, rec, places) else TownRole.CITIZEN
        val seed = seed(rec.key, r.id, r.since)
        val woman = Names.woman(TownRole.CHILD, r.look, seed)
        val look = VillageGrowth.look(role, woman, seed)
        val old = r.uuid
        val grown = residents.grownUp(r, role, look)
        if (old != null) {
            if (body != null && body.uuid == old) body.discard() else VillageGrowth.retire(level, g, old, r.id, today)
        }
        g.make.removeIf { it.id == r.id }
        g.make += Make(r.id, today)
        g.grown.removeIf { it.id == r.id }
        // Grown in the night (before noon), they are seen grown that same morning; else the next
        g.grown += Grown(r.id, if (clock < 6000L) today else today + 1)
        g.said(today, "${r.name} came of age: now ${if (role == TownRole.CITIZEN) "a citizen" else "the village's new ${role.id}"}")
        GrowthData.get(level).changed()
        return grown
    }

    /** A neighbour's word on [gr] grown up, if they are about with a player near and a neighbour free; true if it begins. */
    private fun remark(level: ServerLevel, rec: VillageRec, gr: Grown): Boolean {
        val residents = Residents.get(level)
        val r = residents[gr.id] ?: return false
        val p = r.uuid?.let { level.getEntity(it) as? Townsperson } ?: return false
        if (!p.isAlive || p.director != null || p.isSleeping || p.tale >= 0) return false
        if (level.getNearestPlayer(p, REMARK_NEAR) == null) return false
        val household = residents.byCentre(BlockPos.of(rec.key)).filter { it.home == r.home && r.home >= 0 }.mapTo(HashSet()) { it.id } + r.id
        val by = GrowScenes.neighbours(level, rec.key, p.position(), household, 1).firstOrNull() ?: return false
        GrowScenes.Remark(level, rec.key, gr, GrowthRules.mix(rec.key, gr.id.toLong()) and 1L == 0L).begin(p, by)
        return true
    }
}
