package io.github.vaspyyy.guildmark.village.live

import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Spot
import io.github.vaspyyy.guildmark.townsfolk.TownKind
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.village.live.market.VillageStall
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * A village's places, as its people's days read them where a capital's [CityPlaces] stand, built from what the census found
 * ([found]) and kept in memory for the session while the village is awake (a few kilobytes: a capital's are megabytes). With no
 * census yet it is a village known only by its [bell] and [name]: no buildings, stalls, wells, lamps, benches, fields or board, and
 * the green at the bell, which the daily-life code copes with as it does with a capital's odd building (no door: the sleeper's day
 * begins and ends at the bed; no seat: meals standing; no well: no water; no field: the nearest composter; no bell: the middle of
 * the houses).
 *
 * The buildings are the village's homes and the other buildings the census named, each by the id its record gave it ([ids], in the
 * order of the buildings found); a home that is gone is not here. The extras beyond the seam are for the parts that count what a
 * village has: the [jobs] (every job block, in a building or out of doors), the [towers] (the Archer Posts), the [barns] and [coops]
 * (pens of animals), the animals themselves and the [radius].
 */
class VillagePlaces(
    override val bell: BlockPos,
    override val name: String,
    val found: Found? = null,
    ids: List<Int> = emptyList(),
    val radius: Int = 20,
) : TownPlaces {
    override val centre: BlockPos get() = bell
    override val kind: TownKind get() = TownKind.VILLAGE

    override val lodgings: Map<Int, CityPlaces.Lodging> = if (found == null) emptyMap() else {
        val out = LinkedHashMap<Int, CityPlaces.Lodging>()
        found.buildings.forEachIndexed { i, b -> ids.getOrNull(i)?.let { out[it] = b.lodging(it) } }
        out
    }

    override val stalls: List<CityPlaces.Stall> get() = VillageStall.with(bell.asLong(), found?.stalls ?: emptyList())
    override val counters: List<Spot> get() = emptyList()
    override val wells: List<BlockPos> get() = found?.wells ?: emptyList()
    override val lamps: List<BlockPos> get() = found?.lamps ?: emptyList()
    override val benches: List<Spot> get() = found?.benches ?: emptyList()
    override val fields: List<CityPlaces.Field> get() = found?.fields ?: emptyList()
    override val woodpiles: List<CityPlaces.Woodpile> get() = emptyList()

    /** The middle of the green, at the height people stand; the bell where there is no census yet. */
    override val market: BlockPos get() = found?.green ?: bell

    /** The village's board, its bottom middle cell and the way it faces; null where there is none. */
    override val marketBoard: Spot? = found?.board?.let { Spot(it, found.boardFacing ?: Direction.NORTH) }

    override val harbour: Boolean get() = false

    /** Every job block, in a building or out of doors (a farmer's composter in a field, a fisher's barrel). */
    val jobs: List<CityPlaces.Station> get() = found?.jobs ?: emptyList()

    /** The Archer Posts, on towers and keeps. */
    val towers: List<BlockPos> get() = found?.towers ?: emptyList()

    /** Pens of animals in a fence: two or more cows, sheep or pigs make a barn, three or more hens a coop. */
    val barns: Int get() = found?.barns ?: 0
    val coops: Int get() = found?.coops ?: 0

    /** The cattle (cows, sheep, pigs) and hens in the village's box when it was counted. */
    val cattle: Int get() = found?.cattle ?: 0
    val hens: Int get() = found?.hens ?: 0

    /** The building whose ground [pos] is on, if any: the first whose box takes in its column and a block above or below. */
    override fun lodgingAt(pos: BlockPos): CityPlaces.Lodging? = lodgings.values.firstOrNull {
        pos.x >= it.box.minX() && pos.x <= it.box.maxX() && pos.z >= it.box.minZ() && pos.z <= it.box.maxZ() &&
            pos.y >= it.box.minY() - 1 && pos.y <= it.box.maxY() + 1
    }

    /** Every building of [kinds], nearest [pos] first. */
    override fun nearest(pos: BlockPos, kinds: Collection<String>): List<CityPlaces.Lodging> =
        lodgings.values.filter { it.kind in kinds }.sortedBy { it.box.center.distSqr(pos) }
}
