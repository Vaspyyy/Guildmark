package io.github.vaspyyy.guildmark.island.plan

import java.util.concurrent.ConcurrentHashMap

/*
 * An island is drawn in the world's own frame (x east, z south, y up, in blocks), with no Minecraft types, so the
 * same drawing can go into a generating chunk or into a preview. Blocks are named the way Minecraft names them,
 * "oak_log" with its state as properties, and the canvas makes the real block from the name.
 */

/** A block by its id (minecraft: unless it says otherwise) and its state properties. One of each is ever made. */
class Bk private constructor(val id: String, val props: List<Pair<String, String>>) {
    /** "minecraft:oak_log[axis=x]": how Minecraft writes it, and how blocks are told apart. */
    val key: String = if (props.isEmpty()) id else id + props.joinToString(",", "[", "]") { "${it.first}=${it.second}" }

    /** This block with [property] set to [value]. */
    fun with(property: String, value: String): Bk = of(id, props.filter { it.first != property } + (property to value))

    fun with(property: String, value: Int): Bk = with(property, value.toString())

    fun with(property: String, value: Boolean): Bk = with(property, value.toString())

    fun facing(dir: Dir): Bk = with("facing", dir.id)

    /** Has it got [property] set to [value]? */
    fun has(property: String, value: String): Boolean = props.any { it.first == property && it.second == value }

    val name: String get() = id.substringAfter(':')

    override fun toString(): String = key

    companion object {
        private val made = ConcurrentHashMap<String, Bk>()

        fun of(id: String, vararg props: Pair<String, String>): Bk = of(if (':' in id) id else "minecraft:$id", props.toList())

        private fun of(id: String, props: List<Pair<String, String>>): Bk {
            val sorted = props.sortedBy { it.first }
            val key = if (sorted.isEmpty()) id else id + sorted.joinToString(",", "[", "]") { "${it.first}=${it.second}" }
            return made.getOrPut(key) { Bk(id, sorted) }
        }

        val AIR = of("air")
        val WATER = of("water")
    }
}

/** A direction across the ground. North is -z. */
enum class Dir(val dx: Int, val dz: Int, val id: String) {
    N(0, -1, "north"), E(1, 0, "east"), S(0, 1, "south"), W(-1, 0, "west");

    /** A quarter turn clockwise, seen from above. */
    val cw: Dir get() = entries[(ordinal + 1) % 4]
    val ccw: Dir get() = entries[(ordinal + 3) % 4]
    val opposite: Dir get() = entries[(ordinal + 2) % 4]
    val alongX: Boolean get() = dx != 0

    /** The axis a log lying this way runs along. */
    val axis: String get() = if (alongX) "x" else "z"

    companion object {
        /** The direction nearest to the heading [radians] (0 east, pi/2 south). */
        fun nearest(radians: Double): Dir {
            val q = Math.floorMod(Math.round(radians / (Math.PI / 2)).toInt(), 4)
            return arrayOf(E, S, W, N)[q]
        }
    }
}
