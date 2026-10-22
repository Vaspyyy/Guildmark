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
 * with no name cut in it says so; one with no day says nothing of when. Every word is a language key (`message.guildmark.tale.memorial.`).
 */
internal object MemorialWords {
    private const val KEY = "message.guildmark.tale.memorial."

    /** The sentence's key for a memorial of [kind] for the tale [template]: the stone's is one for every tale, the cairn's is the road's or the mine's where it is. */
    fun sentence(kind: MemorialKind, template: String): String = KEY + when {
        kind == MemorialKind.STONE -> "stone"
        template == "wagon" -> "cairn.wagon"
        template == "brother" -> "cairn.brother"
        else -> "cairn"
    }

    /** Every key the inscription can be made of, so that `tale check` and the language check see them whatever the tale. */
    val KEYS: List<String> = listOf(
        "stone", "cairn", "cairn.wagon", "cairn.brother", "when", "end", "blank", "lead", "and", "others", "cause",
    ).map { KEY + it } + (1..ORDINALS).map { KEY + "year.$it" } + listOf("pattern", "st", "nd", "rd", "th").map { KEY + "year.$it" }

    /** The words on the stone: [names] cut in a [kind] for [template], laid on market [day] (null if it is not known), seen to by [lead]. */
    fun inscription(level: ServerLevel, kind: MemorialKind, names: List<MemorialName>, template: String, day: Long?, lead: String): Component {
        val line: MutableComponent = if (names.isEmpty()) {
            Component.translatable(KEY + "blank")
        } else {
            val said = Component.translatable(sentence(kind, template), who(names))
            if (day == null || day <= TaleData.NEVER) said.append(Component.translatable(KEY + "end")) else said.append(Component.translatable(KEY + "when", festival(level, day), year(level, day)))
        }
        if (lead.isNotEmpty()) line.append(" ").append(Component.translatable(KEY + "lead", Component.literal(lead).withStyle(ChatFormatting.WHITE)))
        return line.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
    }

    /** The festival of the season market day [day] fell in. */
    private fun festival(level: ServerLevel, day: Long): Component =
        Component.empty().append(FestivalKind.of(Seasons.dateOn(level.server, day).season).title).withStyle(ChatFormatting.WHITE)

    /** The year market day [day] fell in, as a word (the first, the second, ... the twentieth), else a number with its ending. */
    private fun year(level: ServerLevel, day: Long): Component {
        val n = Seasons.dateOn(level.server, day).year
        return if (n in 1..ORDINALS) Component.translatable(KEY + "year.$n") else Component.translatable(KEY + "year.pattern", n, Component.translatable(KEY + "year." + suffix(n)))
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
            if (n.cause.isEmpty()) name else Component.translatable(KEY + "cause", name, n.cause)
        }
        if (parts.size == 1) return parts[0]
        if (parts.size > SHOWN) return Component.translatable(KEY + "others", commas(parts.take(SHOWN)), parts.size - SHOWN)
        return Component.translatable(KEY + "and", commas(parts.dropLast(1)), parts.last())
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

    /** The years that have a word of their own. */
    private const val ORDINALS = 20

    /** The names a line shows before it says how many more. */
    private const val SHOWN = 3
}
