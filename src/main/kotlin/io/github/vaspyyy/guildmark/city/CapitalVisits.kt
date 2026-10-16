package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.ambience.CityDistricts
import io.github.vaspyyy.guildmark.ambience.District
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Use
import io.github.vaspyyy.guildmark.companion.Companions
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import java.util.UUID
import kotlin.math.abs

/**
 * Tells each player's city sounds where they stand in a capital (ambience/CityDistricts): in one at all, and
 * in which part of it: the plaza, the market and its hall, the harbour, up on the walls, or the hush of the
 * cathedral, the library, the noble quarter and the citadel. Each player is looked at twice a second and told
 * only when that changes.
 */
object CapitalVisits {
    /** Ticks between looks at where each player is. */
    private const val CHECK = 10
    /** Ticks between looking for a capital round a player who isn't on one's ground. */
    private const val SEARCH = 40

    private class Visit {
        /** The capital whose ground the player was last on, farms and all. */
        var capital: CapitalPiece? = null
        var searched = Long.MIN_VALUE
        /** What the player's side was last told; null until it's been told anything. */
        var told: Pair<Boolean, District>? = null
    }

    private val visits = HashMap<UUID, Visit>()

    fun register() {
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post::class.java) { event -> (event.entity as? ServerPlayer)?.let(::tick) }
        // A new world on the player's side, or a new player entity, starts from nothing told
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { visits.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { visits.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { visits.remove(it.entity.uuid) }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> visits.clear() }
    }

    private fun tick(player: ServerPlayer) {
        if (player.tickCount % CHECK != 4) return
        val level = player.level()
        val visit = visits.getOrPut(player.uuid) { Visit() }
        val pos = player.blockPosition()
        var capital = visit.capital?.takeIf { it.reaches(pos.x, pos.z) }
        if (capital == null && level.dimension() == Level.OVERWORLD && Capitals.enabled() && level.gameTime - visit.searched >= SEARCH) {
            visit.searched = level.gameTime
            capital = Capitals.covering(level, pos)
        }
        visit.capital = capital
        // Out among the farms is the country, not the town
        val town = capital?.takeIf { it.inTown(pos.x, pos.z) }
        val now = Pair(town != null, town?.let { districtAt(it, pos) } ?: District.NONE)
        if (now == visit.told) return
        // In through the gates: whoever travels with the player has a word about the place
        if (now.first && visit.told?.first != true) Companions.onCapital(player)
        visit.told = now
        CityDistricts.tell(player, now.first, now.second)
    }

    /** The part of [capital] that [pos] is in. */
    fun districtAt(capital: CapitalPiece, pos: BlockPos): District {
        val (x, z) = capital.local(pos.x, pos.z)
        return district(capital.plan, x, pos.y - capital.center.y, z)
    }

    /** The part of the city at [x], [z] in its own frame, [y] over the lower town's standing height. */
    private fun district(plan: CityPlan, x: Int, y: Int, z: Int): District {
        when (plan.grid[x, z]) {
            Use.WALL, Use.TOWER, Use.GATE, Use.INNER_WALL, Use.KEEP_WALL -> if (y >= wallWalk(plan, x, z) - 1) return District.WALLS
            Use.QUAY, Use.PIER, Use.MOORING -> return District.HARBOUR
            Use.PLAZA -> return District.MARKET
            else -> {}
        }
        if (plan.market.contains(x, z)) return District.MARKET
        if (plan.citadel.contains(x, z) || plan.nobleQuarter.contains(x, z)) return District.QUIET
        return when (plan.buildings.getOrNull(plan.grid.owner(x, z))?.kind) {
            Kind.MARKET_HALL -> District.MARKET
            Kind.TEMPLE, Kind.LIBRARY, Kind.CATHEDRAL -> District.QUIET
            else -> District.NONE
        }
    }

    /** The standing height on the walk of whichever wall stands at [x], [z]: the outer walls', the old town's or the citadel's. */
    private fun wallWalk(plan: CityPlan, x: Int, z: Int): Int = when {
        maxOf(abs(x), abs(z)) > plan.outer - CityPlan.WALL_THICK -> plan.walk
        plan.citadel.grow(CityPlan.CITADEL_TOWER_RADIUS.toInt()).contains(x, z) -> plan.high + CityPlan.CITADEL_PARAPET
        else -> plan.upper + CityPlan.INNER_PARAPET
    }
}
