package io.github.vaspyyy.guildmark.trade

/** The rungs of the trader's ladder (living-world/trader/design.md, Part 5), lowest first; the ids are what is saved in [TradeBooks.Book.rung]. */
enum class Rung(val id: String) {
    PEDLAR("pedlar"),
    HAWKER("hawker"),
    CHAPMAN("chapman"),
    FACTOR("factor"),
    HOUSE("house"),
    ;

    companion object {
        /** The rung with the id [id]; the first if it is not one. */
        fun byId(id: String): Rung = entries.firstOrNull { it.id == id } ?: PEDLAR
    }
}

/** The small bonuses of the Trade tree that reach the trader's numbers (Part 5), each read in one line where it is used. */
enum class Perk {
    /** The share added to a ticket's pay at delivery (0.05 is five per cent more). */
    HAUL_PAY,

    /** Hours taken off the age of the Word. */
    WORD_FRESH,

    /** The share taken off the Hunt's chance (0.1 is a tenth less). */
    ROAD_WARY,
}

/**
 * What the Tally's Standing page draws: the rung a player holds and what the next asks, each ask a tick when it is met and a bar
 * while it is being worked towards. An empty list is a rung with nothing more to ask, or no asks known yet.
 */
data class RungView(val rung: Rung, val asks: List<Ask>) {
    /**
     * One thing the next rung asks: [key] is its words (a lang key, with no argument in it, so that a page may print it as it is), [have]
     * how far along it is (never above [need]) and [need] what it takes.
     */
    data class Ask(val key: String, val have: Int, val need: Int) {
        val met: Boolean get() = have >= need
    }
}

/**
 * The ladder's rule (living-world/trader/design.md, Part 5; build part F1), with nothing of the game in it: it reads a [Facts] record and
 * answers, so that every row of the ladder can be tried in a plain JVM. [Rungs] gathers the facts from the world and asks.
 *
 * - **Hawker**: Known (a rank of 2) at two capitals and [HAWKER_DELIVERIES] deliveries.
 * - **Chapman**: the Carrier's Warrant, which the hall's counter sells to someone Trusted (3) at one capital, Known at three and with
 *   [CHAPMAN_DELIVERIES] deliveries. Holding the Warrant is what makes a Chapman: it is the one thing those asks buy.
 * - **Factor**: the Warrant, a Store standing in one capital and a stall (a rented table or their own pitch) in another.
 * - **House**: the Warrant and a Counting House standing.
 *
 * Wherever a number of capitals is asked, a world with fewer capitals open than that asks for all of them (and for one at the least), so
 * that no world is left with a rung it cannot climb to. Standing at Known or better counts towards "Known at"; Trusted is better than Known.
 * Rungs are earned once and kept: [rung] never answers lower than the rung in the facts (only an operator's command sets one lower).
 */
object Ladder {
    /** The supplier ranks the asks name, as `SupplierLink.rank` answers them (whole ranks: 0 a stranger up to 4 a purveyor). */
    const val KNOWN = 2
    const val TRUSTED = 3

    /** Hawker: Known at this many capitals, and this many deliveries. */
    const val HAWKER_KNOWN = 2
    const val HAWKER_DELIVERIES = 6

    /** Chapman: Trusted at this many capitals, Known at this many, and this many deliveries, then the Warrant. */
    const val CHAPMAN_TRUSTED = 1
    const val CHAPMAN_KNOWN = 3
    const val CHAPMAN_DELIVERIES = 12

    /** What the Warrant costs at the hall's counter, in Guild Marks. */
    const val WARRANT_PRICE = 60

    // ---- Words: the lang keys of what a rung asks (the Standing page prints them), and of what the counter says is missing ----

    const val ASK_KNOWN = "gui.guildmark.trade.ladder.ask.known"
    const val ASK_TRUSTED = "gui.guildmark.trade.ladder.ask.trusted"
    const val ASK_DELIVERIES = "gui.guildmark.trade.ladder.ask.deliveries"
    const val ASK_WARRANT = "gui.guildmark.trade.ladder.ask.warrant"
    const val ASK_STORE = "gui.guildmark.trade.ladder.ask.store"
    const val ASK_STALL = "gui.guildmark.trade.ladder.ask.stall"
    const val ASK_HOUSE = "gui.guildmark.trade.ladder.ask.house"

