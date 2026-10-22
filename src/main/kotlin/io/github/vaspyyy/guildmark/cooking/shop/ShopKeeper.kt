package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.dish.DishItem
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.market.Owed
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.UUID

/**
 * The keeper of a cookshop and a dish held out to them (living-world/cook/design.md 6.4): the shop's owner, or one of their party,
 * who uses its keeper with a dish in the main hand has the keeper put it in the larder, as they can by opening the larder
 * themselves. It is a listener on the use of an entity, as the back doors' is ([io.github.vaspyyy.guildmark.cooking.market.KitchenDoors]),
 * so it needs no edit to the townsperson, and it is any of its business only for a dish in the main hand held out to a cookshop's
 * keeper by someone who may open the larder; for everyone else it returns at once and the usual chain runs (a click with an empty hand
 * is a talk, never the market hall's screen, and a dish held out by anyone else is a talk too).
 */
internal object ShopKeeper {
    /** The game time each player last held a dish out to a keeper, so the same click does not also eat it. */
    private val poked = HashMap<UUID, Long>()

    fun register() {
        Cooking.onForget { poked.clear() }
        val bus = NeoForge.EVENT_BUS
        bus.addListener(PlayerInteractEvent.EntityInteract::class.java, ::onUseEntity)
        bus.addListener(PlayerInteractEvent.RightClickItem::class.java, ::onUseItem)
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { (it.entity as? ServerPlayer)?.let { player -> poked.remove(player.uuid) } }
    }

    private fun onUseEntity(event: PlayerInteractEvent.EntityInteract) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.hand != InteractionHand.MAIN_HAND || player.isSpectator) return
        val stack = player.mainHandItem
        if (stack.item !is DishItem) return
        val keeper = event.target as? Townsperson ?: return
        // Cookshops off: the keeper is only a person to talk to
        if (!Cooking.enabled() || !Cooking.shops()) return
        val level = player.level()
        if (!CookShops.keeps(keeper)) return
        val works = CookShops.keptBy(level, keeper) ?: return
        val shop = CookShops.shopOf(level, works) ?: return
        if (!CookShops.mayOpen(level, shop, player)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        poked[player.uuid] = level.gameTime
        hand(level, player, keeper, shop, stack)
    }

    /** The same click, as the game sends it on: an item used. A dish that was just held out to a keeper is not also eaten. */
    private fun onUseItem(event: PlayerInteractEvent.RightClickItem) {
        val player = event.entity as? ServerPlayer ?: return
        val at = poked[player.uuid] ?: return
        if (player.level().gameTime - at > 1L || event.itemStack.item !is DishItem) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
    }

    /** [player] holds [stack] out to [keeper]: it goes into [shop]'s larder as far as there is room, and what does not fit stays in their hand. */
    private fun hand(level: ServerLevel, player: ServerPlayer, keeper: Townsperson, shop: ShopRec, stack: ItemStack) {
        keeper.lookAt(player)
        keeper.attend(player, 60)
        if (!CookShops.sellable(stack)) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.cook.shop.unsellable"))
            return
        }
        val name = Dishes.kindOf(stack)?.let { Owed.dish(it) } ?: Component.empty()
        val moved = CookShops.view(level, shop).insert(stack)
        if (moved <= 0) {
            player.sendOverlayMessage(Component.translatable("message.guildmark.cook.shop.full"))
            return
        }
        val all = stack.isEmpty
        if (all) player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        if (keeper.director == null) keeper.act(Activity.RECEIVE)
        level.playSound(null, keeper.blockPosition(), CookSounds.get("cook.larder"), SoundSource.NEUTRAL, 0.8f, 1f)
        player.sendOverlayMessage(Component.translatable(if (all) "message.guildmark.cook.shop.stocked" else "message.guildmark.cook.shop.stocked.some", moved, name))
    }
}
