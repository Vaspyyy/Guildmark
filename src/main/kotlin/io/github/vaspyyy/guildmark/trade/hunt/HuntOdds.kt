package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.works.WorksWatch
import io.github.vaspyyy.guildmark.guild.Guilds
import io.github.vaspyyy.guildmark.guild.Holdings
import io.github.vaspyyy.guildmark.outlaw.OutlawBand
import io.github.vaspyyy.guildmark.outlaw.OutlawRole
import io.github.vaspyyy.guildmark.outlaw.Raids
import io.github.vaspyyy.guildmark.trade.Perk
import io.github.vaspyyy.guildmark.trade.Rungs
import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where a Hunt may start and how likely it is (living-world/trader/design.md, Part 4, "Rules and numbers"): the caravans' own table of odds
 * ([Caravans.chanceFor]) for a band and a trader standing at a place, with the Hunt's own factors multiplied in after it. Everything here only
 * reads; `trade hunt chance` prints what [odds] works out, so that what the roll uses and what an operator is shown are one calculation.
 */
internal object HuntOdds {
    /** A town: no Hunt starts within this many blocks of a capital's heart, nor within [BELL] of a village's bell. */
    const val TOWN = 128.0
    const val BELL = 48

    /** The gate guards of a capital drive a party off within this many blocks of its heart (a Hunt already on). */
    const val DRIVE = 96.0

    /** A road is "under" a trader when one of its legs passes within this many blocks of them. */
    const val ROAD = 24.0

    /** How far off a capital still counts as the town a trader is making for (the talk's own reach for a rumour). */
    const val NEAR_TOWN = 400.0

    /** The laden factor's two numbers: the Marks' worth of goods it takes for the factor to be one, and how steeply it rises. */
    private const val LADEN_BASE = 0.5
    private const val LADEN_PER_MARK = 1.0 / 70.0
    private const val LADEN_MOST = 2.0

    /** How far off a band's camp must be before its lookout cannot see a trader (when his post is empty). */
    const val BLIND_REACH = 96

    /** A waystation: within this of a village a guild holds, its members are hunted half as often. */
    private const val WAYSTATION = 160.0

    /** Where a trader stands as the roads see it: the capital whose road runs by them (or the nearest town, if none does), and which road. */
    class Stand(val capital: Long, val road: Int, val onRoad: Boolean) {
        /** The capital's heart, or null where no town is known. */
        val heart: BlockPos? get() = if (capital == 0L) null else BlockPos.of(capital)
    }

    /** One factor of the odds: its name as the command shows it, and what it multiplies by. */
    class Part(val name: String, val factor: Double)

    /**
     * The odds of [band] stopping a trader at some place: every factor, the number they make, the number after the cap, and whether the band is
     * free to strike; [table] is what the caravans' own table alone gives there (the first [tableParts] of [parts]), the number a wagon at that
     * place would have before a tower's watch.
     */
    class Odds(val band: OutlawBand, val reach: Double, val parts: List<Part>, val product: Double, val chance: Double, val rested: Boolean, val table: Double, val tableParts: Int)

    /** The reach of [band]'s lookout: the setting's, or 96 blocks if the lookout is dead. */
    fun reachOf(band: OutlawBand): Double {
        val set = TradeConfig.huntReach().toDouble()
        val blind = band.members.any { it.role == OutlawRole.CAPTAIN } && band.members.none { it.role == OutlawRole.LOOKOUT }
        return if (blind) min(set, BLIND_REACH.toDouble()) else set
    }

    /** How far [player] stands from [band]'s camp, across the ground. */
    fun distance(player: ServerPlayer, band: OutlawBand): Double = hypot(player.x - (band.pos.x + 0.5), player.z - (band.pos.z + 0.5))

    /** The laden factor: 0.86 at 25 Marks, 1.5 at 70, 2 from 105. */
    fun laden(marks: Int): Double = (LADEN_BASE + marks * LADEN_PER_MARK).coerceAtMost(LADEN_MOST)

    /** Whether [pos] is in a town: within [TOWN] of a capital's heart or [BELL] of a village's bell. */
    fun inTown(level: ServerLevel, pos: BlockPos): Boolean {
        for (m in Markets.get(level).all()) {
            val c = BlockPos.of(m.market.capital)
            if (hypot((c.x - pos.x).toDouble(), (c.z - pos.z).toDouble()) <= TOWN) return true
        }
        return Raids.nearestBell(level, pos, BELL) != null
    }

