package io.github.vaspyyy.guildmark.item

import io.github.vaspyyy.guildmark.loot.Rarity
import io.github.vaspyyy.guildmark.loot.Relics
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/** A sealed relic cache: use it to break the seal and get a relic of at least its tier's rarity. */
class RelicCacheItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (level is ServerLevel) {
            val relic = Relics.open(level, player, stack)
            stack.consume(1, player)
            player.inventory.placeItemBackInInventory(relic, Prediction.SERVER_ONLY)
        }
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        val tier = Rarity.of(stack.getOrDefault(ModDataComponents.CACHE_TIER.get(), 0))
        builder.accept(Component.translatable("item.guildmark.relic_cache.tier", tier.title).withStyle { it.withColor(tier.color) })
        builder.accept(Component.translatable("item.guildmark.relic_cache.hint").withStyle { it.withColor(0x8A8A8A) })
    }
}
