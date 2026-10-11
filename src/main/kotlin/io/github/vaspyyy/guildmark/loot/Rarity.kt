package io.github.vaspyyy.guildmark.loot

import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource

/** How good a relic is: its colour, how many affixes it rolls and how strongly it's enchanted. */
enum class Rarity(val id: String, val color: Int, val affixes: Int, val enchantLevel: Int) {
    COMMON("common", 0xD8D8D8, 0, 8),
    UNCOMMON("uncommon", 0x5FD35F, 1, 14),
    RARE("rare", 0x4F9BFF, 2, 20),
    EPIC("epic", 0xB46CFF, 3, 26),
    LEGENDARY("legendary", 0xFFB830, 4, 30);

    val title: Component get() = Component.translatable("rarity.guildmark.$id").withStyle { it.withColor(color).withItalic(false) }

    companion object {
        fun of(index: Int): Rarity = entries[index.coerceIn(0, entries.size - 1)]

        /**
         * Roll a rarity for loot of the given strength (roughly an adventurer rank, 0 to 6). Stronger
         * sources shift the odds up; [floor] is the worst it can be.
         */
        fun roll(random: RandomSource, strength: Int, floor: Rarity = COMMON): Rarity {
            val weights = entries.map { r ->
                when (r) {
                    COMMON -> (40 - strength * 6).coerceAtLeast(4)
                    UNCOMMON -> 34
                    RARE -> 18 + strength * 3
                    EPIC -> 5 + strength * 3
                    LEGENDARY -> 1 + strength
                }
            }
            var pick = random.nextInt(weights.sum())
            for ((i, w) in weights.withIndex()) {
                if (pick < w) return entries[maxOf(i, floor.ordinal)]
                pick -= w
            }
            return floor
        }
    }
}
