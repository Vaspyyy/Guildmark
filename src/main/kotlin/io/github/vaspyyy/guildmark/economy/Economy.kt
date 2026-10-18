package io.github.vaspyyy.guildmark.economy

import kotlin.math.pow
import kotlin.random.Random

/**
 * The world in the hour being worked out: which hour of the overworld clock it is ([hour], counted from the
 * world's first), the weather, how the season has the fields, beasts and orchards doing ([pace] of a good, 1 as
 * usual), how much coal homes burn for warmth ([heating], 1 as usual), and whether it's a feast day.
 */
class Conditions(
    val hour: Long,
    val rain: Boolean = false,
    val thunder: Boolean = false,
    val pace: (Good) -> Double = { 1.0 },
    val heating: Double = 1.0,
    val feast: Boolean = false,
) {
    /** The clock's hour, 0 at midnight. */
    val clock: Int get() = Math.floorMod(hour + 6, 24L).toInt()

    /** The town's day, which starts at two in the morning (as townsfolk's days do). */
    val day: Long get() = Math.floorDiv(hour + 4, 24L)

    /** Every seventh day the trades rest (the inn and the stalls don't). */
    val restDay: Boolean get() = Math.floorMod(day, 7L) == 6L
}

/**
 * A capital's trade, worked out an hour at a time whether or not anyone is there to see it: the fields, barns,
 * coops, orchards and boats bring in their yield while their people are at work; the carters take it to the mill
 * and the market; the mill grinds, the bakehouse bakes, the forge makes tools and the fletcher arrows; the town
 * eats three meals a day from the market by habit and by price, wears out its wool and leather, burns candles and
 * coal, and drinks the tavern's ale of an evening; stores go off; and the morning caravans bring in what the town
 * can't make and take away what it can't use. Once a day the market closes its books and now and then trouble or
 * luck comes its way (see [EventKind]). Everything here is plain arithmetic on a [Market], so it can be run ahead
 * after a long sleep and checked on its own.
 */
object Economy {
    // --- The people ---

    const val MEALS = 3.0
    /** On a feast day everyone eats this much more, and the tavern pours three times as much. */
    const val FEAST = 1.4
    const val WOOL = 0.04
    const val STRING = 0.02
    const val LEATHER = 0.03
    const val CANDLES = 0.08
    const val BOTTLES = 0.01
    /** Coal a person's hearth burns in a day at the year's usual. */
    const val HEATING = 0.04
    /** Arrows a guard looses at the butts in a day. */
    const val ARROWS = 1.0
    /** Tools worn out in a day by a farmer or a fisher, and by anyone else with a trade. */
    const val FIELD_WEAR = 0.06
    const val WEAR = 0.02
    /** Of the grown-ups, how many are at the tavern of an evening, and what each drinks. */
    const val TAVERN_SHARE = 0.35
    const val ALE = 2.0
    /** How far a price that's off its usual turns people to other food: half again as dear, a third less eaten. */
    private const val CHOOSY = 1.5

    // --- The makers: per worker (or barn, coop, tree, windmill) per working hour, or per day ---

    /** What a farmer brings in from the fields in an hour, seed kept back for sowing. */
    val FIELD: Map<Good, Double> = linkedMapOf(Good.WHEAT to 2.6, Good.CARROT to 0.3, Good.POTATO to 0.33, Good.BEETROOT to 0.22)
    const val FIELD_HOURS = 11.0
    /** A barn's beasts in a day, and a coop's. */
    val BARN: Map<Good, Double> = linkedMapOf(Good.BEEF to 2.0, Good.PORK to 1.6, Good.MUTTON to 2.0, Good.LEATHER to 1.6, Good.WOOL to 2.4)
    val COOP: Map<Good, Double> = linkedMapOf(Good.CHICKEN to 2.6, Good.FEATHER to 5.0)
    /** How much of their yield barns and coops give with no farmer to tend them. */
    const val UNTENDED = 0.3
    /** Apples from a fruit tree in a day. */
    const val TREE = 0.6
    /** A fisher's catch in an hour, and what it's made of. */
    const val FISH = 4.5
    val CATCH: Map<Good, Double> = linkedMapOf(Good.COD to 0.6, Good.SALMON to 0.4)
    const val FISH_HOURS = 6.0
    /** Grain a windmill grinds in an hour. */
    const val MILL = 40.0
    const val MILL_HOURS = 10.0
    /** Loaves a baker bakes in an hour, and the flour a loaf takes. */
    const val BAKE = 45.0
    const val BAKE_FLOUR = 3.0
    const val BAKE_HOURS = 7.0
    /** Tools a smith makes in an hour, and the iron and coal each takes. */
    const val FORGE = 0.5
    const val FORGE_IRON = 2.0
    const val FORGE_COAL = 1.0
    const val FORGE_HOURS = 9.0
    /** Arrows a fletcher makes in an hour, and the flint and feathers each takes. */
    const val FLETCH = 6.0
    const val FLETCH_FLINT = 0.25
    const val FLETCH_FEATHER = 0.25
    /** Ale an innkeeper brews in an hour, and the grain each measure takes. */
    const val BREW = 12.0
    const val BREW_WHEAT = 1.0 / 3.0
    const val BREW_HOURS = 4.0
    /** What a cart carries in an hour of going to and fro. */
    const val CART = 96.0
    /** How well the trades work with worn-out tools. */
    const val WORN = 0.75

