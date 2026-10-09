package io.github.vaspyyy.guildmark.quest

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/** Per-contract progress: kills counted so far, when it expires, and the board it was taken from. */
data class ContractState(
    val progress: Int,
    /** Game time after which the contract can no longer be completed. */
    val deadline: Long,
    val boardPos: BlockPos,
    val dimension: ResourceKey<Level>,
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
            ).apply(i) { progress, deadline, boardPos, dimension ->
                ContractState(progress, deadline, boardPos, dimension)
            }
        }
    }
}
