package io.github.vaspyyy.guildmark.city.plan

/**
 * The lie of the land round a city as the world made it, in the city's own frame (x east, z south, the
 * plaza's middle at 0, 0). Heights are standing heights (the first air over the ground, or over the
 * water) counted from the lower town's standing height, so 0 is level with the lower town.
 */
interface Land {
    /** Standing height of the land at a column; where water stands, of the water's surface. */
    fun height(x: Int, z: Int): Int

    /** Whether water stands over the ground there: a sea, lake, river or marsh. */
    fun wet(x: Int, z: Int): Boolean

    /** Standing height on still sea water. */
    val sea: Int

    companion object {
        /** Level land with no water anywhere: for previews. */
        val FLAT: Land = object : Land {
            override fun height(x: Int, z: Int): Int = 0
            override fun wet(x: Int, z: Int): Boolean = false
            override val sea: Int = -64
        }
    }
}

/**
 * How a capital is laid out on its site (see [Site]): its size, how far its old town stands over the
 * lower town ([upper]) and its citadel over the old town ([citadel]), the side ([harbour], east or west
 * in the city's frame) that opens onto water, and the sides whose farms a village nearby has taken
 * ([crowded]).
 */
class Layout(val tier: Tier, val upper: Int, val citadel: Int, val harbour: Dir?, val crowded: Set<Dir> = setOf()) {
    companion object {
        fun of(site: Site, crowded: Set<Dir> = setOf()): Layout = Layout(site.tier, site.upper, site.citadel, site.harbour, crowded)

        /** A middling city on a gentle hill: for previews. */
        val DEFAULT = Layout(Tier.LARGE, 6, 7, null)
    }
}
