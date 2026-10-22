package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.townsfolk.streets.AlmsTable
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The doors a dish is sold at and what each takes of a day (living-world/cook/design.md 5.2): the shares of the town's
 * meals, the most of one kind, the 70% rule when cooks share a door, the first of a kind, and the whole Marks a sale is
 * paid in with the fraction it carries. Plain arithmetic on a [Rec] and numbers, with no world and no market, so the
 * ledger, the commands and the checks against the model (`cook_model.py`, `analysis.py`) all read the same rules.
 *
 * A door's cap is told in whole portions, rounded to the nearest: a medium capital (103 people, 309 meals a day) has an
 * inn of 25 portions (24.72), 9 of any one kind (8.65), a bakery of 9 (9.27) and 5 of a kind (4.64). The model counts in
 * fractions of a portion; the rounding is under half a portion a door a day (the comparison is in the Part G guide).
 */
object Doors {
    /** A first batch of a kind that a player sells in a capital pays this many times over (5.2), once. */
    const val FIRST = 2.0

    /** What the inn pays for a kind it is not asking for today, as a share of the pay of a wanted one. */
    const val UNWANTED = 0.75

    /** A request pays this many times the value, with no standing's bonus. */
    const val REQUEST = 1.15

    /** The cookshop pays this share of the value: the 10% cut (6.4). Part H's, kept here with the other doors' pay. */
    const val SHOP_PAY = 0.90

    /** With another cook about a door, a player takes at most this share of its cap in a day. */
    const val SHARED = 0.70

    /** How many days back another cook counts as about: today and yesterday. */
    const val ABOUT = 1L

    /** Meal hours in a day; a door serves a third of its cap at each. */
    const val MEALS = 3.0

    /** The share of the town's day meals [door] takes (the inn 8%, the bakery 3%, a cookshop 2.5%; the alms table has the pot's room, not a share). */
    fun share(door: Door): Double = when (door) {
        Door.INN -> 0.08
        Door.BAKERY -> 0.03
        Door.SHOP -> 0.025
        Door.ALMS -> 0.0
    }

    /** The most of [door]'s cap one kind may take (the inn 35%, the bakery 50%, a cookshop 40%). */
    fun oneKind(door: Door): Double = when (door) {
        Door.INN -> 0.35
        Door.BAKERY -> 0.50
        Door.SHOP -> 0.40
        Door.ALMS -> 1.0
    }

    /** The meals a town of [people] eats in a day: [people] x [Economy.MEALS]. */
    fun meals(people: Int): Double = people * Economy.MEALS

    /** What [door] takes of a day in portions before rounding (24.72 at a medium capital's inn); the alms table's is the pot. */
    fun exact(door: Door, people: Int): Double = if (door == Door.ALMS) AlmsTable.POT.toDouble() else meals(people) * share(door)

    /** The door's cap for a day in whole portions, rounded to the nearest. */
    fun cap(door: Door, people: Int): Int = exact(door, people).roundToInt()

    /** The most of one kind [door] takes in a day, in whole portions. */
    fun kindCap(door: Door, people: Int): Int = (exact(door, people) * oneKind(door)).roundToInt()

    /** The most one player takes of [door]'s day when another cook is about, in whole portions. */
    fun sharedCap(door: Door, people: Int): Int = (exact(door, people) * SHARED).roundToInt()

    /** The portions [door] serves at each meal hour: a third of its day's cap, rounded up (9 at the inn, 4 at the bakery, 11 at the alms table). */
    fun serving(door: Door, people: Int): Int = ceil(exact(door, people) / MEALS).toInt()

    /** Whether [door] buys [kind] at all: the inn buys what the table of kinds sends to it (2.1), the bakery the baked ones. */
    fun takes(door: Door, kind: DishKind): Boolean = door == Door.ALMS || door in kind.doors

    /** Whether a cook other than [player] sold at [door] today or yesterday (so [player] may take only [SHARED] of its cap). */
    fun shared(rec: Rec, door: Door, player: UUID, today: Long): Boolean =
        rec.seen[door.ordinal].any { (cook, day) -> cook != player && today - day <= ABOUT }

    /** Portions of [door]'s day [player] has already taken. */
    fun taken(rec: Rec, door: Door, player: UUID): Int = rec.mine[player]?.getOrNull(door.ordinal) ?: 0

    /**
     * How many more portions of [kind] [door] will take from [player] today in a town of [people]: the door's room, the
     * kind's room and, when another cook is about, what is left of the player's 70%. Zero for a door that does not buy
     * the kind, a cookshop (it sells, never buys) and the alms table (its room is the pot's).
     */
    fun room(rec: Rec, door: Door, kind: DishKind, player: UUID, people: Int, today: Long): Int {
        if (door == Door.SHOP || door == Door.ALMS || !takes(door, kind)) return 0
        var room = cap(door, people) - rec.soldAt(door)
        room = minOf(room, kindCap(door, people) - rec.sold[door.ordinal][kind.ordinal])
        if (shared(rec, door, player, today)) room = minOf(room, sharedCap(door, people) - taken(rec, door, player))
        return room.coerceAtLeast(0)
    }

    /** Whether [player] has had the first-of-a-kind fee for [kind] in this capital. */
    fun tasted(rec: Rec, player: UUID, kind: DishKind): Boolean = ((rec.taster[player] ?: 0) and (1 shl kind.ordinal)) != 0

    /** Marks that [player] has had the first-of-a-kind fee for [kind] here. */
    fun taste(rec: Rec, player: UUID, kind: DishKind) {
        rec.taster[player] = (rec.taster[player] ?: 0) or (1 shl kind.ordinal)
    }

    /** A sale's pay made whole: the Marks that go into the hand and the fraction of a Mark that is carried. */
    class Count(val marks: Int, val carry: Double)

    /**
     * Pays [total] Marks with the [carry] the player has at the door: `floor(total + carry)` whole Marks, and what is
     * left over is carried, so nobody loses or gains a fraction (5.2). The tiny allowance keeps 4.9999999999 from being 4.
     */
    fun count(total: Double, carry: Double): Count {
        val sum = (total + carry).coerceAtLeast(0.0)
        val marks = floor(sum + 1.0e-9).toInt()
        return Count(marks, (sum - marks).coerceIn(0.0, 0.999999))
    }

    /** The fraction of a Mark [player] carries at [door] in [rec]. */
    fun carried(rec: Rec, door: Door, player: UUID): Double = rec.carry[player]?.getOrNull(door.ordinal) ?: 0.0
}
