package io.github.vaspyyy.guildmark.festival

import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.SeasonDate
import net.minecraft.network.chat.Component

/**
 * The four grand festivals, one in the middle of each season. [color] is the festival's own (its name, its emblem's
 * glow); [palette] the colours its pennants, ribbons, lanterns and fireworks are dyed in.
 */
enum class FestivalKind(val id: String, val season: Season, val color: Int, val palette: IntArray) {
    /** Spring: the ribbon pole, the ribbon weave, the ribbon hunt. */
    RIBBONWAKE("ribbonwake", Season.SPRING, 0xF2A6C8, intArrayOf(0xF2A6C8, 0xF6E27A, 0x9BD36A, 0x8EC9F0, 0xC4A3E8, 0xF8F4EC)),
    /** Summer: the great fire and the sun wheel, the couples' reel, archery at the butts. */
    SUNHEARTH("sunhearth", Season.SUMMER, 0xF5B342, intArrayOf(0xF5B342, 0xD9432F, 0xF08A3A, 0xFFF1C9, 0x6FB7E8, 0xE8C26A)),
    /** Autumn: the harvest stack, the turning rings, the produce show. */
    SHEAFMOOT("sheafmoot", Season.AUTUMN, 0xE08B3C, intArrayOf(0xB5532A, 0xD9A23A, 0x8C2D3A, 0xC98B2E, 0x6F8F3A, 0xE8C26A)),
    /** Winter: the lantern tree, the lantern ring, the lantern riddles, and the lanterns let go at dusk. */
    LANTERNRISE("lanternrise", Season.WINTER, 0xF6DE96, intArrayOf(0xF4F1EA, 0xE8C26A, 0xB32D35, 0x2F6B45, 0xBFD9EE, 0xF6DE96));

    /** "the Ribbonwake". */
    val title: Component get() = Component.translatable("festival.guildmark.$id")

    /** The [i]th colour of the palette, going round. */
    fun color(i: Int): Int = palette[Math.floorMod(i, palette.size)]

    companion object {
        fun of(season: Season): FestivalKind = entries[season.ordinal]
        fun byId(id: String): FestivalKind? = entries.firstOrNull { it.id == id }
        fun byOrdinal(i: Int): FestivalKind = entries.getOrElse(i) { RIBBONWAKE }
    }
}

/**
 * When the festivals fall, from the date alone (the same on both sides): each on the middle day of its season.
 *
 * For the Living World: [on] says which festival (if any) is held on a date, [next] which comes next and in how many
 * days, [dateOf] the day of a festival in a year, and [upcoming] the next few.
 */
object FestivalCalendar {
    fun on(date: SeasonDate): FestivalKind? = if (date.day == SeasonDate.festivalDay(date.length)) FestivalKind.of(date.season) else null

    /** The date of [kind] in [date]'s year. */
    fun dateOf(kind: FestivalKind, date: SeasonDate): SeasonDate = date.startOf(kind.season).plus((SeasonDate.festivalDay(date.length) - 1).toLong())

    /** The next festival from [date] (today's counts, at 0 days), and how many days off it is. */
    fun next(date: SeasonDate): Pair<FestivalKind, Int> {
        for (ahead in 0..4 * date.length) {
            val day = date.plus(ahead.toLong())
            on(day)?.let { return it to ahead }
        }
        return FestivalKind.of(date.season) to 0
    }

    /** The next [count] festivals from [date], each with its date. */
    fun upcoming(date: SeasonDate, count: Int = 4): List<Pair<FestivalKind, SeasonDate>> {
        val out = ArrayList<Pair<FestivalKind, SeasonDate>>()
        var ahead = 0L
        while (out.size < count && ahead <= 8L * date.length) {
            val day = date.plus(ahead)
            on(day)?.let { out.add(it to day) }
            ahead++
        }
        return out
    }
}
