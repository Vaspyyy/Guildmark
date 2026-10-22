package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.CapitalPiece
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos

/**
 * The town a market serves as its counters need to know it: the key its market is kept under ([centre]: a capital's
 * centre, a village's bell) and the [name] on its screen. A capital's counters and a village's are the same screen over
 * the same market, so [Stalls] asks this and not for a [CapitalPiece] (the villages design, section 7.6).
 */
class MarketTown(val centre: BlockPos, val name: String) {
    companion object {
        fun of(capital: CapitalPiece): MarketTown = MarketTown(capital.center, VillageNames.of(capital.boardAnchor))

        fun of(places: TownPlaces): MarketTown = MarketTown(places.centre, places.name)
    }
}
