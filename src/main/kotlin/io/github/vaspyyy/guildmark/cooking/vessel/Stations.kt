package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/** Finding the cooking stations (living-world/cook/design.md 1.1, 12.3). Part B's; until it lands, only the one in view is found. */
object Stations {
    /** How far a player reaches to a station they are looking at, blocks. */
    private const val REACH = 6.0

    /** The station [player] is looking at, within reach, or null. */
    fun lookedAt(player: ServerPlayer): StationBlockEntity? {
        val hit = player.pick(REACH, 1.0f, false)
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return null
        return player.level().getBlockEntity(hit.blockPos) as? StationBlockEntity
    }

    /** The stations within [blocks] of [pos], nearest first (stub: none). */
    fun near(level: ServerLevel, pos: BlockPos, blocks: Int): List<StationBlockEntity> = emptyList()

    /** Part B's registration: the vessel's calls, fuel and burning, and its commands (stub: nothing). */
    fun register() {}
}
