package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.client.townsfolk.TownsfolkLooks
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import net.minecraft.core.BlockPos
import kotlin.math.abs

/**
 * The numbers the locket tale keeps in `Tale.n`, by their place (`Locket.numbers` names them for `tale show`), and the values they hold.
 * Nothing else of the tale is kept but the cast and the tale's own reasons: the three facts of the thief and what each of the three who
 * were about shows are these numbers.
 */
internal object LocketSlots {
    /** Which of the three sightings have been heard (bits of [SAW_SIDE], [SAW_PIECE], [SAW_CHILD]); all three once the sill is read. */
    const val WORDS = 0

    /** Where the locket is: [WITH_CULPRIT], [WITH_PLAYER] (the culprit gave it over) or [HOME]. It is never an item. */
    const val LOCKET = 1

    /** The side of the owner's door the culprit's door is on (an index of [LocketFacts.SIDES]). */
    const val SIDE = 2

    /** The piece the culprit shows, which the two decoys wear too (an index of [LocketFacts.PIECES]). */
    const val PIECE = 3

    /** What the two decoys show, packed by [LocketFacts.pack]: decoy_a in the low six bits, decoy_b in the six above. */
    const val DECOYS = 4

    /**
     * The faction tools spent ([READ], [LAW_TOOL], [QUIET_TOOL]), whether the theft was set down in the town's record ([NOTED]), and
     * which decoys have been wrongly asked already ([WRONG_A], [WRONG_B]: a suspect is asked once, and the slight is the only cost).
     */
    const val TOOLS = 5

    /** The way the locket goes back: [BREAD], [FOUND], [STREET], [LAW], [QUIET] or [SILL] (none until it is chosen, or the world chooses). */
    const val WAY = 6

    /** Whether the scene of the return has played through (1). */
    const val PLAYED = 7

    const val SAW_SIDE = 1
    const val SAW_PIECE = 2
    const val SAW_CHILD = 4
    const val ALL_WORDS = 7

    const val READ = 1
    const val LAW_TOOL = 2
    const val QUIET_TOOL = 4
    const val NOTED = 8
    const val WRONG_A = 16
    const val WRONG_B = 32

    const val NONE = 0
    const val BREAD = 1
    const val FOUND = 2
    const val STREET = 3
    const val LAW = 4
    const val QUIET = 5
    const val SILL = 6

    const val WITH_CULPRIT = 0
    const val WITH_PLAYER = 1
    const val HOME = 2

    /** The steps by their place in the table: ask, the three words, find the thief, choose how it goes back, the return. */
    const val AT_ASK = 0
    const val AT_WORDS = 1
    const val AT_FIND = 2
    const val AT_CHOOSE = 3
    const val AT_RETURN = 4
}

/** The cast slots of the locket. */
internal object LocketCast {
    const val OWNER = "owner"
    const val CULPRIT = "culprit"
    const val DECOY_A = "decoy_a"
    const val DECOY_B = "decoy_b"
    const val WITNESS_1 = "witness1"
    const val WITNESS_2 = "witness2"
    const val WITNESS_3 = "witness3"

    /** The three who were about, in the order the cast is made. */
    val SUSPECTS: List<String> = listOf(CULPRIT, DECOY_A, DECOY_B)

    /** The two who were about and did not take it. */
    val DECOYS: List<String> = listOf(DECOY_A, DECOY_B)

    /** The three who saw, and the word each gives (the bit of [LocketSlots.WORDS]). */
    val WITNESSES: Map<String, Int> = linkedMapOf(
        WITNESS_1 to LocketSlots.SAW_SIDE,
        WITNESS_2 to LocketSlots.SAW_PIECE,
        WITNESS_3 to LocketSlots.SAW_CHILD,
    )
}

/**
 * The three facts of the thief (design 8.11): the side of the owner's door the thief's door is on (F1), the piece of their look (F2) and the
 * child in their household (F3). The culprit has all three; decoy_a has F1 and F2 but no child; decoy_b has F2 and the child but is on another
 * side. The facts are read from the look table the server already reads for names and voices, and from where the doors are.
 */
