package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLimits
import io.github.vaspyyy.guildmark.tales.TaleLines
import io.github.vaspyyy.guildmark.tales.TaleWords
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance

/**
 * What the townsfolk say of a tale that has ended (living-world/stories/design.md 6.2 and 6.6), Part C slice 2, offered by
 * [TaleTopics] beside what it says of an open one. Everything is worked out when a person is asked, from the record and the rumours,
 * and none of it holds anyone to anything: these are opinions, and what a person says of a bad ending never names who let it happen.
 *
 *  - **Thanks** (`tale.after.thanks`), from a cast member of a tale that ended A or B to the player who did their part in it, in their
 *    first talk after the ending and for [TaleLimits.KEPT_DAYS] market days (while the record is kept): weight 0.7, said ahead of the
 *    draw, once. The ending's sentence follows in grey.
 *  - **Glad** and **sorry** (`tale.after.glad`, `tale.after.sorry`), from anyone who has heard the ending's rumour (kind
 *    `tale.<template>.<word>`, which `RumourWords` leaves to this source), by the rumour's tone, when asked for news or in a chat:
 *    weight 0.3, once to each player. The ending's sentence follows in grey, in the tale's own words with its names; a version
 *    that has grown in the telling says only that it did. This is also how the ending's rumour is told, so a player who is told
 *    it knows it, and a chat spreads it.
 *  - **Old times** (`tale.after.old`), from the elders (residents of twenty days or more), forever: weight 0.2, one line of the
 *    capital's chronicle with its age in grey.
 *
 * A line is said once to a player in a session (the talk keeps no more than that, and nothing here is saved): a person may say it
 * again after the server has been stopped, which does no harm.
 */
internal object TaleAfter {
    private const val THANKS = 0.7
    private const val OTHERS = 0.3
    private const val OLD = 0.2

    /** The days a resident has been in the town to be one of its elders. */
    private const val ELDER_DAYS = 20L

    /** What each player was told, this session: "player/tale or rumour/person/kind". Emptied when it grows too long: it is only a guard against repeating. */
    private val told = HashSet<String>()
    private const val MOST_TOLD = 4000

    fun offer(ask: Ask, out: MutableList<Topic>) {
        when (ask.occasion) {
            Occasion.OPENER, Occasion.ANSWER, Occasion.NEWS, Occasion.CHAT -> {}
            else -> return
        }
        val r = ask.resident ?: return
        if (r.id < 0) return
        val data = TaleData.get(ask.level)
        val today = TaleEngine.today(ask.level, r.capital.asLong())
        val recent = data.all().filter { !it.open && it.ended != TaleData.NEVER && today - it.ended <= TaleLimits.KEPT_DAYS && it.capital == r.capital.asLong() }
        if (recent.isNotEmpty()) {
            thanks(ask, r, recent, out)
            heard(ask, r, today, out)
        }
        old(ask, r, today, out)
    }

    // ---- Thanks ----

    /** The cast member helped, to the player who helped them: ahead of the draw in the first talk, once. */
    private fun thanks(ask: Ask, r: Resident, recent: List<Tale>, out: MutableList<Topic>) {
        val player = ask.player ?: return
        for (t in recent) {
            if (t.ending != Ending.A && t.ending != Ending.B) continue
            if (t.cast.none { it.resident == r.id && it.gone.isEmpty() }) continue
            if ((t.part(player.uuid)?.did ?: 0) == 0) continue
            val key = "${player.uuid}/${t.id}/${r.id}/thanks"
            if (key in told) continue
            val line = TaleLines.part(ask, "tale.after.thanks", TaleLines.pass(TaleWords.sentence(ask.level, t))) ?: continue
            out.add(Topic(
                "tale.after.thanks.${t.id}", Kind.TALE, THANKS, Utterance(listOf(line)), tone = 1, gesture = Activity.THANK,
                first = ask.occasion == Occasion.OPENER, onSaid = { remember(key) },
            ))
        }
    }

