package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.GuildmarkConfig
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** What a day's tallies count, for each good. */
enum class Tally(val id: String) {
    /** Grown, caught, ground, baked, forged, brewed. */
    MADE("made"),
    /** Brought in by caravan. */
    IMPORTED("imported"),
    /** Eaten, burnt, worn out, drunk, or worked into something else. */
    USED("used"),
    /** Wanted and not to be had. */
    SHORT("short"),
    /** Sold to the town by players. */
    SOLD("sold"),
    /** Bought from the town by players. */
    BOUGHT("bought"),
    /** Sent off with the caravans, more than the town could use. */
    EXPORTED("exported"),
    /** Gone off in store. */
    SPOILED("spoiled"),
    /** Lost on the road: carried off by a band that waylaid the wagon, or spilled in its wreck (see [Caravans]). */
    LOST("lost"),
    /** Put by in the town granary: what the morning caravan would have taken away, and what the wagons bring for it (see economy/works/Reserve). */
    STORED("stored"),
    /** Given out of the town granary: grain to the mill while the market runs short, roots to the market before a meal. */
    RELEASED("released"),
    /** Food given to the town for nothing, at the alms table (see [Economy.given]). */
    GIVEN("given");

    companion object {
        fun byId(id: String): Tally? = entries.firstOrNull { it.id == id }
    }
}

/** The places that make things, for counting the hours they stood idle. */
enum class Maker(val id: String) {
    FIELDS("fields"),
    MILL("mill"),
    OVENS("ovens"),
    FORGE("forge"),
    FLETCHER("fletcher"),
    BREWERY("brewery"),
    BOATS("boats"),
    CARTS("carts");

    companion object {
        fun byId(id: String): Maker? = entries.firstOrNull { it.id == id }
    }
}

/** The ways a capital's carts go (see [Economy.cart]). */
enum class Route {
    /** Grain from the farms to the mill. */
    GRAIN,
    /** Flour from the mill to the bakery. */
    FLOUR,
    /** The farms' yield to the market. */
    MARKET,
    /** The market's grain to the mill, when the fields send none. */
    GRANARY,
    /** The town granary's grain to the mill, while the market's own runs short (see economy/works/Reserve). */
    RESERVE,
}

/**
 * Something that's happened to a capital's trade until hour [until]: a [kind] of trouble or luck, on [good] if it
 * falls on one, making the makers it touches work at [factor] of their pace. Some fall on several goods at once
 * ([goods]: a waylaid wagon's load) and somewhere in particular ([place]: where on the road it was waylaid, see
 * [Caravans.PLACES]); a WAYLAID event's factor leans prices only, it never slows a maker.
 */
class Event(
    val kind: EventKind, val good: Good?, val factor: Double, val since: Long, val until: Long,
    val goods: Set<Good> = setOf(), val place: String = "",
) {
    fun touches(g: Good): Boolean = when {
        goods.isNotEmpty() -> g in goods
        good != null -> g == good
        else -> g in kind.goods
    }

    /** The same, lasting until hour [until] instead. */
    fun until(until: Long): Event = Event(kind, good, factor, since, until, goods, place)

    /** The same, moved [hours] back in time (see [Markets.rewind]). */
    fun back(hours: Long): Event = Event(kind, good, factor, since - hours, until - hours, goods, place)

    companion object {
        val CODEC: Codec<Event> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("kind").forGetter { it.kind.id },
                Codec.STRING.optionalFieldOf("good", "").forGetter { it.good?.id ?: "" },
                Codec.DOUBLE.fieldOf("factor").forGetter(Event::factor),
                Codec.LONG.optionalFieldOf("since", 0L).forGetter(Event::since),
                Codec.LONG.fieldOf("until").forGetter(Event::until),
                Codec.STRING.listOf().optionalFieldOf("goods", listOf()).forGetter { e -> e.goods.map { it.id } },
                Codec.STRING.optionalFieldOf("place", "").forGetter(Event::place),
            ).apply(i) { kind, good, factor, since, until, goods, place ->
                Event(EventKind.byId(kind) ?: EventKind.FEVER, Good.byId(good), factor, since, until, goods.mapNotNull(Good::byId).toSet(), place)
            }
        }
    }
}

