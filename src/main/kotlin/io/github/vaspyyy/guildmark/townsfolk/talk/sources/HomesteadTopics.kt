package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.homestead.HomesteadTiers
import io.github.vaspyyy.guildmark.homestead.Homesteads
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Familiarity
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource

/**
 * The player's homestead, said to them (section 1), if it's within reach of the speaker's town: a tier going up (the
 * most said), one standing, or a plot only staked, with where it is from town, pointed out. The carters, the keepers,
 * the guards and the innkeepers hear of such things from everyone; anyone else has to have heard of the player.
 * What's been shipped to it is M3's to add, with its shipping.
 */
object HomesteadTopics : TopicSource {
    override val id = "homestead"

    private val OCCASIONS = setOf(Occasion.OPENER, Occasion.ANSWER)

    /** How far from a town's heart a homestead is still talked of there (blocks). */
    private const val REACH = 768.0

    /** Who hears what's going up out in the country without knowing the player. */
    private val HEARS = setOf(TownRole.MERCHANT, TownRole.GUARD, TownRole.INNKEEPER)

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS || !Homesteads.enabled() || Say.child(ask.speaker)) return
        val player = ask.player ?: return
        val r = ask.resident ?: return
        val h = Homesteads.of(ask.level, player.uuid) ?: return
        val from = r.capital
        val dx = (h.post.x - from.x).toDouble()
        val dz = (h.post.z - from.z).toDouble()
        if (dx * dx + dz * dz > REACH * REACH) return
        val where = Say.where(ask.level, from, h.post)
        val (weight, detail) = when {
            h.building -> 0.5 to Speech.of("talk.detail.homestead.building", tier(h.build.tier), where)
            h.tier >= 1 -> 0.25 to Speech.of("talk.detail.homestead.standing", tier(h.tier), where)
            else -> 0.2 to Speech.of("talk.detail.homestead.staked", where)
        }
        val say = Say.line(ask, "deed.homestead", detail) ?: return
        val hears = ask.speaker.role in HEARS || ask.life?.carter == true
        out.add(Topic(
            "homestead.${h.id}.${h.tier}.${h.build.tier}", Kind.HOMESTEAD, weight, say,
            if (hears) Familiarity.STRANGER else Familiarity.HEARD_OF, 1, Activity.POINT, h.post, about = player.uuid,
        ))
    }

    /** The name of homestead tier [n] ("gui.guildmark.homestead.tier.cottage"). */
    private fun tier(n: Int): String = "gui.guildmark.homestead.tier.${HomesteadTiers.key(n)}"
}
