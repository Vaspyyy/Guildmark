package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * The country round a capital, past the foot of its glacis: the roads out from the gates, fields and
 * orchards, pastures, farmsteads with their barns, a windmill or two and hamlets along the roads, all
 * following the lie of the land.
 */
class Outskirts(private val plan: CityPlan) {
    /** Lays the farms out on the plan: called once, after the walls, the town and the harbour. */
    fun lay(random: Random) {}

    /** Draws whatever of the farms falls in [canvas]'s clip. */
    fun draw(canvas: Canvas) {}
}
