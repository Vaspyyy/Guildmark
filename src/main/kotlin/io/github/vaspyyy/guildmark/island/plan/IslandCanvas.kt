package io.github.vaspyyy.guildmark.island.plan

/**
 * Where an island gets drawn: one generating chunk at a time in the world, or all at once in a preview. Drawing has
 * to come out the same however it's cut up, so everything random comes from the island's seed, never from the order
 * the chunks come in.
 */
interface IslandCanvas {
    /** The columns this pass may draw in, inclusive; anything outside is ignored. */
    val minX: Int
    val maxX: Int
    val minZ: Int
    val maxZ: Int

    /** The y of the highest solid block the world had in a column before the island came: the sea bed (ice doesn't count). */
    fun floor(x: Int, z: Int): Int

    fun set(x: Int, y: Int, z: Int, b: Bk)

    /** Is there room here for a leaf or a plant: nothing but air, water or another plant? */
    fun open(x: Int, y: Int, z: Int): Boolean

    /** Clears whatever the sea had piled over a column from [y] up (an iceberg run aground), now land stands there. */
    fun clearAbove(x: Int, z: Int, y: Int) {}

    /** A chest (or a barrel, if [barrel]) facing [facing], filled from the loot table [loot] ("guildmark:chests/island/wreck"). */
    fun chest(x: Int, y: Int, z: Int, facing: Dir, loot: String, barrel: Boolean = false)

    /** Fills whatever already stands at [x], [y], [z] that holds things (a pot, suspicious sand) from the loot table [table]. */
    fun loot(x: Int, y: Int, z: Int, table: String) {}

    /**
     * Something living: [what] names it (an entity id, "guildmark:gull", or one with a role after a #,
     * "minecraft:pillager#smuggler", for one of the game's creatures standing in for someone of ours and named for it)
     * standing at [x], [y], [z] facing [yaw] degrees. Only a real world gets them, never a far-view stand-in.
     */
    fun spawn(what: String, x: Double, y: Double, z: Double, yaw: Float = 0f)

    /** A block needing its block entity told something once it's placed: [what] says what (see WorldIslandCanvas). */
    fun mark(x: Int, y: Int, z: Int, what: String) {}

    fun touches(xa: Int, za: Int, xb: Int, zb: Int): Boolean = xb >= minX && xa <= maxX && zb >= minZ && za <= maxZ

    fun contains(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ
}
