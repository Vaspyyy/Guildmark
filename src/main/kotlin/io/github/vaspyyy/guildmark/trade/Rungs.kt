package io.github.vaspyyy.guildmark.trade

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

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
    /** One thing the next rung asks: [key] is its words (a lang key), [have] how far along it is and [need] what it takes. */
    data class Ask(val key: String, val have: Int, val need: Int) {
        val met: Boolean get() = have >= need
    }
}

/**
 * The ladder's gates, and what its rungs give (living-world/trader/design.md, Part 5; build part F1 replaces this file under
 * the same names). This is the stub the other parts are built against until then: every gate passes, no perk is given, and the
 * view is a Pedlar with nothing to ask, so that the parts after it can be built and tried with nothing in the way. Everything
 * they call is here: a part asks, it never works out a rung for itself.
 */
object Rungs {
    /** The most tickets held at once, and guards hired at once, at the top rung: what the stub allows everyone. */
    private const val HELD = 8
    private const val GUARDS = 3

    /** The rung [player] holds. */
    fun of(player: ServerPlayer): Rung = Rung.PEDLAR

    /** Whether [player] has climbed to [rung] (or higher). */
    fun reached(player: ServerPlayer, rung: Rung): Boolean = true

    /** How many tickets [player] may hold booked at once. */
    fun heldLimit(player: ServerPlayer): Int = HELD

    /** Whether [player] may take a ticket of this [kind]. */
    fun may(player: ServerPlayer, kind: HaulKind): Boolean = true

    /** How many guards [player] may have hired at once. */
    fun guardLimit(player: ServerPlayer): Int = GUARDS

    /** The size of [perk] for [player], as [Perk] says; 0.0 for none. */
    fun perk(player: ServerPlayer, perk: Perk): Double = 0.0

    /** Whether [player] has any of [perk]. */
    fun has(player: ServerPlayer, perk: Perk): Boolean = perk(player, perk) > 0.0

    /** What the Standing page shows [player]. */
    fun view(player: ServerPlayer): RungView = RungView(Rung.PEDLAR, listOf())

    /** Looks over [player]'s record and raises their rung if they have earned it (at login, at each delivery, when a Warrant, Store or House is got). Does nothing yet. */
    fun check(level: ServerLevel, player: ServerPlayer) {}
}
