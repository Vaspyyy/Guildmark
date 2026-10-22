package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.block.NoticeBoardBlock
import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.lamps.StreetLampBlock
import io.github.vaspyyy.guildmark.registry.ModBlocks
import io.github.vaspyyy.guildmark.registry.ModVillagers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.animal.cow.MushroomCow
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.StairsShape
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import java.util.IdentityHashMap

/** What a point of interest is to the census: a bed, the bell, the Guildmaster's board, or a profession's job block. */
enum class PoiKind { BED, BELL, BOARD, JOB, NONE }

/** A point of interest: where it is (a `BlockPos.asLong()`) and what it is. */
class Poi(val pos: Long, val kind: PoiKind)

/** A farm animal in the village's box: a [hen] or a beast of the herds (cow, sheep, pig), and where it stands. */
class Beast(val hen: Boolean, val x: Double, val y: Double, val z: Double)

/** What a board looks like to the census: the way its face looks, and how many advances it has built. */
class BoardLook(val facing: Direction, val advances: Int)

/**
 * What the census reads of the world, and the only way it does. Every read answers null (or [UNLOADED]) where its chunk is not
 * loaded: nothing here loads a chunk, makes one or waits for one, so a census can't stall the game, whatever is near and
 * whatever is not. The world gives [LevelTerrain]; a test makes up one of its own.
 */
interface Terrain {
    val minY: Int
    val maxY: Int

    /** How many blocks have been read through this, and how many asks found their chunk not loaded (for the readout of what a census cost). */
    val reads: Long
    val misses: Long

    fun loaded(cx: Int, cz: Int): Boolean

    /** The block at [x], [y], [z], or null where its chunk isn't loaded. Out of the world's height it is air. */
    fun state(x: Int, y: Int, z: Int): BlockState?

    /** The first free height over the highest block at [x], [z] that stops a body, leaves apart (the height map `MOTION_BLOCKING_NO_LEAVES`), or [UNLOADED]. */
    fun top(x: Int, z: Int): Int

    /** Adds the points of interest the loaded chunk [cx], [cz] holds (nothing for one that isn't loaded). */
    fun pois(cx: Int, cz: Int, into: MutableList<Poi>)

    /** The board whose anchor cell is at [pos] (a `BlockPos.asLong()`): its way and its advances, or null if it isn't a board (or isn't loaded). */
    fun board(pos: Long): BoardLook?

    /** Adds the farm animals standing within the square and 24 blocks up and down of [y] (only those in loaded chunks). */
    fun beasts(minX: Int, minZ: Int, maxX: Int, maxZ: Int, y: Int, into: MutableList<Beast>)

    companion object {
        const val UNLOADED = Int.MIN_VALUE
    }
}

/** Where the mod's own blocks come into the census, a seam so that a made-up world can stand in for them. */
interface Mods {
    /** An Archer Post, which stands on a tower's or a keep's roof. */
    fun archerPost(s: BlockState): Boolean

    /** A street lamp, which a plain lantern becomes when its village's lamps are taken in hand. */
    fun streetLamp(s: BlockState): Boolean
}

/** The mod's own blocks. */
object GuildmarkMods : Mods {
    override fun archerPost(s: BlockState): Boolean = s.`is`(ModBlocks.ARCHER_POST.get())
    override fun streetLamp(s: BlockState): Boolean = s.block is StreetLampBlock
}

/**
 * What a block is to the census, worked out once for each kind of block state and kept: the bits below, and in the upper bits
 * the thing it is besides (see [feature]). Every read of the census asks this and nothing else of the block.
 */
object Cls {
    /** A body fits in it: air, or something too low or too thin to stop one (a carpet, a plant, an open gate), and no fluid. */
    const val PASS = 1 shl 0

    /** Nothing in it at all: air, or no collision at all, and no fluid (what `Places.free` asks of a seat's headroom). */
    const val FREE = 1 shl 1

    /** A top to stand on, at least 0.8 high (what `Places.floor` asks). */
    const val FLOOR = 1 shl 2
    const val DOOR = 1 shl 3
    const val BED_HEAD = 1 shl 4
    const val LADDER = 1 shl 5

