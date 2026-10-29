package io.github.vaspyyy.guildmark.trade

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Marked
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksData
import io.github.vaspyyy.guildmark.economy.works.WorksKind
import io.github.vaspyyy.guildmark.economy.works.WorksRec
import io.github.vaspyyy.guildmark.economy.works.WorksSite
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.ContainerHelper
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import java.util.function.Supplier

/**
 * One Carriers' Store as its owner's goods are kept in it (see [Stores]): the Store [owner] keeps in the capital whose heart is [capital],
 * known as [name] in their [colour], its door at [origin] facing [facing], opened on the town's day [opened]. [goods] are the units of each
 * good it holds (by [Good.ordinal]); [parcels] say which of them are hauled goods and since when (never more of a good than it holds: the
 * rest are plain goods, kept as safely but with no name to keep). The clerk's wage is paid for the days before [paidUntil]; [told] is
 * whether the owner has been told it fell due since it last did.
 */
class StoreRec(val capital: Long, val owner: UUID) {
    var name = ""
    var colour = 0
    var origin: BlockPos = BlockPos.ZERO
    var facing: Direction = Direction.NORTH
    var opened = TradeBooks.NEVER
    var paidUntil = TradeBooks.NEVER
    var told = false
    val goods = IntArray(Good.entries.size)
    val parcels = ArrayList<Parcel>()

    /** Every unit it holds, and how many kinds of goods. */
    fun units(): Int = goods.sum()
    fun kinds(): Int = goods.count { it > 0 }

    /** The units of [good] it holds, and how many of those are hauled goods. */
    fun of(good: Good): Int = goods[good.ordinal]
    fun hauled(good: Good): Int = parcels.filter { it.good == good }.sumOf { it.units }
    fun hauled(): Int = parcels.sumOf { it.units }
}

/** What a Store taken down held, kept for its owner until they come to a market hall's merchant for it: its goods and their parcels, and the town it stood in. */
class StoreReturn {
    val goods = IntArray(Good.entries.size)
    val parcels = ArrayList<Parcel>()
    var place = ""
    var told = false
}

/**
 * The Carriers' Stores' records (living-world/trader/design.md, Part 5; build part F2): each Store's goods, its parcels and its clerk's
 * wage, and what Stores taken down left to be handed back. Kept with the overworld as `guildmark:trade_stores`, made the first time a
 * Store opens; every field is optional in what is saved, so a world saved before any of it loads with nothing in it.
 */
class TradeStores(val stores: ArrayList<StoreRec> = ArrayList(), val returns: HashMap<UUID, StoreReturn> = HashMap()) : SavedData() {
    /** [owner]'s Store in [capital], if its record has been made. */
    fun find(capital: Long, owner: UUID): StoreRec? = stores.firstOrNull { it.capital == capital && it.owner == owner }

    /** Every Store [owner] keeps. */
    fun of(owner: UUID): List<StoreRec> = stores.filter { it.owner == owner }

    fun changed() = setDirty()

