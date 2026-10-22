package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.townsfolk.Activity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * What a cast member says about a tale that is the same wherever it is said (living-world/stories/design.md 3.1, 3.6 and 4.7), Part C:
 * the request, as the opener says it and as "About the note..." says it again, how it stands, and the pieces they are made of. The
 * one place so that the opener on the screen and the script that follows it can never tell it two ways.
 */
internal object TaleSays {
    /** The grey words of [beat] from [voice] (the template's), or null: a voice that throws is logged once and its talk goes on without them. */
    fun detail(turn: TaleTurn, voice: TaleVoice?, beat: String): String? =
        TaleVoiceGuard.run(turn.id, "detail $beat", null) { voice?.detail(beat, turn) }

    /** How a cast member says a line: the [gesture] they make (null: the talk's own) and the place they point to as they say it. */
    class Manner(val gesture: Activity?, val toward: BlockPos?)

    /**
     * How the voice has its cast member say [beat]: its gesture, and the place it names, which is pointed to (POINT) unless the voice
     * names another gesture. Nothing of either if the voice has none, or throws.
     */
    fun manner(turn: TaleTurn, voice: TaleVoice?, beat: String): Manner {
        val gesture = TaleVoiceGuard.run(turn.id, "gesture $beat", null) { voice?.gesture(beat, turn) }
        val toward = TaleVoiceGuard.run(turn.id, "toward $beat", null) { voice?.toward(beat, turn) }
        return Manner(gesture ?: if (toward != null) Activity.POINT else null, toward)
    }

    /** What the cast member offers at the tale's step, at most [MOST] of it. */
    fun options(turn: TaleTurn, voice: TaleVoice?): List<TaleOption> =
        TaleVoiceGuard.run(turn.id, "options", listOf<TaleOption>()) { voice?.options(turn).orEmpty() }.take(MOST)

    /** The most options a step shows: with "How does it stand?", "Why?" and "Something else...", the screen's eight rows have room for these. */
    const val MOST = 4

    /** The player who has taken the tale up, if it is another than the one talking (3.6). */
    fun otherLead(turn: TaleTurn): Part? = turn.tale.lead?.takeIf { it.player != turn.player?.uuid }

    /** The family the tale says how it stands in at its step: `stand_<step>` if the tale has one, else `stand`. */
    fun standFamily(turn: TaleTurn): String {
        val step = turn.family("stand_${turn.step}")
        return if (TaleLines.exists(step)) step else turn.family("stand")
    }

    /**
     * The request (3.1): the tale's `ask` family with its grey words ("A borrowed rope" and who owes whom), or, for a player who comes
     * after another, `tale.ask.lead` with who is seeing to it and what comes next (3.6, 4.7). A tale that has turned with nobody
     * seeing to it is told as it now stands, for the request it began with is no longer the one it makes.
     */
    fun ask(turn: TaleTurn, voice: TaleVoice?): TaleSay {
        val lead = otherLead(turn)
        return when (askBeat(turn)) {
            "lead" -> TaleSay(LEAD, lead?.let { leadDetail(turn, it) })
            "stand" -> TaleSay(standFamily(turn), detail(turn, voice, "stand"))
            else -> TaleSay(turn.family("ask"), detail(turn, voice, "ask"))
        }
    }

    /** Which beat [ask] says the request as: "lead" to a player who comes after another, "stand" once the tale has turned and can say so, else "ask". */
    fun askBeat(turn: TaleTurn): String = when {
        otherLead(turn) != null -> "lead"
        turn.step > 0 && TaleLines.exists(standFamily(turn)) -> "stand"
        else -> "ask"
    }

    /** "{lead} is seeing to it. Next: {status}.": who has it, and the tale's tracker line as it stands (in the server's language). */
    private fun leadDetail(turn: TaleTurn, lead: Part): String? {
        val name = TaleWords.leadName(turn.level, lead.player)
        val next = status(turn, lead)
        return when {
            name.isNotBlank() && next.isNotBlank() -> TaleLines.facts("talk.detail.tale.lead", TaleLines.lit(name), TaleLines.lit(next))
            name.isNotBlank() -> TaleLines.facts("talk.detail.tale.lead.alone", TaleLines.lit(name))
            next.isNotBlank() -> TaleLines.facts("talk.detail.tale.lead.someone", TaleLines.lit(next))
            else -> TaleLines.facts("talk.detail.tale.lead.nobody")
        }
    }

    /** What the tracker says the tale's next step is for [part]'s player, in words: the template's own row. */
    private fun status(turn: TaleTurn, part: Part): String = TaleVoiceGuard.run(turn.id, "view", "") {
        val view = turn.template.view(turn.tale, part.player)
        Component.translatable(view.status, *view.statusArgs.toTypedArray()).string
    }

    /** A label under the first of [keys] the language has (the template's own, then the talk's general one): the last if it has none. */
    fun label(vararg keys: String): Component =
        Component.translatable(keys.firstOrNull { TaleLines.quiet(it) } ?: keys.last())

    /** The general line a tale says to a second player (design 8.0; H's). */
    private const val LEAD = "tale.ask.lead"
}
