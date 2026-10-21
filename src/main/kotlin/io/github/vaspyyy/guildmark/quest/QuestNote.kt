package io.github.vaspyyy.guildmark.quest

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.faction.Trials
import io.github.vaspyyy.guildmark.lair.LairTheme
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/** One quest as written on a board note or a taken contract. */
data class QuestNote(
    val type: QuestType,
    val poster: String,
    /** Item to fetch, entity to hunt or slay, or (salvage) the kind of island; unused for CLEAR and CHART. */
    val target: Identifier,
    val count: Int,
    val reward: Int,
    val deadlineDays: Int,
    /** Translation key of the poster's flavor line; empty for none. */
    val story: String = "",
    /** Adventurer rank index the job is rated at (see AdventurerRank). */
    val rank: Int = 0,
    /** A rank trial: beating it promotes the adventurer to [rank]. */
    val trial: Boolean = false,
    /** A named character's story chapter, as "<character>:<step>"; empty for ordinary jobs. */
    val chain: String = "",
    /** Wanted: whose head it is, as a label (see Labels); empty for a note naming no one. */
    val subject: String = "",
    /** Wanted: the band the note was posted on (see outlaw/OutlawCamps), or -1. */
    val site: Int = -1,
    /**
     * Fetch: whether the note was posted under the carrier's cap (see MarketFetch.cap): a fetch for the markets' goods
     * away from a capital's own board is held, when handed in, to what the nearest market asks for them and a carrier's
     * quarter. A note saved from before that rule has no such mark (it reads back as false) and pays what it states,
     * as it was posted to; every note made now is held to it.
     */
    val capped: Boolean = true,
) {
    val rankLetter: String get() = AdventurerRank.of(rank).letter
    fun rankLine(): Component = Component.translatable(if (trial) "quest.guildmark.trial_rank" else "quest.guildmark.rank", rankLetter)

    fun title(): Component = when (type) {
        QuestType.FETCH -> Component.translatable("quest.guildmark.fetch.title", count, targetName())
        QuestType.HUNT -> Component.translatable("quest.guildmark.hunt.title", count, targetName())
        QuestType.CLEAR -> Component.translatable("quest.guildmark.clear.title")
        QuestType.DELIVER -> Component.translatable("quest.guildmark.deliver.title")
        QuestType.ESCORT -> Component.translatable("quest.guildmark.escort.title")
        QuestType.CHAMPION -> Component.translatable("quest.guildmark.champion.title", targetName())
        QuestType.LAIR -> Component.translatable("quest.guildmark.lair.title", LairTheme.of(target, rank).title)
        QuestType.TRIAL -> Component.translatable("quest.guildmark.trial.${target.path}")
        QuestType.CHART -> Component.translatable("quest.guildmark.chart.title", count)
        QuestType.SALVAGE -> Component.translatable("quest.guildmark.salvage.${target.path}.title")
        QuestType.SLAY -> Component.translatable("quest.guildmark.slay.title", targetName())
        QuestType.BOUNTY -> if (subject.isEmpty()) Component.translatable("quest.guildmark.bounty.title_any") else Component.translatable("quest.guildmark.bounty.title", Labels.text(subject))
        QuestType.RESCUE -> if (subject.isEmpty()) Component.translatable("quest.guildmark.rescue.title_any") else Component.translatable("quest.guildmark.rescue.title", subject)
        QuestType.CARAVAN -> Component.translatable("quest.guildmark.caravan.title")
    }

    fun description(): Component = when (type) {
        QuestType.FETCH -> Component.translatable("quest.guildmark.fetch.desc", count, targetName())
        QuestType.HUNT -> Component.translatable("quest.guildmark.hunt.desc", count, targetName())
        QuestType.CLEAR -> Component.translatable("quest.guildmark.clear.desc", count, CLEAR_RADIUS)
        QuestType.DELIVER -> Component.translatable("quest.guildmark.deliver.desc")
        QuestType.ESCORT -> Component.translatable("quest.guildmark.escort.desc")
        QuestType.CHAMPION -> Component.translatable("quest.guildmark.champion.desc", targetName())
        QuestType.LAIR -> LairTheme.of(target, rank).let { Component.translatable("quest.guildmark.lair.desc", it.title, it.bossTitle) }
        QuestType.TRIAL -> Trials.describe(this)
        QuestType.CHART -> Component.translatable("quest.guildmark.chart.desc", count)
        QuestType.SALVAGE -> Component.translatable("quest.guildmark.salvage.${target.path}.desc")
        QuestType.SLAY -> Component.translatable("quest.guildmark.slay.${target.path}.desc")
        QuestType.BOUNTY -> Component.translatable("quest.guildmark.bounty.desc")
        QuestType.RESCUE -> Component.translatable("quest.guildmark.rescue.desc")
        QuestType.CARAVAN -> Component.translatable("quest.guildmark.caravan.desc", if (subject.isEmpty()) Component.translatable("road.guildmark.way.any") else Labels.text(subject))
    }

    /** The flavour line; situational notes carry their arguments after the key, as "key|arg|arg" (see Labels). */
    fun storyLine(): Component? {
        if (story.isEmpty()) return null
        return Labels.text("#$story")
    }

    fun posterLine(): Component = Component.translatable("quest.guildmark.posted_by", Labels.text(poster))
    fun rewardLine(): Component = Component.translatable("quest.guildmark.reward", reward)
    fun deadlineLine(): Component = Component.translatable("quest.guildmark.deadline", deadlineDays)

    private fun targetName(): Component = when (type) {
        QuestType.FETCH -> ItemStack(BuiltInRegistries.ITEM.getValue(target)).hoverName
        QuestType.HUNT, QuestType.CHAMPION, QuestType.SLAY -> BuiltInRegistries.ENTITY_TYPE.getValue(target).description
        QuestType.CLEAR, QuestType.DELIVER, QuestType.ESCORT, QuestType.LAIR, QuestType.TRIAL, QuestType.CHART, QuestType.SALVAGE, QuestType.BOUNTY, QuestType.RESCUE, QuestType.CARAVAN -> Component.empty()
    }

    companion object {
        const val CLEAR_RADIUS = 32

        val CODEC: Codec<QuestNote> = RecordCodecBuilder.create { i ->
            i.group(
                QuestType.CODEC.fieldOf("type").forGetter(QuestNote::type),
                Codec.STRING.fieldOf("poster").forGetter(QuestNote::poster),
                Identifier.CODEC.fieldOf("target").forGetter(QuestNote::target),
                Codec.INT.fieldOf("count").forGetter(QuestNote::count),
                Codec.INT.fieldOf("reward").forGetter(QuestNote::reward),
                Codec.INT.fieldOf("deadline_days").forGetter(QuestNote::deadlineDays),
                Codec.STRING.optionalFieldOf("story", "").forGetter(QuestNote::story),
                Codec.INT.optionalFieldOf("rank", 0).forGetter(QuestNote::rank),
                Codec.BOOL.optionalFieldOf("trial", false).forGetter(QuestNote::trial),
                Codec.STRING.optionalFieldOf("chain", "").forGetter(QuestNote::chain),
                Codec.STRING.optionalFieldOf("subject", "").forGetter(QuestNote::subject),
                Codec.INT.optionalFieldOf("site", -1).forGetter(QuestNote::site),
                // Missing means a note from before the carrier's cap, though a note made now is capped unless said otherwise
                Codec.BOOL.optionalFieldOf("capped", false).forGetter(QuestNote::capped),
            ).apply(i) { type, poster, target, count, reward, deadline, story, rank, trial, chain, subject, site, capped ->
                QuestNote(type, poster, target, count, reward, deadline, story, rank, trial, chain, subject, site, capped)
            }
        }
    }
}