    companion object {
        private class RawParcel(val good: String, val units: Int, val origin: Long, val boughtAt: Long)

        private val PARCEL: Codec<RawParcel> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("good").forGetter(RawParcel::good),
                Codec.INT.fieldOf("units").forGetter(RawParcel::units),
                Codec.LONG.fieldOf("origin").forGetter(RawParcel::origin),
                Codec.LONG.fieldOf("at").forGetter(RawParcel::boughtAt),
            ).apply(i) { good, units, origin, at -> RawParcel(good, units, origin, at) }
        }

        private fun raw(list: List<Parcel>): List<RawParcel> = list.map { RawParcel(it.good.id, it.units, it.origin.asLong(), it.boughtAt) }

        // A good this version no longer knows is let go rather than kept as something nobody can read
        private fun parcels(list: List<RawParcel>): List<Parcel> = list.mapNotNull { p -> Good.byId(p.good)?.let { Parcel(it, p.units, BlockPos.of(p.origin), p.boughtAt) } }

        private fun goodsMap(goods: IntArray): Map<String, Int> = Good.entries.filter { goods[it.ordinal] > 0 }.associate { it.id to goods[it.ordinal] }

        private fun goodsInto(map: Map<String, Int>, goods: IntArray) {
            for ((id, n) in map) Good.byId(id)?.let { goods[it.ordinal] = maxOf(0, n) }
        }

        private val STORE: Codec<StoreRec> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("capital").forGetter(StoreRec::capital),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(StoreRec::owner),
                Codec.STRING.optionalFieldOf("name", "").forGetter(StoreRec::name),
                Codec.INT.optionalFieldOf("colour", 0).forGetter(StoreRec::colour),
                Codec.LONG.optionalFieldOf("origin", 0L).forGetter { it.origin.asLong() },
                Codec.INT.optionalFieldOf("facing", Direction.NORTH.get2DDataValue()).forGetter { it.facing.get2DDataValue() },
                Codec.LONG.optionalFieldOf("opened", TradeBooks.NEVER).forGetter(StoreRec::opened),
                Codec.LONG.optionalFieldOf("paidUntil", TradeBooks.NEVER).forGetter(StoreRec::paidUntil),
                Codec.BOOL.optionalFieldOf("told", false).forGetter(StoreRec::told),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("goods", mapOf()).forGetter { goodsMap(it.goods) },
                PARCEL.listOf().optionalFieldOf("parcels", listOf()).forGetter { raw(it.parcels) },
            ).apply(i) { capital, owner, name, colour, origin, facing, opened, paidUntil, told, goods, parcels ->
                val s = StoreRec(capital, owner)
                s.name = name
                s.colour = colour
                s.origin = BlockPos.of(origin)
                s.facing = Direction.from2DDataValue(facing)
                s.opened = opened
                s.paidUntil = paidUntil
                s.told = told
                goodsInto(goods, s.goods)
                s.parcels.addAll(parcels(parcels))
                s
            }
        }

        private class SavedReturn(val player: UUID, val r: StoreReturn)

        private val RETURN: Codec<SavedReturn> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(SavedReturn::player),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("goods", mapOf()).forGetter { goodsMap(it.r.goods) },
                PARCEL.listOf().optionalFieldOf("parcels", listOf()).forGetter { raw(it.r.parcels) },
                Codec.STRING.optionalFieldOf("place", "").forGetter { it.r.place },
                Codec.BOOL.optionalFieldOf("told", false).forGetter { it.r.told },
            ).apply(i) { player, goods, parcels, place, told ->
                val r = StoreReturn()
                goodsInto(goods, r.goods)
                r.parcels.addAll(parcels(parcels))
                r.place = place
                r.told = told
                SavedReturn(player, r)
            }
        }

        private val CODEC: Codec<TradeStores> = RecordCodecBuilder.create { i ->
            i.group(
                STORE.listOf().optionalFieldOf("stores", listOf()).forGetter { it.stores },
                RETURN.listOf().optionalFieldOf("returns", listOf()).forGetter { s -> s.returns.map { (p, r) -> SavedReturn(p, r) } },
            ).apply(i) { stores, returns -> TradeStores(ArrayList(stores), HashMap(returns.associate { it.player to it.r })) }
        }

        /** The saved data's type: [get] makes the record (and the game saves what it made), so whatever only looks reads it with [peek]. */
        internal val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "trade_stores"), Supplier { TradeStores() }, CODEC)

        fun get(level: ServerLevel): TradeStores = level.server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** The records if a Store has ever opened, else null, making nothing. */
        fun peek(level: ServerLevel): TradeStores? = level.server.overworld().dataStorage.get(TYPE)
    }
}

/**
 * The Carriers' Store (living-world/trader/design.md, Part 5; build part F2): a trader's own works in a capital, raised from their Marks by
 * the town's builders as a stall of one's own is. What it does is here, its records in [TradeStores]:
 *
 * - **Bonding** ([bond], [withdraw]). Goods from the owner's pack go into the Store, and with them as many of their hauled parcels as the
 *   ledger has of that good, oldest first ([Ledger.take]): in the Store a parcel keeps its name for [Ledger.HELD_DAYS] (30) days from the day
 *   it was bought, where the markets' own records forget it after ten. Taken back, the hauled goods come first, and their parcels go into the
 *   owner's books ([Ledger.add]) with the days they have left. A parcel whose days run out in the Store is a plain good there; nothing else
 *   is lost, ever.
 * - **The clerk's wage** ([pay]): [WAGE] Marks a week, the first week with the building, paid at the desk up to [AHEAD] days ahead. Owing,
 *   the Store takes no new goods (everything in it can still be taken back) and nothing else is lost; once a market hour the owners of
 *   Stores fallen due are told.
 * - **Half rent** ([halves]): while a Store of theirs stands, a trader's rented tables cost half ([io.github.vaspyyy.guildmark.economy.StallDesk.keep]).
 * - **A Store taken down** ([closing]) keeps its goods for its owner, and any market hall's merchant hands them back ([StoreDesk]).
 *
 * With `tradeStores` off ([TradeConfig.stores]) nothing is bonded, no wage falls due, the rent is whole and the hours pass by; what is in a
 * Store can still be taken back, and the records stay for when it is on again. Reading never writes: whatever only looks reads the records
 * and the books with [TradeStores.peek] and [TradeBooks.peek].
 */
