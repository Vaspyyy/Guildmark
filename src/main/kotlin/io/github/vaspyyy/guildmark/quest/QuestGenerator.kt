package io.github.vaspyyy.guildmark.quest

import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Builds notes from the datapack [QuestPools]: each villager posts the jobs its profession
 * cares about, and villages further from spawn ask for more and pay more.
 */
object QuestGenerator {
    const val MAX_TIER = 3
    private const val BLOCKS_PER_TIER = 1500.0
    /** Every job pays at least this, so small errands are still worth the walk. */
    private const val BASE_REWARD = 2

    private val NAMES = listOf(
        "Maren", "Tobin", "Elsbeth", "Garrick", "Wren", "Osric", "Hilde", "Bram", "Isolde", "Pell",
        "Aldo", "Brigid", "Cedric", "Dagny", "Edwin", "Freya", "Gunnar", "Halla", "Ivo", "Jorun",
        "Kestrel", "Linnea", "Magnus", "Nell", "Odo", "Petra", "Quill", "Rosalind", "Sten", "Tilda",
    )

    /** A stable first name for a given seed, so the same villager always signs the same way. */
    fun nameFor(seed: Long): String = NAMES[Math.floorMod(seed, NAMES.size)]

    /** "Farmer", "Leatherworker", ... from a profession id; jobless villagers are just "Villager". */
    fun title(profession: Identifier?): String {
        val path = profession?.path ?: return "Villager"
        if (path == "none" || path == "nitwit") return "Villager"
        return path.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    /** 1 near spawn, rising every [BLOCKS_PER_TIER] blocks out, up to [MAX_TIER]. */
    fun tierAt(x: Int, z: Int): Int {
        val distance = sqrt(x.toDouble() * x + z.toDouble() * z)
        return (1 + (distance / BLOCKS_PER_TIER).toInt()).coerceAtMost(MAX_TIER)
    }

    /** A note from a specific villager. */
    fun generate(random: RandomSource, profession: Identifier?, poster: String, tier: Int): QuestNote {
        val entries = QuestPools.pools
            .filter { it.professions.isEmpty() || profession in it.professions }
            .flatMap { it.entries }
        return build(random, pick(random, entries, tier), poster, tier)
    }

    /** A note with no villager behind it yet, e.g. the first notes on a new board. */
    fun generateAnonymous(random: RandomSource, tier: Int): QuestNote {
        val pool = QuestPools.pools.randomOrNull(random)
        val profession = pool?.professions?.randomOrNull(random)
        val poster = "${NAMES[random.nextInt(NAMES.size)]} the ${title(profession)}"
        return build(random, pick(random, pool?.entries.orEmpty(), tier), poster, tier)
    }

    private fun pick(random: RandomSource, entries: List<QuestPool.Entry>, tier: Int): QuestPool.Entry {
        val open = entries.filter { it.minTier <= tier }
        if (open.isEmpty()) return FALLBACK
        var roll = random.nextInt(open.sumOf { it.weight })
        for (entry in open) {
            roll -= entry.weight
            if (roll < 0) return entry
        }
        return open.last()
    }

    private fun build(random: RandomSource, entry: QuestPool.Entry, poster: String, tier: Int): QuestNote {
        val countScale = 1.0f + 0.25f * (tier - 1)
        val rewardScale = 1.0f + 0.5f * (tier - 1)
        val base = entry.min + random.nextInt(entry.max - entry.min + 1)
        val count = (base * countScale).roundToInt().coerceAtLeast(1)
        val reward = BASE_REWARD + (count * entry.rewardPer * rewardScale).roundToInt()
        val deadline = 2 + random.nextInt(3)
        val story = entry.stories.randomOrNull(random).orEmpty()
        return QuestNote(entry.type, poster, entry.target, count, reward, deadline, story)
    }

    private fun <T> List<T>.randomOrNull(random: RandomSource): T? = if (isEmpty()) null else this[random.nextInt(size)]

    /** Used only when no datapack offers anything, so a board is never stuck empty. */
    private val FALLBACK = QuestPool.Entry(QuestType.HUNT, Identifier.withDefaultNamespace("zombie"), 4, 8, 1.0f, 1, 1, listOf())
}
