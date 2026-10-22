package io.github.vaspyyy.guildmark.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.city.plan.Kind
import io.github.vaspyyy.guildmark.network.MarketOrder
import io.github.vaspyyy.guildmark.network.MarketOrderPayload
import io.github.vaspyyy.guildmark.network.SupplierOrdersPayload
import io.github.vaspyyy.guildmark.network.TrackedOrder
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import io.github.vaspyyy.guildmark.townsfolk.talk.Witness
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Where an order stands. */
enum class OrderState(val id: String) {
    /** Posted, for any supplier to take. */
    OPEN("open"),

    /** Taken by one supplier, and theirs alone until it's filled or its day comes. */
    TAKEN("taken"),

    /** Filled in full by a supplier the town knows well, and offered to them again every week (see [Orders.WEEKS]). */
    OFFERED("offered"),

    /** Filled every week by the supplier who took the offer. */
    STANDING("standing");

    companion object {
        fun byId(id: String): OrderState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One business's order at a capital: [amount] of [good] wanted at [shop] (a standing order's is each week's), worth
 * [basis] a unit to the market when it was posted (see [Orders.basis]; a standing order's is worked out afresh each
 * week), posted at market hour [posted]. Its day is [due]: while it's open, the hour it's withdrawn if nobody takes
 * it; once taken, the start of the rest day it's to be delivered by. Once someone takes it, [taker] (called [name])
 * holds it at [each] Marks a unit; [delivered] have come in so far, earning [earned] Marks to the fraction, of which
 * [paid] have been paid out. [week] is which week of a standing order this is.
 */
class Order(val id: Int, val shop: Shop, val good: Good, var amount: Int, var basis: Double, var posted: Long, var due: Long) {
    var state = OrderState.OPEN
    var taker: UUID? = null
    var name = ""
    var each = 0.0
    var delivered = 0
    var earned = 0.0
    var paid = 0
    var week = 0

    /** How many are still to come in. */
    val left: Int get() = (amount - delivered).coerceAtLeast(0)

    /** Whether a supplier holds it: taken, or standing. */
    val held: Boolean get() = state == OrderState.TAKEN || state == OrderState.STANDING

    /** Roughly what it's worth: its goods at the market's price for them. */
    val worth: Double get() = amount * basis
}

/** An order filled at a capital at market [hour]: [count] of [good] for [shop], by [who]; for the crier. */
class Fill(var hour: Long, val good: Good, val shop: Shop, val count: Int, val who: String)

/** [player] let an order at [shop] lapse at market [hour]; its keeper has a word for them next time. */
class Lapse(val player: UUID, val shop: Shop, var hour: Long)

/**
 * One capital's orders and what its businesses need to know to post them: its [name], where its market [board]
 * stands, which businesses it has that post orders and where each is kept ([shops]), what's been delivered lately
 * into stores other than the market's ([fed], fading as the town takes it in), the orders filled lately ([fills])
 * and let lapse ([lapses]), how many new orders are owed for those filled or lapsed since the last hour ([owed]), the
 * latest start of trouble orders have been posted for ([trouble]), and the last market hour looked at ([hour]).
 */
class Book(val capital: Long) {
    val orders = ArrayList<Order>()
    var hour = Long.MIN_VALUE
    var name = ""
    var board: BlockPos? = null
    val shops = LinkedHashMap<Shop, BlockPos>()
    val fed = DoubleArray(Good.entries.size)
    val fills = ArrayList<Fill>()
    val lapses = ArrayList<Lapse>()
    var owed = 0
    var trouble = Long.MIN_VALUE

    val pos: BlockPos get() = BlockPos.of(capital)
}

/** What came of a delivery: [count] went in for [pay] Marks; [own] of what was carried came off the market's own stalls lately and was turned away. */
class Delivery(
    val count: Int,
    val pay: Int,
    val own: Int,
    /** The order (or this week of it) is filled; and a standing order is offered for it. */
    val done: Boolean,
    val offered: Boolean,
    /** The supplier's rank there before and after. */
    val before: SupplierRank,
    val after: SupplierRank,
)

/**
 * The capitals' orders (see [Order]): a capital's businesses asking for the goods it has to bring in by caravan or
 * is short of now, weighted by what it pays for them, each for one good at one business (two businesses may want the
 * same good: the produce stall, the bakery and the inn all buy wheat). A capital keeps up to [MOST] at once: two to
 * four posted at dawn, one more for each filled, lapsed or withdrawn, and one for the goods trouble falls on as it
 * starts (a blight posts one for the blighted crop). An order nobody takes is withdrawn after [OPEN_HOURS], so the
 * town asks afresh. A player takes one at the business's market screen and it's theirs alone until the next rest day
 * at least [MIN_DAYS] days off; they may hold as many as their best standing anywhere allows (see
 * [SupplierRank.orders]). Delivering takes what they carry of the good (part deliveries welcome, but nothing they
 * bought at this market lately) and pays at once; the goods go where the business keeps them, outside the stalls'
 * room. Past its day an order lapses: they keep what they were paid and lose standing for the rest. A supplier the
 * town knows well may be offered a filled order again every week.
 *
 * An order pays a fixed price a unit, worked out when it's posted: a third and more over what the stall pays, never
 * over [CAP] of what the market's price comes to over the order's whole amount, and never more than the market's own
 * price for each unit as it goes in, so no order pays for goods bought at its own stalls. Kept with the overworld;
 * worked out hour by hour of the markets' clock, so a long sleep catches up, and frozen while `supplierOrders` is off.
 */
class Orders(private val books: MutableMap<Long, Book> = HashMap(), private var next: Int = 1) : SavedData() {
    operator fun get(capital: BlockPos): Book? = books[capital.asLong()]

    /** [capital]'s book, started if it hasn't one yet. */
    fun book(capital: BlockPos): Book = books.getOrPut(capital.asLong()) { Book(capital.asLong()) }

    fun all(): Collection<Book> = books.values

    /** The order numbered [id], and the book it's in. */
    fun find(id: Int): Pair<Book, Order>? {
        for (book in books.values) book.orders.firstOrNull { it.id == id }?.let { return book to it }
        return null
    }

    /** Every order [player] holds, at every capital. */
    fun held(player: UUID): List<Pair<Book, Order>> = books.values.flatMap { book -> book.orders.filter { it.held && it.taker == player }.map { book to it } }

    /** A number for a new order. */
    fun number(): Int {
        setDirty()
        return next++
    }

    fun changed() = setDirty()

    companion object {
        /** The most orders a capital has out at once (standing orders count; offers don't). */
        const val MOST = 6

        /** Over what selling at the stall pays, what an order pays; and the most of the market's price it ever pays. */
        const val OVER = 1.35
        const val CAP = 0.9

        /** A standing order pays this much more, and runs this many weeks. */
        const val STANDING_MORE = 1.1
        const val WEEKS = 4

        /** A taken order is due by the start of the next rest day at least this many days off. */
        const val MIN_DAYS = 3

        /** Market hours an order stays out for anyone to take before the business asks afresh. */
        const val OPEN_HOURS = 48L

        /** Days of what the town brings in that an order asks for, fewest and most. */
        private const val DAYS_LEAST = 2
        private const val DAYS_MOST = 4

        /** The amounts an order comes in, the most of what the town likes to keep it asks for at once, and how little need is worth an order. */
        val SIZES = listOf(16, 32, 48, 64, 96, 128)
        private const val OF_TARGET = 1.5
        private const val LEAST_NEED = 8.0

        /** A purveyor takes orders half as big again, up to this many. */
        private const val BIGGER = 1.5
        private const val LARGEST = 192

        /**
         * Points of standing for an order filled in full and in time, of what it paid (and at least so many: an order
         * of cheap goods is worth filling too); lost for one let lapse, of what the rest would have paid.
         */
        private const val FULL = 0.25
        private const val FULL_LEAST = 5.0
        private const val LAPSE = 0.5

        /** The chance a fill offers a standing order, to a supplier known there or better. */
        private const val OFFER = 0.5

        /** How much of what's been delivered into the mill's or the inn's stores the town takes in, each hour. */
        private const val SETTLE = 0.15

        /** Orders posted when a capital's businesses are first known, and at dawn (fewest and most). */
        private const val FIRST = 2
        private const val DAWN_LEAST = 2
        private const val DAWN_MOST = 4

        /** Hours after the crier's round an order's ticket goes up on the board without them. */
        const val GRACE = 2

        /** Market hours of fills and lapses kept: two days, and a week. */
        private const val FILLS_KEPT = 48L
        private const val LAPSES_KEPT = 168L

        /** Server ticks between runs, and between looks at the capitals players stand in. */
        private const val TICK = 20
        private const val LOOK = 100

        /**
         * The most hours worked out in one run for all the books together, catching up; the rest wait for the next. As
         * many as the markets work out in a run (the orders run just after them, in their order), so a book catching up
         * goes about as fast as its market.
         */
        private const val STEPS = 48

        // ---- Keeping ----

        private val ORDER: Codec<Order> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("id").forGetter(Order::id),
                Codec.STRING.fieldOf("shop").forGetter { it.shop.id },
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.INT.fieldOf("amount").forGetter(Order::amount),
                Codec.DOUBLE.fieldOf("basis").forGetter(Order::basis),
                Codec.LONG.fieldOf("posted").forGetter(Order::posted),
                Codec.LONG.fieldOf("due").forGetter(Order::due),
                Codec.STRING.optionalFieldOf("state", "open").forGetter { it.state.id },
                UUIDUtil.CODEC.optionalFieldOf("taker").forGetter { Optional.ofNullable(it.taker) },
                Codec.STRING.optionalFieldOf("name", "").forGetter(Order::name),
                Codec.DOUBLE.optionalFieldOf("each", 0.0).forGetter(Order::each),
                Codec.INT.optionalFieldOf("delivered", 0).forGetter(Order::delivered),
                Codec.DOUBLE.optionalFieldOf("earned", 0.0).forGetter(Order::earned),
                Codec.INT.optionalFieldOf("paid", 0).forGetter(Order::paid),
                Codec.INT.optionalFieldOf("week", 0).forGetter(Order::week),
            ).apply(i) { id, shop, good, amount, basis, posted, due, state, taker, name, each, delivered, earned, paid, week ->
                // An order for a business or good no longer known is kept as the produce stall's wheat, and lapses in its time
                Order(id, Shop.byId(shop) ?: Shop.PRODUCE, Good.byId(good) ?: Good.WHEAT, amount, basis, posted, due).also { o ->
                    o.state = OrderState.byId(state) ?: OrderState.OPEN
                    o.taker = taker.orElse(null)
                    o.name = name
                    o.each = each
                    o.delivered = delivered
                    o.earned = earned
                    o.paid = paid
                    o.week = week
                }
            }
        }

        private val FILL: Codec<Fill> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("hour").forGetter(Fill::hour),
                Codec.STRING.fieldOf("good").forGetter { it.good.id },
                Codec.STRING.fieldOf("shop").forGetter { it.shop.id },
                Codec.INT.fieldOf("count").forGetter(Fill::count),
                Codec.STRING.optionalFieldOf("who", "").forGetter(Fill::who),
            ).apply(i) { hour, good, shop, count, who -> Fill(hour, Good.byId(good) ?: Good.WHEAT, Shop.byId(shop) ?: Shop.PRODUCE, count, who) }
        }

        private val LAPSED: Codec<Lapse> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Lapse::player),
                Codec.STRING.fieldOf("shop").forGetter { it.shop.id },
                Codec.LONG.fieldOf("hour").forGetter(Lapse::hour),
            ).apply(i) { player, shop, hour -> Lapse(player, Shop.byId(shop) ?: Shop.PRODUCE, hour) }
        }

        private val BOOK: Codec<Book> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(Book::capital),
                ORDER.listOf().optionalFieldOf("orders", listOf()).forGetter { it.orders },
                Codec.LONG.optionalFieldOf("hour", Long.MIN_VALUE).forGetter(Book::hour),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Book::name),
                Codec.LONG.optionalFieldOf("board").forGetter { b -> Optional.ofNullable(b.board?.asLong()) },
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("shops", mapOf()).forGetter { b -> b.shops.entries.associate { it.key.id to it.value.asLong() } },
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("fed", mapOf()).forGetter { b ->
                    Good.entries.filter { b.fed[it.ordinal] > 0.01 }.associate { it.id to b.fed[it.ordinal] }
                },
                FILL.listOf().optionalFieldOf("fills", listOf()).forGetter { it.fills },
                LAPSED.listOf().optionalFieldOf("lapses", listOf()).forGetter { it.lapses },
                Codec.INT.optionalFieldOf("owed", 0).forGetter(Book::owed),
                Codec.LONG.optionalFieldOf("trouble", Long.MIN_VALUE).forGetter(Book::trouble),
            ).apply(i) { capital, orders, hour, name, board, shops, fed, fills, lapses, owed, trouble ->
                Book(capital).also { b ->
                    b.orders.addAll(orders)
                    b.hour = hour
                    b.name = name
                    b.board = board.map { BlockPos.of(it) }.orElse(null)
                    for ((id, pos) in shops) Shop.byId(id)?.let { b.shops[it] = BlockPos.of(pos) }
                    for ((id, n) in fed) Good.byId(id)?.let { b.fed[it.ordinal] = n }
                    b.fills.addAll(fills)
                    b.lapses.addAll(lapses)
                    b.owed = owed
                    b.trouble = trouble
                }
            }
        }

        private val CODEC: Codec<Orders> = RecordCodecBuilder.create { i ->
            i.group(
                BOOK.listOf().optionalFieldOf("capitals", listOf()).forGetter { it.books.values.toList() },
                Codec.INT.optionalFieldOf("next", 1).forGetter { it.next },
            ).apply(i) { list, next -> Orders(list.associateBy { it.capital }.toMutableMap(), next) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "orders"), Supplier { Orders() }, CODEC)

        fun get(level: ServerLevel): Orders = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Whether orders were on at the last run, to tell everyone's tracker when that changes. */
        private var wasOn: Boolean? = null

        /** Registers the orders' payloads, their hourly run, and each player's tracker as they join. */
        fun register(modBus: IEventBus) {
            modBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
                event.registrar("1")
                    .playToServer(MarketOrderPayload.TYPE, MarketOrderPayload.STREAM_CODEC, MarketOrderPayload::handle)
                    .playToClient(SupplierOrdersPayload.TYPE, SupplierOrdersPayload.STREAM_CODEC, SupplierOrdersPayload::handle)
            }
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
            NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let { sync(it.level(), it) } }
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ -> wasOn = null }
        }

        // ---- The hours ----

        private fun tick(server: MinecraftServer) {
            if (server.tickCount % TICK != 0) return
            val level = server.overworld()
            val on = Suppliers.enabled()
            if (on != wasOn) {
                wasOn = on
                for (player in server.playerList.players) sync(level, player)
            }
            val data = get(level)
            val now = Markets.hourOf(level)
            if (on && server.tickCount % LOOK == 0) look(level, data)
            var steps = STEPS
            for (entry in Markets.get(level).all()) {
                val book = data.book(entry.capital)
                if (on) steps -= run(level, data, book, entry, now, steps) else hold(data, book, now)
            }
        }

        /** Learns the businesses of every capital a player stands in, as soon as its places are known. */
        private fun look(level: ServerLevel, data: Orders) {
            val seen = HashSet<BlockPos>()
            for (player in level.players()) {
                val capital = Furnish.capitalAt(level, player.blockPosition()) ?: continue
                if (!seen.add(capital.center)) continue
                val places = CityPlaces.of(level, capital) ?: continue
                if (Markets.get(level)[capital.center] == null) continue
                learn(data, data.book(capital.center), places)
            }
        }

        /** What [book]'s capital has of the businesses that post orders, and where each is kept, from its [places]. */
        fun learn(data: Orders, book: Book, places: CityPlaces) {
            val name = places.name
            val shops = LinkedHashMap<Shop, BlockPos>()
            for (stall in places.stalls) Shop.of(stall.trade)?.let { shops.putIfAbsent(it, stall.keeper.pos) }
            if (places.harbour) {
                places.counters.firstOrNull { Shop.at(places, it.pos) == Shop.QUAY }?.let { shops[Shop.QUAY] = it.pos }
            }
            fun at(l: CityPlaces.Lodging): BlockPos = l.front?.outside ?: l.box.center
            places.lodgings.values.firstOrNull { it.kind == Kind.BAKERY.name }?.let { shops[Shop.BAKERY] = at(it) }
            places.lodgings.values.firstOrNull { it.kind == Kind.SMITHY.name }?.let { shops[Shop.SMITHY] = at(it) }
            places.nearest(places.market, CityPlaces.TAVERNS).firstOrNull()?.let { shops[Shop.INN] = at(it) }
            if (name == book.name && shops == book.shops && book.board == places.marketBoard.pos) return
            book.name = name
            book.board = places.marketBoard.pos
            book.shops.clear()
            book.shops.putAll(shops)
            data.setDirty()
        }

        /** While orders are switched off (or the markets are), their days stand still against the clock: nothing lapses for want of a switch. */
        private fun hold(data: Orders, book: Book, now: Long) {
            if (book.hour == Long.MIN_VALUE || now == book.hour) return
            shift(book, now - book.hour, trouble = false)
            data.setDirty()
        }

        /**
         * Moves the hours of everything [book] keeps [hours] on (back, if negative), so its orders keep the days they had
         * left; [trouble] too when the market's own trouble moved with the clock.
         */
        private fun shift(book: Book, hours: Long, trouble: Boolean) {
            for (o in book.orders) {
                o.posted += hours
                o.due += hours
            }
            for (f in book.fills) f.hour += hours
            for (l in book.lapses) l.hour += hours
            if (trouble && book.trouble != Long.MIN_VALUE) book.trouble += hours
            book.hour += hours
        }

        /**
         * Works out the hours of the markets' clock [book] is behind ([now]), each in turn, [steps] of them at most (the
         * rest wait for the next run): up to [Markets.CATCH_UP] of them, as the market works out no more than that, the
         * orders' days standing still over the rest. The hours it worked out.
         */
        private fun run(level: ServerLevel, data: Orders, book: Book, entry: Markets.Entry, now: Long, steps: Int): Int {
            if (book.hour == Long.MIN_VALUE) {
                // Nothing's asked for until its businesses are known and its market is open; then a first few orders at once
                if (book.shops.isEmpty() || entry.market.hour == Long.MIN_VALUE) return 0
                book.hour = now
                book.trouble = entry.market.events.maxOfOrNull { it.since } ?: Long.MIN_VALUE
                post(data, book, entry, FIRST, random(book, now), now)
                data.setDirty()
                return 0
            }
            // Time set back: everything keeps the days it had left, as the market's own trouble does
            if (now < book.hour) {
                shift(book, now - book.hour, trouble = true)
                data.setDirty()
                return 0
            }
            if (now - book.hour > Markets.CATCH_UP) shift(book, now - Markets.CATCH_UP - book.hour, trouble = false)
            var worked = 0
            while (book.hour < now && worked < steps) {
                book.hour++
                hour(level, data, book, entry, book.hour)
                data.setDirty()
                worked++
            }
            return worked
        }

        /** One market [hour] at [book]'s capital: orders whose day is up, orders for new trouble, the day's new orders at dawn, and those owed. */
        private fun hour(level: ServerLevel, data: Orders, book: Book, entry: Markets.Entry, hour: Long) {
            for (i in book.fed.indices) book.fed[i] *= 1.0 - SETTLE
            for (o in book.orders.filter { it.due <= hour }) due(level, data, book, entry, o, hour)
            val random = random(book, hour)
            for (e in entry.market.events.sortedBy { it.since }) {
                if (!e.kind.trouble || e.since <= book.trouble || e.since > hour) continue
                book.trouble = e.since
                post(data, book, entry, 1, random, hour, e)
            }
            if (Math.floorMod(hour, 24L) == 0L) post(data, book, entry, DAWN_LEAST + random.nextInt(DAWN_MOST - DAWN_LEAST + 1), random, hour)
            if (book.owed > 0) {
                post(data, book, entry, book.owed, random, hour)
                book.owed = 0
            }
            book.fills.removeAll { hour - it.hour > FILLS_KEPT }
            book.lapses.removeAll { hour - it.hour > LAPSES_KEPT }
        }

        private fun random(book: Book, hour: Long): Random = Random(book.capital * 0x5DEECE66DL xor hour * -0x61C8864680B583EBL)

        // ---- Posting ----

        /** The goods [shop] asks for in [town], if it has the hands to want them. */
        fun asks(shop: Shop, town: Town): List<Good> = when (shop) {
            // Wheat for its flour, milled for it
            Shop.BAKERY -> if (town.bakers > 0 && town.mills > 0 && town.farmers > 0) listOf(Good.WHEAT) else listOf()
            Shop.SMITHY -> if (town.smiths > 0) shop.goods else listOf()
            // Wheat for the ale, potatoes and meat for the table
            Shop.INN -> if (town.innkeepers > 0) listOf(Good.WHEAT, Good.POTATO, Good.BEEF, Good.PORK, Good.MUTTON, Good.CHICKEN) else listOf()
            Shop.HALL -> listOf()
            else -> shop.goods
        }

        /** Where [shop] keeps what's delivered to it of [good]: the bakery's wheat at the mill, the inn's in its cellar, the rest in the market. */
        fun site(shop: Shop, good: Good): Site = when {
            good == Good.WHEAT && shop == Shop.BAKERY -> Site.MILL
            good == Good.WHEAT && shop == Shop.INN -> Site.TAVERN
            else -> Site.MARKET
        }

        /** A good the businesses could ask for: which of them would, how much the town needs of it a day ([need]) and how far short of its keep it is ([short]), and what that's worth ([weight]). */
        private class Want(val good: Good, val shops: List<Shop>, val need: Double, val short: Double, val weight: Double)

        /** The businesses at [book]'s capital that would ask [town] for [good] and have no order out for it. */
        private fun askers(book: Book, town: Town, good: Good): List<Shop> =
            Shop.entries.filter { s -> s in book.shops && good in asks(s, town) && book.orders.none { it.shop == s && it.good == good } }

        /** The goods [book]'s businesses would ask [entry]'s market for now, those with no order out for them yet; only those [trouble] falls on, if it's given. */
        private fun wants(book: Book, entry: Markets.Entry, trouble: Event?): List<Want> {
            val m = entry.market
            val town = entry.town
            val out = ArrayList<Want>()
            val touched = trouble?.let(::troubled)
            for (good in Good.TRADED) {
                if (touched != null && good !in touched) continue
                val shops = askers(book, town, good)
                if (shops.isEmpty()) continue
                // Luck under way brings plenty of it; a market with all it has room for wants nothing
                if (touched == null && m.events(good).any { it.factor > 1.0 }) continue
                val target = town.target(good)
                val stock = m.stock(good)
                if (touched == null && stock >= target * Lots.ROOM) continue
                var need = (town.use(good) + town.spoils(good)) * max(m.brought[good.ordinal], town.shortfall(good))
                // What the trouble takes from what the town makes, on top (a waylaid wagon takes nothing the town makes)
                if (trouble != null && trouble.kind != EventKind.WAYLAID) need += town.made(good) * (1.0 - trouble.factor).coerceAtLeast(0.0)
                val short = (target - stock).coerceAtLeast(0.0)
                if (need * DAYS_MOST + short < LEAST_NEED) continue
                out.add(Want(good, shops, need, short, (need + short / 3.0) * m.price(town, good)))
            }
            return out
        }

        /** The goods [e] falls on: a waylaid wagon's load, the blighted crop, the barns' and coops' goods, the catch. */
        private fun troubled(e: Event): Set<Good> = when {
            e.goods.isNotEmpty() -> e.goods
            e.good != null -> setOf(e.good)
            e.kind.maker == Maker.BOATS -> Economy.CATCH.keys
            else -> e.kind.goods
        }

        /**
         * Posts up to [count] new orders at [book]'s capital at market [hour], the goods picked by what they're worth to
         * the town; for [trouble], one for the goods it falls on, making room by dropping the least of the orders
         * nobody's taken.
         */
        fun post(data: Orders, book: Book, entry: Markets.Entry, count: Int, random: Random, hour: Long, trouble: Event? = null): List<Order> {
            val posted = ArrayList<Order>()
            val wants = wants(book, entry, trouble).toMutableList()
            var room = MOST - book.orders.count { it.state != OrderState.OFFERED }
            repeat(count) {
                if (wants.isEmpty()) return posted
                if (room <= 0) {
                    if (trouble == null) return posted
                    val least = book.orders.filter { it.state == OrderState.OPEN }.minByOrNull { it.worth } ?: return posted
                    book.orders.remove(least)
                    room++
                }
                // A waylaid wagon's order is for the lost good the town is shortest of
                val want = if (trouble?.kind == EventKind.WAYLAID) {
                    wants.maxBy { it.short / max(1.0, entry.town.target(it.good)) }
                } else {
                    pick(wants, random) { it.weight }
                }
                wants.remove(want)
                val shop = pick(want.shops, random) { if (it.goods.contains(want.good)) 2.0 else 1.0 }
                val amount = amount(want, entry.town.target(want.good), DAYS_LEAST + random.nextInt(DAYS_MOST - DAYS_LEAST + 1))
                val order = Order(data.number(), shop, want.good, amount, basis(book, entry, want.good, amount), hour, hour + OPEN_HOURS)
                book.orders.add(order)
                posted.add(order)
                room--
            }
            data.setDirty()
            return posted
        }

        /** Posts an order for [good] at [book]'s capital at market [hour], whatever the town wants (for the commands); null if no business there asks for it, or each one that does has an order out for it already. */
        fun postFor(data: Orders, book: Book, entry: Markets.Entry, good: Good, hour: Long): Order? {
            val town = entry.town
            val shops = askers(book, town, good)
            if (shops.isEmpty()) return null
            val shop = shops.firstOrNull { it.goods.contains(good) } ?: shops.first()
            val need = (town.use(good) + town.spoils(good)) * max(entry.market.brought[good.ordinal], town.shortfall(good))
            val amount = amount(Want(good, shops, need, (town.target(good) - entry.market.stock(good)).coerceAtLeast(0.0), 0.0), town.target(good), DAYS_MOST)
            val order = Order(data.number(), shop, good, amount, basis(book, entry, good, amount), hour, hour + OPEN_HOURS)
            book.orders.add(order)
            data.setDirty()
            return order
        }

        private fun <T> pick(list: List<T>, random: Random, weight: (T) -> Double): T {
            var r = random.nextDouble() * list.sumOf(weight)
            for (x in list) {
                r -= weight(x)
                if (r < 0.0) return x
            }
            return list.last()
        }

        /** How many an order for [want] asks for: [days] days of what the town needs and what it's short, in one of [SIZES], no more than [OF_TARGET] times what it keeps. */
        private fun amount(want: Want, target: Double, days: Int): Int {
            val most = max(target * OF_TARGET, SIZES.first().toDouble())
            val wanted = (want.need * days + want.short).coerceIn(SIZES.first().toDouble(), most)
            return SIZES.filter { it <= most }.minByOrNull { abs(ln(it / wanted)) } ?: SIZES.first()
        }

        /**
         * What a unit of [good] is worth to [entry]'s market over [amount] more of it than it holds now (counting what's
         * gone lately into [book]'s other stores): the average of its price as each unit comes in. What an order for
         * that many pays is reckoned from this.
         */
        fun basis(book: Book, entry: Markets.Entry, good: Good, amount: Int): Double {
            val m = entry.market
            val from = m.stock(good) + book.fed[good.ordinal]
            var total = 0.0
            for (k in 0 until amount) total += m.price(entry.town, good, from + k + 0.5)
            return total / max(1, amount)
        }

        /** What a unit of an order worth [basis] pays a supplier whose standing adds [bonus] to the stalls' share; a [standing] order a tenth more. Never over [CAP] of [basis]. */
        fun each(basis: Double, bonus: Double, standing: Boolean): Double =
            min(basis * (Lots.SELL_SHARE + bonus) * OVER * (if (standing) STANDING_MORE else 1.0), basis * CAP)

        /** The market hour an order posted at [hour] is due by: the start of the next rest day at least [least] days off. */
        fun dueFrom(hour: Long, least: Int = MIN_DAYS): Long {
            var day = Math.floorDiv(hour + 4, 24L) + least
            while (Math.floorMod(day, 7L) != 6L) day++
            return day * 24 - 4
        }

        /** Whole days left at market hour [now] before [due] (0 on the last). */
        fun daysLeft(due: Long, now: Long): Int = Math.floorDiv(due - now - 1, 24L).coerceAtLeast(0L).toInt()

        /** [player]'s sale bonus at [book]'s capital. */
        private fun bonus(level: ServerLevel, book: Book, player: UUID): Double = SupplierRank.entries[Suppliers.rank(level, book.pos, player)].bonus

        // ---- Their day comes ----

        /** [o]'s day is up at [hour]: an open one or an offer is withdrawn, a taken one lapses, a standing one goes on to its next week if this one's was met. */
        private fun due(level: ServerLevel, data: Orders, book: Book, entry: Markets.Entry, o: Order, hour: Long) {
            when (o.state) {
                OrderState.OPEN, OrderState.OFFERED -> {
                    book.orders.remove(o)
                    book.owed++
                }
                OrderState.TAKEN -> end(level, data, book, o, hour, given = false)
                OrderState.STANDING -> if (o.delivered >= o.amount) week(level, data, book, entry, o) else end(level, data, book, o, hour, given = false)
            }
        }

        /** A standing order's week was met: on to the next, priced afresh, or done after the last. */
        private fun week(level: ServerLevel, data: Orders, book: Book, entry: Markets.Entry, o: Order) {
            val taker = o.taker ?: return
            val player = level.server.playerList.getPlayer(taker)
            if (o.week >= WEEKS) {
                book.orders.remove(o)
                book.owed++
                player?.sendSystemMessage(Component.translatable("supplier.guildmark.standing.done", what(o), shopName(o.shop), book.name).withStyle(ChatFormatting.YELLOW))
            } else {
                o.week++
                o.delivered = 0
                o.earned = 0.0
                o.paid = 0
                o.due += 7 * 24
                o.basis = basis(book, entry, o.good, o.amount)
                o.each = each(o.basis, bonus(level, book, taker), standing = true)
                player?.sendSystemMessage(Component.translatable("supplier.guildmark.standing.week", o.week, WEEKS, what(o), shopName(o.shop), book.name).withStyle(ChatFormatting.YELLOW))
            }
            data.setDirty()
            sync(level, taker)
        }

        /**
         * [o] comes to an end with goods still owed at [hour]: lapsed, or [given] up. The supplier keeps what they were
         * paid and loses standing for the rest; the business may post it again.
         */
        private fun end(level: ServerLevel, data: Orders, book: Book, o: Order, hour: Long, given: Boolean) {
            book.orders.remove(o)
            book.owed++
            data.setDirty()
            val taker = o.taker ?: return
            val rest = o.left
            if (rest > 0) {
                Suppliers.gain(level, book.pos, taker, -max(1.0, LAPSE * o.each * rest))
                val suppliers = Suppliers.get(level)
                suppliers.of(book.pos, taker).lapsed++
                suppliers.changed()
                if (!given) book.lapses.add(Lapse(taker, o.shop, hour))
                Witness.lapsed(level, book.pos, o.shop, taker, o.name)
            }
            val key = if (given) "supplier.guildmark.given_up" else "supplier.guildmark.lapsed"
            level.server.playerList.getPlayer(taker)?.sendSystemMessage(
                Component.translatable(key, what(o), shopName(o.shop), book.name, rest).withStyle(ChatFormatting.GRAY),
            )
            sync(level, taker)
        }

        // ---- What players do ----

        /** Why [player] can't take [o] at [book]'s capital, as a key under gui.guildmark.order.refused., or null if they can. */
        fun refusal(level: ServerLevel, player: UUID, o: Order): String? {
            if (o.state != OrderState.OPEN) return "taken"
            val held = get(level).held(player).size
            if (held >= Suppliers.best(level, player).orders) return "full"
            return null
        }

        /** [player] takes [o] at [book]'s capital: theirs now, at their standing's pay (and half as big again for its purveyor). Gives why not, if they can't (see [refusal]). */
        fun take(level: ServerLevel, player: ServerPlayer, book: Book, entry: Markets.Entry, o: Order): String? {
            refusal(level, player.uuid, o)?.let { return it }
            o.state = OrderState.TAKEN
            o.taker = player.uuid
            o.name = player.gameProfile.name
            o.due = dueFrom(Markets.hourOf(level))
            if (Suppliers.rank(level, book.pos, player.uuid) == SupplierRank.PURVEYOR.ordinal) {
                o.amount = min(LARGEST, (o.amount * BIGGER).toInt())
                o.basis = basis(book, entry, o.good, o.amount)
            }
            o.each = each(o.basis, bonus(level, book, player.uuid), standing = false)
            get(level).setDirty()
            sync(level, player)
            return null
        }

        /**
         * How many of what [player] carries of [o]'s good it will take: none that they or their party bought at this
         * market in the last week (or since it was posted).
         */
        fun deliverable(level: ServerLevel, player: ServerPlayer, book: Book, o: Order): Int {
            val item = o.good.item ?: return 0
            return (Trading.count(player.inventory, item, book.pos) - barred(level, player.uuid, book, o)).coerceAtLeast(0)
        }

        /**
         * How many of [o]'s good [player] and everyone in their party bought at [book]'s market lately, which it won't
         * take: what one of them bought there and handed to another is kept out all the same, as a stall of their own
         * keeps it off its table (see [SupplierLink.boughtByParty]).
         */
        fun barred(level: ServerLevel, player: UUID, book: Book, o: Order): Int {
            val since = min(Suppliers.today(level) - Suppliers.WEEK + 1, Math.floorDiv(o.posted + 4, 24L))
            val party = Parties.get(level.server).of(player)?.members ?: listOf()
            return (party + player).distinct().sumOf { Suppliers.boughtSince(level, book.pos, it, o.good, since) }
        }

        /**
         * [player] delivers what they carry of [o]'s good to its business (only what it will take, up to what's still
         * wanted), paid at once; [conjured] for testing, when the goods come from nowhere. Each unit pays the order's
         * price, but never more than the market's own price for it as it goes in.
         */
        fun deliver(level: ServerLevel, player: ServerPlayer, book: Book, entry: Markets.Entry, o: Order, conjured: Int = -1): Delivery {
            val rank = Suppliers.get(level)[book.pos, player.uuid]?.rank ?: SupplierRank.STRANGER
            val item = o.good.item
            // Salvage off the capital's own waylaid wagons doesn't fill its orders: that's to be handed in (see Marked)
            val carried = if (item == null) 0 else Trading.count(player.inventory, item, book.pos)
            val barred = if (conjured >= 0) 0 else barred(level, player.uuid, book, o)
            val can = if (conjured >= 0) conjured else (carried - barred).coerceAtLeast(0)
            val count = min(o.left, can)
            val own = if (conjured >= 0) 0 else min(carried, barred)
            if (item == null || count <= 0 || !o.held || o.taker != player.uuid) return Delivery(0, 0, own, false, false, rank, rank)
            val m = entry.market
            val good = o.good
            val site = site(o.shop, good)
            val from = m.stock(good) + book.fed[good.ordinal]
            var earned = 0.0
            for (k in 0 until count) earned += min(o.each, m.price(entry.town, good, from + k + 0.5))
            o.earned += earned
            val pay = (floor(o.earned + 1e-9).toInt() - o.paid).coerceAtLeast(0)
            o.paid += pay
            o.delivered += count
            if (conjured < 0) Trading.take(player.inventory, item, count, book.pos)
            if (pay > 0) Trading.give(player, ModItems.GUILD_MARK.get(), pay)
            // Into the market they move its price at once and keep it moved, as a sale does; into the mill or the cellar they're felt as they're used
            if (site == Site.MARKET) m.traded(good, count.toDouble()) else m.add(site, good, count.toDouble())
            if (site != Site.MARKET) book.fed[good.ordinal] += count.toDouble()
            m.tally(Tally.SOLD, good, count.toDouble())
            Markets.get(level).changed()
            get(level).setDirty()
            val hour = Markets.hourOf(level)
            val gained = Suppliers.gain(level, book.pos, player.uuid, pay.toDouble())
            Suppliers.dealt(level, player.uuid, pay.toDouble())
            val before = gained.first
            var after = gained.second
            val done = o.delivered >= o.amount
            var offered = false
            if (done) {
                after = Suppliers.gain(level, book.pos, player.uuid, max(FULL_LEAST, o.paid * FULL)).second
                val suppliers = Suppliers.get(level)
                suppliers.of(book.pos, player.uuid).filled++
                suppliers.changed()
                book.fills.add(Fill(hour, good, o.shop, o.amount, player.gameProfile.name))
                Witness.filled(level, book.pos, o.shop, player)
                if (o.state == OrderState.TAKEN) {
                    offered = after >= SupplierRank.KNOWN && player.random.nextDouble() < OFFER
                    if (offered) offer(book, entry, o, hour) else {
                        book.orders.remove(o)
                        book.owed++
                    }
                }
            }
            sync(level, player)
            return Delivery(count, pay, own, done, offered, before, after)
        }

        /** [o], just filled, is offered again to its supplier as a standing order, until the start of the next rest day. */
        private fun offer(book: Book, entry: Markets.Entry, o: Order, hour: Long) {
            o.state = OrderState.OFFERED
            o.delivered = 0
            o.earned = 0.0
            o.paid = 0
            o.due = dueFrom(hour, 1)
            o.basis = basis(book, entry, o.good, o.amount)
        }

        /** [player] accepts the standing order [o] they were offered: its first week runs to the next rest day at least [MIN_DAYS] days off. Gives why not, if they can't. */
        fun accept(level: ServerLevel, player: ServerPlayer, book: Book, entry: Markets.Entry, o: Order): String? {
            if (o.state != OrderState.OFFERED || o.taker != player.uuid) return "gone"
            if (get(level).held(player.uuid).size >= Suppliers.best(level, player.uuid).orders) return "full"
            o.state = OrderState.STANDING
            o.week = 1
            o.due = dueFrom(Markets.hourOf(level))
            o.basis = basis(book, entry, o.good, o.amount)
            o.each = each(o.basis, bonus(level, book, player.uuid), standing = true)
            get(level).setDirty()
            sync(level, player)
            return null
        }

        /** [player] turns down the standing order [o] they were offered. */
        fun decline(level: ServerLevel, player: ServerPlayer, book: Book, o: Order) {
            if (o.state != OrderState.OFFERED || o.taker != player.uuid) return
            book.orders.remove(o)
            book.owed++
            get(level).setDirty()
        }

        /** [player] gives up [o], which they hold: as if it lapsed now (a standing order whose week is met, without a loss). */
        fun giveUp(level: ServerLevel, player: ServerPlayer, book: Book, o: Order) {
            if (!o.held || o.taker != player.uuid) return
            end(level, get(level), book, o, Markets.hourOf(level), given = true)
        }

        /** Ends [o] at once, lapsed, for the commands. */
        fun lapse(level: ServerLevel, book: Book, o: Order) {
            when {
                o.held -> end(level, get(level), book, o, Markets.hourOf(level), given = false)
                else -> {
                    book.orders.remove(o)
                    book.owed++
                    get(level).setDirty()
                }
            }
        }

        /** Whether [player] let an order at [shop] in [capital] lapse lately, and hasn't heard about it from its keeper yet; they're told now. */
        fun scold(level: ServerLevel, capital: BlockPos, player: UUID, shop: Shop): Boolean {
            if (!Suppliers.enabled()) return false
            val data = get(level)
            val book = data[capital] ?: return false
            val lapse = book.lapses.firstOrNull { it.player == player && it.shop == shop } ?: return false
            book.lapses.remove(lapse)
            data.setDirty()
            return true
        }

        /** Whether [shop] at [capital] has offered [player] a standing order they haven't answered yet. */
        fun offered(level: ServerLevel, player: UUID, capital: BlockPos, shop: Shop): Boolean =
            Suppliers.enabled() && get(level)[capital]?.orders?.any { it.shop == shop && it.state == OrderState.OFFERED && it.taker == player } == true

        /** Whether [shop] at [capital] has an order out that [player] could take now, with room for another. */
        fun toBeHad(level: ServerLevel, player: UUID, capital: BlockPos, shop: Shop): Boolean {
            if (!Suppliers.enabled()) return false
            val data = get(level)
            val book = data[capital] ?: return false
            if (book.orders.none { it.shop == shop && it.state == OrderState.OPEN }) return false
            return data.held(player).size < Suppliers.best(level, player).orders
        }

        // ---- What's shown ----

        /** [shop]'s orders at [book]'s capital as its screen shows them to [player]: theirs first, then those to be had, then those promised to others. */
        fun view(level: ServerLevel, player: ServerPlayer, book: Book, shop: Shop): List<MarketOrder> {
            if (!Suppliers.enabled()) return listOf()
            val now = Markets.hourOf(level)
            val bonus = bonus(level, book, player.uuid)
            val out = ArrayList<Pair<Int, MarketOrder>>()
            for (o in book.orders) {
                if (o.shop != shop) continue
                val mine = o.taker == player.uuid
                val state = when {
                    o.state == OrderState.OPEN -> MarketOrder.OPEN
                    o.state == OrderState.OFFERED -> if (mine) MarketOrder.OFFERED else continue
                    !mine -> MarketOrder.PROMISED
                    o.state == OrderState.STANDING -> MarketOrder.STANDING
                    else -> MarketOrder.YOURS
                }
                val each = when (state) {
                    MarketOrder.OPEN -> each(o.basis, bonus, standing = false)
                    MarketOrder.OFFERED -> each(o.basis, bonus, standing = true)
                    else -> o.each
                }
                val item = o.good.item
                val carried = if (item == null) 0 else Trading.count(player.inventory, item, book.pos)
                val deliverable = if (state == MarketOrder.PROMISED) 0 else deliverable(level, player, book, o)
                val first = when (state) {
                    MarketOrder.OFFERED -> 0
                    MarketOrder.YOURS, MarketOrder.STANDING -> 1
                    MarketOrder.OPEN -> 2
                    else -> 3
                }
                // One not yet taken shows the days its taker would have, and how long it's open for
                val waiting = state == MarketOrder.OPEN || state == MarketOrder.OFFERED
                val days = daysLeft(if (waiting) dueFrom(now) else o.due, now)
                val open = if (waiting) daysLeft(o.due, now) else 0
                out.add(first to MarketOrder(o.id, o.good.id, o.amount, o.delivered, each.toFloat(), days, open, state, o.week, carried, deliverable, if (mine) "" else o.name))
            }
            return out.sortedWith(compareBy({ it.first }, { it.second.id })).map { it.second }
        }

        /** Sends [player]'s tracker every order they hold. */
        fun sync(level: ServerLevel, player: ServerPlayer) {
            val list = if (!Suppliers.enabled()) listOf() else get(level).held(player.uuid).map { (book, o) ->
                TrackedOrder(o.id, o.good.id, o.amount, o.delivered, o.shop.id, book.name, o.due, book.shops[o.shop] ?: book.pos, o.week)
            }
            PacketDistributor.sendToPlayer(player, SupplierOrdersPayload(list))
        }

        fun sync(level: ServerLevel, player: UUID) {
            level.server.playerList.getPlayer(player)?.let { sync(level, it) }
        }

        /** When [o] is due, at market hour [now], for the chat ("by the rest day, in 3 days"). */
        fun due(o: Order, now: Long): Component = when (val days = daysLeft(o.due, now)) {
            0 -> Component.translatable("supplier.guildmark.due.tonight")
            1 -> Component.translatable("supplier.guildmark.due.tomorrow")
            else -> Component.translatable("supplier.guildmark.due", days)
        }

        /** "64 Wheat", for the chat. */
        fun what(o: Order): Component = Component.translatable("supplier.guildmark.what", o.amount, Component.translatable(o.good.key + ".many"))

        /** A business's name ("The Bakery"). */
        fun shopName(shop: Shop): Component = Component.translatable(shop.key)

        /** "12 Marks" (or "1 Mark"), for the chat. */
        fun marks(n: Int): Component = Component.translatable(if (n == 1) "gui.guildmark.market.marks.one" else "gui.guildmark.market.marks", n)
    }
}
