package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.townsfolk.talk.Experience
import io.github.vaspyyy.guildmark.townsfolk.talk.Felt
import io.github.vaspyyy.guildmark.townsfolk.talk.Memory
import io.github.vaspyyy.guildmark.townsfolk.talk.Mind
import java.util.UUID
import kotlin.math.min

/**
 * How a resident's FEASTED remembers whose dish it was and of what kind (Hearthcraft, living-world/cook/design.md 8.2). A
 * [Felt] keeps one whole number, [Felt.about], and no player, so that number holds both: a tag of the cook's id (the same in
 * every session; two players that share a tag are one in eight million) above the dish's kind in the low six bits (its
 * ordinal; all ones for a dish whose kind was not told). A number from a build with more kinds reads as a kind this one has
 * not got, never as an error.
 *
 * A feast is a week's memory ([Experience.FEASTED]) among the four things that befell a resident that [Memory.felt] keeps, so
 * [keep] lets it take no more than [MOST] places, and none that a loss or a fright holds.
 */
internal object Feasted {
    /** The most feasts a resident keeps, the newest: a good supper never crowds out a death in the house or a robbery. */
    const val MOST = 2

    private const val KIND_BITS = 6
    private const val NO_KIND = (1 shl KIND_BITS) - 1
    private const val TAG_MASK = (1 shl 23) - 1

    /** What `cook perf` reads: the feasts kept since the server started. */
    @JvmField
    var kept = 0L

    /** The tag of [cook]: some of their id's bits, the same every time. */
    fun tag(cook: UUID): Int = (cook.mostSignificantBits xor cook.leastSignificantBits).hashCode() and TAG_MASK

    /** What FEASTED's `about` holds for a dish of [kind] (null: not told) that [cook] cooked. */
    fun about(cook: UUID, kind: DishKind?): Int = (tag(cook) shl KIND_BITS) or (kind?.ordinal ?: NO_KIND)

    /** Whether [felt] is a feast on a dish that [cook] cooked. */
    fun of(felt: Felt, cook: UUID): Boolean =
        felt.experience == Experience.FEASTED && felt.about >= 0 && (felt.about ushr KIND_BITS) == tag(cook)

    /** The kind of the dish [felt] is a feast on, if it was told and this build has it. */
    fun kind(felt: Felt): DishKind? = if (felt.about < 0) null else DishKind.entries.getOrNull(felt.about and NO_KIND)

    /** The feasts among [felt], newest first (it is as [Memory.felt] gives it). */
    fun among(felt: List<Felt>): List<Felt> = felt.filter { it.experience == Experience.FEASTED }

    /**
     * [mind] feasted on [day] on the dish [about] tells: the same dish of the same cook on the same day is kept once; a third
     * feast lets the oldest go; and with four losses and frights held already it is not kept at all. False if it was not.
     */
    fun keep(mind: Mind, day: Int, about: Int): Boolean {
        val live = Memory.felt(mind, day)
        val others = live.count { it.experience != Experience.FEASTED }
        val room = min(MOST, Memory.EXPERIENCES - others)
        if (room <= 0) return false
        val feasts = live.filter { it.experience == Experience.FEASTED && it.about != about }
        val excess = feasts.size + 1 - room
        if (excess > 0) mind.felt.removeAll(feasts.sortedBy { it.day }.take(excess).toSet())
        Memory.felt(mind, Experience.FEASTED, day, about)
        kept++
        return true
    }
}
