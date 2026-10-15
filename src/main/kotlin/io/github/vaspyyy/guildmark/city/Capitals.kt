package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureCheckResult
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Finding the capitals the world has made, and the ways in and out of them. A capital is known by its centre
 * start (see [CapitalPlacement]): the chunk /locate and maps lead to, whose first piece is the city's middle
 * cell and knows the whole plan.
 */
object Capitals {
    val KEY: ResourceKey<Structure> = ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "capital"))

    /** Villages and capitals: anywhere a road, a delivery or an escort can be bound for. */
    val DESTINATIONS: TagKey<Structure> = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "road_destination"))

    /** Chunks from a capital's centre start to anywhere it may reach, farms and all. */
    private val START_REACH = (8 + CapitalPlacement.MAX_REACH + 15) / 16

    /** Blocks from the middle of a capital's centre chunk to the far corner of its farms, and a little over. */
    val START_SLACK: Int = ceil(CapitalPlacement.MAX_REACH * sqrt(2.0)).toInt() + 16

    fun enabled(): Boolean = try {
        GuildmarkConfig.CAPITALS.get()
    } catch (e: IllegalStateException) {
        true
    }

    /**
     * The capital with [pos] inside its walls, if there is one; or whose centre chunk it's in, since that's where
     * /locate and maps say a capital is, though its site may lie a little way off.
     */
    fun at(level: ServerLevel, pos: BlockPos): CapitalPiece? {
        val chunk = ChunkPos.containing(pos)
        return starts(level, pos.x, pos.z, pos.x, pos.z) { _, _ -> true }
            .firstOrNull { (centre, capital) -> centre == chunk || capital.within(pos.x, pos.z) }?.second
    }

    /**
     * Every capital that could reach into the box from ([x0], [z0]) to ([x1], [z1]), of those whose centre (the
     * middle of its centre chunk) passes [keep].
     */
    fun around(
        level: ServerLevel, x0: Int, z0: Int, x1: Int, z1: Int,
        keep: (Int, Int) -> Boolean = { _, _ -> true },
    ): List<CapitalPiece> = starts(level, x0, z0, x1, z1, keep).map { it.second }

    /**
     * The centre starts round a box, each with its middle cell. Capitals are spread on a grid, one possible centre
     * per square, so only those few chunks are looked at: each is first checked the cheap way (worked out, not
     * generated), and only a real start is read from its chunk. Capitals saved by older versions are left out.
     */
    private fun starts(level: ServerLevel, x0: Int, z0: Int, x1: Int, z1: Int, keep: (Int, Int) -> Boolean): List<Pair<ChunkPos, CapitalPiece>> {
        val structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(KEY).orElse(null) ?: return listOf()
        val state = level.chunkSource.generatorState
        val chunkX0 = SectionPos.blockToSectionCoord(minOf(x0, x1)) - START_REACH
        val chunkX1 = SectionPos.blockToSectionCoord(maxOf(x0, x1)) + START_REACH
        val chunkZ0 = SectionPos.blockToSectionCoord(minOf(z0, z1)) - START_REACH
        val chunkZ1 = SectionPos.blockToSectionCoord(maxOf(z0, z1)) + START_REACH
        val found = ArrayList<Pair<ChunkPos, CapitalPiece>>()
        for (placement in state.getPlacementsForStructure(structure)) {
            val spread = placement as? CapitalPlacement ?: continue
            val spacing = spread.spacing()
            for (rx in Math.floorDiv(chunkX0, spacing)..Math.floorDiv(chunkX1, spacing)) {
                for (rz in Math.floorDiv(chunkZ0, spacing)..Math.floorDiv(chunkZ1, spacing)) {
                    val centre = spread.getPotentialStructureChunk(state.levelSeed, rx * spacing, rz * spacing)
                    if (centre.x !in chunkX0..chunkX1 || centre.z !in chunkZ0..chunkZ1) continue
                    if (!keep(centre.middleBlockX, centre.middleBlockZ)) continue
                    val presence = level.structureManager().checkStructurePresence(centre, structure.value(), spread, false)
                    if (presence == StructureCheckResult.START_NOT_PRESENT) continue
                    val begun = level.getChunk(centre.x, centre.z, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure.value())
                    val capital = begun?.takeIf { it.isValid }?.pieces?.firstOrNull() as? CapitalPiece ?: continue
                    if (capital.layout == null || capital.proxy) continue
                    found.add(centre to capital.inWorld(level))
                }
            }
        }
        return found
    }

    /**
     * The way out of [capital] toward [toward]: from the plaza along the streets and out through whichever outer
     * gate faces most nearly that way (never the harbour's, which opens onto the water), ending on the road just
     * past the farms.
     */
    fun wayOut(capital: CapitalPiece, toward: BlockPos): List<BlockPos> {
        val dx = (toward.x - capital.center.x).toDouble()
        val dz = (toward.z - capital.center.z).toDouble()
        val plan = capital.plan
        val gates = plan.outerGates.filter { it != plan.layout.harbour }.ifEmpty { plan.outerGates }
        val gate = gates.maxBy { side ->
            val d = capital.direction(side)
            d.stepX * dx + d.stepZ * dz
        }
        return plan.wayOut(gate).map { capital.standing(plan, it) }
    }

    /** All the ground a capital takes, its farms included, which a road outside keeps off. */
    fun grounds(capital: CapitalPiece): BoundingBox {
        val reach = capital.layout?.tier?.reach ?: 0
        return BoundingBox(
            capital.center.x - reach, capital.center.y - 64, capital.center.z - reach,
            capital.center.x + reach, capital.center.y + 64, capital.center.z + reach,
        )
    }
}
