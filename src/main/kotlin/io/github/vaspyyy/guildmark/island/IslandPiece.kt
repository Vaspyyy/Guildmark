package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.island.plan.Climate
import io.github.vaspyyy.guildmark.island.plan.Form
import io.github.vaspyyy.guildmark.island.plan.IslandKind
import io.github.vaspyyy.guildmark.island.plan.IslandPlan
import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import io.github.vaspyyy.guildmark.island.plan.Rock
import io.github.vaspyyy.guildmark.island.plan.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext

/**
 * A whole island, its box everything it reaches (its shallows and the slopes down to the sea bed too). It keeps only
 * its spec: every chunk it reaches draws its own share of the island's plan as the chunk generates, so the island is
 * never held as blocks. In a far-view mod's stand-in world, which asks for the whole box at once, it draws the lot.
 */
class IslandPiece : StructurePiece {
    val spec: IslandSpec

    constructor(spec: IslandSpec) : super(IslandRegistry.ISLAND_PIECE.get(), 0, boxOf(spec)) {
        this.spec = spec
    }

    constructor(tag: CompoundTag) : super(IslandRegistry.ISLAND_PIECE.get(), tag) {
        spec = IslandSpec(
            seed = tag.getLongOr("seed", 0L),
            cx = tag.getIntOr("cx", 0),
            cz = tag.getIntOr("cz", 0),
            sea = tag.getIntOr("sea", 63),
            kind = named(IslandKind.entries, tag.getStringOr("kind", ""), IslandKind.ISLE),
            climate = named(Climate.entries, tag.getStringOr("climate", ""), Climate.TEMPERATE),
            form = named(Form.entries, tag.getStringOr("form", ""), Form.BLOB),
            rock = named(Rock.entries, tag.getStringOr("rock", ""), Rock.GRANITE),
            radius = tag.getIntOr("radius", 20),
            name = tag.getIntOr("name", 0),
            version = tag.getIntOr("version", 1),
        )
    }

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putLong("seed", spec.seed)
        tag.putInt("cx", spec.cx)
        tag.putInt("cz", spec.cz)
        tag.putInt("sea", spec.sea)
        tag.putString("kind", spec.kind.name)
        tag.putString("climate", spec.climate.name)
        tag.putString("form", spec.form.name)
        tag.putString("rock", spec.rock.name)
        tag.putInt("radius", spec.radius)
        tag.putInt("name", spec.name)
        tag.putInt("version", spec.version)
    }

    /** The island's plan: the same one, shared, for every chunk that draws a share of it. */
    val plan: IslandPlan get() = IslandPlan.of(spec)

    override fun postProcess(
        level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource,
        chunkBB: BoundingBox, chunkPos: ChunkPos, referencePos: BlockPos,
    ) {
        // Only this island's own columns, whatever the caller's box
        val area = BoundingBox(
            maxOf(chunkBB.minX(), boundingBox.minX()), chunkBB.minY(), maxOf(chunkBB.minZ(), boundingBox.minZ()),
            minOf(chunkBB.maxX(), boundingBox.maxX()), chunkBB.maxY(), minOf(chunkBB.maxZ(), boundingBox.maxZ()),
        )
        if (area.minX() > area.maxX() || area.minZ() > area.maxZ()) return
        plan.draw(WorldIslandCanvas(level, area, random, spec.sea, boundingBox.minY()))
    }

    companion object {
        fun boxOf(spec: IslandSpec): BoundingBox {
            val reach = Terrain.reach(spec.radius)
            return BoundingBox(
                spec.cx - reach, spec.sea - Terrain.DEPTH, spec.cz - reach,
                spec.cx + reach, spec.sea + Terrain.HEADROOM, spec.cz + reach,
            )
        }

        private fun <T : Enum<T>> named(entries: List<T>, name: String, fallback: T): T = entries.firstOrNull { it.name == name } ?: fallback
    }
}
