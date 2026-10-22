package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleOptions
import io.github.vaspyyy.guildmark.tales.TaleSays
import io.github.vaspyyy.guildmark.tales.TaleScript
import io.github.vaspyyy.guildmark.tales.TaleSpeakers
import io.github.vaspyyy.guildmark.tales.TaleTurn
import io.github.vaspyyy.guildmark.tales.TaleVoiceGuard
import io.github.vaspyyy.guildmark.tales.TaleVoices
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.tales.Templates
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Script
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance

/**
 * What the townsfolk say of the town's open tale (living-world/stories/design.md 3.1 and 3.2), Part C. A tale is a town's trouble, not a
 * favour to its friends, so everything here is said to strangers as to anyone (`Kind.TALE`, the plain kinds' row of the weights).
 * Everything is worked out when a person is asked, from the tale's record and the template's words; nothing runs between, nothing is
 * kept here, and with tales off ([Tales.live]) this source offers nothing at all, so the talk is as it was.
 *
 * For the cast member who asks (the template says which; else the first of its cast):
 *
 *  - **the opener**, once, ahead of the draw (`Topic.first`) when the player's part is UNMET, or DECLINED and two market days old at
 *    least with fewer than three asks: the tale's `ask` family with the grey words of the template, or what [TaleSays.ask] says to
 *    a player who comes after another. Saying it counts the ask (UNMET becomes ASKED). Its id carries the asks so far, so the freshness
 *    the talk keeps of what it said lately does not keep a second ask from being said;
 *  - **the choice** "About the note..." (pressing 0.95 until the player is IN or DONE, 0.6 after, when it reads "How does it stand?"),
 *    which opens [TaleScript] in place;
 *  - **in passing**, to a passer-by or a neighbour, while the player has not been asked: the tale's `passing` family, weight 0.5.
 *
 * For anyone who has heard the tale's rumour (kind `tale.<template>.ask`, begun when the tale was seeded, `TaleTalk`): a topic carrying
 * the rumour, in the tale's `rumour` family with the live words after it, pointing to the cast member's door when it is near. A
 * rumour is told in the street as news without its rumour mark, and said to a player who has not taken the tale up.
 *
 * A template adds its own through `Template.topics` (a cast member's other words) and `Template.script` (a conversation wholly its own).
 */
object TaleTopics : TopicSource {
    override val id = "tale"

    /** The choice's id: "About the note..." before the player has taken the tale up, "How does it stand?" after. */
    const val ASK = "tale:ask"

    /** How much the opener and the passing words matter (3.1), and the rumour at its hottest and coldest. */
    private const val OPENER = 0.9
    private const val PASSING = 0.5
    private const val RUMOUR_MIN = 0.15
    private const val RUMOUR_HEAT = 0.55

    /** How pressing the choice is among the two the screen shows (3.1): before the player has taken the tale up, and after. */
    private const val PRESSING = 0.95
    private const val PRESSING_AFTER = 0.6

