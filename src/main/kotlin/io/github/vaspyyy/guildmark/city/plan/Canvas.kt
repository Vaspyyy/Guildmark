package io.github.vaspyyy.guildmark.city.plan

/** Columns from [x0], [z0] to [x1], [z1], all inclusive. */
data class Rect(val x0: Int, val z0: Int, val x1: Int, val z1: Int) {
    val width: Int get() = x1 - x0 + 1
    val depth: Int get() = z1 - z0 + 1

    fun contains(x: Int, z: Int): Boolean = x in x0..x1 && z in z0..z1
    fun intersects(o: Rect): Boolean = o.x1 >= x0 && o.x0 <= x1 && o.z1 >= z0 && o.z0 <= z1
    fun grow(by: Int): Rect = Rect(x0 - by, z0 - by, x1 + by, z1 + by)
}

/** What a chest holds; the style picks the loot table. */
enum class Loot { HOUSE, TAVERN, SMITH, TEMPLE, LIBRARY, BARRACKS, STABLES, TREASURY, MARKET }

/** Who lives in the city. */
enum class Spawn { VILLAGER, GOLEM, RECEPTIONIST, HORSE, CAT }

/** The townsfolk's trades, as far as a city's plan knows them (townsfolk/TownRole). */
enum class Folk { CITIZEN, SMITH, BAKER, MERCHANT, GUARD, NOBLE, INNKEEPER, BARD, PRIEST, CHILD }

/** A block in the city's frame. */
data class At(val x: Int, val y: Int, val z: Int)

/**
 * Where a city gets drawn. A city is drawn one piece of land at a time ([clip]), so drawing has to
 * come out the same however often it's repeated: everything random comes from the city's seed.
 */
interface Canvas {
    /** The columns this pass can draw in; anything outside is ignored. */
    val clip: Rect

    fun set(x: Int, y: Int, z: Int, b: B)

    /**
     * Lays the ground in a column: [surface] just under standing height (y = -1), solid [fill] under that
     * down to the land, and nothing the land grew left standing above it.
     */
    fun ground(x: Int, z: Int, surface: B, fill: B)

    /** Outside the walls: the land's own ground levelled off, with nothing left standing on it. */
    fun clearing(x: Int, z: Int)

    fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot)

    /** A quest board facing [facing], with its anchor (bottom middle) cell at [x], [y], [z]. */
    fun board(x: Int, y: Int, z: Int, facing: Dir)

    fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir)

    /**
     * One of the townsfolk, a [folk] who lives here, standing at [at]: the block they [work] at (an anvil, an
     * oven, a workbench), where they stand to work facing [facing] when that's a [post] (a stall's counter, the
     * bar, a gate), and their [home] if it isn't where they stand.
     */
    fun resident(folk: Folk, at: At, work: At? = null, post: At? = null, facing: Dir? = null, home: At? = null)

    fun touches(r: Rect): Boolean = clip.intersects(r)
}
