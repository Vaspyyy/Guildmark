package io.github.vaspyyy.guildmark.townsfolk.talk

import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.village.StandingTier
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a capital's people remember of the players (living-world/design-voices.md section 3), as the records in
 * [TownTalkData] keep it: each resident's acquaintance with each player ([Acq]), the opinion and familiarity worked out
 * from it, and what befell them ([Felt]). Every score is kept with the day it was last set and faded when it's read
 * (`score × 0.5^(days / halfLife)`), so nothing needs going over day by day; what has faded away for good is let go
 * at dawn ([tidy]). Days here are the agendas' days ([Days.dayOf], from two in the morning to two), the same the talk
 * compares them with.
 */
object Memory {
    /** The most players a resident remembers, deeds seen of each, and things that befell them. */
    const val ACQUAINTANCES = 16
    const val DEEDS = 4
    const val EXPERIENCES = 4

    /** Days a name is kept after the player was last about; a lukewarm acquaintance; a deed seen; a big deed seen. */
    const val NAME_DAYS = 120
    const val KEEP_DAYS = 60
    const val DEED_DAYS = 30
    const val BIG_DEED_DAYS = 60

    /** Half-lives in days: the Marks spent at their stall, a favour, and the opinion the test command sets. */
    const val SPENT_HALF = 30.0
    const val FAVOUR_HALF = 10.0
    const val SET_HALF = 10.0

    /** The three kinds of slight by how long they weigh: small, harm and a killing (their half-lives in days). */
    val HALVES = intArrayOf(5, 15, 60)

    /** Opinions at or past these are hostile, wary, or (met as well) a friend's. */
    const val HOSTILE = -50
    const val WARY = -15
    const val FRIEND = 40

    /** The most deeds counted each way in an opinion. */
    private const val MOST_DEEDS = 4

    /** A slight that has faded below this is no longer the one that weighs. */
    private const val FELT_SLIGHT = 0.05

