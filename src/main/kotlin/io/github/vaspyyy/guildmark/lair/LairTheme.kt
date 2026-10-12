package io.github.vaspyyy.guildmark.lair

import net.minecraft.core.registries.Registries
import net.minecraft.util.RandomSource
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
    /** Only built as a shard vault for the main quest, never for an ordinary lair hunt. */
    val vaultOnly: Boolean = false,
    /** How many minions wake with the boss; -1 for the usual three plus the lair's rank. */
    val guards: Int = -1,
    /** The boss is one of our own, with its own bar, fight and size; the lair leaves it as it is. */
    val ownBoss: Boolean = false,
) {
    SPIDER_DEN("spider_den", 0, { Blocks.COARSE_DIRT }, { Blocks.MOSSY_COBBLESTONE }, { Blocks.COBWEB },
        listOf("spider", "cave_spider"), "spider", BuiltInLootTables.SIMPLE_DUNGEON),
    BANDIT_CAMP("bandit_camp", 1, { Blocks.COARSE_DIRT }, { Blocks.SPRUCE_LOG }, { Blocks.CAMPFIRE },
        listOf("pillager", "vindicator", "pillager"), "vindicator", BuiltInLootTables.PILLAGER_OUTPOST),
    WARDENS_WATCH("wardens_watch", 2, { Blocks.CRACKED_STONE_BRICKS }, { Blocks.STONE_BRICKS }, { Blocks.SOUL_LANTERN },
        listOf("guildmark:hollow_soldier"), "guildmark:fallen_warden", BuiltInLootTables.SIMPLE_DUNGEON, guards = 2, ownBoss = true),
    BARROW("barrow", 2, { Blocks.CRACKED_STONE_BRICKS }, { Blocks.MOSSY_STONE_BRICKS }, { Blocks.SOUL_LANTERN },
        listOf("zombie", "skeleton", "zombie"), "husk", BuiltInLootTables.BURIED_TREASURE),
    WITCH_HOLLOW("witch_hollow", 3, { Blocks.PODZOL }, { Blocks.DARK_OAK_LOG }, { Blocks.RED_MUSHROOM_BLOCK },
        listOf("witch", "slime", "zombie_villager"), "witch", BuiltInLootTables.WOODLAND_MANSION),
    ASHEN_PIT("ashen_pit", 5, { Blocks.BLACKSTONE }, { Blocks.POLISHED_BLACKSTONE_BRICKS }, { Blocks.MAGMA_BLOCK },
        listOf("wither_skeleton", "blaze"), "wither_skeleton", BuiltInLootTables.BASTION_TREASURE),
    SUNKEN_SHRINE("sunken_shrine", 4, { Blocks.PRISMARINE_BRICKS }, { Blocks.DARK_PRISMARINE }, { Blocks.SEA_LANTERN },
        listOf("drowned", "drowned", "zombie"), "drowned", BuiltInLootTables.UNDERWATER_RUIN_BIG, vaultOnly = true),
    RIME_VAULT("rime_vault", 5, { Blocks.PACKED_ICE }, { Blocks.BLUE_ICE }, { Blocks.POWDER_SNOW },
        listOf("stray", "stray", "skeleton"), "stray", BuiltInLootTables.IGLOO_CHEST, vaultOnly = true);

    val title: Component get() = Component.translatable("lair.guildmark.$id")
    val bossTitle: Component get() = Component.translatable("lair.guildmark.$id.boss")
    fun minionIds(): List<Identifier> = minions.map { Identifier.parse(it) }
    fun bossId(): Identifier = Identifier.parse(boss)

    /** The note target that marks a lair hunt as this theme. */
    val target: Identifier get() = Identifier.fromNamespaceAndPath("guildmark", id)

    companion object {
        /** The nastiest theme open at [rank]; lair notes from before themes were picked per note use this. */
        fun forRank(rank: Int): LairTheme = entries.filter { !it.vaultOnly && it.minRank <= rank }.maxBy { it.minRank }

        /** One of the two nastiest themes open at [rank], so lair hunts of the same rank vary. */
        fun pick(random: RandomSource, rank: Int): LairTheme {
            val open = entries.filter { !it.vaultOnly && it.minRank <= rank }.sortedByDescending { it.minRank }.take(2)
            return open[random.nextInt(open.size)]
        }

        /** The theme a lair note's target names, or the rank's default for older notes. */
        fun of(target: Identifier, rank: Int): LairTheme =
            entries.firstOrNull { !it.vaultOnly && target.namespace == "guildmark" && it.id == target.path } ?: forRank(rank)
    }
}
