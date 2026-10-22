package io.github.vaspyyy.guildmark.trade

import io.github.vaspyyy.guildmark.economy.CaravanData
import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.economy.Economy
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Market
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.RoadPeril
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.fortune.FortuneReasons
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How well stocked a town is of a good, against what it likes to keep (living-world/trader/design.md, Part 1): the five bands the
 * Word sorts every good into. Scarce is under half of the target, short under nine tenths, fair up to one and a tenth, plenty up to one
 * and four tenths, and glut from there.
 */
enum class Band(val id: String) {
    SCARCE("scarce"),
    SHORT("short"),
    FAIR("fair"),
    PLENTY("plenty"),
    GLUT("glut"),
    ;

    /** Short or worse: what a red chip stands for. */
    val wanting: Boolean get() = this <= SHORT

    companion object {
        /** The band for a stock that is [ratio] of its target. */
        fun of(ratio: Double): Band = when {
            ratio < 0.5 -> SCARCE
            ratio < 0.9 -> SHORT
            ratio < 1.1 -> FAIR
            ratio < 1.4 -> PLENTY
            else -> GLUT
        }
    }
}

/** A price against the usual one, in three bands: the stalls say why a price is off once it is a fifth either way ([Trading.OFF]). */
enum class PriceBand(val id: String) {
    CHEAP("cheap"),
    USUAL("usual"),
    DEAR("dear"),
    ;

    companion object {
        private const val OFF = 1.2

        /** The band for a price that is [ratio] of the usual one. */
        fun of(ratio: Double): PriceBand = when {
            ratio >= OFF -> DEAR
            ratio <= 1.0 / OFF -> CHEAP
            else -> USUAL
        }
    }
}

/**
 * One capital as it stood at one moment (living-world/trader/design.md, Part 1: "the Word"): for each traded good how much its market
 * held, what the town likes to keep, and what that made the price; the market's trouble and luck then; the worst road into it; and
 * the haul tickets it had out. It is a copy: nothing in it changes after it is taken, and it is kept as it was, whether by the
 * capital's ring of twelve ([TradeWord]) or in a trader's Tally ([TradeBooks.Book.sights]), so a snapshot never says more than it knew.
 *
 * The numbers are kept as the market itself keeps them, enough to work a price out at any other stock (the Tally's Routes page
 * prices a lot there that nobody has bought yet): the stock, the target, the supply the price goes by (what the market held lately and
 * what has changed hands since, [Market.price]), the freight and the outlook that lean on the price, and what the town's wagons
 * usually carry of the good (for the room a dealer's lot has, [Routes]).
 *
 * [tick] is the overworld's clock, in game ticks, at the moment the state held: a snapshot is stamped with the hour of the market it
 * was taken from and a sighting with the moment the player stood there.
 */
