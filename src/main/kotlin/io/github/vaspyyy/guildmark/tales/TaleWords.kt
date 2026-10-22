package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The words every channel of an ending says with (living-world/stories/design.md 6.2 and 8.0), in one place so that the times'
 * reason, the rumour, the crier's grey line, the board's slip, the chronicle and the keepsake's lore always agree.
 *
 * The convention (the guide `tl_e.md` gives it to the twelve tales): an ending's sentence is the key
 * `fortune.guildmark.reason.tale.<template>.<word>` ("note.paid"), and it is said with three words, which a language line reads as
 * `%1$s`, `%2$s` and `%3$s`: the first two [names] of the tale, then the lead's name. A tale's names are its cast's names in the
 * order the template cast them (a figure with no name is left out), unless the template says otherwise by implementing [Names]
 * (a sentence about "the Hales" or "the oven" wants a word that is not a person). A sentence the language file lacks reads "A
 * tale.", never a key.
 */
object TaleWords {
    /**
     * A template may implement this, besides [Template], to choose the words its sentences, rumours and keepsakes are said with:
     * [words] answers the first two of them. Without it the cast's names stand in.
     */
    interface Names {
        fun words(t: Tale): List<String>
    }

    /** What a sentence reads when its line is missing, with the stop the fortunes' text puts after one. */
    private const val FALLBACK = "A tale."

    /** The names [t] is told with: the template's own words if it gives them, else the cast's names in cast order. */
    fun names(t: Tale): List<String> =
        ((Templates.find(t.template) as? Names)?.words(t) ?: t.cast.map { it.name }).filter { it.isNotBlank() }

    /** The word of [t]'s ending ("paid", "time"): the template's own (the probe's too, `Templates.find`), else the letter in lower case for a tale whose template this build does not know. */
    fun word(t: Tale, letter: String = t.ending): String =
        Templates.find(t.template)?.ending(letter)?.word ?: letter.lowercase()

    /** The key of the sentence of an ending, `<template>.<word>`, as the times' reason and the rumour's kind carry it. */
    fun id(t: Tale, letter: String = t.ending): String = "${t.template}.${word(t, letter)}"

    /** The size of [t]'s template, 1 to 3; 1 for one no template is built for. */
    fun size(t: Tale): Int = Templates.find(t.template)?.size?.coerceIn(1, 3) ?: 1

    /** Whether [t]'s template is one whose world's way hurts others (6.1). */
    fun hurts(t: Tale): Boolean = Templates.find(t.template)?.hurts ?: false

    /** The lead's name (the one who took the tale up), whether they are on or not; "" if there is none or the world no longer knows them. */
    fun lead(level: ServerLevel, t: Tale): String = t.lead?.let { leadName(level, it.player) }.orEmpty()

    fun leadName(level: ServerLevel, player: UUID): String = Say.name(level, player).orEmpty()

    /** The name of the capital whose heart is [capital], or "" when its piece cannot be found yet (a cached lookup). */
    fun town(level: ServerLevel, capital: Long): String =
        CaravanRoutes.capital(level, capital)?.let { VillageNames.of(it.boardAnchor) }.orEmpty()

    /** The words an ending is said with, in the order a line reads them: `<template>.<word>`, the first two names, the lead. */
    fun args(level: ServerLevel, t: Tale): List<String> {
        val names = names(t)
        return listOf(id(t), names.getOrElse(0) { "" }, names.getOrElse(1) { "" }, lead(level, t))
    }

    /** The sentence of the ending [args] (as [args] gives them) says. */
    fun sentence(args: List<String>): MutableComponent {
        val id = args.firstOrNull().orEmpty()
        return Component.translatableWithFallback(
            "fortune.guildmark.reason.tale.$id", FALLBACK,
            args.getOrElse(1) { "" }, args.getOrElse(2) { "" }, args.getOrElse(3) { "" },
        )
    }

    /** The sentence [t]'s ending says. */
    fun sentence(level: ServerLevel, t: Tale): MutableComponent = sentence(args(level, t))

    /** The sentence the chronicle's [line] says, said with the names and the lead it kept. */
    fun sentence(line: TaleData.Line): MutableComponent = sentence(
        listOf("${line.template}.${Templates.find(line.template)?.ending(line.ending)?.word ?: line.ending.lowercase()}") +
            listOf(line.names.getOrElse(0) { "" }, line.names.getOrElse(1) { "" }, line.lead),
    )
}