/** The kinds of trouble and luck a capital's trade sees, the goods they fall on, and which makers they slow or speed. */
enum class EventKind(val id: String, val maker: Maker?, val goods: Set<Good> = setOf()) {
    /** One of the field crops withers (which one is the event's good). */
    BLIGHT("blight", Maker.FIELDS),
    /** A crop comes up far better than usual. */
    BUMPER("bumper", Maker.FIELDS),
    /** Sickness in the barns: fewer beasts to the butcher, fewer hides and fleeces. */
    MURRAIN("murrain", null, setOf(Good.BEEF, Good.PORK, Good.MUTTON, Good.LEATHER, Good.WOOL)),
    /** Sickness in the coops. */
    FOWL_PEST("fowl_pest", null, setOf(Good.CHICKEN, Good.FEATHER)),
    /** A sail or the wheel of the windmill has given out. */
    MILL_BROKEN("mill_broken", Maker.MILL),
    /** The bakehouse oven has cracked and bakes at half its batch. */
    OVEN_CRACKED("oven_cracked", Maker.OVENS),
    /** The shoals are in. */
    GOOD_CATCH("good_catch", Maker.BOATS),
    /** The nets come up empty. */
    POOR_CATCH("poor_catch", Maker.BOATS),
    /** A fever goes round: every trade works short-handed. */
    FEVER("fever", null),
    /**
     * A wagon bound for the town was waylaid on the road: what it carried won't come ([Event.goods]), and the market
     * leans those goods dearer until wagons get through again (see [Caravans]).
     */
    WAYLAID("waylaid", null);

    /** Whether it's trouble (and its end news); otherwise it's luck. */
    val trouble: Boolean get() = this != BUMPER && this != GOOD_CATCH

    companion object {
        fun byId(id: String): EventKind? = entries.firstOrNull { it.id == id }
    }
}

/** A piece of market news for the board and the crier, from hour [hour]: a lang [key], about [good] if it's about one, with a [value] (a price, a count) if it has one. */
class News(val hour: Long, val key: String, val good: Good?, val value: Double) {
    companion object {
        val CODEC: Codec<News> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("hour").forGetter(News::hour),
                Codec.STRING.fieldOf("key").forGetter(News::key),
                Codec.STRING.optionalFieldOf("good", "").forGetter { it.good?.id ?: "" },
                Codec.DOUBLE.optionalFieldOf("value", 0.0).forGetter(News::value),
            ).apply(i) { hour, key, good, value -> News(hour, key, Good.byId(good), value) }
        }
    }
}

/**
 * One capital's trade: what each of its sites holds ([stores], fractions and all, which is how the simulation keeps
 * count), today's and yesterday's tallies, the hours its makers stood idle today, the last few days' closing prices,
 * what's happened to it lately, its news, and the last hour simulated ([hour], in hours of the overworld clock).
 * [Economy] runs it hour by hour; prices come from what the market holds against what the town likes to keep.
 */
class Market(val capital: Long) {
    val stores: Array<DoubleArray> = Array(Site.entries.size) { DoubleArray(Good.entries.size) }
    var hour: Long = Long.MIN_VALUE
    val today: Array<DoubleArray> = Array(Tally.entries.size) { DoubleArray(Good.entries.size) }
    val yesterday: Array<DoubleArray> = Array(Tally.entries.size) { DoubleArray(Good.entries.size) }
    val idle = IntArray(Maker.entries.size)
    val idleYesterday = IntArray(Maker.entries.size)

    /**
     * What the market has held of each good lately, smoothed over the last several hours ([settle]), and what it held
     * when that was last worked out: prices follow the one, moved by whatever has changed hands since. A player's
     * trade moves both (see [traded]).
     */
    val held = DoubleArray(Good.entries.size)
    val mark = DoubleArray(Good.entries.size)

    /** Of what the town has used of each good lately, the share the caravans brought in, smoothed over days. */
    val brought = DoubleArray(Good.entries.size)

