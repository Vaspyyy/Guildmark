package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.island.plan.Bk
import io.github.vaspyyy.guildmark.island.plan.Dir
import io.github.vaspyyy.guildmark.island.plan.IslandCanvas
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.entity.BrushableBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Draws an island's plan into one generating chunk (or, from /guildmark island here, into the live world, or into a
 * far-view mod's stand-in world): turns its block names into the game's blocks, finds the sea bed it stands on, fills
 * chests from loot tables and brings in what lives there. Only the columns of [area] are written, and nothing under
 * [bottom], the deepest the island's foot may go; [sea] is the first block of air over the water.
 */
class WorldIslandCanvas(
    private val level: WorldGenLevel,
    private val area: BoundingBox,
    private val random: RandomSource,
    private val sea: Int,
    private val bottom: Int,
) : IslandCanvas {
    override val minX: Int = area.minX()
    override val maxX: Int = area.maxX()
    override val minZ: Int = area.minZ()
    override val maxZ: Int = area.maxZ()

    /** A chunk really being generated, or the live world; not a far-view stand-in, which gets no creatures. */
    private val real = WorldCanvas.isReal(level)

    /** Generating, the heightmaps are the generator's own; in the live world, the finished ones. */
    private val generating = level is WorldGenRegion
    private val cursor = BlockPos.MutableBlockPos()

    /** Drawn into the live world, the blocks that join up with their neighbours, joined once everything's in. */
    private val joins = ArrayList<BlockPos>()

    override fun floor(x: Int, z: Int): Int {
        // Down from the top of whatever's solid, through any ice the sea has piled up, to the sea bed
        var y = try {
            level.getHeight(if (generating) Heightmap.Types.OCEAN_FLOOR_WG else Heightmap.Types.OCEAN_FLOOR, x, z) - 1
        } catch (e: RuntimeException) {
            // A stand-in world keeping no heightmaps: down through the sea from its surface
            sea - 1
        }
        val lowest = maxOf(bottom, level.minY)
        while (y > lowest) {
            val state = level.getBlockState(cursor.set(x, y, z))
            if (state.`is`(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP) && !state.`is`(BlockTags.ICE) && !state.`is`(Blocks.SNOW_BLOCK)) break
            y--
        }
        return y
    }

    override fun set(x: Int, y: Int, z: Int, b: Bk) {
        if (!contains(x, z)) return
        val pos = BlockPos(x, y, z)
        if (level.isOutsideBuildHeight(pos)) return
        place(pos, state(b))
    }

    override fun open(x: Int, y: Int, z: Int): Boolean {
        val state = level.getBlockState(cursor.set(x, y, z))
        return state.isAir || state.canBeReplaced()
    }

    override fun clearAbove(x: Int, z: Int, y: Int) {
        val top = try {
            level.getHeight(if (generating) Heightmap.Types.WORLD_SURFACE_WG else Heightmap.Types.WORLD_SURFACE, x, z)
        } catch (e: RuntimeException) {
            return
        }
        val air = Blocks.AIR.defaultBlockState()
        for (h in y until top) {
            val state = level.getBlockState(cursor.set(x, h, z))
            if (state.`is`(BlockTags.ICE) || state.`is`(Blocks.SNOW_BLOCK) || state.`is`(Blocks.SNOW)) level.setBlock(BlockPos(x, h, z), air, Block.UPDATE_CLIENTS)
        }
    }

    override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: String, barrel: Boolean) {
        if (!contains(x, z)) return
        val pos = BlockPos(x, y, z)
        if (level.isOutsideBuildHeight(pos)) return
        val wet = level.getBlockState(pos).fluidState.isSource
        val state = if (barrel) {
            Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, direction(facing))
        } else {
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, direction(facing)).setValue(BlockStateProperties.WATERLOGGED, wet)
        }
        level.setBlock(pos, state, Block.UPDATE_CLIENTS)
        val table = Identifier.tryParse(loot) ?: return
        RandomizableContainer.setBlockEntityLootTable(level, random, pos, ResourceKey.create(Registries.LOOT_TABLE, table))
    }

    override fun loot(x: Int, y: Int, z: Int, table: String) {
        if (!contains(x, z)) return
        val id = Identifier.tryParse(table) ?: return
        val key = ResourceKey.create(Registries.LOOT_TABLE, id)
        when (val holder = level.getBlockEntity(BlockPos(x, y, z))) {
            is RandomizableContainer -> holder.setLootTable(key, random.nextLong())
            is BrushableBlockEntity -> holder.setLootTable(key, random.nextLong())
        }
    }

    override fun spawn(what: String, x: Double, y: Double, z: Double, yaw: Float) {
        if (!real || !contains(floor(x).toInt(), floor(z).toInt())) return
        // "type#role": one of the game's creatures standing in for someone of ours, named for what they are (a smuggler)
        val id = Identifier.tryParse(what.substringBefore('#')) ?: return
        val role = what.substringAfter('#', "")
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return
        val entity = type.create(level.level, EntitySpawnReason.STRUCTURE) ?: return
        entity.snapTo(x, y, z, yaw, 0f)
        if (role.isNotEmpty()) entity.customName = Component.translatable("entity.${Guildmark.MOD_ID}.$role")
        if (entity is Mob) {
            entity.yHeadRot = yaw
            entity.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), EntitySpawnReason.STRUCTURE, null)
            entity.setPersistenceRequired()
        }
        level.addFreshEntityWithPassengers(entity)
    }

    private fun place(pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS)
        if (!real) return
        val fluid = state.fluidState
        if (!fluid.isEmpty) level.scheduleTick(pos, fluid.type, 0)
        // Fences, walls and stairs join up with their neighbours once the chunk is finished (or, in the live world,
        // once the whole island is in: see finish)
        val block = state.block
        if (block is CrossCollisionBlock || block is WallBlock || block is StairBlock) {
            if (generating) level.getChunk(pos).markPosForPostProcessing(pos) else joins.add(pos)
        }
    }

    /** Drawn into the live world, joins up the fences, walls and stairs with whatever they now stand by. */
    fun finish() {
        for (pos in joins) {
            val state = level.getBlockState(pos)
            Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, pos), level, pos, Block.UPDATE_CLIENTS)
        }
        joins.clear()
    }

    companion object {
        /** Every block name an island uses, turned into the game's block once. */
        private val STATES = ConcurrentHashMap<Bk, BlockState>()

        fun state(b: Bk): BlockState = STATES.computeIfAbsent(b) { parse(it) }

        private fun parse(b: Bk): BlockState = try {
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, b.key, false).blockState()
        } catch (e: Exception) {
            Guildmark.LOGGER.warn("Islands: no such block as {}", b.key)
            Blocks.AIR.defaultBlockState()
        }

        fun direction(dir: Dir): Direction = when (dir) {
            Dir.N -> Direction.NORTH
            Dir.E -> Direction.EAST
            Dir.S -> Direction.SOUTH
            Dir.W -> Direction.WEST
        }
    }
}
