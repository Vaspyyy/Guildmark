package io.github.vaspyyy.guildmark.trade

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.economy.Good
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID
import java.util.function.Supplier

/**
 * Some units of a [good] a trader carried a long way: bought at the market of the capital whose heart is [origin], on the
 * town's day [boughtAt] (as `Standing.bought` keeps it). The hauled ledger is made of these; goods a Store holds bonded, and
 * [TradeBooks.Book.held], keep them as they are, so a parcel is hauled for as long as its day allows wherever it is kept.
 */
data class Parcel(val good: Good, val units: Int, val origin: BlockPos, val boughtAt: Long)

/**
 * What each player has done and holds as a trader (living-world/trader/design.md, Parts 1 to 5): the one record every part of the
 * trader's life keeps its player-side state in, so that no part has to add a saved file of its own for a few numbers. Kept with the
 * overworld as `guildmark:trade_books`. Every field is optional in what is saved, so a world saved before any of it, or by an
 * older shape of it, loads with the defaults, and a player nothing has been written for has no record at all.
 *
 * Whoever changes a [Book] calls [changed] after, as any saved data wants. Nothing here reads a switch: a record is a record, and
 * when a system is off its records stay for when it is on again.
 */
class TradeBooks(private val books: MutableMap<UUID, Book> = HashMap()) : SavedData() {
    /**
     * One player's record. Who writes what: [started] and [sights] and the reports are the Word's (Part 1); [held] the
     * ledger's and the Stores' (Parts 2 and 5); [rung], [warrant] and [deliveries] the ladder's (Part 5); [stores] and
     * [houses] the Store's and the House's, which the ladder reads; [guards], [waylaid] and [hunts] the Hunt's (Part 4).
     */
    class Book {
        /** Whether they have taken up the trade: the Tally given (the hall's merchant asked about hauls). Nothing of the trader's look or load is worked out before it. */
        var started = false

        /** The rung they have reached, by its id ("pedlar" up to "house"; [PEDLAR] if none is written). It only ever goes up (see [Rungs]). */
        var rung = PEDLAR

        /** Whether they hold a Carrier's Warrant (the item itself can be lost and is given again free at any hall desk). */
        var warrant = false

        /** Tickets filled in full, a parcel counting 1, a consignment 2 and a relief 3. */
        var deliveries = 0

        /** The Tally's sights (Part 1, up to eight), each a compound that part writes and reads; the book only keeps them. */
        val sights = ArrayList<CompoundTag>()

        /** The town's day the reports filed today are of, and how many have been filed that day (Part 1: a few a day). */
        var reportsDay = NEVER
        var reportsToday = 0

        /** The hired guards' contracts (Part 4), each a compound that part writes and reads. */
        val guards = ArrayList<CompoundTag>()

        /** When they were last waylaid by a Hunt, as the overworld's clock reads it ([NEVER] if not): the Tally's note, and the day a band leaves them be (Part 4). */
        var waylaid = NEVER

        /** The Hunt's counters by name ("started", "won", "lost"... Part 4 names them); what is not there is 0. */
        val hunts = HashMap<String, Int>()

        /** Goods hauled that are not in a Store (taken out of the market ledgers' reach, or put back from a Store), oldest first. */
        val held = ArrayList<Parcel>()

        /** The capitals, by the key of their heart ([BlockPos.asLong]), where they have a Carriers' Store and where they have a Counting House (Part 5). */
        val stores = ArrayList<Long>()
        val houses = ArrayList<Long>()

        fun hasStore(capital: BlockPos): Boolean = capital.asLong() in stores

        fun addStore(capital: BlockPos) {
            if (!hasStore(capital)) stores.add(capital.asLong())
        }

        fun removeStore(capital: BlockPos) {
            stores.remove(capital.asLong())
        }

        fun hasHouse(capital: BlockPos): Boolean = capital.asLong() in houses

        fun addHouse(capital: BlockPos) {
            if (!hasHouse(capital)) houses.add(capital.asLong())
        }

        fun removeHouse(capital: BlockPos) {
            houses.remove(capital.asLong())
        }

        /** Whether nothing has been written to it: such a record is not saved. */
        fun untouched(): Boolean = !started && rung == PEDLAR && !warrant && deliveries == 0 && sights.isEmpty() && reportsDay == NEVER &&
            reportsToday == 0 && guards.isEmpty() && waylaid == NEVER && hunts.isEmpty() && held.isEmpty() && stores.isEmpty() && houses.isEmpty()
    }

