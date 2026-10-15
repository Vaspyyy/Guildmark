package io.github.vaspyyy.guildmark.city

import com.mojang.serialization.MapCodec
import io.github.vaspyyy.guildmark.city.CapitalPlacement.Companion.CELLS
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Ground
import io.github.vaspyyy.guildmark.city.plan.Layout
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Site
import io.github.vaspyyy.guildmark.city.plan.Survey
import io.github.vaspyyy.guildmark.registry.ModStructures
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import java.util.EnumSet
import java.util.Optional
import java.util.Random
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * A capital city. Where one may start is up to data/guildmark/worldgen/structure_set/capitals.json, whose
 * placement ([CapitalPlacement]) gives each capital a grid of starts round a centre. Every one of them looks
 * round the centre for open, level, dry land in the right country with no village or other surface structure
 * due nearby, comes to the same site, and holds the pieces of its own cell of the city.
 */
class CapitalStructure(settings: StructureSettings) : Structure(settings) {
    override fun type(): StructureType<*> = ModStructures.CAPITAL.get()

    // Every start of a capital asks where it stands, and the search takes a while: each capital's is made
    // once (any other thread asking meanwhile waits for it) and kept for a while, by world and centre
    private val foundings = WeakHashMap<RandomState, LinkedHashMap<Long, Lazy<Founding?>>>()

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        if (!Capitals.enabled()) return Optional.empty()
        val placement = placement(context) ?: return Optional.empty()
        val chunk = context.chunkPos()
        val centre = placement.centreOf(context.seed(), chunk.x, chunk.z) ?: return Optional.empty()
        val founding = founding(context, centre) ?: return Optional.empty()
        val cellX = CapitalPlacement.cell(chunk.x - centre.x)
        val cellZ = CapitalPlacement.cell(chunk.z - centre.z)
        // Where the city doesn't reach into this start's cell, there's nothing for it to hold
        val box = founding.cell(centre, cellX, cellZ) ?: return Optional.empty()
        val land = GeneratedLand.of(context)
        return Optional.of(
            GenerationStub(founding.center) { builder ->
                val town = CapitalPiece(founding, box, false, land)
                builder.addPiece(town)
                val terraces = town.plan.terraces
                for (terrace in terraces) for (beard in founding.beards(terrace, terraces, centre, cellX, cellZ)) builder.addPiece(CapitalBeard(beard))
                // The centre also keeps a copy of every cell, for far-view mods that only ever build the centre
                if (cellX == 0 && cellZ == 0) {
                    for (i in -CELLS..CELLS) for (j in -CELLS..CELLS) {
                        founding.cell(centre, i, j)?.let { builder.addPiece(CapitalPiece(founding, it, true, land)) }
                    }
                }
            },
        )
    }

    /** The placement of the structure set that places this structure, if it's a capital's. */
    private fun placement(context: GenerationContext): CapitalPlacement? {
        for (set in context.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElements()) {
            val placement = set.value().placement() as? CapitalPlacement ?: continue
            if (set.value().structures().any { it.structure().value() === this }) return placement
        }
        return null
    }

    private fun founding(context: GenerationContext, centre: ChunkPos): Founding? {
        val held = synchronized(foundings) {
            val known = foundings.getOrPut(context.randomState()) {
                object : LinkedHashMap<Long, Lazy<Founding?>>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Lazy<Founding?>>?): Boolean = size > FOUNDINGS_KEPT
                }
            }
            known.getOrPut(centre.pack()) { lazy { found(context, centre) } }
        }
        return held.value
    }

    /**
     * Where the capital centred on chunk [centre] stands, if anywhere: the first site the survey finds round the
     * centre that no other structure crowds. Everything comes from the world's seed and the centre, never from
     * the start asking, so all of a capital's starts come to the same answer.
     */
    private fun found(context: GenerationContext, centre: ChunkPos): Founding? {
        val random = WorldgenRandom(LegacyRandomSource(0L))
        random.setLargeFeatureSeed(context.seed(), centre.x, centre.z)
        val sizes = Random(random.nextLong())
        val seed = random.nextLong()
        val land = GeneratedLand.of(context)
        val survey = Survey(
            object : Ground {
                override fun height(x: Int, z: Int): Int = land.surface(x, z)

                // The structure's own country rather than the context's, which /place opens to anywhere: the site
                // is shared by every start, however it came to be asked for
                override fun fits(x: Int, z: Int, y: Int): Boolean = biomes().contains(biome(context, x, y, z))

                override fun wet(x: Int, z: Int, surface: Int): Boolean = land.floor(x, z) < surface

                override val seaLevel: Int = land.seaLevel
            },
        )
        var tries = 0
        for (site in survey.sites(centre.middleBlockX, centre.middleBlockZ, sizes)) {
            val crowded = crowding(context, survey, site)
            if (crowded != null) {
                val style = CityStyle.forBiome(biome(context, site.x, site.ground, site.z))
                return Founding(site, crowded, style, seed, top(survey, site))
            }
            if (++tries >= MAX_TRIES) break
        }
        return null
    }

    /** The highest land over the city's own ground, from the heights the survey has already looked at. */
    private fun top(survey: Survey, site: Site): Int {
        val step = Survey.FINE[site.tier.ordinal]
        val r = site.tier.levelled / step
        var top = site.ground
        for (i in -r..r) for (j in -r..r) top = max(top, survey.height(site.x + i * step, site.z + j * step))
        return top
    }

    /**
     * Would a village, outpost, temple or other surface structure start near enough to run into a capital on
     * [site]? Null if one would stand in the city or on its glacis; otherwise the sides (in the city's own
     * frame) where one would stand among its farms, which the farms leave to it. Each spreads its starts on a
     * grid of its own, one possible start to a square, so only a handful of spots have to be looked at.
     */
    private fun crowding(context: GenerationContext, survey: Survey, site: Site): Set<Dir>? {
        val sides = EnumSet.noneOf(Dir::class.java)
        for (set in context.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElements()) {
            // A ruined portal is a scatter of rubble that can stand anywhere
            if (set.`is`(BuiltinStructureSets.RUINED_PORTALS)) continue
            val placement = set.value().placement() as? RandomSpreadStructurePlacement ?: continue
            // A start in every chunk (mineshafts, buried treasure) is too thin to dodge, and it's underground anyway
            if (placement.spacing() < MIN_SPACING) continue
            val structures = set.value().structures().map { it.structure() }
                .filter { it.value().step() == GenerationStep.Decoration.SURFACE_STRUCTURES && it.value() !is CapitalStructure }
            if (structures.isEmpty()) continue
            val big = set.`is`(BuiltinStructureSets.VILLAGES) || set.`is`(BuiltinStructureSets.WOODLAND_MANSIONS)
            val margin = if (big) BIG_MARGIN else SMALL_MARGIN
            val reach = site.tier.reach + margin
            val chunks = (reach + 15) / 16 + 1
            val spacing = placement.spacing()
            val chunkX = site.x shr 4
            val chunkZ = site.z shr 4
            for (rx in Math.floorDiv(chunkX - chunks, spacing)..Math.floorDiv(chunkX + chunks, spacing)) {
                for (rz in Math.floorDiv(chunkZ - chunks, spacing)..Math.floorDiv(chunkZ + chunks, spacing)) {
                    val start = placement.getPotentialStructureChunk(context.seed(), rx * spacing, rz * spacing)
                    val dx = start.middleBlockX - site.x
                    val dz = start.middleBlockZ - site.z
                    val distance = max(abs(dx), abs(dz))
                    if (distance > reach) continue
                    if (!placement.applyAdditionalChunkRestrictions(start.x, start.z, context.seed())) continue
                    val y = survey.height(start.middleBlockX, start.middleBlockZ)
                    val biome = biome(context, start.middleBlockX, y, start.middleBlockZ)
                    if (structures.none { it.value().biomes().contains(biome) }) continue
                    if (distance <= site.tier.levelled + margin) return null
                    // Its side of the city, turned from the world's frame to the city's
                    sides.add(Dir.entries[Math.floorMod(Survey.sideOf(dx, dz).ordinal - site.turn, 4)])
                }
            }
        }
        return sides
    }

    private fun biome(context: GenerationContext, x: Int, y: Int, z: Int): Holder<Biome> =
        context.biomeResolver().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))

    companion object {
        val CODEC: MapCodec<CapitalStructure> = simpleCodec(::CapitalStructure)

        /** Capitals whose site each world keeps at hand. */
        private const val FOUNDINGS_KEPT = 32

        /** Sites tried, best first, before giving up on a capital as hemmed in by other structures. */
        private const val MAX_TRIES = 8

        /** Blocks past the city's own ground (or its farms) that a village or mansion keeps clear of, and anything smaller. */
        private const val BIG_MARGIN = 96
        private const val SMALL_MARGIN = 32
        private const val MIN_SPACING = 8
    }
}

