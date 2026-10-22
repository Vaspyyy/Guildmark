package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Who keeps which counter in a village (the villages design, section 7.6). A baker at the oven keeps the bakery and a smith at the
 * anvil the smithy as they do anywhere ([Shop.kept]); a village adds three more:
 *
 * - a **farmer** standing at their composter keeps the produce counter (the farm gate);
 * - a **fisher** standing at their bank or barrel keeps the fish and fruit counter;
 * - **whoever keeps a stall today** keeps the stall they stand at. For each stall of the village, in order, the first resident whose
 *   craft (the vanilla profession they had) is the stall's shop's, else the first citizen who has no duty already (not a carter
 *   or the crier); a stall with nobody left to keep it stays shut for the day, which is true and is a thing to see (a hamlet of
 *   eight cannot staff five stalls). A keeper is a maker, not a new person: no bed is wanted and the home count does not change.
 *
 * The keepers are worked out once for each town day ([keepers]) and are the same whoever asks; the days (V3) read them to send the
 * keeper to their stall from ten to four, and eight on market day.
 */
object VillageStall {
    /** How near (squared, in blocks) someone stands to a composter, a bank or a stall's keeper spot to be keeping it. */
    private const val NEAR = 6.0

    /** One town's keepers for one day: resident id to the index of the stall they keep. */
    private class Roster(val day: Long, val stalls: Int, val keepers: Map<Int, Int>)

    private val rosters = HashMap<Long, Roster>()

    /** The shop [p] keeps in the village whose places are [places], standing where they are; null if they keep none. */
    fun kept(p: Townsperson, places: TownPlaces): Shop? {
        val here = p.blockPosition()
        when (p.role) {
            TownRole.FARMER -> return if (at(p, here)) Shop.PRODUCE else null
            TownRole.FISHER -> return if (at(p, here)) Shop.FISH_AND_FRUIT else null
            else -> {}
        }
        val life = p.life ?: return null
        val level = p.level() as? ServerLevel ?: return null
        val index = keepers(level, places)[life.resident.id] ?: return null
        val stall = places.stalls.firstOrNull { it.index == index } ?: return null
        if (stall.keeper.pos.distSqr(here) > NEAR) return null
        return Shop.at(places, stall.keeper.pos)
    }

    /** Whether [p], at [here], stands at the work block or post they are at their trade by. */
    private fun at(p: Townsperson, here: BlockPos): Boolean {
        val spot = p.post ?: p.work ?: return false
        return spot.distSqr(here) <= NEAR
    }

    /** The residents of [places] who keep a stall today, by resident id to the stall's index; the same all day. */
    fun keepers(level: ServerLevel, places: TownPlaces): Map<Int, Int> {
        val key = places.centre.asLong()
        val day = Days.dayOf(level.overworldClockTime)
        val stalls = places.stalls.filter { it.index >= 0 }.sortedBy { it.index }
        rosters[key]?.let { if (it.day == day && it.stalls == stalls.size) return it.keepers }
        val out = LinkedHashMap<Int, Int>()
        if (stalls.isNotEmpty()) {
            val crew = Hands.of(level, places)
            val living = Residents.get(level).byCentre(places.centre).filter { it.alive && it.role != TownRole.CHILD }.sortedBy { it.id }
            for (stall in stalls) {
                val shop = Shop.of(stall.trade)
                val keeper = living.firstOrNull { it.id !in out && shop != null && fits(it.craft, shop) }
                    ?: living.firstOrNull { it.id !in out && it.role == TownRole.CITIZEN && it.id !in crew.carters && it.id != crew.crier && it.id !in crew.labourers }
                    ?: continue
                out[keeper.id] = stall.index
            }
        }
        rosters[key] = Roster(day, stalls.size, out)
        return out
    }

    /** Whether the vanilla profession [craft] ("minecraft:shepherd") is the trade of [shop]'s stall. */
    fun fits(craft: String, shop: Shop): Boolean {
        val trade = craft.substringAfter(':')
        return when (shop) {
            Shop.PRODUCE -> trade == "farmer"
            Shop.FISH_AND_FRUIT -> trade == "fisherman"
            Shop.BUTCHER -> trade == "butcher"
            Shop.CLOTH -> trade == "shepherd"
            Shop.FLETCHER -> trade == "fletcher"
            Shop.CHANDLER -> trade == "leatherworker"
            else -> false
        }
    }

    /** The stalls with nobody to keep them today: the shut ones, by the stall's index. */
    fun unkept(level: ServerLevel, places: TownPlaces): List<Int> {
        val kept = keepers(level, places).values.toSet()
        return places.stalls.filter { it.index >= 0 && it.index !in kept }.map { it.index }
    }

    fun forget() {
        rosters.clear()
    }
}
