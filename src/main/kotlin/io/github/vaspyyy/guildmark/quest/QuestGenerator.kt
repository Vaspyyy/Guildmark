package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.progression.AdventurerRank
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
    fun generate(random: RandomSource, profession: Identifier?, poster: String, tier: Int, hasRoad: Boolean): QuestNote {
        val entries = QuestPools.pools
            .filter { it.professions.isEmpty() || profession in it.professions }
            .flatMap { it.entries }
            .filter { hasRoad || !it.type.needsRoad }
        val entry = pick(random, entries, tier)
        return build(random, entry, poster, rankFor(random, entry, tier))
    }

    /** A note with no villager behind it yet, e.g. the first notes on a new board. */
    fun generateAnonymous(random: RandomSource, tier: Int, hasRoad: Boolean): QuestNote {
        val pool = QuestPools.pools.randomOrNull(random)
        val profession = pool?.professions?.randomOrNull(random)
        val poster = "${NAMES[random.nextInt(NAMES.size)]} the ${title(profession)}"
        val entry = pick(random, pool?.entries.orEmpty().filter { hasRoad || !it.type.needsRoad }, tier)
        return build(random, entry, poster, rankFor(random, entry, tier))
    }

    /** A note of one specific type from any pool, for testing. Null if no pool offers that type. */
    fun generateOfType(random: RandomSource, type: QuestType, tier: Int): QuestNote? {
        val entries = QuestPools.pools.flatMap { it.entries }.filter { it.type == type }
        if (entries.isEmpty()) return null
        val poster = "${NAMES[random.nextInt(NAMES.size)]} the Guildmaster"
        val entry = pick(random, entries, MAX_TIER)
        return build(random, entry, poster, rankFor(random, entry, tier))
    }

    /**
     * A guild hall contract: any job from any pool, rated at the adventurer's own rank and paying half
     * again as much as a board note.
     */
    fun generateHall(random: RandomSource, rank: Int, hasRoad: Boolean, poster: String): QuestNote {
        val entries = QuestPools.pools.flatMap { it.entries }.filter { hasRoad || !it.type.needsRoad }
        val note = build(random, pick(random, entries, MAX_TIER), poster, rank)
        return note.copy(reward = note.reward * 3 / 2)
    }

    /** A rank trial: a champion rated at the rank it promotes to. */
    fun generateTrial(rank: AdventurerRank, poster: String): QuestNote =
        QuestNote(QuestType.CHAMPION, poster, rank.trialEntity, 1, 5 + rank.ordinal * 5, 3, "", rank.ordinal, trial = true)

    /**
     * Notes near spawn are mostly F and E rank; further out they spread up to A. Champions rate a rank
     * higher than other jobs in the same place.
     */
    private fun rankFor(random: RandomSource, entry: QuestPool.Entry, tier: Int): Int {
        val base = (tier - 1) * 2 + random.nextInt(4) - 2 + if (entry.type == QuestType.CHAMPION) 1 else 0
        return base.coerceIn(0, AdventurerRank.A.ordinal)
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

    /** Bigger jobs and better pay the higher the rank. */
    private fun build(random: RandomSource, entry: QuestPool.Entry, poster: String, rank: Int): QuestNote {
        val countScale = 1.0f + 0.15f * rank
        val rewardScale = 1.0f + 0.35f * rank
        val base = entry.min + random.nextInt(entry.max - entry.min + 1)
        val count = if (entry.type.isSingle) 1 else (base * countScale).roundToInt().coerceAtLeast(1)
        val reward = BASE_REWARD + (count * entry.rewardPer * rewardScale).roundToInt()
        val deadline = 2 + random.nextInt(3)
        val story = entry.stories.randomOrNull(random).orEmpty()
        return QuestNote(entry.type, poster, entry.target, count, reward, deadline, story, rank)
    }

    private fun <T> List<T>.randomOrNull(random: RandomSource): T? = if (isEmpty()) null else this[random.nextInt(size)]

    /** Used only when no datapack offers anything, so a board is never stuck empty. */
    private val FALLBACK = QuestPool.Entry(QuestType.HUNT, Identifier.withDefaultNamespace("zombie"), 4, 8, 1.0f, 1, 1, listOf())
}
