package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.TalkLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Utterance
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.Say
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import java.util.WeakHashMap

/**
 * How a tale's people put a line into words in a talk (living-world/stories/design.md 4.5): a part built from a family of the
 * tale's voice, `tale.<template>.<beat>`, and its grey detail, `talk.detail.tale.<template>.<beat>` with the live words filled in.
 *
 * - **Recorded.** If the family has takes (`TalkLines.FAMILIES`), the part is voiced: a wording the listener has not heard lately,
 *   and its detail after it, unvoiced.
 * - **Not recorded.** If it has none, the part is *text alone* (`Talk.spoken` says the detail as the speech when the line is empty): the
 *   wording's typed text, `say.guildmark.talk.<family>_<n>`, which `make_talk_voices.py table` writes into the language file whether
 *   or not the takes are recorded, and the detail after it. So a tale can be played and read in full before one take exists.
 * - **Neither.** With no typed text either, the detail alone stands as a line of its own; with no detail, there is no part (null).
 *
 * A line the language file does not have is never shown as its key: the part is built without it, and the log says so once.
 */
internal object TaleLines {
    /**
     * The grey detail that says a sentence of the language as it is, `%s`. A detail can name only a key of its own prefix
     * (`say.guildmark.`) and no arguments of its own, so a sentence whose key lies elsewhere (a reason of the tale, an ending's
     * sentence, `fortune.guildmark.reason.tale.*`, with its names filled in) is put into words by the server and passed as the one
     * argument: [pass].
     */
    const val PASS = "talk.detail.tale.pass"

    /** A line every build has: if the language this side holds lacks it, it does not carry the mod's lines at all, and no key is judged missing. */
    private const val CANARY = "say.guildmark.talk.ask.yes_0"

    /** The most wordings a family has to look for, and the typed wordings each speaker keeps in mind so as not to say the same twice running. */
    private const val WORDINGS = 8
    private const val KEPT = 6

    /** The most missing lines warned of, so that a language lacking a family does not fill the log. */
    private const val WARNED = 64

    private val lately = WeakHashMap<Townsperson, ArrayDeque<String>>()
    private val warned = HashSet<String>()

    /** Whether the language this side holds carries the mod's lines (it does in a game and on a server with the mod's language loaded). */
    private fun loaded(): Boolean = Language.getInstance().has(CANARY)

    /** Whether the language has [key]; a missing one is warned of once. True for every key if the language does not carry the mod's lines at all. */
    fun has(key: String): Boolean {
        val language = Language.getInstance()
        if (!language.has(CANARY) || language.has(key)) return true
        if (warned.size < WARNED && warned.add(key)) Guildmark.LOGGER.warn("Tales talk: the language has no line {}", key)
        return false
    }

    /** Whether the language has [key], with no word in the log if it has not: for a choice's label, which has a plainer line to fall back to. */
    fun quiet(key: String): Boolean {
        val language = Language.getInstance()
        return !language.has(CANARY) || language.has(key)
    }

    /**
     * The word for a number of market days away, as an argument of a detail ("Due %s."): "today", "tomorrow", "in 3 days", up to
     * twelve (a tale lasts no longer). A language key of its own, so that a detail never says "in 1 days".
     */
    fun due(days: Long): String = "say.guildmark.talk.detail.tale.when.${days.coerceIn(0L, 12L)}"

    /** Whether the detail [facts] (a Speech line without its `say.guildmark.`) names a line the language has. */
    private fun named(facts: String): Boolean = has("say.guildmark." + facts.substringBefore('|'))

    /** A name as a Speech argument: the name itself, not a key to look up. */
    fun lit(name: String): String = "=" + clean(name)

    /** [text] with the two marks a Speech line is cut at taken out. */
    private fun clean(text: String): String = text.replace('|', ' ').replace('~', ' ')

    /**
     * The detail [key] (`talk.detail.tale...`, no `say.guildmark.`) with [args] (names through [lit], numbers as they are), or null if the
     * language has no such line. Null is the right answer to carry on with: [part] then says the line without its grey words.
     */
    fun facts(key: String, vararg args: Any): String? = if (has("say.guildmark.$key")) Speech.of(key, *args) else null

    /** The detail that says [sentence] as it is ([PASS]); null for an empty one. */
    fun pass(sentence: Component): String? {
        val text = clean(sentence.string).trim()
        return if (text.isEmpty()) null else Speech.of(PASS, "=$text")
    }

    /** The detail that says the reason [why] gave, in the tale's own sentence for it ([TaleKeys.why]); null if the language has no sentence for its kind. */
    fun pass(why: Why): String? = if (has(TaleKeys.why(why.kind))) pass(TaleKeys.text(why)) else null

    /**
     * The part for [ask]'s speaker saying [family] with the grey [detail] ("talk.detail.tale.note.ask|=Orrin Vale|24"): voiced when the
     * family has takes, as typed text when it has none, or [detail] alone; null if there is nothing to say.
     */
    fun part(ask: Ask, family: String, detail: String? = null): Utterance.Part? {
        val facts = detail?.takeIf { it.isNotEmpty() && named(it) }
        val take = Say.take(ask, family)
        if (take.isNotEmpty() && has("say.guildmark.$take")) return Utterance.Part(take, facts)
        val text = typed(ask.speaker, family)
        if (text != null) return Utterance.Part("", Speech.withDetail(text, facts))
        return facts?.let { Utterance.Part("", it) }
    }

    /** Whether [family] has anything of its own to say: takes recorded, or a typed wording the language has. */
    fun exists(family: String): Boolean = (TalkLines.FAMILIES[family]?.count ?: 0) > 0 || wordings("talk.$family").isNotEmpty()

    /** One typed wording of [family] (`talk.<family>_<n>`) that [speaker] has not said lately, if the language has any. */
    private fun typed(speaker: Townsperson, family: String): String? {
        val keys = wordings("talk.$family")
        if (keys.isEmpty()) return null
        val mine = lately.getOrPut(speaker) { ArrayDeque() }
        val fresh = keys.filter { it !in mine }.ifEmpty { keys }
        val key = fresh[speaker.random.nextInt(fresh.size)]
        mine.addLast(key)
        while (mine.size > KEPT) mine.removeFirst()
        return key
    }

    /** The typed wordings of [prefix] (`talk.tale.ask.later` for `talk.tale.ask.later_0`, `_1`...) the language has, in order from 0 with no gap. */
    private fun wordings(prefix: String): List<String> =
        if (loaded()) (0 until WORDINGS).map { "${prefix}_$it" }.takeWhile { Language.getInstance().has("say.guildmark.$it") } else listOf("${prefix}_0")

    /** Everything the talk remembers of what was said lately is let go (the server stopped). */
    fun forget() {
        lately.clear()
        warned.clear()
    }
}
