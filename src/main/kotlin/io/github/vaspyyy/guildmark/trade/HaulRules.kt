package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.Carriage
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.Orders
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.Tally
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The rules of haul tickets (living-world/trader/design.md, Part 2) as plain functions of the market's and the town's own numbers:
 * what a capital short of something asks for, how many, and at what price a unit; what a ticket pays in whole Marks however it is split
 * between deliveries; what a booking holds and gives back; how many tickets are posted a day. [Hauls] is the glue that keeps tickets,
 * runs the hours and talks to players; the numbers are here so that they can be read, and run outside the game, in one place.
 *
 * Every Mark a ticket ever pays is worked out here, and none can be more than [pay] for the ticket (see [owed]).
 */
object HaulRules {
    /** A ticket is posted only while the market holds under this share of what the town likes to keep. */
    const val BELOW = 0.9

    /** A source is another capital with at least [SOURCE_LEAST] units of a good above [KEEPS] of its own target; the ticket asks for at most [CUT] of the nearest one's spare. */
    const val SOURCE_LEAST = 16.0
    const val KEEPS = 0.25
    const val CUT = 0.6

    /** The least and most a ticket asks for, and the most a parcel is (anything bigger is a consignment). */
    const val LEAST = 16
    const val MOST = 128
    const val PARCEL_MOST = 48

    /** Days of the town's need that a ticket asks for, by Orders' own rule of sizes ([Orders.amount]). */
    const val ASK_DAYS = 3

    /** Orders' own bar for a good worth asking for: its need over four days and what is short must come to this many units (Orders.kt, `LEAST_NEED` and `DAYS_MOST`). */
    private const val LEAST_NEED = 8.0
    private const val NEED_DAYS = 4

    /** A good is bought up when today's buying and half of yesterday's reach this share of its target (Trading.kt `A_LOT`): nothing is posted for it then. */
    const val BOUGHT_UP = 0.25

    /** Of a good's base price and the road's cost from the nearest source ([Carriage.FAR] at most), the fee a unit carries on top of the pay. */
    const val FEE = 0.15

    /** A unit of a parcel pays the usual price times this plus [SHORTNESS_PAY] times how short the town is, a consignment or a feast likewise, never over [CAP] times the usual. */
    const val PARCEL_PAY = 1.2
    const val CONSIGNMENT_PAY = 1.35
    const val SHORTNESS_PAY = 0.5
    const val CAP = 2.4

    /** A relief unit pays twice the usual price and the fee, never over three times the usual. */
    const val RELIEF_PAY = 2.0
    const val RELIEF_CAP = 3.0

    /** A staple the town went short of by this share of a day's use yesterday gets a relief ticket for a day and a half of its use, in lots of 16, up to [RELIEF_MOST]. */
    const val RELIEF_SHORT = 0.05
    const val RELIEF_DAYS = 1.5
    const val RELIEF_MOST = 256

    /** Hours a ticket stays out: a parcel or a consignment four days, a relief three. */
    const val PARCEL_HOURS = 96L
    const val RELIEF_HOURS = 72L

    /** Hours a booking stands without a quarter of its goods bought, and the share of its goods that is. */
    const val BOOKING_HOURS = 24L
    const val QUARTER = 0.25

    /** Hours a Factor has first call on a relief or a feast ticket before the others are shown it. */
    const val FIRST_HOURS = 24L

    /** The most tickets one capital has out at once. */
    const val OPEN_MOST = 8

    /** Hours after a delivery that a carrier is counted among those at work, and the rise in the posting rate for each beyond the first. */
    const val ACTIVE_HOURS = 48L
    const val CARRIER_RATE = 0.5

    /** A good a capital would ask to have brought: [need] a day, [short] of what it keeps, worth [weight] (as Orders weighs a want). */
    class Want(val good: Good, val need: Double, val short: Double, val weight: Double)

    /** A capital with some of a good to spare: [distance] blocks off, with [spare] units above a quarter of its target. */
    class Source(val entry: Markets.Entry, val distance: Double, val spare: Double)

    /** A ticket to be posted: [units] of [good] at [each] a unit, of the given [kind], out for [hours]. */
    class Plan(val good: Good, val units: Int, val each: Double, val kind: HaulKind, val hours: Long, val distance: Double)

    // ---- Prices ----

    /** What carrying a unit costs along the road of [distance] blocks, of its base price (the freight's own measure). */
    fun carry(distance: Double): Double = min(Carriage.FAR, Carriage.PER_BLOCK * distance * Carriage.WINDING)

    /** The fee a unit of a good with the base price [base] carries on top, from a source [distance] blocks off. */
    fun fee(base: Double, distance: Double): Double = FEE * base * carry(distance)

    /** How short of its target a market is, 0 at the target and 1 with none. */
    fun shortness(stock: Double, target: Double): Double = if (target <= 0.0) 0.0 else (1.0 - stock / target).coerceIn(0.0, 1.0)

