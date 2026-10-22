package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.cooking.Cooking
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.UUID

/**
 * Eating at a Plate (living-world/cook/design.md 3.3, 3.5): the dish is lifted into the hand and eaten as any food is
 * eaten, which is by holding the use key down for the second and a half it takes. Let go early, and the game stops the
 * meal; this puts the dish back on its plate (if the player is still beside it and it is still bare) rather than leaving
 * it in their hand. If the plate is out of reach, the dish simply stays in the hand: it is never lost.
 */
object PlateEating {
    /** How long after lifting a dish an early stop still sends it back: the longest meal takes 32 ticks. */
    private const val WINDOW = 60L

    /** How far from the plate (squared) the player may be for the dish to go back to it. */
    private const val RANGE_SQ = 64.0

    private class Spot(val dimension: ResourceKey<Level>, val pos: BlockPos, val until: Long)

    /** Who is eating from which plate; at most one entry a player, and gone when the meal ends. */
    private val eating = HashMap<UUID, Spot>()

    /** [player] has lifted the dish off the plate at [pos] and begun to eat it. */
    fun begin(player: ServerPlayer, level: ServerLevel, pos: BlockPos) {
        eating[player.uuid] = Spot(level.dimension(), pos.immutable(), level.gameTime + WINDOW)
    }

    fun register() {
        NeoForge.EVENT_BUS.addListener(LivingEntityUseItemEvent.Stop::class.java) { event -> stopped(event) }
        NeoForge.EVENT_BUS.addListener(LivingEntityUseItemEvent.Finish::class.java) { event -> (event.entity as? ServerPlayer)?.let { eating.remove(it.uuid) } }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event -> eating.remove(event.entity.uuid) }
        Cooking.onForget { eating.clear() }
    }

    private fun stopped(event: LivingEntityUseItemEvent.Stop) {
        val player = event.entity as? ServerPlayer ?: return
        val spot = eating.remove(player.uuid) ?: return
        val level = player.level() as? ServerLevel ?: return
        if (level.dimension() != spot.dimension || level.gameTime > spot.until || !level.isLoaded(spot.pos)) return
        val hand = player.usedItemHand
        val held = player.getItemInHand(hand)
        // the dish that was being eaten, not another that a change of slot has put in the hand
        if (held.item !is DishItem || held.count != 1 || !ItemStack.isSameItemSameComponents(held, event.item)) return
        if (player.distanceToSqr(Vec3.atCenterOf(spot.pos)) > RANGE_SQ) return
        val plate = level.getBlockEntity(spot.pos) as? PlateBlockEntity ?: return
        if (plate.lay(held)) player.setItemInHand(hand, ItemStack.EMPTY)
    }
}
