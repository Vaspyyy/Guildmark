package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** One chapter of a character's story: the job they ask for, rated and paid like a board note. */
data class StoryStep(val type: QuestType, val target: String, val count: Int, val reward: Int, val rank: Int)

/**
 * The named characters of the world. Wren, Tobin, Ilsa and Pip each turn up in a village that knows the
 * adventurer, tell a story over three jobs, and hand over a piece of the world's lore when it ends.
 * Maren only appears in the main story. Their words live
 * in the language file under character.guildmark.<id>.*.
 */
enum class StoryCharacter(
    val id: String,
    /** Plain name, also used as the poster on their contracts. */
    val displayName: String,
    /** Adventurer rank index they wait for before showing up. */
    val minRank: Int,
    val color: Int,
    /** Lore fragments (see LoreBooks) handed over when the story ends. */
    val lore: List<Int>,
    val steps: List<StoryStep>,
) {
    WREN(
        "wren", "Wren Ashdown", 0, 0x2E7D9A, listOf(6),
        listOf(
            StoryStep(QuestType.FETCH, "paper", 12, 8, 0),
            StoryStep(QuestType.HUNT, "spider", 6, 12, 0),
            StoryStep(QuestType.LAIR, "zombie", 1, 25, 0),
        ),
    ),
    TOBIN(
        "tobin", "Tobin Greaves", 1, 0x8A4B1E, listOf(1, 4),
        listOf(
            StoryStep(QuestType.HUNT, "zombie", 12, 12, 1),
            StoryStep(QuestType.FETCH, "iron_ingot", 16, 16, 1),
            StoryStep(QuestType.LAIR, "zombie", 1, 40, 2),
        ),
    ),
    ILSA(
        "ilsa", "Sister Ilsa Venn", 2, 0x7A3E9D, listOf(2, 3),
        listOf(
            StoryStep(QuestType.HUNT, "skeleton", 10, 15, 2),
            StoryStep(QuestType.FETCH, "amethyst_shard", 8, 18, 2),
            StoryStep(QuestType.LAIR, "zombie", 1, 50, 3),
        ),
    ),
    PIP(
        "pip", "Pip Larkspur", 1, 0xC23A48, listOf(5),
        listOf(
            StoryStep(QuestType.FETCH, "string", 12, 10, 1),
            StoryStep(QuestType.HUNT, "creeper", 6, 16, 1),
            StoryStep(QuestType.LAIR, "zombie", 1, 40, 2),
        ),
    ),
    /** The Last Warden. Never settles in a village: she appears where the story needs her. */
    MAREN("maren", "Maren", Int.MAX_VALUE, 0x7FE8D0, listOf(), listOf()),
    ;

    /** Has a story of jobs to give, rather than only scenes in the main quest. */
    val givesJobs: Boolean get() = steps.isNotEmpty()

    /**
     * What the player can ask them about. The question is character.guildmark.<id>.topic.<name> and the
     * answer topic.<name>.0, .1 and so on.
     */
    val topics: List<String> get() = when (this) {
        WREN -> listOf("maps", "king")
        TOBIN -> listOf("wardens", "halvard")
        ILSA -> listOf("bell", "court")
        PIP -> listOf("songs", "fame")
        MAREN -> listOf("oath")
    }

    val title: Component get() = Component.literal(displayName).withColor(color)
    val epithet: Component get() = Component.translatable("character.guildmark.$id.epithet")
    fun line(key: String): Component = Component.translatable("character.guildmark.$id.$key")

    /** The contract for chapter [step], as handed over by the character. */
    fun note(step: Int): QuestNote {
        val s = steps[step]
        return QuestNote(
            s.type, displayName, Identifier.withDefaultNamespace(s.target), s.count, s.reward, DEADLINE_DAYS,
            "character.guildmark.$id.step$step.note", s.rank, chain = "$id:$step",
        )
    }

    companion object {
        const val DEADLINE_DAYS = 7

        fun byId(id: String): StoryCharacter? = entries.firstOrNull { it.id == id }

        /** The character and chapter a story contract belongs to, from its chain tag. */
        fun parseChain(chain: String): Pair<StoryCharacter, Int>? {
            val character = byId(chain.substringBefore(':')) ?: return null
            val step = chain.substringAfter(':').toIntOrNull() ?: return null
            return character to step
        }
    }
}
