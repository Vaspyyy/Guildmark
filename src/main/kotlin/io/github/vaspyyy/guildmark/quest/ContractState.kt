package io.github.vaspyyy.guildmark.quest

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.Optional
import java.util.UUID

/** Per-contract progress: kills counted so far, when it expires, and the board it was taken from. */
data class ContractState(
    val progress: Int,
    /** Game time after which the contract can no longer be completed. */
    val deadline: Long,
    val boardPos: BlockPos,
    val dimension: ResourceKey<Level>,
    /** Delivery and escort: the village to reach. Champion: where it was last seen. */
    val destination: BlockPos? = null,
    /** Escort: the traveller. Champion: the champion. */
    val bound: UUID? = null,
    /** The traveller's or champion's name. */
    val label: String = "",
    /** Delivery and escort: the road to the destination (see RoadNetwork), or -1 if there isn't one. */
    val road: Int = -1,
) {
    fun isExpired(gameTime: Long): Boolean = gameTime > deadline

    /** Whole in-game days left, rounded up. */
    fun daysLeft(gameTime: Long): Long = (deadline - gameTime + TICKS_PER_DAY - 1) / TICKS_PER_DAY

    companion object {
        const val TICKS_PER_DAY = 24000L

        val CODEC: Codec<ContractState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("progress").forGetter(ContractState::progress),
                Codec.LONG.fieldOf("deadline").forGetter(ContractState::deadline),
                BlockPos.CODEC.fieldOf("board_pos").forGetter(ContractState::boardPos),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ContractState::dimension),
                BlockPos.CODEC.optionalFieldOf("destination").forGetter { Optional.ofNullable(it.destination) },
                UUIDUtil.CODEC.optionalFieldOf("bound").forGetter { Optional.ofNullable(it.bound) },
                Codec.STRING.optionalFieldOf("label", "").forGetter(ContractState::label),
                Codec.INT.optionalFieldOf("road", -1).forGetter(ContractState::road),
            ).apply(i) { progress, deadline, boardPos, dimension, destination, bound, label, road ->
                ContractState(progress, deadline, boardPos, dimension, destination.orElse(null), bound.orElse(null), label, road)
            }
        }
    }
}
