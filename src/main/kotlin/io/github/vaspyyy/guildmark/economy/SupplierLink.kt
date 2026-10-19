package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.party.Parties
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Where a player's own stall (see [OwnStalls]) and a homestead's shipping hear of supplier standing, which is kept
 * elsewhere (the capitals' standing and orders, M3 part 1): three hooks, each a function value its keeper sets once
 * at start-up. A capital is known by its centre, as [Markets] keys its market. Until they're set, the defaults let
 * everything here be tried: every player counts as a Purveyor everywhere, Marks earned go unrecorded, and nobody has
 * bought anything anywhere.
 */
object SupplierLink {
    /** The rank renting a table in a capital's market hall takes. */
    const val TRUSTED = 3

    /** The rank whose stall keep is lower. */
    const val PURVEYOR = 4

    /** [player]'s rank as a supplier of the capital whose centre is [capital]: 0 a stranger, up to [PURVEYOR]. */
    @JvmStatic
    var rank: (level: ServerLevel, player: UUID, capital: BlockPos) -> Int = { _, _, _ -> PURVEYOR }

    /**
     * [player] has been paid [marks] Guild Marks for goods sold to the market of the capital whose centre is
     * [capital], without trading at its stalls by hand: a homestead's shipment, or their own stall's takings as
     * they collect them. Standing counts these as it counts selling at the stalls (a point a Mark); what came
     * of goods the player had bought at that market in the last week is already left out of [marks].
     */
    @JvmStatic
    var earned: (level: ServerLevel, player: UUID, capital: BlockPos, marks: Int) -> Unit = { _, _, _, _ -> }

    /** How many of [good] [player] has bought at the market of the capital whose centre is [capital] in the last seven days. */
    @JvmStatic
    var bought: (level: ServerLevel, player: UUID, capital: BlockPos, good: Good) -> Int = { _, _, _, _ -> 0 }

    /**
     * How many of [good] [player] and everyone in their party have bought at that market in the last seven days (see
     * [bought]): what one of them bought there and handed to another is kept off a stall or out of standing all the same.
     */
    fun boughtByParty(level: ServerLevel, player: UUID, capital: BlockPos, good: Good): Int {
        val party = Parties.get(level.server).of(player)?.members ?: listOf()
        return (party + player).distinct().sumOf { bought(level, it, capital, good) }
    }
}
