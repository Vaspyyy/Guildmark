package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.scenes.Words
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Topics
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.server.level.ServerPlayer

/**
 * A kitchen line (Hearthcraft, living-world/cook/design.md 8.5): the take of a kitchen family with its detail after it,
 * or, while the family has no take recorded, one of its wordings as words, its detail in grey after it, so the whole kitchen is
 * played, read and tested before one take exists and a recorded tier changes only that the same lines are heard too.
 *
 * The wording is the text the pipeline would give its take: the key `talk.<family>_<n>`, whose text is in the language file
 * (art/lang/cook_lines.json, from art/audio/kitchen_lines.py) whether the family is recorded or not, so a part made here for a
 * family with no take has that key as its line and the engine treats it as it treats a take whose length it does not know:
 * it shows the text, plays no sound (no sound event is named after the key) and notes the wording as said, so the same one is
 * not said again at once. With Kitchen Talk off none of this says anything.
 *
 * The topics ([KitchenTopics]) and every other system that has the town say a kitchen line (the back door, the guest pot,
 * the Harvest Table, the cookshop) come here for it; [Say.part] and [Say.lead] come here for a family that is the kitchen's and
 * has no take, so the two works families of a raised cookshop (which WorksTopics says) read as text as well.
 */
object KitchenLines {
    /** How long a wording with no take hangs over a head in a scene (the entity keeps it 20 ticks longer, as for a take). */
    private const val TEXT_TICKS = 60

    /** Whether [family] is one of the kitchen's: the table of wordings has it (KitchenWords). */
    fun has(family: String): Boolean = KitchenWords.count(family) > 0

    /** Whether [family] has takes recorded, so it is heard and not only read. */
    private fun recorded(family: String): Boolean = (TalkLines.FAMILIES[family]?.count ?: 0) > 0

    /**
     * The key of a wording of [family] for [speaker] to say to [player] as words: one neither of them has had lately, else any;
     * null if [family] is not the kitchen's.
     */
    fun wording(speaker: Townsperson, player: ServerPlayer?, family: String): String? {
        val n = KitchenWords.count(family)
        if (n <= 0) return null
        val avoid = Topics.avoid(speaker, player)
        val keys = (0 until n).map { "talk.${family}_$it" }
        val from = keys.filter { it !in avoid }.ifEmpty { keys }
        return from[speaker.random.nextInt(from.size)]
    }

    /**
     * The words of [family] with [detail] after them, for a family that is the kitchen's and has no take: null if it has one (the
     * take is [Say]'s to give), if it is not the kitchen's, or with Kitchen Talk off.
     */
    internal fun text(ask: Ask, family: String, detail: String?): Utterance.Part? {
        if (!has(family) || recorded(family) || !Cooking.talk()) return null
        val key = wording(ask.speaker, ask.player, family) ?: return null
        return Utterance.Part(key, detail)
    }

    /**
     * The part of a line for [family] with [detail], as the speaker of [ask] would say it: a take with its detail, else a wording as
     * words with its detail in grey; the detail alone for a family that is not the kitchen's and has no take, and null if there is
     * nothing to show or Kitchen Talk is off.
     */
    fun part(ask: Ask, family: String, detail: String?): Utterance.Part? = if (Cooking.talk()) Say.part(ask, family, detail) else null

    /** The part that leads a line for [family], with [detail] when it has one: a take, else a wording as words; null if Kitchen Talk is off. */
    fun lead(ask: Ask, family: String, detail: String? = null): Utterance.Part? = if (Cooking.talk()) Say.lead(ask, family, detail) else null

    /** The whole line for [family] with [detail] ([part] as a line). */
    fun line(ask: Ask, family: String, detail: String?): Utterance? = part(ask, family, detail)?.let { Utterance(listOf(it)) }

    /**
     * Has [p] say [family] now, because of [why] ("back door", a scene's name...): a take over their head when the family is recorded
     * (as the scenes say theirs), else a wording as words in the same place, which no sound goes with. [force] says it even if they said
     * something just now or others near are talking. Whether it was said: not with Kitchen Talk or the street talk off, with no
     * player in hearing, or (for a take) when every take has been heard lately, so the caller's own words may follow; with [always]
     * (a scene's line, which belongs to the moment and has no words of its own to fall back on) the take heard longest ago then.
     */
    fun say(p: Townsperson, family: String, why: String, force: Boolean = false, always: Boolean = false): Boolean {
        if (!Cooking.talk() || !Words.on()) return false
        val player = Words.hearer(p) ?: return false
        val line = if (!recorded(family)) wording(p, player, family)?.let { Street.Line(it, it, TEXT_TICKS) }
        else Words.of(p, player, family) ?: if (always) stalest(p, player, family) else null
        return line != null && Words.say(p, player, line, why, force)
    }

    /** The take of recorded [family] said by [p] or heard by [player] longest ago, as a line (see [Topics.stalest]). */
    private fun stalest(p: Townsperson, player: ServerPlayer, family: String): Street.Line? {
        val count = TalkLines.FAMILIES[family]?.count ?: 0
        val key = Topics.stalest(p, player, (0 until count).map { "talk.${family}_$it" }) ?: return null
        return Street.Line(key, key, Talk.ticks(p, key))
    }
}
