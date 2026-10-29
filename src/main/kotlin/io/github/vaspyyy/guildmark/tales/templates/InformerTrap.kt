package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Phase
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Run
import io.github.vaspyyy.guildmark.tales.Ctx
import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_CHOOSE
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_MEET
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_TELL
import io.github.vaspyyy.guildmark.tales.templates.Informer.AT_TEST
import io.github.vaspyyy.guildmark.tales.templates.Informer.BIT_A
import io.github.vaspyyy.guildmark.tales.templates.Informer.BIT_B
import io.github.vaspyyy.guildmark.tales.templates.Informer.BIT_FRIEND
import io.github.vaspyyy.guildmark.tales.templates.Informer.FRIEND
import io.github.vaspyyy.guildmark.tales.templates.Informer.SERGEANT

/**
 * The Sergeant's Friend's trap (living-world/stories/design.md 8.10), Tales T3: what the town's close does for the tale, from the record.
 *
 *  - **The roads.** With road ambushes switched off before the leak is shown, the strikes stop of themselves and the tale ends C; with the band
 *    broken (or gone from the record) before then, so does it.
 *  - **The sergeant's own test.** Three market days in with nobody told (the meeting, or the test with no slip yet), the sergeant tries the two
 *    neighbours themselves and comes out with both clear: they will not try the friend, and the player is left with the one test that counts.
 *  - **The decoy.** With one of the three told and not yet tried, the close sends a decoy down the road (`Caravans.decoy`: a test wagon with
 *    nothing on it). If the one told is the friend, the band strikes it and is beaten off: the leak is shown, and the choice is next. Anyone else,
 *    the road stays quiet and that one is clear. A band that lies low (it raided a village today or yesterday) strikes nothing and so tells
 *    nothing: the test is tried again at the next close, and after [Informer.LIES_LOW] such closes it is given back (the one told may be told
 *    again). The record is the truth: with no road worked out, or scenes and trade aside, the answer is the record's all the same.
 *  - **The scene's days.** Two market days after the way was chosen (A or B), the Naming or the Handshake not seen, the record ends it.
 */
internal object InformerTrap {
    /** The close, heard by every step (`Template.heard`). */
    fun close(c: Ctx, t: Tale) {
        if (!t.open) return
        if (t.step <= AT_TEST && !Informer.flag(t, Informer.SEEN)) {
            if (!Caravans.ambushes()) {
                Informer.set(t, Informer.QUIET)
                c.why(t, "informer.quiet", Informer.side(t))
                c.data.changed()
                return c.end(t, Ending.C)
            }
            val band = Informer.band(c.level, t)
            if (band == null || band.slain) {
                Informer.set(t, Informer.BROKEN)
                c.why(t, "informer.broken", Informer.bandWord(t), Informer.side(t))
                c.data.changed()
                return c.end(t, Ending.C)
            }
        }
        when (t.step) {
            AT_MEET -> if (c.day > t.d0 + SELF_DAYS) selfTest(c, t)
            AT_TEST -> {
                if (Informer.pending(t) != 0) test(c, t)
                else if (c.day > t.d0 + SELF_DAYS) selfTest(c, t)
            }
            AT_TELL -> if (Informer.chosen(t) >= 0 && c.day > Informer.chosen(t) + SCENE_DAYS) c.end(t, Informer.outcome(t))
        }
    }

    /** The market days after the seed before the sergeant tries the neighbours themselves. */
    private const val SELF_DAYS = 3

    /** The market days after the choice the scene may be seen in. */
    private const val SCENE_DAYS = 1

    /**
     * The sergeant tries the two neighbours themselves, once, if nobody has been told: both come out clear (they were never the one), and the
     * test that is left is the friend's.
     */
    fun selfTest(c: Ctx, t: Tale) {
        if (!t.open || Informer.flag(t, Informer.SELF) || Informer.told(t) != 0 || Informer.pending(t) != 0) return
        if (t.step != AT_MEET && t.step != AT_TEST) return
        Informer.set(t, Informer.SELF)
        Informer.setTold(t, BIT_A or BIT_B)
        Informer.setAnswer(t, Informer.CLEAR, BIT_A or BIT_B)
        InformerWatch.answered(t.id)
        c.why(t, "informer.self", Informer.name(t, SERGEANT), Informer.name(t, Informer.NEIGHBOUR_A), Informer.name(t, Informer.NEIGHBOUR_B))
        c.data.changed()
        if (t.step == AT_MEET) c.go(t, AT_TEST)
    }

