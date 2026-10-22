package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.network.TallyActPayload
import io.github.vaspyyy.guildmark.network.TallyParcel
import io.github.vaspyyy.guildmark.network.TallyRoutes
import io.github.vaspyyy.guildmark.network.TallyTown
import io.github.vaspyyy.guildmark.network.TallyView
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
    var leg = 0
    var held = 0
    var parcel = 0

    /** When a town's sight came fresher than the one the screen had (its ring pulses, its glass turns), or the town first showed. */
    val pulsed = HashMap<Long, Double>()
    val shown = HashMap<Long, Double>()

    /** When a parcel's notch was last cut (by its good and origin), and the days it had left before. */
    val cut = HashMap<String, Double>()
    val spent = HashMap<String, Int>()

    /** When a rung was last climbed to (the knot is tied), and when a report was last filed (the stamp comes down) or tickets were last booked. */
    var tiedAt = -1e9
    var tiedRung = -1
    var filedAt = -1e9
    var filedTown = TallyRoutes.NONE
    var bookedAt = -1e9

    /** The town a report was last asked for, to answer it when the next view says it went. */
    private var asked = TallyRoutes.NONE
    private var askedAt = -1e9

    /** When the server was last asked for news. */
    private var refreshed = 0.0

    init {
        for (town in view.towns) shown[town.capital] = 0.0
        for (p in view.parcels) spent[key(p)] = p.left
        this.town = pick(view)
        if (shownRung < 0) shownRung = view.standing.rung
        tiedRung = view.standing.rung
        if (view.standing.rung > shownRung) {
            tiedAt = 0.55
            shownRung = view.standing.rung
        }
    }

    // ---- Time ----

    /** The game's clock now, in ticks, as far as the screen can tell from the last view and the time since. */
    fun ticks(): Double = view.now + (t - viewAt) * 20.0

    /** How old the news of [town] is, in ticks: none if it is live. */
    fun age(town: TallyTown): Double = if (town.live) 0.0 else max(0.0, ticks() - town.tick)

    /** How long ago the screen was last given a view, in seconds. */
    fun stale(): Double = t - viewAt

    // ---- What a new view changes ----

    /** The server sent a newer [next]: what it changes in what is shown sets those things going. */
    fun update(next: TallyView) {
        val before = view
        val old = before.towns.associateBy { it.capital }
        for (town in next.towns) {
            val was = old[town.capital]
            if (was == null) shown[town.capital] = t
            else if (town.tick > was.tick && !town.live) pulsed[town.capital] = t
        }
        // A day gone from a parcel's days: its notch is cut
        for (p in next.parcels) {
            val k = key(p)
            val had = spent[k]
            if (had != null && p.left < had) {
                cut[k] = t
                if (page == LEDGER) env.sound("trade.tally_notch", 1f, 0.7f)
            }
            spent[k] = p.left
        }
        if (next.standing.rung > before.standing.rung && next.standing.rung > shownRung) {
            tiedAt = t
            tiedRung = next.standing.rung
            shownRung = next.standing.rung
            if (page == STANDING) env.sound("trade.seal_press", 1f, 0.7f)
        }
        // A report that went (one fewer left, and the town no longer asks it) and tickets that came
        if (next.reports < before.reports && asked != TallyRoutes.NONE && t - askedAt < 8.0) {
            filedAt = t
            filedTown = asked
            asked = TallyRoutes.NONE
        }
        if (next.held.size > before.held.size) bookedAt = t
        view = next
        viewAt = t
        if (town == TallyRoutes.NONE || next.towns.none { it.capital == town }) town = pick(next)
        leg = leg.coerceIn(0, max(0, next.routes.legs.size - 1))
        held = held.coerceIn(0, max(0, next.held.size - 1))
        parcel = parcel.coerceIn(0, max(0, next.parcels.size - 1))
    }

    /** The town the Word page opens at: where the trader stands, else the freshest news. */
    private fun pick(v: TallyView): Long = v.towns.firstOrNull { it.capital == v.here }?.capital ?: v.towns.firstOrNull()?.capital ?: TallyRoutes.NONE

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

        /** The page the book was last open at, and the rung it last showed, for the session. */
        var lastPage = 0
        var shownRung = -1

        /** A parcel's name in the maps above: its good and where it came from. */
        fun key(p: TallyParcel): String = "${p.good}@${p.origin}"
    }
}
