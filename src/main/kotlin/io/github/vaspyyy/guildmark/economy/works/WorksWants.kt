package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.SupplierLink
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Tally
import io.github.vaspyyy.guildmark.economy.Town
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

/** A works the day's close found wanted: its kind, its road (a tower's) or owner (a stall's), and why (a lang key). */
internal class Want(val kind: WorksKind, val road: Int, val owner: UUID?, val why: String)

/**
 * What a capital asks for (design-influence.md section 1, each works' "Wanted when"), worked out at each market close
 * from what the day really held: the tallies, the makers' idle hours, the hungry meals, the events under way, the
 * roads' records and the players' standing. A works is asked for only once something real showed the need; the rest
 * are [notYet], with what would make them wanted. Each is asked for once (per road for a tower, per player for a
 * stall), and never again once it's in the records.
 */
internal object WorksWants {
    /** Days of the last eight the bread must have run short on, and by more than [SHORT_SHARE] of a day's use, for a second oven. */
    const val SHORT_DAYS = 2
    const val SHORT_SHARE = 0.02

    /** How far past its plan the town must have grown for a second oven. */
    const val GROWN = 1.1

    /** Hours in a day the mill must have stood idle for want of grain, and how low the market's wheat must have fallen against its target, for a granary. */
    const val IDLE_HOURS = 3
    const val LOW = 0.5

    /** How far under its room the granary must have fallen, having given grain, for the great granary. */
    const val DRAWN = 1.0 / 3.0

    /** The share of a day's meals going without that makes a hungry day (as the town talks of it), and the fewest. */
    const val HUNGRY_SHARE = 0.05
    const val HUNGRY_LEAST = 3.0

    /** Whether the bread went short on the day [m] just closed, the bakehouse never short of flour: one of the days that count toward a second oven. */
    fun shortDay(m: Market, town: Town): Boolean =
        m.yesterday(Tally.SHORT, Good.BREAD) > town.use(Good.BREAD) * SHORT_SHARE && m.idleYesterday[Maker.OVENS.ordinal] == 0

    /** Whether the day [m] just closed was a hungry one: a twentieth of the town's meals or more went without. */
    fun hungry(m: Market, town: Town): Boolean = (m.hungry.lastOrNull() ?: 0.0) >= maxOf(HUNGRY_LEAST, town.people * Economy.MEALS * HUNGRY_SHARE)

    /** Whether [capital] has asked for [kind] (for [road], or for [owner]) already, or can't yet because what it follows on from doesn't stand. */
    private fun taken(data: WorksData, capital: Long, kind: WorksKind, road: Int = -1, owner: UUID? = null): Boolean =
        data.find(capital, kind, road, owner) != null || kind.after != null && data.done(capital, kind.after) == null

    /** What [entry]'s town asks for at the close of the day [rec] kept. */
    fun wants(level: ServerLevel, entry: Markets.Entry, data: WorksData, rec: TownRec): List<Want> {
        val m = entry.market
        val town = entry.town
        val capital = m.capital
        val out = ArrayList<Want>()
        if (!taken(data, capital, WorksKind.OVEN)) oven(town, rec)?.let { out.add(Want(WorksKind.OVEN, -1, null, it)) }
        if (!taken(data, capital, WorksKind.GRANARY)) granary(m, town, rec)?.let { out.add(Want(WorksKind.GRANARY, -1, null, it)) }
        if (!taken(data, capital, WorksKind.GREAT_GRANARY) && drawn(data, m, town)) out.add(Want(WorksKind.GREAT_GRANARY, -1, null, "gui.guildmark.works.why.great_granary.drawn"))
        if (Caravans.ambushes()) {
            val roads = CaravanData.get(level)
            for (leg in CaravanRoutes.legs(level, capital).orEmpty()) {
                if (taken(data, capital, WorksKind.TOWER, leg.road)) continue
                when {
                    leg.road in rec.waylaid -> out.add(Want(WorksKind.TOWER, leg.road, null, "gui.guildmark.works.why.tower.waylaid"))
                    RoadPeril.stage(roads, capital, leg.road).troubled -> out.add(Want(WorksKind.TOWER, leg.road, null, "gui.guildmark.works.why.tower.troubled"))
                }
            }
        }
        // A stall of your own only where stalls are let and sell (Own Stalls) and suppliers are trusted (Supplier Orders)
        if (Suppliers.enabled() && OwnStalls.enabled()) for (player in level.server.playerList.players) {
            if (taken(data, capital, WorksKind.STALL, owner = player.uuid) || !trusted(level, capital, player.uuid)) continue
            out.add(Want(WorksKind.STALL, -1, player.uuid, "gui.guildmark.works.why.stall.trusted"))
        }
        return out
    }

    /** Why the town wants a second oven, if it does: an oven cracked, the bread ran short, or the town outgrew its plan. */
    private fun oven(town: Town, rec: TownRec): String? = when {
        rec.cracked -> "gui.guildmark.works.why.oven.cracked"
        Integer.bitCount(rec.short) >= SHORT_DAYS -> "gui.guildmark.works.why.oven.short"
        grown(town) -> "gui.guildmark.works.why.oven.grown"
        else -> null
    }

