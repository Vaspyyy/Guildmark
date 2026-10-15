package io.github.vaspyyy.guildmark.progression

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/** A player's guild standing. Immutable: replace it with setData so it syncs to the client. */
data class GuildProgress(
    val level: Int = 1,
    /** Guild XP banked: spent at a Wardfire, a level at a time (see [Levelling]). */
    val xp: Int = 0,
    /** Adventurer rank index, see [AdventurerRank]. */
    val adventurerRank: Int = 0,
) {
    val rank: AdventurerRank get() = AdventurerRank.of(adventurerRank)

    /** The next rank, if the guild level for its trial has been reached. */
    fun trialReady(): AdventurerRank? = rank.next?.takeIf { level >= it.minLevel }

    fun xpToNext(): Int = xpToNext(level)
    fun isMaxLevel(): Boolean = level >= MAX_LEVEL

    /** Levels the banked XP would pay for, one after another. */
    fun levelsBanked(): Int {
        var at = level
        var left = xp
        while (at < MAX_LEVEL && left >= xpToNext(at)) {
            left -= xpToNext(at)
            at++
        }
        return at - level
    }

    companion object {
        const val MAX_LEVEL = 60

        fun xpToNext(level: Int): Int = 50 + 25 * level

        val CODEC: Codec<GuildProgress> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("level", 1).forGetter(GuildProgress::level),
                Codec.INT.optionalFieldOf("xp", 0).forGetter(GuildProgress::xp),
                Codec.INT.optionalFieldOf("adventurer_rank", 0).forGetter(GuildProgress::adventurerRank),
            ).apply(i, ::GuildProgress)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, GuildProgress> = ByteBufCodecs.fromCodec(CODEC)
    }
}
