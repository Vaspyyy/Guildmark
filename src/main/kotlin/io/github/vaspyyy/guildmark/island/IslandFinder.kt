package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureCheckResult
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement

/**
 * Islands out at sea, told before anyone has sailed out to them: where the world grows them and what each will be,
 * reckoned from the world's seed just as the world places them (see [IslandStructure]), so no chunk is loaded for it.
 * The quest boards use it to send people to isles that are really there.
 */
object IslandFinder {
    /**
     * Every island the world may grow within [range] blocks of [from], nearest first: each where its sea and its roll
     * put it, and what it'll be. Whether the sea bed round one lets it stand is for [stands] to say.
     */
    fun around(level: ServerLevel, from: BlockPos, range: Int): List<IslandSpec> {
        val structure = structure(level) ?: return listOf()
        val state = level.chunkSource.generatorState
        val placement = state.getPlacementsForStructure(structure).firstNotNullOfOrNull { it as? RandomSpreadStructurePlacement } ?: return listOf()
        val seed = state.levelSeed
        val sea = level.chunkSource.generator.seaLevel
        val spacing = placement.spacing()
        val gx = Math.floorDiv(SectionPos.blockToSectionCoord(from.x), spacing)
        val gz = Math.floorDiv(SectionPos.blockToSectionCoord(from.z), spacing)
        val reach = range / (spacing * 16) + 1
        val found = ArrayList<Pair<IslandSpec, Long>>()
        for (dx in -reach..reach) for (dz in -reach..reach) {
            // The one chunk of each cell of the spread where an island may start
            val chunk = placement.getPotentialStructureChunk(seed, (gx + dx) * spacing, (gz + dz) * spacing)
            val x = chunk.middleBlockX
            val z = chunk.middleBlockZ
            val ex = (x - from.x).toLong()
            val ez = (z - from.z).toLong()
            val d2 = ex * ex + ez * ez
            if (d2 > range.toLong() * range) continue
            // Too near an ocean monument, or the like
            if (!placement.isStructureChunk(state, chunk.x, chunk.z)) continue
            val biome = level.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea - 8), QuartPos.fromBlock(z))
            if (!structure.value().biomes().contains(biome)) continue
            found.add(IslandSpec.roll(IslandStructure.seedFor(seed, chunk), x, z, sea, IslandStructure.climateOf(biome)) to d2)
        }
        found.sortBy { it.second }
        return found.map { it.first }
    }

    /**
     * Whether the island [spec] stands: grown already, or sure to be when its waters are first explored (the sea deep
     * enough at its middle and all round it). Asked of waters no one has seen, the sea bed there is worked out once.
     */
    fun stands(level: ServerLevel, spec: IslandSpec): Boolean {
        val structure = structure(level) ?: return false
        val placement = level.chunkSource.generatorState.getPlacementsForStructure(structure).firstOrNull() ?: return false
        val chunk = ChunkPos(SectionPos.blockToSectionCoord(spec.cx), SectionPos.blockToSectionCoord(spec.cz))
        return when (level.structureManager().checkStructurePresence(chunk, structure.value(), placement, false)) {
            StructureCheckResult.START_PRESENT, StructureCheckResult.CHUNK_LOAD_NEEDED -> true
            else -> false
        }
    }

    private fun structure(level: ServerLevel): Holder<Structure>? =
        level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(Islands.KEY).orElse(null)
}
