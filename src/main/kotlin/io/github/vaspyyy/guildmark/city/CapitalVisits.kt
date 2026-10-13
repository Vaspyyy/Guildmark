package io.github.vaspyyy.guildmark.city

import io.github.vaspyyy.guildmark.ambience.CityDistricts
import io.github.vaspyyy.guildmark.ambience.District
import io.github.vaspyyy.guildmark.city.plan.CityBuilder
import io.github.vaspyyy.guildmark.city.plan.CityPlan
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.city.plan.Use
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import java.util.UUID

/**
 * Tells each player's city sounds where they stand in a capital (ambience/CityDistricts): in one at all, and
 * in which part of it: the plaza and the market hall, up on the walls, or the hush of the temple, the library
 * and the keep's ward. Each player is looked at twice a second and told only when that changes.
 */
object CapitalVisits {
    /** Ticks between looks at where each player is. */
    private const val CHECK = 10
    /** Ticks between looking for a capital round a player who isn't in one. */
    private const val SEARCH = 40

    private class Visit {
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
        var capital = visit.capital?.takeIf { it.within(pos.x, pos.z) }
        if (capital == null && level.dimension() == Level.OVERWORLD && Capitals.enabled() && level.gameTime - visit.searched >= SEARCH) {
            visit.searched = level.gameTime
            capital = Capitals.at(level, pos)
        }
        visit.capital = capital
        val now = Pair(capital != null, capital?.let { districtAt(it, pos) } ?: District.NONE)
        if (now == visit.told) return
        visit.told = now
        CityDistricts.tell(player, now.first, now.second)
    }

    /** The part of [capital] that [pos] is in. */
    fun districtAt(capital: CapitalPiece, pos: BlockPos): District {
        val (x, z) = capital.local(pos.x, pos.z)
        return district(capital.plan, x, pos.y - capital.center.y, z)
    }

    /** The part of the city at [x], [z] in its own frame, [y] above the plaza. */
    private fun district(plan: CityPlan, x: Int, y: Int, z: Int): District {
        val use = plan.grid[x, z]
        val fortified = use == Use.WALL || use == Use.TOWER || use == Use.GATE || use == Use.KEEP_WALL
        if (fortified && y >= CityBuilder.WARD_WALK) return District.WALLS
        if (CityPlan.KEEP_WARD.contains(x, z)) return District.QUIET
        if (use == Use.PLAZA) return District.MARKET
        return when (plan.buildings.getOrNull(plan.grid.owner(x, z))?.kind) {
            Kind.MARKET_HALL -> District.MARKET
            Kind.TEMPLE, Kind.LIBRARY -> District.QUIET
            else -> District.NONE
        }
    }
}
