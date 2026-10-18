package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Heard
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Rumour
import io.github.vaspyyy.guildmark.townsfolk.talk.RumourWords
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.Venue
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * The news the speaker has (sections 1 and 4): the rumours they know, about anyone but the one they're telling (whose
 * deeds are the deeds source's), each said as two parts: a lead-in that says where they had it and how sure they are
 * ("Word in the tavern is...", "You won't believe this, but..."), then the news with the version they heard as its
 * detail. A rumour the player has heard already is passed over, unless this teller heard it differently: then "Ah, but
 * I heard different." comes first. When the rumour is about a player the speaker can see close by, they point them out
 * instead of the lead-in ("That's the one, over there."), once in two minutes for each player. In passing, only big
 * news the player hasn't heard, without the lead-in. With townsfolkRumours off they know only what they saw and what
 * the crier cried.
 */
object NewsTopics : TopicSource {
    override val id = "news"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** A rumour weighs this times its heat (section 1). */
    private const val RUMOUR = 0.7

    /** Below this heat it's old news, told only when asked for news; below [GONE] it isn't told at all. */
    private const val OLD = 0.1f
    private const val GONE = 0.03f

    /** What old news weighs when asked for news, so it's told when there's nothing fresher. */
    private const val STALE = 0.12

    /** Big news, told even to strangers. */
    private const val BIG = 0.7f

    /** How many rumours a teller looks over: as many as anyone keeps. */
    private const val KEPT = 12

    /** How near the player the news is about must be to be pointed out, and how often each may be. */
    private const val THERE = 16.0
    private const val THERE_TICKS = 2 * 60 * 20L

    /** When each player was last pointed out (game time); kept in memory only. */
    private val pointed = HashMap<UUID, Long>()

    fun forget() = pointed.clear()

    /**
     * How many pieces of news [ask]'s speaker tells to "Any news?" and "Anything else?" before "That's all I know." (section
     * 2): three from the chatty, one from the shy and to strangers, two from anyone else (as Talk counts them).
     */
    fun limit(ask: Ask): Int {
        val r = ask.resident
        return when {
            r?.has(Trait.CHATTY) == true -> 3
            r?.has(Trait.SHY) == true || ask.familiarity == Familiarity.STRANGER -> 1
            else -> 2
        }
    }

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS) return
        val r = ask.resident ?: return
        val level = ask.level
        val known = TownTalk.known(level, r)
        if (known.isEmpty()) return
        val rumours = Say.rumours()
        val player = ask.player
        val theirs = if (player != null) TownTalk.heard(level, r.capital, player.uuid) else mapOf()
        val partner = ask.listener?.takeIf { it.resident >= 0 }?.let { Residents.get(level)[it.resident] }
        val partnerKnows = partner?.let { p -> TownTalk.known(level, p).mapTo(HashSet()) { it.rumour.id } } ?: setOf()
        val asked = ask.occasion == Occasion.NEWS
        val bark = ask.occasion == Occasion.BARK
        for (k in known.take(KEPT)) {
            val rumour = k.rumour
            val heard = k.heard
            if (rumour.heat < GONE || rumour.heat < OLD && !asked || bark && rumour.heat < BIG) continue
            // With word not going round, only what they saw and what the crier cried
            if (!rumours && heard.venue != Venue.SEEN && heard.from != -1) continue
            if (player != null && rumour.about == player.uuid) continue
            if (rumour.resident >= 0 && rumour.resident == r.id) continue
            if (rumour.id in partnerKnows) continue
            val family = RumourWords.family(rumour) ?: continue
            val warp = heard.warp.coerceIn(0, 3)
            val had = theirs[rumour.id]
            if (had != null && (had == warp || bark)) continue
            val news = Say.part(ask, family, RumourWords.detail(level, rumour, warp, r.capital)) ?: continue
            val there = if (had == null && !bark) there(ask, rumour) else null
            val parts = when {
                bark -> listOf(news)
                there != null -> listOfNotNull(news, Say.lead(ask, "news.there"))
                had != null -> listOfNotNull(Say.lead(ask, "ask.other"), news)
                else -> listOfNotNull(lead(ask, rumour, heard), news)
            }
            val weight = (RUMOUR * rumour.heat).let { if (rumour.heat < OLD) maxOf(it, STALE) else it }
            val least = if (player == null || rumour.heat >= BIG) Familiarity.STRANGER else Familiarity.MET
            val gesture = when {
                bark -> null
                there != null -> Activity.POINT
                warp == 3 && !ask.speaker.activity.seated -> Activity.RECOUNT
                else -> null
            }
            out.add(Topic(
                "news.${rumour.id}.$warp", Kind.NEWS, weight, Utterance(parts), least, rumour.tone, gesture,
                toward = there?.blockPosition(), rumour = rumour.id, about = rumour.about,
            ))
            if (there != null) pointed[there.uuid] = level.gameTime
        }
    }

    /**
     * The lead-in for [rumour] as [heard]: a child's own, a tall tale's ("You won't believe this, but..."), a wrong
     * kept quiet ("Don't say I told you, but..."), a muddled one's "They say...", or where they had it; with who told
     * them as its detail when it was one of the town.
     */
    private fun lead(ask: Ask, rumour: Rumour, heard: Heard): Utterance.Part? {
        if (Say.child(ask.speaker)) return Say.lead(ask, "child.news")
        val frame = when {
            heard.warp >= 3 -> "tall"
            rumour.kind.startsWith("slight.") && ask.resident?.has(Trait.CHATTY) != true -> "secret"
            heard.warp == 2 -> "they"
            else -> when (heard.venue) {
                Venue.SEEN -> "seen"
                Venue.HOUSEHOLD -> "home"
                Venue.TAVERN -> "tavern"
                Venue.WELL -> "well"
                Venue.MARKET -> "market"
                Venue.WORK -> "work"
                Venue.CRIER -> "crier"
                Venue.AFAR -> "afar"
                Venue.VISIT, Venue.TEMPLE, Venue.STROLL, Venue.STREET -> "they"
            }
        }
        val from = if (heard.from >= 0 && heard.venue != Venue.SEEN) Residents.get(ask.level)[heard.from]?.name else null
        return Say.lead(ask, "news.frame.$frame", from?.let { Speech.of("talk.detail.news.from", "=$it") })
    }

    /** The player [rumour] is about, if they're close by and the speaker can see them (not the one being told), and not pointed out lately. */
    private fun there(ask: Ask, rumour: Rumour): ServerPlayer? {
        val about = rumour.about ?: return null
        if (about == ask.player?.uuid) return null
        val them = ask.level.server.playerList.getPlayer(about) ?: return null
        if (them.level() !== ask.level || them.isSpectator || them.distanceToSqr(ask.speaker) > THERE * THERE) return null
        if (!ask.speaker.hasLineOfSight(them)) return null
        val last = pointed[about]
        if (last != null && ask.level.gameTime - last in 0 until THERE_TICKS) return null
        return them
    }
}
