package io.github.vaspyyy.guildmark.cooking

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import net.minecraft.network.codec.ByteBufCodecs
import java.util.Optional
import java.util.UUID

/*
 * The data every part of Hearthcraft reads (living-world/cook/design.md 12.3, Appendix C): the enums, the table of the
 * twelve kinds, the dish a stack carries and the verdict of the ladle. Real from the first commit, because every part
 * reads them. The names are contracts: a save, a packet or a data file carries them, so none is renamed or reordered
 * and a new one is appended (Appendix C.2).
 */

/** What an ingredient brings to a pot (2.2): its share of each of the seven roles. */
enum class Role { BODY, MEAT, SEA, GREEN, SWEET, RICH, SAVOUR }

/** The four kinds of fire to cook at (1.1). */
enum class Station { STEWPOT, HOB, OVEN, SPIT }

/** The four families of meal, each a mob effect (4.1). */
enum class Family { WARMTH, HEARTY, STEADY, PROVISIONED }

/** The four doors a dish is sold at (5.2): the inn's and the bakery's back doors, the cook's own shop, and the alms table. */
enum class Door { INN, BAKERY, SHOP, ALMS }

/** How a dish has kept (3.2): fresh, then stale (sells at 60%, gives 0.6 of its effect), then gone off (fed to the hungry, no effect). */
enum class Stage { FRESH, STALE, GONE }

/**
 * How well a batch came out (2.5, 4.1), kept as 0 to 4 in the dish. [q] is the least quality that earns the tier, [value]
 * what a portion fetches as a multiple of a Sound one, [xp] the XP factor and [strength] the meal effect's.
 */
enum class Tier(val q: Double, val value: Double, val xp: Double, val strength: Double) {
    SPOILT(0.0, 0.0, 0.1, 0.0),
    PLAIN(0.30, 0.80, 0.5, 0.7),
    SOUND(0.52, 1.00, 1.0, 1.0),
    FINE(0.74, 1.30, 1.4, 1.25),
    HALLMARKED(0.90, 1.65, 1.8, 1.6),
    ;

    companion object {
        /** The tier saved as [index] (0 to 4); anything else reads as Plain, never as an error. */
        fun of(index: Int): Tier = entries.getOrElse(index) { PLAIN }
    }
}

/**
 * The twelve kinds of dish, the table of 2.1 and nowhere else: [id] (the item is `dish_<id>`), the [station] it is cooked
 * at, the COOKING [level] that unlocks it, the [portions] a batch makes, the cooking [time] in dish-seconds, the
 * [complexity] (0 to 4: what a batch asks of the cook, 2.5), the sale [factor] (a Sound portion as a multiple of a plain
 * meal's price, 5.1), the days it is [keep] fresh, stale and gone off (3.2), its meal [family] (null: sweet, none), whether
 * it is the [strong] kind of its family (4.1), the [doors] it is sold at and the standard [bill], food name to count (the
 * names are those of the role data, Appendix B). Ids are contracts.
 */
