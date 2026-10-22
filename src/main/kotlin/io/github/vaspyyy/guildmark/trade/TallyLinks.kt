package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Where the Word and the Tally (build part B) meet the haul tickets (build part A), in one place, so that B can be built, tried and merged
 * before A is in, and the lead joins them with one edit when it is. Everything here answers its default until then: no ticket is
 * open anywhere, none is booked, and the Tally's Book button is greyed.
 *
 * **To join them** once part A's `Hauls` is in: set [HAULS] to true, and give [open], [held], [book] and [filled] the one body their comments
 * name (written against A's real `Hauls`, `Booking` and `Fill`). Nothing else of B calls A. ([ledger] already answers from the market ledgers'
 * own records and the book's `held` list, which is what A's `Ledger.lines(level, player.uuid)` reads, so it needs no swap; if A ever keeps the
 * days differently, that call gives each parcel with its days left and whether it is one of the book's, to map as [ledger] does.)
 */
object TallyLinks {
    /** Whether part A is in: the tickets are real, and the Routes page's Book button can book them. Greyed while it is false. */
    val HAULS: Boolean = false

    /**
     * Some of [pay] for [units] of the [of] a ticket asks for: what part of a ticket pays, to the whole Mark. A ticket's pay is for all of its units, and a
     * trader who is told of it (or books some of it) is told of the part that is theirs to take.
     */
    fun part(pay: Int, units: Int, of: Int): Int = if (of <= 0 || units <= 0) 0 else Math.round(pay.toDouble() * units.coerceAtMost(of) / of).toInt()

    /**
     * The tickets open at the capital whose heart is [capital] now, as the Word keeps them: the units nobody has booked, and what those pay. Part A:
     * `Hauls.open(level, capital).map { h -> Sight.Ticket(h.id, h.good, h.open, part(h.pay, h.open, h.units), h.shop, h.due, h.kind) }`.
     */
    fun open(level: ServerLevel, capital: BlockPos): List<Sight.Ticket> = listOf()

    /** A ticket [player] holds booked: the [haul] as it stands and the units of it [owes] that are still to come in on their booking. */
    data class Held(val haul: Haul, val owes: Int)

    /**
     * The tickets [player] holds booked, at any capital, soonest due first. Part A (its `Hauls.held` only counts them): `Hauls.all(level).mapNotNull { h ->
     * Hauls.bookedOn(level, player.uuid, h.id)?.let { Held(h, it.left) } }.sortedBy { it.haul.due }`.
     */
    fun held(level: ServerLevel, player: ServerPlayer): List<Held> = listOf()

    /**
     * [player] books [units] of ticket [haul] (as the Word last heard of it) at its capital, checked against the town now, as the shop's
     * Take button does. Gives how it went. Part A: `Hauls.book(level, haul, Taker.Player(player.uuid), units, player.gameProfile.name)` gives
     * null if it was made ([Booked.DONE]), else why not as a key: `gone` is [Booked.GONE], `off` is [Booked.NOT_YET], and any other (`taken`,
     * `yours`, `warrant`, `first_call`, `limit`, `poor`) is [Booked.REFUSED].
     */
    fun book(level: ServerLevel, player: ServerPlayer, haul: Int, capital: BlockPos, units: Int): Booked = Booked.NOT_YET

    /** A delivery [player] made at a capital that its people still thank them for: [units] of [good], on the town's day [day]. */
    class Filled(val good: Good, val units: Int, val day: Long)

    /**
     * The last ticket [player] filled at the capital whose heart is [capital] on the town's day [since] or later, for the town's thanks (the talk's
     * `trade.thanks`); null if none. Part A: from `Hauls.fills(capital)`, the last two days' fills of each capital, which name who filled them by name and
     * not by id: `val name = level.server.playerList.getPlayer(player)?.gameProfile?.name ?: return null`, then `Hauls.fills(capital).filter { it.who == name
     * && Days.dayOf(it.hour * Word.HOUR) >= since }.maxByOrNull { it.hour }?.let { Filled(it.good, it.count, Days.dayOf(it.hour * Word.HOUR)) }`.
     */
    fun filled(level: ServerLevel, capital: BlockPos, player: UUID, since: Long): Filled? = null

    /** How a booking asked for from the Tally went. */
    enum class Booked(val key: String) {
        /** Booked. */
        DONE("message.guildmark.trade.tally.booked"),

        /** The ticket is gone (filled, withdrawn, lapsed) or was never there: the Word was old. Nothing was taken. */
        GONE("message.guildmark.trade.tally.gone"),

        /** The ticket is there but this player may not take it (their rung, tickets held, a deposit they cannot pay). Nothing was taken. */
        REFUSED("message.guildmark.trade.tally.refused"),

        /** Part A is not in yet. */
        NOT_YET("message.guildmark.trade.tally.not_yet"),
    }

    /** One lot of the hauled ledger as the Ledger page tells it: the [parcel], which stays hauled for [of] days from the day it was bought, in a Store's keeping if [bonded]. */
    class Hauled(val parcel: Parcel, val of: Int, val bonded: Boolean)

    /**
     * The hauled goods [player] has bought and not sold back, oldest first (the Ledger page lays them against what they carry): what each capital's
     * market remembers of their purchases for [Suppliers.KEPT] days (`Standing.bought`), and the parcels a Store or a delivery put back into their
     * record ([TradeBooks.Book.held]), which keep for thirty days. Reads only. Nothing is [Hauled.bonded] here: a Store's own goods are not carried, so they
     * are not in this list (part F2 may bring them in). Part A's `Ledger.lines(level, player.uuid)` lists the same parcels.
     */
    fun ledger(level: ServerLevel, player: ServerPlayer): List<Hauled> {
        val today = Days.dayOf(level.overworldClockTime)
        val out = ArrayList<Hauled>()
        if (Markets.enabled()) {
            for ((capital, standing) in Suppliers.get(level).everywhere(player.uuid)) {
                for (b in standing.bought) {
                    if (b.count <= 0 || b.day <= today - Suppliers.KEPT || b.good.item == null) continue
                    out.add(Hauled(Parcel(b.good, b.count, capital, minOf(b.day, today)), Suppliers.KEPT.toInt(), false))
                }
            }
        }
        peekBooks(level)?.get(player.uuid)?.held?.forEach { out.add(Hauled(it, KEPT_BONDED, false)) }
        return out.filter { it.of - (today - it.parcel.boughtAt) > 0 }.sortedBy { it.parcel.boughtAt }
    }

    /** The days a parcel put back into a trader's record stays hauled (the Store's thirty). */
    private const val KEPT_BONDED = 30
}