    /** [player]'s record, if one has been written. */
    operator fun get(player: UUID): Book? = books[player]

    /** [player]'s record, started afresh if they have none yet (call [changed] if you write to it). */
    fun of(player: UUID): Book = books.getOrPut(player) { Book() }

    /** Everyone who has a record. */
    fun players(): List<UUID> = books.keys.toList()

    fun changed() = setDirty()

    companion object {
        /** The first rung's id: what a record with none written has reached. */
        const val PEDLAR = "pedlar"

        /** A day or an hour that has not come: the stamp of a thing that has not happened. */
        const val NEVER = Long.MIN_VALUE / 4

        private class RawParcel(val good: String, val units: Int, val origin: Long, val boughtAt: Long)

        private val PARCEL: Codec<RawParcel> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("good").forGetter(RawParcel::good),
                Codec.INT.fieldOf("units").forGetter(RawParcel::units),
                Codec.LONG.fieldOf("origin").forGetter(RawParcel::origin),
                Codec.LONG.fieldOf("at").forGetter(RawParcel::boughtAt),
            ).apply(i) { good, units, origin, at -> RawParcel(good, units, origin, at) }
        }

        private class Saved(val player: UUID, val book: Book)

        private val BOOK: Codec<Saved> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Saved::player),
                Codec.BOOL.optionalFieldOf("started", false).forGetter { it.book.started },
                Codec.STRING.optionalFieldOf("rung", PEDLAR).forGetter { it.book.rung },
                Codec.BOOL.optionalFieldOf("warrant", false).forGetter { it.book.warrant },
                Codec.INT.optionalFieldOf("deliveries", 0).forGetter { it.book.deliveries },
                CompoundTag.CODEC.listOf().optionalFieldOf("sights", listOf()).forGetter { it.book.sights },
                Codec.LONG.optionalFieldOf("reportsDay", NEVER).forGetter { it.book.reportsDay },
                Codec.INT.optionalFieldOf("reportsToday", 0).forGetter { it.book.reportsToday },
                CompoundTag.CODEC.listOf().optionalFieldOf("guards", listOf()).forGetter { it.book.guards },
                Codec.LONG.optionalFieldOf("waylaid", NEVER).forGetter { it.book.waylaid },
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("hunts", mapOf()).forGetter { it.book.hunts },
                PARCEL.listOf().optionalFieldOf("held", listOf()).forGetter { s ->
                    s.book.held.map { RawParcel(it.good.id, it.units, it.origin.asLong(), it.boughtAt) }
                },
                Codec.LONG.listOf().optionalFieldOf("stores", listOf()).forGetter { it.book.stores },
                Codec.LONG.listOf().optionalFieldOf("houses", listOf()).forGetter { it.book.houses },
            ).apply(i) { player, started, rung, warrant, deliveries, sights, reportsDay, reportsToday, guards, waylaid, hunts, held, stores, houses ->
                val book = Book()
                book.started = started
                book.rung = rung
                book.warrant = warrant
                book.deliveries = deliveries
                book.sights.addAll(sights)
                book.reportsDay = reportsDay
                book.reportsToday = reportsToday
                book.guards.addAll(guards)
                book.waylaid = waylaid
                book.hunts.putAll(hunts)
                // A good this version no longer knows is let go rather than kept as something nobody can read
                for (parcel in held) Good.byId(parcel.good)?.let { book.held.add(Parcel(it, parcel.units, BlockPos.of(parcel.origin), parcel.boughtAt)) }
                book.stores.addAll(stores)
                book.houses.addAll(houses)
                Saved(player, book)
            }
        }

        private val CODEC: Codec<TradeBooks> = RecordCodecBuilder.create { i ->
            i.group(
                BOOK.listOf().optionalFieldOf("players", listOf()).forGetter { s ->
                    s.books.filterValues { !it.untouched() }.map { (player, book) -> Saved(player, book) }
                },
            ).apply(i) { list -> TradeBooks(list.associate { it.player to it.book }.toMutableMap()) }
        }

        /** The saved data's type: [get] makes the record if there is none (and the game saves what it made, however empty), so a part that only looks reads it with `dataStorage.get(TYPE)` instead. */
        internal val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "trade_books"), Supplier { TradeBooks() }, CODEC)

        fun get(level: ServerLevel): TradeBooks = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
