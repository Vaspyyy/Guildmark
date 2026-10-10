package io.github.vaspyyy.guildmark.lair

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootTable

/**
 * What kind of den a lair is: who lives there, who rules it, what it's built from and what it hoards.
 * Higher-rank lairs pick the nastier themes.
 */
enum class LairTheme(
    val id: String,
    val minRank: Int,
    val floor: () -> Block,
    val wall: () -> Block,
    val accent: () -> Block,
    val minions: List<String>,
    val boss: String,
    val loot: ResourceKey<LootTable>,
) {
    SPIDER_DEN("spider_den", 0, { Blocks.COARSE_DIRT }, { Blocks.MOSSY_COBBLESTONE }, { Blocks.COBWEB },
        listOf("spider", "cave_spider"), "spider", BuiltInLootTables.SIMPLE_DUNGEON),
    BARROW("barrow", 2, { Blocks.CRACKED_STONE_BRICKS }, { Blocks.MOSSY_STONE_BRICKS }, { Blocks.SOUL_LANTERN },
        listOf("zombie", "skeleton", "zombie"), "husk", BuiltInLootTables.BURIED_TREASURE),
    WITCH_HOLLOW("witch_hollow", 3, { Blocks.PODZOL }, { Blocks.DARK_OAK_LOG }, { Blocks.RED_MUSHROOM_BLOCK },
        listOf("witch", "slime", "zombie_villager"), "witch", BuiltInLootTables.WOODLAND_MANSION),
    ASHEN_PIT("ashen_pit", 5, { Blocks.BLACKSTONE }, { Blocks.POLISHED_BLACKSTONE_BRICKS }, { Blocks.MAGMA_BLOCK },
        listOf("wither_skeleton", "blaze"), "wither_skeleton", BuiltInLootTables.BASTION_TREASURE);

    val title: Component get() = Component.translatable("lair.guildmark.$id")
    val bossTitle: Component get() = Component.translatable("lair.guildmark.$id.boss")
    fun minionIds(): List<Identifier> = minions.map { Identifier.withDefaultNamespace(it) }
    fun bossId(): Identifier = Identifier.withDefaultNamespace(boss)

    companion object {
        fun forRank(rank: Int): LairTheme = entries.last { it.minRank <= rank }
    }
}
