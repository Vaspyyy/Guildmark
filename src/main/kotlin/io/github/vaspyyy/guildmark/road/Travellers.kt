package io.github.vaspyyy.guildmark.road

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.quest.Expeditions
import io.github.vaspyyy.guildmark.registry.ModAttachments
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap

/** Where a traveller is along its road: the waypoint it's heading for, and how long it's been stuck. */
data class TravelProgress(val waypoint: Int = 0, val stuckChecks: Int = 0, val bestDistance: Int = Int.MAX_VALUE) {
    companion object {
        val CODEC: Codec<TravelProgress> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("waypoint", 0).forGetter(TravelProgress::waypoint),
                Codec.INT.optionalFieldOf("stuck", 0).forGetter(TravelProgress::stuckChecks),
                Codec.INT.optionalFieldOf("best", Int.MAX_VALUE).forGetter(TravelProgress::bestDistance),
            ).apply(i, ::TravelProgress)
        }
    }
}

/**
 * Escorted travellers walk the road to their destination on their own; the player is their guard.
 * They wait if their guard falls too far behind, and hop ahead a waypoint if they get stuck on terrain.
 */
object Travellers {
    /** Beyond this the traveller stops and waits for their guard. */
    private const val GUARD_RANGE = 32.0
    private const val ARRIVED = 3
    /** Checks (every 10 ticks) without getting closer before the traveller is nudged forward. */
    private const val STUCK_LIMIT = 15

    /** Called every 10 ticks for each escort contract the player carries. */
    fun tick(level: ServerLevel, guard: Player, villager: Villager, points: List<BlockPos>) {
        val brain = villager.brain
        Expeditions.keepOnTheRoad(villager)
        if (villager.distanceTo(guard) > GUARD_RANGE) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            if (level.gameTime % 100L == 0L) guard.sendOverlayMessage(Component.translatable("message.guildmark.traveller_waiting", villager.name))
            return
        }

        var progress = villager.getData(ModAttachments.TRAVEL_PROGRESS)
        val last = points.size - 1
        if (progress.waypoint > last) return
        val target = surface(level, points[progress.waypoint])
        val distance = horizontalDistance(villager.blockPosition(), target)

        progress = when {
            distance <= ARRIVED -> TravelProgress(progress.waypoint + 1)
            distance < progress.bestDistance -> progress.copy(bestDistance = distance, stuckChecks = 0)
            progress.stuckChecks + 1 >= STUCK_LIMIT -> {
                villager.teleportTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
                TravelProgress(progress.waypoint + 1)
            }
            else -> progress.copy(stuckChecks = progress.stuckChecks + 1)
        }
        villager.setData(ModAttachments.TRAVEL_PROGRESS, progress)

        if (progress.waypoint <= last) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(surface(level, points[progress.waypoint]), 0.6f, 1))
        } else {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        }
    }

    /** The real ground height at a waypoint if its chunk is loaded, otherwise the planned height. */
    private fun surface(level: ServerLevel, point: BlockPos): BlockPos =
        if (level.chunkSource.getChunkNow(point.x shr 4, point.z shr 4) != null) {
            level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, point)
        } else point

    private fun horizontalDistance(a: BlockPos, b: BlockPos): Int {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return Math.sqrt(dx * dx + dz * dz).toInt()
    }
}
