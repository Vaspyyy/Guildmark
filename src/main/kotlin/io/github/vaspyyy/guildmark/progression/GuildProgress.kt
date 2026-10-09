package io.github.vaspyyy.guildmark.progression

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/** A player's guild standing. Immutable: replace it with setData so it syncs to the client. */
data class GuildProgress(
    val level: Int = 1,
    /** XP earned toward the next level. */
    val xp: Int = 0,
    val points: Int = 0,
    val perks: Map<String, Int> = mapOf(),
) {
    fun rank(perk: Perk): Int = perks[perk.id] ?: 0
    fun xpToNext(): Int = xpToNext(level)
    fun isMaxLevel(): Boolean = level >= MAX_LEVEL

    companion object {
        const val MAX_LEVEL = 30

        fun xpToNext(level: Int): Int = 50 + 25 * level

        val CODEC: Codec<GuildProgress> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("level", 1).forGetter(GuildProgress::level),
                Codec.INT.optionalFieldOf("xp", 0).forGetter(GuildProgress::xp),
                Codec.INT.optionalFieldOf("points", 0).forGetter(GuildProgress::points),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("perks", mapOf()).forGetter(GuildProgress::perks),
            ).apply(i, ::GuildProgress)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, GuildProgress> = ByteBufCodecs.fromCodec(CODEC)
    }
}
