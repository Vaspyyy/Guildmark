package io.github.vaspyyy.guildmark.season

import net.minecraft.network.chat.Component

/** The four seasons, in the order a year turns through them. */
enum class Season(val id: String, val color: Int) {
    SPRING("spring", 0xA8DA7A),
    SUMMER("summer", 0xF0C454),
    AUTUMN("autumn", 0xE0843A),
    WINTER("winter", 0xB4D2EA);

    val next: Season get() = entries[(ordinal + 1) % 4]
    val previous: Season get() = entries[(ordinal + 3) % 4]

    /** "Spring". */
    val title: Component get() = Component.translatable("season.guildmark.$id")

    companion object {
        fun byId(id: String): Season? = entries.firstOrNull { it.id == id }
    }
}

/** A season's first, middle and last third. */
enum class SeasonPhase(val id: String) {
    EARLY("early"),
    MID("mid"),
    LATE("late");

    /** "Early Spring", "Mid-Summer", "Late Autumn". */
    fun of(season: Season): Component = Component.translatable("season.guildmark.phase.$id", season.title)
}

/**
 * A day of the calendar: [calendarDay] counted from the world's first (day 0, the first of spring, year 1), with
 * seasons [length] days long. Everything else is worked out from those two.
 */
data class SeasonDate(val calendarDay: Long, val length: Int) {
    private val yearLength: Long get() = 4L * length

    /** Which year it is, from 1. */
    val year: Int get() = (Math.floorDiv(calendarDay, yearLength) + 1).toInt()

    /** The day of the year, from 0. */
    val dayOfYear: Int get() = Math.floorMod(calendarDay, yearLength).toInt()

    val season: Season get() = Season.entries[dayOfYear / length]

    /** The day of the season, from 1 to [length]. */
    val day: Int get() = dayOfYear % length + 1

    val phase: SeasonPhase
        get() {
            val third = length / 3
            return when {
                day <= third -> SeasonPhase.EARLY
                day > length - third -> SeasonPhase.LATE
                else -> SeasonPhase.MID
            }
        }

    /** Days left in this season after today. */
    val daysLeft: Int get() = length - day

    /** Where in the year the middle of this day falls, in seasons: 0 is the first light of spring, 4 a year on. */
    val position: Double get() = (dayOfYear + 0.5) / length

    fun plus(days: Long): SeasonDate = SeasonDate(calendarDay + days, length)

    /** The first day of [season] in this date's year. */
    fun startOf(season: Season): SeasonDate = SeasonDate(calendarDay - dayOfYear + season.ordinal.toLong() * length, length)

    /** "Day 5 of Spring". */
    val title: Component get() = Component.translatable("season.guildmark.date", day, season.title)

    companion object {
        /** The day of each season its festival falls on: the middle one (5 of 9). */
        fun festivalDay(length: Int): Int = (length + 1) / 2
    }
}
