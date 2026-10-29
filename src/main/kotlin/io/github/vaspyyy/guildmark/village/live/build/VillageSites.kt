package io.github.vaspyyy.guildmark.village.live.build

import io.github.vaspyyy.guildmark.homestead.BuilderCrew
import io.github.vaspyyy.guildmark.homestead.Worksite
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The building sites of the villages, as the labourers' dispatch finds them (living-world/villages/design.md section 12.3):
 * townsfolk/Labour sends a village's residents lent to its site here, where a capital's go to its works. The name and signature of
 * [workAt] are the contract of design section 19.3.
 */
object VillageSites {
    /** The site a crew is building at in the village whose bell is [centre], or null if there is none (or its land is not loaded). */
    fun workAt(level: ServerLevel, centre: Long): Worksite? {
        if (!VillageBuilds.enabled()) return null
        return VillageBuilds.siteFor(level, centre)?.takeIf { !it.finished }
    }

    /** Where the residents lent to that site report for work: by its gate. */
    fun reportAt(level: ServerLevel, centre: Long): BlockPos? = workAt(level, centre)?.let { BlockPos.containing(it.gate()) }

    /** Gives [p], lent to the site of the village at [centre], their work: a builder of its crew, while there is work and someone near to see it. */
    fun labour(level: ServerLevel, p: Townsperson, centre: Long): Director? {
        val site = workAt(level, centre) as? VillageSite ?: return null
        val wanted = VillageBuilds.watched(site.id) && site.opened() && site.workday() && !site.onBreak() && !site.finished
        return BuilderCrew.lend(level, site, p, wanted)
    }
}
