package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.item.ContractItem
import io.github.vaspyyy.guildmark.item.RelicCacheItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.UnaryOperator

object ModItems {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)

    // Quest currency
    val GUILD_MARK: DeferredItem<Item> = ITEMS.registerSimpleItem("guild_mark", UnaryOperator<Item.Properties> {
        it.rarity(Rarity.UNCOMMON)
    })

    val CONTRACT: DeferredItem<ContractItem> = ITEMS.registerItem("contract", ::ContractItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1)
    })

    /** One of the seven shards of the Hollow Crown; which one is in its SHARD component. */
    val CROWN_SHARD: DeferredItem<Item> = ITEMS.registerSimpleItem("crown_shard", UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** A sealed cache that opens into a relic; its minimum rarity is in its CACHE_TIER component. */
    val RELIC_CACHE: DeferredItem<RelicCacheItem> = ITEMS.registerItem("relic_cache", ::RelicCacheItem, UnaryOperator<Item.Properties> {
        it.stacksTo(16).rarity(Rarity.RARE)
    })

    val QUEST_BOARD: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("quest_board", ModBlocks.QUEST_BOARD)
}
