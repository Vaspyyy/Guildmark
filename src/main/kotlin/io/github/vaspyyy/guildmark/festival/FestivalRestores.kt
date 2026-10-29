package io.github.vaspyyy.guildmark.festival

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlock
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlockEntity
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB

/**
 * Putting decorations up and taking them down again, leaving the world as it was.
 *
 * A decoration only ever goes where there's air, or a plant that anything may build over (grass, a fern; never a
 * flower, a crop or anything with a block entity), and never into fluid or onto anyone standing there. What stood
 * there is written into the chunk's own record ([Pieces]) before the decoration goes in, so it's saved along with
 * the decoration itself: whatever happens (the festival ends, the server stops, it crashes) the chunk knows what to
 * put back. It's put back only if the decoration is still there: a block someone placed since is theirs.
 *
 * Every record names the festival it belongs to ([PieceRecord.site], [PieceRecord.run]); a chunk that loads with
 * records no running festival wants has them put back a moment later ([queue]).
 */
object FestivalRestores {
    /** What stood at a decorated block before, and whose decoration it is. */
    data class PieceRecord(val original: BlockState, val site: Long, val run: Long)

    /** A chunk's decorations, by packed position. */
    class Pieces(val records: Long2ObjectOpenHashMap<PieceRecord> = Long2ObjectOpenHashMap()) {
        companion object {
            private data class Entry(val pos: Long, val original: BlockState, val site: Long, val run: Long)

            private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                    BlockState.CODEC.fieldOf("was").forGetter(Entry::original),
                    Codec.LONG.fieldOf("site").forGetter(Entry::site),
                    Codec.LONG.fieldOf("run").forGetter(Entry::run),
                ).apply(i, ::Entry)
            }