    /** A bottom, straight, dry stair: a seat, if it has room. */
    const val SEAT = 1 shl 6

    /** A fence, a fence gate or a wall. */
    const val POST = 1 shl 7

    /** A lantern standing. */
    const val LANTERN = 1 shl 8

    /** A lantern hanging. */
    const val HANGING = 1 shl 9
    const val WOOL = 1 shl 10
    const val FARMLAND = 1 shl 11
    const val CROP = 1 shl 12

    /** A source of water, in a block of its own. */
    const val WATER = 1 shl 13

    /** A whole block of stone, brick or the like (what rings a well). */
    const val STONE = 1 shl 14

    /** The lower half of a door. */
    const val DOOR_LOWER = 1 shl 15

    /** One of the mod's own street lamps. */
    const val LAMP_BLOCK = 1 shl 16

    /** The upper bits say what a block is besides (see [feature]): a table, a store, an Archer Post, a polished andesite counter, or a work block. */
    const val FEATURE_SHIFT = 20
    const val F_TABLE = 1
    const val F_STORE = 2
    const val F_POST = 3
    const val F_ANDESITE = 4
    const val F_STATION = 16

    /** The work blocks a building lists as stations, by the code [F_STATION] plus their place here. */
    val STATIONS: List<M> = listOf(
        M.ANVIL, M.GRINDSTONE, M.SMITHING_TABLE, M.STONECUTTER, M.CARTOGRAPHY_TABLE, M.FLETCHING_TABLE, M.LOOM, M.CAULDRON,
        M.BREWING_STAND, M.COMPOSTER, M.LECTERN, M.FURNACE, M.SMOKER, M.BLAST_FURNACE, M.HAY, M.BOOKSHELF,
    )

    /** The mod's own blocks; set before the first read, by a test that has none. */
    var mods: Mods = GuildmarkMods

    private val cache = IdentityHashMap<BlockState, Int>(512)

    fun of(s: BlockState): Int {
        val known = cache[s]
        if (known != null) return known
        val made = compute(s)
        cache[s] = made
        return made
    }

    /** What [f] says a block besides is (0 for nothing). */
    fun feature(f: Int): Int = f ushr FEATURE_SHIFT

    /** The work block [f] is, if it is one. */
    fun station(f: Int): M? = (feature(f) - F_STATION).let { if (it >= 0 && it < STATIONS.size) STATIONS[it] else null }

    /** What a stall's counter is made of, if [s] is a job block that goes on one. */
    fun counter(s: BlockState): M? = when {
        s.`is`(Blocks.SMOKER) -> M.SMOKER
        s.`is`(Blocks.FLETCHING_TABLE) -> M.FLETCHING_TABLE
        s.`is`(Blocks.LOOM) -> M.LOOM
        s.`is`(Blocks.CARTOGRAPHY_TABLE) -> M.CARTOGRAPHY_TABLE
        s.`is`(Blocks.BARREL) -> M.BARREL
        s.`is`(Blocks.COMPOSTER) -> M.COMPOSTER
        s.`is`(Blocks.SMITHING_TABLE) -> M.SMITHING_TABLE
        s.`is`(Blocks.STONECUTTER) -> M.STONECUTTER
        s.`is`(Blocks.GRINDSTONE) -> M.GRINDSTONE
        s.`is`(Blocks.BREWING_STAND) -> M.BREWING_STAND
        s.block is AbstractCauldronBlock -> M.CAULDRON
        else -> null
    }

    private fun station(m: M): Int = (F_STATION + STATIONS.indexOf(m)) shl FEATURE_SHIFT

