package io.github.vaspyyy.guildmark.city.plan

/*
 * The city is drawn in its own frame, with no Minecraft types: x runs east, z south, y up, and the
 * middle of the plaza at standing height is (0, 0, 0). Blocks are named by what they are for (a wall,
 * a roof, a floor) and the city's style picks the real block when it's placed, turning the whole
 * frame to face whichever way the city was built. The same drawing can be rendered to a preview.
 */

/** A direction across the ground. North is -z. */
enum class Dir(val dx: Int, val dz: Int) {
    N(0, -1), E(1, 0), S(0, 1), W(-1, 0);

    /** A quarter turn clockwise, seen from above. */
    val cw: Dir get() = entries[(ordinal + 1) % 4]
    val ccw: Dir get() = entries[(ordinal + 3) % 4]
    val opposite: Dir get() = entries[(ordinal + 2) % 4]
    /** Does it run along x? */
    val alongX: Boolean get() = dx != 0
}

enum class Axis { X, Y, Z }

/** Axis a log or beam lies along to run in [dir]. */
fun axisOf(dir: Dir): Axis = if (dir.alongX) Axis.X else Axis.Z

/**
 * What a block is for. [rgb] is its colour in previews; [solid] whether it fills its whole space,
 * which previews use to skip faces nobody can see.
 */
enum class M(val rgb: Int, val solid: Boolean = true) {
    /** Cleared: nothing stands here. */
    AIR(0, false),

    // The ground
    PAVE_PLAZA(0xA8A49A), PAVE_STREET(0x8C8A84), PAVE_LANE(0x7E6E5C), PAVE_YARD(0x96887A), GRASS(0x6E9A4A),
    SOIL(0x5A3E28), FILL(0x7A5A3E), FOUNDATION(0x77746E),

    // Walls and towers
    WALL(0x9C9890), WALL_TRIM(0xB4B0A6), WALL_DARK(0x5E5C58), WALL_STAIRS(0x9C9890, false), WALL_SLAB(0x9C9890, false),
    WALL_POST(0x9C9890, false), BARS(0x6A6A70, false),

    // Buildings
    STONE(0x8E8A84), STONE_STAIRS(0x8E8A84, false), STONE_SLAB(0x8E8A84, false),
    TIMBER(0x4A3424), PLASTER(0xE6DDCC), PLANKS(0xA07848), PLANK_STAIRS(0xA07848, false), PLANK_SLAB(0xA07848, false),
    ROOF(0x47474F), ROOF_STAIRS(0x47474F, false), ROOF_SLAB(0x47474F, false),
    FINE(0xE8E4DA), FINE_PILLAR(0xE8E4DA), FINE_STAIRS(0xE8E4DA, false), FINE_SLAB(0xE8E4DA, false),
    WINDOW(0xA8C8D8, false), GLASS(0xA8C8D8), STAINED(0xC05050, false),
    SHUTTER(0x6A4A2A, false), DOOR(0x7A5632, false), FENCE(0x7A5632, false), FENCE_GATE(0x7A5632, false),
    TRAPDOOR(0x7A5632, false), LADDER(0x9A7A4A, false),

    // Light
    LANTERN(0xF0C060, false), TORCH(0xF0C060, false), CHAIN(0x50505A, false), CAMPFIRE(0xE07030, false),

    // Things inside and about
    BED(0xC03030, false), CHEST(0x9A6A30), BARREL(0x7A5632), BOOKSHELF(0x8A6040), LECTERN(0xA07848, false),
    CRAFTING_TABLE(0x9A6A3A), FURNACE(0x6A6A6A), SMOKER(0x5A5048), BLAST_FURNACE(0x5A5A60), ANVIL(0x46464C, false),
    GRINDSTONE(0x8A8A8A, false), SMITHING_TABLE(0x3A3A48), STONECUTTER(0x8A8A8A, false), CARTOGRAPHY_TABLE(0x6A5A4A),
    FLETCHING_TABLE(0xC8B080), LOOM(0xB09070), CAULDRON(0x404048, false), BREWING_STAND(0x9A8A50, false),
    COMPOSTER(0x6A5030, false), CARPET(0xB02828, false), WOOL(0xB02828), BANNER(0xB02828, false),
    FLOWER_POT(0xA05A3A, false), FLOWER(0xE04060, false), LEAVES(0x3E7A2E), LOG(0x5A4028), HAY(0xC8A83A),
    BELL(0xE8C040, false), WATER(0x3A6AD0, false), GOLD(0xF0D040), CANDLE(0xE8E0C8, false),
    SOUL_LANTERN(0x60D0E0, false), CROP(0x8AB040, false), WARDFIRE(0x404048, false), TARGET(0xE0D0C0),

    // The harbour: crates of goods on the quays, and the hook on a crane's chain
    CRATE(0x8A6A3A), HOOK(0xA27A62, false),
    // The farms. A crop's [B.v] picks it (wheat, carrots, potatoes, beetroot) and, by fours, how far it is from
    // ripe; a pumpkin's is plain, carved (facing [B.dir]), lit or a melon; leaves' 1 is a fruit tree in blossom
    FARMLAND(0x6A4A2E), TRACK(0x8E8880), PUMPKIN(0xE08020),
}

/**
 * One block in the city's frame. [dir] is which way it faces (a stair's high side, a door's inside,
 * a bed's head, a torch away from its wall); [top] puts a slab, stair or trapdoor in the upper half
 * and hangs a lantern; [part] is a door's upper half or a bed's head; [v] picks a variant (a roof's
 * colours, a flower) and [color] a dye.
 */
class B(
    val m: M,
    val dir: Dir? = null,
    val top: Boolean = false,
    val axis: Axis = Axis.Y,
    val color: Dye = Dye.WHITE,
    val v: Int = 0,
    val part: Int = 0,
    val open: Boolean = false,
) {
    fun facing(d: Dir) = B(m, d, top, axis, color, v, part, open)

    companion object {
        val AIR = B(M.AIR)
    }
}

/** The sixteen dye colours, in Minecraft's order. */
enum class Dye(val rgb: Int) {
    WHITE(0xF0F0F0), ORANGE(0xF08020), MAGENTA(0xC040B0), LIGHT_BLUE(0x50A0E0), YELLOW(0xF0D030), LIME(0x80C020),
    PINK(0xF090B0), GRAY(0x505058), LIGHT_GRAY(0x9A9A96), CYAN(0x20908E), PURPLE(0x7A30B0), BLUE(0x30409A),
    BROWN(0x7A4E2A), GREEN(0x4E6A22), RED(0xA02828), BLACK(0x1E1E22),
}
