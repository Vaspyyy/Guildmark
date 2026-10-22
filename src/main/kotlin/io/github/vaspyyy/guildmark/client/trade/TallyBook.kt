package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyActPayload
import io.github.vaspyyy.guildmark.network.TallyParcel
import io.github.vaspyyy.guildmark.network.TallyRoutes
import io.github.vaspyyy.guildmark.network.TallyTown
import io.github.vaspyyy.guildmark.network.TallyView
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.trade.Routes
import kotlin.math.max

/** Where the trader stands in the world, for the Word's map to put them on. */
class Stand(val x: Double, val z: Double)

/** What the book does in the world outside it: the sounds it makes, what it asks of the server, and where its reader stands. The screen's is the game's; a preview's records them. */
interface Env {
    /** Plays the trade sound [name] (`trade.tally_page`...) for the reader alone. */
    fun sound(name: String, pitch: Float = 1f, volume: Float = 0.7f)

    /** Asks the server to do [action] ([TallyActPayload]'s REFRESH, REPORT or BOOK) about the capital whose key is [about]. */
    fun send(action: Int, about: Long)

    fun stand(): Stand?

    /** A light click for a choice made on a page (a town picked on the map, a ticket picked from a list). */
    fun select() {}
}

/** The measures of the open book (screen px) that its art ([tally_screen_art.py]) and its pages are laid by. */
object Book {
    const val W = 342
    const val H = 210
    const val MARGIN = 9
    const val PAGE_W = 158
    const val PAGE_H = 192
    const val GAP = 8
    const val SHADOW = 8
    const val PAGES = 5

    /** The book's one picture: the cover, the page block, the two sheets and their spine, and a soft shadow round it. */
    val ART = Tex("textures/gui/trade/tally_book.png", (W + 2 * SHADOW) * 2, (H + 2 * SHADOW) * 2)

    /** The page's names in the order they lie, as the tabs and the headings say them. */
    val NAMES = listOf("word", "routes", "held", "ledger", "standing")
}

/**
 * What the Tally's screen knows and remembers between one frame and the next (living-world/trader/design.md, Part 1): the last [view] the server
 * sent and when it came, the page the book is open at, what is picked on each page, and the things the view's changes have set going (a ring that
 * pulses because a fresher sight came, a notch that is cut because a day went by, a knot that is tied because a rung was climbed). Every time in it
 * is [t], the seconds the book has been open, so that a picture of it can be had for any moment.
 */
class TallyBook(view: TallyView, val env: Env) {
    var view: TallyView = view
        private set

    /** Seconds since the screen opened. */
    var t = 0.0

    /** When [view] came, in [t]; its clock is dead-reckoned from there at twenty ticks a second. */
    private var viewAt = 0.0

    var page = lastPage.coerceIn(0, Book.PAGES - 1)
        private set

    /** When the page was turned to. */
    var pageAt = -10.0
        private set

    // What is picked on each page
    var town: Long = TallyRoutes.NONE
        private set
    var leg = 0
        private set
    var held = 0
        private set
    var parcel = 0
        private set

    /** When a way, a ticket and a parcel were last picked on their pages (a pick starts that page's picture of it over). */
    var legAt = -10.0
        private set
    var heldAt = -10.0
        private set
    var parcelAt = -10.0
        private set

    /** When the town on the Word page was last picked, when the hourglass last turned over and how far its sand had run before (0 to 1: three days is all of it). */
    var pickedAt = -10.0
        private set
    var glassAt = -10.0
        private set
    var glassFrom = 0.0
        private set

    /** When a town's sight came fresher than the one the screen had (its ring pulses, its glass turns), or the town first showed. */
    val pulsed = HashMap<Long, Double>()
    val shown = HashMap<Long, Double>()

    /** The things a drawing does once, at a moment of it (a sound played as a knife bites), by the name each was given. */
    private val fired = HashSet<String>()

    /** When a parcel's notch was last cut (by its good and origin), and the days it had left before. */
    val cut = HashMap<String, Double>()
    val spent = HashMap<String, Int>()

    /** When a ticket first showed in the book's own list (it flutters in), by its number. */
    val arrived = HashMap<Int, Double>()

    /** When each of the next rung's asks was met (its tick is inked), by its key. */
    val metAt = HashMap<String, Double>()

    /** A page the book is asked to turn to (a link on a page); the stage turns the leaf as it does for a tab. -1 for none. */
    var want = -1
        private set

    /** When a rung was last climbed to (the knot is tied), whether the Standing page has shown it since, and when a report was last filed (the stamp comes down) or tickets were last booked. */
    var tiedAt = -1e9
    var tiedRung = -1
    var tieSeen = true
    var filedAt = -1e9
    var filedTown = TallyRoutes.NONE
    var bookedAt = -1e9

