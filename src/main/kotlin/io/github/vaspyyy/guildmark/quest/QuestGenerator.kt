package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.economy.MarketFetch
import io.github.vaspyyy.guildmark.outlaw.Captives
import io.github.vaspyyy.guildmark.progression.AdventurerRank
import io.github.vaspyyy.guildmark.lair.LairTheme
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

    /**
     * What lies round a board, deciding which jobs it can post: a Trade Road to other villages, work out at sea
     * (looked into only once a job that wants the sea comes up), the beasts living in reach ([quarry] says whether
     * there's one of the kind a hunt's target names), the outlaw bands camped in reach, whose notices [dress]
     * writes onto wanted notes, and at a capital's own board its market: [market] gives what it asks for one of an
     * item and whether it's short of it, or null for one it doesn't trade (see MarketFetch). Anywhere else, [errand]
     * gives what the nearest market asks for one, or null for an item no market trades.
     */
    class Surroundings(
        val hasRoad: Boolean,
        sea: () -> SeaWork.Reach = { SeaWork.Reach.NONE },
        private val quarry: (Identifier) -> Boolean = { false },
        wanted: () -> List<Bounties.Wanted> = { listOf() },
        held: () -> List<Captives.Held> = { listOf() },
        private val market: (Identifier) -> MarketFetch.Ask? = { null },
        private val errand: (Identifier) -> Double? = { null },
    ) {
        private val sea by lazy(sea)
        private val wanted by lazy(wanted)
        private val held by lazy(held)

        fun allows(entry: QuestPool.Entry): Boolean = when (entry.type) {
            QuestType.CHART -> sea.chart
            QuestType.SALVAGE -> SeaWork.kindOf(entry.target) in sea.salvage
            QuestType.SLAY -> quarry(entry.target)
            QuestType.BOUNTY -> wanted.isNotEmpty()
            QuestType.RESCUE -> held.isNotEmpty()
            // The market's own goods are asked for only while it's short of them
            QuestType.FETCH -> market(entry.target)?.short ?: (hasRoad || !entry.type.needsRoad)
            else -> hasRoad || !entry.type.needsRoad
        }

        /**
         * [note] paying no more than it may, or null if that would leave it paying too little to post: a fetch for
         * the market's goods half what the market asks for them, one for the markets' goods anywhere else what the
         * nearest market asks and a carrier's quarter (asking for more of cheap goods rather than go unposted), any
         * other fetch for goods that can be bought half what they cost (see GoodsPrices).
         */
        fun fit(note: QuestNote): QuestNote? {
            if (note.type != QuestType.FETCH) return GoodsPrices.fit(note)
            market(note.target)?.let { return GoodsPrices.fit(note, it.price) }
            errand(note.target)?.let { return GoodsPrices.carried(note, it * MarketFetch.CARRIED) }
            return GoodsPrices.fit(note)
        }

        /**
         * A wanted note names one of the bands in reach, the price on them and what for, and a plea for a captive names
         * someone held; any other note is left be.
         */
        fun dress(random: RandomSource, note: QuestNote): QuestNote = when (note.type) {
            QuestType.BOUNTY -> wanted.randomOrNull(random)?.let { Bounties.dress(note, it) } ?: note
            QuestType.RESCUE -> held.randomOrNull(random)?.let { Captives.dress(note, it) } ?: note
            else -> note
        }
    }

    /** A note from a specific villager. */
    fun generate(random: RandomSource, profession: Identifier?, poster: String, tier: Int, around: Surroundings): QuestNote {
        val entries = QuestPools.pools
            .filter { it.professions.isEmpty() || profession in it.professions }
            .flatMap { it.entries }
            .filter(around::allows)
        return around.dress(random, pickAndBuild(random, entries, tier, poster, around::fit) { rankFor(random, it, tier) })
    }

    /** A note with no villager behind it yet, e.g. the first notes on a new board. */
    fun generateAnonymous(random: RandomSource, tier: Int, around: Surroundings): QuestNote {
        val pool = QuestPools.pools.randomOrNull(random)
        val profession = pool?.professions?.randomOrNull(random)
        val poster = "${NAMES[random.nextInt(NAMES.size)]} the ${title(profession)}"
        val entries = pool?.entries.orEmpty().filter(around::allows)
        return around.dress(random, pickAndBuild(random, entries, tier, poster, around::fit) { rankFor(random, it, tier) })
    }

    /** A note from any pool's entries that [around] allows and [which] picks, signed by [poster]; null if there's none. */
    fun generateWhere(random: RandomSource, tier: Int, around: Surroundings, poster: String, which: (QuestPool.Entry) -> Boolean): QuestNote? {
        val entries = QuestPools.pools.flatMap { it.entries }.filter { it.minTier <= tier && which(it) && around.allows(it) }
        if (entries.isEmpty()) return null
        return pickAndBuild(random, entries, tier, poster, around::fit) { rankFor(random, it, tier) }
    }

    /** A note of one specific type from any pool, for testing. Null if no pool offers that type. */
    fun generateOfType(random: RandomSource, type: QuestType, tier: Int): QuestNote? {
        val entries = QuestPools.pools.flatMap { it.entries }.filter { it.type == type }
        if (entries.isEmpty()) return null
        val poster = "${NAMES[random.nextInt(NAMES.size)]} the Guildmaster"
        return pickAndBuild(random, entries, MAX_TIER, poster) { rankFor(random, it, tier) }
    }

    /**
     * A guild hall contract: any job from any pool, rated at the adventurer's own rank and paying half
     * again as much as a board note (a fetch, no more than its goods allow: see GoodsPrices).
     */
    fun generateHall(random: RandomSource, rank: Int, around: Surroundings, poster: String): QuestNote {
        val entries = QuestPools.pools.flatMap { it.entries }.filter(around::allows)
        val note = around.dress(random, pickAndBuild(random, entries, MAX_TIER, poster, around::fit) { rank })
        val more = note.copy(reward = note.reward * 3 / 2)
        return around.fit(more) ?: GoodsPrices.capped(more)
    }

    /**
     * Picks one of [entries] and builds its note, rated at [rank]. A fetch for goods that can be bought pays no more than
     * half what they cost ([fit]; see GoodsPrices and Surroundings.fit), and one that would be left paying next to
     * nothing is passed over for another.
     */
    private fun pickAndBuild(
        random: RandomSource, entries: List<QuestPool.Entry>, tier: Int, poster: String,
        fit: (QuestNote) -> QuestNote? = GoodsPrices::fit, rank: (QuestPool.Entry) -> Int,
    ): QuestNote {
        var open = entries
        while (true) {
            val entry = pick(random, open, tier)
            fit(build(random, entry, poster, rank(entry)))?.let { return it }
            open = open.filter { it !== entry }
        }
    }

    /** A lair hunt at the adventurer's own rank: one boss, paid well. */
    fun generateLair(random: RandomSource, rank: Int, poster: String): QuestNote {
        val theme = LairTheme.pick(random, rank)
        return QuestNote(QuestType.LAIR, poster, theme.target, 1, 12 + rank * 8 + random.nextInt(5), 4, lairStory(random, theme), rank)
    }

    /** Every lair theme has its own rumours, used when a lair note doesn't bring its own. */
    private fun lairStory(random: RandomSource, theme: LairTheme): String = "quest.guildmark.story.lair.${theme.id}.${random.nextInt(LAIR_STORIES)}"
    private const val LAIR_STORIES = 3

    /** A rank trial: a champion rated at the rank it promotes to. */
    fun generateTrial(rank: AdventurerRank, poster: String): QuestNote =
        QuestNote(QuestType.CHAMPION, poster, rank.trialEntity, 1, 5 + rank.ordinal * 5, 3, "", rank.ordinal, trial = true)

    /**
     * Notes near spawn are mostly F and E rank; further out they spread up to A. Champions and outlaw captains rate a
     * rank higher than other jobs in the same place, and a field boss's hunt two higher.
     */
    private fun rankFor(random: RandomSource, entry: QuestPool.Entry, tier: Int): Int {
        val base = (tier - 1) * 2 + random.nextInt(4) - 2 + when (entry.type) {
            QuestType.CHAMPION, QuestType.BOUNTY, QuestType.RESCUE -> 1
            QuestType.SLAY -> 2
            else -> 0
        }
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
        // A voyage gets two days more
        val deadline = 2 + random.nextInt(3) + if (entry.type.needsSea) 2 else 0
        if (entry.type == QuestType.LAIR) {
            // Lair hunts pick their den now, so the note can say what's out there
            val theme = LairTheme.pick(random, rank)
            val story = entry.stories.randomOrNull(random) ?: lairStory(random, theme)
            return QuestNote(entry.type, poster, theme.target, count, reward, deadline, story, rank)
        }
        val story = entry.stories.randomOrNull(random).orEmpty()
        return QuestNote(entry.type, poster, entry.target, count, reward, deadline, story, rank)
    }

    private fun <T> List<T>.randomOrNull(random: RandomSource): T? = if (isEmpty()) null else this[random.nextInt(size)]

    /** Used only when no datapack offers anything, so a board is never stuck empty. */
    private val FALLBACK = QuestPool.Entry(QuestType.HUNT, Identifier.withDefaultNamespace("zombie"), 4, 8, 1.0f, 1, 1, listOf())
}