/**
 * Where a capital stands and how it's laid out: the same for every one of its starts. [site] is the survey's,
 * [crowded] the sides (in the city's frame) whose farms other structures have taken, [seed] what its plan
 * grows from and [top] the highest land over its own ground.
 */
class Founding(val site: Site, val crowded: Set<Dir>, val style: CityStyle, val seed: Long, private val top: Int) {
    /** The plaza's middle, at the lower town's standing height. */
    val center: BlockPos = BlockPos(site.x, site.ground, site.z)
    val turn: Rotation = Rotation.entries[site.turn]
    val layout: Layout = Layout.of(site, crowded)

    /**
     * The box of cell [i], [j] of the grid of starts round chunk [centre] (see [CapitalPlacement]): its columns
     * the city reaches, from under its foundations to over the citadel's towers. Null if it reaches none.
     */
    fun cell(centre: ChunkPos, i: Int, j: Int): BoundingBox? {
        val reach = site.tier.reach
        val xs = CapitalPlacement.cellBlocks(centre.x, i, site.x - reach, site.x + reach) ?: return null
        val zs = CapitalPlacement.cellBlocks(centre.z, j, site.z - reach, site.z + reach) ?: return null
        val high = max(site.ground + site.upper + site.citadel, top)
        return BoundingBox(xs.first, site.ground - BELOW, zs.first, xs.last, high + ABOVE, zs.last)
    }

