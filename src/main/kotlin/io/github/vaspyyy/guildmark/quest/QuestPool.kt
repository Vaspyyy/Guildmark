package io.github.vaspyyy.guildmark.quest

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier

/**
 * A datapack file at `data/<namespace>/quest_pool/<name>.json`: the jobs a set of professions
 * can post. A pool with no professions is open to every villager.
 */
data class QuestPool(
    val professions: List<Identifier>,
    val entries: List<Entry>,
) {
    /** One kind of job, e.g. "fetch 16-32 wheat". */
    data class Entry(
        val type: QuestType,
        /** Item to fetch or entity to hunt; ignored for CLEAR. */
        val target: Identifier,
        val min: Int,
        val max: Int,
        /** Guild Marks per unit, on top of a small base pay and before the distance bonus. */
        val rewardPer: Float,
        val weight: Int,
        /** Only offered this far from spawn or further (1 = near spawn, 3 = the frontier). */
        val minTier: Int,
        /** Translation keys; one is picked as the note's flavor line. */
        val stories: List<String>,
    ) {
        companion object {
            val CODEC: Codec<Entry> = RecordCodecBuilder.create { i ->
                i.group(
                    QuestType.CODEC.fieldOf("type").forGetter(Entry::type),
                    Identifier.CODEC.optionalFieldOf("target", Identifier.withDefaultNamespace("zombie")).forGetter(Entry::target),
                    Codec.intRange(1, 1024).fieldOf("min").forGetter(Entry::min),
                    Codec.intRange(1, 1024).fieldOf("max").forGetter(Entry::max),
                    Codec.floatRange(0.0f, 64.0f).fieldOf("reward_per").forGetter(Entry::rewardPer),
                    Codec.intRange(1, 1000).optionalFieldOf("weight", 10).forGetter(Entry::weight),
                    Codec.intRange(1, QuestGenerator.MAX_TIER).optionalFieldOf("min_tier", 1).forGetter(Entry::minTier),
                    Codec.STRING.listOf().optionalFieldOf("stories", listOf()).forGetter(Entry::stories),
                ).apply(i) { type, target, min, max, rewardPer, weight, minTier, stories ->
                    Entry(type, target, min, maxOf(min, max), rewardPer, weight, minTier, stories)
                }
            }
        }
    }

    companion object {
        val CODEC: Codec<QuestPool> = RecordCodecBuilder.create { i ->
            i.group(
                Identifier.CODEC.listOf().optionalFieldOf("professions", listOf()).forGetter(QuestPool::professions),
                Entry.CODEC.listOf().fieldOf("entries").forGetter(QuestPool::entries),
            ).apply(i, ::QuestPool)
        }
    }
}

/** Pools from the last datapack reload. */
object QuestPools {
    @Volatile
    var pools: List<QuestPool> = listOf()
}
