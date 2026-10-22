package io.github.vaspyyy.guildmark.tales

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * What a player carries of the tales they have been part of (living-world/stories/design.md 11.3, Part E): the value of the one
 * attachment `ModAttachments.TALE_LOG`, saved with the player and kept through death, never synced (the advancements and the book are
 * made on the server). The record of a tale is the truth; this is only the player's own count of it, so a lost or renamed player
 * leaves nothing to repair. Only the counting is here, so it can be tried with no game running; [TaleLogs] puts it on a player.
 *
 * [count] is the tales that ended A or B with the player's part, [best] each such template's best ending letter (A before B),
 * [towns] the capitals those were in (at most [MAX_TOWNS]), and [seen] the ids of the last [MAX_SEEN] tales counted, so that a pay
 * run again after a failure counts a tale once. Every field is optional on disk, so an old save reads as no tales.
 */
data class TaleLog(
    val count: Int = 0,
    val best: Map<String, String> = mapOf(),
    val towns: List<Long> = listOf(),
    val seen: List<Int> = listOf(),
) {
    /** Whether any tale is on this log at all (a log with nothing in it is not written). */
    val any: Boolean get() = count > 0 || best.isNotEmpty() || towns.isNotEmpty()

    /** "A Tale Told": a tale ended well with the player's part. */
    val first: Boolean get() = count >= 1

    /** "Good Neighbour": three tales ended well, in at least two towns. */
    val threeInTwo: Boolean get() = count >= 3 && towns.size >= 2

    /** "The Whole Chronicle": each of the twelve templates ended well once. */
    val wholeChronicle: Boolean get() = TWELVE.all { it in best }

    /**
     * This log with tale [tale] of [template], which ended [ending] in [capital] (null: no longer known), counted once: the same log
     * if [ending] is not A or B or the tale was counted already, else the count one more, the template's best ending kept (A before
     * B), the capital added to the towns if there is room, and the tale put on the list of those seen.
     */
    fun counted(tale: Int, template: String, ending: String, capital: Long?): TaleLog {
        if (!counts(ending) || tale in seen) return this
        val had = best[template]
        val nowBest = if (had == null || rank(ending) < rank(had)) best + (template to ending) else best
        val nowTowns = if (capital != null && capital !in towns && towns.size < MAX_TOWNS) towns + capital else towns
        return TaleLog(count + 1, nowBest, nowTowns, (seen + tale).takeLast(MAX_SEEN))
    }

    companion object {
        /** The most capitals the log names, and the most tale ids it remembers having counted. */
        const val MAX_TOWNS = 16
        const val MAX_SEEN = 8

        /** The twelve templates of the Chronicle (design 8.0): "The Whole Chronicle" is one good ending of each. */
        val TWELVE: List<String> = listOf("note", "chair", "wagon", "child", "fence", "lute", "moot", "cart", "lantern", "informer", "locket", "brother")

        val CODEC: Codec<TaleLog> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("count", 0).forGetter(TaleLog::count),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("best", mapOf()).forGetter(TaleLog::best),
                Codec.LONG.listOf().optionalFieldOf("towns", listOf()).forGetter(TaleLog::towns),
                Codec.INT.listOf().optionalFieldOf("seen", listOf()).forGetter(TaleLog::seen),
            ).apply(i, ::TaleLog)
        }

        /** Whether [ending] is one that counts: a tale ended well, A or B. */
        fun counts(ending: String): Boolean = ending == Ending.A || ending == Ending.B

        /** How good an ending is, 0 the best; a letter that is not one of the four is worse than all of them. */
        private fun rank(ending: String): Int = Ending.LETTERS.indexOf(ending).let { if (it < 0) Ending.LETTERS.size else it }
    }
}
