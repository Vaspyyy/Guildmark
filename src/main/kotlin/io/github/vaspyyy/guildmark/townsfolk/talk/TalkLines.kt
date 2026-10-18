package io.github.vaspyyy.guildmark.townsfolk.talk

import net.minecraft.util.RandomSource

/**
 * The townsfolk's talk lines that have takes recorded (art/audio/make_talk_voices.py writes this file from its table):
 * each family ("greet.morning"), how many wordings it has (keys talk.<family>_0 to _<count - 1>), each wording's length
 * in ticks (the longest timbre's), whether it has a .named text, and the timbres that say it.
 *
 * As it stands no family is recorded: every line is said as text with the blip.
 */
object TalkLines {
    class Family(val count: Int, val ticks: IntArray, val named: Boolean, val timbres: Set<String>)

    val FAMILIES: Map<String, Family> = mapOf()

    /** A wording of [family] not in [avoid] if there is one (else any), as its key; null if none is recorded. */
    fun pick(family: String, avoid: Set<String>, random: RandomSource): String? {
        val f = FAMILIES[family]?.takeIf { it.count > 0 } ?: return null
        val keys = (0 until f.count).map { "talk.${family}_$it" }
        val fresh = keys.filter { it !in avoid }
        val from = fresh.ifEmpty { keys }
        return from[random.nextInt(from.size)]
    }

    /** How long take [key] lasts, in ticks; 0 if it isn't recorded. */
    fun ticks(key: String): Int {
        val name = key.removePrefix("talk.")
        val cut = name.lastIndexOf('_')
        if (cut < 0) return 0
        val f = FAMILIES[name.substring(0, cut)] ?: return 0
        return name.substring(cut + 1).toIntOrNull()?.let { f.ticks.getOrNull(it) } ?: 0
    }
}