    /**
     * What bringing each good in costs here, of its base, for all of it brought: by the road from the nearest capital
     * with it to spare, or from afar. [Carriage] works out where it should stand ([carryGoal]) and it eases there
     * through the days ([settle]), so a price doesn't leap when a neighbour's market opens. Kept with the world while
     * the ease is on (see [carryKnown]).
     */
    val carry = DoubleArray(Good.entries.size) { Economy.FREIGHT }

    /** Where [carry] is heading: what [Carriage] last found bringing each good in costs here (not kept; it is found again as the markets load). */
    val carryGoal = DoubleArray(Good.entries.size) { Economy.FREIGHT }

    /** Whether [carry] has been set from a goal, or kept from a save: until it has, the first [Carriage.reckon] puts it at its goal at once. */
    var carryKnown = false

    /** Each good's price at the close of each of the last [HISTORY] days, oldest first, as a share of its base. */
    val closes: Array<DoubleArray> = Array(Good.entries.size) { DoubleArray(0) }
    val events = ArrayList<Event>()
    val news = ArrayList<News>()

    /** Meals the town went without on each of the last [HISTORY] days, oldest first. */
    var hungry = DoubleArray(0)

    /**
     * What each maker turned out in the hour last worked out (the mill's flour, the ovens' loaves, the carts' loads):
     * what the town's people are seen at just now. Not kept with the world; the next hour tells it again.
     */
    val worked = DoubleArray(Maker.entries.size)

    /** What the carts took each [Route] the last hour they went out, and what they brought the market of each good then (not kept either). */
    val carted = DoubleArray(Route.entries.size)
    val carts = DoubleArray(Good.entries.size)

    /**
     * What the town bought at the market of each good in the hour last worked out: its meals, what it wore out and
     * burnt, and what its makers fetched to work with (not what the caravans took away). Players' own stalls sell
     * out of it (see [OwnStalls]). Not kept either.
     */
    val drawn = DoubleArray(Good.entries.size)

    operator fun get(site: Site, good: Good): Double = stores[site.ordinal][good.ordinal]

    operator fun set(site: Site, good: Good, amount: Double) {
        stores[site.ordinal][good.ordinal] = amount.coerceAtLeast(0.0)
    }

    fun add(site: Site, good: Good, amount: Double) = set(site, good, get(site, good) + amount)

    /** Takes up to [amount] of [good] from [site]; gives how much there was to take. */
    fun take(site: Site, good: Good, amount: Double): Double {
        val had = get(site, good)
        val took = minOf(had, amount.coerceAtLeast(0.0))
        set(site, good, had - took)
        return took
    }

    /** What the market has for sale. */
    fun stock(good: Good): Double = get(Site.MARKET, good)

    /** Whether [maker] is at work this hour: it turned something out in the hour last worked out. */
    fun busy(maker: Maker): Boolean = worked[maker.ordinal] > 0.0

    /** [maker] turned out [amount] this hour. */
    fun did(maker: Maker, amount: Double) {
        if (amount > 0.0) worked[maker.ordinal] += amount
    }

    fun tally(tally: Tally, good: Good, amount: Double) {
        today[tally.ordinal][good.ordinal] += amount
    }

    fun today(tally: Tally, good: Good): Double = today[tally.ordinal][good.ordinal]
    fun yesterday(tally: Tally, good: Good): Double = yesterday[tally.ordinal][good.ordinal]

    /** The factor every event under way puts on [maker]'s work with [good]. */
    fun factor(maker: Maker?, good: Good?): Double {
        var f = 1.0
        for (e in events) {
            val on = when {
                // A waylaid wagon makes goods dear; it never slows the town's own hands
                e.kind == EventKind.WAYLAID -> false
                e.kind == EventKind.FEVER -> maker != null
                e.kind.maker != null -> e.kind.maker == maker && (e.good == null || e.good == good)
                else -> good != null && e.touches(good)
            }
            if (on) f *= e.factor
        }
        return f
    }

