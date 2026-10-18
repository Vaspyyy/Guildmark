package io.github.vaspyyy.guildmark.island.plan

import io.github.vaspyyy.guildmark.city.plan.At
import io.github.vaspyyy.guildmark.city.plan.Axis
import io.github.vaspyyy.guildmark.city.plan.B
import io.github.vaspyyy.guildmark.city.plan.Canvas
import io.github.vaspyyy.guildmark.city.plan.Dye
import io.github.vaspyyy.guildmark.city.plan.Folk
import io.github.vaspyyy.guildmark.city.plan.Loot
import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.city.plan.Spawn
import io.github.vaspyyy.guildmark.city.plan.Wares
import io.github.vaspyyy.guildmark.city.plan.Dir as CityDir

/**
 * Lets an island build what the capitals build (a cottage of the country, a boat at its moorings) into a landmark's
 * [Build]: the capital's pieces draw in the world's own frame through this canvas, and an island's [style] picks
 * the real blocks for them by the sea it stands in. Chests take their loot table from [loot]; whoever lives in a
 * cottage is a villager, and its child stays home.
 */
internal class CityBridge(
    private val build: Build,
    private val style: IslandStyle,
    private val seed: Int,
    private val loot: (Loot) -> String,
) : Canvas {
    override val clip: Rect = Rect(-30_000_000, -30_000_000, 30_000_000, 30_000_000)

    override fun set(x: Int, y: Int, z: Int, b: B) = build.set(x, y, z, style.bk(b, Noise.hash(x, y, z, seed)))

    override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) {
        build.set(x, y - 1, z, style.bk(surface, Noise.hash(x, y - 1, z, seed)))
        for (k in 2..4) build.set(x, y - k, z, style.bk(fill, Noise.hash(x, y - k, z, seed)))
    }

    override fun clearing(x: Int, z: Int, y: Int) {
        build.set(x, y - 1, z, Bk.of("grass_block"))
        for (k in 2..4) build.set(x, y - k, z, Bk.of("dirt"))
    }

    override fun chest(x: Int, y: Int, z: Int, facing: CityDir, loot: Loot) = build.chest(x, y, z, dir(facing), this.loot(loot))

    override fun board(x: Int, y: Int, z: Int, facing: CityDir) {}

    override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: CityDir) {
        val id = when (what) {
            Spawn.VILLAGER, Spawn.RECEPTIONIST -> "minecraft:villager"
            Spawn.GOLEM -> "minecraft:iron_golem"
            Spawn.HORSE -> "minecraft:horse"
            Spawn.CAT -> "minecraft:cat"
            Spawn.COW -> "minecraft:cow"
            Spawn.SHEEP -> "minecraft:sheep"
            Spawn.PIG -> "minecraft:pig"
            Spawn.CHICKEN -> "minecraft:chicken"
        }
        build.spawn(id, x + 0.5, y.toDouble(), z + 0.5, yaw(dir(facing)))
    }

    override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: CityDir?, home: At?) {
        if (folk == Folk.CHILD) return
        build.spawn("minecraft:villager", at.x + 0.5, at.y.toDouble(), at.z + 0.5, yaw(dir(facing ?: CityDir.S)))
    }

    companion object {
        fun dir(d: CityDir): Dir = Dir.entries[d.ordinal]

        fun city(d: Dir): CityDir = CityDir.entries[d.ordinal]

        /** Minecraft's yaw for facing [d]: south is 0, west 90. */
        fun yaw(d: Dir): Float = when (d) {
            Dir.S -> 0f
            Dir.W -> 90f
            Dir.N -> 180f
            Dir.E -> 270f
        }
    }
}

/**
 * How what an island builds looks, by the sea it stands in: whitewash, palm timber and thatch in the warm seas,
 * grey stone, white plaster and slate in the temperate ones, dark stone and spruce in the cold. Turns the capitals'
 * blocks-by-purpose ([B]) into an island's blocks by name.
 */
internal class IslandStyle(climate: Climate) {
    private class Family(val block: String, val stairs: String, val slab: String)

    private class Names(
        val plaza: List<String>,
        val street: List<String>,
        val lane: List<String>,
        val yard: String,
        val fill: String,
        val foundation: String,
        val wall: Family,
        val trim: String,
        val dark: String,
        val wallPost: String,
        val stone: Family,
        val timber: String,
        val plaster: String,
        val planks: List<String>,
        val plankStairs: String,
        val plankSlab: String,
        val roofs: List<Family>,
        val wood: String,
        val log: String,
        val leaves: String,
    )

    private val p: Names = when (climate) {
        Climate.WARM, Climate.LUKEWARM -> WARM
        Climate.TEMPERATE -> TEMPERATE
        Climate.COLD, Climate.FROZEN -> NORTHERN
    }

