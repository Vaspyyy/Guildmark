package io.github.vaspyyy.guildmark.quest

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
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
    /** Item to fetch or entity to hunt; unused for CLEAR. */
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
        QuestType.LAIR -> Component.translatable("quest.guildmark.lair.title", LairTheme.forRank(rank).title)
    }

    fun description(): Component = when (type) {
        QuestType.FETCH -> Component.translatable("quest.guildmark.fetch.desc", count, targetName())
        QuestType.HUNT -> Component.translatable("quest.guildmark.hunt.desc", count, targetName())
        QuestType.CLEAR -> Component.translatable("quest.guildmark.clear.desc", count, CLEAR_RADIUS)
        QuestType.DELIVER -> Component.translatable("quest.guildmark.deliver.desc")
        QuestType.ESCORT -> Component.translatable("quest.guildmark.escort.desc")
        QuestType.CHAMPION -> Component.translatable("quest.guildmark.champion.desc", targetName())
        QuestType.LAIR -> Component.translatable("quest.guildmark.lair.desc", LairTheme.forRank(rank).title, LairTheme.forRank(rank).bossTitle)
    }

    fun storyLine(): Component? = if (story.isEmpty()) null else Component.translatable(story)

    fun posterLine(): Component = Component.translatable("quest.guildmark.posted_by", poster)
    fun rewardLine(): Component = Component.translatable("quest.guildmark.reward", reward)
    fun deadlineLine(): Component = Component.translatable("quest.guildmark.deadline", deadlineDays)

    private fun targetName(): Component = when (type) {
        QuestType.FETCH -> ItemStack(BuiltInRegistries.ITEM.getValue(target)).hoverName
        QuestType.HUNT, QuestType.CHAMPION -> BuiltInRegistries.ENTITY_TYPE.getValue(target).description
        QuestType.CLEAR, QuestType.DELIVER, QuestType.ESCORT, QuestType.LAIR -> Component.empty()
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
            ).apply(i) { type, poster, target, count, reward, deadline, story, rank, trial ->
                QuestNote(type, poster, target, count, reward, deadline, story, rank, trial)
            }
        }
    }
}