    /**
     * What a unit of [good] goes for while the market holds [stock] of it: its base price, dearer the shorter the
     * town is of it against what it likes to keep (up to three times, and as low as under half in a glut), dearer
     * for what it has to have brought in, the more the farther it comes, and leaning ahead of trouble or luck already
     * under way. What it holds is what it's held lately ([held]) and whatever has changed hands since, so a stall's
     * prices don't swing with every meal, but every lot a player buys or sells moves them, and keeps them moved until
     * the town's own buying and making and the caravans have made it up (see [traded]).
     */
    fun price(town: Town, good: Good, stock: Double = stock(good)): Double {
        val target = town.target(good)
        val supply = held[good.ordinal] + stock - mark[good.ordinal]
        val ratio = sqrt(target / maxOf(supply, target * 0.01)).coerceIn(LOW, HIGH)
        return good.base * (ratio * freight(good) * outlook(good)).coerceIn(LOW, HIGHEST)
    }

    /** How much dearer [good] runs for the share of it the caravans have lately had to bring, and how far they bring it ([carry]). */
    fun freight(good: Good): Double = 1.0 + carry[good.ordinal] * brought[good.ordinal]

    /**
     * A player's trade: [amount] of [good] into the market, or out of it if less than nothing (no more than it has).
     * What it's held lately moves with it, so the hour's settle doesn't ease the trade back out of the price: a lot
     * bought keeps the good dear, and a lot sold keeps it cheap, until the town has used it up or its makers and the
     * caravans have made it up. Gives how much moved.
     */
    fun traded(good: Good, amount: Double): Double {
        val i = good.ordinal
        val moved = if (amount >= 0.0) amount.also { add(Site.MARKET, good, it) } else -take(Site.MARKET, good, -amount)
        val was = held[i]
        held[i] = (was + moved).coerceAtLeast(0.0)
        // Bought past what it's held lately: what that couldn't take off eases out at the settle, as before
        mark[i] += held[i] - was
        return moved
    }

    /** At the end of an hour: what the market holds now counts a little toward what it's held lately, and what bringing goods in costs a little toward where it's heading. */
    fun settle() {
        for (g in Good.entries) {
            held[g.ordinal] += (stock(g) - held[g.ordinal]) * SETTLE
            mark[g.ordinal] = stock(g)
        }
        easeCarry(1)
    }

    /**
     * [carry] eases toward [carryGoal] for [hours] hours: each day closes [carryEaseRate] of what is left of the gap (a
     * share of 0.3 gets nine tenths of the way in a week), so what a good costs to bring in works its way to its new
     * level instead of leaping, and the price with it. With the ease off, or at a rate of 1, it is at its goal at once.
     * Each good ends exactly at its goal, however long it takes.
     */
    fun easeCarry(hours: Int) {
        val open = (1.0 - carryEaseRate()).pow(hours / 24.0)
        for (i in carry.indices) {
            val gap = carryGoal[i] - carry[i]
            if (gap == 0.0) continue
            carry[i] = if (abs(gap) * open < CARRY_SNAP) carryGoal[i] else carryGoal[i] - gap * open
        }
    }

    /**
     * Days until [good]'s carry is within [within] of its goal, easing at the rate it does now (the same sum
     * [easeCarry] works hour by hour): 0 if it is already, or if the ease is off and the next hour puts it there. With
     * [CARRY_SNAP] it is the days until it lands exactly on its goal.
     */
    fun carryDays(good: Good, within: Double = CARRY_SNAP): Double {
        val gap = abs(carryGoal[good.ordinal] - carry[good.ordinal])
        val rate = carryEaseRate()
        if (gap <= within || rate >= 1.0) return 0.0
        return ln(within / gap) / ln(1.0 - rate)
    }

    /** Starts what the market has held lately afresh from what it holds, and [brought] from how [town] usually fares. */
    fun reset(town: Town) {
        for (g in Good.entries) {
            held[g.ordinal] = stock(g)
            mark[g.ordinal] = stock(g)
            brought[g.ordinal] = town.shortfall(g)
        }
    }

    /** How the market leans on [good] for what's under way: dearer ahead of a blight, cheaper ahead of a good catch. */
    fun outlook(good: Good): Double {
        var f = 1.0
        for (e in events(good)) f *= if (e.factor < 1.0) 1.0 + (1.0 - e.factor) * LEAN_DEAR else 1.0 - (e.factor - 1.0) * LEAN_CHEAP
        return f.coerceIn(0.7, 1.5)
    }

