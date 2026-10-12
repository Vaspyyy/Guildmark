package io.github.vaspyyy.guildmark.city

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * Runs once in every chunk before trees and plants go in (see the capital_groundwork biome modifier), and
 * lays [CapitalPiece.groundwork] wherever a capital covers the chunk. Most chunks have none and are done
 * after one lookup.
 */
class CapitalGroundwork : Feature {
    override fun codec(): MapCodec<CapitalGroundwork> = CODEC

    override fun place(level: WorldGenLevel, chunkGenerator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        val chunk = ChunkPos.containing(origin)
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Capitals.KEY) ?: return false
        val references = level.getChunk(chunk.x, chunk.z).getReferencesForStructure(structure)
        var laid = false
        for (key in references) {
            val home = ChunkPos.unpack(key)
            // The city's start is a few chunks off. Vanilla generation can always see it; a distant-terrain mod's own generator may not
            val start = try {
                level.getChunk(home.x, home.z, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure)
            } catch (e: RuntimeException) {
                null
            }
            if (start == null || !start.isValid) continue
            for (piece in start.pieces) {
                if (piece is CapitalPiece) {
                    piece.groundwork(level, chunk)
                    laid = true
                }
            }
        }
        return laid
    }

    companion object {
        val CODEC: MapCodec<CapitalGroundwork> = MapCodec.unit(CapitalGroundwork())
    }
}
