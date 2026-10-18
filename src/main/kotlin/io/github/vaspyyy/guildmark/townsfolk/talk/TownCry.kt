package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.block.MarketBoardBlockEntity.Slip
import io.github.vaspyyy.guildmark.townsfolk.Crier
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel

/**
 * The talk of the town (living-world/design-voices.md section 4): once a day the hottest rumour a capital's people are
 * telling each other, of its own or word from afar, at [HOT] or more and never cried before, is the crier's to cry at
 * noon as one of the noon's items, told straight, with a slip of its own for the market board ([KIND], on a paper of
 * its own) saying how widely it's known. Everyone listening knows it then (see [listened], and the noon of [Spread]).
 *
 * The crier's own file has two lines to take this in (they're the lead's to add, after M3's changes there): in
 * `Crier.news`, among the groups, `if (entry != null) TownCry.cry(level, entry.capital, c.day)?.let { groups.add(listOf(it)) }`;
 * and in `Crier.round`, where the bell rings off (`crying.remove(p)`), `TownCry.listened(level, p)`.
 */
object TownCry {
    /** How hot a rumour must be to be the talk of the town. */
    const val HOT = 0.5f

    /** Known by this share of the town or more, the whole town is talking about it; by this share, half the town has heard. */
    const val WHOLE = 0.75
    const val HALF = 0.4

    /** The kind of slip it's pinned up on (`Slip.Kind.TALK` once the board knows it; an older board draws it on the word paper). */
    const val KIND = "talk"

    /** How near the crier the folk and players are who hear it as the bell rings off. */
    private const val HEARD = 32.0

    /**
     * Today's talk of the town at [entry] as of [hour]: the one chosen already today if it has been, else the hottest
     * now (and chosen for the day if [keep]). Null if nothing's hot enough.
     */
    fun pick(entry: TownTalkData.Entry, view: Spread.TownView, hour: Long, keep: Boolean = true): Story? {
        val day = Days.dayOf(hour * 1000L + 500L)
        if (view.talkDay == day) return entry.rumours[view.talk]
        var best: Story? = null
        var most = HOT
        for (s in entry.rumours.values) {
            if (s.by != Rumours.TOWN && s.by != Rumours.AFAR || s.cried || s.rumour.born > hour) continue
            val h = Rumours.heat(s, hour)
            if (h >= most) {
                most = h
                best = s
            }
        }
        if (keep) {
            view.talkDay = day
            view.talk = best?.rumour?.id ?: -1
        }
        return best
    }

    /** The crier's line for [s]: word from afar, a death, a player's deed (or misdeed), bad news, or good. */
    fun line(s: Story): String {
        val r = s.rumour
        return when {
            s.by == Rumours.AFAR -> "crier_talk_far"
            r.kind == "death" -> "crier_talk_death"
            r.about != null && (r.kind.startsWith("deed.") || r.kind.startsWith("slight.")) -> "crier_talk_deed"
            r.tone < 0 -> "crier_talk_trouble"
            else -> "crier_talk_good"
        }
    }

    /**
     * What the crier at [capital] cries of the talk of the town on [day] (the agendas' day, as the crier's
     * conditions count it), if there's any and the rumours are on: the voiced line, what it was in chat, and its slip.
     */
    fun cry(level: ServerLevel, capital: BlockPos, day: Long): Crier.Cry? {
        if (!Rumours.enabled()) return null
        val entry = TownTalkData.get(level)[capital] ?: return null
        val hour = Spread.hourOf(level)
        if (Days.dayOf(hour * 1000L + 500L) != day) return null
        val s = pick(entry, Spread.view(capital), hour) ?: return null
        Rumours.settle(s, hour)
        val line = line(s)
        val said = Component.translatable("say.guildmark.$line")
        val words = words(s.rumour)
        val text = if (words != null) said.copy().append(" ").append(words.copy().withStyle(ChatFormatting.GRAY)) else said
        val slip = Slip(KIND, Component.translatable("board.guildmark.slip.talk"), (words ?: said.copy()).append(" ").append(spread(level, entry, s)), level.gameTime)
        return Crier.Cry(line, text, slip, s.rumour.tone.coerceIn(-1, 1))
    }

    /** The rumour's facts as they really were, in words (see [RumourWords]), or null if it has none yet. */
    fun words(r: Rumour): MutableComponent? = RumourWords.detail(r, Rumours.TRUE)?.let { Speech.detail("~$it") }

    /** How much of [entry]'s town has heard [s], from 0 to 1. */
    fun share(level: ServerLevel, entry: TownTalkData.Entry, s: Story): Double {
        val living = Residents.get(level).of(entry.pos).count { it.alive }
        if (living == 0) return 0.0
        val knowers = entry.minds.values.count { s.rumour.id in it.heard }
        return (knowers.toDouble() / living).coerceIn(0.0, 1.0)
    }

    /** How widely [s] is known, as the slip says it. */
    fun spread(level: ServerLevel, entry: TownTalkData.Entry, s: Story): Component {
        val share = share(level, entry, s)
        val key = when {
            share >= WHOLE -> "whole"
            share >= HALF -> "half"
            else -> "round"
        }
        return Component.translatable("board.guildmark.slip.talk.$key")
    }

    /** [s] has been cried at [hour]: the talk of the town isn't cried again, the guild's news goes round at its full heat. */
    fun done(s: Story, hour: Long) {
        when (s.by) {
            Rumours.TOWN, Rumours.AFAR -> s.cried = true
            Rumours.GUILD -> Rumours.cried(s, hour)
        }
    }

    /**
     * The bell has rung off after [crier] cried the news: the folk of the town about them and the players near know
     * what was cried (the talk of the town, the guild's news, the market's), as the crier had it.
     */
    fun listened(level: ServerLevel, crier: Townsperson) {
        val residents = Residents.get(level)
        val r = residents.of(crier) ?: return
        val data = TownTalkData.get(level)
        val entry = data[r.capital] ?: return
        val view = Spread.view(r.capital)
        val hour = Spread.hourOf(level)
        val cried = Spread.cries(level, entry, view, hour)
        if (cried.isEmpty()) return
        for (p in level.getEntitiesOfClass(Townsperson::class.java, crier.boundingBox.inflate(HEARD, 8.0, HEARD))) {
            if (p === crier || !p.isAlive || p.isSleeping) continue
            val them = residents.of(p)?.takeIf { it.alive && it.capital == r.capital } ?: continue
            val mind = entry.mind(them.id, true)!!
            for (s in cried) Rumours.hear(entry, mind, s, hour, Rumours.CRIER, Venue.CRIER, Rumours.TRUE)
        }
        for (s in cried) Rumours.hear(entry, entry.mind(r.id, true)!!, s, hour, Rumours.SAW, Venue.CRIER, Rumours.TRUE)
        for (player in level.players()) {
            if (player.isSpectator || player.distanceToSqr(crier) > HEARD * HEARD) continue
            val heard = entry.players.getOrPut(player.uuid) { HashMap() }
            for (s in cried) heard[s.rumour.id] = Rumours.TRUE
        }
        for (s in cried) done(s, hour)
        data.changed()
    }
}
