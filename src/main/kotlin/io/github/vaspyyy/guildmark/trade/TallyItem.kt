package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.registry.ModCreativeTabs
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

/**
 * The Tally (living-world/trader/design.md, Part 1): a small leather-bound book with notched edges, the trader's own. Right-click it anywhere
 * and its five pages open ([Tally.open]). It holds nothing itself: what it knows is kept in its owner's record ([TradeBooks.Book.sights]), so
 * a lost one is given again, free, with all its news.
 */
class TallyItem(properties: Item.Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) Tally.open(level, player)
        return InteractionResult.SUCCESS
    }
}

/**
 * What the Tally adds to the registries: the item, in a register of its own (as the seasons' Almanac and the stalls' wares have), so that no
 * shared registry is touched. Its model is `items/tally.json` (the trade's art stream's): the closed book in the pack, on the ground and on a shelf, an open one in the hand.
 */
object TallyItems {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)

    /** The Tally. */
    val TALLY: DeferredItem<TallyItem> = ITEMS.registerItem("tally", ::TallyItem, UnaryOperator<Item.Properties> { it.stacksTo(1).rarity(Rarity.UNCOMMON) })

    fun register(modBus: IEventBus) {
        ITEMS.register(modBus)
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == ModCreativeTabs.MAIN.key) event.accept(TALLY.get())
        }
    }
}
