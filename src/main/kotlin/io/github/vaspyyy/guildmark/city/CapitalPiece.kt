package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.city.plan.CityBuilder
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Use
import io.github.vaspyyy.guildmark.registry.ModStructures
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
import net.neoforged.neoforge.common.world.PieceBeardifierModifier
import kotlin.math.abs

/**
 * A whole capital as one piece: where the middle of its plaza is (at standing height), which way it's
 * turned, its style and the seed its plan grows from. Every chunk the city covers draws its own share
 * of the plan as that chunk generates, so the city is never held in memory as blocks.
 */
class CapitalPiece : StructurePiece, PieceBeardifierModifier {
    val center: BlockPos
    val turn: Rotation
    val style: CityStyle
    val seed: Long

    constructor(center: BlockPos, turn: Rotation, style: CityStyle, seed: Long) :
        super(ModStructures.CAPITAL_PIECE.get(), 0, footprint(center)) {
        this.center = center
        this.turn = turn
        this.style = style
        this.seed = seed
    }

    constructor(tag: CompoundTag) : super(ModStructures.CAPITAL_PIECE.get(), tag) {
        center = BlockPos(tag.getIntOr("cx", 0), tag.getIntOr("cy", 64), tag.getIntOr("cz", 0))
        turn = Rotation.entries.getOrElse(tag.getIntOr("rotation", 0)) { Rotation.NONE }
        style = CityStyle.entries.getOrElse(tag.getIntOr("style", 0)) { CityStyle.TEMPERATE }
        seed = tag.getLongOr("seed", 0L)
    }

    val plan: CityPlan get() = plan(seed)

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putInt("cx", center.x)
        tag.putInt("cy", center.y)
        tag.putInt("cz", center.z)
        tag.putInt("rotation", turn.ordinal)
        tag.putInt("style", style.ordinal)
        tag.putLong("seed", seed)
    }

    override fun postProcess(
        level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource,
        chunkBB: BoundingBox, chunkPos: ChunkPos, referencePos: BlockPos,
    ) {
        val plan = plan
        CityBuilder(plan, WorldCanvas(level, this, plan, chunkBB, random)).draw()
    }

    /**
     * Before anything grows in a chunk: a course of stone just over the land wherever the city will stand,
     * so no tree or plant takes root there. The city is drawn after the trees (so it can clear the ones
     * already standing), and a tree that grew afterwards in a chunk next door would be cut in half by its
     * share of the city, its crown left hanging. The city's ground replaces the course, or buries it.
     */
    fun groundwork(level: WorldGenLevel, chunk: ChunkPos) {
        val grid = plan.grid
        val course = Blocks.STONE.defaultBlockState()
        val cursor = BlockPos.MutableBlockPos()
        for (wx in chunk.minBlockX..chunk.maxBlockX) for (wz in chunk.minBlockZ..chunk.maxBlockZ) {
            val (x, z) = local(wx, wz)
            if (grid[x, z] == Use.OUTSIDE) continue
            // Down to the land through anything loose; nothing roots under water, so a pond is left be
            var y = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1
            var wet = false
            while (y > level.minY) {
                val state = level.getBlockState(cursor.set(wx, y, wz))
                if (!WorldCanvas.loose(state)) break
                if (!state.fluidState.isEmpty) wet = true
                y--
            }
            if (!wet) level.setBlock(cursor.set(wx, y + 1, wz), course, Block.UPDATE_CLIENTS)
        }
    }

    // The land is drawn down (or up) to the plaza's height out to the edge of the clearing round the walls
    override fun getBeardifierBox(): BoundingBox = BoundingBox(
        center.x - CityPlan.CLEAR, center.y, center.z - CityPlan.CLEAR,
        center.x + CityPlan.CLEAR, center.y + BEARD_HEIGHT, center.z + CityPlan.CLEAR,
    )

    override fun getTerrainAdjustment(): TerrainAdjustment = TerrainAdjustment.BEARD_BOX

    override fun getGroundLevelDelta(): Int = 0

    /** Where a spot in the city's own frame (x east, z south, the plaza's middle at 0, 0, 0) is in the world. */
    fun world(x: Int, y: Int, z: Int): BlockPos = when (turn) {
        Rotation.NONE -> BlockPos(center.x + x, center.y + y, center.z + z)
        Rotation.CLOCKWISE_90 -> BlockPos(center.x - z, center.y + y, center.z + x)
        Rotation.CLOCKWISE_180 -> BlockPos(center.x - x, center.y + y, center.z - z)
        Rotation.COUNTERCLOCKWISE_90 -> BlockPos(center.x + z, center.y + y, center.z - x)
    }

    /** The city's own x, z for a world column. */
    fun local(worldX: Int, worldZ: Int): Pair<Int, Int> {
        val dx = worldX - center.x
        val dz = worldZ - center.z
        return when (turn) {
            Rotation.NONE -> Pair(dx, dz)
            Rotation.CLOCKWISE_90 -> Pair(dz, -dx)
            Rotation.CLOCKWISE_180 -> Pair(-dx, -dz)
            Rotation.COUNTERCLOCKWISE_90 -> Pair(-dz, dx)
        }
    }

    /** A direction in the city's frame, turned to the world's. */
    fun direction(dir: Dir): Direction = turn.rotate(CityStyle.direction(dir))

    /** Is this column inside the city's walls? */
    fun within(x: Int, z: Int): Boolean = abs(x - center.x) <= CityPlan.WALL_OUT && abs(z - center.z) <= CityPlan.WALL_OUT

    /** The quest board's anchor cell. */
    val boardAnchor: BlockPos get() = plan.board.let { world(it.x, plan.level(it.x, it.z), it.z) }

    /** Where the city's bell hangs: the middle of the city as far as villagers (and roads) go. */
    val bell: BlockPos get() = plan.bell.let { world(it.x, plan.level(it.x, it.z), it.z) }

    companion object {
        /** How far above the plaza the land is drawn down. */
        private const val BEARD_HEIGHT = 12
        /** Room under the plaza for foundations, and over it for the towers' spires. */
        private const val BELOW = 20
        private const val ABOVE = 48

        fun footprint(center: BlockPos): BoundingBox = BoundingBox(
            center.x - CityPlan.REACH, center.y - BELOW, center.z - CityPlan.REACH,
            center.x + CityPlan.REACH, center.y + ABOVE, center.z + CityPlan.REACH,
        )

        // Every chunk of a city draws from the same plan; laying one out takes a moment, so the last few are kept
        private val PLANS = object : LinkedHashMap<Long, CityPlan>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CityPlan>?): Boolean = size > 4
        }

        fun plan(seed: Long): CityPlan {
            synchronized(PLANS) { PLANS[seed]?.let { return it } }
            val plan = CityPlan(seed)
            synchronized(PLANS) { PLANS[seed] = plan }
            return plan
        }
    }
}
