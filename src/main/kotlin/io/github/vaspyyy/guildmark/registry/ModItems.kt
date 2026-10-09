package io.github.vaspyyy.guildmark.registry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.item.ContractItem
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

    val QUEST_BOARD: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem("quest_board", ModBlocks.QUEST_BOARD)
}
