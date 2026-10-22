package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import net.minecraft.core.BlockPos

/**
 * A village's places, as its people's days will read them where a capital's [CityPlaces] stand. Empty for now: a village
 * known only by its [bell] and [name], with no buildings, stalls, wells, lamps, benches, fields or board, and the green
 * at the bell. The census that fills it in comes with the village's record (the villages design, part V1).
 */
class VillagePlaces(override val bell: BlockPos, override val name: String) : TownPlaces {
    override val centre: BlockPos get() = bell
    override val kind: TownKind get() = TownKind.VILLAGE
    override val lodgings: Map<Int, CityPlaces.Lodging> get() = emptyMap()
    override val stalls: List<CityPlaces.Stall> get() = emptyList()
    override val counters: List<Spot> get() = emptyList()
    override val wells: List<BlockPos> get() = emptyList()
    override val lamps: List<BlockPos> get() = emptyList()
    override val benches: List<Spot> get() = emptyList()
    override val fields: List<CityPlaces.Field> get() = emptyList()
    override val woodpiles: List<CityPlaces.Woodpile> get() = emptyList()
    override val market: BlockPos get() = bell
    override val marketBoard: Spot? get() = null
    override val harbour: Boolean get() = false

    override fun lodgingAt(pos: BlockPos): CityPlaces.Lodging? = null

    override fun nearest(pos: BlockPos, kinds: Collection<String>): List<CityPlaces.Lodging> = emptyList()
}
