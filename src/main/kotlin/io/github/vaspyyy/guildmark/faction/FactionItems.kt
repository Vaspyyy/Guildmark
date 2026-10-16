package io.github.vaspyyy.guildmark.faction

import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/** A purse lifted off someone in a city: use it to count what's inside (see Pickpocket). */
class PurseItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (level is ServerLevel) {
            val marks = stack.getOrDefault(FactionRegistry.PURSE_MARKS.get(), 0)
            stack.consume(1, player)
            level.playSound(null, player.x, player.y, player.z, FactionRegistry.PURSE_OPEN.get(), SoundSource.PLAYERS, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
            if (marks > 0) {
                player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), marks), Prediction.SERVER_ONLY)
                player.sendOverlayMessage(Component.translatable("message.guildmark.purse_opened", marks))
            } else {
                player.sendOverlayMessage(Component.translatable("message.guildmark.purse_empty"))
            }
        }
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.purse.desc").withStyle { it.withColor(0x8A8A8A) })
    }

    companion object {
        /** A purse holding [marks] Guild Marks. */
        fun of(marks: Int): ItemStack = ItemStack(FactionRegistry.PURSE.get()).also { it.set(FactionRegistry.PURSE_MARKS.get(), marks) }
    }
}

/**
 * Something stolen on a faction's job: House Varn's ledger, the Ember of the First Hearth, the Regent's seal or
 * the Widow's own ledger. Its look is picked by items/heist_prize.json from the custom model data.
 */
class HeistPrizeItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.get(FactionRegistry.PRIZE.get())?.let { Component.translatable("item.guildmark.heist_prize.$it") } ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        builder.accept(Component.translatable("item.guildmark.heist_prize.lore").withStyle { it.withColor(0x8A8A8A).withItalic(true) })
    }

    companion object {
        /** The prize [id] (see [Heist.prize]). */
        fun of(id: String): ItemStack = ItemStack(FactionRegistry.HEIST_PRIZE.get()).also {
            it.set(FactionRegistry.PRIZE.get(), id)
            it.set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(id), listOf()))
        }
    }
}
