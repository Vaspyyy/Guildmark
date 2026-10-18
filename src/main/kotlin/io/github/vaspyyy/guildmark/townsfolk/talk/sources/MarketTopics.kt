package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Event
import io.github.vaspyyy.guildmark.economy.EventKind
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Maker
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Quote
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The market as the speaker deals in it (section 1): the goods they sell (a keeper's own) or buy for the house, from
 * the market's quotes and reasons this hour. No bread or no fish to be had, a staple sold out, the mill standing idle
 * or broken, the town going hungry, everything dear or much cheap, the end of a trouble or a stroke of luck, and,
 * while they're shopping, what it's going for. "Any news?" gets the troubles and the luck, not the prices.
 */
object MarketTopics : TopicSource {
    override val id = "market"

    private val OCCASIONS = Say.TALK + Occasion.NEWS + Occasion.BARK

    /** What everyone buys for the house, the staples first. */
    private val HOUSE = listOf(
        Good.BREAD, Good.POTATO, Good.CARROT, Good.COD, Good.SALMON, Good.APPLE, Good.BEEF, Good.PORK, Good.MUTTON,
        Good.CHICKEN, Good.BEETROOT, Good.CANDLE, Good.COAL,
    )

    /** The share of the town's eating bread is: the most any good is, so the staple that weighs most. */
    private const val STAPLE = 0.34

    /** How long after a trouble ends it's still good news (market hours). */
    private const val GLAD = 24L

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS || Say.child(ask.speaker)) return
        val entry = Say.market(ask) ?: return
        val m = entry.market
        val town = entry.town
        val shop = Shop.kept(ask.speaker)
        val goods = goodsOf(ask, shop)
        val quotes = goods.associateWith { Trading.quote(entry, it) }
        val talk = ask.occasion != Occasion.NEWS

        // No bread, no fish
        if (Good.BREAD in goods && m.stock(Good.BREAD) < 1.0 && town.bakers > 0) {
            val q = quotes.getValue(Good.BREAD)
            add(out, ask, "market.no_bread", "market.no_bread.${reason(q)}", 0.8, -1, why(q) ?: none(Good.BREAD))
        }
        val fish = listOf(Good.COD, Good.SALMON).filter { it in goods }
        if (fish.isNotEmpty() && town.fishers > 0 && fish.all { m.stock(it) < 1.0 }) {
            val poor = m.events.any { it.kind == EventKind.POOR_CATCH }
            val detail = if (poor) Speech.of("talk.detail.market.why", Good.COD.key + ".many", Say.WORD + "event.poor_catch") else "talk.detail.market.no_fish"
            add(out, ask, "market.no_fish", "market.no_fish.$poor", 0.6, -1, detail)
        }

        // A staple sold out: the one that weighs most in the house
        goods.filter { it != Good.BREAD && it != Good.COD && it != Good.SALMON && m.stock(it) < 1.0 }.maxByOrNull { it.share }?.let { g ->
            val q = quotes.getValue(g)
            add(out, ask, "market.short", "market.short.${g.id}.${reason(q)}", 0.8 * (0.5 + 0.5 * (g.share / STAPLE).coerceIn(0.0, 1.0)), -1, why(q) ?: none(g))
        }

        // The mill: its sails broken, or standing idle for want of grain
        if (town.mills > 0) {
            if (m.events.any { it.kind == EventKind.MILL_BROKEN }) {
                add(out, ask, "market.mill", "market.mill.broken", 0.72, -1, "talk.detail.market.mill.broken")
            } else if (m.idle[Maker.MILL.ordinal] >= 3 || m.idleYesterday[Maker.MILL.ordinal] >= 6) {
                add(out, ask, "market.mill", "market.mill.idle", 0.48, -1, "talk.detail.market.mill.idle")
            }
        }

        // The town going hungry, as the crier would cry it
        val hungry = m.hungry.lastOrNull() ?: 0.0
        val enough = town.people * Economy.MEALS * 0.05
        if (hungry >= maxOf(3.0, enough)) {
            val how = (hungry / maxOf(3.0, enough * 3.0)).coerceIn(0.0, 1.0)
            add(out, ask, "market.hungry", "market.hungry.${Say.day(ask.level)}", 0.8 * (0.5 + 0.5 * how), -1, Speech.of("talk.detail.market.hungry", hungry.roundToInt()))
        }

        // Good news: a trouble over lately, or a stroke of luck under way on what they deal in
        val hour = Markets.hourOf(ask.level)
        m.news.lastOrNull { it.key.startsWith(OVER) && hour - it.hour in 0..GLAD }?.let { n ->
            val kind = EventKind.byId(n.key.substringAfterLast('.'))
            if (kind != null) add(out, ask, "market.glad", "market.glad.over.${kind.id}.${n.hour}", 0.5, 1, Speech.of("talk.detail.market.over", Say.WORD + "event.${kind.id}"))
        }
        m.events.firstOrNull { !it.kind.trouble && goods.any { g -> affects(it, g) } }?.let { e ->
            val g = e.good ?: goods.first { affects(e, it) }
            add(out, ask, "market.glad", "market.glad.${e.kind.id}.${e.since}", 0.5, 1, Speech.of("talk.detail.market.why", g.key + ".many", Say.WORD + "event.${e.kind.id}"))
        }
        if (!talk) return

        // Prices: everything dear, or much cheap, of what they deal in, the furthest off first
        val off = quotes.values.filter { it.why != null }.sortedByDescending { abs(ln(it.price / it.usual)) }
        val dear = off.filter { it.why?.dear == true }
        val cheap = off.filter { it.why?.dear == false }
        if (dear.size >= 3) add(out, ask, "market.dear", "market.dear." + names(dear), (0.4 + 0.05 * (dear.size - 3)).coerceAtMost(0.6), -1, list("dear", dear))
        if (cheap.size >= 2) add(out, ask, "market.cheap", "market.cheap." + names(cheap), 0.35, 1, list("cheap", cheap))

        // Out shopping: what they're after, and what it's going for
        val life = ask.life
        if (shop == null && life != null && ask.player != null && Days.stretch(life, ask.level.overworldClockTime).doing == Doing.MARKET) {
            val day = Say.day(ask.level)
            val wanted = HOUSE.filter { m.stock(it) >= 1.0 }
            if (wanted.isNotEmpty()) {
                val g = wanted[Math.floorMod(day * 7 + ask.speaker.id, wanted.size.toLong()).toInt()]
                val lot = Trading.quote(entry, g).buy
                if (lot != null) {
                    val name = g.key + ".many"
                    val detail = if (lot.marks == 1) Speech.of("talk.detail.market.price.one", name, lot.count) else Speech.of("talk.detail.market.price", name, lot.count, lot.marks)
                    add(out, ask, "market.shopping", "market.shopping.${g.id}.$day", 0.3, 0, detail, Activity.EXPLAIN)
                }
            }
        }
    }

    private fun add(out: MutableList<Topic>, ask: Ask, family: String, id: String, weight: Double, tone: Int, detail: String?, gesture: Activity? = null) {
        val say = Say.line(ask, family, detail) ?: return
        out.add(Topic(id, Kind.MARKET, weight, say, tone = tone, gesture = gesture))
    }

    /** Whether [e] falls on [g]: its own good or goods, or the boats' catch for the boats' luck and trouble. */
    private fun affects(e: Event, g: Good): Boolean = e.touches(g) || e.kind.maker == Maker.BOATS && (g == Good.COD || g == Good.SALMON)

    /** The goods [ask]'s speaker deals in: a keeper's own; anyone else's what they buy for the house, and a farmer's wheat. */
    private fun goodsOf(ask: Ask, shop: Shop?): List<Good> {
        if (shop != null) return shop.goods
        return if (ask.speaker.role == TownRole.FARMER) HOUSE + Good.WHEAT else HOUSE
    }

    /** Why [q]'s price is off, as a word for an id: the trouble or luck, the reason, or none. */
    private fun reason(q: Quote): String = q.event?.id ?: q.why?.id ?: "none"

    /** "Bread: no flour for the ovens.": why [q] is dear or cheap, if it's far enough off for the stalls to say. */
    private fun why(q: Quote): String? {
        val word = q.event?.let { "event.${it.id}" } ?: q.why?.let { "why.${it.id}" } ?: return null
        return Speech.of("talk.detail.market.why", q.good.key + ".many", Say.WORD + word)
    }

    /** "Carrots: none at the stalls." */
    private fun none(g: Good): String = Speech.of("talk.detail.market.none", g.key + ".many")

    /** The goods of [quotes] for an id ("bread+coal"). */
    private fun names(quotes: List<Quote>): String = quotes.take(3).joinToString("+") { it.good.id }

    /** "Bread, Carrots and Coal, all dear." (or two of them). */
    private fun list(how: String, quotes: List<Quote>): String {
        val names = quotes.take(3).map { it.good.key + ".many" }
        return if (names.size >= 3) Speech.of("talk.detail.market.$how.three", names[0], names[1], names[2])
        else Speech.of("talk.detail.market.$how.two", names[0], names[1])
    }

    private const val OVER = "news.guildmark.market.over."
}
