package io.github.vaspyyy.guildmark.ambience

import io.github.vaspyyy.guildmark.network.CityDistrictPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/** Parts of a city that sound unlike the rest of it. */
enum class District {
    NONE,
    /** A market square or street of stalls: a busier crowd, criers calling oftener. */
    MARKET,
    /** Quays and docks: the water, gulls and moorings even where the town round them is small. */
    HARBOUR,
    /** The wall walks, towers and gatehouses: the wind blows even where the ground isn't far below. */
    WALLS,
    /** A cathedral close, noble quarter or garden: a hushed crowd, no criers or carts. */
    QUIET,
    /** A village's ground: the country's quiet (hens and birdsong by day, crickets and a far dog by night) and a thinner crowd than a city's. Told with capital = false. */
    VILLAGE,
    /** A village on its market day, from the stalls' opening to their closing: the market's murmur is heard as well. Told with capital = false. */
    VILLAGE_MARKET,
}

/**
 * Lets the server, which knows a city's plan, tell a player's city sounds (client/ambience/CityAmbience)
 * where they stand: in a capital at all, and in which district. Call [tell] as a player comes into a capital
 * or crosses into another district, and with capital = false as they leave it; the client keeps the last word
 * until it leaves the world. Without it the sounds still follow what they find round the player (the folk,
 * beds, workshops, bells and water); this only sharpens them. A village is told with capital = false and
 * [District.VILLAGE] or [District.VILLAGE_MARKET] (village/live/VillageVisits), which the village's own sounds
 * (client/ambience/VillageAmbience) go by.
 */
object CityDistricts {
    fun tell(player: ServerPlayer, capital: Boolean, district: District = District.NONE) {
        PacketDistributor.sendToPlayer(player, CityDistrictPayload(capital, district))
    }
}
