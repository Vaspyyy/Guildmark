package io.github.vaspyyy.guildmark.village.live.links

import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Run
import net.minecraft.server.level.ServerLevel

/**
 * What a capital's wagon does for the village at the end of its road: carries the village's surplus in and the capital's
 * goods and news back (living-world/villages/design.md section 9.2). The names and signatures are the contract of design
 * section 19.3; part V7 fills them in and adds the two calls to `Caravans`. Until then the wagon is the capital's alone.
 */
object VillageWagons {
    /** The wagon [c] of [entry]'s capital sets out (`Caravans.depart`, after the capital's load is worked out): the village's share goes aboard. */
    fun load(level: ServerLevel, entry: Markets.Entry, c: CaravanRec) {
        // A trading house's wagon carries a ticket's goods, not the capital's (trader design 11.4, need 2)
        if (c.run == Run.HOUSE) return
    }

    /** The wagon [c] is back at the start of its leg (`Caravans.end`): the capital's exports and the supply from afar go to the village. */
    fun home(level: ServerLevel, c: CaravanRec) {
        // Nor does it bring the village a supply from afar for a trip that is a ticket's
        if (c.run == Run.HOUSE) return
    }
}
