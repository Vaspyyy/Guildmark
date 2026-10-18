package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource

/**
 * What has passed between the speaker and the player (section 1), from what the speaker remembers of them: a slight
 * still felt ("I haven't forgotten, you know.", and what it was), a good turn ("What you sold me went in an hour.",
 * from a keeper the player sold what the town was short of), and being a regular at their stall or their door.
 */
object DealingTopics : TopicSource {
    override val id = "dealings"

    private val OCCASIONS = setOf(Occasion.OPENER, Occasion.ANSWER, Occasion.BARK)

    /** A slight felt at least this much (faded) is brought up; a favour at least [FAVOUR] is thanked for. */
    private const val SLIGHT = 0.5
    private const val FAVOUR = 0.25

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS || !Say.memory() || Say.child(ask.speaker)) return
        ask.player ?: return
        val k = ask.knows ?: return
        if (k.met < 0) return
        val bark = ask.occasion == Occasion.BARK

        // A slight still felt, and the worst of it
        val worst = k.worst
        if (worst != null && k.slights >= SLIGHT) {
            add(ask, out, "deal.grudge", "dealings.grudge.${worst.name.lowercase()}", 0.9, -1, "talk.detail.deal.slight.${worst.name.lowercase()}", Activity.ARMS_FOLDED)
        }
        // A good turn: a keeper's for what was sold them, anyone else's thanks
        if (k.favour >= FAVOUR) {
            val keeper = Shop.kept(ask.speaker) != null && k.trades > 0
            add(ask, out, if (keeper) "deal.sold" else "deal.thanks", "dealings.favour.${(k.favour * 2).toInt()}", 0.8, 1, null)
        }
        if (bark) return
        // A regular: at their stall, or met on many days
        if (k.trades >= 3 || k.days >= 3) {
            val detail = if (k.spent > 0) Speech.of("talk.detail.deal.spent", k.spent) else Speech.of("talk.detail.deal.days", k.days)
            add(ask, out, "deal.regular", "dealings.regular.${k.trades}.${k.days}", 0.5, 1, detail)
        }
    }

    private fun add(ask: Ask, out: MutableList<Topic>, family: String, id: String, weight: Double, tone: Int, detail: String?, gesture: Activity? = null) {
        val say = Say.line(ask, family, detail) ?: return
        out.add(Topic(id, Kind.DEALINGS, weight, say, Familiarity.MET, tone, gesture, about = ask.player?.uuid))
    }
}
