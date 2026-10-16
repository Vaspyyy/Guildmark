package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.story.StoryCharacter
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** How a companion fights (see [CompanionFighting]). */
enum class Style {
    /** Bram: greatsword combos at the front. */
    BLADE,
    /** Ysolde: hangs back, heals and wards with her censer, swings it at anything that comes close. */
    PRAYER,
    /** Kestrel: shoots from range, the dagger up close. */
    BOW,
    /** Lucan: bolts and lances from range, blinks away from anyone who closes in. */
    SORCERY,
}

/**
 * Something the player did that a companion beside them has an opinion on (see [CompanionKind.reaction]). [group]
 * is the bark it draws ("approve.fight", "disapprove.theft" and so on); [daily] how many times a day it can move
 * their loyalty at all, so nothing can be farmed.
 */
enum class Deed(val group: String, val daily: Int) {
    CONTRACT("help", 4),
    KINDLE("help", 3),
    BOSS("fight", 3),
    DRAGON("fight", 2),
    LAIR("fight", 2),
    THEFT("theft", 3),
    HEIST("theft", 2),
    MURDER("murder", 99),
    SORCERY("magic", 2),
    PRAYER("magic", 2),
    NEW_LAND("land", 3),
    REST("rest", 1),
    JOIN_STARWATCH("faction.starwatch", 1),
    JOIN_HEARTHSWORN("faction.hearthsworn", 1),
    JOIN_QUIET_HAND("faction.quiet_hand", 1),
    STARWATCH_DONE("starwatch_done", 1);

    /** Heard of rather than seen: it moves a companion's loyalty wherever they are. */
    val news: Boolean get() = group.startsWith("faction.") || this == STARWATCH_DONE

    companion object {
        fun join(faction: Faction): Deed = when (faction) {
            Faction.STARWATCH -> JOIN_STARWATCH
            Faction.HEARTHSWORN -> JOIN_HEARTHSWORN
            Faction.QUIET_HAND -> JOIN_QUIET_HAND
        }
    }
}

/**
 * The four who can travel with the player. Each is also a [StoryCharacter] (their words, voice and three-chapter
 * loyalty questline live under character.guildmark.<id>.*) and wears a look of the townsfolk model (role
 * "companion", look [ordinal]). What they like and don't is in [reaction]; what they'll take as a gift in [likes].
 */
