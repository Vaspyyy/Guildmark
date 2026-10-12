package io.github.vaspyyy.guildmark.city

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.registry.ModStructures
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import java.util.Optional
import kotlin.math.abs

/**
 * A capital city. Where one may start is up to data/guildmark/worldgen/structure_set/capitals.json;
 * this looks round that spot for open, level, dry land in the right country with no village or other
 * surface structure due nearby, and lays the city out there.
 */
class CapitalStructure(settings: StructureSettings) : Structure(settings) {
    override fun type(): StructureType<*> = ModStructures.CAPITAL.get()

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        if (!Capitals.enabled()) return Optional.empty()
        val chunk = context.chunkPos()
        val generator = context.chunkGenerator()
        for ((ox, oz) in SITES) {
            val x = chunk.middleBlockX + ox * 16
            val z = chunk.middleBlockZ + oz * 16
            // The country first, since that's one look; then the lie of the land and the neighbours
            val height = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState())
            val biome = context.biomeResolver().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(height), QuartPos.fromBlock(z))
            if (!context.validBiome().test(biome)) continue
            val ground = levelGround(context, x, z) ?: continue
            if (crowded(context, x shr 4, z shr 4)) continue
            val center = BlockPos(x, ground, z)
            val rotation = Rotation.getRandom(context.random())
            val seed = context.random().nextLong()
            val style = CityStyle.forBiome(biome)
            return Optional.of(GenerationStub(center) { it.addPiece(CapitalPiece(center, rotation, style, seed)) })
        }
        return Optional.empty()
    }

    /**
     * Standing height for a city around [x], [z], or null if the land there is too low (sea, river,
     * marsh), too broken or too steep. Heights come straight from the generator, so nothing has to be
     * built to look.
     */
    private fun levelGround(context: GenerationContext, x: Int, z: Int): Int? {
        val generator = context.chunkGenerator()
        val heights = IntArray(SAMPLES * SAMPLES)
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        var i = 0
        for (sx in 0 until SAMPLES) for (sz in 0 until SAMPLES) {
            val px = x - SPAN + sx * SPAN * 2 / (SAMPLES - 1)
            val pz = z - SPAN + sz * SPAN * 2 / (SAMPLES - 1)
            val height = generator.getBaseHeight(px, pz, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState())
            // Down at the water's edge or under it
            if (height <= generator.seaLevel + 1) return null
            low = minOf(low, height)
            high = maxOf(high, height)
            if (high - low > MAX_SPREAD) return null
            heights[i++] = height
        }
        heights.sort()
        // A little under the middle height, so more of the land is built up than cut away
        val ground = heights[heights.size * 2 / 5]
        val roughness = heights.sumOf { abs(it - ground) }.toDouble() / heights.size
        return if (roughness <= MAX_ROUGHNESS) ground else null
    }

    /**
     * Would a village, outpost, temple or other surface structure start near enough to run into a city
     * with its middle in chunk [chunkX], [chunkZ]? Each spreads its starts on a grid of its own, one
     * possible start per square, so only a handful of chunks have to be looked at.
     */
    private fun crowded(context: GenerationContext, chunkX: Int, chunkZ: Int): Boolean {
        val sets = context.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        for (set in sets.listElements()) {
            val placement = set.value().placement() as? RandomSpreadStructurePlacement ?: continue
            // A start in every chunk (mineshafts, buried treasure) is too thin to dodge, and it's underground anyway
            if (placement.spacing() < MIN_SPACING) continue
            val structures = set.value().structures().map { it.structure() }
                .filter { it.value().step() == GenerationStep.Decoration.SURFACE_STRUCTURES && it.value() !is CapitalStructure }
            if (structures.isEmpty()) continue
            val big = set.`is`(BuiltinStructureSets.VILLAGES) || set.`is`(BuiltinStructureSets.WOODLAND_MANSIONS)
            val reach = CITY_CHUNKS + if (big) BIG_REACH else SMALL_REACH
            val spacing = placement.spacing()
            for (rx in Math.floorDiv(chunkX - reach, spacing)..Math.floorDiv(chunkX + reach, spacing)) {
                for (rz in Math.floorDiv(chunkZ - reach, spacing)..Math.floorDiv(chunkZ + reach, spacing)) {
                    val start = placement.getPotentialStructureChunk(context.seed(), rx * spacing, rz * spacing)
                    if (abs(start.x - chunkX) > reach || abs(start.z - chunkZ) > reach) continue
                    if (!placement.applyAdditionalChunkRestrictions(start.x, start.z, context.seed())) continue
                    val sx = start.middleBlockX
                    val sz = start.middleBlockZ
                    val sy = context.chunkGenerator().getBaseHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState())
                    val biome = context.biomeResolver().getNoiseBiome(QuartPos.fromBlock(sx), QuartPos.fromBlock(sy), QuartPos.fromBlock(sz))
                    if (structures.any { it.value().biomes().contains(biome) }) return true
                }
            }
        }
        return false
    }

    companion object {
        val CODEC: MapCodec<CapitalStructure> = simpleCodec(::CapitalStructure)

        /** Chunk offsets tried for the city's middle, nearest the chosen spot first. */
        private val SITES = listOf(0 to 0, 2 to 0, -2 to 0, 0 to 2, 0 to -2)
        /** Heights sampled on a grid this many to a side, out to this far from the middle. */
        private const val SAMPLES = 7
        private const val SPAN = 66
        /** Most the land may rise and fall across the city, and on average away from its height. */
        private const val MAX_SPREAD = 16
        private const val MAX_ROUGHNESS = 3.5

        /** Chunks from a city's middle to the edge of its clearing. */
        private const val CITY_CHUNKS = (CityPlan.CLEAR + 15) / 16
        /** Chunks a village or mansion reaches from its start, and anything smaller. */
        private const val BIG_REACH = 6
        private const val SMALL_REACH = 2
        private const val MIN_SPACING = 8
    }
}
