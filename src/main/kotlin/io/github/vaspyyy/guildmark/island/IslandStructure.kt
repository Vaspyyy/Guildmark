package io.github.vaspyyy.guildmark.island

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.island.plan.Climate
import io.github.vaspyyy.guildmark.island.plan.IslandKind
import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import java.util.Optional
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An island out at sea. Where one may start is up to data/guildmark/worldgen/structure_set/islands.json; a start
 * holds only where the open sea runs all round it, deep enough at its middle and with no coast near (an island is
 * never grown onto the mainland's shore). Its kind, lie and size come from the world's seed and its chunk, and the
 * sea it stands in from the ocean at its middle.
 */
class IslandStructure(settings: StructureSettings) : Structure(settings) {
    override fun type(): StructureType<*> = IslandRegistry.ISLAND.get()

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        if (!Islands.enabled()) return Optional.empty()
        val chunk = context.chunkPos()
        val x = chunk.middleBlockX
        val z = chunk.middleBlockZ
        val generator = context.chunkGenerator()
        val sea = generator.seaLevel
        // The ocean first (cheap), then the sea bed round about (each reading works out a column of the land)
        val biome = context.biomeResolver().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea - 8), QuartPos.fromBlock(z))
        if (!biomes().contains(biome)) return Optional.empty()
        val spec = IslandSpec.roll(seedFor(context.seed(), chunk), x, z, sea, climateOf(biome))
        val heights = context.heightAccessor()
        val state = context.randomState()
        fun bed(bx: Int, bz: Int): Int = generator.getBaseHeight(bx, bz, Heightmap.Types.OCEAN_FLOOR_WG, heights, state)
        if (bed(x, z) > sea - MIN_DEPTH) return Optional.empty()
        val reach = spec.radius * 1.4 + 24
        for (ring in doubleArrayOf(reach, reach * 1.6)) {
            for (k in 0 until RING) {
                val a = k * 2 * PI / RING + (if (ring > reach) PI / RING else 0.0)
                if (bed(x + (cos(a) * ring).roundToInt(), z + (sin(a) * ring).roundToInt()) > sea - SHORE_DEPTH) return Optional.empty()
            }
        }
        return Optional.of(GenerationStub(BlockPos(x, sea, z)) { builder -> builder.addPiece(IslandPiece(spec)) })
    }

    companion object {
        val CODEC: MapCodec<IslandStructure> = simpleCodec(::IslandStructure)

        /** How deep the sea must be at an island's middle, and anywhere round it out past its shallows. */
        private const val MIN_DEPTH = 7
        private const val SHORE_DEPTH = 4

        /** Readings of the sea bed on each ring round a start. */
        private const val RING = 12

        /** What an island at chunk [chunk] grows from, in a world of [worldSeed]: the same every time it's asked. */
        fun seedFor(worldSeed: Long, chunk: ChunkPos): Long {
            val random = WorldgenRandom(LegacyRandomSource(0L))
            random.setLargeFeatureSeed(worldSeed xor 0x15A1D5L, chunk.x, chunk.z)
            return random.nextLong()
        }

        /** The sea an ocean is: warm seas grow palms and coral, frozen ones snow over. */
        fun climateOf(biome: Holder<Biome>): Climate = when {
            biome.`is`(Biomes.WARM_OCEAN) -> Climate.WARM
            biome.`is`(Biomes.LUKEWARM_OCEAN) || biome.`is`(Biomes.DEEP_LUKEWARM_OCEAN) -> Climate.LUKEWARM
            biome.`is`(Biomes.COLD_OCEAN) || biome.`is`(Biomes.DEEP_COLD_OCEAN) -> Climate.COLD
            biome.`is`(Biomes.FROZEN_OCEAN) || biome.`is`(Biomes.DEEP_FROZEN_OCEAN) -> Climate.FROZEN
            else -> {
                // Another mod's sea: by how warm it is
                val t = biome.value().baseTemperature
                when {
                    t >= 0.9f -> Climate.WARM
                    t >= 0.7f -> Climate.LUKEWARM
                    t < 0.05f -> Climate.FROZEN
                    t < 0.3f -> Climate.COLD
                    else -> Climate.TEMPERATE
                }
            }
        }

        /** An island of [kind] (or any) for /guildmark island here, standing at [x], [z] in a sea of [climate]. */
        fun specFor(seed: Long, x: Int, z: Int, sea: Int, climate: Climate, kind: IslandKind?): IslandSpec = IslandSpec.roll(seed, x, z, sea, climate, kind)
    }
}
