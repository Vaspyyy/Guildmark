package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.fortune.Fortunes
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.lamps.LampKeepers
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.scenes.Neighbours
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * A household coming to a capital or leaving it: its [members] (resident ids), the [home] (lodging id) it comes to or
 * leaves, the market [day], and why: "replace" (someone died), "grow" (good times), "trade" (a trade the town lacks),
 * "hard" (leaving in hard times) or "return".
 */
class Household(val id: Int, val capital: Long, val home: Int, val members: List<Int>, val day: Long, val reason: String)

/**
 * Comings and goings (living-world/design-influence.md section 6): newcomers in through a gate for the dead and in
 * good times, and households leaving in hard times and coming back. The API between M6's parts (section 10).
 *
 * **The records.** Each capital's days are worked out one market day at a time, from the day after its record begins
 * (the first look at a capital with people only notes the day, so an older world starts from the next market day):
 * - each of the dead not yet seen to is sent someone new, [WAIT] to [WAIT] + [WAIT_SPAN] - 1 days after the death
 *   (fixed by the dead's id and the day they died; twice as long in hard times, [GOOD_WAIT] to [GOOD_WAIT] +
 *   [GOOD_SPAN] - 1 days in good): someone of the dead's trade to the dead's home, a new household to a home whose
 *   household died out, and for a citizen someone new in an empty house (else the dead's home). Children aren't
 *   replaced, nor the factions' own; someone a works sent for is sent for again ([hire]);
 * - in good times a household comes to grow the town every [GROW_GOOD] days (every [GROW_THRIVING] thriving), up to
 *   [GROWTH] times its planned people, to the empty house nearest the square (never a workshop or a manor);
 * - after [HARD_RUN] hard days running a household leaves, then one a [WEEK] at most: only a citizens' household with
 *   its children, never anyone with a trade or a duty or lent to a works, the newest first, and never while the town
 *   is at or under [FLOOR] of its planned people;
 * - one household comes in a day at most, at an hour of the day fixed by the household (between [ARRIVE_FROM] and
 *   [ARRIVE_TO]); in good times those away come back before anyone new, the same people to their old home if it's
 *   still empty. One leaves of a morning.
 * Everything is kept in [ComingsData] with its cause, for `/guildmark comings` ([ComingsCommand]) to say.
 *
 * **The bodies.** A household coming in with a player within [ComingsBodies.SEEN] blocks of a gate walks in at it
 * with its cart ([ComingsBodies]); with nobody near, its record says it's in and its bodies are made at home out of
 * sight when a player next comes ([bodies]). One leaving with a player near packs its cart at the door and goes out
 * at the nearest gate; with nobody near, its bodies are taken away unseen, and any of theirs that loads later is
 * dropped as it loads ([Residents.onJoin]). New bodies are made by [Townsfolk.spawn] and given their resident id at
 * once. The listeners hear of a household as its bodies reach their door (at once, with nobody there to see it).
 *
 * **The switches.** Until [READY] is set (M6 is wired in) nothing here runs and every game is as it was. With
 * townComings off (or the Living World), nobody comes or goes, households away are home again (their bodies made at
 * home out of sight), and those who came stay.
 */
object Comings {
    /**
     * Whether Part D3 is wired in. M6's parts were built side by side and are all switched on now; false would turn
     * them off again: nothing here runs (no record is kept, no body made, no command offered), so every game is as it
     * was before M6.
     */
    private const val READY = true

    /** Why a household comes or goes ([Household.reason]). */
    const val REPLACE = "replace"
    const val GROW = "grow"
    const val TRADE = "trade"
    const val HARD = "hard"
    const val RETURN = "return"

    /** Server ticks between looks at each capital's record, and at the bodies still to be made at home. */
    private const val LOOK = 100
    private const val BODIES = 40

    /** Market days someone new is waited for after a death in steady times (twice that in hard), and in good. */
    const val WAIT = 3
    const val WAIT_SPAN = 5
    const val GOOD_WAIT = 2
    const val GOOD_SPAN = 3

    /** Market days between households come to grow the town in good times, and in thriving; and how far it grows. */
    const val GROW_GOOD = 4
    const val GROW_THRIVING = 2
    const val GROWTH = 1.2

    /** Hard days running before a household leaves; the least market days between two leaving; the share of the plan nobody leaves at or under. */
    const val HARD_RUN = 5
    const val WEEK = 7
    const val FLOOR = 0.85

    /** The most market days worked out at once, catching up, and the most in one look for all capitals together (the rest wait for the next). */
    private const val CATCH_UP = 30L
    private const val DAYS = 20

    /** The hours of the clock a household comes in between, and leaves between. */
    const val ARRIVE_FROM = 8
    const val ARRIVE_TO = 17
    const val LEAVE_FROM = 7
    const val LEAVE_TO = 12

    /** Bodies made at home at once in each capital, each look. */
    private const val MOST_BODIES = 2

    /** How near (with a clear line) a player may be and still not see a body made at home. */
    private const val SIGHT = 96.0

    /** The kind of lodging a household lives in. */
    private const val HOUSE = "HOUSE"

    /** Whether households come and go (townComings, with Living World). */
    fun enabled(): Boolean = READY && Days.enabled() && setting { GuildmarkConfig.TOWN_COMINGS.get() }

    /** Whether Part D3 is wired in at all (while it is and households can't come and go, those away are brought home). */
    internal fun ready(): Boolean = READY

    /** Households due in at the capital whose heart is [capital]. */
    fun due(level: ServerLevel, capital: Long): List<Household> {
        if (!enabled()) return listOf()
        val town = ComingsData.get(level).find(capital) ?: return listOf()
        val times = Fortunes.times(level, capital)
        return queue(town, times).map { Household(it.id, capital, it.home, listOf(), dueDay(it, times), it.reason) }
    }

    /**
     * Households that came to [capital] in the last [days] market days, those come home ("return") among them, each
     * dated by the clock's market day (see [dated]).
     */
    fun arrived(level: ServerLevel, capital: Long, days: Int): List<Household> {
        if (!enabled() || days <= 0) return listOf()
        val town = ComingsData.get(level).find(capital) ?: return listOf()
        val today = dated(level)
        return town.log.filter { it.day > today - days }.map { Household(it.id, capital, it.home, it.members, it.day, it.reason) }
    }

    /** [capital]'s households away in hard times. */
    fun away(level: ServerLevel, capital: Long): List<Household> {
        if (!enabled()) return listOf()
        val town = ComingsData.get(level).find(capital) ?: return listOf()
        return town.away.map { Household(it.id, capital, it.home, it.members, it.day, it.reason) }
    }

