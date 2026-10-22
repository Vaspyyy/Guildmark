package io.github.vaspyyy.guildmark.tales

import com.google.gson.JsonParser
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.io.InputStreamReader

/**
 * `/guildmark tale check` (living-world/stories/design.md 14.5): for the probe and each template in the draw, one line, green or
 * red. The id is unique and one of the twelve; the size is 1 to 3 and each ending pays the figure of 7.1; every step lasts at most
 * five days and the longest route nine; the title and every step's status are in the language file and sixteen letters or fewer;
 * each ending has a sentence and a banner line; the numbers and strings are inside their caps; and `needs()` does not throw. A part
 * that adds a template runs it before handing over. It reads the language file the game itself ships, so a line a fragment has not
 * yet been merged into is found here and not by a player.
 */
internal object TaleCheck {
    private const val LANG = "/assets/guildmark/lang/en_us.json"

    /** The most letters a tracker title or status may have (5.1). */
    private const val LETTERS = 16

    /** A word the game fills in (`%s`, `%1$s`, `%d`) is not counted among a line's letters. */
    private val FILLED = Regex("%(\\d+\\$)?[sd]")

    fun run(source: CommandSourceStack): Int {
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val lang = lang()
        TaleCommand.say(source, TaleCommand.header("Tale check"))
        if (lang.isEmpty()) TaleCommand.say(source, coloured("The language file could not be read, so no line can be checked.", ChatFormatting.RED))
        val ids = HashSet<String>()
        var red = 0
        val templates = listOf(Templates.PROBE) + Templates.ALL
        for (t in templates) {
            val problems = problems(t, lang, ids)
            if (problems.isEmpty()) {
                TaleCommand.say(source, line(true, safe { t.id }, summary(t)))
            } else {
                red++
                TaleCommand.say(source, line(false, safe { t.id }, problems.joinToString("; ")))
            }
        }
        val engine = (TaleKeys.ENGINE_WHY + TaleKeys.ENGINE_LINES).filter { it !in lang }
        if (engine.isEmpty()) {
            TaleCommand.say(source, line(true, "engine", "its ${TaleKeys.ENGINE_WHY.size + TaleKeys.ENGINE_LINES.size} lines are in the language file"))
        } else {
            red++
            TaleCommand.say(source, line(false, "engine", "missing " + engine.joinToString(", ")))
        }
        val built = Templates.ALL.size
        TaleCommand.say(
            source,
            TaleCommand.grey("$built of the ${Templates.IDS.size} tales are built, and the probe. " + if (red == 0) "All green." else "$red red."),
        )
        return 1
    }

    /** The first line's worth of what a template is, when it is well. */
    private fun summary(t: Template): String {
        val pay = Ending.LETTERS.joinToString("/") { TaleLimits.points(t.size, it).toString() }
        val last = t.steps.maxOf { it.by }
        return "size ${t.size}, pay $pay, ${t.steps.size} steps over $last days"
    }

    /** What is wrong with [t], in words; empty if it is well. */
    private fun problems(t: Template, lang: Map<String, String>, ids: MutableSet<String>): List<String> {
        val out = ArrayList<String>()
        try {
            val id = t.id
            if (id.isBlank()) out += "the id is blank" else if (!ids.add(id)) out += "the id $id is used twice"
            if (t !== Templates.PROBE && id !in Templates.IDS) out += "the id is none of the twelve"
            if (t.size !in 1..3) out += "size ${t.size} is not 1 to 3"
            val letters = t.endings.map { it.letter }
            if (letters.sorted() != Ending.LETTERS.sorted()) out += "its endings are ${letters.joinToString("")}, not A, B, C and W once each"
            val words = t.endings.map { it.word }
            if (words.any { it.isBlank() } || words.toSet().size != words.size) out += "its endings' words are blank or repeated"
            if (t.size in 1..3) for (e in t.endings) if (TaleLimits.points(t.size, e.letter) <= 0) out += "ending ${e.letter} pays nothing"
            TaleRules.timing(t.steps.map { it.by })?.let { out += it }
            if (t.numbers.size > TaleLimits.NUMBERS) out += "${t.numbers.size} numbers, at most ${TaleLimits.NUMBERS}"
            if (t.strings.size > TaleLimits.STRINGS) out += "${t.strings.size} strings, at most ${TaleLimits.STRINGS}"
            if (id.isNotBlank()) {
                key(lang, TaleKeys.title(id), true, out)
                for (i in t.steps.indices) key(lang, TaleKeys.status(id, i), true, out)
                for (e in t.endings) {
                    key(lang, TaleKeys.sentence(id, e.word), false, out)
                    key(lang, TaleKeys.ended(id, e.word), false, out)
                }
            }
            try {
                t.needs()
            } catch (e: Exception) {
                out += "needs() throws ${e.javaClass.simpleName}"
            }
        } catch (e: Exception) {
            out += "could not be read: ${e.javaClass.simpleName}"
        }
        return out
    }

    /** Notes in [out] that [key] is missing from [lang], or (for a [short] one) is more than sixteen letters. */
    private fun key(lang: Map<String, String>, key: String, short: Boolean, out: MutableList<String>) {
        val text = lang[key]
        if (text == null) {
            out += "missing $key"
            return
        }
        if (short) {
            val n = text.replace(FILLED, "").length
            if (n > LETTERS) out += "$key is $n letters, at most $LETTERS"
        }
    }

    /** The mod's own English language file, as the game ships it; empty if it cannot be read. */
    private fun lang(): Map<String, String> =
        try {
            val stream = TaleCheck::class.java.getResourceAsStream(LANG)
            if (stream == null) {
                mapOf()
            } else {
                stream.use { s ->
                    val root = JsonParser.parseReader(InputStreamReader(s, Charsets.UTF_8)).asJsonObject
                    val out = HashMap<String, String>()
                    for ((k, v) in root.entrySet()) if (v.isJsonPrimitive) out[k] = v.asString
                    out
                }
            }
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("The language file could not be read for tale check", e)
            mapOf()
        }

    private fun safe(id: () -> String): String = try {
        id()
    } catch (e: Exception) {
        "?"
    }

    private fun line(green: Boolean, id: String, text: String): MutableComponent =
        TaleCommand.line()
            .append(coloured(if (green) "ok   " else "FAIL ", if (green) ChatFormatting.GREEN else ChatFormatting.RED))
            .append(TaleCommand.label("$id: "))
            .append(TaleCommand.plain(text))

    private fun coloured(s: String, colour: ChatFormatting): MutableComponent = Component.literal(s).withStyle(colour)
}
