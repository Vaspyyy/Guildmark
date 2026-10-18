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
enum class Loot { HOUSE, TAVERN, SMITH, TEMPLE, LIBRARY, BARRACKS, STABLES, TREASURY, MARKET, HARBOUR }

/** Who lives in the city, and the beasts on its farms. */
enum class Spawn { VILLAGER, GOLEM, RECEPTIONIST, HORSE, CAT, COW, SHEEP, PIG, CHICKEN }

/** The townsfolk's trades, as far as a city's plan knows them (townsfolk/TownRole). */
enum class Folk {
    CITIZEN, SMITH, BAKER, MERCHANT, GUARD, NOBLE, INNKEEPER, BARD, PRIEST, CHILD,

    /** Works the fields round a composter: give them the composter as their work. */
    FARMER,

    /** Fishes off a quay or pier: give them the edge to stand on as their post, facing the water. */
    FISHER,
}

/** A block in the city's frame. */
data class At(val x: Int, val y: Int, val z: Int) {
    fun up(dy: Int): At = if (dy == 0) this else At(x, y + dy, z)
}

/**
 * Where a city gets drawn. A city is drawn one piece of land at a time ([clip]), so drawing has to
 * come out the same however often it's repeated: everything random comes from the city's seed.
 */
interface Canvas {
    /** The columns this pass can draw in; anything outside is ignored. */
    val clip: Rect

    fun set(x: Int, y: Int, z: Int, b: B)

    /**
     * Lays the ground in a column with its standing height at [y]: [surface] just under it (at y - 1),
     * solid [fill] under that down to the land, and nothing the land grew left standing from y up.
     */
    fun ground(x: Int, z: Int, y: Int, surface: B, fill: B)

    /** Outside the walls: the land's own ground made to stand at [y] (cut down or built up), with nothing left standing on it. */
    fun clearing(x: Int, z: Int, y: Int)

    fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot)

    /** A quest board facing [facing], with its anchor (bottom middle) cell at [x], [y], [z]. */
    fun board(x: Int, y: Int, z: Int, facing: Dir)

    /** A market board facing [facing], with its anchor (bottom middle) cell at [x], [y], [z]; only the world draws one. */
    fun marketBoard(x: Int, y: Int, z: Int, facing: Dir) {}

    fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir)

    /**
     * One of the townsfolk, a [folk] who lives here, standing at [at]: the block they [work] at (an anvil, an
     * oven, a workbench), where they stand to work facing [facing] when that's a [post] (a stall's counter, the
     * bar, a gate), and their [home] if it isn't where they stand.
     */
    fun resident(folk: Folk, at: At, work: At? = null, post: At? = null, facing: Dir? = null, home: At? = null)

    /**
     * A display of the market's goods showing [wares], its front (where the buyers stand) to [facing], on a spot
     * that held [was] before capitals had displays (null: nothing). The world sets it up for the capital's market,
     * or with displays switched off puts down [was] instead.
     */
    fun wares(x: Int, y: Int, z: Int, wares: Wares, facing: Dir, was: B? = null) = set(x, y, z, B(M.WARES, facing, v = wares.ordinal))

    fun touches(r: Rect): Boolean = clip.intersects(r)

    /**
     * Whether the mill whose sails or wheel turn on [hub] is built of blocks here, standing still, as the mills were before
     * they turned: on a canvas that can't have the hub blocks the game draws turning (a far-view mod's stand-in world), for
     * a windmill whose sails would sweep through something next to it (see [MillSite]), and for working out what an older
     * capital was built with.
     */
    fun blockMill(hub: At): Boolean = false
}

/** Another canvas, raised [dy] blocks: for drawing something written for standing height 0 on a terrace. */
class Raised(private val canvas: Canvas, private val dy: Int) : Canvas {
    override val clip: Rect get() = canvas.clip
    override fun set(x: Int, y: Int, z: Int, b: B) = canvas.set(x, y + dy, z, b)
    override fun ground(x: Int, z: Int, y: Int, surface: B, fill: B) = canvas.ground(x, z, y + dy, surface, fill)
    override fun clearing(x: Int, z: Int, y: Int) = canvas.clearing(x, z, y + dy)
    override fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: Loot) = canvas.chest(x, y + dy, z, facing, loot)
    override fun board(x: Int, y: Int, z: Int, facing: Dir) = canvas.board(x, y + dy, z, facing)
    override fun marketBoard(x: Int, y: Int, z: Int, facing: Dir) = canvas.marketBoard(x, y + dy, z, facing)
    override fun spawn(what: Spawn, x: Int, y: Int, z: Int, facing: Dir) = canvas.spawn(what, x, y + dy, z, facing)
    override fun resident(folk: Folk, at: At, work: At?, post: At?, facing: Dir?, home: At?) =
        canvas.resident(folk, at.up(dy), work?.up(dy), post?.up(dy), facing, home?.up(dy))
    override fun wares(x: Int, y: Int, z: Int, wares: Wares, facing: Dir, was: B?) = canvas.wares(x, y + dy, z, wares, facing, was)
    override fun blockMill(hub: At): Boolean = canvas.blockMill(hub.up(dy))
}
