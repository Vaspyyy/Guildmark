package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.city.plan.CityBuilder
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Layout
import io.github.vaspyyy.guildmark.city.plan.Tier
import io.github.vaspyyy.guildmark.city.plan.Use
import io.github.vaspyyy.guildmark.registry.ModStructures
import io.github.vaspyyy.guildmark.townsfolk.Town
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
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
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * One cell of a capital (see [CapitalPlacement]), its box the columns of the city it draws: where the city's
 * middle is (the plaza's, at the lower town's standing height), which way it's turned, its style, the seed its
 * plan grows from and how it's laid out. Every chunk draws its own share of the plan as it generates, so the
 * city is never held in memory as blocks.
 *
 * A [proxy] is a copy of a cell kept by the capital's centre start, for far-view mods that build only the
 * starts /locate would find, in a stand-in world of their own, to show land in the distance. A proxy draws only
 * there and a real piece only in the real world, so each cell is drawn once either way.
 */
class CapitalPiece : StructurePiece, PieceBeardifierModifier {
    val center: BlockPos
    val turn: Rotation
    val style: CityStyle
    val seed: Long

    /** How the city is laid out; null for a piece saved by an older version of the mod, which draws nothing. */
    val layout: Layout?
    val proxy: Boolean

    /** The land the plan is laid over: a new piece knows it from the start, a saved one from its world. */
    @Volatile
    private var land: GeneratedLand? = null

    constructor(founding: Founding, box: BoundingBox, proxy: Boolean, land: GeneratedLand) :
        super(ModStructures.CAPITAL_PIECE.get(), 0, box) {
        center = founding.center
        turn = founding.turn
        style = founding.style
        seed = founding.seed
        layout = founding.layout
        this.proxy = proxy
        this.land = land
    }

