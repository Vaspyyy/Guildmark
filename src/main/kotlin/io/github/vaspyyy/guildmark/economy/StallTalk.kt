package io.github.vaspyyy.guildmark.economy

import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import net.minecraft.server.level.ServerLevel

/**
 * What a shop's keeper says to the players they serve (lines under say.guildmark.market., text only for now): a
 * greeting by the hour, the weather and their ways, why a price is where it is ("Dear today, I'm afraid: the mill's
 * sails are broken"), a word on a deal and what came of it ("Carrots, all gone! You've cleared me out."), a goodbye,
 * and out of hours that they're shut, and why. Over orders and standing they speak in their own voices (lines under
 * say.guildmark.order_, which name no goods and no numbers; the details go to the chat): an order offered, taken,
 * filled or part filled, let lapse or given up, goods refused for coming off their own stall, and each rank a
 * supplier rises to.
 */
object StallTalk {
    /** Which of the keepers' own lines fit [shop]: a stall's, the market hall's, the bakery's, the smithy's or the inn's. */
    private fun kind(shop: Shop): String = when (shop) {
        Shop.BAKERY -> "bakery"
        Shop.SMITHY -> "smithy"
        Shop.HALL -> "hall"
        Shop.INN -> "inn"
        else -> "stall"
    }

    private fun pick(p: Townsperson, key: String, n: Int): String = "market.$key.${p.random.nextInt(n)}"

    /** The clock's hour (0 at midnight) where [level] keeps time. */
    private fun clock(level: ServerLevel): Int = Math.floorMod(Math.floorDiv(level.overworldClockTime, 1000L) + 6L, 24L).toInt()

    /** Whether [p] stands indoors: in the market hall, the bakery or the smithy. */
    private fun indoors(p: Townsperson, life: Life?): Boolean =
        life?.places?.lodgingAt(p.blockPosition())?.contains(p.blockPosition()) == true

    /** Why [p] can't serve just now: asleep, not up yet, at their lunch, done for the day, at the tavern, the rest day. */
    fun shut(p: Townsperson, life: Life, doing: Doing, shop: Shop, level: ServerLevel): String {
        val kind = kind(shop)
        if (p.isSleeping || doing == Doing.SLEEP) return "market.shut.asleep"
        if (Days.restDay(Days.dayOf(level.overworldClockTime))) return "market.shut.rest"
        return when (doing) {
            Doing.RISE, Doing.BREAKFAST -> if (kind == "smithy") "market.shut.early.smithy" else "market.shut.early"
            Doing.WORSHIP -> "market.shut.worship"
            Doing.LUNCH -> "market.shut.lunch"
            Doing.TAVERN -> "market.shut.tavern"
            Doing.SUPPER, Doing.EVENING, Doing.BED -> "market.shut.evening.$kind"
            else -> {
                // The market hall deals in everything, all day
                val hall = kind != "hall" && life.places.lodgings.values.any { it.kind == Kind.MARKET_HALL.name }
                if (hall && clock(level) in 8..16 && p.random.nextInt(2) == 0) "market.shut.hall" else "market.shut.done.$kind"
            }
        }
    }

    /**
     * What [p] says as a player steps up to their shop: by the weather, the hour, their ways and their trade; [busy]
     * if they're serving someone else already, [again] if they've served this player lately.
     */
    fun greet(p: Townsperson, life: Life?, shop: Shop, level: ServerLevel, busy: Boolean, again: Boolean): String {
        val r = p.random
        val kind = kind(shop)
        if (busy) return "market.greet.busy"
        if (again && r.nextInt(2) == 0) return "market.greet.again"
        val inside = indoors(p, life)
        if (level.isThundering && !inside) return "market.greet.storm"
        if (level.isRaining && r.nextInt(3) != 0) return if (inside) "market.greet.rain.indoors" else pick(p, "greet.rain", 2)
        if (life?.resident?.has(Trait.GRUMPY) == true && r.nextInt(2) == 0) return pick(p, "greet.grumpy", 2)
        if (life?.resident?.has(Trait.CHEERFUL) == true && r.nextInt(2) == 0) return pick(p, "greet.cheerful", 2)
        if ((kind == "bakery" || kind == "smithy") && r.nextInt(5) < 2) return "market.greet.$kind"
        return when (clock(level)) {
            in 4..10 -> pick(p, "greet.morning", 2)
            in 11..14 -> pick(p, "greet.day", 2)
            in 15..18 -> pick(p, "greet.late", 2)
            else -> "market.greet.night"
        }
    }

