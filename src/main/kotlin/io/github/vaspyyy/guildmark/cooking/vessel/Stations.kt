package io.github.vaspyyy.guildmark.cooking.vessel

import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Finding the cooking stations (living-world/cook/design.md 1.1, 12.3). Part B's: the stations that are loaded on the
 * server keep their place in a set (put there when a chunk loads them and taken out when it lets them go), so a part that
 * wants the ones near a place ([near]) looks through a few dozen and not through the world, and the one in view is found
 * with the player's own reach ([lookedAt]).
 */
object Stations {
    /** How far a player reaches to a station they are looking at, blocks. */
    private const val REACH = 6.0

    /** The loaded server-side stations (the Prep Board is a helper and not one). Server thread only. */
    private val loaded: MutableSet<VesselBlockEntity> = Collections.newSetFromMap(IdentityHashMap())

    /** What `cook perf` counts: vessels brought up to date by their tick, and the nanoseconds that took. */
    @JvmField
    var advances = 0L

    @JvmField
    var nanos = 0L

    internal fun add(station: VesselBlockEntity) {
        loaded.add(station)
    }

    internal fun remove(station: VesselBlockEntity) {
        loaded.remove(station)
    }

    /** The station [player] is looking at, within reach, or null (a Prep Board is found too). */
    fun lookedAt(player: ServerPlayer): StationBlockEntity? {
        val hit = player.pick(REACH, 1.0f, false)
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return null
        return player.level().getBlockEntity(hit.blockPos) as? StationBlockEntity
    }

    /** The stations within [blocks] of [pos] in [level], nearest first. */
    fun near(level: ServerLevel, pos: BlockPos, blocks: Int): List<StationBlockEntity> {
        val limit = blocks.toDouble() * blocks
        return loaded.filter { it.level === level && !it.isRemoved && it.blockPos.distSqr(pos) <= limit }.sortedBy { it.blockPos.distSqr(pos) }
    }

    /** Part B's registration: the readout of `cook perf`, the clearing of the tables, and the commands of `cook vessel`. */
    fun register() {
        Cooking.onForget {
            loaded.clear()
            advances = 0L
            nanos = 0L
        }
        CookStats.readout("stations") {
            val lit = loaded.count { it.vessel.lit }
            val each = if (advances > 0) nanos / advances / 1000.0 else 0.0
            "${loaded.size} loaded, $lit lit, $advances ticks worked out at ${"%.1f".format(each)} us each"
        }
        VesselCommand.init()
        // Part C's `cook view`: a made-up state on the station in view
        ViewCommand.init()
    }
}
