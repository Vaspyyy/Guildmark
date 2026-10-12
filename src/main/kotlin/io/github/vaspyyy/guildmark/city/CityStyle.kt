package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.city.plan.Axis
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.registry.ModBlocks
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.tags.BiomeTags
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BellAttachType
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType

/** A stone or wood with its stairs and slab. */
class Family(val block: Block, val stairs: Block, val slab: Block)

/**
 * The real blocks a capital is built of, by what each is for. Paving lists run plain, border, inlay;
 * [planks] runs upper floors, ground floor; [roofs] runs slate, red, wood (see GreatBuildings).
 */
class Palette(
    val plaza: List<Block>,
    val street: List<Block>,
    val lane: List<Block>,
    val yard: Block,
    val fill: Block,
    val foundation: Block,
    /** The land's own top and what's under it, where a clearing has nothing better to go on. */
    val ground: Block,
    val subsoil: Block,
    val wall: Family,
    val trim: Block,
    val dark: Block,
    val wallPost: Block,
    val stone: Family,
    val timber: Block,
    val plaster: Block,
    val planks: List<Block>,
    val plankStairs: Block,
    val plankSlab: Block,
    val roofs: List<Family>,
    val wood: Wood,
    val log: Block,
    val leaves: Block,
    /** Vanilla's village house chest that suits the style. */
    val houseLoot: String,
)

/** Doors, hatches and fences, all of one wood. */
class Wood(val door: Block, val trapdoor: Block, val fence: Block, val gate: Block)

/**
 * How a capital looks, chosen by the land it's built in: dressed grey stone, timber and plaster in
 * the temperate lands, sandstone in the desert, dark stone and spruce in the north, and mud brick
 * and acacia on the savanna.
 */
enum class CityStyle {
    TEMPERATE, DESERT, NORTHERN, SAVANNA;

    val palette: Palette by lazy { palettes(this) }