    // --- The caravans ---

    /** How much dearer than its base a good runs where all of it comes from afar. */
    const val FREIGHT = 0.35
    /** Of a day's use, how much more than its usual shortfall the caravans can bring. */
    const val SPARE = 0.25
    /** Of how far the market is below its target, how much the caravans can make up in a day however short it is. */
    const val CATCH_UP = 0.2
    /** The market keeps no more than this many times its target; the caravans take away half the rest each day. */
    const val GLUT = 2.0
    /** The least a market likes to have of anything. */
    const val LEAST = 12.0

    // --- Trouble and luck ---

    /** The chance each morning of something new happening to the town's trade. */
    const val EVENT_CHANCE = 0.14
    private const val MAX_EVENTS = 2

    /** The hours of the clock each trade works on a working day, with an hour off for lunch where they take one. */
    fun farming(h: Int): Boolean = h in 6..11 || h in 13..17
    fun milling(h: Int): Boolean = h in 7..11 || h in 13..17
    fun baking(h: Int, ahead: Boolean = false): Boolean = h in 4..(if (ahead) 13 else 10)
    fun forging(h: Int): Boolean = h in 7..11 || h in 13..16
    fun fishing(h: Int): Boolean = h in 5..10
    fun carting(h: Int): Boolean = h in 8..11 || h in 13..16
    fun brewing(h: Int): Boolean = h in 10..13

    /** The day before the rest day, when the bakehouse bakes on past noon for that day too. */
    fun ahead(day: Long): Boolean = Math.floorMod(day + 1, 7L) == 6L

    /** Meals (breakfast, lunch, supper), the tavern's hours, and when the caravans come in (three a day). */
    fun meal(h: Int): Boolean = h == 7 || h == 12 || h == 18
    fun tavern(h: Int): Boolean = h in 19..23
    fun caravan(h: Int): Boolean = h == 9 || h == 12 || h == 15
    const val NEW_DAY = 2

    /** Works out one hour of [m]'s trade for [town] in [c]; [random] decides what trouble or luck a new day brings. */
    fun hour(m: Market, town: Town, c: Conditions, random: Random) {
        m.hour = c.hour
        m.worked.fill(0.0)
        val h = c.clock
        if (h == NEW_DAY) newDay(m, town, c, random)
        val over = m.events.filter { it.until <= c.hour }
        if (over.isNotEmpty()) {
            m.events.removeAll(over)
            // The good news the crier has when trouble ends (a bumper crop or a run of fish just ends)
            for (e in over) if (e.kind.trouble) m.say("news.guildmark.market.over.${e.kind.id}", e.good)
        }
        if (!c.restDay) {
            if (farming(h)) {
                farm(m, town, c)
                herds(m, town, c)
            }
            if (fishing(h)) fish(m, town, c)
            if (milling(h)) mill(m, town)
            if (baking(h, ahead(c.day))) bake(m, town, c)
            if (h == 7) stockForge(m, town)
            if (forging(h)) {
                forge(m, town)
                fletch(m, town)
            }
            if (carting(h)) cart(m, town)
        }
        // The inn keeps every day: grain bought at the market after lunch, brewing before it
        if (h == 14) stockTavern(m, town)
        if (brewing(h)) brew(m, town)
        if (meal(h)) eat(m, town, c)
        wares(m, town, c)
        if (tavern(h)) drink(m, town, c)
        spoil(m)
        if (caravan(h)) caravans(m, town, first = h == 9)
        m.settle()
    }

