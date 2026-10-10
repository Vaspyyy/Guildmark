package io.github.vaspyyy.guildmark.road

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * Every road in a dimension. Roads are planned up front (so travellers know the way) and paved a chunk
 * at a time as those chunks get loaded, so far-off land is never generated just for a road.
 */
class RoadNetwork(val roads: MutableList<Road> = mutableListOf()) : SavedData() {
    fun road(id: Int): Road? = roads.firstOrNull { it.id == id }

    /** Finished roads (or ones being built) that start or end at this village centre. */
    fun roadsFrom(center: BlockPos): List<Road> = roads.filter { it.touches(center) && (it.finished || it.underConstruction) }

    fun isConnected(a: BlockPos, b: BlockPos): Boolean = roads.any { it.connects(a, b, 64.0) }

    /**
     * Survey a road between two village centres in the background, then start building all of it.
     * [done] runs back on the server thread with the new road, or null if there's no route.
     */
    fun survey(level: ServerLevel, a: BlockPos, b: BlockPos, done: (Road?) -> Unit) {
        CompletableFuture.supplyAsync({ RoadPlanner.plan(level, a, b) }, Util.backgroundExecutor())
            .exceptionally { error -> Guildmark.LOGGER.error("Road survey failed", error); null }
            .thenAcceptAsync({ points ->
                if (points == null) return@thenAcceptAsync done(null)
                val road = Road((roads.maxOfOrNull { it.id } ?: 0) + 1, a, b, points, mutableSetOf(), true)
                road.unpaved.addAll(road.columnsByChunk.keys)
                roads.add(road)
                setDirty()
                done(road)
            }, level.server)
    }

    /**
     * Every tick: roads under construction load their next chunk (generating it if needed) and pave it,
     * one chunk per tick so the server keeps up. Every 20 ticks, any other unpaved stretch whose chunk
     * happens to be loaded is paved too.
     */
    fun tick(level: ServerLevel) {
        val building = roads.firstOrNull { it.underConstruction && !it.finished }
        if (building != null) {
            val chunkKey = building.unpaved.first()
            val chunkPos = ChunkPos.unpack(chunkKey)
            level.getChunk(chunkPos.x, chunkPos.z)
            pave(level, building, chunkKey)
            if (building.finished) finish(level, building)
        }
        if (level.gameTime % 20L != 0L) return
        var budget = 4
        for (road in roads) {
            for (chunkKey in road.unpaved.toList()) {
                if (budget == 0) return
                val chunkPos = ChunkPos.unpack(chunkKey)
                if (level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) == null) continue
                pave(level, road, chunkKey)
                budget--
            }
        }
    }

    private fun pave(level: ServerLevel, road: Road, chunkKey: Long) {
        for ((x, z) in road.columnsByChunk[chunkKey].orEmpty()) paveColumn(level, x, z)
        road.unpaved.remove(chunkKey)
        setDirty()
    }

    private fun finish(level: ServerLevel, road: Road) {
        road.underConstruction = false
        setDirty()
        val message = Component.translatable("message.guildmark.road_finished", road.to.x, road.to.z)
        for (player in level.players()) {
            if (road.touches(player.blockPosition(), 160.0)) player.sendSystemMessage(message)
        }
    }

    private fun paveColumn(level: ServerLevel, x: Int, z: Int) {
        var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
        // A village palisade or a tree trunk in the way: cut through it down to the ground
        while (true) {
            val below = level.getBlockState(top.below())
            if (!below.`is`(Blocks.STRIPPED_SPRUCE_LOG) && !(below.`is`(BlockTags.LOGS) && isTree(level, top.below()))) break
            top = top.below()
            level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        }
        val ground = top.below()
        val state = level.getBlockState(ground)
        val surface = when {
            !level.getFluidState(ground).isEmpty -> if (level.getFluidState(ground).isSource) Blocks.SPRUCE_PLANKS else null
            state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.DIRT) || state.`is`(Blocks.COARSE_DIRT) ||
                state.`is`(Blocks.PODZOL) || state.`is`(Blocks.MYCELIUM) || state.`is`(Blocks.ROOTED_DIRT) -> Blocks.DIRT_PATH
            state.`is`(Blocks.SAND) -> Blocks.SMOOTH_SANDSTONE
            state.`is`(Blocks.RED_SAND) -> Blocks.SMOOTH_RED_SANDSTONE
            state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(Blocks.GRAVEL) -> Blocks.COBBLESTONE
            state.`is`(Blocks.SNOW_BLOCK) -> Blocks.PACKED_ICE
            else -> null // buildings, farmland: leave them be
        } ?: return
        // Clear plants, leaves and branches out of the way, leaving headroom to walk and ride
        for (i in 0 until HEADROOM) {
            val above = top.above(i)
            val aboveState = level.getBlockState(above)
            if (aboveState.isAir || !level.getFluidState(above).isEmpty) continue
            val natural = aboveState.canBeReplaced() || isWildLeaves(aboveState) || (aboveState.`is`(BlockTags.LOGS) && isTree(level, above))
            if (natural) level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        }
        level.setBlock(ground, surface.defaultBlockState(), Block.UPDATE_ALL)
    }

    private fun isWildLeaves(state: BlockState): Boolean =
        state.`is`(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)

    /** A log that belongs to a grown tree (wild leaves around the top of its trunk), not a house beam. */
    private fun isTree(level: ServerLevel, log: BlockPos): Boolean {
        var top = log
        while (level.getBlockState(top.above()).`is`(BlockTags.LOGS) && top.y - log.y < 32) top = top.above()
        for (dx in -1..1) for (dy in 0..1) for (dz in -1..1) {
            if (isWildLeaves(level.getBlockState(top.offset(dx, dy, dz)))) return true
        }
        return false
    }

    companion object {
        /** Blocks of open space kept above a road. */
        private const val HEADROOM = 4

        private val CODEC: Codec<RoadNetwork> = RecordCodecBuilder.create { i ->
            i.group(Road.CODEC.listOf().fieldOf("roads").forGetter { it.roads })
                .apply(i) { roads -> RoadNetwork(roads.toMutableList()) }
        }

        private val TYPE = SavedDataType(
            Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "roads"),
            Supplier { RoadNetwork() },
            CODEC,
        )

        fun get(level: ServerLevel): RoadNetwork = level.dataStorage.computeIfAbsent(TYPE)
    }
}