class Sight(
    val tick: Long,
    private val data: FloatArray,
    val events: List<Note>,
    val road: Road?,
    val tickets: List<Ticket>,
) {
    /** Trouble or luck on the market then: the id of its [io.github.vaspyyy.guildmark.economy.EventKind] ("blight", "fever"...) or "feast", the id of the good it fell on ("" for none), and the market hour it was to last until. */
    class Note(val kind: String, val good: String, val until: Long)

    /** The worst road into the town: the stage it was at ([RoadPeril.Stage.ordinal], 0 open to 3 closed) and the side it comes in from ("n", "e", "s", "w" or "any"). */
    class Road(val stage: Int, val side: String)

    /** A haul ticket it had out: number [id], for [units] of [good] to its [shop], paying [pay] Marks in all, due at market hour [due]. */
    class Ticket(val id: Int, val good: Good, val units: Int, val pay: Int, val shop: Shop, val due: Long, val kind: HaulKind) {
        /** What a unit of it pays. */
        val each: Double get() = if (units <= 0) 0.0 else pay.toDouble() / units
    }

    private fun at(g: Good, field: Int): Double {
        val i = INDEX[g.ordinal]
        return if (i < 0) 0.0 else data[i * FIELDS + field].toDouble()
    }

    /** How many it held. */
    fun stock(g: Good): Double = at(g, STOCK)

    /** How many it likes to keep. */
    fun target(g: Good): Double = at(g, TARGET)

    /** What its price went by: what the market held lately, moved by what had changed hands since ([Market.price]). */
    fun supply(g: Good): Double = at(g, SUPPLY)

    /** What bringing the good in added to the price, and what trouble or luck under way did to it. */
    fun freight(g: Good): Double = at(g, FREIGHT)
    fun outlook(g: Good): Double = at(g, OUTLOOK)

    /** What the town's wagons usually carry of it in a day and a little more ([Economy.bring]): the part of a wagon's load that does not depend on the stock. */
    fun carry(g: Good): Double = at(g, CARRY)

    /** The stock as a share of the target (1 when it holds just what it likes to keep). */
    fun ratio(g: Good): Double = stock(g) / max(target(g), 1e-6)

    /** The band the good's stock was in. */
    fun band(g: Good): Band = Band.of(ratio(g))

    /** What a unit went for while the supply stood at [supply], as [Market.price] works it out. */
    fun price(g: Good, supply: Double = supply(g)): Double {
        val target = target(g)
        val ratio = sqrt(target / max(supply, target * 0.01)).coerceIn(Market.LOW, Market.HIGH)
        return g.base * (ratio * freight(g) * outlook(g)).coerceIn(Market.LOW, Market.HIGHEST)
    }

    /** The usual price of a unit: its base and what bringing it in costs, as the stalls reckon it ([Trading.quote]). */
    fun usual(g: Good): Double = g.base * freight(g)

    /** The price band: what it went for against the usual price. */
    fun priceBand(g: Good): PriceBand = PriceBand.of(price(g) / max(usual(g), 1e-9))

    /** The goods it was short of, the scarcest first (at most [most]). */
    fun shorts(most: Int = Int.MAX_VALUE): List<Good> = Good.TRADED.filter { band(it).wanting }.sortedBy { ratio(it) }.take(most)

    /** The goods it had in glut, the most glutted first (at most [most]). */
    fun gluts(most: Int = Int.MAX_VALUE): List<Good> = Good.TRADED.filter { band(it) == Band.GLUT }.sortedByDescending { ratio(it) }.take(most)

    /** Its tickets for [good]. */
    fun ticketsFor(good: Good): List<Ticket> = tickets.filter { it.good == good }

    /** How old it is, in hours, at the overworld's clock [now] (never less than none: a clock set back does not make news from the future). */
    fun age(now: Long): Double = max(0L, now - tick) / HOUR

    /** The same sight, stamped as of [tick] instead (for the commands that set a sight's age). */
    fun stamped(tick: Long): Sight = Sight(tick, data, events, road, tickets)

    /** The same sight with [tickets] in place of its own (the Tally's live look at a town, where the tickets are the town's now). */
    fun withTickets(tickets: List<Ticket>): Sight = Sight(tick, data, events, road, tickets)

    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putLong("tick", tick)
        tag.putInt("goods", GOODS.size)
        tag.put("data", IntArrayTag(IntArray(data.size) { java.lang.Float.floatToIntBits(data[it]) }))
        if (events.isNotEmpty()) {
            val list = ListTag()
            for (e in events) {
                val t = CompoundTag()
                t.putString("kind", e.kind)
                if (e.good.isNotEmpty()) t.putString("good", e.good)
                t.putLong("until", e.until)
                list.add(t)
            }
            tag.put("events", list)
        }
        if (road != null) {
            tag.putInt("stage", road.stage)
            tag.putString("side", road.side)
        }
        if (tickets.isNotEmpty()) {
            val list = ListTag()
            for (k in tickets) {
                val t = CompoundTag()
                t.putInt("id", k.id)
                t.putString("good", k.good.id)
                t.putInt("units", k.units)
                t.putInt("pay", k.pay)
                t.putString("shop", k.shop.id)
                t.putLong("due", k.due)
                t.putString("kind", k.kind.id)
                list.add(t)
            }
            tag.put("tickets", list)
        }
        return tag
    }

    companion object {
        /** The goods a sight is kept of, in the order its numbers are kept in. A sight kept for another list is let go (it is only news). */
        val GOODS: List<Good> = Good.TRADED

        /** Each good's place among [GOODS] by its ordinal, -1 for one the Word does not keep. */
        private val INDEX = IntArray(Good.entries.size) { -1 }.also { index -> for ((i, g) in GOODS.withIndex()) index[g.ordinal] = i }

        private const val HOUR = 1000.0

        private const val STOCK = 0
        private const val TARGET = 1
        private const val SUPPLY = 2
        private const val FREIGHT = 3
        private const val OUTLOOK = 4
        private const val CARRY = 5
        private const val FIELDS = 6

        /** [entry]'s market as it stands, stamped as of [tick] (a snapshot of the hour, or a sighting just now); [tickets] are what the town has out. */
        fun of(level: ServerLevel, entry: Markets.Entry, tick: Long, tickets: List<Ticket>): Sight {
            val m = entry.market
            val town = entry.town
            val data = FloatArray(GOODS.size * FIELDS)
            for ((i, g) in GOODS.withIndex()) {
                val at = i * FIELDS
                data[at + STOCK] = m.stock(g).toFloat()
                data[at + TARGET] = town.target(g).toFloat()
                data[at + SUPPLY] = max(0.0, m.held[g.ordinal] + m.stock(g) - m.mark[g.ordinal]).toFloat()
                data[at + FREIGHT] = m.freight(g).toFloat()
                data[at + OUTLOOK] = m.outlook(g).toFloat()
                data[at + CARRY] = ((town.use(g) + town.spoils(g)) * (town.shortfall(g) + Economy.SPARE)).toFloat()
            }
            val events = ArrayList<Note>()
            for (e in m.events) events.add(Note(e.kind.id, e.good?.id ?: "", e.until))
            if (entry.felt?.feast == true) events.add(Note(FEAST, "", m.hour + 1))
            return Sight(tick, data, events, road(level, m.capital), tickets)
        }

        /** The id a feast day is noted by, beside the market's own events. */
        const val FEAST = "feast"

        /** The worst road into the capital whose heart is [capital], if the roads are being kept; every road open and from no side in particular if none has been troubled. */
        fun road(level: ServerLevel, capital: Long): Road? {
            if (!Caravans.enabled()) return null
            val worst = CaravanData.get(level).roads.filter { it.capital == capital }.maxByOrNull { it.peril }
                ?: return Road(RoadPeril.Stage.OPEN.ordinal, "any")
            return Road(RoadPeril.Stage.of(worst.peril).ordinal, FortuneReasons.side(capital, worst.road))
        }

        fun fromTag(tag: CompoundTag): Sight? {
            if (tag.getIntOr("goods", -1) != GOODS.size) return null
            val raw = tag.getIntArray("data").orElse(null) ?: return null
            if (raw.size != GOODS.size * FIELDS) return null
            val data = FloatArray(raw.size) { java.lang.Float.intBitsToFloat(raw[it]) }
            val events = ArrayList<Note>()
            val list = tag.getListOrEmpty("events")
            for (i in 0 until list.size) {
                val t = list.getCompoundOrEmpty(i)
                events.add(Note(t.getStringOr("kind", ""), t.getStringOr("good", ""), t.getLongOr("until", 0L)))
            }
            val road = if (tag.contains("stage")) Road(tag.getIntOr("stage", 0).coerceIn(0, RoadPeril.Stage.entries.size - 1), tag.getStringOr("side", "any")) else null
            val tickets = ArrayList<Ticket>()
            val held = tag.getListOrEmpty("tickets")
            for (i in 0 until held.size) {
                val t = held.getCompoundOrEmpty(i)
                val good = Good.byId(t.getStringOr("good", "")) ?: continue
                val shop = Shop.byId(t.getStringOr("shop", "")) ?: continue
                val kind = HaulKind.byId(t.getStringOr("kind", "")) ?: HaulKind.PARCEL
                tickets.add(Ticket(t.getIntOr("id", 0), good, t.getIntOr("units", 0), t.getIntOr("pay", 0), shop, t.getLongOr("due", 0L), kind))
            }
            return Sight(tag.getLongOr("tick", 0L), data, events, road, tickets)
        }
    }
}
