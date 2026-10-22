package io.github.vaspyyy.guildmark.village.live

import net.minecraft.server.level.ServerLevel

/**
 * What the villages may spend this tick, so that dozens of them cost about what the vanilla ones did (living-world/villages/
 * design.md section 13.5): the hour steps the capitals' run leaves, the columns the census may read, whether one more
 * villager may be taken in, and whether a unit of work of so many microseconds fits. Part V1 gives it enough for the census
 * and part V11 completes it. Until then it is closed: it hands out nothing, so work that asks waits and nothing runs away.
 */
object VillageBudget {
    /** A tick begins for the villages: the numbers it may spend are worked out afresh. */
    fun begin(level: ServerLevel) {}

    /** The hour steps left for the villages this run (at most 12, after the capitals'). */
    fun steps(): Int = 0

    /** [n] of those steps were used. */
    fun usedSteps(n: Int) {}

    /** The columns of the world the census may still read this tick. */
    fun columns(): Int = 0

    /** Whether one more villager may be taken in, in the village [key], now. */
    fun adoptOk(key: Long): Boolean = false

    /** Whether a unit of work of [micros] microseconds fits in what is left of this tick's share. */
    fun fits(micros: Int): Boolean = false
}
