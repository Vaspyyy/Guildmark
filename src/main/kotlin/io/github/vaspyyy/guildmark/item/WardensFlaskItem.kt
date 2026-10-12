package io.github.vaspyyy.guildmark.item

import io.github.vaspyyy.guildmark.wardfire.WardensFlask
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.level.Level

/** The Warden's Flask in hand: drinking from it takes a draught from the player's flask (see WardensFlask). */
class WardensFlaskItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (WardensFlask.get(player).charges <= 0) {
            if (player is ServerPlayer) WardensFlask.empty(player)
            return InteractionResult.FAIL
        }
        player.startUsingItem(hand)
        return InteractionResult.CONSUME
    }

    override fun getUseAnimation(itemStack: ItemStack): ItemUseAnimation = ItemUseAnimation.DRINK

    override fun getUseDuration(itemStack: ItemStack, user: LivingEntity): Int = DRINK_TICKS

    override fun onUseTick(level: Level, livingEntity: LivingEntity, itemStack: ItemStack, ticksRemaining: Int) {
        if (!level.isClientSide() && ticksRemaining % 5 == 0) {
            level.playSound(null, livingEntity.x, livingEntity.y, livingEntity.z, SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.5f, 0.9f + level.random.nextFloat() * 0.2f)
        }
    }

    override fun finishUsingItem(itemStack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (entity is ServerPlayer && !WardensFlask.drink(entity)) WardensFlask.empty(entity)
        return itemStack
    }

    companion object {
        const val DRINK_TICKS = 28
    }
}