    /** The wood its doors, fences and hatches are of ("spruce"). */
    val wood: String get() = p.wood

    fun bk(b: B, hash: Int): Bk = when (b.m) {
        M.AIR -> Bk.AIR
        M.PAVE_PLAZA -> worn(p.plaza[b.v.coerceIn(0, 2)], hash)
        M.PAVE_STREET -> worn(p.street[b.v.coerceIn(0, 1)], hash)
        M.PAVE_LANE -> worn(p.lane[b.v.coerceIn(0, 1)], hash)
        M.PAVE_YARD -> worn(p.yard, hash)
        M.GRASS -> Bk.of("grass_block")
        M.SOIL -> Bk.of("dirt")
        M.FILL -> Bk.of(p.fill)
        M.FOUNDATION -> worn(p.foundation, hash)

        M.WALL -> worn(p.wall.block, hash)
        M.WALL_TRIM -> Bk.of(p.trim)
        M.WALL_DARK -> worn(p.dark, hash)
        M.WALL_STAIRS -> stairs(p.wall.stairs, b)
        M.WALL_SLAB -> slab(p.wall.slab, b)
        M.WALL_POST -> Bk.of(p.wallPost)
        M.BARS -> Bk.of("iron_bars")

        M.STONE -> worn(p.stone.block, hash)
        M.STONE_STAIRS -> stairs(p.stone.stairs, b)
        M.STONE_SLAB -> slab(p.stone.slab, b)
        M.TIMBER -> pillar(p.timber, b.axis)
        M.PLASTER -> Bk.of(p.plaster)
        M.PLANKS -> Bk.of(p.planks[b.v.coerceIn(0, 1)])
        M.PLANK_STAIRS -> stairs(p.plankStairs, b)
        M.PLANK_SLAB -> slab(p.plankSlab, b)
        M.ROOF -> Bk.of(p.roofs[b.v.coerceIn(0, 3)].block)
        M.ROOF_STAIRS -> stairs(p.roofs[b.v.coerceIn(0, 3)].stairs, b)
        M.ROOF_SLAB -> slab(p.roofs[b.v.coerceIn(0, 3)].slab, b)
        M.FINE -> Bk.of("smooth_quartz")
        M.FINE_PILLAR -> pillar("quartz_pillar", b.axis)
        M.FINE_STAIRS -> stairs("smooth_quartz_stairs", b)
        M.FINE_SLAB -> slab("smooth_quartz_slab", b)
        M.WINDOW -> Bk.of("glass_pane")
        M.GLASS -> Bk.of("glass")
        M.STAINED -> Bk.of("${dye(b.color)}_stained_glass_pane")
        M.SHUTTER, M.TRAPDOOR -> Bk.of("${p.wood}_trapdoor", "facing" to face(b), "half" to if (b.top) "top" else "bottom", "open" to "${b.open}")
        M.DOOR -> Bk.of(
            "${p.wood}_door", "facing" to face(b), "half" to if (b.part == 1) "upper" else "lower",
            "hinge" to if (b.v == 1) "right" else "left", "open" to "${b.open}",
        )
        M.FENCE -> Bk.of("${p.wood}_fence")
        M.FENCE_GATE -> Bk.of("${p.wood}_fence_gate", "facing" to face(b), "open" to "${b.open}")
        M.LADDER -> Bk.of("ladder", "facing" to face(b))

        M.LANTERN -> Bk.of("lantern", "hanging" to "${b.top}")
        M.SOUL_LANTERN -> Bk.of("soul_lantern", "hanging" to "${b.top}")
        M.TORCH -> if (b.dir != null) Bk.of("wall_torch", "facing" to face(b)) else Bk.of("torch")
        M.CHAIN -> Bk.of("iron_chain")
        M.CAMPFIRE -> Bk.of("campfire")

        M.BED -> Bk.of("${dye(b.color)}_bed", "facing" to face(b), "part" to if (b.part == 1) "head" else "foot")
        M.CHEST -> Bk.of("chest", "facing" to face(b))
        M.BARREL -> Bk.of("barrel", "facing" to (b.dir?.let { CityBridge.dir(it).id } ?: "up"))
        M.BOOKSHELF -> Bk.of("bookshelf")
        M.LECTERN -> Bk.of("lectern", "facing" to face(b))
        M.CRAFTING_TABLE -> Bk.of("crafting_table")
        M.FURNACE -> Bk.of("furnace", "facing" to face(b))
        M.SMOKER -> Bk.of("smoker", "facing" to face(b))
        M.BLAST_FURNACE -> Bk.of("blast_furnace", "facing" to face(b))
        M.ANVIL -> Bk.of("anvil", "facing" to face(b))
        M.GRINDSTONE -> Bk.of("grindstone", "face" to "floor", "facing" to face(b))
        M.SMITHING_TABLE -> Bk.of("smithing_table")
        M.STONECUTTER -> Bk.of("stonecutter", "facing" to face(b))
        M.CARTOGRAPHY_TABLE -> Bk.of("cartography_table")
        M.FLETCHING_TABLE -> Bk.of("fletching_table")
        M.LOOM -> Bk.of("loom", "facing" to face(b))
        M.CAULDRON -> when (b.v) {
            1 -> Bk.of("lava_cauldron")
            2 -> Bk.of("water_cauldron", "level" to "3")
            else -> Bk.of("cauldron")
        }
        M.BREWING_STAND -> Bk.of("brewing_stand")
        M.COMPOSTER -> Bk.of("composter")
        M.CARPET -> Bk.of("${dye(b.color)}_carpet")
        M.WOOL -> Bk.of("${dye(b.color)}_wool")
        M.BANNER -> if (b.dir != null) Bk.of("${dye(b.color)}_wall_banner", "facing" to face(b)) else Bk.of("${dye(b.color)}_banner")
        M.FLOWER_POT -> Bk.of(POTS[Math.floorMod(b.v, POTS.size)])
        M.FLOWER -> Bk.of(FLOWERS[Math.floorMod(b.v, FLOWERS.size)])
        M.LEAVES -> Bk.of(if (b.v == 1) "flowering_azalea_leaves" else p.leaves, "persistent" to "true")
        M.LOG -> pillar(p.log, b.axis)
        M.HAY -> pillar("hay_block", b.axis)
        M.BELL -> Bk.of("bell", "facing" to face(b), "attachment" to if (b.top) "ceiling" else "floor")
        M.WATER -> Bk.WATER
        M.GOLD -> Bk.of("gold_block")
        M.CANDLE -> Bk.of("candle", "candles" to "${1 + Math.floorMod(hash, 3)}", "lit" to "true")
        M.CROP -> crop(b.v, hash)
        M.WARDFIRE -> Bk.of("guildmark:wardfire")
        M.TARGET -> Bk.of("target")
        M.CRATE -> Bk.of("note_block")
        M.HOOK -> Bk.of("waxed_exposed_lightning_rod", "facing" to "down")
        M.FARMLAND -> Bk.of("farmland", "moisture" to "7")
        M.TRACK -> Bk.of(if (Math.floorMod(hash, 4) == 0) "coarse_dirt" else "gravel")
        M.PUMPKIN -> when (b.v) {
            1 -> Bk.of("carved_pumpkin", "facing" to face(b))
            2 -> Bk.of("jack_o_lantern", "facing" to face(b))
            3 -> Bk.of("melon")
            else -> Bk.of("pumpkin")
        }
        // Nothing an island builds sets out the market's goods; a display would only ever show its usual stock
        M.WARES -> Bk.of("guildmark:" + Wares.entries[Math.floorMod(b.v, Wares.entries.size)].look.id, "facing" to face(b))

        // The mills' hubs (an island builds no mills)
        M.SAILS -> Bk.of("guildmark:windmill_sails", "facing" to face(b))
        M.WATER_WHEEL -> Bk.of("guildmark:water_wheel", "facing" to face(b))
    }

