package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.server.level.ServerLevel

/**
 * A household coming to a capital or leaving it: its [members] (resident ids), the [home] (lodging id) it comes to or
 * leaves, the market [day], and why: "replace" (someone died), "grow" (good times), "trade" (a trade the town lacks),
 * "hard" (leaving in hard times) or "return".
 */
class Household(val id: Int, val capital: Long, val home: Int, val members: List<Int>, val day: Long, val reason: String)

/**
 * Comings and goings (living-world/design-influence.md section 6): newcomers in through a gate for the dead and in
 * good times, and households leaving in hard times and coming back. The API between M6's parts (section 10). Until
 * Part D3 fills it in, nobody comes or goes.
 */
object Comings {
    /** Whether households come and go (townComings, with Living World). */
    fun enabled(): Boolean = false

    /** Households due in at the capital whose heart is [capital]. */
    fun due(level: ServerLevel, capital: Long): List<Household> = listOf()

    /** Households that came to [capital] in the last [days] market days. */
    fun arrived(level: ServerLevel, capital: Long, days: Int): List<Household> = listOf()

    /** [capital]'s households away in hard times. */
    fun away(level: ServerLevel, capital: Long): List<Household> = listOf()

    /** [capital]'s homes (lodging ids) left and not yet taken. */
    fun empty(level: ServerLevel, capital: Long): List<Int> = listOf()

    /** Sends for a [role] to [capital] for works [works]: their resident id once they're in, else -1. */
    fun hire(level: ServerLevel, capital: Long, role: TownRole, works: Int): Int = -1

    private val listeners = ArrayList<Listener>()

    /** Adds [listener], once, at setup. */
    fun listen(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    /** Hears households arrive, leave and come back. */
    interface Listener {
        fun arrived(level: ServerLevel, h: Household) {}
        fun left(level: ServerLevel, h: Household) {}
        fun returned(level: ServerLevel, h: Household) {}
    }
}