    /** What's under way that moves [good]'s price: trouble or luck with it, or with the makers of what it's made into. */
    fun events(good: Good): List<Event> = events.filter { e ->
        when {
            e.kind == EventKind.FEVER -> true
            e.kind.maker == Maker.MILL -> good == Good.BREAD || good == Good.FLOUR
            e.kind.maker == Maker.OVENS -> good == Good.BREAD
            e.kind.maker == Maker.BOATS -> good in Economy.CATCH.keys
            else -> e.touches(good)
        }
    }

    /** Today's price of [good] against yesterday's close: 1 rising, -1 falling, 0 steady. */
    fun trend(town: Town, good: Good): Int {
        val closes = closes[good.ordinal]
        if (closes.isEmpty()) return 0
        val now = price(town, good) / good.base
        val then = closes.last()
        return when {
            now > then * 1.06 -> 1
            now < then / 1.06 -> -1
            else -> 0
        }
    }

    /**
     * Closes the day: keeps [town]'s prices and how hungry it went, and starts the tallies afresh. What was lost on
     * the road counts with what the caravans brought, so a waylaid load doesn't make freight any cheaper.
     */
    fun close(town: Town) {
        for (g in Good.entries) {
            val used = today(Tally.USED, g) + today(Tally.SPOILED, g) + today(Tally.EXPORTED, g)
            val came = today(Tally.IMPORTED, g) + today(Tally.LOST, g)
            if (used > 0.0) brought[g.ordinal] += ((came / used).coerceIn(0.0, 1.0) - brought[g.ordinal]) * BROUGHT
        }
        for (g in Good.entries) {
            val list = closes[g.ordinal]
            val next = if (list.size >= HISTORY) list.copyOfRange(1, list.size) else list
            closes[g.ordinal] = next + price(town, g) / g.base
        }
        val meals = Good.FOODS.sumOf { today(Tally.SHORT, it) * it.food }
        hungry = (if (hungry.size >= HISTORY) hungry.copyOfRange(1, hungry.size) else hungry) + meals
        for (t in Tally.entries) {
            today[t.ordinal].copyInto(yesterday[t.ordinal])
            today[t.ordinal].fill(0.0)
        }
        idle.copyInto(idleYesterday)
        idle.fill(0)
    }

    /** The waylaid wagon the market is feeling, if there is one (there's only ever one: a second joins the first). */
    fun waylaid(): Event? = events.firstOrNull { it.kind == EventKind.WAYLAID }

    fun say(key: String, good: Good? = null, value: Double = 0.0) {
        news.add(News(hour, key, good, value))
        while (news.size > NEWS) news.removeAt(0)
    }

