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
 * in the city's frame) that opens onto water, the sides whose farms a village nearby has taken
 * ([crowded]), and the [version] of the plans it was generated with: a capital keeps its own, so neither
 * what it built nor the people its plan set down change under it when the plans do.
 */
class Layout(val tier: Tier, val upper: Int, val citadel: Int, val harbour: Dir?, val crowded: Set<Dir> = setOf(), val version: Int = VERSION) {
    companion object {
        /**
         * The plans as capitals are generated now. 1: townsfolk keep every market stall (in capitals generated
         * before, version 0, villagers keep half of them). 2: the town works' lots are kept (CityPlan.worksLots: the
         * granary's, and two stall pitches on the market square) and the bakery is drawn with its other back corner
         * clear for a second oven, its flue's footing laid (M6).
         */
        const val VERSION = 2

        /** The plans before the town works: new capitals are drawn to them until the works are wired in (Works.ready). */
        const val BEFORE_WORKS = 1

        fun of(site: Site, crowded: Set<Dir> = setOf(), version: Int = VERSION): Layout =
            Layout(site.tier, site.upper, site.citadel, site.harbour, crowded, version)

        /** A middling city on a gentle hill: for previews. */
        val DEFAULT = Layout(Tier.LARGE, 6, 7, null)
    }
}
