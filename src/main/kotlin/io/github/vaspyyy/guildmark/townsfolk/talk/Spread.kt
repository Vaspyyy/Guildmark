package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.townsfolk.Agenda
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How word goes round a capital with nobody watching (living-world/design-voices.md section 4): an hour of the
 * overworld's clock at a time, on the pattern of [Markets], whether anyone's there or not, catching up the last
 * [CATCH_UP] hours at most after a sleep or a /time add, its random fixed by capital and hour so a catch-up comes out
 * as the hours would have one by one, and taken back with the clock when time is set back ([rewind]).
 *
 * Each hour every resident is where their day has them ([Days.plan]: worked out once a day for the whole town), and
 * those in the same place may talk: a household at its meals and of an evening, a caller and the house they call at,
 * the tavern nearest home, the well, the market, a trade at work, the temple, a stroll, the children at their lessons
 * and their games. Each knower tells two others there what they know that's still news, each with a chance of the
 * venue's × the rumour's heat × the teller's tongue × the listener's ear, and each telling may change it a step (see
 * [Rumours.retold]); the innkeeper tells everyone at the bar. At noon the crier's crowd hears the news cried; at dawn
 * what's faded is let go. Word bound for the other capitals arrives when it's due, and visitors bring some of an
 * evening. Faction members and anyone with no record live no days here, so they take no part.
 *
 * The spreading needs both [Rumours.enabled] and the Living World ([Days.enabled]); with the rumours off the hours
 * still go by (so nothing bursts out later), the crier still cries, and the town knows only what it saw and heard cried.
 */
object Spread {
    /** Server ticks between runs, and between looks at where the players are. */
    private const val TICK = 20
    private const val LOOK = 100

    /** The most hours caught up, and the most stepped in one run for all capitals together. */
    const val CATCH_UP = 24L
    private const val STEPS = 8

    /** Hours of the day (0 is six in the morning): dawn, when the night's hunger is known, noon, and the tavern's late evening. */
    private const val DAWN = 0
    private const val HUNGER = 1
    private const val NOON = 6
    private const val EVENING = 15

    /** Each knower tells this many others in a place an hour. */
    private const val LISTENERS = 2

    /** The chances of a telling, before heat, tongue and ear. */
    private const val HOUSEHOLD = 0.5
    private const val SUPPER = 0.6
    private const val CALL = 0.6
    private const val TAVERN = 0.35
    private const val WELL = 0.25
    private const val MARKET = 0.2
    private const val WORK = 0.2
    private const val TEMPLE = 0.1
    private const val SERVICE = 0.3
    private const val STROLL = 0.05
    private const val INNKEEPER = 0.5

    /** A trade at work in bands of about this many (so the town's citizens aren't one crowd), and the homes a caller chooses among. */
    private const val CREW = 8
    private const val NEIGHBOURS = 8

    /** How many guards hear word from afar first, with the innkeepers and keepers. */
    private const val GATE = 3

    /** Ticks between tries at finding a capital whose piece isn't known (its centre must be loaded). */
    private const val FIND = 600L

    /** Of the plan's grown-ups, this many in ten know the old news a capital's talk starts with. */
    private const val SEEDED = 3

    /** Market news and guild news the talk starts with in an old world: days back. */
    private const val SEED_MARKET_HOURS = 72L
    private const val SEED_GUILD_DAYS = 7L

    /** The most rumours a capital keeps on their way in. */
    private const val ARRIVALS = 64

    private const val EVENT_NEWS = "news.guildmark.market.event."
    private const val OVER_NEWS = "news.guildmark.market.over."

    // ---- What's kept in memory ----

    /** One of a capital's people as the step sees them on one day: their record, their day and the places it takes them. */
    class Person(
        val r: Resident,
        val agenda: Agenda,
        /** The tavern, well and temple nearest home (a lodging id, a well's index, a lodging id; 0 where the places aren't known). */
        val tavern: Int,
        val well: Int,
        val temple: Int,
        /** Whose home they'd call at today (a lodging id), or -1. */
        val calls: Int,
        /** Which band of their trade they work among. */
        val crew: Int,
    )

    /** Those in one place this hour, the [venue] it is and the [chance] of a telling there; the [innkeeper] if it's a bar. */
    class Group(val venue: Venue, var chance: Double) {
        val members = ArrayList<Person>()
        var innkeeper: Person? = null
    }

    /**
     * What the talk knows of a capital in memory only: its piece and places once found (and its board and name), its
     * people's day, the players' standing as last seen at its board, today's talk of the town, and how many know what.
     */
    class TownView(val capital: Long) {
        var piece: CapitalPiece? = null
        var places: CityPlaces? = null
        var board: BlockPos? = null
        var name: String? = null
        var tried = Long.MIN_VALUE / 2
        var day = Long.MIN_VALUE
        var people: List<Person>? = null
        val standing = HashMap<UUID, Int>()

