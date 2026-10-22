package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.guild.NewsItem
import net.minecraft.server.level.ServerLevel

/**
 * The word a village has from outside, by the wagon or the pedlar (living-world/villages/design.md section 9.5). The names
 * and signatures are the contract of design section 19.3: part V7 writes it and part V6, the bellman, reads it. Until
 * then a village has heard nothing from outside.
 */
object VillageNews {
    /** The wagon from [capital] came in at [village] (the keys of their bells and centres): the capital's headlines are kept for the village's next cry. */
    fun carried(level: ServerLevel, village: Long, capital: Long) {}

    /** What the village [key] has heard and not yet cried, the newest first: none while there is nothing to say. */
    fun items(level: ServerLevel, key: Long): List<NewsItem> = emptyList()
}
