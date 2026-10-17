package io.github.vaspyyy.guildmark.quest

import net.minecraft.resources.Identifier
import kotlin.math.floor

/**
 * What the goods a FETCH contract asks for cost to buy, in Guild Marks, so that buying goods to hand in never pays. A
 * fetch never pays more than [MAX_SHARE] of what its goods cost, bonuses and all: even a full party, whose mates each
 * get a quarter more on top, gets back less than the goods cost. A note that would be left paying less than [MIN_PAY]
 * isn't posted. Goods nobody sells aren't listed here, and pay what their notes say.
 *
 * The prices are the Guildmaster's own, in Marks (data/guildmark/villager_trade/guildmaster), and the village traders' at
 * the cheapest a trade goes once a village thinks well of you, one emerald, with an emerald at the Guildmaster's 3 Marks.
 * Wandering traders, who only pass by now and then, aren't counted. Anything sold for Marks elsewhere belongs here too.
 */
object GoodsPrices {
    /** The most of what its goods cost that a fetch pays, bonuses and all. */
    const val MAX_SHARE = 0.5

    /** Below this, a fetch for goods that can be bought isn't worth posting. */
    const val MIN_PAY = 3

    /** What the Guildmaster asks for an emerald. */
    private const val EMERALD = 3.0

    private val WOOL = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )

    private val COSTS: Map<Identifier, Double> = HashMap<Identifier, Double>().apply {
        fun sold(item: String, marks: Double) {
            merge(Identifier.withDefaultNamespace(item), marks) { a, b -> minOf(a, b) }
        }
        fun of(item: String): Double = getValue(Identifier.withDefaultNamespace(item))
        // The Guildmaster
        sold("emerald", EMERALD)
        sold("torch", 1.0 / 16)
        sold("cooked_beef", 2.0 / 8)
        sold("arrow", 2.0 / 16)
        sold("ender_pearl", 5.0 / 2)
        sold("golden_carrot", 4.0 / 6)
        sold("experience_bottle", 6.0 / 8)
        sold("shield", 6.0)
        sold("diamond", 12.0)
        sold("name_tag", 8.0)
        sold("saddle", 16.0)
        sold("totem_of_undying", 40.0)
        sold("enchanted_golden_apple", 48.0)
        // The village traders: so many for an emerald
        fun traded(item: String, count: Int) = sold(item, EMERALD / count)
        traded("bread", 6)
        traded("apple", 4)
        traded("pumpkin_pie", 4)
        traded("cookie", 18)
        traded("cake", 1)
        traded("golden_carrot", 3)
        traded("cooked_chicken", 8)
        traded("cooked_porkchop", 5)
        traded("rabbit_stew", 1)
        traded("compass", 1)
        traded("clock", 1)
        traded("lantern", 1)
        traded("glass", 4)
        traded("bookshelf", 1)
        traded("lapis_lazuli", 1)
        traded("redstone", 2)
        traded("glowstone", 1)
        traded("ender_pearl", 1)
        traded("experience_bottle", 1)
        traded("shears", 1)
        traded("saddle", 1)
        traded("shield", 1)
        traded("brick", 10)
        traded("chiseled_stone_bricks", 4)
        traded("polished_andesite", 4)
        traded("polished_diorite", 4)
        traded("polished_granite", 4)
        traded("dripstone_block", 4)
        traded("quartz_block", 1)
        traded("quartz_pillar", 1)
        for (color in WOOL) traded("${color}_wool", 1)
        // Made from what's bought: a pane is 6 glass to 16, a bottle a glass, and a bookshelf breaks into 3 books
        sold("glass_pane", of("glass") * 6 / 16)
        sold("glass_bottle", of("glass"))
        sold("book", of("bookshelf") / 3)
    }

    /** What one of [item] costs to buy, or null if nobody sells it. */
    fun cost(item: Identifier): Double? = COSTS[item]

    /** The most a fetch of [count] [item] may pay, or null if nobody sells it and it pays what its note says. */
    fun cap(item: Identifier, count: Int): Int? = cost(item)?.let { floor(it * count * MAX_SHARE).toInt() }

    /** [note], paying no more than its goods' [cap] if it's a fetch for goods that can be bought. */
    fun capped(note: QuestNote): QuestNote {
        if (note.type != QuestType.FETCH) return note
        val cap = cap(note.target, note.count) ?: return note
        return if (note.reward <= cap) note else note.copy(reward = cap.coerceAtLeast(1))
    }

    /** [note] as [capped], or null if it's a fetch for goods that sell so cheap it could pay no more than [MIN_PAY]. */
    fun fit(note: QuestNote): QuestNote? {
        if (note.type != QuestType.FETCH) return note
        val cap = cap(note.target, note.count) ?: return note
        return if (cap < MIN_PAY) null else capped(note)
    }
}