    const val WARRANT_HELD = "gui.guildmark.trade.ladder.warrant.held"
    const val WARRANT_NEEDS_TRUSTED = "gui.guildmark.trade.ladder.warrant.trusted"
    const val WARRANT_NEEDS_KNOWN = "gui.guildmark.trade.ladder.warrant.known"
    const val WARRANT_NEEDS_KNOWN_ALL = "gui.guildmark.trade.ladder.warrant.known_all"
    const val WARRANT_NEEDS_DELIVERIES = "gui.guildmark.trade.ladder.warrant.deliveries"

    /**
     * What the rule knows of one player. [rung] is the rung they hold; [deliveries] the tickets they have filled (a parcel counting 1, a
     * consignment 2, a relief 3); [warrant] whether the book has them down for a Warrant; [ranks] their supplier rank at each open capital,
     * by the capital's key (so its size is the number of capitals open); [stores], [stalls] and [houses] the capitals, by key, where they have
     * a Carriers' Store, a table or pitch of their own and a Counting House; [stallsOn] whether stalls can be had at all (with them switched
     * off the Factor's stall is not asked, as with standing off the standing asks pass).
     */
    data class Facts(
        val rung: Rung = Rung.PEDLAR,
        val deliveries: Int = 0,
        val warrant: Boolean = false,
        val ranks: Map<Long, Int> = emptyMap(),
        val stores: Set<Long> = emptySet(),
        val stalls: Set<Long> = emptySet(),
        val houses: Set<Long> = emptySet(),
        val stallsOn: Boolean = true,
    )

    /**
     * The rung [f] holds: the highest whose asks it meets, and never lower than the rung it already holds.
     *
     * A Warrant is a Chapman (the counter sells it only to someone who met the asks, and a given one is as good), whatever the standing is
     * now; a Store of theirs and a stall in another capital make a Factor; a Counting House makes the House. Without a Warrant, the asks
     * of the Hawker are all there is to meet.
     */
    fun rung(f: Facts): Rung {
        val earned = when {
            f.warrant && f.houses.isNotEmpty() -> Rung.HOUSE
            f.warrant && factor(f) -> Rung.FACTOR
            f.warrant -> Rung.CHAPMAN
            hawker(f) -> Rung.HAWKER
            else -> Rung.PEDLAR
        }
        return if (earned > f.rung) earned else f.rung
    }

    /**
     * What [next] asks of [f], each ask with how far along it is, in the order the Standing page shows them; empty for a rung nobody
     * climbs to (the first). The Warrant's own ask is met when the book has them down for one.
     */
    fun asks(f: Facts, next: Rung): List<RungView.Ask> = when (next) {
        Rung.PEDLAR -> listOf()
        Rung.HAWKER -> listOf(
            ask(ASK_KNOWN, known(f), capitals(HAWKER_KNOWN, f.ranks.size)),
            ask(ASK_DELIVERIES, f.deliveries, HAWKER_DELIVERIES),
        )
        Rung.CHAPMAN -> listOf(
            ask(ASK_TRUSTED, trusted(f), CHAPMAN_TRUSTED),
            ask(ASK_KNOWN, known(f), capitals(CHAPMAN_KNOWN, f.ranks.size)),
            ask(ASK_DELIVERIES, f.deliveries, CHAPMAN_DELIVERIES),
            ask(ASK_WARRANT, if (f.warrant) 1 else 0, 1),
        )
        Rung.FACTOR -> listOf(
            ask(ASK_STORE, if (f.stores.isNotEmpty()) 1 else 0, 1),
            ask(ASK_STALL, if (stalled(f)) 1 else 0, 1),
        )
        Rung.HOUSE -> listOf(ask(ASK_HOUSE, if (f.houses.isNotEmpty()) 1 else 0, 1))
    }

