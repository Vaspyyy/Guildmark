package io.github.vaspyyy.guildmark.advance

import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * A village project. Every contract turned in at the village board adds its pay as progress;
 * contracts that fit the project count triple. Villages work through these in order, then keep
 * building Trade Roads to further villages.
 */
enum class Advance(
    val id: String,
    val cost: Int,
    private val iconItem: () -> Item,
    private val fetchItems: Set<String> = setOf(),
    private val countsLogs: Boolean = false,
    private val huntTargets: Set<String> = setOf(),
    private val countsClear: Boolean = false,
    private val countsTravel: Boolean = false,
) {
    LAMP_POSTS(
        "lamp_posts", 60, { Items.LANTERN },
        fetchItems = setOf("coal", "torch", "iron_ingot", "iron_nugget", "copper_ingot", "glowstone_dust"),
        countsClear = true,
    ),
    GUILD_HALL(
        "guild_hall", 100, { Items.LECTERN },
        fetchItems = setOf("oak_planks", "spruce_planks", "glass", "glass_pane", "book", "paper", "bookshelf", "lantern", "stone_bricks"),
        countsLogs = true,
    ),
    TRADE_ROAD(
        "trade_road", 120, { Items.DIRT_PATH },
        fetchItems = setOf("cobblestone", "stone", "gravel", "flint", "torch"),
        countsLogs = true,
        countsTravel = true,
    ),
    PALISADE(
        "palisade", 150, { Items.SPRUCE_LOG },
        fetchItems = setOf("stone", "cobblestone", "andesite", "granite", "diorite", "clay_ball", "stick"),
        countsLogs = true,
        huntTargets = setOf("zombie", "husk", "drowned", "spider"),
    ),
    ARCHER_TOWER(
        "archer_tower", 300, { Items.BOW },
        fetchItems = setOf("stick", "feather", "flint", "string", "iron_ingot", "stone", "cobblestone"),
        huntTargets = setOf("skeleton", "pillager", "vindicator", "witch"),
    );

    val title: Component get() = Component.translatable("advance.guildmark.$id")
    val description: Component get() = Component.translatable("advance.guildmark.$id.desc")
    val wants: Component get() = Component.translatable("advance.guildmark.$id.wants")
    val icon: ItemStack get() = ItemStack(iconItem())

    /** Does this contract help this project directly? */
    fun matches(note: QuestNote): Boolean {
        val vanilla = note.target.namespace == Identifier.DEFAULT_NAMESPACE
        return when (note.type) {
            QuestType.CLEAR -> countsClear
            QuestType.HUNT, QuestType.CHAMPION -> vanilla && note.target.path in huntTargets
            // Clearing a lair near the village makes everyone safer
            QuestType.LAIR -> countsClear || huntTargets.isNotEmpty()
            QuestType.DELIVER, QuestType.ESCORT -> countsTravel
            QuestType.FETCH -> (vanilla && note.target.path in fetchItems) ||
                (countsLogs && ItemStack(BuiltInRegistries.ITEM.getValue(note.target)).`is`(ItemTags.LOGS))
        }
    }

    /** Progress a turned-in contract adds. */
    fun pointsFor(note: QuestNote): Int = if (matches(note)) note.reward * 3 else note.reward

    companion object {
        /** Once the fixed chain is done, every further project is another Trade Road to the next village. */
        fun at(index: Int): Advance = entries.getOrNull(index) ?: TRADE_ROAD
    }
}