    /** Whether a capital's heart lies within [DRIVE] of [pos]: its gate guards are about. */
    fun guarded(level: ServerLevel, pos: BlockPos): Boolean {
        for (m in Markets.get(level).all()) {
            val c = BlockPos.of(m.market.capital)
            if (hypot((c.x - pos.x).toDouble(), (c.z - pos.z).toDouble()) <= DRIVE) return true
        }
        return false
    }

    /**
     * Where [pos] is among the roads: the capital whose leg passes within [ROAD] blocks of it and that leg's road, else the nearest capital within
     * [NEAR_TOWN] and no road at all (the road's peril counts only on a road). Looks at the legs already worked out and starts none.
     */
    fun stand(level: ServerLevel, pos: BlockPos): Stand {
        var near = 0L
        var nearSq = NEAR_TOWN * NEAR_TOWN
        var onCapital = 0L
        var onRoad = 0
        var onSq = Double.MAX_VALUE
        for (m in Markets.get(level).all()) {
            val capital = m.market.capital
            val c = BlockPos.of(capital)
            val dx = (c.x - pos.x).toDouble()
            val dz = (c.z - pos.z).toDouble()
            val sq = dx * dx + dz * dz
            if (sq < nearSq) {
                nearSq = sq
                near = capital
            }
            if (sq > LEGS * LEGS) continue
            val ids = ArrayList<Int>()
            ids.add(-1)
            for (road in CaravanRoutes.roads(level, capital)) ids.add(road.id)
            for (id in ids) {
                val leg = CaravanRoutes.cached(capital, id) ?: continue
                if (sq < onSq && leg.near(pos.x + 0.5, pos.z + 0.5, ROAD)) {
                    onSq = sq
                    onCapital = capital
                    onRoad = id
                }
            }
        }
        return if (onSq < Double.MAX_VALUE) Stand(onCapital, onRoad, true) else Stand(near, Int.MIN_VALUE, false)
    }

    /** The key of the heart of the capital nearest [pos], however far off; 0 if there is no market at all. */
    fun nearestCapital(level: ServerLevel, pos: BlockPos): Long =
        Markets.get(level).all().map { it.market.capital }.minByOrNull { BlockPos.of(it).distSqr(pos) } ?: 0L

    /** How far off a capital a leg of its roads can still pass a trader (the longest a wagon's road runs). */
    private const val LEGS = 700.0

    /** The Hunt's own factor for a village a guild holds: its members are hunted half as often within [WAYSTATION] of its banner. Null if it does not apply. */
    private fun waystation(level: ServerLevel, player: ServerPlayer): Boolean {
        val guild = Guilds.get(level).guilds.firstOrNull { it.leader == player.uuid || player.uuid in it.members } ?: return false
        return Holdings.get(level).of(guild.id).any { hypot(it.anchor.x + 0.5 - player.x, it.anchor.z + 0.5 - player.z) <= WAYSTATION }
    }

    /**
     * The odds of [band] stopping [player] at [now] (clock ticks) with goods worth [marks]: the caravans' own table for a trader standing at [stand]
     * with [followers] about them, then the Hunt's three differences (the laden factor, a tower's watch, the settings) and the ladder's road-wary
     * perk and a held village's waystation. [Odds.rested] is true when the band may not strike (it is resting, or it raided a village lately).
     */
    fun odds(level: ServerLevel, player: ServerPlayer, band: OutlawBand, marks: Int, stand: Stand, followers: Int, now: Long): Odds {
        val data = CaravanData.get(level)
        val reach = distance(player, band)
        val exposure = Caravans.Exposure(reach, now, followers, if (stand.onRoad) stand.capital else player.blockPosition().asLong(), stand.road)
        val parts = ArrayList<Part>()
        for ((name, f) in Caravans.factors(level, data, exposure)) parts.add(Part(name, f))
        val table = parts.size
        parts.add(Part("laden", laden(marks)))
        parts.add(Part("watch", WorksWatch.wary(level, player.blockPosition(), now)))
        parts.add(Part("waystation", if (waystation(level, player)) 0.5 else 1.0))
        parts.add(Part("ladder", (1.0 - Rungs.perk(player, Perk.ROAD_WARY)).coerceIn(0.0, 1.0)))
        parts.add(Part("setting", TradeConfig.huntChance()))
        val extra = parts.drop(table).fold(1.0) { a, p -> a * p.factor }
        val rested = !Caravans.allowed(level, data, band, now)
        val product = if (rested) 0.0 else 0.15 * parts.fold(1.0) { a, p -> a * p.factor }
        val chance = if (rested) 0.0 else Caravans.chanceFor(level, data, band, exposure, extra)
        return Odds(band, reach, parts, product, chance, rested, Caravans.chanceFor(level, data, band, exposure), table)
    }
}