    private fun compute(s: BlockState): Int {
        var f = 0
        val air = s.isAir
        val wet = !s.fluidState.isEmpty
        val shape = if (air) Shapes.empty() else s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        val empty = shape.isEmpty
        val high = if (empty) 0.0 else shape.max(Direction.Axis.Y)
        if (!wet && (air || empty || high <= 0.2)) f = f or PASS
        if (!wet && (air || empty)) f = f or FREE
        if (!air && !(wet && !s.hasProperty(BlockStateProperties.WATERLOGGED)) && !empty && high >= 0.8) f = f or FLOOR
        val block = s.block
        if (s.`is`(BlockTags.DOORS)) {
            f = f or DOOR
            if (block is DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) f = f or DOOR_LOWER
        }
        if (s.`is`(BlockTags.BEDS) && s.hasProperty(AbstractBedBlock.PART) && s.getValue(AbstractBedBlock.PART) == BedPart.HEAD) f = f or BED_HEAD
        if (s.`is`(Blocks.LADDER)) f = f or LADDER or PASS
        if (block is StairBlock && s.`is`(BlockTags.STAIRS) && s.getValue(StairBlock.HALF) == Half.BOTTOM &&
            s.getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT && !s.getValue(StairBlock.WATERLOGGED)
        ) f = f or SEAT
        if (s.`is`(BlockTags.FENCES) || s.`is`(BlockTags.FENCE_GATES) || s.`is`(BlockTags.WALLS)) f = f or POST
        if (block is LanternBlock) f = f or (if (s.getValue(BlockStateProperties.HANGING)) HANGING else LANTERN)
        if (mods.streetLamp(s)) f = f or LAMP_BLOCK
        if (s.`is`(BlockTags.WOOL)) f = f or WOOL
        if (s.`is`(Blocks.FARMLAND)) f = f or FARMLAND
        if (s.`is`(BlockTags.CROPS)) f = f or CROP
        if (s.`is`(Blocks.WATER) && s.fluidState.isSource) f = f or WATER
        if (!empty && high >= 1.0 && stoneName(block)) f = f or STONE
        f = f or when {
            s.`is`(Blocks.CRAFTING_TABLE) -> F_TABLE shl FEATURE_SHIFT
            s.`is`(Blocks.BARREL) || s.`is`(Blocks.CHEST) || s.`is`(Blocks.TRAPPED_CHEST) -> F_STORE shl FEATURE_SHIFT
            mods.archerPost(s) -> F_POST shl FEATURE_SHIFT
            s.`is`(Blocks.POLISHED_ANDESITE) -> F_ANDESITE shl FEATURE_SHIFT
            s.`is`(BlockTags.ANVIL) -> station(M.ANVIL)
            s.`is`(Blocks.GRINDSTONE) -> station(M.GRINDSTONE)
            s.`is`(Blocks.SMITHING_TABLE) -> station(M.SMITHING_TABLE)
            s.`is`(Blocks.STONECUTTER) -> station(M.STONECUTTER)
            s.`is`(Blocks.CARTOGRAPHY_TABLE) -> station(M.CARTOGRAPHY_TABLE)
            s.`is`(Blocks.FLETCHING_TABLE) -> station(M.FLETCHING_TABLE)
            s.`is`(Blocks.LOOM) -> station(M.LOOM)
            block is AbstractCauldronBlock -> station(M.CAULDRON)
            s.`is`(Blocks.BREWING_STAND) -> station(M.BREWING_STAND)
            s.`is`(Blocks.COMPOSTER) -> station(M.COMPOSTER)
            s.`is`(Blocks.LECTERN) -> station(M.LECTERN)
            s.`is`(Blocks.FURNACE) -> station(M.FURNACE)
            s.`is`(Blocks.SMOKER) -> station(M.SMOKER)
            s.`is`(Blocks.BLAST_FURNACE) -> station(M.BLAST_FURNACE)
            s.`is`(Blocks.HAY_BLOCK) -> station(M.HAY)
            s.`is`(Blocks.BOOKSHELF) -> station(M.BOOKSHELF)
            else -> 0
        }
        return f
    }

    private val STONES = listOf("stone", "brick", "cobble", "andesite", "diorite", "granite", "deepslate", "tuff", "basalt", "blackstone", "quartz", "prismarine")

    /** Whether the block is of stone by its name: what a well's rim is made of. */
    private fun stoneName(block: Block): Boolean {
        val path = BuiltInRegistries.BLOCK.getKey(block).path
        return STONES.any { path.contains(it) }
    }
}

