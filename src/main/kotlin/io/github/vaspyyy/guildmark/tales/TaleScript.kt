package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import java.util.UUID

/**
 * The conversation a cast member has with a player about the tale (living-world/stories/design.md 3.1, 3.5, 3.6, 4.5 and 5.4), Part C:
 * what "About the note..." opens on the talk screen, in place, with the same person, and hands back to the ordinary talk when it is done
 * (`Script.back`). It is the same for every tale; a template adds its words and its options through [TaleVoice].
 *
 * **Before the player has taken the tale up** it says the request (the tale's `ask` family with its grey words, or `tale.ask.lead` with
 * who is seeing to it if another player has taken it up) and offers "I'll see to it" (a yes: the part goes IN through `Tales.join`),
 * "Not now" (the cast say "Another time, then.", ask again after two market days, at most twice more, and nothing is held against the
 * player) and "Something else..." (back to the ordinary talk, nothing counted).
 *
 * **Once they have** it says how it stands (the step's `stand` family, the live numbers in grey) and offers what the template offers at
 * the step (the choices of a CHOOSE, the sums of a GIVE, the tools of a MEET, the note of a DO), "How does it stand?" again when
 * something else was said since, "Why?" while the tale has reasons left to tell (newest first, one a tap, in the tale's own sentence for
 * each) and "Something else...".
 *
 * An option is offered at the step the tale was at: its id carries the step, so a player whose screen shows what another player has
 * since decided hears "It's been decided." and the choices as they stand now. Everything is decided here, on the server, from the
 * record, each time it is asked: the script keeps only how many reasons it has told.
 */
