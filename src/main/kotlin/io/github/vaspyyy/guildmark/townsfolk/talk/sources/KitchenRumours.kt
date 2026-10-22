package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.cooking.DishKind
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The three kitchen rumours (Hearthcraft, living-world/cook/design.md 8.3): a Hallmarked dish sold, a pot burnt, a cook-off
 * won. Part J's wrappers over the witnessing of the town talk; the ledger, the guest pot and the Harvest Table call them.
 * Until Part J lands each is a call that does nothing.
 */
object KitchenRumours {
    /** [cook] sold a Hallmarked dish of [kind] at [at] (stub: nothing). */
    fun hallmark(level: ServerLevel, at: BlockPos, cook: ServerPlayer, kind: DishKind) {}

    /** A guest pot burnt at [at] (stub: nothing). */
    fun burnt(level: ServerLevel, at: BlockPos) {}

    /** [winner] won the Harvest Table with a dish of [kind], at the fair centred on [centre] (stub: nothing). */
    fun cookoff(level: ServerLevel, centre: BlockPos, winner: ServerPlayer, kind: DishKind) {}
}
