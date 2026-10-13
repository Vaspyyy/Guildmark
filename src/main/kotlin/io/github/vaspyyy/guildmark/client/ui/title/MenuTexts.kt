package io.github.vaspyyy.guildmark.client.ui.title

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.io.IOException
import java.time.MonthDay
import java.util.Random

/**
 * Guildmark's splash lines and loading tips, one to a line in assets/guildmark/texts/splashes/ and
 * texts/tips/, in the game's language when there's a file for it and in English otherwise. A splash line
 * written `12-24..12-26: line` or `10-31: line` only shows on those days, and on those days it always does.
 */
object MenuTexts {
    private val DATED = Regex("""^(\d\d)-(\d\d)(?:\.\.(\d\d)-(\d\d))?:\s*(.+)$""")
    private val random = Random()

    /** A splash for the title screen, or null if there are none. */
    fun splash(): String? {
        val today = MonthDay.now()
        val plain = ArrayList<String>()
        val dated = ArrayList<String>()
        for (line in read("splashes")) {
            val match = DATED.matchEntire(line)
            if (match == null) {
                plain.add(line)
                continue
            }
            val (m0, d0, m1, d1, text) = match.destructured
            val from = day(m0, d0) ?: continue
            val to = if (m1.isEmpty()) from else day(m1, d1) ?: continue
            if (!today.isBefore(from) && !today.isAfter(to)) dated.add(text)
        }
        val pool = dated.ifEmpty { plain }
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }

    /** The loading tips, shuffled. */
    fun tips(): List<String> = read("tips").shuffled(random)

    private fun day(month: String, day: String): MonthDay? = try {
        MonthDay.of(month.toInt(), day.toInt())
    } catch (e: RuntimeException) {
        null
    }

    private fun read(name: String): List<String> {
        val minecraft = Minecraft.getInstance()
        val language = minecraft.languageManager.selected
        for (code in listOf(language, "en_us").distinct()) {
            val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "texts/$name/$code.txt")
            val resource = minecraft.resourceManager.getResource(id).orElse(null) ?: continue
            try {
                return resource.openAsReader().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
            } catch (e: IOException) {
                Guildmark.LOGGER.warn("Couldn't read {}", id, e)
            }
        }
        return emptyList()
    }
}
