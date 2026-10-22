package io.github.vaspyyy.guildmark.cooking.vessel

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult

/** What a click on a station came to: it did something, it was refused (and said why, taking nothing), or it is none of the station's business. */
enum class Used { DONE, REFUSED, PASS }

/**
 * What a click on a station does to its block entity (living-world/cook/design.md 1.1): the part of it that the four
 * fires and the Prep Board share, so [StationBlock] can speak to either.
 */
interface StationUse {
    /**
     * Client side: whether a click with [stack] in [hand] will certainly do something, so the arm swings now and not when
     * the server answers. A click that might not is still taken by the station (the server decides what it came to), so
     * the game never falls through to using the item on the world: a bucket poured on the ground by the pot, a carrot eaten.
     */
    fun predicts(player: Player, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): Boolean

    /** Server side: a click with [stack] in [hand] on the station, where [hit] is what the cook's eye met. */
    fun interact(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): InteractionResult

    /** Server side: once a tick (the block entity decides how often it acts). */
    fun serverTick(level: ServerLevel)
}
