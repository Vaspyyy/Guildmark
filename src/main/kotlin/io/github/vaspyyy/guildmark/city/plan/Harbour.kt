package io.github.vaspyyy.guildmark.city.plan

import java.util.Random

/**
 * A capital's harbour, on the side of the city ([Layout.harbour], east or west in its frame) that opens
 * onto water: quays along the shore below the walls, piers out over the water, warehouses, cranes and
 * the ships tied up at them, reached from the town through the water gate.
 */
class Harbour(private val plan: CityPlan) {
    /** Lays the harbour out on the plan: called once, after the walls and the town. */
    fun lay(random: Random) {}

    /** Draws whatever of the harbour falls in [canvas]'s clip. */
    fun draw(canvas: Canvas) {}
}
