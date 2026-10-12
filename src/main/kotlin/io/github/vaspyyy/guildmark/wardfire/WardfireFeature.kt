package io.github.vaspyyy.guildmark.wardfire

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * A cold Wardfire out in the wild, in a clearing of its own. How often one turns up is up to
 * data/guildmark/worldgen/placed_feature/wardfire.json; only land generated from now on gets them.
 */
class WardfireFeature : Feature {
    override fun codec(): MapCodec<WardfireFeature> = CODEC

    override fun place(level: WorldGenLevel, chunkGenerator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!WardfireSites.enabled() || !WardfireSites.fits(level, origin)) return false
        WardfireSites.build(level, origin, random)
        return true
    }

    companion object {
        val CODEC: MapCodec<WardfireFeature> = MapCodec.unit(WardfireFeature())
    }
}
