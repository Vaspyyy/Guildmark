package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource

/**
 * What the town says of the kitchen (Hearthcraft, living-world/cook/design.md 8.1): what was eaten and who cooked it,
 * the slate and the request, the seasons' and the palates' tastes, who was relished and who burnt a pot. Part J's: it
 * gives the seven topic families and the choice "About the kitchen...". Registered with the other sources in
 * [TalkSources.ALL]; until Part J lands it has nothing to say.
 */
object KitchenTopics : TopicSource {
    override val id = "kitchen"

    override fun offer(ask: Ask, out: MutableList<Topic>) {}
}
