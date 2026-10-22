package io.github.vaspyyy.guildmark.trade

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Where the Word and the Tally (build part B) meet the haul tickets (build part A), in one place, so that B can be built, tried and merged
 * before A is in, and the lead joins them with one edit when it is. Everything here answers its default until then: no ticket is
 * open anywhere, none is booked, and the Tally's Book button is greyed.
 *
 * **To join them** once part A's `Hauls` is in: set [HAULS] to true, and give each function below the one call its comment names.
 * Nothing else of B calls A.
 */
object TallyLinks {
    /** Whether part A is in: the tickets are real, and the Routes page's Book button can book them. Greyed while it is false. */
    val HAULS: Boolean = false

    /** The tickets open at the capital whose heart is [capital] now. Part A: `Hauls.open(level, capital)`. */
    fun open(level: ServerLevel, capital: BlockPos): List<Haul> = listOf()
}