    private fun face(b: B): String = b.dir?.let { CityBridge.dir(it).id } ?: "north"

    private fun stairs(name: String, b: B): Bk = Bk.of(name, "facing" to face(b), "half" to if (b.top) "top" else "bottom")

    private fun slab(name: String, b: B): Bk = Bk.of(name, "type" to if (b.top) "top" else "bottom")

    private fun pillar(name: String, axis: Axis): Bk = Bk.of(name, "axis" to axis.name.lowercase())

    /** A crop by kind ([v]'s low two bits: wheat, carrots, potatoes, beetroot), ripe at 0 and younger as [v] counts up by fours. */
    private fun crop(v: Int, hash: Int): Bk {
        val young = (v shr 2) and 3
        val uneven = if (young > 0) Math.floorMod(hash, 2) else 0
        if (v and 3 == 3) return Bk.of("beetroots", "age" to "${3 - young}")
        val name = when (v and 3) {
            1 -> "carrots"
            2 -> "potatoes"
            else -> "wheat"
        }
        return Bk.of(name, "age" to "${7 - young * 2 + uneven}")
    }

    /** The block, or now and then the worn stand-in for it. */
    private fun worn(name: String, hash: Int): Bk {
        val worn = WEATHERING[name] ?: return Bk.of(name)
        var roll = Math.floorMod(hash, 100)
        for ((other, chance) in worn) {
            if (roll < chance) return Bk.of(other)
            roll -= chance
        }
        return Bk.of(name)
    }