    fun enabled(): Boolean = try {
        GuildmarkConfig.TOWNSFOLK_MEMORY.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The day as memory counts it: the agendas' day. */
    fun day(level: ServerLevel): Int = Days.dayOf(level.overworldClockTime).toInt()

    // ---- Scores ----

    /** [s] as it stands on [day], faded by its [half]-life in days. */
    fun faded(s: Score, day: Int, half: Double): Double =
        if (s.value == 0.0) 0.0 else s.value * 0.5.pow(max(0, day - s.day) / half)

    /** Adds [amount] to [s] on [day]. */
    fun add(s: Score, day: Int, half: Double, amount: Double) {
        s.value = faded(s, day, half) + amount
        s.day = day
    }

    /** Which of the three slights [slight] adds to. */
    fun bucket(slight: Slight): Int = HALVES.indexOfFirst { slight.halfLife <= it }.let { if (it < 0) HALVES.size - 1 else it }

    /** Slight [k] of [a] as it weighs on [day]. */
    fun hurt(a: Acq, k: Int, day: Int): Double {
        val h = a.slights[k]
        return if (h.value == 0.0) 0.0 else h.value * 0.5.pow(max(0, day - h.day) / HALVES[k].toDouble())
    }

    /** All [a]'s slights together as they weigh on [day]. */
    fun slights(a: Acq, day: Int): Double = a.slights.indices.sumOf { hurt(a, it, day) }

    /** The slight that weighs most with [a] on [day], if any still does. */
    fun worst(a: Acq, day: Int): Slight? {
        var worst: Slight? = null
        var most = FELT_SLIGHT
        for (k in a.slights.indices) {
            val v = hurt(a, k, day)
            if (v > most) {
                most = v
                worst = a.slights[k].kind
            }
        }
        return worst
    }

    /** The deeds [a] saw that they still remember on [day], newest first. */
    fun deeds(a: Acq, day: Int): List<Saw> =
        a.saw.filter { day - it.day <= if (it.big) BIG_DEED_DAYS else DEED_DAYS }.sortedByDescending { it.day }

    // ---- Opinion and familiarity ----

    /** +5 for the cheerful, -5 for the grumpy. */
    fun leaning(r: Resident): Int = (if (r.has(Trait.CHEERFUL)) 5 else 0) - (if (r.has(Trait.GRUMPY)) 5 else 0)

    /** What the player's standing at the capital's board adds. */
    fun standing(tier: StandingTier?): Int = when (tier) {
        StandingTier.KNOWN -> 5
        StandingTier.TRUSTED -> 10
        StandingTier.HONORED -> 15
        StandingTier.HERO -> 25
        else -> 0
    }

    /**
     * The deeds that count in an opinion: those [seen] and the rumours [heard] about the player, each +1 or -1 by its
     * tone, at most [MOST_DEEDS] each way; a deed seen and heard of counts once.
     */
    fun deedScore(seen: List<Saw>, heard: List<Rumour>): Int {
        val counted = HashSet<Int>()
        var good = 0
        var bad = 0
        for (s in seen) {
            if (s.rumour >= 0 && !counted.add(s.rumour)) continue
            if (s.tone > 0) good++ else if (s.tone < 0) bad++
        }
        for (r in heard) {
            if (!counted.add(r.id)) continue
            if (r.tone > 0) good++ else if (r.tone < 0) bad++
        }
        return min(good, MOST_DEEDS) - min(bad, MOST_DEEDS)
    }

    /** What [a] alone adds to an opinion on [day] (no leaning, standing or rumours): how strong the feeling is. */
    fun feeling(a: Acq, day: Int): Double =
        3.0 * min(a.days, 5) + min(a.trades, 10) + 10.0 * faded(a.favour, day, FAVOUR_HALF) - 10.0 * slights(a, day) +
            5.0 * deedScore(deeds(a, day), listOf()) + faded(a.set, day, SET_HALF)

    /**
     * [r]'s opinion of a player on [day], from -100 to 100: their leaning, the player's standing at the board, the days
     * they've talked, their trades, the favour and slights felt now, and the [deeds] seen or heard of.
     */
    fun opinion(r: Resident, a: Acq?, day: Int, deeds: Int, tier: StandingTier?): Int {
        var o = leaning(r) + standing(tier) + 5.0 * deeds
        if (a != null) {
            o += 3.0 * min(a.days, 5) + min(a.trades, 10) + 10.0 * faded(a.favour, day, FAVOUR_HALF) - 10.0 * slights(a, day) +
                faded(a.set, day, SET_HALF)
        }
        return o.roundToInt().coerceIn(-100, 100)
    }

    /**
     * How well someone knows a player with [opinion], from what they keep of them ([a], null if nothing) and whether
     * they've [heardOf] them (a rumour about them, or their standing).
     */
    fun familiarity(opinion: Int, a: Acq?, heardOf: Boolean): Familiarity {
        val known = a != null || heardOf
        val met = a != null && a.met >= 0
        return when {
            known && opinion <= HOSTILE -> Familiarity.HOSTILE
            known && opinion <= WARY -> Familiarity.WARY
            met && opinion >= FRIEND -> Familiarity.FRIEND
            met && (a!!.days >= 2 || a.trades >= 3) -> Familiarity.ACQUAINTED
            met -> Familiarity.MET
            known -> Familiarity.HEARD_OF
            else -> Familiarity.STRANGER
        }
    }

    /** Whether [a] still has the player's name on [day]. */
    fun hasName(a: Acq?, day: Int): Boolean = a != null && a.named && day - a.last <= NAME_DAYS

    /** [a] worked out for [day] as the read-only view the talk is given. */
    fun view(a: Acq, day: Int, named: Boolean, opinion: Int): Acquaintance = Acquaintance(
        a.player, a.met, named, a.seen, a.days, a.trades, faded(a.spent, day, SPENT_HALF).roundToInt(),
        faded(a.favour, day, FAVOUR_HALF), slights(a, day), worst(a, day),
        deeds(a, day).map { Deed(it.kind, it.day, it.tone) }, opinion,
    )

    // ---- Keeping ----

    /** What [mind] keeps of [player], made on [day] if [make] and they have nothing yet (the weakest feeling makes room). */
    fun acq(mind: Mind, player: UUID, day: Int, make: Boolean): Acq? {
        mind.known[player]?.let { return it }
        if (!make) return null
        if (mind.known.size >= ACQUAINTANCES) {
            mind.known.values.minWithOrNull(compareBy<Acq>({ abs(feeling(it, day)) }, { it.last }))?.let { mind.known.remove(it.player) }
        }
        return Acq(player).also {
            it.last = day
            mind.known[player] = it
        }
    }

    /** They talked or dealt on [day]: met from then on, one more day of it. */
    fun met(a: Acq, day: Int) {
        if (a.met < 0) a.met = day
        if (a.seen != day) {
            a.days++
            a.seen = day
        }
        a.last = max(a.last, day)
    }

    /** The player told them their name. */
    fun named(a: Acq, day: Int) {
        met(a, day)
        a.named = true
    }

    /** The player dealt at their stall for [marks] Marks. */
    fun traded(a: Acq, day: Int, marks: Int) {
        met(a, day)
        a.trades++
        add(a.spent, day, SPENT_HALF, marks.coerceAtLeast(0).toDouble())
    }

    /** The player did them [favour]. */
    fun favour(a: Acq, day: Int, favour: Favour) {
        add(a.favour, day, FAVOUR_HALF, favour.amount)
        a.last = max(a.last, day)
    }

    /** The player wronged them ([slight]); false if it doesn't count again (walking off counts once a day). */
    fun slight(a: Acq, day: Int, slight: Slight): Boolean {
        if (slight == Slight.WALKED_OFF) {
            if (a.off == day) return false
            a.off = day
        }
        val k = bucket(slight)
        val before = hurt(a, k, day)
        val h = a.slights[k]
        h.value = before + slight.amount
        h.day = day
        // The slight that weighs most in this kind is the one it's remembered by
        val kind = h.kind
        if (kind == null || slight.amount >= kind.amount || before < slight.amount) h.kind = slight
        a.last = max(a.last, day)
        return true
    }

    /** They saw the player do a deed of [kind] (a rumour's kind, [rumour] its id) on [day]. */
    fun saw(a: Acq, day: Int, kind: String, tone: Int, big: Boolean, rumour: Int) {
        if (rumour >= 0) a.saw.removeAll { it.rumour == rumour }
        a.saw.add(Saw(kind, day, tone, big, rumour))
        while (a.saw.size > DEEDS) a.saw.removeAt(0)
        a.last = max(a.last, day)
    }

    /** Sets the opinion of [a] to [target] from [now] (for testing): the difference fades out over [SET_HALF] days. */
    fun set(a: Acq, day: Int, now: Int, target: Int) {
        add(a.set, day, SET_HALF, (target - now).toDouble())
        a.last = max(a.last, day)
    }

    // ---- What befell them ----

    /** [experience] befell them on [day] ([about]: the resident it concerned, or -1): the newest are kept. */
    fun felt(mind: Mind, experience: Experience, day: Int, about: Int) {
        mind.felt.removeAll { day - it.day >= it.experience.days || it.experience == experience && it.about == about }
        mind.felt.add(Felt(experience, day, about))
        while (mind.felt.size > EXPERIENCES) mind.felt.removeAt(0)
    }

    /** What still stays with [mind] on [day], newest first. */
    fun felt(mind: Mind?, day: Int): List<Felt> =
        mind?.felt?.filter { day - it.day in 0 until it.experience.days }?.sortedByDescending { it.day } ?: listOf()

    /**
     * At dawn: forgotten names, lukewarm acquaintances not seen for [KEEP_DAYS], deeds and experiences past their time
     * are let go. True if anything was.
     */
    fun tidy(mind: Mind, day: Int): Boolean {
        var changed = false
        val each = mind.known.values.iterator()
        while (each.hasNext()) {
            val a = each.next()
            if (a.saw.removeAll { day - it.day > if (it.big) BIG_DEED_DAYS else DEED_DAYS }) changed = true
            if (a.named && day - a.last > NAME_DAYS) {
                a.named = false
                changed = true
            }
            if (day - a.last > KEEP_DAYS) {
                val f = feeling(a, day)
                if (f > WARY && f < FRIEND) {
                    each.remove()
                    changed = true
                }
            }
        }
        if (mind.felt.removeAll { day - it.day >= it.experience.days }) changed = true
        return changed
    }
}
