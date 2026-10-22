package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.season.Seasons
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel

/**
 * What a memorial says when it is read (living-world/stories/design.md 6.4): one chat line in the tale's own voice, "Here the town laid
 * Tam Hale to rest, in the Ribbonwake of the second year." The sentence is chosen by the look of the stone and the tale it is for (a cairn
 * for the Fourth Wagon marks a road, one for the brother marks the old mine), the festival and the year are those of the market day it was
 * laid (the season's festival names the season, as the town does), and the lead who saw to it, if there is one, is named after. A stone
 * with no name cut in it says so; one with no day says nothing of when. Every word is a language key (`message.guildmark.tale.memorial.*`),
 * each named below in full, so that the language check (which reads whole strings) sees every one.
 */
internal object MemorialWords {
    private const val SAY_STONE = "message.guildmark.tale.memorial.stone"
    private const val SAY_CAIRN = "message.guildmark.tale.memorial.cairn"
    private const val SAY_WAGON = "message.guildmark.tale.memorial.cairn.wagon"
    private const val SAY_BROTHER = "message.guildmark.tale.memorial.cairn.brother"
    private const val WHEN = "message.guildmark.tale.memorial.when"
    private const val END = "message.guildmark.tale.memorial.end"
    private const val BLANK = "message.guildmark.tale.memorial.blank"
    private const val LEAD = "message.guildmark.tale.memorial.lead"
    private const val AND = "message.guildmark.tale.memorial.and"
    private const val OTHERS = "message.guildmark.tale.memorial.others"
    private const val CAUSE = "message.guildmark.tale.memorial.cause"
    private const val YEAR_PATTERN = "message.guildmark.tale.memorial.year.pattern"

    /** The years that have a word of their own: the first to the twentieth. */
    private val YEARS: List<String> = listOf(
        "message.guildmark.tale.memorial.year.1", "message.guildmark.tale.memorial.year.2", "message.guildmark.tale.memorial.year.3",
        "message.guildmark.tale.memorial.year.4", "message.guildmark.tale.memorial.year.5", "message.guildmark.tale.memorial.year.6",
        "message.guildmark.tale.memorial.year.7", "message.guildmark.tale.memorial.year.8", "message.guildmark.tale.memorial.year.9",
        "message.guildmark.tale.memorial.year.10", "message.guildmark.tale.memorial.year.11", "message.guildmark.tale.memorial.year.12",
        "message.guildmark.tale.memorial.year.13", "message.guildmark.tale.memorial.year.14", "message.guildmark.tale.memorial.year.15",
        "message.guildmark.tale.memorial.year.16", "message.guildmark.tale.memorial.year.17", "message.guildmark.tale.memorial.year.18",
        "message.guildmark.tale.memorial.year.19", "message.guildmark.tale.memorial.year.20",
    )

    /** The endings of a year's number, past the twentieth: 21st, 22nd, 23rd, 24th. */
    private val ENDINGS: Map<String, String> = mapOf(
        "st" to "message.guildmark.tale.memorial.year.st", "nd" to "message.guildmark.tale.memorial.year.nd",
        "rd" to "message.guildmark.tale.memorial.year.rd", "th" to "message.guildmark.tale.memorial.year.th",
    )

    /** Every key the inscription can be made of, so that `tale check` and the language check see them whatever the tale. */
    val KEYS: List<String> = listOf(SAY_STONE, SAY_CAIRN, SAY_WAGON, SAY_BROTHER, WHEN, END, BLANK, LEAD, AND, OTHERS, CAUSE, YEAR_PATTERN) + YEARS + ENDINGS.values

    /** The sentence's key for a memorial of [kind] for the tale [template]: the stone's is one for every tale, the cairn's is the road's or the mine's where it is. */
    fun sentence(kind: MemorialKind, template: String): String = when {
        kind == MemorialKind.STONE -> SAY_STONE
        template == "wagon" -> SAY_WAGON
        template == "brother" -> SAY_BROTHER
        else -> SAY_CAIRN
    }

    /** The words on the stone: [names] cut in a [kind] for [template], laid on market [day] (null if it is not known), seen to by [lead]. */
    fun inscription(level: ServerLevel, kind: MemorialKind, names: List<MemorialName>, template: String, day: Long?, lead: String): Component {
        val line: MutableComponent = if (names.isEmpty()) {
            Component.translatable(BLANK)
        } else {
            val said = Component.translatable(sentence(kind, template), who(names))
            if (day == null || day <= TaleData.NEVER) said.append(Component.translatable(END)) else said.append(Component.translatable(WHEN, festival(level, day), year(level, day)))
        }
        if (lead.isNotEmpty()) line.append(" ").append(Component.translatable(LEAD, Component.literal(lead).withStyle(ChatFormatting.WHITE)))
        return line.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
    }

    /** The festival of the season market day [day] fell in. */
    private fun festival(level: ServerLevel, day: Long): Component =
        Component.empty().append(FestivalKind.of(Seasons.dateOn(level.server, day).season).title).withStyle(ChatFormatting.WHITE)

    /** The year market day [day] fell in, as a word (the first, the second, ... the twentieth), else a number with its ending. */
    private fun year(level: ServerLevel, day: Long): Component {
        val n = Seasons.dateOn(level.server, day).year
        return if (n in 1..YEARS.size) Component.translatable(YEARS[n - 1]) else Component.translatable(YEAR_PATTERN, n, Component.translatable(ENDINGS.getValue(suffix(n))))
    }

    /** The English ending of the number [n] (st, nd, rd, th). */
    internal fun suffix(n: Int): String = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    /** The names, as a list that reads aloud: one; "A and B"; "A, B and C"; more than three, "A, B, C and 4 others". A name's cause, if it has one, follows it in brackets. */
    fun who(names: List<MemorialName>): Component {
        val parts = names.map { n ->
            val name = Component.literal(n.name).withStyle(ChatFormatting.WHITE)
            if (n.cause.isEmpty()) name else Component.translatable(CAUSE, name, n.cause)
        }
        if (parts.size == 1) return parts[0]
        if (parts.size > SHOWN) return Component.translatable(OTHERS, commas(parts.take(SHOWN)), parts.size - SHOWN)
        return Component.translatable(AND, commas(parts.dropLast(1)), parts.last())
    }

    private fun commas(parts: List<Component>): Component {
        val out = Component.empty()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) out.append(", ")
            out.append(p)
        }
        return out
    }

    /** The names on the atlas's pin and in a rumour: up to three, plain, with commas (no word of a language in it), and dots for the rest. */
    fun label(names: List<String>): String = names.take(SHOWN).joinToString(", ") + if (names.size > SHOWN) "..." else ""

    /** The names a line shows before it says how many more. */
    private const val SHOWN = 3
}
