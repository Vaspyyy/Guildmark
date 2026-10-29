package io.github.vaspyyy.guildmark.village.live.grow

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Noted
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Household
import io.github.vaspyyy.guildmark.townsfolk.Names
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import io.github.vaspyyy.guildmark.village.live.Home
import io.github.vaspyyy.guildmark.village.live.VillagePlaces
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.Villages
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Coming
import io.github.vaspyyy.guildmark.village.live.grow.GrowthRules.Leaving
import io.github.vaspyyy.guildmark.village.live.links.VillageLinks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * A village's households coming and going (living-world/villages/design.md 5.6).
 *
 * - **Coming** ([arrive]): a household [GrowthRules] set coming arrives at dusk on its day (half past five to eight in the
 *   evening): by the record into the empty home it was set for (or another empty one, if someone has moved in since), each with a
 *   name of the land's (kin take the family's name of the household they are kin to), the first grown-up taking up the trade the
 *   village lacks most (a stranger seeking work; a citizen's if it lacks none), the second the next; those who left in hard times
 *   back as they were. Its fortunes note them (NEWCOMERS), its chronicle ("arrived") and the street (`Witness.newcomer`, or the
 *   household back). With a player within [GrowScenes.SEEN] blocks of the door they walk in from the end of the road with their
 *   cart ([GrowScenes.Arrival]); otherwise they are made at home out of sight, as soon as nobody is near it. Never past the bed
 *   count or the cap, whatever happened since the close set them coming.
 * - **Leaving** ([leave]): a household set leaving goes after nine in the morning of its day: off the record (away, not dead, its
 *   beds free), its fortunes noted (LEAVING) and the street told; with a player within [GrowScenes.NEAR] blocks of the door they
 *   pack their cart and go ([GrowScenes.Departure]), otherwise their bodies go as soon as nobody can see them.
 */
internal object Newcomers {
    /** The evening's hours (ticks since six in the morning) a household comes in: from half past five; by eight, by the record. */
    private const val DUSK = 11500L
    private const val DARK = 14000L

    /** The hour a household leaves, from nine in the morning. */
    private const val LEAVE_AT = 3000L

    /** Distances from the bell (blocks) the end of the road is looked for at, the nearest first. */
    private val ROAD = intArrayOf(50, 44, 56, 62)
    private val TURNS = doubleArrayOf(0.0, 0.25, -0.25, 0.5, -0.5)

    /** [c] set coming at the close of [today]. */
    fun coming(level: ServerLevel, rec: VillageRec, g: Grow, c: Coming, today: Long) {
        val who = when {
            c.back.isNotEmpty() -> "the household that left in hard times (${c.size})"
            c.kin >= 0 -> "kin of home ${c.kin} (${c.size})"
            else -> "a household of ${c.size}"
        }
        g.said(today, "$who to come at dusk on day ${c.day}, to home ${c.home} (${why(c)})")
    }

    /** [l] set leaving at the close of [today]. */
    fun leaving(level: ServerLevel, rec: VillageRec, g: Grow, l: Leaving, today: Long) {
        g.said(today, "the household of home ${l.home} is to leave on the morning of day ${l.day} (hard times)")
    }

    private fun why(c: Coming): String = when (c.cause) {
        GrowthRules.DEATH -> "in the place of the dead, the wait from day ${c.from}"
        GrowthRules.EMPTY -> "an empty home, the wait from day ${c.from}"
        GrowthRules.ZERO -> "nobody lives here"
        GrowthRules.RETURN -> "home again"
        else -> "called"
    }

    // ---- Each look ----

    /** The household on its way in at dusk, the one leaving in the morning. */
    fun step(level: ServerLevel, rec: VillageRec, places: VillagePlaces?, g: Grow) {
        val s = g.state
        val today = VillageBoards.day(level)
        val clock = Math.floorMod(level.overworldClockTime, 24000L)
        s.coming?.let { c ->
            if (!VillageGrowth.grows(rec)) {
                s.coming = null
                g.said(today, "the household on its way does not come: the village does not grow now")
                GrowthData.get(level).changed()
                return@let
            }
            if (today < c.day || today == c.day && clock < DUSK) return@let
            val late = today > c.day || clock >= DARK
            val home = places?.lodgings?.get(c.home)
            val door = home?.front
            val watched = door != null && level.getNearestPlayer(door.pos.x + 0.5, door.pos.y + 0.5, door.pos.z + 0.5, GrowScenes.SEEN, false) != null
            if (watched && !late && VillageAdopt.swapping(rec.key) == 0 && Furnish.loaded(level, home!!)) arrive(level, rec, places, g, c, scene = true)
            else if (late || !watched) arrive(level, rec, places, g, c, scene = false)
        }
        s.leaving?.let { l ->
            if (today < l.day || today == l.day && clock < LEAVE_AT) return@let
            leave(level, rec, places, g, l)
        }
    }

    // ---- Coming ----

    /**
     * [c] comes now: its people on the record in their home and, with [scene], walking in off the road (else made at home out
     * of sight). Who came, or an empty list if nobody could (no empty home, no room: the household does not come).
     */
    fun arrive(level: ServerLevel, rec: VillageRec, places: VillagePlaces?, g: Grow, c: Coming, scene: Boolean): List<Resident> {
        val today = VillageBoards.day(level)
        val residents = Residents.get(level)
        val bell = BlockPos.of(rec.key)
        val data = GrowthData.get(level)
        val s = g.state
        val living = residents.byCentre(bell).filter { it.alive }
        val lived = living.mapTo(HashSet()) { it.home }
        val homes = rec.homes.filter { !it.gone && it.beds.isNotEmpty() && it.id !in lived }
        val home = homes.firstOrNull { it.id == c.home } ?: homes.maxByOrNull { it.beds.size }
        val beds = rec.homes.filter { !it.gone }.sumOf { it.beds.size }
        val room = minOf(beds, VillageAdopt.maxFolk()) - living.size - maxOf(0, g.vanilla)
        if (home == null || room < 1) {
            s.coming = null
            g.said(today, if (home == null) "the household on its way finds no empty home, and does not come" else "no room left: the household on its way does not come")
            data.changed()
            return listOf()
        }
        val n = minOf(c.size, home.beds.size, room)
        val members = if (c.back.isNotEmpty()) back(level, g, c, home, n, living) else new(level, rec, places, c, home, n, living, today)
        if (members.isEmpty()) {
            s.coming = null
            data.changed()
            return listOf()
        }
        GrowthRules.arrived(s, today)
        val first = members.firstOrNull { it.role.adult } ?: members.first()
        val family = first.family
        val reason = when {
            c.back.isNotEmpty() -> "return"
            c.cause == GrowthRules.DEATH -> "replace"
            c.kin < 0 && members.any { it.role.adult && it.role != TownRole.CITIZEN } -> "trade"
            else -> "grow"
        }
        Fortunes.note(level, rec.key, Noted.NEWCOMERS, listOf(reason, family))
        Villages.note(level, rec.key, "arrived", family)
        if (c.back.isNotEmpty()) Witness.back(level, Household(first.id, rec.key, home.id, members.map { it.id }, today, "return"))
        else Witness.newcomer(level, first)
        val trades = members.filter { it.role.adult && it.role != TownRole.CITIZEN }.joinToString { it.role.id }
        g.said(today, "the $family household came to home ${home.id}: ${members.size} of them${if (trades.isNotEmpty()) ", for the trade ($trades)" else ""}")
        // Their bodies: off the road with a player to see it, else at home out of sight
        val walked = if (scene && places != null) walkIn(level, rec, places, members, home.id, c.back.isNotEmpty()) else listOf()
        for (r in members) if (r.id !in walked) g.make += Make(r.id, today)
        data.changed()
        return members
    }

    /** New people for [c] in [home], [n] of them: their names, trades and looks, on the record. */
    private fun new(level: ServerLevel, rec: VillageRec, places: VillagePlaces?, c: Coming, home: Home, n: Int, living: List<Resident>, today: Long): List<Resident> {
        val residents = Residents.get(level)
        val bell = BlockPos.of(rec.key)
        val style = VillageGrowth.style(level, rec)
        val kin = c.kin.takeIf { it >= 0 }?.let { k -> living.firstOrNull { it.home == k && it.role.adult }?.family }
        val family = kin ?: Names.family(style, c.seed)
        val adults = minOf(maxOf(1, c.adults), n)
        val children = minOf(c.children, n - adults)
        val beds = home.beds.map { BlockPos.of(it) }.toMutableList()
        val firstWoman = (c.seed ushr 17) and 1L == 0L
        val roles = ArrayList<TownRole>()
        val out = ArrayList<Resident>()
        for (k in 0 until adults) {
            val role = if (places != null) VillageGrowth.lacking(level, rec, places, roles) else TownRole.CITIZEN
            roles += role
            val id = residents.newId()
            val seed = GrowthRules.mix(c.seed, id.toLong())
            val woman = if (k == 0) firstWoman else !firstWoman
            val look = VillageGrowth.look(role, woman, seed)
            val bed = Births.freeBed(level, beds, out)?.also { beds.remove(it) }
            val r = Resident(id, null, bell, -1, Names.given(style, woman, seed), family, role, look, home.id, bed, false, Residents.traits(seed), true, -1L, today)
            residents.add(r)
            out += r
        }
        for (k in 0 until children) {
            val id = residents.newId()
            // Children of a few ages: some will be grown in days, the youngest in eight
            val since = today - Math.floorMod(GrowthRules.mix(c.seed, id + 7L), 6L)
            val seed = GrowthRules.mix(rec.key, id * 31L + since)
            val look = Math.floorMod(seed ushr 3, maxOf(1, TownRole.CHILD.common).toLong()).toInt()
            val woman = Names.woman(TownRole.CHILD, look, seed)
            val bed = Births.freeBed(level, beds, out)?.also { beds.remove(it) }
            val r = Resident(id, null, bell, -1, Names.given(style, woman, seed), family, TownRole.CHILD, look, home.id, bed, false, Residents.traits(seed), true, -1L, since)
            residents.add(r)
            out += r
        }
        return out
    }

    /** Those of [c] who left in hard times, home again in [home] ([n] of them): alive on the record as they were. */
    private fun back(level: ServerLevel, g: Grow, c: Coming, home: Home, n: Int, living: List<Resident>): List<Resident> {
        val residents = Residents.get(level)
        val beds = home.beds.map { BlockPos.of(it) }.toMutableList()
        val out = ArrayList<Resident>()
        for (r in c.back.mapNotNull { residents[it] }.filter { !it.alive && it.died < 0 && it.left >= 0 }.take(n)) {
            r.alive = true
            r.left = -1L
            r.home = home.id
            r.uuid = null
            r.bed = Births.freeBed(level, beds, living + out)?.also { beds.remove(it) }
            residents.add(r)
            g.gone.remove(r.id)
            out += r
        }
        return out
    }

    /** [members] walking in off the road to [homeId] with their cart: the ids of those set down there (the scene has them). */
    private fun walkIn(level: ServerLevel, rec: VillageRec, places: VillagePlaces, members: List<Resident>, homeId: Int, back: Boolean): List<Int> {
        val lodging = places.lodgings[homeId] ?: return listOf()
        val door = lodging.front ?: return listOf()
        val start = roadEnd(level, rec, door.pos, hidden = true) ?: return listOf()
        return GrowScenes.Arrival(level, rec.key, lodging, door, back).begin(rec, places, members, start)
    }

    /**
     * The end of [rec]'s road where a household is set down or makes for: out from the bell toward the first capital it is linked
     * to (else a way fixed by the village), some fifty blocks out, on loaded ground. With [hidden], only where nobody can see it
     * and nobody is within 32 blocks. Null if there is none.
     */
    fun roadEnd(level: ServerLevel, rec: VillageRec, door: BlockPos, hidden: Boolean): BlockPos? {
        val bell = BlockPos.of(rec.key)
        val link = VillageLinks.linked(level, rec.key).firstOrNull()
        val base = if (link != null) {
            Math.atan2((BlockPos.getZ(link.capital) - bell.z).toDouble(), (BlockPos.getX(link.capital) - bell.x).toDouble())
        } else {
            Math.floorMod(GrowthRules.mix(rec.key, 77L), 628L) / 100.0
        }
        for (d in ROAD) for (turn in TURNS) {
            val dist = maxOf(d, rec.radius + 12).toDouble()
            val a = base + turn
            val pos = VillageGrowth.ground(level, BlockPos.containing(bell.x + cos(a) * dist, bell.y.toDouble(), bell.z + sin(a) * dist)) ?: continue
            if (!hidden) return pos
            if (!level.isPositionEntityTicking(pos) || !Comings.unseen(level, pos)) continue
            if (level.players().any { it.distanceToSqr(Vec3.atCenterOf(pos)) < 32.0 * 32.0 }) continue
            return pos
        }
        return if (hidden) null else door.relative(net.minecraft.core.Direction.fromYRot(Math.toDegrees(base) - 90.0), 40)
    }

    /**
     * A household of new people set coming now (`village grow newcomer`), whatever the waits and the times: to the empty home
     * with most beds, as many as there is room for. With a player near its door they walk in now; else they are in by the
     * record. Who came, or why nobody did.
     */
    fun force(level: ServerLevel, rec: VillageRec, g: Grow): Pair<List<Resident>, String> {
        if (g.state.coming != null) return listOf<Resident>() to "a household is on its way already (to home ${g.state.coming?.home}, day ${g.state.coming?.day})"
        val today = VillageBoards.day(level)
        val p = VillageGrowth.picture(level, rec, g, today)
        val beds = p.homes.sumOf { it.beds }
        val room = minOf(beds, VillageAdopt.maxFolk()) - p.living.size - maxOf(0, p.vanilla)
        if (room < 1) return listOf<Resident>() to "no room: every bed is taken, or the village is at maxVillageFolk"
        val lived = p.living.mapTo(HashSet()) { it.home }
        val home = p.homes.filter { it.id !in lived && it.beds > 0 }.maxByOrNull { it.beds } ?: return listOf<Resident>() to "no empty home"
        val seed = GrowthRules.mix(rec.key, today * 0x9E37L + home.id + level.gameTime)
        val (adults, children) = GrowthRules.family(seed, minOf(room, home.beds))
        val c = Coming(today, home.id, GrowthRules.COMMAND, adults, children, -1, listOf(), today, seed)
        g.state.coming = c
        val places = Villages.places(level, rec.key)
        val door = places?.lodgings?.get(home.id)?.front
        val near = door != null && level.getNearestPlayer(door.pos.x + 0.5, door.pos.y + 0.5, door.pos.z + 0.5, GrowScenes.SEEN, false) != null
        val came = arrive(level, rec, places, g, c, scene = near)
        return came to "home ${home.id}${if (near) ", walking in off the road" else ", by the record"}"
    }

    // ---- Leaving ----

    /** [l] leaves now: off the record, its beds free, and its bodies away (packing up and off with a player to see it). */
    fun leave(level: ServerLevel, rec: VillageRec, places: VillagePlaces?, g: Grow, l: Leaving, forced: Boolean = false): List<Resident> {
        val today = VillageBoards.day(level)
        val residents = Residents.get(level)
        val data = GrowthData.get(level)
        GrowthRules.gone(g.state)
        val living = residents.byCentre(BlockPos.of(rec.key)).filter { it.alive }
        val members = living.filter { it.home == l.home }
        val households = living.filter { it.home >= 0 }.map { it.home }.distinct().size
        val stays = !forced && !VillageGrowth.grows(rec) || members.isEmpty() || members.any { it.role == TownRole.GUARD || io.github.vaspyyy.guildmark.tales.Tales.holds(it.id) } ||
            living.size - members.size < GrowthRules.KEEP_PEOPLE || households - 1 < GrowthRules.KEEP_HOUSEHOLDS
        if (stays) {
            g.said(today, "the household of home ${l.home} stays after all")
            data.changed()
            return listOf()
        }
        val ids = members.map { it.id }
        val first = members.firstOrNull { it.role.adult } ?: members.first()
        // The street hears of it while the records still name them
        Witness.gone(level, Household(first.id, rec.key, l.home, ids, today, "hard"))
        // Their bodies about now that nothing else has charge of (anyone held by a tale or a scene of another part just goes, unseen)
        val bodies = members.mapNotNull { r -> r.uuid?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive && it.resident == r.id } }
            .filter { it.director == null || it.director is io.github.vaspyyy.guildmark.townsfolk.scenes.Aside }
        for (r in members) {
            r.alive = false
            r.left = today
            r.bed?.let { Furnish.release(level, it) }
            r.bed = null
            r.uuid?.let { VillageGrowth.retire(level, g, it, r.id, today) }
            r.uuid = null
            g.make.removeIf { it.id == r.id }
            g.aging.remove(r.id)
            g.babies.removeIf { it.id == r.id }
            g.grown.removeIf { it.id == r.id }
        }
        residents.changed()
        g.gone.removeAll(ids)
        g.gone += ids
        Fortunes.note(level, rec.key, Noted.LEAVING, listOf("hard", first.family))
        g.said(today, "the ${first.family} household left home ${l.home} in hard times: ${members.size} of them")
        data.changed()
        if (places != null && bodies.isNotEmpty()) {
            val lodging = places.lodgings[l.home]
            val door = lodging?.front
            if (door != null && Furnish.loaded(level, lodging) &&
                level.getNearestPlayer(door.pos.x + 0.5, door.pos.y + 0.5, door.pos.z + 0.5, GrowScenes.NEAR, false) != null
            ) {
                val away = roadEnd(level, rec, door.pos, hidden = false) ?: door.pos
                GrowScenes.Departure(level, rec.key, lodging, door, away).begin(bodies)
            }
        }
        return members
    }

    /**
     * A household sent off now (`village grow leave`): the newest with no duty whose going leaves the village at least
     * [GrowthRules.KEEP_HOUSEHOLDS] households and [GrowthRules.KEEP_PEOPLE] people. Who left, or why nobody did.
     */
    fun forceLeave(level: ServerLevel, rec: VillageRec, g: Grow): Pair<List<Resident>, String> {
        val today = VillageBoards.day(level)
        val living = Residents.get(level).byCentre(BlockPos.of(rec.key)).filter { it.alive && it.home >= 0 }
        val households = living.groupBy { it.home }
        val pick = households.entries
            .filter { (_, m) -> m.any { it.role.adult } && m.none { it.role == TownRole.GUARD || io.github.vaspyyy.guildmark.tales.Tales.holds(it.id) } }
            .sortedByDescending { (_, m) -> m.maxOf { it.since } }
            .firstOrNull { (_, m) -> living.size - m.size >= GrowthRules.KEEP_PEOPLE && households.size - 1 >= GrowthRules.KEEP_HOUSEHOLDS }
            ?: return listOf<Resident>() to "nobody may leave: the village keeps at least ${GrowthRules.KEEP_HOUSEHOLDS} households and ${GrowthRules.KEEP_PEOPLE} people (and its guards)"
        val l = Leaving(today, pick.key)
        g.state.leaving = l
        g.state.left = today
        val gone = leave(level, rec, Villages.places(level, rec.key), g, l, forced = true)
        return gone to "home ${pick.key}"
    }
}
