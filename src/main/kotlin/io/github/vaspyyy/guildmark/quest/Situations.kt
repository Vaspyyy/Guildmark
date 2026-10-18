package io.github.vaspyyy.guildmark.quest

import io.github.vaspyyy.guildmark.block.QuestBoardBlockEntity
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.economy.MarketFetch
import io.github.vaspyyy.guildmark.guild.Holdings
import io.github.vaspyyy.guildmark.guild.RivalGuild
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.npc.villager.Villager

/**
 * Notes about what's going on in the village right now, posted now and then instead of a villager's
 * usual work: rebuilding after a lost siege, a feast after a won one, the monsters of the village's own
 * land, a den in the hills, or the rival guild that holds the village and does nothing about any of it.
 * Story lines name the village (and the rival), so they read as news from that place.
 */
object Situations {
    private const val CHANCE = 0.3f

    private class Job(val type: QuestType, val target: String, val min: Int, val max: Int, val rewardPer: Float)

    // Goods nobody sells, so handing in what was bought never pays (see GoodsPrices): no glass, bread, beef, cake or pie
    private val REBUILD = listOf(Job(QuestType.FETCH, "oak_planks", 32, 64, 0.15f), Job(QuestType.FETCH, "cobblestone", 32, 64, 0.12f),
        Job(QuestType.FETCH, "stone_bricks", 16, 32, 0.25f), Job(QuestType.FETCH, "oak_log", 16, 32, 0.25f))
    private val FEAST = listOf(Job(QuestType.FETCH, "baked_potato", 12, 24, 0.3f), Job(QuestType.FETCH, "cooked_mutton", 8, 16, 0.5f),
        Job(QuestType.FETCH, "mushroom_stew", 1, 2, 4.0f), Job(QuestType.FETCH, "cooked_salmon", 4, 8, 0.8f))
    private val LAND = mapOf(
        "desert" to listOf(Job(QuestType.HUNT, "husk", 6, 12, 0.8f)),
        "snow" to listOf(Job(QuestType.HUNT, "stray", 5, 10, 1.0f)),
        "swamp" to listOf(Job(QuestType.HUNT, "slime", 8, 14, 0.6f), Job(QuestType.HUNT, "witch", 1, 2, 5.0f)),
        "taiga" to listOf(Job(QuestType.HUNT, "pillager", 3, 6, 2.0f)),
        "savanna" to listOf(Job(QuestType.HUNT, "pillager", 3, 6, 2.0f)),
        "jungle" to listOf(Job(QuestType.HUNT, "spider", 6, 12, 0.8f)),
        "plains" to listOf(Job(QuestType.HUNT, "zombie", 8, 14, 0.6f)),
    )
    private val RIVAL = listOf(Job(QuestType.CLEAR, "zombie", 8, 14, 0.7f), Job(QuestType.HUNT, "skeleton", 6, 12, 0.9f),
        Job(QuestType.HUNT, "creeper", 4, 8, 1.2f))

    fun note(level: ServerLevel, villager: Villager, anchor: BlockPos, poster: String, tier: Int): QuestNote? {
        val random = level.random
        if (random.nextFloat() >= CHANCE) return null
        val board = level.getBlockEntity(anchor) as? QuestBoardBlockEntity ?: return null
        val today = VillageBoards.day(level)
        val village = VillageNames.of(anchor)
        val rank = ((tier - 1) * 2 + random.nextInt(3) - 1).coerceIn(0, 5)

        val rival = Holdings.get(level).at(anchor)?.let { RivalGuild.byId(it.guild) }
        val (jobs, key, args, bonus) = when {
            today - board.lastSiegeLost in 0..3 -> Quad(REBUILD, "rebuild", listOf(village), 1.6f)
            today - board.lastSiegeWon in 0..2 -> Quad(FEAST, "feast", listOf(village), 1.3f)
            rival != null && random.nextBoolean() -> Quad(RIVAL, "rival", listOf(village, rival.displayName), 1.4f)
            random.nextInt(4) == 0 -> return QuestGenerator.generateLair(random, rank, poster).copy(story = story("den", random, listOf(village)))
            else -> {
                val land = villager.villagerData.type().unwrapKey().orElse(null)?.identifier()?.path ?: "plains"
                Quad(LAND[land] ?: LAND.getValue("plains"), "land.${if (land in LAND) land else "plains"}", listOf(village), 1.2f)
            }
        }
        val job = jobs[random.nextInt(jobs.size)]
        val countScale = 1.0f + 0.15f * rank
        val base = job.min + random.nextInt(job.max - job.min + 1)
        val count = if (job.type.isSingle) 1 else (base * countScale).toInt().coerceAtLeast(1)
        val reward = 3 + (count * job.rewardPer * (1.0f + 0.35f * rank) * bonus).toInt()
        val note = QuestNote(job.type, poster, Identifier.withDefaultNamespace(job.target), count, reward, 3, story(key, random, args), rank)
        // At a capital's own board, its market's goods pay no more than half what the market asks for them
        return GoodsPrices.fit(note, MarketFetch.at(level, anchor)?.let { MarketFetch.ask(it, note.target) }?.price)
    }

    /** "key|arg|arg": QuestNote.storyLine fills the arguments in. */
    private fun story(key: String, random: net.minecraft.util.RandomSource, args: List<String>): String =
        (listOf("situation.guildmark.$key.${random.nextInt(STORIES)}") + args).joinToString("|")

    private const val STORIES = 3

    private data class Quad(val jobs: List<Job>, val key: String, val args: List<String>, val bonus: Float)
}
