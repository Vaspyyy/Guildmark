package io.github.vaspyyy.guildmark.lair

import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What a dungeon is dug from and how it's dressed. Built styles are squared-off halls of worked stone;
 * organic ones ([organic]) are caves hollowed out of the rock, sealed only where the rock gives way.
 * Rock styles turn to deepslate below y 0, as the land does.
 */
enum class DungeonStyle(val organic: Boolean, private val stairBlock: () -> Block) {
    CRYPT(false, { Blocks.STONE_BRICK_STAIRS }),
    CAVE(true, { Blocks.COBBLESTONE_STAIRS }),
    MINE(false, { Blocks.SPRUCE_STAIRS }),
    PIT(false, { Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS }),
    SHRINE(false, { Blocks.PRISMARINE_BRICK_STAIRS }),
    RIME(false, { Blocks.POLISHED_DIORITE_STAIRS }),
    GROTTO(true, { Blocks.MUD_BRICK_STAIRS });

    /** The steps down corridors and round the stairwell. */
    val stairs: Block get() = stairBlock()

    /** What a floor is laid with, at height [y]. */
    fun floor(random: RandomSource, y: Int): BlockState = when (this) {
        CRYPT -> pick(random, Blocks.STONE_BRICKS to 5, Blocks.CRACKED_STONE_BRICKS to 3, Blocks.MOSSY_STONE_BRICKS to 2, Blocks.COBBLESTONE to 1)
        CAVE -> if (y < 0) pick(random, Blocks.COBBLED_DEEPSLATE to 3, Blocks.DEEPSLATE to 2, Blocks.TUFF to 2, Blocks.COARSE_DIRT to 1)
        else pick(random, Blocks.COARSE_DIRT to 3, Blocks.STONE to 2, Blocks.COBBLESTONE to 2, Blocks.MOSSY_COBBLESTONE to 1, Blocks.ROOTED_DIRT to 1)
        MINE -> rock(random, y)
        PIT -> pick(random, Blocks.BLACKSTONE to 4, Blocks.POLISHED_BLACKSTONE to 3, Blocks.POLISHED_BLACKSTONE_BRICKS to 2)
        SHRINE -> pick(random, Blocks.PRISMARINE_BRICKS to 4, Blocks.PRISMARINE to 3, Blocks.DARK_PRISMARINE to 1)
        RIME -> pick(random, Blocks.PACKED_ICE to 4, Blocks.SNOW_BLOCK to 3, Blocks.BLUE_ICE to 1)
        GROTTO -> pick(random, Blocks.MOSS_BLOCK to 3, Blocks.PODZOL to 2, Blocks.ROOTED_DIRT to 2, Blocks.COARSE_DIRT to 1, Blocks.MUD to 1)
    }

    /** What walls are built from (or, for caves, what patches the rock where it gives way). */
    fun wall(random: RandomSource, y: Int): BlockState = when (this) {
        CRYPT -> pick(random, Blocks.STONE_BRICKS to 6, Blocks.MOSSY_STONE_BRICKS to 3, Blocks.CRACKED_STONE_BRICKS to 3)
        CAVE -> if (y < 0) pick(random, Blocks.DEEPSLATE to 4, Blocks.COBBLED_DEEPSLATE to 2, Blocks.TUFF to 1)
        else pick(random, Blocks.STONE to 5, Blocks.COBBLESTONE to 2, Blocks.MOSSY_COBBLESTONE to 1, Blocks.ANDESITE to 1, Blocks.TUFF to 1)
        MINE -> if (random.nextInt(14) == 0) ore(random, y) else rock(random, y)
        PIT -> if (random.nextInt(40) == 0) Blocks.GILDED_BLACKSTONE.defaultBlockState()
        else pick(random, Blocks.POLISHED_BLACKSTONE_BRICKS to 5, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS to 3, Blocks.BLACKSTONE to 2)
        SHRINE -> pick(random, Blocks.PRISMARINE to 4, Blocks.PRISMARINE_BRICKS to 3, Blocks.DARK_PRISMARINE to 2)
        RIME -> pick(random, Blocks.PACKED_ICE to 5, Blocks.BLUE_ICE to 2, Blocks.SNOW_BLOCK to 2)
        GROTTO -> if (y < 0) pick(random, Blocks.DEEPSLATE to 3, Blocks.ROOTED_DIRT to 1, Blocks.MOSS_BLOCK to 1)
        else pick(random, Blocks.ROOTED_DIRT to 2, Blocks.DIRT to 2, Blocks.STONE to 2, Blocks.MOSS_BLOCK to 1)
    }

    /** What ceilings are built from. */
    fun ceiling(random: RandomSource, y: Int): BlockState = when (this) {
        CRYPT -> pick(random, Blocks.STONE_BRICKS to 4, Blocks.CRACKED_STONE_BRICKS to 2, Blocks.MOSSY_STONE_BRICKS to 1)
        PIT -> pick(random, Blocks.BLACKSTONE to 3, Blocks.BASALT to 2, Blocks.POLISHED_BLACKSTONE_BRICKS to 2)
        SHRINE -> pick(random, Blocks.DARK_PRISMARINE to 3, Blocks.PRISMARINE to 2)
        RIME -> pick(random, Blocks.PACKED_ICE to 3, Blocks.BLUE_ICE to 2)
        else -> wall(random, y)
    }

    /** Columns and the solid core of the stairwell. */
    fun pillar(random: RandomSource, y: Int): BlockState = when (this) {
        CRYPT -> Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
        CAVE -> wall(random, y)
        MINE -> Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()
        PIT -> Blocks.POLISHED_BASALT.defaultBlockState()
        SHRINE -> Blocks.DARK_PRISMARINE.defaultBlockState()
        RIME -> Blocks.BLUE_ICE.defaultBlockState()
        GROTTO -> Blocks.DARK_OAK_LOG.defaultBlockState()
    }

    private fun rock(random: RandomSource, y: Int): BlockState =
        if (y < 0) pick(random, Blocks.DEEPSLATE to 4, Blocks.COBBLED_DEEPSLATE to 2, Blocks.TUFF to 1)
        else pick(random, Blocks.STONE to 5, Blocks.ANDESITE to 2, Blocks.COBBLESTONE to 2, Blocks.TUFF to 1)

    private fun ore(random: RandomSource, y: Int): BlockState =
        if (y < 0) pick(random, Blocks.DEEPSLATE_IRON_ORE to 2, Blocks.DEEPSLATE_COAL_ORE to 2, Blocks.DEEPSLATE_COPPER_ORE to 1)
        else pick(random, Blocks.COAL_ORE to 3, Blocks.IRON_ORE to 2, Blocks.COPPER_ORE to 2)

    companion object {
        fun pick(random: RandomSource, vararg weighted: Pair<Block, Int>): BlockState {
            var roll = random.nextInt(weighted.sumOf { it.second })
            for ((block, weight) in weighted) {
                roll -= weight
                if (roll < 0) return block.defaultBlockState()
            }
            return weighted.last().first.defaultBlockState()
        }
    }
}
