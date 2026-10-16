package io.github.vaspyyy.guildmark.story

import io.github.vaspyyy.guildmark.companion.CompanionKind
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * One chapter of a character's story: the job they ask for, rated and paid like a board note. [target] is an
 * id, read as minecraft:<target> when it names no namespace.
 */
data class StoryStep(val type: QuestType, val target: String, val count: Int, val reward: Int, val rank: Int)

/**
 * The named characters of the world. Wren, Tobin, Ilsa and Pip each turn up in a village that knows the
 * adventurer, tell a story over three jobs, and hand over a piece of the world's lore when it ends.
 * Maren only appears in the main story. Idris, Corra and Odile lead the factions (see [Faction]): they
 * keep their seat in a capital, and their chapters are the factions' questlines. Bram, Ysolde, Kestrel and Lucan
 * are the companions (see [CompanionKind]), whose chapters are their loyalty questlines. Their words live in the
 * language file under character.guildmark.<id>.*.
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

    // The factions' leaders, who never settle in a village either: they keep their seat in a capital (see FactionSeats)

    /** Magister of the Starwatch. */
    IDRIS(
        "idris", "Idris Vael", Int.MAX_VALUE, 0x9AB4FF, listOf(),
        listOf(
            StoryStep(QuestType.TRIAL, "guildmark:ward_lights", 5, 10, 0),
            StoryStep(QuestType.FETCH, "lapis_lazuli", 24, 18, 1),
            StoryStep(QuestType.HUNT, "phantom", 6, 28, 2),
            StoryStep(QuestType.LAIR, "guildmark:witch_hollow", 1, 45, 3),
            StoryStep(QuestType.TRIAL, "guildmark:renegade", 1, 70, 4),
        ),
    ),
    /** Knight-Commander of the Hearthsworn. */
    CORRA(
        "corra", "Corra Wystan", Int.MAX_VALUE, 0xE58A4A, listOf(),
        listOf(
            StoryStep(QuestType.TRIAL, "guildmark:spar", 1, 10, 0),
            StoryStep(QuestType.HUNT, "guildmark:hollow_soldier", 10, 18, 1),
            StoryStep(QuestType.TRIAL, "guildmark:hold", 1, 28, 2),
            StoryStep(QuestType.TRIAL, "guildmark:heist_den", 1, 45, 3),
            StoryStep(QuestType.TRIAL, "guildmark:dusk_rider", 1, 70, 4),
        ),
    ),
    /** The Widow, who runs the Quiet Hand. */
    ODILE(
        "odile", "Odile Fenn", Int.MAX_VALUE, 0xB9A6D6, listOf(),
        listOf(
            StoryStep(QuestType.TRIAL, "guildmark:pickpocket", 3, 10, 0),
            StoryStep(QuestType.TRIAL, "guildmark:heist_manor", 1, 18, 1),
            StoryStep(QuestType.HUNT, "pillager", 8, 28, 2),
            StoryStep(QuestType.TRIAL, "guildmark:heist_reliquary", 1, 45, 3),
            StoryStep(QuestType.TRIAL, "guildmark:heist_vault", 1, 70, 4),
        ),
    ),

    // The companions, who travel with the player (see CompanionKind): their chapters are their loyalty questlines,
    // opened by trust rather than rank, so a chapter's rank only rates and pays it

    /** Bram Hale, the last of the Greyhart Company. */
    BRAM(
        "bram", "Bram Hale", Int.MAX_VALUE, 0xA8A39A, listOf(),
        listOf(
            StoryStep(QuestType.HUNT, "pillager", 8, 15, 1),
            StoryStep(QuestType.LAIR, "guildmark:turncoat_camp", 1, 30, 2),
            StoryStep(QuestType.HUNT, "guildmark:hill_troll", 1, 45, 3),
        ),
    ),
    /** Ysolde Marsh, a pilgrim of the Lamplighters. */
    YSOLDE(
        "ysolde", "Ysolde Marsh", Int.MAX_VALUE, 0xE8B866, listOf(),
        listOf(
            StoryStep(QuestType.HUNT, "guildmark:hollow_soldier", 8, 15, 1),
            StoryStep(QuestType.TRIAL, "guildmark:kindle", 3, 25, 2),
            StoryStep(QuestType.LAIR, "guildmark:wardens_watch", 1, 45, 3),
        ),
    ),
    /** Kestrel Vey, a ranger out of the Thornwood. */
    KESTREL(
        "kestrel", "Kestrel Vey", Int.MAX_VALUE, 0x6FA05A, listOf(),
        listOf(
            StoryStep(QuestType.LAIR, "guildmark:spider_den", 1, 15, 1),
            StoryStep(QuestType.TRIAL, "guildmark:dusk_rider", 1, 35, 3),
            StoryStep(QuestType.HUNT, "guildmark:dragon", 1, 60, 4),
        ),
    ),
    /** Lucan Dray, thrown out of the Starwatch. */
    LUCAN(
        "lucan", "Lucan Dray", Int.MAX_VALUE, 0x8FA8D8, listOf(),
        listOf(
            StoryStep(QuestType.HUNT, "enderman", 3, 15, 1),
            StoryStep(QuestType.LAIR, "guildmark:witch_hollow", 1, 30, 3),
            StoryStep(QuestType.LAIR, "guildmark:ashe_archive", 1, 50, 4),
        ),
    ),
    ;

    /** Has a story of jobs to give, rather than only scenes in the main quest. */
    val givesJobs: Boolean get() = steps.isNotEmpty()

    /** The faction they lead, if they lead one. */
    val faction: Faction? get() = Faction.ledBy(this)

    /** The companion they are, if they travel with the player. */
    val companion: CompanionKind? get() = CompanionKind.of(this)

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
        IDRIS -> listOf("starwatch", "omens")
        CORRA -> listOf("oath", "wardens")
        ODILE -> listOf("hand", "widow")
        BRAM -> listOf("greyharts", "coin")
        YSOLDE -> listOf("lamplighters", "shrine")
        KESTREL -> listOf("thornwood", "rider")
        LUCAN -> listOf("starwatch", "marrow")
    }

    val title: Component get() = Component.literal(displayName).withColor(color)
    val epithet: Component get() = Component.translatable("character.guildmark.$id.epithet")
    fun line(key: String): Component = Component.translatable("character.guildmark.$id.$key")

    /** The contract for chapter [step], as handed over by the character. */
    fun note(step: Int): QuestNote {
        val s = steps[step]
        return QuestNote(
            s.type, displayName, Identifier.parse(s.target), s.count, s.reward, DEADLINE_DAYS,
            "character.guildmark.$id.step$step.note", s.rank, chain = "$id:$step",
        )
    }

    companion object {
        const val DEADLINE_DAYS = 7

        fun byId(id: String): StoryCharacter? = entries.firstOrNull { it.id == id }

        /**
         * Who a contract from a character belongs to, from its chain tag: a chapter ("<id>:<step>") or other
         * work they gave ("<id>:<anything else>"), turned in to them alone.
         */
        fun ownerOf(chain: String): StoryCharacter? = if (':' in chain) byId(chain.substringBefore(':')) else null

        /** The character and chapter a story contract belongs to, from its chain tag. */
        fun parseChain(chain: String): Pair<StoryCharacter, Int>? {
            val character = byId(chain.substringBefore(':')) ?: return null
            val step = chain.substringAfter(':').toIntOrNull() ?: return null
            return character to step
        }
    }
}
