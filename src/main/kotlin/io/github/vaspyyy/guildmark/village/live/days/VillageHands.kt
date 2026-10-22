package io.github.vaspyyy.guildmark.village.live.days

import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Hands
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownPlaces
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.village.Festivals
import io.github.vaspyyy.guildmark.village.live.Villages
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * A village's crew for a day (living-world/villages/design.md 5.4 and 7.6), as [Hands] gives a capital's: who holds the bell,
 * who pins the board's note, who carts, which stall each keeper keeps, who of the guards keep the night, and whether it is the
 * village's market day. Worked out from the living folk and the date alone, so the same wherever it is asked.
 *
 * - The bell's holder and the day's poster are [BellKeeper] and [Posters]'s.
 * - The carters are citizens (one for a village of fewer than twelve, two beyond), those living nearest a barn first.
 * - A stall's keeper is, in the stalls' order, the first resident (a farmer, a fisher, a citizen or a merchant, not the bell's holder
 *   or a carter) whose craft is the stall's shop, else the first citizen left; a stall that finds nobody stays shut for the day.
 * - Of the guards, every third from the second (by their ids) keeps the night, so a village with two guards has one for each.
 * - Market day is the village's own (one in seven, by its board's position: [Festivals.isMarketDay]) unless it falls on the
 *   rest day, which wins.
 */
object VillageHands {
    /** A village of this many folk or more has two carters. */
    private const val TWO_CARTS = 12

    fun crew(level: ServerLevel, places: TownPlaces, day: Long, now: Long): Hands.Crew {
        val centre = places.centre
        val folk = Residents.get(level).byCentre(centre)
        val living = folk.filter { it.alive && it.id >= 0 }
        val bellman = BellKeeper.holder(level, centre, day)
        val poster = Posters.pick(folk, centre, day)
        val carters = carters(places, living, bellman)
        val guards = living.filter { it.role == TownRole.GUARD }.sortedBy { it.id }
        val night = guards.filterIndexed { i, _ -> i % 3 == 1 }.map { it.id }.toSet()
        val market = market(level, centre, day)
        return Hands.Crew(
            carters, mapOf(), null, now, setOf(), day,
            keepers = if (Days.restDay(day)) mapOf() else keepers(places, living, carters, bellman),
            bellman = bellman, poster = poster, night = night, market = market,
        )
    }

    /** The carters among [living]: citizens other than [bellman], those living nearest a barn first; two in a village of [TWO_CARTS] folk or more. */
    private fun carters(places: TownPlaces, living: List<Resident>, bellman: Int?): Set<Int> {
        val barns = places.lodgings.values.filter { it.kind == "BARN" }
        fun out(r: Resident): Double {
            val home = places.lodgings[r.home]?.box?.center ?: return Double.MAX_VALUE
            return barns.minOfOrNull { it.box.center.distSqr(home) } ?: Double.MAX_VALUE
        }
        val carts = if (living.size >= TWO_CARTS) 2 else 1
        return living.filter { it.role == TownRole.CITIZEN && it.id != bellman }
            .sortedWith(compareBy<Resident>({ out(it) }, { it.id })).take(carts).map { it.id }.toSet()
    }

    /** Who would keep each stall on town day [day] were it a working day (for the market scene, which plays on the rest day too): resident id to stall. */
    fun keepersOn(level: ServerLevel, places: TownPlaces, day: Long): Map<Int, Int> {
        val living = Residents.get(level).byCentre(places.centre).filter { it.alive && it.id >= 0 }
        val bellman = BellKeeper.holder(level, places.centre, day)
        return keepers(places, living, carters(places, living, bellman), bellman)
    }

    /** Whether it is the market day of the village at [centre] on town day [day]: its own, unless the rest day keeps it. */
    fun market(level: ServerLevel, centre: BlockPos, day: Long): Boolean {
        if (Days.restDay(day)) return false
        val rec = Villages.byKey(level, centre.asLong())
        val anchor = rec?.board?.takeIf { it != -1L }?.let { BlockPos.of(it) } ?: centre
        return Festivals.isMarketDay(anchor, day)
    }

    /** The keeper of each stall that has one today: resident id to the stall's index. */
    private fun keepers(places: TownPlaces, living: List<Resident>, carters: Set<Int>, bellman: Int?): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        if (places.stalls.isEmpty()) return out
        val able = living.filter { it.role in KEEPERS && it.id != bellman && it.id !in carters }.sortedBy { it.id }
        places.stalls.forEachIndexed { i, stall ->
            val shop = Shop.of(stall.trade)
            val keeper = able.firstOrNull { it.id !in out && fits(it, shop) } ?: able.firstOrNull { it.id !in out && it.role == TownRole.CITIZEN }
            if (keeper != null) out[keeper.id] = i
        }
        return out
    }

    private val KEEPERS = setOf(TownRole.FARMER, TownRole.FISHER, TownRole.CITIZEN, TownRole.MERCHANT)

    /** Whether [r]'s craft (the vanilla profession they had; their trade, if they had none) is what [shop] sells. */
    private fun fits(r: Resident, shop: Shop?): Boolean = when (shop) {
        Shop.PRODUCE -> r.craft == "minecraft:farmer" || r.craft.isEmpty() && r.role == TownRole.FARMER
        Shop.FISH_AND_FRUIT -> r.craft == "minecraft:fisherman" || r.craft.isEmpty() && r.role == TownRole.FISHER
        Shop.BUTCHER -> r.craft == "minecraft:butcher"
        Shop.CLOTH -> r.craft == "minecraft:shepherd" || r.craft == "minecraft:leatherworker"
        Shop.FLETCHER -> r.craft == "minecraft:fletcher"
        Shop.CHANDLER -> r.craft == "minecraft:cleric" || r.craft == "minecraft:librarian"
        else -> false
    }
}
