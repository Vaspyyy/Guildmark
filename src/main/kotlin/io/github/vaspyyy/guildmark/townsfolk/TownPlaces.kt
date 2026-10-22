package io.github.vaspyyy.guildmark.townsfolk

import net.minecraft.core.BlockPos

/** Which kind of town a [TownPlaces] is the places of. */
enum class TownKind {
    /** One of Guildmark's capitals, laid out from its city plan (see [CityPlaces]). */
    CAPITAL,

    /** A village, counted from what stands round its bell (see [io.github.vaspyyy.guildmark.village.live.VillagePlaces]). */
    VILLAGE,
}

/**
 * A town's places as its people's days read them: where its folk live, shop, draw water, sit and work, and the middle
 * of it all. A capital's are [CityPlaces], worked out from its city plan; a village's are
 * [io.github.vaspyyy.guildmark.village.live.VillagePlaces], counted from what stands round its bell. The shared
 * daily-life code ([Days], [Acts], [Trades], the scenes, the shops) asks this wherever a town's centre, name or a
 * list of its places will do, and so runs on either; what needs a capital's plan (its census, its streets and
 * walls) asks [CityPlaces] and gets none for a village (`places as? CityPlaces`).
 */
interface TownPlaces {
    /** A capital's centre; a village's bell. The key a town's records are kept under. */
    val centre: BlockPos

    /** The town's name, as [io.github.vaspyyy.guildmark.village.VillageNames] gives it from where its board stands. */
    val name: String

    val kind: TownKind

    /** The bell people are called to. */
    val bell: BlockPos

    /** Its buildings, by the id [Resident.home] gives. */
    val lodgings: Map<Int, CityPlaces.Lodging>

    /** The market's stalls. */
    val stalls: List<CityPlaces.Stall>

    /** Where those who keep no stall stand to serve, facing their customers. */
    val counters: List<Spot>
    val wells: List<BlockPos>
    val lamps: List<BlockPos>
    val benches: List<Spot>
    val fields: List<CityPlaces.Field>
    val woodpiles: List<CityPlaces.Woodpile>

    /** The square, or the green: its middle at its standing height. */
    val market: BlockPos

    /** Where the crier chalks and cries (the board's bottom middle cell and the way it faces); null where there is no board. */
    val marketBoard: Spot?

    /** Whether the town has a harbour, with a quay (and fish market) below its walls. */
    val harbour: Boolean

    /** The building whose ground [pos] is on, if any. */
    fun lodgingAt(pos: BlockPos): CityPlaces.Lodging?

    /** Every lodging of [kinds], nearest [pos] first. */
    fun nearest(pos: BlockPos, kinds: Collection<String>): List<CityPlaces.Lodging>
}
