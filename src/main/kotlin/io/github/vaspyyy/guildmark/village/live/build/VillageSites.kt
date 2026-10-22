package io.github.vaspyyy.guildmark.village.live.build

import io.github.vaspyyy.guildmark.homestead.Worksite
import net.minecraft.server.level.ServerLevel

/**
 * The building sites of the villages, as the labourers' dispatch finds them (living-world/villages/design.md section 12.3).
 * The name and signature are the contract of design section 19.3; part V9 fills it in. Until then a village has no site.
 */
object VillageSites {
    /** The site a crew is building at in the village whose bell is [centre], or null if there is none. */
    fun workAt(level: ServerLevel, centre: Long): Worksite? = null
}
