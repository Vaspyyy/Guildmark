package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.registry.ModVillagers
import net.minecraft.ChatFormatting
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.item.trading.ItemCost
import net.minecraft.world.item.trading.MerchantOffer
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.Optional
import java.util.function.Consumer

/**
 * A homestead charter, sold by the Guildmaster: used on open ground, it stakes a plot for its holder there, the Charter
 * Post where they stand and the plot reaching the way they face (HomesteadClaims). Held, it shows where the plot would
 * go and whether the land will do (client/homestead/HomesteadClient). A dye in the other hand picks the homestead's
 * colour.
 */
class CharterItem(properties: Properties) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player ?: return InteractionResult.PASS
        if (context.clickedFace != Direction.UP) {
            if (player is ServerPlayer) player.sendOverlayMessage(Component.translatable("message.guildmark.homestead.on_ground").withStyle(ChatFormatting.GOLD))
            return InteractionResult.FAIL
        }
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        val verdict = HomesteadClaims.claim(level, player, context.clickedPos.above(), player.direction)
        if (!verdict.ok) return InteractionResult.FAIL
        if (!player.isCreative) context.itemInHand.shrink(1)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        add.accept(Component.translatable("item.guildmark.homestead_charter.desc").withStyle(ChatFormatting.GRAY))
        add.accept(Component.translatable("item.guildmark.homestead_charter.facing").withStyle(ChatFormatting.DARK_GRAY))
        add.accept(Component.translatable("item.guildmark.homestead_charter.dye").withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        /**
         * The Guildmaster sells charters (two payments of Marks, as a trade takes at most a stack in each): their trades
         * come from data, so the charter is put among them as a player turns to them.
         */
        fun onTrade(event: PlayerInteractEvent.EntityInteract) {
            if (event.level.isClientSide) return
            val villager = event.target as? Villager ?: return
            val guildmaster = ModVillagers.GUILDMASTER.key ?: return
            if (!villager.villagerData.profession().`is`(guildmaster)) return
            val offers = villager.offers
            val have = offers.indexOfFirst { it.result.`is`(HomesteadRegistry.CHARTER.get()) }
            if (!Homesteads.enabled()) {
                if (have >= 0) offers.removeAt(have)
                return
            }
            if (have >= 0) return
            val mark = ModItems.GUILD_MARK.get()
            offers.add(
                MerchantOffer(
                    ItemCost(mark, HomesteadTiers.CHARTER_MARKS_A), Optional.of(ItemCost(mark, HomesteadTiers.CHARTER_MARKS_B)),
                    ItemStack(HomesteadRegistry.CHARTER.get()), 0, 4, 0, 0f,
                ),
            )
        }
    }
}
