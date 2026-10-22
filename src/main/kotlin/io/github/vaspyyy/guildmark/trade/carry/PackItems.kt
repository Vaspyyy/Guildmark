package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.trade.TradeConfig
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * The Pack Harness (living-world/trader/design.md, Part 3): bought at the market hall's counter for 12 Marks with a Drover's Whistle,
 * and buckled on a tame donkey, mule or llama by using it on the animal, which [Harness] does. It does nothing in the air.
 */
class PackHarnessItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.pack_harness.hint").withStyle { it.withColor(HINT) })
        builder.accept(Component.translatable("item.guildmark.pack_harness.hint2").withStyle { it.withColor(HINT) })
    }

    private companion object {
        const val HINT = 0x8A8A8A
    }
}

/**
 * The Drover's Whistle: held up and blown it calls every pack animal of yours within [PackFollow.WHISTLE_RANGE] blocks to follow,
 * and blown while sneaking it has them stay where they are (see [PackFollow.whistle]). It is blown like the goat horn is: held up for
 * a moment, which is what turns its picture to the blown one while it is in use. With carrying switched off it does nothing at all.
 */
class DroversWhistleItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (!TradeConfig.carry()) return InteractionResult.PASS
        player.startUsingItem(hand)
        if (player is ServerPlayer) {
            PackFollow.whistle(player, player.isShiftKeyDown)
            player.cooldowns.addCooldown(player.getItemInHand(hand), COOLDOWN)
        }
        return InteractionResult.CONSUME
    }

    override fun getUseAnimation(stack: ItemStack): ItemUseAnimation = ItemUseAnimation.TOOT_HORN

    override fun getUseDuration(stack: ItemStack, entity: LivingEntity): Int = BLOW_TICKS

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.drovers_whistle.hint").withStyle { it.withColor(HINT) })
        builder.accept(Component.translatable("item.guildmark.drovers_whistle.hint2").withStyle { it.withColor(HINT) })
    }

    private companion object {
        const val HINT = 0x8A8A8A

        /** How long it is held up and blown, and how long before it can be blown again. */
        const val BLOW_TICKS = 20
        const val COOLDOWN = 30
    }
}