    companion object {
        /** Days of closing prices and hunger kept. */
        const val HISTORY = 8

        /** Pieces of news kept. */
        const val NEWS = 12

        /** The lowest and highest a price goes against its base for what the market holds, and with everything else too. */
        const val LOW = 0.45
        const val HIGH = 3.0
        const val HIGHEST = 4.0

        /** How much of the way what the market holds moves what it's held lately in an hour, and what's brought in a day. */
        private const val SETTLE = 0.15
        private const val BROUGHT = 0.3

        /** How much of what is left of the gap to its new value what bringing a good in costs closes in a day, and how near its goal counts as there. */
        private const val CARRY_EASE = 0.3
        const val CARRY_SNAP = 0.001

        /** The share of the gap to a good's new carry that closes in a day: the config marketCarryEaseRate, or 1 (the old jump) with marketCarryEase off. */
        fun carryEaseRate(): Double = try {
            if (GuildmarkConfig.MARKET_CARRY_EASE.get()) GuildmarkConfig.MARKET_CARRY_EASE_RATE.get() else 1.0
        } catch (e: IllegalStateException) {
            CARRY_EASE
        }

        /** Whether the carry eases to its goal over days rather than jumping there (marketCarryEase on, at a rate under 1). */
        fun easing(): Boolean = carryEaseRate() < 1.0

        /** How far trouble or luck under way moves a price before the market feels it. */
        private const val LEAN_DEAR = 0.3
        private const val LEAN_CHEAP = 0.15

        private fun table(ids: (Int) -> String, size: Int, values: Array<DoubleArray>): Map<String, Map<String, Double>> =
            (0 until size).associate { i -> ids(i) to goods(values[i]) }.filterValues { it.isNotEmpty() }

        private fun goods(values: DoubleArray): Map<String, Double> {
            val out = HashMap<String, Double>()
            for (g in Good.entries) if (values[g.ordinal] != 0.0) out[g.id] = values[g.ordinal]
            return out
        }

        private fun fill(into: DoubleArray, from: Map<String, Double>) {
            for ((id, v) in from) Good.byId(id)?.let { into[it.ordinal] = v }
        }

        /** The carry for the save: only while the ease is on and it has been set; off, nothing new is written and a load takes the goals at once, as it always has. */
        private fun carried(m: Market): Map<String, Map<String, Double>> = if (m.carryKnown && easing()) mapOf("carry" to goods(m.carry)) else mapOf()

        private val GOODS = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
        private val TABLE = Codec.unboundedMap(Codec.STRING, GOODS)

        val CODEC: Codec<Market> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(Market::capital),
                Codec.LONG.optionalFieldOf("hour", Long.MIN_VALUE).forGetter(Market::hour),
                TABLE.optionalFieldOf("stores", mapOf()).forGetter { m -> table({ Site.entries[it].id }, Site.entries.size, m.stores) },
                TABLE.optionalFieldOf("today", mapOf()).forGetter { m -> table({ Tally.entries[it].id }, Tally.entries.size, m.today) },
                TABLE.optionalFieldOf("yesterday", mapOf()).forGetter { m -> table({ Tally.entries[it].id }, Tally.entries.size, m.yesterday) },
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("idle", mapOf()).forGetter { m ->
                    Maker.entries.filter { m.idle[it.ordinal] != 0 }.associate { it.id to m.idle[it.ordinal] }
                },
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE.listOf()).optionalFieldOf("closes", mapOf()).forGetter { m ->
                    Good.entries.filter { m.closes[it.ordinal].isNotEmpty() }.associate { g -> g.id to m.closes[g.ordinal].toList() }
                },
                Event.CODEC.listOf().optionalFieldOf("events", listOf()).forGetter { it.events },
                News.CODEC.listOf().optionalFieldOf("news", listOf()).forGetter { it.news },
                Codec.DOUBLE.listOf().optionalFieldOf("hungry", listOf()).forGetter { it.hungry.toList() },
                TABLE.optionalFieldOf("held", mapOf()).forGetter { m -> mapOf("held" to goods(m.held), "mark" to goods(m.mark), "brought" to goods(m.brought)) + carried(m) },
            ).apply(i) { capital, hour, stores, today, yesterday, idle, closes, events, news, hungry, held ->
                Market(capital).also { m ->
                    m.hour = hour
                    for ((id, goods) in stores) Site.byId(id)?.let { fill(m.stores[it.ordinal], goods) }
                    for ((id, goods) in today) Tally.byId(id)?.let { fill(m.today[it.ordinal], goods) }
                    for ((id, goods) in yesterday) Tally.byId(id)?.let { fill(m.yesterday[it.ordinal], goods) }
                    for ((id, n) in idle) Maker.byId(id)?.let { m.idle[it.ordinal] = n }
                    for ((id, list) in closes) Good.byId(id)?.let { m.closes[it.ordinal] = list.toDoubleArray() }
                    m.events.addAll(events)
                    m.news.addAll(news)
                    m.hungry = hungry.toDoubleArray()
                    held["held"]?.let { fill(m.held, it) }
                    held["mark"]?.let { fill(m.mark, it) }
                    held["brought"]?.let { fill(m.brought, it) }
                    // A save before the ease, or made with it off, has no carry: its first reckon takes the goals at once
                    held["carry"]?.let {
                        fill(m.carry, it)
                        fill(m.carryGoal, it)
                        m.carryKnown = true
                    }
                }
            }
        }
    }
}