    /** Sets a new market up as a town keeps one: everything at what it likes to hold, a day's work in hand. */
    fun open(m: Market, town: Town) {
        for (g in Good.entries) if (g.traded || g == Good.TOOLS) m[Site.MARKET, g] = town.target(g)
        m[Site.MILL, Good.WHEAT] = town.mills * MILL * MILL_HOURS
        m[Site.BAKEHOUSE, Good.FLOUR] = town.use(Good.FLOUR) * 2.5
        m[Site.SMITHY, Good.IRON] = town.smiths * FORGE * FORGE_HOURS * FORGE_IRON
        m[Site.SMITHY, Good.COAL] = town.smiths * FORGE * FORGE_HOURS * FORGE_COAL
        m[Site.TAVERN, Good.WHEAT] = town.innkeepers * BREW * BREW_HOURS * BREW_WHEAT * 2
        m[Site.TAVERN, Good.ALE] = town.target(Good.ALE)
        m.reset(town)
    }

    private fun tools(m: Market): Double = if (m.stock(Good.TOOLS) >= 1.0) 1.0 else WORN

    private fun make(m: Market, site: Site, good: Good, amount: Double) {
        if (amount <= 0.0) return
        m.add(site, good, amount)
        m.tally(Tally.MADE, good, amount)
    }

    private fun use(m: Market, site: Site, good: Good, amount: Double): Double {
        val got = m.take(site, good, amount)
        m.tally(Tally.USED, good, got)
        if (got < amount - 1e-9) m.tally(Tally.SHORT, good, amount - got)
        return got
    }

    private fun farm(m: Market, town: Town, c: Conditions) {
        if (town.farmers == 0) return
        val weather = when {
            c.thunder -> 0.2
            c.rain -> 0.5
            else -> 1.0
        }
        val tools = tools(m)
        for ((g, rate) in FIELD) {
            val n = town.farmers * rate * c.pace(g) * weather * tools * m.factor(Maker.FIELDS, g)
            make(m, Site.FARMS, g, n)
            m.did(Maker.FIELDS, n)
        }
        if (weather < 1.0) m.idle[Maker.FIELDS.ordinal]++
    }

    private fun herds(m: Market, town: Town, c: Conditions) {
        val tended = if (town.farmers > 0) 1.0 else UNTENDED
        for ((g, rate) in BARN) make(m, Site.FARMS, g, town.barns * rate / FIELD_HOURS * c.pace(g) * tended * m.factor(null, g))
        for ((g, rate) in COOP) make(m, Site.FARMS, g, town.coops * rate / FIELD_HOURS * c.pace(g) * tended * m.factor(null, g))
        make(m, Site.FARMS, Good.APPLE, town.trees * TREE / FIELD_HOURS * c.pace(Good.APPLE))
    }

    private fun fish(m: Market, town: Town, c: Conditions) {
        if (town.fishers == 0) return
        if (c.thunder) {
            m.idle[Maker.BOATS.ordinal]++
            return
        }
        // No one puts out to sea with the stalls still full
        if (CATCH.keys.all { m.stock(it) >= town.target(it) * 1.5 }) return
        val weather = if (c.rain) 0.7 else 1.0
        val tools = tools(m)
        for ((g, share) in CATCH) {
            val n = town.fishers * FISH * share * weather * tools * m.factor(Maker.BOATS, g)
            make(m, Site.MARKET, g, n)
            m.did(Maker.BOATS, n)
        }
    }

    private fun mill(m: Market, town: Town) {
        // The miller is one of the farmers
        if (town.mills == 0 || town.farmers == 0 || town.bakers == 0) return
        val want = town.use(Good.FLOUR) * 3.0 - m[Site.MILL, Good.FLOUR] - m[Site.BAKEHOUSE, Good.FLOUR]
        if (want <= 0.0) return
        val can = town.mills * MILL * m.factor(Maker.MILL, Good.FLOUR)
        val grain = m[Site.MILL, Good.WHEAT]
        if (grain < can * 0.5) m.idle[Maker.MILL.ordinal]++
        val ground = use(m, Site.MILL, Good.WHEAT, minOf(can, want, grain))
        make(m, Site.MILL, Good.FLOUR, ground)
        m.did(Maker.MILL, ground)
    }