    /** The real block for [b], facing as it does in the city's own frame (the caller turns it to the world). */
    fun state(b: B, hash: Int): BlockState {
        val p = palette
        return when (b.m) {
            M.AIR -> Blocks.AIR.defaultBlockState()
            M.PAVE_PLAZA -> weathered(p.plaza[b.v.coerceIn(0, 2)], hash)
            M.PAVE_STREET -> weathered(p.street[b.v.coerceIn(0, 1)], hash)
            M.PAVE_LANE -> weathered(p.lane[b.v.coerceIn(0, 1)], hash)
            M.PAVE_YARD -> weathered(p.yard, hash)
            M.GRASS -> Blocks.GRASS_BLOCK.defaultBlockState()
            M.SOIL -> Blocks.DIRT.defaultBlockState()
            M.FILL -> p.fill.defaultBlockState()
            M.FOUNDATION -> p.foundation.defaultBlockState()

            M.WALL -> weathered(p.wall.block, hash)
            M.WALL_TRIM -> p.trim.defaultBlockState()
            M.WALL_DARK -> weathered(p.dark, hash)
            M.WALL_STAIRS -> stairs(p.wall.stairs, b)
            M.WALL_SLAB -> slab(p.wall.slab, b)
            M.WALL_POST -> p.wallPost.defaultBlockState()
            M.BARS -> Blocks.IRON_BARS.defaultBlockState()

            M.STONE -> weathered(p.stone.block, hash)
            M.STONE_STAIRS -> stairs(p.stone.stairs, b)
            M.STONE_SLAB -> slab(p.stone.slab, b)
            M.TIMBER -> pillar(p.timber, b.axis)
            M.PLASTER -> p.plaster.defaultBlockState()
            M.PLANKS -> p.planks[b.v.coerceIn(0, 1)].defaultBlockState()
            M.PLANK_STAIRS -> stairs(p.plankStairs, b)
            M.PLANK_SLAB -> slab(p.plankSlab, b)
            M.ROOF -> p.roofs[b.v.coerceIn(0, 2)].block.defaultBlockState()
            M.ROOF_STAIRS -> stairs(p.roofs[b.v.coerceIn(0, 2)].stairs, b)
            M.ROOF_SLAB -> slab(p.roofs[b.v.coerceIn(0, 2)].slab, b)
            M.FINE -> FINE.block.defaultBlockState()
            M.FINE_PILLAR -> pillar(Blocks.QUARTZ_PILLAR, b.axis)
            M.FINE_STAIRS -> stairs(FINE.stairs, b)
            M.FINE_SLAB -> slab(FINE.slab, b)
            M.WINDOW -> Blocks.GLASS_PANE.defaultBlockState()
            M.GLASS -> Blocks.GLASS.defaultBlockState()
            M.STAINED -> Blocks.STAINED_GLASS_PANE.pick(dye(b.color)).defaultBlockState()
            M.SHUTTER, M.TRAPDOOR -> facing(p.wood.trapdoor, b.dir)
                .setValue(BlockStateProperties.HALF, if (b.top) Half.TOP else Half.BOTTOM)
                .setValue(BlockStateProperties.OPEN, b.open)
            M.DOOR -> facing(p.wood.door, b.dir)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, if (b.part == 1) DoubleBlockHalf.UPPER else DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.DOOR_HINGE, if (b.v == 1) DoorHingeSide.RIGHT else DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, b.open)
            M.FENCE -> p.wood.fence.defaultBlockState()
            M.FENCE_GATE -> facing(p.wood.gate, b.dir).setValue(BlockStateProperties.OPEN, b.open)
            M.LADDER -> facing(Blocks.LADDER, b.dir)

            M.LANTERN -> Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, b.top)
            M.SOUL_LANTERN -> Blocks.SOUL_LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, b.top)
            M.TORCH -> if (b.dir != null) facing(Blocks.WALL_TORCH, b.dir) else Blocks.TORCH.defaultBlockState()
            M.CHAIN -> Blocks.IRON_CHAIN.defaultBlockState()
            M.CAMPFIRE -> Blocks.CAMPFIRE.defaultBlockState()

            M.BED -> facing(Blocks.BED.pick(dye(b.color)), b.dir)
                .setValue(BlockStateProperties.BED_PART, if (b.part == 1) BedPart.HEAD else BedPart.FOOT)
            M.CHEST -> facing(Blocks.CHEST, b.dir)
            M.BARREL -> Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, b.dir?.let(::direction) ?: Direction.UP)
            M.BOOKSHELF -> Blocks.BOOKSHELF.defaultBlockState()
            M.LECTERN -> facing(Blocks.LECTERN, b.dir)
            M.CRAFTING_TABLE -> Blocks.CRAFTING_TABLE.defaultBlockState()
            M.FURNACE -> facing(Blocks.FURNACE, b.dir)
            M.SMOKER -> facing(Blocks.SMOKER, b.dir)
            M.BLAST_FURNACE -> facing(Blocks.BLAST_FURNACE, b.dir)
            M.ANVIL -> facing(Blocks.ANVIL, b.dir)
            M.GRINDSTONE -> facing(Blocks.GRINDSTONE, b.dir).setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
            M.SMITHING_TABLE -> Blocks.SMITHING_TABLE.defaultBlockState()
            M.STONECUTTER -> facing(Blocks.STONECUTTER, b.dir)
            M.CARTOGRAPHY_TABLE -> Blocks.CARTOGRAPHY_TABLE.defaultBlockState()
            M.FLETCHING_TABLE -> Blocks.FLETCHING_TABLE.defaultBlockState()
            M.LOOM -> facing(Blocks.LOOM, b.dir)
            M.CAULDRON -> when (b.v) {
                1 -> Blocks.LAVA_CAULDRON.defaultBlockState()
                2 -> Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3)
                else -> Blocks.CAULDRON.defaultBlockState()
            }
            M.BREWING_STAND -> Blocks.BREWING_STAND.defaultBlockState()
            M.COMPOSTER -> Blocks.COMPOSTER.defaultBlockState()
            M.CARPET -> Blocks.CARPET.pick(dye(b.color)).defaultBlockState()
            M.WOOL -> Blocks.WOOL.pick(dye(b.color)).defaultBlockState()
            M.BANNER -> if (b.dir != null) facing(Blocks.WALL_BANNER.pick(dye(b.color)), b.dir)
                else Blocks.BANNER.pick(dye(b.color)).defaultBlockState()
            M.FLOWER_POT -> POTS[Math.floorMod(b.v, POTS.size)].defaultBlockState()
            M.FLOWER -> FLOWERS[Math.floorMod(b.v, FLOWERS.size)].defaultBlockState()
            M.LEAVES -> p.leaves.defaultBlockState().trySetValue(BlockStateProperties.PERSISTENT, true)
            M.LOG -> pillar(p.log, b.axis)
            M.HAY -> pillar(Blocks.HAY_BLOCK, b.axis)
            M.BELL -> facing(Blocks.BELL, b.dir)
                .setValue(BlockStateProperties.BELL_ATTACHMENT, if (b.top) BellAttachType.CEILING else BellAttachType.FLOOR)
            M.WATER -> Blocks.WATER.defaultBlockState()
            M.GOLD -> Blocks.GOLD_BLOCK.defaultBlockState()
            M.CANDLE -> Blocks.CANDLE.defaultBlockState()
                .setValue(BlockStateProperties.CANDLES, 1 + Math.floorMod(hash, 3))
                .setValue(BlockStateProperties.LIT, true)
            M.CROP -> Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7)
            M.WARDFIRE -> ModBlocks.WARDFIRE.get().defaultBlockState()
            M.TARGET -> Blocks.TARGET.defaultBlockState()
        }
    }

    companion object {
        /** Marble for temples, fountains and thrones, whatever the style. */
        private val FINE by lazy { Family(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB) }

        private val POTS by lazy {
            listOf(
                Blocks.POTTED_POPPY, Blocks.POTTED_DANDELION, Blocks.POTTED_AZURE_BLUET, Blocks.POTTED_RED_TULIP,
                Blocks.POTTED_FERN, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_OXEYE_DAISY,
            )
        }
        private val FLOWERS by lazy { listOf(Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY) }

        /** Worn stand-ins and how many in a hundred of each block are swapped for them. */
        private val WEATHERING: Map<Block, List<Pair<Block, Int>>> by lazy {
            mapOf(
                Blocks.STONE_BRICKS to listOf(Blocks.MOSSY_STONE_BRICKS to 7, Blocks.CRACKED_STONE_BRICKS to 5),
                Blocks.COBBLESTONE to listOf(Blocks.MOSSY_COBBLESTONE to 12),
                Blocks.DEEPSLATE_BRICKS to listOf(Blocks.CRACKED_DEEPSLATE_BRICKS to 8),
                Blocks.COARSE_DIRT to listOf(Blocks.GRAVEL to 14),
                Blocks.PODZOL to listOf(Blocks.COARSE_DIRT to 20),
                Blocks.PACKED_MUD to listOf(Blocks.COARSE_DIRT to 12),
                Blocks.SANDSTONE to listOf(Blocks.SMOOTH_SANDSTONE to 10),
            )
        }

        fun forBiome(biome: Holder<Biome>): CityStyle = when {
            biome.`is`(Biomes.DESERT) -> DESERT
            biome.`is`(BiomeTags.IS_SAVANNA) -> SAVANNA
            biome.`is`(BiomeTags.IS_TAIGA) || biome.`is`(Biomes.SNOWY_PLAINS) || biome.`is`(Biomes.SNOWY_TAIGA) -> NORTHERN
            else -> TEMPERATE
        }

        private fun weathered(block: Block, hash: Int): BlockState {
            val worn = WEATHERING[block] ?: return block.defaultBlockState()
            var roll = Math.floorMod(hash, 100)
            for ((other, chance) in worn) {
                if (roll < chance) return other.defaultBlockState()
                roll -= chance
            }
            return block.defaultBlockState()
        }

        fun direction(dir: Dir): Direction = when (dir) {
            Dir.N -> Direction.NORTH
            Dir.E -> Direction.EAST
            Dir.S -> Direction.SOUTH
            Dir.W -> Direction.WEST
        }

        fun dye(color: Dye): DyeColor = DyeColor.entries[color.ordinal]

        private fun facing(block: Block, dir: Dir?): BlockState {
            val state = block.defaultBlockState()
            val d = dir?.let(::direction) ?: return state
            return when {
                state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) -> state.setValue(BlockStateProperties.HORIZONTAL_FACING, d)
                state.hasProperty(BlockStateProperties.FACING) -> state.setValue(BlockStateProperties.FACING, d)
                else -> state
            }
        }

        private fun stairs(block: Block, b: B): BlockState =
            facing(block, b.dir ?: Dir.N).setValue(BlockStateProperties.HALF, if (b.top) Half.TOP else Half.BOTTOM)

        private fun slab(block: Block, b: B): BlockState =
            block.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, if (b.top) SlabType.TOP else SlabType.BOTTOM)

        private fun pillar(block: Block, axis: Axis): BlockState = block.defaultBlockState().trySetValue(
            BlockStateProperties.AXIS,
            when (axis) {
                Axis.X -> Direction.Axis.X
                Axis.Y -> Direction.Axis.Y
                Axis.Z -> Direction.Axis.Z
            },
        )

        private fun palettes(style: CityStyle): Palette = when (style) {
            TEMPERATE -> Palette(
                plaza = listOf(Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE),
                street = listOf(Blocks.POLISHED_ANDESITE, Blocks.TUFF_BRICKS),
                lane = listOf(Blocks.COBBLESTONE, Blocks.DIRT_PATH),
                yard = Blocks.COARSE_DIRT,
                fill = Blocks.DIRT,
                foundation = Blocks.COBBLESTONE,
                ground = Blocks.GRASS_BLOCK,
                subsoil = Blocks.DIRT,
                wall = Family(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB),
                trim = Blocks.POLISHED_ANDESITE,
                dark = Blocks.COBBLESTONE,
                wallPost = Blocks.STONE_BRICK_WALL,
                stone = Family(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB),
                timber = Blocks.STRIPPED_DARK_OAK_LOG,
                plaster = Blocks.CALCITE,
                planks = listOf(Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS),
                plankStairs = Blocks.SPRUCE_STAIRS,
                plankSlab = Blocks.SPRUCE_SLAB,
                roofs = listOf(
                    Family(Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB),
                    Family(Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB),
                    Family(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB),
                ),
                wood = Wood(Blocks.SPRUCE_DOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE),
                log = Blocks.OAK_LOG,
                leaves = Blocks.OAK_LEAVES,
                houseLoot = "village_plains_house",
            )
            DESERT -> Palette(
                plaza = listOf(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE),
                street = listOf(Blocks.CUT_SANDSTONE, Blocks.CUT_RED_SANDSTONE),
                lane = listOf(Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE),
                yard = Blocks.SAND,
                fill = Blocks.SANDSTONE,
                foundation = Blocks.SANDSTONE,
                ground = Blocks.SAND,
                subsoil = Blocks.SANDSTONE,
                wall = Family(Blocks.CUT_SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE_SLAB),
                trim = Blocks.CUT_RED_SANDSTONE,
                dark = Blocks.RED_SANDSTONE,
                wallPost = Blocks.SANDSTONE_WALL,
                stone = Family(Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB),
                timber = Blocks.STRIPPED_JUNGLE_LOG,
                plaster = Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE),
                planks = listOf(Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS),
                plankStairs = Blocks.BIRCH_STAIRS,
                plankSlab = Blocks.BIRCH_SLAB,
                roofs = listOf(
                    Family(Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB),
                    Family(Blocks.SMOOTH_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE_STAIRS, Blocks.SMOOTH_RED_SANDSTONE_SLAB),
                    Family(Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_SLAB),
                ),
                wood = Wood(Blocks.JUNGLE_DOOR, Blocks.JUNGLE_TRAPDOOR, Blocks.JUNGLE_FENCE, Blocks.JUNGLE_FENCE_GATE),
                log = Blocks.JUNGLE_LOG,
                leaves = Blocks.JUNGLE_LEAVES,
                houseLoot = "village_desert_house",
            )
            NORTHERN -> Palette(
                plaza = listOf(Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DEEPSLATE, Blocks.SMOOTH_STONE),
                street = listOf(Blocks.COBBLESTONE, Blocks.POLISHED_DEEPSLATE),
                lane = listOf(Blocks.COBBLESTONE, Blocks.DIRT_PATH),
                yard = Blocks.PODZOL,
                fill = Blocks.DIRT,
                foundation = Blocks.COBBLESTONE,
                ground = Blocks.GRASS_BLOCK,
                subsoil = Blocks.DIRT,
                wall = Family(Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_SLAB),
                trim = Blocks.POLISHED_DEEPSLATE,
                dark = Blocks.COBBLED_DEEPSLATE,
                wallPost = Blocks.DEEPSLATE_BRICK_WALL,
                stone = Family(Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB),
                timber = Blocks.SPRUCE_LOG,
                plaster = Blocks.STRIPPED_SPRUCE_WOOD,
                planks = listOf(Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS),
                plankStairs = Blocks.SPRUCE_STAIRS,
                plankSlab = Blocks.SPRUCE_SLAB,
                roofs = listOf(
                    Family(Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB),
                    Family(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB),
                    Family(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB),
                ),
                wood = Wood(Blocks.SPRUCE_DOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE),
                log = Blocks.SPRUCE_LOG,
                leaves = Blocks.SPRUCE_LEAVES,
                houseLoot = "village_taiga_house",
            )
            SAVANNA -> Palette(
                plaza = listOf(Blocks.SMOOTH_SANDSTONE, Blocks.TERRACOTTA, Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE)),
                street = listOf(Blocks.PACKED_MUD, Blocks.MUD_BRICKS),
                lane = listOf(Blocks.PACKED_MUD, Blocks.DIRT_PATH),
                yard = Blocks.COARSE_DIRT,
                fill = Blocks.DIRT,
                foundation = Blocks.PACKED_MUD,
                ground = Blocks.GRASS_BLOCK,
                subsoil = Blocks.DIRT,
                wall = Family(Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB),
                trim = Blocks.SMOOTH_SANDSTONE,
                dark = Blocks.TERRACOTTA,
                wallPost = Blocks.MUD_BRICK_WALL,
                stone = Family(Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB),
                timber = Blocks.ACACIA_LOG,
                plaster = Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE),
                planks = listOf(Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS),
                plankStairs = Blocks.ACACIA_STAIRS,
                plankSlab = Blocks.ACACIA_SLAB,
                roofs = listOf(
                    Family(Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB),
                    Family(Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB),
                    Family(Blocks.ACACIA_PLANKS, Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB),
                ),
                wood = Wood(Blocks.ACACIA_DOOR, Blocks.ACACIA_TRAPDOOR, Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE),
                log = Blocks.ACACIA_LOG,
                leaves = Blocks.ACACIA_LEAVES,
                houseLoot = "village_savanna_house",
            )
        }
    }
}
