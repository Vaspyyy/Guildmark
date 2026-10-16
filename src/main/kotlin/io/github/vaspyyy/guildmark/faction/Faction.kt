package io.github.vaspyyy.guildmark.faction

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.progression.Bonus
import io.github.vaspyyy.guildmark.story.StoryCharacter
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.netty.buffer.ByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * The three factions seated in every grand capital: a mage college, a knightly order and a thieves' ring.
 * Each has a leader (a [StoryCharacter] whose chapters are its questline), members who keep a working day
 * in its seat ([role]), five ranks earned by finishing chapters, and a perk for every rank held. Their words
 * live in the language file under faction.guildmark.<id>.* and character.guildmark.<leader>.*.
 */
enum class Faction(val id: String, val role: TownRole, val color: Int) {
    /** The mage college: reads the sky from the library on the plaza and keeps the old sorceries. */
    STARWATCH("starwatch", TownRole.MAGE, 0x9AB4FF),
    /** The knightly order: keeps the Wardfires lit and the dead off the roads, from the barracks and its yard. */
    HEARTHSWORN("hearthsworn", TownRole.KNIGHT, 0xE58A4A),
    /** The thieves' ring: polite, careful, and meeting in the attic of the plaza's tavern. */
    QUIET_HAND("quiet_hand", TownRole.THIEF, 0x8FBF7A);

    /** Who leads it and gives its questline. */
    val leader: StoryCharacter get() = when (this) {
        STARWATCH -> StoryCharacter.IDRIS
        HEARTHSWORN -> StoryCharacter.CORRA
        QUIET_HAND -> StoryCharacter.ODILE
    }

    /**
     * The faction this one robs, and is robbed by, in its fourth chapter ([RIVAL_CHAPTER]): finishing that
     * chapter for either shuts the player out of the other for good.
     */
    val rival: Faction? get() = when (this) {
        HEARTHSWORN -> QUIET_HAND
        QUIET_HAND -> HEARTHSWORN
        STARWATCH -> null
    }

    val title: Component get() = Component.translatable("faction.guildmark.$id").withColor(color)

    /** The name of [rank] (1 to [RANKS]). */
    fun rankTitle(rank: Int): Component = Component.translatable("faction.guildmark.$id.rank.${(rank - 1).coerceIn(0, RANKS - 1)}")

    /** What each rank held adds to the build. */
    val perks: List<Pair<Bonus, Double>> get() = when (this) {
        STARWATCH -> listOf(Bonus.SORCERY_DAMAGE to 0.03, Bonus.FOCUS_REGEN to 0.04)
        HEARTHSWORN -> listOf(Bonus.UNDEAD_DAMAGE to 0.03, Bonus.BLOCK_COST to -0.03)
        QUIET_HAND -> listOf(Bonus.STEALTH to 0.04, Bonus.BACKSTAB_DAMAGE to 0.04)
    }

    companion object {
        const val RANKS = 5
        /** The chapter (counting from 0) that robs the rival faction. */
        const val RIVAL_CHAPTER = 3

        fun byId(id: String): Faction? = entries.firstOrNull { it.id == id }

        /** The faction [character] leads, if any. */
        fun ledBy(character: StoryCharacter): Faction? = entries.firstOrNull { it.leader == character }

        /** The faction whose members have [role]. */
        fun of(role: TownRole): Faction? = entries.firstOrNull { it.role == role }
    }
}

/**
 * Where a player stands with each faction: how many of its chapters they've finished (their rank; the
 * first, the initiation, is what joins them) and which factions have shut them out, expelled if they
 * were in, barred from joining if not. Kept through death and synced to its player, whose build it adds
 * perks to on both sides.
 */
data class FactionState(val chapters: Map<String, Int> = mapOf(), val shut: Set<String> = setOf()) {
    /** Chapters of [faction]'s questline finished. */
    fun chapters(faction: Faction): Int = chapters[faction.id] ?: 0

    fun isShut(faction: Faction): Boolean = faction.id in shut

    /** A member in good standing: joined, and not thrown out. */
    fun member(faction: Faction): Boolean = chapters(faction) > 0 && !isShut(faction)

    /** The rank held (0 for none), which counts for perks only while a member. */
    fun rank(faction: Faction): Int = if (isShut(faction)) 0 else chapters(faction).coerceAtMost(Faction.RANKS)

    fun withChapters(faction: Faction, count: Int): FactionState = copy(chapters = chapters + (faction.id to count))
    fun shutOut(faction: Faction): FactionState = copy(shut = shut + faction.id)

    companion object {
        val CODEC: Codec<FactionState> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("chapters", mapOf()).forGetter(FactionState::chapters),
                Codec.STRING.listOf().optionalFieldOf("shut", listOf()).forGetter { it.shut.toList() },
            ).apply(i) { chapters, shut -> FactionState(chapters, shut.toSet()) }
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, FactionState> = StreamCodec.composite(
            ByteBufCodecs.map({ HashMap<String, Int>() }, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT, 8), { HashMap(it.chapters) },
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), { it.shut.toList() },
        ) { chapters, shut -> FactionState(chapters, shut.toSet()) }
    }
}