enum class CompanionKind(
    val id: String,
    val character: StoryCharacter,
    val style: Style,
    val health: Double,
    val armor: Double,
    /** Before what they hold: Bram's greatsword adds 8 and Kestrel's dagger 3; her arrows start from this alone. */
    val damage: Double,
    val speed: Double,
    /** Guild level they turn up at a Wardfire from, if nothing else of theirs brings them sooner (see Meetings). */
    val meetLevel: Int,
) {
    BRAM("bram", StoryCharacter.BRAM, Style.BLADE, 60.0, 10.0, 1.0, 0.3, 3),
    YSOLDE("ysolde", StoryCharacter.YSOLDE, Style.PRAYER, 40.0, 4.0, 3.0, 0.3, 8),
    KESTREL("kestrel", StoryCharacter.KESTREL, Style.BOW, 44.0, 5.0, 2.0, 0.32, 5),
    LUCAN("lucan", StoryCharacter.LUCAN, Style.SORCERY, 36.0, 3.0, 5.0, 0.3, 10);

    val title: Component get() = character.title
    fun line(key: String): Component = character.line(key)

    /** Loyalty each deed is worth to them; 0 when they don't care. */
    fun reaction(deed: Deed): Int = when (this) {
        BRAM -> when (deed) {
            Deed.CONTRACT -> 1
            Deed.BOSS -> 3
            Deed.DRAGON -> 4
            Deed.LAIR -> 1
            Deed.THEFT, Deed.HEIST -> -2
            Deed.MURDER -> -8
            Deed.REST -> 1
            Deed.JOIN_HEARTHSWORN -> 3
            Deed.JOIN_QUIET_HAND -> -4
            else -> 0
        }
        YSOLDE -> when (deed) {
            Deed.CONTRACT, Deed.KINDLE -> 2
            Deed.BOSS -> 1
            Deed.DRAGON -> 2
            Deed.PRAYER -> 1
            Deed.THEFT, Deed.HEIST -> -3
            Deed.MURDER -> -10
            Deed.REST -> 1
            Deed.JOIN_HEARTHSWORN -> 2
            Deed.JOIN_QUIET_HAND -> -5
            else -> 0
        }
        KESTREL -> when (deed) {
            Deed.KINDLE -> 1
            Deed.BOSS -> 3
            Deed.DRAGON -> 6
            Deed.LAIR -> 2
            Deed.THEFT -> 1
            Deed.HEIST -> 2
            Deed.MURDER -> -5
            Deed.NEW_LAND -> 2
            Deed.REST -> 1
            Deed.JOIN_HEARTHSWORN -> -3
            Deed.JOIN_QUIET_HAND -> 3
            else -> 0
        }
        LUCAN -> when (deed) {
            Deed.BOSS -> 2
            Deed.DRAGON -> 3
            Deed.LAIR -> 2
            Deed.SORCERY -> 1
            Deed.NEW_LAND -> 1
            Deed.THEFT -> -1
            Deed.MURDER -> -6
            Deed.REST -> 1
            Deed.JOIN_STARWATCH -> -3
            Deed.JOIN_QUIET_HAND -> -1
            Deed.STARWATCH_DONE -> 8
            else -> 0
        }
    }

    /** Whether they'd be glad of [stack] as a gift. */
    fun likes(stack: ItemStack): Boolean = !stack.isEmpty && stack.item in gifts

    private val gifts: Set<Item> by lazy {
        when (this) {
            BRAM -> setOf(Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.HONEY_BOTTLE)
            YSOLDE -> setOf(
                Items.BREAD, Items.GLOW_BERRIES, Items.CANDLE, Items.POPPY, Items.DANDELION, Items.CORNFLOWER, Items.BLUE_ORCHID,
                Items.ALLIUM, Items.AZURE_BLUET, Items.OXEYE_DAISY, Items.LILY_OF_THE_VALLEY, Items.RED_TULIP, Items.ORANGE_TULIP,
                Items.WHITE_TULIP, Items.PINK_TULIP,
            )
            KESTREL -> setOf(Items.ARROW, Items.SPECTRAL_ARROW, Items.RABBIT_STEW, Items.COOKED_RABBIT, Items.COOKED_CHICKEN, Items.SWEET_BERRIES)
            LUCAN -> setOf(Items.BOOK, Items.WRITABLE_BOOK, Items.ENCHANTED_BOOK, Items.AMETHYST_SHARD, Items.LAPIS_LAZULI, Items.GLOW_INK_SAC, Items.PAPER)
        }
    }

    companion object {
        /** Loyalty runs between these. */
        const val LEAST = -50
        const val MOST = 100
        /** Where it starts when they first join, and where it comes back to when they're talked round. */
        const val FIRST = 10
        /** At or below this they leave. */
        const val LEAVE_AT = -25
        /** Their chapters open at these. */
        val CHAPTERS = intArrayOf(25, 50, 75)

        fun byId(id: String): CompanionKind? = entries.firstOrNull { it.id == id }
        fun of(character: StoryCharacter): CompanionKind? = entries.firstOrNull { it.character == character }

        /** How they feel, as the answer to "How do we stand?" picks it (character.guildmark.<id>.standing.<n>). */
        fun band(loyalty: Int, sworn: Boolean): Int = when {
            sworn -> 5
            loyalty < 0 -> 0
            loyalty < CHAPTERS[0] -> 1
            loyalty < CHAPTERS[1] -> 2
            loyalty < CHAPTERS[2] -> 3
            else -> 4
        }
    }
}
