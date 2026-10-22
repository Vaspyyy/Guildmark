package io.github.vaspyyy.guildmark.cooking.dish

import io.github.vaspyyy.guildmark.client.cooking.CookBookScreen
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer
import java.util.function.UnaryOperator

/**
 * The Cook's Book (living-world/cook/design.md 3.4): the player's record of what they have learned, given once at the
 * first use of a station. Its data is the player's [CookJournal], so losing the item loses nothing. Using it opens its
 * screen, the way the Almanac's does: a call behind `level.isClientSide`, so nothing of the client is touched on a server.
 */
class CookBookItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) CookBookScreen.open()
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, builder, flag)
        builder.accept(Component.translatable("item.guildmark.cook_book.desc").withStyle(ChatFormatting.GRAY))
        builder.accept(Component.translatable("item.guildmark.cook_book.keep").withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        /** How the item is made (stack size, rarity...): Part D's to tune, so the registry never needs to change. */
        val PROPERTIES: UnaryOperator<Item.Properties> = UnaryOperator { it.stacksTo(1).rarity(Rarity.UNCOMMON) }
    }
}
