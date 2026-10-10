package io.github.vaspyyy.guildmark.road

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.function.Supplier

/**
 * Every road in a dimension. Roads are planned up front (so travellers know the way) and paved a chunk
 * at a time as those chunks get loaded, so far-off land is never generated just for a road.
 */
class RoadNetwork(val roads: MutableList<Road> = mutableListOf()) : SavedData() {
    fun road(id: Int): Road? = roads.firstOrNull { it.id == id }

    /** An existing road between these two village centres, or a newly planned one. Null if no route. */
    fun connect(level: ServerLevel, a: BlockPos, b: BlockPos): Road? {
        roads.firstOrNull { it.connects(a, b, 64.0) }?.let { return it }
        val points = RoadPlanner.plan(level, a, b) ?: return null
        val road = Road((roads.maxOfOrNull { it.id } ?: 0) + 1, a, b, points, mutableSetOf())
        road.unpaved.addAll(road.columnsByChunk.keys)
        roads.add(road)
        setDirty()
        return road
    }

    /** Pave the parts of roads whose chunks are loaded now; a few chunks per call. */
    fun paveLoaded(level: ServerLevel) {
        var budget = 4
        for (road in roads) {
            val iterator = road.unpaved.iterator()
            while (iterator.hasNext() && budget > 0) {
                val chunkKey = iterator.next()
                val chunkPos = ChunkPos.unpack(chunkKey)
                if (level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) == null) continue
                for ((x, z) in road.columnsByChunk[chunkKey].orEmpty()) paveColumn(level, x, z)
                iterator.remove()
                budget--
                setDirty()
            }
            if (budget == 0) return
        }
    }

    private fun paveColumn(level: ServerLevel, x: Int, z: Int) {
        val top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, 0, z))
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
            else -> null // trees, buildings, farmland: leave them be
        } ?: return
        // Clear grass and flowers off the road
        for (i in 0..1) {
            val above = top.above(i)
            val aboveState = level.getBlockState(above)
            if (!aboveState.isAir && aboveState.canBeReplaced() && level.getFluidState(above).isEmpty) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            }
        }
        level.setBlock(ground, surface.defaultBlockState(), Block.UPDATE_ALL)
    }

    companion object {
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