    private fun grown(town: Town): Boolean {
        val planned = town.planned ?: return false
        return planned.people > 0 && town.people > planned.people * GROWN
    }

    /** Why the town wants a granary, if it does: the mill idle for grain, the market's wheat under half, a blight on the wheat, or a hungry day. */
    private fun granary(m: Market, town: Town, rec: TownRec): String? = when {
        m.idleYesterday[Maker.MILL.ordinal] >= IDLE_HOURS -> "gui.guildmark.works.why.granary.idle"
        rec.low < LOW -> "gui.guildmark.works.why.granary.low"
        rec.blighted -> "gui.guildmark.works.why.granary.blight"
        hungry(m, town) -> "gui.guildmark.works.why.granary.hungry"
        else -> null
    }

    /** Whether the granary has given grain and fallen under a third of its room. */
    private fun drawn(data: WorksData, m: Market, town: Town): Boolean {
        val granary = data.done(m.capital, WorksKind.GRANARY) ?: return false
        return granary.gave && Reserve.full(m, town, Good.WHEAT) < DRAWN
    }

    /** Whether [player] is a Trusted Supplier of the capital whose heart is [capital], by their standing there. */
    private fun trusted(level: ServerLevel, capital: Long, player: UUID): Boolean =
        Suppliers.rank(level, BlockPos.of(capital), player) >= SupplierLink.TRUSTED

    /**
     * The works [entry]'s town doesn't want yet (and [player]'s stall), shown greyed, each with what would make it
     * wanted and how things stand now. A tower for each road in (with outlaws on the roads at all), and a great
     * granary once there's a granary to follow on from.
     */
    fun notYet(level: ServerLevel, entry: Markets.Entry, data: WorksData, rec: TownRec, player: UUID?): List<NotYet> {
        val m = entry.market
        val town = entry.town
        val capital = m.capital
        val out = ArrayList<NotYet>()
        if (!taken(data, capital, WorksKind.OVEN)) {
            out.add(NotYet(WorksKind.OVEN, -1, "gui.guildmark.works.not_yet.oven", listOf(Integer.bitCount(rec.short).toString())))
        }
        if (!taken(data, capital, WorksKind.GRANARY)) {
            out.add(NotYet(WorksKind.GRANARY, -1, "gui.guildmark.works.not_yet.granary", listOf(percent(m.stock(Good.WHEAT) / town.target(Good.WHEAT)))))
        }
        if (data.find(capital, WorksKind.GREAT_GRANARY) == null) {
            val granary = data.done(capital, WorksKind.GRANARY)
            out.add(
                when {
                    granary == null -> NotYet(WorksKind.GREAT_GRANARY, -1, "gui.guildmark.works.not_yet.great_granary.first", listOf())
                    !granary.gave -> NotYet(WorksKind.GREAT_GRANARY, -1, "gui.guildmark.works.not_yet.great_granary.gave", listOf())
                    else -> NotYet(WorksKind.GREAT_GRANARY, -1, "gui.guildmark.works.not_yet.great_granary.third", listOf(percent(Reserve.full(m, town, Good.WHEAT))))
                },
            )
        }
        val roads = CaravanData.get(level)
        for (leg in CaravanRoutes.legs(level, capital).orEmpty()) {
            if (taken(data, capital, WorksKind.TOWER, leg.road)) continue
            val way = "#road.guildmark.way.${leg.side.name.lowercase()}"
            out.add(
                if (!Caravans.ambushes()) NotYet(WorksKind.TOWER, leg.road, "gui.guildmark.works.not_yet.tower.quiet", listOf(way))
                else NotYet(WorksKind.TOWER, leg.road, "gui.guildmark.works.not_yet.tower", listOf(way, "#gui.guildmark.works.road.${RoadPeril.stage(roads, capital, leg.road).id}")),
            )
        }
        if (player != null && !taken(data, capital, WorksKind.STALL, owner = player)) {
            out.add(
                when {
                    !Suppliers.enabled() -> NotYet(WorksKind.STALL, -1, "gui.guildmark.works.not_yet.stall.standing", listOf())
                    trusted(level, capital, player) -> NotYet(WorksKind.STALL, -1, "gui.guildmark.works.not_yet.stall.close", listOf())
                    else -> {
                        val points = Suppliers.get(level)[BlockPos.of(capital), player]?.points ?: 0.0
                        NotYet(WorksKind.STALL, -1, "gui.guildmark.works.not_yet.stall", listOf(floor(points).toInt().toString(), SupplierRank.TRUSTED.points.toString()))
                    }
                },
            )
        }
        return out
    }

    /** [share] as a whole percentage, for a lang key's argument. */
    private fun percent(share: Double): String = (share * 100.0).coerceIn(0.0, 999.0).roundToInt().toString()
}
