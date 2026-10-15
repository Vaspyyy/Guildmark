package io.github.vaspyyy.guildmark.city

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.city.plan.Survey
import io.github.vaspyyy.guildmark.city.plan.Tier
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
 * Where capitals start. A capital is far too big for one structure start: the game only looks eight chunks
 * round a chunk for the starts whose pieces reach into it. So each capital is a five by five grid of starts
 * eight chunks apart: its centre, one to a region of [spacing] chunks as with any random spread, and the
 * satellites round it. Every start finds the same site (see CapitalStructure) and holds the pieces of its own
 * cell of the city. Only the centre counts as where the capital is, for /locate, maps and [Capitals].
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

    /**
     * The centre start of the capital whose grid chunk [chunkX], [chunkZ] is on, as the centre itself or one of
     * its satellites, or null if it's on none. Grids never share a chunk (see [CODEC]'s check on separation).
     */
    fun centreOf(seed: Long, chunkX: Int, chunkZ: Int): ChunkPos? {
        val spacing = spacing()
        for (rx in Math.floorDiv(chunkX - GRID, spacing)..Math.floorDiv(chunkX + GRID, spacing)) {
            for (rz in Math.floorDiv(chunkZ - GRID, spacing)..Math.floorDiv(chunkZ + GRID, spacing)) {
                val centre = getPotentialStructureChunk(seed, rx * spacing, rz * spacing)
                val dx = chunkX - centre.x
                val dz = chunkZ - centre.z
                if (abs(dx) <= GRID && abs(dz) <= GRID && dx % STEP == 0 && dz % STEP == 0) return centre
            }
        }
        return null
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

    // The placement registry finds a placement's type by its codec, so this must be the one registered
    @Suppress("UNCHECKED_CAST")
    override fun codec(): MapCodec<RandomSpreadStructurePlacement> = CODEC as MapCodec<RandomSpreadStructurePlacement>

    companion object {
        /** Chunks between neighbouring starts of one capital. */
        const val STEP = 8

        /** Cells each way from the centre: the grid is twice this plus one starts to a side. */
        const val CELLS = 2

        /** Chunks from the centre start to the furthest satellite, along each axis. */
        const val GRID = STEP * CELLS

        /**
         * Furthest a capital may reach from its centre chunk's middle, in blocks: the site search's shift
         * and the biggest city's reach. The outermost cells take all of the city beyond them, and must still
         * lie within [STEP] chunks of their own start.
         */
        val MAX_REACH: Int = Survey.SHIFT_STEPS * Survey.COARSE + Tier.entries.maxOf { it.reach }

        init {
            check(8 + MAX_REACH < (GRID + STEP + 1) * 16) { "A capital may reach $MAX_REACH blocks, too far for its grid of starts" }
        }

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
                // Centres are at least separation + 1 chunks apart, so two grids can't share a chunk
                placement.separation() < 2 * GRID -> DataResult.error { "Separation has to be at least ${2 * GRID} so capitals' grids of starts don't meet" }
                else -> DataResult.success(placement)
            }
        }
    }
}
