package io.github.vaspyyy.guildmark.trade

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Shop
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * One taker's hold on a ticket: [units] of it booked by [taker] (a player, or a House when [house] is set; [name] is what the ticket shows),
 * at market hour [at]. [done] of them are in. The booking held a [deposit] of Marks when it was made, of which [repaid] have been given
 * back as units came in (see [HaulRules.repayable]); what is left of it is lost if the booking ends short. It [stands] once the booker has bought
 * a quarter of its goods far enough away, or when made by a House; until then it lapses [HaulRules.BOOKING_HOURS] after [at].
 */
class Booking(val taker: UUID, val house: Boolean, var name: String, var units: Int, var at: Long) {
    var done = 0
    var deposit = 0
    var repaid = 0
    var stands = false

    /** How many are still to come in. */
    val left: Int get() = (units - done).coerceAtLeast(0)

    /** The taker as the listeners know them. */
    fun who(): Taker = if (house) Taker.House(taker) else Taker.Player(taker)
}

/**
 * A haul ticket at a capital: a wish for [units] of [good] to be brought to [shop] at [each] Marks a unit (the pay is fixed when it is posted;
 * see [HaulRules.pay] for what that comes to in whole Marks), of the given [kind], posted at market hour [posted] and out until [due]. [done]
 * have been brought in so far by anyone, and [bookings] are the takers' holds on the rest.
 */
class Ticket(val id: Int, val shop: Shop, val good: Good, var units: Int, val each: Double, val kind: HaulKind, var posted: Long, var due: Long) {
    var done = 0
    val bookings = ArrayList<Booking>()

    /** Whether the ticket was posted by hand or for a festival or another part (see [Hauls.postFeast]): the town's stock reaching its target does not withdraw it, only its day. */
    var fixed = false

    /** The Marks the whole ticket pays. */
    val pay: Int get() = HaulRules.pay(each, units)

    /** How many are still to come in, booked or not. */
    val left: Int get() = (units - done).coerceAtLeast(0)

    /** How many are booked and still to come in. */
    val booked: Int get() = bookings.sumOf { it.left }

    /** How many nobody has booked. */
    val open: Int get() = (left - booked).coerceAtLeast(0)
}

/** A ticket filled at a capital, at market [hour]: [units] of [good] brought to [shop] by [who] for [pay] Marks, of the given [kind]; for the crier's thanks (see [Hauls.fills]). */
class HaulFill(var hour: Long, val good: Good, val shop: Shop, val units: Int, val who: String, val kind: HaulKind, val pay: Int)

/** [player] let a booking at [shop] lapse at market [hour]; its keeper has a word for them next time they step up. */
class HaulLapse(val player: UUID, val shop: Shop, var hour: Long)

/**
 * One capital's tickets: [tickets] out, the market hour last worked out ([hour]), the tickets filled lately ([fills]) and the market hour each
 * staple last had a relief ticket posted ([relief], by the good's id), so that a good has one a day at most, and the bookings let lapse lately
 * ([lapses], for the keepers' word). Every hour kept here moves with the
 * clock when the days stand still or the clock is set back, as an order's do (see [Hauls]).
 */
class HaulBook(val capital: Long) {
    val tickets = ArrayList<Ticket>()
    var hour = Long.MIN_VALUE
    val fills = ArrayList<HaulFill>()
    val relief = HashMap<String, Long>()
    val lapses = ArrayList<HaulLapse>()

    fun find(id: Int): Ticket? = tickets.firstOrNull { it.id == id }
}

/**
 * Every capital's haul tickets, their bookings and fills, kept with the overworld as `guildmark:hauls` (living-world/trader/design.md, Part 2).
 * Every field is optional in what is saved, so a world saved before any of it loads with none. Whoever changes anything here calls [changed].
 *
 * Besides the books: the number for the next ticket ([next]), the market hour each player last filled one ([active], which sets how many carriers are
 * at work and so the rate tickets are posted at), the Marks owed to players who were not there when a booking of theirs was let go ([owed]; they are
 * paid when they next join) and the rate an operator has set with `trade hauls rate` ([rate], under 0 for none: the setting rules).
 */