    private fun bake(m: Market, town: Town, c: Conditions) {
        if (town.bakers == 0) return
        val want = town.target(Good.BREAD) * 1.25 + (if (ahead(c.day)) town.use(Good.BREAD) else 0.0) - m.stock(Good.BREAD)
        if (want <= 0.0) return
        val can = town.bakers * BAKE * tools(m) * m.factor(Maker.OVENS, Good.BREAD)
        val flour = m[Site.BAKEHOUSE, Good.FLOUR]
        if (flour / BAKE_FLOUR < can * 0.5) m.idle[Maker.OVENS.ordinal]++
        val loaves = minOf(can, want, flour / BAKE_FLOUR)
        use(m, Site.BAKEHOUSE, Good.FLOUR, loaves * BAKE_FLOUR)
        make(m, Site.MARKET, Good.BREAD, loaves)
        m.did(Maker.OVENS, loaves)
    }

    /** The smith buys the day's iron and coal at the market first thing. */
    private fun stockForge(m: Market, town: Town) {
        if (town.smiths == 0) return
        val day = town.smiths * FORGE * FORGE_HOURS
        for ((g, per) in listOf(Good.IRON to FORGE_IRON, Good.COAL to FORGE_COAL)) {
            val want = day * per - m[Site.SMITHY, g]
            if (want > 0) m.add(Site.SMITHY, g, m.take(Site.MARKET, g, want))
        }
    }

    private fun forge(m: Market, town: Town) {
        if (town.smiths == 0) return
        if (m.stock(Good.TOOLS) >= town.target(Good.TOOLS) * 1.3) return
        val can = town.smiths * FORGE * m.factor(Maker.FORGE, Good.TOOLS)
        val tools = minOf(can, m[Site.SMITHY, Good.IRON] / FORGE_IRON, m[Site.SMITHY, Good.COAL] / FORGE_COAL)
        if (tools < can * 0.5) m.idle[Maker.FORGE.ordinal]++
        use(m, Site.SMITHY, Good.IRON, tools * FORGE_IRON)
        use(m, Site.SMITHY, Good.COAL, tools * FORGE_COAL)
        make(m, Site.MARKET, Good.TOOLS, tools)
        m.did(Maker.FORGE, tools)
    }

    private fun fletch(m: Market, town: Town) {
        if (town.fletchers == 0) return
        if (m.stock(Good.ARROW) >= town.target(Good.ARROW) * 1.3) return
        val can = town.fletchers * FLETCH * m.factor(Maker.FLETCHER, Good.ARROW)
        val arrows = minOf(can, m.stock(Good.FLINT) / FLETCH_FLINT, m.stock(Good.FEATHER) / FLETCH_FEATHER)
        if (arrows < can * 0.5) m.idle[Maker.FLETCHER.ordinal]++
        use(m, Site.MARKET, Good.FLINT, arrows * FLETCH_FLINT)
        use(m, Site.MARKET, Good.FEATHER, arrows * FLETCH_FEATHER)
        make(m, Site.MARKET, Good.ARROW, arrows)
        m.did(Maker.FLETCHER, arrows)
    }

    /** The carters' hour: grain to the mill, flour to the bakehouse, then the rest of the farms' yield to the market. */
    private fun cart(m: Market, town: Town) {
        var load = town.carters * CART * m.factor(Maker.CARTS, null)
        m.carted.fill(0.0)
        m.carts.fill(0.0)
        fun move(g: Good, from: Site, to: Site, want: Double, route: Route) {
            val n = minOf(load, want, m[from, g])
            if (n <= 0.0) return
            m.take(from, g, n)
            m.add(to, g, n)
            load -= n
            m.carted[route.ordinal] += n
            if (route == Route.MARKET) m.carts[g.ordinal] += n
            m.did(Maker.CARTS, n)
        }
        val milling = town.mills > 0 && town.farmers > 0 && town.bakers > 0
        val millDay = town.mills * MILL * MILL_HOURS
        if (milling) move(Good.WHEAT, Site.FARMS, Site.MILL, millDay - m[Site.MILL, Good.WHEAT], Route.GRAIN)
        if (town.bakers > 0) move(Good.FLOUR, Site.MILL, Site.BAKEHOUSE, town.use(Good.FLOUR) * 2.5 - m[Site.BAKEHOUSE, Good.FLOUR], Route.FLOUR)
        for (g in Good.entries) if (g != Good.WHEAT && g != Good.FLOUR) move(g, Site.FARMS, Site.MARKET, Double.MAX_VALUE, Route.MARKET)
        move(Good.WHEAT, Site.FARMS, Site.MARKET, Double.MAX_VALUE, Route.MARKET)
        // The granary feeds the mill when the fields don't
        if (milling && m[Site.MILL, Good.WHEAT] < millDay * 0.5) move(Good.WHEAT, Site.MARKET, Site.MILL, millDay * 0.5 - m[Site.MILL, Good.WHEAT], Route.GRANARY)
        if (load <= 0.0 && Good.entries.any { m[Site.FARMS, it] > CART }) m.idle[Maker.CARTS.ordinal]++
    }

