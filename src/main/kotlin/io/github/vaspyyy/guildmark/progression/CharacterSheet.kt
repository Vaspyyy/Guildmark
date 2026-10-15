package io.github.vaspyyy.guildmark.progression

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * A player's stats and the skill tree nodes they've learned. Immutable: replace it with setData so it
 * syncs to the client. Levels and guild XP stay in [GuildProgress]; skill XP in [SkillState].
 */
data class CharacterSheet(
    /** Stat values by id; a stat not listed is at [Stat.BASE]. */
    val stats: Map<String, Int> = mapOf(),
    /** Learned tree nodes, in the order they were learned (the root is always learned and never listed). */
    val nodes: List<String> = listOf(),
    /** An Undying keystone's save has been spent since the last rest at a Wardfire. */
    val undyingSpent: Boolean = false,
) {
    fun stat(stat: Stat): Int = stats[stat.id] ?: Stat.BASE

    /** Points put into stats so far. */
    val raised: Int get() = Stat.entries.sumOf { stat(it) - Stat.BASE }

    fun has(node: String): Boolean = node == SkillTree.ROOT || node in nodes

    /** Stat raises owed from levels gained before stats existed (or set by command), placed for free. */
    fun owed(level: Int): Int = (level - 1 - raised).coerceAtLeast(0)

    fun withStat(stat: Stat, value: Int): CharacterSheet = copy(stats = stats + (stat.id to value))

    companion object {
        val CODEC: Codec<CharacterSheet> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("stats", mapOf()).forGetter(CharacterSheet::stats),
                Codec.STRING.listOf().optionalFieldOf("nodes", listOf()).forGetter(CharacterSheet::nodes),
                Codec.BOOL.optionalFieldOf("undying_spent", false).forGetter(CharacterSheet::undyingSpent),
            ).apply(i, ::CharacterSheet)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, CharacterSheet> = ByteBufCodecs.fromCodec(CODEC)
    }
}