    /** The town a report was last asked for, to answer it when the next view says it went. */
    private var asked = TallyRoutes.NONE
    private var askedAt = -1e9

    /** When the server was last asked for news. */
    private var refreshed = 0.0

    init {
        val glassSeen = HashMap<Long, Double>()
        for (town in view.towns) {
            shown[town.capital] = 0.0
            // A sight fresher than the one this session last saw of the town: its ring pulses once as the book opens, and its glass turns
            val before = seenTick[town.capital]
            if (before != null && town.tick > before && !town.live) {
                pulsed[town.capital] = OPEN_PULSE
                glassSeen[town.capital] = ((view.now - before) / STRIKE).coerceIn(0.0, 1.0)
            }
            seenTick[town.capital] = town.tick
        }
        for (p in view.parcels) spent[key(p)] = p.left
        this.town = first(view)
        glassSeen[this.town]?.let {
            glassFrom = it
            glassAt = OPEN_PULSE
        }
        if (shownRung < 0) shownRung = view.standing.rung
        tiedRung = view.standing.rung
        if (view.standing.rung > shownRung) {
            tiedAt = 0.55
            tieSeen = page == STANDING
            shownRung = view.standing.rung
        }
    }

    // ---- Time ----

    /** The game's clock now, in ticks, as far as the screen can tell from the last view and the time since. */
    fun ticks(): Double = view.now + (t - viewAt) * 20.0

    /** How old the news of [town] is, in ticks: none if it is live. */
    fun age(town: TallyTown): Double = if (town.live) 0.0 else max(0.0, ticks() - town.tick)

    /** The ticks until the town's day next turns, when a parcel loses a day (the town counts its days from a little before the world's, as [Days] has it). */
    fun untilDay(): Double = DAY - Days.offsetOf(ticks().toLong())

    /** How long ago the screen was last given a view, in seconds. */
    fun stale(): Double = t - viewAt

    // ---- What a new view changes ----

    /** The server sent a newer [next]: what it changes in what is shown sets those things going. */
    fun update(next: TallyView) {
        val before = view
        val old = before.towns.associateBy { it.capital }
        for (sight in next.towns) {
            val was = old[sight.capital]
            if (was == null) {
                shown[sight.capital] = t
            } else if (sight.tick > was.tick && !sight.live) {
                // A fresher sight: its ring pulses once, and if it is the town on the page the hourglass is turned over
                pulsed[sight.capital] = t
                if (sight.capital == town) {
                    glassFrom = sand(was)
                    glassAt = t
                    if (page == WORD) env.sound("trade.hourglass", 1f, 0.6f)
                }
            }
            seenTick[sight.capital] = sight.tick
        }
        // A day gone from a parcel's days: its notch is cut (the ledger page's knife does it, and sounds it, when it is next looked at)
        for (p in next.parcels) {
            val k = key(p)
            val had = spent[k]
            if (had != null && p.left < had) cut[k] = t
            spent[k] = p.left
        }
        // A rung climbed: the next knot is tied (the standing page's rope does it, and sounds it)
        if (next.standing.rung > before.standing.rung && next.standing.rung > shownRung) {
            tiedAt = t
            tieSeen = page == STANDING
            tiedRung = next.standing.rung
            shownRung = next.standing.rung
        }
        // An ask of the next rung met: its tick is inked
        val unmet = before.standing.asks.filter { it.have < it.need }.map { it.key }.toSet()
        for (ask in next.standing.asks) if (ask.have >= ask.need && ask.key in unmet) metAt[ask.key] = t
        // Tickets that came into the book flutter in
        val had = before.held.map { it.id }.toSet()
        for (h in next.held) if (h.id !in had) arrived[h.id] = t
        // A report that went (one fewer left, and the town no longer asks it) and tickets that came
        if (next.reports < before.reports && asked != TallyRoutes.NONE && t - askedAt < 8.0) {
            filedAt = t
            filedTown = asked
            asked = TallyRoutes.NONE
        }
        if (next.held.size > before.held.size) bookedAt = t
        view = next
        viewAt = t
        if (town == TallyRoutes.NONE || next.towns.none { it.capital == town }) town = first(next)
        leg = leg.coerceIn(0, max(0, next.routes.legs.size - 1))
        held = held.coerceIn(0, max(0, next.held.size - 1))
        parcel = parcel.coerceIn(0, max(0, next.parcels.size - 1))
    }

    /** The town the Word page opens at: where the trader stands, else the freshest news. */
    private fun first(v: TallyView): Long = v.towns.firstOrNull { it.capital == v.here }?.capital ?: v.towns.firstOrNull()?.capital ?: TallyRoutes.NONE

