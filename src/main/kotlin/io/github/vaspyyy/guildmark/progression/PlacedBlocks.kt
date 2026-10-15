package io.github.vaspyyy.guildmark.progression

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.registry.ModAttachments
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.Tags
import java.util.stream.LongStream

/**
 * The ores and logs put down in one chunk, by a player or anything else. Breaking one again isn't mining
 * or woodcutting: it gives no skill XP, doubles no drops and starts no Timberfall or Vein Breaker chain.
 * Kept with the chunk, so a reload doesn't wash a placed block clean, and carried along by pistons.
 */
class PlacedBlocks(val positions: LongOpenHashSet = LongOpenHashSet()) {
    companion object {
        val CODEC: Codec<PlacedBlocks> = Codec.LONG_STREAM.xmap(
            { PlacedBlocks(LongOpenHashSet(it.toArray())) },
            { LongStream.of(*it.positions.toLongArray()) },
        )

        /** A chunk remembers at most this many; past that, more put down there count as natural. */
        private const val MOST = 4096

        /** The blocks worth remembering: the ones that pay skill XP or double their drops. */
        fun tracked(state: BlockState): Boolean = state.`is`(Tags.Blocks.ORES) || state.`is`(BlockTags.LOGS)

        fun has(level: ServerLevel, pos: BlockPos): Boolean {
            val chunk = level.getChunkAt(pos)
            return chunk.hasData(ModAttachments.PLACED_BLOCKS) && chunk.getData(ModAttachments.PLACED_BLOCKS).positions.contains(pos.asLong())
        }

        fun mark(level: ServerLevel, pos: BlockPos) {
            val chunk = level.getChunkAt(pos)
            val placed = chunk.getData(ModAttachments.PLACED_BLOCKS).positions
            if (placed.size < MOST && placed.add(pos.asLong())) chunk.markUnsaved()
        }

        /** Forgets [pos], saying whether it was remembered. */
        fun take(level: ServerLevel, pos: BlockPos): Boolean {
            val chunk = level.getChunkAt(pos)
            if (!chunk.hasData(ModAttachments.PLACED_BLOCKS)) return false
            if (!chunk.getData(ModAttachments.PLACED_BLOCKS).positions.remove(pos.asLong())) return false
            chunk.markUnsaved()
            return true
        }
    }
}
