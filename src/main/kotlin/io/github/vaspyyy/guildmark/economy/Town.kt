package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import kotlin.math.pow

/**
 * What a capital has to work with: its people by trade (as many as are living; [planned] is how many the city was
 * built for), and its barns, coops, windmills, fruit trees and boats. From these come how much of each good it uses
 * in a day, how much it holds by choice, how much it can make, and how much it has to bring in by caravan.
 */
class Town(
    /** Everyone who eats, children too. */
    val people: Int,
    val children: Int,
    val farmers: Int,
    val fishers: Int,
    val bakers: Int,
    val smiths: Int,
    val fletchers: Int,
    val innkeepers: Int,
    val guards: Int,
    val barns: Int,
    val coops: Int,
    val mills: Int,
    val trees: Int,
    /** The same counts for the town as it was built, before anyone died. */
    val planned: Town? = null,
) {
    val adults: Int get() = (people - children).coerceAtLeast(0)

    /** Everyone else with a trade, whose tools wear out too. */
    val workers: Int get() = (adults - farmers - fishers - guards).coerceAtLeast(0)

    /** The carts going to and fro between the farms, the mill and the market: one for every fifty mouths to feed. */
    val carters: Int get() = maxOf(1, Math.round(people / 50.0).toInt())

    /** Of an evening, how many go to the tavern. */
    val drinkers: Double get() = if (innkeepers > 0) adults * Economy.TAVERN_SHARE else 0.0

    private val use = DoubleArray(Good.entries.size).also { use ->
        for (g in Good.FOODS) use[g.ordinal] += people * Economy.MEALS * g.share / g.food
        use[Good.WOOL.ordinal] += people * Economy.WOOL
        use[Good.STRING.ordinal] += people * Economy.STRING
        use[Good.LEATHER.ordinal] += people * Economy.LEATHER
        use[Good.CANDLE.ordinal] += people * Economy.CANDLES
        use[Good.BOTTLE.ordinal] += people * Economy.BOTTLES
        use[Good.COAL.ordinal] += people * Economy.HEATING
        use[Good.ARROW.ordinal] += guards * Economy.ARROWS
        val wear = (farmers + fishers) * Economy.FIELD_WEAR + workers * Economy.WEAR
        use[Good.TOOLS.ordinal] += wear
        // What the town's makers work with
        use[Good.IRON.ordinal] += wear * Economy.FORGE_IRON
        use[Good.COAL.ordinal] += wear * Economy.FORGE_COAL
        val arrows = minOf(use[Good.ARROW.ordinal], fletchers * Economy.FLETCH * Economy.FORGE_HOURS)
        use[Good.FLINT.ordinal] += arrows * Economy.FLETCH_FLINT
        use[Good.FEATHER.ordinal] += arrows * Economy.FLETCH_FEATHER
        use[Good.ALE.ordinal] += drinkers * Economy.ALE
        use[Good.FLOUR.ordinal] += use[Good.BREAD.ordinal] * Economy.BAKE_FLOUR
        use[Good.WHEAT.ordinal] += use[Good.FLOUR.ordinal] + use[Good.ALE.ordinal] * Economy.BREW_WHEAT
    }

    private val made = DoubleArray(Good.entries.size).also { made ->
        for ((g, rate) in Economy.FIELD) made[g.ordinal] += farmers * rate * Economy.FIELD_HOURS
        val tended = if (farmers > 0) 1.0 else Economy.UNTENDED
        for ((g, rate) in Economy.BARN) made[g.ordinal] += barns * rate * tended
        for ((g, rate) in Economy.COOP) made[g.ordinal] += coops * rate * tended
        made[Good.APPLE.ordinal] += trees * Economy.TREE
        for ((g, share) in Economy.CATCH) made[g.ordinal] += fishers * Economy.FISH * share * Economy.FISH_HOURS
        made[Good.BREAD.ordinal] += bakers * Economy.BAKE * Economy.BAKE_HOURS
        made[Good.TOOLS.ordinal] += smiths * Economy.FORGE * Economy.FORGE_HOURS
        made[Good.ARROW.ordinal] += fletchers * Economy.FLETCH * Economy.FORGE_HOURS
        made[Good.ALE.ordinal] += innkeepers * Economy.BREW * Economy.BREW_HOURS
    }

    /** How much of [good] the town gets through in an ordinary day. */
    fun use(good: Good): Double = use[good.ordinal]

    /** How much of [good] its people can make in an ordinary day, at the year's usual pace. */
    fun made(good: Good): Double = made[good.ordinal]

    /** How much it likes to have in store: [Good.keep] days of its use, and a little of anything at all. */
    fun target(good: Good): Double = maxOf(use(good) * good.keep, Economy.LEAST)

    /** How much of what it keeps of [good] goes off in a day. */
    fun spoils(good: Good): Double = if (good.halfLife > 0.0) target(good) * (1.0 - 0.5.pow(1.0 / good.halfLife)) else 0.0

    /** Of what it gets through of [good] in a day, used and gone off, the share it can't make and has brought in as a rule. */
    fun shortfall(good: Good): Double {
        val need = use(good) + spoils(good)
        if (use(good) <= 0.0) return 0.0
        return (1.0 - made(good) / need).coerceIn(0.0, 1.0)
    }

    /** Its counts by name, as saved. */
    fun counts(): Map<String, Int> = linkedMapOf(
        "people" to people, "children" to children, "farmers" to farmers, "fishers" to fishers, "bakers" to bakers,
        "smiths" to smiths, "fletchers" to fletchers, "innkeepers" to innkeepers, "guards" to guards, "barns" to barns,
        "coops" to coops, "mills" to mills, "trees" to trees,
    )

    override fun toString(): String = counts().entries.joinToString(", ") { "${it.key} ${it.value}" }

    companion object {
        private fun of(c: Map<String, Int>, planned: Town?): Town {
            fun n(key: String) = c[key] ?: 0
            return Town(
                n("people"), n("children"), n("farmers"), n("fishers"), n("bakers"), n("smiths"), n("fletchers"),
                n("innkeepers"), n("guards"), n("barns"), n("coops"), n("mills"), n("trees"), planned,
            )
        }

        private val COUNTS = Codec.unboundedMap(Codec.STRING, Codec.INT)

        val CODEC: Codec<Town> = RecordCodecBuilder.create { i ->
            i.group(
                COUNTS.fieldOf("living").forGetter { it.counts() },
                COUNTS.optionalFieldOf("planned").forGetter { Optional.ofNullable(it.planned?.counts()) },
            ).apply(i) { living, planned -> of(living, planned.map { of(it, null) }.orElse(null)) }
        }
    }
}
