package io.github.vaspyyy.guildmark.village.live.market

import io.github.vaspyyy.guildmark.city.plan.M
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
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
 * - **whoever keeps a stall today** keeps the stall they stand at. Who that is is the village's crew's ([Hands.of], worked out by the
 *   days' [io.github.vaspyyy.guildmark.village.live.days.VillageHands]): the one rule, so the keeper the days send to a stall is the one
 *   whose counter opens there. A stall with nobody to keep it stays shut for the day, which is true and is a thing to see (a hamlet
 *   of eight cannot staff five stalls). The shop is read from the stall's counter block ([shopOf]);
 * - a **farmer** standing at their composter keeps the produce counter (the farm gate);
 * - a **fisher** standing at their bank or barrel keeps the fish and fruit counter.
 *
 * Behind this is the pedlar's own stall ([cart]): while the pedlar is at the green, the village's places list a stall of the market
 * hall's kind at the cart (index [CART]), so the shopping folk go to it with no code of their own (section 7.5). Nothing of it is saved.
 */
object VillageStall {
    /** How near (squared, in blocks) someone stands to a composter, a bank or a stall's keeper spot to be keeping it. */
    private const val NEAR = 6.0

    /** The shop [p] keeps in the village whose places are [places], standing where they are; null if they keep none. */
    fun kept(p: Townsperson, places: TownPlaces): Shop? {
        val here = p.blockPosition()
        // At the stall they keep today; away from it (their own trade before ten), what their trade keeps
        stall(p, places)?.let { stall -> if (stall.keeper.pos.distSqr(here) <= NEAR) return shopOf(stall.trade) }
        return when (p.role) {
            TownRole.FARMER -> if (at(p, here)) Shop.PRODUCE else null
            TownRole.FISHER -> if (at(p, here)) Shop.FISH_AND_FRUIT else null
            else -> null
        }
    }

    /** The stall of the village's own (not the pedlar's) that [p] keeps today, if they keep one. */
    private fun stall(p: Townsperson, places: TownPlaces): CityPlaces.Stall? {
        val level = p.level() as? ServerLevel ?: return null
        val life = p.life
        val index = if (life != null && life.places === places && life.keeper >= 0) life.keeper else {
            val id = life?.resident?.id ?: Residents.get(level).of(p)?.id ?: return null
            Hands.of(level, places).keepers[id] ?: return null
        }
        return places.stalls.getOrNull(index)?.takeIf { it.index != CART }
    }

    /**
     * The shop a village's stall with [trade] for its counter keeps (section 7.6): the market's own stall kinds as [Shop.of] reads them, a
     * grindstone's or smithing table's the smithy, a brewing stand's the chandler's, and a counter with no shop of its own (a cartography
     * table, a stonecutter) the market hall's.
     */
    fun shopOf(trade: M): Shop = Shop.of(trade) ?: when (trade) {
        M.GRINDSTONE, M.SMITHING_TABLE -> Shop.SMITHY
        M.BREWING_STAND -> Shop.CHANDLER
        else -> Shop.HALL
    }

    /**
     * The places of the village [p] stands in, if they are one of a village's people: how a counter finds its village when the days
     * have not given them a life of their own to look it up by (Village Folk's days off). Null for anyone else, and a passer-by's is
     * never a village's.
     */
    fun places(p: Townsperson): TownPlaces? {
        if (p.resident < 0 || p.crowd) return null
        val level = p.level() as? ServerLevel ?: return null
        val rec = Villages.at(level, p.blockPosition()) ?: return null
        return Villages.places(level, rec.key)
    }

    /**
     * A life to ask the hour of for [p], a village's resident whose [io.github.vaspyyy.guildmark.townsfolk.Days.life] has none: made fresh
     * each time, kept nowhere (it must not give them a day; that is the days' to do). Null if they are no village's resident or are dead.
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

    /** The pedlar's stall at the green, for each village whose bell is the key, while the pedlar is at the cart. */
    private val carts = HashMap<Long, CityPlaces.Stall>()

    /** Sets up (or, with null, takes down) the pedlar's stall at the green of the village whose bell is [bell]. */
    fun cart(bell: Long, stall: CityPlaces.Stall?) {
        if (stall == null) carts.remove(bell) else carts[bell] = stall
    }

    /** The pedlar's stall now at [bell]'s green, if the pedlar is there. */
    fun cart(bell: Long): CityPlaces.Stall? = carts[bell]

    /**
     * [base], the stalls the census found, with the pedlar's after them while the pedlar is at the green: a stall of the market hall's
     * kind (index [CART]), its keeper's spot at the cart's side, its customers' a step across, so that the days' shopping takes the
     * villagers to it with no code of its own (the villages design, section 7.5). The census's stalls keep their places in the list.
     */
    fun with(bell: Long, base: List<CityPlaces.Stall>): List<CityPlaces.Stall> {
        val cart = carts[bell] ?: return base
        return base + cart
    }

    fun forget() {
        carts.clear()
    }

    /** The index of the pedlar's stall: not one of the village's, and kept by nobody but the pedlar. */
    const val CART = -1
}