    /** The innkeeper's run to the market for two days' brewing grain. */
    private fun stockTavern(m: Market, town: Town) {
        if (town.innkeepers == 0) return
        val want = town.innkeepers * BREW * BREW_HOURS * BREW_WHEAT * 2 - m[Site.TAVERN, Good.WHEAT]
        if (want > 0) m.add(Site.TAVERN, Good.WHEAT, m.take(Site.MARKET, Good.WHEAT, want))
    }

    private fun brew(m: Market, town: Town) {
        if (town.innkeepers == 0) return
        val want = town.target(Good.ALE) * 1.25 - m[Site.TAVERN, Good.ALE]
        if (want <= 0.0) return
        val can = town.innkeepers * BREW * m.factor(Maker.BREWERY, Good.ALE)
        val grain = m[Site.TAVERN, Good.WHEAT]
        if (grain / BREW_WHEAT < can * 0.5) m.idle[Maker.BREWERY.ordinal]++
        val ale = minOf(can, want, grain / BREW_WHEAT)
        use(m, Site.TAVERN, Good.WHEAT, ale * BREW_WHEAT)
        make(m, Site.TAVERN, Good.ALE, ale)
        m.did(Maker.BREWERY, ale)
    }

    /**
     * A meal for the whole town from the market: each food by habit, more of what's cheap and less of what's dear,
     * and then whatever's left for those who couldn't get what they wanted. What can't be had at all goes short.
     */
    private fun eat(m: Market, town: Town, c: Conditions) {
        var need = town.people * (if (c.feast) FEAST else 1.0)
        val weights = DoubleArray(Good.entries.size)
        for (round in 0 until 2) {
            var total = 0.0
            for (g in Good.FOODS) {
                weights[g.ordinal] = 0.0
                if (m.stock(g) < 0.01) continue
                weights[g.ordinal] = g.share * (m.price(town, g) / g.base).pow(-CHOOSY)
                total += weights[g.ordinal]
            }
            if (total <= 0.0) break
            var ate = 0.0
            for (g in Good.FOODS) {
                val w = weights[g.ordinal]
                if (w <= 0.0) continue
                val units = m.take(Site.MARKET, g, need * w / total / g.food)
                m.tally(Tally.USED, g, units)
                ate += units * g.food
            }
            need -= ate
            if (need < 0.01) return
        }
        // Gone without: counted against each food by habit
        for (g in Good.FOODS) m.tally(Tally.SHORT, g, need * g.share / g.food)
    }

    /** What the town wears out, burns and looses in an hour. */
    private fun wares(m: Market, town: Town, c: Conditions) {
        val people = town.people.toDouble()
        use(m, Site.MARKET, Good.WOOL, people * WOOL / 24)
        use(m, Site.MARKET, Good.STRING, people * STRING / 24)
        use(m, Site.MARKET, Good.LEATHER, people * LEATHER / 24)
        use(m, Site.MARKET, Good.BOTTLE, people * BOTTLES / 24)
        use(m, Site.MARKET, Good.COAL, people * HEATING * c.heating / 24)
        if (c.clock in 18..23) use(m, Site.MARKET, Good.CANDLE, people * CANDLES / 6)
        if (c.clock in 8..17) use(m, Site.MARKET, Good.ARROW, town.guards * ARROWS / 10)
        use(m, Site.MARKET, Good.TOOLS, ((town.farmers + town.fishers) * FIELD_WEAR + town.workers * WEAR) / 24)
    }

    private fun drink(m: Market, town: Town, c: Conditions) {
        if (town.innkeepers == 0) return
        use(m, Site.TAVERN, Good.ALE, town.drinkers * ALE / 5 * (if (c.feast) 3.0 else 1.0))
    }

    private fun spoil(m: Market) {
        for (g in Good.entries) {
            if (g.halfLife <= 0.0) continue
            val keep = 0.5.pow(1.0 / (24 * g.halfLife))
            for (site in Site.entries) {
                val had = m[site, g]
                if (had <= 0.0) continue
                m[site, g] = had * keep
                m.tally(Tally.SPOILED, g, had * (1 - keep))
            }
        }
    }

