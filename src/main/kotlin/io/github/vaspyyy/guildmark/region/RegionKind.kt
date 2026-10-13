package io.github.vaspyyy.guildmark.region

import net.minecraft.core.Holder
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.neoforged.neoforge.common.Tags

/**
 * The sorts of land a region can be. Every overworld biome belongs to one (rivers and beaches to none:
 * they're the seams between regions), read from biome tags first so other mods' biomes find their place.
 * The kind sets a region's look and sound: the air's tint, how near the haze closes and what drifts in it,
 * its theme and its names.
 */
enum class RegionKind(
    val id: String,
    /** The land's own theme plays here: sound event guildmark:music.region.<id>. */
    val theme: Boolean,
    /** The colour the air leans toward, as RGB, and how far (0 to 1). */
    val fogColor: Int,
    val fogTint: Float,
    /** How far off (blocks) the haze grows thick here, or 0 to leave the air as clear as it is. */
    val fogEnd: Float,
    /** Ink for its name on the map, as RGB. */
    val ink: Int,
    /** Lies below the land: found and named apart from the region above it. */
    val underground: Boolean = false,
) {
    /** Meadows, fields and the kinder woods: where most villages stand. */
    HEARTLAND("heartland", true, 0xF2DFB0, 0.08f, 0f, 0x5A4220),
    /** Dark forests, pale gardens and the old pine woods. */
    WILDWOOD("wildwood", true, 0x4C5E4E, 0.28f, 460f, 0x2F4A2A),
    /** Swamps and mangroves, rotting under a green murk. */
    MARSH("marsh", true, 0x5F6B3C, 0.5f, 150f, 0x40502A),
    /** Snowfields, ice and the frozen peaks. */
    FROZEN("frozen", true, 0xCADCF0, 0.4f, 300f, 0x3A5A7A),
    /** Badlands and the high savannas, burnt and falling ash. */
    ASHEN("ashen", true, 0x8C7464, 0.5f, 210f, 0x6A3420),
    /** Windswept hills and stone peaks. */
    CRAGS("crags", true, 0x9DA7B2, 0.18f, 640f, 0x4A4E58),
    /** Deserts. */
    DUNES("dunes", true, 0xE2C890, 0.3f, 380f, 0x7A5A28),
    /** The low savannas. */
    STEPPE("steppe", true, 0xDCC28A, 0.14f, 0f, 0x6E5428),
    /** Jungles. */
    JUNGLE("jungle", true, 0x6C8E5A, 0.22f, 420f, 0x2E5A2A),
    /** The open sea. */
    SEA("sea", true, 0x90A8C2, 0.1f, 0f, 0x2A4A6E),
    /** Mushroom isles. */
    MUSHROOM("mushroom", true, 0x9C7CA4, 0.3f, 360f, 0x5A3A60),
    /** Lush, dripstone and sulfur caves. */
    CAVERN("cavern", false, 0x55605A, 0.12f, 0f, 0x3A3A3A, underground = true),
    /** The deep dark. */
    DEEP("deep", false, 0x101820, 0.0f, 0f, 0x1A2A3A, underground = true),
    /** Caves far below some lands, grown through with crystal. */
    CRYSTAL("crystal", true, 0x8C6CDA, 0.42f, 140f, 0x4A2A7A, underground = true);

    /** Its music, if it has a theme of its own. */
    val musicKey: String get() = "music.region.$id"

    /** "region.guildmark.kind.<id>", e.g. "Rotting Marsh". */
    val titleKey: String get() = "region.guildmark.kind.$id"

    companion object {
        private val BY_ID = entries.associateBy { it.id }

        fun byId(id: String): RegionKind? = BY_ID[id]

        /** A river, beach or shore: the seam between regions, belonging to none. */
        fun isSeam(biome: Holder<Biome>): Boolean =
            biome.`is`(BiomeTags.IS_RIVER) || biome.`is`(BiomeTags.IS_BEACH) || biome.`is`(Tags.Biomes.IS_RIVER) ||
                biome.`is`(Tags.Biomes.IS_BEACH) || biome.`is`(Tags.Biomes.IS_STONY_SHORES) || biome.`is`(Biomes.STONY_SHORE)

        /**
         * The kind of land [biome] is, or null for a seam (rivers, beaches) and for anything outside the
         * overworld. Doesn't know the crystal deeps, which aren't a biome: see Regions.kindAt.
         */
        fun ofBiome(biome: Holder<Biome>): RegionKind? {
            if (biome.`is`(BiomeTags.IS_NETHER) || biome.`is`(BiomeTags.IS_END) || biome.`is`(Biomes.THE_VOID)) return null
            return when {
                biome.`is`(Biomes.DEEP_DARK) -> DEEP
                biome.`is`(Biomes.LUSH_CAVES) || biome.`is`(Biomes.DRIPSTONE_CAVES) || biome.`is`(Biomes.SULFUR_CAVES) ||
                    biome.`is`(Tags.Biomes.IS_CAVE) -> CAVERN
                biome.`is`(Biomes.MUSHROOM_FIELDS) || biome.`is`(Tags.Biomes.IS_MUSHROOM) -> MUSHROOM
                isSeam(biome) -> null
                // Frozen seas belong with the ice
                biome.`is`(Biomes.FROZEN_OCEAN) || biome.`is`(Biomes.DEEP_FROZEN_OCEAN) -> FROZEN
                biome.`is`(BiomeTags.IS_OCEAN) || biome.`is`(Tags.Biomes.IS_OCEAN) -> SEA
                biome.`is`(Biomes.SWAMP) || biome.`is`(Biomes.MANGROVE_SWAMP) || biome.`is`(Tags.Biomes.IS_SWAMP) -> MARSH
                biome.`is`(BiomeTags.IS_BADLANDS) || biome.`is`(Tags.Biomes.IS_BADLANDS) ||
                    biome.`is`(Biomes.WINDSWEPT_SAVANNA) || biome.`is`(Biomes.SAVANNA_PLATEAU) -> ASHEN
                biome.`is`(Tags.Biomes.IS_SNOWY) || biome.`is`(Tags.Biomes.IS_ICY) || biome.`is`(Biomes.SNOWY_PLAINS) ||
                    biome.`is`(Biomes.ICE_SPIKES) || biome.`is`(Biomes.SNOWY_TAIGA) || biome.`is`(Biomes.SNOWY_SLOPES) ||
                    biome.`is`(Biomes.FROZEN_PEAKS) || biome.`is`(Biomes.JAGGED_PEAKS) || biome.`is`(Biomes.GROVE) -> FROZEN
                biome.`is`(Biomes.DESERT) || biome.`is`(Tags.Biomes.IS_DESERT) -> DUNES
                biome.`is`(BiomeTags.IS_SAVANNA) || biome.`is`(Tags.Biomes.IS_SAVANNA) -> STEPPE
                biome.`is`(BiomeTags.IS_JUNGLE) || biome.`is`(Tags.Biomes.IS_JUNGLE) -> JUNGLE
                biome.`is`(Biomes.DARK_FOREST) || biome.`is`(Biomes.PALE_GARDEN) || biome.`is`(BiomeTags.IS_TAIGA) ||
                    biome.`is`(Tags.Biomes.IS_TAIGA) || biome.`is`(Tags.Biomes.IS_DARK_FOREST) || biome.`is`(Tags.Biomes.IS_SPOOKY) -> WILDWOOD
                biome.`is`(Biomes.MEADOW) || biome.`is`(Biomes.CHERRY_GROVE) -> HEARTLAND
                biome.`is`(Biomes.WINDSWEPT_HILLS) || biome.`is`(Biomes.WINDSWEPT_GRAVELLY_HILLS) || biome.`is`(Biomes.WINDSWEPT_FOREST) ||
                    biome.`is`(Biomes.STONY_PEAKS) || biome.`is`(Tags.Biomes.IS_WINDSWEPT) || biome.`is`(Tags.Biomes.IS_MOUNTAIN_PEAK) -> CRAGS
                else -> HEARTLAND
            }
        }
    }
}
