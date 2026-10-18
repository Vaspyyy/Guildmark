package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.festival.FestivalKind
import io.github.vaspyyy.guildmark.season.SeasonCrops
import io.github.vaspyyy.guildmark.season.SeasonDate
import io.github.vaspyyy.guildmark.season.SeasonPhase
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * A new season (section 1): in its first two days the most said about it, less through the rest of its first third,
 * and nothing after. The detail is the season's festival, the day it falls on, if the festivals are on and it's still
 * to come; else the field crops that do best now.
 */
object SeasonTopics : TopicSource {
    override val id = "season"

    private val OCCASIONS = Say.TALK + Occasion.BARK

    /** The field crops the seasons pace, by the goods the market knows them as. */
    private val CROPS: Map<Block, Good> = mapOf(Blocks.WHEAT to Good.WHEAT, Blocks.CARROTS to Good.CARROT, Blocks.POTATOES to Good.POTATO, Blocks.BEETROOTS to Good.BEETROOT)

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS || !Seasons.enabled() || Say.child(ask.speaker)) return
        val date = Seasons.today(ask.level)
        if (date.phase != SeasonPhase.EARLY) return
        val season = date.season
        val say = Say.line(ask, "sky.${season.id}", detail(date)) ?: return
        out.add(Topic("season.${season.id}.${date.year}", Kind.SEASON, if (date.day <= 2) 0.6 else 0.2, say))
    }

    /** "Ribbonwake on day 5.", or "Carrots and Wheat do best now." */
    private fun detail(date: SeasonDate): String? {
        val festival = SeasonDate.festivalDay(date.length)
        if (Seasons.festivalsEnabled() && date.day < festival) {
            return Speech.of("talk.detail.season.festival", "festival.guildmark.${FestivalKind.of(date.season).id}", festival)
        }
        val crops = SeasonCrops.inSeason(date.season).mapNotNull { CROPS[it] }
        return when {
            crops.size >= 2 -> Speech.of("talk.detail.season.crops", crops[0].key + ".many", crops[1].key + ".many")
            crops.size == 1 -> Speech.of("talk.detail.season.crop", crops[0].key + ".many")
            else -> null
        }
    }
}
