package io.github.vaspyyy.guildmark.tales.templates

import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.festival.FestivalPhase
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.tales.Tale
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.townsfolk.Days
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The hours of A Lantern for Nell (living-world/stories/design.md 8.9), in one place: the old resident is at their door in the
 * middle of the day, the walk to the square can set out between noon and half past five on the festival's day, the town's lanterns
 * are let go at nightfall and the festival comes down at eight. Hours are offsets into the Living World's day (`Days`: 0 at two in
 * the morning, as the market's day turns), and the days are market days: the festival's day F is `d0 + n[festival]`, kept as an
 * offset from the day the tale began.
 *
 * A Lanternrise forced in the capital with `/guildmark festival start lanternrise` keeps a clock of its own (`FestivalSite.local`,
 * 0 at six in the morning, moved by `festival phase`): while one is live there it is the festival's day whatever the record's day,
 * its own clock gives the hours, and the walk may set out from the start of its fair (rather than from noon) so a test need not wait
 * five minutes for it. A festival that keeps to the calendar is read by the world's clock.
 */
internal object LanternClock {
    /** The old resident is at their door from ten, until five on a day before the festival and until nine on the day. */
    val DOOR_FROM: Int = Days.clock(10.0)
    val DOOR_UNTIL: Int = Days.clock(17.0)
    val DOOR_UNTIL_FESTIVAL: Int = Days.clock(21.0)

    /** The walk sets out from noon until half past five on the day (from the fair's start at a forced Lanternrise). */
    val NOON: Int = Days.clock(12.0)
    val LATEST: Int = Days.clock(17.5)
    val FAIR: Int = SIX + FestivalPhase.FAIR.from

    /** The town stands up to watch at six, the lanterns go up at seven (`FestivalSite.LANTERNS_AT`), the festival comes down at eight. */
    val GATHER: Int = SIX + FestivalPhase.DUSK.from
    val DUSK: Int = SIX + FestivalSite.LANTERNS_AT
    val TEARDOWN: Int = SIX + FestivalPhase.TEARDOWN.from

    /** How the walk stands at this hour: not the day, the day but before it can set out, the hours it can set out in, or past them. */
    enum class Walk { NOT_TODAY, EARLY, OPEN, LATE }

    /** The live Lanternrise of [t]'s capital, if one is on there now (by the calendar or forced), or null. */
    fun site(t: Tale): FestivalSite? = Festivities.all().firstOrNull { it.live && it.kind == FestivalKind.LANTERNRISE && it.key == t.capital }

    /** The live Lanternrise whose square is at [centre] (the festival's own centre, as `FestivalSite.lanterns` gives it), or null. */
    fun siteAt(centre: net.minecraft.world.phys.Vec3): FestivalSite? =
        Festivities.all().firstOrNull { it.live && it.kind == FestivalKind.LANTERNRISE && it.centre.distanceToSqr(centre) < 1.0 }

    /** The hour of [t]'s day: a forced Lanternrise's own clock while one is live in its capital, else the world's. */
    fun hour(level: ServerLevel, t: Tale): Int {
        val site = site(t)
        if (site != null && site.forced) return Math.floorMod(site.local(level) + SIX, 24000)
        return Days.offsetOf(level.overworldClockTime)
    }

    /** The festival's market day F. */
    fun festival(t: Tale): Long = t.d0 + t.n[Lantern.N_FESTIVAL]

    /** The market day tales stand at in [t]'s capital. */
    fun today(level: ServerLevel, t: Tale): Long = TaleEngine.today(level, t.capital)

    /** Whether it is the festival's day: its market day, or a forced Lanternrise is live in the capital before the day is past. */
    fun festivalDay(level: ServerLevel, t: Tale): Boolean {
        val day = today(level, t)
        val f = festival(t)
        return day == f || day < f && site(t)?.forced == true
    }

    /** Whole market days to the festival, for the grey words: 0 on the day itself (or with a forced Lanternrise live in the capital). */
    fun daysTo(level: ServerLevel, t: Tale): Long = if (festivalDay(level, t)) 0L else (festival(t) - today(level, t)).coerceIn(0L, 12L)

    /** Whether the walk can set out now, or why not. */
    fun walk(level: ServerLevel, t: Tale): Walk {
        val day = today(level, t)
        val f = festival(t)
        if (day > f) return Walk.LATE
        if (!festivalDay(level, t)) return Walk.NOT_TODAY
        val hour = hour(level, t)
        return when {
            hour < from(t) -> Walk.EARLY
            hour < LATEST -> Walk.OPEN
            else -> Walk.LATE
        }
    }

    /** The hour the walk can set out from on the day: noon, or the start of the fair at a Lanternrise forced in the capital. */
    fun from(t: Tale): Int = if (site(t)?.forced == true) FAIR else NOON

    /** Whether the old resident is at their door at this hour (a day before the festival, ten to five; the day, ten to nine). */
    fun atDoor(level: ServerLevel, t: Tale): Boolean {
        val day = today(level, t)
        if (day > festival(t)) return false
        val hour = hour(level, t)
        return hour >= DOOR_FROM && hour < if (festivalDay(level, t)) DOOR_UNTIL_FESTIVAL else DOOR_UNTIL
    }

    /** Whether it is the festival's day at or after six, when the town gathers to watch (the old resident at the square stands up). */
    fun gathering(level: ServerLevel, t: Tale): Boolean = festivalDay(level, t) && hour(level, t) >= GATHER

    /** Whether it is the festival's day at or after the lanterns' hour (seven), and before nine. */
    fun dusk(level: ServerLevel, t: Tale): Boolean = festivalDay(level, t) && hour(level, t) in DUSK until DOOR_UNTIL_FESTIVAL

    /** Whether the festival has come down (eight on the day, or a day after it): too late for the square. */
    fun over(level: ServerLevel, t: Tale): Boolean {
        val day = today(level, t)
        if (day > festival(t)) return true
        val site = site(t)
        if (site != null) return site.phase == FestivalPhase.TEARDOWN
        return festivalDay(level, t) && hour(level, t) >= TEARDOWN
    }

    /** The door the old resident sits at, as the seed kept it, or null. */
    fun door(t: Tale): BlockPos? = t.s[Lantern.S_DOOR].toLongOrNull()?.let { BlockPos.of(it) }

    /** The clock time of [hour] (an offset into the Living World's day), as "17:30". */
    fun text(hour: Int): String = Days.hourOf(hour)

    /** Six in the morning, where a festival's own clock starts, as an offset into the Living World's day. */
    private val SIX: Int get() = Days.clock(6.0)
}