    /** What a unit of a ticket of this [kind] pays: [usual] is the good's base times the market's freight, [shortness] and [fee] as above. */
    fun each(kind: HaulKind, usual: Double, shortness: Double, fee: Double): Double = when (kind) {
        HaulKind.RELIEF -> min(RELIEF_PAY * usual + fee, RELIEF_CAP * usual)
        HaulKind.PARCEL -> min(usual * (PARCEL_PAY + SHORTNESS_PAY * shortness) + fee, usual * CAP)
        HaulKind.CONSIGNMENT, HaulKind.FEAST -> min(usual * (CONSIGNMENT_PAY + SHORTNESS_PAY * shortness) + fee, usual * CAP)
    }

    /** The kind a ticket for [units] is when nothing else says: a parcel up to 48, a consignment above. */
    fun kindFor(units: Int): HaulKind = if (units <= PARCEL_MOST) HaulKind.PARCEL else HaulKind.CONSIGNMENT

    /** Hours a ticket of this [kind] stays out. */
    fun hours(kind: HaulKind): Long = if (kind == HaulKind.RELIEF) RELIEF_HOURS else PARCEL_HOURS

    // ---- Pay ----

    /** The whole Marks a ticket for [units] at [each] a unit pays in all: rounded up, and never less than a Mark. */
    fun pay(each: Double, units: Int): Int = if (units <= 0) 0 else max(1, ceil(each * units - 1e-9).toInt())

    /**
     * The whole Marks owed in all once [done] of the ticket's [units] are in: the Marks of the units, rounded down, until the last unit
     * is in, which brings it up to [pay]. However the units come in, a few at a time or all at once, from one carrier or from several,
     * what has been paid never goes over [pay] and the whole comes to exactly [pay]; a delivery is paid what this comes to now less what
     * it came to before. [each] may be raised by a perk (the Trade tree's `HAUL_PAY`): the pay is then worked out on the raised price.
     */
    fun owed(each: Double, units: Int, done: Int): Int {
        if (done <= 0 || units <= 0) return 0
        val all = pay(each, units)
        return if (done >= units) all else min(all, floor(each * done + 1e-9).toInt())
    }

    /** The deposit a booking of [units] at [each] holds: [rate] of its pay, at least a Mark (none if the rate is 0). */
    fun deposit(rate: Double, each: Double, units: Int): Int = if (rate <= 0.0 || units <= 0) 0 else max(1, ceil(rate * each * units - 1e-9).toInt())

    /** Of a booking's [deposit], how much is given back once [done] of its [units] are in: all of it at the last unit, the share of the units before that rounded down. */
    fun repayable(deposit: Int, units: Int, done: Int): Int = when {
        done >= units -> deposit
        done <= 0 -> 0
        else -> floor(deposit.toDouble() * done / units + 1e-9).toInt()
    }

    /** The units of a booking of [units] that must be bought, at a market far enough off, for the booking to stand past its first day. */
    fun quarter(units: Int): Int = max(1, ceil(units * QUARTER - 1e-9).toInt())

    // ---- The rate ----

    /** The tickets a capital posts a day: [base] plus half a ticket for each carrier at work beyond the first, up to [ceiling] (never under [base]). */
    fun rate(base: Double, ceiling: Double, carriers: Int): Double = min(max(base, ceiling), base + CARRIER_RATE * max(0, carriers - 1))

    /** How many tickets a rate of [rate] posts when a roll of [roll] (0 up to 1) is made: the whole part, and one more with the chance of the fraction. */
    fun count(rate: Double, roll: Double): Int {
        val whole = floor(rate)
        return whole.toInt() + if (roll < rate - whole) 1 else 0
    }

    // ---- The haul room ----

    /**
     * The room a dealer's lot is given on top of a market's usual: [days] days of three wagons' loads, a wagon's load being [load] (see
     * [io.github.vaspyyy.guildmark.economy.Economy.load]); none once the market holds [stock] up to its [target].
     */
    fun room(days: Int, target: Double, stock: Double, load: Double): Double = if (days <= 0 || stock >= target) 0.0 else days * 3.0 * max(0.0, load)

    // ---- What a capital asks for ----

    /** Whether [m]'s players have bought [good] up: today's buying and half of yesterday's reach a quarter of what the town keeps. */
    fun boughtUp(entry: Markets.Entry, good: Good): Boolean {
        val m = entry.market
        return m.today(Tally.BOUGHT, good) + 0.5 * m.yesterday(Tally.BOUGHT, good) >= BOUGHT_UP * entry.town.target(good)
    }

    /** The goods some business of a town with these hands would ask for, which is what a ticket may be for (a business asks for nothing it has no hands for). */
    fun asked(entry: Markets.Entry): List<Good> {
        val all = HashSet<Good>()
        for (shop in Shop.entries) all.addAll(Orders.asks(shop, entry.town))
        return Good.TRADED.filter { it in all }
    }

