package io.github.vaspyyy.guildmark.cooking.shop

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Freshness
import io.github.vaspyyy.guildmark.cooking.market.Doors
import io.github.vaspyyy.guildmark.cooking.market.Index
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.market.NO_DAY
import io.github.vaspyyy.guildmark.cooking.market.Pricing
import io.github.vaspyyy.guildmark.cooking.market.Rec
import io.github.vaspyyy.guildmark.cooking.market.Requests
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.economy.Conditions
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.OwnStalls
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.season.Season
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

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

/**
 * The cookshops' selling hour (living-world/cook/design.md 6.4, 5.7), worked out from the ledger's record and the shops' own, so
 * it is the same whether the owner or any player is there and whether the shop's land is loaded: up to a third of the door's day cap
 * (2.5% of the town's meals) sold in the hour, the dearest portions first, no kind over 40% of the cap, each paying 0.90 of its value
 * into the shop's till after the keeper's wage, with the owner's standing a point a Mark. Two shops in one capital sell from the one
 * cap and the one hour's share, a portion each in turn, so neither is the first to take it all.
 */
internal object ShopSales {
    /** The keeper's weekly wage is paid a twenty-first at each of the week's 21 selling hours, out of what the shop took in the hour. */
    private const val SELLING_HOURS = 21.0

    /** The most portions kept waiting for the shoppers to fetch after a selling hour. */
    private const val WAITING_MOST = 12

    /** One shop's part in an hour: what it has sold so far. */
    private class Seller(val shop: ShopRec, val prices: Prices) {
        var portions = 0
        var takings = 0.0

        /**
         * Sells the shop's dearest portion whose kind has room under [kindCap] in [rec]'s count for the door, if it has one: it leaves the
         * stack, is counted in the ledger, the shop and its week, and waits for a shopper. False if there is nothing to sell.
         */
        fun sellOne(level: ServerLevel, rec: Rec, kindCap: Int, day: Long): Boolean {
            var best = -1
            var bestPay = 0.0
            for (slot in shop.stock.indices) {
                val stack = shop.stock[slot]
                if (stack.isEmpty) continue
                val kind = Dishes.kindOf(stack) ?: continue
                if (rec.sold[Door.SHOP.ordinal][kind.ordinal] >= kindCap) continue
                val pay = prices.pay(kind, Dishes.dataOf(stack) ?: continue)
                if (pay > bestPay + 1.0e-12) {
                    best = slot
                    bestPay = pay
                }
            }
            if (best < 0) return false
            val stack = shop.stock[best]
            val kind = Dishes.kindOf(stack) ?: return false
            val portion = stack.copyWithCount(1)
            stack.shrink(1)
            if (stack.isEmpty) shop.stock[best] = ItemStack.EMPTY
            rec.sold[Door.SHOP.ordinal][kind.ordinal]++
            shop.sold[kind.ordinal]++
            shop.week.sold(day, kind, 1)
            portions++
            takings += bestPay
            if (shop.waiting.isEmpty()) shop.waitingSince = level.gameTime
            if (shop.waiting.size < WAITING_MOST) shop.waiting.add(portion)
            return true
        }
    }

    /**
     * The portions the shops of [entry]'s market sold in market hour [hour], which the ledger adds to what the town ate. The ledger's
     * record is brought to the hour's day first, as the ledger's own step does just after.
     */
    fun hour(level: ServerLevel, entry: Markets.Entry, hour: Long): Double {
        if (!Cooking.shops() || !Works.enabled()) return 0.0
        val kitchens = Kitchens.get(level)
        val capital = entry.market.capital
        val rec = kitchens.rec(capital) ?: return 0.0
        if (rec.shops.isEmpty()) return 0.0
        // The shops that stand: a cookshop of its owner's, built and not taken down
        val standing = ArrayList<ShopRec>()
        for (w in Works.records(level, capital)) {
            if (w.kind != WorksKind.COOKSHOP || w.state != WorksState.DONE) continue
            val shop = w.owner?.let { rec.shops[it] } ?: continue
            if (!shop.closed) standing.add(shop)
        }
        if (standing.isEmpty()) return 0.0
        standing.sortBy { it.owner }
        val at = Conditions(hour)
        val today = Cooking.today(level)
        if (rec.day != NO_DAY && rec.day > today && hour <= Markets.hourOf(level)) kitchens.roll(rec, today)
        if (rec.day == NO_DAY || at.day > rec.day) kitchens.roll(rec, at.day)
        val people = entry.town.people
        val kindCap = Doors.kindCap(Door.SHOP, people)
        var allowance = minOf(Doors.serving(Door.SHOP, people), Doors.cap(Door.SHOP, people) - rec.soldAt(Door.SHOP))
        val sellers = standing.map { shop ->
            shop.roll(at.day)
            Seller(shop, Prices.of(level, capital, rec, shop, at.day))
        }
        // A portion each in turn, the shop that begins turning with the hour, until the hour's share is sold or nothing more will sell
        val begin = Math.floorMod(hour / 4L, sellers.size.toLong()).toInt()
        var sold = 0
        while (allowance > 0) {
            var any = false
            for (i in sellers.indices) {
                if (allowance <= 0) break
                if (!sellers[(begin + i) % sellers.size].sellOne(level, rec, kindCap, at.day)) continue
                allowance--
                sold++
                any = true
            }
            if (!any) break
        }
        for (s in sellers) pay(level, rec, capital, s, at.day)
        kitchens.changed()
        return sold.toDouble()
    }

    /** What [s] took in the hour goes into its till, the keeper's wage first, and the owner's standing rises by it. */
    private fun pay(level: ServerLevel, rec: Rec, capital: Long, s: Seller, day: Long) {
        val shop = s.shop
        shop.wages += OwnStalls.KEEPER_WAGE / SELLING_HOURS
        val paid = minOf(shop.wages, s.takings)
        shop.wages -= paid
        val net = s.takings - paid
        val carried = Doors.carried(rec, Door.SHOP, shop.owner)
        val count = Doors.count(net, carried)
        if (net > 0.0 || carried > 0.0) rec.carry.getOrPut(shop.owner) { DoubleArray(Rec.DOORS) }[Door.SHOP.ordinal] = count.carry
        shop.till += count.marks
        shop.week.took(day, net)
        shop.lastAt = level.gameTime
        shop.lastPortions = s.portions
        shop.lastMarks = net
        if (s.portions > 0) {
            shop.touched = true
            if (offers(shop, s.prices).isEmpty()) shop.soldOutAt = level.gameTime
        }
        if (net > 0.0 && Suppliers.enabled()) Suppliers.gain(level, BlockPos.of(capital), shop.owner, net * Suppliers.SOLD)
    }
}