    /** [capital]'s homes (lodging ids) left and not yet taken. */
    fun empty(level: ServerLevel, capital: Long): List<Int> {
        if (!enabled()) return listOf()
        val town = ComingsData.get(level).find(capital) ?: return listOf()
        if (town.away.isEmpty()) return listOf()
        val lived = Residents.get(level).of(BlockPos.of(capital)).filter { it.alive }.mapTo(HashSet()) { it.home }
        return town.away.map { it.home }.filter { it >= 0 && it !in lived }.distinct()
    }

    /**
     * Sends for a [role] to [capital] for works [works]: their resident id once they're in, else -1. Asked again it's
     * the same newcomer (or -1 while they're still on their way); if they've died since, someone new is sent for, once:
     * by the day's mourning ([mourn]) if it hasn't seen to them yet.
     */
    fun hire(level: ServerLevel, capital: Long, role: TownRole, works: Int): Int {
        if (!enabled()) return -1
        val data = ComingsData.get(level)
        val had = data.hired[works]
        if (had != null && had >= 0) {
            val r = Residents.get(level)[had]
            if (r != null && r.alive) return had
            if (r != null && r.died >= 0 && data.find(capital)?.seen?.contains(r.id) == false) return -1
        }
        val town = data.town(capital)
        if (town.due.none { it.works == works }) {
            val today = today(level, capital) ?: return -1
            town.due += ComingsData.Due(data.newId(), TRADE, listOf(role), listOf(-1), -1, today, 0, listOf(), works)
        }
        data.hired[works] = -1
        data.changed()
        return -1
    }

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Hears households arrive, leave and come back, and the clock set back. */
    interface Listener {
        fun arrived(level: ServerLevel, h: Household) {}
        fun left(level: ServerLevel, h: Household) {}
        fun returned(level: ServerLevel, h: Household) {}

        /** The clock was set back [days] market days ([setBack]): any market day it keeps goes back with it. */
        fun back(level: ServerLevel, days: Long) {}
    }

    // ---- Setup ----

    /**
     * At setup (once [READY]): the days worked out and the bodies seen to each tick, the bodies of those gone dropped
     * as they load, the household's half of the neighbours' hugs, and `/guildmark comings`. Nothing at all before.
     */
    fun register() {
        if (!READY) return
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { Residents.onJoin(it) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> ComingsBodies.forget() }
        Neighbours.embrace = Neighbours.Embrace { level, member, neighbour -> ComingsBodies.embrace(level, member, neighbour) }
        ComingsCommand.init()
    }

