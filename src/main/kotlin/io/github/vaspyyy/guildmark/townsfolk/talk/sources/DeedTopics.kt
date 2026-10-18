package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.RumourWords
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue

/**
 * The player's own deeds, said to them (section 1): what the speaker saw them do (the most, the day it happened) and
 * what they've heard of it, good or ill: "A dragon! You really killed a dragon?", "I'm watching your hands." A tall
 * version of good news gets a doubt. In passing, only what happened within the day ("There goes the dragon-slayer!").
 * Children have their own line for the good ones.
 */
object DeedTopics : TopicSource {
    override val id = "deeds"

    private val OCCASIONS = setOf(Occasion.OPENER, Occasion.ANSWER, Occasion.BARK)

    /** How long a deed seen is talked of (days), and a rumour too cold to bring up. */
    private const val SEEN = 60L
    private const val COLD = 0.03f

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS) return
        val player = ask.player ?: return
        val r = ask.resident ?: return
        val level = ask.level
        val today = Say.day(level)
        val child = Say.child(ask.speaker)
        val bark = ask.occasion == Occasion.BARK
        val said = HashSet<String>()

        // What they saw the player do, the newest first
        if (Say.memory()) for (d in ask.knows?.witnessed ?: listOf()) {
            val age = today - d.day
            if (age < 0 || age > SEEN || bark && age > 1 || !said.add(d.kind)) continue
            val family = family(d.kind, d.tone, seen = true, mine = false, child = child) ?: continue
            val say = Say.line(ask, family, seen(age)) ?: continue
            out.add(Topic("deeds.${d.kind}.${d.day}", Kind.DEEDS, weight(age), say, tone = d.tone, about = player.uuid))
        }

        // What they've heard of the player, or saw and it went round
        val rumours = Say.rumours()
        for (k in TownTalk.known(level, r)) {
            val rumour = k.rumour
            val heard = k.heard
            if (rumour.about != player.uuid || rumour.heat < COLD) continue
            val seen = heard.venue == Venue.SEEN
            if (!rumours && !seen && heard.from != -1) continue
            val age = today - Math.floorDiv(rumour.born, 24L)
            if (bark && age > 1 || !said.add(rumour.kind)) continue
            val tall = heard.warp >= 3 && !seen && rumour.tone >= 0
            val family = if (tall) (if (child) "child.deed" else "deed.doubt")
            else family(rumour.kind, rumour.tone, seen, mine = rumour.resident == r.id, child = child) ?: continue
            val detail = if (seen) seen(age) else "talk.detail.heard.${heard.venue.name.lowercase()}"
            val say = Say.line(ask, family, detail) ?: continue
            val weight = if (seen) weight(age) else 0.7 * rumour.heat
            out.add(Topic(
                "deeds.r${rumour.id}.${heard.warp}", Kind.DEEDS, weight, say, tone = if (tall) 0 else rumour.tone,
                gesture = if (tall && !child) Activity.WAVE_OFF else null, about = player.uuid,
            ))
        }
    }

    /** The family a deed of [kind] is said with: its own, else praise for a good one; a child's own for a good one. */
    private fun family(kind: String, tone: Int, seen: Boolean, mine: Boolean, child: Boolean): String? {
        if (child) return if (tone > 0) "child.deed" else null
        return RumourWords.deed(kind, seen, mine) ?: if (tone > 0) "deed.praise" else null
    }

    /** A deed seen today weighs the most, within three days less, and less again after (section 1). */
    private fun weight(age: Long): Double = when {
        age <= 0 -> 0.9
        age <= 3 -> 0.7
        else -> 0.5 * (1.0 - age.toDouble() / SEEN).coerceAtLeast(0.2)
    }

    /** "Saw it myself, today." */
    private fun seen(age: Long): String = when {
        age <= 0 -> "talk.detail.deed.seen.0"
        age == 1L -> "talk.detail.deed.seen.1"
        else -> Speech.of("talk.detail.deed.seen", age)
    }
}