    /**
     * What stands between [f] and buying the Warrant at the counter: null if nothing does, else the lang key of the first thing that
     * does (the Warrant held already, then the asks in the order the design gives them). The Warrant is bought, not earned, so it is not
     * among its own asks.
     */
    fun warrantMissing(f: Facts): String? = when {
        f.warrant -> WARRANT_HELD
        trusted(f) < CHAPMAN_TRUSTED -> WARRANT_NEEDS_TRUSTED
        known(f) < capitals(CHAPMAN_KNOWN, f.ranks.size) -> if (f.ranks.size < CHAPMAN_KNOWN) WARRANT_NEEDS_KNOWN_ALL else WARRANT_NEEDS_KNOWN
        f.deliveries < CHAPMAN_DELIVERIES -> WARRANT_NEEDS_DELIVERIES
        else -> null
    }

    /** How many tickets [rung] may hold booked at once. */
    fun heldLimit(rung: Rung): Int = when (rung) {
        Rung.PEDLAR -> 2
        Rung.HAWKER -> 3
        Rung.CHAPMAN -> 4
        Rung.FACTOR -> 6
        Rung.HOUSE -> 8
    }

    /** How many guards [rung] may have hired at once. */
    fun guardLimit(rung: Rung): Int = when (rung) {
        Rung.PEDLAR -> 0
        Rung.HAWKER -> 1
        Rung.CHAPMAN -> 2
        Rung.FACTOR -> 3
        Rung.HOUSE -> 3
    }

    /** Whether [rung] may take consignments, relief and feast tickets; a parcel anyone may take. */
    fun bigTickets(rung: Rung): Boolean = rung >= Rung.CHAPMAN

    /**
     * The most [perk] may come to in all, whatever the tree and anything added to it later put together: a tenth for the pay (the design's
     * ceiling), twelve hours for the Word, and half of the Hunt's chance.
     */
    fun most(perk: Perk): Double = when (perk) {
        Perk.HAUL_PAY -> 0.10
        Perk.WORD_FRESH -> 12.0
        Perk.ROAD_WARY -> 0.5
    }

    /** What a [total] of the tree's bonus comes to as [perk]: never below none (nor a number that is not one), and never above [most]. */
    fun capped(perk: Perk, total: Double): Double = if (total.isNaN()) 0.0 else maxOf(0.0, minOf(total, most(perk)))

    // ---- The pieces ----

    /** How many capitals an ask for [wanted] of them means with [open] open: that many, or all that are open if fewer, and one at the least. */
    fun capitals(wanted: Int, open: Int): Int = minOf(wanted, maxOf(open, 1))

    /** At how many capitals [f] is Known or better, and at how many Trusted or better. */
    fun known(f: Facts): Int = f.ranks.values.count { it >= KNOWN }
    fun trusted(f: Facts): Int = f.ranks.values.count { it >= TRUSTED }

    private fun hawker(f: Facts): Boolean = known(f) >= capitals(HAWKER_KNOWN, f.ranks.size) && f.deliveries >= HAWKER_DELIVERIES

    /** Whether they have a Store, and a stall in a capital other than one of the Stores' (or any, with stalls not to be had). */
    private fun factor(f: Facts): Boolean = f.stores.isNotEmpty() && stalled(f)

    /**
     * Whether the Factor's stall is in hand, for the page's tick: not asked at all with stalls off; while there is no Store yet, any stall
     * is a start (the Store goes up in another capital); once there is one, a stall elsewhere than at a Store.
     */
    private fun stalled(f: Facts): Boolean = !f.stallsOn || if (f.stores.isEmpty()) f.stalls.isNotEmpty() else elsewhere(f)

    /** Whether some stall of theirs is in a different capital from some Store of theirs. */
    private fun elsewhere(f: Facts): Boolean = f.stores.any { store -> f.stalls.any { it != store } }

    private fun ask(key: String, have: Int, need: Int) = RungView.Ask(key, minOf(maxOf(have, 0), need), need)
}