            val CODEC: Codec<Pieces> = ENTRY.listOf().xmap(
                { list -> Pieces(Long2ObjectOpenHashMap<PieceRecord>().also { map -> for (e in list) map.put(e.pos, PieceRecord(e.original, e.site, e.run)) }) },
                { pieces -> pieces.records.long2ObjectEntrySet().map { Entry(it.longKey, it.value.original, it.value.site, it.value.run) } },
            )
        }
    }

    /** Chunks that loaded with records, and the game time to look at them (a running festival may want them). */
    private val queue = ArrayDeque<Pair<Long, Long>>()

    /**
     * A block of another part's that goes up and comes down as the festival's decorations do, through this record
     * (the streets' alms table, M6 Part D2): taken down, it's put back as it was, and a stray one goes.
     */
    interface Restorable

    /** Is [state] one of the festival's own blocks (or one put up the same way, a [Restorable])? */
    fun ours(state: BlockState): Boolean = state.block is FestivalTrimBlock || state.block is FestivalOrnamentBlock || state.block is Restorable

    /**
     * Whether [state] at [pos] is a plate laid for show (a part of the town's, never a player's: Hearthcraft's Plate), the kind
     * Sheafmoot's Harvest Table puts on its trestle. Its own record says whether it is a decoration; only then is it ours.
     */
    private fun shownPlate(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean =
        state.block is PlateBlock && (level.getBlockEntity(pos) as? PlateBlockEntity)?.let { it.forShow || it.dish.isEmpty } == true

    /**
     * Whether a decoration may go at [pos]: it's loaded, and holds air or a plant anything may build over (or a
     * decoration of ours already). A [solid] one also needs nobody standing there.
     */
    fun free(level: ServerLevel, pos: BlockPos, solid: Boolean): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        if (ours(state) || shownPlate(level, pos, state) && recordAt(level, pos) != null) return true
        if (!state.isAir) {
            if (!state.canBeReplaced() || !state.fluidState.isEmpty || state.hasBlockEntity() || state.block is DoublePlantBlock) return false
        }
        if (solid && level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)).isNotEmpty()) return false
        return true
    }

    /**
     * Puts [state] up at [pos] for the festival [site]'s [run], remembering what was there; or, if a decoration of
     * ours is there already (from before a restart), takes it over. Returns whether the decoration stands.
     */
    fun place(level: ServerLevel, pos: BlockPos, state: BlockState, site: Long, run: Long, solid: Boolean): Boolean {
        if (!free(level, pos, solid)) return false
        val chunk = level.getChunkAt(pos)
        val current = level.getBlockState(pos)
        val pieces = chunk.getData(FestivalRegistry.PIECES)
        val key = pos.asLong()
        val had = pieces.records.get(key)
        if (ours(current) || had != null && shownPlate(level, pos, current)) {
            // Ours already: keep what it stood in place of, and claim it for this run
            if (had == null) pieces.records.put(key, PieceRecord(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), site, run))
            else if (had.site != site || had.run != run) pieces.records.put(key, had.copy(site = site, run = run))
            chunk.markUnsaved()
            if (current != state) level.setBlock(pos, state, Block.UPDATE_ALL)
            return true
        }
        pieces.records.put(key, PieceRecord(current, site, run))
        chunk.markUnsaved()
        if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
            pieces.records.remove(key)
            return false
        }
        return true
    }

    /** Takes the decoration at [pos] down, putting back what stood there if the decoration is still ours. */
    fun restore(level: ServerLevel, pos: BlockPos) {
        if (!level.isLoaded(pos)) return
        // A plate laid for show on this one (the Harvest Table's trestle) comes off first, so it never falls and breaks
        val above = pos.above()
        if (level.isLoaded(above) && shownPlate(level, above, level.getBlockState(above)) && recordAt(level, above) != null) restore(level, above)
        val chunk = level.getChunkAt(pos)
        if (!chunk.hasData(FestivalRegistry.PIECES)) {
            // No record at all: a stray of ours goes, and anything else stays
            if (ours(level.getBlockState(pos))) level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            return
        }
        val pieces = chunk.getData(FestivalRegistry.PIECES)
        val record = pieces.records.remove(pos.asLong())
        chunk.markUnsaved()
        val current = level.getBlockState(pos)
        if (!ours(current) && !(record != null && shownPlate(level, pos, current))) return
        level.setBlock(pos, record?.original ?: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
    }

    /** What stood at [pos] before a decoration went up, and whose it is, if it is one. */
    fun recordAt(level: ServerLevel, pos: BlockPos): PieceRecord? {
        if (!level.isLoaded(pos)) return null
        val chunk = level.getChunkAt(pos)
        if (!chunk.hasData(FestivalRegistry.PIECES)) return null
        return chunk.getData(FestivalRegistry.PIECES).records.get(pos.asLong())
    }

    /** Every decoration of [site] in the loaded chunks touching the box round [center] ([reach] blocks), as positions. */
    fun recordsOf(level: ServerLevel, site: Long, center: BlockPos, reach: Int): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        for (cx in ((center.x - reach) shr 4)..((center.x + reach) shr 4)) {
            for (cz in ((center.z - reach) shr 4)..((center.z + reach) shr 4)) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                if (!chunk.hasData(FestivalRegistry.PIECES)) continue
                for (entry in chunk.getData(FestivalRegistry.PIECES).records.long2ObjectEntrySet()) {
                    if (entry.value.site == site) out.add(BlockPos.of(entry.longKey))
                }
            }
        }
        return out
    }

    /** A chunk loaded: if it has decorations, look at them in a few seconds, once any festival has picked them up. */
    fun loaded(level: ServerLevel, chunk: LevelChunk) {
        if (!chunk.hasData(FestivalRegistry.PIECES) || chunk.getData(FestivalRegistry.PIECES).records.isEmpty()) return
        queue.addLast(chunk.pos.pack() to level.gameTime + LOOK_AFTER)
    }

    /** A few queued chunks a tick: put back the records no running festival wants ([wanted] says which it does). */
    fun tick(level: ServerLevel, wanted: (BlockPos, PieceRecord) -> Boolean) {
        var budget = 4
        while (budget-- > 0) {
            val (packed, due) = queue.firstOrNull() ?: return
            if (due > level.gameTime) return
            queue.removeFirst()
            val pos = ChunkPos.unpack(packed)
            val chunk = level.chunkSource.getChunkNow(pos.x, pos.z) ?: continue
            if (!chunk.hasData(FestivalRegistry.PIECES)) continue
            val records = chunk.getData(FestivalRegistry.PIECES).records
            val stale = records.long2ObjectEntrySet().filter { !wanted(BlockPos.of(it.longKey), it.value) }.map { it.longKey }
            for (key in stale) restore(level, BlockPos.of(key))
            if (stale.isNotEmpty()) Guildmark.LOGGER.debug("Put back {} festival decorations in chunk {}", stale.size, pos)
        }
    }

    fun clear() = queue.clear()

    /** How long after a chunk loads its decorations are looked at. */
    private const val LOOK_AFTER = 200L
}