    /** The cast member's door is pointed to from this far at most. */
    private const val HOME_NEAR = 64.0

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (!Tales.live) return
        val found = TaleSpeakers.find(ask.level, ask.speaker)
        if (found != null) cast(ask, found, out)
        rumours(ask, found, out)
    }

    // ---- The cast ----

    private fun cast(ask: Ask, found: TaleSpeakers.Found, out: MutableList<Topic>) {
        val tale = found.tale
        // The template's own words for this cast member: all of what it adds is dropped if its code throws
        val before = out.size
        if (!TaleVoiceGuard.run(tale.template, "topics", false) { found.template.topics(ask, tale, out); true }) {
            while (out.size > before) out.removeAt(out.size - 1)
        }
        if (!found.asks) return
        val turn = TaleTurn(ask, tale, found.slot, found.template, ask.player)
        val player = ask.player
        when (ask.occasion) {
            Occasion.OPENER -> if (player != null && TaleSpeakers.askable(tale, player.uuid, turn.today)) opener(ask, turn, found, out)
            Occasion.BARK, Occasion.CHAT -> if (player == null || TaleSpeakers.askable(tale, player.uuid, turn.today)) passing(ask, turn, found, out)
            else -> {}
        }
    }

    /** The request, said first and once to a player the cast have not asked (or asked and were told "Not now" two market days ago): 3.1. */
    private fun opener(ask: Ask, turn: TaleTurn, found: TaleSpeakers.Found, out: MutableList<Topic>) {
        val said = TaleSays.ask(turn, found.voice)
        val part = TaleLines.part(ask, said.family, said.detail) ?: return
        val tale = found.tale
        // Said, the ask is counted; the id carries the asks so far, so that a second ask is not the same topic as the first
        out.add(Topic(
            "tale.${tale.id}.ask.${turn.mine?.asks ?: 0}", Kind.TALE, OPENER, Utterance(listOf(part)), tone = -1, first = true,
            onSaid = { a -> a.player?.let { TaleSpeakers.asked(a.level, tale, it, true) } },
        ))
    }

    /** The cast member's trouble, mentioned to anyone near and to a neighbour (3.1): while the player has not been asked. */
    private fun passing(ask: Ask, turn: TaleTurn, found: TaleSpeakers.Found, out: MutableList<Topic>) {
        val family = turn.family("passing")
        if (!TaleLines.exists(family)) return
        val part = TaleLines.part(ask, family, TaleSays.detail(turn, found.voice, "passing")) ?: return
        out.add(Topic("tale.${found.tale.id}.passing", Kind.TALE, PASSING, Utterance(listOf(part)), tone = -1))
    }

    // ---- The rumours ----

    /**
     * What anyone who has heard the tale's rumour says of it, in the tale's own voice (3.2): to a player who has not taken it up and has
     * not heard it yet, in passing, in a chat, or when asked for news. Not the cast, who tell it themselves.
     */
    private fun rumours(ask: Ask, found: TaleSpeakers.Found?, out: MutableList<Topic>) {
        val r = ask.resident ?: return
        if (r.id < 0) return
        val level = ask.level
        val bark = ask.occasion == Occasion.BARK
        if (!bark && ask.occasion != Occasion.NEWS && ask.occasion !in Say.TALK) return
        val player = ask.player
        for (k in TownTalk.known(level, r)) {
            val rumour = k.rumour
            if (!rumour.kind.startsWith("tale.") || !rumour.kind.endsWith(".ask")) continue
            val tale = TaleData.get(level)[rumour.args.firstOrNull()?.toIntOrNull() ?: continue]?.takeIf { it.open && it.capital == r.capital.asLong() } ?: continue
            if (found != null && found.tale === tale) continue
            if (player != null) {
                if (TaleSpeakers.joined(tale.part(player.uuid))) continue
                if (!bark && rumour.id in TownTalk.heard(level, r.capital, player.uuid)) continue
            }
            val template = Templates.find(tale.template) ?: continue
            val turn = TaleTurn(ask, tale, "", template, player)
            val detail = if (k.heard.warp == 0) TaleSays.detail(turn, TaleVoices.of(template), "rumour") else TaleLines.facts("talk.detail.tale.hearsay")
            val part = TaleLines.part(ask, turn.family("rumour"), detail) ?: continue
            val home = rumour.origin?.takeIf { it.distSqr(ask.speaker.blockPosition()) <= HOME_NEAR * HOME_NEAR }
            out.add(Topic(
                "tale.rumour.${rumour.id}.${k.heard.warp}", Kind.TALE, RUMOUR_MIN + RUMOUR_HEAT * rumour.heat, Utterance(listOf(part)), tone = -1,
                gesture = if (home != null) Activity.POINT else null, toward = home, rumour = rumour.id.takeIf { !bark },
                // The talk marks what a player is told when they ask for news; what it opens with or answers with is marked here
                onSaid = { a -> if (!bark && a.occasion != Occasion.NEWS) a.player?.let { TownTalk.told(a.level, a.resident, it, rumour.id) } },
            ))
        }
    }

    // ---- The choice and what it opens ----

    override fun choice(ask: Ask): Choice? {
        val player = ask.player ?: return null
        if (!Tales.live) return null
        val found = TaleSpeakers.find(ask.level, ask.speaker) ?: return null
        if (!found.asks && own(ask, found) == null) return null
        val joined = TaleSpeakers.joined(found.tale.part(player.uuid))
        val id = found.tale.template
        val label = if (joined) {
            TaleSays.label("talk.guildmark.choice.tale.$id.stand", "talk.guildmark.choice.tale.stand")
        } else {
            TaleSays.label("talk.guildmark.choice.tale.$id.about", "talk.guildmark.choice.tale.about")
        }
        return Choice(ASK, label, pressing = if (joined) PRESSING_AFTER else PRESSING)
    }

    /** The conversation the choice opens, in place: the template's own if it has one for this cast member, else the same for every tale. */
    override fun script(ask: Ask, choice: String): Script? {
        if (choice != ASK || !Tales.live) return null
        val player = ask.player ?: return null
        val found = TaleSpeakers.find(ask.level, ask.speaker) ?: return null
        own(ask, found)?.let { return it }
        if (!found.asks) return null
        return TaleScript(found.tale, found.slot, found.template, found.voice, player.uuid)
    }

    /** The template's own talk with this cast member, if it gives one (guarded: a template that throws has none). */
    private fun own(ask: Ask, found: TaleSpeakers.Found): Script? =
        TaleVoiceGuard.run(found.tale.template, "script", null) { found.template.script(ask, found.tale, found.slot) }

    /** The server stopped: what the tales' talk kept of what was said and what it warned of is let go. */
    fun forget() {
        TaleLines.forget()
        TaleVoiceGuard.forget()
        TaleOptions.forget()
    }
}
