package io.github.vaspyyy.guildmark.economy.works

import io.github.vaspyyy.guildmark.economy.CaravanRec
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.math.abs

/**
 * A watchtower's watch over its road (design-influence.md section 1, "The watchtower"), as the road's records feel
 * it: where it stands along the road and how far it sees, how a band keeps its strike out of that sight or is seen,
 * and the road it keeps quieter. The caravans' code has one-line hooks for it (CaravanRoutes.strikeAt, Caravans.aim,
 * `chance` and `odds`, RoadPeril.morning); the rest is here. With the town's works off a tower counts for nothing,
 * and with no tower everything is as it was.
 */
internal object WorksWatch {
    /** How far along the road its watch sees each way, in the leg's distances. */
    const val SIGHT = 112.0

    /** How far out from the farm edge a tower stands before the building has sited it (it's sited 128 to 256 out). */
    const val OUT = 192.0

    /** How far past the watch's sight a strike point moved out of it falls. */
    private const val EDGE = 1.0

    /**
     * The band's chance against a tower: warier when it moved its strike out of the watch's sight; much warier when
     * there was nowhere out of sight to go, so it strikes in sight; and on the plan's own road, struck before anyone
     * sees the wagon, warier for the tower at the farm edge.
     */
    const val WARY = 0.75
    const val SEEN = 0.4
    const val OWN_ROAD = 0.6

    /** Guards the watch counts for when it rides out to a strike it sees. */
    const val RIDERS = 2.0

    /** Guards the town needs for its full watch: two by day and one by night; with fewer, one by day and none by night. */
    const val FULL_WATCH = 4

    /** How a wagon's strike stands with a tower's watch. */
    private enum class Seen { NONE, AVOIDED, SEEN, OWN_ROAD }

    /**
     * Where [w], a tower standing on [leg], keeps its watch: by its site once the building has one (how far along the
     * road it stands and where), otherwise [OUT] out from the farm edge (at the farm edge on the plan's own road).
     */
    fun place(w: WorksRec, leg: CaravanRoutes.Leg): Watch {
        val site = w.site
        if (site != null) return Watch(leg.nearest(site.origin.x + 0.5, site.origin.z + 0.5), SIGHT, site.origin)
        val along = if (leg.road < 0) leg.farms else (leg.farms - OUT).coerceAtLeast(0.0)
        return Watch(along, SIGHT, BlockPos.containing(leg.at(along)))
    }

    /** Whether [watch] sees [along] blocks along its road. */
    fun sees(watch: Watch, along: Double): Boolean = abs(along - watch.along) <= watch.sight

    /**
     * Where on [leg] a band strikes whose spot would be [natural], within its stretch [from] to [to]: out of a tower's
     * sight if the spot is in it and there's somewhere out of it in the stretch (the nearest such spot), else where
     * it would. The hook at the end of CaravanRoutes.strikeAt.
     */
    fun aside(leg: CaravanRoutes.Leg, natural: Double, from: Double, to: Double): Double {
        if (leg.road < 0 || to < from) return natural
        val level = Caravans.overworld() ?: return natural
        val watch = Works.watch(level, leg.capital, leg.road) ?: return natural
        if (!sees(watch, natural)) return natural
        return listOf(watch.along - watch.sight - EDGE, watch.along + watch.sight + EDGE)
            .filter { it in from..to }
            .minByOrNull { abs(it - natural) } ?: natural
    }

    /** How [c]'s strike at [point] by [band], falling at clock time [at], stands with its road's tower. */
    private fun seen(level: ServerLevel, c: CaravanRec, band: OutlawBand, point: Double, at: Long): Seen {
        val watch = Works.watch(level, c.capital, c.road) ?: return Seen.NONE
        val posted = watchmen(level, c.capital, Caravans.clockOf(Math.floorDiv(at, 1000L))) > 0
        if (c.road < 0) return if (posted) Seen.OWN_ROAD else Seen.NONE
        val leg = CaravanRoutes.cached(c.capital, c.road) ?: return Seen.NONE
        if (!sees(watch, CaravanRoutes.natural(leg, band))) return Seen.NONE
        if (!sees(watch, point)) return Seen.AVOIDED
        return if (posted) Seen.SEEN else Seen.NONE
    }

    private fun factor(seen: Seen): Double = when (seen) {
        Seen.NONE -> 1.0
        Seen.AVOIDED -> WARY
        Seen.SEEN -> SEEN
        Seen.OWN_ROAD -> OWN_ROAD
    }

    /** What a tower does to [band]'s chance of striking [c] at [point], at clock time [at]: the hook in Caravans.chance, before an escort's floor. */
    fun wary(level: ServerLevel, c: CaravanRec, band: OutlawBand, point: Double, at: Long): Double = factor(seen(level, c, band, point, at))

    /**
     * [c]'s strike settled as it sets out (or as its escort meets it, [escorted]): its dice [roll] against [p], the
     * chance with the tower in it. Gives whether a strike falls in a watch's sight (the record's `watched`, so the
     * wagon's bodies bring the watchmen into the fight); remembers a strike the tower kept off (the dice under what
     * the chance would have been without it), whose wagon thanks it when it gets in. The hook in Caravans.aim.
     */
    fun aimed(level: ServerLevel, c: CaravanRec, band: OutlawBand, point: Double, roll: Double, p: Double, escorted: Boolean): Boolean {
        if (!Works.enabled()) return false
        val data = WorksData.get(level)
        if (data.deterred.remove(c.id)) data.changed()
        val seen = seen(level, c, band, point, c.setOut + c.held + Caravans.ticksFor(point))
        if (roll < p) return seen == Seen.SEEN
        val f = factor(seen)
        if (!escorted && f < 1.0 && roll < p / f) {
            data.deterred.add(c.id)
            data.changed()
        }
        return false
    }

    /**
     * Whether [c]'s strike put at [point] by hand (`caravan ambush`), falling at clock time [at], is in its road's
     * tower's sight with a watch posted: the record's `watched`, as for a strike the dice put there. The hook in
     * Caravans.ambush.
     */
    fun struck(level: ServerLevel, c: CaravanRec, point: Double, at: Long): Boolean {
        if (!Works.enabled() || c.road < 0) return false
        val watch = Works.watch(level, c.capital, c.road) ?: return false
        return sees(watch, point) && watchmen(level, c.capital, Caravans.clockOf(Math.floorDiv(at, 1000L))) > 0
    }

    /** Guards the watch counts for when it rides out to the strike [c] (the hook in Caravans.odds): none with the works off. */
    fun riders(c: CaravanRec): Double = if (c.watched && Works.enabled()) RIDERS else 0.0

    /** How long [road] into [capital] must go without a strike before it starts to fade: half the [usual] under a tower's watch (the hook in RoadPeril.morning). */
    fun quiet(capital: Long, road: Int, usual: Long): Long {
        val level = Caravans.overworld() ?: return usual
        return if (Works.standing(level, capital, WorksKind.TOWER, road) != null) usual / 2 else usual
    }

    /** The watchmen [capital]'s town can post at a tower at [clock]: two by day and one by night, or one by day and none by night with fewer than [FULL_WATCH] guards. */
    fun watchmen(level: ServerLevel, capital: Long, clock: Int): Int {
        val guards = Markets.get(level)[BlockPos.of(capital)]?.town?.guards ?: 0
        val night = clock >= 19 || clock < 6
        return when {
            guards >= FULL_WATCH -> if (night) 1 else 2
            guards >= 1 -> if (night) 0 else 1
            else -> 0
        }
    }
}