    /** Which of [quotes] is furthest off its usual price for a reason, if any is and that hasn't been [told] yet. */
    fun news(quotes: List<Quote>, told: Set<Good>): Quote? =
        quotes.filter { it.why != null && it.good !in told }.maxByOrNull { kotlin.math.abs(kotlin.math.ln(it.price / it.usual)) }

    /** Why [q]'s price is where it is, as the keeper puts it ("Dear today, I'm afraid: the mill's sails are broken"). */
    fun price(q: Quote): String? {
        val why = q.why ?: return null
        val event = q.event
        waylaid(q)?.let { return it }
        if (why.dear) return when {
            event != null -> if (event in DEAR) "market.dear.event.${event.id}" else "market.dear.scarce"
            else -> "market.dear.${why.id}"
        }
        return when {
            event != null -> if (event in CHEAP) "market.cheap.event.${event.id}" else "market.cheap.plenty"
            why == Why.SOLD_IN || why.season -> "market.cheap.${why.id}"
            else -> "market.cheap.plenty"
        }
    }

    /**
     * A waylaid wagon as the reason [q]'s good is dear (see [Caravans]), in the words of the kind of stall it's sold
     * at: the baker's (bread, and the grain or flour the mill and the ovens stood idle for), the fishmonger's, the
     * produce stall's, or anyone else's wares; where on the road it was taken goes in the detail. Null if it's not that.
     */
    private fun waylaid(q: Quote): String? {
        val trouble = q.trouble ?: return null
        val kind = when {
            q.good == Good.BREAD || q.good == Good.FLOUR || q.why == Why.NO_GRAIN || q.why == Why.NO_FLOUR -> "bakery"
            q.good in Economy.CATCH.keys -> "fish"
            q.good.food > 0 || q.good == Good.WHEAT -> "produce"
            else -> "wares"
        }
        val place = trouble.place.takeIf { it in Caravans.PLACES } ?: "road"
        return Speech.withDetail("$WAYLAID.$kind", "market.detail.waylaid.$place")
    }

    /** The keepers' line for a waylaid wagon, by kind of stall ("market.dear.event.waylaid.fish": "No fish since they took the wagon."). */
    const val WAYLAID = "market.dear.event.waylaid"

    /** The events the keepers have a word for when they make a good dear, and when they make it cheap. */
    private val DEAR = setOf(
        EventKind.BLIGHT, EventKind.MURRAIN, EventKind.FOWL_PEST, EventKind.MILL_BROKEN, EventKind.OVEN_CRACKED,
        EventKind.POOR_CATCH, EventKind.FEVER, EventKind.WAYLAID,
    )
    private val CHEAP = setOf(EventKind.BUMPER, EventKind.GOOD_CATCH)

    /**
     * What [p] has to say to a player [buying] or selling [good] when [done] lots of it went through and [stop] is
     * what stopped the rest (if anything did), [after] being the good's quote now: a refusal, the last of it gone,
     * no room for more, or why it's priced as it is if that's not been [told] yet (it's told now). Null if it's
     * nothing that needs saying; see [remark] for the small talk.
     */
    fun deal(good: Good, buying: Boolean, done: Int, stop: Deal?, after: Quote, told: MutableSet<Good>): String? {
        val name = good.key + ".many"
        if (done == 0 && stop != null) return when (stop.outcome) {
            Deal.Outcome.NONE -> Speech.of(if (buying) "market.none_left" else "market.full", name)
            Deal.Outcome.POOR -> (stop.lot?.marks ?: 1).let { if (it == 1) "market.poor.one" else Speech.of("market.poor", it) }
            Deal.Outcome.LACKING -> Speech.of("market.lacking", stop.lot?.count ?: 1)
            Deal.Outcome.OWN_WAGON -> OWN_WAGON
            Deal.Outcome.DONE -> null
        }
        if (buying && after.buy == null) return Speech.of("market.cleared", name)
        if (!buying && after.sell == null) return Speech.of("market.full", name)
        val why = after.why ?: return null
        if (!told.add(good)) return null
        return when {
            buying -> price(after)
            why.dear -> "market.bought.wanted"
            else -> "market.bought.glut"
        }
    }