enum class DishKind(
    val id: String,
    val station: Station,
    val level: Int,
    val portions: Int,
    val time: Int,
    val complexity: Int,
    val factor: Double,
    val keep: IntArray,
    val family: Family?,
    val strong: Boolean,
    val doors: Set<Door>,
    val bill: Map<String, Int>,
) {
    POTTAGE("pottage", Station.STEWPOT, 1, 8, 80, 0, 2.6, intArrayOf(2, 4, 6), Family.WARMTH, false, setOf(Door.INN), linkedMapOf("potato" to 3, "carrot" to 2, "wheat" to 2, "beetroot" to 1, "herb" to 1)),
    STEW("stew", Station.STEWPOT, 1, 8, 120, 1, 4.6, intArrayOf(2, 4, 6), Family.HEARTY, false, setOf(Door.INN), linkedMapOf("beef" to 3, "carrot" to 2, "potato" to 1, "wheat" to 1, "mushroom" to 1)),
    BROTH("broth", Station.STEWPOT, 3, 8, 140, 1, 3.0, intArrayOf(2, 4, 6), Family.WARMTH, true, setOf(Door.INN), linkedMapOf("mutton" to 1, "carrot" to 1, "bone" to 2, "herb" to 1)),
    ROOTS("roots", Station.HOB, 4, 4, 60, 1, 4.0, intArrayOf(2, 4, 6), null, false, setOf(Door.INN), linkedMapOf("carrot" to 2, "potato" to 1, "honey" to 1)),
    CHOWDER("chowder", Station.STEWPOT, 5, 8, 100, 1, 4.4, intArrayOf(1, 3, 5), Family.WARMTH, false, setOf(Door.INN), linkedMapOf("cod" to 3, "potato" to 3, "milk" to 1, "herb" to 1)),
    CAKES("cakes", Station.HOB, 8, 4, 50, 2, 5.0, intArrayOf(2, 4, 6), null, false, setOf(Door.INN, Door.BAKERY), linkedMapOf("wheat" to 2, "honey" to 1, "egg" to 1)),
    LOAF("loaf", Station.OVEN, 10, 8, 130, 2, 4.0, intArrayOf(30, 60, 90), Family.PROVISIONED, false, setOf(Door.BAKERY), linkedMapOf("wheat" to 4, "honey" to 1, "apple" to 2)),
    PIE("pie", Station.OVEN, 12, 6, 150, 3, 5.6, intArrayOf(4, 8, 12), Family.STEADY, false, setOf(Door.INN, Door.BAKERY), linkedMapOf("pork" to 2, "potato" to 2, "wheat" to 3, "egg" to 1)),
    TART("tart", Station.OVEN, 15, 6, 140, 3, 5.6, intArrayOf(3, 6, 9), null, false, setOf(Door.INN, Door.BAKERY), linkedMapOf("apple" to 4, "honey" to 1, "wheat" to 3)),
    FISH("fish", Station.OVEN, 18, 4, 90, 3, 6.0, intArrayOf(1, 2, 4), Family.HEARTY, false, setOf(Door.INN), linkedMapOf("salmon" to 3, "herb" to 1)),
    ROAST("roast", Station.SPIT, 20, 4, 160, 4, 8.0, intArrayOf(1, 3, 5), Family.STEADY, true, setOf(Door.INN), linkedMapOf("beef" to 4, "herb" to 1)),
    STRIPS("strips", Station.OVEN, 22, 8, 170, 3, 5.0, intArrayOf(30, 60, 90), Family.PROVISIONED, true, setOf(Door.BAKERY), linkedMapOf("mutton" to 4, "herb" to 1)),
    ;

    /** The item of this kind, `guildmark:dish_<id>`. */
    val itemId: String get() = "dish_$id"

    companion object {
        private val BY_ID: Map<String, DishKind> = entries.associateBy { it.id }

        /** The kind with [id], or null. */
        fun byId(id: String): DishKind? = BY_ID[id]
    }
}

/**
 * What a dish stack carries, the component `guildmark:dish` (3.1): how well it came out ([tier]), the town's day it was
 * cooked ([day], as [Cooking.today] reads it), who cooked it ([cook], their name, and [cookId]) and the cook's COOK_KEEP
 * in percent when it was cooked ([keep]). Equal components stack, so a batch is one stack. A stack without it reads as a
 * Plain, fresh, anonymous dish (every field optional here, and a missing day is [UNDATED]), never as an error.
 */
data class DishData(val tier: Tier, val day: Long, val cook: String, val cookId: UUID?, val keep: Int) {
    companion object {
        /** The day of a dish that has none: far in the future, so it is always fresh (age counts from 0, 9.3). */
        const val UNDATED: Long = Long.MAX_VALUE / 4

        /** The dish of nobody's cooking: Plain, fresh, anonymous. */
        val PLAIN = DishData(Tier.PLAIN, UNDATED, "", null, 0)

        val CODEC: Codec<DishData> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("tier", Tier.PLAIN.ordinal).forGetter { it.tier.ordinal },
                Codec.LONG.optionalFieldOf("day", UNDATED).forGetter { it.day },
                Codec.STRING.optionalFieldOf("cook", "").forGetter { it.cook },
                UUIDUtil.CODEC.optionalFieldOf("cook_id").forGetter { Optional.ofNullable(it.cookId) },
                Codec.INT.optionalFieldOf("keep", 0).forGetter { it.keep },
            ).apply(i) { tier, day, cook, id, keep -> DishData(Tier.of(tier), day, cook, id.orElse(null), keep.coerceIn(0, 100)) }
        }

        /** The same on the wire (the item's tooltip reads it on the client). */
        val STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC)
    }
}

/**
 * What the ladle says of a mix, and what the keeper says of a dish (2.6): which [role] is furthest from the world's
 * ideal, whether it is [over] (too much; otherwise too little), by how many [items] of the standard bill, and how
 * [close] the whole is (0 far to 3 right on).
 */
class Verdict(val role: Role, val over: Boolean, val items: Double, val close: Int)

/** What a cook is doing, sent to the players who see it so their body plays the motion (1.8, Appendix C.2). */
enum class CookAction { ADD, FEED, STIR, TASTE, EASE, SKIM, SERVE, SERVE_PEEL, CHOP, FLIP, TURN, CARVE, SLICE, SHIVER, EAT_SPOON, EAT_BITE, DRINK }
