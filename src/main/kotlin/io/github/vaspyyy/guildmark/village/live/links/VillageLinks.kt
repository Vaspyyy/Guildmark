package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.village.live.Link
import net.minecraft.server.level.ServerLevel

/**
 * Which capitals' wagon roads end at which village (living-world/villages/design.md section 9.1). The names and
 * signatures are the contract of design section 19.3; part V7 fills them in. Until then no village is linked, so a
 * village is served by the pedlar alone and the capitals' wagons run as they always have.
 */
object VillageLinks {
    /** Works the links out again: for each capital with a market and each of its legs, the village at the far end (once a day, and when a road is finished). */
    fun refresh(level: ServerLevel) {}

    /** The links that end at the village [key]: none while there is nothing to say. */
    fun linked(level: ServerLevel, key: Long): List<Link> = emptyList()
}
