package io.github.vaspyyy.guildmark.island

import io.github.vaspyyy.guildmark.island.plan.IslandPlan
import io.github.vaspyyy.guildmark.island.plan.IslandSpec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Which island's waters each player is in, looked up once a second, so their client can play the island's own sounds
 * and its music. Where those sounds come from is worked out from the island's plan, off the server's thread if the
 * plan has to be made again (after a restart), and sent once it's ready, if they're still there.
 */
object IslandVisits {
    private const val CHECK = 20

    private val visits = HashMap<UUID, IslandSpec>()

    fun tick(player: ServerPlayer) {
        if (player.tickCount % CHECK != 11) return
        val level = player.level()
        val here = if (level.dimension() == Level.OVERWORLD) Islands.at(level, player.blockPosition()) else null
        val before = visits[player.uuid]
        if (here == before) return
        if (here == null) {
            visits.remove(player.uuid)
            PacketDistributor.sendToPlayer(player, IslandPayload.NONE)
            return
        }
        visits[player.uuid] = here
        val server = level.server
        CompletableFuture.supplyAsync({ spots(here) }, Util.backgroundExecutor()).thenAcceptAsync({ spots ->
            if (visits[player.uuid] == here && !player.hasDisconnected()) {
                PacketDistributor.sendToPlayer(player, IslandPayload(IslandMarks.ref(here), here.cx, here.cz, here.sea, here.radius, spots))
            }
        }, server)
    }

    /** The island [player] was last found in the waters of, if any. */
    fun current(player: Player): IslandSpec? = visits[player.uuid]

    fun forget(player: Player) {
        visits.remove(player.uuid)
    }

    fun clear() = visits.clear()

    private fun spots(spec: IslandSpec): List<IslandSoundSpot> =
        IslandPlan.of(spec).sounds().map { IslandSoundSpot(it.spot.ordinal, it.x, it.y, it.z) }
}