    companion object {
        private fun dye(color: Dye): String = color.name.lowercase()

        private val POTS = listOf("potted_poppy", "potted_dandelion", "potted_azure_bluet", "potted_red_tulip", "potted_fern", "potted_cornflower", "potted_oxeye_daisy")
        private val FLOWERS = listOf("poppy", "dandelion", "azure_bluet", "cornflower", "oxeye_daisy")

        private val WEATHERING: Map<String, List<Pair<String, Int>>> = mapOf(
            "stone_bricks" to listOf("mossy_stone_bricks" to 9, "cracked_stone_bricks" to 6),
            "cobblestone" to listOf("mossy_cobblestone" to 16),
            "deepslate_bricks" to listOf("cracked_deepslate_bricks" to 8),
            "coarse_dirt" to listOf("gravel" to 14),
            "podzol" to listOf("coarse_dirt" to 20),
            "sandstone" to listOf("smooth_sandstone" to 10),
        )

        private val THATCH = Family("bamboo_mosaic", "bamboo_mosaic_stairs", "bamboo_mosaic_slab")

        private val TEMPERATE = Names(
            plaza = listOf("smooth_stone", "polished_andesite", "polished_diorite"),
            street = listOf("polished_andesite", "tuff_bricks"),
            lane = listOf("cobblestone", "dirt_path"),
            yard = "coarse_dirt",
            fill = "dirt",
            foundation = "cobblestone",
            wall = Family("stone_bricks", "stone_brick_stairs", "stone_brick_slab"),
            trim = "polished_andesite",
            dark = "cobblestone",
            wallPost = "stone_brick_wall",
            stone = Family("stone_bricks", "stone_brick_stairs", "stone_brick_slab"),
            timber = "stripped_dark_oak_log",
            plaster = "calcite",
            planks = listOf("spruce_planks", "dark_oak_planks"),
            plankStairs = "spruce_stairs",
            plankSlab = "spruce_slab",
            roofs = listOf(
                Family("deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab"),
                Family("deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab"),
                Family("dark_oak_planks", "dark_oak_stairs", "dark_oak_slab"),
                THATCH,
            ),
            wood = "spruce",
            log = "oak_log",
            leaves = "oak_leaves",
        )

        private val WARM = Names(
            plaza = listOf("smooth_sandstone", "cut_sandstone", "smooth_red_sandstone"),
            street = listOf("cut_sandstone", "cut_red_sandstone"),
            lane = listOf("sandstone", "smooth_sandstone"),
            yard = "sand",
            fill = "sandstone",
            foundation = "sandstone",
            wall = Family("cut_sandstone", "sandstone_stairs", "cut_sandstone_slab"),
            trim = "cut_red_sandstone",
            dark = "red_sandstone",
            wallPost = "sandstone_wall",
            stone = Family("smooth_sandstone", "smooth_sandstone_stairs", "smooth_sandstone_slab"),
            timber = "stripped_jungle_log",
            plaster = "white_terracotta",
            planks = listOf("birch_planks", "jungle_planks"),
            plankStairs = "jungle_stairs",
            plankSlab = "jungle_slab",
            roofs = listOf(THATCH, Family("jungle_planks", "jungle_stairs", "jungle_slab"), THATCH, THATCH),
            wood = "jungle",
            log = "jungle_log",
            leaves = "jungle_leaves",
        )

        private val NORTHERN = Names(
            plaza = listOf("polished_andesite", "polished_deepslate", "smooth_stone"),
            street = listOf("cobblestone", "polished_deepslate"),
            lane = listOf("cobblestone", "dirt_path"),
            yard = "podzol",
            fill = "dirt",
            foundation = "cobblestone",
            wall = Family("deepslate_bricks", "deepslate_brick_stairs", "deepslate_brick_slab"),
            trim = "polished_deepslate",
            dark = "cobbled_deepslate",
            wallPost = "deepslate_brick_wall",
            stone = Family("cobblestone", "cobblestone_stairs", "cobblestone_slab"),
            timber = "spruce_log",
            plaster = "stripped_spruce_wood",
            planks = listOf("spruce_planks", "dark_oak_planks"),
            plankStairs = "spruce_stairs",
            plankSlab = "spruce_slab",
            roofs = listOf(
                Family("deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab"),
                Family("spruce_planks", "spruce_stairs", "spruce_slab"),
                Family("dark_oak_planks", "dark_oak_stairs", "dark_oak_slab"),
                THATCH,
            ),
            wood = "spruce",
            log = "spruce_log",
            leaves = "spruce_leaves",
        )
    }
}
