package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.BoardPart
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Canvas
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Loot
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Spawn
import io.github.vaspyyy.guildmark.guild.GuildHall
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.townsfolk.Town
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.storage.loot.LootTable

/**
 * Draws a capital's plan into one generating chunk: turns the city's frame to the world, picks the
 * style's blocks, levels the land, fills chests from loot tables, and brings the townsfolk in.
 * Only [chunk] is written, so each chunk draws exactly its own share.
 */
class WorldCanvas(
    private val level: WorldGenLevel,
    private val piece: CapitalPiece,
    private val plan: CityPlan,
    private val chunk: BoundingBox,
    private val random: RandomSource,
) : Canvas {
    private val style = piece.style

    /**
     * A chunk really being generated (or the world itself, under /place), rather than a stand-in world some
     * far-view mods build structures in to draw them in the distance. Only a real one gets townsfolk, and
     * only there do joined-up blocks get fixed up afterwards.
     */
    private val real = level is WorldGenRegion || level is ServerLevel

    override val clip: Rect = run {
        val (ax, az) = piece.local(chunk.minX(), chunk.minZ())
        val (bx, bz) = piece.local(chunk.maxX(), chunk.maxZ())
        Rect(minOf(ax, bx), minOf(az, bz), maxOf(ax, bx), maxOf(az, bz))
    }

    override fun set(x: Int, y: Int, z: Int, b: B) {
        val pos = piece.world(x, y, z)
        if (!writable(pos)) return
        place(pos, style.state(b, hash(pos)).rotate(piece.turn))
    }

    override fun ground(x: Int, z: Int, surface: B, fill: B) {
        val base = piece.world(x, 0, z)
        if (!writable(base)) return
        clearAbove(base)
        place(base.below(), style.state(surface, hash(base)).rotate(piece.turn))
        // Solid under the paving down to the land, however deep the dip
        val under = style.state(fill, 0)
        val cursor = base.below(2).mutable()
        var depth = 0
        while (depth++ < MAX_FILL && cursor.y > level.minY && loose(level.getBlockState(cursor))) {
            place(cursor.immutable(), under)
            cursor.move(Direction.DOWN)
        }
    }

    override fun clearing(x: Int, z: Int) {
        val base = piece.world(x, 0, z)
        if (!writable(base)) return
        // The land's own top, under anything growing on it
        val cursor = BlockPos.MutableBlockPos(base.x, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, base.x, base.z) - 1, base.z)
        while (cursor.y > level.minY && loose(level.getBlockState(cursor))) cursor.move(Direction.DOWN)
        val land = level.getBlockState(cursor)
        val natural = land.`is`(BlockTags.DIRT) || land.`is`(BlockTags.SAND) || land.`is`(Blocks.SNOW_BLOCK)
        val top = if (natural) land.trySetValue(BlockStateProperties.SNOWY, false) else style.palette.ground.defaultBlockState()
        clearAbove(base)
        // A dip is filled up to the plaza's height; a rise was just cut down to it
        val subsoil = style.palette.subsoil.defaultBlockState()
        for (y in cursor.y + 1 until base.y - 1) place(BlockPos(base.x, y, base.z), subsoil)
        place(base.below(), top)
    }

    override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) {
        val pos = piece.world(x, y, z)
        if (!writable(pos)) return
        place(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, piece.direction(facing)))
        RandomizableContainer.setBlockEntityLootTable(level, random, pos, lootTable(loot))
    }

    override fun board(x: Int, y: Int, z: Int, facing: Dir) {
        val anchor = piece.world(x, y, z)
        val front = piece.direction(facing)
        val state = ModBlocks.QUEST_BOARD.get().defaultBlockState().setValue(QuestBoardBlock.FACING, front)
        for (part in BoardPart.entries) {
            val pos = QuestBoardBlock.cellPos(anchor, front, part)
            if (!writable(pos)) continue
            // Never marked for reshaping: each cell checks the others are there, and they may not be generated yet
            level.setBlock(pos, state.setValue(QuestBoardBlock.PART, part), Block.UPDATE_CLIENTS)
            if (part == BoardPart.ANCHOR) (level.getBlockEntity(pos) as? QuestBoardBlockEntity)?.foundCapital()
        }
    }

    override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) {
        val pos = piece.world(x, y, z)
        if (!real || !writable(pos)) return
        val server = level.level
        val mob: Mob = when (what) {
            Spawn.VILLAGER, Spawn.RECEPTIONIST -> EntityTypes.VILLAGER.create(server, EntitySpawnReason.STRUCTURE)
            Spawn.GOLEM -> EntityTypes.IRON_GOLEM.create(server, EntitySpawnReason.STRUCTURE)
            Spawn.HORSE -> EntityTypes.HORSE.create(server, EntitySpawnReason.STRUCTURE)
            Spawn.CAT -> EntityTypes.CAT.create(server, EntitySpawnReason.STRUCTURE)
        } ?: return
        val yaw = piece.direction(facing).toYRot()
        mob.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, 0.0f)
        mob.yHeadRot = yaw
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.STRUCTURE, null)
        mob.setPersistenceRequired()
        if (what == Spawn.RECEPTIONIST && mob is Villager) GuildHall.makeReceptionist(mob, level.registryAccess(), random, piece.boardAnchor)
        level.addFreshEntityWithPassengers(mob)
    }

    override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) {
        val pos = world(at)
        if (!real || !writable(pos)) return
        Townsfolk.spawn(
            level, pos, role(folk), work = work?.let(::world), home = home?.let(::world), post = post?.let(::world),
            facing = facing?.let(piece::direction), town = town,
        )
    }

    private fun world(at: At): BlockPos = piece.world(at.x, at.y, at.z)

    /** The capital as its townsfolk know it: the walls' square, with the bell as its meeting place. */
    private val town: Town by lazy { Town(piece.center, CityPlan.WALL_OUT, piece.bell) }

    private fun writable(pos: BlockPos): Boolean = chunk.isInside(pos) && !level.isOutsideBuildHeight(pos)

    private fun place(pos: BlockPos, state: BlockState) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS)
        if (!real) return
        val fluid = state.fluidState
        if (!fluid.isEmpty) level.scheduleTick(pos, fluid.type, 0)
        // Fences, walls, panes and stairs join up with their neighbours once the chunk is finished
        val block = state.block
        if (block is CrossCollisionBlock || block is WallBlock || block is StairBlock) level.getChunk(pos).markPosForPostProcessing(pos)
    }

    /** Everything standing in the column from standing height up comes away. */
    private fun clearAbove(base: BlockPos) {
        // The finished heightmap: the worldgen one still shows the bare land, under the trees that grew since
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, base.x, base.z)
        val cursor = base.mutable()
        val air = Blocks.AIR.defaultBlockState()
        while (cursor.y < top) {
            if (!level.getBlockState(cursor).isAir) level.setBlock(cursor, air, Block.UPDATE_CLIENTS)
            cursor.move(Direction.UP)
        }
    }

    private fun lootTable(loot: Loot): ResourceKey<LootTable> = ResourceKey.create(
        Registries.LOOT_TABLE,
        when (loot) {
            Loot.HOUSE -> Identifier.withDefaultNamespace("chests/village/" + style.palette.houseLoot)
            Loot.SMITH -> Identifier.withDefaultNamespace("chests/village/village_weaponsmith")
            Loot.TEMPLE -> Identifier.withDefaultNamespace("chests/village/village_temple")
            else -> Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "chests/capital/" + loot.name.lowercase())
        },
    )

    companion object {
        /** Deepest a dip under the city is filled; anything deeper is a cave, left as it is. */
        private const val MAX_FILL = 40

        private fun hash(pos: BlockPos): Int = (Mth.getSeed(pos.x, pos.y, pos.z) ushr 8).toInt()

        private fun role(folk: Folk): TownRole = when (folk) {
            Folk.CITIZEN -> TownRole.CITIZEN
            Folk.SMITH -> TownRole.SMITH
            Folk.BAKER -> TownRole.BAKER
            Folk.MERCHANT -> TownRole.MERCHANT
            Folk.GUARD -> TownRole.GUARD
            Folk.NOBLE -> TownRole.NOBLE
            Folk.INNKEEPER -> TownRole.INNKEEPER
            Folk.BARD -> TownRole.BARD
            Folk.PRIEST -> TownRole.PRIEST
            Folk.CHILD -> TownRole.CHILD
        }

        /** Air, water, plants and trees: what the ground under a city is filled through. */
        fun loose(state: BlockState): Boolean =
            state.canBeReplaced() || !state.fluidState.isEmpty || state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.LOGS)
    }
}