internal class TaleScript(
    private val tale: Tale,
    private val slot: String,
    private val template: Template,
    private val voice: TaleVoice?,
    private val player: UUID,
) : Script {
    /** How many of the reasons have been told this talk. */
    private var told = 0

    /** Whether the answer just given hands the talk back to the ordinary one. */
    private var handing = false

    /** Whether the last thing said was how it stands: it is not offered again straight after. */
    private var stood = false

    override fun opener(ask: Ask): Utterance {
        val turn = turn(ask) ?: return utter(ask, gone())
        handing = false
        stood = false
        return utter(ask, if (following(turn)) stand(turn) else request(turn))
    }

    override fun choices(ask: Ask): List<Choice> {
        val turn = turn(ask) ?: return listOf(Choice(BACK, TaleSays.label("talk.guildmark.choice.tale.back")))
        if (!following(turn)) {
            val lead = TaleSays.otherLead(turn) != null
            return listOf(
                Choice(YES, TaleSays.label("talk.guildmark.choice.tale.${turn.id}.yes", if (lead) "talk.guildmark.choice.tale.yes_lead" else "talk.guildmark.choice.tale.yes")),
                Choice(LATER, TaleSays.label("talk.guildmark.choice.tale.${turn.id}.later", "talk.guildmark.choice.tale.later")),
                Choice(BACK, TaleSays.label("talk.guildmark.choice.tale.back")),
            )
        }
        val out = ArrayList<Choice>()
        for (option in TaleSays.options(turn, voice)) out += Choice("o${turn.step}.${option.id}", option.label)
        if (!stood) out += Choice(STAND, TaleSays.label("talk.guildmark.choice.tale.${turn.id}.stand", "talk.guildmark.choice.tale.stand"))
        // "Why?" once the tale has turned (5.4), while it has a reason left to tell
        if (tale.step > 0 && told < reasons(turn).size) out += Choice(if (told == 0) WHY else WHY_MORE, TaleSays.label(if (told == 0) "talk.guildmark.choice.tale.why" else "talk.guildmark.choice.tale.why_more"))
        out += Choice(BACK, TaleSays.label("talk.guildmark.choice.tale.back"))
        return out
    }

    override fun answer(ask: Ask, choice: String): Pair<Utterance, Boolean> {
        val turn = turn(ask)
        val reply = if (turn == null) gone() else try {
            reply(turn, choice)
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The {} tale #{}: the talk about it failed at {}", tale.template, tale.id, choice, e)
            TaleReply("ask.dunno", gesture = Activity.SHRUG, back = true)
        }
        // A tale that ended with the answer has nothing more to say: the ordinary talk is back, with what was said
        handing = reply.back || turn == null || !tale.open
        return utter(ask, reply) to reply.ends
    }

    override fun back(ask: Ask, choice: String): Boolean = handing

    // ---- What they say to a choice ----

    private fun reply(turn: TaleTurn, choice: String): TaleReply {
        stood = false
        return when {
            choice == YES -> yes(turn)
            choice == LATER -> later(turn)
            choice == BACK -> TaleReply("ask.yes", gesture = Activity.NOD, back = true)
            choice == STAND -> stand(turn)
            choice == WHY || choice == WHY_MORE -> why(turn)
            choice.startsWith("o") -> option(turn, choice)
            else -> decided()
        }
    }

    /** The request: what the cast member wants, and the tale as it now stands for a player who comes after another (3.6). */
    private fun request(turn: TaleTurn): TaleReply {
        turn.player?.let { TaleSpeakers.asked(turn.level, tale, it, false) }
        return TaleReply(listOf(TaleSays.ask(turn, voice)), TaleSays.gesture(turn, voice, "ask"))
    }

    /** A yes: the part goes IN (the banner and the chime are the following's), and the cast member thanks them. */
    private fun yes(turn: TaleTurn): TaleReply {
        if (following(turn)) return stand(turn)
        val p = turn.player ?: return gone()
        val full = TaleReply("tale.ask.full", gesture = Activity.SHRUG, back = true)
        if (turn.part() == null || TaleData.get(turn.level).joinedBy(p.uuid).size >= TaleLimits.ON_TRACKER) return full
        // The reason goes in first, so that what the next step adds (the ASK step ends inside the join) is newer than it
        val why = Why("took_up", listOf(turn.who))
        tale.addWhy(why)
        if (!Tales.join(turn.level, tale, p)) {
            if (tale.why.lastOrNull() === why) tale.why.removeAt(tale.why.size - 1)
            return full
        }
        turn.changed()
        return TaleReply(listOf(thanks(turn)), Activity.THANK)
    }

    /** What the asker says at the yes: the tale's own line if its voice has one the language can say, else the talk's general thanks. */
    private fun thanks(turn: TaleTurn): TaleSay {
        val own = TaleVoiceGuard.run(turn.id, "yes", null) { voice?.yes(turn) }
        return if (own != null) TaleSay(own.family, own.detail, instead = own.instead ?: GENERAL_YES) else TaleSay(GENERAL_YES, TaleSays.detail(turn, voice, "yes"))
    }

    /** "Not now" (3.5): the cast say "Another time, then.", ask again after two market days at most twice more, and the talk goes on. */
    private fun later(turn: TaleTurn): TaleReply {
        if (following(turn)) return TaleReply("ask.yes", gesture = Activity.NOD, back = true)
        turn.player?.let { TaleSpeakers.declined(turn.level, tale, it) }
        return TaleReply("tale.ask.later", gesture = Activity.NOD, back = true)
    }

    /** How it stands: the step's own line (`stand_<step>` if the tale has one, else `stand`) with the live numbers in grey. */
    private fun stand(turn: TaleTurn): TaleReply {
        stood = true
        return TaleReply(listOf(TaleSay(TaleSays.standFamily(turn), TaleSays.detail(turn, voice, "stand"))), TaleSays.gesture(turn, voice, "stand"))
    }

    /** One reason, newest first (5.4): a short lead and the reason in the tale's own sentence, in grey; "That's all I know." when none is left. */
    private fun why(turn: TaleTurn): TaleReply {
        val list = reasons(turn)
        val why = list.getOrNull(told) ?: return TaleReply("ask.all", gesture = Activity.SHRUG)
        told += 1
        return TaleReply(listOf(TaleSay("tale.why.lead", TaleLines.pass(why))))
    }

    /** An option the template offered at a step: only while the tale is still at that step, and the option still there. */
    private fun option(turn: TaleTurn, choice: String): TaleReply {
        val body = choice.substring(1)
        val dot = body.indexOf('.')
        if (dot < 0 || body.substring(0, dot).toIntOrNull() != turn.step) return decided()
        val id = body.substring(dot + 1)
        val option = TaleSays.options(turn, voice).firstOrNull { it.id == id } ?: return decided()
        return option.run(turn) ?: decided()
    }

    private fun decided(): TaleReply = TaleReply("tale.ask.decided", gesture = Activity.NOD)

    /** The tale is over, or gone from the record, or the player has gone: a word and the ordinary talk. */
    private fun gone(): TaleReply = TaleReply("ask.dunno", gesture = Activity.SHRUG, back = true)

    // ---- What is true now ----

    private fun turn(ask: Ask): TaleTurn? {
        val p = ask.player ?: return null
        if (!Tales.live || !tale.open || p.uuid != player || TaleData.get(ask.level)[tale.id] !== tale) return null
        return TaleTurn(ask, tale, slot, template, p)
    }

    private fun following(turn: TaleTurn): Boolean = TaleSpeakers.joined(turn.mine)

    /** The reasons the tale can tell, newest first: those whose sentence the language has. */
    private fun reasons(turn: TaleTurn): List<Why> = tale.why.asReversed().filter { TaleLines.has(TaleKeys.why(it.kind)) }

    /** [reply] as the screen is sent it: each line as [TaleLines.part] builds it, the one it names instead if it has nothing. */
    private fun utter(ask: Ask, reply: TaleReply): Utterance {
        val parts = reply.says.mapNotNull { s -> TaleLines.part(ask, s.family, s.detail) ?: s.instead?.let { TaleLines.part(ask, it, s.detail) } }
        return Utterance(parts, reply.toward, reply.gesture)
    }

    private companion object {
        const val YES = "yes"
        const val LATER = "later"
        const val BACK = "back"
        const val STAND = "stand"
        const val WHY = "why"
        const val WHY_MORE = "why.more"

        /** The talk's general thanks at a yes, for a tale whose voice has no line of its own for it (one wording is `say.guildmark.talk.tale.yes_0`). */
        const val GENERAL_YES = "tale.yes"
    }
}