    /** Picks the town [capital] on the Word page: its detail is turned to, and the hourglass is turned over to show how old its news is. */
    fun pick(capital: Long) {
        if (capital == town || view.towns.none { it.capital == capital }) return
        glassFrom = sand(selected)
        town = capital
        pickedAt = t
        glassAt = t
        env.select()
        if (page == WORD) env.sound("trade.hourglass", 1f, 0.6f)
    }

    /** How far the sand of a sight has run: 0 for news of this moment, 1 for news of three days, when it is struck through. */
    fun sand(town: TallyTown?): Double = if (town == null) 0.0 else (age(town) / STRIKE).coerceIn(0.0, 1.0)

    /**
     * Whether the moment [at] of a drawing has come that was not yet seen to by [key]: true once, the first time it is asked after [at] (and no more than
     * [grace] seconds after it: a sound that is late for its moment is not played).
     */
    fun due(key: String, at: Double, grace: Double = 0.4): Boolean {
        if (t < at) return false
        if (!fired.add(key)) return false
        return t - at <= grace
    }

    /** The Standing page shows the rung that was climbed to while it was not showing: its knot is tied from [at]. */
    fun seeTie(at: Double) {
        if (tieSeen) return
        tieSeen = true
        if (tiedAt > -1e8) tiedAt = max(tiedAt, at)
    }

    // ---- Picks ----

    /** Picks the way [i] on the Routes page. */
    fun pickLeg(i: Int) {
        val to = i.coerceIn(0, max(0, view.routes.legs.size - 1))
        if (to == leg) return
        leg = to
        legAt = t
        env.select()
    }

    /** Picks the ticket [i] of the Held page. */
    fun pickHeld(i: Int) {
        val to = i.coerceIn(0, max(0, view.held.size - 1))
        if (to == held) return
        held = to
        heldAt = t
        env.select()
    }

    /** Picks the parcel [i] of the Ledger page. */
    fun pickParcel(i: Int) {
        val to = i.coerceIn(0, max(0, view.parcels.size - 1))
        if (to == parcel) return
        parcel = to
        parcelAt = t
        env.select()
    }

    /** Asks the book to turn to [page] (the stage does it). */
    fun go(page: Int) {
        want = page.coerceIn(0, Book.PAGES - 1)
    }

    /** The stage has turned to what the book asked: nothing is asked now. */
    fun went() {
        want = -1
    }

    // ---- Pages ----

    /** Opens the book at page [to] from [at] (the stage turns the page's content over when the leaf is half over, so the page's own clock starts then). */
    fun turn(to: Int, at: Double = t) {
        val next = to.coerceIn(0, Book.PAGES - 1)
        if (next == page) return
        page = next
        pageAt = at
        lastPage = next
    }

    // ---- Asking ----

    /** Asks the server for fresh news, no more than every [EVERY] seconds. */
    fun refresh() {
        if (t - refreshed < EVERY) return
        refreshed = t
        env.send(TallyActPayload.REFRESH, 0L)
    }

    /** Files the Tally's sight of [town] as a report at the board of the town the trader stands in. */
    fun report(town: Long) {
        asked = town
        askedAt = t
        env.send(TallyActPayload.REPORT, town)
    }

    /** Books the tickets of the route to [town]. */
    fun book(town: Long) {
        env.send(TallyActPayload.BOOK, town)
    }

    val selected: TallyTown? get() = view.towns.firstOrNull { it.capital == town }

    companion object {
        const val WORD = 0
        const val ROUTES = 1
        const val HELD = 2
        const val LEDGER = 3
        const val STANDING = 4

        /** Seconds between the open screen's questions to the server (it answers one in two seconds at most). */
        const val EVERY = 5.0

        /** The news of three days, in ticks: at it a sight is struck through and the sand of its glass has all run. */
        const val STRIKE = 72_000.0

        /** When, after the book opens, a ring pulses for a sight fresher than the one this session last showed (the rings have drawn themselves by then). */
        const val OPEN_PULSE = 1.1

        /** A day's ticks, and the Routes' own figures: how many blocks an hour's walk covers, and the hours spent at the stalls at the end of a way. */
        const val DAY = 24_000.0
        const val WALK = Routes.WALK
        const val VISIT = Routes.VISIT

        /** The page the book was last open at, and the rung it last showed, for the session. */
        var lastPage = 0
        var shownRung = -1

        /** The tick of the sight of each town the session last showed, to tell at the next opening that a sight is fresher. */
        val seenTick = HashMap<Long, Long>()

        /** A parcel's name in the maps above: its good and where it came from. */
        fun key(p: TallyParcel): String = "${p.good}@${p.origin}"
    }
}