    /**
     * The boxes the terrain generator levels to [terrace]'s floor in cell [i], [j]: cut down over it and built up
     * under it, before anything grows. Where a higher terrace overlaps it, that one's ground wins. Each stops
     * [SEAM] short of the seams with the cells beside it.
     */
    fun beards(terrace: CityPlan.Terrace, terraces: List<CityPlan.Terrace>, centre: ChunkPos, i: Int, j: Int): List<BoundingBox> {
        var parts = listOf(terrace.rect)
        for (other in terraces) if (other.y > terrace.y) parts = parts.flatMap { minus(it, other.rect) }
        val floor = site.ground + terrace.y
        val ceiling = max(floor + BEARD_HEIGHT, top + OVER_TOP)
        return parts.mapNotNull { rect ->
            val a = CapitalPiece.toWorld(center, turn, rect.x0, 0, rect.z0)
            val b = CapitalPiece.toWorld(center, turn, rect.x1, 0, rect.z1)
            val xs = CapitalPlacement.cellBlocks(centre.x, i, minOf(a.x, b.x), maxOf(a.x, b.x), SEAM) ?: return@mapNotNull null
            val zs = CapitalPlacement.cellBlocks(centre.z, j, minOf(a.z, b.z), maxOf(a.z, b.z), SEAM) ?: return@mapNotNull null
            BoundingBox(xs.first, floor, zs.first, xs.last, ceiling, zs.last)
        }
    }

    /** What's left of [a] once [b] is taken out of it: up to four strips. */
    private fun minus(a: Rect, b: Rect): List<Rect> {
        if (!a.intersects(b)) return listOf(a)
        val left = ArrayList<Rect>()
        if (a.z0 < b.z0) left.add(Rect(a.x0, a.z0, a.x1, b.z0 - 1))
        if (a.z1 > b.z1) left.add(Rect(a.x0, b.z1 + 1, a.x1, a.z1))
        val z0 = max(a.z0, b.z0)
        val z1 = minOf(a.z1, b.z1)
        if (a.x0 < b.x0) left.add(Rect(a.x0, z0, b.x0 - 1, z1))
        if (a.x1 > b.x1) left.add(Rect(b.x1 + 1, z0, a.x1, z1))
        return left
    }

    companion object {
        /** Room under the lower town for foundations, and over the citadel (or the land) for its towers. */
        private const val BELOW = 32
        private const val ABOVE = 64

        /** How far over a terrace the land is cut down to it at least, and past the highest land round about. */
        private const val BEARD_HEIGHT = 16
        private const val OVER_TOP = 8

        /**
         * How far a terrace's box stops short of a seam between cells. The land is shaped some way round a box,
         * so two boxes meeting at a seam would shape it twice over along it; six apart, the two add up to
         * about one.
         */
        private const val SEAM = 3
    }
}
