package io.github.vaspyyy.guildmark.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/** A Dusk Rider's bridle, taken when it falls: use it to call a spectral steed and ride it (see SpectralSteed). */
class DuskBridleItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        // Called to the ground under you, never to catch a fall
        if (player.isPassenger || player.isShiftKeyDown || !player.onGround()) return InteractionResult.FAIL
        if (level is ServerLevel) {
            if (SpectralSteed.summon(level, player) == null) {
                player.sendOverlayMessage(Component.translatable("message.guildmark.bridle_no_room"))
                return InteractionResult.FAIL
            }
            player.cooldowns.addCooldown(player.getItemInHand(hand), COOLDOWN)
        }
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.dusk_bridle.hint").withStyle { it.withColor(0x8A8A8A) })
    }

    private companion object {
        /** Ticks before it can call again. */
        const val COOLDOWN = 200
    }
}
