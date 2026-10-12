package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureCheckResult
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import kotlin.math.abs

/** Finding the capitals the world has made, and the ways in and out of them. */
object Capitals {
    val KEY: ResourceKey<Structure> = ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "capital"))

    /** Villages and capitals: anywhere a road, a delivery or an escort can be bound for. */
    val DESTINATIONS: TagKey<Structure> = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "road_destination"))

    /** Chunks from where a capital may start to anywhere inside its walls: the site's shift, then the walls. */
    private val START_REACH = 2 + (CityPlan.WALL_OUT + 15) / 16

    /** Blocks from the middle of the chunk a capital may start in to the far corner of its clearing, and a little over. */
    const val START_SLACK = 160

    fun enabled(): Boolean = try {
        GuildmarkConfig.CAPITALS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /** The capital with [pos] inside its walls, if there is one. */
    fun at(level: ServerLevel, pos: BlockPos): CapitalPiece? =
        around(level, pos.x, pos.z, pos.x, pos.z).firstOrNull { it.within(pos.x, pos.z) }

    /**
     * Every capital that could reach into the box from ([x0], [z0]) to ([x1], [z1]), of those whose start
     * (the middle of the chunk it could begin in) passes [keep]. Capitals are spread on a grid, one possible
     * start per square, so only those few chunks are looked at: each is first checked the cheap way (worked
     * out, not generated), and only a real start is read from its chunk.
     */
    fun around(
        level: ServerLevel, x0: Int, z0: Int, x1: Int, z1: Int,
        keep: (Int, Int) -> Boolean = { _, _ -> true },
    ): List<CapitalPiece> {
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(KEY).orElse(null) ?: return listOf()
        val state = level.chunkSource.generatorState
        val chunkX0 = SectionPos.blockToSectionCoord(minOf(x0, x1)) - START_REACH
        val chunkX1 = SectionPos.blockToSectionCoord(maxOf(x0, x1)) + START_REACH
        val chunkZ0 = SectionPos.blockToSectionCoord(minOf(z0, z1)) - START_REACH
        val chunkZ1 = SectionPos.blockToSectionCoord(maxOf(z0, z1)) + START_REACH
        val found = ArrayList<CapitalPiece>()
        for (placement in state.getPlacementsForStructure(structure)) {
            val spread = placement as? RandomSpreadStructurePlacement ?: continue
            val spacing = spread.spacing()
            for (rx in Math.floorDiv(chunkX0, spacing)..Math.floorDiv(chunkX1, spacing)) {
                for (rz in Math.floorDiv(chunkZ0, spacing)..Math.floorDiv(chunkZ1, spacing)) {
                    val start = spread.getPotentialStructureChunk(state.levelSeed, rx * spacing, rz * spacing)
                    if (start.x !in chunkX0..chunkX1 || start.z !in chunkZ0..chunkZ1) continue
                    if (!keep(start.middleBlockX, start.middleBlockZ)) continue
                    val presence = level.structureManager().checkStructurePresence(start, structure.value(), spread, false)
                    if (presence == StructureCheckResult.START_NOT_PRESENT) continue
                    val begun = level.getChunk(start.x, start.z, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure.value())
                    val capital = begun?.takeIf { it.isValid }?.pieces?.firstOrNull() as? CapitalPiece ?: continue
                    found.add(capital)
                }
            }
        }
        return found
    }

    /**
     * The way out of [capital] toward [toward]: from the plaza down an avenue and out through whichever
     * gate faces most nearly that way, ending on open ground past the clearing round the walls.
     */
    fun wayOut(capital: CapitalPiece, toward: BlockPos): List<BlockPos> {
        val dx = (toward.x - capital.center.x).toDouble()
        val dz = (toward.z - capital.center.z).toDouble()
        val plan = capital.plan
        val gate = plan.outerGates.maxBy { side ->
            val d = capital.direction(side)
            d.stepX * dx + d.stepZ * dz
        }
        return plan.wayOut(gate).map { capital.world(it.x, plan.level(it.x, it.z), it.z) }
    }

    /** The city and the clearing round it, which a road outside keeps off. */
    fun grounds(capital: CapitalPiece): BoundingBox = BoundingBox(
        capital.center.x - CityPlan.CLEAR, capital.center.y - 64, capital.center.z - CityPlan.CLEAR,
        capital.center.x + CityPlan.CLEAR, capital.center.y + 64, capital.center.z + CityPlan.CLEAR,
    )
}
