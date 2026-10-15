package io.github.vaspyyy.guildmark.city

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement.ExclusionZone
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement.FrequencyReductionMethod
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType
import java.util.Optional
import kotlin.math.abs

/**
 * Where capitals start. The world is cut into square regions of [spacing] chunks, as for any spread of
 * structures, and each region may hold one capital, kept whole within [DOMAIN] chunks of the region's middle
 * chunk, so two capitals never meet; the survey looks over all of that for the best site (see CapitalStructure).
 * A capital is far too big for one structure start: the game only looks eight chunks round a chunk for the
 * starts whose pieces reach into it. So each region has a five by five grid of starts [STEP] chunks apart round
 * its middle chunk, the centre; every start comes to the same site and holds the pieces of its own cell of the
 * city. Only the centre counts as where the capital is, for /locate, maps and [Capitals].
 */
@Suppress("DEPRECATION") // ExclusionZone, which every spread placement still takes
class CapitalPlacement(
    locateOffset: Vec3i,
    frequencyReductionMethod: FrequencyReductionMethod,
    frequency: Float,
    salt: Int,
    exclusionZone: Optional<ExclusionZone>,
    spacing: Int,
    separation: Int,
    spreadType: RandomSpreadType,
) : RandomSpreadStructurePlacement(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone, spacing, separation, spreadType) {

    /** The middle chunk of the region chunk [x], [z] is in: where that region's capital is centred, if it has one. */
    override fun getPotentialStructureChunk(seed: Long, x: Int, z: Int): ChunkPos {
        val spacing = spacing()
        return ChunkPos(Math.floorDiv(x, spacing) * spacing + spacing / 2, Math.floorDiv(z, spacing) * spacing + spacing / 2)
    }

    /** The centre start of the capital whose grid chunk [chunkX], [chunkZ] is on, as the centre itself or a satellite, or null if it's on none. */
    fun centreOf(seed: Long, chunkX: Int, chunkZ: Int): ChunkPos? {
        val centre = getPotentialStructureChunk(seed, chunkX, chunkZ)
        val dx = chunkX - centre.x
        val dz = chunkZ - centre.z
        return if (abs(dx) <= GRID && abs(dz) <= GRID && dx % STEP == 0 && dz % STEP == 0) centre else null
    }

    override fun isPlacementChunk(state: ChunkGeneratorStructureState, x: Int, z: Int): Boolean = centreOf(state.levelSeed, x, z) != null

    // Thinning out and keeping clear of other structures both go by the centre, so a capital comes whole or not at all
    override fun applyAdditionalChunkRestrictions(x: Int, z: Int, seed: Long): Boolean {
        val centre = centreOf(seed, x, z) ?: return false
        return super.applyAdditionalChunkRestrictions(centre.x, centre.z, seed)
    }

    override fun applyInteractionsWithOtherStructures(state: ChunkGeneratorStructureState, x: Int, z: Int): Boolean {
        val centre = centreOf(state.levelSeed, x, z) ?: return false
        return super.applyInteractionsWithOtherStructures(state, centre.x, centre.z)
    }

    // /locate leads to the city's own middle once its site has been worked out, rather than to its region's
    override fun getLocatePos(chunkPos: ChunkPos): BlockPos = CapitalStructure.siteOf(chunkPos) ?: super.getLocatePos(chunkPos)

    // The placement registry finds a placement's type by its codec, so this must be the one registered
    @Suppress("UNCHECKED_CAST")
    override fun codec(): MapCodec<RandomSpreadStructurePlacement> = CODEC as MapCodec<RandomSpreadStructurePlacement>

    companion object {
        /** Chunks between neighbouring starts of one capital. */
        const val STEP = 12

        /** Cells each way from the centre: the grid is twice this plus one starts to a side. */
        const val CELLS = 2

        /** Chunks from the centre start to the furthest satellite, along each axis. */
        const val GRID = STEP * CELLS

        /** How far round a start, in chunks, the game looks for its pieces. */
        private const val REFERENCE = 8

        /**
         * Chunks a capital may take each way from its centre, from DOMAIN before it up to DOMAIN - 1 after: as far
         * as the outermost starts can hold, and so the middle 64 chunks of its region.
         */
        const val DOMAIN = GRID + REFERENCE

        /** Furthest any of a capital may lie from the middle of its centre chunk, along either axis, in blocks. */
        const val MAX_REACH = DOMAIN * 16 + 8

        /** Room for a capital's site and its reach, each way along both axes from the middle of its centre chunk. */
        const val ROOM = DOMAIN * 16 - 9

        /**
         * Which cell of its capital's grid a chunk falls in, along one axis, from its offset in chunks from the
         * centre start: the start [STEP] times that many chunks along holds it.
         */
        fun cell(offset: Int): Int = Math.floorDiv(offset + STEP / 2, STEP).coerceIn(-CELLS, CELLS)

        /**
         * The blocks along one axis in [cell] of the grid round the centre start at chunk [centre], of those
         * from [from] to [to] (the city's reach); null if the city doesn't reach into it. Every cell is whole
         * chunks, so a chunk is always drawn by one start; the outermost take everything past them. With a
         * [seam], the blocks stop that far short of the cells next to it.
         */
        fun cellBlocks(centre: Int, cell: Int, from: Int, to: Int, seam: Int = 0): IntRange? {
            val first = if (cell <= -CELLS) from else maxOf(from, (centre + cell * STEP - STEP / 2) * 16 + seam)
            val last = if (cell >= CELLS) to else minOf(to, (centre + cell * STEP + STEP / 2) * 16 - 1 - seam)
            return if (first <= last) first..last else null
        }

        val CODEC: MapCodec<CapitalPlacement> = RecordCodecBuilder.mapCodec<CapitalPlacement> { i ->
            placementCodec(i).and(
                i.group(
                    Codec.intRange(0, 4096).fieldOf("spacing").forGetter(CapitalPlacement::spacing),
                    Codec.intRange(0, 4096).fieldOf("separation").forGetter(CapitalPlacement::separation),
                    RandomSpreadType.CODEC.optionalFieldOf("spread_type", RandomSpreadType.LINEAR).forGetter(CapitalPlacement::spreadType),
                ),
            ).apply(i, ::CapitalPlacement)
        }.validate { placement ->
            when {
                placement.spacing() <= placement.separation() -> DataResult.error { "Spacing has to be larger than separation" }
                // Every capital keeps to the middle of its own region, which must be big enough to hold one
                placement.spacing() < 2 * DOMAIN -> DataResult.error { "Spacing has to be at least ${2 * DOMAIN} to hold a capital" }
                else -> DataResult.success(placement)
            }
        }
    }
}
