package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.lair.LairTheme
import net.minecraft.network.chat.Component

/**
 * The Hollow King's court: each courtier guards one shard of his crown in a vault built like its lair.
 * The first shard is Wren's; these hold the other six, in the order the story sends you after them.
 */
enum class Courtier(
    /** Which shard (1 to 6) the courtier holds. */
    val shard: Int,
    val id: String,
    val theme: LairTheme,
    /** Adventurer rank index needed before the compass will lead you to this vault. */
    val minRank: Int,
    /** How tough the vault is, as a lair rank. */
    val vaultRank: Int,
    /** Who writes to you once this shard is yours. */
    val writer: StoryCharacter,
    /** The lore fragment found in the vault (see LoreBooks). */
    val lore: Int,
) {
    BROODMOTHER(1, "broodmother", LairTheme.SPIDER_DEN, 0, 1, StoryCharacter.WREN, 8),
    BARROW_LORD(2, "barrow_lord", LairTheme.BARROW, 1, 2, StoryCharacter.TOBIN, 9),
    DROWNED_HERALD(3, "drowned_herald", LairTheme.SUNKEN_SHRINE, 2, 3, StoryCharacter.ILSA, 10),
    HOLLOW_CRONE(4, "hollow_crone", LairTheme.WITCH_HOLLOW, 3, 4, StoryCharacter.ILSA, 11),
    FROST_REGENT(5, "frost_regent", LairTheme.RIME_VAULT, 4, 5, StoryCharacter.TOBIN, 12),
    ASH_WARDEN(6, "ash_warden", LairTheme.ASHEN_PIT, 5, 6, StoryCharacter.WREN, 13);

    val title: Component get() = Component.translatable("courtier.guildmark.$id")
    val vaultTitle: Component get() = Component.translatable("courtier.guildmark.$id.vault")

    companion object {
        fun forShard(shard: Int): Courtier? = entries.firstOrNull { it.shard == shard }
    }
}