object Stores {
    /** The clerk's wage, in Marks a [WEEK] of days, and the most days it can be paid ahead. */
    const val WAGE = 4
    const val WEEK = 7L
    const val AHEAD = 28L

    /** The most units of a good a Store holds, and the most kinds of goods. */
    const val MOST = 1024
    const val KINDS = 12

    /** Ticks between the wage runs (a market hour). */
    private const val HOURLY = 1000

    /** Registers the Store's and the Counting House's part (build part F2): their fittings' blocks, their daily life, the desk and the wage run. */
    fun register(modBus: IEventBus) {
        TradeBlocks.register(modBus)
        StoreLife.register()
        StoreDesk.register(modBus)
        StoreCommand.init()
        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % HOURLY == 0) hourly(it.server) }
        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event -> (event.entity as? ServerPlayer)?.let(::loggedIn) }
    }

    // ---- From the works ----

    /** [owner]'s Store in the capital whose heart is [capital] has opened (its works finished): known as [name] in their [colour], its door at [origin] facing [facing]. */
    fun opening(level: ServerLevel, capital: Long, owner: UUID, name: String, colour: Int, origin: BlockPos, facing: Direction) {
        val books = TradeBooks.get(level)
        books.of(owner).addStore(BlockPos.of(capital))
        books.changed()
        val data = TradeStores.get(level)
        val rec = data.find(capital, owner) ?: StoreRec(capital, owner).also { data.stores.add(it) }
        rec.name = name
        rec.colour = colour
        rec.origin = origin
        rec.facing = facing
        val today = Suppliers.today(level)
        if (rec.opened == TradeBooks.NEVER) rec.opened = today
        // The first week's wage comes with the building
        if (rec.paidUntil == TradeBooks.NEVER) rec.paidUntil = today + WEEK
        data.changed()
        level.server.playerList.getPlayer(owner)?.let { Rungs.check(level, it) }
    }

    /**
     * [owner]'s Store in [capital] is gone (its works taken down): struck out of their book, and its goods kept for them with their parcels,
     * for any market hall's merchant to hand back.
     */
    fun closing(level: ServerLevel, capital: Long, owner: UUID) {
        TradeBooks.peek(level)?.let { books ->
            val book = books[owner]
            if (book != null && book.hasStore(BlockPos.of(capital))) {
                book.removeStore(BlockPos.of(capital))
                books.changed()
            }
        }
        val data = TradeStores.peek(level) ?: return
        val rec = data.find(capital, owner) ?: return
        data.stores.remove(rec)
        if (rec.units() > 0) {
            val back = data.returns.getOrPut(owner) { StoreReturn() }
            for (g in Good.entries) back.goods[g.ordinal] += rec.goods[g.ordinal]
            back.parcels.addAll(rec.parcels)
            back.place = placeOf(level, capital)
            back.told = false
            level.server.playerList.getPlayer(owner)?.let { tellReturn(it, back) }
        }
        data.changed()
    }

    /** The line the Works screen gives a standing Store, from what it really holds. */
    fun doing(level: ServerLevel, capital: Long, owner: UUID?): Component {
        owner ?: return Component.empty()
        val rec = TradeStores.peek(level)?.find(capital, owner)
        if (rec == null || rec.units() == 0) return Component.translatable("gui.guildmark.works.doing.store.empty")
        return Component.translatable("gui.guildmark.works.doing.store", rec.units(), rec.kinds(), rec.hauled())
    }

    /** What `works` says of a standing Carriers' Store: whether the clerk it sent for has come, and what it holds. */
    internal fun describe(level: ServerLevel, w: WorksRec): String {
        val rec = w.owner?.let { TradeStores.peek(level)?.find(w.capital, it) }
        val held = if (rec == null || rec.units() == 0) "; nothing bonded" else "; ${rec.units()} units bonded (${rec.hauled()} hauled)"
        return "a Carriers' Store" + (if (Comings.enabled() && w.hired < 0) "; its clerk hasn't come yet" else "") + held
    }

    // ---- For the other parts ----

    /** Where [owner]'s Store stands in [capital], if it is built (beside [Works.site] for the stall). */
    fun site(level: ServerLevel, owner: UUID, capital: Long): WorksSite? = Works.store(level, capital, owner)

    /**
     * The Store's yard, where a wagon of [player]'s called to their Store in [capital] comes to (part D3): the street before its door,
     * three blocks out from it. Null if they have no Store standing there.
     */
    fun yard(player: ServerPlayer, capital: Long): BlockPos? {
        val site = site(player.level(), player.uuid, capital) ?: return null
        return site.origin.relative(site.facing, YARD_OUT)
    }

    private const val YARD_OUT = 3

    /** Whether [player]'s rented tables cost half: the Stores switched on and a Store of theirs standing. */
    fun halves(player: ServerPlayer): Boolean {
        if (!TradeConfig.stores()) return false
        val level = player.level()
        val stores = TradeBooks.peek(level)?.get(player.uuid)?.stores ?: return false
        return stores.isNotEmpty() && WorksData.get(level).works.values.any { it.kind == WorksKind.STORE && it.owner == player.uuid && it.state == WorksState.DONE }
    }

    /** Whether [p] is a Store's clerk (or a House's scribe), so that no market shop is made for them: a click on them is the desk, or a talk. */
    fun keeps(p: Townsperson): Boolean {
        if (p.role != TownRole.MERCHANT || p.resident < 0) return false
        val level = p.level() as? ServerLevel ?: return false
        return p.resident in clerks(level)
    }

    /** The resident ids of the Stores' clerks and the Houses' scribes, by the works they keep: worked out again every little while. */
    internal fun clerks(level: ServerLevel): Map<Int, Int> {
        val now = level.gameTime
        if (now - clerksAt !in 0 until CLERKS_EVERY) {
            clerksAt = now
            clerkIds = WorksData.get(level).works.values
                .filter { (it.kind == WorksKind.STORE || it.kind == WorksKind.COUNTING_HOUSE) && it.hired >= 0 }
                .associate { it.hired to it.id }
        }
        return clerkIds
    }

    private var clerkIds: Map<Int, Int> = mapOf()
    private var clerksAt = Long.MIN_VALUE / 2
    private const val CLERKS_EVERY = 100L

    // ---- The record ----

    /** The record of [w], a standing Store: made if its works stands without one (it opened before the records were kept). */
    internal fun recOf(level: ServerLevel, w: WorksRec): StoreRec? {
        val owner = w.owner ?: return null
        if (w.kind != WorksKind.STORE || w.state != WorksState.DONE) return null
        TradeStores.peek(level)?.find(w.capital, owner)?.let { return it }
        val site = w.site
        val name = w.pledges.lastOrNull { it.player == owner }?.name ?: level.server.playerList.getPlayer(owner)?.name?.string ?: ""
        opening(level, w.capital, owner, name, io.github.vaspyyy.guildmark.economy.StallDesk.colorOf(level, owner), site?.origin ?: BlockPos.of(w.capital), site?.facing ?: Direction.NORTH)
        return TradeStores.get(level).find(w.capital, owner)
    }

    /** The days of wage paid ahead, counting today (0 or less: owing since that many days). */
    fun paidDays(level: ServerLevel, rec: StoreRec): Long = rec.paidUntil - Suppliers.today(level)

    /** Whether [rec]'s clerk is owed their wage: then it takes no new goods. */
    fun owing(level: ServerLevel, rec: StoreRec): Boolean = rec.paidUntil <= Suppliers.today(level)

    /** The days [parcel] keeps its name, counting today (0: none left, a plain good now). */
    fun daysLeft(level: ServerLevel, parcel: Parcel): Int = (parcel.boughtAt + Ledger.HELD_DAYS - Suppliers.today(level)).toInt().coerceAtLeast(0)

    /** Lets go of the parcels whose days are out (their goods stay, plain) and of any more parcels of a good than the Store holds of it. */
    internal fun tidy(level: ServerLevel, rec: StoreRec): Boolean {
        val today = Suppliers.today(level)
        val before = rec.parcels.size
        rec.parcels.removeAll { it.units <= 0 || it.boughtAt + Ledger.HELD_DAYS <= today }
        rec.parcels.sortBy { it.boughtAt }
        var changed = rec.parcels.size != before
        for (g in Good.entries) {
            var over = rec.hauled(g) - rec.of(g)
            // More named than held cannot be: the oldest names go first
            while (over > 0) {
                val i = rec.parcels.indexOfFirst { it.good == g }
                if (i < 0) break
                val p = rec.parcels[i]
                val n = minOf(over, p.units)
                if (n >= p.units) rec.parcels.removeAt(i) else rec.parcels[i] = p.copy(units = p.units - n)
                over -= n
                changed = true
            }
        }
        return changed
    }

    /** Puts [parcels] into [rec], each with the same parcel of the same good, town and day where there is one. */
    private fun keep(rec: StoreRec, parcels: List<Parcel>) {
        for (p in parcels) {
            if (p.units <= 0) continue
            val same = rec.parcels.indexOfFirst { it.good == p.good && it.origin == p.origin && it.boughtAt == p.boughtAt }
            if (same >= 0) rec.parcels[same] = rec.parcels[same].copy(units = rec.parcels[same].units + p.units) else rec.parcels.add(p)
        }
        rec.parcels.sortBy { it.boughtAt }
    }

    // ---- Bonding ----

    /** What a move into or out of a Store came to: [units] moved, [hauled] of them hauled goods; or, with nothing moved, the lang key of why not ([why]). */
    class Moved(val units: Int, val hauled: Int, val why: String?) {
        companion object {
            fun no(why: String) = Moved(0, 0, why)
        }
    }

    /** How many of [item] [inventory] holds that a Store will take: plain goods, none off a waylaid wagon (whose mark the Store could not keep). */
    fun carried(inventory: Inventory, item: Item): Int =
        ContainerHelper.clearOrCountMatchingItems(inventory, { it.`is`(item) && Marked.of(it) == null }, Int.MAX_VALUE, true)

    private fun takeOut(inventory: Inventory, item: Item, count: Int): Int =
        ContainerHelper.clearOrCountMatchingItems(inventory, { it.`is`(item) && Marked.of(it) == null }, count, false)

    /** Why [player] cannot bond into [rec] now (a lang key), or null if they can. */
    fun refusal(level: ServerLevel, rec: StoreRec): String? = when {
        !TradeConfig.stores() -> "gui.guildmark.trade.store.why.off"
        owing(level, rec) -> "gui.guildmark.trade.store.why.unpaid"
        else -> null
    }

    /** How many more of [good] [rec] would take now: room for it, and a kind's place if it holds none of it yet. */
    fun room(rec: StoreRec, good: Good): Int {
        if (!good.traded || good.item == null) return 0
        if (rec.of(good) == 0 && rec.kinds() >= KINDS) return 0
        return (MOST - rec.of(good)).coerceAtLeast(0)
    }

    /** Moves up to [units] of [good] from [player]'s pack into [rec], with as many of their hauled parcels as there are. */
    fun bond(level: ServerLevel, player: ServerPlayer, rec: StoreRec, good: Good, units: Int): Moved {
        if (rec.owner != player.uuid) return Moved.no("gui.guildmark.trade.store.why.not_yours")
        refusal(level, rec)?.let { return Moved.no(it) }
        val item = good.item?.takeIf { good.traded } ?: return Moved.no("gui.guildmark.trade.store.why.not_goods")
        val room = room(rec, good)
        if (room <= 0) return Moved.no(if (rec.of(good) > 0) "gui.guildmark.trade.store.why.full" else "gui.guildmark.trade.store.why.kinds")
        val n = minOf(units, room, carried(player.inventory, item))
        if (n <= 0) return Moved.no("gui.guildmark.trade.store.why.none")
        val took = takeOut(player.inventory, item, n)
        if (took <= 0) return Moved.no("gui.guildmark.trade.store.why.none")
        val parcels = Ledger.take(level, player.uuid, good, took)
        tidy(level, rec)
        rec.goods[good.ordinal] += took
        keep(rec, parcels)
        tidy(level, rec)
        TradeStores.get(level).changed()
        return Moved(took, parcels.sumOf { it.units }, null)
    }

    /**
     * Puts [units] of [good] into [rec] as though [player] had bonded them, without taking anything from their pack (`trade store bond`): hauled
     * goods bought far off with [days] days left in the Store, or plain goods for [days] 0.
     */
    internal fun bondWritten(level: ServerLevel, rec: StoreRec, good: Good, units: Int, origin: BlockPos, days: Int): Int {
        val n = minOf(units, room(rec, good))
        if (n <= 0) return 0
        rec.goods[good.ordinal] += n
        if (days > 0) keep(rec, listOf(Parcel(good, n, origin, Suppliers.today(level) + days - Ledger.HELD_DAYS)))
        tidy(level, rec)
        TradeStores.get(level).changed()
        return n
    }

    /** Gives [player] back up to [units] of [good] from [rec]: the hauled ones first, the oldest first, their parcels into [player]'s books. */
    fun withdraw(level: ServerLevel, player: ServerPlayer, rec: StoreRec, good: Good, units: Int): Moved {
        if (rec.owner != player.uuid) return Moved.no("gui.guildmark.trade.store.why.not_yours")
        val item = good.item ?: return Moved.no("gui.guildmark.trade.store.why.not_goods")
        tidy(level, rec)
        val n = minOf(units, rec.of(good))
        if (n <= 0) return Moved.no("gui.guildmark.trade.store.why.nothing")
        val out = ArrayList<Parcel>()
        var left = n
        val each = rec.parcels.listIterator()
        while (each.hasNext() && left > 0) {
            val p = each.next()
            if (p.good != good) continue
            val k = minOf(p.units, left)
            out.add(p.copy(units = k))
            if (k >= p.units) each.remove() else each.set(p.copy(units = p.units - k))
            left -= k
        }
        rec.goods[good.ordinal] -= n
        Trading.give(player, item, n)
        Ledger.add(level, player.uuid, out)
        TradeStores.get(level).changed()
        return Moved(n, out.sumOf { it.units }, null)
    }

    // ---- The clerk's wage ----

    /** Why [player] cannot pay a week of [rec]'s wage now (a lang key), or null. */
    fun payRefusal(level: ServerLevel, player: ServerPlayer, rec: StoreRec): String? = when {
        rec.owner != player.uuid -> "gui.guildmark.trade.store.why.not_yours"
        !TradeConfig.stores() -> "gui.guildmark.trade.store.why.off"
        paidDays(level, rec) + WEEK > AHEAD -> "gui.guildmark.trade.store.why.ahead"
        TownControl.marks(player) < WAGE -> "gui.guildmark.trade.store.why.marks"
        else -> null
    }

    /** [player] pays [rec]'s clerk a week: from today if it was owing (what went unpaid is not asked for), else from when it was paid until. */
    fun pay(level: ServerLevel, player: ServerPlayer, rec: StoreRec): String? {
        payRefusal(level, player, rec)?.let { return it }
        TownControl.pay(player, WAGE)
        rec.paidUntil = maxOf(rec.paidUntil, Suppliers.today(level)) + WEEK
        rec.told = false
        TradeStores.get(level).changed()
        return null
    }

    /** Once a market hour: the owners of Stores whose wage has fallen due are told, once each time it does. */
    private fun hourly(server: MinecraftServer) {
        if (!TradeConfig.stores()) return
        val level = server.overworld()
        val data = TradeStores.peek(level) ?: return
        for (rec in data.stores) {
            if (rec.told || !owing(level, rec)) continue
            val player = server.playerList.getPlayer(rec.owner) ?: continue
            player.sendSystemMessage(Component.translatable("message.guildmark.trade.store.unpaid", placeOf(level, rec.capital), WAGE).withStyle(ChatFormatting.GOLD))
            rec.told = true
            data.changed()
        }
    }

    private fun loggedIn(player: ServerPlayer) {
        val level = player.level()
        val data = TradeStores.peek(level) ?: return
        data.returns[player.uuid]?.let { if (!it.told) tellReturn(player, it) }
    }

    private fun tellReturn(player: ServerPlayer, back: StoreReturn) {
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.store.taken_down", back.place, back.goods.sum()).withStyle(ChatFormatting.GOLD))
        back.told = true
        TradeStores.peek(player.level())?.changed()
    }

    // ---- Words ----

    /** The name of the capital whose heart is [capital] ("" if it is not to be found). */
    fun placeOf(level: ServerLevel, capital: Long): String =
        CaravanRoutes.capital(level, capital)?.let { VillageNames.of(it.boardAnchor) } ?: Word.nameOf(level, capital)
}
