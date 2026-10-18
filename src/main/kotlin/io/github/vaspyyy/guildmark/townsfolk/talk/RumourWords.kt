package io.github.vaspyyy.guildmark.townsfolk.talk

/**
 * How a rumour is put into words (design-voices.md sections 4 and 5): the news family a teller says it with, and the
 * unvoiced detail each version of it shows. The news source tells rumours with it, and the crier and the board cry and
 * pin the talk of the town with it.
 *
 * As it stands no rumour has words: every call gives null.
 */
object RumourWords {
    /** The news family [rumour] is told with ("news.dragon"), or null if it has none. */
    fun family(rumour: Rumour): String? = null

    /** The detail of [rumour] as version [warp] of it tells it, in Speech format ("talk.detail.news.dragon|=Ransom|..."), or null. */
    fun detail(rumour: Rumour, warp: Int): String? = null
}
