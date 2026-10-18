package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.festival.FestivalCalendar
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Choice
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel

/**
 * The grand festival in the speaker's town (section 1): coming in the next three days, today (what's on at this hour,
 * as the festival itself has it), and the evening and day after (who took the prize, as the prize-giving had it). The
 * talk screen gets "About the festival..." while any of those holds, and its answer says what's on and how the day's
 * contest goes. Children have their own line for the days before and the day itself.
 */
object FestivalTopics : TopicSource {
    override val id = "festival"

    /** The choice this source adds to the talk screen. */
    const val ASK = "festival:ask"

    private val OCCASIONS = Say.TALK + Occasion.BARK + Occasion.NEWS

    /** How many days ahead a festival is talked of. */
    private const val SOON = 3

    /** A festival held in a town, as its evening found it: which, the day it was held, and who took the prize. */
    class Held(val kind: FestivalKind, val day: Long, val winner: String)

    /** The last festival held in each town, by the town's key (a capital's centre, packed); kept in memory only. */
    private val held = HashMap<Long, Held>()

    /** Where the festivals are, every five seconds: one that's reached its evening is remembered, with its winner. */
    fun watch(level: ServerLevel) {
        if (!Seasons.festivalsEnabled()) return
        for (site in Festivities.all()) {
            if (!site.live || site.phase.ordinal < FestivalPhase.DUSK.ordinal) continue
            held[site.key] = Held(site.kind, Say.day(level), winner(site))
        }
    }

    fun forget() = held.clear()

    /** Who took the prize at [site]: the best in its contest, or the townsperson it went to if nobody entered. */
    private fun winner(site: FestivalSite): String = site.contest.results().firstOrNull()?.name ?: QuestGenerator.nameFor(site.key xor site.run)

    /** What the festival in [ask]'s town is at now, if it's coming, on or just over. */
    private sealed class Now {
        class Soon(val kind: FestivalKind, val days: Int) : Now()
        class Today(val kind: FestivalKind, val phase: FestivalPhase, val site: FestivalSite?) : Now()
        class After(val held: Held) : Now()
    }

    private fun now(ask: Ask): Now? {
        if (!Seasons.festivalsEnabled()) return null
        val level = ask.level
        val key = ask.resident?.capital?.asLong() ?: return null
        val time = Say.time(level)
        val site = Festivities.all().firstOrNull { it.key == key && it.live }
        if (site != null && site.phase != FestivalPhase.TEARDOWN) return Now.Today(site.kind, site.phase, site)
        val (kind, days) = FestivalCalendar.next(Seasons.today(level))
        if (days == 0 && time < FestivalPhase.TEARDOWN.from && site == null) return Now.Today(kind, FestivalPhase.at(time), null)
        val last = held[key]
        val today = Say.day(level)
        if (last != null && (last.day == today && time >= FestivalPhase.TEARDOWN.from || last.day == today - 1 && time < FestivalPhase.TEARDOWN.from)) {
            return Now.After(last)
        }
        if (days in 1..SOON) return Now.Soon(kind, days)
        return null
    }

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS) return
        val child = Say.child(ask.speaker)
        when (val now = now(ask) ?: return) {
            is Now.Soon -> {
                val say = Say.line(ask, if (child) "child.festival" else soonFamily(ask, now.kind), soon(now.kind, now.days)) ?: return
                out.add(Topic("festival.soon.${now.kind.id}.${now.days}", Kind.FESTIVAL, 0.6, say, tone = 1))
            }
            is Now.Today -> {
                val say = Say.line(ask, if (child) "child.festival" else "town.festival_today", phase(now.kind, now.phase)) ?: return
                out.add(Topic("festival.today.${now.kind.id}.${now.phase.id}", Kind.FESTIVAL, 0.9, say, tone = 1))
            }
            is Now.After -> {
                if (child) return
                val say = Say.line(ask, "town.festival_after", Speech.of("talk.detail.festival.won", "=${now.held.winner}")) ?: return
                out.add(Topic("festival.after.${now.held.kind.id}.${now.held.day}", Kind.FESTIVAL, 0.5, say, tone = 1))
            }
        }
    }

    override fun choice(ask: Ask): Choice? = if (now(ask) != null) Choice(ASK, Component.translatable("gui.guildmark.talk.festival")) else null

    override fun answer(ask: Ask, choice: String): Utterance? {
        if (choice != ASK) return null
        val child = Say.child(ask.speaker)
        val parts = when (val now = now(ask) ?: return null) {
            is Now.Soon -> listOf(
                Say.part(ask, if (child) "child.festival" else soonFamily(ask, now.kind), soon(now.kind, now.days)),
                Utterance.Part("", "talk.detail.festival.how.${now.kind.id}"),
            )
            is Now.Today -> listOf(
                Say.part(ask, if (child) "child.festival" else "town.festival_today", phase(now.kind, now.phase)),
                Utterance.Part("", afterPhase(now)),
            )
            is Now.After -> {
                val (next, days) = FestivalCalendar.next(Seasons.today(ask.level).plus(1L))
                listOf(
                    Say.part(ask, "town.festival_after", Speech.of("talk.detail.festival.won", "=${now.held.winner}")),
                    Utterance.Part("", Speech.of("talk.detail.festival.next", "festival.guildmark.${next.id}", days + 1)),
                )
            }
        }
        return Utterance(parts.filterNotNull())
    }

    /** The town's own line for [kind] coming ("Sheafmoot soon. I've a pie to bake for it.") or the plain one, as recorded. */
    private fun soonFamily(ask: Ask, kind: FestivalKind): String {
        val own = "town.${kind.id}"
        val plain = "town.festival_soon"
        return when {
            TalkLines.FAMILIES[own] == null -> plain
            TalkLines.FAMILIES[plain] == null -> own
            else -> if (ask.speaker.random.nextBoolean()) own else plain
        }
    }

    /** "Sheafmoot in 2 days." or "Sheafmoot, tomorrow." */
    private fun soon(kind: FestivalKind, days: Int): String =
        if (days == 1) Speech.of("talk.detail.festival.tomorrow", "festival.guildmark.${kind.id}")
        else Speech.of("talk.detail.festival.soon", "festival.guildmark.${kind.id}", days)

    /** What's on at [phase] of [kind]'s day. */
    private fun phase(kind: FestivalKind, phase: FestivalPhase): String = when (phase) {
        FestivalPhase.SETUP -> "talk.detail.festival.setup"
        FestivalPhase.FAIR -> Speech.of("talk.detail.festival.fair", "festival.guildmark.contest.${kind.id}")
        FestivalPhase.CEREMONY -> "talk.detail.festival.ceremony"
        else -> if (kind == FestivalKind.LANTERNRISE) "talk.detail.festival.dusk.lanternrise" else "talk.detail.festival.dusk"
    }

    /** The rest of the answer on the day: how the contest goes while it's open, then who took the prize, or what it's all about. */
    private fun afterPhase(now: Now.Today): String {
        if (now.phase == FestivalPhase.SETUP || now.phase == FestivalPhase.FAIR) return "talk.detail.festival.how.${now.kind.id}"
        val site = now.site
        if (site != null && now.phase == FestivalPhase.DUSK) return Speech.of("talk.detail.festival.won", "=${winner(site)}")
        return Speech.of("talk.detail.festival.about", "festival.guildmark.${now.kind.id}.about")
    }
}