        /** Today's talk of the town: the day, and the rumour (-1 for none). */
        var talkDay = Long.MIN_VALUE
        var talk = -1

        /** What more than half the town knows, as of an hour, for passers-by. */
        var commonHour = Long.MIN_VALUE
        var common: List<Int> = listOf()
    }

    private val views = HashMap<Long, TownView>()

    fun view(capital: Long): TownView = views.getOrPut(capital) { TownView(capital) }

    fun view(capital: BlockPos): TownView = view(capital.asLong())

    /** What one run of the step came to: tellings tried, rumours heard, hours stepped, and the time it took. */
    class Tally {
        var tries = 0
        var heard = 0
        var hours = 0
        var nanos = 0L
    }

    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> views.clear() }
    }

    /** The hour of the overworld's clock, as the markets count it. */
    fun hourOf(level: ServerLevel): Long = Markets.hourOf(level)

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % TICK != 0) return
        val level = server.overworld()
        val data = TownTalkData.get(level)
        if (server.tickCount % LOOK == 0) look(level, data)
        run(level, data)
    }

    /** Opens the talk of every capital a player stands in, and learns its piece and places and the player's standing there. */
    private fun look(level: ServerLevel, data: TownTalkData) {
        val on = Memory.enabled() || Rumours.enabled()
        for (player in level.players()) {
            val piece = Furnish.capitalAt(level, player.blockPosition()) ?: continue
            val view = view(piece.center)
            found(level, view, piece)
            if (on && data[piece.center] == null) data.open(piece.center, hourOf(level), Memory.day(level).toLong())
            view.board?.let { board -> Standing.board(level, board)?.let { view.standing[player.uuid] = it.standingOf(player.uuid) } }
        }
        // A capital whose piece isn't known yet (after a restart, nobody there): from its centre, once that's loaded
        for (entry in data.all()) {
            val view = view(entry.capital)
            if (view.piece == null && level.gameTime - view.tried >= FIND) {
                view.tried = level.gameTime
                Furnish.capitalAt(level, entry.pos)?.let { found(level, view, it) }
            }
            val piece = view.piece
            if (piece != null && view.places == null) view.places = CityPlaces.of(level, piece)
        }
    }

    /** [view]'s capital is [piece]: its board, its name, and its places (worked out off the thread the first time). */
    fun found(level: ServerLevel, view: TownView, piece: CapitalPiece) {
        if (view.piece == null) {
            view.piece = piece
            try {
                val board = piece.boardAnchor
                view.board = board
                view.name = VillageNames.of(board)
            } catch (e: IllegalStateException) {
                // A capital saved by an older version has no plan: no board, no name
            }
        }
        if (view.places == null) view.places = CityPlaces.of(level, piece)
    }

    /** Works out the hours every capital's talk is behind, [STEPS] of them at most this run; the rest wait for the next. */
    private fun run(level: ServerLevel, data: TownTalkData) {
        val now = hourOf(level)
        var steps = STEPS
        for (entry in data.all()) {
            // Time set back: the talk carries on from the new hour, everything kept moved back with it
            if (entry.hour > now) {
                rewind(entry, entry.hour - now)
                data.changed()
                continue
            }
            if (now - entry.hour > CATCH_UP) entry.hour = now - CATCH_UP
            while (entry.hour < now && steps > 0) {
                step(level, entry, entry.hour + 1, null)
                entry.hour++
                steps--
                data.changed()
            }
        }
    }

    /** Works out [entry]'s talk in [hour]; [tally], if given, counts what it came to. */
    fun step(level: ServerLevel, entry: TownTalkData.Entry, hour: Long, tally: Tally?) {
        val view = view(entry.capital)
        val living = Days.enabled()
        val rumours = Rumours.enabled()
        val clock = Math.floorMod(hour, 24L).toInt()
        market(level, entry, view, hour)
        val people = people(level, view, Days.dayOf(hour * 1000L + 500L))
        if (!entry.seeded && people.isNotEmpty()) {
            seed(level, entry, view, people, hour)
            entry.seeded = true
        }
        if (rumours) arrivals(level, entry, view, people, hour)
        val groups = if (living) groups(people, hour) else listOf()
        if (rumours && living) talk(entry, groups, hour, hour, random(entry, hour), tally ?: Tally())
        if (living && clock == NOON) noon(level, entry, view, groups, hour, tally)
        if (clock == DAWN) dawn(level, entry, hour)
        if (clock == HUNGER) hunger(level, entry, people, hour)
        if (rumours && living && clock == EVENING) visitors(level, entry, view, hour)
    }

    /**
     * For `/guildmark rumour spread`: the talk of the next [hours] at [entry] worked out now, as they'd go (the crier's
     * noon included), without moving its clock; what's heard is heard now.
     */
    fun ahead(level: ServerLevel, entry: TownTalkData.Entry, hours: Int): Tally {
        val tally = Tally()
        val start = System.nanoTime()
        val now = hourOf(level)
        val view = view(entry.capital)
        for (k in 1..hours) {
            val hour = now + k
            val people = people(level, view, Days.dayOf(hour * 1000L + 500L))
            val groups = groups(people, hour)
            talk(entry, groups, hour, now, random(entry, hour), tally)
            if (Math.floorMod(hour, 24L).toInt() == NOON) {
                val square = groups.filter { it.venue == Venue.MARKET }.flatMap { it.members }
                for (s in cries(level, entry, view, hour, keep = false)) for (p in square) {
                    if (Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, now, Rumours.CRIER, Venue.CRIER, Rumours.TRUE)) tally.heard++
                }
            }
            tally.hours++
        }
        tally.nanos = System.nanoTime() - start
        return tally
    }

    // ---- The town's people, and where they are ----

    /** [view]'s living people on [day] as the step sees them (worked out once a day). */
    fun people(level: ServerLevel, view: TownView, day: Long): List<Person> {
        view.people?.takeIf { view.day == day }?.let { return it }
        val centre = BlockPos.of(view.capital)
        val living = Residents.get(level).of(centre).filter { it.alive && it.id >= 0 && Faction.of(it.role) == null }.sortedBy { it.id }
        val places = view.places
        val lessons = places?.lodgings?.values?.any { it.kind in CityPlaces.TEMPLES || it.kind == "LIBRARY" } ?: true
        val crew = places?.let { Hands.of(level, it) }
        val taverns = places?.lodgings?.values?.filter { it.kind in CityPlaces.TAVERNS }.orEmpty()
        val temples = places?.lodgings?.values?.filter { it.kind in CityPlaces.TEMPLES }.orEmpty()
        val wells = places?.wells.orEmpty()
        val homes = living.map { it.home }.filter { it >= 0 }.distinct()
        val counts = living.groupingBy { it.role }.eachCount()
        val out = living.map { r ->
            val at = places?.lodgings?.get(r.home)?.box?.center
            val tavern = at?.let { h -> taverns.minByOrNull { it.box.center.distSqr(h) }?.id } ?: 0
            val temple = at?.let { h -> temples.minByOrNull { it.box.center.distSqr(h) }?.id } ?: 0
            val well = at?.let { h -> wells.indices.minByOrNull { wells[it].distSqr(h) } } ?: 0
            val bands = max(1, (counts[r.role] ?: 1) / CREW)
            val watch = r.role == TownRole.GUARD && nightWatch(level, r)
            val agenda = Days.plan(r, day, lessons, watch, crew?.carters?.contains(r.id) == true, crew?.crier == r.id)
            Person(r, agenda, tavern, well, temple, neighbour(r, at, homes, places, day), Math.floorMod(mix(r.id.toLong()), bands.toLong()).toInt())
        }
        view.day = day
        view.people = out
        return out
    }

    /** Whether [r], a guard, keeps the night watch: as their body has it if it's about, else one in three. */
    private fun nightWatch(level: ServerLevel, r: Resident): Boolean {
        val body = r.uuid?.let { level.getEntity(it) } as? Townsperson
        return body?.nightWatch ?: (Math.floorMod(mix(r.id * 7919L), 3L) == 0L)
    }

    /** The home [r] calls at on [day]: one of the [NEIGHBOURS] nearest theirs (where the places are known), picked by who and when. */
    private fun neighbour(r: Resident, at: BlockPos?, homes: List<Int>, places: CityPlaces?, day: Long): Int {
        val others = homes.filter { it != r.home }
        if (others.isEmpty()) return -1
        val near = if (at != null && places != null) {
            others.sortedBy { places.lodgings[it]?.box?.center?.distSqr(at) ?: Double.MAX_VALUE }.take(NEIGHBOURS)
        } else {
            others
        }
        return near[Math.floorMod(mix(r.id * 31L + day), near.size.toLong()).toInt()]
    }

    /** Where each of [people] is in [hour], and who's together: every place with anyone in it. */
    fun groups(people: List<Person>, hour: Long): List<Group> {
        val time = hour * 1000L + 500L
        val offset = Days.offsetOf(time)
        val rest = Days.restDay(Days.dayOf(time))
        val map = LinkedHashMap<Long, Group>()
        fun join(kind: Int, id: Int, venue: Venue, chance: Double, p: Person): Group {
            val g = map.getOrPut((kind.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)) { Group(venue, chance) }
            if (chance > g.chance) g.chance = chance
            g.members.add(p)
            return g
        }
        val home = HashMap<Int, ArrayList<Person>>()
        for (p in people) {
            if (!p.r.alive) continue
            val doing = p.agenda.at(offset).doing
            when (doing) {
                Doing.BREAKFAST, Doing.LUNCH, Doing.EVENING -> if (p.r.home >= 0) join(AT_HOME, p.r.home, Venue.HOUSEHOLD, HOUSEHOLD, p)
                Doing.SUPPER -> if (p.r.home >= 0) join(AT_HOME, p.r.home, Venue.HOUSEHOLD, SUPPER, p)
                Doing.VISIT -> if (p.calls >= 0) join(CALLING, p.calls, Venue.VISIT, CALL, p)
                Doing.TAVERN -> join(BAR, p.tavern, Venue.TAVERN, TAVERN, p)
                Doing.WATER -> join(WATER, p.well, Venue.WELL, WELL, p)
                Doing.MARKET, Doing.CRY -> join(SQUARE, 0, Venue.MARKET, MARKET, p)
                Doing.WORSHIP -> join(SHRINE, p.temple, Venue.TEMPLE, if (rest) SERVICE else TEMPLE, p)
                Doing.STROLL -> join(WALK, 0, Venue.STROLL, STROLL, p)
                Doing.LESSONS -> join(SCHOOL, 0, Venue.WORK, WORK, p)
                Doing.PLAY -> join(GAMES, p.crew, Venue.STREET, WORK, p)
                Doing.WORK, Doing.WATCH, Doing.CART -> when (p.r.role) {
                    TownRole.INNKEEPER -> join(BAR, p.tavern, Venue.TAVERN, TAVERN, p).also { if (it.innkeeper == null) it.innkeeper = p }
                    TownRole.MERCHANT, TownRole.BARD -> join(SQUARE, 0, Venue.MARKET, MARKET, p)
                    TownRole.PRIEST -> join(SHRINE, p.temple, Venue.TEMPLE, if (rest) SERVICE else TEMPLE, p)
                    else -> join(JOB, (if (doing == Doing.CART) CARTERS else p.r.role.ordinal) * 64 + p.crew, Venue.WORK, WORK, p)
                }
                else -> {}
            }
            if (doing in HOME && p.r.home >= 0) home.getOrPut(p.r.home) { ArrayList() }.add(p)
        }
        // A caller finds whoever of the house is at home
        for ((key, g) in map) {
            if ((key ushr 32).toInt() != CALLING) continue
            home[key.toInt()]?.let { g.members.addAll(it) }
        }
        return map.values.toList()
    }

    /** The kinds of place, for the groups' keys. */
    private const val AT_HOME = 1
    private const val CALLING = 2
    private const val BAR = 3
    private const val WATER = 4
    private const val SQUARE = 5
    private const val JOB = 6
    private const val SHRINE = 7
    private const val WALK = 8
    private const val SCHOOL = 9
    private const val GAMES = 10

    /** The carters' band among the trades at work. */
    private const val CARTERS = 63

    /** What someone at home is at when a caller finds them in. */
    private val HOME = setOf(Doing.RISE, Doing.BREAKFAST, Doing.LUNCH, Doing.SUPPER, Doing.EVENING, Doing.BED)

    // ---- Telling ----

    /** Someone hearing a rumour this hour, held until the hour's talk is done (so word goes a step an hour). */
    private class Hearing(val to: Int, val rumour: Int, val from: Int, val venue: Venue, val warp: Int)

    /** How readily [r] tells something at [venue]: the chatty more, the shy less, the drinker at the bar more. */
    fun tongue(r: Resident, venue: Venue): Double {
        var t = 1.0
        if (r.has(Trait.CHATTY)) t *= 1.5
        if (r.has(Trait.SHY)) t *= 0.5
        if (venue == Venue.TAVERN && r.has(Trait.DRINKER)) t *= 1.3
        return t
    }

    /** How readily [r] listens: the idle more. */
    fun ear(r: Resident): Double = if (r.has(Trait.IDLE)) 1.2 else 1.0

    /**
     * An hour of talk at [entry] in [groups] at [hour]: each knower tells [LISTENERS] others there what's still news
     * (the innkeeper, everyone at the bar), heard as of hour [heard].
     */
    private fun talk(entry: TownTalkData.Entry, groups: List<Group>, hour: Long, heard: Long, random: Random, tally: Tally) {
        val heats = HashMap<Int, Float>()
        for (s in entry.rumours.values) {
            if (s.rumour.born > hour) continue
            val h = Rumours.heat(s, hour)
            if (h >= Rumours.OLD) heats[s.rumour.id] = h
        }
        if (heats.isEmpty()) return
        val news = ArrayList<Hearing>()
        for (g in groups) {
            val members = g.members
            if (members.size < 2) continue
            for (teller in members) {
                val mind = entry.minds[teller.r.id] ?: continue
                if (mind.heard.isEmpty()) continue
                var tellable: ArrayList<Heard>? = null
                for (h in mind.heard.values) if (h.rumour in heats) (tellable ?: ArrayList<Heard>().also { tellable = it }).add(h)
                val told = tellable ?: continue
                told.sortByDescending { heats[it.rumour] ?: 0f }
                val inn = g.innkeeper === teller
                val tongue = tongue(teller.r, g.venue)
                val listeners = if (inn) members else pick(members, teller, random)
                for (l in listeners) {
                    if (l === teller) continue
                    val ear = ear(l.r)
                    val theirs = entry.minds[l.r.id]?.heard
                    for (h in told) {
                        val known = theirs?.get(h.rumour)
                        if (known != null && known.warp <= h.warp) continue
                        val heat = heats[h.rumour] ?: continue
                        val story = entry.rumours[h.rumour] ?: continue
                        tally.tries++
                        val chance = if (inn) INNKEEPER * heat else g.chance * heat * tongue * ear
                        if (random.nextDouble() >= chance) continue
                        h.told++
                        news.add(Hearing(l.r.id, h.rumour, teller.r.id, g.venue, Rumours.retold(teller.r, story.rumour, h.warp, random)))
                    }
                }
            }
        }
        for (n in news) {
            val s = entry.rumours[n.rumour] ?: continue
            if (Rumours.hear(entry, entry.mind(n.to, true)!!, s, heard, n.from, n.venue, n.warp)) tally.heard++
        }
    }

    /** Up to [LISTENERS] of [members] other than [teller], picked at random. */
    private fun pick(members: List<Person>, teller: Person, random: Random): List<Person> {
        val n = members.size - 1
        if (n <= LISTENERS) return members
        val chosen = ArrayList<Person>(LISTENERS)
        while (chosen.size < LISTENERS) {
            val p = members[random.nextInt(members.size)]
            if (p !== teller && p !in chosen) chosen.add(p)
        }
        return chosen
    }

    // ---- The crier ----

    /**
     * What the crier cries at [hour]'s noon that goes round: the talk of the town, yesterday's guild news, the market's
     * news since the noon before. [keep]: today's talk of the town is chosen for good (not for a look ahead).
     */
    fun cries(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, hour: Long, keep: Boolean = true): List<Story> {
        val out = ArrayList<Story>()
        if (Rumours.enabled()) TownCry.pick(entry, view, hour, keep)?.let { out.add(it) }
        // The guild's news of the day before, by the guild's own days (six to six), newest first, two at most
        val yesterday = Math.floorDiv(hour, 24L) - 1
        entry.rumours.values.filter { it.by == Rumours.GUILD && Math.floorDiv(it.rumour.born, 24L) == yesterday }
            .sortedByDescending { it.rumour.born }.take(2).forEach { if (it !in out) out.add(it) }
        entry.rumours.values.filter { it.by == Rumours.MARKET && it.rumour.born in hour - 23..hour }.forEach { if (it !in out) out.add(it) }
        return out
    }

    /** Noon: the crier cries the news, and everyone about the square knows it. */
    private fun noon(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, groups: List<Group>, hour: Long, tally: Tally?) {
        // A capital whose people are known to have no crier cries nothing
        val places = view.places
        if (places != null && Hands.of(level, places).crier == null) return
        val cried = cries(level, entry, view, hour)
        if (cried.isEmpty()) return
        val square = groups.filter { it.venue == Venue.MARKET }.flatMap { it.members }
        for (s in cried) {
            for (p in square) {
                if (Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, hour, Rumours.CRIER, Venue.CRIER, Rumours.TRUE)) tally?.let { it.heard++ }
            }
            TownCry.done(s, hour)
        }
    }

    // ---- Dawn, and the night's hunger ----

    /** Dawn: what's faded is let go, and anyone gone from the town with it. */
    private fun dawn(level: ServerLevel, entry: TownTalkData.Entry, hour: Long) {
        if (Rumours.enabled()) {
            Rumours.fade(entry, hour)
            Rumours.trim(entry, hour)
        }
        val day = Days.dayOf(hour * 1000L).toInt()
        if (Memory.enabled()) for (m in entry.minds.values) Memory.tidy(m, day)
        val residents = Residents.get(level)
        entry.minds.keys.removeIf { residents[it]?.alive != true }
        entry.minds.values.removeIf { it.empty }
        for (p in entry.players.values) p.keys.retainAll(entry.rumours.keys)
        entry.players.values.removeIf { it.isEmpty() }
        val data = TownTalkData.get(level)
        entry.arrivals.removeIf { a -> data[a.from]?.rumours?.containsKey(a.id) != true }
    }

    /**
     * The morning after a hungry day (a twentieth of the town's meals or more went uneaten, as the crier counts it):
     * those who went without feel it, and the town talks of it.
     */
    private fun hunger(level: ServerLevel, entry: TownTalkData.Entry, people: List<Person>, hour: Long) {
        if (people.isEmpty()) return
        val m = Markets.get(level)[entry.pos] ?: return
        val missed = m.market.hungry.lastOrNull() ?: return
        if (missed < maxOf(3.0, m.town.people * Economy.MEALS * 0.05)) return
        val day = Days.dayOf(hour * 1000L).toInt()
        val count = ceil(missed / Economy.MEALS).toInt().coerceIn(1, people.size)
        val hungry = people.filter { it.r.role != TownRole.NOBLE }.sortedBy { mix(it.r.id * 131L + day) }.take(count)
        for (p in hungry) Memory.felt(entry.mind(p.r.id, true)!!, Experience.HUNGRY, day, -1)
        val (s, _) = Rumours.start(entry, hour, "market.hungry", null, -1, listOf(), 0.6f, Fade.SMALL, Scope.TOWN, -1, null, Rumours.MARKET)
        for (p in hungry) Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, hour, Rumours.SAW, Venue.SEEN, Rumours.TRUE)
        for (p in people) if (p.r.role == TownRole.MERCHANT) Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, hour, Rumours.SAW, Venue.MARKET, Rumours.TRUE)
    }

    // ---- The market's news ----

    /** The market's news since it was last looked at goes round: its keepers and the trade it falls on know it first. */
    private fun market(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, hour: Long) {
        val m = Markets.get(level)[entry.pos]?.market ?: return
        // Talk that's just begun takes the market's news in from now on (old news is seeded: see [seed])
        if (entry.news == Long.MIN_VALUE) {
            entry.news = hour
            return
        }
        var last = entry.news
        for (n in m.news) {
            if (n.hour <= entry.news) continue
            last = max(last, n.hour)
            news(level, entry, view, n.hour, n.key, n.good?.key, hour)
        }
        entry.news = last
    }

    /** A piece of the market's news ([key], about the good [good] if any) from [born] begins going round; null if it's not one that does. */
    private fun news(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, born: Long, key: String, good: String?, hour: Long): Story? {
        val started = key.startsWith(EVENT_NEWS)
        if (!started && !key.startsWith(OVER_NEWS)) return null
        val kind = EventKind.byId(key.substringAfterLast('.')) ?: return null
        val tone = if (!started || !kind.trouble) 1 else -1
        val (s, new) = Rumours.start(
            entry, born, (if (started) "market.event." else "market.over.") + kind.id, null, -1, listOfNotNull(good),
            0.6f, Fade.SMALL, Scope.TOWN, tone, null, Rumours.MARKET,
        )
        if (!new) return s
        val trade = when (kind) {
            EventKind.BLIGHT, EventKind.BUMPER, EventKind.MURRAIN, EventKind.FOWL_PEST, EventKind.MILL_BROKEN -> TownRole.FARMER
            EventKind.OVEN_CRACKED -> TownRole.BAKER
            EventKind.GOOD_CATCH, EventKind.POOR_CATCH -> TownRole.FISHER
            EventKind.FEVER -> null
        }
        val heard = minOf(born, hour)
        val crier = view.places?.let { Hands.of(level, it).crier }
        for (r in Residents.get(level).of(entry.pos)) {
            if (!r.alive) continue
            when {
                r.role == TownRole.MERCHANT || r.id == crier -> Rumours.hear(entry, entry.mind(r.id, true)!!, s, heard, Rumours.SAW, Venue.MARKET, Rumours.TRUE)
                r.role == trade -> Rumours.hear(entry, entry.mind(r.id, true)!!, s, heard, Rumours.SAW, Venue.SEEN, Rumours.TRUE)
            }
        }
        return s
    }

    // ---- Old worlds ----

    /**
     * A capital's talk, just begun with people living there, starts with the old news: the market's of the last three
     * days and the guild's of the last seven, known by the keepers, the crier and three grown-ups in ten, as hot as
     * their age leaves them.
     */
    private fun seed(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, people: List<Person>, hour: Long) {
        val crier = view.places?.let { Hands.of(level, it).crier }
        fun knows(r: Resident, rumour: Int): Boolean =
            r.role == TownRole.MERCHANT || r.role == TownRole.INNKEEPER || r.id == crier ||
                r.role.adult && Math.floorMod(mix(r.id * 977L + rumour), 10L) < SEEDED
        val born = ArrayList<Story>()
        Markets.get(level)[entry.pos]?.market?.news?.forEach { n ->
            if (n.hour in hour - SEED_MARKET_HOURS..hour) news(level, entry, view, n.hour, n.key, n.good?.key, hour)?.let { born.add(it) }
        }
        val today = Math.floorDiv(hour, 24L)
        for (item in GuildNews.get(level).items) {
            if (item.day < today - SEED_GUILD_DAYS || item.day > today) continue
            val s = Witness.guild(level, entry, item.key, item.args, item.day * 24L + NOON) ?: continue
            // What's older than today has been cried already
            if (item.day < today) Rumours.cried(s, s.at)
            born.add(s)
        }
        for (s in born) for (p in people) {
            if (!knows(p.r, s.rumour.id)) continue
            val venue = if (s.by == Rumours.MARKET && p.r.role == TownRole.MERCHANT) Venue.MARKET else Venue.CRIER
            Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, minOf(s.rumour.born, hour), Rumours.CRIER, venue, Rumours.TRUE)
        }
    }

    // ---- Between capitals ----

    /** Every capital the talk knows of: those with talk kept, a market, or people living there. */
    fun capitals(level: ServerLevel): Set<Long> {
        val out = LinkedHashSet<Long>()
        for (e in TownTalkData.get(level).all()) out.add(e.capital)
        for (m in Markets.get(level).all()) out.add(m.market.capital)
        for (r in Residents.get(level).all()) if (r.alive) out.add(r.capital.asLong())
        return out
    }

    /** The capital nearest [pos] within [range] blocks, of those the talk knows of. */
    fun nearest(level: ServerLevel, pos: BlockPos, range: Double): Long? {
        var best: Long? = null
        var least = range * range
        for (c in capitals(level)) {
            val at = BlockPos.of(c)
            val dx = (at.x - pos.x).toDouble()
            val dz = (at.z - pos.z).toDouble()
            val d = dx * dx + dz * dz
            if (d <= least) {
                least = d
                best = c
            }
        }
        return best
    }

    /** [s], just begun at [from], is on its way to every other capital within [Rumours.RANGE]: a day for every [Rumours.DAILY] blocks. */
    fun carry(level: ServerLevel, from: TownTalkData.Entry, s: Story) {
        val data = TownTalkData.get(level)
        val here = from.pos
        val hour = hourOf(level)
        for (c in capitals(level)) {
            if (c == from.capital) continue
            val there = BlockPos.of(c)
            val dx = (there.x - here.x).toDouble()
            val dz = (there.z - here.z).toDouble()
            val d = sqrt(dx * dx + dz * dz)
            if (d > Rumours.RANGE) continue
            val entry = data[c] ?: data.open(there, hour, Memory.day(level).toLong())
            entry.arrivals.add(Arrival(from.capital, s.rumour.id, s.rumour.born + Rumours.travel(d) * 24L))
            while (entry.arrivals.size > ARRIVALS) entry.arrivals.removeAt(0)
        }
        data.changed()
    }

    /** Word due at [entry] by [hour] arrives: the innkeepers, a few of the guards at the gates and the keepers have it first. */
    private fun arrivals(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, people: List<Person>, hour: Long) {
        if (entry.arrivals.isEmpty()) return
        val due = entry.arrivals.filter { it.at <= hour }
        if (due.isEmpty()) return
        entry.arrivals.removeAll(due.toSet())
        val data = TownTalkData.get(level)
        for (a in due) {
            val src = data[a.from]?.rumours?.get(a.id) ?: continue
            if (entry.rumours.values.any { it.from == a.from && it.source == a.id }) continue
            val heat = Rumours.heat(src, hour) * Rumours.ARRIVES
            if (heat < Rumours.OLD) continue
            val s = afar(entry, src, a.from, heat, hour)
            for (p in first(people)) Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, hour, Rumours.FAR, Venue.AFAR, 1)
        }
    }

    /** [src], a rumour of the capital at [from], begun here as word from afar at [heat]. */
    private fun afar(entry: TownTalkData.Entry, src: Story, from: Long, heat: Float, hour: Long): Story {
        val r = src.rumour
        return Rumours.start(entry, hour, r.kind, r.about, r.resident, r.args, heat, r.fade, Scope.TOWN, r.tone, r.origin, Rumours.AFAR, from, r.id).first
    }

    /** Who hears word from afar first: the innkeepers, a few of the guards, the keepers. */
    private fun first(people: List<Person>): List<Person> {
        val out = ArrayList<Person>()
        var guards = 0
        for (p in people) when (p.r.role) {
            TownRole.INNKEEPER, TownRole.MERCHANT -> out.add(p)
            TownRole.GUARD -> if (guards < GATE) {
                out.add(p)
                guards++
            }
            else -> {}
        }
        return out
    }

    /**
     * Visitors in for the day bring the hottest word of another capital to [entry]'s taverns, once a day, at
     * [Rumours.VISITORS] of its heat (an evening's visitor on screen brings it as they come in; see [Witness.visitor]).
     * True if they brought any.
     */
    fun visitors(level: ServerLevel, entry: TownTalkData.Entry, view: TownView, hour: Long): Boolean {
        val day = Days.dayOf(hour * 1000L)
        if (entry.brought == day) return false
        entry.brought = day
        val data = TownTalkData.get(level)
        var best: Story? = null
        var from = 0L
        var most = Rumours.OLD / Rumours.VISITORS
        for (other in data.all()) {
            if (other === entry) continue
            for (s in other.rumours.values) {
                if (s.by != Rumours.TOWN || s.rumour.born > hour) continue
                val h = Rumours.heat(s, hour)
                if (h <= most || entry.rumours.values.any { it.from == other.capital && it.source == s.rumour.id }) continue
                most = h
                best = s
                from = other.capital
            }
        }
        val src = best ?: return false
        val s = afar(entry, src, from, most * Rumours.VISITORS, hour)
        val people = people(level, view, Days.dayOf(hour * 1000L + 500L))
        for (p in people) if (p.r.role == TownRole.INNKEEPER) Rumours.hear(entry, entry.mind(p.r.id, true)!!, s, hour, Rumours.FAR, Venue.TAVERN, 1)
        data.changed()
        return true
    }

    // ---- Time set back ----

    /** Takes [entry] back [hours], everything it keeps by the hour and the day with it. */
    fun rewind(entry: TownTalkData.Entry, hours: Long) {
        val days = (Days.dayOf(entry.hour * 1000L) - Days.dayOf((entry.hour - hours) * 1000L)).toInt()
        entry.hour -= hours
        if (entry.news != Long.MIN_VALUE) entry.news -= hours
        if (entry.brought != Long.MIN_VALUE) entry.brought -= days
        entry.made -= days
        for (s in entry.rumours.values) {
            val r = s.rumour
            s.rumour = Rumour(r.id, r.kind, r.about, r.resident, r.args, r.born - hours, r.heat, r.fade, r.scope, r.tone, r.origin)
            s.at -= hours
        }
        val arrivals = entry.arrivals.map { Arrival(it.from, it.id, it.at - hours) }
        entry.arrivals.clear()
        entry.arrivals.addAll(arrivals)
        for (m in entry.minds.values) {
            for ((id, h) in m.heard.entries.toList()) m.heard[id] = Heard(h.rumour, h.hour - hours, h.from, h.venue, h.warp, h.told)
            val felt = m.felt.map { Felt(it.experience, it.day - days, it.about) }
            m.felt.clear()
            m.felt.addAll(felt)
            if (days == 0) continue
            for (a in m.known.values) {
                if (a.met >= 0) a.met -= days
                if (a.seen >= 0) a.seen -= days
                if (a.last >= 0) a.last -= days
                if (a.off >= 0) a.off -= days
                a.spent.day -= days
                a.favour.day -= days
                a.set.day -= days
                for (h in a.slights) h.day -= days
                val saw = a.saw.map { Saw(it.kind, it.day - days, it.tone, it.big, it.rumour) }
                a.saw.clear()
                a.saw.addAll(saw)
            }
        }
        // What's in memory by the day goes too
        views[entry.capital]?.let {
            it.day = Long.MIN_VALUE
            it.talkDay = Long.MIN_VALUE
            it.commonHour = Long.MIN_VALUE
        }
    }

    // ---- Odds and ends ----

    /** The same for the same capital and hour, so catching up comes out as it would have hour by hour. */
    private fun random(entry: TownTalkData.Entry, hour: Long): Random =
        Random(entry.capital * -0x61c8864680b583ebL xor hour * 0x2545F4914F6CDD1DL)

    /** Scrambles [x] (for picks by who and when that look random but are always the same). */
    fun mix(x: Long): Long {
        var z = x * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