    /** A word of small talk over a deal that needs nothing said: [done] lots bought ([buying]) or sold. */
    fun remark(p: Townsperson, buying: Boolean, done: Int, dear: Boolean): String = when {
        !buying -> pick(p, "bought", 3)
        done >= 3 && p.random.nextInt(2) == 0 -> "market.sold.many"
        dear && p.random.nextInt(3) == 0 -> "market.dear.again"
        else -> pick(p, "sold", 4)
    }

    /**
     * What [p] says as the last player they're serving goes: [traded] if they bought or sold anything, [walked] if
     * they walked off without a word.
     */
    fun bye(p: Townsperson, life: Life?, level: ServerLevel, traded: Boolean, walked: Boolean): String {
        if (walked) return "market.bye.walked"
        if (!traded) return if (life?.resident?.has(Trait.GRUMPY) == true) "market.bye.grumpy" else "market.bye.browsed"
        if (level.isRaining && !indoors(p, life) && p.random.nextInt(2) == 0) return "market.bye.rain"
        if (clock(level) >= 17 && p.random.nextInt(2) == 0) return "market.bye.evening"
        return pick(p, "bye", 3)
    }

    /** What a keeper says packing up when their hours are over and the player has stopped trading. */
    const val CLOSING = "market.closing"

    // ---- Salvage off the capital's waylaid wagons (see Marked) ----

    /** Refusing to buy salvage off their own capital's wagon ("That's off our own wagon. I'll not buy it back."). */
    const val OWN_WAGON = "market.own_wagon"

    /** Taking salvage back and paying the finder's fee ("Off our own wagon! Thank you. Here's something for your trouble."). */
    const val SALVAGE_BACK = "market.salvage.back"

    /** Buying what came off another capital's wagon, remarking on the mark ("Off another town's wagon, that. Not my business."). */
    const val SALVAGE_MARKED = "market.salvage.marked"

    /** Greeting a player who kept what they took off the capital's wagon, while the keepers are cool on them. */
    const val COOL = "market.greet.cool"

    // ---- Orders and standing, spoken ----

    private fun spoken(p: Townsperson, key: String, n: Int): String = "order_${key}_${p.random.nextInt(n)}"

    /** An order of theirs to be had ("I've an order going, if you've the goods for it."). */
    fun offer(p: Townsperson): String = spoken(p, "offer", 2)

    /** An order taken, or a standing order accepted ("Good. It's yours, then. Bring it by the rest day."). */
    fun taken(p: Townsperson): String = spoken(p, "taken", 2)

    /** An order filled ("That's the lot. Thank you kindly!"), and part of one ("A good start. I'll mark it down."). */
    fun thanks(p: Townsperson): String = spoken(p, "thanks", 2)
    fun part(p: Townsperson): String = spoken(p, "part", 2)

    /** To a supplier who let one of their orders lapse lately, as they step up ("You never brought it. I had to look elsewhere."). */
    fun lapsed(p: Townsperson): String = spoken(p, "lapsed", 2)

    /** A supplier's rise to [rank] at their capital ("Everyone here knows your goods. A known supplier!"). */
    fun rank(rank: SupplierRank): String = "order_rank_${rank.ordinal}"

    /** An order given up; goods refused, or sold for no standing, for coming off their own stall lately; a standing order offered, and turned down. */
    const val GIVEN_UP = "order_given_up"
    const val BOUGHT_HERE = "order_bought_here"
    const val STANDING = "order_standing"
    const val DECLINED = "order_declined"
}