    constructor(tag: CompoundTag) : super(ModStructures.CAPITAL_PIECE.get(), tag) {
        center = BlockPos(tag.getIntOr("cx", 0), tag.getIntOr("cy", 64), tag.getIntOr("cz", 0))
        turn = Rotation.entries.getOrElse(tag.getIntOr("rotation", 0)) { Rotation.NONE }
        style = CityStyle.entries.getOrElse(tag.getIntOr("style", 0)) { CityStyle.TEMPERATE }
        seed = tag.getLongOr("seed", 0L)
        val crowded = tag.getIntOr("crowded", 0)
        layout = Tier.entries.getOrNull(tag.getIntOr("tier", -1))?.let { tier ->
            Layout(
                tier, tag.getIntOr("upper", 0), tag.getIntOr("citadel", 0), Dir.entries.getOrNull(tag.getIntOr("harbour", -1)),
                Dir.entries.filter { crowded and (1 shl it.ordinal) != 0 }.toSet(),
                // Saved before the plans had versions: the first
                tag.getIntOr("version", 0),
            )
        }
        proxy = tag.getBooleanOr("proxy", false)
    }

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putInt("cx", center.x)
        tag.putInt("cy", center.y)
        tag.putInt("cz", center.z)
        tag.putInt("rotation", turn.ordinal)
        tag.putInt("style", style.ordinal)
        tag.putLong("seed", seed)
        val layout = layout ?: return
        tag.putInt("tier", layout.tier.ordinal)
        tag.putInt("upper", layout.upper)
        tag.putInt("citadel", layout.citadel)
        tag.putInt("harbour", layout.harbour?.ordinal ?: -1)
        tag.putInt("crowded", layout.crowded.sumOf { 1 shl it.ordinal })
        tag.putInt("version", layout.version)
        if (proxy) tag.putBoolean("proxy", true)
    }

    /** The city's plan, laid over the land of the world this piece was found in (see [inWorld]). */
    val plan: CityPlan get() = plan(land ?: throw IllegalStateException("The capital at $center was asked for its plan before its world"))

    /** Tells a piece read from a save which world's land its plan is laid over. */
    fun inWorld(level: ServerLevel): CapitalPiece {
        if (land == null) land = GeneratedLand.of(level)
        return this
    }

    private fun plan(land: GeneratedLand): CityPlan {
        val layout = layout ?: throw IllegalStateException("A capital saved by an older version has no plan")
        val key = PlanKey(seed, center, turn, layout.tier, layout.upper, layout.citadel, layout.harbour, layout.crowded, layout.version)
        val held = synchronized(PLANS) {
            PLANS.getOrPut(key) {
                // One gone from the last few but still held elsewhere (a town's places keep theirs) is taken back, not laid out again
                KEPT[key]?.get()?.let { lazyOf(it) } ?: lazy { CityPlan(seed, layout, CapitalLand(land, center, turn, layout.tier.reach)).also { kept(key, it) } }
            }
        }
        return held.value
    }

    /**
     * The plan when drawing into [level], learning the land from it if this piece came from a save. Null where
     * that can't be done: a far-view mod's stand-in world that won't say which world it stands in for.
     */
    private fun plan(level: WorldGenLevel): CityPlan? {
        if (land == null) {
            val server = try {
                level.level as ServerLevel?
            } catch (e: RuntimeException) {
                null
            }
            inWorld(server ?: return null)
        }
        return plan
    }

    override fun postProcess(
        level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource,
        chunkBB: BoundingBox, chunkPos: ChunkPos, referencePos: BlockPos,
    ) {
        if (layout == null || WorldCanvas.isReal(level) == proxy) return
        // Only this piece's own cell, whatever the caller's box: a stand-in world passes the piece's whole box
        val area = BoundingBox(
            maxOf(chunkBB.minX(), boundingBox.minX()), chunkBB.minY(), maxOf(chunkBB.minZ(), boundingBox.minZ()),
            minOf(chunkBB.maxX(), boundingBox.maxX()), chunkBB.maxY(), minOf(chunkBB.maxZ(), boundingBox.maxZ()),
        )
        if (area.minX() > area.maxX() || area.minZ() > area.maxZ()) return
        val plan = plan(level) ?: return
        CityBuilder(plan, WorldCanvas(level, this, plan, area, random)).draw()
    }

    /**
     * Before anything grows in a chunk: a course of stone just over the land wherever the city lays its own
     * ground, so no tree or plant takes root there. The city is drawn after the trees (so it can clear the ones
     * already standing), and a tree that grew afterwards in a chunk next door would be cut in half by its share
     * of the city, its crown left hanging. The city's ground replaces the course, or buries it. Only a cell's
     * real piece lays it, on its own columns, so no column gets two courses.
     */
    fun groundwork(level: WorldGenLevel, chunk: ChunkPos) {
        if (layout == null || proxy) return
        val grid = (plan(level) ?: return).grid
        val course = Blocks.STONE.defaultBlockState()
        val cursor = BlockPos.MutableBlockPos()
        for (wx in maxOf(chunk.minBlockX, boundingBox.minX())..minOf(chunk.maxBlockX, boundingBox.maxX())) {
            for (wz in maxOf(chunk.minBlockZ, boundingBox.minZ())..minOf(chunk.maxBlockZ, boundingBox.maxZ())) {
                val (x, z) = local(wx, wz)
                if (!grid[x, z].laid) continue
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
    }

    // A cell shapes no land itself: its terraces' own pieces (CapitalBeard) do, and the builder grades the rest
    override fun getBeardifierBox(): BoundingBox = boundingBox

    override fun getTerrainAdjustment(): TerrainAdjustment = TerrainAdjustment.NONE

    override fun getGroundLevelDelta(): Int = 0

    /** Where a spot in the city's own frame (x east, z south, the plaza's middle at 0, 0, 0) is in the world. */
    fun world(x: Int, y: Int, z: Int): BlockPos = toWorld(center, turn, x, y, z)

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

    /** Is this world column inside the city's outer walls? */
    fun within(x: Int, z: Int): Boolean {
        if (!reaches(x, z)) return false
        val (lx, lz) = local(x, z)
        return plan.within(lx, lz)
    }

    /** Is this world column any of the city's ground, its farms included? */
    fun reaches(x: Int, z: Int): Boolean {
        val layout = layout ?: return false
        return maxOf(abs(x - center.x), abs(z - center.z)) <= layout.tier.reach
    }

    /** Is this world column part of the town itself: inside the outer walls, or on the harbour below them? */
    fun inTown(x: Int, z: Int): Boolean {
        if (!reaches(x, z)) return false
        val (lx, lz) = local(x, z)
        val plan = plan
        return plan.within(lx, lz) || plan.grid[lx, lz].let { it == Use.QUAY || it == Use.PIER || it == Use.MOORING }
    }

    /** The capital as its townsfolk know it (see [town]). */
    val town: Town get() = town(plan)

    /**
     * The capital as its townsfolk know it: the square of its outer walls, the bell as its meeting place, and the
     * quays and piers of its harbour, if it has one.
     */
    fun town(plan: CityPlan): Town {
        val harbour = plan.harbour?.area?.let { r ->
            val a = world(r.x0, 0, r.z0)
            val b = world(r.x1, 0, r.z1)
            BoundingBox(minOf(a.x, b.x), 0, minOf(a.z, b.z), maxOf(a.x, b.x), 0, maxOf(a.z, b.z))
        }
        return Town(center, plan.outer, standing(plan, plan.bell), harbour)
    }

    /** The quest board's anchor cell. */
    val boardAnchor: BlockPos get() = boardAnchor(plan)

    fun boardAnchor(plan: CityPlan): BlockPos = standing(plan, plan.board)

    /** Where the city's bell hangs: the middle of the city as far as villagers (and roads) go. */
    val bell: BlockPos get() = plan.let { standing(it, it.bell) }

    /** A column of the plan in the world, at the city's standing height there. */
    fun standing(plan: CityPlan, at: CityPlan.Pos): BlockPos = world(at.x, plan.level(at.x, at.z), at.z)

    /** What a plan is laid out from: every piece of a city has the same, and they share the plan. */
    private data class PlanKey(
        val seed: Long, val center: BlockPos, val turn: Rotation, val tier: Tier, val upper: Int, val citadel: Int,
        val harbour: Dir?, val crowded: Set<Dir>, val version: Int,
    )

    companion object {
        /** Where a spot in a city's own frame lies in the world, for a city with its middle at [center] turned [turn]. */
        fun toWorld(center: BlockPos, turn: Rotation, x: Int, y: Int, z: Int): BlockPos = when (turn) {
            Rotation.NONE -> BlockPos(center.x + x, center.y + y, center.z + z)
            Rotation.CLOCKWISE_90 -> BlockPos(center.x - z, center.y + y, center.z + x)
            Rotation.CLOCKWISE_180 -> BlockPos(center.x - x, center.y + y, center.z - z)
            Rotation.COUNTERCLOCKWISE_90 -> BlockPos(center.x + z, center.y + y, center.z - x)
        }

        // Every chunk of a city draws from the same plan. Laying one out takes a while, so each is laid out once
        // (any other thread wanting it meanwhile waits for it) and the last few are kept
        private val PLANS = object : LinkedHashMap<PlanKey, Lazy<CityPlan>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlanKey, Lazy<CityPlan>>?): Boolean = size > 8
        }

        // Every plan laid out, held weakly (it lasts only while something else keeps it), so one dropped from the last
        // few is found again while it's still about. Guarded by PLANS
        private val KEPT = HashMap<PlanKey, WeakReference<CityPlan>>()

        private fun kept(key: PlanKey, plan: CityPlan) = synchronized(PLANS) {
            KEPT.values.removeIf { it.get() == null }
            KEPT[key] = WeakReference(plan)
        }
    }
}

/**
 * One of a capital's terraces, within one cell: the terrain generator cuts the land down to its floor and
 * builds it up under it, before anything grows, so the city's ground stands on solid land of its own. It
 * draws nothing. The pieces of neighbouring cells stop short of the seam between them, since two boxes side
 * by side would level the land along it twice over.
 */
class CapitalBeard : StructurePiece, PieceBeardifierModifier {
    constructor(box: BoundingBox) : super(ModStructures.CAPITAL_BEARD.get(), 0, box)

    constructor(tag: CompoundTag) : super(ModStructures.CAPITAL_BEARD.get(), tag)

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {}

    override fun postProcess(
        level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource,
        chunkBB: BoundingBox, chunkPos: ChunkPos, referencePos: BlockPos,
    ) {}

    override fun getBeardifierBox(): BoundingBox = boundingBox

    override fun getTerrainAdjustment(): TerrainAdjustment = TerrainAdjustment.BEARD_BOX

    override fun getGroundLevelDelta(): Int = 0
}