    // ---- Glad and sorry ----

    /** The ending's rumour as [r] has it: glad of a good ending, sorry for an ill one, once to a player. */
    private fun heard(ask: Ask, r: Resident, today: Long, out: MutableList<Topic>) {
        val level = ask.level
        val player = ask.player
        for (k in TownTalk.known(level, r)) {
            val rumour = k.rumour
            val kind = rumour.kind
            if (!kind.startsWith("tale.") || kind.endsWith(".ask") || rumour.tone == 0) continue
            if (today - Math.floorDiv(rumour.born, 24L) > TaleLimits.KEPT_DAYS) continue
            val key = "${player?.uuid ?: "-"}/${rumour.id}/${r.id}/heard"
            if (player != null && key in told) continue
            // The sentence of the ending in the tale's words with its names (a rumour carries them after an "="), unless it has grown in the telling
            val detail = if (k.heard.warp == 0) {
                TaleLines.pass(TaleWords.sentence(listOf(kind.removePrefix("tale.")) + rumour.args.map { it.removePrefix("=") }))
            } else {
                TaleLines.facts("talk.detail.tale.hearsay")
            }
            val line = TaleLines.part(ask, if (rumour.tone > 0) "tale.after.glad" else "tale.after.sorry", detail) ?: continue
            out.add(Topic(
                "tale.after.rumour.${rumour.id}.${k.heard.warp}", Kind.TALE, OTHERS, Utterance(listOf(line)), tone = rumour.tone, rumour = rumour.id,
                // The talk marks what a player is told when they ask for news; what it opens with or answers with is marked here
                onSaid = { a ->
                    remember(key)
                    if (a.occasion != Occasion.NEWS) a.player?.let { TownTalk.told(a.level, a.resident, it, rumour.id) }
                },
            ))
        }
    }

    // ---- Old times ----

    /** An elder's old times: one line of the chronicle, with how long ago it was. Never as the opener: it matters less than that. */
    private fun old(ask: Ask, r: Resident, today: Long, out: MutableList<Topic>) {
        if (ask.occasion == Occasion.OPENER) return
        val level = ask.level
        if (Say.day(level) - r.since < ELDER_DAYS) return
        val chronicle = TaleData.get(level).find(r.capital.asLong())?.chronicle ?: return
        if (chronicle.isEmpty()) return
        val line = chronicle[ask.speaker.random.nextInt(chronicle.size)]
        val key = "${ask.player?.uuid ?: "-"}/${r.id}/old/${line.template}/${line.day}"
        if (ask.player != null && key in told) return
        val sentence = TaleWords.sentence(line).string
        val detail = TaleLines.facts("talk.detail.tale.old", TaleLines.lit(sentence), age(today - line.day))
        val said = TaleLines.part(ask, "tale.after.old", detail) ?: return
        out.add(Topic("tale.after.old.${line.day}.${line.template}", Kind.TALE, OLD, Utterance(listOf(said)), onSaid = { remember(key) }))
    }

    /** How long ago [days] market days was, as a lang key a detail names: "A week ago", "Some months back", "Long ago". */
    private fun age(days: Long): String = when {
        days <= 2 -> "say.guildmark.talk.detail.tale.age.recent"
        days <= 9 -> "say.guildmark.talk.detail.tale.age.week"
        days <= 20 -> "say.guildmark.talk.detail.tale.age.weeks"
        days <= 45 -> "say.guildmark.talk.detail.tale.age.month"
        days <= 120 -> "say.guildmark.talk.detail.tale.age.months"
        else -> "say.guildmark.talk.detail.tale.age.long"
    }

    private fun remember(key: String) {
        if (told.size >= MOST_TOLD) told.clear()
        told.add(key)
    }

    /** The server stopped: nothing of what was told is kept. */
    fun forget() = told.clear()
}