    /** The decoy for the one told and not yet tried (`tale informer test` runs it now). False if nobody is waiting to be tried. */
    fun test(c: Ctx, t: Tale): Boolean {
        if (!t.open || t.step != AT_TEST) return false
        val man = Informer.pending(t)
        if (man == 0) return false
        val level = c.level
        val band = Informer.band(level, t)
        val struck = man == BIT_FRIEND
        val leg = CaravanRoutes.cached(t.capital, Informer.road(t)) ?: CaravanRoutes.legs(level, t.capital)?.firstOrNull { it.road == Informer.road(t) }
        // The band will not stir: it raided a village today or yesterday (or is gone), and the test tells nothing
        val lying = band == null || band.slain || (leg != null && Caravans.decoy(level, c.entry, leg, band, struck) != null)
        if (lying) {
            Informer.setLow(t, Informer.low(t) + 1)
            if (Informer.low(t) >= Informer.LIES_LOW) {
                Informer.setPending(t, 0)
                Informer.untell(t, man)
                Informer.setLow(t, 0)
                Informer.setAnswer(t, Informer.RETURNED, man)
                c.why(t, "informer.untested", Informer.name(t, Informer.slotOf(man) ?: FRIEND))
            } else {
                Informer.setAnswer(t, Informer.LOWLIE, man)
                c.why(t, "informer.lowlie", Informer.bandWord(t))
            }
            InformerWatch.answered(t.id)
            c.data.changed()
            return true
        }
        Informer.setPending(t, 0)
        Informer.setLow(t, 0)
        if (struck) {
            Informer.set(t, Informer.SEEN)
            Informer.setAnswer(t, Informer.STRUCK, man)
            InformerWatch.answered(t.id)
            c.why(t, "informer.struck", Informer.side(t), Informer.name(t, FRIEND))
            c.data.changed()
            c.go(t, AT_CHOOSE)
        } else {
            Informer.setAnswer(t, Informer.CLEAR, man)
            InformerWatch.answered(t.id)
            c.why(t, "informer.clear", Informer.name(t, Informer.slotOf(man) ?: FRIEND))
            c.data.changed()
        }
        return true
    }

    /** The road the tale is on: its peril moved [by] (`RoadPeril.change`, as any strike or escort moves it). */
    fun peril(c: Ctx, t: Tale, by: Int) {
        if (!Caravans.enabled()) return
        RoadPeril.change(CaravanData.get(c.level), t.capital, Informer.road(t), by, Markets.hourOf(c.level))
    }

    /**
     * W: the band, still told the days, strikes the next of the town's wagons on the road that is on its way (a wagon of the timetable, and it is
     * waylaid as `caravan ambush ... waylaid` has it), if one is; nothing if none is, or the band is gone.
     */
    fun lastStrike(c: Ctx, t: Tale) {
        if (!Caravans.ambushes()) return
        val level = c.level
        val band = Informer.band(level, t)?.takeIf { !it.slain && it.built } ?: return
        val road = Informer.road(t)
        val rec = CaravanData.get(level).caravans
            .filter { it.capital == t.capital && it.road == road && it.run == Run.WAGON && (it.phase == Phase.PLANNED || it.phase == Phase.WAITING || it.phase == Phase.ROLLING) }
            .minByOrNull { it.setOut } ?: return
        if (Caravans.ambush(level, rec, band, true) == null) c.why(t, "informer.again", Informer.bandWord(t), Informer.side(t))
    }

    /** Whether [t]'s scene is still to come: the way chosen (A or B) at the last step. */
    fun toTell(t: Tale): Boolean = t.open && t.step == AT_TELL && (Informer.way(t) == Informer.WAY_A || Informer.way(t) == Informer.WAY_B)

    /** For `tale informer`: what the close would do with [t] tonight, in a few words. */
    fun tonight(c: Ctx, t: Tale): String {
        if (!t.open) return "nothing: it has ended"
        return when (t.step) {
            Informer.AT_ASK -> "nothing; the step lapses at the close of day ${t.by}"
            AT_MEET -> if (Informer.told(t) == 0 && c.day >= t.d0 + SELF_DAYS) "the sergeant tries the two neighbours themselves" else "nothing yet (the sergeant tries the neighbours themselves from the close of day ${t.d0 + SELF_DAYS})"
            AT_TEST -> when (Informer.pending(t)) {
                0 -> if (Informer.told(t) == 0) "the sergeant tries the two neighbours themselves" else "nothing: nobody is told and untried"
                BIT_FRIEND -> "a decoy goes down the ${Informer.side(t)} road, and the band strikes it (the friend talks)"
                else -> "a decoy goes down the ${Informer.side(t)} road, and nothing strikes it (${Informer.names(t, Informer.pending(t))} is clear)"
            }
            AT_CHOOSE -> "nothing; at the close of day ${t.by} the sergeant doubles the watch (C)"
            else -> "the scene waits until the close of day ${Informer.chosen(t) + SCENE_DAYS}; then the record ends it ${Informer.outcome(t)}"
        }
    }
}