/** What each kind of point of interest is, remembered by its type (a few dozen in all). */
internal object PoiKinds {
    private val known = HashMap<Holder<PoiType>, PoiKind>()

    fun of(h: Holder<PoiType>): PoiKind = known.getOrPut(h) {
        when {
            h.`is`(PoiTypes.HOME) -> PoiKind.BED
            h.`is`(PoiTypes.MEETING) -> PoiKind.BELL
            h.`is`(ModVillagers.GUILDMASTER_POI.key!!) -> PoiKind.BOARD
            VillagerProfession.ALL_ACQUIRABLE_JOBS.test(h) -> PoiKind.JOB
            else -> PoiKind.NONE
        }
    }

    /** Forgets what was learned (when the server stops: the registries a world holds are its own). */
    fun forget() = known.clear()
}

/**
 * The world as the census reads it. A chunk is read only if it is loaded already (`ServerChunkCache.getChunkNow`): one that
 * isn't answers null, whether it is a block, a height or a point of interest. Made new for every tick of a census, and not
 * kept past it, since the chunks it holds can unload between ticks.
 */
class LevelTerrain(private val level: ServerLevel) : Terrain {
    override val minY: Int = level.minY
    override val maxY: Int = level.minY + level.height - 1
    override var reads: Long = 0L
        private set
    override var misses: Long = 0L
        private set

    private var cx = Int.MIN_VALUE
    private var cz = Int.MIN_VALUE
    private var chunk: LevelChunk? = null
    private val cursor = BlockPos.MutableBlockPos()

    private fun chunkAt(x: Int, z: Int): LevelChunk? {
        val a = x shr 4
        val b = z shr 4
        if (a != cx || b != cz) {
            cx = a
            cz = b
            chunk = level.chunkSource.getChunkNow(a, b)
        }
        val c = chunk
        if (c == null) misses++
        return c
    }

    override fun loaded(cx: Int, cz: Int): Boolean = level.chunkSource.getChunkNow(cx, cz) != null

    override fun state(x: Int, y: Int, z: Int): BlockState? {
        val c = chunkAt(x, z) ?: return null
        reads++
        if (y < minY || y > maxY) return AIR
        return c.getBlockState(cursor.set(x, y, z))
    }

    override fun top(x: Int, z: Int): Int {
        val c = chunkAt(x, z) ?: return Terrain.UNLOADED
        return c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
    }

    override fun pois(cx: Int, cz: Int, into: MutableList<Poi>) {
        if (level.chunkSource.getChunkNow(cx, cz) == null) {
            misses++
            return
        }
        level.poiManager.getInChunk({ PoiKinds.of(it) != PoiKind.NONE }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY).forEach { r ->
            into.add(Poi(r.pos.asLong(), PoiKinds.of(r.poiType)))
        }
    }

    override fun board(pos: Long): BoardLook? {
        val x = BlockPos.getX(pos)
        val z = BlockPos.getZ(pos)
        val c = level.chunkSource.getChunkNow(x shr 4, z shr 4)
        if (c == null) {
            misses++
            return null
        }
        val p = BlockPos.of(pos)
        val s = c.getBlockState(p)
        if (s.block !is NoticeBoardBlock) return null
        val advances = (c.getBlockEntity(p) as? QuestBoardBlockEntity)?.advanceIndex ?: 0
        return BoardLook(s.getValue(NoticeBoardBlock.FACING), advances)
    }

    override fun beasts(minX: Int, minZ: Int, maxX: Int, maxZ: Int, y: Int, into: MutableList<Beast>) {
        val box = AABB(minX.toDouble(), (y - 24).toDouble(), minZ.toDouble(), (maxX + 1).toDouble(), (y + 24).toDouble(), (maxZ + 1).toDouble())
        for (a in level.getEntitiesOfClass(Animal::class.java, box)) {
            when (a) {
                is Chicken -> into.add(Beast(true, a.x, a.y, a.z))
                is Cow, is MushroomCow, is Sheep, is Pig -> into.add(Beast(false, a.x, a.y, a.z))
                else -> {}
            }
        }
    }

    private companion object {
        val AIR: BlockState = Blocks.AIR.defaultBlockState()
    }
}
