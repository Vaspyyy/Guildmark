package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.tales.Ending
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Idle
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.Sit
import io.github.vaspyyy.guildmark.townsfolk.Social
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walk
import io.github.vaspyyy.guildmark.townsfolk.scenes.Stage
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * The silence of the Silent Lute (living-world/stories/design.md 8.6): the square without its songs. `Jobs.bard` asks [silent] before it
 * sends a bard to play, and a bard who is told so does [quiet] instead: sits on the nearest bench of the square by day (a seat in the
 * tavern of an evening), hands empty, with a sigh and a shrug now and then. She never takes up `Perform`, so `Social.performingBard`
 * finds nobody playing and no one gathers as an `Audience`: nothing is faked, the square is quiet because no one plays in it. The crowd
 * bard of `Leisure.next` is held to the same word.
 *
 * Nothing of its own is saved. The silence is read from the lute tales the record keeps (11.1), each time it is asked and then kept for
 * five seconds:
 *
 *  - while a lute tale of the capital is open, from its seed until the lute is back in her hands (the full sum, the first half of a B, or the
 *    night's loan: `n[BACK]`);
 *  - in the hush before the concert (see [silentBy]): the lute is hers, but she sits the evening out until the stage asks for her;
 *  - for ten market days after an ending W (the lute was sold) and after an ending C (the lute went back to the keeper), from the day it ended.
 *
 * Off (tales off), none of it holds, and the bards play as they ever did.
 */
object LuteSilence {
    const val ID = "lute"

    /** Market days the silence lasts after an ending W, and after the lute of a C has gone back to its keeper. */
    const val TAIL = 10

    /** The hours of the evening the concert plays in and the bard is kept for it (her tavern stretch, to eight). */
    val HUSH: ClosedFloatingPointRange<Double> = 18.0..20.0

    /** Ticks a capital's answer is kept before the record is read again. */
    private const val KEPT = 100L

    private class Known(val at: Long, val silent: Boolean)

    private val known = HashMap<Long, Known>()

    /** What a bard of a lute tale's capital does while the silence holds, in turn: sits, sighs, sits, shrugs. */
    private val MOODS = listOf(Activity.SIT, Activity.SIT, Activity.SIGH, Activity.SIT, Activity.SHRUG, Activity.SIT)

    // ---- From Jobs.bard ----

    /** Whether the bard [p] plays no music now, for a lute tale's sake: false for anyone else, and with tales off. */
    fun silent(p: Townsperson): Boolean {
        if (p.role != TownRole.BARD || !Tales.live) return false
        val level = p.level() as? ServerLevel ?: return false
        val capital = capital(level, p) ?: return false
        val now = level.gameTime
        known[capital]?.let { if (now >= it.at && now - it.at < KEPT) return it.silent }
        val silent = silentIn(level, capital)
        known[capital] = Known(now, silent)
        return silent
    }

    /** Lets the capital's answer be worked out again at once (a tale just changed the lute's whereabouts). */
    fun refresh(capital: Long) {
        known.remove(capital)
    }

    fun forget() = known.clear()

    /** The key tales go by for the capital [p] belongs to: a resident's, else the town they are in. */
    private fun capital(level: ServerLevel, p: Townsperson): Long? {
        if (p.resident >= 0) Residents.get(level)[p.resident]?.let { return it.capital.asLong() }
        return p.town?.center?.asLong()
    }

    /** Whether the capital whose heart is [capital] has the lute out of its bard's hands today, read from the record. */
    fun silentIn(level: ServerLevel, capital: Long): Boolean {
        val today = TaleEngine.today(level, capital)
        for (t in TaleData.get(level).all()) {
            if (t.template != ID || t.capital != capital) continue
            if (silentBy(level, t, today)) return true
        }
        return false
    }

    /**
     * Whether the lute tale [t] holds the square silent on market [today]:
     *
     *  - while it is open and the lute is not in her hands (`n[BACK]` 0);
     *  - in the hush before the concert: the lute is hers again but the evening's song is still to be played (step 3, `n[PLAYED]` 0, the
     *    hours the concert may play in), so that she is sitting, hands empty, when the stage asks for her, and not already playing (a bard
     *    at her `Perform` is anchored and no scene may take her);
     *  - for [TAIL] market days from the day an ending W or C ended, unless the tale's own bard is gone (then the bards who are left play).
     */
    fun silentBy(level: ServerLevel, t: Tale, today: Long): Boolean = when {
        t.open -> t.n[LuteSlots.BACK] == 0 || hush(level, t)
        t.ending == Ending.W || t.ending == Ending.C -> t.ended != TaleData.NEVER && today - t.ended in 0..TAIL.toLong() && t.cast("bard")?.gone.isNullOrEmpty()
        else -> false
    }

    /** Whether it is the hush before the concert of [t]: its step, the evening hours, and scenes on. */
    private fun hush(level: ServerLevel, t: Tale): Boolean =
        t.step == LuteSlots.AT_SCENE && t.n[LuteSlots.PLAYED] == 0 && Tales.scenes() && Stage.within(level, HUSH)

    /** The market day the silence of the ended tale [t] lifts (its last silent day), or null if an ending leaves none. */
    fun until(t: Tale): Long? =
        if (!t.open && (t.ending == Ending.W || t.ending == Ending.C) && t.ended != TaleData.NEVER) t.ended + TAIL else null

    // ---- Quiet ----

    /**
     * What a bard who is [silent] does in place of playing (design 8.6): by day she sits on the nearest bench of the square, of an evening on a
     * seat of the tavern, hands empty, and sighs or shrugs now and then; with no seat free she stands about the square. Always a task, so that
     * her day goes on as a day does.
     */
    fun quiet(p: Townsperson): Task? {
        val level = p.level() as? ServerLevel ?: return null
        val evening = isEvening(level, p)
        val square = p.town?.square
        val around: BlockPos? = (if (evening) Social.tavern(p) else null) ?: square
        val seat = around?.let { Places.seat(level, it, if (evening && it != square) TAVERN_REACH else SQUARE_REACH, p) }
        if (seat != null) return Sit(p, seat, 500 + p.random.nextInt(700), MOODS)
        val at = square ?: around ?: return Idle(p, 200 + p.random.nextInt(200))
        val spot = Places.street(level, at, 6, p.random, tries = 6) ?: at
        val stand = Idle(p, 300 + p.random.nextInt(300))
        if (p.distanceToSqr(Vec3.atBottomCenterOf(spot)) > 16.0) return Walk(p, spot, Jobs.WALK, near = 3.0).andThen(stand)
        return stand
    }

    /** Whether [p]'s day has them at the tavern now (the evening call of `Jobs.bard`). */
    private fun isEvening(level: ServerLevel, p: Townsperson): Boolean {
        val life = Days.life(p) ?: return false
        return Days.stretch(life, level.overworldClockTime).doing == Doing.TAVERN
    }

    private const val SQUARE_REACH = 14
    private const val TAVERN_REACH = 9
}

/**
 * The numbers the lute tale keeps in `Tale.n`, by their place (`Lute.numbers` names them for `tale show`): the way chosen at the keeper, the Marks
 * put in so far, the sum, whether the lute is in the bard's hands and whether the concert has played through.
 */
object LuteSlots {
    const val WAY = 0
    const val PAID = 1
    const val SUM = 2
    const val BACK = 3
    const val PLAYED = 4

    /** The step the concert is (the template's table: ask, choose, give, scene). */
    const val AT_SCENE = 3

    /** The ways: none chosen yet, A (all at once), B (in two parts), C (a night's loan). */
    const val NONE = 0
    const val A = 1
    const val B = 2
    const val C = 3
}