    /**
     * What [entry]'s town would ask to have brought just now, as its businesses would (Orders' own wants, with the rule that a business
     * has an order out dropped): goods under [BELOW] of their target, no good that luck is bringing plenty of, and none worth less than
     * eight units of need and shortfall over four days.
     */
    fun wants(entry: Markets.Entry): List<Want> {
        val m = entry.market
        val town = entry.town
        val out = ArrayList<Want>()
        for (good in asked(entry)) {
            if (m.events(good).any { it.factor > 1.0 }) continue
            val target = town.target(good)
            val stock = m.stock(good)
            if (stock >= target * BELOW) continue
            val need = (town.use(good) + town.spoils(good)) * max(m.brought[good.ordinal], town.shortfall(good))
            val short = max(target - stock, 0.0)
            if (need * NEED_DAYS + short < LEAST_NEED) continue
            out.add(Want(good, need, short, (need + short / 3.0) * m.price(town, good)))
        }
        return out
    }

    /** The nearest other capital with [good] to spare, if there is one. */
    fun source(here: Markets.Entry, others: Collection<Markets.Entry>, good: Good): Source? {
        var best: Source? = null
        for (o in others) {
            if (o === here) continue
            val spare = o.market.stock(good) - KEEPS * o.town.target(good)
            if (spare < SOURCE_LEAST) continue
            val distance = Hauls.distance(o.capital, here.capital)
            if (best == null || distance < best.distance) best = Source(o, distance, spare)
        }
        return best
    }

    /** How many units a ticket for [want] asks for when the nearest source has [spare]: Orders' own size, cut to [CUT] of the spare and to [MOST]; 0 if that is under [LEAST]. */
    fun size(want: Want, target: Double, spare: Double): Int {
        val asked = Orders.amount(want.need, want.short, target, ASK_DAYS)
        val most = min(asked.toDouble(), min(CUT * spare, MOST.toDouble()))
        return Orders.SIZES.lastOrNull { it <= most } ?: 0
    }

    /**
     * The tickets [entry]'s capital posts at a dawn, [count] draws at most (fewer when fewer goods qualify): each draw picks a good by
     * what it is worth to the town among those wanted, short of their target, with no ticket out ([out]), a source to spare and not bought
     * up, and asks for as many as [size] allows; a good the size drops (under 16) is a draw spent. [others] are the other capitals' markets.
     */
    fun plan(entry: Markets.Entry, others: Collection<Markets.Entry>, out: Collection<Good>, count: Int, random: Random): List<Plan> {
        val m = entry.market
        val town = entry.town
        val pool = ArrayList<Want>()
        val sources = HashMap<Good, Source>()
        for (w in wants(entry)) {
            if (w.good in out || boughtUp(entry, w.good)) continue
            sources[w.good] = source(entry, others, w.good) ?: continue
            pool.add(w)
        }
        val posted = ArrayList<Plan>()
        repeat(count) {
            if (pool.isEmpty()) return posted
            val want = Orders.pick(pool, random) { it.weight }
            pool.remove(want)
            val src = sources.getValue(want.good)
            val target = town.target(want.good)
            val units = size(want, target, src.spare)
            if (units < LEAST) return@repeat
            val kind = kindFor(units)
            val usual = want.good.base * m.freight(want.good)
            val price = each(kind, usual, shortness(m.stock(want.good), target), fee(want.good.base, src.distance))
            posted.add(Plan(want.good, units, price, kind, hours(kind), src.distance))
        }
        return posted
    }

    /**
     * The relief tickets [entry]'s capital posts at a dawn: one for each staple (a food or coal) it was short of by [RELIEF_SHORT] of a
     * day's use yesterday, that has no relief ticket out ([out]) and none posted today ([today]), has a source and is not bought up.
     */
    fun relief(entry: Markets.Entry, others: Collection<Markets.Entry>, out: Collection<Good>, today: Collection<Good>): List<Plan> {
        val m = entry.market
        val town = entry.town
        val posted = ArrayList<Plan>()
        for (good in Good.TRADED) {
            if (good.food <= 0.0 && good != Good.COAL) continue
            val use = town.use(good)
            if (use <= 0.0 || good in out || good in today) continue
            if (m.yesterday(Tally.SHORT, good) < RELIEF_SHORT * use || boughtUp(entry, good)) continue
            val src = source(entry, others, good) ?: continue
            val units = min(RELIEF_MOST, ceil(RELIEF_DAYS * use / LEAST).toInt() * LEAST)
            val usual = good.base * m.freight(good)
            posted.add(Plan(good, units, each(HaulKind.RELIEF, usual, 0.0, fee(good.base, src.distance)), HaulKind.RELIEF, RELIEF_HOURS, src.distance))
        }
        return posted
    }
}