internal object LocketFacts {
    /** The four sides, as [sideOf] counts them: -z is north. */
    val SIDES: List<String> = listOf("north", "east", "south", "west")

    /** The pieces of a look that can be told apart from a street away. */
    val PIECES: List<String> = listOf("cap", "basket", "beard", "kerchief_knot", "hair_bun", "ponytail")

    /** The piece number that means none of [PIECES]. */
    const val NO_PIECE = 7

    /** The parts the look [look] of [role] shows, from the look table (none for a look the table does not have). */
    fun parts(role: TownRole, look: Int): Set<String> = TownsfolkLooks.BY_ROLE[role.id]?.getOrNull(look)?.parts ?: setOf()

    /** The pieces [r] shows, in the order of [PIECES]. */
    fun pieces(r: Resident): List<String> = PIECES.filter { it in parts(r.role, r.look) }

    /** Whether [r] shows [piece]. */
    fun wears(r: Resident, piece: String): Boolean = piece in parts(r.role, r.look)

    /** The side of the door [from] that the door [to] is on: the larger axis of the difference between them (0 north, 1 east, 2 south, 3 west). */
    fun sideOf(from: BlockPos, to: BlockPos): Int {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (abs(dx) >= abs(dz)) (if (dx >= 0) 1 else 3) else (if (dz >= 0) 2 else 0)
    }

    /** Where a home is told from: its front door, else the middle of the building. */
    fun door(lodging: CityPlaces.Lodging): BlockPos = lodging.front?.pos ?: lodging.box.center

    /** The piece the record names, or "" if the number is outside the table. */
    fun piece(t: Tale): String = PIECES.getOrElse(t.n[LocketSlots.PIECE]) { "" }

    /** What one of the three who were about shows: the side of their door, the piece they wear ("" for none of the six) and whether a child is at home. */
    class Alibi(val side: Int, val piece: String, val child: Boolean)

    /** A decoy's [side], [child] and [piece] (an index of [PIECES], or [NO_PIECE]) in six bits. */
    fun pack(side: Int, child: Boolean, piece: Int): Int = (side and 3) or (if (child) 4 else 0) or ((piece and 7) shl 3)

    /** The six bits of the decoy in [slot] within [packed]. */
    private fun bits(packed: Int, slot: String): Int = if (slot == LocketCast.DECOY_B) (packed shr 6) and 63 else packed and 63

    /** [packed] with the decoy in [slot] set to [six] bits. */
    fun with(packed: Int, slot: String, six: Int): Int =
        if (slot == LocketCast.DECOY_B) (packed and (63 shl 6).inv()) or ((six and 63) shl 6) else (packed and 63.inv()) or (six and 63)

    /** The facts of the suspect in [slot] of [t] (the culprit's are the thief's; the decoys' fall short by one), or null for a slot that is not a suspect. */
    fun alibi(t: Tale, slot: String): Alibi? = when (slot) {
        LocketCast.CULPRIT -> Alibi(t.n[LocketSlots.SIDE], piece(t), true)
        LocketCast.DECOY_A, LocketCast.DECOY_B -> {
            val b = bits(t.n[LocketSlots.DECOYS], slot)
            Alibi(b and 3, PIECES.getOrElse((b shr 3) and 7) { "" }, b and 4 != 0)
        }
        else -> null
    }

    /** Whether the suspect in [slot] shows all three of the thief's facts (only the culprit does, unless a stand-in happens to). */
    fun fits(t: Tale, slot: String): Boolean {
        val a = alibi(t, slot) ?: return false
        return a.side == t.n[LocketSlots.SIDE] && a.piece == piece(t) && a.child
    }

    /** How many sightings the bits [words] hold. */
    fun count(words: Int): Int = Integer.bitCount(words and LocketSlots.ALL_WORDS)
}
