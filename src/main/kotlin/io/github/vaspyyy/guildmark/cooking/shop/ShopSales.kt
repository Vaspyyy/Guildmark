package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Freshness
import io.github.vaspyyy.guildmark.cooking.market.Doors
import io.github.vaspyyy.guildmark.cooking.market.Index
import io.github.vaspyyy.guildmark.cooking.market.Pricing
import io.github.vaspyyy.guildmark.cooking.market.Rec
import io.github.vaspyyy.guildmark.cooking.market.Requests
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.season.Season
import net.minecraft.server.level.ServerLevel

/**
 * What a portion fetches a cookshop on a town's [day] (living-world/cook/design.md 5.1, 6.4): the value of the dish (the kind's
 * factor, the tier, the capital's index, the season's and the palate's factors, how it has kept) less the shop's 10% cut. The
 * selling hour and the slate read the same prices, so the slate says what the sale pays.
 */
internal class Prices(private val index: Double, private val season: Season?, private val style: CityStyle, private val day: Long, private val spoils: Boolean) {
    /** How a dish of [kind] with [data] has kept on this day (always fresh while dishes do not spoil). */
    fun stage(kind: DishKind, data: DishData): Stage = if (spoils) Freshness.stageAt(day, kind, data) else Stage.FRESH

    /** What a portion of [kind] with [data] brings the shop: nothing for one that came out Spoilt or has gone off. */
    fun pay(kind: DishKind, data: DishData): Double {
        if (data.tier == Tier.SPOILT) return 0.0
        val s = season?.let { Tastes.season(kind, it) } ?: 1.0
        return Pricing.value(kind, data.tier, stage(kind, data), index, s, Tastes.palate(style, kind)) * Doors.SHOP_PAY
    }

    companion object {
        /**
         * The prices of [capital]'s shops on [day]: the index of its market (the week's meal price, 1 when it has no market), the
         * season's and the look of the town [shop] learned (the temperate one before it has).
         */
        fun of(level: ServerLevel, capital: Long, rec: Rec?, shop: ShopRec, day: Long): Prices {
            val entry = Markets.find(level, capital)
            val index = if (entry != null) Index.of(rec, entry) else 1.0
            val style = CityStyle.entries.getOrElse(shop.style) { CityStyle.TEMPERATE }
            return Prices(index, Requests.season(level, day), style, day, Cooking.spoilage())
        }
    }
}

/** What a shop has to sell of one kind, tier and freshness: [portions] of it at [pay] Marks each, the first of them in [slot]. */
internal class Offer(val kind: DishKind, val tier: Tier, val stage: Stage, var portions: Int, val pay: Double, val slot: Int)

/** [shop]'s larder as an offer: what it sells at [prices], dearest first (ties by slot); nothing for Gruel, Scorched mess, Spoilt dishes and dishes gone off. */
internal fun offers(shop: ShopRec, prices: Prices): List<Offer> {
    val out = ArrayList<Offer>()
    for ((slot, stack) in shop.stock.withIndex()) {
        if (stack.isEmpty) continue
        val kind = Dishes.kindOf(stack) ?: continue
        val data = Dishes.dataOf(stack) ?: continue
        val pay = prices.pay(kind, data)
        if (pay <= 1.0e-9) continue
        val stage = prices.stage(kind, data)
        val same = out.firstOrNull { it.kind == kind && it.tier == data.tier && it.stage == stage }
        if (same != null) same.portions += stack.count else out.add(Offer(kind, data.tier, stage, stack.count, pay, slot))
    }
    out.sortWith(compareByDescending<Offer> { it.pay }.thenBy { it.slot })
    return out
}