class HaulData(
    private val books: MutableMap<Long, HaulBook> = HashMap(),
    private var next: Int = 1,
    val active: MutableMap<UUID, Long> = HashMap(),
    val owed: MutableMap<UUID, Int> = HashMap(),
    var rate: Double = -1.0,
) : SavedData() {
    operator fun get(capital: Long): HaulBook? = books[capital]

    /** [capital]'s book, started if it has none yet. */
    fun book(capital: Long): HaulBook = books.getOrPut(capital) { HaulBook(capital) }

    fun all(): Collection<HaulBook> = books.values

    /** The ticket numbered [id], and the book it is in. */
    fun find(id: Int): Pair<HaulBook, Ticket>? {
        for (book in books.values) book.find(id)?.let { return book to it }
        return null
    }

    /** A number for a new ticket. */
    fun number(): Int {
        setDirty()
        return next++
    }

    fun changed() = setDirty()

    companion object {
        private val BOOKING: Codec<Booking> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("taker").forGetter(Booking::taker),
                Codec.BOOL.optionalFieldOf("house", false).forGetter(Booking::house),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Booking::name),
                Codec.INT.fieldOf("units").forGetter(Booking::units),
                Codec.LONG.fieldOf("at").forGetter(Booking::at),
                Codec.INT.optionalFieldOf("done", 0).forGetter(Booking::done),
                Codec.INT.optionalFieldOf("deposit", 0).forGetter(Booking::deposit),
                Codec.INT.optionalFieldOf("repaid", 0).forGetter(Booking::repaid),
                Codec.BOOL.optionalFieldOf("stands", false).forGetter(Booking::stands),
            ).apply(i) { taker, house, name, units, at, done, deposit, repaid, stands ->
                Booking(taker, house, name, units, at).also {
                    it.done = done
                    it.deposit = deposit
                    it.repaid = repaid
                    it.stands = stands
                }
            }
        }

        private val TICKET: Codec<Ticket> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Ticket::id),
                Codec.STRING.fieldOf("shop").forGetter { it.shop.id },
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.INT.fieldOf("units").forGetter(Ticket::units),
                Codec.DOUBLE.fieldOf("each").forGetter(Ticket::each),
                Codec.STRING.optionalFieldOf("kind", HaulKind.PARCEL.id).forGetter { it.kind.id },
                Codec.LONG.fieldOf("posted").forGetter(Ticket::posted),
                Codec.LONG.fieldOf("due").forGetter(Ticket::due),
                Codec.INT.optionalFieldOf("done", 0).forGetter(Ticket::done),
                BOOKING.listOf().optionalFieldOf("bookings", listOf()).forGetter { it.bookings },
                Codec.BOOL.optionalFieldOf("fixed", false).forGetter(Ticket::fixed),
            ).apply(i) { id, shop, good, units, each, kind, posted, due, done, bookings, fixed ->
                // A ticket for a shop or good no longer known is let go with the rest of its kind: it is read as the hall's wheat and lapses in its time
                Ticket(id, Shop.byId(shop) ?: Shop.HALL, Good.byId(good) ?: Good.WHEAT, units, each, HaulKind.byId(kind) ?: HaulKind.PARCEL, posted, due).also {
                    it.done = done
                    it.bookings.addAll(bookings)
                    it.fixed = fixed
                }
            }
        }

        private val FILL: Codec<HaulFill> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("hour").forGetter(HaulFill::hour),
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.STRING.fieldOf("shop").forGetter { it.shop.id },
                Codec.INT.fieldOf("units").forGetter(HaulFill::units),
                Codec.STRING.optionalFieldOf("who", "").forGetter(HaulFill::who),
                Codec.STRING.optionalFieldOf("kind", HaulKind.PARCEL.id).forGetter { it.kind.id },
                Codec.INT.optionalFieldOf("pay", 0).forGetter(HaulFill::pay),
            ).apply(i) { hour, good, shop, units, who, kind, pay ->
                HaulFill(hour, Good.byId(good) ?: Good.WHEAT, Shop.byId(shop) ?: Shop.HALL, units, who, HaulKind.byId(kind) ?: HaulKind.PARCEL, pay)
            }
        }

        private val LAPSE: Codec<HaulLapse> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(HaulLapse::player),
                Codec.STRING.fieldOf("shop").forGetter { it.shop.id },
                Codec.LONG.fieldOf("hour").forGetter(HaulLapse::hour),
            ).apply(i) { player, shop, hour -> HaulLapse(player, Shop.byId(shop) ?: Shop.HALL, hour) }
        }

        private val BOOK: Codec<HaulBook> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(HaulBook::capital),
                TICKET.listOf().optionalFieldOf("tickets", listOf()).forGetter { it.tickets },
                Codec.LONG.optionalFieldOf("hour", Long.MIN_VALUE).forGetter(HaulBook::hour),
                FILL.listOf().optionalFieldOf("fills", listOf()).forGetter { it.fills },
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("relief", mapOf()).forGetter { it.relief },
                LAPSE.listOf().optionalFieldOf("lapses", listOf()).forGetter { it.lapses },
            ).apply(i) { capital, tickets, hour, fills, relief, lapses ->
                HaulBook(capital).also {
                    it.tickets.addAll(tickets)
                    it.hour = hour
                    it.fills.addAll(fills)
                    it.relief.putAll(relief)
                    it.lapses.addAll(lapses)
                }
            }
        }

        private val CODEC: Codec<HaulData> = RecordCodecBuilder.create { i ->
            i.group(
                BOOK.listOf().optionalFieldOf("capitals", listOf()).forGetter { it.books.values.toList() },
                Codec.INT.optionalFieldOf("next", 1).forGetter { it.next },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).optionalFieldOf("active", mapOf()).forGetter { it.active },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("owed", mapOf()).forGetter { it.owed },
                Codec.DOUBLE.optionalFieldOf("rate", -1.0).forGetter { it.rate },
            ).apply(i) { list, next, active, owed, rate ->
                HaulData(list.associateBy { it.capital }.toMutableMap(), next, HashMap(active), HashMap(owed), rate)
            }
        }

        internal val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "hauls"), Supplier { HaulData() }, CODEC)

        /** The tickets, made (and so saved) if there are none yet: for whoever posts or books. */
        fun get(level: ServerLevel): HaulData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** The tickets if there are any, else null, making nothing: for a look, for the hours while the trade is off, and for the market screen. */
        fun peek(level: ServerLevel): HaulData? = level.server.overworld().dataStorage.get(TYPE)
    }
}