    /**
     * One of the day's three caravans: the first takes away half of whatever the market holds beyond twice its
     * target, and each brings in a third of what it's short of, as much as they usually carry for the town and a
     * little more, or a share of how far short it is, whichever is more.
     */
    private fun caravans(m: Market, town: Town, first: Boolean) {
        for (g in Good.entries) {
            if (!g.traded && g != Good.TOOLS) continue
            val stock = m.stock(g)
            val target = town.target(g)
            if (stock > target * GLUT) {
                if (!first) continue
                val n = (stock - target * GLUT) * 0.5
                m.take(Site.MARKET, g, n)
                m.tally(Tally.EXPORTED, g, n)
                continue
            }
            val most = maxOf((town.use(g) + town.spoils(g)) * (town.shortfall(g) + SPARE), (target - stock) * CATCH_UP, LEAST / 4) / 3
            val n = minOf(target - stock, most)
            if (n <= 0.0) continue
            m.add(Site.MARKET, g, n)
            m.tally(Tally.IMPORTED, g, n)
        }
        // A town with no mill buys its flour
        if (first && town.bakers > 0 && (town.mills == 0 || town.farmers == 0)) {
            val n = town.use(Good.FLOUR) * 2.5 - m[Site.BAKEHOUSE, Good.FLOUR]
            if (n > 0) {
                m.add(Site.BAKEHOUSE, Good.FLOUR, n)
                m.tally(Tally.IMPORTED, Good.FLOUR, n)
            }
        }
    }

    private fun newDay(m: Market, town: Town, c: Conditions, random: Random) {
        m.close(town)
        if (random.nextDouble() >= EVENT_CHANCE || m.events.size >= MAX_EVENTS) return
        val event = roll(town, c, random) ?: return
        if (m.events.any { it.kind == event.kind }) return
        m.events.add(event)
        m.say("news.guildmark.market.event.${event.kind.id}", event.good)
    }

    /** Something that might happen to [town]'s trade from [c]'s hour: a kind it has makers for, picked by how often each comes. */
    fun roll(town: Town, c: Conditions, random: Random): Event? {
        val choices = ArrayList<Pair<EventKind, Double>>()
        if (town.farmers > 0) {
            choices.add(EventKind.BLIGHT to 3.0)
            choices.add(EventKind.BUMPER to 2.0)
        }
        if (town.barns > 0) choices.add(EventKind.MURRAIN to 1.5)
        if (town.coops > 0) choices.add(EventKind.FOWL_PEST to 1.0)
        if (town.mills > 0 && town.farmers > 0) choices.add(EventKind.MILL_BROKEN to 1.0)
        if (town.bakers > 0) choices.add(EventKind.OVEN_CRACKED to 1.0)
        if (town.fishers > 0) {
            choices.add(EventKind.GOOD_CATCH to 1.5)
            choices.add(EventKind.POOR_CATCH to 1.5)
        }
        choices.add(EventKind.FEVER to 0.7)
        var pick = random.nextDouble() * choices.sumOf { it.second }
        val kind = choices.firstOrNull { (_, w) -> (pick < w).also { pick -= w } }?.first ?: return null
        fun days(from: Int, to: Int) = from + random.nextInt(to - from + 1)
        val crops = FIELD.keys.toList()
        val (good, factor, length) = when (kind) {
            EventKind.BLIGHT -> Triple(crops[random.nextInt(crops.size)], 0.25, days(3, 5))
            EventKind.BUMPER -> Triple(crops[random.nextInt(crops.size)], 1.6, days(3, 5))
            EventKind.MURRAIN -> Triple(null, 0.4, days(4, 6))
            EventKind.FOWL_PEST -> Triple(null, 0.3, days(3, 5))
            EventKind.MILL_BROKEN -> Triple(null, 0.0, days(1, 2))
            EventKind.OVEN_CRACKED -> Triple(null, 0.5, days(1, 2))
            EventKind.GOOD_CATCH -> Triple(null, 1.8, days(2, 3))
            EventKind.POOR_CATCH -> Triple(null, 0.3, days(2, 4))
            EventKind.FEVER -> Triple(null, 0.75, days(3, 4))
        }
        return Event(kind, good, factor, c.hour, c.hour + length * 24L)
    }
}