    private fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val on = enabled()
        ComingsBodies.tick(level, on)
        val t = server.tickCount
        if (t % BODIES == 7) bodies(level)
        if (t % LOOK != 23) return
        setBack(level)
        if (on) records(level) else restore(level)
    }

    /**
     * Each look, before the days: whether the clock has been set back since the last (`/time set` to an earlier time),
     * by the hour it was last looked at (kept with the comings' data, so a restart in between sees it too). If so, every
     * capital's comings and goings go back the market days it went ([back]) once, whether the markets are on or off, so
     * the clock's next day is theirs and nobody due waits for it to come round, and so do the days others keep by
     * them ([Listener.back]: the streets' boards, say) and the residents' own (the world's days, see [Residents.back]).
     * With the markets on, a market the clock has gone back past takes itself back first (Markets.run, with its
     * fortunes and works), and the comings follow at the look after; a clock moved on only notes its hour. Days lived on
     * ahead by a market run stay as far ahead.
     */
    private fun setBack(level: ServerLevel) {
        val data = ComingsData.get(level)
        val now = Markets.hourOf(level)
        val was = data.looked
        if (was == now) return
        // The markets' own days go back first (the day a market is on is what the comings' days are read against)
        if (was > now && Markets.enabled() && Markets.get(level).all().any { it.market.hour > now }) return
        data.looked = now
        data.changed()
        if (was < now) return
        val days = Conditions(was).day - Conditions(now).day
        if (days > 0L) {
            for (capital in data.all().keys.toList()) back(level, capital, days)
            for (l in listeners.toList()) {
                try {
                    l.back(level, days)
                } catch (e: RuntimeException) {
                    Guildmark.LOGGER.warn("Something keeping market days failed to go back with the clock", e)
                }
            }
        }
        // The residents' days are the world's own, from six in the morning where the markets' are from two
        Residents.get(level).back(Math.floorDiv(was, 24L) - Math.floorDiv(now, 24L))
    }

    // ---- The days ----

    /** One capital as its days are worked out: its record, its places and its people. */
    internal class Ctx(val level: ServerLevel, val data: ComingsData, val key: Long, val town: ComingsData.Town, val places: CityPlaces) {
        val center: BlockPos get() = places.capital.center
        val residents: Residents = Residents.get(level)

        /** The capital's people, the living and the dead (looked up again after a change). */
        var people: List<Resident> = residents.of(center)
            private set

        fun refresh() {
            people = residents.of(center)
        }

        /** How many people the plan set down. */
        val planned: Int get() = places.census.residents.size

        /** The homes of the plan's people not met yet: someone will live there. */
        val unmet: Set<Int> by lazy {
            val homes = HashSet<Int>()
            val hs = places.housing.homes
            for (i in hs.indices) if (residents.planned(center, i) == null) homes.add(hs[i].lodge)
            homes
        }

        /** The villagers' homes. */
        val villagers: Set<Int> by lazy { places.census.villagers.mapNotNullTo(HashSet()) { places.census.lodgeAt(it.x, it.z)?.id } }
    }

    /**
     * [places]'s capital's record and places for its days to be worked out: its record begun (with nothing in it) if it
     * has none, or with [begin] false only looked at (a blank one, kept nowhere, standing in for it).
     */
    internal fun ctx(level: ServerLevel, places: CityPlaces, begin: Boolean = true): Ctx {
        val data = ComingsData.get(level)
        val key = places.capital.center.asLong()
        return Ctx(level, data, key, if (begin) data.town(key) else data.find(key) ?: ComingsData.Town(), places)
    }

    /** The market's hour in [entry]'s capital (the clock's, with the markets switched off). */
    private fun hour(level: ServerLevel, entry: Markets.Entry): Long = if (Markets.enabled()) entry.market.hour else Markets.hourOf(level)

    /** The market day it is in [capital] for its comings and goings, if it has a market (see [day]). */
    internal fun today(level: ServerLevel, capital: Long): Long? = Markets.get(level)[BlockPos.of(capital)]?.let { day(level, it) }

    /**
     * The market day it is in [entry]'s capital for its comings and goings: the market's, or the last its fortunes
     * closed if that's later, and never one before the last day they worked out. A market run on ahead (`market run`)
     * has lived the days it closed, so the comings go by them too, and keep to them with the fortunes switched off
     * since; with the clock set back their days go back with it ([back]).
     */
    private fun day(level: ServerLevel, entry: Markets.Entry): Long {
        val day = Conditions(hour(level, entry)).day
        val lived = if (Fortunes.enabled()) maxOf(day, Fortunes.today(level, entry.market.capital)) else day
        return maxOf(lived, ComingsData.get(level).find(entry.market.capital)?.closed ?: ComingsData.NEVER)
    }

    /**
     * The clock set back (from [setBack]): [capital]'s comings and goings go back [days] market days with it, the day they
     * last worked out and every day they date by (the households due, away and come in; the last to leave, to grow the
     * town and to come; the day one is to leave), so the clock's next day is their next and nobody due waits the longer
     * (the days the dead died on go back with the residents' own, see [Residents.back]). Days lived on ahead by a market
     * run stay ahead.
     */
    internal fun back(level: ServerLevel, capital: Long, days: Long) {
        if (!READY || days == 0L) return
        val data = ComingsData.get(level)
        val town = data.find(capital) ?: return
        fun earlier(d: Long): Long = if (d == ComingsData.NEVER) d else d - days
        town.closed = earlier(town.closed)
        town.grown = earlier(town.grown)
        town.came = earlier(town.came)
        town.leaving = earlier(town.leaving)
        // Nothing goes before the clock's first day, where -1 is "none"
        if (town.left >= 0) town.left = maxOf(0L, town.left - days)
        town.due.replaceAll { ComingsData.Due(it.id, it.reason, it.roles, it.planned, it.home, it.day - days, it.wait, it.dead, it.works) }
        town.away.replaceAll { ComingsData.Moved(it.id, it.members, it.home, it.day - days, it.reason, it.why) }
        town.log.replaceAll { ComingsData.Moved(it.id, it.members, it.home, it.day - days, it.reason, it.why) }
        data.changed()
    }

    /**
     * The clock's market day, which a household that came, left or came back is dated by (as the town's talk counts a
     * household's days): the comings' own day ([today]) but for a market run on ahead, whose days the clock hasn't come
     * round to yet.
     */
    internal fun dated(level: ServerLevel): Long = Conditions(Markets.hourOf(level)).day

    /** The market's hour in [capital] as the clock reads it (0 to 23), if it has a market. */
    internal fun clock(level: ServerLevel, capital: Long): Int? = Markets.get(level)[BlockPos.of(capital)]?.let { Conditions(hour(level, it)).clock }

    /** Each capital with a market: its days worked out up to today ([DAYS] at most this look, for them all), and what's due in the day so far. */
    private fun records(level: ServerLevel) {
        val data = ComingsData.get(level)
        var days = DAYS
        for (entry in Markets.get(level).all()) {
            val key = entry.market.capital
            val today = day(level, entry)
            val town = data.find(key)
            if (town == null) {
                if (Residents.get(level).of(entry.capital).isEmpty()) continue
                // The day the record begins is only noted: the next market day is the first worked out
                data.town(key).closed = today
                data.changed()
                continue
            }
            val piece = CaravanRoutes.capital(level, key, soon = true) ?: continue
            val places = CityPlaces.of(level, piece) ?: continue
            days -= work(Ctx(level, data, key, town, places), today, Conditions(hour(level, entry)).clock, days)
        }
    }

    /**
     * [c]'s market days worked out up to [today], [most] of them at most, then what's due in the day so far; with days
     * still to work out, the rest wait for the next look, and what's due today with them. The days worked out.
     */
    private fun work(c: Ctx, today: Long, clock: Int, most: Int): Int {
        val town = c.town
        var worked = 0
        if (town.closed == ComingsData.NEVER) {
            town.closed = today
            c.data.changed()
        } else if (today > town.closed) {
            var d = maxOf(town.closed + 1, today - CATCH_UP + 1)
            while (d <= today && worked < most) {
                day(c, d)
                d++
                worked++
            }
            if (d - 1 > town.closed) {
                town.closed = d - 1
                c.data.changed()
            }
            if (town.closed < today) return worked
        }
        during(c, today, clock)
        return worked
    }

    /** Market day [d] in [c]'s capital: the dead seen to, the town grown in good times, a household to leave in hard. */
    private fun day(c: Ctx, d: Long) {
        mourn(c, d)
        grow(c, d)
        hard(c, d)
        c.town.log.removeIf { it.day <= minOf(d, dated(c.level)) - ComingsData.LOG }
        c.data.changed()
    }

    /**
     * Someone new sent for each of the dead not yet seen to: someone of the dead's trade to the dead's home, a new
     * household to a home whose household died out, a citizen's place to an empty house (else the dead's home).
     */
    private fun mourn(c: Ctx, d: Long) {
        val town = c.town
        val dead = c.people.filter { !it.alive && it.died >= 0 && it.id !in town.seen }
        if (dead.isEmpty()) return
        for (r in dead) town.seen.add(r.id)
        // Someone a works sent for is sent for again by it
        val hiredBy = HashMap<Int, Int>()
        for ((works, id) in c.data.hired) if (id >= 0) hiredBy[id] = works
        val sent = HashSet<Int>()
        for (r in dead) {
            val works = hiredBy[r.id] ?: continue
            sent.add(r.id)
            c.data.hired[works] = -1
            if (town.due.none { it.works == works }) town.due += ComingsData.Due(c.data.newId(), TRADE, listOf(r.role), listOf(-1), -1, d, 0, listOf(r.id), works)
        }
        // The factions keep their own, and a child's place isn't filled
        val rest = dead.filter { it.id !in sent && Faction.of(it.role) == null }
        for ((home, gone) in rest.groupBy { it.home }) {
            val adults = gone.filter { it.role.adult }.sortedWith(compareBy({ if (it.role == TownRole.CITIZEN) 1 else 0 }, { it.id }))
            if (adults.isEmpty()) continue
            if (c.places.lodgings[home] != null && diedOut(c, home)) {
                // A new household takes the home: two grown-ups at most, the trades first, and children for the children lost
                val first = adults.take(2)
                val members = first + gone.filter { !it.role.adult }.take(2)
                town.due += ComingsData.Due(
                    c.data.newId(), REPLACE, members.map { it.role }, members.map { it.planned }, home, first[0].died, wait(first[0]),
                    members.map { it.id }, -1,
                )
                for (r in adults.drop(2)) town.due += single(c, r, home)
            } else for (r in adults) {
                // Into the bed they left where the home goes with the work (the barracks, the tavern, a farm); a house
                // their family still lives in is left to them, and someone new finds a house of their own
                val withWork = c.places.lodgings[home]?.kind?.let { it != HOUSE } == true
                town.due += single(c, r, if (withWork) home else -1)
            }
        }
    }

    /** Someone new for [r] alone, to [home] (-1: an empty house as they come). */
    private fun single(c: Ctx, r: Resident, home: Int): ComingsData.Due =
        ComingsData.Due(c.data.newId(), REPLACE, listOf(r.role), listOf(r.planned), home, r.died, wait(r), listOf(r.id), -1)

    /** Whether nobody lives in [home] any more, nor will: no one living or away there, none of the plan's still to meet, no household due to it. */
    private fun diedOut(c: Ctx, home: Int): Boolean =
        home !in c.unmet && c.people.none { it.home == home && (it.alive || away(it)) } && c.town.due.none { it.home == home }

    /** Whether [r] is away in hard times. */
    private fun away(r: Resident): Boolean = !r.alive && r.died < 0 && r.left >= 0

    /** Market days someone new is waited for after [r]'s death, in steady times: fixed by who they were and the day they died. */
    private fun wait(r: Resident): Int = WAIT + Math.floorMod(mix(r.id.toLong(), r.died), WAIT_SPAN.toLong()).toInt()

    /** The market day [due] comes in on, the times being [times]: a death's newcomer later in hard times and sooner in good; the rest at once. */
    internal fun dueDay(due: ComingsData.Due, times: Times): Long {
        if (due.reason != REPLACE) return due.day
        val first = due.dead.firstOrNull() ?: due.id
        return when (times) {
            Times.HARD -> due.day + 2L * due.wait
            Times.GOOD, Times.THRIVING -> due.day + GOOD_WAIT + Math.floorMod(mix(first.toLong() * 7 + 1, due.day), GOOD_SPAN.toLong())
            else -> due.day + due.wait
        }
    }

    /** [town]'s households due, in the order they come: those a works sent for, then the dead's, then those come to grow it; soonest first. */
    internal fun queue(town: ComingsData.Town, times: Times): List<ComingsData.Due> =
        town.due.sortedWith(compareBy({ rank(it.reason) }, { dueDay(it, times) }, { it.id }))

    private fun rank(reason: String): Int = when (reason) {
        TRADE -> 0
        REPLACE -> 1
        else -> 2
    }

    /** In good times a household comes to grow the town every few days, up to [GROWTH] times its planned people. */
    private fun grow(c: Ctx, d: Long) {
        val town = c.town
        val every = when (times(c.level, c.key, d)) {
            Times.GOOD -> GROW_GOOD
            Times.THRIVING -> GROW_THRIVING
            else -> return
        }
        if (town.grown != ComingsData.NEVER && d - town.grown < every) return
        if (town.due.any { it.reason == GROW }) return
        if (town.away.isNotEmpty()) return note(c, "Good times, but those who left come back before anyone new comes.")
        val cap = Math.floor(GROWTH * c.planned).toInt()
        val now = living(c) + town.due.sumOf { it.roles.size }
        if (now >= cap) return note(c, "Good times, but the town is as big as it grows: $now people, and it grows to $cap at most.")
        if (emptyHouses(c).isEmpty()) return note(c, "Good times, but there's no empty house for a new household.")
        town.grown = d
        val roles = family(mix(c.key, d), cap - now)
        town.due += ComingsData.Due(c.data.newId(), GROW, roles, roles.map { -1 }, -1, d, 0, listOf(), -1)
    }

    /** A household of up to [room] people: one or two grown-ups (two, mostly) and up to two children, from [seed]. */
    internal fun family(seed: Long, room: Int): List<TownRole> {
        val roll = Math.floorMod(seed, 100L).toInt()
        var adults = if (roll < 70) 2 else 1
        var children = when (Math.floorMod(seed ushr 8, 100L).toInt()) {
            in 0..34 -> 0
            in 35..74 -> 1
            else -> 2
        }
        while (adults + children > room && children > 0) children--
        while (adults + children > room && adults > 1) adults--
        return List(adults) { TownRole.CITIZEN } + List(children) { TownRole.CHILD }
    }

    /** After [HARD_RUN] hard days running a household is to leave, then one a [WEEK] at most. */
    private fun hard(c: Ctx, d: Long) {
        if (hardRun(c.level, c.key, d) < HARD_RUN) return
        val town = c.town
        if (town.left >= 0 && d - town.left < WEEK) return
        if (town.leaving != ComingsData.NEVER && town.leaving >= d) return
        town.leaving = d
    }

    /** How many hard days [capital] has had running, up to market day [d] (as its fortunes closed them). */
    internal fun hardRun(level: ServerLevel, capital: Long, d: Long): Int {
        var n = 0
        var want = d
        for (day in Fortunes.days(level, capital).asReversed()) {
            if (day.day > want) continue
            if (day.day != want || day.times != Times.HARD) break
            n++
            want--
        }
        return n
    }

    /** The times [capital] had on market day [d] (as its fortunes closed it), else its times now. */
    private fun times(level: ServerLevel, capital: Long, d: Long): Times =
        Fortunes.days(level, capital).lastOrNull { it.day <= d }?.takeIf { it.day == d }?.times ?: Fortunes.times(level, capital)

    /** What comes of today in [c]'s capital so far: a household leaving of a morning, and one coming in at its hour. */
    private fun during(c: Ctx, today: Long, clock: Int) {
        val town = c.town
        if (town.leaving != ComingsData.NEVER && town.leaving <= today && (town.leaving < today || clock >= leaveHour(c.key, today))) {
            town.leaving = ComingsData.NEVER
            c.data.changed()
            leave(c, today, force = false, near = null)
        }
        if (town.came == today || clock < ARRIVE_FROM || clock > ARRIVE_TO) return
        val times = Fortunes.times(c.level, c.key)
        if (times == Times.GOOD || times == Times.THRIVING) {
            val away = town.away.firstOrNull()
            if (away != null) {
                if (clock >= arriveHour(away.id)) comeBack(c, away, today, gate = null, force = false)
                return
            }
        }
        for (due in queue(town, times)) {
            if (dueDay(due, times) > today) continue
            if (clock < arriveHour(due.id)) return
            if (arrive(c, due, today, gate = null, force = false, home = null) != null) return
        }
    }

    /** The hour (of the clock) the household [id] comes in at, its day come. */
    internal fun arriveHour(id: Int): Int = ARRIVE_FROM + Math.floorMod(mix(id.toLong(), 0x51L), (ARRIVE_TO - ARRIVE_FROM + 1).toLong()).toInt()

    /** The hour a household leaves [capital] at on market day [day]. */
    internal fun leaveHour(capital: Long, day: Long): Int = LEAVE_FROM + Math.floorMod(mix(capital, day * 31 + 7), (LEAVE_TO - LEAVE_FROM + 1).toLong()).toInt()

    /** Notes [why] nothing came of something that might have (for `/guildmark comings`). */
    private fun note(c: Ctx, why: String) {
        if (c.town.why == why) return
        c.town.why = why
        c.data.changed()
    }

    // ---- The town's people and homes ----

    /** [c]'s people as its market counts them: the plan's still living (or not met yet) and everyone taken in since. */
    internal fun living(c: Ctx): Int {
        var n = 0
        for (i in c.places.census.residents.indices) if (c.residents.planned(c.center, i)?.alive != false) n++
        n += c.people.count { it.planned < 0 && it.alive }
        return n
    }

    /** Whether nobody lives in [home] now, nor will (those away don't count: their home may be taken). */
    internal fun free(c: Ctx, home: Int): Boolean =
        home !in c.unmet && home !in c.villagers && c.people.none { it.alive && it.home == home } && c.town.due.none { it.home == home }

    /** [c]'s empty houses (nobody living in them, nor due to), nearest [to] first (the square's middle unless told). */
    internal fun emptyHouses(c: Ctx, to: BlockPos = c.places.market): List<CityPlaces.Lodging> {
        val lived = HashSet<Int>()
        for (r in c.people) if (r.alive) lived.add(r.home)
        for (d in c.town.due) if (d.home >= 0) lived.add(d.home)
        return c.places.lodgings.values
            .filter { it.kind == HOUSE && it.front != null && it.id !in lived && it.id !in c.unmet && it.id !in c.villagers }
            .sortedBy { it.box.center.distSqr(to) }
    }

    /** The house nearest [to], lived in or not. */
    private fun nearestHouse(c: Ctx, to: BlockPos): CityPlaces.Lodging? =
        c.places.lodgings.values.filter { it.kind == HOUSE && it.front != null }.minByOrNull { it.box.center.distSqr(to) }

    /** The home [due] comes to: the one it's for, else an empty house (nearest the square, the works or the work), else the dead's home or the house nearest. */
    internal fun homeFor(c: Ctx, due: ComingsData.Due, near: BlockPos? = null): CityPlaces.Lodging? {
        c.places.lodgings[due.home]?.let { return it }
        return when (due.reason) {
            GROW -> emptyHouses(c, near ?: c.places.market).firstOrNull()
            TRADE -> {
                val at = near ?: Works.get(c.level, due.works)?.site?.origin ?: c.places.market
                emptyHouses(c, at).firstOrNull() ?: nearestHouse(c, at)
            }
            else -> {
                val dead = due.dead.firstNotNullOfOrNull { c.residents[it] }
                val at = near ?: deadWork(c, due) ?: c.places.market
                emptyHouses(c, at).firstOrNull() ?: dead?.home?.let { c.places.lodgings[it] } ?: nearestHouse(c, at)
            }
        }
    }

    /** Where the plan had the first of [due]'s dead at work, if it had them anywhere. */
    private fun deadWork(c: Ctx, due: ComingsData.Due): BlockPos? {
        if (due.roles.firstOrNull() == TownRole.CITIZEN) return null
        val p = due.planned.firstOrNull { it >= 0 }?.let { c.places.census.residents.getOrNull(it) } ?: return null
        val at = p.post ?: p.work ?: p.at
        return c.places.capital.world(at.x, at.y, at.z)
    }

    // ---- Coming in ----

    /**
     * [due] comes in to [home] (else the home it's for or one found), now: its people taken in, their bodies through
     * [gate] (else whichever gate a player is near; with none, at home out of sight), set down after [delay] ticks.
     * [force]: a command's, which doesn't count as the day's household ([remark] goes in the record with its cause). The
     * household, or null if there was no home for it.
     */
    internal fun arrive(
        c: Ctx, due: ComingsData.Due, today: Long, gate: Dir?, force: Boolean, home: CityPlaces.Lodging?, delay: Int = 0, remark: String? = null,
    ): Household? {
        val lodging = home ?: homeFor(c, due) ?: run {
            note(c, "${what(due).replaceFirstChar { it.uppercase() }} waits: there's no house for them to take.")
            return null
        }
        val town = c.town
        town.due.remove(due)
        if (!force) town.came = today
        val made = make(c, due, lodging)
        val ids = made.map { it.id }
        if (due.works >= 0) c.data.hired[due.works] = ids.firstOrNull() ?: -1
        town.log += ComingsData.Moved(due.id, ids, lodging.id, dated(c.level), due.reason, cause(c, due) + if (remark != null) " ($remark)" else "")
        town.why = ""
        c.data.changed()
        c.residents.changed()
        c.refresh()
        for (r in made) newcomer(c.level, r)
        val h = Household(due.id, c.key, lodging.id, ids, dated(c.level), due.reason)
        show(c, h, ComingsBodies.Kind.ARRIVE, gate, delay)
        return h
    }

    /**
     * The town hears of someone new ([Witness.newcomer], as for anyone taken in as they're met), each grown-up of a
     * household. It hears only of those with no place in the plan (the plan's own people being the town's from the
     * first), so someone who takes the plan's place of one who died is told of as one with none; the record keeps their
     * place, which the talk of a household new to the street goes by.
     */
    private fun newcomer(level: ServerLevel, r: Resident) {
        if (!r.role.adult) return
        val seen = if (r.planned < 0) r else Resident(
            r.id, r.uuid, r.capital, -1, r.given, r.family, r.role, r.look, r.home, r.bed, r.furnished, r.traits, r.alive, r.died, r.since, r.left,
        )
        Witness.newcomer(level, seen)
    }

    /** [due] in a few words, for the record ("a baker for Alda Merrow", "a household come to grow the town"). */
    internal fun what(due: ComingsData.Due): String {
        val who = if (due.roles.size == 1) "a ${due.roles[0].id}" else "a household of ${due.roles.size}"
        return when (due.reason) {
            GROW -> "$who come to grow the town"
            TRADE -> "$who sent for by works #${due.works}"
            else -> "$who for the dead"
        }
    }

    /** What brought [due], in words, with the dead's names. */
    private fun cause(c: Ctx, due: ComingsData.Due): String = when (due.reason) {
        GROW -> "came to grow the town in good times"
        TRADE -> "sent for by works #${due.works}" + if (due.dead.isNotEmpty()) " in the place of ${names(c, due.dead)}" else ""
        else -> "came for ${names(c, due.dead)}, who died"
    }

    /** [ids]'s names, "A, B and C". */
    internal fun names(c: Ctx, ids: List<Int>): String {
        val names = ids.mapNotNull { c.residents[it]?.name }
        return when (names.size) {
            0 -> "nobody known"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    /** [due]'s people taken in, to [home]: named in the capital's style, a household sharing its name, with the beds free there. */
    private fun make(c: Ctx, due: ComingsData.Due, home: CityPlaces.Lodging): List<Resident> {
        val style = c.places.capital.style
        val beds = freeBeds(c, home)
        val made = ArrayList<Resident>()
        var family: String? = null
        var first: Boolean? = null
        // The grown-ups first, so they have the beds
        for ((i, role) in due.roles.withIndex().sortedBy { if (it.value.adult) 0 else 1 }.map { it.index to it.value }) {
            val id = c.residents.newId()
            val seed = c.center.asLong() * 31 + id
            // A couple: one of each, where the looks allow
            val look = look(role, seed, if (role.adult && first != null) !first!! else null)
            val woman = Names.woman(role, look, seed)
            if (role.adult && first == null) first = woman
            val name = family ?: Names.family(style, seed).also { family = it }
            val slot = due.planned.getOrElse(i) { -1 }.takeIf { it >= 0 && c.residents.planned(c.center, it)?.alive != true } ?: -1
            val bed = beds.removeFirstOrNull()
            // A bed left by someone away is theirs no longer
            if (bed != null) for (o in c.people) if (!o.alive && o.bed == bed) {
                o.bed = null
                o.furnished = false
            }
            val r = Resident(
                id, null, c.center, slot, Names.given(style, woman, seed), name, role, look, home.id, bed, false,
                Residents.traits(seed), true, -1L, VillageBoards.day(c.level),
            )
            c.residents.add(r)
            post(c, r, due)?.let { c.town.posts[r.id] = it }
            made += r
        }
        return made
    }

    /** A look for someone new of [role] (one with a [woman]'s voice if that's wanted and there is one), from [seed]. */
    private fun look(role: TownRole, seed: Long, woman: Boolean?): Int {
        val n = maxOf(1, role.common)
        val start = Math.floorMod(mix(seed, 0x10L), n.toLong()).toInt()
        if (woman == null) return start
        for (k in 0 until n) {
            val l = (start + k) % n
            if (Names.woman(role, l, seed) == woman) return l
        }
        return start
    }

    /** The beds the plan made up in [home] that nobody living has, and those left by anyone away from it. */
    private fun freeBeds(c: Ctx, home: CityPlaces.Lodging): ArrayDeque<BlockPos> {
        val used = HashSet<BlockPos>()
        for (r in c.people) if (r.alive) r.bed?.let { used.add(it) }
        val free = ArrayDeque<BlockPos>()
        for (b in home.beds) if (b !in used) free.add(b)
        for (r in c.people) if (away(r) && r.home == home.id) r.bed?.let { if (it !in used && it !in free) free.add(it) }
        return free
    }

    /** Where [r] works, coming as [due]: at the works that sent for them, else where the plan had the one whose place they take. */
    private fun post(c: Ctx, r: Resident, due: ComingsData.Due): ComingsData.Post? {
        if (due.works >= 0) worksPost(c, r, due.works)?.let { return it }
        val planned = c.places.census.residents.getOrNull(r.planned) ?: return null
        val capital = c.places.capital
        return ComingsData.Post(
            r.id, planned.work?.let { capital.world(it.x, it.y, it.z) }, planned.post?.let { capital.world(it.x, it.y, it.z) },
            planned.facing?.let { capital.direction(it) }, null,
        )
    }

    /** Where someone sent for by works [works] works: behind its stall's display facing the buyers, or at its oven. */
    private fun worksPost(c: Ctx, r: Resident, works: Int): ComingsData.Post? {
        val w = Works.get(c.level, works) ?: return null
        val owner = w.owner
        if (w.kind == WorksKind.STALL && owner != null) {
            val stall = OwnStalls.get(c.level).find(c.key)?.pitches?.get(owner)
            if (stall != null) return ComingsData.Post(r.id, null, stall.at.relative(stall.front.opposite), stall.front, null)
        }
        val site = w.site ?: return null
        return ComingsData.Post(r.id, site.origin, null, null, null)
    }

    // ---- Leaving ----

    /** A household that may leave [c]'s capital: its home and its people. */
    internal class Leaver(val home: CityPlaces.Lodging, val members: List<Resident>) {
        /** When the newest of them came. */
        val came: Long get() = members.maxOf { it.since }
        val newest: Int get() = members.maxOf { it.id }
    }

    /**
     * The households that may leave [c]'s capital: a citizens' household with its children, none of them with a trade
     * or a duty (carting, milling, crying the news, keeping the lamps), lent to a works or sent for by one, all of them
     * met, and none in the middle of coming or going. The newest first.
     */
    internal fun leavers(c: Ctx): List<Leaver> {
        val crew = Hands.of(c.level, c.places)
        val hired = c.data.hired.values.toSet()
        val lamps = LampKeepers.get(c.level)[c.center]?.toSet() ?: setOf()
        val out = ArrayList<Leaver>()
        for ((home, members) in c.people.filter { it.alive && it.home >= 0 }.groupBy { it.home }) {
            val lodging = c.places.lodgings[home] ?: continue
            if (home in c.unmet || members.none { it.role == TownRole.CITIZEN }) continue
            if (members.any { it.role != TownRole.CITIZEN && it.role != TownRole.CHILD }) continue
            if (members.any { it.id in crew.carters || it.id == crew.crier || it.id in crew.millers || it.id in crew.labourers || it.id in hired || it.id in lamps }) continue
            if (members.any { ComingsBodies.busy(it.id) || body(c.level, it)?.let { p -> Labour.lentNow(p) } == true }) continue
            if (Tales.stays(c.level, c.key, home, members)) continue
            out += Leaver(lodging, members)
        }
        return out.sortedWith(compareByDescending<Leaver> { it.came }.thenByDescending { it.newest })
    }

    /**
     * A household leaves [c]'s capital now (the newest that may; with [near], the one living nearest it), unless the
     * town is at or under [FLOOR] of its planned people ([force]: a command's, whatever the town). [why] is what the record
     * says of the cause where it isn't the usual (a tale sending one: `tale <template> <id>`). The household, or null
     * with why not noted.
     */
    internal fun leave(c: Ctx, today: Long, force: Boolean, near: BlockPos?, why: String? = null): Household? {
        val living = living(c)
        if (!force && living <= FLOOR * c.planned) {
            note(c, "Hard times, but nobody leaves: the town is down to $living of the ${c.planned} people it was planned for, and nobody leaves at or under ${(FLOOR * 100).toInt()}%.")
            return null
        }
        val all = leavers(c)
        val pick = (if (near != null) all.minByOrNull { it.home.box.center.distSqr(near) } else all.firstOrNull()) ?: run {
            note(c, "Hard times, but no household may leave: none is a citizens' household free of a trade, a duty or a works.")
            return null
        }
        val members = pick.members
        val day = VillageBoards.day(c.level)
        val bodies = members.mapNotNull { body(c.level, it) }
        for (r in members) {
            // Where they work, as it was, for when they come back
            val b = bodies.firstOrNull { it.resident == r.id }
            if (b != null) c.town.posts[r.id] = ComingsData.Post(r.id, b.work, b.post, b.postFacing, b.evening)
            r.alive = false
            r.left = day
        }
        val id = c.data.newId()
        val ids = members.map { it.id }
        val run = hardRun(c.level, c.key, today)
        val said = why ?: if (force) "sent off by a command" else "after $run hard days running"
        c.town.away += ComingsData.Moved(id, ids, pick.home.id, dated(c.level), HARD, said)
        if (!force) c.town.left = today
        c.town.why = ""
        c.data.changed()
        c.residents.changed()
        c.refresh()
        val h = Household(id, c.key, pick.home.id, ids, dated(c.level), HARD)
        val door = pick.home.front
        val seen = door != null && seen(c.level, door.pos, ComingsBodies.SEEN) || bodies.any { c.level.getNearestPlayer(it, ComingsBodies.SEEN) != null }
        if (bodies.isEmpty() || !seen || !ComingsBodies.go(c.level, c.places, h, bodies)) {
            // Nobody to see them go: they're simply gone
            tell(c.level, h, ComingsBodies.Kind.LEAVE)
            for (b in bodies) b.discard()
        }
        return h
    }

    // ---- Coming back ----

    /**
     * The household [away] comes back to [c]'s capital now: the same people, to their old home if it's still empty
     * (else an empty house, else the old one all the same), through [gate] (else whichever a player is near), set down
     * after [delay] ticks.
     */
    internal fun comeBack(c: Ctx, away: ComingsData.Moved, today: Long, gate: Dir?, force: Boolean, delay: Int = 0): Household? {
        val town = c.town
        val members = away.members.mapNotNull { c.residents[it] }.filter { away(it) }
        if (members.isEmpty()) {
            // Nobody left to come back (they've been seen to some other way)
            town.away.remove(away)
            c.data.changed()
            return null
        }
        val old = c.places.lodgings[away.home]
        val home = old?.takeIf { free(c, it.id) } ?: emptyHouses(c).firstOrNull() ?: old ?: nearestHouse(c, c.places.market) ?: run {
            note(c, "Good times, but there's no house for those away to come back to.")
            return null
        }
        town.away.remove(away)
        c.data.changed()
        if (!force) town.came = today
        for (r in members) {
            r.alive = true
            r.left = -1L
            if (r.home != home.id) {
                r.home = home.id
                r.bed = null
                r.furnished = false
            }
        }
        val ids = members.map { it.id }
        val why = (if (home.id == away.home) "came back to their old home" else "came back, to a new home (theirs was taken)") +
            if (force) " (brought back now by a command)" else ""
        town.log += ComingsData.Moved(away.id, ids, home.id, dated(c.level), RETURN, why)
        town.why = ""
        c.residents.changed()
        c.refresh()
        val h = Household(away.id, c.key, home.id, ids, dated(c.level), RETURN)
        show(c, h, ComingsBodies.Kind.RETURN, gate, delay)
        return h
    }

    /**
     * [h]'s bodies come in at [gate] (else whichever gate a player is within [ComingsBodies.SEEN] blocks of), set down
     * after [delay] ticks, the listeners hearing as they reach their door; with nobody near any gate, the listeners hear
     * now, and the bodies are made at home out of sight when a player next comes.
     */
    private fun show(c: Ctx, h: Household, kind: ComingsBodies.Kind, gate: Dir?, delay: Int) {
        if (ComingsBodies.come(c.level, c.places, h, kind, gate, delay)) return
        tell(c.level, h, kind)
        later(c.level, h.capital, h.members)
    }

    /** The listeners hear of [h]: come in, come back or gone. */
    internal fun tell(level: ServerLevel, h: Household, kind: ComingsBodies.Kind) {
        for (l in listeners.toList()) {
            try {
                when (kind) {
                    ComingsBodies.Kind.ARRIVE -> l.arrived(level, h)
                    ComingsBodies.Kind.RETURN -> l.returned(level, h)
                    ComingsBodies.Kind.LEAVE -> l.left(level, h)
                }
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Something hearing of the comings and goings failed on household #{}", h.id, e)
            }
        }
    }

    // ---- Switched off ----

    /** Switched off: every household away is home again (to their old home, whoever's in it now), their bodies made there out of sight. */
    private fun restore(level: ServerLevel) {
        if (!READY) return
        val data = ComingsData.get(level)
        if (data.all().values.none { it.away.isNotEmpty() }) return
        val residents = Residents.get(level)
        for (town in data.all().values) {
            if (town.away.isEmpty()) continue
            for (a in town.away) for (id in a.members) {
                val r = residents[id] ?: continue
                if (!away(r)) continue
                r.alive = true
                r.left = -1L
                town.bodies.add(id)
            }
            town.away.clear()
            town.leaving = ComingsData.NEVER
            town.why = "The households away came home: comings and goings were switched off."
        }
        data.changed()
        residents.changed()
    }

    // ---- The bodies ----

    /** [ids]'s bodies are to be made at home, out of sight, when a player next comes. */
    internal fun later(level: ServerLevel, capital: Long, ids: List<Int>) {
        if (ids.isEmpty()) return
        val data = ComingsData.get(level)
        data.town(capital).bodies.addAll(ids)
        data.changed()
    }

    /** [ids] have their bodies about now: none is to be made for them at home. */
    internal fun met(level: ServerLevel, capital: Long, ids: List<Int>) {
        if (ids.isEmpty()) return
        val town = ComingsData.get(level).find(capital) ?: return
        if (town.bodies.removeAll(ids.toSet())) ComingsData.get(level).changed()
    }

    /**
     * The bodies still to be made at home in the capitals players are in: each just inside their front door, with its
     * land loaded, nobody within [Furnish.UNSEEN] blocks of the house and nobody in sight of the spot ([MOST_BODIES] a
     * capital at a time). Someone whose own body is about already needs none, and someone coming in at a gate just now
     * has theirs made there (if it never is, the server stopping first, say, it's made at home after all).
     */
    private fun bodies(level: ServerLevel) {
        val data = ComingsData.get(level)
        if (data.all().values.none { it.bodies.isNotEmpty() }) return
        val players = level.players()
        if (players.isEmpty()) return
        val near = HashMap<Long, io.github.vaspyyy.guildmark.city.CapitalPiece>()
        for (player in players) Furnish.capitalAt(level, player.blockPosition())?.let { near[it.center.asLong()] = it }
        val residents = Residents.get(level)
        for ((key, piece) in near) {
            val town = data.find(key) ?: continue
            if (town.bodies.isEmpty()) continue
            val places = CityPlaces.of(level, piece) ?: continue
            var made = 0
            for (id in town.bodies.toList()) {
                if (made >= MOST_BODIES) break
                val r = residents[id]
                if (r == null || !r.alive) {
                    town.bodies.remove(id)
                    data.changed()
                    continue
                }
                // Coming in at a gate just now: their bodies are made on the road (they're kept here only in case they never are)
                if (ComingsBodies.busy(id)) continue
                if (body(level, r) != null) {
                    town.bodies.remove(id)
                    data.changed()
                    continue
                }
                val home = places.lodgings[r.home]
                if (home == null) {
                    town.bodies.remove(id)
                    data.changed()
                    continue
                }
                if (!Furnish.loaded(level, home) || watched(level, home)) continue
                val spot = inside(level, home) ?: continue
                if (!unseen(level, spot)) continue
                if (body(level, places, r, spot) == null) continue
                town.bodies.remove(id)
                data.changed()
                made++
            }
        }
    }

    /** [r]'s body, if it's about. */
    internal fun body(level: ServerLevel, r: Resident): Townsperson? =
        r.uuid?.let { level.getEntity(it) as? Townsperson }?.takeIf { it.isAlive && !it.isRemoved && it.resident == r.id }

    /**
     * A body made now for [r] at [at] (facing [yaw] if given): their trade and look, their home, and where they work as
     * the record has it; their resident id and name given at once (so Residents.adopt knows them). Their bed is the
     * one the record gives them, or one Furnish makes up out of sight. Any other body of theirs is someone gone from
     * now on, dropped as it loads.
     */
    internal fun body(level: ServerLevel, places: CityPlaces, r: Resident, at: BlockPos, yaw: Float? = null): Townsperson? {
        val data = ComingsData.get(level)
        val town = data.town(places.capital.center.asLong())
        val post = town.posts[r.id]
        val home = places.lodgings[r.home]
        val p = Townsfolk.spawn(
            level, at, r.role, work = work(level, r, post), home = home?.front?.inside, post = post?.post, facing = post?.facing,
            evening = post?.evening, town = places.capital.town, look = r.look,
        ) ?: return null
        // Only a post they have: not wherever they were set down
        p.post = post?.post
        p.postFacing = post?.facing
        if (yaw != null) {
            p.snapTo(p.x, p.y, p.z, yaw, 0f)
            p.yHeadRot = yaw
            p.yBodyRot = yaw
        }
        p.resident = r.id
        Residents.named(p, r)
        val residents = Residents.get(level)
        if (r.uuid != null && r.uuid != p.uuid) town.renewed.add(r.id)
        r.uuid = p.uuid
        residents.add(r)
        data.changed()
        return p
    }

    /** Where [r] works: their record's block, or for the journeyman at a works' second oven, the oven nearest its drawing. */
    private fun work(level: ServerLevel, r: Resident, post: ComingsData.Post?): BlockPos? {
        val work = post?.work ?: return null
        if (r.role != TownRole.BAKER || !level.isLoaded(work) || Jobs.oven(level.getBlockState(work))) return work
        return Places.nearest(level, work, 8, 3, null) { state, _ -> Jobs.oven(state) } ?: work
    }

    /** Just inside [home]'s front door, if there's room to stand. */
    internal fun inside(level: ServerLevel, home: CityPlaces.Lodging): BlockPos? {
        val door = home.front ?: return null
        for (pos in listOf(door.inside, door.inside.relative(door.inward), door.inside.above(), door.inside.below())) {
            if (level.isLoaded(pos) && Places.standable(level, pos)) return pos
        }
        return null
    }

    /** Whether a player is within [Furnish.UNSEEN] blocks of [home]. */
    private fun watched(level: ServerLevel, home: CityPlaces.Lodging): Boolean {
        val b = home.box
        return level.players().any { p ->
            val dx = maxOf(b.minX() - p.x, 0.0, p.x - b.maxX() - 1)
            val dy = maxOf(b.minY() - p.y, 0.0, p.y - b.maxY() - 1)
            val dz = maxOf(b.minZ() - p.z, 0.0, p.z - b.maxZ() - 1)
            dx * dx + dy * dy + dz * dz < Furnish.UNSEEN * Furnish.UNSEEN
        }
    }

    /** Whether no player is within [Furnish.UNSEEN] blocks of [at], nor within [SIGHT] with a clear line to it. */
    internal fun unseen(level: ServerLevel, at: BlockPos): Boolean {
        val spot = Vec3.atCenterOf(at).add(0.0, 0.5, 0.0)
        for (player in level.players()) {
            val d = player.distanceToSqr(spot)
            if (d < Furnish.UNSEEN * Furnish.UNSEEN) return false
            if (d > SIGHT * SIGHT) continue
            val hit = level.clip(ClipContext(player.eyePosition, spot, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.MISS) return false
        }
        return true
    }

    /** Whether any player is within [r] blocks of [pos]. */
    internal fun seen(level: ServerLevel, pos: BlockPos, r: Double): Boolean =
        level.getNearestPlayer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, r, false) != null

    /** Whether [r]'s body was made anew since (so any other body of theirs is someone gone, dropped as it loads). */
    internal fun renewed(level: ServerLevel, r: Resident): Boolean =
        ComingsData.get(level).find(r.capital.asLong())?.renewed?.contains(r.id) == true

    /** A well-stirred mix of [a] and [b], for what's fixed by them. */
    internal fun mix(a: Long, b: Long): Long {
        var x = a * -0x61c8864680b583ebL xor b * 0x2545F4914F6CDD1DL
        x = x xor (x ushr 31)
        x *= -0x4b6d499041670d8dL
        return x xor (x ushr 29)
    }

    private inline fun setting(get: () -> Boolean): Boolean = try {
        get()
    } catch (e: IllegalStateException) {
        true
    }
}
