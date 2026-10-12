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
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import kotlin.math.abs

/** Finding the capitals the world has made, and the ways in and out of them. */
object Capitals {
    val KEY: ResourceKey<Structure> = ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "capital"))

    /** Villages and capitals: anywhere a road, a delivery or an escort can be bound for. */
    val DESTINATIONS: TagKey<Structure> = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "road_destination"))

    /**
     * Points along the way out, counted from the plaza's middle: past the fountain and down the avenue a
     * step at a time, then straight through the gatehouse (no point under its roof, where a traveller
     * looking for the ground would find the top of the arch) to the field and past the clearing.
     */
    private val WAY_OUT = listOf(8, 16, 24, 32, 40, 48, 56, CityPlan.WALL_IN + 9, CityPlan.CLEAR + 5)

    /** Chunks from where a capital may start to anywhere inside its walls: the site's shift, then the walls. */
    private const val START_REACH = 2 + (CityPlan.WALL_OUT + 15) / 16

    fun enabled(): Boolean = try {
        GuildmarkConfig.CAPITALS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /**
     * The capital with [pos] inside its walls, if there is one. Capitals are spread on a grid, one possible
     * start per square, so this looks only at the few chunks one near [pos] could have started in; each
     * is read (or generated) just far enough to know what started there.
     */
    fun at(level: ServerLevel, pos: BlockPos): CapitalPiece? {
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(KEY).orElse(null) ?: return null
        val state = level.chunkSource.generatorState
        val chunkX = SectionPos.blockToSectionCoord(pos.x)
        val chunkZ = SectionPos.blockToSectionCoord(pos.z)
        for (placement in state.getPlacementsForStructure(structure)) {
            val spread = placement as? RandomSpreadStructurePlacement ?: continue
            val spacing = spread.spacing()
            for (rx in Math.floorDiv(chunkX - START_REACH, spacing)..Math.floorDiv(chunkX + START_REACH, spacing)) {
                for (rz in Math.floorDiv(chunkZ - START_REACH, spacing)..Math.floorDiv(chunkZ + START_REACH, spacing)) {
                    val start = spread.getPotentialStructureChunk(state.levelSeed, rx * spacing, rz * spacing)
                    if (abs(start.x - chunkX) > START_REACH || abs(start.z - chunkZ) > START_REACH) continue
                    val begun = level.getChunk(start.x, start.z, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure.value())
                    val capital = begun?.takeIf { it.isValid }?.pieces?.firstOrNull() as? CapitalPiece ?: continue
                    if (capital.within(pos.x, pos.z)) return capital
                }
            }
        }
        return null
    }

    /**
     * The way out of [capital] toward [toward]: from the plaza down an avenue and out through whichever
     * gate faces most nearly that way, ending on open ground past the clearing round the walls.
     */
    fun wayOut(capital: CapitalPiece, toward: BlockPos): List<BlockPos> {
        val dx = (toward.x - capital.center.x).toDouble()
        val dz = (toward.z - capital.center.z).toDouble()
        val plan = capital.plan
        val gate = plan.gates.maxBy { side ->
            val d = capital.direction(side)
            d.stepX * dx + d.stepZ * dz
        }
        return WAY_OUT.map { out -> plan.sidePos(gate, 0, out).let { capital.world(it.x, 0, it.z) } }
    }

    /** The city and the clearing round it, which a road outside keeps off. */
    fun grounds(capital: CapitalPiece): BoundingBox = BoundingBox(
        capital.center.x - CityPlan.CLEAR, capital.center.y - 64, capital.center.z - CityPlan.CLEAR,
        capital.center.x + CityPlan.CLEAR, capital.center.y + 64, capital.center.z + CityPlan.CLEAR,
    )
}
