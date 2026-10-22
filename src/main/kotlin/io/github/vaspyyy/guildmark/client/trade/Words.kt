package io.github.vaspyyy.guildmark.client.trade

import net.minecraft.locale.Language
import java.util.Locale

/**
 * What the Tally says, in words and figures. Every word is a lang line (`gui.guildmark.tally.*`, and the goods' own `good.guildmark.*`), so it
 * reads in the player's language; the figures are set the way the book's other screens set them. [lookup] is where the lines come from: the game's
 * language, unless a preview has put its own.
 */
object Words {
    /** The line for a key, or null if there is none. */
    var lookup: (String) -> String? = { key ->
        val language = Language.getInstance()
        if (language.has(key)) language.getOrDefault(key) else null
    }

    /** The line [key] with [args] set into it (`%s`, `%d`), or the key itself if the language has no such line. */
    fun tr(key: String, vararg args: Any?): String {
        val raw = lookup(key) ?: return key
        if (args.isEmpty()) return raw
        return try {
            String.format(Locale.ROOT, raw, *args)
        } catch (e: java.util.IllegalFormatException) {
            raw
        }
    }

    /** The screen's own line: `gui.guildmark.tally.<key>`. */
    fun t(key: String, vararg args: Any?): String = tr("gui.guildmark.tally.$key", *args)

    /** The name of the good [id] ("Iron Ingot"), as the market screens write it. */
    fun good(id: String): String = tr("good.guildmark.$id")

    /** [units] of the good [id] as a count ("12 Iron Ingots"): the plural line for more than one. */
    fun goods(id: String, units: Int): String = "$units " + (if (units == 1) good(id) else tr("good.guildmark.$id.many"))

    /** A whole number with its thousands set apart ("1,010"). */
    fun num(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    /** Marks, as a figure and its unit ("86 Marks"). */
    fun marks(n: Int): String = t("marks", num(n))

    /** A price of one unit: two places under ten, one above ("0.45", "12.3"). */
    fun price(each: Float): String = if (each < 10f) String.format(Locale.ROOT, "%.2f", each) else String.format(Locale.ROOT, "%.1f", each)

    /** Hours of walking or waiting: one place ("3.2 h"). */
    fun hours(h: Float): String = t("hours", String.format(Locale.ROOT, "%.1f", h))

    /** How old a thing is that was [ticks] game ticks ago (a thousand to the hour): "now", "under 1 h", "5 h", "1 d 12 h", "3 d". */
    fun age(ticks: Double): String = if (ticks < 500.0) t("age.now") else span(ticks)

    /** How long is left to a thing that is due in [ticks] game ticks: "under 1 h", "5 h", "1 d 12 h", and "gone" at none. */
    fun left(ticks: Double): String = if (ticks <= 0.0) t("age.gone") else span(ticks)

    private fun span(ticks: Double): String {
        val hours = (ticks / 1000.0).toInt()
        if (hours < 1) return t("age.under")
        if (hours < 24) return t("age.h", hours)
        val days = hours / 24
        val rest = hours % 24
        return if (rest == 0) t("age.d", days) else t("age.dh", days, rest)
    }

    /** The name of a town, or what to call one that has no name yet. */
    fun town(name: String): String = name.ifEmpty { t("town.unnamed") }
}
