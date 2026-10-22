package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Villages
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

    /**
     * The places of the village [p] stands in, if they are one of a village's people: how a counter finds its village before the days
     * (V3) give its people a life of their own to look it up by. Null for anyone else, and a passer-by's is never a village's.
     */
    fun places(p: Townsperson): TownPlaces? {
        if (p.resident < 0 || p.crowd) return null
        val level = p.level() as? ServerLevel ?: return null
        val rec = Villages.at(level, p.blockPosition()) ?: return null
        return Villages.places(level, rec.key)
    }

    /**
     * A life to ask the hour of for [p], a village's resident whose [Days.life] has none yet: made fresh each time, kept nowhere (it
     * must not give them a day of a capital's kind; that is V3's to do). Null if they are no village's resident or are dead.
     */
    fun life(p: Townsperson): Life? {
        val places = places(p) ?: return null
        val level = p.level() as? ServerLevel ?: return null
        val resident = Residents.get(level).of(p)?.takeIf { it.alive } ?: return null
        return Life(resident, places, null)
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

    /** The pedlar's stall at the green, for each village whose bell is the key, while he is at his cart. */
    private val carts = HashMap<Long, CityPlaces.Stall>()

    /** Sets up (or, with null, takes down) the pedlar's stall at the green of the village whose bell is [bell]. */
    fun cart(bell: Long, stall: CityPlaces.Stall?) {
        if (stall == null) carts.remove(bell) else carts[bell] = stall
    }

    /** The pedlar's stall now at [bell]'s green, if he is there. */
    fun cart(bell: Long): CityPlaces.Stall? = carts[bell]

    /**
     * [base], the stalls the census found, with the pedlar's added while he is at the green: a stall of the market hall's kind (index
     * [CART]), its keeper's spot at the cart's side, its customers' a step across, so that the days' shopping takes the villagers to it
     * with no code of its own (the villages design, section 7.5).
     */
    fun with(bell: Long, base: List<CityPlaces.Stall>): List<CityPlaces.Stall> {
        val cart = carts[bell] ?: return base
        return base + cart
    }

    fun forget() {
        rosters.clear()
        carts.clear()
    }

    /** The index of the pedlar's stall: not one of the village's. */
    const val CART = -1
}
